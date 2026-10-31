/* Copyright (C) 2026 Álinson Santos Xavier and contributors. GPL-3.0-or-later. */
package org.isoron.uhabits.core.statistics

import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitGoal
import org.isoron.uhabits.core.models.NumericalHabitType
import org.isoron.uhabits.core.models.projection.EffectiveHabitDay
import org.isoron.uhabits.core.rules.HabitTimeValueConverter
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
        val cutoffLimit = minOf(context.to, context.habitToday)

        for (habitInput in habits) {
            val evaluated = evaluateHabit(context, habitInput)
            // A cycle starting after the report's observation cutoff has not started.
            for (cycle in evaluated.cycles) {
                if (cycle.startDate <= cutoffLimit) {
                    allEvaluatedCycles.add(cycle)
                }
            }
            allIssues.addAll(evaluated.issues)
        }

        val closedCycles = ArrayList<HabitCycle>(allEvaluatedCycles.size)
        val ongoingCycles = ArrayList<HabitCycle>()
        for (cycle in allEvaluatedCycles) {
            if (!cycle.isContextual && cycle.lifecycle == HabitCycleLifecycle.CLOSED) {
                closedCycles.add(cycle)
            } else if (cycle.isContextual || cycle.lifecycle == HabitCycleLifecycle.ONGOING) {
                ongoingCycles.add(cycle)
            }
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
        val unitKeysByGoal = normalizedGoals.map { unitKey(it.unit) }
        val versionKeysByGoal = normalizedGoals.mapIndexed { idx, g ->
            "${g.effectiveDate}_${g.frequency.numerator}over${g.frequency.denominator}_${g.targetType}_${g.targetValue}_${unitKeysByGoal[idx]}"
        }
        val targetNativeByGoal = if (habit.isNumerical) {
            normalizedGoals.map { safeScaledTargetNative(it.targetValue, it.targetType) }
        } else {
            emptyList()
        }
        val candidateIntervals = generateCandidateIntervals(context, normalizedGoals)

        val cycles = ArrayList<HabitCycle>(candidateIntervals.size)
        val issues = mutableListOf<StatisticsIssue>()
        issues.addAll(habitInput.issues)

        val inputDays = habitInput.days
        val isContiguous = inputDays.isNotEmpty() &&
            inputDays.size == inputDays.last().manualEntry.date.daysSince2000 - inputDays.first().manualEntry.date.daysSince2000 + 1 &&
            inputDays.indices.all { i -> inputDays[i].manualEntry.date.daysSince2000 == inputDays[0].manualEntry.date.daysSince2000 + i }
        val daysByDate = if (isContiguous) null else inputDays.associateBy { it.manualEntry.date }
        val startCutoff = habit.effectiveStatisticsStartDate() ?: habitInput.observationStart
        val contextLimit = minOf(context.to, context.habitToday)

        for (interval in candidateIntervals) {
            val cycle = evaluateCycle(
                context = context,
                habitInput = habitInput,
                habitUuid = habitUuid,
                habitName = habitName,
                normalizedGoals = normalizedGoals,
                unitKeysByGoal = unitKeysByGoal,
                versionKeysByGoal = versionKeysByGoal,
                targetNativeByGoal = targetNativeByGoal,
                interval = interval,
                inputDays = inputDays,
                isContiguous = isContiguous,
                daysByDate = daysByDate,
                startCutoff = startCutoff,
                contextLimit = contextLimit,
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
            val cycleKey = HabitCycleKey(habitUuid, versionKeysByGoal[0], startDate, endDate)
            val targetNative = if (habit.isNumerical) targetNativeByGoal[0] else null
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
                quantity = computeQuantity(habit, goal, targetNative, emptyList(), 0).quantity,
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

        return if (normalizedGoals.size <= 1) intervals else intervals.distinctBy { Triple(it.startDate, it.endDate, it.cadence) }
    }

    private fun evaluateCycle(
        context: StatisticsQueryContext,
        habitInput: StatisticsHabitInput,
        habitUuid: String,
        habitName: String,
        normalizedGoals: List<HabitGoal>,
        unitKeysByGoal: List<String>,
        versionKeysByGoal: List<String>,
        targetNativeByGoal: List<Long?>,
        interval: CandidateInterval,
        inputDays: List<EffectiveHabitDay>,
        isContiguous: Boolean,
        daysByDate: Map<LocalDate, EffectiveHabitDay>?,
        startCutoff: LocalDate?,
        contextLimit: LocalDate,
    ): HabitCycle {
        val startDate = interval.startDate
        val endDate = interval.endDate
        val cadence = interval.cadence
        val habit = habitInput.habit
        val issues = if (habitInput.issues.isEmpty()) mutableListOf() else habitInput.issues.toMutableList()

        val endsInP = endDate >= context.from && endDate <= context.to
        val isClosedByDate = endDate < context.habitToday
        val expectedDaysCount = endDate.daysSince2000 - startDate.daysSince2000 + 1

        // Check midcycle goal transitions
        val firstGoalIdx: Int
        val representativeGoalIdx: Int
        val hasGoalTransition: Boolean
        if (normalizedGoals.size == 1) {
            firstGoalIdx = 0
            representativeGoalIdx = 0
            hasGoalTransition = false
        } else if (startDate == endDate) {
            firstGoalIdx = goalIndexAtDate(normalizedGoals, startDate)
            representativeGoalIdx = firstGoalIdx
            hasGoalTransition = false
        } else {
            firstGoalIdx = goalIndexAtDate(normalizedGoals, startDate)
            val firstGoal = normalizedGoals[firstGoalIdx]
            val firstUnitKey = unitKeysByGoal[firstGoalIdx]
            var lastIdx = firstGoalIdx
            var transitionFound = false
            var d = startDate.plus(1)
            while (d <= endDate) {
                val idx = goalIndexAtDate(normalizedGoals, d)
                lastIdx = idx
                if (!transitionFound && idx != firstGoalIdx) {
                    val g = normalizedGoals[idx]
                    if (g.frequency != firstGoal.frequency ||
                        g.targetType != firstGoal.targetType ||
                        g.targetValue != firstGoal.targetValue ||
                        unitKeysByGoal[idx] != firstUnitKey
                    ) {
                        transitionFound = true
                    }
                }
                d = d.plus(1)
            }
            representativeGoalIdx = lastIdx
            hasGoalTransition = transitionFound
        }
        val firstGoal = normalizedGoals[firstGoalIdx]
        val representativeGoal = normalizedGoals[representativeGoalIdx]

        // Check initial cutoff / observation start:
        // Canonical C1 specifies explicit effectiveStatisticsStartDate has priority,
        // otherwise observationStart is the lower bound.
        // Any cycle starting before startCutoff (wholly before or intersecting cutoff)
        // is marked PARTIAL and excluded from closed outcomes (never S/F/U).
        val isPartial = startCutoff != null && startDate < startCutoff

        val lifecycle = when {
            cadence == HabitCadence.UNSUPPORTED -> {
                issues.add(
                    StatisticsIssue(
                        code = StatisticsIssueCode.UNSUPPORTED_FREQUENCY,
                        entityId = habitUuid,
                        message = "Unsupported frequency denominator: ${firstGoal.frequency.denominator}",
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
        val isContextual = lifecycle == HabitCycleLifecycle.ONGOING || endDate > contextLimit
        val contextTruncatedTo = if (isContextual || endDate > contextLimit) contextLimit else null

        val goalVersionKey = if (hasGoalTransition) {
            "transition_${startDate}_$endDate"
        } else {
            versionKeysByGoal[representativeGoalIdx]
        }
        val cycleKey = HabitCycleKey(habitUuid, goalVersionKey, startDate, endDate)

        val effectiveEnd = if (contextTruncatedTo != null) minOf(endDate, contextTruncatedTo) else endDate
        val effectiveDays: List<EffectiveHabitDay> = when {
            startDate > effectiveEnd -> emptyList()
            isContiguous && startDate >= inputDays.first().manualEntry.date && effectiveEnd <= inputDays.last().manualEntry.date -> {
                val baseDays = inputDays.first().manualEntry.date.daysSince2000
                inputDays.subList(startDate.daysSince2000 - baseDays, effectiveEnd.daysSince2000 - baseDays + 1)
            }
            else -> {
                val span = effectiveEnd.daysSince2000 - startDate.daysSince2000 + 1
                val list = ArrayList<EffectiveHabitDay>(span)
                val baseDays = if (isContiguous) inputDays.first().manualEntry.date.daysSince2000 else 0
                var d = startDate
                while (d <= effectiveEnd) {
                    val found = if (isContiguous) {
                        val idx = d.daysSince2000 - baseDays
                        if (idx in inputDays.indices) inputDays[idx] else null
                    } else {
                        daysByDate?.get(d)
                    }
                    list.add(
                        found ?: EffectiveHabitDay(
                            manualEntry = Entry(d, Entry.UNKNOWN),
                            manualValue = null,
                            contributionValue = 0L,
                            totalValue = null,
                            contributions = emptyList(),
                            isNumerical = habit.isNumerical,
                            includedInStatistics = habit.isDateIncludedInStatistics(d),
                        )
                    )
                    d = d.plus(1)
                }
                list
            }
        }

        // Numerical validation & scaled target representation
        // Note: No type history: current Habit.type only, cannot reconstruct historical type edits.
        if (!habit.isNumerical) {
            var invalidObs = representativeGoal.frequency.numerator < 0
            if (!invalidObs) {
                for (day in effectiveDays) {
                    val v = day.manualEntry.value
                    if (v != Entry.UNKNOWN && v != Entry.NO && v != Entry.YES_AUTO && v != Entry.YES_MANUAL && v != Entry.SKIP) {
                        invalidObs = true
                        break
                    }
                }
            }
            if (invalidObs) {
                issues.add(StatisticsIssue(StatisticsIssueCode.INVALID_OBSERVATION, habitUuid,
                    "Invalid boolean quota or observation"))
            }
        }
        val targetNative = if (habit.isNumerical) targetNativeByGoal[representativeGoalIdx] else null
        if (habit.isNumerical) {
            if (targetNative == null) {
                issues.add(
                    StatisticsIssue(
                        code = StatisticsIssueCode.INVALID_GOAL,
                        entityId = habitUuid,
                        message = "Invalid or unrepresentable numerical target value: ${representativeGoal.targetValue}",
                    )
                )
            }
            if (normalizedGoals.size > 1) {
                val repUnitKey = unitKeysByGoal[representativeGoalIdx]
                var hasIncompatible = false
                for (day in effectiveDays) {
                    if (day.includedInStatistics && !day.isSkipped && day.totalValue != null) {
                        val uKey = unitKeysByGoal[goalIndexAtDate(normalizedGoals, day.manualEntry.date)]
                        if (uKey != repUnitKey) {
                            hasIncompatible = true
                            break
                        }
                    }
                }
                if (hasIncompatible) {
                    val observedUnits = effectiveDays
                        .filter { it.includedInStatistics && !it.isSkipped && it.totalValue != null }
                        .map { unitKeysByGoal[goalIndexAtDate(normalizedGoals, it.manualEntry.date)] }
                        .distinct()
                    issues.add(
                        StatisticsIssue(
                            code = StatisticsIssueCode.INCOMPATIBLE_UNIT,
                            entityId = habitUuid,
                            message = "Incompatible units in cycle: observed $observedUnits, goal unit ${representativeGoal.unit}",
                        )
                    )
                }
            }
        }

        val quantityResult = computeQuantity(habit, representativeGoal, targetNative, effectiveDays, expectedDaysCount)
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
        targetNative: Long?,
        days: List<EffectiveHabitDay>,
        expectedDaysCount: Int,
    ): QuantityResult {
        val isNumerical = habit.isNumerical
        val totalDays = expectedDaysCount

        if (!isNumerical) {
            var skippedDays = 0
            var excludedDays = 0
            var booleanManual = 0
            var booleanUnknown = 0
            var knownDays = 0
            for (d in days) {
                if (d.isSkipped) {
                    skippedDays++
                } else if (!d.includedInStatistics) {
                    excludedDays++
                } else {
                    val v = d.manualEntry.value
                    if (v == Entry.YES_MANUAL) {
                        booleanManual++
                        knownDays++
                    } else if (v == Entry.UNKNOWN) {
                        booleanUnknown++
                    } else {
                        knownDays++
                    }
                }
            }
            val booleanCapacity = expectedDaysCount - skippedDays - excludedDays
            val booleanUpper = booleanManual + booleanUnknown

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
            var skippedDays = 0
            var excludedDays = 0
            var activeDaysCount = 0
            var unknownDays = 0
            var knownDays = 0
            for (d in days) {
                if (d.isSkipped) {
                    skippedDays++
                }
                if (!d.includedInStatistics) {
                    excludedDays++
                }
                if (d.includedInStatistics && !d.isSkipped) {
                    activeDaysCount++
                    if (d.manualValue == null) unknownDays++ else knownDays++
                }
            }

            var hasOverflow = false

            var manualSum = 0L
            for (d in days) {
                if (!d.includedInStatistics || d.isSkipped) continue
                val mv = d.manualValue ?: continue
                try {
                    manualSum = Math.addExact(manualSum, mv)
                } catch (_: ArithmeticException) {
                    hasOverflow = true
                    break
                }
            }

            var activeContribSum = 0L
            for (d in days) {
                if (!d.includedInStatistics || d.isSkipped) continue
                try {
                    activeContribSum = Math.addExact(activeContribSum, d.contributionValue)
                } catch (_: ArithmeticException) {
                    hasOverflow = true
                    break
                }
            }

            var excludedContribSum = 0L
            for (d in days) {
                if (d.includedInStatistics && !d.isSkipped) continue
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
                    booleanCapacity = activeDaysCount,
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
        val closed = mutableListOf<HabitCycle>()
        var satisfiedCount = 0
        var notSatisfiedCount = 0
        var unknownCount = 0
        var exemptCount = 0
        var capacityExcludedCount = 0
        var unavailableCount = 0
        var partialCount = 0
        var transitionCount = 0
        var unsupportedCount = 0
        var ongoingCount = 0

        for (cycle in allCycles) {
            if (cycle.cadence != cadence) continue
            if (!cycle.isContextual && cycle.lifecycle == HabitCycleLifecycle.CLOSED) {
                closed.add(cycle)
                when (cycle.outcome) {
                    HabitCycleOutcome.SATISFIED -> satisfiedCount++
                    HabitCycleOutcome.NOT_SATISFIED -> notSatisfiedCount++
                    HabitCycleOutcome.UNKNOWN -> unknownCount++
                    HabitCycleOutcome.EXEMPT -> exemptCount++
                    HabitCycleOutcome.CAPACITY_EXCLUDED -> capacityExcludedCount++
                    HabitCycleOutcome.UNAVAILABLE -> unavailableCount++
                }
            }
            when (cycle.lifecycle) {
                HabitCycleLifecycle.PARTIAL -> partialCount++
                HabitCycleLifecycle.TRANSITION -> transitionCount++
                HabitCycleLifecycle.UNSUPPORTED -> unsupportedCount++
                else -> {}
            }
            if (cycle.isContextual || cycle.lifecycle == HabitCycleLifecycle.ONGOING) {
                ongoingCount++
            }
        }

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
                val closed = ArrayList<HabitCycle>(cyclesInCadence.size)
                var satisfied = 0
                var notSatisfied = 0
                var unknown = 0
                var exempt = 0
                var capacityExcluded = 0
                var partial = 0
                var transition = 0
                for (c in cyclesInCadence) {
                    if (!c.isContextual && c.lifecycle == HabitCycleLifecycle.CLOSED) {
                        closed.add(c)
                        when (c.outcome) {
                            HabitCycleOutcome.SATISFIED -> satisfied++
                            HabitCycleOutcome.NOT_SATISFIED -> notSatisfied++
                            HabitCycleOutcome.UNKNOWN -> unknown++
                            HabitCycleOutcome.EXEMPT -> exempt++
                            HabitCycleOutcome.CAPACITY_EXCLUDED -> capacityExcluded++
                            HabitCycleOutcome.UNAVAILABLE -> {}
                        }
                    }
                    if (c.lifecycle == HabitCycleLifecycle.PARTIAL) partial++
                    else if (c.lifecycle == HabitCycleLifecycle.TRANSITION) transition++
                }

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
                var satisfied = 0
                var notSatisfied = 0
                var unknown = 0
                for (c in cycles) {
                    when (c.outcome) {
                        HabitCycleOutcome.SATISFIED -> satisfied++
                        HabitCycleOutcome.NOT_SATISFIED -> notSatisfied++
                        HabitCycleOutcome.UNKNOWN -> unknown++
                        else -> {}
                    }
                }
                HabitCycleDateBucket(
                    date = date,
                    cadence = cadence,
                    satisfiedCount = satisfied,
                    notSatisfiedCount = notSatisfied,
                    unknownCount = unknown,
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

    private fun goalIndexAtDate(normalizedGoals: List<HabitGoal>, date: LocalDate): Int {
        for (i in normalizedGoals.size - 1 downTo 0) {
            if (!normalizedGoals[i].effectiveDate.isNewerThan(date)) return i
        }
        return 0
    }

    private fun cadenceOf(goal: HabitGoal): HabitCadence {
        return when (goal.frequency.denominator) {
            1 -> HabitCadence.DAILY
            7 -> HabitCadence.WEEKLY
            30 -> HabitCadence.MONTHLY
            else -> HabitCadence.UNSUPPORTED
        }
    }

    private fun unitKey(unit: String): String = HabitTimeValueConverter.normalizeUnitKey(unit)
}
