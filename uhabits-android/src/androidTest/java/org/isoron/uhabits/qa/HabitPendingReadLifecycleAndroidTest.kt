package org.isoron.uhabits.qa

import android.content.Intent
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.Toolbar
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.isoron.platform.time.getToday
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.common.dialogs.NumberDialog
import org.isoron.uhabits.activities.habits.list.views.NumberButtonView
import org.isoron.uhabits.activities.habits.list.ListHabitsFragment
import org.isoron.uhabits.activities.habits.show.ShowHabitActivity
import org.isoron.uhabits.activities.habits.show.ShowHabitView
import org.isoron.uhabits.activities.main.MainActivity
import org.isoron.uhabits.activities.main.MainDestination
import org.isoron.uhabits.core.containers.session.DatasetCapabilities
import org.isoron.uhabits.core.containers.session.DatasetMode
import org.isoron.uhabits.database.AndroidSqlTiming
import org.isoron.uhabits.inject.HabitsApplicationComponent
import org.isoron.uhabits.intents.IntentFactory
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Real Activity + SQLite interleavings, restricted by the existing named offline AVD fixture. */
@RunWith(AndroidJUnit4::class)
class HabitPendingReadLifecycleAndroidTest {
    private val ins = InstrumentationRegistry.getInstrumentation()

    private fun withFixture(body: (HabitsApplicationComponent) -> Unit) {
        val experiment = UiReviewCaptureTest.initializeFixture()
        ProductionUiHotfixFixture.prepare(experiment)
        val app = ins.targetContext.applicationContext as HabitsApplication
        val session = experiment.session.copy(mode = DatasetMode.PRODUCTION_NEXT,
            capabilities = DatasetCapabilities.PRODUCTION_NEXT.copy(syncEnabled = false,
                remoteCallsAllowed = false, backgroundJobsEnabled = false,
                widgetsEnabled = false, remindersEnabled = false))
        ins.runOnMainSync { app.rebuildComponent(session) }
        try {
            assertEquals(DatasetMode.PRODUCTION_NEXT, HabitsApplication.component.session.mode)
            assertFalse(HabitsApplication.component.session.capabilities.remoteCallsAllowed)
            body(HabitsApplication.component)
        } finally {
            AndroidSqlTiming.observer = null
            ins.runOnMainSync { app.rebuildComponent(experiment.session) }
        }
    }

    private fun field(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name)
        .also { it.isAccessible = true }.get(target)

    private fun awaitCondition(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15000
        while (SystemClock.elapsedRealtime() < deadline) {
            ins.waitForIdleSync()
            if (condition()) return
            Thread.sleep(25)
        }
        fail(message)
    }

    @Test fun cachedSnapshotIsRevalidatedWhenCommitOccursDuringSelection() = withFixture { component ->
        val habit = component.habitList.toList().first { it.name == "Медитация" }
        val reads = component.effectiveHabitReads!!
        val today = getToday()
        val before = reads.snapshot(habit)
        var accesses = 0
        // The second traversal selects cached results, after the context and
        // missing-habit check. Commit exactly there; no eventual refresh can
        // repair the immutable snapshot returned by this invocation.
        val interleaved = object : AbstractList<org.isoron.uhabits.core.models.Habit>() {
            override val size = 1
            override fun get(index: Int): org.isoron.uhabits.core.models.Habit {
                check(index == 0)
                if (++accesses == 2) {
                    habit.originalEntries.add(org.isoron.uhabits.core.models.Entry(today, 1000))
                }
                return habit
            }
        }
        val selected = reads.snapshots(interleaved).values.single()
        assertNotSame(before, selected)
        assertEquals(component.effectiveHabitReads!!.revision.value, selected.revision)
        assertEquals(1062L, selected.day(today).totalValue)
    }

