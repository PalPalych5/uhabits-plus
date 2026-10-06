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

package org.isoron.uhabits.core.contribution

import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.focus.FocusSession
import org.isoron.uhabits.core.focus.FocusSessionId

/**
 * Domain service providing the explicit integration boundary for projecting
 * durable FocusSessions into the Contribution ledger.
 *
 * Explicit boundary invariants (PR13):
 * - Does NOT automatically project FocusSessions upon finalization.
 * - Requires explicit caller-provided target Habit and contribution value.
 * - Does NOT write back to Habit Entry / Repetitions records.
 * - Does NOT alter Task completion status or parent Container state.
 */
class ContributionService(
    private val repository: ContributionRepository,
) {

    /**
     * Explicitly attributes work from a finalized [FocusSession] to a [targetHabit].
     *
     * @param session The finalized source work fact.
     * @param targetHabit The target Habit to receive the contribution.
     * @param contributionId Stable UUID for this contribution.
     * @param contributedValue The attributed numerical value (in target Habit's native scale).
     * @param effectiveTimestamp The semantic timestamp for the contribution (defaults to session.endedAt).
     * @param timestamp System timestamp for createdAt/updatedAt (defaults to session.endedAt).
     */
    fun contributeFromFocusSession(
        session: FocusSession,
        targetHabit: HabitRef,
        contributionId: ContributionId,
        contributedValue: Long,
        effectiveTimestamp: Long = session.endedAt,
        timestamp: Long = session.endedAt,
    ): ContributionRecordResult {
        require(session.id.value.isNotBlank()) { "FocusSession id must not be blank" }
        require(session.durationMs > 0) { "FocusSession must have positive duration" }
        require(contributedValue >= 0) { "contributedValue must be nonnegative" }

        val contribution = Contribution(
            id = contributionId,
            targetHabit = targetHabit,
            sourceType = ContributionSourceType.FOCUS_SESSION,
            sourceId = session.id.value,
            contributedValue = contributedValue,
            effectiveTimestamp = effectiveTimestamp,
            createdAt = timestamp,
            updatedAt = timestamp,
            removedAt = null,
        )
        return repository.record(contribution)
    }

    /**
     * Explicitly revokes / removes a contribution previously derived from a [FocusSession].
     */
    fun revokeFocusSessionContribution(
        sessionId: FocusSessionId,
        targetHabit: HabitRef,
        removedAt: Long,
    ): ContributionRemovalResult {
        return repository.remove(
            sourceType = ContributionSourceType.FOCUS_SESSION,
            sourceId = sessionId.value,
            targetHabit = targetHabit,
            removedAt = removedAt,
        )
    }
}
