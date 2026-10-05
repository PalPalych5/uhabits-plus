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
    fun from(tasks: List<UserTask>): List<TaskCalendarEvent> = tasks.flatMap { task ->
        buildList {
            task.plannedDate?.let { add(TaskCalendarEvent(task, it, TaskCalendarEventKind.PLAN)) }
            task.deadline?.let { timestamp ->
                val date = Calendar.getInstance().apply { timeInMillis = timestamp }
                add(TaskCalendarEvent(task, LocalDate(date.get(Calendar.YEAR), date.get(Calendar.MONTH) + 1, date.get(Calendar.DAY_OF_MONTH)), TaskCalendarEventKind.DEADLINE))
            }
        }
    }.sortedWith(compareBy<TaskCalendarEvent> { it.day.daysSince2000 }.thenBy { it.task.deadline ?: Long.MAX_VALUE })
}
