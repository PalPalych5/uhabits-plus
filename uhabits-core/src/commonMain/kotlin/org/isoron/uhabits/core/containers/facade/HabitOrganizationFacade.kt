/*
 * Copyright (C) 2016-2026 Álinson Santos Xavier <git@axavier.org>
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

package org.isoron.uhabits.core.containers.facade

import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.containers.OrganizationResult
import org.isoron.uhabits.core.containers.OrganizationRevision
import org.isoron.uhabits.core.models.PaletteColor

data class OrganizationalItem(
    val key: String,
    val name: String,
    val color: PaletteColor?,
    val icon: String? = null,
    val path: String? = null,
    val isRoot: Boolean = true,
    val isUnassigned: Boolean = false
)

data class HabitPlacementInfo(
    val habitUuid: String,
    val containerId: ContainerId?,
    val containerName: String?,
    val containerPath: List<String>,
    val rootContainerId: ContainerId?,
    val rootContainerName: String?,
    val isUnassigned: Boolean
)

/**
 * Typed facade between existing Habit UI / application code and organizational model.
 *
 * Provides a clean abstraction separating:
 * - [OrganizationAuthorityMode.LEGACY]: HabitBlock is the authority.
 * - [OrganizationAuthorityMode.CONTAINER_LOCAL]: Container + HabitPlacement are the authority.
 */
interface HabitOrganizationFacade {
    val mode: OrganizationAuthorityMode

    /**
     * Returns current placement information for the habit.
     */
    fun getPlacementInfo(habitUuid: String, legacyBlockId: Long? = null): HabitPlacementInfo

    /**
     * Returns the root grouping key for BY_SPHERE sorting and list item separators.
     * In LEGACY mode: returns legacyBlockId (Long?).
     * In CONTAINER_LOCAL mode: returns root container UUID (String?) or null for unassigned.
     */
    fun getRootGroupKey(habitUuid: String, legacyBlockId: Long? = null): Any?

    /**
     * Returns human-readable root grouping name.
     */
    fun getRootGroupName(habitUuid: String, legacyBlockId: Long? = null): String?

    /**
     * Returns list of selectable organizational containers.
     * In LEGACY mode: returns existing HabitBlocks.
     * In CONTAINER_LOCAL mode: returns active Containers with hierarchical paths.
     */
    fun availableContainers(): List<OrganizationalItem>

    /**
     * Returns list of root containers / spheres for section-level filtering or bulk actions.
     */
    fun rootContainers(): List<OrganizationalItem>

    /**
     * Returns the set of Habit UUIDs belonging to the specified container/sphere.
     * If subtree == true, includes all descendants; if false, only direct habits.
     */
    fun getHabitUuidsForContainer(containerKey: String, subtree: Boolean = true): Set<String>

    /**
     * Moves a habit to targetContainer (or unassigns if null).
     * In LEGACY mode: no-op or legacy update.
     * In CONTAINER_LOCAL mode: records placement in HabitPlacements and HabitPlacementHistory via OrganizationService,
     * and NEVER writes to HabitBlocks or HabitExtensions.block_id.
     */
    fun placeHabit(
        habitUuid: String,
        targetContainerId: ContainerId?,
        expectedRevision: OrganizationRevision? = null,
        opUuid: String? = null
    ): OrganizationResult<Unit>

    /**
     * Creates initial placement for a newly created habit in CONTAINER_LOCAL mode.
     */
    fun placeNewHabit(
        habitUuid: String,
        targetContainerId: ContainerId?,
        opUuid: String? = null
    ): OrganizationResult<Unit>
}
