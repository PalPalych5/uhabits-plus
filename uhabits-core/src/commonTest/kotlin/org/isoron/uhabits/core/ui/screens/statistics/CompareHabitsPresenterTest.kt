package org.isoron.uhabits.core.ui.screens.statistics

import org.isoron.platform.time.DayOfWeek
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.getToday
import org.isoron.platform.time.setToday
import org.isoron.platform.time.startOfLocalDay
import org.isoron.uhabits.core.BaseUnitTest
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.contribution.Contribution
import org.isoron.uhabits.core.contribution.ContributionId
import org.isoron.uhabits.core.contribution.ContributionSourceType
import org.isoron.uhabits.core.contribution.memory.MemoryContributionRepository
import org.isoron.uhabits.core.models.DayTier
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Frequency
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitGoal
import org.isoron.uhabits.core.models.HabitType
import org.isoron.uhabits.core.models.NumericalHabitType
import org.isoron.uhabits.core.models.projection.EffectiveHabitReadSnapshot
import org.isoron.uhabits.core.models.projection.EffectiveHabitReadSnapshotProvider
import org.isoron.uhabits.core.models.projection.EffectiveReadCalendarContext
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CompareHabitsPresenterTest : BaseUnitTest() {
    private val monday = LocalDate(2026, 10, 5)
    private val sunday = LocalDate(2026, 10, 11)
    private val calendar = EffectiveReadCalendarContext("local", DayOfWeek.MONDAY, 0, 0)
    private var sequence = 0
    private val repository = MemoryContributionRepository()

    @BeforeTest
    fun setupToday() {
        setToday(sunday)
    }

    private fun habit(
        frequency: Frequency = Frequency(1, 7),
        targetType: NumericalHabitType = NumericalHabitType.AT_LEAST,
        targetValue: Double = 300.0,
        unit: String = "min",
        isNumerical: Boolean = true
    ): Habit = modelFactory.buildHabit().apply {
        uuid = "cmp-${++sequence}"
        type = if (isNumerical) HabitType.NUMERICAL else HabitType.YES_NO
        this.unit = unit
        this.frequency = frequency
        this.targetType = targetType
        this.targetValue = targetValue
        this.dayTier = DayTier.MINIMUM
    }

    private fun credit(h: Habit, date: LocalDate, value: Long): Contribution {
        val id = "credit-${++sequence}"
        val timestamp = startOfLocalDay(date) + 1000
        return Contribution(
            ContributionId(id),
            HabitRef(h.uuid!!),
            ContributionSourceType.FOCUS_SESSION,
            id,
            value,
            timestamp,
            timestamp,
            timestamp
        ).also(repository::record)
    }

    private fun read(
        habits: List<Habit>,
        from: LocalDate = monday.minus(30),
        to: LocalDate = sunday,
        revision: Long = 1
    ): Map<HabitRef, EffectiveHabitReadSnapshot> =
        EffectiveHabitReadSnapshotProvider(repository).prepare(habits, from, to, "test", revision, calendar)

    @Test
    fun testWeeklyNumerical330to240TombstoneAndAgreementWithStatistics() {
        val h = habit(frequency = Frequency(1, 7), targetValue = 300.0)
        // Explicit zeros make this full calendar period exact.
        for (offset in 0..6) h.originalEntries.add(Entry(monday.plus(offset), 0))
        // Manual 60 min (60,000 milli-units) on Monday
        h.originalEntries.add(Entry(monday, 60000))
        h.recompute()

        // Contributions 180 min (180,000) on Tuesday and 90 min (90,000) on Wednesday
        val c1 = credit(h, monday.plus(1), 180000)
        val c2 = credit(h, monday.plus(2), 90000)

        // 1. Initial state: 60 + 180 + 90 = 330 min (goal reached >= 300 min)
        val snapshotsBefore = read(listOf(h), monday, sunday, revision = 1)
        val snapBefore = snapshotsBefore.getValue(HabitRef(h.uuid!!))

        val compareOverviewBefore = CompareHabitsPresenter.buildOverviewMetrics(
            habit = h,
            startDate = monday,
            today = sunday,
            period = ComparePeriod.WEEK,
            snapshot = snapBefore
        )
        val statsSliceBefore = StatisticsReportStateBuilder.evaluateHabitSlice(
            habit = h,
            sliceStart = monday,
            sliceEnd = sunday,
            snapshot = snapBefore
        )

        assertNotNull(compareOverviewBefore.periodCompletion)
        assertEquals(1.0, compareOverviewBefore.periodCompletion, 0.001)
        assertNotNull(statsSliceBefore?.progress)
        assertEquals(1.0, statsSliceBefore.progress!!, 0.001)
        assertEquals(statsSliceBefore.progress, compareOverviewBefore.periodCompletion)
        assertEquals(330.0, statsSliceBefore.actual, 0.001)

        // 2. Tombstone the 90 min contribution
        repository.removeById(c2.id, removedAt = c2.createdAt + 1000L)

        // 3. Updated state: 60 + 180 = 240 min (goal NOT reached, 240 / 300 = 0.8)
        val snapshotsAfter = read(listOf(h), monday, sunday, revision = 2)
        val snapAfter = snapshotsAfter.getValue(HabitRef(h.uuid!!))

        val compareOverviewAfter = CompareHabitsPresenter.buildOverviewMetrics(
            habit = h,
            startDate = monday,
            today = sunday,
            period = ComparePeriod.WEEK,
            snapshot = snapAfter
        )
        val statsSliceAfter = StatisticsReportStateBuilder.evaluateHabitSlice(
            habit = h,
            sliceStart = monday,
            sliceEnd = sunday,
            snapshot = snapAfter
        )

        assertNotNull(compareOverviewAfter.periodCompletion)
        assertEquals(0.8, compareOverviewAfter.periodCompletion, 0.001)
        assertNotNull(statsSliceAfter?.progress)
        assertEquals(0.8, statsSliceAfter.progress!!, 0.001)
        assertEquals(statsSliceAfter.progress, compareOverviewAfter.periodCompletion)
        assertEquals(240.0, statsSliceAfter.actual, 0.001)
    }

    @Test
    fun testNoContributionLegacyParity() {
        val h1 = habit(frequency = Frequency.DAILY, targetValue = 100.0)
        h1.originalEntries.add(Entry(monday, 100000))
        h1.originalEntries.add(Entry(monday.plus(1), 50000))
        h1.originalEntries.add(Entry(monday.plus(2), Entry.SKIP))
        h1.recompute()

        val h2 = habit(frequency = Frequency.DAILY, isNumerical = false)
        h2.originalEntries.add(Entry(monday, Entry.YES_MANUAL))
        h2.originalEntries.add(Entry(monday.plus(1), 0))
        h2.recompute()

        val habits = listOf(h1, h2)
        val snapshots = read(habits, monday, sunday)

        for (h in habits) {
            val ref = HabitRef(h.uuid!!)
            val snap = snapshots.getValue(ref)

            // Overview metrics parity
            val legacyMetrics = CompareHabitsPresenter.buildOverviewMetrics(h, monday, sunday, ComparePeriod.WEEK, null)
            val snapshotMetrics = CompareHabitsPresenter.buildOverviewMetrics(h, monday, sunday, ComparePeriod.WEEK, snap)
            assertEquals(legacyMetrics.periodCompletion, snapshotMetrics.periodCompletion)
            assertEquals(legacyMetrics.streakLength, snapshotMetrics.streakLength)

            // Stability series parity
            val legacyStability = CompareHabitsPresenter.buildStabilitySeries(h, monday, sunday, null)
            val snapshotStability = CompareHabitsPresenter.buildStabilitySeries(h, monday, sunday, snap)
            assertEquals(legacyStability, snapshotStability)

            // Execution series parity
            val legacyExec = CompareHabitsPresenter.buildExecutionSeries(h, monday, sunday, null)
            val snapshotExec = CompareHabitsPresenter.buildExecutionSeries(h, monday, sunday, snap)
            assertEquals(legacyExec, snapshotExec)

            // Streak state parity
            val legacyStreak = CompareHabitsPresenter.buildStreakState(h, null)
            val snapshotStreak = CompareHabitsPresenter.buildStreakState(h, snap)
            assertEquals(legacyStreak.latestStreak?.length, snapshotStreak.latestStreak?.length)
            assertEquals(legacyStreak.recordStreak?.length, snapshotStreak.recordStreak?.length)

            // Frequency parity
            val legacyFreq = CompareHabitsPresenter.buildFrequency(h, null)
            val snapshotFreq = CompareHabitsPresenter.buildFrequency(h, snap)
            assertEquals(legacyFreq.keys, snapshotFreq.keys)
            legacyFreq.forEach { (date, values) -> assertEquals(values.toList(), snapshotFreq.getValue(date).toList()) }
        }
    }

    @Test
    fun testContributionOnlyTuesdayEntersComparisonDateRanges() {
        val h = habit(frequency = Frequency.DAILY, targetValue = 60.0)
        // No manual entries, only a contribution on Tuesday
        val tuesday = monday.plus(1)
        credit(h, tuesday, 60000)

        val snapshots = read(listOf(h), monday, sunday)
        val snap = snapshots.getValue(HabitRef(h.uuid!!))

        val habitStart = StatisticsReportStateBuilder.getHabitStartDate(h, sunday, snap)
        assertEquals(tuesday, habitStart)

        val startForPeriod = CompareHabitsPresenter.getStartDateForPeriod(listOf(h), sunday, ComparePeriod.WEEK, snapshots)
        assertEquals(tuesday, startForPeriod)

        val execSeries = CompareHabitsPresenter.buildExecutionSeries(h, monday, sunday, snap)
        assertTrue(execSeries.any { it.first == tuesday && it.second == 1.0 })
    }

    @Test
    fun testUnknownLowerBoundHandling() {
        val h = habit(frequency = Frequency.DAILY, targetValue = 100.0)
        // Tuesday: manual UNKNOWN + contribution of 45 min (< target 100 min)
        val tuesday = monday.plus(1)
        credit(h, tuesday, 45000)

        // Wednesday: manual UNKNOWN + contribution of 120 min (>= target 100 min)
        val wednesday = monday.plus(2)
        credit(h, wednesday, 120000)

        val snapshots = read(listOf(h), monday, sunday)
        val snap = snapshots.getValue(HabitRef(h.uuid!!))

        // On Tuesday: lower bound 45 < 100 with incomplete coverage -> null (uncertain exact scalar)
        val tuesdayProg = CompareHabitsPresenter.computeDailyNormalizedProgress(h, tuesday, snap)
        assertNull(tuesdayProg)

        // On Wednesday: lower bound 120 >= 100 -> 1.0 (proven achieved despite incomplete coverage)
        val wednesdayProg = CompareHabitsPresenter.computeDailyNormalizedProgress(h, wednesday, snap)
        assertEquals(1.0, wednesdayProg)
    }

    @Test
    fun testSkipProvenanceExclusion() {
        val h = habit(frequency = Frequency.DAILY, targetValue = 60.0)
        val thursday = monday.plus(3)
        h.originalEntries.add(Entry(thursday, Entry.SKIP))
        h.recompute()

        // Focus session on the skipped day must be excluded
        credit(h, thursday, 60000)

        val snapshots = read(listOf(h), monday, sunday)
        val snap = snapshots.getValue(HabitRef(h.uuid!!))

        val thursdayProg = CompareHabitsPresenter.computeDailyNormalizedProgress(h, thursday, snap)
        assertNull(thursdayProg)

        val overview = CompareHabitsPresenter.buildOverviewMetrics(h, monday, sunday, ComparePeriod.WEEK, snap)
        // Skipped day is excluded from calculations
        val slice = StatisticsReportStateBuilder.evaluateHabitSlice(h, monday, sunday, snap)
        assertEquals(slice?.progress, overview.periodCompletion)
    }

    @Test
    fun testAtMostIncompleteCoverage() {
        val h = habit(
            frequency = Frequency.DAILY,
            targetType = NumericalHabitType.AT_MOST,
            targetValue = 60.0
        )
        val tuesday = monday.plus(1)
        val wednesday = monday.plus(2)

        // Tuesday: UNKNOWN + 30 min (<= 60, but incomplete coverage -> cannot be declared achieved yet)
        credit(h, tuesday, 30000)

        // Wednesday: UNKNOWN + 90 min (> 60 -> proven violated, progress 0.0)
        credit(h, wednesday, 90000)

        val snapshots = read(listOf(h), monday, sunday)
        val snap = snapshots.getValue(HabitRef(h.uuid!!))

        val tuesdayProg = CompareHabitsPresenter.computeDailyNormalizedProgress(h, tuesday, snap)
        assertNull(tuesdayProg)

        val wednesdayProg = CompareHabitsPresenter.computeDailyNormalizedProgress(h, wednesday, snap)
        assertEquals(0.0, wednesdayProg)
    }

    @Test
    fun testIncompatibleUnitsKeptSeparate() {
        val h1 = habit(targetValue = 5.0, unit = "km") // 5 km
        val h2 = habit(targetValue = 300.0, unit = "min") // 300 min

        h1.originalEntries.add(Entry(monday, 5000))
        h1.recompute()
        h2.originalEntries.add(Entry(monday, 150000))
        h2.recompute()

        val snapshots = read(listOf(h1, h2), monday, sunday)
        val snap1 = snapshots.getValue(HabitRef(h1.uuid!!))
        val snap2 = snapshots.getValue(HabitRef(h2.uuid!!))

        val exec1 = CompareHabitsPresenter.buildExecutionSeries(h1, monday, sunday, snap1)
        val exec2 = CompareHabitsPresenter.buildExecutionSeries(h2, monday, sunday, snap2)

        // Habit 1: 5km / 5km = 1.0 (100%)
        assertEquals(1.0, exec1.first { it.first == monday }.second, 0.001)
        // Habit 2: 150min / 300min = 0.5 (50%)
        assertEquals(0.5, exec2.first { it.first == monday }.second, 0.001)
    }

    @Test
    fun testHistoricalGoals() {
        val h = habit(frequency = Frequency.DAILY, targetValue = 300.0)
        val wednesday = monday.plus(2)
        // Goal change: before Wednesday, target was 100 min; starting Wednesday, target is 300 min
        val oldGoal = HabitGoal(
            effectiveDate = LocalDate(2000, 1, 1),
            frequency = Frequency.DAILY,
            targetType = NumericalHabitType.AT_LEAST,
            targetValue = 100.0,
            unit = "min"
        )
        val newGoal = HabitGoal(
            effectiveDate = wednesday,
            frequency = Frequency.DAILY,
            targetType = NumericalHabitType.AT_LEAST,
            targetValue = 300.0,
            unit = "min"
        )
        h.goalHistory = mutableListOf(oldGoal, newGoal)

        // Tuesday: 100 min manual (reaches old 100 min goal)
        val tuesday = monday.plus(1)
        h.originalEntries.add(Entry(tuesday, 100000))
        // Thursday: 150 min manual (50% of new 300 min goal)
        val thursday = monday.plus(3)
        h.originalEntries.add(Entry(thursday, 150000))
        h.recompute()

        val snapshots = read(listOf(h), monday, sunday)
        val snap = snapshots.getValue(HabitRef(h.uuid!!))

        val exec = CompareHabitsPresenter.buildExecutionSeries(h, monday, sunday, snap)
        // Tuesday: 100 / 100 = 1.0
        assertEquals(1.0, exec.first { it.first == tuesday }.second, 0.001)
        // Thursday: 150 / 300 = 0.5
        assertEquals(0.5, exec.first { it.first == thursday }.second, 0.001)
    }

    @Test
    fun testQueryAndPeriodDateRanges() {
        val today = LocalDate(2026, 10, 8)
        assertEquals(today.minus(7), CompareHabitsPresenter.getQueryStartDate(today, ComparePeriod.WEEK))
        assertEquals(minOf(today.minus(30), today.startOfMonth()), CompareHabitsPresenter.getQueryStartDate(today, ComparePeriod.MONTH))
        assertEquals(today.minus(90), CompareHabitsPresenter.getQueryStartDate(today, ComparePeriod.THREE_MONTHS))
        assertEquals(today.minus(365), CompareHabitsPresenter.getQueryStartDate(today, ComparePeriod.YEAR))
        assertEquals(LocalDate(1970, 1, 1), CompareHabitsPresenter.getQueryStartDate(today, ComparePeriod.ALL))
    }
    @Test
    fun testIncompleteCreditCannotManufactureStreakOrFrequency() {
        val h = habit(frequency = Frequency.DAILY, targetValue = 60.0)
        credit(h, monday, 60000)
        val snapshot = read(listOf(h), monday, sunday).getValue(HabitRef(h.uuid!!))
        val state = CompareHabitsPresenter.buildStreakState(h, snapshot)
        assertTrue(!state.isExact)
        assertNull(state.latestStreak)
        assertNull(state.recordStreak)
        assertTrue(CompareHabitsPresenter.buildFrequency(h, snapshot).isEmpty())
        assertNull(CompareHabitsPresenter.buildOverviewMetrics(h, monday, sunday, ComparePeriod.WEEK, snapshot).scoreToday)
        assertTrue(CompareHabitsPresenter.buildStabilitySeries(h, monday, sunday, snapshot).isEmpty())
    }

    @Test
    fun testFrequencyUsesExactEffectiveQuantitiesAndExcludesSkip() {
        val h = habit(frequency = Frequency.DAILY, targetValue = 60.0)
        for (offset in 0..6) h.originalEntries.add(Entry(monday.plus(offset), 0))
        h.originalEntries.add(Entry(monday, 60000))
        h.originalEntries.add(Entry(monday.plus(1), Entry.SKIP))
        h.recompute()
        credit(h, monday, 180000)
        credit(h, monday.plus(1), 90000)
        val snapshot = read(listOf(h), monday, sunday).getValue(HabitRef(h.uuid!!))
        val frequency = CompareHabitsPresenter.buildFrequency(h, snapshot)
        val weekday = (monday.dayOfWeek.daysSinceSunday + 1) % 7
        assertEquals(240000, frequency.getValue(monday.startOfMonth())[weekday])
        assertEquals(240000, frequency.getValue(monday.startOfMonth()).sum())
    }

    @Test
    fun testUnrepresentableCreditCannotCrashStreakOrOverflowFrequency() {
        val h = habit(frequency = Frequency.DAILY)
        h.originalEntries.add(Entry(monday, 0))
        h.recompute()
        credit(h, monday, Int.MAX_VALUE.toLong() + 1)
        val snapshot = read(listOf(h), monday, monday).getValue(HabitRef(h.uuid!!))
        assertTrue(!CompareHabitsPresenter.buildStreakState(h, snapshot).isExact)
        assertTrue(CompareHabitsPresenter.buildFrequency(h, snapshot).isEmpty())
    }

    @Test
    fun testFrequencyBucketOverflowIsUnavailable() {
        val h = habit(frequency = Frequency.DAILY)
        setToday(monday.plus(7))
        for (offset in 0..7) h.originalEntries.add(Entry(monday.plus(offset), 0))
        h.recompute()
        credit(h, monday, 1500000000)
        credit(h, monday.plus(7), 1500000000)
        val snapshot = read(listOf(h), monday, monday.plus(7)).getValue(HabitRef(h.uuid!!))
        assertTrue(CompareHabitsPresenter.buildFrequency(h, snapshot).isEmpty())
    }
    @Test
    fun testIncompletePeriodCannotExposeExactPercentage() {
        val h = habit(frequency = Frequency(1, 7), targetValue = 300.0)
        h.originalEntries.add(Entry(monday, 60000))
        h.recompute()
        credit(h, monday.plus(1), 180000)
        val snapshot = read(listOf(h), monday, sunday).getValue(HabitRef(h.uuid!!))
        val metric = CompareHabitsPresenter.buildOverviewMetrics(h, monday, sunday, ComparePeriod.WEEK, snapshot)
        assertNull(metric.periodCompletion)
    }

    @Test
    fun testNoContributionAtMostExecutionUsesGoalDirection() {
        val h = habit(frequency = Frequency.DAILY, targetType = NumericalHabitType.AT_MOST, targetValue = 60.0)
        h.originalEntries.add(Entry(monday, 30000))
        h.originalEntries.add(Entry(monday.plus(1), 90000))
        h.recompute()
        val snapshot = read(listOf(h), monday, sunday).getValue(HabitRef(h.uuid!!))
        assertEquals(1.0, CompareHabitsPresenter.computeDailyNormalizedProgress(h, monday, snapshot))
        assertEquals(0.0, CompareHabitsPresenter.computeDailyNormalizedProgress(h, monday.plus(1), snapshot))
    }

    @Test
    fun testHistoricalUnitChangeCannotProduceMixedUnitPercentage() {
        val h = habit(frequency = Frequency.DAILY, targetValue = 300.0)
        h.goalHistory = mutableListOf(
            HabitGoal(LocalDate(2000, 1, 1), Frequency.DAILY, NumericalHabitType.AT_LEAST, 5.0, "km"),
            HabitGoal(monday.plus(2), Frequency.DAILY, NumericalHabitType.AT_LEAST, 300.0, "min")
        )
        h.originalEntries.add(Entry(monday, 5000))
        h.originalEntries.add(Entry(monday.plus(2), 0))
        h.recompute()
        credit(h, monday.plus(2), 150000)
        val snapshot = read(listOf(h), monday, sunday).getValue(HabitRef(h.uuid!!))
        assertNull(CompareHabitsPresenter.buildOverviewMetrics(h, monday, sunday, ComparePeriod.WEEK, snapshot).periodCompletion)
        assertEquals(1.0, CompareHabitsPresenter.computeDailyNormalizedProgress(h, monday, snapshot))
        assertEquals(0.5, CompareHabitsPresenter.computeDailyNormalizedProgress(h, monday.plus(2), snapshot))
    }

    @Test
    fun testCalendarAchievementUsesLiveWeekdayAndIsSeparateFromDailyExecution() {
        val h = habit(frequency = Frequency(1, 7), targetValue = 300.0)
        for (offset in 0..6) h.originalEntries.add(Entry(monday.plus(offset), 0))
        h.originalEntries.add(Entry(monday, 60000))
        h.recompute()
        credit(h, monday.plus(1), 180000)
        credit(h, monday.plus(2), 90000)
        val provider = EffectiveHabitReadSnapshotProvider(repository)
        val mon = provider.prepare(listOf(h), monday, sunday, "test", 1, calendar).getValue(HabitRef(h.uuid!!))
        val sun = provider.prepare(listOf(h), monday, sunday, "test", 2, calendar.copy(firstWeekday = DayOfWeek.SUNDAY)).getValue(HabitRef(h.uuid!!))
        assertEquals(org.isoron.uhabits.core.models.projection.HabitGoalStatus.SATISFIED, mon.goalAchievementOn(sunday).status)
        assertEquals(org.isoron.uhabits.core.models.projection.HabitGoalStatus.UNKNOWN, sun.goalAchievementOn(sunday).status)
        // Daily execution is normalized against the historical goal, never replaced by a calendar flag.
        assertEquals(0.2, CompareHabitsPresenter.computeDailyNormalizedProgress(h, monday, mon))
    }
}
