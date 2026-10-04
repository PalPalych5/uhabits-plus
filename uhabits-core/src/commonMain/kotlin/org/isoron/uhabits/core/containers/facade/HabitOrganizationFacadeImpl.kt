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

import org.isoron.uhabits.core.containers.ArchiveContainer
import org.isoron.uhabits.core.containers.Container
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.containers.ContainerQueries
import org.isoron.uhabits.core.containers.CreateContainer
import org.isoron.uhabits.core.containers.EditContainer
import org.isoron.uhabits.core.containers.HabitPlacementQueries
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.containers.HabitVisualIdentity
import org.isoron.uhabits.core.containers.HabitVisualIdentityStore
import org.isoron.uhabits.core.containers.IconRef
import org.isoron.uhabits.core.containers.MoveContainer
import org.isoron.uhabits.core.containers.OrganizationError
import org.isoron.uhabits.core.containers.OrganizationResult
import org.isoron.uhabits.core.containers.OrganizationRevision
import org.isoron.uhabits.core.containers.OrganizationService
import org.isoron.uhabits.core.containers.PlaceHabit
import org.isoron.uhabits.core.containers.UnarchiveContainer
import org.isoron.uhabits.core.database.HabitBlockRepository
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitBlock
import org.isoron.uhabits.core.models.PaletteColor
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class HabitOrganizationFacadeImpl(
    override val mode: OrganizationAuthorityMode = OrganizationAuthorityMode.LEGACY,
    override val containerQueries: ContainerQueries? = null,
    override val habitPlacementQueries: HabitPlacementQueries? = null,
    override val organizationService: OrganizationService? = null,
    override val visualIdentityStore: HabitVisualIdentityStore? = null,
    private val habitBlockRepository: HabitBlockRepository? = null,
    private val blocksProvider: (() -> List<HabitBlock>)? = null,
    private val allHabitsProvider: (() -> List<Habit>)? = null
) : HabitOrganizationFacade {

    override fun getPlacementInfo(habitUuid: String, legacyBlockId: Long?): HabitPlacementInfo {
        if (mode == OrganizationAuthorityMode.CONTAINER_LOCAL) {
            val queries = habitPlacementQueries ?: return unassignedPlacement(habitUuid)
            val placement = queries.current(HabitRef(habitUuid))
            val containerId = placement?.containerId ?: return unassignedPlacement(habitUuid)
            val cQueries = containerQueries ?: return unassignedPlacement(habitUuid)
            val container = cQueries.find(containerId) ?: return unassignedPlacement(habitUuid)
            val pathNodes = cQueries.path(containerId).nodes
            val pathNames = pathNodes.mapNotNull { cQueries.find(it)?.name }
            val rootId = pathNodes.firstOrNull()
            val rootName = rootId?.let { cQueries.find(it)?.name }
            return HabitPlacementInfo(
                habitUuid = habitUuid,
                containerId = containerId,
                containerName = container.name,
                containerPath = pathNames,
                rootContainerId = rootId,
                rootContainerName = rootName,
                isUnassigned = false
            )
        } else {
            if (legacyBlockId == null) return unassignedPlacement(habitUuid)
            val block = findLegacyBlock(legacyBlockId) ?: return unassignedPlacement(habitUuid)
            return HabitPlacementInfo(
                habitUuid = habitUuid,
                containerId = null,
                containerName = block.name,
                containerPath = listOf(block.name),
                rootContainerId = null,
                rootContainerName = block.name,
                isUnassigned = false
            )
        }
    }

    override fun getRootGroupKey(habitUuid: String, legacyBlockId: Long?): Any? {
        if (mode == OrganizationAuthorityMode.CONTAINER_LOCAL) {
            val queries = habitPlacementQueries ?: return null
            val placement = queries.current(HabitRef(habitUuid)) ?: return null
            val containerId = placement.containerId ?: return null
            val cQueries = containerQueries ?: return null
            val pathNodes = cQueries.path(containerId).nodes
            return pathNodes.firstOrNull()?.value
        } else {
            return legacyBlockId
        }
    }

    override fun getRootGroupName(habitUuid: String, legacyBlockId: Long?): String? {
        if (mode == OrganizationAuthorityMode.CONTAINER_LOCAL) {
            val rootKey = getRootGroupKey(habitUuid, legacyBlockId) as? String ?: return null
            return containerQueries?.find(ContainerId(rootKey))?.name
        } else {
            if (legacyBlockId == null) return null
            return findLegacyBlock(legacyBlockId)?.name
        }
    }

    override fun availableContainers(): List<OrganizationalItem> {
        if (mode == OrganizationAuthorityMode.CONTAINER_LOCAL) {
            val cQueries = containerQueries ?: return emptyList()
            val roots = cQueries.roots(includeArchived = false).sortedBy { it.siblingOrder }
            val result = mutableListOf<OrganizationalItem>()

            fun traverse(c: Container) {
                val pathNodes = cQueries.path(c.id).nodes
                val pathNames = pathNodes.mapNotNull { cQueries.find(it)?.name }
                result.add(
                    OrganizationalItem(
                        key = c.id.value,
                        name = c.name,
                        color = c.color,
                        icon = c.icon,
                        path = pathNames.joinToString(" / "),
                        isRoot = (c.parentId == null),
                        isUnassigned = false
                    )
                )
                val children = cQueries.children(c.id, includeArchived = false).sortedBy { it.siblingOrder }
                for (child in children) {
                    traverse(child)
                }
            }

            for (root in roots) {
                traverse(root)
            }
            return result
        } else {
            val blocks = getLegacyBlocks()
            return blocks.map {
                OrganizationalItem(
                    key = it.id.toString(),
                    name = it.name,
                    color = it.color,
                    icon = it.icon,
                    path = it.name,
                    isRoot = true,
                    isUnassigned = false
                )
            }
        }
    }

    override fun rootContainers(): List<OrganizationalItem> {
        if (mode == OrganizationAuthorityMode.CONTAINER_LOCAL) {
            val cQueries = containerQueries ?: return emptyList()
            return cQueries.roots(includeArchived = false).sortedBy { it.siblingOrder }.map { c ->
                OrganizationalItem(
                    key = c.id.value,
                    name = c.name,
                    color = c.color,
                    icon = c.icon,
                    path = c.name,
                    isRoot = true,
                    isUnassigned = false
                )
            }
        } else {
            return availableContainers()
        }
    }

    override fun getHabitUuidsForContainer(containerKey: String, subtree: Boolean): Set<String> {
        if (mode == OrganizationAuthorityMode.CONTAINER_LOCAL) {
            val queries = habitPlacementQueries ?: return emptySet()
            val cid = ContainerId(containerKey)
            return if (subtree) {
                queries.subtreeHabits(cid).map { it.uuid }.toSet()
            } else {
                queries.directHabits(cid).map { it.uuid }.toSet()
            }
        } else {
            val blockId = containerKey.toLongOrNull() ?: return emptySet()
            val habits = allHabitsProvider?.invoke() ?: emptyList()
            return habits.filter { it.blockId == blockId }.mapNotNull { it.uuid }.toSet()
        }
    }

    override fun placeHabit(
        habitUuid: String,
        targetContainerId: ContainerId?,
        expectedRevision: OrganizationRevision?,
        opUuid: String?
    ): OrganizationResult<Unit> {
        if (mode == OrganizationAuthorityMode.CONTAINER_LOCAL) {
            val service = organizationService ?: return OrganizationResult.Failure(
                OrganizationError.StorageError("OrganizationService not configured")
            )
            val rev = expectedRevision ?: service.currentRevision()
            val op = opUuid ?: Uuid.random().toHexString()
            return service.placeHabit(
                PlaceHabit(
                    habit = HabitRef(habitUuid),
                    targetContainerId = targetContainerId,
                    expectedRevision = rev,
                    opUuid = op
                )
            )
        } else {
            return OrganizationResult.Success(Unit)
        }
    }

    override fun placeNewHabit(
        habitUuid: String,
        targetContainerId: ContainerId?,
        opUuid: String?
    ): OrganizationResult<Unit> {
        if (mode == OrganizationAuthorityMode.CONTAINER_LOCAL) {
            val service = organizationService ?: return OrganizationResult.Failure(
                OrganizationError.StorageError("OrganizationService not configured")
            )
            val rev = service.currentRevision()
            val op = opUuid ?: Uuid.random().toHexString()
            return service.placeHabit(
                PlaceHabit(
                    habit = HabitRef(habitUuid),
                    targetContainerId = targetContainerId,
                    expectedRevision = rev,
                    opUuid = op
                )
            )
        } else {
            return OrganizationResult.Success(Unit)
        }
    }

    override fun childrenOf(containerId: ContainerId, includeArchived: Boolean): List<Container> {
        val cQueries = containerQueries ?: return emptyList()
        return cQueries.children(containerId, includeArchived)
    }

    override fun rootContainerModels(): List<Container> {
        val cQueries = containerQueries ?: return emptyList()
        return cQueries.roots(includeArchived = false).sortedBy { it.siblingOrder }
    }

    override fun archivedRoots(): List<Container> {
        val cQueries = containerQueries ?: return emptyList()
        val result = mutableListOf<Container>()
        val queue = ArrayDeque<Container>()
        queue.addAll(cQueries.roots(includeArchived = true))
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (current.isArchived) {
                result.add(current)
            } else {
                queue.addAll(cQueries.children(current.id, includeArchived = true))
            }
        }
        return result
    }

    override fun containerOverview(containerId: ContainerId): ContainerOverview? {
        val cQueries = containerQueries ?: return null
        val pQueries = habitPlacementQueries ?: return null
        val c = cQueries.find(containerId) ?: return null
        val children = cQueries.children(containerId, includeArchived = false)
        val directHabits = pQueries.directHabits(containerId)
        val subtreeHabits = pQueries.subtreeHabits(containerId)
        return ContainerOverview(
            container = c,
            directChildrenCount = children.size,
            directHabitCount = directHabits.size,
            totalHabitCount = subtreeHabits.size
        )
    }

    override fun containerPath(containerId: ContainerId): List<Container> {
        val cQueries = containerQueries ?: return emptyList()
        val pathNodes = cQueries.path(containerId).nodes
        return pathNodes.mapNotNull { cQueries.find(it) }
    }

    override fun getHabitVisualIdentity(habitUuid: String): HabitVisualIdentity? {
        val store = visualIdentityStore ?: return null
        val iconRef = store.get(habitUuid) ?: return null
        return HabitVisualIdentity(habitUuid, iconRef)
    }

    override fun setHabitVisualIdentity(habitUuid: String, iconRef: IconRef) {
        visualIdentityStore?.set(habitUuid, iconRef)
    }

    override fun removeHabitVisualIdentity(habitUuid: String) {
        visualIdentityStore?.remove(habitUuid)
    }

    override fun archiveContainer(id: ContainerId): OrganizationResult<Unit> {
        val service = organizationService
            ?: return OrganizationResult.Failure(OrganizationError.StorageError("Organization service unavailable"))
        return service.archive(
            ArchiveContainer(
                id = id,
                expectedRevision = service.currentRevision()
            )
        )
    }

    override fun unarchiveContainer(id: ContainerId): OrganizationResult<Unit> {
        val service = organizationService
            ?: return OrganizationResult.Failure(OrganizationError.StorageError("Organization service unavailable"))
        return service.unarchive(
            UnarchiveContainer(
                id = id,
                expectedRevision = service.currentRevision()
            )
        )
    }

    override fun moveContainer(id: ContainerId, newParentId: ContainerId?): OrganizationResult<Unit> {
        val service = organizationService
            ?: return OrganizationResult.Failure(OrganizationError.StorageError("Organization service unavailable"))
        return service.move(
            MoveContainer(
                id = id,
                newParentId = newParentId,
                expectedRevision = service.currentRevision()
            )
        )
    }

    override fun createContainer(
        name: String,
        parentId: ContainerId?,
        color: PaletteColor?,
        icon: String?
    ): OrganizationResult<Container> {
        val service = organizationService
            ?: return OrganizationResult.Failure(OrganizationError.StorageError("Organization service unavailable"))
        return service.create(
            CreateContainer(
                name = name,
                parentId = parentId,
                color = color,
                icon = icon,
                expectedRevision = service.currentRevision()
            )
        )
    }

    override fun editContainer(
        id: ContainerId,
        name: String,
        color: PaletteColor?,
        icon: String?
    ): OrganizationResult<Container> {
        val service = organizationService
            ?: return OrganizationResult.Failure(OrganizationError.StorageError("Organization service unavailable"))
        return service.edit(
            EditContainer(
                id = id,
                name = name,
                color = color,
                icon = icon,
                expectedRevision = service.currentRevision()
            )
        )
    }

    private fun findLegacyBlock(blockId: Long): HabitBlock? {
        return getLegacyBlocks().firstOrNull { it.id == blockId }
    }

    private fun getLegacyBlocks(): List<HabitBlock> {
        blocksProvider?.invoke()?.let { return it }
        return habitBlockRepository?.findAll()?.map {
            HabitBlock(
                id = it.id,
                name = it.name,
                color = PaletteColor(it.color),
                icon = it.icon,
                position = it.position,
                isArchived = it.isArchived
            )
        } ?: emptyList()
    }

    private fun unassignedPlacement(habitUuid: String): HabitPlacementInfo = HabitPlacementInfo(
        habitUuid = habitUuid,
        containerId = null,
        containerName = null,
        containerPath = emptyList(),
        rootContainerId = null,
        rootContainerName = null,
        isUnassigned = true
    )
}
