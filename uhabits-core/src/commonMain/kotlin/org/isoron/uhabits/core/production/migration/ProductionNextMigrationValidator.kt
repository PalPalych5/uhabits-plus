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

import org.isoron.platform.io.Database
import org.isoron.platform.io.StepResult
import org.isoron.platform.io.getVersion
import org.isoron.platform.io.queryLong
import org.isoron.platform.io.querySingle
import org.isoron.uhabits.core.containers.migration.MigrationPlan
import org.isoron.uhabits.core.containers.migration.RawLegacyInventory
import org.isoron.uhabits.core.containers.migration.RawLegacyInventoryReader
import org.isoron.uhabits.core.containers.sqlite.OrganizationSchema

sealed interface ProductionValidationResult {
    data class Valid(
        val plan: MigrationPlan,
        val metadata: ProductionNextMetadata,
    ) : ProductionValidationResult

    data class Invalid(
        val errors: List<String>,
    ) : ProductionValidationResult
}

object ProductionNextMigrationValidator {

    private val EXPECTED_NEXT_TABLES = setOf(
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

    private val EXPECTED_LEGACY_TABLES = setOf(
        "Habits",
        "HabitBlocks",
        "HabitExtensions",
        "HabitGoals",
        "Repetitions",
        "EntryOps",
        "SyncQueue",
        "AppSettings",
    )

    private val ALLOWED_AUXILIARY_TABLES = setOf(
        "Events",
        "android_metadata",
        "sqlite_sequence",
        "BackupManifest",
    )

    fun validate(
        db: Database,
        beforeInventory: RawLegacyInventory,
        plan: MigrationPlan,
        expectedMetadata: ProductionNextMetadata,
    ): ProductionValidationResult {
        val errors = mutableListOf<String>()

        // 1. SQLite integrity check
        try {
            val integrity = db.querySingle("PRAGMA integrity_check") { it.getText(0) } ?: ""
            if (integrity != "ok") {
                errors.add("SQLite integrity_check failed: $integrity")
            }
        } catch (t: Throwable) {
            errors.add("Failed to run integrity_check: ${t.message}")
        }

        // 2. Foreign keys check
        try {
            val fkStmt = db.prepareStatement("PRAGMA foreign_key_check")
            while (fkStmt.step() == StepResult.ROW) {
                val table = fkStmt.getText(0)
                val rowid = fkStmt.getLong(1)
                val targetTable = fkStmt.getText(2)
                errors.add("Foreign key violation in table '$table' at rowid=$rowid pointing to '$targetTable'")
            }
            fkStmt.finalize()
        } catch (t: Throwable) {
            errors.add("Failed to run foreign_key_check: ${t.message}")
        }

        // 3. User version check
        val version = db.getVersion()
        if (version != 30) {
            errors.add("Database user_version must be 30 after migration, but was $version")
        }

        // 4. Schema and table inventory validation
        val allTables = mutableSetOf<String>()
        try {
            val tblStmt = db.prepareStatement("SELECT name FROM sqlite_master WHERE type = 'table'")
            while (tblStmt.step() == StepResult.ROW) {
                allTables.add(tblStmt.getText(0))
            }
            tblStmt.finalize()

            for (expectedNext in EXPECTED_NEXT_TABLES) {
                if (expectedNext !in allTables) {
                    errors.add("Missing expected Next schema table: '$expectedNext'")
                }
            }

            for (expectedLegacy in EXPECTED_LEGACY_TABLES) {
                if (expectedLegacy !in allTables) {
                    errors.add("Legacy table dropped during migration: '$expectedLegacy'")
                }
            }

            val allowedAll = EXPECTED_NEXT_TABLES + EXPECTED_LEGACY_TABLES + ALLOWED_AUXILIARY_TABLES
            for (table in allTables) {
                if (table !in allowedAll && !table.startsWith("sqlite_")) {
                    errors.add("Unexpected table found in database: '$table'")
                }
            }

            // Verify expected indexes
            val expectedIndexes = setOf(
                "idx_containers_tree",
                "idx_containers_active",
                "idx_habit_placements_contents",
                "idx_container_history_lookup",
                "idx_habit_placement_history_lookup",
                "idx_org_changes_recorded",
                "idx_user_tasks_container",
                "idx_user_tasks_parent",
                "idx_user_tasks_status",
                "idx_user_tasks_planned",
                "idx_user_tasks_order",
                "idx_focus_sessions_task",
                "idx_focus_sessions_started",
                "idx_focus_sessions_ended",
                "idx_contributions_habit",
                "idx_contributions_source",
                "idx_contributions_rule",
                "idx_source_rules_active_scope",
                "idx_source_rules_target",
                "idx_source_rules_container",
                "idx_source_rules_active",
            )
            val actualIndexes = mutableSetOf<String>()
            val idxStmt = db.prepareStatement("SELECT name FROM sqlite_master WHERE type = 'index'")
            while (idxStmt.step() == StepResult.ROW) {
                actualIndexes.add(idxStmt.getText(0))
            }
            idxStmt.finalize()

            for (idx in expectedIndexes) {
                if (idx !in actualIndexes) {
                    errors.add("Missing expected index: '$idx'")
                }
            }
        } catch (t: Throwable) {
            errors.add("Failed validating table/index inventory: ${t.message}")
        }

        // 5. Legacy data invariance (Zero mutation)
        try {
            val afterInventory = RawLegacyInventoryReader.read(db)
            if (afterInventory.snapshotSha256 != beforeInventory.snapshotSha256) {
                errors.add("Legacy data checksum changed after migration! Before: ${beforeInventory.snapshotSha256}, After: ${afterInventory.snapshotSha256}")
            }
            if (beforeInventory.blocks != afterInventory.blocks) {
                errors.add("HabitBlocks table was altered during migration")
            }
            if (beforeInventory.habits != afterInventory.habits) {
                errors.add("Habits table was altered during migration")
            }
            if (beforeInventory.extensions != afterInventory.extensions) {
                errors.add("HabitExtensions table was altered during migration")
            }
            if (beforeInventory.goals != afterInventory.goals) {
                errors.add("HabitGoals table was altered during migration")
            }
            if (beforeInventory.repetitions != afterInventory.repetitions) {
                errors.add("Repetitions table was altered during migration")
            }
            if (beforeInventory.entryOps != afterInventory.entryOps) {
                errors.add("EntryOps table was altered during migration")
            }
            if (beforeInventory.syncQueue != afterInventory.syncQueue) {
                errors.add("SyncQueue table was altered during migration")
            }
            if (beforeInventory.settings != afterInventory.settings) {
                errors.add("AppSettings table was altered during migration")
            }
        } catch (t: Throwable) {
            errors.add("Failed verifying legacy data invariance: ${t.message}")
        }

        // 6. Organization baseline verification
        try {
            val containerCount = db.queryLong("SELECT count(*) FROM Containers")
            if (containerCount != beforeInventory.blocks.size.toLong()) {
                errors.add("Container count mismatch: DB has $containerCount, expected ${beforeInventory.blocks.size}")
            }

            val distinctContainerUuids = db.queryLong("SELECT count(DISTINCT uuid) FROM Containers")
            if (distinctContainerUuids != containerCount) {
                errors.add("Duplicate container UUIDs detected: $distinctContainerUuids distinct vs $containerCount total")
            }

            val mapCount = db.queryLong("SELECT count(*) FROM LegacyBlockMap")
            if (mapCount != beforeInventory.blocks.size.toLong()) {
                errors.add("LegacyBlockMap count mismatch: DB has $mapCount, expected ${beforeInventory.blocks.size}")
            }

            val placementCount = db.queryLong("SELECT count(*) FROM HabitPlacements")
            if (placementCount != beforeInventory.habits.size.toLong()) {
                errors.add("Habit placement count mismatch: DB has $placementCount, expected ${beforeInventory.habits.size}")
            }

            // No cycles / root parent validity
            val nonRootCount = db.queryLong("SELECT count(*) FROM Containers WHERE parent_id IS NOT NULL")
            if (nonRootCount != 0L) {
                errors.add("Baseline migration created non-root containers ($nonRootCount), but all legacy blocks must be root containers")
            }

            // Dense ordering of root containers
            val rootOrderStmt = db.prepareStatement("SELECT sibling_order FROM Containers ORDER BY sibling_order ASC")
            var expectedSibling = 0
            while (rootOrderStmt.step() == StepResult.ROW) {
                val actualSibling = rootOrderStmt.getInt(0)
                if (actualSibling != expectedSibling) {
                    errors.add("Container sibling_order gap or duplicate: expected $expectedSibling, actual was $actualSibling")
                    break
                }
                expectedSibling++
            }
            rootOrderStmt.finalize()

            // Dense local order of HabitPlacements per container context
            val contextsStmt = db.prepareStatement("SELECT DISTINCT container_id FROM HabitPlacements")
            val containerContexts = mutableListOf<Long?>()
            while (contextsStmt.step() == StepResult.ROW) {
                containerContexts.add(contextsStmt.getLongOrNull(0))
            }
            contextsStmt.finalize()

            for (ctx in containerContexts) {
                val orderQuery = if (ctx == null) {
                    "SELECT local_order FROM HabitPlacements WHERE container_id IS NULL ORDER BY local_order ASC"
                } else {
                    "SELECT local_order FROM HabitPlacements WHERE container_id = $ctx ORDER BY local_order ASC"
                }
                val orderStmt = db.prepareStatement(orderQuery)
                var expectedOrder = 0
                while (orderStmt.step() == StepResult.ROW) {
                    val actualOrder = orderStmt.getInt(0)
                    if (actualOrder != expectedOrder) {
                        errors.add("HabitPlacements local_order gap in context $ctx: expected $expectedOrder, actual was $actualOrder")
                        break
                    }
                    expectedOrder++
                }
                orderStmt.finalize()
            }

            // Revision continuity: OrganizationChanges has exactly 1 baseline record
            val changesCount = db.queryLong("SELECT count(*) FROM OrganizationChanges")
            if (changesCount != 1L) {
                errors.add("OrganizationChanges expected exactly 1 baseline row, but found $changesCount")
            } else {
                val changeStmt = db.prepareStatement("SELECT revision, op_uuid, recorded_at, operation_type FROM OrganizationChanges WHERE revision = 1")
                if (changeStmt.step() == StepResult.ROW) {
                    val rev = changeStmt.getLong(0)
                    val opUuid = changeStmt.getText(1)
                    val recordedAt = changeStmt.getLong(2)
                    if (rev != 1L) errors.add("OrganizationChanges baseline revision was $rev, expected 1")
                    if (opUuid != expectedMetadata.baselineOpUuid) errors.add("OrganizationChanges op_uuid mismatch: '$opUuid' vs '${expectedMetadata.baselineOpUuid}'")
                    if (recordedAt != expectedMetadata.cutoverAt) errors.add("OrganizationChanges recorded_at mismatch: $recordedAt vs ${expectedMetadata.cutoverAt}")
                } else {
                    errors.add("OrganizationChanges baseline row at revision=1 is missing")
                }
                changeStmt.finalize()
            }

            // Exact current / history parity (Requirement 18)
            val containerHistoryCount = db.queryLong("SELECT count(*) FROM ContainerHistory")
            if (containerHistoryCount != containerCount) {
                errors.add("ContainerHistory count mismatch: $containerHistoryCount vs $containerCount containers")
            }

            // Verify each Container row matches its ContainerHistory counterpart
            val chCheckStmt = db.prepareStatement(
                """
                SELECT c.id, c.name, c.color, c.icon, c.sibling_order, c.is_archived, c.created_at, c.updated_at, c.deleted_at,
                       h.name, h.color, h.icon, h.sibling_order, h.is_archived, h.created_at, h.updated_at, h.deleted_at
                FROM Containers c
                JOIN ContainerHistory h ON c.id = h.container_id AND h.revision = 1
                """.trimIndent()
            )
            var matchedContainers = 0L
            while (chCheckStmt.step() == StepResult.ROW) {
                matchedContainers++
                val cName = chCheckStmt.getText(1)
                val hName = chCheckStmt.getText(9)
                if (cName != hName) errors.add("ContainerHistory name mismatch for container ${chCheckStmt.getLong(0)}: '$cName' vs '$hName'")
                val cOrder = chCheckStmt.getInt(4)
                val hOrder = chCheckStmt.getInt(12)
                if (cOrder != hOrder) errors.add("ContainerHistory order mismatch for container ${chCheckStmt.getLong(0)}: $cOrder vs $hOrder")
            }
            chCheckStmt.finalize()
            if (matchedContainers != containerCount) {
                errors.add("ContainerHistory matching row count mismatch: $matchedContainers vs $containerCount")
            }

            // Verify each HabitPlacement matches its HabitPlacementHistory counterpart
            val placementHistoryCount = db.queryLong("SELECT count(*) FROM HabitPlacementHistory")
            if (placementHistoryCount != placementCount) {
                errors.add("HabitPlacementHistory count mismatch: $placementHistoryCount vs $placementCount placements")
            }

            val hphCheckStmt = db.prepareStatement(
                """
                SELECT p.habit_id, p.container_id, p.local_order, p.placement_origin,
                       h.container_id, h.local_order, h.placement_origin
                FROM HabitPlacements p
                JOIN HabitPlacementHistory h ON p.habit_id = h.habit_id AND h.revision = 1
                """.trimIndent()
            )
            var matchedPlacements = 0L
            while (hphCheckStmt.step() == StepResult.ROW) {
                matchedPlacements++
                val pContainer = hphCheckStmt.getLongOrNull(1)
                val hContainer = hphCheckStmt.getLongOrNull(4)
                if (pContainer != hContainer) errors.add("HabitPlacementHistory container_id mismatch for habit ${hphCheckStmt.getLong(0)}: $pContainer vs $hContainer")
                val pOrder = hphCheckStmt.getInt(2)
                val hOrder = hphCheckStmt.getInt(5)
                if (pOrder != hOrder) errors.add("HabitPlacementHistory local_order mismatch for habit ${hphCheckStmt.getLong(0)}: $pOrder vs $hOrder")
                val pOrigin = hphCheckStmt.getText(3)
                val hOrigin = hphCheckStmt.getText(6)
                if (pOrigin != hOrigin) errors.add("HabitPlacementHistory origin mismatch for habit ${hphCheckStmt.getLong(0)}: '$pOrigin' vs '$hOrigin'")
            }
            hphCheckStmt.finalize()
            if (matchedPlacements != placementCount) {
                errors.add("HabitPlacementHistory matching row count mismatch: $matchedPlacements vs $placementCount")
            }

            // Provenance verification: unknown vs unassigned
            val rawExtByHabit = beforeInventory.extensions.associateBy { it.habitId }
            val rawHabitById = beforeInventory.habits.associateBy { it.id }
            val blockLocalIdMap = mutableMapOf<Long, Long>()
            val blockMapStmt = db.prepareStatement("SELECT legacy_block_id, container_id FROM LegacyBlockMap")
            while (blockMapStmt.step() == StepResult.ROW) {
                blockLocalIdMap[blockMapStmt.getLong(0)] = blockMapStmt.getLong(1)
            }
            blockMapStmt.finalize()

            val placementStmt = db.prepareStatement("SELECT habit_id, container_id, placement_origin FROM HabitPlacements")
            while (placementStmt.step() == StepResult.ROW) {
                val habitId = placementStmt.getLong(0)
                val containerId = placementStmt.getLongOrNull(1)
                val origin = placementStmt.getText(2)
                val ext = rawExtByHabit[habitId]
                if (ext == null) {
                    if (origin != "UNKNOWN_LEGACY") errors.add("Habit $habitId missing extension must have origin UNKNOWN_LEGACY, was '$origin'")
                    if (containerId != null) errors.add("Habit $habitId missing extension must have container_id NULL, was $containerId")
                } else if (ext.blockId == null) {
                    if (origin != "MIGRATION_SNAPSHOT") errors.add("Habit $habitId with null block_id must have origin MIGRATION_SNAPSHOT, was '$origin'")
                    if (containerId != null) errors.add("Habit $habitId with null block_id must have container_id NULL, was $containerId")
                } else {
                    val expectedContainerId = blockLocalIdMap[ext.blockId]
                    if (origin != "MIGRATION_SNAPSHOT") errors.add("Habit $habitId with block_id must have origin MIGRATION_SNAPSHOT, was '$origin'")
                    if (containerId != expectedContainerId) errors.add("Habit $habitId mapped to wrong container: $containerId vs expected $expectedContainerId")
                }
            }
            placementStmt.finalize()

        } catch (t: Throwable) {
            errors.add("Failed validating organization baseline: ${t.message}")
        }

        // 7. Empty new stores verification
        try {
            val taskCount = db.queryLong("SELECT count(*) FROM UserTasks")
            if (taskCount != 0L) errors.add("UserTasks table must start empty, but has $taskCount rows")

            val focusCount = db.queryLong("SELECT count(*) FROM FocusSessions")
            if (focusCount != 0L) errors.add("FocusSessions table must start empty, but has $focusCount rows")

            val journalCount = db.queryLong("SELECT count(*) FROM FocusCaptureJournal")
            if (journalCount != 0L) errors.add("FocusCaptureJournal table must start empty, but has $journalCount rows")

            val contribCount = db.queryLong("SELECT count(*) FROM Contributions")
            if (contribCount != 0L) errors.add("Contributions table must start empty, but has $contribCount rows")

            val ruleCount = db.queryLong("SELECT count(*) FROM SourceRules")
            if (ruleCount != 0L) errors.add("SourceRules table must start empty, but has $ruleCount rows")
        } catch (t: Throwable) {
            errors.add("Failed validating new stores emptiness: ${t.message}")
        }

        // 8. Metadata verification
        try {
            val stateStmt = db.prepareStatement(
                """
                SELECT dataset_uuid, foundation_version, mode, cutover_revision, current_revision,
                       cutover_at, source_schema_version, source_snapshot_sha256, source_file_sha256,
                       source_schema_sha256, runtime_authority, authority_epoch, conversion_op_uuid
                FROM OrganizationState WHERE id = 1
                """.trimIndent()
            )
            if (stateStmt.step() == StepResult.ROW) {
                val datasetUuid = stateStmt.getText(0)
                val foundationVersion = stateStmt.getInt(1)
                val mode = stateStmt.getText(2)
                val cutoverRev = stateStmt.getLong(3)
                val currentRev = stateStmt.getLong(4)
                val cutoverAt = stateStmt.getLong(5)
                val sourceSchemaVer = stateStmt.getInt(6)
                val sourceSnapshotSha = stateStmt.getText(7)
                val sourceFileSha = stateStmt.getTextOrNull(8)
                val sourceSchemaSha = stateStmt.getTextOrNull(9)
                val runtimeAuthority = stateStmt.getText(10)
                val authorityEpoch = stateStmt.getLong(11)
                val conversionOpUuid = stateStmt.getTextOrNull(12)

                if (datasetUuid != expectedMetadata.datasetUuid) errors.add("OrganizationState dataset_uuid mismatch: '$datasetUuid' vs '${expectedMetadata.datasetUuid}'")
                if (foundationVersion != 1) errors.add("OrganizationState foundation_version was $foundationVersion, expected 1")
                if (mode != expectedMetadata.mode) errors.add("OrganizationState mode was '$mode', expected '${expectedMetadata.mode}'")
                if (cutoverRev != 1L || currentRev != 1L) errors.add("OrganizationState revision mismatch: current=$currentRev, cutover=$cutoverRev")
                if (cutoverAt != expectedMetadata.cutoverAt) errors.add("OrganizationState cutover_at mismatch: $cutoverAt vs ${expectedMetadata.cutoverAt}")
                if (sourceSchemaVer != 29) errors.add("OrganizationState source_schema_version was $sourceSchemaVer, expected 29")
                if (sourceSnapshotSha != beforeInventory.snapshotSha256) errors.add("OrganizationState source_snapshot_sha256 mismatch")
                if (runtimeAuthority != expectedMetadata.runtimeAuthority) {
                    errors.add("OrganizationState runtime_authority mismatch: expected '${expectedMetadata.runtimeAuthority}', was '$runtimeAuthority'")
                }
                if (authorityEpoch != expectedMetadata.authorityEpoch) {
                    errors.add("OrganizationState authority_epoch mismatch: expected ${expectedMetadata.authorityEpoch}, was $authorityEpoch")
                }
                if (expectedMetadata.conversionOpUuid.isNotBlank() && conversionOpUuid != expectedMetadata.conversionOpUuid) {
                    errors.add("OrganizationState conversion_op_uuid mismatch: '$conversionOpUuid' vs '${expectedMetadata.conversionOpUuid}'")
                }
            } else {
                errors.add("OrganizationState row with id=1 is missing")
            }
            stateStmt.finalize()
        } catch (t: Throwable) {
            errors.add("Failed validating OrganizationState metadata: ${t.message}")
        }

        return if (errors.isEmpty()) {
            ProductionValidationResult.Valid(plan, expectedMetadata)
        } else {
            ProductionValidationResult.Invalid(errors)
        }
    }
}
