/*
 * Copyright (C) 2016-2026 Álinson Santos Xavier <git@axavier.org> and contributors
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

package org.isoron.uhabits.activities.tasks

import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.core.userTasks.UserTask
import org.isoron.uhabits.core.userTasks.due.TaskDue
import java.util.Calendar

enum class TaskCalendarEventKind { PLAN, DEADLINE }
data class TaskCalendarEvent(val task: UserTask, val day: LocalDate, val kind: TaskCalendarEventKind)

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
}
