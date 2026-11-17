/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.activities.tasks

import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.userTasks.UserTask
import org.isoron.uhabits.core.userTasks.due.TaskDue
import java.util.Calendar

enum class TaskCalendarEventKind { PLAN, DEADLINE }

data class TaskCalendarEvent(
    val task: UserTask,
    val day: LocalDate,
    val kind: TaskCalendarEventKind
) {
    val hour: Int?
        get() = (task.due as? TaskDue.Timed)?.let {
            val c = Calendar.getInstance()
            c.timeInMillis = it.epochMillis
            c.get(Calendar.HOUR_OF_DAY)
        }

    val minute: Int?
        get() = (task.due as? TaskDue.Timed)?.let {
            val c = Calendar.getInstance()
            c.timeInMillis = it.epochMillis
            c.get(Calendar.MINUTE)
        }

    val isAllDay: Boolean get() = task.due is TaskDue.Day
    val isTimed: Boolean get() = task.due is TaskDue.Timed
}

data class DayCalendarAgenda(
    val day: LocalDate,
    val allDayTasks: List<UserTask>,
    val timedTasks: List<TaskCalendarEvent>,
    val allDayHabits: List<PlannedHabit>,
    val timedHabits: List<PlannedHabit>
) {
    val hasItems: Boolean get() = allDayTasks.isNotEmpty() || timedTasks.isNotEmpty() || allDayHabits.isNotEmpty() || timedHabits.isNotEmpty()
}

object TaskCalendarEvents {
    private val eventComparator = Comparator<TaskCalendarEvent> { a, b ->
        val dayCmp = a.day.daysSince2000.compareTo(b.day.daysSince2000)
        if (dayCmp != 0) {
            dayCmp
        } else {
            val aTime = (a.task.due as? TaskDue.Timed)?.epochMillis ?: Long.MAX_VALUE
            val bTime = (b.task.due as? TaskDue.Timed)?.epochMillis ?: Long.MAX_VALUE
            aTime.compareTo(bTime)
        }
    }

    fun from(tasks: List<UserTask>): List<TaskCalendarEvent> {
        if (tasks.isEmpty()) return emptyList()
        val result = ArrayList<TaskCalendarEvent>(tasks.size)
        var cal: Calendar? = null
        for (task in tasks) {
            when (val due = task.due) {
                is TaskDue.None -> { /* not on calendar */ }
                is TaskDue.Day -> {
                    result.add(TaskCalendarEvent(task, due.date.toLocalDate(), TaskCalendarEventKind.PLAN))
                }
                is TaskDue.Timed -> {
                    val c = cal ?: Calendar.getInstance().also { cal = it }
                    c.timeInMillis = due.epochMillis
                    val deadlineDate = LocalDate(
                        c.get(Calendar.YEAR),
                        c.get(Calendar.MONTH) + 1,
                        c.get(Calendar.DAY_OF_MONTH)
                    )
                    result.add(TaskCalendarEvent(task, deadlineDate, TaskCalendarEventKind.DEADLINE))
                }
            }
        }
        result.sortWith(eventComparator)
        return result
    }

    fun buildAgendaForDay(
        tasks: List<UserTask>,
        habits: List<Habit>,
        day: LocalDate
    ): DayCalendarAgenda {
        val events = from(tasks).filter { it.day == day }
        val allDayTasks = events.filter { it.isAllDay }.map { it.task }
        val timedTasks = events.filter { it.isTimed }

        val plannedHabits = PlannedHabitProjector.projectForDate(habits, day)
        val allDayHabits = plannedHabits.filter { it.isAllDay }
        val timedHabits = plannedHabits.filter { !it.isAllDay }

        return DayCalendarAgenda(
            day = day,
            allDayTasks = allDayTasks,
            timedTasks = timedTasks,
            allDayHabits = allDayHabits,
            timedHabits = timedHabits
        )
    }
}
