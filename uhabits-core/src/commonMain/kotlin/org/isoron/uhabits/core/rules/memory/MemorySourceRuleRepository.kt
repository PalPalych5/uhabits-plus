/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.core.rules.memory

import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.rules.*

class MemorySourceRuleRepository : SourceRuleRepository {

    private val rulesById = mutableMapOf<SourceRuleId, SourceRule>()
    private val scopeMap = mutableMapOf<String, SourceRuleId>()

    private val order = compareBy<SourceRule> { it.createdAt }.thenBy { it.id.value }

    private fun scopeKey(rule: SourceRule): String =
        "${rule.sourceKind.name}:${rule.sourceContainerId.value}:${rule.targetHabit.uuid}"

    override fun record(rule: SourceRule): SourceRuleRecordResult {
        val byId = rulesById[rule.id]
        if (byId != null) {
            if (byId != rule) {
                throw SourceRuleConflictException(
                    "A different SourceRule is already recorded for id ${rule.id}",
                    rule.id,
                )
            }
            return SourceRuleRecordResult.ALREADY_RECORDED
        }

        val key = scopeKey(rule)
        val existingId = scopeMap[key]
        if (rule.isActive && existingId != null) {
            val existing = rulesById[existingId]
            if (existing != null && existing.isActive) {
                if (existing.id == rule.id && existing == rule) {
                    return SourceRuleRecordResult.ALREADY_RECORDED
                }
                throw SourceRuleConflictException(
                    "A SourceRule for container ${rule.sourceContainerId.value} to habit ${rule.targetHabit.uuid} already exists with id $existingId",
                    rule.id,
                )
            }
        }

        rulesById[rule.id] = rule
        if (rule.isActive) scopeMap[key] = rule.id
        return SourceRuleRecordResult.INSERTED
    }

    override fun remove(id: SourceRuleId, removedAt: Long): SourceRuleRemovalResult {
        val existing = rulesById[id] ?: return SourceRuleRemovalResult.NOT_FOUND
        if (!existing.isActive) {
            return SourceRuleRemovalResult.ALREADY_REMOVED
        }
        val updated = existing.copy(
            removedAt = removedAt,
            updatedAt = maxOf(existing.updatedAt, removedAt),
        )
        rulesById[id] = updated
        return SourceRuleRemovalResult.REMOVED
    }

    override fun getById(id: SourceRuleId): SourceRule? = rulesById[id]

    override fun replaceForTarget(targetHabit: HabitRef, replacement: SourceRule?, endedAt: Long) {
        require(replacement == null || replacement.targetHabit == targetHabit)
        val oldRules = rulesById.toMap()
        val oldScopes = scopeMap.toMap()
        try {
            getByTargetHabit(targetHabit).forEach { remove(it.id, endedAt) }
            if (replacement != null) record(replacement)
        } catch (error: Throwable) {
            rulesById.clear(); rulesById.putAll(oldRules)
            scopeMap.clear(); scopeMap.putAll(oldScopes)
            throw error
        }
    }

    override fun getByTargetHabit(targetHabit: HabitRef, includeRemoved: Boolean): List<SourceRule> =
        rulesById.values
            .filter { it.targetHabit == targetHabit && (includeRemoved || it.isActive) }
            .sortedWith(order)

    override fun getByContainer(containerId: ContainerId, includeRemoved: Boolean): List<SourceRule> =
        rulesById.values
            .filter { it.sourceContainerId == containerId && (includeRemoved || it.isActive) }
            .sortedWith(order)

    override fun getAll(includeRemoved: Boolean): List<SourceRule> =
        rulesById.values
            .filter { includeRemoved || it.isActive }
            .sortedWith(order)

    override fun getActiveRules(): List<SourceRule> =
        rulesById.values
            .filter { it.isActive }
            .sortedWith(order)
}
