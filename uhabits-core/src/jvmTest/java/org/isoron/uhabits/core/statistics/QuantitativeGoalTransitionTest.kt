/* Copyright (C) 2026 Álinson Santos Xavier and contributors. GPL-3.0-or-later. */
package org.isoron.uhabits.core.statistics

import org.isoron.platform.time.DayOfWeek
import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Frequency
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitGoal
import org.isoron.uhabits.core.models.HabitType
import org.isoron.uhabits.core.models.NumericalHabitType
import org.isoron.uhabits.core.models.memory.MemoryModelFactory
import org.isoron.uhabits.core.models.projection.EffectiveHabitDay
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class QuantitativeGoalTransitionTest {

    private val factory = MemoryModelFactory()

    private fun createQueryContext(
        from: LocalDate,
        to: LocalDate,
        timezoneId: String = "UTC",
        firstWeekday: DayOfWeek = DayOfWeek.MONDAY,
        now: Long = 1793232000000L // 2026-10-28 12:00:00 UTC
    ): StatisticsQueryContext {
        return StatisticsQueryContext(
            from = from,
            to = to,
            timezoneId = timezoneId,
            firstWeekday = firstWeekday,
            habitHourOffset = 0,
            habitMinuteOffset = 0,
            datasetId = "test-dataset",
            revision = 1L,
            now = now,
        )
    }

    private fun buildDay(
        date: LocalDate,
        value: Int,
        manualValue: Long? = null,
        contributionValue: Long = 0L,
        isNumerical: Boolean = true,
        includedInStatistics: Boolean = true,
    ): EffectiveHabitDay {
        return EffectiveHabitDay(
            manualEntry = Entry(date, value),
            manualValue = manualValue ?: (if (value >= 0) value.toLong() else null),
            contributionValue = contributionValue,
            totalValue = (manualValue ?: (if (value >= 0) value.toLong() else null))?.let { it + contributionValue },
            contributions = emptyList(),
            isNumerical = isNumerical,
            includedInStatistics = includedInStatistics,
        )
    }

    @Test
    fun testDailyToWeeklyGoalTransitionInStatistics() {
        // Goal 1: From 2026-10-01 to 2026-10-11 -> Daily 60 min
        // Goal 2: From 2026-10-12 (Monday) onwards -> Weekly 180 min
        val transitionDate = LocalDate(2026, 10, 12)

        val habit = factory.buildHabit().apply {
            uuid = "habit-daily-to-weekly"
            name = "Exercise"
            type = HabitType.NUMERICAL
            targetType = NumericalHabitType.AT_LEAST
            targetValue = 180.0
            frequency = Frequency(1, 7)
            unit = "min"
            goalHistory = mutableListOf(
                HabitGoal(LocalDate(2026, 10, 1), Frequency.DAILY, NumericalHabitType.AT_LEAST, 60.0, "min"),
                HabitGoal(transitionDate, Frequency(1, 7), NumericalHabitType.AT_LEAST, 180.0, "min")
            )
        }

        // Query covering 2026-10-05 (Mon) to 2026-10-25 (Sun)
        val fromDate = LocalDate(2026, 10, 5)
        val toDate = LocalDate(2026, 10, 25)

        val days = mutableListOf<EffectiveHabitDay>()
        var d = fromDate
        while (d <= toDate) {
            // Under daily goal (10-05..10-11): 70m daily (satisfies 60m)
            // Under weekly goal (10-12..10-25): 40m on Mon, Wed, Fri, Sat -> 160m per week (under 180m)
            val minutes = if (d < transitionDate) {
                70_000
            } else {
                when (d.dayOfWeek) {
                    DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY -> 40_000
                    else -> 0
                }
            }
            days.add(buildDay(d, minutes))
            d = d.plus(1)
        }

        val context = createQueryContext(from = fromDate, to = toDate)
        val result = HabitCycleStatisticsAggregator.aggregate(
            context,
            listOf(StatisticsHabitInput(habit, days, observationStart = fromDate))
        )

        // 1. Daily cycles exist for the pre-transition period (10-05 to 10-11: 7 daily cycles)
        assertTrue(result.daily.satisfiedCount >= 7, "Daily cycles before transition should be satisfied")

        // 2. Weekly cycles exist for the post-transition period
        // Week 1: 10-12 to 10-18 (total 160m < 180m) -> NOT_SATISFIED
        // Week 2: 10-19 to 10-25 (total 160m < 180m) -> NOT_SATISFIED
        assertEquals(2, result.weekly.notSatisfiedCount, "Weekly cycles with 160m should be NOT_SATISFIED for 180m target")

        // Check that cycles are properly mapped to their cadences
        val dailyCycles = result.allCycles.filter { it.cadence == HabitCadence.DAILY }
        val weeklyCycles = result.allCycles.filter { it.cadence == HabitCadence.WEEKLY }
        assertTrue(dailyCycles.isNotEmpty(), "Daily cycles must be present before transition")
        assertTrue(weeklyCycles.isNotEmpty(), "Weekly cycles must be present after transition")

        for (dc in dailyCycles) {
            assertTrue(dc.startDate < transitionDate, "Daily cycles must belong to pre-transition dates")
        }
        for (wc in weeklyCycles) {
            assertTrue(wc.startDate >= transitionDate, "Weekly cycles must belong to post-transition dates")
        }
    }

    @Test
    fun testAtMostQuantitativeGoalTransition() {
        // Goal 1: From 2026-10-05 to 2026-10-11 -> Daily AT_MOST 60 min
        // Goal 2: From 2026-10-12 (Monday) to 2026-10-25 -> Weekly AT_MOST 180 min
        val transitionDate = LocalDate(2026, 10, 12)

        val habit = factory.buildHabit().apply {
            uuid = "habit-at-most-transition"
            name = "Screen Time"
            type = HabitType.NUMERICAL
            targetType = NumericalHabitType.AT_MOST
            targetValue = 180.0
            frequency = Frequency(1, 7)
            unit = "min"
            goalHistory = mutableListOf(
                HabitGoal(LocalDate(2026, 10, 5), Frequency.DAILY, NumericalHabitType.AT_MOST, 60.0, "min"),
                HabitGoal(transitionDate, Frequency(1, 7), NumericalHabitType.AT_MOST, 180.0, "min")
            )
        }

        val fromDate = LocalDate(2026, 10, 5)
        val toDate = LocalDate(2026, 10, 25)

        val days = mutableListOf<EffectiveHabitDay>()
        var d = fromDate
        while (d <= toDate) {
            val minutes = when {
                // Pre-transition (Daily AT_MOST 60m):
                // 10-05: 45m (<= 60m -> satisfied)
                // 10-06: 70m (> 60m -> not satisfied)
                // 10-07..10-11: 50m (<= 60m -> satisfied)
                d == LocalDate(2026, 10, 5) -> 45_000
                d == LocalDate(2026, 10, 6) -> 70_000
                d < transitionDate -> 50_000

                // Post-transition Week 1 (10-12..10-18):
                // 25m each day * 7 = 175m (<= 180m -> satisfied)
                d <= LocalDate(2026, 10, 18) -> 25_000

                // Post-transition Week 2 (10-19..10-25):
                // 30m each day * 7 = 210m (> 180m -> not satisfied)
                else -> 30_000
            }
            days.add(buildDay(d, minutes))
            d = d.plus(1)
        }

        val context = createQueryContext(from = fromDate, to = toDate)
        val result = HabitCycleStatisticsAggregator.aggregate(
            context,
            listOf(StatisticsHabitInput(habit, days, observationStart = fromDate))
        )

        // Pre-transition Daily: 6 days satisfied, 1 day not satisfied
        assertEquals(6, result.daily.satisfiedCount, "6 daily cycles <= 60m should be satisfied")
        assertEquals(1, result.daily.notSatisfiedCount, "1 daily cycle of 70m > 60m should be not satisfied")

        // Post-transition Weekly: 1 week satisfied (175m <= 180m), 1 week not satisfied (210m > 180m)
        assertEquals(1, result.weekly.satisfiedCount, "Week 1 with 175m <= 180m should be satisfied under AT_MOST")
        assertEquals(1, result.weekly.notSatisfiedCount, "Week 2 with 210m > 180m should be not satisfied under AT_MOST")
    }

    @Test
    fun testAtMostPartialCoverageExceededVersusUnderLimit() {
        val weekStart = LocalDate(2026, 10, 12) // Monday
        val weekEnd = LocalDate(2026, 10, 18) // Sunday

        val habit = factory.buildHabit().apply {
            uuid = "habit-partial-at-most"
            type = HabitType.NUMERICAL
            targetType = NumericalHabitType.AT_MOST
            targetValue = 180.0
            frequency = Frequency(1, 7)
            unit = "min"
        }

        // Scenario A: Active sum = 200m (> 180m) with 1 unknown day.
        // Even though coverage is incomplete, AT_MOST budget is ALREADY exceeded!
        // Outcome must be NOT_SATISFIED.
        val daysA = mutableListOf<EffectiveHabitDay>()
        daysA.add(buildDay(weekStart, 100_000))
        daysA.add(buildDay(weekStart.plus(1), 100_000)) // Sum so far = 200m
        daysA.add(buildDay(weekStart.plus(2), Entry.UNKNOWN, manualValue = null))
        daysA.add(buildDay(weekStart.plus(3), 0))
        daysA.add(buildDay(weekStart.plus(4), 0))
        daysA.add(buildDay(weekStart.plus(5), 0))
        daysA.add(buildDay(weekStart.plus(6), 0))

        val context = createQueryContext(from = weekStart, to = weekEnd)
        val resultA = HabitCycleStatisticsAggregator.aggregate(
            context,
            listOf(StatisticsHabitInput(habit, daysA, observationStart = weekStart))
        )
        val cycleA = resultA.allCycles.single { it.startDate == weekStart }
        assertEquals(HabitCycleOutcome.NOT_SATISFIED, cycleA.outcome,
            "AT_MOST cycle that already exceeds target must be NOT_SATISFIED despite unknown days")

        // Scenario B: Active sum = 120m (<= 180m) with 1 unknown day.
        // Incomplete coverage means we do NOT know if the unknown day pushes sum over 180m.
        // Outcome must be UNKNOWN.
        val daysB = mutableListOf<EffectiveHabitDay>()
        daysB.add(buildDay(weekStart, 60_000))
        daysB.add(buildDay(weekStart.plus(1), 60_000)) // Sum = 120m
        daysB.add(buildDay(weekStart.plus(2), Entry.UNKNOWN, manualValue = null))
        daysB.add(buildDay(weekStart.plus(3), 0))
        daysB.add(buildDay(weekStart.plus(4), 0))
        daysB.add(buildDay(weekStart.plus(5), 0))
        daysB.add(buildDay(weekStart.plus(6), 0))

        val resultB = HabitCycleStatisticsAggregator.aggregate(
            context,
            listOf(StatisticsHabitInput(habit, daysB, observationStart = weekStart))
        )
        val cycleB = resultB.allCycles.single { it.startDate == weekStart }
        assertEquals(HabitCycleOutcome.UNKNOWN, cycleB.outcome,
            "AT_MOST cycle under budget with unknown days must be UNKNOWN")

        // Scenario C: Active sum = 120m (<= 180m) with 0 unknown days (complete coverage).
        // Outcome must be SATISFIED.
        val daysC = daysB.toMutableList()
        daysC[2] = buildDay(weekStart.plus(2), 0) // Replace unknown with 0m

        val resultC = HabitCycleStatisticsAggregator.aggregate(
            context,
            listOf(StatisticsHabitInput(habit, daysC, observationStart = weekStart))
        )
        val cycleC = resultC.allCycles.single { it.startDate == weekStart }
        assertEquals(HabitCycleOutcome.SATISFIED, cycleC.outcome,
            "AT_MOST cycle under budget with complete coverage must be SATISFIED")
    }
}
