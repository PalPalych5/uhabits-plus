/* Copyright (C) 2026 Álinson Santos Xavier and contributors. GPL-3.0-or-later. */
package org.isoron.uhabits.core.statistics

import org.isoron.platform.time.DayOfWeek
import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitGoal
import org.isoron.uhabits.core.models.NumericalHabitType
import org.isoron.uhabits.core.models.projection.EffectiveHabitDay
import org.isoron.uhabits.core.rules.HabitTimeValueConverter
import org.isoron.uhabits.core.rules.IncompatibleTargetHabitException
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Detached Habit facts provided to the aggregator.
 *
 * @param habit Detached Habit model instance.
 * @param days Projected effective days covering the relevant query cycles.
 * @param observationStart Earliest known observation date from original entries or ledger facts.
 */
data class StatisticsHabitInput(
    val habit: Habit,
    val days: List<EffectiveHabitDay>,
    val observationStart: LocalDate? = null,
    val issues: List<StatisticsIssue> = emptyList(),
)

enum class HabitCadence {
    DAILY,
    WEEKLY,
    MONTHLY,
    UNSUPPORTED,
}

enum class HabitCycleLifecycle {
    CLOSED,
    ONGOING,
    PARTIAL,
    TRANSITION,
    UNSUPPORTED,
}

enum class HabitCycleOutcome {
    SATISFIED,
    NOT_SATISFIED,
    UNKNOWN,
    EXEMPT,
    CAPACITY_EXCLUDED,
    UNAVAILABLE,
}

data class HabitCycleKey(
    val habitUuid: String,
    val goalVersionKey: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
) {
    override fun toString(): String = "$habitUuid/$goalVersionKey/$startDate..$endDate"
}

data class HabitCycleQuantity(
    val isNumerical: Boolean,
    val lowerBound: Long,
    val manualValue: Long,
    val activeContributionValue: Long,
    val excludedContributionValue: Long = 0L,
    val revokedContributionValue: Long = 0L,
    val targetValue: Double,
    val targetValueNative: Long? = null,
    val unit: String = "",
    val booleanManualCount: Int = 0,
    val booleanUnknownDays: Int = 0,
    val booleanUpperBound: Int = 0,
    val booleanCapacity: Int = 0,
    val totalDays: Int = 0,
    val knownDays: Int = 0,
    val unknownDays: Int = 0,
    val skippedDays: Int = 0,
    val excludedDays: Int = 0,
    val hasCompleteCoverage: Boolean = true,
)

data class HabitCycle(
    val key: HabitCycleKey,
    val habitUuid: String,
    val habitName: String,
    val cadence: HabitCadence,
    val lifecycle: HabitCycleLifecycle,
    val outcome: HabitCycleOutcome,
    val goal: HabitGoal,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val quantity: HabitCycleQuantity,
    val days: List<EffectiveHabitDay>,
    val issues: List<StatisticsIssue> = emptyList(),
    val isContextual: Boolean = false,
    val contextTruncatedTo: LocalDate? = null,
)

data class HabitCadenceAggregate(
    val cadence: HabitCadence,
    val satisfiedCount: Int = 0,
    val notSatisfiedCount: Int = 0,
    val unknownCount: Int = 0,
    val exemptCount: Int = 0,
    val capacityExcludedCount: Int = 0,
    val partialCount: Int = 0,
    val transitionCount: Int = 0,
    val ongoingCount: Int = 0,
    val unsupportedCount: Int = 0,
    val unavailableCount: Int = 0,
    val cycles: List<HabitCycle> = emptyList(),
) {
    val knownCount: Int get() = satisfiedCount + notSatisfiedCount
    val eligibleCount: Int get() = knownCount + unknownCount
    val coverageRate: Double? get() = if (eligibleCount > 0) knownCount.toDouble() / eligibleCount else null
}

data class HabitSummary(
    val habitUuid: String,
    val habitName: String,
    val cadence: HabitCadence,
    val satisfiedCount: Int,
    val notSatisfiedCount: Int,
    val unknownCount: Int,
    val exemptCount: Int,
    val capacityExcludedCount: Int,
    val partialCount: Int,
    val transitionCount: Int,
    val cycles: List<HabitCycle>,
) {
    val knownCount: Int get() = satisfiedCount + notSatisfiedCount
    val eligibleCount: Int get() = knownCount + unknownCount
    val successRate: Double? get() = if (knownCount > 0) satisfiedCount.toDouble() / knownCount else null
    val coverageRate: Double? get() = if (eligibleCount > 0) knownCount.toDouble() / eligibleCount else null
}

data class HabitCycleDateBucket(
    val date: LocalDate,
    val cadence: HabitCadence,
    val satisfiedCount: Int,
    val notSatisfiedCount: Int,
    val unknownCount: Int,
    val cycles: List<HabitCycle>,
)

