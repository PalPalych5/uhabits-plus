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
import org.isoron.uhabits.core.rules.HabitTimeValueConverter
import org.isoron.uhabits.core.rules.IncompatibleTargetHabitException
import org.junit.Test
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
            observationStart: LocalDate? = null,
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
            val startCutoff = habit.effectiveStatisticsStartDate() ?: observationStart

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

                val isPartial = startCutoff != null && start < startCutoff

                if (targetNative == null || isPartial) {
                    results.add(
                        OracleCycleExpectation(
                            startDate = start,
                            endDate = end,
                            lowerBound = 0L,
                            unknownDays = 0,
                            skippedDays = 0,
                            outcome = HabitCycleOutcome.UNAVAILABLE,
                        )
                    )
                    continue
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

        fun evaluate(
            context: StatisticsQueryContext,
            habit: Habit,
            days: List<EffectiveHabitDay>,
            observationStart: LocalDate? = null,
        ): List<OracleCycleExpectation> {
            val goals = habit.normalizedGoalHistory()
            if (goals.size <= 1) {
                val singleGoal = goals.firstOrNull() ?: HabitGoal(
                    context.from,
                    habit.frequency,
                    habit.targetType,
                    habit.targetValue,
                    habit.unit
                )
                return evaluate(context, habit, singleGoal, days, observationStart)
            }

            val daysByDate = days.associateBy { it.manualEntry.date }
            val results = mutableListOf<OracleCycleExpectation>()

            val earliestDate = minOf(
                context.from.startOfMonth(),
                context.from.startOfWeek(context.firstWeekday),
            )

            val rawIntervals = mutableListOf<Pair<LocalDate, LocalDate>>()
            for (i in goals.indices) {
                val goal = goals[i]
                val effectiveStart = if (i == 0) earliestDate else goal.effectiveDate
                val effectiveEnd = if (i + 1 < goals.size) goals[i + 1].effectiveDate.plus(-1) else null
                when (goal.frequency.denominator) {
                    1 -> {
                        val searchStart = maxOf(context.from, effectiveStart)
                        val searchEnd = if (effectiveEnd != null) minOf(context.to, effectiveEnd) else context.to
                        var d = searchStart
                        while (d <= searchEnd) {
                            rawIntervals.add(d to d)
                            d = d.plus(1)
                        }
                    }
                    7 -> {
                        var weekStart = earliestDate.startOfWeek(context.firstWeekday)
                        while (weekStart <= context.to) {
                            val weekEnd = weekStart.plus(6)
                            val inContext = weekStart <= context.to && weekEnd >= context.from
                            val inGoal = (effectiveEnd == null || weekStart <= effectiveEnd) && weekEnd >= effectiveStart
                            if (inContext && inGoal) {
                                rawIntervals.add(weekStart to weekEnd)
                            }
                            weekStart = weekEnd.plus(1)
                        }
                    }
                }
            }
            val intervals = rawIntervals.distinct()
            val startCutoff = habit.effectiveStatisticsStartDate() ?: observationStart

            for ((start, end) in intervals) {
                var d = start
                val cycleDays = mutableListOf<EffectiveHabitDay>()
                val goalsInCycle = mutableListOf<HabitGoal>()
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
                    goalsInCycle.add(habit.goalAt(d))
                    d = d.plus(1)
                }

                val hasGoalTransition = goalsInCycle.any { g ->
                    g.frequency != goalsInCycle[0].frequency ||
                        g.targetType != goalsInCycle[0].targetType ||
                        g.targetValue != goalsInCycle[0].targetValue ||
                        g.unit != goalsInCycle[0].unit
                }

                val isPartial = startCutoff != null && start < startCutoff

                if (hasGoalTransition || isPartial) {
                    results.add(
                        OracleCycleExpectation(
                            startDate = start,
                            endDate = end,
                            lowerBound = 0L,
                            unknownDays = 0,
                            skippedDays = 0,
                            outcome = HabitCycleOutcome.UNAVAILABLE,
                        )
                    )
                    continue
                }

                val goal = goalsInCycle.last()
                val targetNative = safeNative(goal.targetValue, goal.targetType)
                if (targetNative == null) {
                    results.add(
                        OracleCycleExpectation(
                            startDate = start,
                            endDate = end,
                            lowerBound = 0L,
                            unknownDays = 0,
                            skippedDays = 0,
                            outcome = HabitCycleOutcome.UNAVAILABLE,
                        )
                    )
                    continue
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

        private fun safeNative(target: Double, type: NumericalHabitType): Long? {
            if (!target.isFinite() || target < 0.0) return null
            return try {
                BigDecimal.valueOf(target).movePointRight(3).setScale(
                    0,
                    if (type == NumericalHabitType.AT_LEAST) RoundingMode.CEILING else RoundingMode.FLOOR
                ).longValueExact()
            } catch (_: ArithmeticException) { null }
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

    @Test
    fun testMissingEntryAndUnknownHandlingWithOracle() {
        val oracle = IndependentCycleStatisticsOracle()
        val weekStart = LocalDate(2026, 10, 12)
        val weekEnd = LocalDate(2026, 10, 18)
        val context = createQueryContext(from = weekStart, to = weekEnd)

        // Case A: AT_LEAST target 100.0, sum 60.0, 1 unknown -> UNKNOWN
        val habitAtLeast = factory.buildHabit().apply {
            uuid = "h-at-least-unk"
            type = HabitType.NUMERICAL
            targetType = NumericalHabitType.AT_LEAST
            targetValue = 100.0
            frequency = Frequency(1, 7)
        }
        val goalAtLeast = HabitGoal(weekStart, Frequency(1, 7), NumericalHabitType.AT_LEAST, 100.0, "")

        val daysA = mutableListOf<EffectiveHabitDay>()
        daysA.add(buildDay(weekStart, 60_000))
        daysA.add(buildDay(weekStart.plus(1), Entry.UNKNOWN, manualValue = null))
        for (i in 2..6) daysA.add(buildDay(weekStart.plus(i), 0))

        val oracleA = oracle.evaluate(context, habitAtLeast, goalAtLeast, daysA).single()
        val aggA = HabitCycleStatisticsAggregator.aggregate(
            context, listOf(StatisticsHabitInput(habitAtLeast, daysA, observationStart = weekStart))
        ).allCycles.single { it.startDate == weekStart }

        assertEquals(HabitCycleOutcome.UNKNOWN, oracleA.outcome)
        assertEquals(HabitCycleOutcome.UNKNOWN, aggA.outcome)
        assertEquals(1, aggA.quantity.unknownDays)

        // Case B: AT_LEAST target 100.0, sum 120.0 >= target, 1 unknown -> SATISFIED despite unknown!
        val daysB = mutableListOf<EffectiveHabitDay>()
        daysB.add(buildDay(weekStart, 120_000))
        daysB.add(buildDay(weekStart.plus(1), Entry.UNKNOWN, manualValue = null))
        for (i in 2..6) daysB.add(buildDay(weekStart.plus(i), 0))

        val oracleB = oracle.evaluate(context, habitAtLeast, goalAtLeast, daysB).single()
        val aggB = HabitCycleStatisticsAggregator.aggregate(
            context, listOf(StatisticsHabitInput(habitAtLeast, daysB, observationStart = weekStart))
        ).allCycles.single { it.startDate == weekStart }

        assertEquals(HabitCycleOutcome.SATISFIED, oracleB.outcome)
        assertEquals(HabitCycleOutcome.SATISFIED, aggB.outcome)

        // Case C: AT_MOST target 100.0, sum 120.0 > target, 1 unknown -> NOT_SATISFIED (budget exceeded!)
        val habitAtMost = factory.buildHabit().apply {
            uuid = "h-at-most-unk"
            type = HabitType.NUMERICAL
            targetType = NumericalHabitType.AT_MOST
            targetValue = 100.0
            frequency = Frequency(1, 7)
        }
        val goalAtMost = HabitGoal(weekStart, Frequency(1, 7), NumericalHabitType.AT_MOST, 100.0, "")

        val oracleC = oracle.evaluate(context, habitAtMost, goalAtMost, daysB).single()
        val aggC = HabitCycleStatisticsAggregator.aggregate(
            context, listOf(StatisticsHabitInput(habitAtMost, daysB, observationStart = weekStart))
        ).allCycles.single { it.startDate == weekStart }

        assertEquals(HabitCycleOutcome.NOT_SATISFIED, oracleC.outcome)
        assertEquals(HabitCycleOutcome.NOT_SATISFIED, aggC.outcome)

        // Case D: AT_MOST target 100.0, sum 60.0 <= target, 1 unknown -> UNKNOWN
        val oracleD = oracle.evaluate(context, habitAtMost, goalAtMost, daysA).single()
        val aggD = HabitCycleStatisticsAggregator.aggregate(
            context, listOf(StatisticsHabitInput(habitAtMost, daysA, observationStart = weekStart))
        ).allCycles.single { it.startDate == weekStart }

        assertEquals(HabitCycleOutcome.UNKNOWN, oracleD.outcome)
        assertEquals(HabitCycleOutcome.UNKNOWN, aggD.outcome)
    }

    @Test
    fun testExplicitZeroAndNonPerformanceWithOracle() {
        val oracle = IndependentCycleStatisticsOracle()
        val date = LocalDate(2026, 10, 14)
        val context = createQueryContext(from = date, to = date)

        // Explicit 0 entry
        val dayZero = listOf(buildDay(date, 0))

        // AT_LEAST 50.0: 0 < 50.0 -> NOT_SATISFIED
        val habitAtLeast = factory.buildHabit().apply {
            type = HabitType.NUMERICAL
            targetType = NumericalHabitType.AT_LEAST
            targetValue = 50.0
            frequency = Frequency.DAILY
        }
        val goalAtLeast = HabitGoal(date, Frequency.DAILY, NumericalHabitType.AT_LEAST, 50.0, "")

        val oracleRes1 = oracle.evaluate(context, habitAtLeast, goalAtLeast, dayZero).single()
        val aggRes1 = HabitCycleStatisticsAggregator.aggregate(
            context, listOf(StatisticsHabitInput(habitAtLeast, dayZero, observationStart = date))
        ).allCycles.single { it.startDate == date }

        assertEquals(0, aggRes1.quantity.unknownDays, "Explicit zero must count as known day")
        assertEquals(HabitCycleOutcome.NOT_SATISFIED, oracleRes1.outcome)
        assertEquals(HabitCycleOutcome.NOT_SATISFIED, aggRes1.outcome)

        // AT_LEAST 0.0: sum 0 >= 0 -> SATISFIED
        val habitZeroTarget = factory.buildHabit().apply {
            type = HabitType.NUMERICAL
            targetType = NumericalHabitType.AT_LEAST
            targetValue = 0.0
            frequency = Frequency.DAILY
        }
        val goalZeroTarget = HabitGoal(date, Frequency.DAILY, NumericalHabitType.AT_LEAST, 0.0, "")

        val oracleRes2 = oracle.evaluate(context, habitZeroTarget, goalZeroTarget, dayZero).single()
        val aggRes2 = HabitCycleStatisticsAggregator.aggregate(
            context, listOf(StatisticsHabitInput(habitZeroTarget, dayZero, observationStart = date))
        ).allCycles.single { it.startDate == date }

        assertEquals(HabitCycleOutcome.SATISFIED, oracleRes2.outcome)
        assertEquals(HabitCycleOutcome.SATISFIED, aggRes2.outcome)

        // AT_MOST 50.0: sum 0 <= 50.0 and complete coverage -> SATISFIED
        val habitAtMost = factory.buildHabit().apply {
            type = HabitType.NUMERICAL
            targetType = NumericalHabitType.AT_MOST
            targetValue = 50.0
            frequency = Frequency.DAILY
        }
        val goalAtMost = HabitGoal(date, Frequency.DAILY, NumericalHabitType.AT_MOST, 50.0, "")

        val oracleRes3 = oracle.evaluate(context, habitAtMost, goalAtMost, dayZero).single()
        val aggRes3 = HabitCycleStatisticsAggregator.aggregate(
            context, listOf(StatisticsHabitInput(habitAtMost, dayZero, observationStart = date))
        ).allCycles.single { it.startDate == date }

        assertEquals(HabitCycleOutcome.SATISFIED, oracleRes3.outcome)
        assertEquals(HabitCycleOutcome.SATISFIED, aggRes3.outcome)
    }

    @Test
    fun testNegativeValuesAndValidationRejection() {
        // Entry validation rejects negative values
        assertFailsWith<IllegalArgumentException> {
            Entry.toScaledValue(-1.0)
        }
        assertFailsWith<IllegalArgumentException> {
            Entry.toScaledValue(-0.002)
        }
        // Entry UNKNOWN sentinel -0.001 is mapped to Entry.UNKNOWN (-1)
        assertEquals(Entry.UNKNOWN, Entry.toScaledValue(-0.001))

        // Negative target value rejected by statistics aggregator as INVALID_GOAL
        val date = LocalDate(2026, 10, 14)
        val context = createQueryContext(from = date, to = date)

        val habitNegativeTarget = factory.buildHabit().apply {
            uuid = "h-neg-target"
            type = HabitType.NUMERICAL
            targetType = NumericalHabitType.AT_LEAST
            targetValue = -10.0
            frequency = Frequency.DAILY
        }
        val days = listOf(buildDay(date, 5_000))

        val aggRes = HabitCycleStatisticsAggregator.aggregate(
            context, listOf(StatisticsHabitInput(habitNegativeTarget, days, observationStart = date))
        )
        val cycle = aggRes.allCycles.single { it.startDate == date }
        assertEquals(HabitCycleOutcome.UNAVAILABLE, cycle.outcome)
        assertTrue(aggRes.issues.any { it.code == StatisticsIssueCode.INVALID_GOAL })
    }

    @Test
    fun testTargetBoundariesMinusOneExactPlusOne() {
        val weekStart = LocalDate(2026, 10, 12)
        val weekEnd = LocalDate(2026, 10, 18)
        val context = createQueryContext(from = weekStart, to = weekEnd)

        val targetNative = 100_000L // 100.0 units

        // AT_LEAST boundaries
        val habitAtLeast = factory.buildHabit().apply {
            type = HabitType.NUMERICAL
            targetType = NumericalHabitType.AT_LEAST
            targetValue = 100.0
            frequency = Frequency(1, 7)
        }
        val goalAtLeast = HabitGoal(weekStart, Frequency(1, 7), NumericalHabitType.AT_LEAST, 100.0, "")

        val boundariesAtLeast = listOf(
            (targetNative - 1) to HabitCycleOutcome.NOT_SATISFIED,
            targetNative to HabitCycleOutcome.SATISFIED,
            (targetNative + 1) to HabitCycleOutcome.SATISFIED,
        )

        val oracle = IndependentCycleStatisticsOracle()

        for ((sumVal, expected) in boundariesAtLeast) {
            val days = mutableListOf<EffectiveHabitDay>()
            days.add(buildDay(weekStart, sumVal.toInt()))
            for (i in 1..6) days.add(buildDay(weekStart.plus(i), 0))

            val oracleRes = oracle.evaluate(context, habitAtLeast, goalAtLeast, days).single()
            val aggRes = HabitCycleStatisticsAggregator.aggregate(
                context, listOf(StatisticsHabitInput(habitAtLeast, days, observationStart = weekStart))
            ).allCycles.single { it.startDate == weekStart }

            assertEquals(expected, oracleRes.outcome, "Oracle mismatch for AT_LEAST sum $sumVal")
            assertEquals(expected, aggRes.outcome, "Aggregator mismatch for AT_LEAST sum $sumVal")
        }

        // AT_MOST boundaries
        val habitAtMost = factory.buildHabit().apply {
            type = HabitType.NUMERICAL
            targetType = NumericalHabitType.AT_MOST
            targetValue = 100.0
            frequency = Frequency(1, 7)
        }
        val goalAtMost = HabitGoal(weekStart, Frequency(1, 7), NumericalHabitType.AT_MOST, 100.0, "")

        val boundariesAtMost = listOf(
            (targetNative - 1) to HabitCycleOutcome.SATISFIED,
            targetNative to HabitCycleOutcome.SATISFIED,
            (targetNative + 1) to HabitCycleOutcome.NOT_SATISFIED,
        )

        for ((sumVal, expected) in boundariesAtMost) {
            val days = mutableListOf<EffectiveHabitDay>()
            days.add(buildDay(weekStart, sumVal.toInt()))
            for (i in 1..6) days.add(buildDay(weekStart.plus(i), 0))

            val oracleRes = oracle.evaluate(context, habitAtMost, goalAtMost, days).single()
            val aggRes = HabitCycleStatisticsAggregator.aggregate(
                context, listOf(StatisticsHabitInput(habitAtMost, days, observationStart = weekStart))
            ).allCycles.single { it.startDate == weekStart }

            assertEquals(expected, oracleRes.outcome, "Oracle mismatch for AT_MOST sum $sumVal")
            assertEquals(expected, aggRes.outcome, "Aggregator mismatch for AT_MOST sum $sumVal")
        }
    }

    @Test
    fun testFractionalValuesAndRoundingBoundaries() {
        val date = LocalDate(2026, 10, 14)
        val context = createQueryContext(from = date, to = date)
        val oracle = IndependentCycleStatisticsOracle()

        // 1. AT_LEAST CEILING rounding: target 10.0001 -> 10001 native units
        val habitCeil = factory.buildHabit().apply {
            type = HabitType.NUMERICAL
            targetType = NumericalHabitType.AT_LEAST
            targetValue = 10.0001
            frequency = Frequency.DAILY
        }
        val goalCeil = HabitGoal(date, Frequency.DAILY, NumericalHabitType.AT_LEAST, 10.0001, "")

        // sum 10000 -> NOT_SATISFIED
        val days10000 = listOf(buildDay(date, 10_000))
        assertEquals(HabitCycleOutcome.NOT_SATISFIED, oracle.evaluate(context, habitCeil, goalCeil, days10000).single().outcome)
        assertEquals(HabitCycleOutcome.NOT_SATISFIED, HabitCycleStatisticsAggregator.aggregate(
            context, listOf(StatisticsHabitInput(habitCeil, days10000, observationStart = date))
        ).allCycles.single().outcome)

        // sum 10001 -> SATISFIED
        val days10001 = listOf(buildDay(date, 10_001))
        assertEquals(HabitCycleOutcome.SATISFIED, oracle.evaluate(context, habitCeil, goalCeil, days10001).single().outcome)
        assertEquals(HabitCycleOutcome.SATISFIED, HabitCycleStatisticsAggregator.aggregate(
            context, listOf(StatisticsHabitInput(habitCeil, days10001, observationStart = date))
        ).allCycles.single().outcome)

        // 2. AT_MOST FLOOR rounding: target 10.0009 -> 10000 native units
        val habitFloor = factory.buildHabit().apply {
            type = HabitType.NUMERICAL
            targetType = NumericalHabitType.AT_MOST
            targetValue = 10.0009
            frequency = Frequency.DAILY
        }
        val goalFloor = HabitGoal(date, Frequency.DAILY, NumericalHabitType.AT_MOST, 10.0009, "")

        // sum 10000 -> SATISFIED
        assertEquals(HabitCycleOutcome.SATISFIED, oracle.evaluate(context, habitFloor, goalFloor, days10000).single().outcome)
        assertEquals(HabitCycleOutcome.SATISFIED, HabitCycleStatisticsAggregator.aggregate(
            context, listOf(StatisticsHabitInput(habitFloor, days10000, observationStart = date))
        ).allCycles.single().outcome)

        // sum 10001 -> NOT_SATISFIED
        assertEquals(HabitCycleOutcome.NOT_SATISFIED, oracle.evaluate(context, habitFloor, goalFloor, days10001).single().outcome)
        assertEquals(HabitCycleOutcome.NOT_SATISFIED, HabitCycleStatisticsAggregator.aggregate(
            context, listOf(StatisticsHabitInput(habitFloor, days10001, observationStart = date))
        ).allCycles.single().outcome)

        // 3. Fractional target 0.5 -> 500 native units
        val habitHalf = factory.buildHabit().apply {
            type = HabitType.NUMERICAL
            targetType = NumericalHabitType.AT_LEAST
            targetValue = 0.5
            frequency = Frequency.DAILY
        }
        val goalHalf = HabitGoal(date, Frequency.DAILY, NumericalHabitType.AT_LEAST, 0.5, "")

        val days499 = listOf(buildDay(date, 499))
        val days500 = listOf(buildDay(date, 500))
        assertEquals(HabitCycleOutcome.NOT_SATISFIED, oracle.evaluate(context, habitHalf, goalHalf, days499).single().outcome)
        assertEquals(HabitCycleOutcome.SATISFIED, oracle.evaluate(context, habitHalf, goalHalf, days500).single().outcome)
    }

    @Test
    fun testMaximumAllowedValueAndOverflowRejection() {
        // Entry maximum representation
        val maxUserValue = Entry.MAX_NUMERICAL_VALUE
        val maxScaled = Entry.toScaledValue(maxUserValue)
        assertEquals(Int.MAX_VALUE, maxScaled)

        // Overflow rejected on entry conversion
        assertFailsWith<IllegalArgumentException> {
            Entry.toScaledValue(maxUserValue + 0.001)
        }

        // Arithmetic Long addition overflow inside cycle summation
        val date = LocalDate(2026, 10, 14)
        val context = createQueryContext(from = date, to = date)

        val habit = factory.buildHabit().apply {
            uuid = "h-overflow"
            type = HabitType.NUMERICAL
            targetType = NumericalHabitType.AT_LEAST
            targetValue = 100.0
            frequency = Frequency.DAILY
        }

        // Day with manualValue + contributionValue exceeding Long.MAX_VALUE
        val overflowDay = EffectiveHabitDay(
            manualEntry = Entry(date, Entry.MAX_SCALED_VALUE),
            manualValue = Long.MAX_VALUE - 100L,
            contributionValue = 200L,
            totalValue = null,
            contributions = emptyList(),
            isNumerical = true,
            includedInStatistics = true,
        )

        val aggRes = HabitCycleStatisticsAggregator.aggregate(
            context, listOf(StatisticsHabitInput(habit, listOf(overflowDay), observationStart = date))
        )
        val cycle = aggRes.allCycles.single { it.startDate == date }
        assertEquals(HabitCycleOutcome.UNAVAILABLE, cycle.outcome)
        assertTrue(aggRes.issues.any { it.code == StatisticsIssueCode.OVERFLOW })
    }

    @Test
    fun testTimeUnitsAndFocusContributions() {
        // Valid minute unit conversion
        val focusMs = 25 * 60 * 1000L // 25 mins
        val habitVal = HabitTimeValueConverter.convertFocusDurationToHabitValue(focusMs)
        assertEquals(25_000L, habitVal)

        // Target habit validation accepts 'min', 'mins', 'minutes'
        val habitMin = factory.buildHabit().apply {
            type = HabitType.NUMERICAL
            unit = "min"
        }
        HabitTimeValueConverter.validateTargetHabit(habitMin)

        // Target habit validation rejects 'hr', 'hours', 'sec'
        val habitHr = factory.buildHabit().apply {
            type = HabitType.NUMERICAL
            unit = "hr"
        }
        assertFailsWith<IncompatibleTargetHabitException> {
            HabitTimeValueConverter.validateTargetHabit(habitHr)
        }

        // Aggregator evaluates manual + focus contribution together against oracle
        val date = LocalDate(2026, 10, 14)
        val context = createQueryContext(from = date, to = date)
        val habitTime = factory.buildHabit().apply {
            type = HabitType.NUMERICAL
            targetType = NumericalHabitType.AT_LEAST
            targetValue = 45.0 // 45 min
            frequency = Frequency.DAILY
            unit = "min"
        }
        val goalTime = HabitGoal(date, Frequency.DAILY, NumericalHabitType.AT_LEAST, 45.0, "min")

        // 20 min manual + 25 min focus contribution = 45 min total -> SATISFIED
        val dayContrib = buildDay(date, 20_000, contributionValue = 25_000L)
        val oracle = IndependentCycleStatisticsOracle()

        val oracleRes = oracle.evaluate(context, habitTime, goalTime, listOf(dayContrib)).single()
        val aggRes = HabitCycleStatisticsAggregator.aggregate(
            context, listOf(StatisticsHabitInput(habitTime, listOf(dayContrib), observationStart = date))
        ).allCycles.single()

        assertEquals(45_000L, oracleRes.lowerBound)
        assertEquals(HabitCycleOutcome.SATISFIED, oracleRes.outcome)
        assertEquals(HabitCycleOutcome.SATISFIED, aggRes.outcome)
    }

    @Test
    fun testDailyToWeeklyGoalTransitionsDifferentialOracle() {
        val oracle = IndependentCycleStatisticsOracle()
        val transitionDate = LocalDate(2026, 10, 12) // Monday
        val fromDate = LocalDate(2026, 10, 5) // Monday
        val toDate = LocalDate(2026, 10, 25) // Sunday (3 weeks total)
        val context = createQueryContext(from = fromDate, to = toDate)

        val habit = factory.buildHabit().apply {
            uuid = "h-daily-to-weekly"
            type = HabitType.NUMERICAL
            targetType = NumericalHabitType.AT_LEAST
            targetValue = 180.0
            frequency = Frequency(1, 7)
            unit = "min"
            goalHistory = mutableListOf(
                HabitGoal(fromDate, Frequency.DAILY, NumericalHabitType.AT_LEAST, 60.0, "min"),
                HabitGoal(transitionDate, Frequency(1, 7), NumericalHabitType.AT_LEAST, 180.0, "min")
            )
        }

        val days = mutableListOf<EffectiveHabitDay>()
        var d = fromDate
        while (d <= toDate) {
            // Daily epoch: 70 min/day (satisfies 60m daily)
            // Weekly epoch: 40 min Mon, Wed, Fri, Sat -> 160m total (< 180m weekly, NOT_SATISFIED)
            val amount = if (d < transitionDate) 70_000 else {
                when (d.dayOfWeek) {
                    DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY -> 40_000
                    else -> 0
                }
            }
            days.add(buildDay(d, amount))
            d = d.plus(1)
        }

        val oracleCycles = oracle.evaluate(context, habit, days)
        val aggResult = HabitCycleStatisticsAggregator.aggregate(
            context, listOf(StatisticsHabitInput(habit, days, observationStart = fromDate))
        )

        for (exp in oracleCycles) {
            val aggCycle = aggResult.allCycles.firstOrNull { it.startDate == exp.startDate && it.endDate == exp.endDate }
            assertNotNull(aggCycle, "Aggregator should produce cycle for ${exp.startDate}..${exp.endDate}")
            assertEquals(exp.outcome, aggCycle.outcome, "Outcome mismatch on cycle ${exp.startDate}..${exp.endDate}")
        }
    }

    @Test
    fun testChangingHistoricalLimitsAtMostWithOracle() {
        val oracle = IndependentCycleStatisticsOracle()
        val fromDate = LocalDate(2026, 10, 5)
        val toDate = LocalDate(2026, 10, 25)
        val context = createQueryContext(from = fromDate, to = toDate)

        val epoch1 = LocalDate(2026, 10, 5) // Daily AT_MOST 60m
        val epoch2 = LocalDate(2026, 10, 12) // Daily AT_MOST 40m
        val epoch3 = LocalDate(2026, 10, 19) // Weekly AT_MOST 180m

        val habit = factory.buildHabit().apply {
            uuid = "h-at-most-changing"
            type = HabitType.NUMERICAL
            targetType = NumericalHabitType.AT_MOST
            targetValue = 180.0
            frequency = Frequency(1, 7)
            unit = "min"
            goalHistory = mutableListOf(
                HabitGoal(epoch1, Frequency.DAILY, NumericalHabitType.AT_MOST, 60.0, "min"),
                HabitGoal(epoch2, Frequency.DAILY, NumericalHabitType.AT_MOST, 40.0, "min"),
                HabitGoal(epoch3, Frequency(1, 7), NumericalHabitType.AT_MOST, 180.0, "min")
            )
        }

        val days = mutableListOf<EffectiveHabitDay>()
        var d = fromDate
        while (d <= toDate) {
            val amount = when {
                // Epoch 1 (<= 60m): 50m -> SATISFIED
                d < epoch2 -> 50_000
                // Epoch 2 (<= 40m): 50m -> NOT_SATISFIED (exceeds 40m!)
                d < epoch3 -> 50_000
                // Epoch 3 (Weekly <= 180m): 20m/day * 7 = 140m -> SATISFIED
                else -> 20_000
            }
            days.add(buildDay(d, amount))
            d = d.plus(1)
        }

        val oracleCycles = oracle.evaluate(context, habit, days)
        val aggResult = HabitCycleStatisticsAggregator.aggregate(
            context, listOf(StatisticsHabitInput(habit, days, observationStart = fromDate))
        )

        for (exp in oracleCycles) {
            val aggCycle = aggResult.allCycles.firstOrNull { it.startDate == exp.startDate && it.endDate == exp.endDate }
            assertNotNull(aggCycle, "Aggregator should produce cycle for ${exp.startDate}..${exp.endDate}")
            assertEquals(exp.outcome, aggCycle.outcome, "Outcome mismatch on cycle ${exp.startDate}..${exp.endDate}")
        }
    }

    @Test
    fun testWeeklySumsAcrossWeekBoundaries() {
        val week1Start = LocalDate(2026, 10, 12) // Monday
        val week1End = LocalDate(2026, 10, 18) // Sunday
        val week2Start = LocalDate(2026, 10, 19) // Monday
        val week2End = LocalDate(2026, 10, 25) // Sunday

        val context = createQueryContext(from = week1Start, to = week2End)

        val habit = factory.buildHabit().apply {
            type = HabitType.NUMERICAL
            targetType = NumericalHabitType.AT_LEAST
            targetValue = 50.0
            frequency = Frequency(1, 7)
        }

        val days = mutableListOf<EffectiveHabitDay>()
        var d = week1Start
        while (d <= week2End) {
            // Sunday (week 1 end) has 80_000L. All other days are 0.
            val amount = if (d == week1End) 80_000 else 0
            days.add(buildDay(d, amount))
            d = d.plus(1)
        }

        val aggResult = HabitCycleStatisticsAggregator.aggregate(
            context, listOf(StatisticsHabitInput(habit, days, observationStart = week1Start))
        )

        val week1Cycle = aggResult.allCycles.single { it.startDate == week1Start }
        val week2Cycle = aggResult.allCycles.single { it.startDate == week2Start }

        assertEquals(80_000L, week1Cycle.quantity.lowerBound, "Week 1 must contain Sunday entry")
        assertEquals(HabitCycleOutcome.SATISFIED, week1Cycle.outcome)

        assertEquals(0L, week2Cycle.quantity.lowerBound, "Week 2 must NOT receive Sunday entry from Week 1")
        assertEquals(HabitCycleOutcome.NOT_SATISFIED, week2Cycle.outcome)
    }

    @Test
    fun testFirstWeekdayVariationsWithOracle() {
        val oracle = IndependentCycleStatisticsOracle()
        // Anchor around 2026-10-18 (Sunday)
        val fromDate = LocalDate(2026, 10, 11)
        val toDate = LocalDate(2026, 10, 24)

        val habit = factory.buildHabit().apply {
            type = HabitType.NUMERICAL
            targetType = NumericalHabitType.AT_LEAST
            targetValue = 50.0
            frequency = Frequency(1, 7)
        }
        val goal = HabitGoal(fromDate, Frequency(1, 7), NumericalHabitType.AT_LEAST, 50.0, "")

        // Entry only on Sunday 2026-10-18 (60_000)
        val days = mutableListOf<EffectiveHabitDay>()
        var d = fromDate
        while (d <= toDate) {
            val amount = if (d == LocalDate(2026, 10, 18)) 60_000 else 0
            days.add(buildDay(d, amount))
            d = d.plus(1)
        }

        for (firstDay in listOf(DayOfWeek.MONDAY, DayOfWeek.SUNDAY, DayOfWeek.SATURDAY)) {
            val context = createQueryContext(from = fromDate, to = toDate, firstWeekday = firstDay)
            val oracleExpectations = oracle.evaluate(context, habit, goal, days, observationStart = fromDate)
            val aggResult = HabitCycleStatisticsAggregator.aggregate(
                context, listOf(StatisticsHabitInput(habit, days, observationStart = fromDate))
            )

            for (exp in oracleExpectations) {
                val matching = aggResult.allCycles.firstOrNull { it.startDate == exp.startDate && it.endDate == exp.endDate }
                assertNotNull(matching, "Matching cycle must exist for $firstDay: ${exp.startDate}..${exp.endDate}")
                assertEquals(exp.lowerBound, matching.quantity.lowerBound, "LowerBound mismatch for $firstDay on ${exp.startDate}")
                assertEquals(exp.outcome, matching.outcome, "Outcome mismatch for $firstDay on ${exp.startDate}")
            }
        }
    }
}
