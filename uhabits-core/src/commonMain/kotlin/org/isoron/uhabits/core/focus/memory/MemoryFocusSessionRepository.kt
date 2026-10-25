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

package org.isoron.uhabits.core.focus.memory

import org.isoron.uhabits.core.focus.FocusSession
import org.isoron.uhabits.core.focus.FocusSessionConflictException
import org.isoron.uhabits.core.focus.FocusSessionId
import org.isoron.uhabits.core.focus.FocusSessionRecordResult
import org.isoron.uhabits.core.focus.FocusSessionRepository
import org.isoron.uhabits.core.focus.FocusSessionCursor
import org.isoron.uhabits.core.userTasks.TaskId

class MemoryFocusSessionRepository : FocusSessionRepository {
    private val sessions = mutableMapOf<FocusSessionId, FocusSession>()
    private val order = compareBy<FocusSession> { it.startedAt }.thenBy { it.id.value }

    override fun record(session: FocusSession): FocusSessionRecordResult {
        val existing = sessions[session.id]
        if (existing != null) {
            if (existing != session) throw FocusSessionConflictException(session.id)
            return FocusSessionRecordResult.ALREADY_RECORDED
        }
        sessions[session.id] = session
        return FocusSessionRecordResult.INSERTED
    }

    override fun getById(id: FocusSessionId): FocusSession? = sessions[id]

    override fun getFinalizedPage(after: FocusSessionCursor?, limit: Int): List<FocusSession> {
        require(limit in 1..1000)
        return sessions.values.asSequence().filter {
            after == null || it.endedAt > after.endedAt ||
                (it.endedAt == after.endedAt && it.id.value > after.id)
        }.sortedWith(compareBy<FocusSession> { it.endedAt }.thenBy { it.id.value }).take(limit).toList()
    }

    override fun getAll(): List<FocusSession> = sessions.values.sortedWith(order)

    override fun getTotalDurationByTask(taskId: TaskId): Long = sessions.values
        .filter {
            it.taskId == taskId && (
                it.primaryTargetType == org.isoron.uhabits.core.focus.FocusPrimaryTargetType.TASK ||
                    it.primaryTargetType == null
            )
        }
        .sumOf { it.durationMs }

    override fun getByTask(taskId: TaskId): List<FocusSession> = sessions.values
        .filter { it.taskId == taskId }
        .sortedWith(order)

    override fun getByTimeRange(startMillis: Long, endMillis: Long): List<FocusSession> = sessions.values
        .filter { it.endedAt in startMillis until endMillis }
        .sortedWith(compareBy<FocusSession> { it.endedAt }.thenBy { it.id.value })
}
