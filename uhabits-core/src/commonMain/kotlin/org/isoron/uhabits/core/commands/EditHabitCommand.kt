/*
 * Copyright (C) 2016-2025 Álinson Santos Xavier <git@axavier.org>
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
package org.isoron.uhabits.core.commands

import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.containers.IconRef
import org.isoron.uhabits.core.containers.facade.HabitOrganizationFacade
import org.isoron.uhabits.core.containers.facade.OrganizationAuthorityMode
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitList
import org.isoron.uhabits.core.models.HabitNotFoundException
import org.isoron.uhabits.core.models.sqlite.SQLiteHabitList

data class EditHabitCommand(
    val habitList: HabitList,
    val habitId: Long,
    val modified: Habit,
    val targetContainerId: ContainerId? = null,
    val changeContainer: Boolean = false,
    val organizationFacade: HabitOrganizationFacade? = null,
    val visualIdentity: IconRef? = null
) : Command {
    override fun run() {
        val habit = habitList.getById(habitId) ?: throw HabitNotFoundException()
        val originalBlockId = habit.blockId
        habit.copyFrom(modified)
        if (organizationFacade?.mode == OrganizationAuthorityMode.CONTAINER_LOCAL) {
            habit.blockId = originalBlockId
        }
        val habitUuid = habit.uuid ?: modified.uuid
        if (habitUuid != null && !changeContainer) {
            org.isoron.uhabits.core.models.projection.EffectiveReadRevision.withMutationScope(setOf(org.isoron.uhabits.core.containers.HabitRef(habitUuid))) {
                habitList.update(habit)
            }
        } else {
            habitList.update(habit)
        }
        habit.recompute()
        habit.observable.notifyListeners()
        habitList.resort()
        if (organizationFacade?.mode == OrganizationAuthorityMode.CONTAINER_LOCAL) {
            val habitUuid = habit.uuid ?: modified.uuid
            if (changeContainer) {
                if (habitUuid != null) {
                    organizationFacade.placeHabit(habitUuid, targetContainerId)
                }
            }
            if (habitUuid != null && visualIdentity != null) {
                if (visualIdentity is IconRef.None) {
                    organizationFacade.removeHabitVisualIdentity(habitUuid)
                } else {
                    organizationFacade.setHabitVisualIdentity(habitUuid, visualIdentity)
                }
            }
        } else {
            (habitList as? SQLiteHabitList)?.syncManager?.enqueueHabitUpdate(habit)
        }
    }
}
