/* Copyright (C) 2026 Álinson Santos Xavier and contributors. GPL-3.0-or-later. */
package org.isoron.uhabits.core.models

import org.isoron.platform.time.DayOfWeek
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.setToday
import org.isoron.uhabits.core.BaseUnitTest
import kotlin.math.abs
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QuantitativeGoalsBoundaryTest : BaseUnitTest() {

    private val baseDate = LocalDate(2026, 10, 19) // Monday

    @BeforeTest
    override fun setUp() {
        super.setUp()
        setToday(baseDate)
    }

    @Test
    fun testAtLeastVsAtMostExactBoundarySemantics() {
        // Target: 60.0 units
        val habitAtLeast = fixtures.createEmptyNumericalHabit(NumericalHabitType.AT_LEAST).apply {
            targetValue = 60.0
            frequency = Frequency.DAILY
        }
        val habitAtMost = fixtures.createEmptyNumericalHabit(NumericalHabitType.AT_MOST).apply {
            targetValue = 60.0
            frequency = Frequency.DAILY
        }

        val d1 = baseDate
        val d2 = baseDate.plus(1)
        val d3 = baseDate.plus(2)

        // d1: exactly 59.999 (scaled: 59999)
        habitAtLeast.computedEntries.add(Entry(d1, 59999))
        habitAtMost.computedEntries.add(Entry(d1, 59999))

        // d2: exactly 60.000 (scaled: 60000)
        habitAtLeast.computedEntries.add(Entry(d2, 60000))
        habitAtMost.computedEntries.add(Entry(d2, 60000))

        // d3: exactly 60.001 (scaled: 60001)
        habitAtLeast.computedEntries.add(Entry(d3, 60001))
        habitAtMost.computedEntries.add(Entry(d3, 60001))

        // 59.999: AtMost is completed (<= 60.0), AtLeast is NOT completed (< 60.0)
        assertFalse(habitAtLeast.isCompletedOn(d1), "59.999 should not complete AT_LEAST 60.0")
        assertTrue(habitAtMost.isCompletedOn(d1), "59.999 should complete AT_MOST 60.0")

        // 60.000: Both are completed at exact boundary (>= and <=)
        assertTrue(habitAtLeast.isCompletedOn(d2), "60.000 must complete AT_LEAST 60.0 at exact boundary")
        assertTrue(habitAtMost.isCompletedOn(d2), "60.000 must complete AT_MOST 60.0 at exact boundary")

        // 60.001: AtMost is NOT completed (> 60.0), AtLeast is completed (> 60.0)
        assertTrue(habitAtLeast.isCompletedOn(d3), "60.001 must complete AT_LEAST 60.0")
        assertFalse(habitAtMost.isCompletedOn(d3), "60.001 should not complete AT_MOST 60.0")
    }

    @Test
    fun testZeroTargetValueBoundaries() {
        val habitAtLeast = fixtures.createEmptyNumericalHabit(NumericalHabitType.AT_LEAST).apply {
            targetValue = 0.0
            frequency = Frequency.DAILY
        }
        val habitAtMost = fixtures.createEmptyNumericalHabit(NumericalHabitType.AT_MOST).apply {
            targetValue = 0.0
            frequency = Frequency.DAILY
        }

        val d0 = baseDate
        val dPos = baseDate.plus(1)

        habitAtLeast.computedEntries.add(Entry(d0, 0))
        habitAtMost.computedEntries.add(Entry(d0, 0))

        habitAtLeast.computedEntries.add(Entry(dPos, 1)) // 0.001
        habitAtMost.computedEntries.add(Entry(dPos, 1))

        // When target is 0.0, 0 is satisfied for both
        assertTrue(habitAtLeast.isCompletedOn(d0), "0.0 completes AT_LEAST 0.0")
        assertTrue(habitAtMost.isCompletedOn(d0), "0.0 completes AT_MOST 0.0")

        // When entry > 0, AT_LEAST is satisfied, but AT_MOST 0.0 is violated
        assertTrue(habitAtLeast.isCompletedOn(dPos), "0.001 completes AT_LEAST 0.0")
        assertFalse(habitAtMost.isCompletedOn(dPos), "0.001 violates AT_MOST 0.0")
    }

    @Test
    fun testWeeklyAtMostPeriodAggregation() {
        // Target: AT_MOST 180.0 minutes per week (denominator = 7)
        val habit = fixtures.createEmptyNumericalHabit(NumericalHabitType.AT_MOST).apply {
            targetValue = 180.0
            frequency = Frequency(1, 7)
        }

        // Add entries for Monday to Sunday (7 days)
        // Mon: 30m, Tue: 40m, Wed: 50m, Thu: 40m, Fri: 20m -> Sum = 180m (exactly 180.0)
        val monday = baseDate.startOfWeek(DayOfWeek.MONDAY)
        habit.computedEntries.add(Entry(monday, 30_000))
        habit.computedEntries.add(Entry(monday.plus(1), 40_000))
        habit.computedEntries.add(Entry(monday.plus(2), 50_000))
        habit.computedEntries.add(Entry(monday.plus(3), 40_000))
        habit.computedEntries.add(Entry(monday.plus(4), 20_000))
        habit.computedEntries.add(Entry(monday.plus(5), 0))
        habit.computedEntries.add(Entry(monday.plus(6), 0))

        // At exactly 180.0, each day in the week should evaluate isCompletedOn == true
        val actual = habit.periodActualOn(monday)
        assertEquals(180.0, actual)
        assertTrue(habit.isCompletedOn(monday), "Weekly sum of 180.0 should satisfy AT_MOST 180.0")

        // Exceed by 1 minute on Sunday: 180 + 1 = 181.0
        habit.computedEntries.add(Entry(monday.plus(6), 1_000))
        val exceededActual = habit.periodActualOn(monday)
        assertEquals(181.0, exceededActual)
        assertFalse(habit.isCompletedOn(monday), "Weekly sum of 181.0 should violate AT_MOST 180.0")
    }

    @Test
    fun testMonthlyAtMostPeriodAggregation() {
        // Target: AT_MOST 500.0 per month (denominator = 30)
        val habit = fixtures.createEmptyNumericalHabit(NumericalHabitType.AT_MOST).apply {
            targetValue = 500.0
            frequency = Frequency(1, 30)
        }

        val firstOfMonth = LocalDate(2026, 10, 1)
        habit.computedEntries.add(Entry(firstOfMonth, 200_000))
        habit.computedEntries.add(Entry(firstOfMonth.plus(10), 200_000))
        habit.computedEntries.add(Entry(firstOfMonth.plus(20), 100_000))

        // Sum = 500.0
        val actual = habit.periodActualOn(firstOfMonth)
        assertEquals(500.0, actual)
        assertTrue(habit.isCompletedOn(firstOfMonth), "Monthly sum of 500.0 satisfies AT_MOST 500.0")

        // Exceed by 0.5
        habit.computedEntries.add(Entry(firstOfMonth.plus(25), 500))
        val actualExceeded = habit.periodActualOn(firstOfMonth)
        assertEquals(500.5, actualExceeded)
        assertFalse(habit.isCompletedOn(firstOfMonth), "Monthly sum of 500.5 violates AT_MOST 500.0")
    }

    @Test
    fun testScoreListAtMostLinearDecaySemantics() {
        val habit = fixtures.createEmptyNumericalHabit(NumericalHabitType.AT_MOST).apply {
            targetValue = 60.0
            frequency = Frequency.DAILY
        }

        val scoreList = ScoreList()

        // Populate 10 days of entries with varying amounts:
        // Day 0..2: 40.0 (under target 60.0) -> percentageCompleted = 1.0
        // Day 3..4: 60.0 (exact target) -> percentageCompleted = 1.0
        // Day 5: 90.0 (halfway to 2x target 120.0) -> percentageCompleted = 0.5
        // Day 6: 120.0 (exact 2x target) -> percentageCompleted = 0.0
        // Day 7: 150.0 (exceeds 2x target) -> percentageCompleted = 0.0 coerced
        val start = baseDate
        habit.computedEntries.add(Entry(start, 40_000))
        habit.computedEntries.add(Entry(start.plus(1), 40_000))
        habit.computedEntries.add(Entry(start.plus(2), 40_000))
        habit.computedEntries.add(Entry(start.plus(3), 60_000))
        habit.computedEntries.add(Entry(start.plus(4), 60_000))
        habit.computedEntries.add(Entry(start.plus(5), 90_000))
        habit.computedEntries.add(Entry(start.plus(6), 120_000))
        habit.computedEntries.add(Entry(start.plus(7), 150_000))

        scoreList.recompute(habit, habit.computedEntries, start, start.plus(7))

        val scoreDay2 = scoreList.get(start.plus(2)).value
        val scoreDay4 = scoreList.get(start.plus(4)).value
        val scoreDay5 = scoreList.get(start.plus(5)).value
        val scoreDay6 = scoreList.get(start.plus(6)).value
        val scoreDay7 = scoreList.get(start.plus(7)).value

        // Score starts at 1.0 for AT_MOST (previousValue initialized to 1.0)
        // Day 2 and Day 4 maintain score near 1.0 because completed = 1.0
        assertTrue(scoreDay2 > 0.95, "Score should remain high when under AT_MOST target")
        assertTrue(scoreDay4 > 0.95, "Score should remain high at exact AT_MOST target")

        // Exceeding target decays score
        assertTrue(scoreDay5 < scoreDay4, "Score should decay when exceeding AT_MOST target")
        assertTrue(scoreDay6 < scoreDay5, "Score should decay further when reaching 2x AT_MOST target")
        assertTrue(scoreDay7 < scoreDay6, "Score should decay when exceeding 2x AT_MOST target")
    }

    @Test
    fun testStreakListAtMostContinuityAndBreaks() {
        val habit = fixtures.createEmptyNumericalHabit(NumericalHabitType.AT_MOST).apply {
            targetValue = 50.0
            frequency = Frequency.DAILY
        }

        val streakList = StreakList()

        // Days 0..2: 30.0, 45.0, 50.0 (all <= 50.0) -> streak of 3 days
        // Day 3: 55.0 (> 50.0) -> break
        // Days 4..6: 20.0, 10.0, 50.0 (all <= 50.0) -> streak of 3 days
        val start = baseDate
        habit.computedEntries.add(Entry(start, 30_000))
        habit.computedEntries.add(Entry(start.plus(1), 45_000))
        habit.computedEntries.add(Entry(start.plus(2), 50_000))
        habit.computedEntries.add(Entry(start.plus(3), 55_000))
        habit.computedEntries.add(Entry(start.plus(4), 20_000))
        habit.computedEntries.add(Entry(start.plus(5), 10_000))
        habit.computedEntries.add(Entry(start.plus(6), 50_000))

        streakList.recompute(habit, habit.computedEntries, start, start.plus(6))

        val bestStreaks = streakList.getBest(5)
        assertEquals(2, bestStreaks.size, "Should find exactly two separate streaks")

        val latest = streakList.getLatest()
        assertEquals(start.plus(4), latest?.start)
        assertEquals(start.plus(6), latest?.end)
        assertEquals(3, latest?.length)
    }

    @Test
    fun testUnknownAndSkipEntriesInAtMostStreaks() {
        val habit = fixtures.createEmptyNumericalHabit(NumericalHabitType.AT_MOST).apply {
            targetValue = 50.0
            frequency = Frequency.DAILY
        }

        val streakList = StreakList()
        val start = baseDate

        // Day 0: 30.0 (satisfied)
        // Day 1: UNKNOWN -> isCompletedOn returns false -> breaks streak
        // Day 2: 40.0 (satisfied)
        // Day 3: SKIP -> isCompletedOn returns false
        // Day 4: 50.0 (satisfied)
        habit.computedEntries.add(Entry(start, 30_000))
        habit.computedEntries.add(Entry(start.plus(1), Entry.UNKNOWN))
        habit.computedEntries.add(Entry(start.plus(2), 40_000))
        habit.computedEntries.add(Entry(start.plus(3), Entry.SKIP))
        habit.computedEntries.add(Entry(start.plus(4), 50_000))

        assertFalse(habit.isCompletedOn(start.plus(1)), "UNKNOWN entry should not be completed")
        assertFalse(habit.isCompletedOn(start.plus(3)), "SKIP entry should not be completed")

        streakList.recompute(habit, habit.computedEntries, start, start.plus(4))
        val streaks = streakList.getBest(10)
        assertEquals(3, streaks.size, "UNKNOWN and SKIP days must segment streaks")
        for (s in streaks) {
            assertEquals(1, s.length, "Each streak should be 1 day long due to gaps")
        }
    }
}
