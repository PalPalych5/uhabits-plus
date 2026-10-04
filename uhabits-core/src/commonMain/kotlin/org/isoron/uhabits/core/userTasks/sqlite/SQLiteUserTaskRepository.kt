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

package org.isoron.uhabits.core.userTasks.sqlite

import org.isoron.platform.io.Database
import org.isoron.platform.io.PreparedStatement
import org.isoron.platform.io.StepResult
import org.isoron.platform.io.run
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.userTasks.TaskId
import org.isoron.uhabits.core.userTasks.TaskStatus
import org.isoron.uhabits.core.userTasks.UserTask
import org.isoron.uhabits.core.userTasks.UserTaskRepository
import org.isoron.uhabits.core.userTasks.parseIsoDateString
import org.isoron.uhabits.core.userTasks.toIsoDateString

class SQLiteUserTaskRepository(
    private val db: Database
) : UserTaskRepository {

    override fun getById(id: TaskId): UserTask? {
        val stmt = db.prepareStatement(
            "SELECT id, title, description, container_id, status, planned_date, deadline, completed_at, parent_task_id, sort_order, created_at, updated_at FROM UserTasks WHERE id = ?"
        )
        return try {
            stmt.bindText(1, id.value)
            if (stmt.step() == StepResult.ROW) {
                mapRow(stmt)
            } else {
                null
            }
        } finally {
            stmt.finalize()
        }
    }

    override fun getAll(): List<UserTask> {
        val stmt = db.prepareStatement(
            "SELECT id, title, description, container_id, status, planned_date, deadline, completed_at, parent_task_id, sort_order, created_at, updated_at FROM UserTasks ORDER BY sort_order ASC, created_at ASC"
        )
        val list = mutableListOf<UserTask>()
        return try {
            while (stmt.step() == StepResult.ROW) {
                list.add(mapRow(stmt))
            }
            list
        } finally {
            stmt.finalize()
        }
    }

    override fun getRoots(): List<UserTask> {
        val stmt = db.prepareStatement(
            "SELECT id, title, description, container_id, status, planned_date, deadline, completed_at, parent_task_id, sort_order, created_at, updated_at FROM UserTasks WHERE parent_task_id IS NULL ORDER BY sort_order ASC, created_at ASC"
        )
        val list = mutableListOf<UserTask>()
        return try {
            while (stmt.step() == StepResult.ROW) {
                list.add(mapRow(stmt))
            }
            list
        } finally {
            stmt.finalize()
        }
    }

    override fun getByContainer(containerId: ContainerId): List<UserTask> {
        val stmt = db.prepareStatement(
            "SELECT id, title, description, container_id, status, planned_date, deadline, completed_at, parent_task_id, sort_order, created_at, updated_at FROM UserTasks WHERE container_id = ? ORDER BY sort_order ASC, created_at ASC"
        )
        val list = mutableListOf<UserTask>()
        return try {
            stmt.bindText(1, containerId.value)
            while (stmt.step() == StepResult.ROW) {
                list.add(mapRow(stmt))
            }
            list
        } finally {
            stmt.finalize()
        }
    }

    override fun getSubtasks(parentTaskId: TaskId): List<UserTask> {
        val stmt = db.prepareStatement(
            "SELECT id, title, description, container_id, status, planned_date, deadline, completed_at, parent_task_id, sort_order, created_at, updated_at FROM UserTasks WHERE parent_task_id = ? ORDER BY sort_order ASC, created_at ASC"
        )
        val list = mutableListOf<UserTask>()
        return try {
            stmt.bindText(1, parentTaskId.value)
            while (stmt.step() == StepResult.ROW) {
                list.add(mapRow(stmt))
            }
            list
        } finally {
            stmt.finalize()
        }
    }

    override fun save(task: UserTask) {
        val stmt = db.prepareStatement(
            """
            INSERT OR REPLACE INTO UserTasks (
                id, title, description, container_id, status, planned_date,
                deadline, completed_at, parent_task_id, sort_order, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        )
        try {
            bindTask(stmt, task)
            stmt.step()
        } finally {
            stmt.finalize()
        }
    }

    override fun saveAll(tasks: Collection<UserTask>) {
        if (tasks.isEmpty()) return
        val stmt = db.prepareStatement(
            """
            INSERT OR REPLACE INTO UserTasks (
                id, title, description, container_id, status, planned_date,
                deadline, completed_at, parent_task_id, sort_order, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        )
        try {
            for (task in tasks) {
                stmt.reset()
                bindTask(stmt, task)
                stmt.step()
            }
        } finally {
            stmt.finalize()
        }
    }

    override fun delete(id: TaskId) {
        val stmt = db.prepareStatement("DELETE FROM UserTasks WHERE id = ?")
        try {
            stmt.bindText(1, id.value)
            stmt.step()
        } finally {
            stmt.finalize()
        }
    }

    override fun deleteByContainer(containerId: ContainerId) {
        val stmt = db.prepareStatement("DELETE FROM UserTasks WHERE container_id = ?")
        try {
            stmt.bindText(1, containerId.value)
            stmt.step()
        } finally {
            stmt.finalize()
        }
    }

    override fun clear() {
        db.run("DELETE FROM UserTasks")
    }

    private fun bindTask(stmt: PreparedStatement, task: UserTask) {
        stmt.bindText(1, task.id.value)
        stmt.bindText(2, task.title)
        stmt.bindText(3, task.description)
        if (task.containerId != null) stmt.bindText(4, task.containerId.value) else stmt.bindNull(4)
        stmt.bindText(5, task.status.name)
        if (task.plannedDate != null) stmt.bindText(6, task.plannedDate.toIsoDateString()) else stmt.bindNull(6)
        if (task.deadline != null) stmt.bindLong(7, task.deadline) else stmt.bindNull(7)
        if (task.completedAt != null) stmt.bindLong(8, task.completedAt) else stmt.bindNull(8)
        if (task.parentTaskId != null) stmt.bindText(9, task.parentTaskId.value) else stmt.bindNull(9)
        stmt.bindInt(10, task.sortOrder)
        stmt.bindLong(11, task.createdAt)
        stmt.bindLong(12, task.updatedAt)
    }

    private fun mapRow(stmt: PreparedStatement): UserTask {
        val id = TaskId(stmt.getText(0))
        val title = stmt.getText(1)
        val description = stmt.getText(2)
        val containerId = stmt.getTextOrNull(3)?.let { ContainerId(it) }
        val status = try { TaskStatus.valueOf(stmt.getText(4)) } catch (_: Throwable) { TaskStatus.PENDING }
        val plannedDate = parseIsoDateString(stmt.getTextOrNull(5))
        val deadline = stmt.getLongOrNull(6)
        val completedAt = stmt.getLongOrNull(7)
        val parentTaskId = stmt.getTextOrNull(8)?.let { TaskId(it) }
        val sortOrder = stmt.getInt(9)
        val createdAt = stmt.getLong(10)
        val updatedAt = stmt.getLong(11)

        return UserTask(
            id = id,
            title = title,
            description = description,
            containerId = containerId,
            status = status,
            plannedDate = plannedDate,
            deadline = deadline,
            completedAt = completedAt,
            parentTaskId = parentTaskId,
            sortOrder = sortOrder,
            createdAt = createdAt,
            updatedAt = updatedAt
        )
    }
}
