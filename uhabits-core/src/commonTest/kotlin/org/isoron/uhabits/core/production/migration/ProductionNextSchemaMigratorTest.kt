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

package org.isoron.uhabits.core.production.migration

import kotlinx.coroutines.test.runTest
import org.isoron.platform.io.StepResult
import org.isoron.platform.io.getVersion
import org.isoron.platform.io.queryLong
import org.isoron.platform.io.queryString
import org.isoron.platform.io.run
import org.isoron.uhabits.core.DATABASE_VERSION
import org.isoron.uhabits.core.LEGACY_DATABASE_VERSION
import org.isoron.uhabits.core.containers.migration.RawLegacyInventoryReader
import org.isoron.uhabits.core.containers.migration.SemanticMigrationFixtures
import org.isoron.uhabits.core.production.cutover.ProductionCutoverGate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProductionNextSchemaMigratorTest {

    @Test
    fun migratesV29ToV30Successfully() = runTest {
        val db = SemanticMigrationFixtures.createAndSeedV29Database()
        try {
            val initialVersion = db.getVersion()
            assertEquals(LEGACY_DATABASE_VERSION, initialVersion)

            val initialHabitsCount = db.queryLong("SELECT count(*) FROM Habits")
            val initialBlocksCount = db.queryLong("SELECT count(*) FROM HabitBlocks")

            val cutoverTimestamp = 1700000000000L
            val migrationResult = ProductionNextSchemaMigrator.migrate(
                db = db,
                cutoverTimestamp = cutoverTimestamp,
                sourceFileSha256 = "11".repeat(32),
                sourceSchemaSha256 = "22".repeat(32),
            )

            assertIs<ProductionNextMigrationResult.Success>(migrationResult)
            assertEquals(30, db.getVersion())
            assertEquals(initialBlocksCount.toInt(), migrationResult.plan.containers.size)
            assertEquals(initialHabitsCount.toInt(), migrationResult.plan.placements.size)

            // Verify empty fact and rule tables
            assertEquals(0L, db.queryLong("SELECT count(*) FROM UserTasks"))
            assertEquals(0L, db.queryLong("SELECT count(*) FROM FocusSessions"))
            assertEquals(0L, db.queryLong("SELECT count(*) FROM FocusCaptureJournal"))
            assertEquals(0L, db.queryLong("SELECT count(*) FROM Contributions"))
            assertEquals(0L, db.queryLong("SELECT count(*) FROM SourceRules"))

            // Verify OrganizationState
            val mode = db.queryString("SELECT mode FROM OrganizationState")
            val runtimeAuthority = db.queryString("SELECT runtime_authority FROM OrganizationState")
            val authorityEpoch = db.queryLong("SELECT authority_epoch FROM OrganizationState")
            val sourceFileSha = db.queryString("SELECT source_file_sha256 FROM OrganizationState")
            val sourceSchemaSha = db.queryString("SELECT source_schema_sha256 FROM OrganizationState")

            assertEquals("PRODUCTION_NEXT", mode)
            assertEquals(ProductionCutoverGate.AUTHORITY_LEGACY, runtimeAuthority)
            assertEquals(0L, authorityEpoch)
            assertEquals("11".repeat(32), sourceFileSha)
            assertEquals("22".repeat(32), sourceSchemaSha)

            // Verify legacy rows are preserved
            assertEquals(initialHabitsCount, db.queryLong("SELECT count(*) FROM Habits"))
            assertEquals(initialBlocksCount, db.queryLong("SELECT count(*) FROM HabitBlocks"))

            // Verify container order is dense 0..N-1
            val positions = mutableListOf<Int>()
            val stmt = db.prepareStatement("SELECT sibling_order FROM Containers ORDER BY sibling_order ASC")
            while (stmt.step() == StepResult.ROW) {
                positions.add(stmt.getInt(0))
            }
            stmt.finalize()
            assertEquals((0 until initialBlocksCount.toInt()).toList(), positions)
        } finally {
            db.close()
        }
    }

    @Test
    fun failsIfDatabaseVersionIsNot29() = runTest {
        val db = SemanticMigrationFixtures.createAndSeedV29Database()
        try {
            db.run("PRAGMA user_version = 28")
            val result = ProductionNextSchemaMigrator.migrate(
                db = db,
                cutoverTimestamp = 1700000000000L,
            )
            assertIs<ProductionNextMigrationResult.Failure>(result)
            assertTrue(result.reason.contains("Database version must be 29"))
        } finally {
            db.close()
        }
    }
}
