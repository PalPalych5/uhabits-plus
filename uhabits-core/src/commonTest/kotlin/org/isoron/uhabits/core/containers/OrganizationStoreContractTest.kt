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

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

abstract class OrganizationStoreContractTest {

    protected abstract suspend fun createStoreAndService(): Pair<OrganizationStore, OrganizationService>

    @Test
    fun testDeepHierarchy_depthAtLeast6() = runTest {
        val (_, service) = createStoreAndService()
        var currentParent: ContainerId? = null
        val createdIds = mutableListOf<ContainerId>()

        for (i in 0..6) {
            val res = service.create(
                CreateContainer(
                    name = "Level $i",
                    parentId = currentParent,
                )
            )
            assertTrue(res.isSuccess)
            val container = res.getOrThrow()
            createdIds.add(container.id)
            currentParent = container.id
        }

        assertEquals(7, createdIds.size)
        val leafId = createdIds.last()
        val path = service.containerQueries.path(leafId)
        assertEquals(createdIds, path.nodes)

        val rootId = createdIds.first()
        val subtreeOfRoot = service.containerQueries.subtreeIds(rootId)
        assertEquals(createdIds.drop(1).toSet(), subtreeOfRoot)
    }

    @Test
    fun testMove_rejectsCyclesAndSelfMove() = runTest {
        val (store, service) = createStoreAndService()
        val root = service.create(CreateContainer(name = "Root")).getOrThrow()
        val child1 = service.create(CreateContainer(name = "Child 1", parentId = root.id)).getOrThrow()
        val child2 = service.create(CreateContainer(name = "Child 2", parentId = child1.id)).getOrThrow()

        // 1. Move into self
        val selfMove = service.move(
            MoveContainer(
                id = root.id,
                newParentId = root.id,
                expectedRevision = store.readState().currentRevision,
            )
        )
        assertTrue(selfMove.isFailure)
        assertTrue((selfMove as OrganizationResult.Failure).error is OrganizationError.CannotMoveIntoSelf)

        // 2. Move root into its descendant child2 (cycle)
        val cycleMove = service.move(
            MoveContainer(
                id = root.id,
                newParentId = child2.id,
                expectedRevision = store.readState().currentRevision,
            )
        )
        assertTrue(cycleMove.isFailure)
        assertTrue((cycleMove as OrganizationResult.Failure).error is OrganizationError.CannotMoveIntoDescendant)

        // 3. Move child2 to root: valid
        val validMove = service.move(
            MoveContainer(
                id = child2.id,
                newParentId = null,
                expectedRevision = store.readState().currentRevision,
            )
        )
        assertTrue(validMove.isSuccess)
        val roots = service.containerQueries.roots()
        assertTrue(roots.any { it.id == child2.id })
        assertEquals(listOf(child2.id), service.containerQueries.path(child2.id).nodes)
    }

    @Test
    fun testMove_rejectsMoveIntoEffectivelyArchivedTarget() = runTest {
        val (store, service) = createStoreAndService()
        val root = service.create(CreateContainer(name = "Root")).getOrThrow()
        val active = service.create(CreateContainer(name = "Active Root")).getOrThrow()

        service.archive(ArchiveContainer(root.id, expectedRevision = store.readState().currentRevision))

        val moveRes = service.move(
            MoveContainer(
                id = active.id,
                newParentId = root.id,
                expectedRevision = store.readState().currentRevision,
            )
        )
        assertTrue(moveRes.isFailure)
        assertTrue((moveRes as OrganizationResult.Failure).error is OrganizationError.TargetParentArchived)
    }

    @Test
    fun testStableUuidAndDuplicateNames() = runTest {
        val (store, service) = createStoreAndService()
        val c1 = service.create(CreateContainer(name = "Inbox")).getOrThrow()
        val c2 = service.create(CreateContainer(name = "Inbox")).getOrThrow()

        assertTrue(c1.id != c2.id)
        val initialId1 = c1.id

        val editRes = service.edit(
            EditContainer(
                id = c1.id,
                name = "Renamed Inbox",
                color = null,
                icon = null,
                expectedRevision = store.readState().currentRevision,
            )
        )
        assertTrue(editRes.isSuccess)
        assertEquals(initialId1, editRes.getOrThrow().id)

        service.archive(ArchiveContainer(c1.id, expectedRevision = store.readState().currentRevision))
        val archived = service.containerQueries.find(initialId1)
        assertNotNull(archived)
        assertEquals(initialId1, archived.id)
        assertTrue(archived.isArchived)
    }

