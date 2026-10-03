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

import org.isoron.uhabits.core.containers.Clock
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.containers.CreateContainer
import org.isoron.uhabits.core.containers.HabitIdentityLookup
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.containers.IdGenerator
import org.isoron.uhabits.core.containers.OrganizationResult
import org.isoron.uhabits.core.containers.OrganizationRevision
import org.isoron.uhabits.core.containers.OrganizationServiceImpl
import org.isoron.uhabits.core.containers.PlaceHabit
import org.isoron.uhabits.core.containers.TreePolicy
import org.isoron.uhabits.core.containers.memory.MemoryOrganizationStore
import org.isoron.uhabits.core.models.HabitBlock
import org.isoron.uhabits.core.models.PaletteColor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class HabitOrganizationFacadeTest {

    private val legacyBlocks = listOf(
        HabitBlock(id = 1L, name = "Health", color = PaletteColor(1), icon = null, position = 0),
        HabitBlock(id = 2L, name = "Work", color = PaletteColor(2), icon = null, position = 1),
        HabitBlock(id = 7L, name = "Default", color = PaletteColor(7), icon = null, position = 2)
    )

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
    fun `legacy mode - placement info returns legacy block`() {
        val facade = HabitOrganizationFacadeImpl(
            mode = OrganizationAuthorityMode.LEGACY,
            blocksProvider = { legacyBlocks }
        )

        val info = facade.getPlacementInfo("habit-1", legacyBlockId = 1L)
        assertFalse(info.isUnassigned)
        assertEquals("Health", info.containerName)
        assertEquals("Health", info.rootContainerName)
        assertEquals(listOf("Health"), info.containerPath)
        assertNull(info.containerId)
        assertNull(info.rootContainerId)

        val unassigned = facade.getPlacementInfo("habit-2", legacyBlockId = null)
        assertTrue(unassigned.isUnassigned)
        assertNull(unassigned.containerName)
    }

    @Test
    fun `legacy mode - root group key returns legacy block id`() {
        val facade = HabitOrganizationFacadeImpl(
            mode = OrganizationAuthorityMode.LEGACY,
            blocksProvider = { legacyBlocks }
        )

        assertEquals(1L, facade.getRootGroupKey("habit-1", 1L))
        assertEquals(7L, facade.getRootGroupKey("habit-2", 7L))
        assertNull(facade.getRootGroupKey("habit-3", null))
        assertEquals("Health", facade.getRootGroupName("habit-1", 1L))
    }

    @Test
    fun `legacy mode - available containers lists legacy blocks`() {
        val facade = HabitOrganizationFacadeImpl(
            mode = OrganizationAuthorityMode.LEGACY,
            blocksProvider = { legacyBlocks }
        )

        val items = facade.availableContainers()
        assertEquals(3, items.size)
        assertEquals("Health", items[0].name)
        assertEquals("1", items[0].key)
        assertTrue(items[0].isRoot)
    }

    @Test
    fun `container local mode - placement info with deep hierarchy`() {
        val (store, service) = createMemoryService()

        val rootId = ContainerId(Uuid.random().toHexString())
        val childId = ContainerId(Uuid.random().toHexString())
        val subchildId = ContainerId(Uuid.random().toHexString())
        val leafId = ContainerId(Uuid.random().toHexString())

        service.create(CreateContainer(name = "Root", parentId = null, color = PaletteColor(1), opUuid = "op-1", explicitId = rootId))
        service.create(CreateContainer(name = "Child", parentId = rootId, color = PaletteColor(2), opUuid = "op-2", explicitId = childId))
        service.create(CreateContainer(name = "SubChild", parentId = childId, color = PaletteColor(3), opUuid = "op-3", explicitId = subchildId))
        service.create(CreateContainer(name = "Leaf", parentId = subchildId, color = PaletteColor(4), opUuid = "op-4", explicitId = leafId))

        val habitUuid = "habit-deep-1"
        service.placeHabit(
            PlaceHabit(
                habit = HabitRef(habitUuid),
                targetContainerId = leafId,
                opUuid = "op-place",
                expectedRevision = service.currentRevision()
            )
        )

        val facade = HabitOrganizationFacadeImpl(
            mode = OrganizationAuthorityMode.CONTAINER_LOCAL,
            containerQueries = store,
            habitPlacementQueries = store,
            organizationService = service
        )

        val info = facade.getPlacementInfo(habitUuid, legacyBlockId = null)
        assertFalse(info.isUnassigned)
        assertEquals(leafId, info.containerId)
        assertEquals("Leaf", info.containerName)
        assertEquals(rootId, info.rootContainerId)
        assertEquals("Root", info.rootContainerName)
        assertEquals(listOf("Root", "Child", "SubChild", "Leaf"), info.containerPath)

        // Root grouping resolves to root container ID
        assertEquals(rootId.value, facade.getRootGroupKey(habitUuid, null))
        assertEquals("Root", facade.getRootGroupName(habitUuid, null))
    }

    @Test
    fun `container local mode - available and root containers`() {
        val (store, service) = createMemoryService()

        val r1 = ContainerId("root-1")
        val r2 = ContainerId("root-2")
        val c1 = ContainerId("child-1")

        service.create(CreateContainer(name = "Health", parentId = null, color = PaletteColor(1), opUuid = "op-1", explicitId = r1))
        service.create(CreateContainer(name = "Work", parentId = null, color = PaletteColor(2), opUuid = "op-2", explicitId = r2))
        service.create(CreateContainer(name = "Fitness", parentId = r1, color = PaletteColor(3), opUuid = "op-3", explicitId = c1))

        val facade = HabitOrganizationFacadeImpl(
            mode = OrganizationAuthorityMode.CONTAINER_LOCAL,
            containerQueries = store,
            habitPlacementQueries = store,
            organizationService = service
        )

        val roots = facade.rootContainers()
        assertEquals(2, roots.size)
        assertEquals("Health", roots[0].name)
        assertEquals("Work", roots[1].name)

        val all = facade.availableContainers()
        assertEquals(3, all.size)
        val fitness = all.first { it.key == c1.value }
        assertEquals("Health / Fitness", fitness.path)
        assertFalse(fitness.isRoot)
    }

    @Test
    fun `container local mode - direct vs subtree habit query`() {
        val (store, service) = createMemoryService()

        val rootId = ContainerId("root")
        val childId = ContainerId("child")

        service.create(CreateContainer(name = "Root", parentId = null, color = PaletteColor(1), opUuid = "op-1", explicitId = rootId))
        service.create(CreateContainer(name = "Child", parentId = rootId, color = PaletteColor(2), opUuid = "op-2", explicitId = childId))

        service.placeHabit(PlaceHabit(habit = HabitRef("h-root"), targetContainerId = rootId, opUuid = "op-h1", expectedRevision = service.currentRevision()))
        service.placeHabit(PlaceHabit(habit = HabitRef("h-child"), targetContainerId = childId, opUuid = "op-h2", expectedRevision = service.currentRevision()))

        val facade = HabitOrganizationFacadeImpl(
            mode = OrganizationAuthorityMode.CONTAINER_LOCAL,
            containerQueries = store,
            habitPlacementQueries = store,
            organizationService = service
        )

        val directRoot = facade.getHabitUuidsForContainer(rootId.value, subtree = false)
        assertEquals(setOf("h-root"), directRoot)

        val subtreeRoot = facade.getHabitUuidsForContainer(rootId.value, subtree = true)
        assertEquals(setOf("h-root", "h-child"), subtreeRoot)

        val directChild = facade.getHabitUuidsForContainer(childId.value, subtree = false)
        assertEquals(setOf("h-child"), directChild)
    }

    @Test
    fun `container local mode - placement mutation via facade`() {
        val (store, service) = createMemoryService()

        val rootId = ContainerId("root")
        service.create(CreateContainer(name = "Root", parentId = null, color = PaletteColor(1), opUuid = "op-1", explicitId = rootId))

        val facade = HabitOrganizationFacadeImpl(
            mode = OrganizationAuthorityMode.CONTAINER_LOCAL,
            containerQueries = store,
            habitPlacementQueries = store,
            organizationService = service
        )

        // Place new habit
        val res1 = facade.placeNewHabit("h-new", rootId)
        assertTrue(res1 is OrganizationResult.Success)

        val info = facade.getPlacementInfo("h-new", null)
        assertEquals(rootId, info.containerId)
        assertEquals("Root", info.containerName)

        // Move to unassigned
        val res2 = facade.placeHabit("h-new", null)
        assertTrue(res2 is OrganizationResult.Success)

        val unassignedInfo = facade.getPlacementInfo("h-new", null)
        assertTrue(unassignedInfo.isUnassigned)
    }
}