data class HabitCycleStatisticsResult(
    val context: StatisticsQueryContext,
    val dataState: StatisticsDataState,
    val daily: HabitCadenceAggregate,
    val weekly: HabitCadenceAggregate,
    val monthly: HabitCadenceAggregate,
    val unsupported: HabitCadenceAggregate,
    val closedCycles: List<HabitCycle>,
    val ongoingCycles: List<HabitCycle>,
    val allCycles: List<HabitCycle>,
    val habitSummaries: List<HabitSummary>,
    val dateBuckets: Map<HabitCadence, List<HabitCycleDateBucket>>,
    val issues: List<StatisticsIssue> = emptyList(),
)

object HabitCycleStatisticsAggregator {

    fun aggregate(
        context: StatisticsQueryContext,
        habits: List<StatisticsHabitInput>,
    ): HabitCycleStatisticsResult {
        if (habits.isEmpty()) {
            return HabitCycleStatisticsResult(
                context = context,
                dataState = StatisticsDataState.EMPTY,
                daily = HabitCadenceAggregate(HabitCadence.DAILY),
                weekly = HabitCadenceAggregate(HabitCadence.WEEKLY),
                monthly = HabitCadenceAggregate(HabitCadence.MONTHLY),
                unsupported = HabitCadenceAggregate(HabitCadence.UNSUPPORTED),
                closedCycles = emptyList(),
                ongoingCycles = emptyList(),
                allCycles = emptyList(),
                habitSummaries = emptyList(),
                dateBuckets = mapOf(
                    HabitCadence.DAILY to emptyList(),
                    HabitCadence.WEEKLY to emptyList(),
                    HabitCadence.MONTHLY to emptyList(),
                ),
                issues = emptyList(),
            )
        }

        val allEvaluatedCycles = mutableListOf<HabitCycle>()
        val allIssues = mutableListOf<StatisticsIssue>()

        for (habitInput in habits) {
            val evaluated = evaluateHabit(context, habitInput)
            // A cycle starting after the report's observation cutoff has not started.
            allEvaluatedCycles.addAll(evaluated.cycles.filter { it.startDate <= minOf(context.to, context.habitToday) })
            allIssues.addAll(evaluated.issues)
        }

        val closedCycles = allEvaluatedCycles.filter {
            !it.isContextual && it.lifecycle == HabitCycleLifecycle.CLOSED
        }
        val ongoingCycles = allEvaluatedCycles.filter {
            it.isContextual || it.lifecycle == HabitCycleLifecycle.ONGOING
        }

        val dailyAggregate = buildCadenceAggregate(HabitCadence.DAILY, allEvaluatedCycles)
        val weeklyAggregate = buildCadenceAggregate(HabitCadence.WEEKLY, allEvaluatedCycles)
        val monthlyAggregate = buildCadenceAggregate(HabitCadence.MONTHLY, allEvaluatedCycles)
        val unsupportedAggregate = buildCadenceAggregate(HabitCadence.UNSUPPORTED, allEvaluatedCycles)

        val habitSummaries = buildHabitSummaries(habits, allEvaluatedCycles)
        val dateBuckets = buildDateBuckets(context, closedCycles)

        val dataState = when {
            allEvaluatedCycles.isEmpty() -> StatisticsDataState.EMPTY
            allEvaluatedCycles.all { it.outcome == HabitCycleOutcome.UNAVAILABLE } -> StatisticsDataState.UNAVAILABLE
            allEvaluatedCycles.any {
                it.quantity.unknownDays > 0 ||
                    it.lifecycle == HabitCycleLifecycle.PARTIAL ||
                    it.lifecycle == HabitCycleLifecycle.TRANSITION ||
                    it.issues.isNotEmpty()
            } -> StatisticsDataState.PARTIAL
            else -> StatisticsDataState.COMPLETE
        }

        return HabitCycleStatisticsResult(
            context = context,
            dataState = dataState,
            daily = dailyAggregate,
            weekly = weeklyAggregate,
            monthly = monthlyAggregate,
            unsupported = unsupportedAggregate,
            closedCycles = closedCycles,
            ongoingCycles = ongoingCycles,
            allCycles = allEvaluatedCycles,
            habitSummaries = habitSummaries,
            dateBuckets = dateBuckets,
            issues = allIssues,
        )
    }

    private data class EvaluatedHabit(
        val cycles: List<HabitCycle>,
        val issues: List<StatisticsIssue>,
    )

