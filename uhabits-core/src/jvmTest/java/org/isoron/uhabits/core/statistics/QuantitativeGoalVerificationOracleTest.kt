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
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Independent verification oracle for quantitative goals (AT_LEAST >= and AT_MOST <= semantics).
 * Evaluates cycles independently of HabitCycleStatisticsAggregator and performs differential verification.
 */
class QuantitativeGoalVerificationOracleTest {

    private val factory = MemoryModelFactory()

    private data class OracleCycleExpectation(
        val startDate: LocalDate,
        val endDate: LocalDate,
        val lowerBound: Long,
        val unknownDays: Int,
        val skippedDays: Int,
        val outcome: HabitCycleOutcome,
    )

    private class IndependentCycleStatisticsOracle {
        fun evaluate(
            context: StatisticsQueryContext,
            habit: Habit,
            goal: HabitGoal,
            days: List<EffectiveHabitDay>,
        ): List<OracleCycleExpectation> {
            val daysByDate = days.associateBy { it.manualEntry.date }
            val results = mutableListOf<OracleCycleExpectation>()

            val intervals = when (goal.frequency.denominator) {
                1 -> {
                    var d = context.from
                    val list = mutableListOf<Pair<LocalDate, LocalDate>>()
                    while (d <= context.to) {
                        list.add(d to d)
                        d = d.plus(1)
                    }
                    list
                }
                7 -> {
                    val list = mutableListOf<Pair<LocalDate, LocalDate>>()
                    var start = context.from.startOfWeek(context.firstWeekday)
                    while (start <= context.to) {
                        val end = start.plus(6)
                        if (start <= context.to && end >= context.from) {
                            list.add(start to end)
                        }
                        start = end.plus(1)
                    }
                    list
                }
                else -> emptyList()
            }

            val targetNative = safeNative(goal.targetValue, goal.targetType)

            for ((start, end) in intervals) {
                var d = start
                val cycleDays = mutableListOf<EffectiveHabitDay>()
                while (d <= end) {
                    val effective = daysByDate[d] ?: EffectiveHabitDay(
                        manualEntry = Entry(d, Entry.UNKNOWN),
                        manualValue = null,
                        contributionValue = 0L,
                        totalValue = null,
                        contributions = emptyList(),
                        isNumerical = true,
                        includedInStatistics = true,
                    )
                    cycleDays.add(effective)
                    d = d.plus(1)
                }

                val totalDays = cycleDays.size
                val skippedDays = cycleDays.count { it.isSkipped }
                val activeDays = cycleDays.filter { it.includedInStatistics && !it.isSkipped }
                val unknownDays = activeDays.count { it.manualValue == null }

                var sum = 0L
                for (ad in activeDays) {
                    sum += (ad.manualValue ?: 0L) + ad.contributionValue
                }

                val isOngoing = end >= context.habitToday || end > minOf(context.to, context.habitToday)

                val outcome = when {
                    totalDays > 0 && skippedDays == totalDays -> HabitCycleOutcome.EXEMPT
                    goal.targetType == NumericalHabitType.AT_LEAST -> {
                        if (goal.targetValue <= 0.0) {
                            HabitCycleOutcome.SATISFIED
                        } else if (sum >= targetNative) {
                            HabitCycleOutcome.SATISFIED
                        } else if (isOngoing || unknownDays > 0) {
                            HabitCycleOutcome.UNKNOWN
                        } else {
                            HabitCycleOutcome.NOT_SATISFIED
                        }
                    }
                    goal.targetType == NumericalHabitType.AT_MOST -> {
                        if (sum > targetNative) {
                            HabitCycleOutcome.NOT_SATISFIED
                        } else if (isOngoing || unknownDays > 0) {
                            HabitCycleOutcome.UNKNOWN
                        } else {
                            HabitCycleOutcome.SATISFIED
                        }
                    }
                    else -> HabitCycleOutcome.UNKNOWN
                }

                results.add(
                    OracleCycleExpectation(
                        startDate = start,
                        endDate = end,
                        lowerBound = sum,
                        unknownDays = unknownDays,
                        skippedDays = skippedDays,
                        outcome = outcome,
                    )
                )
            }

            return results
        }

