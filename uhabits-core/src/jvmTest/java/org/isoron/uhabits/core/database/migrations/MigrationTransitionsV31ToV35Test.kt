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

package org.isoron.uhabits.core.database.migrations

import kotlinx.coroutines.test.runTest
import org.isoron.platform.io.Database
import org.isoron.platform.io.JavaDatabaseOpener
import org.isoron.platform.io.StepResult
import org.isoron.platform.io.TestDatabaseHelper
import org.isoron.platform.io.getVersion
import org.isoron.platform.io.migrateTo
import org.isoron.platform.io.queryLong
import org.isoron.platform.io.queryString
import org.isoron.platform.io.run
import org.isoron.platform.io.runInTransaction
import org.isoron.platform.io.setVersion
import org.isoron.uhabits.core.database.SQLParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Focused SQLite migration transition tests for v31->v32, v32->v33, v33->v34, and v34->v35
 * using [TestDatabaseHelper] and actual migration SQL files.
 *
 * Validates:
 * - Preserved synthetic existing data across legacy and next tables.
 * - Expected schema tables, columns, constraints, and indexes.
 * - SQLite PRAGMA integrity_check and foreign_key_check.
 * - Idempotent no-op version handling.
 * - Transaction rollback of partial DDL/DML mutations on injected statement failure.
 * - Independent re-open persistence on disk.
 */
class MigrationTransitionsV31ToV35Test {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val opener = JavaDatabaseOpener()

    private data class ColumnInfo(
        val name: String,
        val type: String,
        val notNull: Boolean,
        val defaultValue: String?
    )

    private fun getTableColumns(db: Database, tableName: String): Map<String, ColumnInfo> {
        val result = mutableMapOf<String, ColumnInfo>()
        val stmt = db.prepareStatement("PRAGMA table_info($tableName)")
        try {
            while (stmt.step() == StepResult.ROW) {
                val name = stmt.getText(1)
                val type = stmt.getText(2)
                val notNull = stmt.getInt(3) == 1
                val defaultVal = stmt.getTextOrNull(4)
                result[name] = ColumnInfo(name, type, notNull, defaultVal)
            }
        } finally {
            stmt.finalize()
        }
        return result
    }

    private fun getIndexNames(db: Database): Set<String> {
        val result = mutableSetOf<String>()
        val stmt = db.prepareStatement("SELECT name FROM sqlite_master WHERE type = 'index'")
        try {
            while (stmt.step() == StepResult.ROW) {
                result.add(stmt.getText(0))
            }
        } finally {
            stmt.finalize()
        }
        return result
    }

    private fun assertIntegrityAndForeignKeys(db: Database, context: String) {
        val integrity = db.queryString("PRAGMA integrity_check")
        assertEquals("$context: integrity_check must be ok", "ok", integrity)
        val fkErrors = db.queryLong("SELECT count(*) FROM pragma_foreign_key_check()")
        assertEquals("$context: foreign_key_check must have 0 errors", 0L, fkErrors)
    }

    private suspend fun createDatabaseAtV31(file: File): Database {
        val db = opener.open(file.absolutePath)
        db.run("PRAGMA foreign_keys = ON")
        db.setVersion(8)
        db.migrateTo(31) { TestDatabaseHelper.loadMigrationSQL(it) }
        assertEquals(31, db.getVersion())
        return db
    }

