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
import org.isoron.platform.io.Database
import org.isoron.platform.io.TestDatabaseHelper
import org.isoron.platform.io.queryLong
import org.isoron.platform.io.run
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.containers.migration.*
import org.isoron.uhabits.core.focus.*
import org.isoron.uhabits.core.focus.sqlite.FocusSessionSchema
import org.isoron.uhabits.core.focus.sqlite.SQLiteFocusSessionRepository
import org.isoron.uhabits.core.userTasks.TaskId
import kotlin.test.*

class FocusSessionBackupTest {
    private suspend fun source(): Database = SemanticMigrationFixtures.createAndSeedV29Database().also {
        val plan = LegacyContainerMigrationPlanner.plan(RawLegacyInventoryReader.read(it), 1000000L)
        assertTrue(LegacyContainerMigrationExecutor.execute(it, plan) is MigrationExecutionResult.Success)
    }
    private fun fact() = FocusSession(
        id = FocusSessionId("focus-original"), startedAt = 1000, endedAt = 5000, durationMs = 2500,
        taskId = TaskId("deleted-task"), containerId = ContainerId("historical-container"),
        source = FocusSessionSource.TIMER, deviceId = "device", createdAt = 5000, updatedAt = 5000,
        primaryTargetType = FocusPrimaryTargetType.TASK, organizationContext = "{\"path\":[\"Учёба\",\"БД\"]}",
    )
    private suspend fun backup(db: Database): Database = TestDatabaseHelper.createEmptyDatabase().also {
        assertTrue(ContainerBackupService.createBackup(db, it, 7000) is BackupCreationResult.Success)
    }
    private suspend fun restore(db: Database, destination: Database): RestoreResult {
        val stage = TestDatabaseHelper.createEmptyDatabase()
        return try { ContainerRestoreService.restore(db, stage, destination) } finally { stage.close() }
    }

    @Test fun roundtripPreservesFinalizedTypedHistoricalFacts() = runTest {
        val source = source(); FocusSessionSchema.createSchema(source)
        SQLiteFocusSessionRepository(source).record(fact())
        val backup = backup(source); val destination = TestDatabaseHelper.createEmptyDatabase()
        try {
            assertEquals(2, DatabaseSnapshotter.readManifest(backup)?.formatVersion)
            assertTrue(restore(backup, destination) is RestoreResult.Success)
            assertEquals(listOf(fact()), SQLiteFocusSessionRepository(destination).getAll())
        } finally { source.close(); backup.close(); destination.close() }
    }

    @Test fun tamperedFactRejectedBeforeDestinationChanges() = runTest {
        val source = source(); FocusSessionSchema.createSchema(source)
        SQLiteFocusSessionRepository(source).record(fact()); val backup = backup(source)
        val destination = TestDatabaseHelper.createEmptyDatabase()
        try {
            backup.run("UPDATE FocusSessions SET organization_context = 'tampered'")
            destination.run("CREATE TABLE Sentinel (id INTEGER)"); destination.run("INSERT INTO Sentinel VALUES (7)")
            val result = restore(backup, destination)
            assertTrue(result is RestoreResult.Failure); assertTrue(result.reason.contains("checksum"))
            assertEquals(7L, destination.queryLong("SELECT id FROM Sentinel"))
            assertFalse("FocusSessions" in DatabaseSnapshotter.listTables(destination))
        } finally { source.close(); backup.close(); destination.close() }
    }

    @Test fun oldFormatRestoresEmptyFactsAndClearsExistingRuntime() = runTest {
        val source = source(); val backup = backup(source); val destination = TestDatabaseHelper.createEmptyDatabase()
        try {
            val current = DatabaseSnapshotter.readManifest(backup)!!
            DatabaseSnapshotter.writeManifest(backup, current.copy(formatVersion = 1, focusSessionsSha256 = ""))
            FocusSessionSchema.createSchema(destination); SQLiteFocusSessionRepository(destination).record(fact())
            destination.run("CREATE TABLE FocusCaptureJournal (id INTEGER PRIMARY KEY)"); destination.run("INSERT INTO FocusCaptureJournal VALUES (1)")
            assertTrue(restore(backup, destination) is RestoreResult.Success)
            assertTrue(SQLiteFocusSessionRepository(destination).getAll().isEmpty())
            assertEquals(0L, destination.queryLong("SELECT count(*) FROM FocusCaptureJournal"))
        } finally { source.close(); backup.close(); destination.close() }
    }

    @Test fun journalNeverCopiedOrModifiedInSource() = runTest {
        val source = source(); FocusSessionSchema.createSchema(source)
        source.run("CREATE TABLE FocusCaptureJournal (id INTEGER PRIMARY KEY)"); source.run("INSERT INTO FocusCaptureJournal VALUES (1)")
        val backup = backup(source); val destination = TestDatabaseHelper.createEmptyDatabase()
        try {
            assertFalse("FocusCaptureJournal" in DatabaseSnapshotter.listTables(backup))
            assertEquals(1L, source.queryLong("SELECT count(*) FROM FocusCaptureJournal"))
            assertTrue(restore(backup, destination) is RestoreResult.Success)
            assertFalse("FocusCaptureJournal" in DatabaseSnapshotter.listTables(destination))
        } finally { source.close(); backup.close(); destination.close() }
    }

