/* Copyright (C) 2026 Álinson Santos Xavier and contributors. GPL-3.0-or-later. */
package org.isoron.uhabits.core.models.projection

import org.isoron.platform.time.DayOfWeek
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.getFirstWeekdayNumberAccordingToLocale
import org.isoron.platform.time.localDateAt
import org.isoron.platform.time.startOfLocalDay
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.contribution.Contribution
import org.isoron.uhabits.core.contribution.ContributionRepository
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitGoal
import org.isoron.uhabits.core.models.NumericalHabitType
import org.isoron.uhabits.core.rules.HabitTimeValueConverter
import org.isoron.uhabits.core.rules.IncompatibleTargetHabitException

class HabitProjectionIntegrityException(message: String) : IllegalStateException(message)

/** Quantity and coverage are separate: a derived quantity does not prove a complete manual log. */
data class EffectiveHabitDay(
    val manualEntry: Entry,
    val manualValue: Long?,
    val contributionValue: Long,
    val totalValue: Long?,
    val contributions: List<Contribution>,
    val isNumerical: Boolean,
    val includedInStatistics: Boolean,
) {
    val isSkipped: Boolean get() = manualEntry.value == Entry.SKIP
    val hasCompleteCoverage: Boolean get() = manualValue != null
    val contributionCount: Int get() = contributions.size
}

data class EffectiveHabitValue(
    val days: List<EffectiveHabitDay>,
    val manualValue: Long,
    val contributionValue: Long,
    /** null means no included numerical observation; explicit manual/derived zero remains zero. */
    val totalValue: Long?,
    val excludedContributionValue: Long,
) {
    val contributionCount: Int get() = days.filter { it.includedInStatistics && !it.isSkipped }.sumOf { it.contributionCount }
    val unknownDates: List<LocalDate> get() = days.filter {
        it.includedInStatistics && !it.isSkipped && !it.hasCompleteCoverage
    }.map { it.manualEntry.date }
    val skippedDates: List<LocalDate> get() = days.filter { it.includedInStatistics && it.isSkipped }.map { it.manualEntry.date }
    val hasCompleteCoverage: Boolean get() = unknownDates.isEmpty()
}

enum class HabitGoalStatus { SATISFIED, NOT_SATISFIED, UNKNOWN, SKIPPED, EXCLUDED }

data class EffectiveHabitGoal(
    val goal: HabitGoal,
    val from: LocalDate,
    val to: LocalDate,
    val value: EffectiveHabitValue,
    val status: HabitGoalStatus,
)

/**
 * Opt-in read projection. Never changes Habit entries, scores, goals, facts or rule definitions.
 * Callers pass the same day offset as computeToday; platform conversion uses the current local timezone.
 * No container/rule reevaluation and no duration conversion. Contributions are already x1000 values.
 * SKIP excludes that day's automatic quantity from goal totals, retaining it in the breakdown.
 * UNKNOWN plus Contributions yields a known lower bound, not complete coverage (AT_MOST stays UNKNOWN).
 */
