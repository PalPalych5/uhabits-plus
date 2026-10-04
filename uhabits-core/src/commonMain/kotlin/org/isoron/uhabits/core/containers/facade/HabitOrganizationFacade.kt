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

import org.isoron.uhabits.core.containers.Container
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.containers.ContainerQueries
import org.isoron.uhabits.core.containers.HabitPlacementQueries
import org.isoron.uhabits.core.containers.HabitVisualIdentity
import org.isoron.uhabits.core.containers.HabitVisualIdentityStore
import org.isoron.uhabits.core.containers.IconRef
import org.isoron.uhabits.core.containers.OrganizationError
import org.isoron.uhabits.core.containers.OrganizationResult
import org.isoron.uhabits.core.containers.OrganizationRevision
import org.isoron.uhabits.core.containers.OrganizationService
import org.isoron.uhabits.core.models.PaletteColor

data class ContainerOverview(
    val container: Container,
    val directChildrenCount: Int,
    val directHabitCount: Int,
    val totalHabitCount: Int,
)

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
    val containerQueries: ContainerQueries? get() = null
    val habitPlacementQueries: HabitPlacementQueries? get() = null
    val organizationService: OrganizationService? get() = null

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

    val visualIdentityStore: HabitVisualIdentityStore? get() = null

    fun childrenOf(containerId: ContainerId, includeArchived: Boolean = false): List<Container> = emptyList()
    fun rootContainerModels(): List<Container> = emptyList()
    fun archivedRoots(): List<Container> = emptyList()
    fun containerOverview(containerId: ContainerId): ContainerOverview? = null
    fun containerPath(containerId: ContainerId): List<Container> = emptyList()
    fun getHabitVisualIdentity(habitUuid: String): HabitVisualIdentity? = null
    fun setHabitVisualIdentity(habitUuid: String, iconRef: IconRef) {}
    fun removeHabitVisualIdentity(habitUuid: String) {}
    fun archiveContainer(id: ContainerId): OrganizationResult<Unit> =
        OrganizationResult.Failure(OrganizationError.StorageError("Not supported in current mode"))
    fun unarchiveContainer(id: ContainerId): OrganizationResult<Unit> =
        OrganizationResult.Failure(OrganizationError.StorageError("Not supported in current mode"))
    fun moveContainer(id: ContainerId, newParentId: ContainerId?): OrganizationResult<Unit> =
        OrganizationResult.Failure(OrganizationError.StorageError("Not supported in current mode"))
    fun createContainer(
        name: String,
        parentId: ContainerId? = null,
        color: PaletteColor? = null,
        icon: String? = null
    ): OrganizationResult<Container> =
        OrganizationResult.Failure(OrganizationError.StorageError("Not supported in current mode"))
    fun editContainer(
        id: ContainerId,
        name: String,
        color: PaletteColor? = null,
        icon: String? = null
    ): OrganizationResult<Container> =
        OrganizationResult.Failure(OrganizationError.StorageError("Not supported in current mode"))
}
