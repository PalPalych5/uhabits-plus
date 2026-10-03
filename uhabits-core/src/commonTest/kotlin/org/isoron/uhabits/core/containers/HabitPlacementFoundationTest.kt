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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HabitPlacementFoundationTest {

    private lateinit var store: MemoryOrganizationStore
    private lateinit var service: OrganizationService
    private var testClockTime = 1000L
    private var idCounter = 1

    private val validHabitUuids = mutableSetOf<String>()
    private val clock = Clock { testClockTime += 10L; testClockTime }
    private val idGen = IdGenerator { "test-id-${idCounter++}" }
    private val habitLookup = HabitIdentityLookup { it.uuid in validHabitUuids }

    @BeforeTest
    fun setUp() {
        testClockTime = 1000L
        idCounter = 1
        validHabitUuids.clear()
        validHabitUuids.addAll(listOf("h1", "h2", "h3", "h-unknown", "h-unassigned"))

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
    fun testPlacement_directAndSubtreeQueries() {
        val root = service.create(CreateContainer(name = "Root")).getOrThrow()
        val child = service.create(CreateContainer(name = "Child", parentId = root.id)).getOrThrow()

        val h1 = HabitRef("h1")
        val h2 = HabitRef("h2")

        // Place h1 into root
        val p1 = service.placeHabit(
            PlaceHabit(
                habit = h1,
                targetContainerId = root.id,
                expectedRevision = store.readState().currentRevision,
            )
        )
        assertTrue(p1.isSuccess)

        // Place h2 into child
        val p2 = service.placeHabit(
            PlaceHabit(
                habit = h2,
                targetContainerId = child.id,
                expectedRevision = store.readState().currentRevision,
            )
        )
        assertTrue(p2.isSuccess)

        // Direct habits query
        assertEquals(listOf(h1), service.placementQueries.directHabits(root.id))
        assertEquals(listOf(h2), service.placementQueries.directHabits(child.id))

        // Subtree habits query: root contains both h1 and h2
        assertEquals(setOf(h1, h2), service.placementQueries.subtreeHabits(root.id))
        assertEquals(setOf(h2), service.placementQueries.subtreeHabits(child.id))
    }

    @Test
    fun testDenseLocalOrdering_andReorder() {
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

        // Reorder habits explicitly: [h3, h1, h2]
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

        // Move h1 to unassigned (containerId = null): remaining habits in container renumber densely to [0, 1]
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
    fun testPlacementValidation_nonExistentHabitAndArchivedTarget() {
        val nonExistentHabit = HabitRef("non-existent-uuid")
        val container = service.create(CreateContainer(name = "Container")).getOrThrow()

        // 1. Habit not found in lookup
        val err1 = service.placeHabit(
            PlaceHabit(
                habit = nonExistentHabit,
                targetContainerId = container.id,
                expectedRevision = store.readState().currentRevision,
            )
        )
        assertTrue(err1.isFailure)
        assertTrue((err1 as OrganizationResult.Failure).error is OrganizationError.HabitNotFound)

        // 2. Target container is archived
        service.archive(ArchiveContainer(container.id, expectedRevision = store.readState().currentRevision))
        val h1 = HabitRef("h1")
        val err2 = service.placeHabit(
            PlaceHabit(
                habit = h1,
                targetContainerId = container.id,
                expectedRevision = store.readState().currentRevision,
            )
        )
        assertTrue(err2.isFailure)
        assertTrue((err2 as OrganizationResult.Failure).error is OrganizationError.TargetContainerArchived)
    }

    @Test
    fun testHistoricalLocation_baselineAndBeforeCutover() {
        val cutoverRev = OrganizationRevision(1L)
        val containerA = Container(
            id = ContainerId("c-a"),
            parentId = null,
            name = "Sphere A",
            color = null,
            icon = null,
            siblingOrder = 0,
            isArchived = false,
            createdAt = 1000L,
            updatedAt = 1000L,
            deletedAt = null,
            revision = cutoverRev,
        )

        val hUnknown = HabitRef("h-unknown")
        val hUnassigned = HabitRef("h-unassigned")
        val hPlaced = HabitRef("h1")

        // Seed baseline directly
        store.seedBaseline(
            revision = cutoverRev,
            recordedAt = 1000L,
            containersList = listOf(containerA),
            placementsList = listOf(
                HabitPlacement(hUnknown, null, 0, PlacementOrigin.UNKNOWN_LEGACY, cutoverRev),
                HabitPlacement(hUnassigned, null, 1, PlacementOrigin.USER_CHANGE, cutoverRev),
                HabitPlacement(hPlaced, containerA.id, 0, PlacementOrigin.MIGRATION_SNAPSHOT, cutoverRev),
            ),
        )

        // 1. Before cutover query -> UnknownBeforeCutover
        val beforeCutover = service.placementQueries.locationAtRevision(hPlaced, OrganizationRevision(0L))
        assertEquals(HistoricalLocation.UnknownBeforeCutover, beforeCutover)

        // 2. Unknown legacy unassigned -> UnknownLegacyAssignment
        val locUnknown = service.placementQueries.locationAtRevision(hUnknown, cutoverRev)
        assertEquals(HistoricalLocation.UnknownLegacyAssignment, locUnknown)

        // 3. Known unassigned -> Unassigned
        val locUnassigned = service.placementQueries.locationAtRevision(hUnassigned, cutoverRev)
        assertEquals(HistoricalLocation.Unassigned, locUnassigned)

        // 4. Placed habit -> Known path
        val locPlaced = service.placementQueries.locationAtRevision(hPlaced, cutoverRev)
        assertTrue(locPlaced is HistoricalLocation.Known)
        assertEquals(listOf(containerA.id), locPlaced.path.nodes)
        assertEquals(cutoverRev, locPlaced.path.revision)
    }

    @Test
    fun testAncestorMovePreservesHistoricalPathAtPriorRevision() {
        // Revision 1: baseline with Container A (root) and Habit h1 placed in A
        val rev1 = OrganizationRevision(1L)
        val containerA = Container(
            id = ContainerId("c-a"),
            parentId = null,
            name = "Project A",
            color = null,
            icon = null,
            siblingOrder = 0,
            isArchived = false,
            createdAt = 1000L,
            updatedAt = 1000L,
            deletedAt = null,
            revision = rev1,
        )
        val h1 = HabitRef("h1")
        store.seedBaseline(
            revision = rev1,
            recordedAt = 1000L,
            containersList = listOf(containerA),
            placementsList = listOf(
                HabitPlacement(h1, containerA.id, 0, PlacementOrigin.MIGRATION_SNAPSHOT, rev1)
            ),
        )

        // Revision 2: Create root Container B
        val resB = service.create(
            CreateContainer(
                name = "Area B",
                explicitId = ContainerId("c-b"),
                expectedRevision = store.readState().currentRevision,
            )
        )
        assertTrue(resB.isSuccess)
        val containerB = resB.getOrThrow()
        val rev2 = store.readState().currentRevision
        assertEquals(OrganizationRevision(2L), rev2)

        // Revision 3: Move Container A under Container B
        val moveRes = service.move(
            MoveContainer(
                id = containerA.id,
                newParentId = containerB.id,
                expectedRevision = rev2,
            )
        )
        assertTrue(moveRes.isSuccess)
        val rev3 = store.readState().currentRevision
        assertEquals(OrganizationRevision(3L), rev3)

        // Historical query at revision 1: path must be [c-a]
        val locAtRev1 = service.placementQueries.locationAtRevision(h1, rev1)
        assertTrue(locAtRev1 is HistoricalLocation.Known)
        assertEquals(listOf(containerA.id), locAtRev1.path.nodes)

        // Historical query at revision 3: path must be [c-b, c-a]
        val locAtRev3 = service.placementQueries.locationAtRevision(h1, rev3)
        assertTrue(locAtRev3 is HistoricalLocation.Known)
        assertEquals(listOf(containerB.id, containerA.id), locAtRev3.path.nodes)

        // Revision 4: Move habit h1 directly to Container B
        val moveHabitRes = service.placeHabit(
            PlaceHabit(
                habit = h1,
                targetContainerId = containerB.id,
                expectedRevision = rev3,
            )
        )
        assertTrue(moveHabitRes.isSuccess)
        val rev4 = store.readState().currentRevision
        assertEquals(OrganizationRevision(4L), rev4)

        // Query at rev 3 still reflects [c-b, c-a]
        val locAtRev3Again = service.placementQueries.locationAtRevision(h1, rev3)
        assertTrue(locAtRev3Again is HistoricalLocation.Known)
        assertEquals(listOf(containerB.id, containerA.id), locAtRev3Again.path.nodes)

        // Query at rev 4 reflects direct placement in [c-b]
        val locAtRev4 = service.placementQueries.locationAtRevision(h1, rev4)
        assertTrue(locAtRev4 is HistoricalLocation.Known)
        assertEquals(listOf(containerB.id), locAtRev4.path.nodes)
    }
}
