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

package org.isoron.uhabits.activities.common.dialogs

import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitList

sealed class HistoryEditorResolutionResult {
    data class Rebound(val habit: Habit, val habitChanged: Boolean) : HistoryEditorResolutionResult()
    data object HabitDeleted : HistoryEditorResolutionResult()
    data object SessionMismatched : HistoryEditorResolutionResult()
}

class HistoryEditorHabitBinding(
    private val habitList: HabitList
) {
    var habit: Habit? = null
        private set

    fun initialize(initialHabit: Habit) {
        habit = initialHabit
    }

    fun resolve(
        habitId: Long?,
        habitUuid: String?,
        isDatasetRebuilding: Boolean = false,
        currentSessionDatasetId: String? = null,
        initialDatasetId: String? = null
    ): HistoryEditorResolutionResult {
        if (isDatasetRebuilding || (initialDatasetId != null && currentSessionDatasetId != null && initialDatasetId != currentSessionDatasetId)) {
            return HistoryEditorResolutionResult.SessionMismatched
        }

        val freshHabit = habitId?.let { habitList.getById(it) }
            ?: habitUuid?.let { habitList.getByUUID(it) }
            ?: (habit?.id?.let { habitList.getById(it) } ?: habit?.uuid?.let { habitList.getByUUID(it) })

        if (freshHabit == null) {
            return HistoryEditorResolutionResult.HabitDeleted
        }

        val habitChanged = (freshHabit !== habit)
        habit = freshHabit
        return HistoryEditorResolutionResult.Rebound(freshHabit, habitChanged)
    }
}
