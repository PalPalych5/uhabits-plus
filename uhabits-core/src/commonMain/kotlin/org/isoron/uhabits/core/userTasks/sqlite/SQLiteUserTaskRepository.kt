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
import org.isoron.platform.io.runInTransaction
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.userTasks.TaskId
import org.isoron.uhabits.core.userTasks.TaskStatus
import org.isoron.uhabits.core.userTasks.UserTask
import org.isoron.uhabits.core.userTasks.UserTaskRepository
import org.isoron.uhabits.core.userTasks.due.TaskDue
import org.isoron.uhabits.core.userTasks.due.TaskDueDate

class SQLiteUserTaskRepository(
    private val db: Database
) : UserTaskRepository {

    companion object {
        private const val SELECT_COLUMNS =
            "id, title, description, container_id, status, due_kind, due_date, due_time_millis, due_zone, completed_at, parent_task_id, sort_order, created_at, updated_at, planned_date, legacy_plan_review_pending, important, urgent"
    }

    override fun getById(id: TaskId): UserTask? {
        val stmt = db.prepareStatement(
            "SELECT $SELECT_COLUMNS FROM UserTasks WHERE id = ?"
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
            "SELECT $SELECT_COLUMNS FROM UserTasks ORDER BY sort_order ASC, created_at ASC"
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
            "SELECT $SELECT_COLUMNS FROM UserTasks WHERE parent_task_id IS NULL ORDER BY sort_order ASC, created_at ASC"
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
            "SELECT $SELECT_COLUMNS FROM UserTasks WHERE container_id = ? ORDER BY sort_order ASC, created_at ASC"
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
            "SELECT $SELECT_COLUMNS FROM UserTasks WHERE parent_task_id = ? ORDER BY sort_order ASC, created_at ASC"
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

    override fun getMaxSortOrder(parentTaskId: TaskId?): Int? {
        val sql = if (parentTaskId == null) {
            "SELECT MAX(sort_order) FROM UserTasks WHERE parent_task_id IS NULL"
        } else {
            "SELECT MAX(sort_order) FROM UserTasks WHERE parent_task_id = ?"
        }
        val stmt = db.prepareStatement(sql)
        return try {
            if (parentTaskId != null) {
                stmt.bindText(1, parentTaskId.value)
            }
            if (stmt.step() == StepResult.ROW) {
                stmt.getIntOrNull(0)
            } else {
                null
            }
        } finally {
            stmt.finalize()
        }
    }

    override fun save(task: UserTask) {
        UserTaskSqlWriter.upsert(db, task.id.value, writeClassification = true, important = task.important, urgent = task.urgent) {
            bindTask(this, task)
        }
    }

    override fun saveAll(tasks: Collection<UserTask>) {
        if (tasks.isEmpty()) return
        db.runInTransaction {
            for (task in tasks) save(task)
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
        when (val due = task.due) {
            is TaskDue.None -> {
                stmt.bindText(6, "NONE")
                stmt.bindNull(7)
                stmt.bindNull(8)
                stmt.bindNull(9)
            }
            is TaskDue.Day -> {
                stmt.bindText(6, "DAY")
                stmt.bindText(7, due.date.isoDate)
                stmt.bindNull(8)
                stmt.bindNull(9)
            }
            is TaskDue.Timed -> {
                stmt.bindText(6, "TIMED")
                stmt.bindNull(7)
                stmt.bindLong(8, due.epochMillis)
                if (due.originZoneId != null) stmt.bindText(9, due.originZoneId) else stmt.bindNull(9)
            }
        }
        if (task.completedAt != null) stmt.bindLong(10, task.completedAt) else stmt.bindNull(10)
        if (task.parentTaskId != null) stmt.bindText(11, task.parentTaskId.value) else stmt.bindNull(11)
        stmt.bindInt(12, task.sortOrder)
        stmt.bindLong(13, task.createdAt)
        stmt.bindLong(14, task.updatedAt)
        if (task.legacyPlanDate != null) stmt.bindText(15, task.legacyPlanDate) else stmt.bindNull(15)
        stmt.bindInt(16, if (task.legacyPlanReviewPending) 1 else 0)
    }

    private fun mapRow(stmt: PreparedStatement): UserTask {
        val id = TaskId(stmt.getText(0))
        val title = stmt.getText(1)
        val description = stmt.getText(2)
        val containerId = stmt.getTextOrNull(3)?.let { ContainerId(it) }
        val status = try { TaskStatus.valueOf(stmt.getText(4)) } catch (_: Throwable) { TaskStatus.PENDING }
        val dueKind = stmt.getTextOrNull(5)
        val dueDateStr = stmt.getTextOrNull(6)
        val dueTimeMillis = stmt.getLongOrNull(7)
        val dueZone = stmt.getTextOrNull(8)

        val due: TaskDue = when (dueKind) {
            "DAY" -> dueDateStr?.let { runCatching { TaskDue.Day(TaskDueDate.parse(it)) }.getOrNull() } ?: TaskDue.None
            "TIMED" -> dueTimeMillis?.let { TaskDue.Timed.of(it, dueZone) } ?: TaskDue.None
            else -> TaskDue.None
        }

        val completedAt = stmt.getLongOrNull(9)
        val parentTaskId = stmt.getTextOrNull(10)?.let { TaskId(it) }
        val sortOrder = stmt.getInt(11)
        val createdAt = stmt.getLong(12)
        val updatedAt = stmt.getLong(13)
        val legacyPlanDate = stmt.getTextOrNull(14)

        return UserTask(
            id = id,
            important = stmt.getIntOrNull(16)?.let { it != 0 },
            urgent = stmt.getIntOrNull(17)?.let { it != 0 },
            title = title,
            description = description,
            containerId = containerId,
            status = status,
            due = due,
            legacyPlanDate = legacyPlanDate,
            legacyPlanReviewPending = stmt.getInt(15) != 0,
            completedAt = completedAt,
            parentTaskId = parentTaskId,
            sortOrder = sortOrder,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
    }
}
