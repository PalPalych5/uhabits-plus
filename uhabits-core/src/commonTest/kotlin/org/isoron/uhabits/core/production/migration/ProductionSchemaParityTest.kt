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
import org.isoron.platform.io.Database
import org.isoron.platform.io.StepResult
import org.isoron.platform.io.TestDatabaseHelper
import org.isoron.platform.io.run
import org.isoron.uhabits.core.containers.migration.RawLegacyInventoryReader
import org.isoron.uhabits.core.containers.migration.SemanticMigrationFixtures
import org.isoron.uhabits.core.containers.sqlite.OrganizationSchema
import org.isoron.uhabits.core.contribution.sqlite.ContributionSchema
import org.isoron.uhabits.core.focus.sqlite.FocusSessionSchema
import org.isoron.uhabits.core.focus.sqlite.SQLiteFocusCaptureStore
import org.isoron.uhabits.core.rules.sqlite.SourceRuleSchema
import org.isoron.uhabits.core.userTasks.sqlite.UserTaskSchema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProductionSchemaParityTest {

    data class ColumnInfo(
        val cid: Int,
        val name: String,
        val type: String,
        val notNull: Boolean,
        val defaultValue: String?,
        val pk: Int,
    )

    data class ForeignKeyInfo(
        val id: Int,
        val seq: Int,
        val toTable: String,
        val fromColumn: String,
        val toColumn: String?,
        val onUpdate: String,
        val onDelete: String,
    )

    data class IndexInfo(
        val name: String,
        val tableName: String,
        val sql: String?,
    )

    private fun readTableColumns(db: Database, table: String): List<ColumnInfo> {
        val list = mutableListOf<ColumnInfo>()
        val stmt = db.prepareStatement("PRAGMA table_info($table)")
        while (stmt.step() == StepResult.ROW) {
            list.add(
                ColumnInfo(
                    cid = stmt.getInt(0),
                    name = stmt.getText(1),
                    type = stmt.getText(2).uppercase(),
                    notNull = stmt.getInt(3) != 0,
                    defaultValue = stmt.getTextOrNull(4),
                    pk = stmt.getInt(5),
                )
            )
        }
        stmt.finalize()
        return list.sortedBy { it.name }
    }

    private fun readTableForeignKeys(db: Database, table: String): List<ForeignKeyInfo> {
        val list = mutableListOf<ForeignKeyInfo>()
        val stmt = db.prepareStatement("PRAGMA foreign_key_list($table)")
        while (stmt.step() == StepResult.ROW) {
            list.add(
                ForeignKeyInfo(
                    id = stmt.getInt(0),
                    seq = stmt.getInt(1),
                    toTable = stmt.getText(2),
                    fromColumn = stmt.getText(3),
                    toColumn = stmt.getTextOrNull(4),
                    onUpdate = stmt.getText(5),
                    onDelete = stmt.getText(6),
                )
            )
        }
        stmt.finalize()
        return list.sortedBy { "${it.fromColumn}->${it.toTable}" }
    }

    private fun readIndexes(db: Database): List<IndexInfo> {
        val list = mutableListOf<IndexInfo>()
        val stmt = db.prepareStatement("SELECT name, tbl_name, sql FROM sqlite_master WHERE type = 'index' AND name NOT LIKE 'sqlite_%' ORDER BY name")
        while (stmt.step() == StepResult.ROW) {
            list.add(
                IndexInfo(
                    name = stmt.getText(0),
                    tableName = stmt.getText(1),
                    sql = stmt.getTextOrNull(2),
                )
            )
        }
        stmt.finalize()
        return list
    }

    @Test
    fun migration30SqlMatchesRuntimeSchemaCreation() = runTest {
        val dbViaMigration = TestDatabaseHelper.createEmptyDatabase()
        val dbViaRuntimeHelpers = TestDatabaseHelper.createEmptyDatabase()

        try {
            // Setup DB via 30.sql
            val sql30 = TestDatabaseHelper.loadMigrationSQL(30)
            val statements = sql30.split(";").map { it.trim() }.filter { it.isNotEmpty() }
            for (stmt in statements) {
                dbViaMigration.run(stmt)
            }

            // Setup DB via runtime schema helpers
            OrganizationSchema.createSchema(dbViaRuntimeHelpers)
            UserTaskSchema.createSchema(dbViaRuntimeHelpers)
            FocusSessionSchema.createSchema(dbViaRuntimeHelpers)
            SQLiteFocusCaptureStore(dbViaRuntimeHelpers) // creates FocusCaptureJournal table
            ContributionSchema.createSchema(dbViaRuntimeHelpers)
            SourceRuleSchema.createSchema(dbViaRuntimeHelpers)

            val nextTables = listOf(
                "OrganizationState",
                "OrganizationChanges",
                "Containers",
                "HabitPlacements",
                "ContainerHistory",
                "HabitPlacementHistory",
                "LegacyBlockMap",
                "HabitVisualIdentities",
                "UserTasks",
                "FocusSessions",
                "FocusCaptureJournal",
                "Contributions",
                "SourceRules",
            )

            for (table in nextTables) {
                val colsMigration = readTableColumns(dbViaMigration, table)
                val colsRuntime = readTableColumns(dbViaRuntimeHelpers, table)
                assertEquals(
                    colsMigration,
                    colsRuntime,
                    "Column mismatch in table $table between 30.sql and runtime helpers",
                )

                val fkMigration = readTableForeignKeys(dbViaMigration, table)
                val fkRuntime = readTableForeignKeys(dbViaRuntimeHelpers, table)
                assertEquals(
                    fkMigration,
                    fkRuntime,
                    "Foreign key mismatch in table $table between 30.sql and runtime helpers",
                )
            }

            val idxMigration = readIndexes(dbViaMigration)
            val idxRuntime = readIndexes(dbViaRuntimeHelpers)
            val nextIdxMigration = idxMigration.filter { it.tableName in nextTables }.map { it.name }.sorted()
            val nextIdxRuntime = idxRuntime.filter { it.tableName in nextTables }.map { it.name }.sorted()

            assertEquals(
                nextIdxMigration,
                nextIdxRuntime,
                "Index mismatch between 30.sql and runtime helpers",
            )
        } finally {
            dbViaMigration.close()
            dbViaRuntimeHelpers.close()
        }
    }

    @Test
    fun validatorCatchesMutationsInAllEightLegacyTablesIndividually() = runTest {
        val mutationStatements = mapOf(
            "HabitBlocks" to "INSERT INTO HabitBlocks (uuid, name, color, position, is_archived, updated_at) VALUES ('b_extra', 'Extra Block', 0, 99, 0, 1000)",
            "Habits" to "INSERT INTO Habits (uuid, name, description, question, freq_num, freq_den, color, position, archived, type, target_value, target_type, unit, updated_at) VALUES ('h_extra', 'Extra Habit', '', '', 1, 1, 0, 99, 0, 0, 1.0, 0, '', 1000)",
            "HabitExtensions" to "UPDATE HabitExtensions SET day_tier = 'IDEAL' WHERE habit_id = (SELECT habit_id FROM HabitExtensions LIMIT 1)",
            "HabitGoals" to "INSERT INTO HabitGoals (habit_id, effective_timestamp, freq_num, freq_den, target_type, target_value, unit, uuid, updated_at) VALUES (1, 1000, 1, 1, 0, 1.0, '', 'g_extra', 1000)",
            "Repetitions" to "INSERT INTO Repetitions (habit, timestamp, value, uuid, updated_at) VALUES (1, 1700000000000, 2, 'r_extra', 1000)",
            "EntryOps" to "INSERT INTO EntryOps (op_uuid, habit_uuid, entry_timestamp, delta_value, op_type, device_id, created_at) VALUES ('op_extra', 'h1', 1700000000000, 2, 'ADD', 'dev1', 1000)",
            "SyncQueue" to "INSERT INTO SyncQueue (op_uuid, entity_type, entity_uuid, operation_type, payload_json, created_at, device_id) VALUES ('q_extra', 'Habit', 'h1', 'INSERT', '{}', 1000, 'dev1')",
            "AppSettings" to "INSERT INTO AppSettings (key, long_value) VALUES ('extra_setting_key', 42)",
        )

        assertEquals(8, mutationStatements.size, "Must test exactly 8 legacy tables")

        for ((tableName, mutationSql) in mutationStatements) {
            val db = SemanticMigrationFixtures.createAndSeedV29Database()
            try {
                val beforeInventory = RawLegacyInventoryReader.read(db)
                val result = ProductionNextSchemaMigrator.migrate(
                    db = db,
                    cutoverTimestamp = 1700000000000L,
                )
                assertIs<ProductionNextMigrationResult.Success>(result)

                // Inject mutation into legacy table after migration
                db.run(mutationSql)

                val validation = ProductionNextMigrationValidator.validate(
                    db = db,
                    beforeInventory = beforeInventory,
                    plan = result.plan,
                    expectedMetadata = result.metadata,
                )

                assertIs<ProductionValidationResult.Invalid>(
                    validation,
                    "Validator failed to reject mutation in legacy table '$tableName'",
                )

                assertTrue(
                    validation.errors.any { it.contains(tableName) || it.contains("checksum") },
                    "Expected error mentioning '$tableName' or 'checksum', but got: ${validation.errors}",
                )
            } finally {
                db.close()
            }
        }
    }
}
