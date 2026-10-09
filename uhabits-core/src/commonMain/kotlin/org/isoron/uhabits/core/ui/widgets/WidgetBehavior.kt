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
package org.isoron.uhabits.core.ui.widgets

import me.tatarka.inject.annotations.Inject
import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.core.commands.CommandRunner
import org.isoron.uhabits.core.commands.CreateRepetitionCommand
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Entry.Companion.nextToggleValue
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitList
import org.isoron.uhabits.core.preferences.Preferences
import org.isoron.uhabits.core.ui.NotificationTray

@Inject
class WidgetBehavior(
    private val habitList: HabitList,
    private val commandRunner: CommandRunner,
    private val notificationTray: NotificationTray,
    private val preferences: Preferences
) {
    fun onAddRepetition(habit: Habit, date: LocalDate) {
        notificationTray.cancel(habit)
        val entry = habit.originalEntries.get(date)
        setValue(habit, date, Entry.YES_MANUAL, entry.notes)
    }

    fun onRemoveRepetition(habit: Habit, date: LocalDate) {
        notificationTray.cancel(habit)
        val entry = habit.originalEntries.get(date)
        setValue(habit, date, Entry.NO, entry.notes)
    }

    fun onToggleRepetition(habit: Habit, date: LocalDate) {
        val entry = habit.originalEntries.get(date)
        val currentValue = entry.value
        val newValue = nextToggleValue(
            value = currentValue,
            isSkipEnabled = preferences.isSkipEnabled,
            areQuestionMarksEnabled = preferences.areQuestionMarksEnabled
        )
        setValue(habit, date, newValue, entry.notes)
        notificationTray.cancel(habit)
    }

    fun onIncrement(habit: Habit, date: LocalDate, amount: Int) {
        adjustManualQuantity(habit, date, amount, increment = true)
    }

    fun onDecrement(habit: Habit, date: LocalDate, amount: Int) {
        adjustManualQuantity(habit, date, amount, increment = false)
    }

    private fun adjustManualQuantity(habit: Habit, date: LocalDate, amount: Int, increment: Boolean) {
        require(habit.isNumerical) { "Quantity adjustments require a numerical Habit" }
        require(amount >= 0) { "Adjustment amount must be nonnegative" }
        val entry = habit.originalEntries.get(date)
        // Sentinels describe missing/excluded observations, never a quantity to increment.
        val currentValue = when (entry.value) {
            Entry.UNKNOWN, Entry.SKIP -> 0L
            else -> entry.value.toLong().also { require(it >= 0) }
        }
        val newValue = if (increment) currentValue + amount else (currentValue - amount).coerceAtLeast(0)
        require(newValue <= Int.MAX_VALUE && newValue != Entry.SKIP.toLong()) {
            "Manual quantity cannot be represented without a sentinel collision"
        }
        setValue(habit, date, newValue.toInt(), entry.notes)
        notificationTray.cancel(habit)
    }

    fun setValue(habit: Habit, date: LocalDate, newValue: Int, notes: String) {
        commandRunner.run(
            CreateRepetitionCommand(habitList, habit, date, newValue, notes)
        )
    }
}
