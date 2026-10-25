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

package org.isoron.uhabits.core.rules

import org.isoron.uhabits.core.focus.*

data class FocusCreditFailure(val sessionId: FocusSessionId, val error: Exception)
data class FocusCreditReconcilePage(
    val nextCursor: FocusSessionCursor?,
    val hasMore: Boolean,
    val processed: Int,
    val created: Int,
    val failures: List<FocusCreditFailure>,
)

/** Bounded recovery pages. Caller owns dataset lifetime, scheduling and retry policy. */
class FocusCreditReconciler(
    private val sessions: FocusSessionRepository,
    private val attribution: SourceRuleAttributionService,
) {
    fun processPage(cursor: FocusSessionCursor? = null, limit: Int = 100, now: Long): FocusCreditReconcilePage {
        val page = sessions.getFinalizedPage(cursor, limit)
        val failures = mutableListOf<FocusCreditFailure>()
        var created = 0
        page.forEach { session ->
            try {
                created += attribution.attributeSession(session, now).count {
                    it is AttributionOutcome.Attributed && it.wasInserted
                }
            } catch (error: Exception) { failures.add(FocusCreditFailure(session.id, error)) }
        }
        return FocusCreditReconcilePage(
            page.lastOrNull()?.let { FocusSessionCursor(it.endedAt, it.id.value) } ?: cursor,
            page.size == limit, page.size, created, failures,
        )
    }

    fun reconcileSession(
        id: FocusSessionId,
        now: Long,
        isLiveCapture: Boolean = false,
    ): List<AttributionOutcome> =
        sessions.getById(id)?.let { attribution.attributeSession(it, now, isLiveCapture) } ?: emptyList()
}
