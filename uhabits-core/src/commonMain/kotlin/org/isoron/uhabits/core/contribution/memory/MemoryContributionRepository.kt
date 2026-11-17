/* SPDX-License-Identifier: GPL-3.0-or-later */

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

    override fun restoreById(id: ContributionId, updatedAt: Long): Boolean {
        val existing = contributionsById[id] ?: return false
        if (existing.isActive) return false
        contributionsById[id] = existing.copy(removedAt = null, updatedAt = maxOf(existing.updatedAt, updatedAt))
        return true
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

    override fun getByHabitsInRange(
        targetHabits: Set<HabitRef>,
        fromTimestamp: Long,
        toTimestamp: Long,
        includeRemoved: Boolean,
    ): List<Contribution> {
        if (targetHabits.isEmpty() || fromTimestamp > toTimestamp) return emptyList()
        return contributionsById.values.filter {
            it.targetHabit in targetHabits &&
                it.effectiveTimestamp in fromTimestamp..toTimestamp &&
                (includeRemoved || it.isActive)
        }.sortedWith(order)
    }

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

    override fun getByRule(
        ruleId: org.isoron.uhabits.core.rules.SourceRuleId,
        includeRemoved: Boolean,
    ): List<Contribution> = contributionsById.values
        .filter { it.sourceRuleId == ruleId && (includeRemoved || it.isActive) }
        .sortedWith(order)
}
