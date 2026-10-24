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
import org.isoron.uhabits.core.models.PaletteColor

/**
 * Informational projection of a planned habit repetition for a specific calendar date.
 * Habits and tasks remain distinct domain models; planned habits are never converted into UserTasks.
 */
data class PlannedHabit(
    val habitId: Long,
    val habitUuid: String?,
    val name: String,
    val color: PaletteColor,
    val date: LocalDate,
    val reminderHour: Int? = null,
    val reminderMinute: Int? = null,
    val isNumerical: Boolean = false,
    val targetValue: Double = 0.0,
    val unit: String = ""
) {
    val isAllDay: Boolean get() = reminderHour == null
}