    @Test
    fun testSiblingOrdering_denseSequence() = runTest {
        val (store, service) = createStoreAndService()
        val c0 = service.create(CreateContainer(name = "C0")).getOrThrow()
        val c1 = service.create(CreateContainer(name = "C1")).getOrThrow()
        val c2 = service.create(CreateContainer(name = "C2")).getOrThrow()

        assertEquals(0, c0.siblingOrder)
        assertEquals(1, c1.siblingOrder)
        assertEquals(2, c2.siblingOrder)

        val cNew = service.create(CreateContainer(name = "CNew", siblingOrder = 1)).getOrThrow()
        val roots = service.containerQueries.roots()
        assertEquals(listOf(c0.id, cNew.id, c1.id, c2.id), roots.map { it.id })
        assertEquals(listOf(0, 1, 2, 3), roots.map { it.siblingOrder })

        val reorderRes = service.reorder(
            ReorderContainers(
                parentId = null,
                orderedIds = listOf(c2.id, c0.id, cNew.id, c1.id),
                expectedRevision = store.readState().currentRevision,
            )
        )
        assertTrue(reorderRes.isSuccess)
        val reorderedRoots = service.containerQueries.roots()
        assertEquals(listOf(c2.id, c0.id, cNew.id, c1.id), reorderedRoots.map { it.id })
        assertEquals(listOf(0, 1, 2, 3), reorderedRoots.map { it.siblingOrder })
    }

    @Test
    fun testArchiveAndUnarchive_doesNotRewriteDescendantFlags() = runTest {
        val (store, service) = createStoreAndService()
        val root = service.create(CreateContainer(name = "Root")).getOrThrow()
        val child = service.create(CreateContainer(name = "Child", parentId = root.id)).getOrThrow()
        val grandchild = service.create(CreateContainer(name = "Grandchild", parentId = child.id)).getOrThrow()

        service.archive(ArchiveContainer(child.id, expectedRevision = store.readState().currentRevision))
        assertTrue(service.containerQueries.find(child.id)!!.isArchived)
        assertFalse(service.containerQueries.find(grandchild.id)!!.isArchived)
        assertTrue(service.containerQueries.isEffectivelyArchived(grandchild.id))

        service.archive(ArchiveContainer(root.id, expectedRevision = store.readState().currentRevision))
        assertTrue(service.containerQueries.find(root.id)!!.isArchived)
        assertTrue(service.containerQueries.find(child.id)!!.isArchived)
        assertFalse(service.containerQueries.find(grandchild.id)!!.isArchived)

        service.unarchive(UnarchiveContainer(root.id, expectedRevision = store.readState().currentRevision))
        assertFalse(service.containerQueries.find(root.id)!!.isArchived)
        assertFalse(service.containerQueries.isEffectivelyArchived(root.id))

        assertTrue(service.containerQueries.find(child.id)!!.isArchived)
        assertTrue(service.containerQueries.isEffectivelyArchived(child.id))
        assertTrue(service.containerQueries.isEffectivelyArchived(grandchild.id))
    }

    @Test
    fun testDeleteEmpty_rejectsNonEmptyAndSoftDeletesEmpty() = runTest {
        val (store, service) = createStoreAndService()
        val root = service.create(CreateContainer(name = "Root")).getOrThrow()
        val child = service.create(CreateContainer(name = "Child", parentId = root.id)).getOrThrow()

        val deleteRootRes = service.deleteEmpty(
            DeleteEmptyContainer(id = root.id, expectedRevision = store.readState().currentRevision)
        )
        assertTrue(deleteRootRes.isFailure)
        val err = (deleteRootRes as OrganizationResult.Failure).error
        assertTrue(err is OrganizationError.ContainerNotEmpty)
        assertEquals(1, err.activeOrArchivedChildrenCount)

        val deleteChildRes = service.deleteEmpty(
            DeleteEmptyContainer(id = child.id, expectedRevision = store.readState().currentRevision)
        )
        assertTrue(deleteChildRes.isSuccess)
        assertNull(service.containerQueries.find(child.id, includeDeleted = false))
        val deletedChild = service.containerQueries.find(child.id, includeDeleted = true)
        assertNotNull(deletedChild)
        assertNotNull(deletedChild.deletedAt)

        val deleteRootAgain = service.deleteEmpty(
            DeleteEmptyContainer(id = root.id, expectedRevision = store.readState().currentRevision)
        )
        assertTrue(deleteRootAgain.isSuccess)
        assertNull(service.containerQueries.find(root.id, includeDeleted = false))
    }

