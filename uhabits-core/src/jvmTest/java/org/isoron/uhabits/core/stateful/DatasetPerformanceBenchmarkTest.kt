/* Copyright (C) 2026 Álinson Santos Xavier and contributors. GPL-3.0-or-later. */
package org.isoron.uhabits.core.stateful

import kotlinx.coroutines.runBlocking
import org.isoron.platform.io.Database
import org.isoron.platform.io.JavaDatabaseOpener
import org.isoron.platform.io.TestDatabaseHelper
import org.isoron.platform.io.migrateTo
import org.isoron.platform.io.querySingle
import org.isoron.platform.io.setVersion
import org.isoron.platform.time.DayOfWeek
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.setToday
import org.isoron.uhabits.core.LEGACY_DATABASE_VERSION
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.containers.OrganizationRevision
import org.isoron.uhabits.core.containers.memory.MemoryOrganizationStore
import org.isoron.uhabits.core.containers.sqlite.SQLiteOrganizationStore
import org.isoron.uhabits.core.contribution.memory.MemoryContributionRepository
import org.isoron.uhabits.core.contribution.sqlite.SQLiteContributionRepository
import org.isoron.uhabits.core.focus.memory.MemoryFocusSessionRepository
import org.isoron.uhabits.core.focus.sqlite.SQLiteFocusSessionRepository
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.memory.MemoryModelFactory
import org.isoron.uhabits.core.models.sqlite.SQLModelFactory
import org.isoron.uhabits.core.statistics.StatisticsQueryContext
import org.isoron.uhabits.core.statistics.StatisticsReadIdentity
import org.isoron.uhabits.core.statistics.StatisticsSectionScope
import org.isoron.uhabits.core.statistics.UnifiedStatisticsService
import org.isoron.uhabits.core.userTasks.memory.MemoryUserTaskRepository
import org.isoron.uhabits.core.userTasks.sqlite.SQLiteUserTaskRepository
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DatasetPerformanceBenchmarkTest {

    private val baseDate = LocalDate(2026, 10, 31)
    private val tempDir = java.nio.file.Files.createTempDirectory("uhabit-benchmark-").toFile()
    private val reportFile = File(tempDir, "sprint03-jvm-perf-metrics.txt")

    enum class StatefulTestLevel(
        val seedsCount: Int,
        val opsPerSeed: Int,
        val checkInterval: Int,
    ) {
        STRESS(seedsCount = 50, opsPerSeed = 2000, checkInterval = 250),
    }

    data class ProfileBenchmarkResult(
        val profile: DatasetProfile,
        val habitCount: Int,
        val daySpan: Int,
        val totalEntries: Int,
        val bulkPopulateMs: Long,
        val dbSizeBytes: Long,
        val dbSizeKB: Double,
        val dbSizeMB: Double,
        val coldHydrationMs: Long,
        val goalRecomputeMs: Long,
        val unifiedStatsMs: Long,
        val historicalEditMs: Long,
        val sqliteQueryMs: Long,
        val heapDeltaMB: Double,
    )

    data class StressRunResult(
        val memorySeedsCount: Int,
        val opsPerSeed: Int,
        val memoryTotalOps: Int,
        val differentialSeedsCount: Int,
        val differentialTotalOps: Int,
        val totalOps: Int,
        val elapsedMs: Long,
        val invariantViolations: Int,
    )

    @Before
    fun setUp() {
        setToday(baseDate)
        if (!tempDir.exists()) tempDir.mkdirs()
        reportFile.parentFile?.mkdirs()
    }

    private suspend fun createDatabase(file: File?): Database {
        return if (file != null) {
            if (file.exists()) file.delete()
            val db = JavaDatabaseOpener().open(file.absolutePath)
            db.setVersion(8)
            db.migrateTo(LEGACY_DATABASE_VERSION) { v -> TestDatabaseHelper.loadMigrationSQL(v) }
            db
        } else {
            TestDatabaseHelper.createEmptyDatabase()
        }
    }

    private fun profileDataset(
        profile: DatasetProfile,
        seed: Long = 20261031L,
    ): ProfileBenchmarkResult = runBlocking {
        val config = DatasetConfig(profile = profile, baseDate = baseDate)
        val generator = SyntheticDatasetGenerator(config)
        val dataset = generator.generate(seed)

        val habitCount = dataset.habits.size
        val daySpan = profile.daySpan
        val totalEntries = dataset.habits.sumOf { it.entries.size }

        val dbFile = File(tempDir, "benchmark_${profile.name.lowercase()}.db")
        val db = createDatabase(dbFile)

        try {
            val sqlFactory = SQLModelFactory(db)

            // 1. Bulk population latency
            val tPopulateStart = System.currentTimeMillis()
            dataset.populate(db, sqlFactory)
            val bulkPopulateMs = System.currentTimeMillis() - tPopulateStart

            // 2. Database size via PRAGMA
            val pageCount = db.querySingle("PRAGMA page_count") { it.getLong(0) } ?: 0L
            val pageSize = db.querySingle("PRAGMA page_size") { it.getLong(0) } ?: 4096L
            val dbSizeBytes = pageCount * pageSize
            val dbSizeKB = dbSizeBytes / 1024.0
            val dbSizeMB = dbSizeBytes / (1024.0 * 1024.0)

            // 3. Cold hydration latency
            val tHydrateStart = System.currentTimeMillis()
            val hydratedFactory = SQLModelFactory(db)
            val habitList = hydratedFactory.buildHabitList()
            val habits = habitList.toList()
            val orgStore = SQLiteOrganizationStore(db)
            val placements = orgStore.getAllHabitPlacements()
            val coldHydrationMs = System.currentTimeMillis() - tHydrateStart
            assertEquals(habitCount, habits.size)
            assertEquals(dataset.placements.size, placements.size)

            // 4. Quantitative goal recomputation latency
            val numericalHabits = habits.filter { it.isNumerical }
            val tRecomputeStart = System.currentTimeMillis()
            for (h in numericalHabits) {
                h.recompute()
            }
            val goalRecomputeMs = System.currentTimeMillis() - tRecomputeStart

            // 5. Unified Statistics recomputation latency
            val statsWindowDays = minOf(30, daySpan - 1)
            val tStatsStart = System.currentTimeMillis()
            val queryContext = StatisticsQueryContext(
                from = baseDate.minus(statsWindowDays),
                to = baseDate,
                timezoneId = "UTC",
                firstWeekday = DayOfWeek.MONDAY,
                datasetId = "synthetic-dataset-uuid",
                revision = 1L,
                now = baseDate.unixTime,
                sectionScope = StatisticsSectionScope.All,
            )
            val statsService = UnifiedStatisticsService(
                habitList = habitList,
                tasks = SQLiteUserTaskRepository(db),
                focus = SQLiteFocusSessionRepository(db),
                contributions = SQLiteContributionRepository(db),
                placements = orgStore,
                containers = orgStore,
            )
            val report = statsService.read(
                request = queryContext,
                currentIdentity = { StatisticsReadIdentity(queryContext.datasetId, queryContext.revision) },
            )
            val unifiedStatsMs = System.currentTimeMillis() - tStatsStart
            assertNotNull(report.habits, "Unified statistics habits report should not be null")
            assertNotNull(report.tasks, "Unified statistics tasks report should not be null")
            assertNotNull(report.focus, "Unified statistics focus report should not be null")
            assertTrue(report.readIssues.isEmpty(), "Unified statistics must have 0 read issues")

            // 6. Historical entry edit latency
            val editHabit = numericalHabits.firstOrNull() ?: habits.first()
            val targetOffset = minOf(180, daySpan - 1)
            val targetDate = baseDate.minus(targetOffset)
            val tEditStart = System.currentTimeMillis()
            editHabit.originalEntries.add(Entry(targetDate, 65_000))
            editHabit.recompute()
            val historicalEditMs = System.currentTimeMillis() - tEditStart

            // 7. SQLite query latency
            val tQueryStart = System.currentTimeMillis()
            val recentThreshold = baseDate.minus(minOf(30, daySpan - 1)).unixTime
            db.querySingle("SELECT count(*) FROM Repetitions WHERE timestamp >= $recentThreshold") { it.getLong(0) }
            db.querySingle("SELECT count(*) FROM HabitGoals") { it.getLong(0) }
            db.querySingle("SELECT current_revision FROM OrganizationState WHERE id = 1") { it.getLong(0) }
            db.querySingle("SELECT count(*) FROM Containers") { it.getLong(0) }
            db.querySingle("SELECT count(*) FROM HabitPlacements") { it.getLong(0) }
            val sqliteQueryMs = System.currentTimeMillis() - tQueryStart

            // 8. Heap memory delta before vs after hydrating memory model
            System.gc()
            Thread.sleep(30)
            System.gc()
            val memBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()

            val memFactory = MemoryModelFactory()
            val inMemoryHabits = dataset.habits.map { hData ->
                hData.toHabit(memFactory).apply {
                    for ((date, value) in hData.entries) {
                        originalEntries.add(Entry(date, value))
                    }
                    recompute()
                }
            }
            assertEquals(habitCount, inMemoryHabits.size)

            val memAfter = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
            val heapDeltaMB = maxOf(0.0, (memAfter - memBefore) / (1024.0 * 1024.0))

            // Non-catastrophic bounds assertion (zero crashes / zero OOM)
            assertTrue(bulkPopulateMs < 120_000L, "Bulk populate exceeded catastrophic bound: ${bulkPopulateMs}ms")
            assertTrue(coldHydrationMs < 30_000L, "Cold hydration exceeded catastrophic bound: ${coldHydrationMs}ms")
            assertTrue(goalRecomputeMs < 30_000L, "Goal recompute exceeded catastrophic bound: ${goalRecomputeMs}ms")
            assertTrue(unifiedStatsMs < 30_000L, "Unified stats exceeded catastrophic bound: ${unifiedStatsMs}ms")
            assertTrue(historicalEditMs < 5_000L, "Historical edit exceeded catastrophic bound: ${historicalEditMs}ms")
            assertTrue(sqliteQueryMs < 5_000L, "Query latency exceeded catastrophic bound: ${sqliteQueryMs}ms")

            ProfileBenchmarkResult(
                profile = profile,
                habitCount = habitCount,
                daySpan = daySpan,
                totalEntries = totalEntries,
                bulkPopulateMs = bulkPopulateMs,
                dbSizeBytes = dbSizeBytes,
                dbSizeKB = dbSizeKB,
                dbSizeMB = dbSizeMB,
                coldHydrationMs = coldHydrationMs,
                goalRecomputeMs = goalRecomputeMs,
                unifiedStatsMs = unifiedStatsMs,
                historicalEditMs = historicalEditMs,
                sqliteQueryMs = sqliteQueryMs,
                heapDeltaMB = heapDeltaMB,
            )
        } finally {
            db.close()
            if (dbFile.exists()) dbFile.delete()
        }
    }

    private fun executeStressHarness(level: StatefulTestLevel = StatefulTestLevel.STRESS): StressRunResult {
        val tStressStart = System.currentTimeMillis()
        var totalOpsExecuted = 0
        var invariantViolations = 0

        // 1. 50 seeds x 2000 ops in MemoryStatefulEnv (100,000 ops)
        for (seedIdx in 0 until level.seedsCount) {
            val seed = 1000L + seedIdx
            val generator = StatefulSequenceGenerator(seed)
            val ops = generator.generate(count = level.opsPerSeed, baseDate = baseDate)

            val memEnv = MemoryStatefulEnv(baseDate)
            try {
                for ((opIndex, op) in ops.withIndex()) {
                    val memBefore = if ((opIndex + 1) % level.checkInterval == 0 || opIndex == ops.lastIndex) {
                        memEnv.getAllHabits().associate { it.name to captureHabitSnapshot(it) }
                    } else emptyMap()

                    memEnv.apply(op)
                    totalOpsExecuted++

                    val isCheckPoint = (opIndex + 1) % level.checkInterval == 0 || opIndex == ops.lastIndex
                    if (isCheckPoint) {
                        try {
                            verifyMemoryInvariants(
                                stepIndex = opIndex,
                                seed = seed,
                                op = op,
                                memEnv = memEnv,
                                memBefore = memBefore,
                            )
                        } catch (t: Throwable) {
                            invariantViolations++
                            throw t
                        }
                    }
                }
            } finally {
                memEnv.close()
            }
        }

        val memoryTotalOps = totalOpsExecuted
        assertEquals(100_000, memoryTotalOps, "STRESS level MemoryStatefulEnv must execute exactly 100,000 ops")

        // 2. Bounded multi-seed differential MemoryStatefulEnv vs SQLiteStatefulEnv (3 seeds x 2000 ops = 6,000 ops)
        val diffSeedsCount = 3
        var diffOpsExecuted = 0
        for (seedIdx in 0 until diffSeedsCount) {
            val seed = 2000L + seedIdx
            val generator = StatefulSequenceGenerator(seed)
            val ops = generator.generate(count = level.opsPerSeed, baseDate = baseDate)

            val memEnv = MemoryStatefulEnv(baseDate)
            val sqlEnv = SQLiteStatefulEnv(baseDate)

            try {
                for ((opIndex, op) in ops.withIndex()) {
                    val memBefore = if ((opIndex + 1) % level.checkInterval == 0 || opIndex == ops.lastIndex) {
                        memEnv.getAllHabits().associate { it.name to captureHabitSnapshot(it) }
                    } else emptyMap()

                    memEnv.apply(op)
                    sqlEnv.apply(op)
                    diffOpsExecuted++
                    totalOpsExecuted++

                    val isCheckPoint = (opIndex + 1) % level.checkInterval == 0 || opIndex == ops.lastIndex
                    if (isCheckPoint) {
                        try {
                            verifyDifferentialInvariants(
                                stepIndex = opIndex,
                                seed = seed,
                                op = op,
                                memEnv = memEnv,
                                sqlEnv = sqlEnv,
                                memBefore = memBefore,
                            )
                        } catch (t: Throwable) {
                            invariantViolations++
                            throw t
                        }
                    }
                }
            } finally {
                memEnv.close()
                sqlEnv.close()
            }
        }

        val elapsedMs = System.currentTimeMillis() - tStressStart
        assertEquals(0, invariantViolations, "STRESS level must complete with exactly 0 invariant violations")

        return StressRunResult(
            memorySeedsCount = level.seedsCount,
            opsPerSeed = level.opsPerSeed,
            memoryTotalOps = memoryTotalOps,
            differentialSeedsCount = diffSeedsCount,
            differentialTotalOps = diffOpsExecuted,
            totalOps = totalOpsExecuted,
            elapsedMs = elapsedMs,
            invariantViolations = invariantViolations,
        )
    }

    private fun verifyMemoryInvariants(
        stepIndex: Int,
        seed: Long,
        op: StatefulHabitOp,
        memEnv: StatefulEnv,
        memBefore: Map<String, HabitSnapshot>,
    ) {
        val msg = "Memory Seed $seed Op $stepIndex (${op::class.simpleName} on ${op.label}):"

        // Invariant 1: RAW
        when (op) {
            is StatefulHabitOp.CreateHabit -> {
                val h = memEnv.getHabit(op.label)
                assertNotNull(h, "$msg Habit not found")
                assertEquals(op.targetValue, h.targetValue, "$msg Target value mismatch")
                assertEquals(op.frequency, h.frequency, "$msg Frequency mismatch")
            }
            is StatefulHabitOp.AddBinaryCheckmark -> {
                val h = memEnv.getHabit(op.label)
                if (h != null) assertEquals(Entry.YES_MANUAL, h.originalEntries.get(op.date).value, "$msg Checkmark mismatch")
            }
            is StatefulHabitOp.AddNumericalEntry -> {
                val h = memEnv.getHabit(op.label)
                if (h != null) assertEquals(op.value, h.originalEntries.get(op.date).value, "$msg Entry mismatch")
            }
            is StatefulHabitOp.EditEntryValue -> {
                val h = memEnv.getHabit(op.label)
                if (h != null) assertEquals(op.newValue, h.originalEntries.get(op.date).value, "$msg Edited entry mismatch")
            }
            is StatefulHabitOp.DeleteEntry -> {
                val h = memEnv.getHabit(op.label)
                if (h != null) assertEquals(Entry.UNKNOWN, h.originalEntries.get(op.date).value, "$msg Delete entry mismatch")
            }
            is StatefulHabitOp.ArchiveAndUnarchive -> {
                val h = memEnv.getHabit(op.label)
                if (h != null) assertEquals(op.archive, h.isArchived, "$msg Archive state mismatch")
            }
            is StatefulHabitOp.Delete -> {
                assertNull(memEnv.getHabit(op.label), "$msg Delete failed")
            }
            else -> {}
        }

        // Invariant 2: Isolation
        if (memBefore.isNotEmpty() && op.label !in listOf("ALL", "GLOBAL_RESTART", "GLOBAL_BACKUP_RESTORE", "GLOBAL_SETTINGS")) {
            for ((name, before) in memBefore) {
                if (name != op.label && name != (op as? StatefulHabitOp.ReorderHabits)?.targetLabel) {
                    val after = memEnv.getHabit(name)
                    if (after != null) {
                        val afterSnap = captureHabitSnapshot(after)
                        assertEquals(before.name, afterSnap.name, "$msg Isolation name violated on $name")
                        assertEquals(before.entries, afterSnap.entries, "$msg Isolation entries violated on $name")
                        assertEquals(before.targetValue, afterSnap.targetValue, "$msg Isolation target value violated on $name")
                    }
                }
            }
        }

        // Invariant 3: Idempotency
        for (h in memEnv.getAllHabits()) {
            val s1 = h.scores.get(baseDate).value
            val st1 = h.streaks.getLatest()?.length ?: 0
            h.recompute()
            val s2 = h.scores.get(baseDate).value
            val st2 = h.streaks.getLatest()?.length ?: 0
            assertEquals(s1, s2, "$msg Idempotency score mismatch on ${h.name}")
            assertEquals(st1, st2, "$msg Idempotency streak mismatch on ${h.name}")
        }

        // Invariant 6: Strictly monotonic goal history
        for (h in memEnv.getAllHabits()) {
            val goals = h.normalizedGoalHistory()
            for (i in 0 until goals.size - 1) {
                assertTrue(
                    goals[i].effectiveDate.isOlderThan(goals[i + 1].effectiveDate),
                    "$msg Goal history must be strictly monotonic for ${h.name}"
                )
            }
        }
    }

    private fun verifyDifferentialInvariants(
        stepIndex: Int,
        seed: Long,
        op: StatefulHabitOp,
        memEnv: StatefulEnv,
        sqlEnv: StatefulEnv,
        memBefore: Map<String, HabitSnapshot>,
    ) {
        verifyMemoryInvariants(stepIndex, seed, op, memEnv, memBefore)

        val msg = "Diff Seed $seed Op $stepIndex (${op::class.simpleName} on ${op.label}):"
        val memHabits = memEnv.getAllHabits().associateBy { it.name }
        val sqlHabits = sqlEnv.getAllHabits().associateBy { it.name }
        assertEquals(memHabits.keys, sqlHabits.keys, "$msg Habit keys mismatch between Memory and SQLite")

        for (name in memHabits.keys) {
            val m = memHabits[name]!!
            val s = sqlHabits[name]!!

            assertEquals(m.name, s.name, "$msg Differential name mismatch")
            assertEquals(m.isNumerical, s.isNumerical, "$msg Differential isNumerical mismatch")
            assertEquals(m.targetType, s.targetType, "$msg Differential targetType mismatch on $name")
            assertEquals(m.targetValue, s.targetValue, "$msg Differential targetValue mismatch on $name")
            assertEquals(m.frequency, s.frequency, "$msg Differential frequency mismatch on $name")
            assertEquals(m.unit, s.unit, "$msg Differential unit mismatch on $name")
            assertEquals(m.isArchived, s.isArchived, "$msg Differential isArchived mismatch on $name")

            val mEntries = m.originalEntries.getKnown().filter { it.value != Entry.UNKNOWN }.associate { it.date to it.value }
            val sEntries = s.originalEntries.getKnown().filter { it.value != Entry.UNKNOWN }.associate { it.date to it.value }
            assertEquals(mEntries, sEntries, "$msg Differential entries mismatch on $name")

            assertEquals(m.isCompletedOn(baseDate), s.isCompletedOn(baseDate), "$msg isCompletedOn mismatch on $name")
            assertEquals(m.scores.get(baseDate).value, s.scores.get(baseDate).value, "$msg Score mismatch on $name")
            assertEquals(m.streaks.getLatest()?.length, s.streaks.getLatest()?.length, "$msg Streak mismatch on $name")
        }
    }

    private fun writeMetricsReport(
        profileResults: List<ProfileBenchmarkResult>,
        stressResult: StressRunResult,
    ) {
        val sb = StringBuilder()
        sb.appendLine("# Sprint 03 JVM & SQLite Performance Benchmark Report")
        sb.appendLine()
        sb.appendLine("## Phase E: JVM/SQLite Performance & Scale Profiling")
        sb.appendLine()
        sb.appendLine("| Profile | Habits | Days | Total Entries | Bulk Populate (ms) | DB Size (KB / MB) | Cold Hydration (ms) | Goal Recompute (ms) | Unified Stats (ms) | Hist. Edit (ms) | Query Latency (ms) | Heap Delta (MB) |")
        sb.appendLine("|---|---|---|---|---|---|---|---|---|---|---|---|")
        for (r in profileResults) {
            val sizeStr = if (r.dbSizeMB >= 1.0) "%.2f MB".format(r.dbSizeMB) else "%.1f KB".format(r.dbSizeKB)
            val heapStr = "%.2f MB".format(r.heapDeltaMB)
            sb.appendLine(
                "| ${r.profile.name} | ${r.habitCount} | ${r.daySpan} | ${r.totalEntries} | ${r.bulkPopulateMs} | $sizeStr | ${r.coldHydrationMs} | ${r.goalRecomputeMs} | ${r.unifiedStatsMs} | ${r.historicalEditMs} | ${r.sqliteQueryMs} | $heapStr |"
            )
        }
        sb.appendLine()
        sb.appendLine("## STRESS Stateful Run Metrics")
        sb.appendLine()
        sb.appendLine("- Seeds tested (Memory STRESS): ${stressResult.memorySeedsCount} seeds (1000..${1000 + stressResult.memorySeedsCount - 1})")
        sb.appendLine("- Operations per seed: ${stressResult.opsPerSeed} operations")
        sb.appendLine("- Memory STRESS total operations: ${stressResult.memoryTotalOps} operations")
        sb.appendLine("- Differential seeds tested: ${stressResult.differentialSeedsCount} seeds (2000..${2000 + stressResult.differentialSeedsCount - 1}) × ${stressResult.opsPerSeed} operations = ${stressResult.differentialTotalOps} operations")
        sb.appendLine("- Total operations executed: ${stressResult.totalOps} operations")
        sb.appendLine("- Elapsed time: ${stressResult.elapsedMs} ms")
        sb.appendLine("- Invariant violations: ${stressResult.invariantViolations}")
        sb.appendLine("- Failures / Crashes / OOM: 0")
        sb.appendLine()
        sb.appendLine("Status: SUCCESS - All 4 synthetic dataset scale profiles and STRESS 106,000 operations validated cleanly.")

        reportFile.writeText(sb.toString())
    }

    @Test
    fun testAllProfilesAndStressRun() {
        val results = mutableListOf<ProfileBenchmarkResult>()

        for (profile in DatasetProfile.values()) {
            val res = profileDataset(profile)
            results.add(res)
        }

        val stressRes = executeStressHarness(StatefulTestLevel.STRESS)
        writeMetricsReport(results, stressRes)

        assertTrue(reportFile.exists(), "Benchmark report must be written to disk")
        assertTrue(reportFile.length() > 0, "Benchmark report must not be empty")
    }
}
