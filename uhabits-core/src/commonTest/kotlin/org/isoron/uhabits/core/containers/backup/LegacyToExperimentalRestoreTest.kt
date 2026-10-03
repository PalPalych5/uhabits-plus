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
import org.isoron.uhabits.core.containers.migration.LegacyContainerMigrationPlanner
import org.isoron.uhabits.core.containers.migration.LegacyContainerMigrationValidator
import org.isoron.uhabits.core.containers.migration.MigrationValidationResult
import org.isoron.uhabits.core.containers.migration.RawLegacyInventoryReader
import org.isoron.uhabits.core.containers.migration.SemanticMigrationFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LegacyToExperimentalRestoreTest {

    @Test
    fun testLegacyV29BackupRestoresToExperimentalDataset_sourceUntouched() = runTest {
        // 1. Create pure legacy v29 database
        val legacyDb = SemanticMigrationFixtures.createAndSeedV29Database()
        val beforeInventory = RawLegacyInventoryReader.read(legacyDb)
        val beforeSha = beforeInventory.snapshotSha256

        val stagingDb = TestDatabaseHelper.createEmptyDatabase()
        val destinationDb = TestDatabaseHelper.createEmptyDatabase()

        // 2. Perform restore through ContainerRestoreService
        val cutoverTime = 123456789L
        val restoreRes = ContainerRestoreService.restore(
            backupDb = legacyDb,
            stagingDb = stagingDb,
            destinationDb = destinationDb,
            cutoverTimestamp = cutoverTime,
        )

        // 3. Verify restore succeeded as migrated legacy
        assertTrue(restoreRes is RestoreResult.Success)
        assertEquals(true, restoreRes.wasMigratedFromLegacy)
        assertEquals(29, restoreRes.manifest.sourceSchemaVersion)
        assertEquals(1, restoreRes.manifest.foundationVersion)

        // 4. CRITICAL: Verify source legacyDb was NOT modified in-place
        val afterInventory = RawLegacyInventoryReader.read(legacyDb)
        assertEquals(beforeSha, afterInventory.snapshotSha256, "Source legacy backup must remain byte-exact untouched")
        assertEquals(beforeInventory.habits, afterInventory.habits)
        assertEquals(beforeInventory.blocks, afterInventory.blocks)
        assertEquals(beforeInventory.repetitions, afterInventory.repetitions)

        // Verify source DB still has NO organization tables
        val legacyOrgCount = legacyDb.queryLong(
            "SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = 'OrganizationState'"
        )
        assertEquals(0L, legacyOrgCount, "Source legacy DB must not have OrganizationState created in-place")

        // 5. Verify destinationDb has full experimental dataset
        assertEquals("ok", destinationDb.querySingle("PRAGMA integrity_check") { it.getText(0) })
        assertEquals(5L, destinationDb.queryLong("SELECT count(*) FROM Containers"))
        assertEquals(7L, destinationDb.queryLong("SELECT count(*) FROM HabitPlacements"))
        assertEquals(5L, destinationDb.queryLong("SELECT count(*) FROM LegacyBlockMap"))
        assertEquals(1L, destinationDb.queryLong("SELECT count(*) FROM OrganizationState"))

        // 6. Verify destinationDb passes PR3 semantic validator
        val plan = LegacyContainerMigrationPlanner.plan(beforeInventory, cutoverTimestamp = cutoverTime)
        val validation = LegacyContainerMigrationValidator.validate(destinationDb, beforeInventory, plan)
        assertTrue(validation is MigrationValidationResult.Valid, "Resulting experimental dataset must pass semantic validator")

        legacyDb.close()
        stagingDb.close()
        destinationDb.close()
    }
}