    @Test fun incompleteAndFutureFocusColumnsRejected() = runTest {
        for (schema in listOf("id TEXT", "id TEXT, future_field TEXT")) {
            val source = source(); val target = TestDatabaseHelper.createEmptyDatabase()
            val beforeTables = DatabaseSnapshotter.listTables(target)
            val beforeHash = RawLegacyInventoryReader.read(target).snapshotSha256
            try {
                source.run("CREATE TABLE FocusSessions ($schema)")
                assertTrue(ContainerBackupService.createBackup(source, target, 7000) is BackupCreationResult.Failure)
                assertTrue(restore(source, target) is RestoreResult.Failure)
                assertEquals(beforeTables, DatabaseSnapshotter.listTables(target))
                assertEquals(beforeHash, RawLegacyInventoryReader.read(target).snapshotSha256)
            } finally { source.close(); target.close() }
        }
    }

    @Test fun futureBackupVersionRejected() = runTest {
        val source = source(); val backup = backup(source); val target = TestDatabaseHelper.createEmptyDatabase()
        val beforeTables = DatabaseSnapshotter.listTables(target)
        val beforeHash = RawLegacyInventoryReader.read(target).snapshotSha256
        try {
            DatabaseSnapshotter.writeManifest(backup, DatabaseSnapshotter.readManifest(backup)!!.copy(formatVersion = 3))
            assertTrue(restore(backup, target) is RestoreResult.Failure)
            assertEquals(beforeTables, DatabaseSnapshotter.listTables(target))
            assertEquals(beforeHash, RawLegacyInventoryReader.read(target).snapshotSha256)
        } finally { source.close(); backup.close(); target.close() }
    }

    @Test fun deletedFactTableAndTamperedManifestRejected() = runTest {
        val source = source(); FocusSessionSchema.createSchema(source)
        SQLiteFocusSessionRepository(source).record(fact())
        val backup = backup(source); val target = TestDatabaseHelper.createEmptyDatabase()
        val beforeTables = DatabaseSnapshotter.listTables(target)
        val beforeHash = RawLegacyInventoryReader.read(target).snapshotSha256
        try {
            val manifest = DatabaseSnapshotter.readManifest(backup)!!
            DatabaseSnapshotter.writeManifest(backup, manifest.copy(canonicalChecksum = "tampered"))
            assertTrue(restore(backup, target) is RestoreResult.Failure)
            DatabaseSnapshotter.writeManifest(backup, manifest)
            backup.run("DROP TABLE FocusSessions")
            assertTrue(restore(backup, target) is RestoreResult.Failure)
            assertEquals(beforeTables, DatabaseSnapshotter.listTables(target))
            assertEquals(beforeHash, RawLegacyInventoryReader.read(target).snapshotSha256)
        } finally { source.close(); backup.close(); target.close() }
    }

    @Test fun legacyPr11FocusColumnsPreservedAndUpgraded() = runTest {
        val source = source()
        source.run("""CREATE TABLE FocusSessions (id TEXT PRIMARY KEY, started_at INTEGER, ended_at INTEGER, duration_ms INTEGER,
            task_id TEXT, habit_uuid TEXT, container_id TEXT, source TEXT, device_id TEXT, created_at INTEGER, updated_at INTEGER, revision INTEGER)""")
        source.run("INSERT INTO FocusSessions VALUES ('legacy-focus', 1000, 5000, 2500, 'old-task', NULL, 'old-context', 'TIMER', NULL, 5000, 5000, 1)")
        val backup = backup(source); val target = TestDatabaseHelper.createEmptyDatabase()
        try {
            assertTrue(restore(backup, target) is RestoreResult.Success)
            val restored = SQLiteFocusSessionRepository(target).getAll().single()
            assertEquals("legacy-focus", restored.id.value); assertEquals(FocusPrimaryTargetType.TASK, restored.primaryTargetType)
            assertEquals(ContainerId("old-context"), restored.containerId); assertNull(restored.organizationContext)
        } finally { source.close(); backup.close(); target.close() }
    }

    @Test fun activeTimerPreferencesCannotBeRecovered() = runTest {
        val sanitized = ContainerBackupManifest.sanitizePreferences(mapOf("pref_timer_is_running" to "true", "pref_timer_habit_id" to "7", "theme" to "dark", "pref_pomodoro_default_focus_minutes" to "25"))
        assertEquals(mapOf("theme" to "dark", "pref_pomodoro_default_focus_minutes" to "25"), sanitized)
    }
}
