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

package org.isoron.uhabits.core.focus.sqlite

import org.isoron.platform.io.Database
import org.isoron.platform.io.PreparedStatement
import org.isoron.platform.io.StepResult
import org.isoron.platform.io.queryLong
import org.isoron.platform.io.run
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.focus.FocusSession
import org.isoron.uhabits.core.focus.FocusSessionConflictException
import org.isoron.uhabits.core.focus.FocusSessionId
import org.isoron.uhabits.core.focus.FocusSessionRecordResult
import org.isoron.uhabits.core.focus.FocusSessionRepository
import org.isoron.uhabits.core.focus.FocusSessionSource
import org.isoron.uhabits.core.userTasks.TaskId

/**
 * Uses an existing, caller-owned Database, confined to its owning thread like other repositories.
 * Recording uses a savepoint and acquires the SQLite write lock before checking a duplicate.
 * Caller transactions are supported; durability follows their commit. No context foreign keys:
 * these references describe recorded work and must survive later Task/Container deletion.
 */
class SQLiteFocusSessionRepository(private val db: Database) : FocusSessionRepository {
    override fun getById(id: FocusSessionId): FocusSession? =
        query("WHERE id = ?") { bindText(1, id.value) }.firstOrNull()

    override fun getAll(): List<FocusSession> = query("")

    override fun getByTask(taskId: TaskId): List<FocusSession> =
        query("WHERE task_id = ?") { bindText(1, taskId.value) }

    override fun record(session: FocusSession): FocusSessionRecordResult {
        db.run("SAVEPOINT record_focus_session")
        try {
            // INSERT OR IGNORE works on supported older SQLite versions. A failed CHECK must
            // still be surfaced below; it must never be mistaken for an idempotent retry.
            val stmt = db.prepareStatement("INSERT OR IGNORE INTO FocusSessions ($COLUMNS) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
            try {
                stmt.bindText(1, session.id.value)
                stmt.bindLong(2, session.startedAt)
                stmt.bindLong(3, session.endedAt)
                stmt.bindLong(4, session.durationMs)
                stmt.bindNullableText(5, session.taskId?.value)
                stmt.bindNullableText(6, session.habit?.uuid)
                stmt.bindNullableText(7, session.containerId?.value)
                stmt.bindText(8, session.source.name)
                stmt.bindNullableText(9, session.deviceId)
                stmt.bindLong(10, session.createdAt)
                stmt.bindLong(11, session.updatedAt)
                stmt.bindLong(12, session.revision)
                stmt.step()
            } finally {
                stmt.finalize()
            }
            val inserted = db.queryLong("SELECT changes()") == 1L
            val stored = checkNotNull(getById(session.id)) { "FocusSession insert rejected by database constraints" }
            if (stored != session) throw FocusSessionConflictException(session.id)
            db.run("RELEASE SAVEPOINT record_focus_session")
            return if (inserted) FocusSessionRecordResult.INSERTED else FocusSessionRecordResult.ALREADY_RECORDED
        } catch (error: Throwable) {
            try {
                db.run("ROLLBACK TO SAVEPOINT record_focus_session")
            } finally {
                db.run("RELEASE SAVEPOINT record_focus_session")
            }
            throw error
        }
    }

    private fun query(where: String, bind: PreparedStatement.() -> Unit = {}): List<FocusSession> {
        val stmt = db.prepareStatement("SELECT $COLUMNS FROM FocusSessions $where ORDER BY started_at, id")
        return try {
            stmt.bind()
            buildList {
                while (stmt.step() == StepResult.ROW) {
                    add(FocusSession(
                        id = FocusSessionId(stmt.getText(0)),
                        startedAt = stmt.getLong(1), endedAt = stmt.getLong(2), durationMs = stmt.getLong(3),
                        taskId = stmt.getTextOrNull(4)?.let(::TaskId),
                        habit = stmt.getTextOrNull(5)?.let(::HabitRef),
                        containerId = stmt.getTextOrNull(6)?.let(::ContainerId),
                        source = FocusSessionSource.valueOf(stmt.getText(7)), deviceId = stmt.getTextOrNull(8),
                        createdAt = stmt.getLong(9), updatedAt = stmt.getLong(10), revision = stmt.getLong(11)
                    ))
                }
            }
        } finally {
            stmt.finalize()
        }
    }

    private fun PreparedStatement.bindNullableText(index: Int, value: String?) {
        if (value == null) bindNull(index) else bindText(index, value)
    }

    companion object {
        private const val COLUMNS = "id, started_at, ended_at, duration_ms, task_id, habit_uuid, container_id, source, device_id, created_at, updated_at, revision"
    }
}
