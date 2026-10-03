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

package org.isoron.uhabits.core.containers.backup

import kotlinx.coroutines.test.runTest
import org.isoron.platform.io.TestDatabaseHelper
import org.isoron.platform.io.migrateTo
import org.isoron.platform.io.queryLong
import org.isoron.platform.io.querySingle
import org.isoron.platform.io.run
import org.isoron.uhabits.core.DATABASE_VERSION
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.containers.migration.LegacyContainerMigrationExecutor
import org.isoron.uhabits.core.containers.migration.LegacyContainerMigrationPlanner
import org.isoron.uhabits.core.containers.migration.RawLegacyInventoryReader
import org.isoron.uhabits.core.containers.migration.SemanticMigrationFixtures
import org.isoron.uhabits.core.containers.sqlite.SQLiteOrganizationStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ContainerMergeImportTest {

    @Test
    fun testMergeImportOfContainerDataset_explicitlyRejectedAsUnsupported() = runTest {
        // Target DB: has experimental container dataset
        val targetDb = SemanticMigrationFixtures.createAndSeedV29Database()
        val targetInventory = RawLegacyInventoryReader.read(targetDb)
        val targetPlan = LegacyContainerMigrationPlanner.plan(targetInventory, cutoverTimestamp = 1000000L)
        LegacyContainerMigrationExecutor.execute(targetDb, targetPlan)

        // Source DB: ALSO has experimental container dataset
        val sourceDb = SemanticMigrationFixtures.createAndSeedV29Database()
        val sourceInventory = RawLegacyInventoryReader.read(sourceDb)
        val sourcePlan = LegacyContainerMigrationPlanner.plan(sourceInventory, cutoverTimestamp = 1000000L)
        LegacyContainerMigrationExecutor.execute(sourceDb, sourcePlan)

        // Attempting to merge-import sourceDb into targetDb
        val result = LegacyHabitMergeImporter.importLegacyHabits(
            sourceDb = sourceDb,
            targetDb = targetDb,
            targetPlacement = ImportPlacementPolicy.Unassigned,
        )

        assertTrue(result is MergeImportResult.Unsupported)
        assertTrue(result.reason.contains("not supported in Foundation"))

        targetDb.close()
        sourceDb.close()
    }

    @Test
    fun testMergeImportLegacyHabits_withExplicitSpecificContainer() = runTest {
        // Target DB: has experimental container dataset with 5 containers, 7 habits
        val targetDb = SemanticMigrationFixtures.createAndSeedV29Database()
        val targetInventory = RawLegacyInventoryReader.read(targetDb)
        val targetPlan = LegacyContainerMigrationPlanner.plan(targetInventory, cutoverTimestamp = 1000000L)
        LegacyContainerMigrationExecutor.execute(targetDb, targetPlan)

        // Source DB: pure legacy DB with a single new unique habit
        val sourceDb = TestDatabaseHelper.createEmptyDatabase()
        sourceDb.migrateTo(DATABASE_VERSION) { TestDatabaseHelper.loadMigrationSQL(it) }
        sourceDb.run("INSERT INTO Habits (id, uuid, name, description, freq_num, freq_den, color, position) VALUES (10, 'h-new-unique', 'Медитация', 'Утренняя', 1, 1, 4, 10);")
        sourceDb.run("INSERT INTO HabitExtensions (habit_id, day_tier, timer_enabled) VALUES (10, 'NORMAL', 0);")
        sourceDb.run("INSERT INTO Repetitions (habit, timestamp, value, notes, uuid, updated_at) VALUES (10, 50000, 2, 'Хорошая сессия', 'rep-med-1', 50000);")

        val targetContainer = ContainerId("b-uuid-1")
        val result = LegacyHabitMergeImporter.importLegacyHabits(
            sourceDb = sourceDb,
            targetDb = targetDb,
            targetPlacement = ImportPlacementPolicy.SpecificContainer(targetContainer),
        )

        assertTrue(result is MergeImportResult.Success, "Expected Success but got: $result")
        assertEquals(1, result.importedHabitCount)
        assertEquals(listOf("h-new-unique"), result.importedHabitUuids)

        // Verify habit was placed into targetContainer in targetDb
        val store = SQLiteOrganizationStore(targetDb)
        val placement = store.getHabitPlacement(HabitRef("h-new-unique"))
        assertNotNull(placement)
        assertEquals(targetContainer, placement.containerId)

        // Verify repetitions were copied
        val repCount = targetDb.queryLong(
            "SELECT count(*) FROM Repetitions r JOIN Habits h ON r.habit = h.id WHERE h.uuid = 'h-new-unique'"
        )
        assertEquals(1L, repCount)

        targetDb.close()
        sourceDb.close()
    }

    @Test
    fun testMergeImportLegacyHabits_withExplicitUnassigned() = runTest {
        // Target DB
        val targetDb = SemanticMigrationFixtures.createAndSeedV29Database()
        val targetInventory = RawLegacyInventoryReader.read(targetDb)
        val targetPlan = LegacyContainerMigrationPlanner.plan(targetInventory, cutoverTimestamp = 1000000L)
        LegacyContainerMigrationExecutor.execute(targetDb, targetPlan)

        // Source DB
        val sourceDb = TestDatabaseHelper.createEmptyDatabase()
        sourceDb.migrateTo(DATABASE_VERSION) { TestDatabaseHelper.loadMigrationSQL(it) }
        sourceDb.run(
            """
            INSERT INTO Habits (id, uuid, name, description, freq_num, freq_den, color, position)
            VALUES (20, 'h-unassigned-unique', 'Йога', 'Вечерняя', 1, 1, 2, 20);
            """.trimIndent()
        )

        val result = LegacyHabitMergeImporter.importLegacyHabits(
            sourceDb = sourceDb,
            targetDb = targetDb,
            targetPlacement = ImportPlacementPolicy.Unassigned,
        )

        assertTrue(result is MergeImportResult.Success, "Expected Success but got: $result")
        val store = SQLiteOrganizationStore(targetDb)
        val placement = store.getHabitPlacement(HabitRef("h-unassigned-unique"))
        assertNotNull(placement)
        assertEquals(null, placement.containerId)

        targetDb.close()
        sourceDb.close()
    }

    @Test
    fun testMergeImportLegacyHabits_uuidCollision_rejectOnConflict() = runTest {
        // Target DB has habit with uuid 'h-uuid-1'
        val targetDb = SemanticMigrationFixtures.createAndSeedV29Database()
        val targetInventory = RawLegacyInventoryReader.read(targetDb)
        val targetPlan = LegacyContainerMigrationPlanner.plan(targetInventory, cutoverTimestamp = 1000000L)
        LegacyContainerMigrationExecutor.execute(targetDb, targetPlan)

        // Source DB also has habit with uuid 'h-uuid-1'
        val sourceDb = TestDatabaseHelper.createEmptyDatabase()
        sourceDb.migrateTo(DATABASE_VERSION) { TestDatabaseHelper.loadMigrationSQL(it) }
        sourceDb.run(
            """
            INSERT INTO Habits (id, uuid, name, description, freq_num, freq_den, color, position)
            VALUES (1, 'h-uuid-1', 'Конфликтная привычка', '', 1, 1, 1, 0);
            """.trimIndent()
        )

        val result = LegacyHabitMergeImporter.importLegacyHabits(
            sourceDb = sourceDb,
            targetDb = targetDb,
            targetPlacement = ImportPlacementPolicy.Unassigned,
            conflictPolicy = ImportConflictPolicy.REJECT_ON_CONFLICT,
        )

        assertTrue(result is MergeImportResult.Conflict, "Expected Conflict but got: $result")
        assertEquals(1, result.conflicts.size)
        val c = result.conflicts[0] as ImportConflict.UuidCollision
        assertEquals("h-uuid-1", c.existingUuid)
        assertEquals("Конфликтная привычка", c.incomingHabitName)

        // Target DB was NOT modified
        assertEquals(7L, targetDb.queryLong("SELECT count(*) FROM Habits"))

        targetDb.close()
        sourceDb.close()
    }

    @Test
    fun testMergeImportLegacyHabits_uuidCollision_generateNewUuid() = runTest {
        // Target DB has habit with uuid 'h-uuid-1'
        val targetDb = SemanticMigrationFixtures.createAndSeedV29Database()
        val targetInventory = RawLegacyInventoryReader.read(targetDb)
        val targetPlan = LegacyContainerMigrationPlanner.plan(targetInventory, cutoverTimestamp = 1000000L)
        LegacyContainerMigrationExecutor.execute(targetDb, targetPlan)

        val originalPlacement = SQLiteOrganizationStore(targetDb).getHabitPlacement(HabitRef("h-uuid-1"))
        assertNotNull(originalPlacement)

        // Source DB also has habit with uuid 'h-uuid-1'
        val sourceDb = TestDatabaseHelper.createEmptyDatabase()
        sourceDb.migrateTo(DATABASE_VERSION) { TestDatabaseHelper.loadMigrationSQL(it) }
        sourceDb.run(
            """
            INSERT INTO Habits (id, uuid, name, description, freq_num, freq_den, color, position)
            VALUES (1, 'h-uuid-1', 'Конфликтная привычка', '', 1, 1, 1, 0);
            """.trimIndent()
        )

        val result = LegacyHabitMergeImporter.importLegacyHabits(
            sourceDb = sourceDb,
            targetDb = targetDb,
            targetPlacement = ImportPlacementPolicy.SpecificContainer(ContainerId("b-uuid-2")),
            conflictPolicy = ImportConflictPolicy.GENERATE_NEW_UUID,
        )

        assertTrue(result is MergeImportResult.Success, "Expected Success but got: $result")
        assertEquals(1, result.importedHabitCount)
        assertTrue(result.remappedUuids.containsKey("h-uuid-1"))
        val remappedUuid = result.remappedUuids["h-uuid-1"]!!

        // Total habits in targetDb is now 8
        assertEquals(8L, targetDb.queryLong("SELECT count(*) FROM Habits"))

        // Original habit 'h-uuid-1' retained its placement
        val store = SQLiteOrganizationStore(targetDb)
        val pOriginal = store.getHabitPlacement(HabitRef("h-uuid-1"))
        assertEquals(originalPlacement.containerId, pOriginal?.containerId)

        // Remapped habit got the new placement in Container 2
        val pRemapped = store.getHabitPlacement(HabitRef(remappedUuid))
        assertNotNull(pRemapped)
        assertEquals(ContainerId("b-uuid-2"), pRemapped.containerId)

        targetDb.close()
        sourceDb.close()
    }
}
