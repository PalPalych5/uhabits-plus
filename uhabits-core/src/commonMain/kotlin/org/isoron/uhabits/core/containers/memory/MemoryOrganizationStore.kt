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

package org.isoron.uhabits.core.containers.memory

import org.isoron.uhabits.core.containers.Container
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.containers.ContainerPath
import org.isoron.uhabits.core.containers.ContainerQueries
import org.isoron.uhabits.core.containers.ContainerSearchHit
import org.isoron.uhabits.core.containers.HabitPlacement
import org.isoron.uhabits.core.containers.HabitPlacementQueries
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.containers.HistoricalLocation
import org.isoron.uhabits.core.containers.OrganizationChangeRecord
import org.isoron.uhabits.core.containers.OrganizationResult
import org.isoron.uhabits.core.containers.OrganizationRevision
import org.isoron.uhabits.core.containers.OrganizationStateSnapshot
import org.isoron.uhabits.core.containers.OrganizationStore
import org.isoron.uhabits.core.containers.OrganizationTransaction
import org.isoron.uhabits.core.containers.PlacementOrigin
import org.isoron.uhabits.core.containers.TreePolicy
import org.isoron.uhabits.core.containers.UnitOfWork

class MemoryOrganizationStore(
    private val initialCutoverRevision: OrganizationRevision = OrganizationRevision(1L),
    private val initialCutoverAt: Long = 0L,
) : OrganizationStore, UnitOfWork, ContainerQueries, HabitPlacementQueries {

    private var cutoverRevision: OrganizationRevision = initialCutoverRevision
    private var currentRevision: OrganizationRevision = initialCutoverRevision
    private var cutoverAt: Long = initialCutoverAt

    private val containers = mutableMapOf<ContainerId, Container>()
    private val habitPlacements = mutableMapOf<HabitRef, HabitPlacement>()
    private val changesByOpUuid = mutableMapOf<String, OrganizationChangeRecord>()
    private val changesByRevision = mutableMapOf<OrganizationRevision, OrganizationChangeRecord>()
    private val containerHistory = mutableListOf<Pair<OrganizationRevision, Container>>()
    private val habitPlacementHistory = mutableListOf<Pair<OrganizationRevision, HabitPlacement>>()

    // ========================================================================
    // Seeding helpers (for fixtures and tests)
    // ========================================================================

    fun seedBaseline(
        revision: OrganizationRevision = initialCutoverRevision,
        recordedAt: Long = initialCutoverAt,
        containersList: List<Container> = emptyList(),
        placementsList: List<HabitPlacement> = emptyList(),
    ) {
        cutoverRevision = revision
        currentRevision = revision
        cutoverAt = recordedAt

        for (c in containersList) {
            containers[c.id] = c
            containerHistory.add(revision to c)
        }
        for (p in placementsList) {
            habitPlacements[p.habit] = p
            habitPlacementHistory.add(revision to p)
        }
        val baselineRecord = OrganizationChangeRecord(
            revision = revision,
            opUuid = "baseline-cutover",
            recordedAt = recordedAt,
            operationType = "BASELINE",
            origin = "BASELINE",
            commandPayload = "BASELINE_SNAPSHOT",
        )
        changesByRevision[revision] = baselineRecord
        changesByOpUuid[baselineRecord.opUuid] = baselineRecord
    }

    // ========================================================================
    // OrganizationStore
    // ========================================================================

    override fun readState(): OrganizationStateSnapshot =
        OrganizationStateSnapshot(
            cutoverRevision = cutoverRevision,
            currentRevision = currentRevision,
            cutoverAt = cutoverAt,
        )

    override fun getContainer(id: ContainerId, includeDeleted: Boolean): Container? {
        val container = containers[id] ?: return null
        if (!includeDeleted && container.deletedAt != null) return null
        return container
    }

    override fun getAllContainers(includeDeleted: Boolean): List<Container> =
        containers.values.filter { includeDeleted || it.deletedAt == null }

    override fun getHabitPlacement(habit: HabitRef): HabitPlacement? =
        habitPlacements[habit]

    override fun getAllHabitPlacements(): List<HabitPlacement> =
        habitPlacements.values.toList()

    override fun getChangeByOpUuid(opUuid: String): OrganizationChangeRecord? =
        changesByOpUuid[opUuid]

    override fun getContainerHistoryAtRevision(
        id: ContainerId,
        revision: OrganizationRevision,
    ): Container? =
        containerHistory
            .filter { it.first <= revision && it.second.id == id }
            .maxByOrNull { it.first }
            ?.second

    override fun getHabitPlacementHistoryAtRevision(
        habit: HabitRef,
        revision: OrganizationRevision,
    ): HabitPlacement? =
        habitPlacementHistory
            .filter { it.first <= revision && it.second.habit == habit }
            .maxByOrNull { it.first }
            ?.second

    // ========================================================================
    // ContainerQueries
    // ========================================================================

    override fun find(id: ContainerId, includeDeleted: Boolean): Container? =
        getContainer(id, includeDeleted)

    override fun roots(includeArchived: Boolean): List<Container> =
        containers.values
            .filter { it.parentId == null && it.deletedAt == null && (includeArchived || !it.isArchived) }
            .sortedWith(compareBy({ it.siblingOrder }, { it.id.value }))

    override fun children(parent: ContainerId, includeArchived: Boolean): List<Container> =
        containers.values
            .filter { it.parentId == parent && it.deletedAt == null && (includeArchived || !it.isArchived) }
            .sortedWith(compareBy({ it.siblingOrder }, { it.id.value }))

    override fun path(id: ContainerId): ContainerPath =
        TreePolicy.buildPath(id, currentRevision) { containers[it] }

    override fun subtreeIds(id: ContainerId): Set<ContainerId> =
        TreePolicy.collectSubtreeIds(id) { parentId ->
            children(parentId, includeArchived = true)
        }

    override fun isEffectivelyArchived(id: ContainerId): Boolean =
        TreePolicy.isEffectivelyArchived(id) { containers[it] }

    override fun search(query: String): List<ContainerSearchHit> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        return containers.values
            .filter { it.deletedAt == null && it.name.contains(trimmed, ignoreCase = true) }
            .sortedWith(compareBy({ it.name.lowercase() }, { it.id.value }))
            .map { container ->
                ContainerSearchHit(
                    container = container,
                    path = path(container.id),
                )
            }
    }

    // ========================================================================
    // HabitPlacementQueries
    // ========================================================================

    override fun current(habit: HabitRef): HabitPlacement? =
        getHabitPlacement(habit)

    override fun directHabits(container: ContainerId?): List<HabitRef> =
        habitPlacements.values
            .filter { it.containerId == container }
            .sortedWith(compareBy({ it.localOrder }, { it.habit.uuid }))
            .map { it.habit }

    override fun subtreeHabits(container: ContainerId): Set<HabitRef> {
        val containerIds = subtreeIds(container) + container
        return habitPlacements.values
            .filter { it.containerId != null && it.containerId in containerIds }
            .map { it.habit }
            .toSet()
    }

    override fun locationAtRevision(
        habit: HabitRef,
        at: OrganizationRevision,
    ): HistoricalLocation {
        if (at < cutoverRevision) {
            return HistoricalLocation.UnknownBeforeCutover
        }

        val placementAtRevision = getHabitPlacementHistoryAtRevision(habit, at)
            ?: return HistoricalLocation.UnknownBeforeCutover

        val containerId = placementAtRevision.containerId
        if (containerId == null) {
            return if (placementAtRevision.origin == PlacementOrigin.UNKNOWN_LEGACY) {
                HistoricalLocation.UnknownLegacyAssignment
            } else {
                HistoricalLocation.Unassigned
            }
        }

        // Reconstruct container path at the specified historical revision
        val nodes = mutableListOf<ContainerId>()
        var current: ContainerId? = containerId
        val visited = mutableSetOf<ContainerId>()

        while (current != null) {
            if (!visited.add(current)) break
            nodes.add(current)
            val histContainer = getContainerHistoryAtRevision(current, at) ?: break
            current = histContainer.parentId
        }
        nodes.reverse()
        return HistoricalLocation.Known(ContainerPath(nodes = nodes, revision = at))
    }

    // ========================================================================
    // UnitOfWork & Transaction
    // ========================================================================

    override fun <T> executeInTransaction(block: (OrganizationTransaction) -> T): T {
        val tx = MemoryTransaction(
            currentRevision = currentRevision,
            cutoverRevision = cutoverRevision,
            cutoverAt = cutoverAt,
            initialContainers = containers,
            initialPlacements = habitPlacements,
            initialChangesByOpUuid = changesByOpUuid,
            initialChangesByRevision = changesByRevision,
            initialContainerHistory = containerHistory,
            initialHabitPlacementHistory = habitPlacementHistory,
        )

        val result: T
        try {
            result = block(tx)
        } catch (t: Throwable) {
            // Rollback: discard tx changes
            throw t
        }

        // If result is a failed OrganizationResult, rollback without commit
        if (result is OrganizationResult<*> && result.isFailure) {
            return result
        }

        // Commit transaction changes
        currentRevision = tx.currentRevision
        containers.clear()
        containers.putAll(tx.containers)
        habitPlacements.clear()
        habitPlacements.putAll(tx.habitPlacements)
        changesByOpUuid.clear()
        changesByOpUuid.putAll(tx.changesByOpUuid)
        changesByRevision.clear()
        changesByRevision.putAll(tx.changesByRevision)
        containerHistory.clear()
        containerHistory.addAll(tx.containerHistory)
        habitPlacementHistory.clear()
        habitPlacementHistory.addAll(tx.habitPlacementHistory)

        return result
    }

    private class MemoryTransaction(
        var currentRevision: OrganizationRevision,
        val cutoverRevision: OrganizationRevision,
        val cutoverAt: Long,
        initialContainers: Map<ContainerId, Container>,
        initialPlacements: Map<HabitRef, HabitPlacement>,
        initialChangesByOpUuid: Map<String, OrganizationChangeRecord>,
        initialChangesByRevision: Map<OrganizationRevision, OrganizationChangeRecord>,
        initialContainerHistory: List<Pair<OrganizationRevision, Container>>,
        initialHabitPlacementHistory: List<Pair<OrganizationRevision, HabitPlacement>>,
    ) : OrganizationTransaction {

        val containers = initialContainers.toMutableMap()
        val habitPlacements = initialPlacements.toMutableMap()
        val changesByOpUuid = initialChangesByOpUuid.toMutableMap()
        val changesByRevision = initialChangesByRevision.toMutableMap()
        val containerHistory = initialContainerHistory.toMutableList()
        val habitPlacementHistory = initialHabitPlacementHistory.toMutableList()

        override fun readState(): OrganizationStateSnapshot =
            OrganizationStateSnapshot(
                cutoverRevision = cutoverRevision,
                currentRevision = currentRevision,
                cutoverAt = cutoverAt,
            )

        override fun getContainer(id: ContainerId, includeDeleted: Boolean): Container? {
            val container = containers[id] ?: return null
            if (!includeDeleted && container.deletedAt != null) return null
            return container
        }

        override fun getAllContainers(includeDeleted: Boolean): List<Container> =
            containers.values.filter { includeDeleted || it.deletedAt == null }

        override fun getHabitPlacement(habit: HabitRef): HabitPlacement? =
            habitPlacements[habit]

        override fun getAllHabitPlacements(): List<HabitPlacement> =
            habitPlacements.values.toList()

        override fun getChangeByOpUuid(opUuid: String): OrganizationChangeRecord? =
            changesByOpUuid[opUuid]

        override fun getContainerHistoryAtRevision(
            id: ContainerId,
            revision: OrganizationRevision,
        ): Container? =
            containerHistory
                .filter { it.first <= revision && it.second.id == id }
                .maxByOrNull { it.first }
                ?.second

        override fun getHabitPlacementHistoryAtRevision(
            habit: HabitRef,
            revision: OrganizationRevision,
        ): HabitPlacement? =
            habitPlacementHistory
                .filter { it.first <= revision && it.second.habit == habit }
                .maxByOrNull { it.first }
                ?.second

        override fun saveContainer(container: Container) {
            containers[container.id] = container
        }

        override fun saveHabitPlacement(placement: HabitPlacement) {
            habitPlacements[placement.habit] = placement
        }

        override fun recordChange(change: OrganizationChangeRecord) {
            changesByOpUuid[change.opUuid] = change
            changesByRevision[change.revision] = change
        }

        override fun recordContainerHistory(container: Container, revision: OrganizationRevision) {
            containerHistory.add(revision to container)
        }

        override fun recordHabitPlacementHistory(
            placement: HabitPlacement,
            revision: OrganizationRevision,
        ) {
            habitPlacementHistory.add(revision to placement)
        }

        override fun updateState(newRevision: OrganizationRevision) {
            currentRevision = newRevision
        }
    }
}