    private fun seedSyntheticV31Data(db: Database) {
        // Legacy data
        db.run("INSERT INTO Habits(id, name, freq_num, freq_den, color, position, archived, type, uuid) " +
                "VALUES (101, 'Exercise', 1, 1, 1, 0, 0, 0, 'habit-exercise-uuid')")
        db.run("INSERT INTO Habits(id, name, freq_num, freq_den, color, position, archived, type, uuid) " +
                "VALUES (102, 'Read', 1, 1, 2, 1, 0, 0, 'habit-read-uuid')")
        db.run("INSERT INTO HabitBlocks(id, name, color, position, is_archived, uuid) " +
                "VALUES (201, 'Health Sphere', 1, 0, 0, 'block-health-uuid')")
        db.run("INSERT INTO Repetitions(habit, timestamp, value) VALUES (101, 1000000, 2)")

        // Container Next data
        db.run("INSERT INTO OrganizationChanges(revision, op_uuid, recorded_at, operation_type, origin, command_payload) " +
                "VALUES (1, 'op-1', 1000, 'CREATE', 'MIGRATION', '{}')")
        db.run("INSERT INTO Containers(id, uuid, parent_id, name, sibling_order, is_archived, updated_at, revision) " +
                "VALUES (1, 'c-health', NULL, 'Health', 0, 0, 1000, 1)")
        db.run("INSERT INTO HabitPlacements(habit_id, container_id, local_order, placement_origin, revision) " +
                "VALUES (101, 1, 0, 'USER_CHANGE', 1)")

        // UserTasks with various due/plan configurations at v31
        // Task 1: plan-only task (due_kind='NONE', planned_date set) -> candidate for review_pending in v32
        db.run("INSERT INTO UserTasks(id, title, description, container_id, status, planned_date, deadline, " +
                "due_kind, due_date, due_time_millis, due_zone, sort_order, created_at, updated_at) " +
                "VALUES ('task-plan-none', 'Plan Review Needed', 'Desc', 'c-health', 'PENDING', '2026-11-15', NULL, " +
                "'NONE', NULL, NULL, NULL, 0, 1000, 1000)")

        // Task 2: timed task with deadline -> should NOT have review_pending in v32
        db.run("INSERT INTO UserTasks(id, title, description, container_id, status, planned_date, deadline, " +
                "due_kind, due_date, due_time_millis, due_zone, sort_order, created_at, updated_at) " +
                "VALUES ('task-timed', 'Timed Deadline Task', 'Desc', 'c-health', 'PENDING', '2026-11-15', 1792432800000, " +
                "'TIMED', NULL, 1792432800000, 'UTC', 1, 1000, 1000)")

        // Task 3: plan-less undated task -> should NOT have review_pending in v32
        db.run("INSERT INTO UserTasks(id, title, description, container_id, status, planned_date, deadline, " +
                "due_kind, due_date, due_time_millis, due_zone, sort_order, created_at, updated_at) " +
                "VALUES ('task-undated', 'Undated Task', 'Desc', 'c-health', 'PENDING', NULL, NULL, " +
                "'NONE', NULL, NULL, NULL, 2, 1000, 1000)")
    }

    /**
     * Builds an injected SQL script that executes [genuineStatementsToExecute] genuine statements
     * parsed from the real migration SQL, followed by a deterministic invalid statement
     * that forces a SQLite execution error.
     */
    private suspend fun buildInjectedFailingMigrationSql(
        targetVersion: Int,
        genuineStatementsToExecute: Int
    ): String {
        val rawSql = TestDatabaseHelper.loadMigrationSQL(targetVersion)
        val commands = SQLParser.parse(rawSql)
        assertTrue(
            "Migration $targetVersion has ${commands.size} commands, expected >= $genuineStatementsToExecute",
            commands.size >= genuineStatementsToExecute
        )
        val genuinePart = commands.take(genuineStatementsToExecute).joinToString(";\n")
        return "$genuinePart;\nINSERT INTO non_existent_table_for_rollback_test VALUES (1);"
    }

    // =========================================================================
    // 1. Transition v31 -> v32
    // =========================================================================