        private fun safeNative(target: Double, type: NumericalHabitType): Long {
            return BigDecimal.valueOf(target).movePointRight(3).setScale(
                0,
                if (type == NumericalHabitType.AT_LEAST) RoundingMode.CEILING else RoundingMode.FLOOR
            ).longValueExact()
        }
    }

    private fun createQueryContext(
        from: LocalDate,
        to: LocalDate,
        firstWeekday: DayOfWeek = DayOfWeek.MONDAY,
        now: Long = 1793232000000L // 2026-10-28 12:00:00 UTC (habitToday = 2026-10-28)
    ): StatisticsQueryContext {
        return StatisticsQueryContext(
            from = from,
            to = to,
            timezoneId = "UTC",
            firstWeekday = firstWeekday,
            habitHourOffset = 0,
            habitMinuteOffset = 0,
            datasetId = "test-oracle-dataset",
            revision = 1L,
            now = now,
        )
    }

    private fun buildDay(
        date: LocalDate,
        value: Int,
        manualValue: Long? = null,
        contributionValue: Long = 0L,
        isSkipped: Boolean = false,
        includedInStatistics: Boolean = true,
    ): EffectiveHabitDay {
        val entryValue = if (isSkipped) Entry.SKIP else value
        val mv = if (isSkipped) null else manualValue ?: (if (value >= 0) value.toLong() else null)
        val tv = mv?.let { it + contributionValue }
        return EffectiveHabitDay(
            manualEntry = Entry(date, entryValue),
            manualValue = mv,
            contributionValue = contributionValue,
            totalValue = tv,
            contributions = emptyList(),
            isNumerical = true,
            includedInStatistics = includedInStatistics,
        )
    }

    @Test
    fun testDifferentialOracleAgainstAggregatorAcrossRandomizedSeries() {
        val oracle = IndependentCycleStatisticsOracle()
        val random = Random(42)

        val fromDate = LocalDate(2026, 10, 5) // Monday
        val toDate = LocalDate(2026, 10, 25) // Sunday (3 full weeks)
        val context = createQueryContext(from = fromDate, to = toDate)

        for (trial in 1..40) {
            val isAtMost = trial % 2 == 0
            val targetType = if (isAtMost) NumericalHabitType.AT_MOST else NumericalHabitType.AT_LEAST
            val targetVal = if (trial == 1) 0.0 else (random.nextInt(30, 200)).toDouble()
            val frequency = if (trial % 3 == 0) Frequency.DAILY else Frequency(1, 7)

            val habit = factory.buildHabit().apply {
                uuid = "habit-oracle-$trial"
                name = "Oracle Habit $trial"
                type = HabitType.NUMERICAL
                this.targetType = targetType
                targetValue = targetVal
                this.frequency = frequency
                unit = "min"
            }

            val goal = HabitGoal(fromDate, frequency, targetType, targetVal, "min")

            val days = mutableListOf<EffectiveHabitDay>()
            var d = fromDate
            while (d <= toDate) {
                val roll = random.nextInt(100)
                val day = when {
                    roll < 10 -> buildDay(d, Entry.UNKNOWN, manualValue = null)
                    roll < 15 -> buildDay(d, Entry.SKIP, isSkipped = true)
                    roll < 20 -> buildDay(d, 0)
                    else -> {
                        val amount = random.nextInt(1, 50) * 1_000
                        val contrib = if (random.nextBoolean()) random.nextInt(0, 15) * 1_000L else 0L
                        buildDay(d, amount, contributionValue = contrib)
                    }
                }
                days.add(day)
                d = d.plus(1)
            }

            val oracleExpectations = oracle.evaluate(context, habit, goal, days)
            val aggregatorResult = HabitCycleStatisticsAggregator.aggregate(
                context,
                listOf(StatisticsHabitInput(habit, days, observationStart = fromDate))
            )

            // Verify each cycle matches the oracle expectation
            for (oracleExp in oracleExpectations) {
                val matchingAggregatorCycle = aggregatorResult.allCycles.firstOrNull {
                    it.startDate == oracleExp.startDate && it.endDate == oracleExp.endDate
                }
                assertNotNull(matchingAggregatorCycle,
                    "Trial $trial: Aggregator should produce cycle for ${oracleExp.startDate}..${oracleExp.endDate}")

                assertEquals(oracleExp.lowerBound, matchingAggregatorCycle.quantity.lowerBound,
                    "Trial $trial lowerBound mismatch on cycle ${oracleExp.startDate}")
                assertEquals(oracleExp.unknownDays, matchingAggregatorCycle.quantity.unknownDays,
                    "Trial $trial unknownDays mismatch on cycle ${oracleExp.startDate}")
                assertEquals(oracleExp.skippedDays, matchingAggregatorCycle.quantity.skippedDays,
                    "Trial $trial skippedDays mismatch on cycle ${oracleExp.startDate}")
                assertEquals(oracleExp.outcome, matchingAggregatorCycle.outcome,
                    "Trial $trial outcome mismatch on cycle ${oracleExp.startDate} (target: $targetVal, type: $targetType, sum: ${oracleExp.lowerBound}, unknown: ${oracleExp.unknownDays})")
            }
        }
    }

