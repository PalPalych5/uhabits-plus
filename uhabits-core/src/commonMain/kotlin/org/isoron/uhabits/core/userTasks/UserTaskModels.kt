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

package org.isoron.uhabits.core.userTasks

import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.localDateAt
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.userTasks.due.TaskDue
import org.isoron.uhabits.core.userTasks.due.TaskDueDate
import kotlin.jvm.JvmInline

@JvmInline
value class TaskId(val value: String) {
    override fun toString(): String = value
}

enum class TaskStatus {
    PENDING,
    COMPLETED
}

data class UserTask(
    val id: TaskId,
    val title: String,
    val description: String = "",
    val containerId: ContainerId? = null,
    val status: TaskStatus = TaskStatus.PENDING,
    val due: TaskDue = TaskDue.None,
    val legacyPlanDate: String? = null,
    val completedAt: Long? = null,
    val parentTaskId: TaskId? = null,
    val sortOrder: Int = 0,
    val createdAt: Long,
    val updatedAt: Long,
) {
    val isCompleted: Boolean get() = status == TaskStatus.COMPLETED
    val hasPendingLegacyPlan: Boolean get() = due is TaskDue.None && !legacyPlanDate.isNullOrBlank()

    fun isOverdue(today: LocalDate, nowMillis: Long): Boolean {
        if (isCompleted) return false
        return when (due) {
            is TaskDue.None -> false
            is TaskDue.Day -> due.date.toLocalDate() < today
            is TaskDue.Timed -> due.epochMillis < nowMillis
        }
    }

    fun isDueToday(today: LocalDate, nowMillis: Long): Boolean {
        if (isCompleted) return false
        return when (due) {
            is TaskDue.None -> false
            is TaskDue.Day -> due.date.toLocalDate() == today
            is TaskDue.Timed -> !isOverdue(today, nowMillis) && localDateAt(due.epochMillis) == today
        }
    }

    fun isDueUpcoming(today: LocalDate, nowMillis: Long): Boolean {
        if (isCompleted) return false
        return when (due) {
            is TaskDue.None -> false
            is TaskDue.Day -> due.date.toLocalDate() > today
            is TaskDue.Timed -> due.epochMillis > nowMillis && localDateAt(due.epochMillis) > today
        }
    }
}

fun LocalDate.toIsoDateString(): String {
    val y = year.toString().padStart(4, '0')
    val m = month.toString().padStart(2, '0')
    val d = day.toString().padStart(2, '0')
    return "$y-$m-$d"
}

fun parseIsoDateString(str: String?): LocalDate? {
    if (str.isNullOrBlank()) return null
    val firstDash = str.indexOf('-')
    if (firstDash <= 0) return null
    val secondDash = str.indexOf('-', firstDash + 1)
    if (secondDash <= firstDash + 1 || secondDash >= str.length - 1) return null

    val y = str.substring(0, firstDash).toIntOrNull() ?: return null
    val m = str.substring(firstDash + 1, secondDash).toIntOrNull() ?: return null
    if (str.indexOf('-', secondDash + 1) >= 0) return null
    val d = str.substring(secondDash + 1).toIntOrNull() ?: return null
    return LocalDate(y, m, d)
}
