package org.isoron.uhabits.qa

import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.runBlocking
import org.isoron.platform.io.queryLong
import org.isoron.platform.io.queryString
import org.isoron.platform.io.run
import org.isoron.platform.time.*
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.core.commands.CreateRepetitionCommand
import org.isoron.uhabits.core.commands.HabitEditTrace
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.containers.session.DatasetCapabilities
import org.isoron.uhabits.core.containers.session.DatasetMode
import org.isoron.uhabits.core.models.*
import org.isoron.uhabits.core.models.projection.EffectiveHabitReadSnapshot
import org.isoron.uhabits.core.models.projection.SnapshotPreparationMetrics
import org.isoron.uhabits.focus.EffectiveHabitReadController
import org.isoron.uhabits.inject.HabitsApplicationComponent
import org.isoron.uhabits.utils.DemoDataGenerator
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections
import kotlin.time.DurationUnit
import kotlin.time.TimeSource

/**
 * Iteration 3 Historical Scaling Investigation:
 * Evaluates whether historical entry depth dominates effective snapshot computation,
 * detached copying, score and streak computation, and lock-held duration,
 * even when the UI requests a fixed 365-day interval.
 */
@RunWith(AndroidJUnit4::class)
class HistoricalScalingInvestigationTest {

    private data class BenchmarkArm(val name: String, val depthDays: Int)

    private fun percentile(sorted: List<Double>, p: Double): Double {
        if (sorted.isEmpty()) return 0.0
        val idx = ((sorted.size - 1) * p).toInt().coerceIn(0, sorted.size - 1)
        return sorted[idx]
    }

