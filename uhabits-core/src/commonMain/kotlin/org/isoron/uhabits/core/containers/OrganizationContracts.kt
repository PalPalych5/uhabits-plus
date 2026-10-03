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

import org.isoron.uhabits.core.models.PaletteColor

// ============================================================================
// Requests
// ============================================================================

data class CreateContainer(
    val name: String,
    val parentId: ContainerId? = null,
    val color: PaletteColor? = null,
    val icon: String? = null,
    val siblingOrder: Int? = null,
    val opUuid: String? = null,
    val expectedRevision: OrganizationRevision? = null,
    val explicitId: ContainerId? = null,
)

data class EditContainer(
    val id: ContainerId,
    val name: String,
    val color: PaletteColor?,
    val icon: String?,
    val opUuid: String? = null,
    val expectedRevision: OrganizationRevision,
)

data class MoveContainer(
    val id: ContainerId,
    val newParentId: ContainerId?,
    val newSiblingOrder: Int? = null,
    val opUuid: String? = null,
    val expectedRevision: OrganizationRevision,
)

data class ReorderContainers(
    val parentId: ContainerId?,
    val orderedIds: List<ContainerId>,
    val opUuid: String? = null,
    val expectedRevision: OrganizationRevision,
)

data class PlaceHabit(
    val habit: HabitRef,
    val targetContainerId: ContainerId?,
    val localOrder: Int? = null,
    val origin: PlacementOrigin = PlacementOrigin.USER_CHANGE,
    val opUuid: String? = null,
    val expectedRevision: OrganizationRevision,
)

data class ReorderPlacedHabits(
    val containerId: ContainerId?,
    val orderedHabits: List<HabitRef>,
    val opUuid: String? = null,
    val expectedRevision: OrganizationRevision,
)

data class ArchiveContainer(
    val id: ContainerId,
    val opUuid: String? = null,
    val expectedRevision: OrganizationRevision,
)

data class UnarchiveContainer(
    val id: ContainerId,
    val opUuid: String? = null,
    val expectedRevision: OrganizationRevision,
)

data class DeleteEmptyContainer(
    val id: ContainerId,
    val opUuid: String? = null,
    val expectedRevision: OrganizationRevision,
)

// ============================================================================
// Results and Errors
// ============================================================================

sealed interface OrganizationResult<out T> {
    val isSuccess: Boolean
        get() = this is Success

    val isFailure: Boolean
        get() = this is Failure

    fun getOrNull(): T? = when (this) {
        is Success -> value
        is Failure -> null
    }

    fun getOrThrow(): T = when (this) {
        is Success -> value
        is Failure -> error("Operation failed with error: $error")
    }

    data class Success<T>(val value: T) : OrganizationResult<T>
    data class Failure(val error: OrganizationError) : OrganizationResult<Nothing>
}

sealed interface OrganizationError {
    data class ContainerNotFound(val id: ContainerId) : OrganizationError
    data class HabitNotFound(val habit: HabitRef) : OrganizationError
    data class CannotMoveIntoSelf(val id: ContainerId) : OrganizationError
    data class CannotMoveIntoDescendant(val id: ContainerId, val targetParentId: ContainerId) : OrganizationError
    data class TargetParentArchived(val parentId: ContainerId) : OrganizationError
    data class TargetParentDeleted(val parentId: ContainerId) : OrganizationError
    data class TargetContainerDeleted(val id: ContainerId) : OrganizationError
    data class TargetContainerArchived(val id: ContainerId) : OrganizationError
    data class ContainerNotEmpty(
        val id: ContainerId,
        val activeOrArchivedChildrenCount: Int,
        val placedHabitsCount: Int,
    ) : OrganizationError
    data class StaleRevision(
        val expectedRevision: OrganizationRevision,
        val currentRevision: OrganizationRevision,
    ) : OrganizationError
    data class OperationPayloadMismatch(val opUuid: String) : OrganizationError
    data class BlankName(val reason: String = "Container name cannot be blank") : OrganizationError
    data class InvalidReorderList(val reason: String) : OrganizationError
    data class ContainerAlreadyDeleted(val id: ContainerId) : OrganizationError
    data class StorageError(val message: String, val cause: Throwable? = null) : OrganizationError
}

