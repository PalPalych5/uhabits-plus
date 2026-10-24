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

import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.userTasks.due.TaskDue

class InvalidLegacyPlanException(cause: IllegalArgumentException) :
    IllegalArgumentException("Legacy plan cannot be converted to a valid Due date", cause)

class UserTaskServiceImpl(
    private val repository: UserTaskRepository,
    private val clock: () -> Long = { 0L },
    private val idGenerator: () -> String = { "" },
    private val syncManager: org.isoron.uhabits.core.sync.SyncManager? = null,
) : UserTaskService {

    override fun createTask(
        title: String,
        description: String,
        containerId: ContainerId?,
        due: TaskDue,
        parentTaskId: TaskId?,
        explicitId: TaskId?,
        sortOrder: Int?,
    ): UserTask {
        require(title.isNotBlank()) { "Task title cannot be blank" }

        if (parentTaskId != null) {
            repository.getById(parentTaskId)
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
            due = due,
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
        due: TaskDue?,
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

        val newDue = due ?: existing.due

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
            due = newDue,
            legacyPlanReviewPending = if (due != null) false else existing.legacyPlanReviewPending,
            parentTaskId = newParentId,
            sortOrder = newSortOrder,
            updatedAt = maxOf(clock(), existing.updatedAt + 1L),
        )

        repository.save(updated)
        syncManager?.enqueueUserTask(updated, "upsert")
        return updated
    }

    override fun toggleComplete(id: TaskId): UserTask {
        val existing = repository.getById(id)
            ?: throw IllegalArgumentException("Task $id not found")

        val now = maxOf(clock(), existing.updatedAt + 1L)
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

        val targetStatus = if (completed) TaskStatus.COMPLETED else TaskStatus.PENDING
        if (existing.status == targetStatus) return existing
        val now = maxOf(clock(), existing.updatedAt + 1L)

        val updated = existing.copy(
            status = targetStatus,
            completedAt = if (completed) now else null,
            updatedAt = now,
        )
        repository.save(updated)
        syncManager?.enqueueUserTask(updated, "upsert")
        return updated
    }

    override fun resolveLegacyPlan(id: TaskId, accept: Boolean): UserTask {
        val existing = repository.getById(id)
            ?: throw IllegalArgumentException("Task $id not found")
        if (!existing.hasPendingLegacyPlan) return existing
        val now = maxOf(clock(), existing.updatedAt + 1L)
        val updated = if (accept) {
            val raw = requireNotNull(existing.legacyPlanDate)
            // Only the documented UTC epoch-day numeric encoding may be converted.
            // ISO input must pass strict validation; never normalize an invalid calendar date.
            val date = try {
                if (raw.toLongOrNull() != null) {
                    org.isoron.uhabits.core.userTasks.due.TaskDueDate.from(
                        requireNotNull(parseTaskPlannedDate(raw))
                    )
                } else {
                    org.isoron.uhabits.core.userTasks.due.TaskDueDate.parse(raw)
                }
            } catch (e: IllegalArgumentException) {
                throw InvalidLegacyPlanException(e)
            }
            existing.copy(due = TaskDue.Day(date), legacyPlanReviewPending = false, updatedAt = now)
        } else {
            existing.copy(legacyPlanReviewPending = false, updatedAt = now)
        }
        repository.save(updated)
        syncManager?.enqueueUserTask(updated, "upsert")
        return updated
    }

    override fun restoreTaskSnapshot(expectedCurrent: UserTask, previousState: UserTask): UserTask? {
        require(expectedCurrent.id == previousState.id) {
            "Snapshot ids must match: ${expectedCurrent.id} != ${previousState.id}"
        }
        val current = repository.getById(previousState.id) ?: return null
        if (current != expectedCurrent) return null

        val now = maxOf(clock(), current.updatedAt + 1L)
        val restored = previousState.copy(updatedAt = now)
        repository.save(restored)
        syncManager?.enqueueUserTask(restored, "upsert")
        return restored
    }

    override fun deleteTask(id: TaskId, cascadeSubtasks: Boolean) {
        if (cascadeSubtasks) {
            val subtasks = repository.getSubtasks(id)
            for (sub in subtasks) {
                deleteTask(sub.id, cascadeSubtasks = true)
            }
        }
        val now = maxOf(clock(), (repository.getById(id)?.updatedAt ?: Long.MIN_VALUE) + 1L)
        repository.delete(id)
        syncManager?.enqueueUserTaskDelete(id.value, now)
    }

    override fun reorderTasks(orderedIds: List<TaskId>) {
        val now = clock()
        val toUpdate = mutableListOf<UserTask>()
        val pendingById = mutableMapOf<TaskId, UserTask>()
        orderedIds.forEachIndexed { index, taskId ->
            val task = pendingById[taskId] ?: repository.getById(taskId)
                ?: return@forEachIndexed
            if (task.sortOrder != index) {
                val updated = task.copy(sortOrder = index, updatedAt = maxOf(now, task.updatedAt + 1L))
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
