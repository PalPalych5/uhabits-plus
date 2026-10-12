package org.isoron.uhabits.qa

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.runBlocking
import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.common.dialogs.NumberDialog
import org.isoron.uhabits.activities.habits.list.views.CheckmarkButtonView
import org.isoron.uhabits.activities.habits.list.views.HabitCardListView
import org.isoron.uhabits.activities.habits.list.views.NumberButtonView
import org.isoron.uhabits.activities.main.MainActivity
import org.isoron.uhabits.activities.main.MainDestination
import org.isoron.uhabits.core.containers.session.DatasetCapabilities
import org.isoron.uhabits.core.containers.session.DatasetMode
import org.isoron.uhabits.database.AndroidSqlTiming
import org.isoron.uhabits.utils.DemoDataGenerator
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class HabitInteractionLagBenchmarkTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(ins)

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
        }
    }

    @Test
    fun runInteractionLagBenchmark() {
        val variant = InstrumentationRegistry.getArguments().getString("benchmarkVariant") ?: "baseline"
        val fixture = UiReviewCaptureTest.initializeFixture()
        ProductionUiHotfixFixture.prepare(fixture)

        val app = ins.targetContext.applicationContext as HabitsApplication
        val isIsolationRun = InstrumentationRegistry.getArguments().getString("isolation") == "true"
        val disabledSideEffects = InstrumentationRegistry.getArguments().getString("disableSideEffects") ?: ""

        val qaSession = fixture.session.copy(
            mode = DatasetMode.PRODUCTION_NEXT,
            capabilities = DatasetCapabilities.PRODUCTION_NEXT.copy(
                syncEnabled = !disabledSideEffects.contains("sync"),
                remoteCallsAllowed = false,
                backgroundJobsEnabled = !disabledSideEffects.contains("jobs"),
                widgetsEnabled = !disabledSideEffects.contains("widgets"),
                remindersEnabled = !disabledSideEffects.contains("reminders")
            )
        )
        ins.runOnMainSync { app.rebuildComponent(qaSession) }
        val component = HabitsApplication.component

        fun drain() {
            repeat(4) {
                runBlocking { component.taskRunner.await() }
                ins.waitForIdleSync()
            }
        }

        val events = Collections.synchronizedList(mutableListOf<JSONObject>())
        val interactionRecords = Collections.synchronizedList(mutableListOf<JSONObject>())
        val choreographerFrames = Collections.synchronizedList(mutableListOf<Double>())
        val mainHandler = Handler(Looper.getMainLooper())
        val heartbeatExecutor = Executors.newSingleThreadScheduledExecutor()

        fun event(stage: String, ms: Double) {
            events.add(
                JSONObject().put("stage", stage).put("ms", ms)
                    .put("mainThread", Looper.myLooper() == Looper.getMainLooper())
            )
        }

        component.commandRunner.timingObserver = ::event
        component.habitCardListCache.timingObserver = ::event
        AndroidSqlTiming.observer = { verb, ms ->
            event("sqlite.$verb", ms)
            if (ms > 10.0 && Looper.myLooper() == Looper.getMainLooper()) {
                val path = Thread.currentThread().stackTrace.filter {
                    it.className.startsWith("org.isoron") &&
                        !it.className.contains("HabitInteractionLag") &&
                        !it.className.contains("AndroidPreparedStatement")
                }.take(8).joinToString(" > ") { "${it.className.substringAfterLast('.')}.${it.methodName}" }
                event("main.sqlPath:$path", ms)
            }
        }

        component.preferences.isFirstRun = false
        component.preferences.isShortToggleEnabled = true

        try {
            ActivityScenario.launch<MainActivity>(Intent(ins.targetContext, MainActivity::class.java)).use { scenario ->
                scenario.onActivity { it.navigate(MainDestination.HABITS) }
                drain()
                QaEmulatorUi.clearSystemUiError()

                // 1. Heartbeat delay measurement
                heartbeatExecutor.scheduleAtFixedRate({
                    val req = SystemClock.elapsedRealtimeNanos()
                    mainHandler.post {
                        val delay = (SystemClock.elapsedRealtimeNanos() - req) / 1e6
                        event("main.heartbeatDelay", delay)
                    }
                }, 0, 16, TimeUnit.MILLISECONDS)

                // 2. Choreographer frame tracking
                var isMonitoringFrames = true
                var lastFrameNanos = 0L
                val frameCallback = object : Choreographer.FrameCallback {
                    override fun doFrame(frameTimeNanos: Long) {
                        if (!isMonitoringFrames) return
                        if (lastFrameNanos > 0L) {
                            val durationMs = (frameTimeNanos - lastFrameNanos) / 1e6
                            choreographerFrames.add(durationMs)
                        }
                        lastFrameNanos = frameTimeNanos
                        Choreographer.getInstance().postFrameCallback(this)
                    }
                }
                scenario.onActivity { Choreographer.getInstance().postFrameCallback(frameCallback) }

                fun performMeasuredClick(scenarioName: String, index: Int, getTargetView: (MainActivity) -> View) {
                    val drawn = CountDownLatch(1)
                    var started = 0L
                    var handlerMs = 0.0
                    var tapToDrawMs = 0.0
                    var clickSuccess = false

                    scenario.onActivity { activity ->
                        val target = getTargetView(activity)
                        lateinit var drawListener: ViewTreeObserver.OnDrawListener
                        drawListener = ViewTreeObserver.OnDrawListener {
                            if (drawn.count > 0) {
                                tapToDrawMs = (SystemClock.elapsedRealtimeNanos() - started) / 1e6
                                drawn.countDown()
                                target.post {
                                    if (target.viewTreeObserver.isAlive) {
                                        target.viewTreeObserver.removeOnDrawListener(drawListener)
                                    }
                                }
                            }
                        }
                        target.viewTreeObserver.addOnDrawListener(drawListener)
                        started = SystemClock.elapsedRealtimeNanos()
                        clickSuccess = target.performClick()
                        handlerMs = (SystemClock.elapsedRealtimeNanos() - started) / 1e6
                    }

                    val didDraw = drawn.await(30, TimeUnit.SECONDS)
                    ins.waitForIdleSync()
                    interactionRecords.add(
                        JSONObject()
                            .put("scenario", scenarioName)
                            .put("index", index)
                            .put("success", clickSuccess)
                            .put("didDraw", didDraw)
                            .put("handlerMs", handlerMs)
                            .put("tapToDrawMs", if (didDraw) tapToDrawMs else -1.0)
                    )
                }

                // ==========================================
                // Scenario 1: 100 sequential Boolean check/uncheck operations
                // ==========================================
                for (i in 0 until 100) {
                    QaEmulatorUi.clearSystemUiError()
                    performMeasuredClick("sequential_boolean", i) { activity ->
                        val buttons = descendants(activity.window.decorView).filterIsInstance<CheckmarkButtonView>()
                            .filter { it.isShown && it.width > 0 }.toList()
                        check(buttons.isNotEmpty()) { "No checkmark button available" }
                        buttons[i % buttons.size]
                    }
                    if (i % 20 == 0) drain()
                    Thread.sleep(25) // realistic human cadence
                }
                drain()

                // ==========================================
                // Scenario 2: Rapid alternating burst across different habits
                // ==========================================
                val rapidDone = CountDownLatch(20)
                scenario.onActivity { activity ->
                    val buttons = descendants(activity.window.decorView).filterIsInstance<CheckmarkButtonView>()
                        .filter { it.isShown && it.width > 0 }.toList()
                    check(buttons.size >= 3)
                    for (i in 0 until 20) {
                        val btn = buttons[i % buttons.size]
                        val req = SystemClock.elapsedRealtimeNanos()
                        mainHandler.post {
                            event("ui.rapidBurstDispatch", (SystemClock.elapsedRealtimeNanos() - req) / 1e6)
                            btn.performClick()
                            rapidDone.countDown()
                        }
                    }
                }
                check(rapidDone.await(30, TimeUnit.SECONDS))
                drain()

                // ==========================================
                // Scenario 3: Numerical value edits, zero and undo
                // ==========================================
                for (iter in 0 until 5) {
                    for (value in listOf("25", "0", "15")) {
                        scenario.onActivity { activity ->
                            val numBtn = descendants(activity.window.decorView).filterIsInstance<NumberButtonView>()
                                .first { it.isShown && it.width > 0 }
                            numBtn.performClick()
                        }
                        drain()
                        scenario.onActivity { activity ->
                            val dialog = activity.supportFragmentManager.findFragmentByTag("numberDialog") as? NumberDialog
                            checkNotNull(dialog)
                            dialog.requireDialog().findViewById<EditText>(R.id.value).setText(value)
                            dialog.save()
                        }
                        drain()
                    }
                }

                // ==========================================
                // Scenario 4: Repeated scrolling while completion updates occur
                // ==========================================
                for (iter in 0 until 5) {
                    scenario.onActivity { activity ->
                        val list = descendants(activity.window.decorView).filterIsInstance<HabitCardListView>().first()
                        list.scrollToPosition(15)
                    }
                    drain()
                    performMeasuredClick("scroll_toggle", iter * 2) { activity ->
                        descendants(activity.window.decorView).filterIsInstance<CheckmarkButtonView>().first { it.isShown }
                    }
                    scenario.onActivity { activity ->
                        val list = descendants(activity.window.decorView).filterIsInstance<HabitCardListView>().first()
                        list.scrollToPosition(0)
                    }
                    drain()
                    performMeasuredClick("scroll_toggle", iter * 2 + 1) { activity ->
                        descendants(activity.window.decorView).filterIsInstance<CheckmarkButtonView>().first { it.isShown }
                    }
                    drain()
                }

                // ==========================================
                // Scenario 5: Switching Habits -> Tasks -> Habits
                // ==========================================
                for (iter in 0 until 5) {
                    scenario.onActivity { activity ->
                        activity.navigate(MainDestination.TASKS)
                    }
                    drain()
                    scenario.onActivity { activity ->
                        activity.navigate(MainDestination.HABITS)
                    }
                    drain()
                    performMeasuredClick("nav_toggle", iter) { activity ->
                        descendants(activity.window.decorView).filterIsInstance<CheckmarkButtonView>().first { it.isShown }
                    }
                    drain()
                }

                // ==========================================
                // Scenario 6: Habit with Focus contribution vs without
                // ==========================================
                // "Медитация" has FocusSession fact
                for (iter in 0 until 10) {
                    performMeasuredClick("focus_habit_toggle", iter) { activity ->
                        // Focus habit is a NumberButtonView
                        val numBtns = descendants(activity.window.decorView).filterIsInstance<NumberButtonView>()
                            .filter { it.isShown && it.width > 0 }.toList()
                        numBtns.first()
                    }
                    drain()
                }

                // Done monitoring
                isMonitoringFrames = false
                heartbeatExecutor.shutdownNow()
                drain()
            }

            // Export comprehensive benchmark results
            val outputDir = File(ins.targetContext.getExternalFilesDir(null), "interaction-lag").apply { mkdirs() }
            val outputFile = File(outputDir, "benchmark-$variant.json")

            val rootJson = JSONObject()
                .put("variant", variant)
                .put("isolationRun", isIsolationRun)
                .put("disabledSideEffects", disabledSideEffects)
                .put("habitCount", component.habitList.size())
                .put("totalInteractions", interactionRecords.size)
                .put("interactions", JSONArray(interactionRecords.toList()))
                .put("choreographerFrames", JSONArray(choreographerFrames.toList()))
                .put("events", JSONArray(events.toList()))

            outputFile.writeText(rootJson.toString(2))

        } finally {
            heartbeatExecutor.shutdownNow()
            component.commandRunner.timingObserver = null
            component.habitCardListCache.timingObserver = null
            AndroidSqlTiming.observer = null
            ins.runOnMainSync { app.rebuildComponent(fixture.session) }
        }
    }
}