    private fun evaluateHabit(
        context: StatisticsQueryContext,
        habitInput: StatisticsHabitInput,
    ): EvaluatedHabit {
        val habit = habitInput.habit
        val habitUuid = habit.uuid ?: ""
        val habitName = habit.name
        val normalizedGoals = normalizeGoals(habit)
        val candidateIntervals = generateCandidateIntervals(context, normalizedGoals)

        val cycles = mutableListOf<HabitCycle>()
        val issues = mutableListOf<StatisticsIssue>()
        issues.addAll(habitInput.issues)

        val daysByDate = habitInput.days.associateBy { it.manualEntry.date }

        for (interval in candidateIntervals) {
            val cycle = evaluateCycle(
                context = context,
                habitInput = habitInput,
                habitUuid = habitUuid,
                habitName = habitName,
                normalizedGoals = normalizedGoals,
                interval = interval,
                daysByDate = daysByDate,
            )
            cycles.add(cycle)
            for (issue in cycle.issues) {
                if (issue !in issues) issues.add(issue)
            }
        }

        if (cycles.isEmpty() && habitInput.issues.isNotEmpty()) {
            val goal = normalizedGoals.first()
            val cadence = cadenceOf(goal)
            val startDate = context.from
            val endDate = context.to
            val cycleKey = HabitCycleKey(habitUuid, goalVersionKey(goal), startDate, endDate)
            val unavailableCycle = HabitCycle(
                key = cycleKey,
                habitUuid = habitUuid,
                habitName = habitName,
                cadence = cadence,
                lifecycle = HabitCycleLifecycle.CLOSED,
                outcome = HabitCycleOutcome.UNAVAILABLE,
                goal = goal,
                startDate = startDate,
                endDate = endDate,
                quantity = computeQuantity(habit, goal, emptyList(), 0).quantity,
                days = emptyList(),
                issues = habitInput.issues,
            )
            cycles.add(unavailableCycle)
        }

        return EvaluatedHabit(cycles, issues)
    }

    private data class CandidateInterval(
        val startDate: LocalDate,
        val endDate: LocalDate,
        val cadence: HabitCadence,
    )

    /**
     * Generates the union of candidate calendar windows across all goal versions in normalizedGoals,
     * ensuring that midcycle cadence changes (e.g. weekly-to-daily or monthly-to-weekly) generate
     * both the candidate window under the earlier goal (which will be marked TRANSITION and excluded)
     * and the subsequent candidate windows under the new cadence (without skipping them).
     */
    private fun generateCandidateIntervals(
        context: StatisticsQueryContext,
        normalizedGoals: List<HabitGoal>,
    ): List<CandidateInterval> {
        val intervals = mutableListOf<CandidateInterval>()

        val earliestDate = minOf(
            context.from.startOfMonth(),
            context.from.startOfWeek(context.firstWeekday),
        )

        for (i in normalizedGoals.indices) {
            val goal = normalizedGoals[i]
            val cadence = cadenceOf(goal)
            val effectiveStart = if (i == 0) earliestDate else goal.effectiveDate
            val effectiveEnd = if (i + 1 < normalizedGoals.size) normalizedGoals[i + 1].effectiveDate.plus(-1) else null

            when (cadence) {
                HabitCadence.DAILY, HabitCadence.UNSUPPORTED -> {
                    val searchStart = maxOf(context.from, effectiveStart)
                    val searchEnd = if (effectiveEnd != null) minOf(context.to, effectiveEnd) else context.to
                    var d = searchStart
                    while (d <= searchEnd) {
                        intervals.add(CandidateInterval(d, d, cadence))
                        d = d.plus(1)
                    }
                }
                HabitCadence.WEEKLY -> {
                    var weekStart = earliestDate.startOfWeek(context.firstWeekday)
                    while (weekStart <= context.to) {
                        val weekEnd = weekStart.plus(6)
                        val intersectsP = weekStart <= context.to && weekEnd >= context.from
                        val intersectsGoal = (effectiveEnd == null || weekStart <= effectiveEnd) && weekEnd >= effectiveStart
                        if (intersectsP && intersectsGoal) {
                            intervals.add(CandidateInterval(weekStart, weekEnd, cadence))
                        }
                        weekStart = weekEnd.plus(1)
                    }
                }
                HabitCadence.MONTHLY -> {
                    var monthStart = earliestDate.startOfMonth()
                    while (monthStart <= context.to) {
                        val monthEnd = monthStart.plus(monthStart.monthLength - 1)
                        val intersectsP = monthStart <= context.to && monthEnd >= context.from
                        val intersectsGoal = (effectiveEnd == null || monthStart <= effectiveEnd) && monthEnd >= effectiveStart
                        if (intersectsP && intersectsGoal) {
                            intervals.add(CandidateInterval(monthStart, monthEnd, cadence))
                        }
                        monthStart = monthEnd.plus(1)
                    }
                }
            }
        }

        return intervals.distinctBy { Triple(it.startDate, it.endDate, it.cadence) }
    }