    @Test
    fun testTransitionV31ToV32PreservesDataAndMigratesReviewPending() = runTest {
        val dbFile = File(tempFolder.newFolder(), "v31_to_v32.db")
        val db = createDatabaseAtV31(dbFile)
        try {
            seedSyntheticV31Data(db)

            val habitsBefore = db.queryLong("SELECT count(*) FROM Habits")
            val containersBefore = db.queryLong("SELECT count(*) FROM Containers")
            val tasksBefore = db.queryLong("SELECT count(*) FROM UserTasks")

            // Execute migration v31 -> v32
            db.migrateTo(32) { TestDatabaseHelper.loadMigrationSQL(it) }

            assertEquals(32, db.getVersion())

            // Schema verification
            val columns = getTableColumns(db, "UserTasks")
            assertTrue("legacy_plan_review_pending column must exist", "legacy_plan_review_pending" in columns)
            val reviewCol = columns["legacy_plan_review_pending"]!!
            assertEquals("INTEGER", reviewCol.type.uppercase())
            assertTrue(reviewCol.notNull)
            assertEquals("0", reviewCol.defaultValue)

            // Constraint verification: CHECK(legacy_plan_review_pending IN (0, 1))
            try {
                db.run("INSERT INTO UserTasks(id, title, created_at, updated_at, due_kind, legacy_plan_review_pending) " +
                        "VALUES ('task-invalid-review', 'Bad', 1, 1, 'NONE', 2)")
                fail("CHECK constraint on legacy_plan_review_pending IN (0, 1) should have failed")
            } catch (e: Exception) {
                // Expected constraint failure
            }

            // Data migration verification
            // Task 1: due_kind='NONE' and planned_date is not null -> review_pending = 1
            val review1 = db.queryLong("SELECT legacy_plan_review_pending FROM UserTasks WHERE id = 'task-plan-none'")
            assertEquals("Plan-only task must be marked legacy_plan_review_pending=1", 1L, review1)

            // Task 2: due_kind='TIMED' -> review_pending = 0
            val review2 = db.queryLong("SELECT legacy_plan_review_pending FROM UserTasks WHERE id = 'task-timed'")
            assertEquals("Timed task must retain legacy_plan_review_pending=0", 0L, review2)

            // Task 3: planned_date is null -> review_pending = 0
            val review3 = db.queryLong("SELECT legacy_plan_review_pending FROM UserTasks WHERE id = 'task-undated'")
            assertEquals("Undated task must retain legacy_plan_review_pending=0", 0L, review3)

            // Data fidelity
            assertEquals("Raw planned_date must be preserved", "2026-11-15",
                db.queryString("SELECT planned_date FROM UserTasks WHERE id = 'task-plan-none'"))
            assertEquals(habitsBefore, db.queryLong("SELECT count(*) FROM Habits"))
            assertEquals(containersBefore, db.queryLong("SELECT count(*) FROM Containers"))
            assertEquals(tasksBefore, db.queryLong("SELECT count(*) FROM UserTasks"))

            // Integrity and foreign keys
            assertIntegrityAndForeignKeys(db, "v31->v32")

            // Idempotent no-op
            db.migrateTo(32) { TestDatabaseHelper.loadMigrationSQL(it) }
            assertEquals(32, db.getVersion())
            assertEquals(tasksBefore, db.queryLong("SELECT count(*) FROM UserTasks"))
        } finally {
            db.close()
        }

        // Verify independent re-open persistence
        val reopened = opener.open(dbFile.absolutePath)
        try {
            assertEquals(32, reopened.getVersion())
            assertEquals(1L, reopened.queryLong("SELECT legacy_plan_review_pending FROM UserTasks WHERE id = 'task-plan-none'"))
        } finally {
            reopened.close()
        }
    }

    // =========================================================================
    // 2. Transition v32 -> v33
    // =========================================================================

