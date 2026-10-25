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

package org.isoron.uhabits.core.focus

import org.isoron.uhabits.core.userTasks.TaskId

enum class FocusSessionRecordResult {
    INSERTED,
    ALREADY_RECORDED,
}

class FocusSessionConflictException(val sessionId: FocusSessionId) :
    IllegalStateException("A different FocusSession is already recorded for id $sessionId")

/** Append-only facts; queries are ordered by startedAt, then id. */
data class FocusSessionCursor(val endedAt: Long, val id: String)

interface FocusSessionRepository {
    /**
     * An exact retry returns ALREADY_RECORDED. Reusing an id with changed content
     * throws FocusSessionConflictException and preserves the original record.
     */
    fun record(session: FocusSession): FocusSessionRecordResult
    fun getById(id: FocusSessionId): FocusSession?
    fun getAll(): List<FocusSession>
    fun getByTask(taskId: TaskId): List<FocusSession>
    /** Returns the sum of durationMs for all finalized sessions associated with the given task as primary target, or 0 if none. */
    fun getTotalDurationByTask(taskId: TaskId): Long
    /** Keyset page, ordered by endedAt/id. Includes late/imported facts on a new startup sweep. */
    fun getFinalizedPage(after: FocusSessionCursor?, limit: Int): List<FocusSession>
    /** Returns finalized sessions with endedAt in [startMillis, endMillis), ordered by endedAt. */
    fun getByTimeRange(startMillis: Long, endMillis: Long): List<FocusSession>
}