    @Test fun queuedBooleanAndNumericalWritesPersistInSubmissionOrder() = withFixture { component ->
        val today = getToday()
        val boolean = component.habitList.toList().first { !it.isNumerical }
        val numerical = component.habitList.toList().first { it.name == "Медитация" }
        val persisted = java.util.Collections.synchronizedList(mutableListOf<Pair<Long, Int>>())
        val expected = (0 until 20).map { index ->
            val habit = if (index % 2 == 0) boolean else numerical
            val value = if (habit.isNumerical) 1000 + index else
                if (index % 4 == 0) org.isoron.uhabits.core.models.Entry.YES_MANUAL else org.isoron.uhabits.core.models.Entry.NO
            habit to value
        }
        ins.runOnMainSync {
            expected.forEach { (habit, value) ->
                component.commandRunner.run(object : org.isoron.uhabits.core.commands.Command {
                    override fun run() {
                        org.isoron.uhabits.core.commands.CreateRepetitionCommand(component.habitList,
                            habit, today, value, "ordered-qa-$value").run()
                        val statement = component.db.prepareStatement("SELECT value FROM Repetitions WHERE habit = ? AND timestamp = ? AND deleted_at IS NULL")
                        try {
                            statement.bindLong(1, habit.id!!)
                            statement.bindLong(2, today.unixTime)
                            assertEquals(org.isoron.platform.io.StepResult.ROW, statement.step())
                            persisted.add(habit.id!! to statement.getInt(0))
                        } finally { statement.finalize() }
                    }
                })
            }
        }
        kotlinx.coroutines.runBlocking { component.taskRunner.await() }
        // Inspect each committed value, not merely the eventually refreshed last row.
        assertEquals(expected.map { (habit, value) -> habit.id!! to value }, persisted.toList())
        assertEquals(1019, numerical.originalEntries.get(today).value)
        assertEquals(1081L, component.effectiveHabitReads!!.snapshot(numerical).day(today).totalValue)
    }

    @Test fun recreateDuringSqlReadDoesNotPublishIntoDestroyedDetail() = withFixture { component ->
        val habit = component.habitList.toList().first { it.name == "Медитация" }
        val manualBefore = habit.originalEntries.get(getToday())
        val pending = CountDownLatch(1)
        val release = CountDownLatch(1)
        val intercepted = AtomicBoolean()
        AndroidSqlTiming.observer = { verb, _ ->
            if (verb == "SELECT" && Thread.currentThread().stackTrace.any {
                    it.className.contains("EffectiveHabitReadController")
                } && Thread.currentThread().stackTrace.any {
                    it.className.contains("ShowHabitActivity")
                } && intercepted.compareAndSet(false, true)) {
                assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
                pending.countDown()
                check(release.await(20, TimeUnit.SECONDS)) { "Test did not release pending detail SQL" }
            }
        }
        try {
            ActivityScenario.launch<ShowHabitActivity>(IntentFactory().startShowHabitActivity(ins.targetContext, habit)).use { scenario ->
                assertTrue("Detail must reach a pending real SQL read", pending.await(15, TimeUnit.SECONDS))
                lateinit var old: ShowHabitActivity
                lateinit var oldView: ShowHabitView
                scenario.onActivity { activity ->
                    old = activity
                    oldView = field(activity, "view") as ShowHabitView
                    assertTrue(oldView.findViewById<Toolbar>(R.id.toolbar).title.isNullOrEmpty())
                }
                scenario.recreate()
                scenario.onActivity { activity ->
                    assertNotSame(old, activity)
                    assertTrue(old.isDestroyed)
                    assertFalse((field(old, "scope") as CoroutineScope).coroutineContext[Job]!!.isActive)
                }
                release.countDown()
                awaitCondition("Recreated detail must render the persisted habit") {
                    var rendered = false
                    scenario.onActivity { rendered = (field(it, "view") as ShowHabitView)
                        .findViewById<Toolbar>(R.id.toolbar).title?.toString() == habit.name }
                    rendered
                }
                scenario.onActivity {
                    assertTrue("Cancelled old read must never publish",
                        oldView.findViewById<Toolbar>(R.id.toolbar).title.isNullOrEmpty())
                }
                assertEquals("Read/recreation must preserve manual persistence", manualBefore,
                    habit.originalEntries.get(getToday()))
                assertEquals("Contribution remains persisted", 1L, component.db.let { db ->
                    val stmt = db.prepareStatement("SELECT COUNT(*) FROM Contributions WHERE source_id = 'qa-hotfix-meditation-short'")
                    try { stmt.step(); stmt.getLong(0) } finally { stmt.finalize() }
                })
            }
        } finally { release.countDown(); AndroidSqlTiming.observer = null }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }

