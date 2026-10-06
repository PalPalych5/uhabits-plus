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
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.containers.migration.*
import org.isoron.uhabits.core.contribution.*
import org.isoron.uhabits.core.contribution.sqlite.ContributionSchema
import org.isoron.uhabits.core.contribution.sqlite.SQLiteContributionRepository
import org.isoron.uhabits.core.focus.*
import org.isoron.uhabits.core.focus.sqlite.FocusSessionSchema
import org.isoron.uhabits.core.focus.sqlite.SQLiteFocusSessionRepository
import kotlin.test.*

class ContributionBackupTest {

    private suspend fun source(): Database = SemanticMigrationFixtures.createAndSeedV29Database().also {
        val plan = LegacyContainerMigrationPlanner.plan(RawLegacyInventoryReader.read(it), 1000000L)
        assertTrue(LegacyContainerMigrationExecutor.execute(it, plan) is MigrationExecutionResult.Success)
    }

    private fun sampleFocusSession() = FocusSession(
        id = FocusSessionId("focus-session-1"),
        startedAt = 1000L,
        endedAt = 3520000L,
        durationMs = 2520000L,
        source = FocusSessionSource.TIMER,
        createdAt = 1000L,
        updatedAt = 3520000L,
        primaryTargetType = FocusPrimaryTargetType.HABIT,
        habit = HabitRef("h-uuid-1"),
    )

    private fun sampleContribution(
        id: String,
        habitUuid: String = "h-uuid-1",
        value: Long = 42000L,
        removedAt: Long? = null,
    ) = Contribution(
        id = ContributionId(id),
        targetHabit = HabitRef(habitUuid),
        sourceType = ContributionSourceType.FOCUS_SESSION,
        sourceId = "focus-session-1",
        contributedValue = value,
        effectiveTimestamp = 3520000L,
        createdAt = 3520000L,
        updatedAt = 3520000L,
        removedAt = removedAt,
    )

    private suspend fun backup(db: Database): Database = TestDatabaseHelper.createEmptyDatabase().also {
        assertTrue(ContainerBackupService.createBackup(db, it, 7000) is BackupCreationResult.Success)
    }

    private suspend fun restore(db: Database, destination: Database): RestoreResult {
        val stage = TestDatabaseHelper.createEmptyDatabase()
        return try {
            ContainerRestoreService.restore(db, stage, destination)
        } finally {
            stage.close()
        }
    }

    @Test
    fun roundtripPreservesActiveAndTombstonedContributions() = runTest {
        val src = source()
        FocusSessionSchema.createSchema(src)
        ContributionSchema.createSchema(src)

        val focusRepo = SQLiteFocusSessionRepository(src)
        focusRepo.record(sampleFocusSession())

        val contribRepo = SQLiteContributionRepository(src)
        val active = sampleContribution("c-active", value = 42000L)
        val tombstoned = sampleContribution(
            "c-tombstone",
            habitUuid = "h-uuid-2",
            value = 18000L,
            removedAt = 4000000L,
        )
        contribRepo.record(active)
        contribRepo.record(tombstoned)

        val bkp = backup(src)
        val dest = TestDatabaseHelper.createEmptyDatabase()
        try {
            val manifest = DatabaseSnapshotter.readManifest(bkp)
            assertNotNull(manifest)
            assertEquals(3, manifest.formatVersion)
            assertNotEquals(ContributionBackupInventory.emptyChecksum, manifest.contributionsSha256)

            val restoreResult = restore(bkp, dest)
            assertTrue(restoreResult is RestoreResult.Success)

            val restoredContribRepo = SQLiteContributionRepository(dest)
            val restoredActive = restoredContribRepo.getById(ContributionId("c-active"))
            assertNotNull(restoredActive)
            assertEquals(active, restoredActive)
            assertTrue(restoredActive.isActive)

            val restoredTombstoned = restoredContribRepo.getById(ContributionId("c-tombstone"))
            assertNotNull(restoredTombstoned)
            assertEquals(tombstoned, restoredTombstoned)
            assertFalse(restoredTombstoned.isActive)
            assertEquals(4000000L, restoredTombstoned.removedAt)

            // Verify totals
            assertEquals(42000L, restoredContribRepo.getActiveTotalForHabit(HabitRef("h-uuid-1")))
            assertEquals(0L, restoredContribRepo.getActiveTotalForHabit(HabitRef("h-uuid-2")))
        } finally {
            src.close()
            bkp.close()
            dest.close()
        }
    }

