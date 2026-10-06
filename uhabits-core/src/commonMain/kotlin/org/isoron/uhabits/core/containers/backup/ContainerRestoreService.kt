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

import org.isoron.platform.io.Database
import org.isoron.platform.io.StepResult
import org.isoron.platform.io.queryLong
import org.isoron.platform.io.querySingle
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.containers.OrganizationRevision
import org.isoron.uhabits.core.containers.PlacementOrigin
import org.isoron.uhabits.core.containers.migration.LegacyBlockMapRecord
import org.isoron.uhabits.core.containers.migration.LegacyContainerMigrationExecutor
import org.isoron.uhabits.core.containers.migration.LegacyContainerMigrationPlanner
import org.isoron.uhabits.core.containers.migration.LegacyContainerMigrationValidator
import org.isoron.uhabits.core.containers.migration.MigrationExecutionResult
import org.isoron.uhabits.core.containers.migration.MigrationPlan
import org.isoron.uhabits.core.containers.migration.MigrationValidationResult
import org.isoron.uhabits.core.containers.migration.OrganizationStateRecord
import org.isoron.uhabits.core.containers.migration.RawLegacyInventory
import org.isoron.uhabits.core.containers.migration.RawLegacyInventoryReader
import org.isoron.uhabits.core.containers.sqlite.OrganizationSchema
import org.isoron.uhabits.core.containers.sqlite.SQLiteOrganizationStore

sealed interface RestoreResult {
    data class Success(
        val manifest: ContainerBackupManifest,
        val kind: DatasetKind,
        val wasMigratedFromLegacy: Boolean = false,
    ) : RestoreResult

    data class Failure(
        val reason: String,
        val errors: List<String> = emptyList(),
        val cause: Throwable? = null,
    ) : RestoreResult
}

object ContainerRestoreService {

    fun restore(
        backupDb: Database,
        stagingDb: Database,
        destinationDb: Database,
        cutoverTimestamp: Long = 0L,
        externalManifest: ContainerBackupManifest? = null,
    ): RestoreResult {
        try {
            // 1. Inspect backup dataset
            val inspection = ContainerBackupRouter.inspect(backupDb, externalManifest)

            when (val kind = inspection.kind) {
                is DatasetKind.UnsupportedNewer -> {
                    return RestoreResult.Failure("Unsupported newer backup format: ${kind.reason}")
                }
                is DatasetKind.Malformed -> {
                    return RestoreResult.Failure(
                        "Malformed backup cannot be restored: ${kind.reason}",
                        errors = if (kind.missingTables.isNotEmpty()) listOf("Missing tables: ${kind.missingTables}") else emptyList()
                    )
                }
                is DatasetKind.LegacyProduction -> {
                    return restoreLegacyBackup(
                        backupDb = backupDb,
                        stagingDb = stagingDb,
                        destinationDb = destinationDb,
                        cutoverTimestamp = if (cutoverTimestamp > 0L) cutoverTimestamp else 1000000L,
                    )
                }
                is DatasetKind.ExperimentalContainer -> {
                    return restoreExperimentalBackup(
                        backupDb = backupDb,
                        stagingDb = stagingDb,
                        destinationDb = destinationDb,
                        containerKind = kind,
                        externalManifest = externalManifest,
                    )
                }
            }
        } catch (t: Throwable) {
            return RestoreResult.Failure("Restore encountered an unexpected error: ${t.message}", cause = t)
        }
    }