    @Test fun popupRetryUsesManualValueCommittedDuringItsRead() = withFixture { component ->
        val habit = component.habitList.toList().first { it.name == "Медитация" }
        val today = getToday()
        habit.originalEntries.add(org.isoron.uhabits.core.models.Entry(today, 1000, "old"))
        ActivityScenario.launch<ShowHabitActivity>(IntentFactory().startShowHabitActivity(ins.targetContext, habit)).use { scenario ->
            awaitCondition("Detail must render before popup interleaving") {
                var rendered = false
                scenario.onActivity { rendered = it.findViewById<Toolbar>(R.id.toolbar).title?.toString() == habit.name }
                rendered
            }
            val pending = CountDownLatch(1)
            val release = CountDownLatch(1)
            val intercepted = AtomicBoolean()
            AndroidSqlTiming.observer = { verb, _ ->
                if (verb == "SELECT" && Thread.currentThread().stackTrace.any {
                        it.className.contains("showNumberPopup")
                    } && intercepted.compareAndSet(false, true)) {
                    pending.countDown()
                    check(release.await(20, TimeUnit.SECONDS))
                }
            }
            try {
                scenario.onActivity {
                    component.effectiveHabitReads!!.revision.changed()
                    (field(it, "screen") as ShowHabitActivity.Screen).showNumberPopup(
                        1.0, "old", habit, today) { _, _ -> }
                }
                assertTrue(pending.await(15, TimeUnit.SECONDS))
                habit.originalEntries.add(org.isoron.uhabits.core.models.Entry(today, 2000, "new"))
                release.countDown()
                awaitCondition("Popup must retry and render after concurrent commit") {
                    var shown = false
                    scenario.onActivity { shown = it.supportFragmentManager.findFragmentByTag("numberDialog") is NumberDialog }
                    shown
                }
                scenario.onActivity {
                    val args = it.supportFragmentManager.findFragmentByTag("numberDialog")!!.requireArguments()
                    assertEquals(2.0, args.getDouble("value"), 0.0)
                    assertEquals("new", args.getString("notes"))
                }
            } finally { release.countDown(); AndroidSqlTiming.observer = null }
        }
    }

    @Test fun listPopupSqlRunsOffMainAndCannotOpenAfterNavigation() = withFixture { component ->
        ActivityScenario.launch<MainActivity>(Intent(ins.targetContext, MainActivity::class.java)).use { scenario ->
            scenario.onActivity { it.navigate(MainDestination.HABITS) }
            awaitCondition("Numerical card must be rendered") {
                var ready = false
                scenario.onActivity { ready = descendants(it.window.decorView).filterIsInstance<NumberButtonView>().any { v -> v.isShown } }
                ready
            }
            val pending = CountDownLatch(1)
            val release = CountDownLatch(1)
            val intercepted = AtomicBoolean()
            val ranOnMain = AtomicBoolean()
            lateinit var pendingPopup: Job
            AndroidSqlTiming.observer = { verb, _ ->
                if (verb == "SELECT" && Thread.currentThread().stackTrace.any {
                        it.className.contains("ListHabitsScreen")
                    } && intercepted.compareAndSet(false, true)) {
                    ranOnMain.set(Looper.myLooper() == Looper.getMainLooper())
                    pending.countDown()
                    if (!ranOnMain.get()) check(release.await(20, TimeUnit.SECONDS))
                }
            }
            try {
                scenario.onActivity {
                    component.effectiveHabitReads!!.revision.changed() // Cached card revision is deliberately obsolete.
                    assertTrue(descendants(it.window.decorView).filterIsInstance<NumberButtonView>().first { v -> v.isShown }.performClick())
                }
                assertTrue("Popup must perform a real snapshot query", pending.await(15, TimeUnit.SECONDS))
                assertFalse("Popup SQL must leave Main free", ranOnMain.get())
                scenario.onActivity {
                    val screen = it.supportFragmentManager.fragments.filterIsInstance<ListHabitsFragment>().first().screen
                    pendingPopup = field(screen, "popupJob") as Job
                    it.navigate(MainDestination.TASKS)
                    assertTrue("Navigation cancels the pending popup", pendingPopup.isCancelled)
                }
                release.countDown()
                AndroidSqlTiming.observer = null
                awaitCondition("Old popup read must finish before checking for a stray dialog") { pendingPopup.isCompleted }
                scenario.onActivity { assertNull(it.supportFragmentManager.findFragmentByTag("numberDialog")) }
                scenario.onActivity { it.navigate(MainDestination.HABITS) }
                awaitCondition("Numerical card must return after navigation") {
                    var ready = false
                    scenario.onActivity { ready = descendants(it.window.decorView).filterIsInstance<NumberButtonView>().any { v -> v.isShown } }
                    ready
                }
                scenario.onActivity { assertTrue(descendants(it.window.decorView).filterIsInstance<NumberButtonView>().first { v -> v.isShown }.performClick()) }
                awaitCondition("Current popup must open normally") {
                    var shown = false
                    scenario.onActivity { shown = it.supportFragmentManager.findFragmentByTag("numberDialog") is NumberDialog }
                    shown
                }
            } finally { release.countDown(); AndroidSqlTiming.observer = null }
        }
    }
}
