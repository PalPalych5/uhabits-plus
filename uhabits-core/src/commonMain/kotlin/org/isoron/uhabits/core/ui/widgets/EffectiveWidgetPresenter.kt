package org.isoron.uhabits.core.ui.widgets

import org.isoron.platform.time.*
import org.isoron.uhabits.core.models.*
import org.isoron.uhabits.core.models.projection.*
import org.isoron.uhabits.core.ui.screens.habits.show.views.*
import org.isoron.uhabits.core.ui.views.HistoryChart
import org.isoron.uhabits.core.ui.views.Theme

data class EffectiveWidgetCheckmarkState(
    val quantity: Long?,
    val manualQuantity: Long?,
    val focusQuantity: Long,
    val completeCoverage: Boolean,
    val skipped: Boolean,
    val compatibilityCompletion: HabitGoalStatus,
    val score: Double?,
    val exactness: EffectiveReadExactness,
)

data class EffectiveWidgetTargetState(val progress: EffectiveHabitGoal?, val available: Boolean)

/** Read-only widget adapters. Null chart states mean unavailable, never manual fallback. */
object EffectiveWidgetPresenter {
    fun checkmark(habit: Habit, snapshot: EffectiveHabitReadSnapshot, today: LocalDate = getToday()): EffectiveWidgetCheckmarkState {
        val day = snapshot.day(today)
        return EffectiveWidgetCheckmarkState(
            if (habit.isNumerical) day.totalValue else habit.computedEntries.get(today).value.toLong(),
            day.manualValue, day.contributionValue, day.hasCompleteCoverage, day.isSkipped,
            snapshot.compatibilityCompletionOn(today).status,
            snapshot.scoreResult.on(today), snapshot.scoreResult.exactness,
        )
    }

    fun target(snapshot: EffectiveHabitReadSnapshot, today: LocalDate = getToday()): EffectiveWidgetTargetState = try {
        EffectiveWidgetTargetState(snapshot.goalAchievementOn(today).progress, true)
    } catch (_: HabitProjectionIntegrityException) {
        EffectiveWidgetTargetState(null, false)
    }

    fun history(habit: Habit, snapshot: EffectiveHabitReadSnapshot, theme: Theme): HistoryCardState {
        if (!snapshot.hasContributions) return HistoryCardPresenter.buildState(habit, snapshot.calendar.firstWeekday, theme)
        val today = getToday()
        val oldest = habit.effectiveStatisticsStartDate() ?: snapshot.observedFrom ?: today
        val dates = mutableListOf<LocalDate>()
        var date = today
        while (date >= oldest) { dates.add(date); date = date.minus(1) }
        val squares = dates.map { current ->
            val day = snapshot.dayOrNull(current)
            when {
                day == null || !day.includedInStatistics -> HistoryChart.Square.OFF
                day.isSkipped -> HistoryChart.Square.HATCHED
                else -> when (try { snapshot.goalAchievementOn(current).status } catch (_: HabitProjectionIntegrityException) { HabitGoalStatus.UNKNOWN }) {
                    HabitGoalStatus.SATISFIED -> HistoryChart.Square.ON
                    HabitGoalStatus.NOT_SATISFIED -> HistoryChart.Square.GREY
                    HabitGoalStatus.SKIPPED -> HistoryChart.Square.HATCHED
                    else -> if (day.totalValue != null) HistoryChart.Square.GREY else HistoryChart.Square.OFF
                }
            }
        }
        return HistoryCardState(habit.color, snapshot.calendar.firstWeekday, squares, HistoryChart.Square.OFF,
            dates.map { snapshot.dayOrNull(it)?.manualEntry?.notes?.isNotEmpty() == true }, theme, today)
    }

    fun score(habit: Habit, snapshot: EffectiveHabitReadSnapshot, firstWeekday: Int, spinnerPosition: Int, theme: Theme): ScoreCardState? {
        if (!snapshot.hasContributions) return ScoreCardPresenter.buildState(habit, firstWeekday, spinnerPosition, theme)
        val scores = snapshot.scoreResult.scores ?: return null
        val size = ScoreCardPresenter.BUCKET_SIZES[spinnerPosition]
        val start = habit.effectiveStatisticsStartDate() ?: snapshot.observedFrom ?: getToday()
        val field = ScoreCardPresenter.getTruncateField(size)
        val grouped = scores.filter { it.date >= start && it.date <= getToday() }.groupBy {
            when (field) {
                TruncateField.WEEK_NUMBER -> it.date.startOfWeek(DayOfWeek.entries[firstWeekday - 1])
                TruncateField.MONTH -> it.date.startOfMonth()
                TruncateField.QUARTER -> it.date.startOfQuarter()
                TruncateField.YEAR -> it.date.startOfYear()
                else -> it.date
            }
        }.map { (date, values) -> Score(date, values.map { it.value }.average()) }.sortedByDescending { it.date }
        return ScoreCardState(grouped, size, spinnerPosition, habit.color, theme)
    }

    fun streak(habit: Habit, snapshot: EffectiveHabitReadSnapshot, theme: Theme): StreakCardState? {
        if (!snapshot.hasContributions) return StreakCartPresenter.buildState(habit, theme)
        val streaks = snapshot.streakResult.streaks ?: return null
        val latest = streaks.maxByOrNull { it.end }
        return StreakCardState(habit.color, latest, streaks.sortedByDescending { it.length }.filterNot { it == latest }.take(10), theme)
    }

    /** Numerical x1000 quantities by weekday/month; Boolean manual check counts retain legacy meaning. */
    fun frequency(habit: Habit, snapshot: EffectiveHabitReadSnapshot, firstWeekday: DayOfWeek, theme: Theme): FrequencyCardState? {
        if (!snapshot.hasContributions) return FrequencyCardPresenter.buildState(habit, firstWeekday, theme)
        if (!snapshot.canUseLegacyEntryAdapter) return null
        val start = snapshot.observedFrom ?: return FrequencyCardState(habit.color, firstWeekday, hashMapOf(), theme, habit.isNumerical)
        val end = minOf(snapshot.observedTo ?: start, getToday())
        val days = snapshot.days.filter { it.manualEntry.date >= start && it.manualEntry.date <= end && it.includedInStatistics && !it.isSkipped }
        if (days.any { !it.hasCompleteCoverage }) return null
        val map = hashMapOf<LocalDate, Array<Int>>()
        for (day in days) {
            val bucket = map.getOrPut(day.manualEntry.date.startOfMonth()) { Array(7) { 0 } }
            val weekday = (day.manualEntry.date.dayOfWeek.daysSinceSunday + 1) % 7
            val quantity = if (habit.isNumerical) day.totalValue ?: continue else if (day.manualEntry.value == Entry.YES_MANUAL) 1L else 0L
            val value = bucket[weekday].toLong() + quantity
            if (value !in 0..Int.MAX_VALUE.toLong()) return null
            bucket[weekday] = value.toInt()
        }
        return FrequencyCardState(habit.color, firstWeekday, map, theme, habit.isNumerical)
    }
}