    @Test
    fun testTransitionV32ToV33CreatesTaskTagSchemaAndPreservesData() = runTest {
        val dbFile = File(tempFolder.newFolder(), "v32_to_v33.db")
        val db = createDatabaseAtV31(dbFile)
        try {
            seedSyntheticV31Data(db)
            db.migrateTo(32) { TestDatabaseHelper.loadMigrationSQL(it) }
            assertEquals(32, db.getVersion())

            val tasksBefore = db.queryLong("SELECT count(*) FROM UserTasks")
            val habitsBefore = db.queryLong("SELECT count(*) FROM Habits")

            // Execute migration v32 -> v33
            db.migrateTo(33) { TestDatabaseHelper.loadMigrationSQL(it) }

            assertEquals(33, db.getVersion())

            // Schema verification: tables created by 33.sql
            val tables = mutableSetOf<String>()
            val stmt = db.prepareStatement("SELECT name FROM sqlite_master WHERE type = 'table'")
            while (stmt.step() == StepResult.ROW) tables.add(stmt.getText(0))
            stmt.finalize()

            assertTrue("TaskTagState table must exist", "TaskTagState" in tables)
            assertTrue("TaskTags table must exist", "TaskTags" in tables)
            assertTrue("TaskTagLinks table must exist", "TaskTagLinks" in tables)
            assertTrue("TaskTagOutbox table must exist", "TaskTagOutbox" in tables)

            // Index verification
            val indexes = getIndexNames(db)
            assertTrue("idx_task_tag_links_tag must exist", "idx_task_tag_links_tag" in indexes)

            // Initial row in TaskTagState
            val stateRevision = db.queryLong("SELECT revision FROM TaskTagState WHERE id = 1")
            assertEquals("Initial tag revision must be 0", 0L, stateRevision)

            // Test constraints and foreign keys on new tables
            db.run("INSERT INTO TaskTags(id, name, normalized_name, updated_at) " +
                    "VALUES ('tag-urgent', 'Urgent Work', 'urgent work', 1700000000)")
            db.run("INSERT INTO TaskTagLinks(task_id, tag_id) VALUES ('task-plan-none', 'tag-urgent')")

            // Link query works
            assertEquals(1L, db.queryLong("SELECT count(*) FROM TaskTagLinks WHERE task_id = 'task-plan-none'"))

            // Foreign key CASCADE verification: deleting task removes link
            db.run("DELETE FROM UserTasks WHERE id = 'task-plan-none'")
            assertEquals("CASCADE delete must remove link when task is deleted",
                0L, db.queryLong("SELECT count(*) FROM TaskTagLinks WHERE task_id = 'task-plan-none'"))
            assertEquals("Tag entity itself must survive task deletion",
                1L, db.queryLong("SELECT count(*) FROM TaskTags WHERE id = 'tag-urgent'"))

            // Foreign key enforcement: linking nonexistent task fails
            try {
                db.run("INSERT INTO TaskTagLinks(task_id, tag_id) VALUES ('nonexistent-task', 'tag-urgent')")
                val errors = db.queryLong("SELECT count(*) FROM pragma_foreign_key_check()")
                assertTrue("Foreign key violation must be detected", errors > 0)
                db.run("DELETE FROM TaskTagLinks WHERE task_id = 'nonexistent-task'")
            } catch (e: Exception) {
                // Expected if immediate FK enforcement triggered
            }

            // Existing habits intact
            assertEquals(habitsBefore, db.queryLong("SELECT count(*) FROM Habits"))

            // Integrity and foreign keys
            assertIntegrityAndForeignKeys(db, "v32->v33")

            // Idempotent no-op
            db.migrateTo(33) { TestDatabaseHelper.loadMigrationSQL(it) }
            assertEquals(33, db.getVersion())
            assertEquals(0L, db.queryLong("SELECT revision FROM TaskTagState WHERE id = 1"))
        } finally {
            db.close()
        }

        // Verify independent re-open persistence
        val reopened = opener.open(dbFile.absolutePath)
        try {
            assertEquals(33, reopened.getVersion())
            assertEquals(1L, reopened.queryLong("SELECT count(*) FROM TaskTags WHERE id = 'tag-urgent'"))
        } finally {
            reopened.close()
        }
    }

    // =========================================================================
    // 3. Transition v33 -> v34
    // =========================================================================

    @Test
    fun testTransitionV33ToV34AddsPriorityColumnsAndPreservesData() = runTest {
        val dbFile = File(tempFolder.newFolder(), "v33_to_v34.db")
        val db = createDatabaseAtV31(dbFile)
        try {
            seedSyntheticV31Data(db)
            db.migrateTo(33) { TestDatabaseHelper.loadMigrationSQL(it) }
            assertEquals(33, db.getVersion())

            val tasksBefore = db.queryLong("SELECT count(*) FROM UserTasks")
            val habitsBefore = db.queryLong("SELECT count(*) FROM Habits")

            // Execute migration v33 -> v34
            db.migrateTo(34) { TestDatabaseHelper.loadMigrationSQL(it) }

            assertEquals(34, db.getVersion())

            // Schema verification: important and urgent columns
            val columns = getTableColumns(db, "UserTasks")
            assertTrue("important column must exist", "important" in columns)
            assertTrue("urgent column must exist", "urgent" in columns)

            // Existing rows default to NULL for both columns
            val nullImportant = db.queryLong("SELECT count(*) FROM UserTasks WHERE important IS NULL")
            val nullUrgent = db.queryLong("SELECT count(*) FROM UserTasks WHERE urgent IS NULL")
            assertEquals("All existing tasks must have NULL important", tasksBefore, nullImportant)
            assertEquals("All existing tasks must have NULL urgent", tasksBefore, nullUrgent)

            // Test updating with valid priority values
            db.run("UPDATE UserTasks SET important = 1, urgent = 0 WHERE id = 'task-timed'")
            assertEquals(1L, db.queryLong("SELECT important FROM UserTasks WHERE id = 'task-timed'"))
            assertEquals(0L, db.queryLong("SELECT urgent FROM UserTasks WHERE id = 'task-timed'"))

            // Test CHECK constraint: important IN (0, 1) and urgent IN (0, 1)
            try {
                db.run("UPDATE UserTasks SET important = 2 WHERE id = 'task-undated'")
                fail("CHECK constraint on important IN (0, 1) should have failed")
            } catch (e: Exception) {
                // Expected constraint violation
            }

            try {
                db.run("UPDATE UserTasks SET urgent = -1 WHERE id = 'task-undated'")
                fail("CHECK constraint on urgent IN (0, 1) should have failed")
            } catch (e: Exception) {
                // Expected constraint violation
            }

            // Existing data fidelity
            assertEquals(tasksBefore, db.queryLong("SELECT count(*) FROM UserTasks"))
            assertEquals(habitsBefore, db.queryLong("SELECT count(*) FROM Habits"))
            assertEquals("Timed Deadline Task", db.queryString("SELECT title FROM UserTasks WHERE id = 'task-timed'"))

            // Integrity and foreign keys
            assertIntegrityAndForeignKeys(db, "v33->v34")

            // Idempotent no-op
            db.migrateTo(34) { TestDatabaseHelper.loadMigrationSQL(it) }
            assertEquals(34, db.getVersion())
        } finally {
            db.close()
        }

        // Verify independent re-open persistence
        val reopened = opener.open(dbFile.absolutePath)
        try {
            assertEquals(34, reopened.getVersion())
            assertEquals(1L, reopened.queryLong("SELECT important FROM UserTasks WHERE id = 'task-timed'"))
            assertEquals(0L, reopened.queryLong("SELECT urgent FROM UserTasks WHERE id = 'task-timed'"))
        } finally {
            reopened.close()
        }
    }

