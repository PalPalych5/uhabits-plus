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

/**
 * Repository interface for durable Contribution facts.
 *
 * Invariant rules enforced at repository boundary:
 * - A contribution represents derived provenance linking a source fact to a target habit.
 * - Idempotency: exact retries (same id and same payload, or same source->target with same payload)
 *   return [ContributionRecordResult.ALREADY_RECORDED].
 * - Conflict rejection: attempting to record an existing ID or existing (sourceType, sourceId, targetHabit)
 *   with conflicting payload throws [ContributionConflictException].
 * - One source fact may contribute to multiple distinct target habits without collision.
 * - Removal is tombstone-based: marks the contribution with [Contribution.removedAt] timestamp.
 *   Repeated removal is idempotent and returns [ContributionRemovalResult.ALREADY_REMOVED].
 */
interface ContributionRepository {

    /**
     * Records a contribution.
     *
     * @return [ContributionRecordResult.INSERTED] if newly inserted,
     *         [ContributionRecordResult.ALREADY_RECORDED] if an exact identical record already exists.
     * @throws ContributionConflictException if the ID or (sourceType, sourceId, targetHabit)
     *         already exists with different payload.
     */
    fun record(contribution: Contribution): ContributionRecordResult

    /**
     * Logically removes / revokes a contribution identified by source and target.
     *
     * @return [ContributionRemovalResult.REMOVED] if successfully transitioned from active to removed,
     *         [ContributionRemovalResult.ALREADY_REMOVED] if already removed (idempotent),
     *         [ContributionRemovalResult.NOT_FOUND] if no such contribution exists.
     */
    fun remove(
        sourceType: ContributionSourceType,
        sourceId: String,
        targetHabit: HabitRef,
        removedAt: Long,
    ): ContributionRemovalResult

    /**
     * Logically removes / revokes a contribution by ID.
     */
    fun removeById(
        id: ContributionId,
        removedAt: Long,
    ): ContributionRemovalResult

    /**
     * Logically restores a previously removed / tombstoned contribution by ID.
     */
    fun restoreById(
        id: ContributionId,
        updatedAt: Long,
    ): Boolean

    /** Retrieves a contribution by its stable ID, including tombstoned records. */
    fun getById(id: ContributionId): Contribution?

    /** Retrieves the contribution linking the specified source fact to the specified target habit. */
    fun getBySourceAndTarget(
        sourceType: ContributionSourceType,
        sourceId: String,
        targetHabit: HabitRef,
    ): Contribution?

    /** Retrieves all contributions originating from the specified source fact. */
    fun getBySource(
        sourceType: ContributionSourceType,
        sourceId: String,
        includeRemoved: Boolean = false,
    ): List<Contribution>

    /** Retrieves all contributions attributing value to the specified target habit. */
    fun getByHabit(
        targetHabit: HabitRef,
        includeRemoved: Boolean = false,
    ): List<Contribution>

    /**
     * Retrieves contributions attributing value to the specified target habit within
     * the effective timestamp range [[fromTimestamp], [toTimestamp]] (inclusive).
     */
    fun getByHabitInRange(
        targetHabit: HabitRef,
        fromTimestamp: Long,
        toTimestamp: Long,
        includeRemoved: Boolean = false,
    ): List<Contribution>

    /**
     * Retrieves contributions for a set of habits within the inclusive effective timestamp range.
     * Empty habit sets and reversed ranges return an empty list. Results are ordered by
     * effective timestamp, then contribution ID, across all requested habits.
     */
    fun getByHabitsInRange(
        targetHabits: Set<HabitRef>,
        fromTimestamp: Long,
        toTimestamp: Long,
        includeRemoved: Boolean = false,
    ): List<Contribution>

    /** Retrieves all contributions in the repository. */
    fun getAll(includeRemoved: Boolean = false): List<Contribution>

    /** Computes the aggregate active (non-tombstoned) contributed value for a habit. */
    fun getActiveTotalForHabit(targetHabit: HabitRef): Long

    /**
     * Computes the aggregate active (non-tombstoned) contributed value for a habit
     * in the effective timestamp range [[fromTimestamp], [toTimestamp]] (inclusive).
     */
    fun getActiveTotalForHabitInRange(
        targetHabit: HabitRef,
        fromTimestamp: Long,
        toTimestamp: Long,
    ): Long

    /** Retrieves all contributions originating from the specified SourceRule. */
    fun getByRule(
        ruleId: org.isoron.uhabits.core.rules.SourceRuleId,
        includeRemoved: Boolean = false,
    ): List<Contribution>
}
