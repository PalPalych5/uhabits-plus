package org.isoron.uhabits.core.production.preflight

import kotlinx.coroutines.test.runTest
import org.isoron.platform.io.Database
import org.isoron.platform.io.TestDatabaseHelper
import org.isoron.platform.io.run
import org.isoron.uhabits.core.containers.migration.SemanticMigrationFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProductionPreflightServiceTest {
    private val sha = "a".repeat(64)
    private fun inspect(db: Database) = ProductionPreflightService.inspect(db, "fixture.db", sha)

    @Test fun populatedV29FixtureIsSourceEligible() = runTest {
        val db = SemanticMigrationFixtures.createAndSeedV29Database()
        try { val report = inspect(db); assertTrue(report.sourceEligible, report.blockingIssues.toString()) }
        finally { db.close() }
    }

    @Test fun duplicateLocalKeysInMalformedPhysicalSchemaBlock() = runTest {
        val db = SemanticMigrationFixtures.createAndSeedV29Database()
        try {
            db.run("PRAGMA foreign_keys = OFF")
            for ((table, key) in listOf("Habits" to "id", "HabitBlocks" to "id", "HabitGoals" to "id", "SyncQueue" to "queue_id", "AppSettings" to "key")) {
                db.run("CREATE TABLE ${table}Copy AS SELECT * FROM $table")
                db.run("DROP TABLE $table")
                db.run("ALTER TABLE ${table}Copy RENAME TO $table")
                db.run("INSERT INTO $table SELECT * FROM $table WHERE $key = (SELECT $key FROM $table LIMIT 1)")
            }
            val issues = inspect(db).blockingIssues
            for (table in listOf("Habits", "HabitBlocks", "HabitGoals", "SyncQueue", "AppSettings"))
                assertTrue(issues.any { it.table == table && it.category == PreflightIssueCategory.MalformedLegacyRow && it.detail == "duplicate natural key" }, issues.toString())
        } finally { db.close() }
    }

    @Test fun integerOverflowAndBooleanCoercionBlock() = runTest {
        val db = SemanticMigrationFixtures.createAndSeedV29Database()
        try {
            db.run("PRAGMA ignore_check_constraints = ON") // disposable malformed-row fixture only
            db.run("UPDATE Habits SET freq_num = 4294967297 WHERE id = 1")
            db.run("UPDATE HabitBlocks SET is_archived = 2 WHERE id = 1")
            val issues = inspect(db).blockingIssues
            assertTrue(issues.any { it.table == "Habits" && it.detail.contains("Int range") }, issues.toString())
            assertTrue(issues.any { it.table == "HabitBlocks" && it.detail.contains("0 or 1") }, issues.toString())
        } finally { db.close() }
    }

    @Test fun cleanV29IsStableAndHasNoMutationStatements() = runTest {
        val db = TestDatabaseHelper.createEmptyDatabase()
        try {
            val observed = mutableListOf<String>()
            val readOnly = object : Database {
                override fun prepareStatement(sql: String): org.isoron.platform.io.PreparedStatement {
                    observed.add(sql)
                    assertTrue(sql.startsWith("SELECT ") || (sql.startsWith("PRAGMA ") && !sql.contains("=")))
                    return db.prepareStatement(sql)
                }
                override fun close() = Unit
            }
            val before = inspect(db)
            val actual = inspect(readOnly)
            assertTrue(actual.sourceEligible, actual.blockingIssues.toString())
            assertFalse(actual.migrationEligible)
            assertEquals(29, actual.userVersion)
            assertEquals(before.fingerprint, actual.fingerprint)
            assertEquals(before.schemaInventory, actual.schemaInventory)
            assertEquals(actual, inspect(db))
            assertTrue(observed.any { it.contains("sqlite_master") })
        } finally { db.close() }
    }

    @Test fun unexpectedNextSchemaIsInventoriedWithoutRepair() = runTest {
        val db = TestDatabaseHelper.createEmptyDatabase()
        try {
            db.run("CREATE TABLE UserTasks(id TEXT)")
            val empty = inspect(db)
            assertTrue(empty.blockingIssues.any { it.category == PreflightIssueCategory.UnexpectedNextTable && it.table == "UserTasks" })
            db.run("INSERT INTO UserTasks VALUES ('retained')")
            val full = inspect(db)
            assertTrue(full.blockingIssues.any { it.category == PreflightIssueCategory.NonEmptyUnexpectedNextTable })
            assertEquals(1L, full.fingerprint.rowCounts["UserTasks"])
            assertEquals(full.fingerprint, inspect(db).fingerprint)
        } finally { db.close() }
    }

    @Test fun rawOrphansAndDanglingReferencesAreBlockers() = runTest {
        val db = SemanticMigrationFixtures.createAndSeedV29Database()
        try {
            db.run("PRAGMA foreign_keys = OFF")
            db.run("UPDATE HabitExtensions SET block_id = 99999 WHERE habit_id = 1")
            db.run("UPDATE HabitExtensions SET habit_id = 99999 WHERE habit_id = 2")
            db.run("UPDATE HabitGoals SET habit_id = 99999 WHERE id = (SELECT MIN(id) FROM HabitGoals)")
            db.run("UPDATE Repetitions SET habit = 99999 WHERE rowid = (SELECT MIN(rowid) FROM Repetitions)")
            db.run("UPDATE EntryOps SET habit_uuid = 'missing'")
            val categories = inspect(db).blockingIssues.map { it.category }.toSet()
            assertTrue(categories.containsAll(setOf(PreflightIssueCategory.DanglingBlockReference, PreflightIssueCategory.OrphanHabitExtension,
                PreflightIssueCategory.OrphanHabitGoal, PreflightIssueCategory.OrphanRepetition, PreflightIssueCategory.OrphanEntryOp)))
        } finally { db.close() }
    }

    @Test fun duplicateBlankIdentityAndDeletedBlockAreBlockers() = runTest {
        val db = SemanticMigrationFixtures.createAndSeedV29Database()
        try {
            db.run("DROP INDEX idx_habits_uuid")
            db.run("UPDATE Habits SET uuid = 'duplicate' WHERE id IN (1,2)")
            db.run("UPDATE HabitBlocks SET uuid = '' WHERE id = 1")
            db.run("UPDATE HabitBlocks SET deleted_at = 100 WHERE id = 1")
            val categories = inspect(db).blockingIssues.map { it.category }.toSet()
            assertTrue(PreflightIssueCategory.DuplicatePortableUuid in categories)
            assertTrue(PreflightIssueCategory.BlankPortableUuid in categories)
            assertTrue(PreflightIssueCategory.ActiveHabitInDeletedBlock in categories)
        } finally { db.close() }
    }

    @Test fun semanticFingerprintChangesWithRowsAndExtraColumns() = runTest {
        val db = TestDatabaseHelper.createEmptyDatabase()
        try {
            val before = inspect(db)
            db.run("INSERT INTO AppSettings(key,long_value) VALUES ('cutover-marker', 1)")
            val after = inspect(db)
            assertFalse(before.fingerprint == after.fingerprint)
            db.run("ALTER TABLE AppSettings ADD COLUMN extra TEXT")
            val extra = inspect(db)
            db.run("UPDATE AppSettings SET extra = 'nul:literal' WHERE key = 'cutover-marker'")
            assertFalse(extra.fingerprint.semanticSha256 == inspect(db).fingerprint.semanticSha256)
        } finally { db.close() }
    }

    @Test fun missingPhysicalSchemaAndWrongVersionFailClosed() = runTest {
        val db = TestDatabaseHelper.createEmptyDatabase()
        try {
            db.run("PRAGMA user_version = 28")
            db.run("DROP TABLE AppSettings")
            val report = inspect(db)
            assertFalse(report.migrationEligible)
            assertTrue(report.blockingIssues.any { it.category == PreflightIssueCategory.UnsupportedDatabaseVersion })
            assertTrue(report.blockingIssues.any { it.category == PreflightIssueCategory.MissingRequiredLegacyTable })
        } finally { db.close() }
    }
}
