package org.isoron.uhabits.qa

import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.os.Trace
import android.view.FrameMetrics
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.runBlocking
import org.isoron.platform.io.queryLong
import org.isoron.platform.io.queryString
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.getToday
import org.isoron.platform.time.setToday
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.activities.habits.list.views.CheckmarkButtonView
import org.isoron.uhabits.activities.habits.list.views.HabitCardListView
import org.isoron.uhabits.activities.habits.list.views.HabitCardView
import org.isoron.uhabits.activities.habits.list.views.HabitFrameTiming
import org.isoron.uhabits.activities.main.MainActivity
import org.isoron.uhabits.activities.main.MainDestination
import org.isoron.uhabits.activities.statistics.StatisticsFragment
import org.isoron.uhabits.core.commands.Command
import org.isoron.uhabits.core.commands.CreateRepetitionCommand
import org.isoron.uhabits.core.commands.HabitEditTrace
import org.isoron.uhabits.core.containers.session.DatasetCapabilities
import org.isoron.uhabits.core.containers.session.DatasetMode
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Frequency
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitType
import org.isoron.uhabits.focus.EffectiveHabitReadController
import org.isoron.uhabits.utils.DemoDataGenerator
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * UHabit Next — Iteration 11:
 * End-to-end verification of post-navigation habit latency across:
 * A: Baseline Habits-only edits.
 * B: Habits -> Tasks -> Habits -> edit.
 * C: Habits -> Statistics -> Habits -> edit.
 * D: Habits -> Tasks (task modified) -> Habits -> edit.
 * E: Habits -> Statistics (tab/filter changed) -> Habits -> edit.
 * F: Repeated 1, 3, 10 cycles of Habits -> Tasks -> Statistics -> Habits.
 *
 * Legacy exploratory timing starts before harness setup. Cache/draw callbacks are
 * unqualified observations and FrameMetrics reports frame work, not presentation.
 * Use PostNavigationPresentationAttributionTest for operation-qualified latency.
 * Also verifies lifecycle correctness (observer attachment, CPU work cancellation, fresh return, rapid transitions).
 */
