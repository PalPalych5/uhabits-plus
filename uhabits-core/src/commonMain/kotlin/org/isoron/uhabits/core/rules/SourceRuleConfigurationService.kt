/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.core.rules

import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.containers.HabitRef

/** One native Focus configuration per Habit. Edits and re-enabling always begin a new interval. */
class SourceRuleConfigurationService(
    private val repository: SourceRuleRepository,
    private val idProvider: () -> SourceRuleId,
) {
    fun current(targetHabit: HabitRef): SourceRule? = repository.getByTargetHabit(targetHabit).filter { it.isActive }.singleOrNull()

    fun configure(
        targetHabit: HabitRef,
        targetInfo: TargetHabitInfo,
        sourceContainerId: ContainerId?,
        includeDescendants: Boolean,
        now: Long,
    ): SourceRule? {
        require(now >= 0)
        if (sourceContainerId != null) HabitTimeValueConverter.validateTargetHabit(
            targetInfo.isNumerical, targetInfo.unit, targetInfo.name, targetHabit.uuid,
        )
        val active = repository.getByTargetHabit(targetHabit).filter { it.isActive }
        val current = active.singleOrNull()
        if (current != null && current.sourceContainerId == sourceContainerId &&
            current.includeDescendants == includeDescendants) return current
        val replacement = sourceContainerId?.let {
            SourceRule(idProvider(), sourceContainerId = it, includeDescendants = includeDescendants,
                targetHabit = targetHabit, effectiveFrom = now, createdAt = now, updatedAt = now)
        }
        repository.replaceForTarget(targetHabit, replacement, now)
        return replacement
    }
}
