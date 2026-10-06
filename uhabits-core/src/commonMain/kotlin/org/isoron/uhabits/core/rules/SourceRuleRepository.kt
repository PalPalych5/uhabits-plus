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

import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.containers.HabitRef

/**
 * Repository interface for durable SourceRule facts.
 *
 * Invariant rules enforced at repository boundary:
 * - Idempotency: exact retries (same id and same payload) return [SourceRuleRecordResult.ALREADY_RECORDED].
 * - Conflict rejection: attempting to record an existing ID or existing (sourceKind, sourceContainerId, targetHabit)
 *   with different payload throws [SourceRuleConflictException].
 * - Removal is tombstone-based: marks the rule with [SourceRule.removedAt] timestamp.
 *   Repeated removal is idempotent and returns [SourceRuleRemovalResult.ALREADY_REMOVED].
 */
interface SourceRuleRepository {

    /**
     * Records a SourceRule.
     *
     * @return [SourceRuleRecordResult.INSERTED] if newly inserted,
     *         [SourceRuleRecordResult.ALREADY_RECORDED] if an exact identical record already exists.
     * @throws SourceRuleConflictException if the ID or (sourceKind, sourceContainerId, targetHabit)
     *         already exists with different payload.
     */
    fun record(rule: SourceRule): SourceRuleRecordResult

    /**
     * Logically removes / revokes a rule by ID.
     *
     * @return [SourceRuleRemovalResult.REMOVED] if successfully transitioned from active to removed,
     *         [SourceRuleRemovalResult.ALREADY_REMOVED] if already removed (idempotent),
     *         [SourceRuleRemovalResult.NOT_FOUND] if no such rule exists.
     */
    fun remove(id: SourceRuleId, removedAt: Long): SourceRuleRemovalResult

    /** Retrieves a rule by its stable ID, including tombstoned records. */
    fun getById(id: SourceRuleId): SourceRule?

    /** Retrieves all rules targeting the specified habit. */
    fun getByTargetHabit(targetHabit: HabitRef, includeRemoved: Boolean = false): List<SourceRule>

    /** Retrieves all rules for the specified source container. */
    fun getByContainer(containerId: ContainerId, includeRemoved: Boolean = false): List<SourceRule>

    /** Retrieves all rules in the repository. */
    fun getAll(includeRemoved: Boolean = false): List<SourceRule>

    /** Retrieves all active (enabled and not removed) rules. */
    fun getActiveRules(): List<SourceRule>
}