    // =========================================================================
    // 4. Transition v34 -> v35
    // =========================================================================

    @Test
    fun testTransitionV34ToV35AddsHabitLinkColumnsAndPreservesData() = runTest {
        val dbFile = File(tempFolder.newFolder(), "v34_to_v35.db")
        val db = createDatabaseAtV31(dbFile)
        try {
            seedSyntheticV31Data(db)
            db.migrateTo(34) { TestDatabaseHelper.loadMigrationSQL(it) }
            assertEquals(34, db.getVersion())

            val tasksBefore = db.queryLong("SELECT count(*) FROM UserTasks")
            val habitsBefore = db.queryLong("SELECT count(*) FROM Habits")

            // Execute migration v34 -> v35
            db.migrateTo(35) { TestDatabaseHelper.loadMigrationSQL(it) }

            assertEquals(35, db.getVersion())

            // Schema verification: habit link columns
            val columns = getTableColumns(db, "UserTasks")
            assertTrue("linked_habit_uuid column must exist", "linked_habit_uuid" in columns)
            assertTrue("habit_time_accrual_enabled column must exist", "habit_time_accrual_enabled" in columns)
            assertTrue("habit_link_effective_from column must exist", "habit_link_effective_from" in columns)

            val accrualCol = columns["habit_time_accrual_enabled"]!!
            assertEquals("INTEGER", accrualCol.type.uppercase())
            assertTrue(accrualCol.notNull)
            assertEquals("0", accrualCol.defaultValue)

            // Index verification
            val indexes = getIndexNames(db)
            assertTrue("idx_user_tasks_linked_habit must exist", "idx_user_tasks_linked_habit" in indexes)

            // Existing tasks default values
            val defaultAccrual = db.queryLong("SELECT count(*) FROM UserTasks WHERE habit_time_accrual_enabled = 0")
            assertEquals(tasksBefore, defaultAccrual)
            val nullHabitUuid = db.queryLong("SELECT count(*) FROM UserTasks WHERE linked_habit_uuid IS NULL")
            assertEquals(tasksBefore, nullHabitUuid)

            // Test updating habit link fields
            db.run("UPDATE UserTasks SET linked_habit_uuid = 'habit-exercise-uuid', " +
                    "habit_time_accrual_enabled = 1, habit_link_effective_from = 1710000000000 " +
                    "WHERE id = 'task-timed'")
            assertEquals("habit-exercise-uuid", db.queryString("SELECT linked_habit_uuid FROM UserTasks WHERE id = 'task-timed'"))
            assertEquals(1L, db.queryLong("SELECT habit_time_accrual_enabled FROM UserTasks WHERE id = 'task-timed'"))
            assertEquals(1710000000000L, db.queryLong("SELECT habit_link_effective_from FROM UserTasks WHERE id = 'task-timed'"))

            // Test CHECK constraint on habit_time_accrual_enabled IN (0, 1)
            try {
                db.run("UPDATE UserTasks SET habit_time_accrual_enabled = 2 WHERE id = 'task-undated'")
                fail("CHECK constraint on habit_time_accrual_enabled IN (0, 1) should have failed")
            } catch (e: Exception) {
                // Expected constraint violation
            }

            // Existing data fidelity
            assertEquals(tasksBefore, db.queryLong("SELECT count(*) FROM UserTasks"))
            assertEquals(habitsBefore, db.queryLong("SELECT count(*) FROM Habits"))

            // Integrity and foreign keys
            assertIntegrityAndForeignKeys(db, "v34->v35")

            // Idempotent no-op
            db.migrateTo(35) { TestDatabaseHelper.loadMigrationSQL(it) }
            assertEquals(35, db.getVersion())
        } finally {
            db.close()
        }

        // Verify independent re-open persistence
        val reopened = opener.open(dbFile.absolutePath)
        try {
            assertEquals(35, reopened.getVersion())
            assertEquals("habit-exercise-uuid", reopened.queryString("SELECT linked_habit_uuid FROM UserTasks WHERE id = 'task-timed'"))
            assertEquals(1L, reopened.queryLong("SELECT habit_time_accrual_enabled FROM UserTasks WHERE id = 'task-timed'"))
        } finally {
            reopened.close()
        }
    }

