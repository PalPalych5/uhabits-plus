package org.isoron.uhabits.qa

import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.Trace
import android.os.SystemClock
import android.view.Choreographer
import android.view.FrameMetrics
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.isoron.platform.io.queryLong
import org.isoron.platform.io.queryString
import org.isoron.platform.time.getToday
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.activities.habits.list.views.CheckmarkButtonView
import org.isoron.uhabits.activities.habits.list.views.HabitCardView
import org.isoron.uhabits.activities.habits.list.views.HabitFrameTiming
import org.isoron.uhabits.activities.main.MainActivity
import org.isoron.uhabits.activities.main.MainDestination
import org.isoron.uhabits.core.containers.session.DatasetCapabilities
import org.isoron.uhabits.core.containers.session.DatasetMode
import org.isoron.uhabits.core.models.Entry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Disposable-device-only target-glyph/frame-timeline evidence; never a root OnDraw proxy. */
@RunWith(AndroidJUnit4::class)
class HabitVisibleFrameStallTest {
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
    private fun card(view: View?): HabitCardView? {
        var current = view
        while (current != null) {
            if (current is HabitCardView) return current
            current = current.parent as? View
        }
        return null
    }

    @Test fun captureCommittedTargetFrames() {
        val ins = InstrumentationRegistry.getInstrumentation()
        // Existing helper checks both supplied token and actual AVD property before writes.
        val fixture = UiReviewCaptureTest.initializeFixture()
        ProductionUiHotfixFixture.prepare(fixture)
        val app = ins.targetContext.applicationContext as HabitsApplication
        val session = fixture.session.copy(mode = DatasetMode.PRODUCTION_NEXT,
            capabilities = DatasetCapabilities.PRODUCTION_NEXT.copy(syncEnabled = false,
                remoteCallsAllowed = false, backgroundJobsEnabled = false,
                widgetsEnabled = false, remindersEnabled = false))
        ins.runOnMainSync { app.rebuildComponent(session) }
        val component = HabitsApplication.component
        val variant = InstrumentationRegistry.getArguments().getString("benchmarkVariant") ?: "baseline"
        check(variant.matches(Regex("[A-Za-z0-9_-]+")))
        check(component.db.queryLong("PRAGMA user_version") == 30L)
        check(!component.session.capabilities.remoteCallsAllowed)
        component.preferences.isFirstRun = false
        component.preferences.isShortToggleEnabled = true
        fun drain() { repeat(3) { runBlocking { component.taskRunner.await() }; ins.waitForIdleSync() } }
        class Interaction(val ordinal: Int, val expected: Int, val target: CheckmarkButtonView) {
            val committed = AtomicBoolean()
            val published = AtomicBoolean()
            val drawn = CountDownLatch(1)
            val events = Collections.synchronizedList(mutableListOf<JSONObject>())
            @Volatile var inputNanos = 0L
            @Volatile var drawnNanos = 0L
            @Volatile var firstExpectedDrawNanos = 0L
            @Volatile var firstExpectedVsyncId = 0L
            @Volatile var firstExpectedFrameTimeNanos = 0L
            @Volatile var intendedVsyncNanos = 0L
            @Volatile var vsyncId = 0L
        }
        val active = AtomicReference<Interaction?>()
        val metrics = Collections.synchronizedList(mutableListOf<JSONObject>())
        val results = mutableListOf<JSONObject>()
        val rowOrdinals = component.habitList.toList().mapIndexedNotNull { index, habit ->
            habit.id?.let { it to index }
        }.toMap()
        var choreographer: Choreographer? = null
        var measuredWindow: Window? = null
        var captureFrames = true
        var latestFrameTimeNanos = 0L
        var latestVsyncId = 0L
        val vsyncCallback = object : Choreographer.VsyncCallback {
            override fun onVsync(frameData: Choreographer.FrameData) {
                if (!captureFrames) return
                latestFrameTimeNanos = frameData.frameTimeNanos
                latestVsyncId = frameData.preferredFrameTimeline.vsyncId
                choreographer?.postVsyncCallback(this)
            }
        }
        val metricThread = HandlerThread("qa-habit-frame-metrics").apply { start() }
        val metricListener = Window.OnFrameMetricsAvailableListener { _, fm, dropped ->
            metrics.add(JSONObject().put("intendedVsyncNanos", fm.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP))
                .put("vsyncId", fm.getMetric(FrameMetrics.FRAME_TIMELINE_VSYNC_ID))
                .put("totalNanos", fm.getMetric(FrameMetrics.TOTAL_DURATION))
                .put("layoutMeasureNanos", fm.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION))
                .put("drawNanos", fm.getMetric(FrameMetrics.DRAW_DURATION)).put("droppedReports", dropped))
        }
        try {
            ActivityScenario.launch<MainActivity>(Intent(ins.targetContext, MainActivity::class.java)).use { scenario ->
                scenario.onActivity { it.navigate(MainDestination.HABITS) }
                drain()
                // Allow the fixture revision's delayed refresh to finish before observation.
                SystemClock.sleep(350); drain()
                lateinit var target: CheckmarkButtonView
                var habitId = 0L
                scenario.onActivity { activity ->
                    val targetCard = descendants(activity.window.decorView).filterIsInstance<HabitCardView>()
                        .first { it.isShown && it.habit?.isNumerical == false && it.dataOffset == 0 }
                    target = targetCard.checkmarkPanel.buttons.first()
                    check(target.isShown && target.width > 0)
                    habitId = requireNotNull(card(target)?.habit?.id)
                    measuredWindow = activity.window
                    activity.window.addOnFrameMetricsAvailableListener(metricListener, Handler(metricThread.looper))
                    choreographer = Choreographer.getInstance()
                    choreographer?.postVsyncCallback(vsyncCallback)
                }
                component.commandRunner.timingObserver = { stage, ms -> active.get()?.let { sample ->
                    sample.events.add(JSONObject().put("stage", stage).put("durationNanos", (ms * 1e6).toLong())
                        .put("timestampNanos", System.nanoTime()).put("mainThread", Looper.myLooper() == Looper.getMainLooper()))
                    if (stage == "command.finished") sample.committed.set(true)
                } }
                component.habitCardListCache.timingObserver = { stage, ms -> active.get()?.let { sample ->
                    sample.events.add(JSONObject().put("stage", stage).put("durationNanos", (ms * 1e6).toLong())
                        .put("timestampNanos", System.nanoTime()).put("mainThread", Looper.myLooper() == Looper.getMainLooper()))
                    if (stage == "cache.publish" && sample.committed.get()) sample.published.set(true)
                } }
                HabitFrameTiming.observer = { stage, view, duration -> active.get()?.let { sample ->
                    val now = System.nanoTime()
                    sample.events.add(JSONObject().put("stage", stage).put("durationNanos", duration)
                        .put("timestampNanos", now).put("targetCard", card(view)?.habit?.id == habitId)
                        .put("rowOrdinal", card(view)?.habit?.id?.let { rowOrdinals[it] } ?: -1)
                        .put("mainThread", Looper.myLooper() == Looper.getMainLooper()))
                    fun marker(kind: String) {
                        Trace.beginSection("habit.target.$kind.${sample.ordinal}")
                        Trace.endSection()
                    }
                    if (view === sample.target && stage == "habit.input.down") {
                        sample.inputNanos = now
                        marker("input")
                    }
                    if (view === sample.target && stage == "habit.checkmark.draw" && sample.target.value == sample.expected &&
                        sample.firstExpectedDrawNanos == 0L) {
                        sample.firstExpectedDrawNanos = now
                        sample.firstExpectedVsyncId = latestVsyncId
                        sample.firstExpectedFrameTimeNanos = latestFrameTimeNanos
                        marker("expected")
                    }
                    if (view === sample.target && stage == "habit.checkmark.draw" && sample.committed.get() && sample.published.get() &&
                        sample.target.value == sample.expected && sample.drawnNanos == 0L) {
                        sample.drawnNanos = now
                        sample.intendedVsyncNanos = latestFrameTimeNanos
                        sample.vsyncId = latestVsyncId
                        marker("committed")
                        sample.drawn.countDown()
                    }
                } }
                repeat(40) { ordinal ->
                    var expected = 0
                    val point = IntArray(2)
                    scenario.onActivity {
                        check(target.isShown && card(target)?.habit?.id == habitId) { "Target recycled or hidden" }
                        expected = Entry.nextToggleValue(target.value, component.preferences.isSkipEnabled,
                            component.preferences.areQuestionMarksEnabled)
                        target.getLocationOnScreen(point)
                        point[0] += target.width / 2; point[1] += target.height / 2
                    }
                    val sample = Interaction(ordinal, expected, target)
                    active.set(sample)
                    val downTime = SystemClock.uptimeMillis()
                    for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                            point[0].toFloat(), point[1].toFloat(), 0)
                        try { ins.sendPointerSync(event) } finally { event.recycle() }
                    }
                    check(sample.drawn.await(15, TimeUnit.SECONDS)) { "No committed target glyph frame for edit $ordinal" }
                    drain()
                    val stored = component.db.queryLong("SELECT value FROM Repetitions WHERE habit=$habitId AND timestamp=${getToday().unixTime}")
                    check(stored == expected.toLong()) { "Rendered target differs from committed entry for edit $ordinal" }
                    // Capture the secondary refresh and unrelated remeasurement in the same edit window.
                    SystemClock.sleep(350); drain()
                    active.set(null)
                    check(sample.inputNanos > 0L)
                    results.add(JSONObject().put("ordinal", ordinal).put("inputNanos", sample.inputNanos)
                        .put("committedTargetDrawNanos", sample.drawnNanos)
                        .put("firstExpectedTargetDrawNanos", sample.firstExpectedDrawNanos)
                        .put("firstExpectedVsyncId", sample.firstExpectedVsyncId)
                        .put("firstExpectedFrameTimeNanos", sample.firstExpectedFrameTimeNanos)
                        .put("targetIntendedVsyncNanos", sample.intendedVsyncNanos)
                        .put("targetVsyncId", sample.vsyncId)
                        .put("inputToCommittedTargetDrawMs", (sample.drawnNanos - sample.inputNanos) / 1e6)
                        .put("committedStateMatches", true).put("events", JSONArray(sample.events.toList())))
                }
                scenario.onActivity {
                    it.window.removeOnFrameMetricsAvailableListener(metricListener)
                    measuredWindow = null
                }
            }
            val integrity = component.db.queryString("PRAGMA quick_check")
            check(integrity == "ok")
            val output = File(ins.targetContext.getExternalFilesDir(null), "habit-visible-frame").apply { mkdirs() }
            File(output, "$variant.json").writeText(JSONObject().put("variant", variant)
                .put("pid", Process.myPid())
                .put("schemaVersion", 30).put("journalMode", component.db.queryString("PRAGMA journal_mode"))
                .put("integrity", integrity).put("interactions", JSONArray(results))
                .put("frameMetrics", JSONArray(metrics.toList()))
                .put("metricScope", "Actual injected input to matching target glyph drawn after command completion, asserted against committed SQL. Join intendedVsyncNanos to FrameMetrics vsyncId then Perfetto FrameTimeline actual presentation; draw alone does not prove display presentation.")
                .toString(2))
        } finally {
            active.set(null)
            HabitFrameTiming.observer = null
            component.commandRunner.timingObserver = null
            component.habitCardListCache.timingObserver = null
            ins.runOnMainSync {
                captureFrames = false
                choreographer?.removeVsyncCallback(vsyncCallback)
                measuredWindow?.removeOnFrameMetricsAvailableListener(metricListener)
            }
            metricThread.quitSafely(); metricThread.join(5000)
            ins.runOnMainSync { app.rebuildComponent(fixture.session) }
        }
    }
}
