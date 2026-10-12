package org.isoron.uhabits.qa

import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.activities.main.MainActivity
import org.isoron.uhabits.activities.main.MainDestination
import org.isoron.uhabits.activities.habits.list.views.CheckmarkButtonView
import org.isoron.uhabits.activities.habits.list.views.NumberButtonView
import org.isoron.uhabits.activities.habits.list.views.HabitCardListView
import org.isoron.uhabits.activities.common.dialogs.NumberDialog
import org.isoron.uhabits.utils.DemoDataGenerator
import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.R
import android.widget.EditText
import org.isoron.uhabits.core.containers.session.DatasetMode
import org.isoron.uhabits.core.containers.session.DatasetCapabilities
import org.isoron.uhabits.database.AndroidSqlTiming
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking

/** Only the same guarded offline disposable fixture used by screenshot capture. */
@RunWith(AndroidJUnit4::class)
class HabitTapPerformanceAndroidTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
    @Test fun measureRenderedBooleanTapsAndPersistence() {
        val startupFixture = InstrumentationRegistry.getArguments().getString("startupFixture") == "true"
        val experiment = if (startupFixture) {
            check(InstrumentationRegistry.getArguments().getString("disposableAvd") == "UHabit_UI_Review_Disposable_20261011")
            check(androidx.test.uiautomator.UiDevice.getInstance(ins).executeShellCommand("getprop ro.boot.qemu.avd_name").trim() == "UHabit_UI_Review_Disposable_20261011")
            check(File(ins.targetContext.filesDir, org.isoron.uhabits.utils.HabitsStartupTrace.MARKER).exists())
            HabitsApplication.component.also {
                check(it.session.mode == DatasetMode.PRODUCTION_NEXT && !it.session.capabilities.remoteCallsAllowed && !it.session.capabilities.syncEnabled)
            }
        } else UiReviewCaptureTest.initializeFixture()
        if (!startupFixture) DemoDataGenerator.generate(ins.targetContext, experiment.sqlModelFactory, experiment.habitList, null,
            experiment.habitCardListCache, true, LocalDate(2026, 10, 11))
        val app = ins.targetContext.applicationContext as HabitsApplication
        check(startupFixture || experiment.session.isExperimental)
        val qaSession = experiment.session.copy(mode = DatasetMode.PRODUCTION_NEXT,
            capabilities = DatasetCapabilities.PRODUCTION_NEXT.copy(syncEnabled = false, remoteCallsAllowed = false,
                backgroundJobsEnabled = false))
        ins.runOnMainSync { app.rebuildComponent(qaSession) }
        val component = HabitsApplication.component
        fun drain() { repeat(6) { runBlocking { component.taskRunner.await() }; ins.waitForIdleSync() } }
        val events = Collections.synchronizedList(mutableListOf<JSONObject>())
        val taps = JSONArray()
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        val heartbeat = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
        fun event(stage: String, ms: Double) { events.add(JSONObject().put("stage", stage).put("ms", ms)
            .put("mainThread", android.os.Looper.myLooper() == android.os.Looper.getMainLooper())) }
        component.commandRunner.timingObserver = ::event
        component.habitCardListCache.timingObserver = ::event
        AndroidSqlTiming.observer = { verb, ms ->
            event("sqlite.$verb", ms)
            if (ms > 10 && android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                val path = Thread.currentThread().stackTrace.filter {
                    it.className.startsWith("org.isoron") && !it.className.contains("HabitTapPerformance") &&
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
                heartbeat.scheduleAtFixedRate({
                    val requestedAt = SystemClock.elapsedRealtimeNanos()
                    mainHandler.post { event("main.heartbeatDelay", (SystemClock.elapsedRealtimeNanos() - requestedAt) / 1e6) }
                }, 0, 16, TimeUnit.MILLISECONDS)
                for (index in 0 until 8) {
                    QaEmulatorUi.clearSystemUiError()
                    val drawn = CountDownLatch(1)
                    var started = 0L
                    var response = 0.0
                    var handler = 0.0
                    scenario.onActivity { activity ->
                        val button = descendants(activity.window.decorView).filterIsInstance<CheckmarkButtonView>()
                            .firstOrNull { it.isShown && it.width > 0 } ?: error("No rendered Boolean completion button")
                        lateinit var listener: ViewTreeObserver.OnDrawListener
                        listener = ViewTreeObserver.OnDrawListener {
                            if (drawn.count > 0) { response = (SystemClock.elapsedRealtimeNanos() - started) / 1e6; drawn.countDown()
                                button.post { if (button.viewTreeObserver.isAlive) button.viewTreeObserver.removeOnDrawListener(listener) } }
                        }
                        button.viewTreeObserver.addOnDrawListener(listener)
                        started = SystemClock.elapsedRealtimeNanos()
                        check(button.performClick())
                        handler = (SystemClock.elapsedRealtimeNanos() - started) / 1e6
                    }
                    check(drawn.await(30, TimeUnit.SECONDS)) { "Tap did not render within 30s" }
                    ins.waitForIdleSync()
                    taps.put(JSONObject().put("index", index).put("handlerMs", handler).put("tapToDrawMs", response))
                    drain()
                }
                // Burst taps exercise the real UI toggle route while background work overlaps.
                scenario.onActivity { activity ->
                    val button = descendants(activity.window.decorView).filterIsInstance<CheckmarkButtonView>().first { it.isShown }
                    repeat(6) { check(button.performClick()) }
                }
                drain()
                var rapidButton: CheckmarkButtonView? = null
                scenario.onActivity { activity -> rapidButton = descendants(activity.window.decorView).filterIsInstance<CheckmarkButtonView>().first { it.isShown } }
                val rapidDone = CountDownLatch(6)
                repeat(6) {
                    val requestedAt = SystemClock.elapsedRealtimeNanos()
                    mainHandler.post {
                        event("ui.rapidTapDispatch", (SystemClock.elapsedRealtimeNanos() - requestedAt) / 1e6)
                        check(rapidButton!!.performClick())
                        rapidDone.countDown()
                    }
                    Thread.sleep(100)
                }
                check(rapidDone.await(30, TimeUnit.SECONDS))
                drain()
                // Numerical edits use the rendered card, real number dialog and its save route.
                for (value in listOf("15", "20", "25")) {
                    scenario.onActivity { activity ->
                        val button = descendants(activity.window.decorView).filterIsInstance<NumberButtonView>().first { it.isShown }
                        check(button.performClick())
                    }
                    drain()
                    scenario.onActivity { activity ->
                        val dialog = activity.supportFragmentManager.findFragmentByTag("numberDialog") as NumberDialog
                        dialog.requireDialog().findViewById<EditText>(R.id.value).setText(value)
                        dialog.save()
                    }
                    ins.waitForIdleSync()
                }
                scenario.onActivity { activity ->
                    descendants(activity.window.decorView).filterIsInstance<HabitCardListView>().first().scrollToPosition(20)
                    activity.navigate(MainDestination.TASKS)
                    activity.navigate(MainDestination.HABITS)
                }
                drain()
            }
            heartbeat.shutdownNow()
            ins.waitForIdleSync()
            val output = File(ins.targetContext.getExternalFilesDir(null), "ui-review").apply { mkdirs() }
            val label = InstrumentationRegistry.getArguments().getString("performanceVariant") ?: "after"
            File(output, "performance-$label.json").writeText(JSONObject().put("variant", label)
                .put("datasetMode", component.session.mode.name).put("seed", 20261011).put("habitCount", component.habitList.size())
                .put("syncDisabled", true).put("taps", taps).put("events", JSONArray(events.toList()))
                .put("smoke", "Six rapid Boolean taps, three numerical dialog saves/increments, scroll and rapid Tasks/Habits navigation")
                .put("measurement", "Rendered CheckmarkButtonView.performClick handler to first actual ViewTreeObserver draw; persistence measured independently")
                .toString(2))
        } finally {
            heartbeat.shutdownNow()
            component.commandRunner.timingObserver = null
            component.habitCardListCache.timingObserver = null
            AndroidSqlTiming.observer = null
            ins.runOnMainSync { app.rebuildComponent(experiment.session) }
        }
    }
}