    @Test
    fun testIdempotencyAndOperationRetry() = runTest {
        val (store, service) = createStoreAndService()
        val opUuid = "op-contract-42"
        val request = CreateContainer(
            name = "Idempotent Node",
            opUuid = opUuid,
        )

        val res1 = service.create(request)
        assertTrue(res1.isSuccess)
        val c1 = res1.getOrThrow()
        val revAfterFirst = store.readState().currentRevision

        val res2 = service.create(request)
        assertTrue(res2.isSuccess)
        assertEquals(c1.id, res2.getOrThrow().id)
        assertEquals(revAfterFirst, store.readState().currentRevision)

        val conflictingRequest = CreateContainer(
            name = "Conflicting Name",
            opUuid = opUuid,
        )
        val res3 = service.create(conflictingRequest)
        assertTrue(res3.isFailure)
        assertTrue((res3 as OrganizationResult.Failure).error is OrganizationError.OperationPayloadMismatch)
    }

    @Test
    fun testStaleRevisionConflict() = runTest {
        val (store, service) = createStoreAndService()
        val c = service.create(CreateContainer(name = "Initial")).getOrThrow()
        val currentRev = store.readState().currentRevision

        val staleRequest = EditContainer(
            id = c.id,
            name = "Updated",
            color = null,
            icon = null,
            expectedRevision = OrganizationRevision(currentRev.value - 1),
        )
        val res = service.edit(staleRequest)
        assertTrue(res.isFailure)
        assertTrue((res as OrganizationResult.Failure).error is OrganizationError.StaleRevision)
    }

    @Test
    fun testTransactionRollback_noRevisionOrHistoryDrift() = runTest {
        val (store, service) = createStoreAndService()
        val root = service.create(CreateContainer(name = "Root")).getOrThrow()
        val revBeforeFailure = store.readState().currentRevision

        val invalidMove = service.move(
            MoveContainer(
                id = root.id,
                newParentId = root.id,
                expectedRevision = revBeforeFailure,
            )
        )
        assertTrue(invalidMove.isFailure)

        assertEquals(revBeforeFailure, store.readState().currentRevision)
        val hist = store.getContainerHistoryAtRevision(root.id, revBeforeFailure)
        assertNotNull(hist)
        assertEquals(root.id, hist.id)
    }

    @Test
    fun testSearch() = runTest {
        val (_, service) = createStoreAndService()
        val work = service.create(CreateContainer(name = "Work")).getOrThrow()
        val project = service.create(CreateContainer(name = "Project Alpha", parentId = work.id)).getOrThrow()
        service.create(CreateContainer(name = "Personal")).getOrThrow()

        val hits = service.containerQueries.search("Alpha")
        assertEquals(1, hits.size)
        assertEquals(project.id, hits[0].container.id)
        assertEquals(listOf(work.id, project.id), hits[0].path.nodes)

        val hitsEmpty = service.containerQueries.search("NonExistent")
        assertTrue(hitsEmpty.isEmpty())
    }

    @Test
    fun testHabitPlacement_directAndSubtreeQueries() = runTest {
        val (store, service) = createStoreAndService()
        val root = service.create(CreateContainer(name = "Root")).getOrThrow()
        val child = service.create(CreateContainer(name = "Child", parentId = root.id)).getOrThrow()

        val h1 = HabitRef("h1")
        val h2 = HabitRef("h2")

        val p1 = service.placeHabit(
            PlaceHabit(
                habit = h1,
                targetContainerId = root.id,
                expectedRevision = store.readState().currentRevision,
            )
        )
        assertTrue(p1.isSuccess)

        val p2 = service.placeHabit(
            PlaceHabit(
                habit = h2,
                targetContainerId = child.id,
                expectedRevision = store.readState().currentRevision,
            )
        )
        assertTrue(p2.isSuccess)

        assertEquals(listOf(h1), service.placementQueries.directHabits(root.id))
        assertEquals(listOf(h2), service.placementQueries.directHabits(child.id))

        assertEquals(setOf(h1, h2), service.placementQueries.subtreeHabits(root.id))
        assertEquals(setOf(h2), service.placementQueries.subtreeHabits(child.id))
    }

