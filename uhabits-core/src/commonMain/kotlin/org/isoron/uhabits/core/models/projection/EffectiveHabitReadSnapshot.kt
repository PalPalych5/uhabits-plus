/* Copyright (C) 2026 Álinson Santos Xavier and contributors. GPL-3.0-or-later. */
package org.isoron.uhabits.core.models.projection

import org.isoron.platform.time.DayOfWeek
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.localDateAt
import org.isoron.platform.time.getFirstWeekdayNumberAccordingToLocale
import org.isoron.platform.time.getToday
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.contribution.ContributionRepository
import org.isoron.uhabits.core.models.*

/** Calendar identity is captured by the platform caller together with its live preferences. */
data class EffectiveReadCalendarContext(
    val timezoneId: String,
    val firstWeekday: DayOfWeek,
    val hourOffset: Int,
    val minuteOffset: Int,
)
enum class EffectiveReadExactness { LEGACY_COMPATIBILITY, EXACT, INCOMPLETE, UNREPRESENTABLE }
data class GoalPeriodAchievement(val progress: EffectiveHabitGoal) {
    val status: HabitGoalStatus get() = progress.status
}
data class CompatibilityCompletion(val status: HabitGoalStatus) {
    val isCompleted: Boolean get() = status == HabitGoalStatus.SATISFIED
}
data class EffectiveScoreResult(val scores: List<Score>?, val exactness: EffectiveReadExactness) {
    fun on(date: LocalDate): Double? = scores?.firstOrNull { it.date == date }?.value
}
data class EffectiveStreakResult(val streaks: List<Streak>?, val exactness: EffectiveReadExactness)

/**
 * An immutable read result. Its captured detached model is private and is never given to a writer.
 * Entry and Contribution records are immutable; copies isolate the lists from caller mutations.
 */