    private fun evaluateCycle(
        context: StatisticsQueryContext,
        habitInput: StatisticsHabitInput,
        habitUuid: String,
        habitName: String,
        normalizedGoals: List<HabitGoal>,
        interval: CandidateInterval,
        daysByDate: Map<LocalDate, EffectiveHabitDay>,
    ): HabitCycle {
        val startDate = interval.startDate
        val endDate = interval.endDate
        val cadence = interval.cadence
        val habit = habitInput.habit
        val issues = mutableListOf<StatisticsIssue>()
        issues.addAll(habitInput.issues)

        val endsInP = endDate >= context.from && endDate <= context.to
        val isClosedByDate = endDate < context.habitToday

        // Check midcycle goal transitions
        val datesInCycle = enumerateDates(startDate, endDate)
        val goalsInCycle = datesInCycle.map { goalAtDate(normalizedGoals, it) }
        val hasGoalTransition = goalsInCycle.any { g ->
            g.frequency != goalsInCycle[0].frequency ||
                g.targetType != goalsInCycle[0].targetType ||
                g.targetValue != goalsInCycle[0].targetValue ||
                unitKey(g.unit) != unitKey(goalsInCycle[0].unit)
        }

        // Check initial cutoff / observation start:
        // Canonical C1 specifies explicit effectiveStatisticsStartDate has priority,
        // otherwise observationStart is the lower bound.
        // Any cycle starting before startCutoff (wholly before or intersecting cutoff)
        // is marked PARTIAL and excluded from closed outcomes (never S/F/U).
        val startCutoff = habit.effectiveStatisticsStartDate() ?: habitInput.observationStart
        val isPartial = startCutoff != null && startDate < startCutoff

        val lifecycle = when {
            cadence == HabitCadence.UNSUPPORTED -> {
                issues.add(
                    StatisticsIssue(
                        code = StatisticsIssueCode.UNSUPPORTED_FREQUENCY,
                        entityId = habitUuid,
                        message = "Unsupported frequency denominator: ${goalsInCycle[0].frequency.denominator}",
                    )
                )
                HabitCycleLifecycle.UNSUPPORTED
            }
            hasGoalTransition -> HabitCycleLifecycle.TRANSITION
            isPartial -> HabitCycleLifecycle.PARTIAL
            endsInP && isClosedByDate -> HabitCycleLifecycle.CLOSED
            else -> HabitCycleLifecycle.ONGOING
        }

        // Contextual truncation:
        // Non-closed cycles (ONGOING, PARTIAL, TRANSITION, UNSUPPORTED) that extend past
        // minOf(context.to, context.habitToday) must truncate their loaded days to that limit,
        // preventing future leak into quantities.
        val contextLimit = minOf(context.to, context.habitToday)
        val isContextual = lifecycle == HabitCycleLifecycle.ONGOING || endDate > contextLimit
        val contextTruncatedTo = if (isContextual || endDate > contextLimit) contextLimit else null

        val representativeGoal = goalsInCycle.last()
        val goalVersionKey = if (hasGoalTransition) {
            "transition_${startDate}_$endDate"
        } else {
            goalVersionKey(representativeGoal)
        }
        val cycleKey = HabitCycleKey(habitUuid, goalVersionKey, startDate, endDate)

        val effectiveDays = if (contextTruncatedTo != null) {
            datesInCycle.filter { it <= contextTruncatedTo }.map { date ->
                daysByDate[date] ?: EffectiveHabitDay(
                    manualEntry = Entry(date, Entry.UNKNOWN),
                    manualValue = null,
                    contributionValue = 0L,
                    totalValue = null,
                    contributions = emptyList(),
                    isNumerical = habit.isNumerical,
                    includedInStatistics = habit.isDateIncludedInStatistics(date),
                )
            }
        } else {
            datesInCycle.map { date ->
                daysByDate[date] ?: EffectiveHabitDay(
                    manualEntry = Entry(date, Entry.UNKNOWN),
                    manualValue = null,
                    contributionValue = 0L,
                    totalValue = null,
                    contributions = emptyList(),
                    isNumerical = habit.isNumerical,
                    includedInStatistics = habit.isDateIncludedInStatistics(date),
                )
            }
        }

        // Numerical validation & scaled target representation
        // Note: No type history: current Habit.type only, cannot reconstruct historical type edits.
        if (!habit.isNumerical && (representativeGoal.frequency.numerator < 0 || effectiveDays.any {
                it.manualEntry.value !in setOf(Entry.UNKNOWN, Entry.NO, Entry.YES_AUTO, Entry.YES_MANUAL, Entry.SKIP)
            })) {
            issues.add(StatisticsIssue(StatisticsIssueCode.INVALID_OBSERVATION, habitUuid,
                "Invalid boolean quota or observation"))
        }
        if (habit.isNumerical) {
            val targetNative = safeScaledTargetNative(representativeGoal.targetValue, representativeGoal.targetType)
            if (targetNative == null) {
                issues.add(
                    StatisticsIssue(
                        code = StatisticsIssueCode.INVALID_GOAL,
                        entityId = habitUuid,
                        message = "Invalid or unrepresentable numerical target value: ${representativeGoal.targetValue}",
                    )
                )
            }
            val observedUnits = effectiveDays
                .filter { it.includedInStatistics && !it.isSkipped && it.totalValue != null }
                .map { unitKey(goalAtDate(normalizedGoals, it.manualEntry.date).unit) }
                .distinct()
            if (observedUnits.size > 1 || (observedUnits.isNotEmpty() && observedUnits.single() != unitKey(representativeGoal.unit))) {
                issues.add(
                    StatisticsIssue(
                        code = StatisticsIssueCode.INCOMPATIBLE_UNIT,
                        entityId = habitUuid,
                        message = "Incompatible units in cycle: observed $observedUnits, goal unit ${representativeGoal.unit}",
                    )
                )
            }
        }

        val quantityResult = computeQuantity(habit, representativeGoal, effectiveDays, datesInCycle.size)
        val quantity = quantityResult.quantity
        if (quantityResult.hasOverflow) {
            issues.add(
                StatisticsIssue(
                    code = StatisticsIssueCode.OVERFLOW,
                    entityId = habitUuid,
                    message = "Numerical value sum overflow in cycle",
                )
            )
        }

        val outcome = when {
            habitInput.issues.isNotEmpty() ||
                lifecycle == HabitCycleLifecycle.UNSUPPORTED ||
                lifecycle == HabitCycleLifecycle.TRANSITION ||
                lifecycle == HabitCycleLifecycle.PARTIAL ||
                issues.any {
                    it.code == StatisticsIssueCode.INVALID_GOAL ||
                        it.code == StatisticsIssueCode.INVALID_OBSERVATION ||
                        it.code == StatisticsIssueCode.INCOMPATIBLE_UNIT ||
                        it.code == StatisticsIssueCode.OVERFLOW
                } -> {
                HabitCycleOutcome.UNAVAILABLE
            }
            quantity.totalDays > 0 && quantity.skippedDays == quantity.totalDays -> {
                HabitCycleOutcome.EXEMPT
            }
            !habit.isNumerical && quantity.booleanCapacity < representativeGoal.frequency.numerator -> {
                HabitCycleOutcome.CAPACITY_EXCLUDED
            }
            !habit.isNumerical -> {
                evaluateBooleanOutcome(
                    quotaT = representativeGoal.frequency.numerator,
                    L = quantity.booleanManualCount,
                    u = quantity.booleanUnknownDays,
                    U = quantity.booleanUpperBound,
                    isOngoing = lifecycle == HabitCycleLifecycle.ONGOING,
                )
            }
            else -> {
                evaluateNumericalOutcome(
                    targetType = representativeGoal.targetType,
                    targetValue = representativeGoal.targetValue,
                    targetValueNative = quantity.targetValueNative ?: 0L,
                    L = quantity.lowerBound,
                    u = quantity.unknownDays,
                    isOngoing = lifecycle == HabitCycleLifecycle.ONGOING,
                )
            }
        }

        return HabitCycle(
            key = cycleKey,
            habitUuid = habitUuid,
            habitName = habitName,
            cadence = cadence,
            lifecycle = lifecycle,
            outcome = outcome,
            goal = representativeGoal,
            startDate = startDate,
            endDate = endDate,
            quantity = quantity,
            days = effectiveDays,
            issues = issues,
            isContextual = isContextual,
            contextTruncatedTo = contextTruncatedTo,
        )
    }

