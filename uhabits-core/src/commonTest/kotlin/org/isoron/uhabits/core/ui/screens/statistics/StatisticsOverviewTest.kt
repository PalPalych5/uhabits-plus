/*
 * Copyright (C) 2016-2025 Álinson Santos Xavier <git@axavier.org>
 *
 * This file is part of Loop Habit Tracker.
 *
 * Loop Habit Tracker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 */
package org.isoron.uhabits.core.ui.screens.statistics

import org.isoron.platform.time.DayOfWeek
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.getToday
import org.isoron.uhabits.core.BaseUnitTest
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Frequency
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitGoal
import org.isoron.uhabits.core.models.HabitType
import org.isoron.uhabits.core.models.NumericalHabitType
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StatisticsOverviewTest : BaseUnitTest() {
    private lateinit var habit: Habit

    @BeforeTest
    override fun setUp() {
        super.setUp()
        habit = fixtures.createShortHabit()
    }

    @Test
    fun testRangeOverviewAggregation() {
        val today = getToday()
        // Mark habit completed today
        habit.originalEntries.add(Entry(today, Entry.YES_MANUAL))
        habit.recompute()

        // Test single day range
        val stateSingle = StatisticsOverviewStateBuilder.build(listOf(habit), today, today)
        assertEquals(1, stateSingle.completedCount)
        assertEquals(1, stateSingle.totalCount)

        // Test multiple days range
        val yesterday = today.minus(1)
        val stateRange = StatisticsOverviewStateBuilder.build(listOf(habit), yesterday, today)
        // Yesterday is UNKNOWN/NO, not completed
        assertEquals(1, stateRange.completedCount)
        assertEquals(2, stateRange.totalCount)
    }

    @Test
    fun testBooleanCompletedMissedSkippedAndUnknown() {
        val today = getToday()
        val habit = fixtures.createEmptyHabit()
        habit.originalEntries.add(Entry(today, Entry.YES_MANUAL))
        habit.originalEntries.add(Entry(today.minus(1), Entry.NO))
        habit.originalEntries.add(Entry(today.minus(2), Entry.SKIP))
        habit.recompute()

        val result = StatisticsCompletionPolicy.evaluate(
            habit,
            today.minus(3),
            today,
            DayOfWeek.SUNDAY
        ).associateBy { it.date }

        assertFalse(result[today.minus(3)]!!.countable)
        assertTrue(result[today.minus(2)]!!.skipped)
        assertFalse(result[today.minus(2)]!!.countable)
        assertTrue(result[today.minus(1)]!!.countable)
        assertTrue(result[today.minus(1)]!!.failed)
        assertTrue(result[today]!!.completed)
    }

    @Test
    fun testNumericalAtLeast() {
        val today = getToday()
        val habit = numericalHabit(NumericalHabitType.AT_LEAST, Frequency.DAILY, 2.0)
        habit.originalEntries.add(Entry(today, 3000))
        habit.originalEntries.add(Entry(today.minus(1), 1000))
        habit.recompute()

        val result = StatisticsCompletionPolicy.evaluate(
            habit,
            today.minus(1),
            today,
            DayOfWeek.SUNDAY
        ).associateBy { it.date }

        assertTrue(result[today]!!.completed)
        assertTrue(result[today.minus(1)]!!.failed)
    }

    @Test
    fun testNumericalAtMostDaily() {
        val today = getToday()
        val habit = numericalHabit(NumericalHabitType.AT_MOST, Frequency.DAILY, 2.0)
        habit.originalEntries.add(Entry(today, 1000))
        habit.originalEntries.add(Entry(today.minus(1), 3000))
        habit.recompute()

        val result = StatisticsCompletionPolicy.evaluate(
            habit,
            today.minus(1),
            today,
            DayOfWeek.SUNDAY
        ).associateBy { it.date }

        assertTrue(result[today]!!.completed)
        assertTrue(result[today.minus(1)]!!.limitViolation)
    }

    @Test
    fun testNumericalAtMostWeeklyIsPeriodBased() {
        val today = getToday()
        val weekStart = today.startOfWeek(DayOfWeek.SUNDAY)
        val habit = numericalHabit(NumericalHabitType.AT_MOST, Frequency(1, 7), 5.0)
        habit.originalEntries.add(Entry(weekStart, 2000))
        habit.originalEntries.add(Entry(weekStart.plus(1), 2000))
        habit.recompute()

        val result = StatisticsCompletionPolicy.evaluate(
            habit,
            weekStart,
            weekStart.plus(6),
            DayOfWeek.SUNDAY
        )

        assertEquals(1, result.count { it.countable })
        assertTrue(result.single { it.countable }.completed)
    }

    @Test
    fun testNumericalAtMostMonthlyIsPeriodBased() {
        val today = getToday()
        val monthStart = today.startOfMonth()
        val habit = numericalHabit(NumericalHabitType.AT_MOST, Frequency(1, 30), 5.0)
        habit.originalEntries.add(Entry(monthStart, 3000))
        habit.originalEntries.add(Entry(monthStart.plus(1), 4000))
        habit.recompute()

        val result = StatisticsCompletionPolicy.evaluate(
            habit,
            monthStart,
            monthStart.plus(monthStart.monthLength - 1),
            DayOfWeek.SUNDAY
        )

        assertEquals(1, result.count { it.countable })
        assertTrue(result.single { it.countable }.limitViolation)
    }

    @Test
    fun testSkipAndUnknownAreExcludedFromDenominator() {
        val today = getToday()
        val habit = fixtures.createEmptyHabit()
        habit.originalEntries.add(Entry(today.minus(1), Entry.SKIP))
        habit.recompute()

        val result = StatisticsCompletionPolicy.evaluate(
            habit,
            today.minus(1),
            today,
            DayOfWeek.SUNDAY
        )

        assertEquals(0, result.count { it.countable })
        assertEquals(1, result.count { it.skipped })
    }

    @Test
    fun testStatisticsStartDatesExcludeEarlierEntries() {
        val today = getToday()
        val habit = fixtures.createEmptyHabit()
        habit.globalStatisticsStartDate = today.minus(1)
        habit.statisticsStartDate = today
        habit.originalEntries.add(Entry(today.minus(1), Entry.YES_MANUAL))
        habit.originalEntries.add(Entry(today, Entry.YES_MANUAL))
        habit.recompute()

        val result = StatisticsCompletionPolicy.evaluate(
            habit,
            today.minus(1),
            today,
            DayOfWeek.SUNDAY
        )

        assertEquals(listOf(today), result.filter { it.countable }.map { it.date })
    }

    @Test
    fun testHistoricalGoalTargetChange() {
        val today = getToday()
        val habit = numericalHabit(NumericalHabitType.AT_LEAST, Frequency.DAILY, 5.0)
        habit.goalHistory = mutableListOf(
            HabitGoal(today.minus(1), Frequency.DAILY, NumericalHabitType.AT_LEAST, 5.0, "miles"),
            HabitGoal(today, Frequency.DAILY, NumericalHabitType.AT_LEAST, 2.0, "miles")
        )
        habit.originalEntries.add(Entry(today.minus(1), 3000))
        habit.originalEntries.add(Entry(today, 3000))
        habit.recompute()

        val result = StatisticsCompletionPolicy.evaluate(
            habit,
            today.minus(1),
            today,
            DayOfWeek.SUNDAY
        ).associateBy { it.date }

        assertTrue(result[today.minus(1)]!!.failed)
        assertTrue(result[today]!!.completed)
    }

    @Test
    fun testHistoricalGoalTypeChangeFromAtLeastToAtMost() {
        val today = getToday()
        val habit = numericalHabit(NumericalHabitType.AT_LEAST, Frequency.DAILY, 2.0)
        habit.goalHistory = mutableListOf(
            HabitGoal(today.minus(1), Frequency.DAILY, NumericalHabitType.AT_LEAST, 2.0, "miles"),
            HabitGoal(today, Frequency.DAILY, NumericalHabitType.AT_MOST, 2.0, "miles")
        )
        habit.originalEntries.add(Entry(today.minus(1), 3000))
        habit.originalEntries.add(Entry(today, 3000))
        habit.recompute()

        val result = StatisticsCompletionPolicy.evaluate(
            habit,
            today.minus(1),
            today,
            DayOfWeek.SUNDAY
        ).associateBy { it.date }

        assertTrue(result[today.minus(1)]!!.completed)
        assertTrue(result[today]!!.limitViolation)
    }

    @Test
    fun testOverviewUsesSamePolicyForWeeklyAtMost() {
        val today = getToday()
        val weekStart = today.startOfWeek(DayOfWeek.SUNDAY)
        val habit = numericalHabit(NumericalHabitType.AT_MOST, Frequency(1, 7), 5.0)
        habit.originalEntries.add(Entry(weekStart, 2000))
        habit.originalEntries.add(Entry(weekStart.plus(1), 2000))
        habit.recompute()

        val overview = StatisticsOverviewStateBuilder.build(
            listOf(habit),
            weekStart,
            weekStart.plus(6),
            DayOfWeek.SUNDAY
        )
        val policy = StatisticsCompletionPolicy.evaluate(
            habit,
            weekStart,
            weekStart.plus(6),
            DayOfWeek.SUNDAY
        )

        assertEquals(policy.count { it.countable }, overview.totalCount)
        assertEquals(policy.count { it.completed }, overview.completedCount)
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
