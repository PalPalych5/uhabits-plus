/* SPDX-License-Identifier: GPL-3.0-or-later */

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
