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

class UserTaskServiceImpl(
    private val repository: UserTaskRepository,
    private val clock: () -> Long,
    private val idGenerator: () -> String,
    private val syncManager: org.isoron.uhabits.core.sync.SyncManager? = null,
) : UserTaskService {

    override fun createTask(
        title: String,
        description: String,
        containerId: ContainerId?,
        plannedDate: LocalDate?,
        deadline: Long?,
        parentTaskId: TaskId?,
        explicitId: TaskId?,
        sortOrder: Int?,
    ): UserTask {
        require(title.isNotBlank()) { "Task title cannot be blank" }

        if (parentTaskId != null) {
            val parent = repository.getById(parentTaskId)
                ?: throw IllegalArgumentException("Parent task $parentTaskId does not exist")
        }

        val now = clock()
        val taskId = explicitId ?: TaskId(idGenerator())

        val effectiveSortOrder = sortOrder ?: ((repository.getMaxSortOrder(parentTaskId) ?: -1) + 1)

        val task = UserTask(
            id = taskId,
            title = title.trim(),
            description = description.trim(),
            containerId = containerId,
            status = TaskStatus.PENDING,
            plannedDate = plannedDate,
            deadline = deadline,
            completedAt = null,
            parentTaskId = parentTaskId,
            sortOrder = effectiveSortOrder,
            createdAt = now,
            updatedAt = now,
        )

        repository.save(task)
        syncManager?.enqueueUserTask(task, "upsert")
        return task
    }

    override fun updateTask(
        id: TaskId,
        title: String?,
        description: String?,
        containerId: ContainerId?,
        clearContainer: Boolean,
        plannedDate: LocalDate?,
        clearPlannedDate: Boolean,
        deadline: Long?,
        clearDeadline: Boolean,
        parentTaskId: TaskId?,
        clearParentTask: Boolean,
        sortOrder: Int?,
    ): UserTask {
        val existing = repository.getById(id)
            ?: throw IllegalArgumentException("Task $id not found")

        val newParentId = when {
            clearParentTask -> null
            parentTaskId != null -> {
                validateNoCycle(id, parentTaskId)
                parentTaskId
            }
            else -> existing.parentTaskId
        }

        val newContainerId = when {
            clearContainer -> null
            containerId != null -> containerId
            else -> existing.containerId
        }

        val newPlannedDate = when {
            clearPlannedDate -> null
            plannedDate != null -> plannedDate
            else -> existing.plannedDate
        }

        val newDeadline = when {
            clearDeadline -> null
            deadline != null -> deadline
            else -> existing.deadline
        }

        val newTitle = title?.let {
            require(it.isNotBlank()) { "Task title cannot be blank" }
            it.trim()
        } ?: existing.title

        val newDescription = description?.trim() ?: existing.description
        val newSortOrder = sortOrder ?: existing.sortOrder

        val updated = existing.copy(
            title = newTitle,
            description = newDescription,
            containerId = newContainerId,
            plannedDate = newPlannedDate,
            deadline = newDeadline,
            parentTaskId = newParentId,
            sortOrder = newSortOrder,
            updatedAt = clock(),
        )

        repository.save(updated)
        syncManager?.enqueueUserTask(updated, "upsert")
        return updated
    }

    override fun toggleComplete(id: TaskId): UserTask {
        val existing = repository.getById(id)
            ?: throw IllegalArgumentException("Task $id not found")

        val now = clock()
        val (newStatus, newCompletedAt) = if (existing.status == TaskStatus.PENDING) {
            TaskStatus.COMPLETED to now
        } else {
            TaskStatus.PENDING to null
        }

        val updated = existing.copy(
            status = newStatus,
            completedAt = newCompletedAt,
            updatedAt = now,
        )
        repository.save(updated)
        syncManager?.enqueueUserTask(updated, "upsert")
        return updated
    }

    override fun setCompleted(id: TaskId, completed: Boolean): UserTask {
        val existing = repository.getById(id)
            ?: throw IllegalArgumentException("Task $id not found")

        val now = clock()
        val targetStatus = if (completed) TaskStatus.COMPLETED else TaskStatus.PENDING
        if (existing.status == targetStatus) return existing

        val updated = existing.copy(
            status = targetStatus,
            completedAt = if (completed) now else null,
            updatedAt = now,
        )
        repository.save(updated)
        syncManager?.enqueueUserTask(updated, "upsert")
        return updated
    }

    override fun deleteTask(id: TaskId, cascadeSubtasks: Boolean) {
        if (cascadeSubtasks) {
            val subtasks = repository.getSubtasks(id)
            for (sub in subtasks) {
                deleteTask(sub.id, cascadeSubtasks = true)
            }
        }
        val now = clock()
        repository.delete(id)
        syncManager?.enqueueUserTaskDelete(id.value, now)
    }

    override fun reorderTasks(orderedIds: List<TaskId>) {
        val now = clock()
        val toUpdate = mutableListOf<UserTask>()
        val pendingById = mutableMapOf<TaskId, UserTask>()
        orderedIds.forEachIndexed { index, taskId ->
            val task = pendingById[taskId] ?: repository.getById(taskId)
            if (task != null && task.sortOrder != index) {
                val updated = task.copy(sortOrder = index, updatedAt = now)
                toUpdate.add(updated)
                pendingById[taskId] = updated
            }
        }
        if (toUpdate.isNotEmpty()) {
            repository.saveAll(toUpdate)
            if (syncManager != null) {
                for (updated in toUpdate) {
                    syncManager.enqueueUserTask(updated, "upsert")
                }
            }
        }
    }

    private fun validateNoCycle(taskId: TaskId, targetParentId: TaskId) {
        if (taskId == targetParentId) {
            throw IllegalArgumentException("Cannot make task $taskId a subtask of itself")
        }

        var currentParent: TaskId? = targetParentId
        val visited = mutableSetOf(taskId)

        while (currentParent != null) {
            if (currentParent == taskId || !visited.add(currentParent)) {
                throw IllegalArgumentException("Cycle detected: setting parent of $taskId to $targetParentId would form a loop")
            }
            val parentTask = repository.getById(currentParent)
                ?: throw IllegalArgumentException("Parent task $currentParent does not exist")
            currentParent = parentTask.parentTaskId
        }
    }
}
