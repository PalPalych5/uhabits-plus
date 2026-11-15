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

package org.isoron.uhabits.core.ui.screens.habits.show.views

import org.isoron.platform.time.DayOfWeek
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.getToday
import org.isoron.uhabits.core.commands.CommandRunner
import org.isoron.uhabits.core.commands.CreateRepetitionCommand
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Entry.Companion.SKIP
import org.isoron.uhabits.core.models.Entry.Companion.YES_AUTO
import org.isoron.uhabits.core.models.Entry.Companion.YES_MANUAL
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitList
import org.isoron.uhabits.core.models.MissingDataPolicy
import org.isoron.uhabits.core.models.NumericalHabitType.AT_LEAST
import org.isoron.uhabits.core.models.NumericalHabitType.AT_MOST
import org.isoron.uhabits.core.models.PaletteColor
import org.isoron.uhabits.core.preferences.Preferences
import org.isoron.uhabits.core.ui.callbacks.CheckMarkDialogCallback
import org.isoron.uhabits.core.ui.callbacks.NumberPickerCallback
import org.isoron.uhabits.core.ui.views.HistoryChart
import org.isoron.uhabits.core.ui.views.HistoryChart.Square.DIMMED
import org.isoron.uhabits.core.ui.views.HistoryChart.Square.GREY
import org.isoron.uhabits.core.ui.views.HistoryChart.Square.HATCHED
import org.isoron.uhabits.core.ui.views.HistoryChart.Square.OFF
import org.isoron.uhabits.core.ui.views.HistoryChart.Square.ON
import org.isoron.uhabits.core.ui.views.OnDateClickedListener
import org.isoron.uhabits.core.ui.views.Theme

data class HistoryCardState(
    val color: PaletteColor,
    val firstWeekday: DayOfWeek,
    val series: List<HistoryChart.Square>,
    val defaultSquare: HistoryChart.Square,
    val notesIndicators: List<Boolean>,
    val theme: Theme,
    val today: LocalDate
)