class EffectiveHabitReadSnapshot internal constructor(
    val datasetId: String,
    val revision: Long,
    val calendar: EffectiveReadCalendarContext,
    val habitRef: HabitRef,
    val from: LocalDate,
    val to: LocalDate,
    val observedFrom: LocalDate?,
    val observedTo: LocalDate?,
    private val resultTo: LocalDate,
    private val compatibilityTo: LocalDate,
    private val captured: Habit,
    val days: List<EffectiveHabitDay>,
    private val service: EffectiveHabitValueService,
    private val compatibilityService: EffectiveHabitValueService,
) {
    private val byDate = days.associateBy { it.manualEntry.date }
    val hasContributions: Boolean = days.any { it.contributions.isNotEmpty() }
    val canUseLegacyEntryAdapter: Boolean = !hasContributions || (
        service.hasUniformObservationUnits(captured, days) && legacyQuantitiesRepresentable(days))
    private val representable get() = canUseLegacyEntryAdapter
    // Legacy UNKNOWN-as-zero remains an explicitly named compatibility result only when no credit exists.
    // Once automatic observations exist, a missing manual log cannot be made exact by an Int adapter.
    private val scoreIncomplete = hasContributions && representable && hasIncompleteAlgorithmInputs(resultTo, true)
    private val streakIncomplete = hasContributions && representable && hasIncompleteAlgorithmInputs(compatibilityTo, false)
    val scoreResult: EffectiveScoreResult
    val streakResult: EffectiveStreakResult

    init {
        val baseExactness = when {
            !hasContributions -> EffectiveReadExactness.LEGACY_COMPATIBILITY
            !representable -> EffectiveReadExactness.UNREPRESENTABLE
            else -> EffectiveReadExactness.EXACT
        }
        if (!representable) {
            scoreResult = EffectiveScoreResult(null, baseExactness)
            streakResult = EffectiveStreakResult(null, baseExactness)
        } else {
            val projected = detachedPresentation()
            val start = projected.effectiveStatisticsStartDate()
                ?: projected.computedEntries.getKnown().lastOrNull()?.date ?: from
            val safe = !hasContributions || rollingWindowsRepresentable(projected, start, resultTo)
            scoreResult = when {
                !safe -> EffectiveScoreResult(null, EffectiveReadExactness.UNREPRESENTABLE)
                scoreIncomplete -> EffectiveScoreResult(null, EffectiveReadExactness.INCOMPLETE)
                else -> {
                    projected.scores.recompute(projected, projected.computedEntries, start, resultTo)
                    EffectiveScoreResult(projected.scores.getByInterval(from, resultTo).toList(), baseExactness)
                }
            }
            streakResult = if (streakIncomplete) EffectiveStreakResult(null, EffectiveReadExactness.INCOMPLETE)
            else EffectiveStreakResult(buildStreaks(minOf(start, compatibilityTo), compatibilityTo), baseExactness)
        }
    }
    fun day(date: LocalDate): EffectiveHabitDay = requireNotNull(byDate[date]) { "Date outside snapshot" }
    fun dayOrNull(date: LocalDate): EffectiveHabitDay? = byDate[date]

    /** PR15 calendar achievement, intentionally distinct from legacy rolling score and completion. */
    fun goalAchievementOn(date: LocalDate): GoalPeriodAchievement =
        GoalPeriodAchievement(service.goalProgressOn(captured, date, days))

    /** Same compatibility definition before/after insert or final tombstone, with typed coverage. */
    fun compatibilityCompletionOn(date: LocalDate): CompatibilityCompletion {
        if (!captured.isNumerical || !hasContributions) {
            return CompatibilityCompletion(if (captured.isCompletedOn(date)) HabitGoalStatus.SATISFIED else HabitGoalStatus.NOT_SATISFIED)
        }
        val day = day(date)
        val status = when {
            !day.includedInStatistics -> HabitGoalStatus.EXCLUDED
            day.isSkipped -> HabitGoalStatus.SKIPPED
            day.totalValue == null -> HabitGoalStatus.UNKNOWN
            captured.goalAt(date).targetType == NumericalHabitType.AT_MOST -> compatibilityService.goalProgressOn(captured, date, days).status
            day.totalValue / 1000.0 >= captured.goalAt(date).targetValue -> HabitGoalStatus.SATISFIED
            !day.hasCompleteCoverage -> HabitGoalStatus.UNKNOWN
            else -> HabitGoalStatus.NOT_SATISFIED
        }
        return CompatibilityCompletion(status)
    }

    /** Fresh disposable adapter for legacy card algorithms; never use as an edit seed or model. */
    fun presentationHabit(): Habit {
        check(representable) { "Effective observations cannot be represented by legacy Int entries" }
        return detachedPresentation().also { projected ->
            if (scoreResult.scores != null) {
                val start = projected.effectiveStatisticsStartDate()
                    ?: projected.computedEntries.getKnown().lastOrNull()?.date ?: from
                projected.scores.recompute(projected, projected.computedEntries, start, resultTo)
            }
            // Legacy StreakList still calls Habit's completion helper. Typed streakResult is authoritative.
            if (streakResult.streaks != null) {
                val start = projected.effectiveStatisticsStartDate()
                    ?: projected.computedEntries.getKnown().lastOrNull()?.date ?: from
                projected.streaks.recompute(projected, projected.computedEntries, minOf(start, compatibilityTo), compatibilityTo)
            }
        }
    }

    private fun detachedPresentation(): Habit = copyDetached(captured).also { projected ->
        if (captured.isNumerical) days.forEach { day ->
            val number = day.totalValue
            if (number != null) {
                check(number <= Int.MAX_VALUE) { "Effective observation exceeds legacy Int representation" }
                projected.computedEntries.add(day.manualEntry.copy(value = number.toInt()))
            }
        }
    }

    /** No UNKNOWN scalar is fed into a derived score/streak and then labelled exact. */
    private fun hasIncompleteAlgorithmInputs(end: LocalDate, includeRolling: Boolean): Boolean {
        var date = captured.effectiveStatisticsStartDate() ?: observedFrom ?: from
        while (date <= end) {
            val current = byDate[date]
            if (captured.isDateIncludedInStatistics(date) && current?.isSkipped != true) {
                if (current?.hasCompleteCoverage != true) return true
                val goal = captured.goalAt(date)
                for (offset in 0 until (if (includeRolling) goal.frequency.denominator else 0)) {
                    val inputDate = date.minus(offset)
                    if (captured.isDateIncludedInStatistics(inputDate)) {
                        val input = byDate[inputDate]
                        if (input?.isSkipped != true && input?.hasCompleteCoverage != true) return true
                    }
                }
                if (goal.targetType == NumericalHabitType.AT_MOST &&
                    compatibilityService.goalProgressOn(captured, date, days).status == HabitGoalStatus.UNKNOWN) return true
            }
            date = date.plus(1)
        }
        return false
    }
    private fun rollingWindowsRepresentable(habit: Habit, start: LocalDate, end: LocalDate): Boolean {
        var date = start
        while (date <= end) {
            var total = 0L
            for (offset in 0 until habit.goalAt(date).frequency.denominator) {
                val day = date.minus(offset)
                if (habit.isDateIncludedInStatistics(day)) total += habit.computedEntries.get(day).value.coerceAtLeast(0).toLong()
                if (total > Int.MAX_VALUE) return false
            }
            date = date.plus(1)
        }
        return true
    }

    private fun buildStreaks(start: LocalDate, end: LocalDate): List<Streak> {
        val result = mutableListOf<Streak>()
        var begin: LocalDate? = null
        var last: LocalDate? = null
        var date = start
        while (date <= end) {
            if (compatibilityCompletionOn(date).isCompleted) {
                if (begin == null) begin = date
                last = date
            } else if (begin != null) {
                result.add(Streak(begin, requireNotNull(last)))
                begin = null; last = null
            }
            date = date.plus(1)
        }
        if (begin != null) result.add(Streak(begin, requireNotNull(last)))
        return result.reversed().toList()
    }
}

