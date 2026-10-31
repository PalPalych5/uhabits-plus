/*
 * Copyright (C) 2016-2026 Álinson Santos Xavier <git@axavier.org>
 *
 * This file is part of Loop Habit Tracker.
 */

package org.isoron.uhabits.core.stateful

import kotlinx.coroutines.runBlocking
import org.isoron.platform.io.Database
import org.isoron.platform.io.JavaDatabaseOpener
import org.isoron.platform.io.PreparedStatement
import org.isoron.platform.io.StepResult
import org.isoron.platform.io.TestDatabaseHelper
import org.isoron.platform.io.migrateTo
import org.isoron.platform.io.setVersion
import org.isoron.platform.time.DayOfWeek
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.setToday
import org.isoron.uhabits.core.BaseUnitTest
import org.isoron.uhabits.core.LEGACY_DATABASE_VERSION
import org.isoron.uhabits.core.commands.CreateRepetitionCommand
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.containers.facade.HabitOrganizationFacadeImpl
import org.isoron.uhabits.core.containers.facade.OrganizationAuthorityMode
import org.isoron.uhabits.core.containers.sqlite.SQLiteHabitVisualIdentityStore
import org.isoron.uhabits.core.containers.sqlite.SQLiteOrganizationStore
import org.isoron.uhabits.core.contribution.sqlite.SQLiteContributionRepository
import org.isoron.uhabits.core.database.HabitBlockRepository
import org.isoron.uhabits.core.focus.sqlite.SQLiteFocusSessionRepository
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.NumericalHabitType
import org.isoron.uhabits.core.models.projection.EffectiveHabitReadSnapshot
import org.isoron.uhabits.core.models.projection.EffectiveHabitReadSnapshotProvider
import org.isoron.uhabits.core.models.projection.EffectiveReadCalendarContext
import org.isoron.uhabits.core.models.projection.EffectiveReadDatabase
import org.isoron.uhabits.core.models.projection.EffectiveReadRevision
import org.isoron.uhabits.core.models.sqlite.SQLModelFactory
import org.isoron.uhabits.core.models.sqlite.SQLiteHabitList
import org.isoron.uhabits.core.preferences.MemoryStorage
import org.isoron.uhabits.core.preferences.Preferences
import org.isoron.uhabits.core.statistics.StatisticsQueryContext
import org.isoron.uhabits.core.statistics.StatisticsReadIdentity
import org.isoron.uhabits.core.statistics.StatisticsSectionScope
import org.isoron.uhabits.core.statistics.UnifiedStatisticsService
import org.isoron.uhabits.core.tasks.Task
import org.isoron.uhabits.core.tasks.TaskRunner
import org.isoron.uhabits.core.ui.screens.habits.list.HabitCardListCache
import org.isoron.uhabits.core.ui.screens.habits.show.ShowHabitPresenter
import org.isoron.uhabits.core.ui.views.LightTheme
import org.isoron.uhabits.core.userTasks.sqlite.SQLiteUserTaskRepository
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Locale
import kotlin.system.measureNanoTime
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PerformanceAuditBenchmarkTest : BaseUnitTest() {

    private class CountingDatabase(private val delegate: Database) : Database by delegate {
        var selectQueryCount: Long = 0
        var writeStepCount: Long = 0
        var rowsReadCount: Long = 0
        val queriesByPrefix = mutableMapOf<String, Long>()

        fun resetCounters() {
            selectQueryCount = 0
            writeStepCount = 0
            rowsReadCount = 0
            queriesByPrefix.clear()
        }

        override fun prepareStatement(sql: String): PreparedStatement {
            val raw = delegate.prepareStatement(sql)
            val normalized = sql.trim().replace(Regex("\\s+"), " ")
            val isSelect = normalized.startsWith("SELECT", ignoreCase = true)
            val key = normalized.take(65)
            return object : PreparedStatement by raw {
                private var countedQueryForRun = false

                override fun reset() {
                    countedQueryForRun = false
                    raw.reset()
                }

                override fun step(): StepResult {
                    if (!countedQueryForRun) {
                        countedQueryForRun = true
                        if (isSelect) {
                            selectQueryCount++
                            queriesByPrefix[key] = (queriesByPrefix[key] ?: 0L) + 1L
                        } else {
                            writeStepCount++
                        }
                    }
                    val res = raw.step()
                    if (isSelect && res == StepResult.ROW) {
                        rowsReadCount++
                    }
                    return res
                }
            }
        }
    }

    private class SynchronousRunner : TaskRunner {
        override val activeTaskCount: Int = 0
        override fun execute(task: Task) {
            task.onAttached(this)
            task.onPreExecute()
            runBlocking { task.doInBackground() }
            task.onPostExecute()
        }
        override fun publishProgress(task: Task, progress: Int) {}
        override fun addListener(listener: TaskRunner.Listener) {}
        override fun removeListener(listener: TaskRunner.Listener) {}
        override suspend fun await() {}
    }

    private class IncrementalSnapshotCache(
        private val provider: EffectiveHabitReadSnapshotProvider,
        private val revision: EffectiveReadRevision,
        private val calendar: EffectiveReadCalendarContext
    ) {
        private var cachedRevision: Long = -1L
        private val cachedSnapshots = mutableMapOf<HabitRef, EffectiveHabitReadSnapshot>()
        var lastPreparedCount = 0
        var lastHitCount = 0

        fun snapshots(habits: List<Habit>, from: LocalDate, to: LocalDate): Map<HabitRef, EffectiveHabitReadSnapshot> {
            val currentRev = revision.observedValue
            if (cachedRevision != currentRev) {
                val affected = if (cachedRevision >= 0L) {
                    revision.affectedHabitsBetween(cachedRevision, currentRev)
                } else null
                if (affected != null) {
                    for (ref in affected) cachedSnapshots.remove(ref)
                    for (ref in cachedSnapshots.keys.toList()) {
                        val s = cachedSnapshots[ref]
                        if (s != null && s.revision != currentRev) {
                            cachedSnapshots[ref] = s.withRevision(currentRev)
                        }
                    }
                } else {
                    cachedSnapshots.clear()
                }
                cachedRevision = currentRev
            }
            val missing = habits.filter { h ->
                val ref = h.uuid?.let { HabitRef(it) } ?: return@filter false
                !cachedSnapshots.containsKey(ref)
            }
            lastPreparedCount = missing.size
            lastHitCount = habits.size - missing.size
            if (missing.isNotEmpty()) {
                val prepared = provider.prepare(missing, from, to, revision.datasetId, currentRev, calendar)
                cachedSnapshots.putAll(prepared)
            }
            val result = mutableMapOf<HabitRef, EffectiveHabitReadSnapshot>()
            for (h in habits) {
                val ref = h.uuid?.let { HabitRef(it) } ?: continue
                cachedSnapshots[ref]?.let { result[ref] = it }
            }
            return result
        }
    }

    private fun medianMs(samplesNs: List<Long>): Double {
        val sorted = samplesNs.sorted()
        val mid = sorted.size / 2
        val ns = if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
        return ns / 1_000_000.0
    }

    private fun minMaxMs(samplesNs: List<Long>): String {
        val sorted = samplesNs.sorted()
        return String.format(Locale.US, "%.2f..%.2f", sorted.first() / 1_000_000.0, sorted.last() / 1_000_000.0)
    }

    @Test
    fun runComprehensivePerformanceAuditBenchmark() = runBlocking {
        val profiles = listOf(
            DatasetProfile.SMALL,
            DatasetProfile.MEDIUM,
            DatasetProfile.LARGE,
            DatasetProfile.EXTREME
        )
        val anchorToday = LocalDate(2026, 10, 31)
        setToday(anchorToday)
        val calendar = EffectiveReadCalendarContext(
            timezoneId = "UTC",
            firstWeekday = DayOfWeek.MONDAY,
            hourOffset = 0,
            minuteOffset = 0
        )

        println("=== UHABIT NEXT PERFORMANCE AUDIT BENCHMARK ===")
        for (profile in profiles) {
            val tempDir = Files.createTempDirectory("uhabits_perf_audit_${profile.name}").toFile()
            try {
                val dbFile = File(tempDir, "audit_${profile.name}.db")
                val rawDb = JavaDatabaseOpener().open(dbFile.absolutePath)
                rawDb.setVersion(8)
                rawDb.migrateTo(LEGACY_DATABASE_VERSION) { v -> TestDatabaseHelper.loadMigrationSQL(v) }
                val revision = EffectiveReadRevision("audit-${profile.name}")
                val observedDb = EffectiveReadDatabase(rawDb, revision)
                val countingDb = CountingDatabase(observedDb)

                val config = DatasetConfig(profile = profile, baseDate = anchorToday)
                val rawDataset = SyntheticDatasetGenerator(config).generate(20261031L)
                val validMinHabitUuids = rawDataset.habits
                    .filter { it.isNumerical && it.unit == "min" }
                    .map { it.uuid }
                    .toSet()
                val dataset = rawDataset.copy(
                    contributions = rawDataset.contributions.filter { it.targetHabit.uuid in validMinHabitUuids }
                )
                val popFactory = SQLModelFactory(countingDb)
                dataset.populate(countingDb, popFactory)
                val totalEntries = dataset.habits.sumOf { it.entries.size }

                // Explain Query Plans on first profile
                if (profile == DatasetProfile.SMALL) {
                    val eqpQueries = listOf(
                        "SELECT timestamp, value, notes FROM Repetitions WHERE habit = 1 ORDER BY timestamp DESC",
                        "SELECT id, habit_id, uuid, effective_timestamp, freq_num, freq_den, target_type, target_value, unit, updated_at, deleted_at FROM HabitGoals WHERE habit_id = 1 AND deleted_at IS NULL ORDER BY effective_timestamp DESC",
                        "SELECT h.uuid, p.container_id FROM HabitPlacements p JOIN Habits h ON h.id = p.habit_id WHERE h.uuid = 'test'"
                    )
                    println("--- EXPLAIN QUERY PLAN ---")
                    for (q in eqpQueries) {
                        val stmt = rawDb.prepareStatement("EXPLAIN QUERY PLAN $q")
                        val details = mutableListOf<String>()
                        while (stmt.step() == StepResult.ROW) {
                            details.add(stmt.getText(3))
                        }
                        stmt.finalize()
                        println("EQP [$q] -> ${details.joinToString(" | ")}")
                    }
                }

                val repeats = if (profile == DatasetProfile.EXTREME) 3 else 5

                // 1. Cold Hydration (SQLiteHabitList init + loadRecords)
                val coldHydrationSamples = mutableListOf<Long>()
                var coldSelects = 0L
                var coldRows = 0L
                lateinit var modelFactory: SQLModelFactory
                lateinit var habitList: SQLiteHabitList
                repeat(repeats) {
                    countingDb.resetCounters()
                    val t = measureNanoTime {
                        modelFactory = SQLModelFactory(countingDb)
                        habitList = SQLiteHabitList(modelFactory)
                        assertTrue(habitList.size() == profile.habitCount)
                    }
                    coldHydrationSamples.add(t)
                    coldSelects = countingDb.selectQueryCount
                    coldRows = countingDb.rowsReadCount
                }

                val orgStore = SQLiteOrganizationStore(countingDb)
                val visualStore = SQLiteHabitVisualIdentityStore(countingDb)
                val facade = HabitOrganizationFacadeImpl(
                    mode = OrganizationAuthorityMode.CONTAINER_LOCAL,
                    containerQueries = orgStore,
                    habitPlacementQueries = orgStore,
                    organizationService = null,
                    visualIdentityStore = visualStore,
                    habitBlockRepository = HabitBlockRepository(countingDb)
                )
                habitList.organizationFacade = facade

                val contribRepo = SQLiteContributionRepository(countingDb)
                val snapshotProvider = EffectiveHabitReadSnapshotProvider(contribRepo)
                val snapshotCache = IncrementalSnapshotCache(snapshotProvider, revision, calendar)

                val allHabits = habitList.toList()
                val listFrom = anchorToday.minus(5)
                val listTo = anchorToday

                // 2. Main List Cold Snapshot + HabitCardListCache Full Refresh
                val runner = SynchronousRunner()
                val cardCache = HabitCardListCache(
                    allHabits = habitList,
                    commandRunner = commandRunner,
                    taskRunner = runner,
                    logging = dev.mokkery.mock()
                ).apply {
                    effectiveSnapshots = { habits -> snapshotCache.snapshots(habits, listFrom, listTo) }
                }
                cardCache.setCheckmarkCount(5)

                countingDb.resetCounters()
                val listColdTimeNs = measureNanoTime {
                    cardCache.refreshAllHabits()
                }
                val listColdSelects = countingDb.selectQueryCount
                val listColdRows = countingDb.rowsReadCount

                // 3. Main List Warm Full Refresh (when snapshots are already cached, e.g., 200ms post-edit refresh)
                val listWarmRefreshSamples = mutableListOf<Long>()
                var listWarmSelects = 0L
                var listWarmRows = 0L
                repeat(repeats) {
                    countingDb.resetCounters()
                    val t = measureNanoTime {
                        cardCache.refreshAllHabits()
                    }
                    listWarmRefreshSamples.add(t)
                    listWarmSelects = countingDb.selectQueryCount
                    listWarmRows = countingDb.rowsReadCount
                }

                // 4. Single Historical Entry Edit (-180 days) + Targeted Refresh + Delayed Full Refresh
                val targetHabit = allHabits.first()
                val editOffset = minOf(180, profile.daySpan - 1)
                val editDate = anchorToday.minus(editOffset)
                val singleEditSamples = mutableListOf<Long>()
                val singleEditComputeSamples = mutableListOf<Long>()
                val singleEditFullRefreshSamples = mutableListOf<Long>()
                var singleEditSelects = 0L
                var singleEditRows = 0L
                repeat(repeats) { iter ->
                    val curVal = targetHabit.originalEntries.get(editDate).value
                    val newVal = if (targetHabit.isNumerical) curVal + 1000 else if (curVal == Entry.YES_MANUAL) Entry.NO else Entry.YES_MANUAL
                    countingDb.resetCounters()
                    var sqliteIoNs = 0L
                    val tTargeted = measureNanoTime {
                        val cmd = CreateRepetitionCommand(habitList, targetHabit, editDate, newVal, "").apply {
                            timingObserver = { stage, ms ->
                                if (stage == "repetition.sqliteWrite" || stage == "repetition.syncQueue") {
                                    sqliteIoNs += (ms * 1_000_000.0).toLong()
                                }
                            }
                        }
                        cmd.run()
                        cardCache.refreshHabit(targetHabit.id!!)
                    }
                    val tDelayedFull = measureNanoTime {
                        cardCache.refreshAllHabits()
                    }
                    singleEditSamples.add(tTargeted)
                    singleEditComputeSamples.add((tTargeted - sqliteIoNs).coerceAtLeast(0L))
                    singleEditFullRefreshSamples.add(tDelayedFull)
                    singleEditSelects = countingDb.selectQueryCount
                    singleEditRows = countingDb.rowsReadCount
                }

                // 5. Habit Detail Screen State Build (ShowHabitPresenter.buildState)
                val numericalAtMostHabit = allHabits.firstOrNull {
                    it.isNumerical && it.targetType == NumericalHabitType.AT_MOST
                } ?: targetHabit
                val preferences = Preferences(MemoryStorage())
                val detailBuildSamples = mutableListOf<Long>()
                var detailSelects = 0L
                var detailRows = 0L
                repeat(repeats) {
                    countingDb.resetCounters()
                    val t = measureNanoTime {
                        val snap = snapshotProvider.prepare(
                            listOf(numericalAtMostHabit),
                            anchorToday.minus(profile.daySpan),
                            anchorToday,
                            revision.datasetId,
                            revision.observedValue,
                            calendar
                        )[HabitRef(numericalAtMostHabit.uuid!!)]
                        val state = ShowHabitPresenter.buildState(
                            habit = numericalAtMostHabit,
                            preferences = preferences,
                            theme = LightTheme(),
                            snapshot = snap
                        )
                        assertNotNull(state)
                    }
                    detailBuildSamples.add(t)
                    detailSelects = countingDb.selectQueryCount
                    detailRows = countingDb.rowsReadCount
                }

                // 6. Unified Statistics across ranges: DAY (0d), WEEK (6d), MONTH (29d), YEAR (364d), ALL (daySpan)
                val statsService = UnifiedStatisticsService(
                    habitList = habitList,
                    tasks = SQLiteUserTaskRepository(countingDb),
                    focus = SQLiteFocusSessionRepository(countingDb),
                    contributions = contribRepo,
                    placements = orgStore,
                    containers = orgStore
                )

                val rangeSpecs = listOf(
                    "DAY" to 0,
                    "WEEK" to 6,
                    "MONTH" to 29,
                    "YEAR" to 364,
                    "ALL" to profile.daySpan
                )
                val statsResults = mutableMapOf<String, Triple<Double, String, Pair<Long, Long>>>()
                for ((label, spanDays) in rangeSpecs) {
                    val samples = mutableListOf<Long>()
                    var sSelects = 0L
                    var sRows = 0L
                    val queryContext = StatisticsQueryContext(
                        from = anchorToday.minus(spanDays),
                        to = anchorToday,
                        timezoneId = "UTC",
                        firstWeekday = DayOfWeek.MONDAY,
                        datasetId = revision.datasetId,
                        revision = revision.observedValue,
                        now = anchorToday.unixTime,
                        sectionScope = StatisticsSectionScope.All
                    )
                    repeat(repeats) {
                        countingDb.resetCounters()
                        val t = measureNanoTime {
                            val res = statsService.read(
                                request = queryContext,
                                currentIdentity = { StatisticsReadIdentity(queryContext.datasetId, queryContext.revision) }
                            )
                            assertNotNull(res.habits)
                        }
                        samples.add(t)
                        sSelects = countingDb.selectQueryCount
                        sRows = countingDb.rowsReadCount
                    }
                    statsResults[label] = Triple(medianMs(samples), minMaxMs(samples), sSelects to sRows)
                }

                println(
                    String.format(
                        Locale.US,
                        "\n[PROFILE %s: %d habits, %d days, %d entries]\n" +
                            "  1. Cold Hydration      : median=%7.2f ms (%s) | SQL=%d, rows=%d\n" +
                            "  2. List Cold Refresh   : single=%7.2f ms | SQL=%d, rows=%d\n" +
                            "  3. List Warm Refresh   : median=%7.2f ms (%s) | SQL=%d, rows=%d\n" +
                            "  4. Single Edit (-180d) : compute=%6.2f ms (%s), targetedWithDiskIo=%6.2f ms + delayedFull=%6.2f ms (%s) | SQL=%d, rows=%d\n" +
                            "  5. Detail Screen Build : median=%7.2f ms (%s) | SQL=%d, rows=%d\n" +
                            "  6. Stats DAY (1d)      : median=%7.2f ms (%s) | SQL=%d, rows=%d\n" +
                            "  7. Stats WEEK (7d)     : median=%7.2f ms (%s) | SQL=%d, rows=%d\n" +
                            "  8. Stats MONTH (30d)   : median=%7.2f ms (%s) | SQL=%d, rows=%d\n" +
                            "  9. Stats YEAR (365d)   : median=%7.2f ms (%s) | SQL=%d, rows=%d\n" +
                            " 10. Stats ALL TIME      : median=%7.2f ms (%s) | SQL=%d, rows=%d",
                        profile.name, profile.habitCount, profile.daySpan, totalEntries,
                        medianMs(coldHydrationSamples), minMaxMs(coldHydrationSamples), coldSelects, coldRows,
                        listColdTimeNs / 1_000_000.0, listColdSelects, listColdRows,
                        medianMs(listWarmRefreshSamples), minMaxMs(listWarmRefreshSamples), listWarmSelects, listWarmRows,
                        medianMs(singleEditComputeSamples), minMaxMs(singleEditComputeSamples),
                        medianMs(singleEditSamples),
                        medianMs(singleEditFullRefreshSamples), minMaxMs(singleEditFullRefreshSamples),
                        singleEditSelects, singleEditRows,
                        medianMs(detailBuildSamples), minMaxMs(detailBuildSamples), detailSelects, detailRows,
                        statsResults["DAY"]!!.first, statsResults["DAY"]!!.second,
                        statsResults["DAY"]!!.third.first, statsResults["DAY"]!!.third.second,
                        statsResults["WEEK"]!!.first, statsResults["WEEK"]!!.second,
                        statsResults["WEEK"]!!.third.first, statsResults["WEEK"]!!.third.second,
                        statsResults["MONTH"]!!.first, statsResults["MONTH"]!!.second,
                        statsResults["MONTH"]!!.third.first, statsResults["MONTH"]!!.third.second,
                        statsResults["YEAR"]!!.first, statsResults["YEAR"]!!.second,
                        statsResults["YEAR"]!!.third.first, statsResults["YEAR"]!!.third.second,
                        statsResults["ALL"]!!.first, statsResults["ALL"]!!.second,
                        statsResults["ALL"]!!.third.first, statsResults["ALL"]!!.third.second
                    )
                )

                rawDb.close()
            } finally {
                tempDir.deleteRecursively()
            }
        }
    }
}
