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
