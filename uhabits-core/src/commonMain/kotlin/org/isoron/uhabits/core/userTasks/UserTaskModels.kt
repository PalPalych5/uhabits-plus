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
import org.isoron.uhabits.core.containers.ContainerId
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
    val plannedDate: LocalDate? = null,
    val deadline: Long? = null,
    val completedAt: Long? = null,
    val parentTaskId: TaskId? = null,
    val sortOrder: Int = 0,
    val createdAt: Long,
    val updatedAt: Long,
) {
    val isCompleted: Boolean get() = status == TaskStatus.COMPLETED

    /**
     * Hard overdue is strictly when a non-null deadline has passed and the task is incomplete.
     * Missed planned date does NOT constitute hard overdue.
     */
    fun isHardOverdue(nowMillis: Long): Boolean {
        if (isCompleted) return false
        val d = deadline ?: return false
        return d < nowMillis
    }

    /**
     * Missed plan is when planned date has passed, the task is incomplete, and it is NOT hard overdue.
     */
    fun isMissedPlan(today: LocalDate, nowMillis: Long): Boolean {
        if (isCompleted) return false
        if (isHardOverdue(nowMillis)) return false
        val p = plannedDate ?: return false
        return p < today
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

    // Keep the original toIntOrNull contract (including + signs and overflow),
    // while avoiding split's list allocation. LocalDate normalization is intentional.
    val y = str.substring(0, firstDash).toIntOrNull() ?: return null
    val m = str.substring(firstDash + 1, secondDash).toIntOrNull() ?: return null
    if (str.indexOf('-', secondDash + 1) >= 0) return null
    val d = str.substring(secondDash + 1).toIntOrNull() ?: return null
    return LocalDate(y, m, d)
}
