/* SPDX-License-Identifier: GPL-3.0-or-later */

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
