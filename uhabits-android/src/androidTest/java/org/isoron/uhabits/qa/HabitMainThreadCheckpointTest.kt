package org.isoron.uhabits.qa

import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.StrictMode
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.EditText
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.isoron.platform.io.queryInt
import org.isoron.platform.time.getToday
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.common.dialogs.NumberDialog
import org.isoron.uhabits.activities.habits.list.views.*
import org.isoron.uhabits.activities.habits.show.ShowHabitActivity
import org.isoron.uhabits.activities.habits.show.views.TimerCardView
import org.isoron.uhabits.activities.main.MainActivity
import org.isoron.uhabits.activities.main.MainDestination
import org.isoron.uhabits.core.commands.Command
import org.isoron.uhabits.core.commands.CommandRunner
import org.isoron.uhabits.core.containers.session.DatasetCapabilities
import org.isoron.uhabits.core.containers.session.DatasetMode
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitList
import org.isoron.uhabits.database.AndroidSqlTiming
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Short, identical baseline/after routes. Fixture guards forbid phones and live datasets. */
@RunWith(AndroidJUnit4::class)
class HabitMainThreadCheckpointTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    @Volatile private var phase = "fixture"
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) repeat(view.childCount) { yieldAll(descendants(view.getChildAt(it))) }
    }

    @Test fun measureRemainingMainThreadPaths() {
        val fixture = UiReviewCaptureTest.initializeFixture()
        ProductionUiHotfixFixture.prepare(fixture)
        val app = ins.targetContext.applicationContext as HabitsApplication
        val qaSession = fixture.session.copy(mode = DatasetMode.PRODUCTION_NEXT,
            capabilities = DatasetCapabilities.PRODUCTION_NEXT.copy(syncEnabled = false, remoteCallsAllowed = false,
                backgroundJobsEnabled = false, widgetsEnabled = false, remindersEnabled = false))
        ins.runOnMainSync { app.rebuildComponent(qaSession) }
        val component = HabitsApplication.component
        component.preferences.isFirstRun = false
        component.preferences.isShortToggleEnabled = true
        component.preferences.isCheckmarkSequenceReversed = false
        component.preferences.isHabitSpheresEnabled = true
        component.preferences.areHabitGroupSeparatorsEnabled = true
        component.preferences.defaultPrimaryOrder = HabitList.Order.BY_SPHERE
        val events = Collections.synchronizedList(mutableListOf<JSONObject>())
        val steps = JSONArray()
        val handler = Handler(Looper.getMainLooper())
        val heartbeat = Executors.newSingleThreadScheduledExecutor()
        val strictExecutor = Executors.newSingleThreadExecutor()
        var oldPolicy: StrictMode.ThreadPolicy? = null
        var monitoring = false
        var lastFrame = 0L
        fun record(kind: String, ms: Double = 0.0, path: String = "", main: Boolean = Looper.myLooper() == Looper.getMainLooper()) {
            events.add(JSONObject().put("phase", phase).put("kind", kind).put("ms", ms)
                .put("mainThread", main).put("path", path))
        }
        fun productPath(trace: Array<StackTraceElement>): String = trace.filter {
            it.className.startsWith("org.isoron") && !it.className.contains("qa.") &&
                !it.className.contains("AndroidPreparedStatement")
        }.take(10).joinToString(" > ") { "${it.className.substringAfterLast('.')}.${it.methodName}" }
        val frameCallback = object : Choreographer.FrameCallback {
            override fun doFrame(time: Long) {
                if (!monitoring) return
                if (lastFrame != 0L) record("frame.interval", (time - lastFrame) / 1e6, main = true)
                lastFrame = time
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
        fun drain() { repeat(3) { runBlocking { component.taskRunner.await() }; ins.waitForIdleSync() } }
        fun frame() {
            val committed = CountDownLatch(1)
            ins.runOnMainSync { Choreographer.getInstance().postFrameCallback { committed.countDown() } }
            check(committed.await(15, TimeUnit.SECONDS)) { "No frame in phase $phase" }
        }
        fun <A : android.app.Activity> measured(name: String, scenario: ActivityScenario<A>, action: (A) -> Unit) {
            phase = name
            val requested = SystemClock.elapsedRealtimeNanos()
            var inputMs = 0.0
            var dispatchMs = 0.0
            var drawMs = 0.0
            val drawn = CountDownLatch(1)
            scenario.onActivity { activity ->
                dispatchMs = (SystemClock.elapsedRealtimeNanos() - requested) / 1e6
                val root = activity.window.decorView
                lateinit var drawListener: ViewTreeObserver.OnDrawListener
                drawListener = ViewTreeObserver.OnDrawListener {
                    if (drawn.count > 0) {
                        drawMs = (SystemClock.elapsedRealtimeNanos() - requested) / 1e6
                        drawn.countDown()
                        root.post { if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnDrawListener(drawListener) }
                    }
                }
                root.viewTreeObserver.addOnDrawListener(drawListener)
                val started = SystemClock.elapsedRealtimeNanos()
                action(activity)
                inputMs = (SystemClock.elapsedRealtimeNanos() - started) / 1e6
                root.invalidate()
            }
            check(drawn.await(15, TimeUnit.SECONDS)) { "No actual draw in $name" }
            steps.put(JSONObject().put("name", name).put("handlerMs", inputMs).put("dispatchDelayMs", dispatchMs)
                .put("inputToDrawMs", drawMs))
        }
        var failure: Throwable? = null
        try {
            ActivityScenario.launch<MainActivity>(Intent(ins.targetContext, MainActivity::class.java)).use { scenario ->
                scenario.onActivity { it.navigate(MainDestination.HABITS) }
                drain()
                QaEmulatorUi.clearSystemUiError()
                ins.runOnMainSync {
                    oldPolicy = StrictMode.getThreadPolicy()
                    StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites()
                        .penaltyListener(strictExecutor) { violation ->
                            record("strict.${violation.javaClass.simpleName}", path = productPath(violation.stackTrace), main = true)
                        }.build())
                    monitoring = true
                    Choreographer.getInstance().postFrameCallback(frameCallback)
                }
                AndroidSqlTiming.observer = { verb, ms ->
                    val main = Looper.myLooper() == Looper.getMainLooper()
                    record("sql.$verb", ms, if (main) productPath(Thread.currentThread().stackTrace) else "", main)
                }
                component.commandRunner.timingObserver = { kind, ms -> record(kind, ms) }
                component.habitCardListCache.timingObserver = { kind, ms -> record(kind, ms) }
                heartbeat.scheduleAtFixedRate({
                    val requested = SystemClock.elapsedRealtimeNanos()
                    val label = phase
                    handler.post {
                        events.add(JSONObject().put("phase", label).put("kind", "main.heartbeatDelay")
                            .put("ms", (SystemClock.elapsedRealtimeNanos() - requested) / 1e6).put("mainThread", true).put("path", ""))
                    }
                }, 0, 16, TimeUnit.MILLISECONDS)

                fun list(activity: MainActivity) = descendants(activity.window.decorView).filterIsInstance<HabitCardListView>().first { it.isShown }
                fun show(habit: Habit) {
                    phase = "locate-row"
                    drain()
                    val deadline = SystemClock.elapsedRealtime() + 15000
                    var found = false
                    while (!found && SystemClock.elapsedRealtime() < deadline) {
                        scenario.onActivity { activity ->
                            val view = list(activity)
                            found = descendants(activity.window.decorView).filterIsInstance<HabitCardView>()
                                .any { it.isShown && it.habit?.id == habit.id }
                            if (!found) {
                                val adapter = view.adapter as HabitCardListAdapter
                                val position = (0 until adapter.itemCount).firstOrNull { adapter.getItem(it)?.id == habit.id }
                                if (position != null) {
                                    (view.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager)
                                        ?.scrollToPositionWithOffset(position, 0)
                                        ?: view.scrollToPosition(position)
                                }
                            }
                        }
                        if (!found) {
                            drain()
                            frame()
                            Thread.sleep(50)
                        }
                    }
                    check(found) { "Could not find card for habit ${habit.name} (id=${habit.id})" }
                    drain()
                    frame()
                }
                fun card(activity: MainActivity, habit: Habit) = descendants(activity.window.decorView)
                    .filterIsInstance<HabitCardView>().first { it.isShown && it.habit?.id == habit.id }
                fun awaitCommit(action: () -> Unit) {
                    val done = CountDownLatch(1)
                    val listener = object : CommandRunner.Listener { override fun onCommandFinished(command: Command) { done.countDown() } }
                    ins.runOnMainSync { component.commandRunner.addListener(listener) }
                    try { action(); check(done.await(15, TimeUnit.SECONDS)) { "Command did not commit in $phase" } }
                    finally { ins.runOnMainSync { component.commandRunner.removeListener(listener) } }
                }
                fun persisted(habit: Habit): Int = component.db.queryInt(
                    "SELECT value FROM Repetitions WHERE habit = ${habit.id} AND timestamp = ${getToday().unixTime}")
                val booleanHabits = component.habitList.filter { !it.isNumerical && !it.isArchived }.take(3)
                check(booleanHabits.size == 3)
                booleanHabits.forEachIndexed { index, habit ->
                    show(habit)
                    repeat(2) { tap ->
                        var expected = 0
                        var observedButton: CheckmarkButtonView? = null
                        val staleFrames = mutableListOf<Int>()
                        val parityDraw = ViewTreeObserver.OnDrawListener {
                            observedButton?.let { button -> if (button.value != expected) staleFrames.add(button.value) }
                        }
                        awaitCommit {
                            measured("boolean-$index-$tap", scenario) { activity ->
                                val panel = descendants(card(activity, habit)).filterIsInstance<CheckmarkPanelView>().first()
                                val button = panel.buttons.first()
                                check(button.performClick()); expected = button.value
                                observedButton = button
                                button.viewTreeObserver.addOnDrawListener(parityDraw)
                            }
                        }
                        frame()
                        ins.runOnMainSync { observedButton?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnDrawListener(parityDraw) }
                        phase = "verify-boolean-$index-$tap"
                        val stored = persisted(habit)
                        var rendered = Int.MIN_VALUE
                        var buttonValues = emptyList<Int>()
                        var compVal = Int.MIN_VALUE
                        var origVal = Int.MIN_VALUE
                        scenario.onActivity {
                            val panel = descendants(card(it, habit)).filterIsInstance<CheckmarkPanelView>().first()
                            rendered = panel.buttons.first().value
                            buttonValues = panel.buttons.map { b -> b.value }
                            compVal = habit.computedEntries.get(getToday()).value
                            origVal = habit.originalEntries.get(getToday()).value
                        }
                        steps.put(JSONObject().put("name", phase).put("habitId", habit.id).put("habitName", habit.name)
                            .put("freq", "${habit.frequency.numerator}/${habit.frequency.denominator}")
                            .put("expected", expected).put("persisted", stored)
                            .put("rendered", rendered).put("compVal", compVal).put("origVal", origVal)
                            .put("buttonValues", JSONArray(buttonValues))
                            .put("parity", expected == stored && stored == rendered)
                            .put("staleIntermediateDraws", JSONArray(staleFrames))
                            .put("forcedFullRefresh", false))
                        check(expected == stored && (stored == rendered || compVal == rendered)) {
                            "Committed/rendered Boolean mismatch for ${habit.name}: expected=$expected stored=$stored rendered=$rendered compVal=$compVal buttons=$buttonValues"
                        }
                        check(staleFrames.isEmpty() || staleFrames.all { it == compVal }) { "An earlier stale Boolean render was hidden by a later refresh" }
                    }
                }
                val numerical = component.habitList.first { it.name == "Велосипед" }
                show(numerical)
                measured("numerical-open", scenario) { activity ->
                    val panel = descendants(card(activity, numerical)).filterIsInstance<NumberPanelView>().first()
                    check(panel.buttons.first().performClick())
                }
                val dialogDeadline = SystemClock.elapsedRealtime() + 15000
                var dialog: NumberDialog? = null
                while (dialog == null && SystemClock.elapsedRealtime() < dialogDeadline) {
                    scenario.onActivity { dialog = it.supportFragmentManager.findFragmentByTag("numberDialog") as? NumberDialog }
                    if (dialog == null) Thread.sleep(20)
                }
                checkNotNull(dialog) { "Numerical editor did not appear" }
                awaitCommit {
                    measured("numerical-save", scenario) {
                        dialog!!.requireDialog().findViewById<EditText>(R.id.value).setText("17")
                        dialog!!.save()
                    }
                }
                drain()
                frame()
                phase = "verify-numerical"
                val numericalStored = persisted(numerical)
                var numericalRendered = Double.NaN
                val numericalDeadline = SystemClock.elapsedRealtime() + 15000
                while (numericalRendered != 17.0 && SystemClock.elapsedRealtime() < numericalDeadline) {
                    scenario.onActivity {
                        val panel = descendants(card(it, numerical)).filterIsInstance<NumberPanelView>().first()
                        numericalRendered = panel.buttons.first().value
                    }
                    if (numericalRendered != 17.0) {
                        drain()
                        frame()
                        Thread.sleep(50)
                    }
                }
                steps.put(JSONObject().put("name", phase).put("persisted", numericalStored).put("rendered", numericalRendered)
                    .put("parity", numericalStored == 17000 && numericalRendered == 17.0).put("forcedFullRefresh", false))
                check(numericalStored == 17000 && numericalRendered == 17.0)

                repeat(3) { iteration ->
                    measured("tasks-navigation-$iteration", scenario) { it.navigate(MainDestination.TASKS) }
                    drain()
                    measured("tasks-scroll-$iteration", scenario) { activity ->
                        val rv = descendants(activity.window.decorView).filterIsInstance<RecyclerView>().first { it.isShown }
                        check((rv.adapter?.itemCount ?: 0) > 0)
                        rv.scrollBy(0, if (iteration % 2 == 0) 500 else -500)
                    }
                    measured("habits-navigation-$iteration", scenario) { it.navigate(MainDestination.HABITS) }
                    drain()
                    measured("grouped-habits-scroll-$iteration", scenario) { list(it).scrollBy(0, if (iteration % 2 == 0) 850 else -850) }
                }
                frame()
            }

            val focus = component.habitList.first { it.name == "Медитация" }
            repeat(2) { iteration ->
                phase = "focus-detail-open-$iteration"
                val started = SystemClock.elapsedRealtimeNanos()
                val intent = Intent(ins.targetContext, ShowHabitActivity::class.java)
                    .setData(ContentUris.withAppendedId(Uri.parse("content://org.isoron.uhabits/habits"), focus.id!!))
                ActivityScenario.launch<ShowHabitActivity>(intent).use { detail ->
                    frame(); ins.waitForIdleSync()
                    var rendered = false
                    val deadline = SystemClock.elapsedRealtime() + 15000
                    while (!rendered && SystemClock.elapsedRealtime() < deadline) {
                        detail.onActivity {
                            val views = descendants(it.window.decorView).toList()
                            rendered = views.filterIsInstance<TimerCardView>().any { view -> view.isShown && view.height > 0 } &&
                                views.filterIsInstance<TextView>().any { view -> view.isShown && view.text.toString() == focus.name }
                        }
                        if (!rendered) Thread.sleep(20)
                    }
                    check(rendered)
                    frame()
                    steps.put(JSONObject().put("name", phase).put("openToFrameMs", (SystemClock.elapsedRealtimeNanos() - started) / 1e6)
                        .put("rendered", rendered).put("focusManualPersisted", focus.originalEntries.get(getToday()).value))
                }
                drain()
            }
            frame()
        } catch (t: Throwable) { failure = t }
        finally {
            phase = "cleanup"
            heartbeat.shutdownNow()
            ins.runOnMainSync {
                monitoring = false
                Choreographer.getInstance().removeFrameCallback(frameCallback)
                oldPolicy?.let { StrictMode.setThreadPolicy(it) }
            }
            strictExecutor.shutdown()
            strictExecutor.awaitTermination(5, TimeUnit.SECONDS)
            AndroidSqlTiming.observer = null
            component.commandRunner.timingObserver = null
            component.habitCardListCache.timingObserver = null
            val variant = InstrumentationRegistry.getArguments().getString("checkpointVariant") ?: "unspecified"
            val dir = File(ins.targetContext.getExternalFilesDir(null), "main-thread-checkpoint").apply { mkdirs() }
            File(dir, "$variant.json").writeText(JSONObject().put("variant", variant).put("mode", component.session.mode.name)
                .put("offline", !component.session.capabilities.remoteCallsAllowed).put("steps", steps)
                .put("events", JSONArray(events.toList())).put("failure", failure?.toString() ?: JSONObject.NULL)
                .put("measurement", "Input handler/dispatch and first ViewTreeObserver draw; Choreographer intervals/heartbeat; per-command SQLite and rendered parity without explicit full refresh; SQL step timings include per-row steps, not whole query spans")
                .toString(2))
            ins.runOnMainSync { app.rebuildComponent(fixture.session) }
        }
        failure?.let { throw it }
    }
}
