/* SPDX-License-Identifier: GPL-3.0-or-later */

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
