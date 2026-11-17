/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.core.userTasks.memory

import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.userTasks.TaskId
import org.isoron.uhabits.core.userTasks.UserTask
import org.isoron.uhabits.core.userTasks.UserTaskRepository

class MemoryUserTaskRepository : UserTaskRepository {
    private val tasks = mutableMapOf<TaskId, UserTask>()

    override fun getById(id: TaskId): UserTask? = tasks[id]

    override fun getAll(): List<UserTask> = tasks.values.sortedBy { it.sortOrder }

    override fun getRoots(): List<UserTask> = tasks.values
        .filter { it.parentTaskId == null }
        .sortedBy { it.sortOrder }

    override fun getByContainer(containerId: ContainerId): List<UserTask> = tasks.values
        .filter { it.containerId == containerId }
        .sortedBy { it.sortOrder }

    override fun getSubtasks(parentTaskId: TaskId): List<UserTask> = tasks.values
        .filter { it.parentTaskId == parentTaskId }
        .sortedBy { it.sortOrder }

    override fun getMaxSortOrder(parentTaskId: TaskId?): Int? = tasks.values
        .filter { it.parentTaskId == parentTaskId }
        .maxOfOrNull { it.sortOrder }

    override fun save(task: UserTask) {
        tasks[task.id] = task
    }

    override fun saveAll(tasks: Collection<UserTask>) {
        for (t in tasks) {
            this.tasks[t.id] = t
        }
    }

    override fun delete(id: TaskId) {
        tasks.remove(id)
    }

    override fun deleteByContainer(containerId: ContainerId) {
        val toRemove = tasks.values.filter { it.containerId == containerId }.map { it.id }
        for (id in toRemove) {
            tasks.remove(id)
        }
    }

    override fun clear() {
        tasks.clear()
    }
}