    @Test
    fun tamperedContributionRejectedBeforeDestinationChanges() = runTest {
        val src = source()
        FocusSessionSchema.createSchema(src)
        ContributionSchema.createSchema(src)

        SQLiteContributionRepository(src).record(sampleContribution("c-1"))
        val bkp = backup(src)
        val dest = TestDatabaseHelper.createEmptyDatabase()
        try {
            bkp.run("UPDATE Contributions SET contributed_value = 99999 WHERE id = 'c-1'")
            dest.run("CREATE TABLE Sentinel (id INTEGER)")
            dest.run("INSERT INTO Sentinel VALUES (42)")

            val result = restore(bkp, dest)
            assertTrue(result is RestoreResult.Failure)
            assertTrue(result.reason.contains("checksum", ignoreCase = true))

            // Destination sentinel remains unchanged
            assertEquals(42L, dest.queryLong("SELECT id FROM Sentinel"))
            assertFalse("Contributions" in DatabaseSnapshotter.listTables(dest))
        } finally {
            src.close()
            bkp.close()
            dest.close()
        }
    }

    @Test
    fun backwardCompatibilityFormat2RestoresSuccessfully() = runTest {
        val src = source()
        FocusSessionSchema.createSchema(src)
        SQLiteFocusSessionRepository(src).record(sampleFocusSession())

        val bkp = backup(src)
        val dest = TestDatabaseHelper.createEmptyDatabase()
        try {
            // Simulate format 2 manifest (created before contributions existed)
            val current = DatabaseSnapshotter.readManifest(bkp)!!
            val format2Manifest = current.copy(
                formatVersion = 2,
                contributionsSha256 = "",
                canonicalChecksum = ContainerBackupManifest.computeCanonicalChecksum(
                    datasetUuid = current.datasetUuid,
                    sourceSnapshotSha256 = current.sourceSnapshotSha256,
                    createdAt = current.createdAt,
                    formatVersion = 2,
                    foundationVersion = current.foundationVersion,
                    focusSessionsSha256 = current.focusSessionsSha256,
                )
            )
            DatabaseSnapshotter.writeManifest(bkp, format2Manifest)

            val result = restore(bkp, dest)
            assertTrue(result is RestoreResult.Success)

            // FocusSessions are restored, destination has valid state
            val focusRepo = SQLiteFocusSessionRepository(dest)
            assertEquals(listOf(sampleFocusSession()), focusRepo.getAll())
        } finally {
            src.close()
            bkp.close()
            dest.close()
        }
    }

    @Test
    fun orphanContributionReferencingNonExistentHabitRejectedByRestore() = runTest {
        val src = source()
        FocusSessionSchema.createSchema(src)
        ContributionSchema.createSchema(src)

        // Seed an orphan contribution pointing to nonexistent habit UUID
        src.run(
            """
            INSERT INTO Contributions (id, target_habit_uuid, source_type, source_id, contributed_value, effective_timestamp, created_at, updated_at, removed_at)
            VALUES ('c-orphan', '00000000-0000-0000-0000-999999999999', 'FOCUS_SESSION', 'src-1', 1000, 1000, 1000, 1000, NULL)
            """.trimIndent()
        )
        val bkp = backup(src)
        val dest = TestDatabaseHelper.createEmptyDatabase()
        try {
            val result = restore(bkp, dest)
            assertTrue(result is RestoreResult.Failure)
            assertTrue(result.errors.any { it.contains("references non-existent habit UUID") })
        } finally {
            src.close()
            bkp.close()
            dest.close()
        }
    }

    @Test
    fun format2WithContributionsTableRefusesSilentFactLoss() = runTest {
        val src = source()
        FocusSessionSchema.createSchema(src)
        ContributionSchema.createSchema(src)
        SQLiteContributionRepository(src).record(sampleContribution("c-1"))

        val bkp = backup(src)
        val dest = TestDatabaseHelper.createEmptyDatabase()
        try {
            // Tamper manifest formatVersion to 2 while Contributions table exists in backup
            val current = DatabaseSnapshotter.readManifest(bkp)!!
            DatabaseSnapshotter.writeManifest(bkp, current.copy(formatVersion = 2))

            val result = restore(bkp, dest)
            assertTrue(result is RestoreResult.Failure)
            assertTrue(result.reason.contains("refusing silent fact loss"))
        } finally {
            src.close()
            bkp.close()
            dest.close()
        }
    }
}
