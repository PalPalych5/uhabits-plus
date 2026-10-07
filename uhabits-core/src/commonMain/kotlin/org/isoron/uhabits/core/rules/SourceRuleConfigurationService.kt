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
