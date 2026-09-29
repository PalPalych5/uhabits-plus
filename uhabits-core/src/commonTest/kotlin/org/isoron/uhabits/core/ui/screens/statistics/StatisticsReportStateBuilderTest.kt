package org.isoron.uhabits.core.ui.screens.statistics

import org.isoron.platform.time.DayOfWeek
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.getToday
import org.isoron.uhabits.core.BaseUnitTest
import org.isoron.uhabits.core.models.DayTier
import org.isoron.uhabits.core.models.DayTierScope
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Frequency
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitGoal
import org.isoron.uhabits.core.models.HabitType
import org.isoron.uhabits.core.models.NumericalHabitType
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StatisticsReportStateBuilderTest : BaseUnitTest() {

    private lateinit var habit1: Habit
    private lateinit var habit2: Habit

    @BeforeTest
    override fun setUp() {
        super.setUp()
        habit1 = fixtures.createEmptyHabit() // boolean daily by default
        habit2 = modelFactory.buildHabit()
        habit2.type = HabitType.YES_NO
        habit2.frequency = Frequency.DAILY
    }

    @Test
    fun testYearAndAllTimeHideHistoryBeforeStatisticsStart() {
        val today = LocalDate(2026, 9, 29)
        habit1.dayTier = DayTier.MINIMUM
        habit1.statisticsStartDate = LocalDate(2026, 9, 20)
        habit1.globalStatisticsStartDate = LocalDate(2026, 9, 19)
        for (month in 6..8) {
            habit1.originalEntries.add(Entry(LocalDate(2026, month, 21), Entry.YES_MANUAL))
        }
        habit1.originalEntries.add(Entry(LocalDate(2026, 9, 21), Entry.YES_MANUAL))
        habit1.recompute()

        val year = StatisticsReportStateBuilder.build(
            habits = listOf(habit1), period = StatisticsPeriod.YEAR,
            start = LocalDate(2026, 1, 1), end = LocalDate(2026, 12, 31),
            today = today, firstWeekday = DayOfWeek.MONDAY,
            filters = StatisticsFilterState(habitStatus = StatisticsHabitStatusFilter.ALL)
        )
        assertTrue(year.trendBuckets.filter { it.start.month < 9 }.all { it.progress == null })
        assertNotNull(year.trendBuckets.first { it.start.month == 9 }.progress)

        val all = StatisticsReportStateBuilder.build(
            habits = listOf(habit1), period = StatisticsPeriod.ALL,
            start = LocalDate(2026, 1, 1), end = today,
            today = today, firstWeekday = DayOfWeek.MONDAY,
            filters = StatisticsFilterState(habitStatus = StatisticsHabitStatusFilter.ALL)
        )
        assertEquals(LocalDate(2026, 9, 20), all.start)
        assertTrue(all.trendBuckets.all { it.start >= LocalDate(2026, 9, 1) })
        assertNull(StatisticsReportStateBuilder.evaluateHabitSlice(habit1,
            LocalDate(2026, 6, 1), LocalDate(2026, 6, 30)))
        assertNull(StatisticsReportStateBuilder.evaluateHabitSlice(habit1,
            LocalDate(2026, 9, 22), LocalDate(2026, 9, 23))?.progress)
        habit1.originalEntries.add(Entry(LocalDate(2026, 9, 22), Entry.NO))
        habit1.recompute()
        assertEquals(0.0, StatisticsReportStateBuilder.evaluateHabitSlice(habit1,
            LocalDate(2026, 9, 22), LocalDate(2026, 9, 22))?.progress)
    }

    @Test
    fun testIndependentTiers() {
        val today = getToday()
        // Habit 1 is MINIMUM tier
        habit1.dayTier = DayTier.MINIMUM
        habit1.originalEntries.add(Entry(today, Entry.YES_MANUAL))
        habit1.recompute()

        // Habit 2 is NORMAL tier
        habit2.dayTier = DayTier.NORMAL
        habit2.originalEntries.add(Entry(today, Entry.NO))
        habit2.recompute()

        val state = StatisticsReportStateBuilder.build(
            habits = listOf(habit1, habit2),
            period = StatisticsPeriod.DAY,
            start = today,
            end = today,
            today = today,
            firstWeekday = DayOfWeek.MONDAY,
            filters = StatisticsFilterState(habitStatus = StatisticsHabitStatusFilter.ALL)
        )

        val minProgress = state.tiers.first { it.tier == DayTier.MINIMUM }.progress
        val normalProgress = state.tiers.first { it.tier == DayTier.NORMAL }.progress
        val idealProgress = state.tiers.first { it.tier == DayTier.IDEAL }.progress

        assertEquals(1.0, minProgress)
        assertEquals(0.0, normalProgress)
        assertNull(idealProgress) // Should be null since there are no IDEAL habits
    }

    @Test
    fun testEqualWeightOfDailyAndWeeklyHabits() {
        val today = getToday()
        val monday = today.startOfWeek(DayOfWeek.MONDAY)
        
        // Habit 1: Daily, completed 1/1 day
        val daily = modelFactory.buildHabit()
        daily.type = HabitType.YES_NO
        daily.frequency = Frequency.DAILY
        daily.dayTier = DayTier.MINIMUM
        daily.originalEntries.add(Entry(monday, Entry.YES_MANUAL))
        daily.recompute()

        // Habit 2: Weekly (1/7), missed week
        val weekly = modelFactory.buildHabit()
        weekly.type = HabitType.YES_NO
        weekly.frequency = Frequency.WEEKLY
        weekly.dayTier = DayTier.MINIMUM
        weekly.originalEntries.add(Entry(monday, Entry.NO))
        weekly.recompute()

        // Evaluate over the single day Monday
        // Since L=1 < weekly frequency F=7, weekly habit uses weekly snapshot containing Monday.
        // It has 0.0 progress for the week, and daily has 1.0 progress.
        // Overall macro-average should be (1.0 + 0.0) / 2 = 0.5 (50%).
        val state = StatisticsReportStateBuilder.build(
            habits = listOf(daily, weekly),
            period = StatisticsPeriod.DAY,
            start = monday,
            end = monday,
            today = today,
            firstWeekday = DayOfWeek.MONDAY,
            filters = StatisticsFilterState(habitStatus = StatisticsHabitStatusFilter.ALL)
        )

        assertEquals(0.5, state.overallProgress)
    }

    @Test
    fun testHabitStartDates() {
        val today = getToday()
        val habit = fixtures.createEmptyHabit()
        
        // No entries, no statistics start date -> starts today
        val start1 = StatisticsReportStateBuilder.getHabitStartDate(habit, today)
        assertEquals(today, start1)

        // Add an entry in the past -> starts on that entry date
        val yesterday = today.minus(1)
        habit.originalEntries.add(Entry(yesterday, Entry.YES_MANUAL))
        val start2 = StatisticsReportStateBuilder.getHabitStartDate(habit, today)
        assertEquals(yesterday, start2)

        // Set effective statistics start date -> takes precedence
        habit.statisticsStartDate = today
        val start3 = StatisticsReportStateBuilder.getHabitStartDate(habit, today)
        assertEquals(today, start3)
    }

    @Test
    fun testUnknownAndSkipHandling() {
        val today = getToday()
        val habit = fixtures.createEmptyHabit()
        habit.frequency = Frequency.DAILY
        // Today is SKIP
        habit.originalEntries.add(Entry(today, Entry.SKIP))
        // Yesterday is UNKNOWN
        val yesterday = today.minus(1)
        habit.originalEntries.add(Entry(yesterday, Entry.UNKNOWN))
        habit.recompute()

        // Start tracking yesterday
        habit.statisticsStartDate = yesterday

        val state = StatisticsReportStateBuilder.build(
            habits = listOf(habit),
            period = StatisticsPeriod.WEEK,
            start = yesterday,
            end = today,
            today = today,
            firstWeekday = DayOfWeek.MONDAY,
            filters = StatisticsFilterState(habitStatus = StatisticsHabitStatusFilter.ALL)
        )

        // Yesterday was UNKNOWN (not completed, target active).
        // Today was SKIP (target excluded).
        // Out of 2 days, 1 is skipped, leaving 1 eligible day (yesterday).
        // Target is 1.0 * (1/2) = 0.5. Actual is 0.0 (UNKNOWN).
        // Progress should be 0.0.
        val habitRes = state.habits.firstOrNull()
        assertNotNull(habitRes)
        assertEquals(0.0, habitRes.progress)
        assertEquals(0.0, habitRes.actual)
        assertEquals(1.0, habitRes.target)
    }

    @Test
    fun testBooleanQuotasWithoutYesAuto() {
        val today = getToday()
        val habit = fixtures.createEmptyHabit()
        habit.frequency = Frequency.WEEKLY // quota is 1
        
        // Add YES_AUTO and YES_MANUAL in the same week
        val monday = today.startOfWeek(DayOfWeek.MONDAY)
        habit.originalEntries.add(Entry(monday, Entry.YES_MANUAL))
        // YES_AUTO is computed, but let's simulate it in calculated actual
        habit.computedEntries.add(Entry(monday.plus(1), Entry.YES_AUTO))
        habit.recompute()

        val periods = StatisticsReportStateBuilder.getPeriodsForReport(habit, monday, monday.plus(6), today, DayOfWeek.MONDAY)
        assertEquals(1, periods.size)
        val p = periods.first()
        val eval = StatisticsReportStateBuilder.evaluatePeriod(habit, p, today)

        // YES_AUTO (1) should not count towards actual. ONLY YES_MANUAL (2) counts.
        // So actual should be 1.0, not 2.0.
        assertEquals(1.0, eval.actual)
        assertEquals(1.0, eval.target)
        assertEquals(1.0, eval.progress)
    }

    @Test
    fun testNumericalAtLeastAndAtMost() {
        val today = getToday()
        
        // AT_LEAST target = 2.0. Actual = 3.0 -> progress = 1.0 (completed)
        val atLeastHabit = numericalHabit(NumericalHabitType.AT_LEAST, Frequency.DAILY, 2.0)
        atLeastHabit.originalEntries.add(Entry(today, 3000))
        atLeastHabit.recompute()

        // AT_MOST target = 5.0. Actual = 6.0 -> progress = 0.0 (violated)
        val atMostHabit = numericalHabit(NumericalHabitType.AT_MOST, Frequency.DAILY, 5.0)
        atMostHabit.originalEntries.add(Entry(today, 6000))
        atMostHabit.recompute()

        val state = StatisticsReportStateBuilder.build(
            habits = listOf(atLeastHabit, atMostHabit),
            period = StatisticsPeriod.DAY,
            start = today,
            end = today,
            today = today,
            firstWeekday = DayOfWeek.MONDAY,
            filters = StatisticsFilterState(habitStatus = StatisticsHabitStatusFilter.ALL)
        )

        val alRes = state.habits.first { it.habit == atLeastHabit }
        val amRes = state.habits.first { it.habit == atMostHabit }

        assertEquals(1.0, alRes.progress)
        assertEquals(StatisticsResultStatus.COMPLETED, alRes.status)

        assertEquals(0.0, amRes.progress)
        assertEquals(StatisticsResultStatus.VIOLATED, amRes.status)
    }

    @Test
    fun testGoalHistorySplitPeriods() {
        val today = getToday()
        val habit = numericalHabit(NumericalHabitType.AT_LEAST, Frequency.DAILY, 5.0)
        // Goal changes on today from 5.0 to 2.0
        habit.goalHistory = mutableListOf(
            HabitGoal(today.minus(1), Frequency.DAILY, NumericalHabitType.AT_LEAST, 5.0, "miles"),
            HabitGoal(today, Frequency.DAILY, NumericalHabitType.AT_LEAST, 2.0, "miles")
        )
        habit.statisticsStartDate = today.minus(1)
        habit.recompute()

        val start = today.minus(1)
        val periods = StatisticsReportStateBuilder.getPeriodsForReport(habit, start, today, today, DayOfWeek.MONDAY)
        
        // The goal history change breaks the periods. So we should have 2 daily periods.
        assertEquals(2, periods.size)
        assertEquals(5.0, periods.first { it.start == start }.targetValue)
        assertEquals(2.0, periods.first { it.start == today }.targetValue)
    }

    @Test
    fun testAtMostEmptyEntries() {
        val today = getToday()
        val habit = numericalHabit(NumericalHabitType.AT_MOST, Frequency.WEEKLY, 5.0)
        habit.statisticsStartDate = today.minus(6)
        habit.recompute()

        val monday = today.startOfWeek(DayOfWeek.MONDAY)
        val periods = StatisticsReportStateBuilder.getPeriodsForReport(habit, monday, monday.plus(6), today, DayOfWeek.MONDAY)
        assertEquals(1, periods.size)
        val eval = StatisticsReportStateBuilder.evaluatePeriod(habit, periods.first(), today)

        assertNull(eval.progress)
        assertEquals(StatisticsResultStatus.PENDING, eval.status)
    }

    @Test
    fun testOngoingPeriodStatus() {
        val today = getToday()
        val monday = today.startOfWeek(DayOfWeek.MONDAY)
        val testToday = monday.plus(1)
        
        val habit = numericalHabit(NumericalHabitType.AT_LEAST, Frequency.WEEKLY, 3.0)
        habit.originalEntries.add(Entry(testToday, 3000))
        habit.recompute()

        val periods = StatisticsReportStateBuilder.getPeriodsForReport(habit, monday, monday.plus(6), testToday, DayOfWeek.MONDAY)
        assertEquals(1, periods.size)
        val eval = StatisticsReportStateBuilder.evaluatePeriod(habit, periods.first(), testToday)

        assertEquals(1.0, eval.progress)
        assertEquals(StatisticsResultStatus.ON_TRACK, eval.status)
    }

    @Test
    fun testPreviousPeriodRange() {
        val today = getToday()
        val monday = today.startOfWeek(DayOfWeek.MONDAY)
        val start = monday
        val end = monday.plus(2)

        val state = StatisticsReportStateBuilder.build(
            habits = listOf(habit1),
            period = StatisticsPeriod.WEEK,
            start = start,
            end = end,
            today = today,
            firstWeekday = DayOfWeek.MONDAY,
            filters = StatisticsFilterState(habitStatus = StatisticsHabitStatusFilter.ALL)
        )

        assertNotNull(state)
    }

    @Test
    fun testAllTimeStartDate() {
        val today = getToday()
        val earliest = today.minus(20)
        habit1.statisticsStartDate = earliest
        habit1.recompute()

        val state = StatisticsReportStateBuilder.build(
            habits = listOf(habit1),
            period = StatisticsPeriod.ALL,
            start = LocalDate(1970, 1, 1),
            end = today,
            today = today,
            firstWeekday = DayOfWeek.MONDAY,
            filters = StatisticsFilterState(habitStatus = StatisticsHabitStatusFilter.ALL)
        )

        assertEquals(earliest, state.start)
    }

    @Test
    fun testUnfinishedTodayIsPending() {
        val today = getToday()
        habit1.statisticsStartDate = today
        habit1.recompute()

        val state = StatisticsReportStateBuilder.build(
            listOf(habit1), StatisticsPeriod.DAY, today, today, today,
            DayOfWeek.MONDAY, StatisticsFilterState()
        )

        assertEquals(StatisticsResultStatus.PENDING, state.habits.single().status)
        assertEquals(0, state.completedHabits)
    }

    @Test
    fun testFullySkippedDayRemainsVisibleButDoesNotLowerAverage() {
        val today = getToday()
        habit1.statisticsStartDate = today
        habit1.originalEntries.add(Entry(today, Entry.SKIP))
        habit1.recompute()

        val state = StatisticsReportStateBuilder.build(
            listOf(habit1), StatisticsPeriod.DAY, today, today, today,
            DayOfWeek.MONDAY, StatisticsFilterState()
        )

        assertEquals(StatisticsResultStatus.SKIPPED, state.habits.single().status)
        assertNull(state.overallProgress)
    }

    @Test
    fun testFutureEntriesDoNotCountTowardOngoingPeriod() {
        val today = getToday()
        habit1.statisticsStartDate = today
        habit1.originalEntries.add(Entry(today.plus(1), Entry.YES_MANUAL))
        habit1.recompute()
        val period = GoalPeriod(today, today.plus(1), Frequency.DAILY,
            NumericalHabitType.AT_LEAST, 1.0, "")

        val evaluated = StatisticsReportStateBuilder.evaluatePeriod(habit1, period, today)

        assertEquals(0.0, evaluated.actual)
        assertEquals(StatisticsResultStatus.PENDING, evaluated.status)
    }

    @Test
    fun testSphereContoursUseOnlyFilteredHabits() {
        val today = getToday()
        habit1.dayTier = DayTier.MINIMUM
        habit1.blockId = 10L
        habit2.blockId = 20L
        habit1.statisticsStartDate = today
        habit2.statisticsStartDate = today
        habit1.originalEntries.add(Entry(today, Entry.YES_MANUAL))
        habit2.originalEntries.add(Entry(today, Entry.NO))
        habit1.recompute()
        habit2.recompute()

        val state = StatisticsReportStateBuilder.build(
            listOf(habit1, habit2), StatisticsPeriod.DAY, today, today, today,
            DayOfWeek.MONDAY, StatisticsFilterState(sphereId = 10L)
        )

        assertEquals(1, state.sphereProgress.size)
        assertEquals(10L, state.sphereProgress.single().blockId)
        assertEquals(1.0, state.sphereProgress.single().progress)
    }

    @Test
    fun testUnfinishedTodayDoesNotCountAsFinishedDay() {
        val today = getToday()
        val yesterday = today.minus(1)
        val habit = fixtures.createEmptyHabit().apply {
            dayTier = DayTier.MINIMUM
            statisticsStartDate = yesterday
            originalEntries.add(Entry(yesterday, Entry.YES_MANUAL))
            recompute()
        }
        val state = StatisticsReportStateBuilder.build(
            habits = listOf(habit),
            period = StatisticsPeriod.WEEK,
            start = yesterday,
            end = today,
            today = today,
            firstWeekday = DayOfWeek.MONDAY,
            filters = StatisticsFilterState()
        )
        assertEquals(1, state.eligibleFinishedDays)
        assertEquals(1, state.fullyClosedDays)
        val todayProgress = state.dailyProgress.first { it.date == today }
        kotlin.test.assertFalse(todayProgress.isEligible)
        kotlin.test.assertFalse(todayProgress.isFullyClosed)
        kotlin.test.assertTrue(todayProgress.isToday)
    }

    @Test
    fun testSkippedDayIsExcludedAndOptionalCannotLowerMinimum() {
        val today = getToday()
        val d1 = today.minus(2)
        val d2 = today.minus(1)
        val minimum = fixtures.createEmptyHabit().apply {
            dayTier = DayTier.MINIMUM
            statisticsStartDate = d1
            originalEntries.add(Entry(d1, Entry.SKIP))
            originalEntries.add(Entry(d2, Entry.YES_MANUAL))
            recompute()
        }
        val optional = fixtures.createEmptyHabit().apply {
            dayTier = DayTier.OPTIONAL
            statisticsStartDate = d1
            recompute()
        }
        val state = StatisticsReportStateBuilder.build(
            habits = listOf(minimum, optional),
            period = StatisticsPeriod.WEEK,
            start = d1,
            end = d2,
            today = today,
            firstWeekday = DayOfWeek.MONDAY,
            filters = StatisticsFilterState()
        )
        val day1 = state.dailyProgress.first { it.date == d1 }
        kotlin.test.assertTrue(day1.isSkipped)
        kotlin.test.assertFalse(day1.isEligible)
        assertEquals(1, state.eligibleFinishedDays)
        assertEquals(1, state.fullyClosedDays)
        assertEquals(1.0, state.tierProgress[DayTier.MINIMUM])
    }

    @Test
    fun testWeeklyGoalDoesNotBecomeDailyGoal() {
        val today = getToday()
        val habit = fixtures.createEmptyHabit().apply {
            dayTier = DayTier.MINIMUM
            frequency = Frequency.WEEKLY
            statisticsStartDate = today.minus(7)
            originalEntries.add(Entry(today.minus(6), Entry.YES_MANUAL))
            recompute()
        }
        val state = StatisticsReportStateBuilder.build(
            habits = listOf(habit),
            period = StatisticsPeriod.WEEK,
            start = today.minus(7),
            end = today.minus(1),
            today = today,
            firstWeekday = DayOfWeek.MONDAY,
            filters = StatisticsFilterState()
        )
        assertEquals(0, state.eligibleFinishedDays)
        assertEquals(0, state.fullyClosedDays)
        kotlin.test.assertFalse(state.hasDailyMinimumGoals)
    }

    @Test
    fun testHigherTierCannotCompensateForOpenMinimum() {
        val today = getToday()
        val yesterday = today.minus(1)
        val minimum = fixtures.createEmptyHabit().apply {
            dayTier = DayTier.MINIMUM
            statisticsStartDate = yesterday
            recompute()
        }
        val ideal = fixtures.createEmptyHabit().apply {
            dayTier = DayTier.IDEAL
            statisticsStartDate = yesterday
            originalEntries.add(Entry(yesterday, Entry.YES_MANUAL))
            recompute()
        }
        val state = StatisticsReportStateBuilder.build(
            habits = listOf(minimum, ideal),
            period = StatisticsPeriod.DAY,
            start = yesterday,
            end = yesterday,
            today = today,
            firstWeekday = DayOfWeek.MONDAY,
            filters = StatisticsFilterState()
        )
        assertEquals(1, state.eligibleFinishedDays)
        assertEquals(0, state.fullyClosedDays)
        val day = state.dailyProgress.single()
        kotlin.test.assertFalse(day.isFullyClosed)
    }

    @Test
    fun testComparisonDeltaInPercentagePointsAndNullForAllTime() {
        val today = getToday()
        val monday = today.startOfWeek(DayOfWeek.MONDAY)
        val prevMonday = monday.minus(7)

        val habit = fixtures.createEmptyHabit().apply {
            dayTier = DayTier.MINIMUM
            statisticsStartDate = prevMonday
            // Previous week: 1 of 2 days
            originalEntries.add(Entry(prevMonday, Entry.YES_MANUAL))
            originalEntries.add(Entry(prevMonday.plus(1), Entry.NO))
            // Current week: 2 of 2 days
            originalEntries.add(Entry(monday, Entry.YES_MANUAL))
            originalEntries.add(Entry(monday.plus(1), Entry.YES_MANUAL))
            recompute()
        }

        val weekState = StatisticsReportStateBuilder.build(
            habits = listOf(habit),
            period = StatisticsPeriod.WEEK,
            start = monday,
            end = monday.plus(1),
            today = today,
            firstWeekday = DayOfWeek.MONDAY,
            filters = StatisticsFilterState()
        )
        // Current: 1.0 (100%), Prev: 0.5 (50%), delta = +0.5 (+50 pp)
        assertNotNull(weekState.comparisonDelta)
        assertEquals(0.5, weekState.comparisonDelta!!, 0.001)

        val allTimeState = StatisticsReportStateBuilder.build(
            habits = listOf(habit),
            period = StatisticsPeriod.ALL,
            start = prevMonday,
            end = monday.plus(1),
            today = today,
            firstWeekday = DayOfWeek.MONDAY,
            filters = StatisticsFilterState()
        )
        assertNull(allTimeState.comparisonDelta)
    }

    @Test
    fun testCurrentWeekComparesOnlyElapsedDays() {
        val monday = LocalDate(2026, 9, 28)
        val today = monday.plus(1)
        val previousMonday = monday.minus(7)
        val habit = fixtures.createEmptyHabit().apply {
            dayTier = DayTier.MINIMUM
            statisticsStartDate = previousMonday
            originalEntries.add(Entry(previousMonday, Entry.YES_MANUAL))
            originalEntries.add(Entry(previousMonday.plus(1), Entry.NO))
            for (offset in 2..6) originalEntries.add(Entry(previousMonday.plus(offset), Entry.YES_MANUAL))
            originalEntries.add(Entry(monday, Entry.YES_MANUAL))
            originalEntries.add(Entry(today, Entry.YES_MANUAL))
            recompute()
        }
        val state = StatisticsReportStateBuilder.build(
            habits = listOf(habit), period = StatisticsPeriod.WEEK,
            start = monday, end = monday.plus(6), today = today,
            firstWeekday = DayOfWeek.MONDAY, filters = StatisticsFilterState()
        )
        // Current: 2/2 = 1.0; Previous matched: 1/2 = 0.5. Delta = +0.5 (+50 pp)
        assertEquals(0.5, state.comparisonDelta!!, 0.001)
    }

    @Test
    fun testCompletedHistoricalWeekComparesFullWeeks() {
        val today = LocalDate(2026, 10, 10)
        val historicalWeekStart = LocalDate(2026, 9, 21)
        val historicalWeekEnd = LocalDate(2026, 9, 27)
        val prevWeekStart = historicalWeekStart.minus(7)
        val habit = fixtures.createEmptyHabit().apply {
            dayTier = DayTier.MINIMUM
            statisticsStartDate = prevWeekStart
            // Previous week: 7 of 7 days
            for (offset in 0..6) originalEntries.add(Entry(prevWeekStart.plus(offset), Entry.YES_MANUAL))
            // Historical week: 5 of 7 days
            for (offset in 0..4) originalEntries.add(Entry(historicalWeekStart.plus(offset), Entry.YES_MANUAL))
            recompute()
        }
        val state = StatisticsReportStateBuilder.build(
            habits = listOf(habit), period = StatisticsPeriod.WEEK,
            start = historicalWeekStart, end = historicalWeekEnd, today = today,
            firstWeekday = DayOfWeek.MONDAY, filters = StatisticsFilterState()
        )
        // Historical: 5/7 = 0.714; Previous full: 7/7 = 1.0. Delta = -0.286
        assertEquals(5.0 / 7.0 - 1.0, state.comparisonDelta!!, 0.001)
    }

    @Test
    fun testCurrentMonthComparesOnlyElapsedDays() {
        val today = LocalDate(2026, 9, 29)
        val monthStart = LocalDate(2026, 9, 1)
        val monthEnd = LocalDate(2026, 9, 30)
        val habit = fixtures.createEmptyHabit().apply {
            dayTier = DayTier.MINIMUM
            statisticsStartDate = LocalDate(2026, 8, 1)
            // Aug 1..29: 20 YES
            for (offset in 0..19) originalEntries.add(Entry(LocalDate(2026, 8, 1).plus(offset), Entry.YES_MANUAL))
            // Aug 30..31: 2 YES (should not be included in matched window 1..29)
            originalEntries.add(Entry(LocalDate(2026, 8, 30), Entry.YES_MANUAL))
            originalEntries.add(Entry(LocalDate(2026, 8, 31), Entry.YES_MANUAL))
            // Sep 1..29: 25 YES
            for (offset in 0..24) originalEntries.add(Entry(monthStart.plus(offset), Entry.YES_MANUAL))
            recompute()
        }
        val state = StatisticsReportStateBuilder.build(
            habits = listOf(habit), period = StatisticsPeriod.MONTH,
            start = monthStart, end = monthEnd, today = today,
            firstWeekday = DayOfWeek.MONDAY, filters = StatisticsFilterState()
        )
        // Current: 25/29; Previous matched (Aug 1..29): 20/29.
        val expected = (25.0 / 29.0) - (20.0 / 29.0)
        assertEquals(expected, state.comparisonDelta!!, 0.001)
    }

    @Test
    fun testCompletedHistoricalMonthComparesFullMonths() {
        val today = LocalDate(2026, 10, 15)
        val augStart = LocalDate(2026, 8, 1)
        val augEnd = LocalDate(2026, 8, 31)
        val habit = fixtures.createEmptyHabit().apply {
            dayTier = DayTier.MINIMUM
            statisticsStartDate = LocalDate(2026, 7, 1)
            // July (31 days): 31 YES -> 1.0
            for (offset in 0..30) originalEntries.add(Entry(LocalDate(2026, 7, 1).plus(offset), Entry.YES_MANUAL))
            // August (31 days): 20 YES -> 20/31
            for (offset in 0..19) originalEntries.add(Entry(augStart.plus(offset), Entry.YES_MANUAL))
            recompute()
        }
        val state = StatisticsReportStateBuilder.build(
            habits = listOf(habit), period = StatisticsPeriod.MONTH,
            start = augStart, end = augEnd, today = today,
            firstWeekday = DayOfWeek.MONDAY, filters = StatisticsFilterState()
        )
        val expected = (20.0 / 31.0) - 1.0
        assertEquals(expected, state.comparisonDelta!!, 0.001)
    }

    @Test
    fun testCurrentYearComparesOnlyElapsedDays() {
        val today = LocalDate(2026, 9, 29)
        val yearStart = LocalDate(2026, 1, 1)
        val yearEnd = LocalDate(2026, 12, 31)
        val habit = fixtures.createEmptyHabit().apply {
            dayTier = DayTier.MINIMUM
            statisticsStartDate = LocalDate(2025, 1, 1)
            // 2025 Jan 1..Sep 29: 200 YES
            for (offset in 0..199) originalEntries.add(Entry(LocalDate(2025, 1, 1).plus(offset), Entry.YES_MANUAL))
            // 2025 Oct..Dec: 50 YES (future relative to Sep 29, must NOT be included in matched window)
            for (offset in 273..320) originalEntries.add(Entry(LocalDate(2025, 1, 1).plus(offset), Entry.YES_MANUAL))
            // 2026 Jan 1..Sep 29: 250 YES
            for (offset in 0..249) originalEntries.add(Entry(yearStart.plus(offset), Entry.YES_MANUAL))
            recompute()
        }
        val state = StatisticsReportStateBuilder.build(
            habits = listOf(habit), period = StatisticsPeriod.YEAR,
            start = yearStart, end = yearEnd, today = today,
            firstWeekday = DayOfWeek.MONDAY, filters = StatisticsFilterState()
        )
        val elapsedDays = yearStart.daysUntil(today) + 1
        val expected = (250.0 / elapsedDays) - (200.0 / elapsedDays)
        assertEquals(expected, state.comparisonDelta!!, 0.001)
    }

    @Test
    fun testHabitStabilityOnlyInAllTime() {
        val today = getToday()
        val habit1 = fixtures.createEmptyHabit().apply {
            dayTier = DayTier.MINIMUM
            statisticsStartDate = today.minus(10)
            originalEntries.add(Entry(today.minus(1), Entry.YES_MANUAL))
            recompute()
        }
        val habit2 = fixtures.createEmptyHabit().apply {
            dayTier = DayTier.NORMAL
            statisticsStartDate = today.minus(10)
            recompute()
        }

        val yearState = StatisticsReportStateBuilder.build(
            habits = listOf(habit1, habit2),
            period = StatisticsPeriod.YEAR,
            start = LocalDate(today.year, 1, 1),
            end = LocalDate(today.year, 12, 31),
            today = today,
            firstWeekday = DayOfWeek.MONDAY,
            filters = StatisticsFilterState()
        )
        assertTrue(yearState.habitStability.isEmpty())

        val allTimeState = StatisticsReportStateBuilder.build(
            habits = listOf(habit1, habit2),
            period = StatisticsPeriod.ALL,
            start = today.minus(10),
            end = today,
            today = today,
            firstWeekday = DayOfWeek.MONDAY,
            filters = StatisticsFilterState()
        )
        assertEquals(2, allTimeState.habitStability.size)
        // Sorted descending by score
        assertTrue(allTimeState.habitStability[0].score >= allTimeState.habitStability[1].score)
    }

    @Test
    fun testDayRemainingMinimumHabits() {
        val today = getToday()
        val doneHabit = fixtures.createEmptyHabit().apply {
            dayTier = DayTier.MINIMUM
            statisticsStartDate = today
            originalEntries.add(Entry(today, Entry.YES_MANUAL))
            recompute()
        }
        val openHabit = fixtures.createEmptyHabit().apply {
            dayTier = DayTier.MINIMUM
            statisticsStartDate = today
            recompute()
        }
        val state = StatisticsReportStateBuilder.build(
            habits = listOf(doneHabit, openHabit),
            period = StatisticsPeriod.DAY,
            start = today,
            end = today,
            today = today,
            firstWeekday = DayOfWeek.MONDAY,
            filters = StatisticsFilterState()
        )
        assertEquals(1, state.remainingMinimumHabits.size)
        assertEquals(openHabit, state.remainingMinimumHabits.single().habit)
        assertEquals(Pair(1, 2), state.todayMinimumCount)
    }

    @Test
    fun testHeadlineProgressUsesOnlyMinimumTier() {
        val today = getToday()

        val minHabit = fixtures.createEmptyHabit().apply {
            dayTier = DayTier.MINIMUM
            statisticsStartDate = today
            originalEntries.add(Entry(today, Entry.YES_MANUAL))
            recompute()
        }
        val normalHabit = fixtures.createEmptyHabit().apply {
            dayTier = DayTier.NORMAL
            statisticsStartDate = today
            originalEntries.add(Entry(today, Entry.NO))
            recompute()
        }
        val idealHabit = fixtures.createEmptyHabit().apply {
            dayTier = DayTier.IDEAL
            statisticsStartDate = today
            originalEntries.add(Entry(today, Entry.NO))
            recompute()
        }
        val optionalHabit = fixtures.createEmptyHabit().apply {
            dayTier = DayTier.OPTIONAL
            statisticsStartDate = today
            originalEntries.add(Entry(today, Entry.NO))
            recompute()
        }

        val state = StatisticsReportStateBuilder.build(
            habits = listOf(minHabit, normalHabit, idealHabit, optionalHabit),
            period = StatisticsPeriod.DAY,
            start = today,
            end = today,
            today = today,
            firstWeekday = DayOfWeek.MONDAY,
            filters = StatisticsFilterState()
        )

        // Headline progress must strictly be Minimum tier progress (1.0 = 100%), not average of all tiers (0.25)
        assertEquals(1.0, state.overallProgress)
        assertEquals(1.0, state.tierProgress[DayTier.MINIMUM])
        assertEquals(0.0, state.tierProgress[DayTier.NORMAL])
        assertEquals(0.0, state.tierProgress[DayTier.IDEAL])
        assertEquals(0.0, state.tierProgress[DayTier.OPTIONAL])
    }

    @Test
    fun testSkipSemanticsForFullyClosedDays() {
        val today = getToday()
        val yesterday = today.minus(1)

        // Case A: 3 complete, 2 SKIP -> fully closed day
        val habitsA = (1..5).map { index ->
            fixtures.createEmptyHabit().apply {
                dayTier = DayTier.MINIMUM
                statisticsStartDate = yesterday
                val entryValue = if (index <= 3) Entry.YES_MANUAL else Entry.SKIP
                originalEntries.add(Entry(yesterday, entryValue))
                recompute()
            }
        }
        val stateA = StatisticsReportStateBuilder.build(
            habits = habitsA,
            period = StatisticsPeriod.DAY,
            start = yesterday,
            end = yesterday,
            today = today,
            firstWeekday = DayOfWeek.MONDAY,
            filters = StatisticsFilterState()
        )
        assertEquals(1, stateA.eligibleFinishedDays)
        assertEquals(1, stateA.fullyClosedDays)
        assertTrue(stateA.dailyProgress.single().isFullyClosed)

        // Case B: 2 complete, 1 incomplete, 2 SKIP -> NOT closed
        val habitsB = (1..5).map { index ->
            fixtures.createEmptyHabit().apply {
                dayTier = DayTier.MINIMUM
                statisticsStartDate = yesterday
                val entryValue = when (index) {
                    1, 2 -> Entry.YES_MANUAL
                    3 -> Entry.NO
                    else -> Entry.SKIP
                }
                originalEntries.add(Entry(yesterday, entryValue))
                recompute()
            }
        }
        val stateB = StatisticsReportStateBuilder.build(
            habits = habitsB,
            period = StatisticsPeriod.DAY,
            start = yesterday,
            end = yesterday,
            today = today,
            firstWeekday = DayOfWeek.MONDAY,
            filters = StatisticsFilterState()
        )
        assertEquals(1, stateB.eligibleFinishedDays)
        assertEquals(0, stateB.fullyClosedDays)
        kotlin.test.assertFalse(stateB.dailyProgress.single().isFullyClosed)

        // Case C: All applicable Minimum goals SKIP -> not an eligible finished day
        val habitsC = (1..5).map {
            fixtures.createEmptyHabit().apply {
                dayTier = DayTier.MINIMUM
                statisticsStartDate = yesterday
                originalEntries.add(Entry(yesterday, Entry.SKIP))
                recompute()
            }
        }
        val stateC = StatisticsReportStateBuilder.build(
            habits = habitsC,
            period = StatisticsPeriod.DAY,
            start = yesterday,
            end = yesterday,
            today = today,
            firstWeekday = DayOfWeek.MONDAY,
            filters = StatisticsFilterState()
        )
        assertEquals(0, stateC.eligibleFinishedDays)
        assertEquals(0, stateC.fullyClosedDays)
        val dayC = stateC.dailyProgress.single()
        assertTrue(dayC.isSkipped)
        kotlin.test.assertFalse(dayC.isEligible)
        kotlin.test.assertFalse(dayC.isFullyClosed)
    }

    @Test
    fun testSphereProgressUsesMinimumTierAndNullWhenNoMinimumHabits() {
        val today = getToday()

        // Sphere 1: 1 Minimum habit (100%), 1 Normal habit (0%)
        val minHabit = fixtures.createEmptyHabit().apply {
            blockId = 1L
            dayTier = DayTier.MINIMUM
            statisticsStartDate = today
            originalEntries.add(Entry(today, Entry.YES_MANUAL))
            recompute()
        }
        val normalHabit = fixtures.createEmptyHabit().apply {
            blockId = 1L
            dayTier = DayTier.NORMAL
            statisticsStartDate = today
            originalEntries.add(Entry(today, Entry.NO))
            recompute()
        }

        // Sphere 2: only 1 Normal habit (100%), 0 Minimum habits
        val normalHabit2 = fixtures.createEmptyHabit().apply {
            blockId = 2L
            dayTier = DayTier.NORMAL
            statisticsStartDate = today
            originalEntries.add(Entry(today, Entry.YES_MANUAL))
            recompute()
        }

        val state = StatisticsReportStateBuilder.build(
            habits = listOf(minHabit, normalHabit, normalHabit2),
            period = StatisticsPeriod.DAY,
            start = today,
            end = today,
            today = today,
            firstWeekday = DayOfWeek.MONDAY,
            filters = StatisticsFilterState()
        )

        val sphere1 = state.sphereProgress.first { it.blockId == 1L }
        val sphere2 = state.sphereProgress.first { it.blockId == 2L }

        // Sphere 1 has 2 habits total, and its Minimum tier progress is 1.0 (100%), NOT averaged with Normal (0.5)
        assertEquals(2, sphere1.habitCount)
        assertEquals(1.0, sphere1.progress)

        // Sphere 2 has 1 habit total, but NO Minimum habits, so progress is null (not 0.0)
        assertEquals(1, sphere2.habitCount)
        assertNull(sphere2.progress)
    }

    @Test
    fun testAllTimeRespectsConfiguredStatisticsStartDate() {
        val today = LocalDate(2026, 9, 28)
        val habit = modelFactory.buildHabit()
        habit.dayTier = DayTier.MINIMUM
        // Old entry from 2024
        habit.originalEntries.add(Entry(LocalDate(2024, 1, 1), Entry.YES_MANUAL))
        // But statistics start date is configured to Sep 20, 2026
        habit.statisticsStartDate = LocalDate(2026, 9, 20)
        habit.originalEntries.add(Entry(LocalDate(2026, 9, 21), Entry.YES_MANUAL))
        habit.recompute()

        val state = StatisticsReportStateBuilder.build(
            habits = listOf(habit),
            period = StatisticsPeriod.ALL,
            start = LocalDate(1970, 1, 1),
            end = today,
            today = today,
            firstWeekday = DayOfWeek.MONDAY,
            filters = StatisticsFilterState()
        )

        // Must start at configured date (Sep 20, 2026), NOT 2024!
        assertEquals(LocalDate(2026, 9, 20), state.start)
        // Since span is < 24 months, trendBuckets are monthly (not yearly 2024..2026)
        assertEquals(1, state.trendBuckets.size)
        assertEquals(LocalDate(2026, 9, 1), state.trendBuckets[0].start)
    }

    @Test
    fun testAllTimeUsesEarliestEntryWhenNoConfiguredStartDate() {
        val today = LocalDate(2026, 9, 28)
        val habit = modelFactory.buildHabit()
        habit.dayTier = DayTier.MINIMUM
        // Entry from Sep 28, 2024 (like demo data)
        habit.originalEntries.add(Entry(LocalDate(2024, 9, 28), Entry.YES_MANUAL))
        habit.originalEntries.add(Entry(today, Entry.YES_MANUAL))
        habit.recompute()

        val state = StatisticsReportStateBuilder.build(
            habits = listOf(habit),
            period = StatisticsPeriod.ALL,
            start = LocalDate(1970, 1, 1),
            end = today,
            today = today,
            firstWeekday = DayOfWeek.MONDAY,
            filters = StatisticsFilterState()
        )

        assertEquals(LocalDate(2024, 9, 28), state.start)
        // Span >= 24 months -> yearly buckets 2024, 2025, 2026
        assertEquals(3, state.trendBuckets.size)
        assertEquals(2024, state.trendBuckets[0].start.year)
        assertEquals(2025, state.trendBuckets[1].start.year)
        assertEquals(2026, state.trendBuckets[2].start.year)
    }

    @Test
    fun testDayTierScopeCumulativeSemantics() {
        val today = LocalDate(2026, 9, 29)
        val hMin = fixtures.createEmptyHabit().apply {
            dayTier = DayTier.MINIMUM
            statisticsStartDate = today
            originalEntries.add(Entry(today, Entry.YES_MANUAL))
            recompute()
        }
        val hNorm = fixtures.createEmptyHabit().apply {
            dayTier = DayTier.NORMAL
            statisticsStartDate = today
            originalEntries.add(Entry(today, Entry.NO))
            recompute()
        }
        val hIdeal = fixtures.createEmptyHabit().apply {
            dayTier = DayTier.IDEAL
            statisticsStartDate = today
            originalEntries.add(Entry(today, Entry.NO))
            recompute()
        }
        val hOpt = fixtures.createEmptyHabit().apply {
            dayTier = DayTier.OPTIONAL
            statisticsStartDate = today
            originalEntries.add(Entry(today, Entry.YES_MANUAL))
            recompute()
        }

        val allHabits = listOf(hMin, hNorm, hIdeal, hOpt)

        // 1. MINIMUM scope
        val stateMin = StatisticsReportStateBuilder.build(
            habits = allHabits, period = StatisticsPeriod.DAY,
            start = today, end = today, today = today, firstWeekday = DayOfWeek.MONDAY,
            filters = StatisticsFilterState(tierScope = DayTierScope.MINIMUM)
        )
        assertEquals(1.0, stateMin.overallProgress)
        assertEquals(1, stateMin.matchingHabits)
        // Independent tier breakdown
        assertEquals(1.0, stateMin.tierProgress[DayTier.MINIMUM])
        assertEquals(0.0, stateMin.tierProgress[DayTier.NORMAL])
        assertEquals(0.0, stateMin.tierProgress[DayTier.IDEAL])
        assertEquals(1.0, stateMin.tierProgress[DayTier.OPTIONAL])

        // 2. UP_TO_NORMAL scope: Minimum (1.0) + Normal (0.0) -> average 0.5
        val stateNorm = StatisticsReportStateBuilder.build(
            habits = allHabits, period = StatisticsPeriod.DAY,
            start = today, end = today, today = today, firstWeekday = DayOfWeek.MONDAY,
            filters = StatisticsFilterState(tierScope = DayTierScope.UP_TO_NORMAL)
        )
        assertEquals(0.5, stateNorm.overallProgress)
        assertEquals(2, stateNorm.matchingHabits)

        // 3. UP_TO_IDEAL scope: Minimum (1.0) + Normal (0.0) + Ideal (0.0) -> average 1/3
        val stateIdeal = StatisticsReportStateBuilder.build(
            habits = allHabits, period = StatisticsPeriod.DAY,
            start = today, end = today, today = today, firstWeekday = DayOfWeek.MONDAY,
            filters = StatisticsFilterState(tierScope = DayTierScope.UP_TO_IDEAL)
        )
        assertEquals(1.0 / 3.0, stateIdeal.overallProgress!!, 0.0001)
        assertEquals(3, stateIdeal.matchingHabits)

        // 4. ALL scope: Minimum (1.0) + Normal (0.0) + Ideal (0.0) + Optional (1.0) -> average 2/4 = 0.5
        val stateAll = StatisticsReportStateBuilder.build(
            habits = allHabits, period = StatisticsPeriod.DAY,
            start = today, end = today, today = today, firstWeekday = DayOfWeek.MONDAY,
            filters = StatisticsFilterState(tierScope = DayTierScope.ALL)
        )
        assertEquals(0.5, stateAll.overallProgress)
        assertEquals(4, stateAll.matchingHabits)
    }

    @Test
    fun testTiersDisabledCalculatesOverAllHabits() {
        val today = LocalDate(2026, 9, 29)
        val hMin = fixtures.createEmptyHabit().apply {
            dayTier = DayTier.MINIMUM
            statisticsStartDate = today
            originalEntries.add(Entry(today, Entry.YES_MANUAL))
            recompute()
        }
        val hNorm = fixtures.createEmptyHabit().apply {
            dayTier = DayTier.NORMAL
            statisticsStartDate = today
            originalEntries.add(Entry(today, Entry.NO))
            recompute()
        }
        val allHabits = listOf(hMin, hNorm)

        // With dayTiersEnabled = false, calculates over all habits even if tierScope is MINIMUM
        val state = StatisticsReportStateBuilder.build(
            habits = allHabits, period = StatisticsPeriod.DAY,
            start = today, end = today, today = today, firstWeekday = DayOfWeek.MONDAY,
            filters = StatisticsFilterState(tierScope = DayTierScope.MINIMUM, dayTiersEnabled = false)
        )
        assertEquals(0.5, state.overallProgress)
        assertEquals(2, state.matchingHabits)
    }

    @Test
    fun testSpheresDisabledYieldsEmptySphereProgress() {
        val today = LocalDate(2026, 9, 29)
        val h = fixtures.createEmptyHabit().apply {
            blockId = 1L
            dayTier = DayTier.MINIMUM
            statisticsStartDate = today
            originalEntries.add(Entry(today, Entry.YES_MANUAL))
            recompute()
        }
        val state = StatisticsReportStateBuilder.build(
            habits = listOf(h), period = StatisticsPeriod.DAY,
            start = today, end = today, today = today, firstWeekday = DayOfWeek.MONDAY,
            filters = StatisticsFilterState(spheresEnabled = false)
        )
        assertTrue(state.sphereProgress.isEmpty())
    }

    private fun numericalHabit(
        targetType: NumericalHabitType,
        frequency: Frequency,
        targetValue: Double
    ): Habit {
        val habit = modelFactory.buildHabit()
        habit.type = HabitType.NUMERICAL
        habit.frequency = frequency
        habit.targetType = targetType
        habit.targetValue = targetValue
        habit.unit = "miles"
        return habit
    }
}
