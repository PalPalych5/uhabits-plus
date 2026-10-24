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

import android.os.Bundle
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.userTasks.due.TaskDue

/**
 * In-memory representation of a task draft shared between
 * Quick Add and TaskDetailBottomSheet (Create mode).
 *
 * No SQLite records or sync mutations are created while editing this draft.
 */
data class TaskDraft(
    val title: String = "",
    val description: String = "",
    val due: TaskDue = TaskDue.None,
    val containerId: ContainerId? = null,
    val subtasks: List<DraftSubtask> = emptyList(),
    val tagIds: Set<String> = emptySet(),
    val important: Boolean? = null,
    val urgent: Boolean? = null,
) {
    data class DraftSubtask(
        val title: String,
        val isCompleted: Boolean = false,
    )

    fun toBundle(): Bundle = Bundle().apply {
        important?.let { putBoolean("draftImportant", it) }
        urgent?.let { putBoolean("draftUrgent", it) }
        putStringArrayList("draftTagIds", ArrayList(tagIds))
        putString("draftTitle", title)
        putString("draftDescription", description)
        putString("draftContainerId", containerId?.value)
        putBundle("draftDue", TaskDuePickerBottomSheet.encode(due))
        putStringArrayList("draftSubtaskTitles", ArrayList(subtasks.map { it.title }))
        putBooleanArray("draftSubtaskCompleted", subtasks.map { it.isCompleted }.toBooleanArray())
    }

    companion object {
        val EMPTY = TaskDraft()

        fun fromBundle(bundle: Bundle?): TaskDraft {
            if (bundle == null || bundle.isEmpty) return EMPTY
            val title = bundle.getString("draftTitle").orEmpty()
            val description = bundle.getString("draftDescription").orEmpty()
            val containerId = bundle.getString("draftContainerId")?.let(::ContainerId)
            val due = bundle.getBundle("draftDue")?.let { TaskDuePickerBottomSheet.readResult(it) } ?: TaskDue.None
            val titles = bundle.getStringArrayList("draftSubtaskTitles").orEmpty()
            val completed = bundle.getBooleanArray("draftSubtaskCompleted") ?: BooleanArray(titles.size)
            val subtasks = titles.mapIndexed { index, subtaskTitle ->
                DraftSubtask(subtaskTitle, completed.getOrElse(index) { false })
            }
            return TaskDraft(
                title = title,
                description = description,
                due = due,
                containerId = containerId,
                subtasks = subtasks,
                tagIds = bundle.getStringArrayList("draftTagIds").orEmpty().toSet(),
                important = if (bundle.containsKey("draftImportant")) bundle.getBoolean("draftImportant") else null,
                urgent = if (bundle.containsKey("draftUrgent")) bundle.getBoolean("draftUrgent") else null,
            )
        }
    }
}
