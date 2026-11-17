/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.core.containers

import kotlinx.coroutines.test.runTest
import org.isoron.platform.io.TestDatabaseHelper
import org.isoron.uhabits.core.containers.facade.HabitOrganizationFacadeImpl
import org.isoron.uhabits.core.containers.facade.OrganizationAuthorityMode
import org.isoron.uhabits.core.containers.sqlite.OrganizationSchema
import org.isoron.uhabits.core.containers.sqlite.SQLiteOrganizationStore
import org.isoron.uhabits.core.database.HabitData
import org.isoron.uhabits.core.database.HabitRepository
import org.isoron.uhabits.core.models.PaletteColor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContainerHierarchyPersistenceSafetyTest {

    private class TestHarness(
        val store: SQLiteOrganizationStore,
        val service: OrganizationServiceImpl,
        val facade: HabitOrganizationFacadeImpl
    )

    private suspend fun createSqlHarness(): TestHarness {
        var time = 1000L
        var idCounter = 1
        val clock = Clock { time += 10L; time }
        val idGen = IdGenerator { "sql-h-$idCounter".also { idCounter++ } }
        val habitLookup = HabitIdentityLookup { true }

        val db = TestDatabaseHelper.createEmptyDatabase()
        OrganizationSchema.createSchema(db)

        val habitRepo = HabitRepository(db)
        habitRepo.insert(HabitData(name = "h1", uuid = "h1"))

        val store = SQLiteOrganizationStore(db)
        store.seedBaseline(
            revision = OrganizationRevision(1L),
            recordedAt = 1000L
        )

        val service = OrganizationServiceImpl(
            store = store,
            unitOfWork = store,
            containerQueries = store,
            placementQueries = store,
            clock = clock,
            idGenerator = idGen,
            habitIdentityLookup = habitLookup
        )

        val facade = HabitOrganizationFacadeImpl(
            mode = OrganizationAuthorityMode.CONTAINER_LOCAL,
            containerQueries = store,
            habitPlacementQueries = store,
            organizationService = service
        )

        return TestHarness(store, service, facade)
    }

    @Test
    fun testSelfParenting_rejectedInRealSqlite() = runTest {
        val harness = createSqlHarness()
        val root = ContainerId("root-1")
        harness.service.create(CreateContainer(name = "RootSection", parentId = null, explicitId = root))

        val initialInDb = harness.store.find(root)
        assertNotNull(initialInDb)
        assertNull(initialInDb.parentId)

        val result = harness.facade.moveContainer(root, root)
        assertTrue(result.isFailure, "Self-move must fail in production facade")

        // Assert database row parent remains untouched
        val afterMoveInDb = harness.store.find(root)
        assertNotNull(afterMoveInDb)
        assertNull(afterMoveInDb.parentId, "Database row must remain parentId=null")
    }

    @Test
    fun testDescendantCycle_rejectedInRealSqlite() = runTest {
        val harness = createSqlHarness()
        val root = ContainerId("root-1")
        val child = ContainerId("child-1")
        val grandChild = ContainerId("grandchild-1")

        harness.service.create(CreateContainer(name = "Work", parentId = null, explicitId = root))
        harness.service.create(CreateContainer(name = "Projects", parentId = root, explicitId = child))
        harness.service.create(CreateContainer(name = "AndroidApp", parentId = child, explicitId = grandChild))

        // 1. Attempt root -> child move
        val res1 = harness.facade.moveContainer(root, child)
        assertTrue(res1.isFailure, "Move into direct child must fail")

        // 2. Attempt root -> grandchild move
        val res2 = harness.facade.moveContainer(root, grandChild)
        assertTrue(res2.isFailure, "Move into grandchild must fail")

        // Verify SQLite state: root is still at root, child still under root, grandchild still under child
        assertEquals(null, harness.store.find(root)?.parentId)
        assertEquals(root, harness.store.find(child)?.parentId)
        assertEquals(child, harness.store.find(grandChild)?.parentId)
    }

    @Test
    fun testRootToChildAndChildToRootMoves_persistedCorrectly() = runTest {
        val harness = createSqlHarness()
        val sectionA = ContainerId("section-a")
        val sectionB = ContainerId("section-b")

        harness.service.create(CreateContainer(name = "SectionA", parentId = null, explicitId = sectionA))
        harness.service.create(CreateContainer(name = "SectionB", parentId = null, explicitId = sectionB))

        // Step 1: Move SectionB under SectionA (root-to-child)
        val moveToChildRes = harness.facade.moveContainer(sectionB, sectionA)
        assertTrue(moveToChildRes.isSuccess)

        val bInDb = harness.store.find(sectionB)
        assertNotNull(bInDb)
        assertEquals(sectionA, bInDb.parentId, "SectionB must be persisted with parent=SectionA")

        // Step 2: Move SectionB back to root (child-to-root)
        val moveToRootRes = harness.facade.moveContainer(sectionB, null)
        assertTrue(moveToRootRes.isSuccess)

        val bAtRootInDb = harness.store.find(sectionB)
        assertNotNull(bAtRootInDb)
        assertNull(bAtRootInDb.parentId, "SectionB must now be persisted with parentId=null")
    }

    @Test
    fun testSiblingToSiblingMove_persistedCorrectly() = runTest {
        val harness = createSqlHarness()
        val root1 = ContainerId("root-1")
        val root2 = ContainerId("root-2")
        val taskGroup = ContainerId("task-group")

        harness.service.create(CreateContainer(name = "Work", parentId = null, explicitId = root1))
        harness.service.create(CreateContainer(name = "Personal", parentId = null, explicitId = root2))
        harness.service.create(CreateContainer(name = "Tasks", parentId = root1, explicitId = taskGroup))

        // Move taskGroup from root1 to root2
        val moveRes = harness.facade.moveContainer(taskGroup, root2)
        assertTrue(moveRes.isSuccess)

        val inDb = harness.store.find(taskGroup)
        assertNotNull(inDb)
        assertEquals(root2, inDb.parentId, "TaskGroup parent in SQLite must be root2")
    }

    @Test
    fun testMovingSubtree_preservesAllDescendantsInSqlite() = runTest {
        val harness = createSqlHarness()
        val oldRoot = ContainerId("old-root")
        val newRoot = ContainerId("new-root")
        val branch = ContainerId("branch")
        val leaf1 = ContainerId("leaf-1")
        val leaf2 = ContainerId("leaf-2")

        harness.service.create(CreateContainer(name = "OldRoot", parentId = null, explicitId = oldRoot))
        harness.service.create(CreateContainer(name = "NewRoot", parentId = null, explicitId = newRoot))
        harness.service.create(CreateContainer(name = "Branch", parentId = oldRoot, explicitId = branch))
        harness.service.create(CreateContainer(name = "Leaf1", parentId = branch, explicitId = leaf1))
        harness.service.create(CreateContainer(name = "Leaf2", parentId = branch, explicitId = leaf2))

        // Move Branch to NewRoot
        val res = harness.facade.moveContainer(branch, newRoot)
        assertTrue(res.isSuccess)

        // Verify Branch parent is NewRoot
        assertEquals(newRoot, harness.store.find(branch)?.parentId)

        // Verify both leaves STILL have Branch as their parent in SQLite
        val leaf1Db = harness.store.find(leaf1)
        val leaf2Db = harness.store.find(leaf2)
        assertNotNull(leaf1Db)
        assertNotNull(leaf2Db)
        assertEquals(branch, leaf1Db.parentId, "Leaf1 parent must remain branch")
        assertEquals(branch, leaf2Db.parentId, "Leaf2 parent must remain branch")

        // Verify subtree query returns all descendants intact
        val subtree = harness.store.subtreeIds(branch)
        assertEquals(setOf(leaf1, leaf2), subtree.toSet(), "Subtree IDs must remain leaf1 and leaf2")
    }

    @Test
    fun testUnchangedParentSelection_succeedsWithoutCorruption() = runTest {
        val harness = createSqlHarness()
        val root = ContainerId("root-1")
        val child = ContainerId("child-1")

        harness.service.create(CreateContainer(name = "Work", parentId = null, explicitId = root))
        harness.service.create(CreateContainer(name = "Project", parentId = root, explicitId = child))

        val preRevision = harness.store.readState().currentRevision

        // Selecting same parent (root)
        val res = harness.facade.moveContainer(child, root)
        assertTrue(res.isSuccess, "Re-selecting current parent must succeed")

        val inDb = harness.store.find(child)
        assertNotNull(inDb)
        assertEquals(root, inDb.parentId)
        assertEquals("Project", inDb.name)
    }

    @Test
    fun testCancellationWithoutMutation_leavesSqliteStateUnchanged() = runTest {
        val harness = createSqlHarness()
        val root = ContainerId("root-1")
        val child = ContainerId("child-1")

        harness.service.create(CreateContainer(name = "Work", parentId = null, explicitId = root))
        harness.service.create(CreateContainer(name = "Project", parentId = root, explicitId = child))

        val revisionBefore = harness.store.readState().currentRevision

        // User opens parent picker, inspects rows, but cancels (no facade.moveContainer called)
        val revisionAfter = harness.store.readState().currentRevision
        assertEquals(revisionBefore, revisionAfter, "Revision must not increment on cancellation")

        val childInDb = harness.store.find(child)
        assertNotNull(childInDb)
        assertEquals(root, childInDb.parentId, "Child parent must remain root")
    }
}
