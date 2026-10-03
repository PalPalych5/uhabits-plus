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

package org.isoron.uhabits.core.containers

object TreePolicy {

    fun validateName(name: String): OrganizationResult<String> {
        val trimmed = name.trim()
        return if (trimmed.isEmpty()) {
            OrganizationResult.Failure(OrganizationError.BlankName())
        } else {
            OrganizationResult.Success(trimmed)
        }
    }

    fun isEffectivelyArchived(
        id: ContainerId,
        getContainer: (ContainerId) -> Container?,
    ): Boolean {
        var currentId: ContainerId? = id
        val visited = mutableSetOf<ContainerId>()
        while (currentId != null) {
            if (!visited.add(currentId)) {
                // Cycle detected in storage, break safely
                break
            }
            val container = getContainer(currentId) ?: break
            if (container.isArchived) {
                return true
            }
            currentId = container.parentId
        }
        return false
    }

    fun buildPath(
        id: ContainerId,
        currentRevision: OrganizationRevision,
        getContainer: (ContainerId) -> Container?,
    ): ContainerPath {
        val nodes = mutableListOf<ContainerId>()
        var currentId: ContainerId? = id
        val visited = mutableSetOf<ContainerId>()
        while (currentId != null) {
            if (!visited.add(currentId)) {
                break
            }
            nodes.add(currentId)
            val container = getContainer(currentId) ?: break
            currentId = container.parentId
        }
        nodes.reverse()
        return ContainerPath(nodes = nodes, revision = currentRevision)
    }

    fun collectSubtreeIds(
        id: ContainerId,
        getChildren: (ContainerId) -> List<Container>,
    ): Set<ContainerId> {
        val subtree = mutableSetOf<ContainerId>()
        val queue = ArrayDeque<ContainerId>()
        queue.add(id)

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val children = getChildren(current)
            for (child in children) {
                if (child.deletedAt == null && subtree.add(child.id)) {
                    queue.add(child.id)
                }
            }
        }
        return subtree
    }

    fun validateMove(
        id: ContainerId,
        newParentId: ContainerId?,
        getContainer: (ContainerId) -> Container?,
        getChildren: (ContainerId) -> List<Container>,
    ): OrganizationError? {
        val node = getContainer(id) ?: return OrganizationError.ContainerNotFound(id)
        if (node.deletedAt != null) {
            return OrganizationError.ContainerAlreadyDeleted(id)
        }

        if (newParentId == null) {
            return null // moving to root is always valid
        }

        if (newParentId == id) {
            return OrganizationError.CannotMoveIntoSelf(id)
        }

        val parent = getContainer(newParentId) ?: return OrganizationError.ContainerNotFound(newParentId)
        if (parent.deletedAt != null) {
            return OrganizationError.TargetParentDeleted(newParentId)
        }

        val subtree = collectSubtreeIds(id, getChildren)
        if (newParentId in subtree) {
            return OrganizationError.CannotMoveIntoDescendant(id, newParentId)
        }

        if (isEffectivelyArchived(newParentId, getContainer)) {
            return OrganizationError.TargetParentArchived(newParentId)
        }

        return null
    }

    fun validateDeleteEmpty(
        id: ContainerId,
        getContainer: (ContainerId) -> Container?,
        getChildren: (ContainerId, includeArchived: Boolean) -> List<Container>,
        getPlacedHabits: (ContainerId) -> List<HabitRef>,
    ): OrganizationError? {
        val node = getContainer(id) ?: return OrganizationError.ContainerNotFound(id)
        if (node.deletedAt != null) {
            return OrganizationError.ContainerAlreadyDeleted(id)
        }

        val children = getChildren(id, true).filter { it.deletedAt == null }
        val habits = getPlacedHabits(id)

        if (children.isNotEmpty() || habits.isNotEmpty()) {
            return OrganizationError.ContainerNotEmpty(
                id = id,
                activeOrArchivedChildrenCount = children.size,
                placedHabitsCount = habits.size,
            )
        }

        return null
    }

    // Canonical payload formatters for local idempotency checking
    fun payloadForCreate(request: CreateContainer): String =
        "CREATE|explicitId=${request.explicitId?.value}|name=${request.name.trim()}|parent=${request.parentId?.value}|color=${request.color?.paletteIndex}|icon=${request.icon}|order=${request.siblingOrder}"

    fun payloadForEdit(request: EditContainer): String =
        "EDIT|id=${request.id.value}|name=${request.name.trim()}|color=${request.color?.paletteIndex}|icon=${request.icon}"

    fun payloadForMove(request: MoveContainer): String =
        "MOVE|id=${request.id.value}|parent=${request.newParentId?.value}|order=${request.newSiblingOrder}"

    fun payloadForReorder(request: ReorderContainers): String =
        "REORDER_CONTAINERS|parent=${request.parentId?.value}|order=${request.orderedIds.joinToString(",") { it.value }}"

    fun payloadForPlaceHabit(request: PlaceHabit): String =
        "PLACE_HABIT|habit=${request.habit.uuid}|container=${request.targetContainerId?.value}|order=${request.localOrder}|origin=${request.origin.name}"

    fun payloadForReorderHabits(request: ReorderPlacedHabits): String =
        "REORDER_HABITS|container=${request.containerId?.value}|order=${request.orderedHabits.joinToString(",") { it.uuid }}"

    fun payloadForArchive(id: ContainerId): String =
        "ARCHIVE|id=${id.value}"

    fun payloadForUnarchive(id: ContainerId): String =
        "UNARCHIVE|id=${id.value}"

    fun payloadForDeleteEmpty(id: ContainerId): String =
        "DELETE_EMPTY|id=${id.value}"
}
