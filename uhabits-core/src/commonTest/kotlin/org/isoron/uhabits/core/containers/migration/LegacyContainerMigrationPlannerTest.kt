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

package org.isoron.uhabits.core.containers.migration

import kotlinx.coroutines.test.runTest
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.containers.PlacementOrigin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LegacyContainerMigrationPlannerTest {

    @Test
    fun testCleanPlanningWithSemanticFixtures() = runTest {
        val db = SemanticMigrationFixtures.createAndSeedV29Database()
        val inventory = RawLegacyInventoryReader.read(db)

        val plan = LegacyContainerMigrationPlanner.plan(inventory, cutoverTimestamp = 1000000L)

        assertTrue(plan.canMigrate, "Clean inventory must produce actionable plan without blocking issues: ${plan.issues}")
        assertEquals(5, plan.containers.size)
        assertEquals(7, plan.placements.size)
        assertEquals(5, plan.legacyBlockMaps.size)

        // Check container mappings
        val container1 = plan.containers.first { it.id == ContainerId("b-uuid-1") }
        assertEquals("Интеллект / обучение", container1.name)
        assertNull(container1.parentId)
        assertEquals(0, container1.siblingOrder)

        // Check duplicated name blocks are preserved separately
        val bodyContainers = plan.containers.filter { it.name == "Тело" }
        assertEquals(2, bodyContainers.size)
        assertTrue(bodyContainers.any { it.id == ContainerId("b-uuid-2") })
        assertTrue(bodyContainers.any { it.id == ContainerId("b-uuid-5") })

        // Check placements
        val p1 = plan.placements.first { it.habit == HabitRef("h-uuid-1") }
        assertEquals(ContainerId("b-uuid-1"), p1.containerId)
        assertEquals(PlacementOrigin.MIGRATION_SNAPSHOT, p1.origin)
        assertEquals(0, p1.localOrder)

        val p7 = plan.placements.first { it.habit == HabitRef("h-uuid-7") }
        assertEquals(ContainerId("b-uuid-1"), p7.containerId)
        assertEquals(PlacementOrigin.MIGRATION_SNAPSHOT, p7.origin)
        assertEquals(1, p7.localOrder, "Habits in same container must have dense localOrder (0, 1)")

        // Check unassigned habit
        val p4 = plan.placements.first { it.habit == HabitRef("h-uuid-4") }
        assertNull(p4.containerId)
        assertEquals(PlacementOrigin.MIGRATION_SNAPSHOT, p4.origin)

        // Check missing extension habit -> UNKNOWN_LEGACY
        val p6 = plan.placements.first { it.habit == HabitRef("h-uuid-6") }
        assertNull(p6.containerId)
        assertEquals(PlacementOrigin.UNKNOWN_LEGACY, p6.origin)

        // Check baseline record & state
        assertEquals(1L, plan.organizationState.currentRevision.value)
        assertEquals(inventory.snapshotSha256, plan.organizationState.sourceSnapshotSha256)
        assertEquals("baseline-cutover", plan.baselineRecord.opUuid)

        db.close()
    }

    @Test
    fun testDeterministicPlanning_sameSnapshotProducesIdenticalPlan() = runTest {
        val db = SemanticMigrationFixtures.createAndSeedV29Database()
        val inventory = RawLegacyInventoryReader.read(db)

        val plan1 = LegacyContainerMigrationPlanner.plan(inventory, cutoverTimestamp = 1000000L)
        val plan2 = LegacyContainerMigrationPlanner.plan(inventory, cutoverTimestamp = 1000000L)

        assertEquals(plan1.containers, plan2.containers)
        assertEquals(plan1.placements, plan2.placements)
        assertEquals(plan1.legacyBlockMaps, plan2.legacyBlockMaps)
        assertEquals(plan1.organizationState, plan2.organizationState)
        assertEquals(plan1.baselineRecord, plan2.baselineRecord)
        assertEquals(plan1.sourceSnapshotSha256, plan2.sourceSnapshotSha256)

        db.close()
    }

    @Test
    fun testIssueDetection_missingReferencedBlock() = runTest {
        val db = SemanticMigrationFixtures.createAndSeedV29Database()
        val inventory = RawLegacyInventoryReader.read(db)

        // Create an inventory where habit 1 references non-existent block 999
        val brokenExtensions = inventory.extensions.map {
            if (it.habitId == 1L) it.copy(blockId = 999L) else it
        }
        val brokenInventory = inventory.copy(extensions = brokenExtensions)

        val plan = LegacyContainerMigrationPlanner.plan(brokenInventory, cutoverTimestamp = 1000000L)
        assertFalse(plan.canMigrate)
        assertTrue(plan.issues.any { it is MigrationIssue.MissingReferencedBlock })

        db.close()
    }

    @Test
    fun testIssueDetection_activeHabitReferencingTombstonedBlock() = runTest {
        val db = SemanticMigrationFixtures.createAndSeedV29Database()
        val inventory = RawLegacyInventoryReader.read(db)

        // In fixtures, block 4 is tombstoned (deleted_at != null). Point active habit 1 to block 4
        val brokenExtensions = inventory.extensions.map {
            if (it.habitId == 1L) it.copy(blockId = 4L) else it
        }
        val brokenInventory = inventory.copy(extensions = brokenExtensions)

        val plan = LegacyContainerMigrationPlanner.plan(brokenInventory, cutoverTimestamp = 1000000L)
        assertFalse(plan.canMigrate)
        assertTrue(plan.issues.any { it is MigrationIssue.ActiveHabitReferencingTombstonedBlock })

        db.close()
    }

    @Test
    fun testIssueDetection_duplicateBlockUuid() = runTest {
        val db = SemanticMigrationFixtures.createAndSeedV29Database()
        val inventory = RawLegacyInventoryReader.read(db)

        val duplicateBlocks = inventory.blocks.map {
            if (it.id == 2L) it.copy(uuid = "b-uuid-1") else it
        }
        val brokenInventory = inventory.copy(blocks = duplicateBlocks)

        val plan = LegacyContainerMigrationPlanner.plan(brokenInventory, cutoverTimestamp = 1000000L)
        assertFalse(plan.canMigrate)
        assertTrue(plan.issues.any { it is MigrationIssue.DuplicateBlockUuid })

        db.close()
    }

    @Test
    fun testIssueDetection_blankBlockUuid() = runTest {
        val db = SemanticMigrationFixtures.createAndSeedV29Database()
        val inventory = RawLegacyInventoryReader.read(db)

        val blankBlocks = inventory.blocks.map {
            if (it.id == 1L) it.copy(uuid = "") else it
        }
        val brokenInventory = inventory.copy(blocks = blankBlocks)

        val plan = LegacyContainerMigrationPlanner.plan(brokenInventory, cutoverTimestamp = 1000000L)
        assertFalse(plan.canMigrate)
        assertTrue(plan.issues.any { it is MigrationIssue.BlankOrMissingBlockUuid })

        db.close()
    }

    @Test
    fun testIssueDetection_blankHabitUuid() = runTest {
        val db = SemanticMigrationFixtures.createAndSeedV29Database()
        val inventory = RawLegacyInventoryReader.read(db)

        val blankHabits = inventory.habits.map {
            if (it.id == 1L) it.copy(uuid = null) else it
        }
        val brokenInventory = inventory.copy(habits = blankHabits)

        val plan = LegacyContainerMigrationPlanner.plan(brokenInventory, cutoverTimestamp = 1000000L)
        assertFalse(plan.canMigrate)
        assertTrue(plan.issues.any { it is MigrationIssue.BlankOrMissingHabitUuid })

        db.close()
    }
}
