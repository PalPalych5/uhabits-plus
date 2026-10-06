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
import org.isoron.platform.io.querySingle
import org.isoron.platform.io.run
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.containers.migration.*
import org.isoron.uhabits.core.rules.*
import org.isoron.uhabits.core.rules.sqlite.SourceRuleSchema
import org.isoron.uhabits.core.rules.sqlite.SQLiteSourceRuleRepository
import kotlin.test.*

class SourceRuleBackupTest {

    private suspend fun source(): Database = SemanticMigrationFixtures.createAndSeedV29Database().also {
        val plan = LegacyContainerMigrationPlanner.plan(RawLegacyInventoryReader.read(it), 1000000L)
        assertTrue(LegacyContainerMigrationExecutor.execute(it, plan) is MigrationExecutionResult.Success)
    }

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

    private fun sampleRule(
        id: String,
        containerId: String,
        habitUuid: String = "h-uuid-1",
        includeDescendants: Boolean = true,
        removedAt: Long? = null,
    ) = SourceRule(
        id = SourceRuleId(id),
        sourceKind = SourceRuleSourceKind.FOCUS_SESSION,
        sourceContainerId = ContainerId(containerId),
        includeDescendants = includeDescendants,
        targetHabit = HabitRef(habitUuid),
        valueMapping = SourceRuleValueMapping.FOCUS_DURATION,
        isEnabled = true,
        effectiveFrom = 1000L,
        createdAt = 1000L,
        updatedAt = 1000L,
        removedAt = removedAt,
    )

    @Test
    fun roundtripPreservesActiveAndTombstonedSourceRules() = runTest {
        val src = source()
        SourceRuleSchema.createSchema(src)

        val containerUuid = src.querySingle("SELECT uuid FROM Containers LIMIT 1") { it.getText(0) }!!
        val repo = SQLiteSourceRuleRepository(src)

        val active = sampleRule("rule-active", containerId = containerUuid, habitUuid = "h-uuid-1")
        val tombstoned = sampleRule(
            id = "rule-tombstone",
            containerId = containerUuid,
            habitUuid = "h-uuid-2",
            removedAt = 5000L,
        )
        repo.record(active)
        repo.record(tombstoned)

        val bkp = backup(src)
        val dest = TestDatabaseHelper.createEmptyDatabase()
        try {
            val manifest = DatabaseSnapshotter.readManifest(bkp)
            assertNotNull(manifest)
            assertEquals(ContainerBackupManifest.CURRENT_FORMAT_VERSION, manifest.formatVersion)
            assertNotEquals(SourceRuleBackupInventory.emptyChecksum, manifest.sourceRulesSha256)

            val restoreResult = restore(bkp, dest)
            assertTrue(restoreResult is RestoreResult.Success)

            val restoredRepo = SQLiteSourceRuleRepository(dest)
            val restoredActive = restoredRepo.getById(SourceRuleId("rule-active"))
            assertNotNull(restoredActive)
            assertEquals(active, restoredActive)
            assertTrue(restoredActive.isActive)

            val restoredTombstoned = restoredRepo.getById(SourceRuleId("rule-tombstone"))
            assertNotNull(restoredTombstoned)
            assertEquals(tombstoned, restoredTombstoned)
            assertFalse(restoredTombstoned.isActive)
            assertEquals(5000L, restoredTombstoned.removedAt)
        } finally {
            src.close()
            bkp.close()
            dest.close()
        }
    }

    @Test
    fun tamperedSourceRuleRejectedBeforeDestinationChanges() = runTest {
        val src = source()
        SourceRuleSchema.createSchema(src)

        val containerUuid = src.querySingle("SELECT uuid FROM Containers LIMIT 1") { it.getText(0) }!!
        SQLiteSourceRuleRepository(src).record(sampleRule("rule-1", containerId = containerUuid))

        val bkp = backup(src)
        val dest = TestDatabaseHelper.createEmptyDatabase()
        try {
            bkp.run("UPDATE SourceRules SET include_descendants = 0 WHERE id = 'rule-1'")
            dest.run("CREATE TABLE Sentinel (id INTEGER)")
            dest.run("INSERT INTO Sentinel VALUES (99)")

            val result = restore(bkp, dest)
            assertTrue(result is RestoreResult.Failure)
            assertTrue(result.reason.contains("checksum", ignoreCase = true))

            // Destination sentinel remains unchanged
            assertEquals(99L, dest.queryLong("SELECT id FROM Sentinel"))
            assertFalse("SourceRules" in DatabaseSnapshotter.listTables(dest))
        } finally {
            src.close()
            bkp.close()
            dest.close()
        }
    }

