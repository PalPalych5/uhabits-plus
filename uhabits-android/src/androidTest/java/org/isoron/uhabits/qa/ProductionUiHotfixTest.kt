package org.isoron.uhabits.qa

import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.runBlocking
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.activities.main.MainActivity
import org.isoron.uhabits.activities.main.MainDestination
import org.isoron.uhabits.activities.habits.list.views.HabitCardView
import org.isoron.uhabits.activities.habits.list.views.HabitCardListView
import org.isoron.uhabits.core.containers.session.DatasetMode
import org.isoron.uhabits.core.containers.session.DatasetCapabilities
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*
import org.isoron.platform.io.run
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Bounded regression capture on the existing guarded offline fixture; no full archive traversal. */
@RunWith(AndroidJUnit4::class)
class ProductionUiHotfixTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(ins)
    private fun descendants(v: View): Sequence<View> = sequence {
        yield(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) yieldAll(descendants(v.getChildAt(i)))
    }
    @Test fun snapshotReuseIsQualifiedByRevisionAndRollback() {
        val fixture = UiReviewCaptureTest.initializeFixture()
        ProductionUiHotfixFixture.prepare(fixture)
        val reads = requireNotNull(fixture.effectiveHabitReads)
        val habit = fixture.habitList.toList().first { it.name == "Медитация" }
        val today = org.isoron.platform.time.getToday()
        val original = reads.snapshot(habit)
        assertSame(original, reads.snapshot(habit))
        habit.originalEntries.add(org.isoron.uhabits.core.models.Entry(today, 1000))
        val edited = reads.snapshot(habit)
        assertNotSame(original, edited)
        assertEquals(1062L, edited.day(today).totalValue)
        repeat(3) { runBlocking { fixture.taskRunner.await() }; ins.waitForIdleSync() }
        // Keep the writer and its read on one thread after draining existing readers.
        // A background reader waiting for this SQLite transaction cannot be joined by its writer.
        ins.runOnMainSync {
            fixture.db.run("BEGIN TRANSACTION")
            try {
                habit.originalEntries.add(org.isoron.uhabits.core.models.Entry(today, 2000))
                val speculative = reads.snapshots(listOf(habit), today.minus(364), today).values.single()
                assertEquals(2062L, speculative.day(today).totalValue)
            } finally { fixture.db.run("ROLLBACK") }
        }
        ins.waitForIdleSync()
        val restored = reads.snapshot(habit)
        assertEquals(1062L, restored.day(today).totalValue)
        assertNotSame(edited, restored)
        val previousLocale = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(if (org.isoron.platform.time.getFirstWeekdayNumberAccordingToLocale() == 1)
                java.util.Locale.forLanguageTag("ru-RU") else java.util.Locale.US)
            assertNotSame(restored, reads.snapshot(habit))
        } finally { java.util.Locale.setDefault(previousLocale) }
        habit.originalEntries.add(org.isoron.uhabits.core.models.Entry(today, org.isoron.uhabits.core.models.Entry.UNKNOWN))
        assertEquals(62L, reads.snapshot(habit).day(today).totalValue)
    }
    @Test fun directlyTargetedTimersCreditOnceAndSurviveComponentRestart() {
        val fixture = UiReviewCaptureTest.initializeFixture()
        ProductionUiHotfixFixture.prepare(fixture)
        val app = ins.targetContext.applicationContext as HabitsApplication
        val qaSession = fixture.session.copy(mode = DatasetMode.PRODUCTION_NEXT,
            capabilities = DatasetCapabilities.PRODUCTION_NEXT.copy(syncEnabled = false, remoteCallsAllowed = false,
                backgroundJobsEnabled = false, widgetsEnabled = false, remindersEnabled = false))
        ins.runOnMainSync { app.rebuildComponent(qaSession) }
        var component = HabitsApplication.component
        fun drain() { repeat(3) { runBlocking { component.taskRunner.await() }; ins.waitForIdleSync() } }
        try {
            val habit = component.habitList.toList().first { it.name == "Медитация" }
            val manual = habit.originalEntries.getKnown().toList()
            val facts = org.isoron.uhabits.core.focus.sqlite.SQLiteFocusSessionRepository(component.db)
            val ledger = org.isoron.uhabits.core.contribution.sqlite.SQLiteContributionRepository(component.db)
            val count = facts.getAll().size
            val credits = ledger.getAll().size
            val target = org.isoron.uhabits.core.containers.HabitRef(habit.uuid!!)
            val before = component.effectiveHabitReads!!.snapshot(habit).day(org.isoron.platform.time.getToday()).totalValue!!
            for ((index, mode) in org.isoron.uhabits.core.timer.TimerMode.entries.withIndex()) {
                ins.runOnMainSync {
                    component.timerSessionManager.prepare(habit)
                    component.timerSessionManager.switchMode(habit, mode)
                    assertTrue(component.timerSessionManager.startOrPause(habit))
                }
                Thread.sleep(1200)
                ins.runOnMainSync { component.timerSessionManager.finish(habit); component.timerSessionManager.finish(habit) }
                drain()
                assertEquals(count + index + 1, facts.getAll().size)
                assertEquals(credits + index + 1, ledger.getAll().size)
                assertEquals(manual, habit.originalEntries.getKnown().toList())
            }
            val expected = ledger.getByHabit(target).filter { it.isActive }.sumOf { it.contributedValue }
            assertTrue(expected > before)
            assertEquals(expected, component.effectiveHabitReads!!.snapshot(habit).day(org.isoron.platform.time.getToday()).totalValue)
            ins.runOnMainSync { app.rebuildComponent(qaSession) }
            component = HabitsApplication.component
            drain()
            val reopenedHabit = component.habitList.getByUUID(target.uuid)!!
            ins.runOnMainSync { component.timerSessionManager.finish(reopenedHabit) }
            drain()
            assertEquals(count + 2, org.isoron.uhabits.core.focus.sqlite.SQLiteFocusSessionRepository(component.db).getAll().size)
            assertEquals(credits + 2, org.isoron.uhabits.core.contribution.sqlite.SQLiteContributionRepository(component.db).getAll().size)
            assertEquals(manual, reopenedHabit.originalEntries.getKnown().toList())
            File(ins.targetContext.getExternalFilesDir(null), "hotfix/direct-restart.json").writeText(
                JSONObject().put("habitUuid", target.uuid).put("facts", count + 2).put("credits", credits + 2)
                    .put("manual", manual.toString()).put("total", expected).toString(2))
        } finally { ins.runOnMainSync { app.rebuildComponent(fixture.session) } }
    }
    @Test fun startupAndFocusedScreens() {
        val fixture = UiReviewCaptureTest.initializeFixture()
        ProductionUiHotfixFixture.prepare(fixture)
        val app = ins.targetContext.applicationContext as HabitsApplication
        val variant = InstrumentationRegistry.getArguments().getString("hotfixVariant") ?: "after"
        val output = File(ins.targetContext.getExternalFilesDir(null), "hotfix/$variant").apply { mkdirs() }
        val events = Collections.synchronizedList(mutableListOf<JSONObject>())
        val started = SystemClock.elapsedRealtimeNanos()
        val qaSession = fixture.session.copy(mode = DatasetMode.PRODUCTION_NEXT,
            capabilities = DatasetCapabilities.PRODUCTION_NEXT.copy(syncEnabled = false, remoteCallsAllowed = false,
                backgroundJobsEnabled = false, widgetsEnabled = false, remindersEnabled = false))
        ins.runOnMainSync { app.rebuildComponent(qaSession) }
        val component = HabitsApplication.component
        component.preferences.isFirstRun = false
        component.preferences.theme = 1
        val sessionMs = (SystemClock.elapsedRealtimeNanos() - started) / 1e6
        component.habitCardListCache.timingObserver = { stage, ms -> events.add(JSONObject().put("stage", stage).put("ms", ms)) }
        fun drain() { repeat(3) { runBlocking { component.taskRunner.await() }; ins.waitForIdleSync() } }
        fun capture(name: String) {
            drain(); device.waitForIdle(); QaEmulatorUi.clearSystemUiError()
            check(device.takeScreenshot(File(output, "$name.png")))
            device.dumpWindowHierarchy(File(output, "$name.xml"))
        }
        fun visibleList(activity: MainActivity, stage: String): HabitCardListView {
            fun describe(view: View): JSONObject = JSONObject()
                .put("class", view.javaClass.simpleName).put("id", view.id)
                .put("visibility", view.visibility).put("shown", view.isShown)
                .put("attached", view.isAttachedToWindow).put("alpha", view.alpha)
                .put("width", view.width).put("height", view.height)
            val lists = descendants(activity.window.decorView).filterIsInstance<HabitCardListView>().toList()
            val diagnostics = JSONObject().put("stage", stage)
                .put("activityLifecycle", activity.lifecycle.currentState.name)
                .put("windowFocus", activity.window.decorView.hasWindowFocus())
                .put("navigation", runCatching {
                    val field = MainActivity::class.java.getDeclaredField("navigationState").apply { isAccessible = true }
                    (field.get(activity) as org.isoron.uhabits.activities.main.MainNavigationState).current.name
                }.getOrElse { "unavailable: ${it.javaClass.simpleName}" })
                .put("fragments", JSONArray(activity.supportFragmentManager.fragments.map { fragment ->
                    JSONObject().put("tag", fragment.tag).put("hidden", fragment.isHidden)
                        .put("visible", fragment.isVisible).put("lifecycle", fragment.lifecycle.currentState.name)
                        .put("root", fragment.view?.let { describe(it) })
                }))
                .put("lists", JSONArray(lists.map { list ->
                    val ancestors = mutableListOf<JSONObject>()
                    var parent = list.parent as? View
                    while (parent != null) { ancestors.add(describe(parent)); parent = parent.parent as? View }
                    describe(list).put("rows", list.adapter?.itemCount).put("ancestors", JSONArray(ancestors))
                }))
            events.add(diagnostics)
            File(output, "visibility-$stage.json").writeText(diagnostics.toString(2))
            return lists.firstOrNull { it.isShown }
                ?: error("No shown HabitCardListView at stage=$stage; navigation=${diagnostics.optString("navigation")}; diagnostic=visibility-$stage.json")
        }
        try {
            val launchAt = SystemClock.elapsedRealtimeNanos()
            val firstDraw = CountDownLatch(1)
            var firstListMs = 0.0
            ActivityScenario.launch<MainActivity>(MainActivity.intent(ins.targetContext, MainDestination.HABITS)).use { scenario ->
                scenario.onActivity { activity ->
                    val root = activity.window.decorView
                    lateinit var listener: ViewTreeObserver.OnDrawListener
                    listener = ViewTreeObserver.OnDrawListener {
                        if (firstDraw.count > 0 && descendants(root).filterIsInstance<HabitCardView>().any { it.isShown && it.width > 0 }) {
                            firstListMs = (SystemClock.elapsedRealtimeNanos() - launchAt) / 1e6
                            firstDraw.countDown()
                            root.post { if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnDrawListener(listener) }
                        }
                    }
                    root.viewTreeObserver.addOnDrawListener(listener)
                    activity.navigate(MainDestination.HABITS)
                    root.invalidate()
                }
                if (!firstDraw.await(30, TimeUnit.SECONDS)) {
                    device.takeScreenshot(File(output, "startup-failure.png"))
                    device.dumpWindowHierarchy(File(output, "startup-failure.xml"))
                    File(output, "startup-failure-events.json").writeText(JSONArray(events.toList()).toString(2))
                    error("No meaningful Habit list frame; cache count=${component.habitCardListCache.habitCount}")
                }
                drain()
                val completeMs = (SystemClock.elapsedRealtimeNanos() - launchAt) / 1e6
                capture("dark-list-initial")
                var warmCount = 0
                val navAt = SystemClock.elapsedRealtimeNanos()
                scenario.onActivity { activity ->
                    activity.navigate(MainDestination.TASKS)
                    activity.navigate(MainDestination.HABITS)
                    val retainedList = descendants(activity.window.decorView).filterIsInstance<HabitCardListView>().first()
                    warmCount = retainedList.adapter!!.itemCount
                    events.add(JSONObject().put("stage", "navigation.sameTurn")
                        .put("rows", warmCount).put("isShown", retainedList.isShown)
                        .put("listVisibility", retainedList.visibility)
                        .put("parentVisibility", (retainedList.parent as? View)?.visibility))
                }
                check(warmCount > 0) { "Loaded list was emptied on navigation" }
                val interactiveMs = (SystemClock.elapsedRealtimeNanos() - navAt) / 1e6
                // Fragment show/hide visibility is applied after this UI turn.
                // Preserve the immediate row check, then verify actual visible UI.
                drain()
                scenario.onActivity { activity ->
                    val list = visibleList(activity, "dark-after-warm-navigation")
                    (list.layoutManager as androidx.recyclerview.widget.LinearLayoutManager).scrollToPositionWithOffset(19, 0)
                }
                capture("dark-focus-list")
                val focusHabit = component.habitList.toList().first { it.name == "Медитация" }
                scenario.onActivity { it.startActivity(org.isoron.uhabits.intents.IntentFactory().startShowHabitActivity(ins.targetContext, focusHabit)) }
                capture("dark-focus-detail")
                device.pressBack(); drain()
                scenario.onActivity { it.navigate(MainDestination.STATISTICS) }
                capture("dark-statistics")
                component.preferences.theme = 2
                scenario.onActivity { it.recreate() }
                capture("light-statistics")
                scenario.onActivity { it.navigate(MainDestination.HABITS) }
                capture("light-list")
                scenario.onActivity { activity ->
                    val list = visibleList(activity, "light-after-statistics-recreation")
                    (list.layoutManager as androidx.recyclerview.widget.LinearLayoutManager).scrollToPositionWithOffset(19, 0)
                }
                capture("light-focus-list")
                File(output, "startup.json").writeText(JSONObject().put("variant", variant)
                    .put("sessionComponentInitMs", sessionMs).put("activityLaunchToFirstListDrawMs", firstListMs)
                    .put("activityLaunchToDrainedDataMs", completeMs).put("warmNavigationHandlerMs", interactiveMs)
                    .put("warmImmediateHabitCount", warmCount).put("events", JSONArray(events.toList()))
                    .put("scope", "Offline migrated QA fixture; activity launch includes scenario harness, not physical-device cold process start")
                    .toString(2))
            }
        } finally {
            File(output, "startup-events-final.json").writeText(JSONArray(events.toList()).toString(2))
            component.habitCardListCache.timingObserver = null
            ins.runOnMainSync { app.rebuildComponent(fixture.session) }
        }
    }
}