class HistoryCardPresenter(
    val commandRunner: CommandRunner,
    val habit: Habit,
    val habitList: HabitList,
    val preferences: Preferences,
    val screen: Screen
) : OnDateClickedListener {

    override fun onDateLongPress(date: LocalDate) {
        screen.showFeedback()
        if (habit.isNumerical) {
            showNumberPopup(date)
        } else {
            showCheckmarkPopup(date)
        }
    }

    override fun onDateShortPress(date: LocalDate) {
        screen.showFeedback()
        if (habit.isNumerical) {
            showNumberPopup(date)
        } else {
            showCheckmarkPopup(date)
        }
    }

    private fun showCheckmarkPopup(date: LocalDate) {
        val entry = habit.computedEntries.get(date)
        screen.showCheckmarkPopup(
            entry.value,
            entry.notes,
            habit.color
        ) { newValue, newNotes ->
            commandRunner.run(
                CreateRepetitionCommand(
                    habitList,
                    habit,
                    date,
                    newValue,
                    newNotes
                )
            )
        }
    }

    private fun toggle(date: LocalDate) {
        val entry = habit.computedEntries.get(date)
        val nextValue = Entry.nextToggleValue(
            value = entry.value,
            isSkipEnabled = preferences.isSkipEnabled,
            areQuestionMarksEnabled = preferences.areQuestionMarksEnabled
        )
        commandRunner.run(
            CreateRepetitionCommand(
                habitList,
                habit,
                date,
                nextValue,
                entry.notes
            )
        )
    }

    private fun showNumberPopup(date: LocalDate) {
        val entry = if (habit.originalEntries.isLoaded) habit.originalEntries.get(date) else null
        val oldValue = entry?.value ?: Entry.UNKNOWN
        val seedNotes = entry?.notes ?: ""
        screen.showNumberPopup(
            value = if (oldValue != Entry.UNKNOWN) oldValue / 1000.0 else 0.0,
            notes = seedNotes,
            habit = habit,
            date = date,
        ) { newValue: Double, newNotes: String ->
            val thousands = if (oldValue == Entry.UNKNOWN && newValue == Entry.UNKNOWN.toDouble() / 1000.0) {
                Entry.UNKNOWN
            } else if (newValue == Entry.SKIP.toDouble() / 1000.0) {
                Entry.SKIP
            } else {
                Entry.toScaledValue(newValue)
            }
            val hourOffset = runCatching { preferences.midnightDelayHours }.getOrDefault(0)
            commandRunner.run(
                CreateRepetitionCommand(
                    habitList = habitList,
                    habit = habit,
                    date = date,
                    value = thousands,
                    notes = newNotes,
                    isTotalEdit = true,
                    hourOffset = hourOffset,
                )
            )
        }
    }

    fun onClickEditButton() {
        screen.showHistoryEditorDialog(this)
    }

    companion object {
        fun buildState(
            habit: Habit,
            firstWeekday: DayOfWeek,
            theme: Theme
        ): HistoryCardState {
            val today = getToday()
            val oldest = habit.effectiveStatisticsStartDate()
                ?: habit.computedEntries.getEarliestDate()
                ?: today
            val entries = habit.computedEntries.getByInterval(oldest, today)
            val series = if (habit.isNumerical) {
                entries.map {
                    val value = it.value
                    val goal = habit.goalAt(it.date)
                    when {
                        value == SKIP -> HATCHED
                        !habit.isDateIncludedInStatistics(it.date) -> OFF
                        goal.targetType == AT_MOST &&
                            habit.effectiveMissingDataPolicy() == MissingDataPolicy.DEFAULT_ZERO -> {
                            val actual = habit.periodActualOn(it.date)
                            when {
                                actual != null && actual > goal.targetValue -> GREY
                                habit.isCompletedOn(it.date, today) -> ON
                                else -> OFF
                            }
                        }
                        value == Entry.UNKNOWN -> OFF
                        (goal.targetType == AT_MOST) -> {
                            val denominator = goal.frequency.denominator
                            if (denominator == 7 || denominator == 30) {
                                val periodEnd = habit.periodEndFor(it.date, goal)
                                val actual = habit.periodActualOn(it.date)
                                when {
                                    actual != null && actual > goal.targetValue -> GREY
                                    !periodEnd.isOlderThan(today) -> OFF
                                    actual != null -> ON
                                    else -> OFF
                                }
                            } else {
                                if (value / 1000.0 <= goal.targetValue) ON else GREY
                            }
                        }
                        (goal.targetType == AT_LEAST) && (value / 1000.0 >= goal.targetValue) -> ON
                        else -> GREY
                    }
                }
            } else {
                entries.map {
                    when (it.value) {
                        YES_MANUAL -> ON
                        YES_AUTO -> DIMMED
                        SKIP -> HATCHED
                        else -> OFF
                    }
                }
            }
            val notesIndicators = entries.map {
                when (it.notes) {
                    "" -> false
                    else -> true
                }
            }

            return HistoryCardState(
                color = habit.color,
                firstWeekday = firstWeekday,
                today = today,
                theme = theme,
                series = series,
                defaultSquare = OFF,
                notesIndicators = notesIndicators
            )
        }
    }

    interface Screen {
        fun showHistoryEditorDialog(listener: OnDateClickedListener)
        fun showFeedback()
        fun showNumberPopup(
            value: Double,
            notes: String,
            callback: NumberPickerCallback
        )
        fun showNumberPopup(
            value: Double,
            notes: String,
            habit: Habit?,
            date: LocalDate?,
            callback: NumberPickerCallback
        ) {
            showNumberPopup(value, notes, callback)
        }
        fun showCheckmarkPopup(
            selectedValue: Int,
            notes: String,
            color: PaletteColor,
            callback: CheckMarkDialogCallback
        )
    }
}