    private fun safeScaledTargetNative(targetValue: Double, targetType: NumericalHabitType): Long? {
        if (!targetValue.isFinite() || targetValue < 0.0) return null
        return try {
            BigDecimal.valueOf(targetValue).movePointRight(3).setScale(0,
                if (targetType == NumericalHabitType.AT_LEAST) RoundingMode.CEILING else RoundingMode.FLOOR)
                .longValueExact()
        } catch (_: ArithmeticException) { null }
    }

    private data class QuantityResult(
        val quantity: HabitCycleQuantity,
        val hasOverflow: Boolean,
    )

    private fun computeQuantity(
        habit: Habit,
        goal: HabitGoal,
        days: List<EffectiveHabitDay>,
        expectedDaysCount: Int,
    ): QuantityResult {
        val isNumerical = habit.isNumerical
        val totalDays = expectedDaysCount
        val skippedDays = days.count { it.isSkipped }
        val excludedDays = days.count { !it.includedInStatistics }

        val activeDays = days.filter { it.includedInStatistics && !it.isSkipped }

        if (!isNumerical) {
            val booleanManual = activeDays.count { it.manualEntry.value == Entry.YES_MANUAL }
            val booleanUnknown = activeDays.count { it.manualEntry.value == Entry.UNKNOWN }
            val booleanCapacity = expectedDaysCount - skippedDays - excludedDays
            val booleanUpper = booleanManual + booleanUnknown
            val knownDays = activeDays.count { it.manualEntry.value != Entry.UNKNOWN }

            return QuantityResult(
                quantity = HabitCycleQuantity(
                    isNumerical = false,
                    lowerBound = booleanManual.toLong(),
                    manualValue = booleanManual.toLong(),
                    activeContributionValue = 0L,
                    excludedContributionValue = 0L,
                    revokedContributionValue = 0L,
                    targetValue = goal.frequency.numerator.toDouble(),
                    targetValueNative = null,
                    unit = goal.unit,
                    booleanManualCount = booleanManual,
                    booleanUnknownDays = booleanUnknown,
                    booleanUpperBound = booleanUpper,
                    booleanCapacity = booleanCapacity,
                    totalDays = totalDays,
                    knownDays = knownDays,
                    unknownDays = booleanUnknown,
                    skippedDays = skippedDays,
                    excludedDays = excludedDays,
                    hasCompleteCoverage = booleanUnknown == 0,
                ),
                hasOverflow = false,
            )
        } else {
            var hasOverflow = false

            var manualSum = 0L
            for (d in activeDays) {
                val mv = d.manualValue ?: continue
                try {
                    manualSum = Math.addExact(manualSum, mv)
                } catch (_: ArithmeticException) {
                    hasOverflow = true
                    break
                }
            }

            var activeContribSum = 0L
            for (d in activeDays) {
                try {
                    activeContribSum = Math.addExact(activeContribSum, d.contributionValue)
                } catch (_: ArithmeticException) {
                    hasOverflow = true
                    break
                }
            }

            var excludedContribSum = 0L
            for (d in days.filter { !it.includedInStatistics || it.isSkipped }) {
                try {
                    excludedContribSum = Math.addExact(excludedContribSum, d.contributionValue)
                } catch (_: ArithmeticException) {
                    hasOverflow = true
                    break
                }
            }

            var revokedContribSum = 0L
            for (d in days) {
                try {
                    revokedContribSum = Math.addExact(revokedContribSum, d.tombstonedContributionValue)
                } catch (_: ArithmeticException) {
                    hasOverflow = true
                    break
                }
            }

            var lowerBound = 0L
            if (!hasOverflow) {
                try {
                    lowerBound = Math.addExact(manualSum, activeContribSum)
                } catch (_: ArithmeticException) {
                    hasOverflow = true
                }
            }

            val unknownDays = activeDays.count { it.manualValue == null }
            val knownDays = activeDays.count { it.manualValue != null }
            val targetNative = safeScaledTargetNative(goal.targetValue, goal.targetType)

            return QuantityResult(
                quantity = HabitCycleQuantity(
                    isNumerical = true,
                    lowerBound = lowerBound,
                    manualValue = manualSum,
                    activeContributionValue = activeContribSum,
                    excludedContributionValue = excludedContribSum,
                    revokedContributionValue = revokedContribSum,
                    targetValue = goal.targetValue,
                    targetValueNative = targetNative,
                    unit = goal.unit,
                    booleanManualCount = 0,
                    booleanUnknownDays = 0,
                    booleanUpperBound = 0,
                    booleanCapacity = activeDays.size,
                    totalDays = totalDays,
                    knownDays = knownDays,
                    unknownDays = unknownDays,
                    skippedDays = skippedDays,
                    excludedDays = excludedDays,
                    hasCompleteCoverage = unknownDays == 0,
                ),
                hasOverflow = hasOverflow,
            )
        }
    }

