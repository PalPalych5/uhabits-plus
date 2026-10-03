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
import org.isoron.platform.io.run
import org.isoron.uhabits.core.containers.migration.LegacyContainerMigrationExecutor
import org.isoron.uhabits.core.containers.migration.LegacyContainerMigrationPlanner
import org.isoron.uhabits.core.containers.migration.RawLegacyInventoryReader
import org.isoron.uhabits.core.containers.migration.SemanticMigrationFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InvalidBackupRejectionTest {

    @Test
    fun testRejectsBackup_missingOrganizationTables_destinationUntouched() = runTest {
        val brokenBackupDb = SemanticMigrationFixtures.createAndSeedV29Database()
        val beforeInventory = RawLegacyInventoryReader.read(brokenBackupDb)
        val plan = LegacyContainerMigrationPlanner.plan(beforeInventory, cutoverTimestamp = 1000000L)
        LegacyContainerMigrationExecutor.execute(brokenBackupDb, plan)

        // Drop ContainerHistory and LegacyBlockMap to simulate incomplete/corrupted backup
        brokenBackupDb.run("DROP TABLE ContainerHistory")
        brokenBackupDb.run("DROP TABLE LegacyBlockMap")

        val stagingDb = TestDatabaseHelper.createEmptyDatabase()
        val destinationDb = TestDatabaseHelper.createEmptyDatabase()
        destinationDb.run("CREATE TABLE ExistingSentinel (id INTEGER PRIMARY KEY)")

        val restoreRes = ContainerRestoreService.restore(
            backupDb = brokenBackupDb,
            stagingDb = stagingDb,
            destinationDb = destinationDb,
        )

        assertTrue(restoreRes is RestoreResult.Failure)
        assertTrue(restoreRes.reason.contains("Incomplete organization schema") || restoreRes.reason.contains("Malformed"))

        // Destination was NOT touched
        assertEquals(1L, destinationDb.queryLong("SELECT count(*) FROM sqlite_master WHERE type='table' AND name='ExistingSentinel'"))
        assertEquals(0L, destinationDb.queryLong("SELECT count(*) FROM sqlite_master WHERE type='table' AND name='Containers'"))

        brokenBackupDb.close()
        stagingDb.close()
        destinationDb.close()
    }

    @Test
    fun testRejectsBackup_checksumMismatch_destinationUntouched() = runTest {
        val backupDb = SemanticMigrationFixtures.createAndSeedV29Database()
        val beforeInventory = RawLegacyInventoryReader.read(backupDb)
        val plan = LegacyContainerMigrationPlanner.plan(beforeInventory, cutoverTimestamp = 1000000L)
        LegacyContainerMigrationExecutor.execute(backupDb, plan)

        // Create manifest with a bogus checksum
        val fakeManifest = ContainerBackupManifest(
            formatVersion = 1,
            sourceSchemaVersion = 29,
            foundationVersion = 1,
            datasetMode = "CONTAINER_LOCAL",
            datasetUuid = "dataset-fake",
            createdAt = 1000000L,
            sourceSnapshotSha256 = "0000000000000000000000000000000000000000000000000000000000000000",
            canonicalChecksum = "fake-checksum",
        )
        DatabaseSnapshotter.writeManifest(backupDb, fakeManifest)

        val stagingDb = TestDatabaseHelper.createEmptyDatabase()
        val destinationDb = TestDatabaseHelper.createEmptyDatabase()
        destinationDb.run("CREATE TABLE ExistingSentinel (id INTEGER PRIMARY KEY)")

        val restoreRes = ContainerRestoreService.restore(
            backupDb = backupDb,
            stagingDb = stagingDb,
            destinationDb = destinationDb,
            externalManifest = fakeManifest,
        )

        assertTrue(restoreRes is RestoreResult.Failure)
        assertTrue(restoreRes.reason.contains("checksum mismatch") || restoreRes.reason.contains("Source snapshot checksum mismatch"))

        // Destination was NOT touched
        assertEquals(1L, destinationDb.queryLong("SELECT count(*) FROM sqlite_master WHERE type='table' AND name='ExistingSentinel'"))
        assertEquals(0L, destinationDb.queryLong("SELECT count(*) FROM sqlite_master WHERE type='table' AND name='Containers'"))

        backupDb.close()
        stagingDb.close()
        destinationDb.close()
    }

    @Test
    fun testRejectsBackup_unsupportedNewerFoundationVersion() = runTest {
        val backupDb = SemanticMigrationFixtures.createAndSeedV29Database()
        val beforeInventory = RawLegacyInventoryReader.read(backupDb)
        val plan = LegacyContainerMigrationPlanner.plan(beforeInventory, cutoverTimestamp = 1000000L)
        LegacyContainerMigrationExecutor.execute(backupDb, plan)

        // Set OrganizationState foundation_version = 999
        backupDb.run("UPDATE OrganizationState SET foundation_version = 999 WHERE id = 1")

        val stagingDb = TestDatabaseHelper.createEmptyDatabase()
        val destinationDb = TestDatabaseHelper.createEmptyDatabase()

        val restoreRes = ContainerRestoreService.restore(
            backupDb = backupDb,
            stagingDb = stagingDb,
            destinationDb = destinationDb,
        )

        assertTrue(restoreRes is RestoreResult.Failure)
        assertTrue(restoreRes.reason.contains("Unsupported") || restoreRes.reason.contains("foundation_version 999"))

        backupDb.close()
        stagingDb.close()
        destinationDb.close()
    }

    @Test
    fun testRejectsBackup_containerHierarchyCycle() = runTest {
        val backupDb = SemanticMigrationFixtures.createAndSeedV29Database()
        val beforeInventory = RawLegacyInventoryReader.read(backupDb)
        val plan = LegacyContainerMigrationPlanner.plan(beforeInventory, cutoverTimestamp = 1000000L)
        LegacyContainerMigrationExecutor.execute(backupDb, plan)

        // Create a circular parent dependency: Container 1 parent = Container 2, Container 2 parent = Container 1
        backupDb.run("PRAGMA foreign_keys = OFF")
        backupDb.run("UPDATE Containers SET parent_id = 2 WHERE id = 1")
        backupDb.run("UPDATE Containers SET parent_id = 1 WHERE id = 2")
        backupDb.run("PRAGMA foreign_keys = ON")

        val manifest = ContainerBackupManifest(
            formatVersion = 1,
            sourceSchemaVersion = 29,
            foundationVersion = 1,
            datasetMode = "CONTAINER_LOCAL",
            datasetUuid = plan.organizationState.datasetUuid,
            createdAt = 1000000L,
            sourceSnapshotSha256 = beforeInventory.snapshotSha256,
            canonicalChecksum = "valid-checksum",
        )
        DatabaseSnapshotter.writeManifest(backupDb, manifest)

        val stagingDb = TestDatabaseHelper.createEmptyDatabase()
        val destinationDb = TestDatabaseHelper.createEmptyDatabase()

        val restoreRes = ContainerRestoreService.restore(
            backupDb = backupDb,
            stagingDb = stagingDb,
            destinationDb = destinationDb,
        )

        assertTrue(restoreRes is RestoreResult.Failure)
        assertTrue(restoreRes.errors.any { it.contains("Cycle detected") } || restoreRes.reason.contains("semantic validation"))

        backupDb.close()
        stagingDb.close()
        destinationDb.close()
    }
}
