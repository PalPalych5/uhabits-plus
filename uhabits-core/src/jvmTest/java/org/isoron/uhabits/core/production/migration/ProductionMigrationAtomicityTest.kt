/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.core.production.migration

import kotlinx.coroutines.test.runTest
import org.isoron.platform.io.getVersion
import org.isoron.platform.io.queryLong
import org.isoron.uhabits.core.DATABASE_VERSION
import org.isoron.uhabits.core.LEGACY_DATABASE_VERSION
import org.isoron.uhabits.core.containers.migration.RawLegacyInventoryReader
import org.isoron.uhabits.core.containers.migration.SemanticMigrationFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductionMigrationAtomicityTest {

    @Test
    fun failureAtEachPointRollsBackCompletelyAndAllowsSubsequentSuccess() = runTest {
        val points = MigrationFailurePoint.values()
        assertEquals(7, points.size)

        for (point in points) {
            val db = SemanticMigrationFixtures.createAndSeedV29Database()
            try {
                val initialHabits = db.queryLong("SELECT count(*) FROM Habits")
                val initialBlocks = db.queryLong("SELECT count(*) FROM HabitBlocks")
                val initialVersion = db.getVersion()
                assertEquals(LEGACY_DATABASE_VERSION, initialVersion)

                // Attempt migration with failure injection
                val result = ProductionNextSchemaMigrator.migrate(
                    db = db,
                    cutoverTimestamp = 1700000000000L,
                    sourceFileSha256 = "aa".repeat(32),
                    sourceSchemaSha256 = "bb".repeat(32),
                    failureInjectionPoint = point,
                )

                assertTrue("Migration should fail at point $point", result is ProductionNextMigrationResult.Failure)
                val failure = result as ProductionNextMigrationResult.Failure
                assertTrue("Failure cause should be InjectedMigrationFailureException", failure.cause is InjectedMigrationFailureException)
                assertEquals(point, (failure.cause as InjectedMigrationFailureException).point)

                // Verify state after failure:
                // 1. Version remains 29
                assertEquals(LEGACY_DATABASE_VERSION, db.getVersion())

                // 2. Legacy rows are completely intact
                assertEquals(initialHabits, db.queryLong("SELECT count(*) FROM Habits"))
                assertEquals(initialBlocks, db.queryLong("SELECT count(*) FROM HabitBlocks"))

                // 3. No Next tables exist (transaction rolled back)
                val containersExists = db.queryLong(
                    "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='Containers'"
                ) > 0
                assertFalse("Containers table should not exist after rollback for point $point", containersExists)

                val organizationStateExists = db.queryLong(
                    "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='OrganizationState'"
                ) > 0
                assertFalse("OrganizationState table should not exist after rollback for point $point", organizationStateExists)

                // 4. Retry without failure injection succeeds completely
                val inventory = RawLegacyInventoryReader.read(db)
                val retryResult = ProductionNextSchemaMigrator.migrate(
                    db = db,
                    cutoverTimestamp = 1700000000000L,
                    sourceFileSha256 = "aa".repeat(32),
                    sourceSchemaSha256 = "bb".repeat(32),
                )
                assertTrue("Retry should succeed", retryResult is ProductionNextMigrationResult.Success)
                val success = retryResult as ProductionNextMigrationResult.Success
                assertEquals(30, db.getVersion())

                val validation = ProductionNextMigrationValidator.validate(
                    db = db,
                    beforeInventory = inventory,
                    plan = success.plan,
                    expectedMetadata = success.metadata,
                )
                assertTrue("Validation after retry should pass for point $point", validation is ProductionValidationResult.Valid)
            } finally {
                db.close()
            }
        }
    }
}
