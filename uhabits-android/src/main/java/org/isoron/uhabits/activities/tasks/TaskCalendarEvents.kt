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
import java.util.Calendar

enum class TaskCalendarEventKind { PLAN, DEADLINE }
data class TaskCalendarEvent(val task: UserTask, val day: LocalDate, val kind: TaskCalendarEventKind)

/** Two independent events; a task with both dates is deliberately represented twice. */
object TaskCalendarEvents {
    private val eventComparator = Comparator<TaskCalendarEvent> { a, b ->
        val dayCmp = a.day.daysSince2000.compareTo(b.day.daysSince2000)
        if (dayCmp != 0) {
            dayCmp
        } else {
            val aDeadline = a.task.deadline ?: Long.MAX_VALUE
            val bDeadline = b.task.deadline ?: Long.MAX_VALUE
            aDeadline.compareTo(bDeadline)
        }
    }

    fun from(tasks: List<UserTask>): List<TaskCalendarEvent> {
        if (tasks.isEmpty()) return emptyList()
        val result = ArrayList<TaskCalendarEvent>(tasks.size)
        var cal: Calendar? = null
        for (task in tasks) {
            val plan = task.plannedDate
            if (plan != null) {
                result.add(TaskCalendarEvent(task, plan, TaskCalendarEventKind.PLAN))
            }
            val deadline = task.deadline
            if (deadline != null) {
                val c = cal ?: Calendar.getInstance().also { cal = it }
                c.timeInMillis = deadline
                val deadlineDate = LocalDate(
                    c.get(Calendar.YEAR),
                    c.get(Calendar.MONTH) + 1,
                    c.get(Calendar.DAY_OF_MONTH)
                )
                result.add(TaskCalendarEvent(task, deadlineDate, TaskCalendarEventKind.DEADLINE))
            }
        }
        result.sortWith(eventComparator)
        return result
    }
}
