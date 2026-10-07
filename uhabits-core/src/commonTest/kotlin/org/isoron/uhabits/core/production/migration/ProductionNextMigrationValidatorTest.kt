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
import org.isoron.platform.io.run
import org.isoron.uhabits.core.containers.migration.RawLegacyInventoryReader
import org.isoron.uhabits.core.containers.migration.SemanticMigrationFixtures
import org.isoron.uhabits.core.production.cutover.ProductionCutoverGate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProductionNextMigrationValidatorTest {

    @Test
    fun validMigratedDatabasePassesValidation() = runTest {
        val db = SemanticMigrationFixtures.createAndSeedV29Database()
        try {
            val inventory = RawLegacyInventoryReader.read(db)
            val result = ProductionNextSchemaMigrator.migrate(
                db = db,
                cutoverTimestamp = 1700000000000L,
                sourceFileSha256 = "11".repeat(32),
                sourceSchemaSha256 = "22".repeat(32),
            )
            assertIs<ProductionNextMigrationResult.Success>(result)

            val validation = ProductionNextMigrationValidator.validate(
                db = db,
                beforeInventory = inventory,
                plan = result.plan,
                expectedMetadata = result.metadata,
            )

            assertIs<ProductionValidationResult.Valid>(validation)
        } finally {
            db.close()
        }
    }

    @Test
    fun validationFailsIfVersionIsNot30() = runTest {
        val db = SemanticMigrationFixtures.createAndSeedV29Database()
        try {
            val inventory = RawLegacyInventoryReader.read(db)
            val result = ProductionNextSchemaMigrator.migrate(
                db = db,
                cutoverTimestamp = 1700000000000L,
            )
            assertIs<ProductionNextMigrationResult.Success>(result)

            db.run("PRAGMA user_version = 29")

            val validation = ProductionNextMigrationValidator.validate(
                db = db,
                beforeInventory = inventory,
                plan = result.plan,
                expectedMetadata = result.metadata,
            )
            assertIs<ProductionValidationResult.Invalid>(validation)
            assertTrue(validation.errors.any { it.contains("user_version") })
        } finally {
            db.close()
        }
    }

    @Test
    fun validationFailsIfTableIsMissing() = runTest {
        val db = SemanticMigrationFixtures.createAndSeedV29Database()
        try {
            val inventory = RawLegacyInventoryReader.read(db)
            val result = ProductionNextSchemaMigrator.migrate(
                db = db,
                cutoverTimestamp = 1700000000000L,
            )
            assertIs<ProductionNextMigrationResult.Success>(result)

            db.run("DROP TABLE UserTasks")

            val validation = ProductionNextMigrationValidator.validate(
                db = db,
                beforeInventory = inventory,
                plan = result.plan,
                expectedMetadata = result.metadata,
            )
            assertIs<ProductionValidationResult.Invalid>(validation)
            assertTrue(validation.errors.any { it.contains("UserTasks") })
        } finally {
            db.close()
        }
    }

    @Test
    fun validationFailsIfLegacyRowCountDiffers() = runTest {
        val db = SemanticMigrationFixtures.createAndSeedV29Database()
        try {
            val inventory = RawLegacyInventoryReader.read(db)
            val result = ProductionNextSchemaMigrator.migrate(
                db = db,
                cutoverTimestamp = 1700000000000L,
            )
            assertIs<ProductionNextMigrationResult.Success>(result)

            db.run("INSERT INTO AppSettings (key, long_value) VALUES ('extra_setting', 1)")

            val validation = ProductionNextMigrationValidator.validate(
                db = db,
                beforeInventory = inventory,
                plan = result.plan,
                expectedMetadata = result.metadata,
            )
            assertIs<ProductionValidationResult.Invalid>(validation)
            assertTrue(validation.errors.any { it.contains("AppSettings") })
        } finally {
            db.close()
        }
    }

    @Test
    fun validationFailsIfFactStoreIsNotEmpty() = runTest {
        val db = SemanticMigrationFixtures.createAndSeedV29Database()
        try {
            val inventory = RawLegacyInventoryReader.read(db)
            val result = ProductionNextSchemaMigrator.migrate(
                db = db,
                cutoverTimestamp = 1700000000000L,
            )
            assertIs<ProductionNextMigrationResult.Success>(result)

            db.run("INSERT INTO UserTasks (id, title, created_at, updated_at) VALUES ('t1', 'Title', 1000, 1000)")

            val validation = ProductionNextMigrationValidator.validate(
                db = db,
                beforeInventory = inventory,
                plan = result.plan,
                expectedMetadata = result.metadata,
            )
            assertIs<ProductionValidationResult.Invalid>(validation)
            assertTrue(validation.errors.any { it.contains("UserTasks") })
        } finally {
            db.close()
        }
    }

    @Test
    fun validationFailsIfMetadataAuthorityIsNotLegacy() = runTest {
        val db = SemanticMigrationFixtures.createAndSeedV29Database()
        try {
            val inventory = RawLegacyInventoryReader.read(db)
            val result = ProductionNextSchemaMigrator.migrate(
                db = db,
                cutoverTimestamp = 1700000000000L,
            )
            assertIs<ProductionNextMigrationResult.Success>(result)

            db.run("UPDATE OrganizationState SET runtime_authority = 'NEXT'")

            val validation = ProductionNextMigrationValidator.validate(
                db = db,
                beforeInventory = inventory,
                plan = result.plan,
                expectedMetadata = result.metadata,
            )
            assertIs<ProductionValidationResult.Invalid>(validation)
            assertTrue(validation.errors.any { it.contains("runtime_authority") })
        } finally {
            db.close()
        }
    }

    @Test
    fun validationFailsIfContainerPositionsAreSparse() = runTest {
        val db = SemanticMigrationFixtures.createAndSeedV29Database()
        try {
            val inventory = RawLegacyInventoryReader.read(db)
            val result = ProductionNextSchemaMigrator.migrate(
                db = db,
                cutoverTimestamp = 1700000000000L,
            )
            assertIs<ProductionNextMigrationResult.Success>(result)

            db.run("UPDATE Containers SET sibling_order = 99 WHERE sibling_order = (SELECT MAX(sibling_order) FROM Containers)")

            val validation = ProductionNextMigrationValidator.validate(
                db = db,
                beforeInventory = inventory,
                plan = result.plan,
                expectedMetadata = result.metadata,
            )
            assertIs<ProductionValidationResult.Invalid>(validation)
            assertTrue(validation.errors.any { it.contains("sibling_order") })
        } finally {
            db.close()
        }
    }
}
