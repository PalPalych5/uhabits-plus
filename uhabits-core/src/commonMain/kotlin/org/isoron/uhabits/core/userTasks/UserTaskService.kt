/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.core.userTasks

import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.userTasks.due.TaskDue

interface UserTaskService {
    fun createTask(
        title: String,
        description: String = "",
        containerId: ContainerId? = null,
        due: TaskDue = TaskDue.None,
        parentTaskId: TaskId? = null,
        explicitId: TaskId? = null,
        sortOrder: Int? = null,
        important: Boolean? = null,
        urgent: Boolean? = null,
        linkedHabitUuid: String? = null,
        habitTimeAccrualEnabled: Boolean = false,
        habitLinkEffectiveFrom: Long? = null,
    ): UserTask

    fun updateTask(
        id: TaskId,
        title: String? = null,
        description: String? = null,
        containerId: ContainerId? = null,
        clearContainer: Boolean = false,
        due: TaskDue? = null,
        parentTaskId: TaskId? = null,
        clearParentTask: Boolean = false,
        sortOrder: Int? = null,
        important: Boolean? = null,
        urgent: Boolean? = null,
        clearImportant: Boolean = false,
        clearUrgent: Boolean = false,
        linkedHabitUuid: String? = null,
        clearLinkedHabit: Boolean = false,
        habitTimeAccrualEnabled: Boolean? = null,
        habitLinkEffectiveFrom: Long? = null,
        clearHabitLinkEffectiveFrom: Boolean = false,
    ): UserTask

    fun toggleComplete(id: TaskId): UserTask

    fun setCompleted(id: TaskId, completed: Boolean): UserTask

    fun resolveLegacyPlan(id: TaskId, accept: Boolean): UserTask

    fun restoreTaskSnapshot(expectedCurrent: UserTask, previousState: UserTask): UserTask?

    fun deleteTask(id: TaskId, cascadeSubtasks: Boolean = true)

    fun reorderTasks(orderedIds: List<TaskId>)
}
