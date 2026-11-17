/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.core.rules

import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.containers.HabitRef
import kotlin.jvm.JvmInline

@JvmInline
value class SourceRuleId(val value: String) {
    init {
        require(value.isNotBlank()) { "SourceRule id must not be blank" }
    }

    override fun toString(): String = value
}

enum class SourceRuleSourceKind {
    FOCUS_SESSION,
}

enum class SourceRuleValueMapping {
    FOCUS_DURATION,
}

enum class SourceRuleRecordResult {
    INSERTED,
    ALREADY_RECORDED,
}

enum class SourceRuleRemovalResult {
    REMOVED,
    ALREADY_REMOVED,
    NOT_FOUND,
}

class SourceRuleConflictException(
    message: String,
    val ruleId: SourceRuleId? = null,
) : IllegalStateException(message)

/**
 * An explicit attribution rule (SourceRule v1).
 *
 * Matches finalized FocusSession facts whose historical container placement falls
 * inside [sourceContainerId] (and optionally its descendants when [includeDescendants] is true),
 * and projects elapsed focus duration into [targetHabit] as a Contribution.
 *
 * Temporal semantics:
 * - [effectiveFrom]: The rule applies to sessions whose effective timestamp (endedAt) >= effectiveFrom.
 * - [isActive]: The rule is enabled and not tombstoned.
 * - An enabled rule ended with [removedAt] remains recoverable for sessions ending in
 *   the half-open interval [effectiveFrom, removedAt). UI edits start a replacement rule.
 * - Disabled records without an end timestamp have no recoverable interval history.
 */
data class SourceRule(
    val id: SourceRuleId,
    val sourceKind: SourceRuleSourceKind = SourceRuleSourceKind.FOCUS_SESSION,
    val sourceContainerId: ContainerId,
    val includeDescendants: Boolean = true,
    val targetHabit: HabitRef,
    val valueMapping: SourceRuleValueMapping = SourceRuleValueMapping.FOCUS_DURATION,
    val isEnabled: Boolean = true,
    val effectiveFrom: Long = 0L,
    val createdAt: Long,
    val updatedAt: Long,
    val removedAt: Long? = null,
) {
    init {
        require(effectiveFrom >= 0) { "effectiveFrom must be nonnegative" }
        require(createdAt >= 0) { "createdAt must be nonnegative" }
        require(updatedAt >= createdAt) { "updatedAt must not precede createdAt" }
        require(removedAt == null || removedAt >= createdAt) {
            "removedAt must not precede createdAt"
        }
    }

    /** Whether this rule is logically active (enabled and not tombstoned). */
    val isActive: Boolean get() = isEnabled && removedAt == null
}