    private fun evaluateBooleanOutcome(
        quotaT: Int,
        L: Int,
        u: Int,
        U: Int,
        isOngoing: Boolean,
    ): HabitCycleOutcome {
        if (quotaT == 0) return HabitCycleOutcome.SATISFIED
        if (L >= quotaT) return HabitCycleOutcome.SATISFIED
        if (isOngoing) return HabitCycleOutcome.UNKNOWN
        if (u > 0 && U >= quotaT) return HabitCycleOutcome.UNKNOWN
        return HabitCycleOutcome.NOT_SATISFIED
    }

    private fun evaluateNumericalOutcome(
        targetType: NumericalHabitType,
        targetValue: Double,
        targetValueNative: Long,
        L: Long,
        u: Int,
        isOngoing: Boolean,
    ): HabitCycleOutcome {
        return when (targetType) {
            NumericalHabitType.AT_LEAST -> {
                if (targetValue <= 0.0) {
                    HabitCycleOutcome.SATISFIED
                } else if (L >= targetValueNative) {
                    HabitCycleOutcome.SATISFIED
                } else if (isOngoing) {
                    HabitCycleOutcome.UNKNOWN
                } else if (u > 0) {
                    HabitCycleOutcome.UNKNOWN
                } else {
                    HabitCycleOutcome.NOT_SATISFIED
                }
            }
            NumericalHabitType.AT_MOST -> {
                if (L > targetValueNative) {
                    HabitCycleOutcome.NOT_SATISFIED
                } else if (isOngoing) {
                    HabitCycleOutcome.UNKNOWN
                } else if (u > 0) {
                    HabitCycleOutcome.UNKNOWN
                } else {
                    HabitCycleOutcome.SATISFIED
                }
            }
        }
    }