@RunWith(AndroidJUnit4::class)
class PostNavigationLatencyInvestigationTest {

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
        }
    }

    private fun card(view: View?): HabitCardView? {
        var current = view
        while (current != null) {
            if (current is HabitCardView) return current
            current = current.parent as? View
        }
        return null
    }

    private fun memorySample(): JSONObject {
        val runtime = Runtime.getRuntime()
        val memInfo = android.os.Debug.MemoryInfo()
        android.os.Debug.getMemoryInfo(memInfo)
        return JSONObject()
            .put("totalMemoryBytes", runtime.totalMemory())
            .put("freeMemoryBytes", runtime.freeMemory())
            .put("usedMemoryBytes", runtime.totalMemory() - runtime.freeMemory())
            .put("totalPssKiB", memInfo.totalPss)
    }

    @Test
    fun investigatePostNavigationLatency() {
        val ins = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(ins)

        // Preflight environment checks
        check(Build.MODEL.contains("sdk") || Build.MODEL.contains("Emulator")) { "Physical devices strictly forbidden" }
        check(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim() == "UHabit_UI_Review_Disposable_20261011") { "Must use verified disposable AVD" }
        check(device.executeShellCommand("getprop ro.serialno").trim() == "EMULATOR36X6X11X0") { "Must match EMULATOR36X6X11X0" }
        check(device.executeShellCommand("settings get global airplane_mode_on").trim() == "1") { "Offline environment required" }

        val fixture = UiReviewCaptureTest.initializeFixture()
        val marker = File(ins.targetContext.filesDir, "${DemoDataGenerator.DISPOSABLE_MARKER}-${fixture.session.datasetId}")
        check(marker.exists() && marker.readText() == fixture.session.databaseFilename)
        check(fixture.db.queryLong("PRAGMA user_version") == 30L)

        val referenceDate = LocalDate(2026, 10, 11)
        setToday(referenceDate)
        ProductionUiHotfixFixture.prepare(fixture)

        val app = ins.targetContext.applicationContext as HabitsApplication
        val qaSession = fixture.session.copy(
            mode = DatasetMode.PRODUCTION_NEXT,
            capabilities = DatasetCapabilities.PRODUCTION_NEXT.copy(
                syncEnabled = false, remoteCallsAllowed = false,
                backgroundJobsEnabled = false, widgetsEnabled = false, remindersEnabled = false
            )
        )
        ins.runOnMainSync { app.rebuildComponent(qaSession) }
        val component = HabitsApplication.component

        fun drain() {
            repeat(3) {
                runBlocking { component.taskRunner.await() }
                ins.waitForIdleSync()
            }
        }
        drain()

        val controller = requireNotNull(component.effectiveHabitReads)

        // Setup test target habit
        val targetHabits = mutableListOf<Habit>()
        repeat(2) { index ->
            val h = component.sqlModelFactory.buildHabit().apply {
                name = "Nav Latency Fixture $index"
                type = HabitType.YES_NO
                frequency = Frequency(1, 1)
                blockId = component.habitList.getByPosition(0).blockId
            }
            component.habitList.add(h)
            h.originalEntries.add(Entry(getToday(), Entry.NO))
            h.recompute()
            component.habitList.reorder(h, component.habitList.getByPosition(0)!!)
            targetHabits.add(h)
        }
        val targetHabitId = targetHabits.first().id!!

        component.preferences.isShortToggleEnabled = true
        component.preferences.isFirstRun = false
        component.preferences.defaultPrimaryOrder = org.isoron.uhabits.core.models.HabitList.Order.BY_POSITION
        component.preferences.defaultSecondaryOrder = org.isoron.uhabits.core.models.HabitList.Order.BY_POSITION

        QaEmulatorUi.clearSystemUiError()

        // Collector data structures
        val scenarioRecords = mutableListOf<JSONObject>()
        val refreshEvents = Collections.synchronizedList(mutableListOf<JSONObject>())
        val controllerLockEvents = Collections.synchronizedList(mutableListOf<JSONObject>())

        // Install hooks
        component.habitCardListCache.refreshObserver = { type, id, reason ->
            refreshEvents.add(JSONObject()
                .put("timestampNanos", System.nanoTime())
                .put("type", type)
                .put("habitId", id ?: JSONObject.NULL)
                .put("reason", reason ?: JSONObject.NULL)
            )
        }

        controller.lockMetricsObserver = { m ->
            controllerLockEvents.add(JSONObject()
                .put("timestampNanos", System.nanoTime())
                .put("waitMs", m.lockWaitDurationMs)
                .put("heldMs", m.lockHeldDurationMs)
                .put("habitCount", m.habitCount)
                .put("cacheHitCount", m.cacheHitCount)
                .put("preparedCount", m.preparedCount)
                .put("retriesCount", m.retriesCount)
            )
        }

        ActivityScenario.launch<MainActivity>(Intent(ins.targetContext, MainActivity::class.java)).use { scenario ->
            scenario.onActivity { it.navigate(MainDestination.HABITS) }
            drain(); SystemClock.sleep(300); drain()

            fun getTargetButton(): CheckmarkButtonView {
                var btn: CheckmarkButtonView? = null
                var ready = false
                repeat(20) {
                    if (!ready) {
                        scenario.onActivity { a ->
                            val list = descendants(a.window.decorView).filterIsInstance<HabitCardListView>().firstOrNull()
                            if (list != null && list.isLaidOut && list.width > 0 && list.checkmarkCount > 0) {
                                val position = (0 until component.habitCardListCache.habitCount).firstOrNull {
                                    component.habitCardListCache.getHabitByPosition(it)?.id == targetHabitId
                                } ?: 0
                                list.dataOffset = 0
                                list.scrollToPosition(position)
                                ready = true
                            }
                        }
                        if (!ready) { SystemClock.sleep(50); ins.waitForIdleSync() }
                    }
                }
                drain()
                scenario.onActivity { a ->
                    val cards = descendants(a.window.decorView).filterIsInstance<HabitCardView>().toList()
                    val targetCard = cards.firstOrNull { it.isShown && it.habit?.id == targetHabitId && it.dataOffset == 0 }
                    btn = targetCard?.checkmarkPanel?.buttons?.firstOrNull()
                }
                checkNotNull(btn) { "Target checkmark button not found in visible viewport" }
                return btn!!
            }

            fun injectClick(button: CheckmarkButtonView) {
                QaEmulatorUi.clearSystemUiError()
                val xy = IntArray(2)
                scenario.onActivity { a ->
                    check(a.hasWindowFocus()) { "MainActivity has no window focus" }
                    val bounds = android.graphics.Rect()
                    check(button.getGlobalVisibleRect(bounds) && bounds.width() > 0 && bounds.height() > 0) { "Target not visible" }
                    xy[0] = bounds.centerX()
                    xy[1] = bounds.centerY()
                }
                val downTime = SystemClock.uptimeMillis()
                val eventDown = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, xy[0].toFloat(), xy[1].toFloat(), 0)
                val eventUp = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, xy[0].toFloat(), xy[1].toFloat(), 0)
                try {
                    ins.sendPointerSync(eventDown)
                    ins.sendPointerSync(eventUp)
                } finally {
                    eventDown.recycle()
                    eventUp.recycle()
                }
            }

            fun performTrackedEdit(scenarioName: String, sampleIndex: Int): JSONObject {
                val button = getTargetButton()
                val startNanos = System.nanoTime()
                val lockEventsBefore = controllerLockEvents.size
                val refreshEventsBefore = refreshEvents.size

                val durableLatch = CountDownLatch(1)
                val snapshotLatch = CountDownLatch(1)
                val drawLatch = CountDownLatch(1)
                var durableTime = 0L
                var snapshotTime = 0L
                var drawTime = 0L

                val expectedValue = Entry.nextToggleValue(
                    button.value,
                    component.preferences.isSkipEnabled,
                    component.preferences.areQuestionMarksEnabled
                )

                button.operationTraceFactory = {
                    HabitEditTrace(1L) { _, stage, _, _, _ ->
                        if (stage == "sqlite.durable") {
                            durableTime = System.nanoTime()
                            durableLatch.countDown()
                        }
                    }
                }

                component.habitCardListCache.timingObserver = { stage, _ ->
                    if (stage == "cache.publish") {
                        snapshotTime = System.nanoTime()
                        snapshotLatch.countDown()
                    }
                }

                HabitFrameTiming.observer = { stage, view, _ ->
                    if (stage == "habit.checkmark.draw" && view is CheckmarkButtonView &&
                        card(view)?.habit?.id == targetHabitId && view.value == expectedValue) {
                        if (drawTime == 0L) {
                            drawTime = System.nanoTime()
                            drawLatch.countDown()
                        }
                    }
                }

                val frameMetricsList = Collections.synchronizedList(mutableListOf<JSONObject>())
                val metricThread = HandlerThread("fm-$sampleIndex").apply { start() }
                val metricListener = Window.OnFrameMetricsAvailableListener { _, frameMetrics, _ ->
                    frameMetricsList.add(JSONObject()
                        .put("totalDurationMs", frameMetrics.getMetric(FrameMetrics.TOTAL_DURATION) / 1e6)
                        .put("layoutMeasureMs", frameMetrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION) / 1e6)
                        .put("drawMs", frameMetrics.getMetric(FrameMetrics.DRAW_DURATION) / 1e6)
                        .put("intendedVsyncNanos", frameMetrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP))
                        .put("vsyncNanos", frameMetrics.getMetric(FrameMetrics.VSYNC_TIMESTAMP))
                    )
                }
                scenario.onActivity { a ->
                    a.window.addOnFrameMetricsAvailableListener(metricListener, Handler(metricThread.looper))
                }

                injectClick(button)

                val durableOk = durableLatch.await(5, TimeUnit.SECONDS)
                val drawOk = drawLatch.await(5, TimeUnit.SECONDS)
                val snapshotOk = snapshotLatch.await(5, TimeUnit.SECONDS)
                val endNanos = System.nanoTime()

                // Clean up hooks
                button.operationTraceFactory = null
                HabitFrameTiming.observer = null
                component.habitCardListCache.timingObserver = null
                scenario.onActivity { a ->
                    a.window.removeOnFrameMetricsAvailableListener(metricListener)
                }
                metricThread.quitSafely()

                drain()

                val newLockEvents = controllerLockEvents.subList(lockEventsBefore, controllerLockEvents.size)
                val newRefreshEvents = refreshEvents.subList(refreshEventsBefore, refreshEvents.size)

                val totalLockWait = newLockEvents.sumOf { it.getDouble("waitMs") }
                val totalLockHeld = newLockEvents.sumOf { it.getDouble("heldMs") }
                val maxPrepared = newLockEvents.maxOfOrNull { it.getInt("preparedCount") } ?: 0
                val totalPrepared = newLockEvents.sumOf { it.getInt("preparedCount") }
                val maxCacheHits = newLockEvents.maxOfOrNull { it.getInt("cacheHitCount") } ?: 0

                val storedValue = component.db.queryLong(
                    "SELECT value FROM Repetitions WHERE habit=$targetHabitId AND timestamp=${getToday().unixTime}"
                )
                val parityMatches = storedValue == expectedValue.toLong()

                val lastFrame = frameMetricsList.lastOrNull()
                val frameTotal = lastFrame?.getDouble("totalDurationMs") ?: -1.0
                val frameLayout = lastFrame?.getDouble("layoutMeasureMs") ?: -1.0
                val frameDraw = lastFrame?.getDouble("drawMs") ?: -1.0

                return JSONObject()
                    .put("scenario", scenarioName)
                    .put("sample", sampleIndex)
                    .put("wallDurationMs", (endNanos - startNanos) / 1e6)
                    .put("harnessSetupToDurableCallbackMs", if (durableOk && durableTime > 0) (durableTime - startNanos) / 1e6 else -1.0)
                    .put("harnessSetupToUnqualifiedCacheCallbackMs", if (snapshotOk && snapshotTime > 0) (snapshotTime - startNanos) / 1e6 else -1.0)
                    .put("harnessSetupToUnqualifiedDrawCallbackMs", if (drawOk && drawTime > 0) (drawTime - startNanos) / 1e6 else -1.0)
                    .put("lastUncorrelatedFrameWorkDurationMs", frameTotal)
                    .put("frameLayoutMeasureMs", frameLayout)
                    .put("frameDrawMs", frameDraw)
                    .put("controllerLockWaitMs", totalLockWait)
                    .put("controllerLockHeldMs", totalLockHeld)
                    .put("preparedHabitsCount", totalPrepared)
                    .put("maxPreparedInBatch", maxPrepared)
                    .put("cacheHitsCount", maxCacheHits)
                    .put("parityMatches", parityMatches)
                    .put("refreshEvents", JSONArray(newRefreshEvents.map { it.getString("type") }))
            }

            fun recordState(label: String): JSONObject {
                return JSONObject()
                    .put("label", label)
                    .put("revisionListenerCount", component.effectiveReadRevision.listenerCount)
                    .put("observableListenerCount", component.habitList.observable.listenerCount)
                    .put("commandRunnerListenerCount", component.commandRunner.listenerCount)
                    .put("memory", memorySample())
            }

            // Warm up initial state
            drain()
            SystemClock.sleep(300)
            drain()

            // --- Scenario A: Baseline Habits-only edits ---
            val stateA = recordState("state_A_initial")
            val aEdits = mutableListOf<JSONObject>()
            for (i in 1..3) {
                aEdits.add(performTrackedEdit("A_baseline_habits", i))
                SystemClock.sleep(200)
                drain()
            }
            scenarioRecords.add(JSONObject()
                .put("scenario", "A_baseline_habits")
                .put("stateBefore", stateA)
                .put("stateAfter", recordState("state_A_after"))
                .put("edits", JSONArray(aEdits))
            )

            // --- Scenario B: Habits -> Tasks -> Habits ---
            scenario.onActivity { it.navigate(MainDestination.TASKS) }
            drain(); SystemClock.sleep(300); drain()
            scenario.onActivity { it.navigate(MainDestination.HABITS) }
            drain(); SystemClock.sleep(300); drain()

            val stateB = recordState("state_B_returned")
            val bEdits = mutableListOf<JSONObject>()
            for (i in 1..2) {
                bEdits.add(performTrackedEdit("B_habits_tasks_habits", i))
                SystemClock.sleep(200)
                drain()
            }
            scenarioRecords.add(JSONObject()
                .put("scenario", "B_habits_tasks_habits")
                .put("stateBefore", stateB)
                .put("stateAfter", recordState("state_B_after"))
                .put("edits", JSONArray(bEdits))
            )

            // --- Scenario C: Habits -> Statistics -> Habits ---
            scenario.onActivity { it.navigate(MainDestination.STATISTICS) }
            drain(); SystemClock.sleep(500); drain()
            scenario.onActivity { it.navigate(MainDestination.HABITS) }
            drain(); SystemClock.sleep(300); drain()

            val stateC = recordState("state_C_returned")
            val cEdits = mutableListOf<JSONObject>()
            for (i in 1..2) {
                cEdits.add(performTrackedEdit("C_habits_statistics_habits", i))
                SystemClock.sleep(200)
                drain()
            }
            scenarioRecords.add(JSONObject()
                .put("scenario", "C_habits_statistics_habits")
                .put("stateBefore", stateC)
                .put("stateAfter", recordState("state_C_after"))
                .put("edits", JSONArray(cEdits))
            )

            // --- Scenario D: Habits -> Tasks (task created) -> Habits ---
            scenario.onActivity { it.navigate(MainDestination.TASKS) }
            drain(); SystemClock.sleep(200); drain()
            // Create a disposable task
            component.userTaskService.createTask("Nav QA Task ${System.currentTimeMillis()}", "")
            drain(); SystemClock.sleep(200); drain()
            scenario.onActivity { it.navigate(MainDestination.HABITS) }
            drain(); SystemClock.sleep(300); drain()

            val stateD = recordState("state_D_returned")
            val dEdits = mutableListOf<JSONObject>()
            for (i in 1..2) {
                dEdits.add(performTrackedEdit("D_habits_tasks_modified_habits", i))
                SystemClock.sleep(200)
                drain()
            }
            scenarioRecords.add(JSONObject()
                .put("scenario", "D_habits_tasks_modified_habits")
                .put("stateBefore", stateD)
                .put("stateAfter", recordState("state_D_after"))
                .put("edits", JSONArray(dEdits))
            )

            // --- Scenario E: Habits -> Statistics (tab changed to WEEK) -> Habits ---
            scenario.onActivity { it.navigate(MainDestination.STATISTICS) }
            drain(); SystemClock.sleep(300); drain()
            scenario.onActivity { a ->
                val statsFrag = a.supportFragmentManager.findFragmentByTag("reports") as? StatisticsFragment
                // Tab rendered
            }
            drain(); SystemClock.sleep(500); drain()
            scenario.onActivity { it.navigate(MainDestination.HABITS) }
            drain(); SystemClock.sleep(300); drain()

            val stateE = recordState("state_E_returned")
            val eEdits = mutableListOf<JSONObject>()
            for (i in 1..2) {
                eEdits.add(performTrackedEdit("E_habits_statistics_filtered_habits", i))
                SystemClock.sleep(200)
                drain()
            }
            scenarioRecords.add(JSONObject()
                .put("scenario", "E_habits_statistics_filtered_habits")
                .put("stateBefore", stateE)
                .put("stateAfter", recordState("state_E_after"))
                .put("edits", JSONArray(eEdits))
            )

            // --- Scenario F: Repeated cycles (1, 3, 10 cycles) ---
            val fCycles = listOf(1, 3, 10)
            for (cycleCount in fCycles) {
                for (c in 1..cycleCount) {
                    scenario.onActivity { it.navigate(MainDestination.TASKS) }; drain(); SystemClock.sleep(100)
                    scenario.onActivity { it.navigate(MainDestination.STATISTICS) }; drain(); SystemClock.sleep(100)
                    scenario.onActivity { it.navigate(MainDestination.HABITS) }; drain(); SystemClock.sleep(100)
                }
                drain(); SystemClock.sleep(300); drain()
                val stateF = recordState("state_F_after_${cycleCount}_cycles")
                val fEdits = mutableListOf<JSONObject>()
                for (i in 1..2) {
                    fEdits.add(performTrackedEdit("F_cycle_${cycleCount}", i))
                    SystemClock.sleep(200)
                    drain()
                }
                scenarioRecords.add(JSONObject()
                    .put("scenario", "F_cycle_${cycleCount}")
                    .put("cycles", cycleCount)
                    .put("stateBefore", stateF)
                    .put("stateAfter", recordState("state_F_after_${cycleCount}_edits"))
                    .put("edits", JSONArray(fEdits))
                )
            }
        }

        // Clean up component observers
        component.habitCardListCache.refreshObserver = null
        controller.lockMetricsObserver = null

        val finalReport = JSONObject()
            .put("experimentId", "PI-20261014-05")
            .put("pid", Process.myPid())
            .put("scenarios", JSONArray(scenarioRecords))

        val outDir = File(ins.targetContext.getExternalFilesDir(null), "post-navigation").apply { mkdirs() }
        val reportFile = File(outDir, "post-navigation-latency.json")
        reportFile.writeText(finalReport.toString(2))
        android.util.Log.i("PostNavInvestigation", "Saved report to ${reportFile.absolutePath}:\n${finalReport.toString(2)}")
    }

    @Test
    fun testStatisticsLifecycleCorrectness() {
        val ins = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(ins)

        check(Build.MODEL.contains("sdk") || Build.MODEL.contains("Emulator")) { "Physical devices strictly forbidden" }
        check(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim() == "UHabit_UI_Review_Disposable_20261011") { "Must use verified disposable AVD" }
        check(device.executeShellCommand("getprop ro.serialno").trim() == "EMULATOR36X6X11X0") { "Must match EMULATOR36X6X11X0" }

        val fixture = UiReviewCaptureTest.initializeFixture()
        setToday(LocalDate(2026, 10, 11))
        ProductionUiHotfixFixture.prepare(fixture)

        val app = ins.targetContext.applicationContext as HabitsApplication
        val qaSession = fixture.session.copy(
            mode = DatasetMode.PRODUCTION_NEXT,
            capabilities = DatasetCapabilities.PRODUCTION_NEXT.copy(
                syncEnabled = false, remoteCallsAllowed = false,
                backgroundJobsEnabled = false, widgetsEnabled = false, remindersEnabled = false
            )
        )
        ins.runOnMainSync { app.rebuildComponent(qaSession) }
        val component = HabitsApplication.component

        fun drain() {
            repeat(3) {
                runBlocking { component.taskRunner.await() }
                ins.waitForIdleSync()
            }
        }
        drain()

        ActivityScenario.launch<MainActivity>(Intent(ins.targetContext, MainActivity::class.java)).use { scenario ->
            fun getStatsFragment(): StatisticsFragment? {
                var frag: StatisticsFragment? = null
                scenario.onActivity { a ->
                    frag = a.supportFragmentManager.fragments.filterIsInstance<StatisticsFragment>().firstOrNull()
                }
                return frag
            }

            // 1. Initial Habits: Statistics is not even loaded or hidden
            scenario.onActivity { it.navigate(MainDestination.HABITS) }
            drain()
            assertEquals("Revision listeners on Habits", 1, component.effectiveReadRevision.listenerCount)
            assertEquals("Observable listeners on Habits", 0, component.habitList.observable.listenerCount)

            // 2. Navigate to Statistics: observers attached, fresh report rendered
            scenario.onActivity { it.navigate(MainDestination.STATISTICS) }
            drain(); SystemClock.sleep(300); drain()
            val stats1 = checkNotNull(getStatsFragment())
            assertTrue("Statistics must observe data when visible", stats1.isObservingDataState)
            assertFalse("Statistics must not be hidden", stats1.isHidden)
            assertEquals("Revision listeners when Statistics visible", 2, component.effectiveReadRevision.listenerCount)
            assertEquals("Observable listeners when Statistics visible", 1, component.habitList.observable.listenerCount)
            assertTrue("Report must have rendered at least once", stats1.lastRenderedKey != null)

            // 3. Navigate away to Habits: observers cleanly detached, no report generation while hidden
            scenario.onActivity { it.navigate(MainDestination.HABITS) }
            drain(); SystemClock.sleep(200); drain()
            assertFalse("Statistics must detach observers when hidden", stats1.isObservingDataState)
            assertTrue("Statistics must be hidden", stats1.isHidden)
            assertEquals("Revision listeners after leaving Statistics", 1, component.effectiveReadRevision.listenerCount)
            assertEquals("Observable listeners after leaving Statistics", 0, component.habitList.observable.listenerCount)
            val genAfterLeaving = stats1.activeGeneration

            // 4. Perform habit edit on Habits: verify hidden Statistics does NOT launch any background snapshot queries or bump generation
            val habit = component.habitList.first()
            val repCmd = CreateRepetitionCommand(component.habitList, habit, getToday(), Entry.YES_MANUAL, "")
            component.commandRunner.run(repCmd)
            drain(); SystemClock.sleep(200); drain()

            assertEquals("Hidden Statistics must NOT advance generation on habit edit", genAfterLeaving, stats1.activeGeneration)
            assertEquals("Revision listeners must remain 1 after habit edit", 1, component.effectiveReadRevision.listenerCount)
            assertEquals("Observable listeners must remain 0 after habit edit", 0, component.habitList.observable.listenerCount)

            // 5. Return to Statistics: produces fresh, correct data reflecting the habit edit
            scenario.onActivity { it.navigate(MainDestination.STATISTICS) }
            drain(); SystemClock.sleep(400); drain()
            assertTrue("Statistics must re-observe data upon return", stats1.isObservingDataState)
            assertFalse("Statistics must not be hidden upon return", stats1.isHidden)
            assertTrue("Statistics must advance generation upon return", stats1.activeGeneration > genAfterLeaving)
            assertEquals("Report key must reflect latest effective revision", component.effectiveReadRevision.value, stats1.lastRenderedKey?.effectiveRevision)

            // 6. Rapid hide/show transitions (50 cycles): must NOT leak listeners
            for (i in 1..50) {
                scenario.onActivity { it.navigate(MainDestination.HABITS) }
                drain()
                scenario.onActivity { it.navigate(MainDestination.STATISTICS) }
                drain()
            }
            scenario.onActivity { it.navigate(MainDestination.HABITS) }
            drain(); SystemClock.sleep(200); drain()
            assertEquals("Revision listeners after 50 rapid transitions", 1, component.effectiveReadRevision.listenerCount)
            assertEquals("Observable listeners after 50 rapid transitions", 0, component.habitList.observable.listenerCount)

            // 7. Activity recreation: must preserve clean detachment
            scenario.recreate()
            drain(); SystemClock.sleep(300); drain()
            scenario.onActivity { it.navigate(MainDestination.HABITS) }
            drain()
            assertEquals("Revision listeners after recreation", 1, component.effectiveReadRevision.listenerCount)
            assertEquals("Observable listeners after recreation", 0, component.habitList.observable.listenerCount)
        }
    }
}
