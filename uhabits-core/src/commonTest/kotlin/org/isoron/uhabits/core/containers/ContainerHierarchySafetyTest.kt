/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.core.containers

import org.isoron.uhabits.core.containers.facade.HabitOrganizationFacadeImpl
import org.isoron.uhabits.core.containers.facade.OrganizationAuthorityMode
import org.isoron.uhabits.core.containers.memory.MemoryOrganizationStore
import org.isoron.uhabits.core.models.PaletteColor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContainerHierarchySafetyTest {

    private fun createMemoryService(): Pair<MemoryOrganizationStore, OrganizationServiceImpl> {
        var time = 1000L
        var idCounter = 1
        val clock = Clock { time += 10L; time }
        val idGen = IdGenerator { "mem-id-${idCounter++}" }
        val habitLookup = HabitIdentityLookup { true }

        val store = MemoryOrganizationStore(
            initialCutoverRevision = OrganizationRevision(1L),
            initialCutoverAt = 1000L,
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
        return store to service
    }

    @Test
    fun testMoveContainer_selfParentingRejected() {
        val (store, service) = createMemoryService()
        val root = ContainerId("root-1")
        service.create(CreateContainer(name = "Work", parentId = null, color = PaletteColor(1), opUuid = "op-1", explicitId = root))

        val facade = HabitOrganizationFacadeImpl(
            mode = OrganizationAuthorityMode.CONTAINER_LOCAL,
            containerQueries = store,
            habitPlacementQueries = store,
            organizationService = service
        )

        // Cannot move container into itself
        val result = facade.moveContainer(root, root)
        assertTrue(result.isFailure, "Self-move must be rejected")
    }

    @Test
    fun testMoveContainer_descendantCycleRejected() {
        val (store, service) = createMemoryService()
        val root = ContainerId("root-1")
        val child = ContainerId("child-1")
        val grandChild = ContainerId("grandchild-1")

        service.create(CreateContainer(name = "Work", parentId = null, color = PaletteColor(1), opUuid = "op-1", explicitId = root))
        service.create(CreateContainer(name = "Project", parentId = root, color = PaletteColor(2), opUuid = "op-2", explicitId = child))
        service.create(CreateContainer(name = "Task", parentId = child, color = PaletteColor(3), opUuid = "op-3", explicitId = grandChild))

        val facade = HabitOrganizationFacadeImpl(
            mode = OrganizationAuthorityMode.CONTAINER_LOCAL,
            containerQueries = store,
            habitPlacementQueries = store,
            organizationService = service
        )

        // Cannot move root into its child
        val moveIntoChild = facade.moveContainer(root, child)
        assertTrue(moveIntoChild.isFailure, "Moving root into its child must be rejected to prevent cycle")

        // Cannot move root into its grandchild
        val moveIntoGrandChild = facade.moveContainer(root, grandChild)
        assertTrue(moveIntoGrandChild.isFailure, "Moving root into its grandchild must be rejected to prevent cycle")
    }

    @Test
    fun testMoveContainer_validMoveToRoot() {
        val (store, service) = createMemoryService()
        val root = ContainerId("root-1")
        val child = ContainerId("child-1")

        service.create(CreateContainer(name = "Work", parentId = null, color = PaletteColor(1), opUuid = "op-1", explicitId = root))
        service.create(CreateContainer(name = "Project", parentId = root, color = PaletteColor(2), opUuid = "op-2", explicitId = child))

        val facade = HabitOrganizationFacadeImpl(
            mode = OrganizationAuthorityMode.CONTAINER_LOCAL,
            containerQueries = store,
            habitPlacementQueries = store,
            organizationService = service
        )

        val result = facade.moveContainer(child, null)
        assertTrue(result.isSuccess, "Moving child to root must succeed")

        val updatedChild = store.find(child)
        assertNotNull(updatedChild)
        assertNull(updatedChild.parentId, "Parent ID should now be null")
        assertEquals("Project", updatedChild.name)
    }

    @Test
    fun testMoveContainer_validMoveToSibling_preservesSubtree() {
        val (store, service) = createMemoryService()
        val root1 = ContainerId("root-1")
        val root2 = ContainerId("root-2")
        val child = ContainerId("child-1")
        val grandChild = ContainerId("grandchild-1")

        service.create(CreateContainer(name = "Work", parentId = null, color = PaletteColor(1), opUuid = "op-1", explicitId = root1))
        service.create(CreateContainer(name = "Personal", parentId = null, color = PaletteColor(2), opUuid = "op-2", explicitId = root2))
        service.create(CreateContainer(name = "Project", parentId = root1, color = PaletteColor(3), opUuid = "op-3", explicitId = child))
        service.create(CreateContainer(name = "SubTask", parentId = child, color = PaletteColor(4), opUuid = "op-4", explicitId = grandChild))

        val facade = HabitOrganizationFacadeImpl(
            mode = OrganizationAuthorityMode.CONTAINER_LOCAL,
            containerQueries = store,
            habitPlacementQueries = store,
            organizationService = service
        )

        // Move child from root1 to root2
        val result = facade.moveContainer(child, root2)
        assertTrue(result.isSuccess, "Moving child to root2 must succeed")

        val updatedChild = store.find(child)
        assertNotNull(updatedChild)
        assertEquals(root2, updatedChild.parentId, "Child's parent must now be root2")

        // Subtree check: grandChild must still have child as its parent
        val updatedGrandChild = store.find(grandChild)
        assertNotNull(updatedGrandChild)
        assertEquals(child, updatedGrandChild.parentId, "Grandchild parent must still be child")
    }

    @Test
    fun testMoveContainer_sameDestination_isSuccessWithoutCorruption() {
        val (store, service) = createMemoryService()
        val root = ContainerId("root-1")
        val child = ContainerId("child-1")

        service.create(CreateContainer(name = "Work", parentId = null, color = PaletteColor(1), opUuid = "op-1", explicitId = root))
        service.create(CreateContainer(name = "Project", parentId = root, color = PaletteColor(2), opUuid = "op-2", explicitId = child))

        val facade = HabitOrganizationFacadeImpl(
            mode = OrganizationAuthorityMode.CONTAINER_LOCAL,
            containerQueries = store,
            habitPlacementQueries = store,
            organizationService = service
        )

        // Move to current parent
        val result = facade.moveContainer(child, root)
        assertTrue(result.isSuccess, "Moving to same parent should be handled cleanly")

        val updatedChild = store.find(child)
        assertNotNull(updatedChild)
        assertEquals(root, updatedChild.parentId)
    }
}