    @Test
    fun investigateHistoricalScaling() {
        val ins = InstrumentationRegistry.getInstrumentation()
        val fixture = UiReviewCaptureTest.initializeFixture()

        // 1. Preflight environment & safety checks
        check(Build.MODEL.contains("sdk") || Build.MODEL.contains("Emulator")) { "Physical devices strictly forbidden" }
        check(InstrumentationRegistry.getArguments().getString("disposableAvd") == "UHabit_UI_Review_Disposable_20261011") {
            "Verified disposable AVD token required"
        }
        val avd = UiDevice.getInstance(ins).executeShellCommand("getprop ro.boot.qemu.avd_name").trim()
        check(avd == "UHabit_UI_Review_Disposable_20261011") { "AVD mismatch: $avd" }
        val serial = UiDevice.getInstance(ins).executeShellCommand("getprop ro.serialno").trim()
        check(serial == "EMULATOR36X6X11X0") { "Serial mismatch: $serial" }
        val airplane = UiDevice.getInstance(ins).executeShellCommand("settings get global airplane_mode_on").trim()
        check(airplane == "1") { "Offline QA environment required: airplane_mode_on was $airplane" }
        val marker = File(ins.targetContext.filesDir, "${DemoDataGenerator.DISPOSABLE_MARKER}-${fixture.session.datasetId}")
        check(marker.exists() && marker.readText() == fixture.session.databaseFilename) { "Disposable marker mismatch" }
        check(fixture.db.queryLong("PRAGMA user_version") == 30L) { "Schema must be v30" }

        // 2. Set QA Session mode to PRODUCTION_NEXT with strictly disabled side effects
        val app = ins.targetContext.applicationContext as HabitsApplication
        val qaSession = fixture.session.copy(
            mode = DatasetMode.PRODUCTION_NEXT,
            capabilities = DatasetCapabilities.PRODUCTION_NEXT.copy(
                syncEnabled = false,
                remoteCallsAllowed = false,
                backgroundJobsEnabled = false,
                widgetsEnabled = false,
                remindersEnabled = false,
            )
        )
        ins.runOnMainSync { app.rebuildComponent(qaSession) }
        val component = HabitsApplication.component
        check(!component.session.capabilities.syncEnabled && !component.session.capabilities.remoteCallsAllowed)

        fun drain() {
            repeat(5) {
                runBlocking { component.taskRunner.await() }
                ins.waitForIdleSync()
            }
        }
        drain()

        // 3. Set fixed reference date (2026-10-11)
        val referenceDate = LocalDate(2026, 10, 11)
        setToday(referenceDate)

        // 4. Build or retrieve the 10 controlled benchmark habits
        val defaultBlockId = component.habitList.getByPosition(0).blockId
        val habits = (0 until 10).map { index ->
            val name = "Bench $index"
            val existing = component.habitList.toList().firstOrNull { it.name == name }
            if (existing != null) existing else {
                val h = component.sqlModelFactory.buildHabit().apply {
                    this.name = name
                    this.blockId = defaultBlockId
                }
                component.habitList.add(h)
                h
            }
        }

        // Configure habits: 5 Boolean, 5 Numerical, 2 historical goal changes, 2 Focus contributions
        habits[0].apply { type = HabitType.YES_NO; frequency = Frequency(1, 1); targetType = NumericalHabitType.AT_LEAST; targetValue = 1.0; unit = ""; goalHistory = mutableListOf() }
        habits[1].apply { type = HabitType.YES_NO; frequency = Frequency(3, 7); targetType = NumericalHabitType.AT_LEAST; targetValue = 1.0; unit = ""; goalHistory = mutableListOf() }
        habits[2].apply { type = HabitType.YES_NO; frequency = Frequency(5, 7); targetType = NumericalHabitType.AT_LEAST; targetValue = 1.0; unit = ""; goalHistory = mutableListOf() }
        habits[3].apply { type = HabitType.YES_NO; frequency = Frequency(20, 30); targetType = NumericalHabitType.AT_LEAST; targetValue = 1.0; unit = ""; goalHistory = mutableListOf() }
        habits[4].apply {
            type = HabitType.YES_NO; frequency = Frequency(1, 1); targetType = NumericalHabitType.AT_LEAST; targetValue = 1.0; unit = ""
            goalHistory = mutableListOf(
                HabitGoal(referenceDate.minus(365), Frequency(2, 7), NumericalHabitType.AT_LEAST, 1.0, ""),
                HabitGoal(referenceDate.minus(180), Frequency(1, 1), NumericalHabitType.AT_LEAST, 1.0, "")
            )
        }
        habits[5].apply { type = HabitType.NUMERICAL; frequency = Frequency(1, 1); targetType = NumericalHabitType.AT_LEAST; targetValue = 2000.0; unit = "ml"; goalHistory = mutableListOf() }
        habits[6].apply { type = HabitType.NUMERICAL; frequency = Frequency(1, 1); targetType = NumericalHabitType.AT_LEAST; targetValue = 10000.0; unit = "steps"; goalHistory = mutableListOf() }
        habits[7].apply {
            type = HabitType.NUMERICAL; frequency = Frequency(1, 7); targetType = NumericalHabitType.AT_LEAST; targetValue = 35000.0; unit = "steps"
            goalHistory = mutableListOf(
                HabitGoal(referenceDate.minus(365), Frequency(1, 7), NumericalHabitType.AT_LEAST, 28000.0, "steps"),
                HabitGoal(referenceDate.minus(180), Frequency(1, 7), NumericalHabitType.AT_LEAST, 35000.0, "steps")
            )
        }
        habits[8].apply { type = HabitType.NUMERICAL; frequency = Frequency(1, 1); targetType = NumericalHabitType.AT_LEAST; targetValue = 30.0; unit = "min"; goalHistory = mutableListOf() }
        habits[9].apply { type = HabitType.NUMERICAL; frequency = Frequency(1, 1); targetType = NumericalHabitType.AT_LEAST; targetValue = 60.0; unit = "min"; goalHistory = mutableListOf() }

        habits.forEach { component.habitList.update(it) }
        drain()

        val habitIds = habits.mapNotNull { it.id }
        val habitUuids = habits.mapNotNull { it.uuid }
        check(habitIds.size == 10 && habitUuids.size == 10)

        // 5. Define benchmark arms
        val arms = listOf(
            BenchmarkArm("Arm A (Recent-90d)", 90),
            BenchmarkArm("Arm B (1Year-365d)", 365),
            BenchmarkArm("Arm C (3Years-1095d)", 1095),
            BenchmarkArm("Arm D (5Years-1825d)", 1825),
        )

        val resultsArray = JSONArray()
        val controller = requireNotNull(component.effectiveHabitReads)

        for (arm in arms) {
            val armJson = JSONObject().put("arm", arm.name).put("depthDays", arm.depthDays)

            // Step A: Clean and populate historical entries for this arm
            component.db.run("DELETE FROM Repetitions WHERE habit IN (${habitIds.joinToString(",")})")
            component.db.run("DELETE FROM Contributions WHERE target_habit_uuid IN (${habitUuids.joinToString(",") { "'$it'" }})")

            val insertStarted = TimeSource.Monotonic.markNow()
            component.db.beginTransaction()
            try {
                val repStmt = component.db.prepareStatement("INSERT OR REPLACE INTO Repetitions (habit, timestamp, value) VALUES (?, ?, ?)")
                val contribStmt = component.db.prepareStatement(
                    "INSERT OR REPLACE INTO Contributions (id, target_habit_uuid, source_type, source_id, contributed_value, effective_timestamp, created_at, updated_at) VALUES (?, ?, 'FOCUS_SESSION', ?, ?, ?, ?, ?)"
                )

                val cal = controller.currentCalendar
                for (d in 0..arm.depthDays) {
                    val date = referenceDate.minus(d)
                    val unix = date.unixTime
                    val tsMillis = startOfLocalDay(date, cal.hourOffset, cal.minuteOffset) + 12 * 3600000L

                    // Habit 0: Daily Boolean
                    repStmt.bindLong(1, habits[0].id!!); repStmt.bindLong(2, unix); repStmt.bindLong(3, Entry.YES_MANUAL.toLong())
                    repStmt.step(); repStmt.reset()

                    // Habit 1: 3x weekly
                    repStmt.bindLong(1, habits[1].id!!); repStmt.bindLong(2, unix)
                    repStmt.bindLong(3, (if (d % 7 in listOf(1, 3, 5)) Entry.YES_MANUAL else 0).toLong())
                    repStmt.step(); repStmt.reset()

                    // Habit 2: 5x weekly
                    repStmt.bindLong(1, habits[2].id!!); repStmt.bindLong(2, unix)
                    repStmt.bindLong(3, (if (d % 7 < 5) Entry.YES_MANUAL else 0).toLong())
                    repStmt.step(); repStmt.reset()

                    // Habit 3: Monthly (20/30)
                    repStmt.bindLong(1, habits[3].id!!); repStmt.bindLong(2, unix)
                    repStmt.bindLong(3, (if (d % 30 < 20) Entry.YES_MANUAL else 0).toLong())
                    repStmt.step(); repStmt.reset()

                    // Habit 4: Goal-changed Boolean
                    repStmt.bindLong(1, habits[4].id!!); repStmt.bindLong(2, unix); repStmt.bindLong(3, Entry.YES_MANUAL.toLong())
                    repStmt.step(); repStmt.reset()

                    // Habit 5: Water 2000 ml
                    repStmt.bindLong(1, habits[5].id!!); repStmt.bindLong(2, unix); repStmt.bindLong(3, 2000000L)
                    repStmt.step(); repStmt.reset()

                    // Habit 6: Steps 10000
                    repStmt.bindLong(1, habits[6].id!!); repStmt.bindLong(2, unix); repStmt.bindLong(3, 10000000L)
                    repStmt.step(); repStmt.reset()

                    // Habit 7: Goal-changed weekly steps
                    repStmt.bindLong(1, habits[7].id!!); repStmt.bindLong(2, unix); repStmt.bindLong(3, 5000000L)
                    repStmt.step(); repStmt.reset()

                    // Habit 8: Focus Reading 30min (manual 0 + Focus Contribution)
                    repStmt.bindLong(1, habits[8].id!!); repStmt.bindLong(2, unix); repStmt.bindLong(3, 0L)
                    repStmt.step(); repStmt.reset()

                    val c8Id = "contrib-8-${habits[8].uuid}-$d"
                    contribStmt.bindText(1, c8Id); contribStmt.bindText(2, habits[8].uuid!!); contribStmt.bindText(3, "sess-8-$d")
                    contribStmt.bindLong(4, 30000L); contribStmt.bindLong(5, tsMillis); contribStmt.bindLong(6, tsMillis); contribStmt.bindLong(7, tsMillis)
                    contribStmt.step(); contribStmt.reset()

                    // Habit 9: Focus Coding 60min (manual 0 + Focus Contribution)
                    repStmt.bindLong(1, habits[9].id!!); repStmt.bindLong(2, unix); repStmt.bindLong(3, 0L)
                    repStmt.step(); repStmt.reset()

                    val c9Id = "contrib-9-${habits[9].uuid}-$d"
                    contribStmt.bindText(1, c9Id); contribStmt.bindText(2, habits[9].uuid!!); contribStmt.bindText(3, "sess-9-$d")
                    contribStmt.bindLong(4, 60000L); contribStmt.bindLong(5, tsMillis); contribStmt.bindLong(6, tsMillis); contribStmt.bindLong(7, tsMillis)
                    contribStmt.step(); contribStmt.reset()
                }
                component.db.setTransactionSuccessful()
            } finally {
                component.db.endTransaction()
            }
            val insertTimeMs = insertStarted.elapsedNow().toDouble(DurationUnit.MILLISECONDS)
            armJson.put("fixtureInsertionMs", insertTimeMs)

            val totalReps = component.db.queryLong("SELECT COUNT(*) FROM Repetitions WHERE habit IN (${habitIds.joinToString(",")})")
            val totalContribs = component.db.queryLong("SELECT COUNT(*) FROM Contributions WHERE target_habit_uuid IN (${habitUuids.joinToString(",") { "'$it'" }})")
            armJson.put("storedRepetitionsCount", totalReps)
            armJson.put("storedContributionsCount", totalContribs)

            habits.forEach {
                it.originalEntries.invalidate()
                it.computedEntries.invalidate()
                it.recompute()
            }

            // Step B: Measure Cold vs Warm original-entry loading
            habits.forEach { it.originalEntries.invalidate() }
            val coldStart = TimeSource.Monotonic.markNow()
            var coldLoadedCount = 0
            habits.forEach { coldLoadedCount += it.originalEntries.getKnown().size }
            val coldDurationMs = coldStart.elapsedNow().toDouble(DurationUnit.MILLISECONDS)

            val warmStart = TimeSource.Monotonic.markNow()
            var warmLoadedCount = 0
            habits.forEach { warmLoadedCount += it.originalEntries.getKnown().size }
            val warmDurationMs = warmStart.elapsedNow().toDouble(DurationUnit.MILLISECONDS)

            armJson.put("entryLoading", JSONObject()
                .put("coldDurationMs", coldDurationMs)
                .put("warmDurationMs", warmDurationMs)
                .put("totalLoadedEntries", coldLoadedCount))

            // Step C: Measure Targeted snapshot (Habit 0, un-cached)
            var targetedMetrics: SnapshotPreparationMetrics? = null
            controller.snapshotMetricsObserver = { targetedMetrics = it }
            val targetedStart = TimeSource.Monotonic.markNow()
            val targetedSnapshots = controller.provider.prepare(
                listOf(habits[0]), referenceDate.minus(365), referenceDate,
                component.effectiveReadRevision.datasetId, component.effectiveReadRevision.value,
                controller.currentCalendar
            )
            val targetedTotalMs = targetedStart.elapsedNow().toDouble(DurationUnit.MILLISECONDS)

            var targetedLockMetrics: EffectiveHabitReadController.ControllerLockMetrics? = null
            controller.lockMetricsObserver = { targetedLockMetrics = it }
            val tTrace = listOf(HabitEditTrace(100L) { _, _, _, _, _ -> })
            controller.snapshotsTraced(listOf(habits[0]), tTrace, 100L, 100L)

            fun formatPrep(p: SnapshotPreparationMetrics?) = if (p == null) JSONObject() else JSONObject()
                .put("habitCount", p.habitCount)
                .put("requestedDays", p.requestedDays)
                .put("processedDays", p.processedDays)
                .put("loadFrom", p.loadFrom.toString())
                .put("loadTo", p.loadTo.toString())
                .put("copyDetachedMs", p.copyDetachedDurationMs)
                .put("contributionsFetchMs", p.contributionsFetchDurationMs)
                .put("domainDiscoveryMs", p.domainDiscoveryDurationMs)
                .put("daysForRangeMs", p.daysForRangeDurationMs)
                .put("snapshotInitMs", p.snapshotInitDurationMs)
                .put("scoresAndStreaksMs", p.scoresAndStreaksDurationMs)
                .put("totalPrepareMs", p.totalPrepareDurationMs)
                .put("copiedOriginalEntries", p.copiedOriginalEntriesCount)
                .put("copiedComputedEntries", p.copiedComputedEntriesCount)
                .put("materializedContributions", p.materializedContributionCount)

            fun formatLock(l: EffectiveHabitReadController.ControllerLockMetrics?) = if (l == null) JSONObject() else JSONObject()
                .put("lockWaitMs", l.lockWaitDurationMs)
                .put("lockHeldMs", l.lockHeldDurationMs)
                .put("cacheHits", l.cacheHitCount)
                .put("preparedCount", l.preparedCount)

            armJson.put("targetedSnapshot", JSONObject()
                .put("directPrepareDurationMs", targetedTotalMs)
                .put("prepMetrics", formatPrep(targetedMetrics))
                .put("controllerLock", formatLock(targetedLockMetrics)))

            // Step D: Measure Full-list snapshot (all 10 habits, un-cached)
            var fullMetrics: SnapshotPreparationMetrics? = null
            controller.snapshotMetricsObserver = { fullMetrics = it }
            val fullStart = TimeSource.Monotonic.markNow()
            val fullSnapshots = controller.provider.prepare(
                habits, referenceDate.minus(365), referenceDate,
                component.effectiveReadRevision.datasetId, component.effectiveReadRevision.value,
                controller.currentCalendar
            )
            val fullTotalMs = fullStart.elapsedNow().toDouble(DurationUnit.MILLISECONDS)

            var fullLockMetrics: EffectiveHabitReadController.ControllerLockMetrics? = null
            controller.lockMetricsObserver = { fullLockMetrics = it }
            val fTrace = listOf(HabitEditTrace(200L) { _, _, _, _, _ -> })
            controller.snapshotsTraced(habits, fTrace, 200L, 200L)

            armJson.put("fullSnapshot", JSONObject()
                .put("directPrepareDurationMs", fullTotalMs)
                .put("prepMetrics", formatPrep(fullMetrics))
                .put("controllerLock", formatLock(fullLockMetrics)))

            // Step E: Measure Normal edit latency (Boolean on Habit 0, Numerical on Habit 5)
            // Boolean edit
            val boolEditStart = TimeSource.Monotonic.markNow()
            val boolCmd = CreateRepetitionCommand(component.habitList, habits[0], referenceDate, Entry.YES_MANUAL, "")
            ins.runOnMainSync { component.commandRunner.run(boolCmd) }
            drain()
            val boolEditDurationMs = boolEditStart.elapsedNow().toDouble(DurationUnit.MILLISECONDS)

            // Numerical edit
            val numEditStart = TimeSource.Monotonic.markNow()
            val numCmd = CreateRepetitionCommand(component.habitList, habits[5], referenceDate, 2000000, "")
            ins.runOnMainSync { component.commandRunner.run(numCmd) }
            drain()
            val numEditDurationMs = numEditStart.elapsedNow().toDouble(DurationUnit.MILLISECONDS)

            armJson.put("editLatencies", JSONObject()
                .put("booleanCommandAndPublishMs", boolEditDurationMs)
                .put("numericalCommandAndPublishMs", numEditDurationMs))

            // Step F: Semantic Parity Verification
            val parityJson = JSONObject()
            val s0 = fullSnapshots.getValue(HabitRef(habits[0].uuid!!))
            val s1 = fullSnapshots.getValue(HabitRef(habits[1].uuid!!))
            val s4 = fullSnapshots.getValue(HabitRef(habits[4].uuid!!))
            val s5 = fullSnapshots.getValue(HabitRef(habits[5].uuid!!))
            val s7 = fullSnapshots.getValue(HabitRef(habits[7].uuid!!))
            val s8 = fullSnapshots.getValue(HabitRef(habits[8].uuid!!))
            val s9 = fullSnapshots.getValue(HabitRef(habits[9].uuid!!))

            assertEquals(Entry.YES_MANUAL, s0.day(referenceDate).manualEntry.value)
            assertTrue(s0.compatibilityCompletionOn(referenceDate).isCompleted)
            assertNotNull(s0.scoreResult.on(referenceDate))
            assertNotNull(s0.streakResult.streaks)

            assertEquals(1, habits[4].goalAt(referenceDate).frequency.denominator)
            assertEquals(7, habits[4].goalAt(referenceDate.minus(181)).frequency.denominator)

            assertEquals(2000000, s5.day(referenceDate).manualEntry.value)
            assertTrue(s5.compatibilityCompletionOn(referenceDate).isCompleted)

            assertEquals(35000.0, habits[7].goalAt(referenceDate).targetValue, 0.01)
            assertEquals(28000.0, habits[7].goalAt(referenceDate.minus(181)).targetValue, 0.01)

            assertTrue(s8.hasContributions)
            assertEquals(30000L, s8.day(referenceDate).totalValue)
            assertTrue(s8.compatibilityCompletionOn(referenceDate).isCompleted)

            assertTrue(s9.hasContributions)
            assertEquals(60000L, s9.day(referenceDate).totalValue)
            assertTrue(s9.compatibilityCompletionOn(referenceDate).isCompleted)

            parityJson.put("booleanCompletion", true)
            parityJson.put("numericalValues", true)
            parityJson.put("historicalGoals", true)
            parityJson.put("focusContributions", true)
            parityJson.put("scoresAndStreaks", true)
            armJson.put("semanticParity", parityJson)

            // Step G: Tracing overhead comparison
            val observerInvocations = mutableListOf<Double>()
            controller.snapshotMetricsObserver = { m ->
                val t0 = System.nanoTime()
                // trace observer processing
                val dummy = m.processedDays + m.habitCount
                observerInvocations.add((System.nanoTime() - t0) / 1e6)
            }
            val tWithTrace = TimeSource.Monotonic.markNow()
            controller.provider.prepare(habits, referenceDate.minus(365), referenceDate,
                component.effectiveReadRevision.datasetId, component.effectiveReadRevision.value, controller.currentCalendar)
            val durationWithTrace = tWithTrace.elapsedNow().toDouble(DurationUnit.MILLISECONDS)

            controller.snapshotMetricsObserver = null
            val tWithoutTrace = TimeSource.Monotonic.markNow()
            controller.provider.prepare(habits, referenceDate.minus(365), referenceDate,
                component.effectiveReadRevision.datasetId, component.effectiveReadRevision.value, controller.currentCalendar)
            val durationWithoutTrace = tWithoutTrace.elapsedNow().toDouble(DurationUnit.MILLISECONDS)

            val sortedCosts = observerInvocations.sorted()
            armJson.put("tracingOverhead", JSONObject()
                .put("durationWithTraceMs", durationWithTrace)
                .put("durationWithoutTraceMs", durationWithoutTrace)
                .put("netDifferenceMs", durationWithTrace - durationWithoutTrace)
                .put("observerInvocationCount", observerInvocations.size)
                .put("observerP50Ms", percentile(sortedCosts, 0.50))
                .put("observerP95Ms", percentile(sortedCosts, 0.95))
                .put("observerMaxMs", sortedCosts.maxOrNull() ?: 0.0))

            resultsArray.put(armJson)
        }

        // 6. Save structured results to file on device
        val report = JSONObject()
            .put("experimentId", "PI-20261013-03")
            .put("timestamp", System.currentTimeMillis())
            .put("avd", avd)
            .put("serial", serial)
            .put("schemaVersion", 30)
            .put("referenceDate", referenceDate.toString())
            .put("habitCount", 10)
            .put("arms", resultsArray)

        val outDir = File(ins.targetContext.getExternalFilesDir(null), "historical-contention")
        outDir.mkdirs()
        val outFile = File(outDir, "results.json")
        outFile.writeText(report.toString(2))
        println("HISTORICAL_CONTENTION_RESULTS_PATH: ${outFile.absolutePath}")
    }
}
