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
import org.isoron.uhabits.core.models.HabitType
import org.isoron.uhabits.core.models.NumericalHabitType
import org.isoron.uhabits.core.models.projection.EffectiveHabitReadSnapshot
import org.isoron.uhabits.core.models.projection.EffectiveHabitReadSnapshotProvider
import org.isoron.uhabits.core.models.projection.EffectiveReadCalendarContext
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StatisticsEffectiveReportTest : BaseUnitTest() {
    private val monday = LocalDate(2026, 10, 5)
    private val calendar = EffectiveReadCalendarContext("local", DayOfWeek.MONDAY, 0, 0)
    private var sequence = 0
    private val repository = MemoryContributionRepository()

    @BeforeTest
    fun setSnapshotToday() {
        setToday(monday.plus(6))
    }

    private fun habit(
        frequency: Frequency = Frequency.DAILY,
        targetType: NumericalHabitType = NumericalHabitType.AT_LEAST,
        targetValue: Double = 300.0
    ): Habit = modelFactory.buildHabit().apply {
        uuid = "stat-${++sequence}"
        type = HabitType.NUMERICAL
        unit = "min"
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
        from: LocalDate = monday,
        to: LocalDate = monday.plus(6),
        revision: Long = 1
    ): Map<HabitRef, EffectiveHabitReadSnapshot> =
        EffectiveHabitReadSnapshotProvider(repository).prepare(habits, from, to, "test", revision, calendar)

    @Test
    fun testNoContributionLegacyParity() {
        val h = habit()
        h.originalEntries.add(Entry(monday, 300000))
        h.originalEntries.add(Entry(monday.plus(1), 0))
        h.recompute()

        val reportWithoutSnapshot = StatisticsReportStateBuilder.build(
            habits = listOf(h),
            period = StatisticsPeriod.WEEK,
            start = monday,
            end = monday.plus(6),
            today = monday.plus(6),
            firstWeekday = DayOfWeek.MONDAY,
            filters = StatisticsFilterState(habitStatus = StatisticsHabitStatusFilter.ALL)
        )

        val snapshots = read(listOf(h))
        val reportWithSnapshot = StatisticsReportStateBuilder.build(
            habits = listOf(h),
            period = StatisticsPeriod.WEEK,
            start = monday,
            end = monday.plus(6),
            today = monday.plus(6),
            firstWeekday = DayOfWeek.MONDAY,
            filters = StatisticsFilterState(habitStatus = StatisticsHabitStatusFilter.ALL),
            snapshots = snapshots
        )

        assertEquals(reportWithoutSnapshot.overallProgress, reportWithSnapshot.overallProgress)
        assertEquals(reportWithoutSnapshot.matchingHabits, reportWithSnapshot.matchingHabits)
        assertEquals(reportWithoutSnapshot.habits.first().progress, reportWithSnapshot.habits.first().progress)
        assertEquals(reportWithoutSnapshot.habits.first().status, reportWithSnapshot.habits.first().status)
    }

    @Test
    fun testContributionOnlyDateDiscovery() {
        val h = habit()
        // No manual entries added at all
        val contribDate = monday.plus(2)
        credit(h, contribDate, 180000) // 180 min

        val snapshots = read(listOf(h))
        val snapshot = snapshots.values.first()
        assertEquals(contribDate, snapshot.observedFrom)

        val discoveredStart = StatisticsReportStateBuilder.getHabitStartDate(h, monday.plus(6), snapshot)
        assertEquals(contribDate, discoveredStart)

        val report = StatisticsReportStateBuilder.build(
            habits = listOf(h),
            period = StatisticsPeriod.ALL,
            start = monday.minus(30),
            end = monday.plus(6),
            today = monday.plus(6),
            firstWeekday = DayOfWeek.MONDAY,
            filters = StatisticsFilterState(habitStatus = StatisticsHabitStatusFilter.ALL),
            snapshots = snapshots
        )

        assertEquals(contribDate, report.start)
        val habitResult = report.habits.firstOrNull()
        assertNotNull(habitResult)
        assertEquals(180.0, habitResult.actual)
    }

    @Test
    fun testAtLeastLowerBoundProof() {
        val h = habit(frequency = Frequency.WEEKLY, targetType = NumericalHabitType.AT_LEAST, targetValue = 300.0)
        // Target is 300 min/week.
        // Manual: 60 min on Monday (unknown for other days).
        h.originalEntries.add(Entry(monday, 60000))
        h.recompute()
        // Focus credit: 180 min + 90 min = 270 min on Wednesday.
        credit(h, monday.plus(2), 180000)
        credit(h, monday.plus(2), 90000)

        // Total = 60 + 270 = 330 min >= 300 min (proven reached even with UNKNOWN on other days!)
        val snapshots = read(listOf(h))
        val report = StatisticsReportStateBuilder.build(
            habits = listOf(h),
            period = StatisticsPeriod.WEEK,
            start = monday,
            end = monday.plus(6),
            today = monday.plus(6),
            firstWeekday = DayOfWeek.MONDAY,
            filters = StatisticsFilterState(habitStatus = StatisticsHabitStatusFilter.ALL),
            snapshots = snapshots
        )

        val result = report.habits.first()
        assertEquals(330.0, result.actual)
        assertEquals(300.0, result.target)
        assertEquals(1.0, result.progress)
        assertEquals(StatisticsResultStatus.ON_TRACK, result.status)

        // When week is past (today is next Monday)
        setToday(monday.plus(7))
        val pastReport = StatisticsReportStateBuilder.build(
            habits = listOf(h),
            period = StatisticsPeriod.WEEK,
            start = monday,
            end = monday.plus(6),
            today = monday.plus(7),
            firstWeekday = DayOfWeek.MONDAY,
            filters = StatisticsFilterState(habitStatus = StatisticsHabitStatusFilter.ALL),
            snapshots = snapshots
        )
        assertEquals(StatisticsResultStatus.COMPLETED, pastReport.habits.first().status)
    }

    @Test
    fun testAtMostExceedingLimitProvesFailure() {
        val h = habit(frequency = Frequency.DAILY, targetType = NumericalHabitType.AT_MOST, targetValue = 60.0)
        // Limit is 60 min/day.
        // On Tuesday, focus credit is 90 min (exceeds limit!).
        credit(h, monday.plus(1), 90000)

        val snapshots = read(listOf(h))
        val report = StatisticsReportStateBuilder.build(
            habits = listOf(h),
            period = StatisticsPeriod.WEEK,
            start = monday,
            end = monday.plus(6),
            today = monday.plus(6),
            firstWeekday = DayOfWeek.MONDAY,
            filters = StatisticsFilterState(habitStatus = StatisticsHabitStatusFilter.ALL),
            snapshots = snapshots
        )

        val result = report.habits.first()
        assertEquals(StatisticsResultStatus.VIOLATED, result.status)
        assertEquals(0.0, result.progress)
    }

    @Test
    fun testSkipProvenanceExclusion() {
        val h = habit(frequency = Frequency.DAILY, targetType = NumericalHabitType.AT_LEAST, targetValue = 60.0)
        // Monday is explicitly SKIPPED
        h.originalEntries.add(Entry(monday, Entry.SKIP))
        h.recompute()
        // Add credit on Monday (which should be excluded from counting toward active target due to SKIP)
        credit(h, monday, 60000)

        val snapshots = read(listOf(h))
        val day = snapshots.values.first().day(monday)
        assertTrue(day.isSkipped)

        val report = StatisticsReportStateBuilder.build(
            habits = listOf(h),
            period = StatisticsPeriod.DAY,
            start = monday,
            end = monday,
            today = monday.plus(6),
            firstWeekday = DayOfWeek.MONDAY,
            filters = StatisticsFilterState(habitStatus = StatisticsHabitStatusFilter.ALL),
            snapshots = snapshots
        )

        val result = report.habits.firstOrNull()
        assertNotNull(result)
        assertEquals(StatisticsResultStatus.SKIPPED, result.status)
    }

    @Test
    fun testCanonicalFixtureAndTombstoneConvergence() {
        val h = habit(frequency = Frequency.WEEKLY, targetType = NumericalHabitType.AT_LEAST, targetValue = 300.0)
        h.name = "Study"
        // Manual = 60 min on Monday
        h.originalEntries.add(Entry(monday, 60000))
        h.recompute()

        // Contributions = 180 + 90 min on Wednesday
        val c180 = credit(h, monday.plus(2), 180000)
        val c90 = credit(h, monday.plus(2), 90000)

        // Snapshot at revision 1: Effective = 330 min >= 300 min (reached)
        var snapshots = read(listOf(h), revision = 1)
        var report = StatisticsReportStateBuilder.build(
            habits = listOf(h),
            period = StatisticsPeriod.WEEK,
            start = monday,
            end = monday.plus(6),
            today = monday.plus(6),
            firstWeekday = DayOfWeek.MONDAY,
            filters = StatisticsFilterState(habitStatus = StatisticsHabitStatusFilter.ALL),
            snapshots = snapshots
        )
        var result = report.habits.first()
        assertEquals(330.0, result.actual)
        assertEquals(300.0, result.target)
        assertEquals(1.0, result.progress)
        assertEquals(StatisticsResultStatus.ON_TRACK, result.status)

        var overview = StatisticsOverviewStateBuilder.build(
            habits = listOf(h),
            start = monday,
            end = monday.plus(6),
            firstWeekday = DayOfWeek.MONDAY,
            snapshots = snapshots
        )
        assertEquals(330.0, overview.focusMinutes)

        // Tombstone the 90-minute contribution
        repository.removeById(c90.id, startOfLocalDay(monday.plus(2)) + 2000)

        // Snapshot at revision 2: Effective = 240 min < 300 min (not reached, incomplete observations pending)
        snapshots = read(listOf(h), revision = 2)
        report = StatisticsReportStateBuilder.build(
            habits = listOf(h),
            period = StatisticsPeriod.WEEK,
            start = monday,
            end = monday.plus(6),
            today = monday.plus(6),
            firstWeekday = DayOfWeek.MONDAY,
            filters = StatisticsFilterState(habitStatus = StatisticsHabitStatusFilter.ALL),
            snapshots = snapshots
        )
        result = report.habits.first()
        assertEquals(240.0, result.actual)
        assertEquals(300.0, result.target)
        assertEquals(240.0 / 300.0, result.progress)
        assertEquals(StatisticsResultStatus.PENDING, result.status)

        overview = StatisticsOverviewStateBuilder.build(
            habits = listOf(h),
            start = monday,
            end = monday.plus(6),
            firstWeekday = DayOfWeek.MONDAY,
            snapshots = snapshots
        )
        assertEquals(240.0, overview.focusMinutes)

        // When week is past, unreached with incomplete coverage remains PENDING
        setToday(monday.plus(7))
        val pastReport = StatisticsReportStateBuilder.build(
            habits = listOf(h),
            period = StatisticsPeriod.WEEK,
            start = monday,
            end = monday.plus(6),
            today = monday.plus(7),
            firstWeekday = DayOfWeek.MONDAY,
            filters = StatisticsFilterState(habitStatus = StatisticsHabitStatusFilter.ALL),
            snapshots = snapshots
        )
        assertEquals(StatisticsResultStatus.PENDING, pastReport.habits.first().status)
    }

    @Test
    fun testHistoricalGoalsAndCutoffAndFirstWeekday() {
        val h = habit(frequency = Frequency.WEEKLY, targetType = NumericalHabitType.AT_LEAST, targetValue = 300.0)
        // Add historical goal change on Thursday: target becomes 400 min/week
        val nextWeek = monday.plus(7)
        h.goalHistory.add(
            org.isoron.uhabits.core.models.HabitGoal(
                effectiveDate = nextWeek,
                targetType = NumericalHabitType.AT_LEAST,
                targetValue = 400.0,
                frequency = Frequency.WEEKLY,
                unit = "min"
            )
        )
        // Statistics cutoff is set to Wednesday: earlier days excluded
        h.statisticsStartDate = monday.plus(2)

        // Credit on Monday (before cutoff) -> excluded from statistics
        credit(h, monday, 120000)
        // Credit on Wednesday (at cutoff) -> included
        credit(h, monday.plus(2), 200000)

        val snapshots = read(listOf(h), from = monday, to = nextWeek.plus(6))
        val report = StatisticsReportStateBuilder.build(
            habits = listOf(h),
            period = StatisticsPeriod.WEEK,
            start = monday,
            end = monday.plus(6),
            today = nextWeek.plus(6),
            firstWeekday = DayOfWeek.MONDAY,
            filters = StatisticsFilterState(habitStatus = StatisticsHabitStatusFilter.ALL),
            snapshots = snapshots
        )
        val res = report.habits.first()
        // Only Wednesday credit (200 min) is included; Monday credit (120 min) is excluded by cutoff
        assertEquals(200.0, res.actual)
    }

    @Test
    fun testAtMostUnderLimitWithIncompleteCoverageRemainsPending() {
        val h = habit(frequency = Frequency.DAILY, targetType = NumericalHabitType.AT_MOST, targetValue = 60.0)
        // Focus credit is 20 min on Monday, but manual entry is UNKNOWN (incomplete coverage).
        // 20 min < 60 min, but we cannot declare the limit observed and satisfied without complete coverage.
        credit(h, monday, 20000)

        val snapshots = read(listOf(h))
        val report = StatisticsReportStateBuilder.build(
            habits = listOf(h),
            period = StatisticsPeriod.DAY,
            start = monday,
            end = monday,
            today = monday.plus(6),
            firstWeekday = DayOfWeek.MONDAY,
            filters = StatisticsFilterState(habitStatus = StatisticsHabitStatusFilter.ALL),
            snapshots = snapshots
        )

        val result = report.habits.first()
        assertEquals(StatisticsResultStatus.PENDING, result.status)
        assertEquals(0.0, result.progress)
    }

    @Test
    fun testMidnightDelayBucketingInReport() {
        val delayedCalendar = EffectiveReadCalendarContext("local", DayOfWeek.MONDAY, 3, 0)
        val h = habit()
        val tuesdayAt1AM = startOfLocalDay(monday.plus(1)) + 3600_000L
        val id = "delayed-credit"
        Contribution(
            ContributionId(id),
            HabitRef(h.uuid!!),
            ContributionSourceType.FOCUS_SESSION,
            id,
            60000L,
            tuesdayAt1AM,
            tuesdayAt1AM,
            tuesdayAt1AM
        ).also(repository::record)

        val snapshots = EffectiveHabitReadSnapshotProvider(repository).prepare(
            listOf(h), monday, monday.plus(6), "test", 1, delayedCalendar
        )
        val snapshot = snapshots.values.first()
        // Tuesday 1 AM with 3h delay belongs to Monday logical day
        assertEquals(60000L, snapshot.day(monday).totalValue)
        assertEquals(null, snapshot.day(monday.plus(1)).totalValue)

        val report = StatisticsReportStateBuilder.build(
            habits = listOf(h),
            period = StatisticsPeriod.DAY,
            start = monday,
            end = monday,
            today = monday.plus(6),
            firstWeekday = DayOfWeek.MONDAY,
            filters = StatisticsFilterState(habitStatus = StatisticsHabitStatusFilter.ALL),
            snapshots = snapshots
        )
        assertEquals(60.0, report.habits.first().actual)
    }
}