class EffectiveHabitReadSnapshotProvider(private val repository: ContributionRepository) {
    /**
     * Domain discovery is one indexed batched Habit range fetch, never a Habit x day loop.
     * The second batch loads the union of history, rolling windows, and complete calendar periods.
     */
    fun prepare(
        habits: List<Habit>, from: LocalDate, to: LocalDate,
        datasetId: String, revision: Long, calendar: EffectiveReadCalendarContext,
    ): Map<HabitRef, EffectiveHabitReadSnapshot> {
        require(from <= to)
        if (habits.isEmpty()) return emptyMap()
        val frozen = habits.map(::copyDetached)
        val refs = frozen.map { HabitRef(requireNotNull(it.uuid)) }.toSet()
        require(refs.size == frozen.size) { "Duplicate Habit identities" }
        val contributions = repository.getByHabitsInRange(refs, Long.MIN_VALUE, Long.MAX_VALUE)
            .filter { it.isActive }.groupBy { it.targetHabit }
        val domains = frozen.associate { habit ->
            val ref = HabitRef(requireNotNull(habit.uuid))
            val dates = habit.originalEntries.getKnown().map { it.date } + contributions[ref].orEmpty().map {
                localDateAt(it.effectiveTimestamp, calendar.hourOffset, calendar.minuteOffset)
            }
            ref to dates.filter { habit.isDateIncludedInStatistics(it) }
        }
        val allKnown = domains.values.flatten() + frozen.mapNotNull { it.effectiveStatisticsStartDate() }
        val earliest = if (allKnown.isNotEmpty()) {
            if (from.year < 2000) allKnown.minOrNull()!! else minOf(allKnown.minOrNull()!!, from)
        } else {
            if (from.year < 2000) getToday() else from
        }
        val compatibilityTo = getToday().plus(30)
        val latest = (domains.values.flatten() + to + compatibilityTo).maxOrNull()!!
        // Complete week/month envelope also supplies periods at historical goal boundaries.
        val maximumWindow = frozen.maxOf { habit ->
            maxOf(habit.frequency.denominator, habit.goalHistory.maxOfOrNull { it.frequency.denominator } ?: 1)
        }
        val loadFrom = minOf(earliest.startOfMonth(), earliest.minus(maxOf(6, maximumWindow - 1)))
        val month = latest.startOfMonth()
        val loadTo = maxOf(month.plus(month.monthLength - 1), latest.plus(6))
        val service = EffectiveHabitValueService(repository, calendar.hourOffset, calendar.minuteOffset, calendar.firstWeekday)
        val compatibilityService = EffectiveHabitValueService(repository, calendar.hourOffset, calendar.minuteOffset,
            DayOfWeek.entries[getFirstWeekdayNumberAccordingToLocale() - 1])
        val values = service.daysForRange(frozen, loadFrom, loadTo)
        return frozen.associate { habit ->
            val ref = HabitRef(requireNotNull(habit.uuid))
            val raw = values.getValue(ref)
            val days = raw.map { it.copy(contributions = it.contributions.toList()) }.toList()
            ref to EffectiveHabitReadSnapshot(datasetId, revision, calendar, ref, loadFrom, loadTo,
                domains.getValue(ref).minOrNull(), domains.getValue(ref).maxOrNull(), to, compatibilityTo, habit, days, service, compatibilityService)
        }
    }
}

private fun copyDetached(habit: Habit): Habit = habit.copy(
    goalHistory = habit.goalHistory.toMutableList(),
    originalEntries = EntryList().also { list -> habit.originalEntries.getKnown().forEach(list::add) },
    computedEntries = EntryList().also { list -> habit.computedEntries.getKnown().forEach(list::add) },
    scores = ScoreList(), streaks = StreakList(),
)






/** Bounds only: this is not a cross-unit quantity aggregation or new metric. */
private fun legacyQuantitiesRepresentable(days: List<EffectiveHabitDay>): Boolean {
    var bound = 0L
    for (day in days) {
        val number = day.totalValue ?: continue
        if (number == Entry.SKIP.toLong() || number > Int.MAX_VALUE) return false
        bound += number
        if (bound > Int.MAX_VALUE) return false
    }
    return true
}