// ============================================================================
// Ports & Query Interfaces
// ============================================================================

fun interface Clock {
    fun nowMillis(): Long
}

fun interface IdGenerator {
    fun nextId(): String
}

fun interface HabitIdentityLookup {
    fun habitExists(habit: HabitRef): Boolean
}

interface ContainerQueries {
    fun find(id: ContainerId, includeDeleted: Boolean = false): Container?
    fun roots(includeArchived: Boolean = false): List<Container>
    fun children(parent: ContainerId, includeArchived: Boolean = false): List<Container>
    fun path(id: ContainerId): ContainerPath
    fun subtreeIds(id: ContainerId): Set<ContainerId>
    fun isEffectivelyArchived(id: ContainerId): Boolean
    fun search(query: String): List<ContainerSearchHit>
}

interface HabitPlacementQueries {
    fun current(habit: HabitRef): HabitPlacement?
    fun directHabits(container: ContainerId?): List<HabitRef>
    fun subtreeHabits(container: ContainerId): Set<HabitRef>
    fun locationAtRevision(habit: HabitRef, at: OrganizationRevision): HistoricalLocation
}

interface OrganizationStore {
    fun readState(): OrganizationStateSnapshot
    fun getContainer(id: ContainerId, includeDeleted: Boolean = false): Container?
    fun getAllContainers(includeDeleted: Boolean = false): List<Container>
    fun getHabitPlacement(habit: HabitRef): HabitPlacement?
    fun getAllHabitPlacements(): List<HabitPlacement>
    fun getChangeByOpUuid(opUuid: String): OrganizationChangeRecord?
    fun getContainerHistoryAtRevision(id: ContainerId, revision: OrganizationRevision): Container?
    fun getHabitPlacementHistoryAtRevision(habit: HabitRef, revision: OrganizationRevision): HabitPlacement?
}

interface OrganizationTransaction : OrganizationStore {
    fun saveContainer(container: Container)
    fun saveHabitPlacement(placement: HabitPlacement)
    fun recordChange(change: OrganizationChangeRecord)
    fun recordContainerHistory(container: Container, revision: OrganizationRevision)
    fun recordHabitPlacementHistory(placement: HabitPlacement, revision: OrganizationRevision)
    fun updateState(newRevision: OrganizationRevision)
}

interface UnitOfWork {
    fun <T> executeInTransaction(block: (OrganizationTransaction) -> T): T
}

interface OrganizationService {
    val containerQueries: ContainerQueries
    val placementQueries: HabitPlacementQueries

    fun currentRevision(): OrganizationRevision

    fun create(request: CreateContainer): OrganizationResult<Container>
    fun edit(request: EditContainer): OrganizationResult<Container>
    fun move(request: MoveContainer): OrganizationResult<Unit>
    fun reorder(request: ReorderContainers): OrganizationResult<Unit>
    fun placeHabit(request: PlaceHabit): OrganizationResult<Unit>
    fun reorderHabits(request: ReorderPlacedHabits): OrganizationResult<Unit>
    fun archive(request: ArchiveContainer): OrganizationResult<Unit>
    fun archive(id: ContainerId, expectedRevision: OrganizationRevision, opUuid: String? = null): OrganizationResult<Unit>
    fun unarchive(request: UnarchiveContainer): OrganizationResult<Unit>
    fun unarchive(id: ContainerId, expectedRevision: OrganizationRevision, opUuid: String? = null): OrganizationResult<Unit>
    fun deleteEmpty(request: DeleteEmptyContainer): OrganizationResult<Unit>
    fun deleteEmpty(id: ContainerId, expectedRevision: OrganizationRevision, opUuid: String? = null): OrganizationResult<Unit>
}