    private fun buildCadenceAggregate(
        cadence: HabitCadence,
        allCycles: List<HabitCycle>,
    ): HabitCadenceAggregate {
        val cadenceCycles = allCycles.filter { it.cadence == cadence }
        val closed = cadenceCycles.filter { !it.isContextual && it.lifecycle == HabitCycleLifecycle.CLOSED }

        val satisfiedCount = closed.count { it.outcome == HabitCycleOutcome.SATISFIED }
        val notSatisfiedCount = closed.count { it.outcome == HabitCycleOutcome.NOT_SATISFIED }
        val unknownCount = closed.count { it.outcome == HabitCycleOutcome.UNKNOWN }
        val exemptCount = closed.count { it.outcome == HabitCycleOutcome.EXEMPT }
        val capacityExcludedCount = closed.count { it.outcome == HabitCycleOutcome.CAPACITY_EXCLUDED }
        val unavailableCount = closed.count { it.outcome == HabitCycleOutcome.UNAVAILABLE }

        val partialCount = cadenceCycles.count { it.lifecycle == HabitCycleLifecycle.PARTIAL }
        val transitionCount = cadenceCycles.count { it.lifecycle == HabitCycleLifecycle.TRANSITION }
        val unsupportedCount = cadenceCycles.count { it.lifecycle == HabitCycleLifecycle.UNSUPPORTED }
        val ongoingCount = cadenceCycles.count { it.isContextual || it.lifecycle == HabitCycleLifecycle.ONGOING }

        return HabitCadenceAggregate(
            cadence = cadence,
            satisfiedCount = satisfiedCount,
            notSatisfiedCount = notSatisfiedCount,
            unknownCount = unknownCount,
            exemptCount = exemptCount,
            capacityExcludedCount = capacityExcludedCount,
            partialCount = partialCount,
            transitionCount = transitionCount,
            ongoingCount = ongoingCount,
            unsupportedCount = unsupportedCount,
            unavailableCount = unavailableCount,
            cycles = closed,
        )
    }

    private fun buildHabitSummaries(
        habits: List<StatisticsHabitInput>,
        allCycles: List<HabitCycle>,
    ): List<HabitSummary> {
        val summaries = mutableListOf<HabitSummary>()
        val cyclesByHabit = allCycles.groupBy { it.habitUuid }

        for (habitInput in habits) {
            val habit = habitInput.habit
            val habitUuid = habit.uuid ?: ""
            val habitCycles = cyclesByHabit[habitUuid].orEmpty()

            if (habitCycles.isEmpty()) {
                val defaultCadence = cadenceOf(normalizeGoals(habit).first())
                summaries.add(
                    HabitSummary(
                        habitUuid = habitUuid,
                        habitName = habit.name,
                        cadence = defaultCadence,
                        satisfiedCount = 0,
                        notSatisfiedCount = 0,
                        unknownCount = 0,
                        exemptCount = 0,
                        capacityExcludedCount = 0,
                        partialCount = 0,
                        transitionCount = 0,
                        cycles = emptyList(),
                    )
                )
                continue
            }

            val byCadence = habitCycles.groupBy { it.cadence }
            for ((cadence, cyclesInCadence) in byCadence) {
                val closed = cyclesInCadence.filter { !it.isContextual && it.lifecycle == HabitCycleLifecycle.CLOSED }
                val satisfied = closed.count { it.outcome == HabitCycleOutcome.SATISFIED }
                val notSatisfied = closed.count { it.outcome == HabitCycleOutcome.NOT_SATISFIED }
                val unknown = closed.count { it.outcome == HabitCycleOutcome.UNKNOWN }
                val exempt = closed.count { it.outcome == HabitCycleOutcome.EXEMPT }
                val capacityExcluded = closed.count { it.outcome == HabitCycleOutcome.CAPACITY_EXCLUDED }
                val partial = cyclesInCadence.count { it.lifecycle == HabitCycleLifecycle.PARTIAL }
                val transition = cyclesInCadence.count { it.lifecycle == HabitCycleLifecycle.TRANSITION }

                summaries.add(
                    HabitSummary(
                        habitUuid = habitUuid,
                        habitName = habit.name,
                        cadence = cadence,
                        satisfiedCount = satisfied,
                        notSatisfiedCount = notSatisfied,
                        unknownCount = unknown,
                        exemptCount = exempt,
                        capacityExcludedCount = capacityExcluded,
                        partialCount = partial,
                        transitionCount = transition,
                        cycles = closed,
                    )
                )
            }
        }
        return summaries
    }

