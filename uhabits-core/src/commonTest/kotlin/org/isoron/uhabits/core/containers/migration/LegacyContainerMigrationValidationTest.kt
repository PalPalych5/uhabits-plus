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
import org.isoron.platform.io.run
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LegacyContainerMigrationValidationTest {

    @Test
    fun testSuccessfulSemanticValidation() = runTest {
        val db = SemanticMigrationFixtures.createAndSeedV29Database()
        val beforeInventory = RawLegacyInventoryReader.read(db)
        val plan = LegacyContainerMigrationPlanner.plan(beforeInventory, cutoverTimestamp = 1000000L)

        val execResult = LegacyContainerMigrationExecutor.execute(db, plan)
        assertTrue(execResult is MigrationExecutionResult.Success)

        val validation = LegacyContainerMigrationValidator.validate(db, beforeInventory, plan)
        assertTrue(validation is MigrationValidationResult.Valid, "Validation must pass cleanly for valid migration: ${(validation as? MigrationValidationResult.Invalid)?.errors}")

        db.close()
    }

    @Test
    fun testValidatorDetectsLegacyDataModification_habitAltered() = runTest {
        val db = SemanticMigrationFixtures.createAndSeedV29Database()
        val beforeInventory = RawLegacyInventoryReader.read(db)
        val plan = LegacyContainerMigrationPlanner.plan(beforeInventory, cutoverTimestamp = 1000000L)

        val execResult = LegacyContainerMigrationExecutor.execute(db, plan)
        assertTrue(execResult is MigrationExecutionResult.Success)

        // Mutate a legacy habit name after migration
        db.run("UPDATE Habits SET name = 'Несанкционированное изменение' WHERE id = 1")

        val validation = LegacyContainerMigrationValidator.validate(db, beforeInventory, plan)
        assertTrue(validation is MigrationValidationResult.Invalid)
        assertTrue(validation.errors.any { it.contains("Habits table was altered") || it.contains("checksum changed") })

        db.close()
    }

    @Test
    fun testValidatorDetectsLegacyDataModification_repetitionAltered() = runTest {
        val db = SemanticMigrationFixtures.createAndSeedV29Database()
        val beforeInventory = RawLegacyInventoryReader.read(db)
        val plan = LegacyContainerMigrationPlanner.plan(beforeInventory, cutoverTimestamp = 1000000L)

        val execResult = LegacyContainerMigrationExecutor.execute(db, plan)
        assertTrue(execResult is MigrationExecutionResult.Success)

        // Mutate a legacy repetition value
        db.run("UPDATE Repetitions SET value = 99999 WHERE habit = 1 AND timestamp = 10000")

        val validation = LegacyContainerMigrationValidator.validate(db, beforeInventory, plan)
        assertTrue(validation is MigrationValidationResult.Invalid)
        assertTrue(validation.errors.any { it.contains("Repetitions table was altered") || it.contains("checksum changed") })

        db.close()
    }

    @Test
    fun testValidatorDetectsLegacyDataModification_entryOpDeleted() = runTest {
        val db = SemanticMigrationFixtures.createAndSeedV29Database()
        val beforeInventory = RawLegacyInventoryReader.read(db)
        val plan = LegacyContainerMigrationPlanner.plan(beforeInventory, cutoverTimestamp = 1000000L)

        val execResult = LegacyContainerMigrationExecutor.execute(db, plan)
        assertTrue(execResult is MigrationExecutionResult.Success)

        // Delete an entry op
        db.run("DELETE FROM EntryOps WHERE op_uuid = 'op-uuid-1'")

        val validation = LegacyContainerMigrationValidator.validate(db, beforeInventory, plan)
        assertTrue(validation is MigrationValidationResult.Invalid)
        assertTrue(validation.errors.any { it.contains("EntryOps table was altered") || it.contains("checksum changed") })

        db.close()
    }

    @Test
    fun testValidatorDetectsUnexpectedTable() = runTest {
        val db = SemanticMigrationFixtures.createAndSeedV29Database()
        val beforeInventory = RawLegacyInventoryReader.read(db)
        val plan = LegacyContainerMigrationPlanner.plan(beforeInventory, cutoverTimestamp = 1000000L)

        val execResult = LegacyContainerMigrationExecutor.execute(db, plan)
        assertTrue(execResult is MigrationExecutionResult.Success)

        // Create an unauthorized rogue table
        db.run("CREATE TABLE RogueTable (id INTEGER PRIMARY KEY)")

        val validation = LegacyContainerMigrationValidator.validate(db, beforeInventory, plan)
        assertTrue(validation is MigrationValidationResult.Invalid)
        assertTrue(validation.errors.any { it.contains("Unexpected table found in database: 'RogueTable'") })

        db.close()
    }
}
