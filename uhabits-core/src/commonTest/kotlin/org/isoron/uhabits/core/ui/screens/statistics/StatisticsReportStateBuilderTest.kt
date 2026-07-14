package org.isoron.uhabits.core.ui.screens.statistics

import org.isoron.platform.time.DayOfWeek
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.getToday
import org.isoron.uhabits.core.BaseUnitTest
import org.isoron.uhabits.core.models.DayTier
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
        daily.originalEntries.add(Entry(monday, Entry.YES_MANUAL))
        daily.recompute()

        // Habit 2: Weekly (1/7), missed week
        val weekly = modelFactory.buildHabit()
        weekly.type = HabitType.YES_NO
        weekly.frequency = Frequency.WEEKLY
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