    private fun restoreExperimentalBackup(
        backupDb: Database,
        stagingDb: Database,
        destinationDb: Database,
        containerKind: DatasetKind.ExperimentalContainer,
        externalManifest: ContainerBackupManifest?,
    ): RestoreResult {
        // 1. Copy backup data to isolated staging database
        DatabaseSnapshotter.initTargetSchema(backupDb, stagingDb)
        DatabaseSnapshotter.copyAllData(backupDb, stagingDb)

        // 2. Validate manifest
        val manifest = externalManifest
            ?: DatabaseSnapshotter.readManifest(stagingDb)
            ?: containerKind.manifest

        if (manifest == null) {
            return RestoreResult.Failure("Experimental container backup is missing manifest")
        }

        if (manifest.formatVersion > ContainerBackupManifest.CURRENT_FORMAT_VERSION) {
            return RestoreResult.Failure("Unsupported backup format version: ${manifest.formatVersion}")
        }
        if (manifest.foundationVersion > ContainerBackupManifest.CURRENT_FOUNDATION_VERSION) {
            return RestoreResult.Failure("Unsupported foundation version: ${manifest.foundationVersion}")
        }
        if (manifest.formatVersion == 1 && "FocusSessions" in DatabaseSnapshotter.listTables(backupDb)) {
            return RestoreResult.Failure("Format 1 cannot inventory FocusSessions; refusing silent fact loss")
        }
        if (manifest.formatVersion < 3 && "Contributions" in DatabaseSnapshotter.listTables(backupDb)) {
            return RestoreResult.Failure("Format ${manifest.formatVersion} cannot inventory Contributions; refusing silent fact loss")
        }
        if (manifest.formatVersion < 4 && "SourceRules" in DatabaseSnapshotter.listTables(backupDb)) {
            return RestoreResult.Failure("Format ${manifest.formatVersion} cannot inventory SourceRules; refusing silent fact loss")
        }

        // 3. Staging DB integrity and foreign keys
        val integrity = stagingDb.querySingle("PRAGMA integrity_check") { it.getText(0) }
        if (integrity != "ok") {
            return RestoreResult.Failure("Staging database failed integrity_check: $integrity")
        }

        OrganizationSchema.enableForeignKeys(stagingDb)
        val fkStmt = stagingDb.prepareStatement("PRAGMA foreign_key_check")
        val fkErrors = mutableListOf<String>()
        while (fkStmt.step() == StepResult.ROW) {
            fkErrors.add("FK violation: table=${fkStmt.getText(0)}, rowid=${fkStmt.getLong(1)}, target=${fkStmt.getText(2)}")
        }
        fkStmt.finalize()
        if (fkErrors.isNotEmpty()) {
            return RestoreResult.Failure("Staging database has foreign key violations", errors = fkErrors)
        }

        // 4. Verify checksum against manifest
        val currentInventory = RawLegacyInventoryReader.read(stagingDb)
        if (manifest.formatVersion >= 2) {
            val focusChecksum = FocusBackupInventory.checksum(stagingDb)
            if (manifest.focusSessionsSha256 != focusChecksum) return RestoreResult.Failure("FocusSessions checksum mismatch")
            val contributionChecksum = if (manifest.formatVersion >= 3) {
                val sum = ContributionBackupInventory.checksum(stagingDb, includeRuleId = manifest.formatVersion >= 4)
                if (manifest.contributionsSha256 != sum) return RestoreResult.Failure("Contributions checksum mismatch")
                sum
            } else {
                ContributionBackupInventory.emptyChecksum
            }
            val sourceRulesChecksum = if (manifest.formatVersion >= 4) {
                val sum = SourceRuleBackupInventory.checksum(stagingDb)
                if (manifest.sourceRulesSha256 != sum) return RestoreResult.Failure("SourceRules checksum mismatch")
                sum
            } else {
                SourceRuleBackupInventory.emptyChecksum
            }
            val canonical = ContainerBackupManifest.computeCanonicalChecksum(
                manifest.datasetUuid, manifest.sourceSnapshotSha256, manifest.createdAt,
                manifest.formatVersion, manifest.foundationVersion, manifest.focusSessionsSha256,
                if (manifest.formatVersion >= 3) manifest.contributionsSha256 else contributionChecksum,
                if (manifest.formatVersion >= 4) manifest.sourceRulesSha256 else sourceRulesChecksum,
            )
            if (canonical != manifest.canonicalChecksum) return RestoreResult.Failure("Manifest canonical checksum mismatch")
        }
        if (manifest.sourceSnapshotSha256.isNotBlank() && manifest.sourceSnapshotSha256 != currentInventory.snapshotSha256) {
            return RestoreResult.Failure(
                "Source snapshot checksum mismatch: manifest expected '${manifest.sourceSnapshotSha256}', but database has '${currentInventory.snapshotSha256}'"
            )
        }

        // 5. Semantic Validation on Staging (tree cycles, valid placements, dense ordering, revisions)
        val semanticErrors = validateContainerDatasetSemantics(stagingDb, currentInventory)
        if (semanticErrors.isNotEmpty()) {
            return RestoreResult.Failure("Staging dataset failed semantic validation", errors = semanticErrors)
        }

        // 6. Only upon 100% valid staging, replace destinationDb
        DatabaseSnapshotter.initTargetSchema(stagingDb, destinationDb)
        DatabaseSnapshotter.copyAllData(stagingDb, destinationDb)
        DatabaseSnapshotter.writeManifest(destinationDb, manifest)

        return RestoreResult.Success(
            manifest = manifest.copy(nonSecretPreferences = ContainerBackupManifest.sanitizePreferences(manifest.nonSecretPreferences)),
            kind = containerKind,
            wasMigratedFromLegacy = false,
        )
    }

