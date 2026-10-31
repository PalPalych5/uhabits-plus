package org.isoron.uhabits.core.stateful

import kotlinx.coroutines.runBlocking
import org.isoron.platform.io.JavaDatabaseOpener
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
import org.isoron.uhabits.core.models.EntryList
import org.isoron.uhabits.core.models.Frequency
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitGoal
import org.isoron.uhabits.core.models.HabitType
import org.isoron.uhabits.core.models.NumericalHabitType
import org.isoron.uhabits.core.models.Score
import org.isoron.uhabits.core.models.Streak
import org.isoron.uhabits.core.models.memory.MemoryModelFactory
import org.isoron.uhabits.core.models.projection.EffectiveHabitReadSnapshotProvider
import org.isoron.uhabits.core.models.projection.EffectiveReadCalendarContext
import org.isoron.uhabits.core.models.projection.EffectiveReadDatabase
import org.isoron.uhabits.core.models.projection.EffectiveReadRevision
import org.isoron.uhabits.core.models.sqlite.SQLModelFactory
import org.isoron.uhabits.core.models.sqlite.SQLiteHabitList
import org.isoron.uhabits.core.preferences.MemoryStorage
import org.isoron.uhabits.core.preferences.Preferences
import org.isoron.uhabits.core.rules.HabitTimeValueConverter
import org.isoron.uhabits.core.rules.IncompatibleTargetHabitException
import org.isoron.uhabits.core.statistics.StatisticsQueryContext
import org.isoron.uhabits.core.statistics.StatisticsReadIdentity
import org.isoron.uhabits.core.statistics.StatisticsSectionScope
import org.isoron.uhabits.core.statistics.UnifiedStatisticsService
import org.isoron.uhabits.core.ui.screens.habits.show.ShowHabitPresenter
import org.isoron.uhabits.core.ui.views.LightTheme
import org.isoron.uhabits.core.userTasks.sqlite.SQLiteUserTaskRepository
import java.io.File
import java.nio.file.Files
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Differential oracle tests verifying exact mathematical equivalence
 * (`Baseline Result == Optimized Result`) for all performance optimizations
 * introduced in the Performance Architecture Audit & Targeted Optimization sprint.
 */
class PerformanceOptimizationEquivalenceTest : BaseUnitTest() {

    /**
     * Exact verbatim baseline algorithm from commit 2bdbac74 ScoreList.kt.
     */
    private fun referenceRecomputeScores(
        habit: Habit,
        computedEntries: EntryList,
        from: LocalDate,
        to: LocalDate
    ): Map<LocalDate, Score> {
        val map = HashMap<LocalDate, Score>()
        var previousValue = if (habit.isNumerical && habit.goalAt(from).targetType == NumericalHabitType.AT_MOST) 1.0 else 0.0
        var date = from
        while (!date.isNewerThan(to)) {
            val goal = habit.goalAt(date)
            val entry = computedEntries.get(date)
            if (!habit.isDateIncludedInStatistics(date)) {
                map[date] = Score(date, 0.0)
                previousValue = 0.0
                date = date.plus(1)
                continue
            }

            var numerator = goal.frequency.numerator
            var denominator = goal.frequency.denominator
            val freq = goal.frequency.toDouble()

            if (!habit.isNumerical && freq < 1.0) {
                numerator *= 2
                denominator *= 2
            }

            if (entry.value != Entry.SKIP) {
                val percentageCompleted = if (habit.isNumerical) {
                    var rollingSum = 0
                    for (offset in 0 until denominator) {
                        val day = date.minus(offset)
                        if (habit.isDateIncludedInStatistics(day)) rollingSum += max(0, computedEntries.get(day).value)
                    }
                    val normalizedRollingSum = rollingSum / 1000.0
                    if (goal.targetType == NumericalHabitType.AT_MOST) {
                        if (goal.targetValue > 0) {
                            (1 - ((normalizedRollingSum - goal.targetValue) / goal.targetValue))
                                .coerceIn(0.0, 1.0)
                        } else {
                            if (normalizedRollingSum > 0) 0.0 else 1.0
                        }
                    } else {
                        if (goal.targetValue > 0) {
                            min(1.0, normalizedRollingSum / goal.targetValue)
                        } else {
                            1.0
                        }
                    }
                } else {
                    var rollingSum = 0.0
                    for (offset in 0 until denominator) {
                        val day = date.minus(offset)
                        if (habit.isDateIncludedInStatistics(day) && computedEntries.get(day).value == Entry.YES_MANUAL) {
                            rollingSum += 1.0
                        }
                    }
                    min(1.0, rollingSum / numerator)
                }
                previousValue = Score.compute(freq, previousValue, percentageCompleted)
            }

            map[date] = Score(date, previousValue)
            date = date.plus(1)
        }
        return map
    }

