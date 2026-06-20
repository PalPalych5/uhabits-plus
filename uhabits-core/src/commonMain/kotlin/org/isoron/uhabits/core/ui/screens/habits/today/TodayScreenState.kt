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
 * Loop Habit Tracker is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.isoron.uhabits.core.ui.screens.habits.today

import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.core.models.HabitType
import org.isoron.uhabits.core.models.DayTier
import org.isoron.uhabits.core.models.NumericalHabitType
import org.isoron.uhabits.core.models.PaletteColor

data class TodayScreenState(
    val date: LocalDate,
    val completedCount: Int,
    val totalCount: Int,
    val focusMinutes: Double,
    val remaining: List<TodayHabitItem>,
    val sections: List<TodaySectionState>,
    val minimum: TodayTierProgress,
    val normal: TodayTierProgress,
    val ideal: TodayTierProgress,
    val motivations: List<String> = emptyList()
)

data class TodayTierProgress(val completedCount: Int, val totalCount: Int)

data class TodaySectionState(
    val blockId: Long?,
    val blockName: String,
    val blockPosition: Int,
    val color: PaletteColor,
    val completedCount: Int,
    val totalCount: Int,
    val focusMinutes: Double,
    val items: List<TodayHabitItem>
)

data class TodayHabitItem(
    val habitId: Long?,
    val name: String,
    val color: PaletteColor,
    val habitType: HabitType,
    val targetType: NumericalHabitType,
    val status: TodayHabitStatus,
    val currentValue: Double?,
    val targetValue: Double?,
    val unit: String,
    val notes: String,
    val dayTier: DayTier,
    val isWeeklyQuota: Boolean = false,
    val weeklyProgressActual: Double? = null,
    val weeklyProgressTarget: Double? = null
) {
    val isCompleted: Boolean
        get() = status == TodayHabitStatus.COMPLETED
}

enum class TodayHabitStatus {
    COMPLETED,
    REMAINING,
    UNKNOWN,
    SKIPPED,
    EXCEEDED
}
