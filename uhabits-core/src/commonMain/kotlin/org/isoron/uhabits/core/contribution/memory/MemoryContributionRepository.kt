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

package org.isoron.uhabits.core.contribution.memory

import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.contribution.*

class MemoryContributionRepository : ContributionRepository {

    private val contributionsById = mutableMapOf<ContributionId, Contribution>()
    private val provenanceMap = mutableMapOf<String, ContributionId>()

    private val order = compareBy<Contribution> { it.effectiveTimestamp }.thenBy { it.id.value }

    private fun provenanceKey(sourceType: ContributionSourceType, sourceId: String, habitUuid: String): String =
        "${sourceType.name}:$sourceId:$habitUuid"

    override fun record(contribution: Contribution): ContributionRecordResult {
        val byId = contributionsById[contribution.id]
        if (byId != null) {
            if (byId != contribution) {
                throw ContributionConflictException(
                    "A different Contribution is already recorded for id ${contribution.id}",
                    contribution.id,
                )
            }
            return ContributionRecordResult.ALREADY_RECORDED
        }

        val key = provenanceKey(contribution.sourceType, contribution.sourceId, contribution.targetHabit.uuid)
        val existingIdForProvenance = provenanceMap[key]
        if (existingIdForProvenance != null) {
            val existing = contributionsById[existingIdForProvenance]
            if (existing != null) {
                if (existing.id == contribution.id && existing == contribution) {
                    return ContributionRecordResult.ALREADY_RECORDED
                }
                throw ContributionConflictException(
                    "A contribution for source ${contribution.sourceType}:${contribution.sourceId} to habit ${contribution.targetHabit.uuid} already exists with id $existingIdForProvenance",
                    contribution.id,
                )
            }
        }

        contributionsById[contribution.id] = contribution
        provenanceMap[key] = contribution.id
        return ContributionRecordResult.INSERTED
    }

    override fun remove(
        sourceType: ContributionSourceType,
        sourceId: String,
        targetHabit: HabitRef,
        removedAt: Long,
    ): ContributionRemovalResult {
        val existing = getBySourceAndTarget(sourceType, sourceId, targetHabit) ?: return ContributionRemovalResult.NOT_FOUND
        return applyRemoval(existing, removedAt)
    }

    override fun removeById(id: ContributionId, removedAt: Long): ContributionRemovalResult {
        val existing = contributionsById[id] ?: return ContributionRemovalResult.NOT_FOUND
        return applyRemoval(existing, removedAt)
    }

    private fun applyRemoval(existing: Contribution, removedAt: Long): ContributionRemovalResult {
        if (!existing.isActive) {
            return ContributionRemovalResult.ALREADY_REMOVED
        }
        val updated = existing.copy(
            removedAt = removedAt,
            updatedAt = maxOf(existing.updatedAt, removedAt),
        )
        contributionsById[existing.id] = updated
        return ContributionRemovalResult.REMOVED
    }

    override fun getById(id: ContributionId): Contribution? = contributionsById[id]

    override fun getBySourceAndTarget(
        sourceType: ContributionSourceType,
        sourceId: String,
        targetHabit: HabitRef,
    ): Contribution? {
        val key = provenanceKey(sourceType, sourceId, targetHabit.uuid)
        val id = provenanceMap[key] ?: return null
        return contributionsById[id]
    }

    override fun getBySource(
        sourceType: ContributionSourceType,
        sourceId: String,
        includeRemoved: Boolean,
    ): List<Contribution> = contributionsById.values
        .filter { it.sourceType == sourceType && it.sourceId == sourceId && (includeRemoved || it.isActive) }
        .sortedWith(order)

    override fun getByHabit(
        targetHabit: HabitRef,
        includeRemoved: Boolean,
    ): List<Contribution> = contributionsById.values
        .filter { it.targetHabit == targetHabit && (includeRemoved || it.isActive) }
        .sortedWith(order)

    override fun getByHabitInRange(
        targetHabit: HabitRef,
        fromTimestamp: Long,
        toTimestamp: Long,
        includeRemoved: Boolean,
    ): List<Contribution> = contributionsById.values
        .filter {
            it.targetHabit == targetHabit &&
                it.effectiveTimestamp in fromTimestamp..toTimestamp &&
                (includeRemoved || it.isActive)
        }
        .sortedWith(order)

    override fun getAll(includeRemoved: Boolean): List<Contribution> = contributionsById.values
        .filter { includeRemoved || it.isActive }
        .sortedWith(order)

    override fun getActiveTotalForHabit(targetHabit: HabitRef): Long =
        getByHabit(targetHabit, includeRemoved = false).sumOf { it.contributedValue }

    override fun getActiveTotalForHabitInRange(
        targetHabit: HabitRef,
        fromTimestamp: Long,
        toTimestamp: Long,
    ): Long = getByHabitInRange(targetHabit, fromTimestamp, toTimestamp, includeRemoved = false)
        .sumOf { it.contributedValue }
}
