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
package org.isoron.uhabits.core.ui

import me.tatarka.inject.annotations.Inject
import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.core.AppScope
import org.isoron.uhabits.core.commands.AddNumericalEntryOpCommand
import org.isoron.uhabits.core.commands.ArchiveHabitsCommand
import org.isoron.uhabits.core.commands.ClearAllEntriesCommand
import org.isoron.uhabits.core.commands.ClearHabitEntriesCommand
import org.isoron.uhabits.core.commands.Command
import org.isoron.uhabits.core.commands.CommandRunner
import org.isoron.uhabits.core.commands.CreateRepetitionCommand
import org.isoron.uhabits.core.commands.DeleteHabitsCommand
import org.isoron.uhabits.core.commands.EditHabitCommand
import org.isoron.uhabits.core.commands.EditHabitGoalCommand
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.NumericalHabitType
import org.isoron.uhabits.core.models.projection.EffectiveHabitReadSnapshot
import org.isoron.uhabits.core.preferences.Preferences
import org.isoron.uhabits.core.tasks.Task
import org.isoron.uhabits.core.tasks.TaskRunner

@AppScope
@Inject
open class NotificationTray(
    private val taskRunner: TaskRunner,
    private val commandRunner: CommandRunner,
    private val preferences: Preferences,
    private val systemTray: SystemTray
) : CommandRunner.Listener, Preferences.Listener {
    private val active: MutableMap<Habit, NotificationData> = mutableMapOf()

    open var effectiveSnapshotProvider: ((Habit) -> EffectiveHabitReadSnapshot?)? = null

    private fun matches(h1: Habit, h2: Habit): Boolean {
        if (h1 === h2) return true
        if (h1.id != null && h2.id != null && h1.id == h2.id) return true
        if (!h1.uuid.isNullOrEmpty() && !h2.uuid.isNullOrEmpty() && h1.uuid == h2.uuid) return true
        return false
    }

    open fun isShowing(habit: Habit): Boolean = active.keys.any { matches(it, habit) }

    open fun cancel(habit: Habit) {
        val notificationId = getNotificationId(habit)
        systemTray.removeNotification(notificationId)
        val iterator = active.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (matches(entry.key, habit)) {
                iterator.remove()
            }
        }
    }

    open fun cancelAll() {
        for (habit in active.keys.toList()) {
            cancel(habit)
        }
    }

    override fun onCommandFinished(command: Command) {
        if (command is CreateRepetitionCommand) {
            val (_, habit) = command
            cancel(habit)
        }
        if (command is DeleteHabitsCommand) {
            val (_, deleted) = command
            for (habit in deleted) cancel(habit)
        }
        if (command is ClearHabitEntriesCommand) {
            habitListSnapshot().firstOrNull { it.id == command.habitId }?.let { cancel(it) }
        }
        if (command is ClearAllEntriesCommand) {
            cancelAll()
        }
        if (command is ArchiveHabitsCommand) {
            for (habit in command.selected) cancel(habit)
        }
        if (command is AddNumericalEntryOpCommand) {
            habitListSnapshot().firstOrNull { matches(it, command.habit) }?.let { reshow(it) }
        }
        if (command is EditHabitGoalCommand) {
            habitListSnapshot().firstOrNull { it.id == command.habitId || matches(it, command.modified) }?.let { reshow(it) }
        }
        if (command is EditHabitCommand) {
            habitListSnapshot().firstOrNull { it.id == command.habitId || matches(it, command.modified) }?.let { reshow(it) }
        }
    }

    override fun onNotificationsChanged() {
        reshowAll()
    }

    open fun onEffectiveRevisionChanged() {
        reshowAll()
    }

    open fun show(habit: Habit, date: LocalDate, reminderTime: Long) {
        val data = NotificationData(date, reminderTime)
        val existing = active.keys.firstOrNull { matches(it, habit) }
        if (existing != null && existing !== habit) {
            active.remove(existing)
        }
        active[habit] = data
        taskRunner.execute(ShowNotificationTask(habit, data))
    }

    open fun startListening() {
        commandRunner.addListener(this)
        preferences.addListener(this)
    }

    open fun stopListening() {
        commandRunner.removeListener(this)
        preferences.removeListener(this)
    }

    private fun getNotificationId(habit: Habit): Int {
        val id = habit.id ?: (habit.uuid?.hashCode()?.toLong()?.and(0x7FFFFFFF) ?: 0L)
        return (id % Int.MAX_VALUE).toInt()
    }

    private fun reshowAll() {
        for ((habit, data) in active.entries.toList()) {
            taskRunner.execute(ShowNotificationTask(habit, data))
        }
    }

    open fun reshow(habit: Habit) {
        val entry = active.entries.firstOrNull { matches(it.key, habit) }
        if (entry != null) {
            taskRunner.execute(ShowNotificationTask(entry.key, entry.value))
        }
    }

    interface SystemTray {
        fun removeNotification(notificationId: Int)
        fun showNotification(
            habit: Habit,
            notificationId: Int,
            date: LocalDate,
            reminderTime: Long
        )

        fun log(msg: String)
    }

    internal class NotificationData(val date: LocalDate, val reminderTime: Long)
    private inner class ShowNotificationTask(private val habit: Habit, data: NotificationData) :
        Task {
        var isCompleted = false
        private val date: LocalDate = data.date
        private val reminderTime: Long = data.reminderTime

        override suspend fun doInBackground() {
            val snapshot = effectiveSnapshotProvider?.invoke(habit)
            isCompleted = if (snapshot != null) {
                snapshot.compatibilityCompletionOn(date).isCompleted
            } else {
                habit.isCompletedOn(date)
            }
        }

        override fun onPostExecute() {
            systemTray.log("Showing notification for habit=" + habit.id)
            if (isCompleted && habit.targetType != NumericalHabitType.AT_MOST) {
                systemTray.log("Habit ${habit.id} already checked. Skipping.")
                cancel(habit)
                return
            }
            if (!habit.hasReminder()) {
                systemTray.log("Habit ${habit.id} does not have a reminder. Skipping.")
                cancel(habit)
                return
            }
            if (habit.isArchived) {
                systemTray.log("Habit ${habit.id} is archived. Skipping.")
                cancel(habit)
                return
            }
            if (!shouldShowReminderToday()) {
                systemTray.log("Habit ${habit.id} not supposed to run today. Skipping.")
                cancel(habit)
                return
            }
            systemTray.showNotification(
                habit,
                getNotificationId(habit),
                date,
                reminderTime
            )
        }

        private fun shouldShowReminderToday(): Boolean {
            if (!habit.hasReminder()) return false
            val reminder = habit.reminder
            val reminderDays = reminder!!.days.toArray()
            val weekday = (date.dayOfWeek.daysSinceSunday + 1) % 7
            return reminderDays[weekday]
        }
    }

    companion object {
        const val REMINDERS_CHANNEL_ID = "REMINDERS"
    }

    private fun habitListSnapshot(): List<Habit> = active.keys.toList()
}
