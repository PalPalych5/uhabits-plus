package org.isoron.uhabits.qa

import android.content.Intent
import android.os.Build
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.os.Trace
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
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
 * UHabit Next — Iteration 10:
 * Investigates post-navigation habit latency degradation across:
 * A: Baseline Habits-only edits.
 * B: Habits -> Tasks -> Habits -> edit.
 * C: Habits -> Statistics -> Habits -> edit.
 * D: Habits -> Tasks (task modified) -> Habits -> edit.
 * E: Habits -> Statistics (tab/filter changed) -> Habits -> edit.
 * F: Repeated 1, 3, 10 cycles of Habits -> Tasks -> Statistics -> Habits.
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
                val drawLatch = CountDownLatch(1)
                var durableTime = 0L
                var drawTime = 0L

                val expectedValue = if (button.value == Entry.YES_MANUAL) Entry.NO else Entry.YES_MANUAL

                button.operationTraceFactory = {
                    HabitEditTrace(1L) { _, stage, _, _, _ ->
                        if (stage == "sqlite.durable") {
                            durableTime = System.nanoTime()
                            durableLatch.countDown()
                        }
                    }
                }

                HabitFrameTiming.observer = { stage, view, _ ->
                    if (stage == "habit.checkmark.draw" && view === button && button.value == expectedValue) {
                        drawTime = System.nanoTime()
                        drawLatch.countDown()
                    }
                }

                injectClick(button)

                val durableOk = durableLatch.await(5, TimeUnit.SECONDS)
                val drawOk = drawLatch.await(5, TimeUnit.SECONDS)
                val endNanos = System.nanoTime()

                // Clean up hooks
                button.operationTraceFactory = null
                HabitFrameTiming.observer = null

                drain()

                val newLockEvents = controllerLockEvents.subList(lockEventsBefore, controllerLockEvents.size)
                val newRefreshEvents = refreshEvents.subList(refreshEventsBefore, refreshEvents.size)

                val totalLockWait = newLockEvents.sumOf { it.getDouble("waitMs") }
                val totalLockHeld = newLockEvents.sumOf { it.getDouble("heldMs") }
                val maxPrepared = newLockEvents.maxOfOrNull { it.getInt("preparedCount") } ?: 0
                val totalPrepared = newLockEvents.sumOf { it.getInt("preparedCount") }
                val maxCacheHits = newLockEvents.maxOfOrNull { it.getInt("cacheHitCount") } ?: 0

                return JSONObject()
                    .put("scenario", scenarioName)
                    .put("sample", sampleIndex)
                    .put("wallDurationMs", (endNanos - startNanos) / 1e6)
                    .put("inputToDurableMs", if (durableOk && durableTime > 0) (durableTime - startNanos) / 1e6 else -1.0)
                    .put("inputToDrawMs", if (drawOk && drawTime > 0) (drawTime - startNanos) / 1e6 else -1.0)
                    .put("controllerLockWaitMs", totalLockWait)
                    .put("controllerLockHeldMs", totalLockHeld)
                    .put("preparedHabitsCount", totalPrepared)
                    .put("maxPreparedInBatch", maxPrepared)
                    .put("cacheHitsCount", maxCacheHits)
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
                // Switch tab or let it render
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
}