class EffectiveHabitValueService(
    private val repository: ContributionRepository,
    private val hourOffset: Int = 0,
    private val minuteOffset: Int = 0,
    private val firstWeekday: DayOfWeek = DayOfWeek.entries[getFirstWeekdayNumberAccordingToLocale() - 1],
) {
    fun valueForDay(habit: Habit, date: LocalDate): EffectiveHabitDay =
        valueForRange(habit, date, date).days.single()

    fun valueForRange(habit: Habit, from: LocalDate, to: LocalDate): EffectiveHabitValue =
        valuesForRange(listOf(habit), from, to).getValue(HabitRef(requireNotNull(habit.uuid)))

    /** One range fetch per bounded batch of Habit IDs; no Habit x day Contribution query loop. */
    fun valuesForRange(habits: List<Habit>, from: LocalDate, to: LocalDate): Map<HabitRef, EffectiveHabitValue> {
        require(from <= to) { "Range start must not follow its end" }
        val byRef = habits.associateBy { HabitRef(requireNotNull(it.uuid)) }
        require(byRef.size == habits.size) { "Duplicate Habit identities in projection batch" }
        if (byRef.isEmpty()) return emptyMap()
        // A configured offset inside a DST overlap can make the existing Habit date label
        // briefly move backwards. Fetch a one-day envelope and bucket with the canonical
        // function, rather than assuming each label is always one contiguous UTC interval.
        val start = startOfLocalDay(from.minus(1), hourOffset, minuteOffset)
        val end = startOfLocalDay(to.plus(2), hourOffset, minuteOffset)
        val facts = repository.getByHabitsInRange(byRef.keys, start, end - 1)
            .filter { it.isActive }
            .groupBy { it.targetHabit }
        return byRef.mapValues { (ref, habit) ->
            val byDay = facts[ref].orEmpty().groupBy { localDateAt(it.effectiveTimestamp, hourOffset, minuteOffset) }
            val days = habit.originalEntries.getByInterval(from, to).map { entry ->
                projectDay(habit, entry, byDay[entry.date].orEmpty())
            }.sortedBy { it.manualEntry.date }
            val observedDays = days.filter { it.includedInStatistics && !it.isSkipped && it.totalValue != null }
            if (observedDays.map { unitKey(goalAt(habit, it.manualEntry.date).unit) }.distinct().size > 1) {
                throw HabitProjectionIntegrityException("Cannot aggregate different historical Habit units")
            }
            if (days.any { it.includedInStatistics && !it.isSkipped && it.contributions.isNotEmpty() }) {
                // A range may straddle a unit change even when facts occur on only one side.
                // Never sum a known manual hour/page measurement with minute Contributions.
                for (day in observedDays) {
                    validateUnits(habit, day.manualEntry.date)
                }
            }
            aggregate(days)
        }
    }

    /** Calendar week/month, following Habit.periodActualOn (denominators 7/30); other goals use one day. */
    fun goalProgressOn(habit: Habit, date: LocalDate): EffectiveHabitGoal {
        val goal = goalAt(habit, date)
        val from = when (goal.frequency.denominator) {
            7 -> date.startOfWeek(firstWeekday)
            30 -> date.startOfMonth()
            else -> date
        }
        val to = when (goal.frequency.denominator) {
            7 -> from.plus(6)
            30 -> from.plus(from.monthLength - 1)
            else -> date
        }
        val value = valueForRange(habit, from, to)
        if (habit.isNumerical && value.days.any {
            it.includedInStatistics && !it.isSkipped && it.totalValue != null &&
                unitKey(goalAt(habit, it.manualEntry.date).unit) != unitKey(goal.unit)
        }) {
            throw HabitProjectionIntegrityException("Period quantity is incompatible with the selected Habit goal unit")
        }
        val day = value.days.single { it.manualEntry.date == date }
        val status = when {
            !day.includedInStatistics -> HabitGoalStatus.EXCLUDED
            day.isSkipped -> HabitGoalStatus.SKIPPED
            !habit.isNumerical -> if (habit.isCompletedOn(date)) HabitGoalStatus.SATISFIED else HabitGoalStatus.NOT_SATISFIED
            value.totalValue == null -> HabitGoalStatus.UNKNOWN
            goal.targetType == NumericalHabitType.AT_MOST && value.totalValue / 1000.0 > goal.targetValue -> HabitGoalStatus.NOT_SATISFIED
            goal.targetType == NumericalHabitType.AT_MOST && !value.hasCompleteCoverage -> HabitGoalStatus.UNKNOWN
            goal.targetType == NumericalHabitType.AT_LEAST && !value.hasCompleteCoverage && value.totalValue / 1000.0 < goal.targetValue -> HabitGoalStatus.UNKNOWN
            else -> {
                val actual = value.totalValue / 1000.0
                val satisfied = when (goal.targetType) {
                    NumericalHabitType.AT_LEAST -> actual >= goal.targetValue
                    NumericalHabitType.AT_MOST -> actual <= goal.targetValue
                }
                if (satisfied) HabitGoalStatus.SATISFIED else HabitGoalStatus.NOT_SATISFIED
            }
        }
        return EffectiveHabitGoal(goal, from, to, value, status)
    }

    private fun projectDay(habit: Habit, entry: Entry, contributions: List<Contribution>): EffectiveHabitDay {
        if (contributions.isNotEmpty()) {
            validateUnits(habit, entry.date)
        }
        if (entry.value < Entry.UNKNOWN) throw HabitProjectionIntegrityException("Invalid manual value ${entry.value}")
        val manual = if (habit.isNumerical && entry.value != Entry.UNKNOWN && entry.value != Entry.SKIP) entry.value.toLong() else null
        val automatic = contributions.fold(0L) { sum, fact -> add(sum, fact.contributedValue) }
        val included = habit.isDateIncludedInStatistics(entry.date)
        val total = if (!habit.isNumerical || entry.value == Entry.SKIP || !included || (manual == null && contributions.isEmpty())) null
        else add(manual ?: 0L, automatic)
        return EffectiveHabitDay(entry, manual, automatic, total, contributions, habit.isNumerical, included)
    }

    private fun aggregate(days: List<EffectiveHabitDay>): EffectiveHabitValue {
        var manual = 0L
        var automatic = 0L
        var excluded = 0L
        var observed = false
        for (day in days) {
            if (!day.includedInStatistics || day.isSkipped) {
                excluded = add(excluded, day.contributionValue)
                continue
            }
            manual = add(manual, day.manualValue ?: 0L)
            automatic = add(automatic, day.contributionValue)
            observed = observed || day.totalValue != null
        }
        return EffectiveHabitValue(days, manual, automatic, if (observed) add(manual, automatic) else null, excluded)
    }

    private fun add(a: Long, b: Long): Long {
        if (b < 0 || a > Long.MAX_VALUE - b) throw HabitProjectionIntegrityException("Habit value projection overflow")
        return a + b
    }

    private fun validateUnits(habit: Habit, date: LocalDate) {
        try {
            // Until units are frozen on the ledger, only explicit minute semantics are additive.
            HabitTimeValueConverter.validateTargetHabit(habit.isNumerical, goalAt(habit, date).unit, habit.name, habit.uuid)
            HabitTimeValueConverter.validateTargetHabit(habit)
        } catch (e: IncompatibleTargetHabitException) {
            throw HabitProjectionIntegrityException(e.message ?: "Incompatible Contribution target")
        }
    }

    private fun unitKey(unit: String): String = try {
        HabitTimeValueConverter.validateTargetHabit(isNumerical = true, unit = unit)
        "minute"
    } catch (_: IncompatibleTargetHabitException) {
        unit.trim().lowercase()
    }

    /** Match legacy normalization without Habit.goalAt's in-memory goalHistory mutations. */
    private fun goalAt(habit: Habit, date: LocalDate): HabitGoal {
        val history = when (habit.goalHistory.size) {
            0 -> listOf(habit.currentGoal())
            1 -> listOf(habit.goalHistory.single().copy(
                frequency = habit.frequency, targetType = habit.targetType,
                targetValue = habit.targetValue, unit = habit.unit,
            ))
            else -> habit.goalHistory.toList()
        }.groupBy { it.effectiveDate }.values.map { it.last() }.sortedBy { it.effectiveDate }
        return history.lastOrNull { it.effectiveDate <= date } ?: history.first()
    }
}