    @Test
    fun testDeterministicEdgeCaseMatrixWithOracle() {
        val oracle = IndependentCycleStatisticsOracle()
        val weekStart = LocalDate(2026, 10, 12)
        val weekEnd = LocalDate(2026, 10, 18)
        val context = createQueryContext(from = weekStart, to = weekEnd)

        // Matrix of cases:
        // Case 1: AT_MOST 100.0, sum exactly 100.0, 0 unknown -> SATISFIED
        // Case 2: AT_MOST 100.0, sum 100.001 (100001), 0 unknown -> NOT_SATISFIED
        // Case 3: AT_LEAST 100.0, sum exactly 100.0, 0 unknown -> SATISFIED
        // Case 4: AT_LEAST 100.0, sum 99.999 (99999), 0 unknown -> NOT_SATISFIED
        val testCases = listOf(
            Triple(NumericalHabitType.AT_MOST, 100.0, 100_000 to HabitCycleOutcome.SATISFIED),
            Triple(NumericalHabitType.AT_MOST, 100.0, 100_001 to HabitCycleOutcome.NOT_SATISFIED),
            Triple(NumericalHabitType.AT_LEAST, 100.0, 100_000 to HabitCycleOutcome.SATISFIED),
            Triple(NumericalHabitType.AT_LEAST, 100.0, 99_999 to HabitCycleOutcome.NOT_SATISFIED),
        )

        for ((numType, target, actualAndOutcome) in testCases) {
            val (scaledVal, expectedOutcome) = actualAndOutcome
            val habit = factory.buildHabit().apply {
                uuid = "matrix-$numType-$scaledVal"
                this.type = HabitType.NUMERICAL
                targetType = numType
                targetValue = target
                frequency = Frequency(1, 7)
            }
            val goal = HabitGoal(weekStart, Frequency(1, 7), numType, target, "")

            val days = mutableListOf<EffectiveHabitDay>()
            days.add(buildDay(weekStart, scaledVal))
            for (i in 1..6) {
                days.add(buildDay(weekStart.plus(i), 0))
            }

            val oracleRes = oracle.evaluate(context, habit, goal, days).single()
            val aggRes = HabitCycleStatisticsAggregator.aggregate(
                context,
                listOf(StatisticsHabitInput(habit, days, observationStart = weekStart))
            ).allCycles.single { it.startDate == weekStart }

            assertEquals(expectedOutcome, oracleRes.outcome)
            assertEquals(expectedOutcome, aggRes.outcome)
        }
    }
}
