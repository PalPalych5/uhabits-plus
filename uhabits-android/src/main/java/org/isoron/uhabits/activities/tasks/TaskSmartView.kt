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

enum class TaskSmartView(val displayName: String) {
    TODAY("Сегодня"),
    INBOX("Входящие"),
    UPCOMING("Предстоящие"),
    ALL("Все задачи"),
    COMPLETED("Завершённые");

    fun matches(task: UserTask, today: LocalDate, nowMillis: Long): Boolean = when (this) {
        TODAY -> !task.isCompleted && (
            task.plannedDate == today ||
                task.isMissedPlan(today, nowMillis) ||
                task.isHardOverdue(nowMillis)
            )
        // Dates describe planning, while Inbox describes organization.
        INBOX -> !task.isCompleted && task.containerId == null
        UPCOMING -> !task.isCompleted && (
            (task.plannedDate?.let { it > today } == true) ||
                (task.deadline?.let { it > nowMillis } == true &&
                    (task.plannedDate?.let { it >= today } != false))
            )
        ALL -> !task.isCompleted
        COMPLETED -> task.isCompleted
    }
}
