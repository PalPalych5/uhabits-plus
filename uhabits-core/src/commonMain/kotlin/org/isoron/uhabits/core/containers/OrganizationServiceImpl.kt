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

class OrganizationServiceImpl(
    private val store: OrganizationStore,
    private val unitOfWork: UnitOfWork,
    override val containerQueries: ContainerQueries,
    override val placementQueries: HabitPlacementQueries,
    private val clock: Clock,
    private val idGenerator: IdGenerator,
    private val habitIdentityLookup: HabitIdentityLookup,
) : OrganizationService {

    // ========================================================================
    // Create Container
    // ========================================================================

    override fun create(request: CreateContainer): OrganizationResult<Container> {
        val nameValidation = TreePolicy.validateName(request.name)
        if (nameValidation is OrganizationResult.Failure) {
            return nameValidation
        }
        val trimmedName = nameValidation.getOrThrow()

        val currentState = store.readState()
        if (request.expectedRevision != null && request.expectedRevision != currentState.currentRevision) {
            return OrganizationResult.Failure(
                OrganizationError.StaleRevision(request.expectedRevision, currentState.currentRevision)
            )
        }

        val canonicalPayload = TreePolicy.payloadForCreate(request)

        if (request.opUuid != null) {
            val existingChange = store.getChangeByOpUuid(request.opUuid)
            if (existingChange != null) {
                return if (existingChange.commandPayload == canonicalPayload) {
                    val existing = if (request.explicitId != null) {
                        containerQueries.find(request.explicitId, includeDeleted = true)
                    } else {
                        store.getAllContainers(includeDeleted = true).firstOrNull { it.revision == existingChange.revision }
                    }
                    if (existing != null) {
                        OrganizationResult.Success(existing)
                    } else {
                        OrganizationResult.Failure(OrganizationError.StorageError("Idempotent container not found"))
                    }
                } else {
                    OrganizationResult.Failure(OrganizationError.OperationPayloadMismatch(request.opUuid))
                }
            }
        }

        val targetId = request.explicitId ?: ContainerId(idGenerator.nextId())

        return unitOfWork.executeInTransaction { tx ->
            if (request.parentId != null) {
                val parent = tx.getContainer(request.parentId)
                    ?: return@executeInTransaction OrganizationResult.Failure(
                        OrganizationError.ContainerNotFound(request.parentId)
                    )
                if (parent.deletedAt != null) {
                    return@executeInTransaction OrganizationResult.Failure(
                        OrganizationError.TargetParentDeleted(request.parentId)
                    )
                }
                if (TreePolicy.isEffectivelyArchived(request.parentId) { tx.getContainer(it) }) {
                    return@executeInTransaction OrganizationResult.Failure(
                        OrganizationError.TargetParentArchived(request.parentId)
                    )
                }
            }

            val nowMillis = clock.nowMillis()
            val newRevision = OrganizationRevision(tx.readState().currentRevision.value + 1)
            val effectiveOpUuid = request.opUuid ?: idGenerator.nextId()

            val siblings = tx.getAllContainers(includeDeleted = false)
                .filter { it.parentId == request.parentId }
                .sortedWith(compareBy({ it.siblingOrder }, { it.id.value }))
                .toMutableList()

            val finalOrder: Int
            if (request.siblingOrder == null || request.siblingOrder >= siblings.size) {
                finalOrder = siblings.size
            } else {
                val insertIndex = request.siblingOrder.coerceAtLeast(0)
                finalOrder = insertIndex
                for (i in insertIndex until siblings.size) {
                    val shifted = siblings[i].copy(
                        siblingOrder = i + 1,
                        updatedAt = nowMillis,
                        revision = newRevision,
                    )
                    tx.saveContainer(shifted)
                    tx.recordContainerHistory(shifted, newRevision)
                }
            }

            val container = Container(
                id = targetId,
                parentId = request.parentId,
                name = trimmedName,
                color = request.color,
                icon = request.icon,
                siblingOrder = finalOrder,
                isArchived = false,
                createdAt = nowMillis,
                updatedAt = nowMillis,
                deletedAt = null,
                revision = newRevision,
            )

            tx.saveContainer(container)
            tx.recordContainerHistory(container, newRevision)
            tx.recordChange(
                OrganizationChangeRecord(
                    revision = newRevision,
                    opUuid = effectiveOpUuid,
                    recordedAt = nowMillis,
                    operationType = "CREATE_CONTAINER",
                    origin = "USER_CHANGE",
                    commandPayload = canonicalPayload,
                )
            )
            tx.updateState(newRevision)

            OrganizationResult.Success(container)
        }
    }

    // ========================================================================
    // Edit Container
    // ========================================================================

    override fun edit(request: EditContainer): OrganizationResult<Container> {
        val nameValidation = TreePolicy.validateName(request.name)
        if (nameValidation is OrganizationResult.Failure) {
            return nameValidation
        }
        val trimmedName = nameValidation.getOrThrow()

        val currentState = store.readState()
        if (request.expectedRevision != currentState.currentRevision) {
            return OrganizationResult.Failure(
                OrganizationError.StaleRevision(request.expectedRevision, currentState.currentRevision)
            )
        }

        val canonicalPayload = TreePolicy.payloadForEdit(request)
        if (request.opUuid != null) {
            val existingChange = store.getChangeByOpUuid(request.opUuid)
            if (existingChange != null) {
                return if (existingChange.commandPayload == canonicalPayload) {
                    val existing = containerQueries.find(request.id)
                    if (existing != null) {
                        OrganizationResult.Success(existing)
                    } else {
                        OrganizationResult.Failure(OrganizationError.ContainerNotFound(request.id))
                    }
                } else {
                    OrganizationResult.Failure(OrganizationError.OperationPayloadMismatch(request.opUuid))
                }
            }
        }

        return unitOfWork.executeInTransaction { tx ->
            val existing = tx.getContainer(request.id)
                ?: return@executeInTransaction OrganizationResult.Failure(
                    OrganizationError.ContainerNotFound(request.id)
                )

            if (existing.deletedAt != null) {
                return@executeInTransaction OrganizationResult.Failure(
                    OrganizationError.ContainerAlreadyDeleted(request.id)
                )
            }

            val nowMillis = clock.nowMillis()
            val newRevision = OrganizationRevision(tx.readState().currentRevision.value + 1)
            val effectiveOpUuid = request.opUuid ?: idGenerator.nextId()

            val updated = existing.copy(
                name = trimmedName,
                color = request.color,
                icon = request.icon,
                updatedAt = nowMillis,
                revision = newRevision,
            )

            tx.saveContainer(updated)
            tx.recordContainerHistory(updated, newRevision)
            tx.recordChange(
                OrganizationChangeRecord(
                    revision = newRevision,
                    opUuid = effectiveOpUuid,
                    recordedAt = nowMillis,
                    operationType = "EDIT_CONTAINER",
                    origin = "USER_CHANGE",
                    commandPayload = canonicalPayload,
                )
            )
            tx.updateState(newRevision)

            OrganizationResult.Success(updated)
        }
    }

    // ========================================================================
    // Move Container
    // ========================================================================

    override fun move(request: MoveContainer): OrganizationResult<Unit> {
        val currentState = store.readState()
        if (request.expectedRevision != currentState.currentRevision) {
            return OrganizationResult.Failure(
                OrganizationError.StaleRevision(request.expectedRevision, currentState.currentRevision)
            )
        }

        val canonicalPayload = TreePolicy.payloadForMove(request)
        if (request.opUuid != null) {
            val existingChange = store.getChangeByOpUuid(request.opUuid)
            if (existingChange != null) {
                return if (existingChange.commandPayload == canonicalPayload) {
                    OrganizationResult.Success(Unit)
                } else {
                    OrganizationResult.Failure(OrganizationError.OperationPayloadMismatch(request.opUuid))
                }
            }
        }

        return unitOfWork.executeInTransaction { tx ->
            val moveError = TreePolicy.validateMove(
                id = request.id,
                newParentId = request.newParentId,
                getContainer = { tx.getContainer(it) },
                getChildren = { pid -> tx.getAllContainers().filter { it.parentId == pid && it.deletedAt == null } },
            )
            if (moveError != null) {
                return@executeInTransaction OrganizationResult.Failure(moveError)
            }

            val node = tx.getContainer(request.id)!!
            val oldParentId = node.parentId
            val nowMillis = clock.nowMillis()
            val newRevision = OrganizationRevision(tx.readState().currentRevision.value + 1)
            val effectiveOpUuid = request.opUuid ?: idGenerator.nextId()

            if (oldParentId == request.newParentId) {
                // Reorder within the same parent
                val siblings = tx.getAllContainers(includeDeleted = false)
                    .filter { it.parentId == oldParentId }
                    .sortedWith(compareBy({ it.siblingOrder }, { it.id.value }))
                    .toMutableList()

                val currentIndex = siblings.indexOfFirst { it.id == request.id }
                if (currentIndex >= 0) {
                    siblings.removeAt(currentIndex)
                    val targetIndex = (request.newSiblingOrder ?: siblings.size).coerceIn(0, siblings.size)
                    siblings.add(targetIndex, node)

                    for ((idx, sibling) in siblings.withIndex()) {
                        val reordered = sibling.copy(
                            siblingOrder = idx,
                            updatedAt = nowMillis,
                            revision = newRevision,
                        )
                        tx.saveContainer(reordered)
                        tx.recordContainerHistory(reordered, newRevision)
                    }
                }
            } else {
                // Dense renumber remaining in old parent
                val oldSiblings = tx.getAllContainers(includeDeleted = false)
                    .filter { it.parentId == oldParentId && it.id != request.id }
                    .sortedWith(compareBy({ it.siblingOrder }, { it.id.value }))

                for ((idx, sibling) in oldSiblings.withIndex()) {
                    if (sibling.siblingOrder != idx) {
                        val renumbered = sibling.copy(
                            siblingOrder = idx,
                            updatedAt = nowMillis,
                            revision = newRevision,
                        )
                        tx.saveContainer(renumbered)
                        tx.recordContainerHistory(renumbered, newRevision)
                    }
                }

                // Dense insert into new parent
                val newSiblings = tx.getAllContainers(includeDeleted = false)
                    .filter { it.parentId == request.newParentId && it.id != request.id }
                    .sortedWith(compareBy({ it.siblingOrder }, { it.id.value }))
                    .toMutableList()

                val targetIndex = (request.newSiblingOrder ?: newSiblings.size).coerceIn(0, newSiblings.size)
                newSiblings.add(targetIndex, node)

                for ((idx, sibling) in newSiblings.withIndex()) {
                    val updated = sibling.copy(
                        parentId = request.newParentId,
                        siblingOrder = idx,
                        updatedAt = nowMillis,
                        revision = newRevision,
                    )
                    tx.saveContainer(updated)
                    tx.recordContainerHistory(updated, newRevision)
                }
            }

            tx.recordChange(
                OrganizationChangeRecord(
                    revision = newRevision,
                    opUuid = effectiveOpUuid,
                    recordedAt = nowMillis,
                    operationType = "MOVE_CONTAINER",
                    origin = "USER_CHANGE",
                    commandPayload = canonicalPayload,
                )
            )
            tx.updateState(newRevision)

            OrganizationResult.Success(Unit)
        }
    }

    // ========================================================================
    // Reorder Containers
    // ========================================================================

    override fun reorder(request: ReorderContainers): OrganizationResult<Unit> {
        val currentState = store.readState()
        if (request.expectedRevision != currentState.currentRevision) {
            return OrganizationResult.Failure(
                OrganizationError.StaleRevision(request.expectedRevision, currentState.currentRevision)
            )
        }

        val canonicalPayload = TreePolicy.payloadForReorder(request)
        if (request.opUuid != null) {
            val existingChange = store.getChangeByOpUuid(request.opUuid)
            if (existingChange != null) {
                return if (existingChange.commandPayload == canonicalPayload) {
                    OrganizationResult.Success(Unit)
                } else {
                    OrganizationResult.Failure(OrganizationError.OperationPayloadMismatch(request.opUuid))
                }
            }
        }

        return unitOfWork.executeInTransaction { tx ->
            val siblings = tx.getAllContainers(includeDeleted = false)
                .filter { it.parentId == request.parentId }

            val siblingIds = siblings.map { it.id }.toSet()
            val requestedIds = request.orderedIds.toSet()

            if (request.orderedIds.size != siblings.size || siblingIds != requestedIds) {
                return@executeInTransaction OrganizationResult.Failure(
                    OrganizationError.InvalidReorderList(
                        "Ordered IDs must exactly match existing active sibling containers"
                    )
                )
            }

            val nowMillis = clock.nowMillis()
            val newRevision = OrganizationRevision(tx.readState().currentRevision.value + 1)
            val effectiveOpUuid = request.opUuid ?: idGenerator.nextId()

            val siblingsById = siblings.associateBy { it.id }
            for ((index, id) in request.orderedIds.withIndex()) {
                val container = siblingsById[id]!!
                val reordered = container.copy(
                    siblingOrder = index,
                    updatedAt = nowMillis,
                    revision = newRevision,
                )
                tx.saveContainer(reordered)
                tx.recordContainerHistory(reordered, newRevision)
            }

            tx.recordChange(
                OrganizationChangeRecord(
                    revision = newRevision,
                    opUuid = effectiveOpUuid,
                    recordedAt = nowMillis,
                    operationType = "REORDER_CONTAINERS",
                    origin = "USER_CHANGE",
                    commandPayload = canonicalPayload,
                )
            )
            tx.updateState(newRevision)

            OrganizationResult.Success(Unit)
        }
    }

    // ========================================================================
    // Place Habit
    // ========================================================================

    override fun placeHabit(request: PlaceHabit): OrganizationResult<Unit> {
        if (!habitIdentityLookup.habitExists(request.habit)) {
            return OrganizationResult.Failure(OrganizationError.HabitNotFound(request.habit))
        }

        val currentState = store.readState()
        if (request.expectedRevision != currentState.currentRevision) {
            return OrganizationResult.Failure(
                OrganizationError.StaleRevision(request.expectedRevision, currentState.currentRevision)
            )
        }

        val canonicalPayload = TreePolicy.payloadForPlaceHabit(request)
        if (request.opUuid != null) {
            val existingChange = store.getChangeByOpUuid(request.opUuid)
            if (existingChange != null) {
                return if (existingChange.commandPayload == canonicalPayload) {
                    OrganizationResult.Success(Unit)
                } else {
                    OrganizationResult.Failure(OrganizationError.OperationPayloadMismatch(request.opUuid))
                }
            }
        }

        return unitOfWork.executeInTransaction { tx ->
            if (request.targetContainerId != null) {
                val targetContainer = tx.getContainer(request.targetContainerId)
                    ?: return@executeInTransaction OrganizationResult.Failure(
                        OrganizationError.ContainerNotFound(request.targetContainerId)
                    )
                if (targetContainer.deletedAt != null) {
                    return@executeInTransaction OrganizationResult.Failure(
                        OrganizationError.TargetContainerDeleted(request.targetContainerId)
                    )
                }
                if (TreePolicy.isEffectivelyArchived(request.targetContainerId) { tx.getContainer(it) }) {
                    return@executeInTransaction OrganizationResult.Failure(
                        OrganizationError.TargetContainerArchived(request.targetContainerId)
                    )
                }
            }

            val nowMillis = clock.nowMillis()
            val newRevision = OrganizationRevision(tx.readState().currentRevision.value + 1)
            val effectiveOpUuid = request.opUuid ?: idGenerator.nextId()

            val oldPlacement = tx.getHabitPlacement(request.habit)
            val oldContainerId = oldPlacement?.containerId

            if (oldContainerId != null && oldContainerId != request.targetContainerId) {
                // Dense renumber remaining habits in old container
                val remainingInOld = tx.getAllHabitPlacements()
                    .filter { it.containerId == oldContainerId && it.habit != request.habit }
                    .sortedWith(compareBy({ it.localOrder }, { it.habit.uuid }))

                for ((idx, placement) in remainingInOld.withIndex()) {
                    if (placement.localOrder != idx) {
                        val renumbered = placement.copy(
                            localOrder = idx,
                            revision = newRevision,
                        )
                        tx.saveHabitPlacement(renumbered)
                        tx.recordHabitPlacementHistory(renumbered, newRevision)
                    }
                }
            }

            // Dense insertion in target container (or unassigned list)
            val targetHabits = tx.getAllHabitPlacements()
                .filter { it.containerId == request.targetContainerId && it.habit != request.habit }
                .sortedWith(compareBy({ it.localOrder }, { it.habit.uuid }))
                .toMutableList()

            val newOrder = (request.localOrder ?: targetHabits.size).coerceIn(0, targetHabits.size)
            val newPlacement = HabitPlacement(
                habit = request.habit,
                containerId = request.targetContainerId,
                localOrder = newOrder,
                origin = request.origin,
                revision = newRevision,
            )
            targetHabits.add(newOrder, newPlacement)

            for ((idx, placement) in targetHabits.withIndex()) {
                val updated = placement.copy(localOrder = idx, revision = newRevision)
                tx.saveHabitPlacement(updated)
                tx.recordHabitPlacementHistory(updated, newRevision)
            }

            tx.recordChange(
                OrganizationChangeRecord(
                    revision = newRevision,
                    opUuid = effectiveOpUuid,
                    recordedAt = nowMillis,
                    operationType = "PLACE_HABIT",
                    origin = request.origin.name,
                    commandPayload = canonicalPayload,
                )
            )
            tx.updateState(newRevision)

            OrganizationResult.Success(Unit)
        }
    }

    // ========================================================================
    // Reorder Placed Habits
    // ========================================================================

    override fun reorderHabits(request: ReorderPlacedHabits): OrganizationResult<Unit> {
        val currentState = store.readState()
        if (request.expectedRevision != currentState.currentRevision) {
            return OrganizationResult.Failure(
                OrganizationError.StaleRevision(request.expectedRevision, currentState.currentRevision)
            )
        }

        val canonicalPayload = TreePolicy.payloadForReorderHabits(request)
        if (request.opUuid != null) {
            val existingChange = store.getChangeByOpUuid(request.opUuid)
            if (existingChange != null) {
                return if (existingChange.commandPayload == canonicalPayload) {
                    OrganizationResult.Success(Unit)
                } else {
                    OrganizationResult.Failure(OrganizationError.OperationPayloadMismatch(request.opUuid))
                }
            }
        }

        return unitOfWork.executeInTransaction { tx ->
            val currentPlacements = tx.getAllHabitPlacements()
                .filter { it.containerId == request.containerId }

            val currentHabits = currentPlacements.map { it.habit }.toSet()
            val requestedHabits = request.orderedHabits.toSet()

            if (request.orderedHabits.size != currentPlacements.size || currentHabits != requestedHabits) {
                return@executeInTransaction OrganizationResult.Failure(
                    OrganizationError.InvalidReorderList(
                        "Ordered habits must exactly match current placed habits in container"
                    )
                )
            }

            val nowMillis = clock.nowMillis()
            val newRevision = OrganizationRevision(tx.readState().currentRevision.value + 1)
            val effectiveOpUuid = request.opUuid ?: idGenerator.nextId()

            val placementsByHabit = currentPlacements.associateBy { it.habit }
            for ((index, habitRef) in request.orderedHabits.withIndex()) {
                val placement = placementsByHabit[habitRef]!!
                val reordered = placement.copy(
                    localOrder = index,
                    revision = newRevision,
                )
                tx.saveHabitPlacement(reordered)
                tx.recordHabitPlacementHistory(reordered, newRevision)
            }

            tx.recordChange(
                OrganizationChangeRecord(
                    revision = newRevision,
                    opUuid = effectiveOpUuid,
                    recordedAt = nowMillis,
                    operationType = "REORDER_HABITS",
                    origin = "USER_CHANGE",
                    commandPayload = canonicalPayload,
                )
            )
            tx.updateState(newRevision)

            OrganizationResult.Success(Unit)
        }
    }

    // ========================================================================
    // Archive / Unarchive / Delete
    // ========================================================================

    override fun archive(request: ArchiveContainer): OrganizationResult<Unit> =
        archive(request.id, request.expectedRevision, request.opUuid)

    override fun archive(
        id: ContainerId,
        expectedRevision: OrganizationRevision,
        opUuid: String?,
    ): OrganizationResult<Unit> {
        val currentState = store.readState()
        if (expectedRevision != currentState.currentRevision) {
            return OrganizationResult.Failure(
                OrganizationError.StaleRevision(expectedRevision, currentState.currentRevision)
            )
        }

        val canonicalPayload = TreePolicy.payloadForArchive(id)
        if (opUuid != null) {
            val existingChange = store.getChangeByOpUuid(opUuid)
            if (existingChange != null) {
                return if (existingChange.commandPayload == canonicalPayload) {
                    OrganizationResult.Success(Unit)
                } else {
                    OrganizationResult.Failure(OrganizationError.OperationPayloadMismatch(opUuid))
                }
            }
        }

        return unitOfWork.executeInTransaction { tx ->
            val existing = tx.getContainer(id)
                ?: return@executeInTransaction OrganizationResult.Failure(
                    OrganizationError.ContainerNotFound(id)
                )

            if (existing.deletedAt != null) {
                return@executeInTransaction OrganizationResult.Failure(
                    OrganizationError.ContainerAlreadyDeleted(id)
                )
            }

            val nowMillis = clock.nowMillis()
            val newRevision = OrganizationRevision(tx.readState().currentRevision.value + 1)
            val effectiveOpUuid = opUuid ?: idGenerator.nextId()

            val updated = existing.copy(
                isArchived = true,
                updatedAt = nowMillis,
                revision = newRevision,
            )

            tx.saveContainer(updated)
            tx.recordContainerHistory(updated, newRevision)
            tx.recordChange(
                OrganizationChangeRecord(
                    revision = newRevision,
                    opUuid = effectiveOpUuid,
                    recordedAt = nowMillis,
                    operationType = "ARCHIVE_CONTAINER",
                    origin = "USER_CHANGE",
                    commandPayload = canonicalPayload,
                )
            )
            tx.updateState(newRevision)

            OrganizationResult.Success(Unit)
        }
    }

    override fun unarchive(request: UnarchiveContainer): OrganizationResult<Unit> =
        unarchive(request.id, request.expectedRevision, request.opUuid)

    override fun unarchive(
        id: ContainerId,
        expectedRevision: OrganizationRevision,
        opUuid: String?,
    ): OrganizationResult<Unit> {
        val currentState = store.readState()
        if (expectedRevision != currentState.currentRevision) {
            return OrganizationResult.Failure(
                OrganizationError.StaleRevision(expectedRevision, currentState.currentRevision)
            )
        }

        val canonicalPayload = TreePolicy.payloadForUnarchive(id)
        if (opUuid != null) {
            val existingChange = store.getChangeByOpUuid(opUuid)
            if (existingChange != null) {
                return if (existingChange.commandPayload == canonicalPayload) {
                    OrganizationResult.Success(Unit)
                } else {
                    OrganizationResult.Failure(OrganizationError.OperationPayloadMismatch(opUuid))
                }
            }
        }

        return unitOfWork.executeInTransaction { tx ->
            val existing = tx.getContainer(id)
                ?: return@executeInTransaction OrganizationResult.Failure(
                    OrganizationError.ContainerNotFound(id)
                )

            if (existing.deletedAt != null) {
                return@executeInTransaction OrganizationResult.Failure(
                    OrganizationError.ContainerAlreadyDeleted(id)
                )
            }

            val nowMillis = clock.nowMillis()
            val newRevision = OrganizationRevision(tx.readState().currentRevision.value + 1)
            val effectiveOpUuid = opUuid ?: idGenerator.nextId()

            val updated = existing.copy(
                isArchived = false,
                updatedAt = nowMillis,
                revision = newRevision,
            )

            tx.saveContainer(updated)
            tx.recordContainerHistory(updated, newRevision)
            tx.recordChange(
                OrganizationChangeRecord(
                    revision = newRevision,
                    opUuid = effectiveOpUuid,
                    recordedAt = nowMillis,
                    operationType = "UNARCHIVE_CONTAINER",
                    origin = "USER_CHANGE",
                    commandPayload = canonicalPayload,
                )
            )
            tx.updateState(newRevision)

            OrganizationResult.Success(Unit)
        }
    }

    override fun deleteEmpty(request: DeleteEmptyContainer): OrganizationResult<Unit> =
        deleteEmpty(request.id, request.expectedRevision, request.opUuid)

    override fun deleteEmpty(
        id: ContainerId,
        expectedRevision: OrganizationRevision,
        opUuid: String?,
    ): OrganizationResult<Unit> {
        val currentState = store.readState()
        if (expectedRevision != currentState.currentRevision) {
            return OrganizationResult.Failure(
                OrganizationError.StaleRevision(expectedRevision, currentState.currentRevision)
            )
        }

        val canonicalPayload = TreePolicy.payloadForDeleteEmpty(id)
        if (opUuid != null) {
            val existingChange = store.getChangeByOpUuid(opUuid)
            if (existingChange != null) {
                return if (existingChange.commandPayload == canonicalPayload) {
                    OrganizationResult.Success(Unit)
                } else {
                    OrganizationResult.Failure(OrganizationError.OperationPayloadMismatch(opUuid))
                }
            }
        }

        return unitOfWork.executeInTransaction { tx ->
            val deleteError = TreePolicy.validateDeleteEmpty(
                id = id,
                getContainer = { tx.getContainer(it) },
                getChildren = { pid, inc ->
                    tx.getAllContainers(includeDeleted = false).filter { it.parentId == pid && (inc || !it.isArchived) }
                },
                getPlacedHabits = { cid ->
                    tx.getAllHabitPlacements().filter { it.containerId == cid }.map { it.habit }
                },
            )
            if (deleteError != null) {
                return@executeInTransaction OrganizationResult.Failure(deleteError)
            }

            val existing = tx.getContainer(id)!!
            val parentId = existing.parentId
            val nowMillis = clock.nowMillis()
            val newRevision = OrganizationRevision(tx.readState().currentRevision.value + 1)
            val effectiveOpUuid = opUuid ?: idGenerator.nextId()

            // Dense renumber remaining siblings in parent
            val remainingSiblings = tx.getAllContainers(includeDeleted = false)
                .filter { it.parentId == parentId && it.id != id }
                .sortedWith(compareBy({ it.siblingOrder }, { it.id.value }))

            for ((idx, sibling) in remainingSiblings.withIndex()) {
                if (sibling.siblingOrder != idx) {
                    val renumbered = sibling.copy(
                        siblingOrder = idx,
                        updatedAt = nowMillis,
                        revision = newRevision,
                    )
                    tx.saveContainer(renumbered)
                    tx.recordContainerHistory(renumbered, newRevision)
                }
            }

            val deleted = existing.copy(
                deletedAt = nowMillis,
                updatedAt = nowMillis,
                revision = newRevision,
            )

            tx.saveContainer(deleted)
            tx.recordContainerHistory(deleted, newRevision)
            tx.recordChange(
                OrganizationChangeRecord(
                    revision = newRevision,
                    opUuid = effectiveOpUuid,
                    recordedAt = nowMillis,
                    operationType = "DELETE_EMPTY_CONTAINER",
                    origin = "USER_CHANGE",
                    commandPayload = canonicalPayload,
                )
            )
            tx.updateState(newRevision)

            OrganizationResult.Success(Unit)
        }
    }
}