    private fun restoreLegacyBackup(
        backupDb: Database,
        stagingDb: Database,
        destinationDb: Database,
        cutoverTimestamp: Long,
    ): RestoreResult {
        // 1. Copy legacy data into staging DB (backupDb remains 100% untouched)
        DatabaseSnapshotter.initTargetSchema(backupDb, stagingDb)
        DatabaseSnapshotter.copyAllData(backupDb, stagingDb)

        // 2. Read raw inventory from staging DB
        val inventory = RawLegacyInventoryReader.read(stagingDb)

        // 3. Plan migration
        val plan = LegacyContainerMigrationPlanner.plan(
            inventory = inventory,
            cutoverTimestamp = cutoverTimestamp,
        )
        if (!plan.canMigrate) {
            return RestoreResult.Failure(
                "Legacy backup cannot be migrated to experimental dataset: found blocking issues",
                errors = plan.issues.map { it.message }
            )
        }

        // 4. Execute migration on staging DB
        val execResult = LegacyContainerMigrationExecutor.execute(stagingDb, plan)
        if (execResult is MigrationExecutionResult.Failure) {
            return RestoreResult.Failure(
                "Migration execution failed on staging database",
                errors = execResult.issues.map { it.message },
                cause = execResult.cause,
            )
        }

        // 5. Validate staging DB with PR3 semantic validator
        val validation = LegacyContainerMigrationValidator.validate(stagingDb, inventory, plan)
        if (validation is MigrationValidationResult.Invalid) {
            return RestoreResult.Failure(
                "Migrated staging database failed semantic validation",
                errors = validation.errors,
            )
        }

        // 6. Create manifest for newly migrated dataset
        val appSettingsMap = inventory.settings.associate { it.key to (it.longValue ?: 0L) }
        val canonicalChecksum = ContainerBackupManifest.computeCanonicalChecksum(
            datasetUuid = plan.organizationState.datasetUuid,
            sourceSnapshotSha256 = inventory.snapshotSha256,
            createdAt = cutoverTimestamp,
            formatVersion = ContainerBackupManifest.CURRENT_FORMAT_VERSION,
            foundationVersion = ContainerBackupManifest.CURRENT_FOUNDATION_VERSION,
            focusSessionsSha256 = FocusBackupInventory.emptyChecksum,
            contributionsSha256 = ContributionBackupInventory.emptyChecksum,
            sourceRulesSha256 = SourceRuleBackupInventory.emptyChecksum,
        )
        val manifest = ContainerBackupManifest(
            formatVersion = ContainerBackupManifest.CURRENT_FORMAT_VERSION,
            sourceSchemaVersion = 29,
            foundationVersion = ContainerBackupManifest.CURRENT_FOUNDATION_VERSION,
            datasetMode = plan.organizationState.mode,
            datasetUuid = plan.organizationState.datasetUuid,
            createdAt = cutoverTimestamp,
            sourceSnapshotSha256 = inventory.snapshotSha256,
            canonicalChecksum = canonicalChecksum,
            appSettings = appSettingsMap,
            nonSecretPreferences = emptyMap(),
            focusSessionsSha256 = FocusBackupInventory.emptyChecksum,
            contributionsSha256 = ContributionBackupInventory.emptyChecksum,
            sourceRulesSha256 = SourceRuleBackupInventory.emptyChecksum,
        )

        // 7. Write manifest to staging DB
        DatabaseSnapshotter.writeManifest(stagingDb, manifest)

        // 8. Copy to destination database
        DatabaseSnapshotter.initTargetSchema(stagingDb, destinationDb)
        DatabaseSnapshotter.copyAllData(stagingDb, destinationDb)
        DatabaseSnapshotter.writeManifest(destinationDb, manifest)

        return RestoreResult.Success(
            manifest = manifest,
            kind = DatasetKind.ExperimentalContainer(
                datasetUuid = plan.organizationState.datasetUuid,
                foundationVersion = ContainerBackupManifest.CURRENT_FOUNDATION_VERSION,
                mode = plan.organizationState.mode,
                manifest = manifest,
            ),
            wasMigratedFromLegacy = true,
        )
    }

