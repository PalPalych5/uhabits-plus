/*
 * Copyright (C) 2016-2025 Álinson Santos Xavier <git@axavier.org>
 *
 * This file is part of Loop Habit Tracker.
 *
 * Loop Habit Tracker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * Loop Habit Tracker is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.isoron.uhabits.core.ui.screens.habits.show

import org.isoron.uhabits.core.commands.CommandRunner
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitList
import org.isoron.uhabits.core.models.PaletteColor
import org.isoron.uhabits.core.models.projection.EffectiveHabitReadSnapshot
import org.isoron.uhabits.core.models.projection.EffectiveReadExactness
import org.isoron.uhabits.core.models.projection.GoalPeriodAchievement
import org.isoron.uhabits.core.models.projection.HabitGoalStatus
import org.isoron.uhabits.core.models.projection.HabitProjectionIntegrityException
import org.isoron.platform.time.getToday
import org.isoron.uhabits.core.ui.views.HistoryChart
import org.isoron.uhabits.core.preferences.Preferences
import org.isoron.uhabits.core.ui.screens.habits.show.views.BarCardPresenter
import org.isoron.uhabits.core.ui.screens.habits.show.views.BarCardState
import org.isoron.uhabits.core.ui.screens.habits.show.views.FrequencyCardPresenter
import org.isoron.uhabits.core.ui.screens.habits.show.views.FrequencyCardState
import org.isoron.uhabits.core.ui.screens.habits.show.views.HistoryCardPresenter
import org.isoron.uhabits.core.ui.screens.habits.show.views.HistoryCardState
import org.isoron.uhabits.core.ui.screens.habits.show.views.NotesCardPresenter
import org.isoron.uhabits.core.ui.screens.habits.show.views.NotesCardState
import org.isoron.uhabits.core.ui.screens.habits.show.views.OverviewCardPresenter
import org.isoron.uhabits.core.ui.screens.habits.show.views.OverviewCardState
import org.isoron.uhabits.core.ui.screens.habits.show.views.ScoreCardPresenter
import org.isoron.uhabits.core.ui.screens.habits.show.views.ScoreCardState
import org.isoron.uhabits.core.ui.screens.habits.show.views.StreakCardState
import org.isoron.uhabits.core.ui.screens.habits.show.views.StreakCartPresenter
import org.isoron.uhabits.core.ui.screens.habits.show.views.SubtitleCardPresenter
import org.isoron.uhabits.core.ui.screens.habits.show.views.SubtitleCardState
import org.isoron.uhabits.core.ui.screens.habits.show.views.TargetCardPresenter
import org.isoron.uhabits.core.ui.screens.habits.show.views.TargetCardState
import org.isoron.uhabits.core.ui.views.Theme

data class ShowHabitState(
    val title: String = "",
    val isNumerical: Boolean = false,
    val color: PaletteColor = PaletteColor(1),
    val subtitle: SubtitleCardState,
    val overview: OverviewCardState,
    val notes: NotesCardState,
    val target: TargetCardState,
    val streaks: StreakCardState,
    val scores: ScoreCardState,
    val frequency: FrequencyCardState,
    val history: HistoryCardState,
    val bar: BarCardState,
    val theme: Theme,
    val scoreUnavailable: Boolean = false,
    val streakUnavailable: Boolean = false,
    val quantityUnavailable: Boolean = false,
    val hasAutomaticQuantity: Boolean = false,
    val hasIncompleteCoverage: Boolean = false,
    val goalAchievement: GoalPeriodAchievement? = null,
    val goalAchievementUnavailable: Boolean = false
)

class ShowHabitPresenter(
    val habit: Habit,
    val habitList: HabitList,
    val preferences: Preferences,
    val screen: Screen,
    val commandRunner: CommandRunner
) {
    val historyCardPresenter = HistoryCardPresenter(
        commandRunner = commandRunner,
        habit = habit,
        habitList = habitList,
        preferences = preferences,
        screen = screen
    )

    val barCardPresenter = BarCardPresenter(
        preferences = preferences,
        screen = screen
    )

    val scoreCardPresenter = ScoreCardPresenter(
        preferences = preferences,
        screen = screen
    )

    companion object {
        fun buildState(
            habit: Habit,
            preferences: Preferences,
            theme: Theme,
            snapshot: EffectiveHabitReadSnapshot? = null
        ): ShowHabitState {
            // Legacy quantity cards aggregate Int entries. Check their whole read domain rather
            // than clamping individual days or letting a monthly/weekday bucket overflow.
            val quantityUnavailable = snapshot?.hasContributions == true && (
                !snapshot.canUseLegacyEntryAdapter ||
                    snapshot.days.filter { it.includedInStatistics && !it.isSkipped }
                        .sumOf { it.totalValue ?: 0L } > Int.MAX_VALUE
                )
            val readHabit = if (quantityUnavailable) habit else snapshot?.presentationHabit() ?: habit
            val scoreUnavailable = snapshot != null && snapshot.scoreResult.scores == null
            val streakUnavailable = snapshot != null && snapshot.streakResult.streaks == null
            val base = ShowHabitState(
                title = habit.name,
                color = habit.color,
                isNumerical = habit.isNumerical,
                theme = theme,
                subtitle = SubtitleCardPresenter.buildState(
                    habit = habit,
                    theme = theme
                ),
                overview = OverviewCardPresenter.buildState(
                    habit = readHabit,
                    theme = theme
                ),
                notes = NotesCardPresenter.buildState(
                    habit = habit
                ),
                target = TargetCardPresenter.buildState(
                    habit = readHabit,
                    firstWeekday = preferences.firstWeekdayInt,
                    theme = theme
                ),
                streaks = StreakCartPresenter.buildState(
                    habit = readHabit,
                    theme = theme
                ),
                scores = ScoreCardPresenter.buildState(
                    spinnerPosition = preferences.scoreCardSpinnerPosition,
                    habit = readHabit,
                    firstWeekday = preferences.firstWeekdayInt,
                    theme = theme
                ),
                frequency = FrequencyCardPresenter.buildState(
                    habit = readHabit,
                    firstWeekday = preferences.firstWeekday,
                    theme = theme
                ),
                history = HistoryCardPresenter.buildState(
                    habit = readHabit,
                    firstWeekday = preferences.firstWeekday,
                    theme = theme
                ),
                bar = BarCardPresenter.buildState(
                    habit = readHabit,
                    firstWeekday = preferences.firstWeekdayInt,
                    boolSpinnerPosition = preferences.barCardBoolSpinnerPosition,
                    numericalSpinnerPosition = preferences.barCardNumericalSpinnerPosition,
                    theme = theme
                )
            )
            if (snapshot == null) return base
            val today = getToday()
            val oldest = habit.effectiveStatisticsStartDate() ?: snapshot.observedFrom ?: today
            val historyDates = readHabit.computedEntries.getByInterval(oldest, today).map { it.date }
            val history = if (habit.isNumerical && snapshot.hasContributions) base.history.copy(
                series = historyDates.map { date ->
                    when (snapshot.compatibilityCompletionOn(date).status) {
                        HabitGoalStatus.SATISFIED -> HistoryChart.Square.ON
                        HabitGoalStatus.NOT_SATISFIED -> HistoryChart.Square.GREY
                        HabitGoalStatus.SKIPPED -> HistoryChart.Square.HATCHED
                        HabitGoalStatus.UNKNOWN -> if (snapshot.day(date).totalValue != null) HistoryChart.Square.GREY else HistoryChart.Square.OFF
                        else -> HistoryChart.Square.OFF
                    }
                },
                notesIndicators = historyDates.map { snapshot.day(it).manualEntry.notes.isNotEmpty() }
            ) else base.history
            val streaks = snapshot.streakResult.streaks
            val latest = streaks?.firstOrNull()
            var goalAchievementUnavailable = false
            val goalAchievement = try {
                snapshot.goalAchievementOn(today)
            } catch (_: HabitProjectionIntegrityException) {
                // An incompatible historical unit boundary prevents calendar aggregation.
                // It must not erase otherwise unchanged legacy score/history representations.
                goalAchievementUnavailable = true
                null
            }
            val effectiveFrequency = hashMapOf<org.isoron.platform.time.LocalDate, Array<Int>>()
            if (habit.isNumerical && snapshot.hasContributions && !quantityUnavailable) {
                snapshot.days.filter { it.includedInStatistics && !it.isSkipped && it.totalValue != null && it.manualEntry.date <= today }
                    .forEach { day ->
                        val date = day.manualEntry.date
                        val bucket = effectiveFrequency.getOrPut(date.startOfMonth()) { Array(7) { 0 } }
                        val weekday = (date.dayOfWeek.daysSinceSunday + 1) % 7
                        bucket[weekday] += requireNotNull(day.totalValue).toInt()
                    }
            }
            return base.copy(
                scoreUnavailable = scoreUnavailable,
                streakUnavailable = streakUnavailable,
                quantityUnavailable = quantityUnavailable,
                hasAutomaticQuantity = snapshot.hasContributions,
                hasIncompleteCoverage = snapshot.hasContributions && snapshot.days.any {
                    it.manualEntry.date <= today && it.manualEntry.date >= oldest &&
                        it.includedInStatistics && !it.isSkipped && !it.hasCompleteCoverage
                },
                goalAchievement = goalAchievement,
                goalAchievementUnavailable = goalAchievementUnavailable,
                history = history,
                scores = if (scoreUnavailable) base.scores.copy(scores = emptyList()) else base.scores,
                streaks = if (snapshot.streakResult.exactness == EffectiveReadExactness.LEGACY_COMPATIBILITY) base.streaks else base.streaks.copy(
                    latestStreak = latest,
                    bestStreaks = streaks.orEmpty().filterNot { it == latest }.sortedWith { a, b -> b.compareLonger(a) }.take(10)
                ),
                frequency = if (habit.isNumerical && snapshot.hasContributions && !quantityUnavailable) base.frequency.copy(
                    frequency = effectiveFrequency
                ) else base.frequency
            )
        }
    }

    interface Screen :
        BarCardPresenter.Screen,
        ScoreCardPresenter.Screen,
        HistoryCardPresenter.Screen
}