    // =========================================================================
    // 5. Full sequential migration v31 -> v35
    // =========================================================================

    @Test
    fun testFullSequentialTransitionV31ToV35() = runTest {
        val dbFile = File(tempFolder.newFolder(), "v31_to_v35_sequential.db")
        val db = createDatabaseAtV31(dbFile)
        try {
            seedSyntheticV31Data(db)

            val habitsBefore = db.queryLong("SELECT count(*) FROM Habits")
            val blocksBefore = db.queryLong("SELECT count(*) FROM HabitBlocks")
            val repetitionsBefore = db.queryLong("SELECT count(*) FROM Repetitions")
            val containersBefore = db.queryLong("SELECT count(*) FROM Containers")
            val placementsBefore = db.queryLong("SELECT count(*) FROM HabitPlacements")
            val tasksBefore = db.queryLong("SELECT count(*) FROM UserTasks")

            // Execute full sequential migration from v31 to v35
            db.migrateTo(35) { TestDatabaseHelper.loadMigrationSQL(it) }

            assertEquals(35, db.getVersion())

            // Verify all cumulative schema features
            val taskCols = getTableColumns(db, "UserTasks")
            assertTrue("v32 col must exist", "legacy_plan_review_pending" in taskCols)
            assertTrue("v34 col must exist", "important" in taskCols)
            assertTrue("v34 col must exist", "urgent" in taskCols)
            assertTrue("v35 col must exist", "linked_habit_uuid" in taskCols)
            assertTrue("v35 col must exist", "habit_time_accrual_enabled" in taskCols)
            assertTrue("v35 col must exist", "habit_link_effective_from" in taskCols)

            val indexes = getIndexNames(db)
            assertTrue("v33 idx must exist", "idx_task_tag_links_tag" in indexes)
            assertTrue("v35 idx must exist", "idx_user_tasks_linked_habit" in indexes)

            // Verify v32 review pending was calculated properly
            assertEquals(1L, db.queryLong("SELECT legacy_plan_review_pending FROM UserTasks WHERE id = 'task-plan-none'"))
            assertEquals(0L, db.queryLong("SELECT legacy_plan_review_pending FROM UserTasks WHERE id = 'task-timed'"))

            // Verify all pre-existing baseline tables are uncorrupted
            assertEquals(habitsBefore, db.queryLong("SELECT count(*) FROM Habits"))
            assertEquals(blocksBefore, db.queryLong("SELECT count(*) FROM HabitBlocks"))
            assertEquals(repetitionsBefore, db.queryLong("SELECT count(*) FROM Repetitions"))
            assertEquals(containersBefore, db.queryLong("SELECT count(*) FROM Containers"))
            assertEquals(placementsBefore, db.queryLong("SELECT count(*) FROM HabitPlacements"))
            assertEquals(tasksBefore, db.queryLong("SELECT count(*) FROM UserTasks"))

            // Verify data contents
            assertEquals("Exercise", db.queryString("SELECT name FROM Habits WHERE id = 101"))
            assertEquals("Health Sphere", db.queryString("SELECT name FROM HabitBlocks WHERE id = 201"))
            assertEquals(2L, db.queryLong("SELECT value FROM Repetitions WHERE habit = 101 AND timestamp = 1000000"))
            assertEquals("Health", db.queryString("SELECT name FROM Containers WHERE id = 1"))

            // Integrity and foreign keys
            assertIntegrityAndForeignKeys(db, "full v31->v35")

            // Idempotent no-op
            db.migrateTo(35) { TestDatabaseHelper.loadMigrationSQL(it) }
            assertEquals(35, db.getVersion())
        } finally {
            db.close()
        }

        // Verify independent re-open persistence
        val reopened = opener.open(dbFile.absolutePath)
        try {
            assertEquals(35, reopened.getVersion())
            assertIntegrityAndForeignKeys(reopened, "reopened v35")
        } finally {
            reopened.close()
        }
    }