    internal fun validateContainerDatasetSemantics(db: Database, inventory: RawLegacyInventory): List<String> {
        val errors = mutableListOf<String>()

        val store = SQLiteOrganizationStore(db)
        val state = store.readState()

        // Containers validation: check for tree cycles and parent existence
        val allContainers = store.getAllContainers(includeDeleted = true)
        val containerIds = allContainers.map { it.id }.toSet()

        for (c in allContainers) {
            val pId = c.parentId
            if (pId != null && !containerIds.contains(pId)) {
                errors.add("Container '${c.id.value}' references missing parent '${pId.value}'")
            }
        }

        // Cycle check using DFS
        val visited = mutableSetOf<ContainerId>()
        val recursionStack = mutableSetOf<ContainerId>()

        fun hasCycle(id: ContainerId): Boolean {
            visited.add(id)
            recursionStack.add(id)
            val parent = allContainers.find { it.id == id }?.parentId
            if (parent != null) {
                if (!visited.contains(parent) && hasCycle(parent)) return true
                if (recursionStack.contains(parent)) return true
            }
            recursionStack.remove(id)
            return false
        }

        for (c in allContainers) {
            if (!visited.contains(c.id)) {
                if (hasCycle(c.id)) {
                    errors.add("Cycle detected in container hierarchy containing '${c.id.value}'")
                    break
                }
            }
        }

        // Placements validation: exactly 1 placement per habit
        val placementCount = db.queryLong("SELECT count(*) FROM HabitPlacements")
        val habitCount = db.queryLong("SELECT count(*) FROM Habits")
        if (placementCount != habitCount) {
            errors.add("Placements cardinality mismatch: $placementCount placements vs $habitCount habits")
        }

        // Check history tables presence and row count
        val containerHistoryCount = db.queryLong("SELECT count(*) FROM ContainerHistory")
        val containerCount = db.queryLong("SELECT count(*) FROM Containers")
        if (containerHistoryCount < containerCount) {
            errors.add("Incomplete ContainerHistory: $containerHistoryCount history rows vs $containerCount containers")
        }

        val placementHistoryCount = db.queryLong("SELECT count(*) FROM HabitPlacementHistory")
        if (placementHistoryCount < placementCount) {
            errors.add("Incomplete HabitPlacementHistory: $placementHistoryCount history rows vs $placementCount placements")
        }

        // HabitVisualIdentities referential integrity check
        val tables = DatabaseSnapshotter.listTables(db)
        if (tables.contains("HabitVisualIdentities")) {
            val invalidVisualIdsStmt = db.prepareStatement(
                """
                SELECT hvi.habit_uuid FROM HabitVisualIdentities hvi
                LEFT JOIN Habits h ON hvi.habit_uuid = h.uuid
                WHERE h.uuid IS NULL
                """.trimIndent()
            )
            while (invalidVisualIdsStmt.step() == StepResult.ROW) {
                val orphanUuid = invalidVisualIdsStmt.getText(0)
                errors.add("HabitVisualIdentity references non-existent habit UUID '$orphanUuid'")
            }
            invalidVisualIdsStmt.finalize()
        }

        // UserTasks referential integrity and cycle check
        if (tables.contains("UserTasks")) {
            val orphanParentStmt = db.prepareStatement(
                """
                SELECT ut.id, ut.parent_task_id FROM UserTasks ut
                LEFT JOIN UserTasks parent ON ut.parent_task_id = parent.id
                WHERE ut.parent_task_id IS NOT NULL AND parent.id IS NULL
                """.trimIndent()
            )
            while (orphanParentStmt.step() == StepResult.ROW) {
                val taskId = orphanParentStmt.getText(0)
                val parentId = orphanParentStmt.getText(1)
                errors.add("UserTask '$taskId' references non-existent parentTask '$parentId'")
            }
            orphanParentStmt.finalize()

            // Subtask cycle detection
            val parentMap = mutableMapOf<String, String>()
            val allTasksStmt = db.prepareStatement("SELECT id, parent_task_id FROM UserTasks WHERE parent_task_id IS NOT NULL")
            while (allTasksStmt.step() == StepResult.ROW) {
                parentMap[allTasksStmt.getText(0)] = allTasksStmt.getText(1)
            }
            allTasksStmt.finalize()

            val visited = mutableSetOf<String>()
            val stack = mutableSetOf<String>()
            fun hasTaskCycle(id: String): Boolean {
                visited.add(id)
                stack.add(id)
                val parent = parentMap[id]
                if (parent != null) {
                    if (stack.contains(parent)) return true
                    if (!visited.contains(parent) && hasTaskCycle(parent)) return true
                }
                stack.remove(id)
                return false
            }
            for (id in parentMap.keys) {
                if (!visited.contains(id) && hasTaskCycle(id)) {
                    errors.add("Cycle detected in UserTasks subtask hierarchy involving task '$id'")
                    break
                }
            }
        }

        if (tables.contains("Contributions")) {
            val orphanContributionStmt = db.prepareStatement(
                """
                SELECT c.id, c.target_habit_uuid FROM Contributions c
                LEFT JOIN Habits h ON c.target_habit_uuid = h.uuid
                WHERE h.uuid IS NULL
                """.trimIndent()
            )
            while (orphanContributionStmt.step() == StepResult.ROW) {
                val contribId = orphanContributionStmt.getText(0)
                val habitUuid = orphanContributionStmt.getText(1)
                errors.add("Contribution '$contribId' references non-existent habit UUID '$habitUuid'")
            }
            orphanContributionStmt.finalize()
        }

        if (tables.contains("SourceRules")) {
            val orphanRuleHabitStmt = db.prepareStatement(
                """
                SELECT r.id, r.target_habit_uuid FROM SourceRules r
                LEFT JOIN Habits h ON r.target_habit_uuid = h.uuid
                WHERE h.uuid IS NULL
                """.trimIndent()
            )
            while (orphanRuleHabitStmt.step() == StepResult.ROW) {
                val ruleId = orphanRuleHabitStmt.getText(0)
                val habitUuid = orphanRuleHabitStmt.getText(1)
                errors.add("SourceRule '$ruleId' references non-existent habit UUID '$habitUuid'")
            }
            orphanRuleHabitStmt.finalize()

            val orphanRuleContainerStmt = db.prepareStatement(
                """
                SELECT r.id, r.source_container_id FROM SourceRules r
                LEFT JOIN Containers c ON r.source_container_id = c.uuid
                WHERE c.uuid IS NULL
                """.trimIndent()
            )
            while (orphanRuleContainerStmt.step() == StepResult.ROW) {
                val ruleId = orphanRuleContainerStmt.getText(0)
                val containerId = orphanRuleContainerStmt.getText(1)
                errors.add("SourceRule '$ruleId' references non-existent container ID '$containerId'")
            }
            orphanRuleContainerStmt.finalize()
        }

        return errors
    }
}
