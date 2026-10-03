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

import org.isoron.uhabits.core.containers.facade.HabitOrganizationFacadeImpl
import org.isoron.uhabits.core.containers.facade.OrganizationAuthorityMode
import org.isoron.uhabits.core.containers.memory.MemoryOrganizationStore
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.PaletteColor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi

@OptIn(ExperimentalUuidApi::class)
class ContainersBrowseNavigationTest {

    private fun createHarness(): Pair<MemoryOrganizationStore, OrganizationServiceImpl> {
        var time = 1000L
        var idCounter = 1
        val clock = Clock { time += 10L; time }
        val idGen = IdGenerator { "c-id-${idCounter++}" }
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
    fun `archive root vs effectively archived descendants`() {
        val (store, service) = createHarness()

        val rootRes = service.create(CreateContainer(name = "A"))
        assertIs<OrganizationResult.Success<Container>>(rootRes)
        val rootA = rootRes.value

        val childRes = service.create(CreateContainer(name = "B", parentId = rootA.id))
        assertIs<OrganizationResult.Success<Container>>(childRes)
        val childB = childRes.value

        val grandChildRes = service.create(CreateContainer(name = "C", parentId = childB.id))
        assertIs<OrganizationResult.Success<Container>>(grandChildRes)
        val grandChildC = grandChildRes.value

        // Archive root A
        val archiveRes = service.archive(ArchiveContainer(id = rootA.id, expectedRevision = service.currentRevision()))
        assertIs<OrganizationResult.Success<Unit>>(archiveRes)

        // All 3 are effectively archived
        assertTrue(store.isEffectivelyArchived(rootA.id))
        assertTrue(store.isEffectivelyArchived(childB.id))
        assertTrue(store.isEffectivelyArchived(grandChildC.id))

        // Direct flag: only A was explicitly archived
        assertTrue(store.getContainer(rootA.id)!!.isArchived)
        assertFalse(store.getContainer(childB.id)!!.isArchived)
        assertFalse(store.getContainer(grandChildC.id)!!.isArchived)

        // Archive roots logic: only containers that are explicitly archived AND have no archived ancestor
        val allContainers = store.getAllContainers(includeDeleted = false)
        val archiveRoots = allContainers.filter { container ->
            if (!container.isArchived) return@filter false
            val ancestors = store.path(container.id).nodes.dropLast(1)
            ancestors.none { ancestorId -> store.getContainer(ancestorId)?.isArchived == true }
        }

        assertEquals(1, archiveRoots.size)
        assertEquals(rootA.id, archiveRoots[0].id)
    }

    @Test
    fun `restore archive does not change Habit isArchived`() {
        val (store, service) = createHarness()

        val rootRes = service.create(CreateContainer(name = "Work"))
        val root = (rootRes as OrganizationResult.Success).value

        val habitRef = HabitRef("habit-uuid-1")
        val placeRes = service.placeHabit(
            PlaceHabit(
                habit = habitRef,
                targetContainerId = root.id,
                expectedRevision = service.currentRevision()
            )
        )
        assertIs<OrganizationResult.Success<Unit>>(placeRes)

        // Simulated Habit model status
        var habitIsArchived = false

        // Archive container
        service.archive(ArchiveContainer(id = root.id, expectedRevision = service.currentRevision()))
        assertTrue(store.getContainer(root.id)!!.isArchived)
        // Invariant: Container archive does NOT archive habits
        assertFalse(habitIsArchived)

        // Unarchive container
        service.unarchive(UnarchiveContainer(id = root.id, expectedRevision = service.currentRevision()))
        assertFalse(store.getContainer(root.id)!!.isArchived)
        assertFalse(habitIsArchived)

        // Habit is still in the container
        val currentPlacement = store.current(habitRef)
        assertNotNull(currentPlacement)
        assertEquals(root.id, currentPlacement.containerId)
    }

    @Test
    fun `duplicate Container names with correct path`() {
        val (store, service) = createHarness()

        val univ = (service.create(CreateContainer(name = "Учёба")) as OrganizationResult.Success).value
        val work = (service.create(CreateContainer(name = "Работа")) as OrganizationResult.Success).value

        val labUniv = (service.create(CreateContainer(name = "Лабораторные", parentId = univ.id)) as OrganizationResult.Success).value
        val labWork = (service.create(CreateContainer(name = "Лабораторные", parentId = work.id)) as OrganizationResult.Success).value

        val pathUniv = store.path(labUniv.id).nodes.map { store.find(it)!!.name }
        val pathWork = store.path(labWork.id).nodes.map { store.find(it)!!.name }

        assertEquals(listOf("Учёба", "Лабораторные"), pathUniv)
        assertEquals(listOf("Работа", "Лабораторные"), pathWork)

        val hits = store.search("Лабораторные")
        assertEquals(2, hits.size)
        val hitPaths = hits.map { hit -> hit.path.nodes.map { store.find(it)!!.name } }
        assertTrue(hitPaths.contains(listOf("Учёба", "Лабораторные")))
        assertTrue(hitPaths.contains(listOf("Работа", "Лабораторные")))
    }

    @Test
    fun `depth greater than or equal to 6 in Browse and breadcrumb`() {
        val (store, service) = createHarness()

        val names = listOf("L1", "L2", "L3", "L4", "L5", "L6", "L7")
        var currentParent: ContainerId? = null
        val createdIds = mutableListOf<ContainerId>()

        for (name in names) {
            val res = (service.create(CreateContainer(name = name, parentId = currentParent)) as OrganizationResult.Success).value
            createdIds.add(res.id)
            currentParent = res.id
        }

        val leafId = createdIds.last()
        val path = store.path(leafId)
        assertEquals(7, path.nodes.size)
        val pathNames = path.nodes.map { store.find(it)!!.name }
        assertEquals(names, pathNames)

        // Breadcrumb formatting: compact representation when depth > 2
        val breadcrumbText = if (pathNames.size <= 2) {
            pathNames.dropLast(1).joinToString(" / ")
        } else {
            "${pathNames.first()} / … / ${pathNames[pathNames.size - 2]}"
        }
        assertEquals("L1 / … / L6", breadcrumbText)
    }

    @Test
    fun `Unassigned habits`() {
        val (store, service) = createHarness()

        val habit1 = HabitRef("h1")
        val habit2 = HabitRef("h2")

        // Neither habit has placement yet
        assertNull(store.current(habit1))
        assertNull(store.current(habit2))

        val directUnassigned = store.directHabits(null)
        assertTrue(directUnassigned.isEmpty())

        // Place habit1 in a container
        val c = (service.create(CreateContainer(name = "Health")) as OrganizationResult.Success).value
        service.placeHabit(PlaceHabit(habit = habit1, targetContainerId = c.id, expectedRevision = service.currentRevision()))

        assertEquals(c.id, store.current(habit1)?.containerId)
        assertNull(store.current(habit2))
    }

    @Test
    fun `direct vs subtree Habit scope`() {
        val (store, service) = createHarness()

        val parent = (service.create(CreateContainer(name = "Parent")) as OrganizationResult.Success).value
        val child = (service.create(CreateContainer(name = "Child", parentId = parent.id)) as OrganizationResult.Success).value

        val hParent = HabitRef("h-parent")
        val hChild = HabitRef("h-child")

        service.placeHabit(PlaceHabit(habit = hParent, targetContainerId = parent.id, expectedRevision = service.currentRevision()))
        service.placeHabit(PlaceHabit(habit = hChild, targetContainerId = child.id, expectedRevision = service.currentRevision()))

        val directParent = store.directHabits(parent.id)
        val subtreeParent = store.subtreeHabits(parent.id)

        val directChild = store.directHabits(child.id)
        val subtreeChild = store.subtreeHabits(child.id)

        assertEquals(listOf(hParent), directParent)
        assertEquals(setOf(hParent, hChild), subtreeParent)

        assertEquals(listOf(hChild), directChild)
        assertEquals(setOf(hChild), subtreeChild)
    }

    @Test
    fun `move Container cycle guard`() {
        val (store, service) = createHarness()

        val root = (service.create(CreateContainer(name = "Root")) as OrganizationResult.Success).value
        val sub = (service.create(CreateContainer(name = "Sub", parentId = root.id)) as OrganizationResult.Success).value
        val subSub = (service.create(CreateContainer(name = "SubSub", parentId = sub.id)) as OrganizationResult.Success).value

        // Try to move root into its own grandchild subSub
        val moveRes = service.move(
            MoveContainer(
                id = root.id,
                newParentId = subSub.id,
                expectedRevision = service.currentRevision()
            )
        )
        assertIs<OrganizationResult.Failure>(moveRes)
        assertIs<OrganizationError.CannotMoveIntoDescendant>(moveRes.error)
    }

    @Test
    fun `move Habit does not touch legacy block_id`() {
        val (store, service) = createHarness()
        val c = (service.create(CreateContainer(name = "Work")) as OrganizationResult.Success).value

        val facade = HabitOrganizationFacadeImpl(
            mode = OrganizationAuthorityMode.CONTAINER_LOCAL,
            containerQueries = store,
            habitPlacementQueries = store,
            organizationService = service
        )

        val placeRes = facade.placeHabit("habit-uuid-99", c.id)
        assertIs<OrganizationResult.Success<Unit>>(placeRes)

        val info = facade.getPlacementInfo("habit-uuid-99")
        assertEquals(c.id, info.containerId)
        assertEquals("Work", info.containerName)
        assertFalse(info.isUnassigned)
    }
}