    private fun buildDateBuckets(
        context: StatisticsQueryContext,
        closedCycles: List<HabitCycle>,
    ): Map<HabitCadence, List<HabitCycleDateBucket>> {
        val result = mutableMapOf<HabitCadence, List<HabitCycleDateBucket>>()
        val dates = context.dates()

        for (cadence in listOf(HabitCadence.DAILY, HabitCadence.WEEKLY, HabitCadence.MONTHLY)) {
            val cadenceCycles = closedCycles.filter { it.cadence == cadence }
            val byEndDate = cadenceCycles.groupBy { it.endDate }

            val bucketDates = if (cadence == HabitCadence.DAILY) dates else dates.filter { it in byEndDate }
            val buckets = bucketDates.map { date ->
                val cycles = byEndDate[date].orEmpty()
                HabitCycleDateBucket(
                    date = date,
                    cadence = cadence,
                    satisfiedCount = cycles.count { it.outcome == HabitCycleOutcome.SATISFIED },
                    notSatisfiedCount = cycles.count { it.outcome == HabitCycleOutcome.NOT_SATISFIED },
                    unknownCount = cycles.count { it.outcome == HabitCycleOutcome.UNKNOWN },
                    cycles = cycles,
                )
            }
            result[cadence] = buckets
        }
        return result
    }

    private fun normalizeGoals(habit: Habit): List<HabitGoal> {
        val history = habit.goalHistory
        if (history.isEmpty()) {
            return listOf(
                HabitGoal(
                    effectiveDate = LocalDate(2000, 1, 1),
                    frequency = habit.frequency,
                    targetType = habit.targetType,
                    targetValue = habit.targetValue,
                    unit = habit.unit,
                )
            )
        }
        if (history.size == 1) {
            val single = history[0]
            return listOf(
                single.copy(
                    frequency = habit.frequency,
                    targetType = habit.targetType,
                    targetValue = habit.targetValue,
                    unit = habit.unit,
                )
            )
        }
        val sorted = history.sortedBy { it.effectiveDate }
        val deduped = mutableListOf<HabitGoal>()
        for (goal in sorted) {
            if (deduped.isNotEmpty() && deduped.last().effectiveDate == goal.effectiveDate) {
                deduped[deduped.lastIndex] = goal
            } else {
                deduped.add(goal)
            }
        }
        return deduped
    }

    private fun goalAtDate(normalizedGoals: List<HabitGoal>, date: LocalDate): HabitGoal {
        return normalizedGoals.lastOrNull { !it.effectiveDate.isNewerThan(date) } ?: normalizedGoals.first()
    }

    private fun cadenceOf(goal: HabitGoal): HabitCadence {
        return when (goal.frequency.denominator) {
            1 -> HabitCadence.DAILY
            7 -> HabitCadence.WEEKLY
            30 -> HabitCadence.MONTHLY
            else -> HabitCadence.UNSUPPORTED
        }
    }

    private fun goalVersionKey(goal: HabitGoal): String {
        val unit = unitKey(goal.unit)
        return "${goal.effectiveDate}_${goal.frequency.numerator}over${goal.frequency.denominator}_${goal.targetType}_${goal.targetValue}_$unit"
    }

    private fun unitKey(unit: String): String = try {
        HabitTimeValueConverter.validateTargetHabit(isNumerical = true, unit = unit)
        "minute"
    } catch (_: IncompatibleTargetHabitException) {
        unit.trim().lowercase()
    }

    private fun enumerateDates(from: LocalDate, to: LocalDate): List<LocalDate> = buildList {
        var date = from
        while (date <= to) {
            add(date)
            date = date.plus(1)
        }
    }
}