    @Test
    fun backwardCompatibilityFormat3RestoresSuccessfully() = runTest {
        val src = source()
        val bkp = backup(src)
        val dest = TestDatabaseHelper.createEmptyDatabase()
        try {
            val current = DatabaseSnapshotter.readManifest(bkp)!!
            val format3Manifest = current.copy(
                formatVersion = 3,
                sourceRulesSha256 = "",
                canonicalChecksum = ContainerBackupManifest.computeCanonicalChecksum(
                    datasetUuid = current.datasetUuid,
                    sourceSnapshotSha256 = current.sourceSnapshotSha256,
                    createdAt = current.createdAt,
                    formatVersion = 3,
                    foundationVersion = current.foundationVersion,
                    focusSessionsSha256 = current.focusSessionsSha256,
                    contributionsSha256 = current.contributionsSha256,
                )
            )
            DatabaseSnapshotter.writeManifest(bkp, format3Manifest)

            val result = restore(bkp, dest)
            assertTrue(result is RestoreResult.Success)
        } finally {
            src.close()
            bkp.close()
            dest.close()
        }
    }

    @Test
    fun orphanSourceRuleReferencingNonExistentHabitRejectedByRestore() = runTest {
        val src = source()
        SourceRuleSchema.createSchema(src)

        val containerUuid = src.querySingle("SELECT uuid FROM Containers LIMIT 1") { it.getText(0) }!!
        src.run(
            """
            INSERT INTO SourceRules (id, source_kind, source_container_id, include_descendants, target_habit_uuid, value_mapping, is_enabled, effective_from, created_at, updated_at, removed_at)
            VALUES ('r-orphan', 'FOCUS_SESSION', '$containerUuid', 1, 'nonexistent-habit-uuid', 'FOCUS_DURATION', 1, 0, 1000, 1000, NULL)
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
    fun orphanSourceRuleReferencingNonExistentContainerRejectedByRestore() = runTest {
        val src = source()
        SourceRuleSchema.createSchema(src)

        src.run(
            """
            INSERT INTO SourceRules (id, source_kind, source_container_id, include_descendants, target_habit_uuid, value_mapping, is_enabled, effective_from, created_at, updated_at, removed_at)
            VALUES ('r-orphan-c', 'FOCUS_SESSION', 'nonexistent-container-uuid', 1, 'h-uuid-1', 'FOCUS_DURATION', 1, 0, 1000, 1000, NULL)
            """.trimIndent()
        )

        val bkp = backup(src)
        val dest = TestDatabaseHelper.createEmptyDatabase()
        try {
            val result = restore(bkp, dest)
            assertTrue(result is RestoreResult.Failure)
            assertTrue(result.errors.any { it.contains("references non-existent container ID") })
        } finally {
            src.close()
            bkp.close()
            dest.close()
        }
    }

    @Test
    fun format3WithSourceRulesTableRefusesSilentFactLoss() = runTest {
        val src = source()
        SourceRuleSchema.createSchema(src)
        val containerUuid = src.querySingle("SELECT uuid FROM Containers LIMIT 1") { it.getText(0) }!!
        SQLiteSourceRuleRepository(src).record(sampleRule("r-1", containerId = containerUuid))

        val bkp = backup(src)
        val dest = TestDatabaseHelper.createEmptyDatabase()
        try {
            val current = DatabaseSnapshotter.readManifest(bkp)!!
            DatabaseSnapshotter.writeManifest(bkp, current.copy(formatVersion = 3))

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