    // =========================================================================
    // 6. Rollback on injected failure after partial genuine DDL/DML execution
    // =========================================================================

    @Test
    fun testRollbackOnInjectedFailureWhereSupported() = runTest {
        val dbFile = File(tempFolder.newFolder(), "rollback_transitions.db")
        val db = createDatabaseAtV31(dbFile)
        try {
            seedSyntheticV31Data(db)
            val initialTaskCount = db.queryLong("SELECT count(*) FROM UserTasks")

            // -----------------------------------------------------------------
            // Transition v31 -> v32 partial DDL execution & rollback
            // Statement 1: ALTER TABLE UserTasks ADD COLUMN legacy_plan_review_pending...
            // Then deterministic invalid statement fails.
            // -----------------------------------------------------------------
            try {
                db.runInTransaction {
                    db.migrateTo(32) { version ->
                        if (version == 32) {
                            buildInjectedFailingMigrationSql(version, genuineStatementsToExecute = 1)
                        } else {
                            TestDatabaseHelper.loadMigrationSQL(version)
                        }
                    }
                }
                fail("Migration v31->v32 should have failed on invalid statement")
            } catch (e: Exception) {
                // Expected SQLite failure
            }

            // Assert earlier DDL was rolled back completely
            assertEquals("Version must remain 31 after rollback", 31, db.getVersion())
            val colsAt31 = getTableColumns(db, "UserTasks")
            assertFalse("legacy_plan_review_pending must NOT exist after rollback", "legacy_plan_review_pending" in colsAt31)
            assertEquals("Tasks count must remain unchanged", initialTaskCount, db.queryLong("SELECT count(*) FROM UserTasks"))

            // Retry v31 -> v32 succeeds cleanly
            db.runInTransaction {
                db.migrateTo(32) { TestDatabaseHelper.loadMigrationSQL(it) }
            }
            assertEquals(32, db.getVersion())
            val colsAt32 = getTableColumns(db, "UserTasks")
            assertTrue("legacy_plan_review_pending must exist after successful retry", "legacy_plan_review_pending" in colsAt32)
            assertIntegrityAndForeignKeys(db, "v31->v32 retry after rollback")

            // -----------------------------------------------------------------
            // Transition v32 -> v33 partial DDL/DML execution & rollback
            // Statements 1..3: CREATE TaskTagState, INSERT (1,0), CREATE TaskTags...
            // Then deterministic invalid statement fails before links/outbox created.
            // -----------------------------------------------------------------
            try {
                db.runInTransaction {
                    db.migrateTo(33) { version ->
                        if (version == 33) {
                            buildInjectedFailingMigrationSql(version, genuineStatementsToExecute = 3)
                        } else {
                            TestDatabaseHelper.loadMigrationSQL(version)
                        }
                    }
                }
                fail("Migration v32->v33 should have failed on invalid statement")
            } catch (e: Exception) {
                // Expected SQLite failure
            }

            // Assert earlier schema/data mutations were rolled back
            assertEquals("Version must remain 32 after rollback", 32, db.getVersion())
            val tagStateExists = db.queryLong(
                "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='TaskTagState'"
            ) > 0
            val tagsExists = db.queryLong(
                "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='TaskTags'"
            ) > 0
            assertFalse("TaskTagState table must not exist after rollback", tagStateExists)
            assertFalse("TaskTags table must not exist after rollback", tagsExists)
            assertEquals(initialTaskCount, db.queryLong("SELECT count(*) FROM UserTasks"))

            // Retry v32 -> v33 succeeds cleanly
            db.runInTransaction {
                db.migrateTo(33) { TestDatabaseHelper.loadMigrationSQL(it) }
            }
            assertEquals(33, db.getVersion())
            val tagsExistsAfterRetry = db.queryLong(
                "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='TaskTags'"
            ) > 0
            assertTrue("TaskTags table must exist after successful retry", tagsExistsAfterRetry)
            assertIntegrityAndForeignKeys(db, "v32->v33 retry after rollback")

            // -----------------------------------------------------------------
            // Transition v33 -> v34 partial DDL execution & rollback
            // Statement 1: ALTER TABLE UserTasks ADD COLUMN important...
            // Then deterministic invalid statement fails before urgent is added.
            // -----------------------------------------------------------------
            try {
                db.runInTransaction {
                    db.migrateTo(34) { version ->
                        if (version == 34) {
                            buildInjectedFailingMigrationSql(version, genuineStatementsToExecute = 1)
                        } else {
                            TestDatabaseHelper.loadMigrationSQL(version)
                        }
                    }
                }
                fail("Migration v33->v34 should have failed on invalid statement")
            } catch (e: Exception) {
                // Expected SQLite failure
            }

            // Assert earlier column addition was rolled back
            assertEquals("Version must remain 33 after rollback", 33, db.getVersion())
            val colsAt33 = getTableColumns(db, "UserTasks")
            assertFalse("important column must not exist after rollback", "important" in colsAt33)
            assertFalse("urgent column must not exist after rollback", "urgent" in colsAt33)

            // Retry v33 -> v34 succeeds cleanly
            db.runInTransaction {
                db.migrateTo(34) { TestDatabaseHelper.loadMigrationSQL(it) }
            }
            assertEquals(34, db.getVersion())
            val colsAt34 = getTableColumns(db, "UserTasks")
            assertTrue("important column must exist after retry", "important" in colsAt34)
            assertTrue("urgent column must exist after retry", "urgent" in colsAt34)
            assertIntegrityAndForeignKeys(db, "v33->v34 retry after rollback")

            // -----------------------------------------------------------------
            // Transition v34 -> v35 partial DDL execution & rollback
            // Statements 1..2: ADD COLUMN linked_habit_uuid, ADD COLUMN habit_time_accrual_enabled...
            // Then deterministic invalid statement fails before effective_from and index.
            // -----------------------------------------------------------------
            try {
                db.runInTransaction {
                    db.migrateTo(35) { version ->
                        if (version == 35) {
                            buildInjectedFailingMigrationSql(version, genuineStatementsToExecute = 2)
                        } else {
                            TestDatabaseHelper.loadMigrationSQL(version)
                        }
                    }
                }
                fail("Migration v34->v35 should have failed on invalid statement")
            } catch (e: Exception) {
                // Expected SQLite failure
            }

            // Assert earlier column additions were rolled back
            assertEquals("Version must remain 34 after rollback", 34, db.getVersion())
            val colsAt34AfterRollback = getTableColumns(db, "UserTasks")
            assertFalse("linked_habit_uuid must not exist after rollback", "linked_habit_uuid" in colsAt34AfterRollback)
            assertFalse("habit_time_accrual_enabled must not exist after rollback", "habit_time_accrual_enabled" in colsAt34AfterRollback)

            // Retry v34 -> v35 succeeds cleanly
            db.runInTransaction {
                db.migrateTo(35) { TestDatabaseHelper.loadMigrationSQL(it) }
            }
            assertEquals(35, db.getVersion())
            val colsAt35 = getTableColumns(db, "UserTasks")
            assertTrue("linked_habit_uuid must exist after retry", "linked_habit_uuid" in colsAt35)
            assertTrue("habit_time_accrual_enabled must exist after retry", "habit_time_accrual_enabled" in colsAt35)
            assertTrue("habit_link_effective_from must exist after retry", "habit_link_effective_from" in colsAt35)
            assertTrue("idx_user_tasks_linked_habit must exist after retry", "idx_user_tasks_linked_habit" in getIndexNames(db))
            assertIntegrityAndForeignKeys(db, "v34->v35 retry after rollback")
        } finally {
            db.close()
        }
    }
}
