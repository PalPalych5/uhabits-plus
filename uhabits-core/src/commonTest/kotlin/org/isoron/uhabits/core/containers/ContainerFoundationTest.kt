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

import org.isoron.uhabits.core.containers.memory.MemoryOrganizationStore
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContainerFoundationTest {

    private lateinit var store: MemoryOrganizationStore
    private lateinit var service: OrganizationService
    private var testClockTime = 1000L
    private var idCounter = 1

    private val clock = Clock { testClockTime += 10L; testClockTime }
    private val idGen = IdGenerator { "test-id-${idCounter++}" }
    private val habitLookup = HabitIdentityLookup { true }

    @BeforeTest
    fun setUp() {
        testClockTime = 1000L
        idCounter = 1
        store = MemoryOrganizationStore(
            initialCutoverRevision = OrganizationRevision(1L),
            initialCutoverAt = 1000L,
        )
        service = OrganizationServiceImpl(
            store = store,
            unitOfWork = store,
            containerQueries = store,
            placementQueries = store,
            clock = clock,
            idGenerator = idGen,
            habitIdentityLookup = habitLookup,
        )
    }

    @Test
    fun testDeepHierarchy_depthAtLeast6() {
        var currentParent: ContainerId? = null
        val createdIds = mutableListOf<ContainerId>()

        // Create a chain of 7 levels: root -> L1 -> L2 -> L3 -> L4 -> L5 -> L6
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
    fun testMove_rejectsCyclesAndSelfMove() {
        val rootRes = service.create(CreateContainer(name = "Root"))
        val root = rootRes.getOrThrow()

        val child1Res = service.create(CreateContainer(name = "Child 1", parentId = root.id))
        val child1 = child1Res.getOrThrow()

        val child2Res = service.create(CreateContainer(name = "Child 2", parentId = child1.id))
        val child2 = child2Res.getOrThrow()

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
    fun testMove_rejectsMoveIntoEffectivelyArchivedTarget() {
        val rootRes = service.create(CreateContainer(name = "Root"))
        val root = rootRes.getOrThrow()

        val activeRes = service.create(CreateContainer(name = "Active Root"))
        val active = activeRes.getOrThrow()

        // Archive root
        val archiveRes = service.archive(
            ArchiveContainer(
                id = root.id,
                expectedRevision = store.readState().currentRevision,
            )
        )
        assertTrue(archiveRes.isSuccess)

        // Attempt to move active container under archived root
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
    fun testStableUuidAndDuplicateNames() {
        // Names are not unique; two containers can share the exact same title
        val c1 = service.create(CreateContainer(name = "Inbox")).getOrThrow()
        val c2 = service.create(CreateContainer(name = "Inbox")).getOrThrow()

        assertTrue(c1.id != c2.id)
        val initialId1 = c1.id

        // Edit c1 name
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

        // Archive c1
        service.archive(
            ArchiveContainer(
                id = c1.id,
                expectedRevision = store.readState().currentRevision,
            )
        )
        val archived = service.containerQueries.find(initialId1)
        assertNotNull(archived)
        assertEquals(initialId1, archived.id)
        assertTrue(archived.isArchived)
    }

    @Test
    fun testSiblingOrdering_denseSequence() {
        val c0 = service.create(CreateContainer(name = "C0")).getOrThrow()
        val c1 = service.create(CreateContainer(name = "C1")).getOrThrow()
        val c2 = service.create(CreateContainer(name = "C2")).getOrThrow()

        assertEquals(0, c0.siblingOrder)
        assertEquals(1, c1.siblingOrder)
        assertEquals(2, c2.siblingOrder)

        // Insert at index 1: old 1 and 2 shift right
        val cNew = service.create(
            CreateContainer(
                name = "CNew",
                siblingOrder = 1,
            )
        ).getOrThrow()

        val roots = service.containerQueries.roots()
        assertEquals(listOf(c0.id, cNew.id, c1.id, c2.id), roots.map { it.id })
        assertEquals(listOf(0, 1, 2, 3), roots.map { it.siblingOrder })

        // Reorder explicitly
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
    fun testArchiveAndUnarchive_doesNotRewriteDescendantFlags() {
        val root = service.create(CreateContainer(name = "Root")).getOrThrow()
        val child = service.create(CreateContainer(name = "Child", parentId = root.id)).getOrThrow()
        val grandchild = service.create(CreateContainer(name = "Grandchild", parentId = child.id)).getOrThrow()

        // 1. Explicitly archive child
        service.archive(ArchiveContainer(id = child.id, expectedRevision = store.readState().currentRevision))
        assertTrue(service.containerQueries.find(child.id)!!.isArchived)
        assertFalse(service.containerQueries.find(grandchild.id)!!.isArchived)
        // Grandchild is effectively archived because parent child is archived
        assertTrue(service.containerQueries.isEffectivelyArchived(grandchild.id))

        // 2. Archive root
        service.archive(ArchiveContainer(id = root.id, expectedRevision = store.readState().currentRevision))
        assertTrue(service.containerQueries.find(root.id)!!.isArchived)
        // Descendants own isArchived flags are preserved
        assertTrue(service.containerQueries.find(child.id)!!.isArchived)
        assertFalse(service.containerQueries.find(grandchild.id)!!.isArchived)

        // 3. Unarchive root: root becomes active, child remains archived, grandchild remains effectively archived
        service.unarchive(UnarchiveContainer(id = root.id, expectedRevision = store.readState().currentRevision))
        assertFalse(service.containerQueries.find(root.id)!!.isArchived)
        assertFalse(service.containerQueries.isEffectivelyArchived(root.id))

        assertTrue(service.containerQueries.find(child.id)!!.isArchived)
        assertTrue(service.containerQueries.isEffectivelyArchived(child.id))
        assertTrue(service.containerQueries.isEffectivelyArchived(grandchild.id))
    }

    @Test
    fun testDeleteEmpty_rejectsNonEmptyAndSoftDeletesEmpty() {
        val root = service.create(CreateContainer(name = "Root")).getOrThrow()
        val child = service.create(CreateContainer(name = "Child", parentId = root.id)).getOrThrow()

        // Attempt to delete non-empty root (has child)
        val deleteRootRes = service.deleteEmpty(
            DeleteEmptyContainer(id = root.id, expectedRevision = store.readState().currentRevision)
        )
        assertTrue(deleteRootRes.isFailure)
        val err = (deleteRootRes as OrganizationResult.Failure).error
        assertTrue(err is OrganizationError.ContainerNotEmpty)
        assertEquals(1, err.activeOrArchivedChildrenCount)

        // Delete empty child
        val deleteChildRes = service.deleteEmpty(
            DeleteEmptyContainer(id = child.id, expectedRevision = store.readState().currentRevision)
        )
        assertTrue(deleteChildRes.isSuccess)
        assertNull(service.containerQueries.find(child.id, includeDeleted = false))
        val deletedChild = service.containerQueries.find(child.id, includeDeleted = true)
        assertNotNull(deletedChild)
        assertNotNull(deletedChild.deletedAt)

        // Now root is empty, delete root succeeds
        val deleteRootAgain = service.deleteEmpty(
            DeleteEmptyContainer(id = root.id, expectedRevision = store.readState().currentRevision)
        )
        assertTrue(deleteRootAgain.isSuccess)
        assertNull(service.containerQueries.find(root.id, includeDeleted = false))
    }

    @Test
    fun testIdempotencyAndOperationRetry() {
        val opUuid = "op-create-42"
        val request = CreateContainer(
            name = "Idempotent Node",
            opUuid = opUuid,
        )

        val res1 = service.create(request)
        assertTrue(res1.isSuccess)
        val c1 = res1.getOrThrow()
        val revAfterFirst = store.readState().currentRevision

        // Replay identical command
        val res2 = service.create(request)
        assertTrue(res2.isSuccess)
        assertEquals(c1.id, res2.getOrThrow().id)
        // Revision must not increment on replaying idempotent operation
        assertEquals(revAfterFirst, store.readState().currentRevision)

        // Replay same opUuid with conflicting payload -> rejected
        val conflictingRequest = CreateContainer(
            name = "Conflicting Name",
            opUuid = opUuid,
        )
        val res3 = service.create(conflictingRequest)
        assertTrue(res3.isFailure)
        assertTrue((res3 as OrganizationResult.Failure).error is OrganizationError.OperationPayloadMismatch)
    }

    @Test
    fun testStaleRevisionConflict() {
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
    fun testTransactionRollback_noRevisionOrHistoryDrift() {
        val initialRev = store.readState().currentRevision
        val root = service.create(CreateContainer(name = "Root")).getOrThrow()
        val revBeforeFailure = store.readState().currentRevision

        // Intentionally try an invalid operation that fails validation inside transaction
        val invalidMove = service.move(
            MoveContainer(
                id = root.id,
                newParentId = root.id, // self move
                expectedRevision = revBeforeFailure,
            )
        )
        assertTrue(invalidMove.isFailure)

        // Ensure state revision did not change and no corrupt history was written
        assertEquals(revBeforeFailure, store.readState().currentRevision)
        val hist = store.getContainerHistoryAtRevision(root.id, revBeforeFailure)
        assertNotNull(hist)
        assertEquals(root.id, hist.id)
    }

    @Test
    fun testSearch() {
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
}