    @Test
    fun testDenseLocalOrdering_andReorderHabits() = runTest {
        val (store, service) = createStoreAndService()
        val container = service.create(CreateContainer(name = "Container")).getOrThrow()
        val h1 = HabitRef("h1")
        val h2 = HabitRef("h2")
        val h3 = HabitRef("h3")

        service.placeHabit(PlaceHabit(h1, container.id, expectedRevision = store.readState().currentRevision))
        service.placeHabit(PlaceHabit(h2, container.id, expectedRevision = store.readState().currentRevision))
        service.placeHabit(PlaceHabit(h3, container.id, expectedRevision = store.readState().currentRevision))

        assertEquals(listOf(h1, h2, h3), service.placementQueries.directHabits(container.id))
        assertEquals(0, service.placementQueries.current(h1)!!.localOrder)
        assertEquals(1, service.placementQueries.current(h2)!!.localOrder)
        assertEquals(2, service.placementQueries.current(h3)!!.localOrder)

        val reorderRes = service.reorderHabits(
            ReorderPlacedHabits(
                containerId = container.id,
                orderedHabits = listOf(h3, h1, h2),
                expectedRevision = store.readState().currentRevision,
            )
        )
        assertTrue(reorderRes.isSuccess)
        assertEquals(listOf(h3, h1, h2), service.placementQueries.directHabits(container.id))
        assertEquals(0, service.placementQueries.current(h3)!!.localOrder)
        assertEquals(1, service.placementQueries.current(h1)!!.localOrder)
        assertEquals(2, service.placementQueries.current(h2)!!.localOrder)

        // Move h1 to unassigned
        val unassignRes = service.placeHabit(
            PlaceHabit(
                habit = h1,
                targetContainerId = null,
                expectedRevision = store.readState().currentRevision,
            )
        )
        assertTrue(unassignRes.isSuccess)
        assertEquals(listOf(h3, h2), service.placementQueries.directHabits(container.id))
        assertEquals(0, service.placementQueries.current(h3)!!.localOrder)
        assertEquals(1, service.placementQueries.current(h2)!!.localOrder)
        assertEquals(listOf(h1), service.placementQueries.directHabits(null))
    }

    @Test
    fun testPlacementValidation_archivedTarget() = runTest {
        val (store, service) = createStoreAndService()
        val container = service.create(CreateContainer(name = "Container")).getOrThrow()

        service.archive(ArchiveContainer(container.id, expectedRevision = store.readState().currentRevision))
        val h1 = HabitRef("h1")
        val err = service.placeHabit(
            PlaceHabit(
                habit = h1,
                targetContainerId = container.id,
                expectedRevision = store.readState().currentRevision,
            )
        )
        assertTrue(err.isFailure)
        assertTrue((err as OrganizationResult.Failure).error is OrganizationError.TargetContainerArchived)
    }

    @Test
    fun testAncestorMovePreservesHistoricalPathAtPriorRevision() = runTest {
        val (store, service) = createStoreAndService()
        val containerA = service.create(
            CreateContainer(
                name = "Project A",
                expectedRevision = store.readState().currentRevision,
            )
        ).getOrThrow()
        val h1 = HabitRef("h1")

        val pRes = service.placeHabit(
            PlaceHabit(
                habit = h1,
                targetContainerId = containerA.id,
                expectedRevision = store.readState().currentRevision,
            )
        )
        assertTrue(pRes.isSuccess)
        val revPlacement = store.readState().currentRevision

        // Create Container B
        val containerB = service.create(
            CreateContainer(
                name = "Area B",
                expectedRevision = store.readState().currentRevision,
            )
        ).getOrThrow()

        // Move Container A under Container B
        val moveRes = service.move(
            MoveContainer(
                id = containerA.id,
                newParentId = containerB.id,
                expectedRevision = store.readState().currentRevision,
            )
        )
        assertTrue(moveRes.isSuccess)
        val revAfterMove = store.readState().currentRevision

        // Query historical location at the revision of original placement: path is [A]
        val locAtPriorRev = service.placementQueries.locationAtRevision(h1, revPlacement)
        assertTrue(locAtPriorRev is HistoricalLocation.Known)
        assertEquals(listOf(containerA.id), locAtPriorRev.path.nodes)

        // Query historical location at revision after move: path is [B, A]
        val locAfterMove = service.placementQueries.locationAtRevision(h1, revAfterMove)
        assertTrue(locAfterMove is HistoricalLocation.Known)
        assertEquals(listOf(containerB.id, containerA.id), locAfterMove.path.nodes)
    }
}