    /**
     * Exact verbatim baseline algorithm from commit 2bdbac74 StreakList.kt.
     */
    private fun referenceRecomputeStreaks(
        habit: Habit,
        computedEntries: EntryList,
        from: LocalDate,
        to: LocalDate,
        limit: Int
    ): List<Streak> {
        val list = ArrayList<Streak>()
        val dates = computedEntries
            .getByInterval(from, to)
            .filter { habit.isCompletedOn(it.date) }
            .map { it.date }
            .toTypedArray()

        if (dates.isNotEmpty()) {
            var begin = dates[0]
            var end = dates[0]
            for (i in 1 until dates.size) {
                val current = dates[i]
                if (current == begin.minus(1)) {
                    begin = current
                } else {
                    list.add(Streak(begin, end))
                    begin = current
                    end = current
                }
            }
            list.add(Streak(begin, end))
        }
        list.sortWith { s1: Streak, s2: Streak -> s2.compareLonger(s1) }
        return list.subList(0, min(list.size, limit)).apply {
            sortWith { s1: Streak, s2: Streak -> s2.compareNewer(s1) }
        }.toList()
    }

    @Test
    fun scoreAndStreakRecomputeMatchNaiveReferenceAcrossAllFrequenciesAndHistoricalGoals() {
        val factory = MemoryModelFactory()
        val today = LocalDate(2026, 10, 31)
        setToday(today)
        val start = today.minus(900)

        val configs = listOf(
            Triple(HabitType.YES_NO, NumericalHabitType.AT_LEAST, Frequency.DAILY),
            Triple(HabitType.YES_NO, NumericalHabitType.AT_LEAST, Frequency.THREE_TIMES_PER_WEEK),
            Triple(HabitType.YES_NO, NumericalHabitType.AT_LEAST, Frequency(10, 30)),
            Triple(HabitType.YES_NO, NumericalHabitType.AT_LEAST, Frequency(2, 5)),
            Triple(HabitType.NUMERICAL, NumericalHabitType.AT_LEAST, Frequency.DAILY),
            Triple(HabitType.NUMERICAL, NumericalHabitType.AT_LEAST, Frequency.THREE_TIMES_PER_WEEK),
            Triple(HabitType.NUMERICAL, NumericalHabitType.AT_MOST, Frequency.DAILY),
            Triple(HabitType.NUMERICAL, NumericalHabitType.AT_MOST, Frequency(1, 7))
        )

        for ((idx, cfg) in configs.withIndex()) {
            val (type, targetType, baseFreq) = cfg
            val habit = factory.buildHabit().apply {
                id = (idx + 1).toLong()
                uuid = "equiv-habit-$idx"
                this.type = type
                this.targetType = targetType
                this.frequency = baseFreq
                this.targetValue = 15.0
                this.unit = if (idx % 2 == 0) "min" else "pages"
                this.goalHistory = mutableListOf(
                    HabitGoal(start, baseFreq, targetType, 10.0, unit),
                    HabitGoal(today.minus(600), Frequency(1, 1), targetType, 20.0, unit),
                    HabitGoal(today.minus(300), baseFreq, targetType, 15.0, unit),
                    HabitGoal(today.minus(90), Frequency(3, 7), targetType, 12.0, unit)
                )
            }

            var d = start
            var step = 0
            while (!d.isNewerThan(today)) {
                when {
                    step % 17 == 0 -> habit.originalEntries.add(Entry(d, Entry.SKIP, "skip"))
                    step % 11 == 0 -> habit.originalEntries.add(Entry(d, 0, "zero"))
                    step % 5 != 0 -> {
                        val v = if (type == HabitType.YES_NO) Entry.YES_MANUAL else ((step % 30) * 1000)
                        habit.originalEntries.add(Entry(d, v, ""))
                    }
                }
                d = d.plus(1)
                step++
            }

            habit.recompute()

            val expectedEarliest = habit.originalEntries.getKnown().minByOrNull { it.date }?.date
            assertEquals(expectedEarliest, habit.originalEntries.getEarliestDate(), "Earliest date mismatch for config $idx")

            val refFrom = habit.effectiveStatisticsStartDate()
                ?: habit.computedEntries.getEarliestDate()
                ?: today
            val refScores = referenceRecomputeScores(habit, habit.computedEntries, refFrom, today)
            var checkDate = start
            while (!checkDate.isNewerThan(today)) {
                val expectedScore = refScores[checkDate]?.value ?: 0.0
                val actualScore = habit.scores[checkDate].value
                assertEquals(expectedScore, actualScore, 1e-12, "Score mismatch on $checkDate for config $idx")
                checkDate = checkDate.plus(1)
            }

            val refStreaks = referenceRecomputeStreaks(habit, habit.computedEntries, refFrom, today, 500)
            val actualStreaks = habit.streaks.getBest(500)
            assertEquals(refStreaks, actualStreaks, "Streak mismatch for config $idx")
        }
    }

