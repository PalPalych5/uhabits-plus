/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.core.userTasks

import org.isoron.uhabits.core.containers.ContainerId

interface UserTaskRepository {
    fun getById(id: TaskId): UserTask?
    fun getAll(): List<UserTask>
    fun getRoots(): List<UserTask>
    fun getByContainer(containerId: ContainerId): List<UserTask>
    fun getSubtasks(parentTaskId: TaskId): List<UserTask>
    fun getMaxSortOrder(parentTaskId: TaskId?): Int?
    fun save(task: UserTask)
    fun saveAll(tasks: Collection<UserTask>)
    fun delete(id: TaskId)
    fun deleteByContainer(containerId: ContainerId)
    fun clear()
}
