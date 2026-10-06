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
        if (existingId != null) {
            val existing = rulesById[existingId]
            if (existing != null) {
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
        scopeMap[key] = rule.id
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
