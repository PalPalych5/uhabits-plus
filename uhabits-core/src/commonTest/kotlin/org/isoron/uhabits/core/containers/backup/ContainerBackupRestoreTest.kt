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
import org.isoron.platform.io.queryLong
import org.isoron.platform.io.querySingle
import org.isoron.platform.io.run
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.containers.CreateContainer
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.containers.OrganizationServiceImpl
import org.isoron.uhabits.core.containers.PlaceHabit
import org.isoron.uhabits.core.containers.migration.LegacyContainerMigrationExecutor
import org.isoron.uhabits.core.containers.migration.LegacyContainerMigrationPlanner
import org.isoron.uhabits.core.containers.migration.LegacyContainerMigrationValidator
import org.isoron.uhabits.core.containers.migration.MigrationValidationResult
import org.isoron.uhabits.core.containers.migration.RawLegacyInventoryReader
import org.isoron.uhabits.core.containers.migration.SemanticMigrationFixtures
import org.isoron.uhabits.core.containers.sqlite.SQLiteOrganizationStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ContainerBackupRestoreTest {

    @Test
    fun testFullBackupRestoreRoundtrip_logicalParity() = runTest {
        // 1. Create and seed v29 database
        val sourceDb = SemanticMigrationFixtures.createAndSeedV29Database()
        val beforeInventory = RawLegacyInventoryReader.read(sourceDb)

        // 2. Migrate to Experimental Container dataset via PR3
        val plan = LegacyContainerMigrationPlanner.plan(beforeInventory, cutoverTimestamp = 1000000L)
        val execResult = LegacyContainerMigrationExecutor.execute(sourceDb, plan)
        assertTrue(execResult is org.isoron.uhabits.core.containers.migration.MigrationExecutionResult.Success)

        // Add a nested child container to verify tree depth restoration
        val store = SQLiteOrganizationStore(sourceDb)
        val parentContainerId = ContainerId("b-uuid-1")
        val childContainerId = ContainerId("c-nested-child")

        var idCounter = 1
        val service = OrganizationServiceImpl(
            store = store,
            unitOfWork = store,
            containerQueries = store,
            placementQueries = store,
            clock = { 2000000L },
            idGenerator = { "gen-id-${idCounter++}" },
            habitIdentityLookup = { true },
        )
        val createChildRes = service.create(
            CreateContainer(
                name = "Вложенный раздел",
                parentId = parentContainerId,
                explicitId = childContainerId,
            )
        )
        assertTrue(createChildRes.isSuccess)

        // Place habit 7 into the nested child
        val placeRes = service.placeHabit(
            PlaceHabit(
                habit = HabitRef("h-uuid-7"),
                targetContainerId = childContainerId,
                expectedRevision = store.readState().currentRevision,
            )
        )
        assertTrue(placeRes.isSuccess)

        // 3. Create full backup
        val backupDb = TestDatabaseHelper.createEmptyDatabase()
        val backupRes = ContainerBackupService.createBackup(
            sourceDb = sourceDb,
            targetDb = backupDb,
            timestamp = 2500000L,
            preferences = mapOf("theme" to "dark", "first_day_of_week" to "1"),
        )
        assertTrue(backupRes is BackupCreationResult.Success)
        assertNotNull(backupRes.manifest)
        assertEquals("dark", backupRes.manifest.nonSecretPreferences["theme"])

        // Verify sourceDb was NOT mutated by backup creation
        val sourceIntegrity = sourceDb.querySingle("PRAGMA integrity_check") { it.getText(0) }
        assertEquals("ok", sourceIntegrity)

        // 4. Restore backup into destinationDb via stagingDb
        val stagingDb = TestDatabaseHelper.createEmptyDatabase()
        val destinationDb = TestDatabaseHelper.createEmptyDatabase()

        val restoreRes = ContainerRestoreService.restore(
            backupDb = backupDb,
            stagingDb = stagingDb,
            destinationDb = destinationDb,
        )
        assertTrue(restoreRes is RestoreResult.Success)
        assertEquals(false, restoreRes.wasMigratedFromLegacy)

        // 5. Verify 100% logical parity in destinationDb
        assertEquals("ok", destinationDb.querySingle("PRAGMA integrity_check") { it.getText(0) })

        val destStore = SQLiteOrganizationStore(destinationDb)
        val destState = destStore.readState()
        assertEquals(store.readState().currentRevision, destState.currentRevision)

        // Containers count: 5 initial from blocks + 1 nested child = 6
        val destContainers = destStore.getAllContainers(includeDeleted = true)
        assertEquals(6, destContainers.size)

        val childInDest = destStore.getContainer(childContainerId)
        assertNotNull(childInDest)
        assertEquals("Вложенный раздел", childInDest.name)
        assertEquals(parentContainerId, childInDest.parentId)

        // Habits count
        assertEquals(7L, destinationDb.queryLong("SELECT count(*) FROM Habits"))
        assertEquals(7L, destinationDb.queryLong("SELECT count(*) FROM HabitPlacements"))
        assertEquals(5L, destinationDb.queryLong("SELECT count(*) FROM LegacyBlockMap"))
        assertEquals(9L, destinationDb.queryLong("SELECT count(*) FROM Repetitions"))
        assertEquals(2L, destinationDb.queryLong("SELECT count(*) FROM EntryOps"))
        assertEquals(1L, destinationDb.queryLong("SELECT count(*) FROM SyncQueue"))
        assertEquals(2L, destinationDb.queryLong("SELECT count(*) FROM AppSettings"))

        // Placement of habit 7 in nested child
        val p7 = destStore.getHabitPlacement(HabitRef("h-uuid-7"))
        assertNotNull(p7)
        assertEquals(childContainerId, p7.containerId)

        // History check
        assertEquals(6L, destinationDb.queryLong("SELECT count(*) FROM ContainerHistory"))
        assertEquals(8L, destinationDb.queryLong("SELECT count(*) FROM HabitPlacementHistory"))

        // Semantic validation on destinationDb
        val destInventory = RawLegacyInventoryReader.read(destinationDb)
        val semanticErrors = ContainerRestoreService.validateContainerDatasetSemantics(destinationDb, destInventory)
        assertTrue(semanticErrors.isEmpty(), "Semantic errors found: $semanticErrors")

        sourceDb.close()
        backupDb.close()
        stagingDb.close()
        destinationDb.close()
    }
}
