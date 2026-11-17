/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.core.containers.migration

import kotlinx.coroutines.test.runTest
import org.isoron.platform.io.TestDatabaseHelper
import org.isoron.platform.io.createTestDatabaseOpenerSuspend
import org.isoron.platform.io.createTestFileOpener
import org.isoron.platform.io.migrateTo
import org.isoron.platform.io.queryLong
import org.isoron.platform.io.querySingle
import org.isoron.platform.io.run
import org.isoron.platform.io.setVersion
import org.isoron.uhabits.core.DATABASE_VERSION
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LegacyContainerMigrationExecutorTest {

    private var fileCounter = 200

    @Test
    fun testSuccessfulStagingExecution() = runTest {
        val db = SemanticMigrationFixtures.createAndSeedV29Database()
        val inventory = RawLegacyInventoryReader.read(db)
        val plan = LegacyContainerMigrationPlanner.plan(inventory, cutoverTimestamp = 1000000L)

        val result = LegacyContainerMigrationExecutor.execute(db, plan)
        assertTrue(result is MigrationExecutionResult.Success)

        // Verify organization tables were populated
        assertEquals(5L, db.queryLong("SELECT count(*) FROM Containers"))
        assertEquals(5L, db.queryLong("SELECT count(*) FROM LegacyBlockMap"))
        assertEquals(7L, db.queryLong("SELECT count(*) FROM HabitPlacements"))
        assertEquals(5L, db.queryLong("SELECT count(*) FROM ContainerHistory"))
        assertEquals(7L, db.queryLong("SELECT count(*) FROM HabitPlacementHistory"))
        assertEquals(1L, db.queryLong("SELECT count(*) FROM OrganizationChanges"))
        assertEquals(1L, db.queryLong("SELECT current_revision FROM OrganizationState WHERE id = 1"))

        db.close()
    }

    @Test
    fun testChecksumMismatchRejection() = runTest {
        val db = SemanticMigrationFixtures.createAndSeedV29Database()
        val inventory = RawLegacyInventoryReader.read(db)
        val plan = LegacyContainerMigrationPlanner.plan(inventory, cutoverTimestamp = 1000000L)

        // Modify a row in the database so that its checksum changes
        db.run("UPDATE Habits SET name = 'Измененное имя' WHERE id = 1")

        val result = LegacyContainerMigrationExecutor.execute(db, plan)
        assertTrue(result is MigrationExecutionResult.Failure)
        assertTrue(result.issues.any { it is MigrationIssue.SourceChecksumMismatch })

        db.close()
    }

    @Test
    fun testFailureRollback_allTablesAtomic() = runTest {
        val db = SemanticMigrationFixtures.createAndSeedV29Database()
        val inventory = RawLegacyInventoryReader.read(db)
        val plan = LegacyContainerMigrationPlanner.plan(inventory, cutoverTimestamp = 1000000L)

        // Corrupt plan with impossible container ID to trigger an exception during insert
        val brokenPlan = plan.copy(
            legacyBlockMaps = plan.legacyBlockMaps.map {
                it.copy(containerId = org.isoron.uhabits.core.containers.ContainerId("non-existent-container-id"))
            }
        )

        val result = LegacyContainerMigrationExecutor.execute(db, brokenPlan)
        assertTrue(result is MigrationExecutionResult.Failure)

        // Verify that Containers table was rolled back / does not exist or has 0 rows
        val hasContainers = db.queryLong(
            "SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = 'Containers'"
        ) > 0
        if (hasContainers) {
            assertEquals(0L, db.queryLong("SELECT count(*) FROM Containers"))
        }

        db.close()
    }

    @Test
    fun testReopenAndPersistenceOfMigrationResult() = runTest {
        val fileOpener = createTestFileOpener()
        val tempFile = fileOpener.openUserFile("test-migration-reopen-${fileCounter++}.db")
        if (tempFile.exists()) {
            tempFile.delete()
        }
        val dbOpener = createTestDatabaseOpenerSuspend()

        var db1: org.isoron.platform.io.Database? = null
        var db2: org.isoron.platform.io.Database? = null

        try {
            val db = dbOpener.open(tempFile.pathString)
            db1 = db
            db.setVersion(8)
            db.migrateTo(DATABASE_VERSION) { v -> TestDatabaseHelper.loadMigrationSQL(v) }
            SemanticMigrationFixtures.seedAllFixtures(db)

            val inventory = RawLegacyInventoryReader.read(db)
            val plan = LegacyContainerMigrationPlanner.plan(inventory, cutoverTimestamp = 1000000L)

            val execResult = LegacyContainerMigrationExecutor.execute(db, plan)
            assertTrue(execResult is MigrationExecutionResult.Success)

            db.close()
            db1 = null

            // Reopen database connection
            val openedDb2 = dbOpener.open(tempFile.pathString)
            db2 = openedDb2

            assertEquals(5L, openedDb2.queryLong("SELECT count(*) FROM Containers"))
            assertEquals(7L, openedDb2.queryLong("SELECT count(*) FROM HabitPlacements"))
            assertEquals(5L, openedDb2.queryLong("SELECT count(*) FROM LegacyBlockMap"))
            assertEquals("ok", openedDb2.querySingle("PRAGMA integrity_check") { it.getText(0) })

            openedDb2.close()
            db2 = null
        } finally {
            try { db1?.close() } catch (_: Throwable) {}
            try { db2?.close() } catch (_: Throwable) {}
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }
}
