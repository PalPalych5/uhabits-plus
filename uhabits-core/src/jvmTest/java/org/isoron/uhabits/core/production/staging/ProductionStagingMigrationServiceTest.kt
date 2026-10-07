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

package org.isoron.uhabits.core.production.staging

import kotlinx.coroutines.test.runTest
import org.isoron.platform.io.JavaDatabaseOpener
import org.isoron.platform.io.TestDatabaseHelper
import org.isoron.platform.io.getVersion
import org.isoron.platform.io.migrateTo
import org.isoron.platform.io.queryLong
import org.isoron.platform.io.queryString
import org.isoron.platform.io.setVersion
import org.isoron.uhabits.core.DATABASE_VERSION
import org.isoron.uhabits.core.LEGACY_DATABASE_VERSION
import org.isoron.uhabits.core.containers.migration.SemanticMigrationFixtures
import org.isoron.uhabits.core.production.cutover.ProductionCutoverGate
import org.isoron.uhabits.core.production.preflight.ProductionPreflightService
import org.isoron.uhabits.core.production.recovery.LegacySnapshotLease
import org.isoron.uhabits.core.production.recovery.ProductionRecoveryGate
import org.isoron.uhabits.core.production.recovery.RecoveryInspection
import org.isoron.uhabits.core.production.recovery.RecoveryPreferenceKey
import org.isoron.uhabits.core.production.recovery.RecoveryPreferences
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProductionStagingMigrationServiceTest {

    private fun createV29SeededDatabase(file: File) {
        val db = JavaDatabaseOpener().open(file.absolutePath)
        try {
            db.setVersion(8)
            kotlinx.coroutines.runBlocking {
                db.migrateTo(29) { TestDatabaseHelper.loadMigrationSQL(it) }
            }
            SemanticMigrationFixtures.seedAllFixtures(db)
        } finally {
            db.close()
        }
    }

    private fun inspectFile(file: File): RecoveryInspection {
        val sha = MessageDigest.getInstance("SHA-256")
        return DriverManager.getConnection("jdbc:sqlite:${file.toURI()}?mode=ro").use { connection ->
            connection.createStatement().use { statement ->
                val version = statement.executeQuery("PRAGMA user_version").use { it.next(); it.getInt(1) }
                val integrity = statement.executeQuery("PRAGMA integrity_check").use { it.next(); it.getString(1) }
                val foreignKeysOkay = statement.executeQuery("PRAGMA foreign_key_check").use { !it.next() }
                val schema = statement.executeQuery("SELECT name,sql FROM sqlite_master ORDER BY name").use {
                    buildString { while (it.next()) append(it.getString(1)).append(':').append(it.getString(2)).append('\n') }
                }
                val semantics = statement.executeQuery("SELECT id,name FROM Habits ORDER BY id").use {
                    buildString { while (it.next()) append(it.getLong(1)).append(':').append(it.getString(2)) }
                }
                val count = statement.executeQuery("SELECT COUNT(*) FROM Habits").use { it.next(); it.getLong(1) }
                RecoveryInspection(
                    version,
                    sha.digest(semantics.toByteArray()).joinToString("") { "%02x".format(it) },
                    integrity == "ok" && foreignKeysOkay,
                    sha.digest(schema.toByteArray()).joinToString("") { "%02x".format(it) },
                    mapOf("Habits" to count),
                    emptyMap()
                )
            }
        }
    }

    private fun idlePreferences(databaseName: String = "uhabits.db"): RecoveryPreferences {
        val values = RecoveryPreferences.requiredKeys.associateWith { "0" }.toMutableMap()
        values[RecoveryPreferenceKey.SYNC_DEVICE_ID] = ""
        listOf(
            RecoveryPreferenceKey.SYNC_ENABLED, RecoveryPreferenceKey.SYNC_REVIEW_REQUIRED,
            RecoveryPreferenceKey.SYNC_BOOTSTRAP_QUEUED, RecoveryPreferenceKey.SYNC_BOOTSTRAP_DONE,
            RecoveryPreferenceKey.MIDNIGHT_DELAY, RecoveryPreferenceKey.POMODORO_AUTO_SWITCH
        ).forEach { values[it] = "false" }
        values[RecoveryPreferenceKey.FIRST_WEEKDAY] = "-1"
        values[RecoveryPreferenceKey.POMODORO_DEFAULT_FOCUS] = "25"
        values[RecoveryPreferenceKey.POMODORO_DEFAULT_BREAK] = "5"
        values[RecoveryPreferenceKey.TIMER_HABIT_ID] = "-1"
        values[RecoveryPreferenceKey.TIMER_MODE] = "STOPWATCH"
        values[RecoveryPreferenceKey.TIMER_PHASE] = "FOCUS"
        values[RecoveryPreferenceKey.TIMER_IS_RUNNING] = "false"
        values[RecoveryPreferenceKey.TIMER_COMPLETION_TRIGGERED] = "false"
        values[RecoveryPreferenceKey.TIMER_COMPLETION_DISPLAY_ACTIVE] = "false"
        values[RecoveryPreferenceKey.TIMER_FOCUS_DURATION_MILLIS] = "1500000"
        values[RecoveryPreferenceKey.TIMER_BREAK_DURATION_MILLIS] = "300000"
        return RecoveryPreferences(databaseName = databaseName, values = values)
    }

    @Test
    fun executeStagingMigrationOnProtectedCopyPreservesSourceAndProducesValidV30() = runTest {
        val tempDir = Files.createTempDirectory("staging-test-").toFile()
        val sourceDb = File(tempDir, "uhabits.db")
        val bundleDir = File(tempDir, "recovery")
        val stagingDb = File(tempDir, "staging.db")

        try {
            createV29SeededDatabase(sourceDb)
            val sourceBytesBefore = Files.readAllBytes(sourceDb.toPath())
            val sourceShaBefore = ProductionRecoveryGate.fileSha256(sourceDb)

            // Prepare recovery bundle
            val prefs = idlePreferences(databaseName = "uhabits.db")
            val gate = ProductionRecoveryGate(bundleDir, ::inspectFile)
            val prepResult = gate.prepare {
                object : LegacySnapshotLease {
                    override val source: File = sourceDb
                    override fun capturePreferences(): RecoveryPreferences = prefs
                    override fun close() {}
                }
            }
            assertTrue(prepResult.migrationEligible, "Preparation should be migration eligible: ${prepResult.blocker}")

            val currentBundleDir = File(bundleDir, prepResult.bundleId!!)

            val service = ProductionStagingMigrationService(JavaDatabaseOpener())
            val result = service.executeStagedMigration(
                sourceFile = sourceDb,
                bundleDir = currentBundleDir,
                stagingFile = stagingDb,
                cutoverTimestamp = 1700000000000L
            )

            assertIs<StagingMigrationResult.Success>(result)
            assertTrue(result.sourceUnchanged)

            // Verify source file is byte-for-byte identical (Zero source mutation)
            val sourceBytesAfter = Files.readAllBytes(sourceDb.toPath())
            assertContentEquals(sourceBytesBefore, sourceBytesAfter)

            // Re-open staging database independently and verify contents
            val reopened = JavaDatabaseOpener().open(stagingDb.absolutePath)
            try {
                assertEquals(DATABASE_VERSION, reopened.getVersion())
                assertEquals("PRODUCTION_NEXT", reopened.queryString("SELECT mode FROM OrganizationState"))
                assertEquals(ProductionCutoverGate.AUTHORITY_LEGACY, reopened.queryString("SELECT runtime_authority FROM OrganizationState"))
                assertEquals(0L, reopened.queryLong("SELECT authority_epoch FROM OrganizationState"))
                assertEquals(0L, reopened.queryLong("SELECT count(*) FROM UserTasks"))
                assertEquals(0L, reopened.queryLong("SELECT count(*) FROM FocusSessions"))
                assertEquals(0L, reopened.queryLong("SELECT count(*) FROM Contributions"))
                assertEquals(0L, reopened.queryLong("SELECT count(*) FROM SourceRules"))
                assertTrue(reopened.queryLong("SELECT count(*) FROM Containers") > 0)
                assertTrue(reopened.queryLong("SELECT count(*) FROM HabitPlacements") > 0)
            } finally {
                reopened.close()
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun failsIfSourceDatabaseIsNotEligible() = runTest {
        val tempDir = Files.createTempDirectory("staging-test-fail-").toFile()
        val sourceDb = File(tempDir, "ineligible_uhabits.db")
        val bundleDir = File(tempDir, "recovery_bundle")
        bundleDir.mkdirs()
        val stagingDb = File(tempDir, "staging_uhabits.db")

        try {
            val db = JavaDatabaseOpener().open(sourceDb.absolutePath)
            try {
                db.setVersion(8)
                kotlinx.coroutines.runBlocking {
                    db.migrateTo(28) { TestDatabaseHelper.loadMigrationSQL(it) }
                }
            } finally {
                db.close()
            }

            val service = ProductionStagingMigrationService(JavaDatabaseOpener())
            val result = service.executeStagedMigration(
                sourceFile = sourceDb,
                bundleDir = bundleDir,
                stagingFile = stagingDb,
                cutoverTimestamp = 1700000000000L
            )

            assertIs<StagingMigrationResult.Failure>(result)
            assertEquals("PREFLIGHT", result.stage)
            assertFalse(stagingDb.exists())
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