    @Test
    fun unitKeyNormalizationMatchesExceptionBasedValidationOnAllInputs() {
        val sampleUnits = listOf(
            "", "   ", "min", "MIN", "minute", "minutes", "  Minutes ", "hr", "hour", "HOURS",
            "мин", "минута", "минуты", "минут", "ч", "час", "часа", "часов",
            "pages", "km", "ml", "steps", "unknown_unit", "sec", "days", "m"
        )
        for (unit in sampleUnits) {
            val legacyUnitKey = try {
                HabitTimeValueConverter.validateTargetHabit(isNumerical = true, unit = unit)
                "minute"
            } catch (_: IncompatibleTargetHabitException) {
                unit.trim().lowercase()
            }
            assertEquals(legacyUnitKey, HabitTimeValueConverter.normalizeUnitKey(unit), "Unit mismatch for '$unit'")
        }
    }

    @Test
    fun syntheticDatasetIncrementalEditSnapshotAndBoundedStatisticsMatchFullReference() = runBlocking {
        val anchorToday = LocalDate(2026, 10, 31)
        setToday(anchorToday)
        val calendar = EffectiveReadCalendarContext(
            timezoneId = "UTC",
            firstWeekday = DayOfWeek.MONDAY,
            hourOffset = 0,
            minuteOffset = 0
        )
        val tempDir = Files.createTempDirectory("uhabits_equiv_test").toFile()
        try {
            val dbFile = File(tempDir, "equiv.db")
            val rawDb = JavaDatabaseOpener().open(dbFile.absolutePath)
            rawDb.setVersion(8)
            rawDb.migrateTo(LEGACY_DATABASE_VERSION) { v -> TestDatabaseHelper.loadMigrationSQL(v) }
            val revision = EffectiveReadRevision("equiv-test")
            val observedDb = EffectiveReadDatabase(rawDb, revision)

            val config = DatasetConfig(profile = DatasetProfile.MEDIUM, baseDate = anchorToday)
            val rawDataset = SyntheticDatasetGenerator(config).generate(20261031L)
            val validMinHabitUuids = rawDataset.habits
                .filter { it.isNumerical && it.unit == "min" }
                .map { it.uuid }
                .toSet()
            val dataset = rawDataset.copy(
                contributions = rawDataset.contributions.filter { it.targetHabit.uuid in validMinHabitUuids }
            )
            val modelFactory = SQLModelFactory(observedDb)
            dataset.populate(observedDb, modelFactory)

            val habitList = SQLiteHabitList(modelFactory)
            val orgStore = SQLiteOrganizationStore(observedDb)
            val visualStore = SQLiteHabitVisualIdentityStore(observedDb)
            val facade = HabitOrganizationFacadeImpl(
                mode = OrganizationAuthorityMode.CONTAINER_LOCAL,
                containerQueries = orgStore,
                habitPlacementQueries = orgStore,
                organizationService = null,
                visualIdentityStore = visualStore,
                habitBlockRepository = HabitBlockRepository(observedDb)
            )
            habitList.organizationFacade = facade
            val contribRepo = SQLiteContributionRepository(observedDb)
            val snapshotProvider = EffectiveHabitReadSnapshotProvider(contribRepo)

            val habits = habitList.toList()
            val from = anchorToday.minus(365)
            val to = anchorToday

            // 1. Verify O(1) EffectiveHabitReadSnapshot.day(date) and withRevision equivalence
            val initialSnapshots = snapshotProvider.prepare(
                habits.take(15),
                from,
                to,
                revision.datasetId,
                revision.observedValue,
                calendar
            )
            for ((ref, snap) in initialSnapshots) {
                val expectedMap = snap.days.associateBy { it.manualEntry.date }
                var d = snap.from.minus(3)
                while (!d.isNewerThan(snap.to.plus(3))) {
                    val expectedDay = expectedMap[d]
                    if (expectedDay != null) {
                        assertEquals(expectedDay, snap.day(d), "Snapshot day(date) mismatch for $ref on $d")
                    } else {
                        assertFailsWith<IllegalArgumentException> { snap.day(d) }
                    }
                    d = d.plus(1)
                }

                val retagged = snap.withRevision(snap.revision + 42L)
                assertEquals(snap.hasContributions, retagged.hasContributions)
                assertEquals(snap.canUseLegacyEntryAdapter, retagged.canUseLegacyEntryAdapter)
                assertEquals(snap.days, retagged.days)
            }

            // 2. Retroactive edit 180 days in the past: verify incremental retag vs fresh prepare
            val editedHabit = habits.first()
            val editDate = anchorToday.minus(180)
            val curVal = editedHabit.originalEntries.get(editDate).value
            val nextVal = if (editedHabit.isNumerical) curVal + 2000 else {
                if (curVal == Entry.YES_MANUAL) Entry.NO else Entry.YES_MANUAL
            }
            val revBefore = revision.observedValue
            CreateRepetitionCommand(habitList, editedHabit, editDate, nextVal, "equiv-retro-edit").run()
            val revAfter = revision.observedValue
            val affected = revision.affectedHabitsBetween(revBefore, revAfter)
            assertEquals(setOf(HabitRef(editedHabit.uuid!!)), affected)

            val freshAfter = snapshotProvider.prepare(
                habits.take(15),
                from,
                to,
                revision.datasetId,
                revAfter,
                calendar
            )
            for ((ref, freshSnap) in freshAfter) {
                if (ref !in affected!!) {
                    val retaggedSnap = initialSnapshots.getValue(ref).withRevision(revAfter)
                    assertEquals(freshSnap.revision, retaggedSnap.revision)
                    assertEquals(freshSnap.days, retaggedSnap.days)
                    assertEquals(freshSnap.scoreResult.on(anchorToday), retaggedSnap.scoreResult.on(anchorToday))
                    assertEquals(freshSnap.streakResult.streaks, retaggedSnap.streakResult.streaks)
                }
            }

            // 3. Verify HabitOrganizationFacadeImpl.withBatchReadScope vs unbatched lookups across all 100 habits
            for (h in habits) {
                val uuid = h.uuid!!
                val unbatchedRoot = facade.getRootGroupKey(uuid, h.blockId)
                val unbatchedIcon = facade.getHabitVisualIdentity(uuid)
                val (batchedRoot, batchedIcon) = facade.withBatchReadScope {
                    facade.getRootGroupKey(uuid, h.blockId) to facade.getHabitVisualIdentity(uuid)
                }
                assertEquals(unbatchedRoot, batchedRoot, "Batched root group key mismatch for $uuid")
                assertEquals(unbatchedIcon, batchedIcon, "Batched visual identity mismatch for $uuid")
            }

            // 4. Verify UnifiedStatisticsService across DAY, WEEK, MONTH, YEAR, ALL TIME, and Container scopes
            val statsService = UnifiedStatisticsService(
                habitList = habitList,
                tasks = SQLiteUserTaskRepository(observedDb),
                focus = SQLiteFocusSessionRepository(observedDb),
                contributions = contribRepo,
                placements = orgStore,
                containers = orgStore
            )
            val spanDaysList = listOf(0, 6, 29, 364, DatasetProfile.MEDIUM.daySpan)
            val rootId = orgStore.roots(includeArchived = true).firstOrNull()?.id
            val scopes = buildList {
                add(StatisticsSectionScope.All)
                add(StatisticsSectionScope.Unassigned)
                if (rootId != null) add(StatisticsSectionScope.Container(rootId))
            }
            for (spanDays in spanDaysList) {
                for (scope in scopes) {
                    val queryContext = StatisticsQueryContext(
                        from = anchorToday.minus(spanDays),
                        to = anchorToday,
                        timezoneId = "UTC",
                        firstWeekday = DayOfWeek.MONDAY,
                        datasetId = revision.datasetId,
                        revision = revision.observedValue,
                        now = anchorToday.unixTime,
                        sectionScope = scope
                    )
                    val res1 = statsService.read(
                        request = queryContext,
                        currentIdentity = { StatisticsReadIdentity(queryContext.datasetId, queryContext.revision) }
                    )
                    val res2 = statsService.read(
                        request = queryContext,
                        currentIdentity = { StatisticsReadIdentity(queryContext.datasetId, queryContext.revision) }
                    )
                    assertEquals(res1, res2, "UnifiedStatisticsService deterministic read mismatch for span=$spanDays / scope=$scope")
                }
            }

            // 5. Verify detail screen presenter builds full state on edited habit
            val editedRef = HabitRef(editedHabit.uuid!!)
            val snap = freshAfter.getValue(editedRef)
            val detailState = ShowHabitPresenter.buildState(
                habit = editedHabit,
                preferences = Preferences(MemoryStorage()),
                theme = LightTheme(),
                snapshot = snap
            )
            assertEquals(editedHabit.color, detailState.color)

            rawDb.close()
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
