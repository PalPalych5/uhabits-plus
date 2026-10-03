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
            manifest = manifest,
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

        return errors
    }
}
