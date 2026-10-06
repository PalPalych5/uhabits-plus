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
import org.isoron.uhabits.core.rules.SourceRuleId
import kotlin.jvm.JvmInline

@JvmInline
value class ContributionId(val value: String) {
    init {
        require(value.isNotBlank()) { "Contribution id must not be blank" }
    }

    override fun toString(): String = value
}

/**
 * Source fact types that can produce a Contribution.
 * PR13 supports FOCUS_SESSION; future PRs may add TASK_COMPLETION etc.
 */
enum class ContributionSourceType {
    FOCUS_SESSION,
}

/**
 * Result of recording a Contribution.
 */
enum class ContributionRecordResult {
    INSERTED,
    ALREADY_RECORDED,
}

/**
 * Result of removing/reversing a Contribution.
 */
enum class ContributionRemovalResult {
    REMOVED,
    ALREADY_REMOVED,
    NOT_FOUND,
}

/**
 * Thrown when an idempotent record attempt finds an existing Contribution with the same
 * logical identity (same ID, or same source -> target pair) but conflicting payload.
 */
class ContributionConflictException(
    message: String,
    val contributionId: ContributionId? = null,
) : IllegalStateException(message)

/**
 * A derived provenance record that attributes a measured value from a known
 * source fact to a target Habit.
 *
 * Contribution is NOT a second FocusSession, NOT a Task completion, and NOT
 * a manual Habit Entry. It is strictly derived/provenance data.
 *
 * Value semantics:
 * Contributed value is stored as a Long in the target Habit's native numerical scale
 * (e.g. numerical habits use the x1000 scale where 1.0 unit = 1000L; for duration-based
 * habits, milliseconds or scaled minutes as defined by caller contract).
 * Nonnegative values (>= 0) are permitted: an explicit 0 is a recorded fact distinguishable
 * from an absent/missing contribution.
 *
 * Tombstone semantics:
 * When [removedAt] is non-null, the contribution is logically revoked/tombstoned.
 * The record remains in the ledger to preserve provenance and audit trail.
 */
data class Contribution(
    val id: ContributionId,
    val targetHabit: HabitRef,
    val sourceType: ContributionSourceType,
    val sourceId: String,
    val contributedValue: Long,
    val effectiveTimestamp: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val removedAt: Long? = null,
    val sourceRuleId: SourceRuleId? = null,
) {
    init {
        require(sourceId.isNotBlank()) { "sourceId must not be blank" }
        require(contributedValue >= 0) { "contributedValue must be nonnegative" }
        require(effectiveTimestamp >= 0) { "effectiveTimestamp must be nonnegative" }
        require(createdAt >= 0) { "createdAt must be nonnegative" }
        require(updatedAt >= createdAt) { "updatedAt must not precede createdAt" }
        require(removedAt == null || removedAt >= createdAt) {
            "removedAt must not precede createdAt"
        }
    }

    /** Whether this contribution is logically active (not tombstoned). */
    val isActive: Boolean get() = removedAt == null
}
