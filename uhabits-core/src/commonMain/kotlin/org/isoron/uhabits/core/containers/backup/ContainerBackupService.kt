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
import org.isoron.platform.io.getVersion
import org.isoron.platform.io.querySingle
import org.isoron.uhabits.core.containers.migration.RawLegacyInventoryReader
import org.isoron.uhabits.core.containers.sqlite.OrganizationSchema

sealed interface BackupCreationResult {
    data class Success(
        val manifest: ContainerBackupManifest,
        val manifestJson: String,
    ) : BackupCreationResult

    data class Failure(
        val error: String,
        val cause: Throwable? = null,
    ) : BackupCreationResult
}

object ContainerBackupService {

    fun createBackup(
        sourceDb: Database,
        targetDb: Database,
        timestamp: Long,
        preferences: Map<String, String> = emptyMap(),
    ): BackupCreationResult {
        try {
            // 1. Inspect source database
            val inspection = ContainerBackupRouter.inspect(sourceDb)
            val containerKind = inspection.kind as? DatasetKind.ExperimentalContainer
                ?: return BackupCreationResult.Failure(
                    "Source database is not an experimental container dataset: ${inspection.kind}"
                )

            // 2. Read inventory and compute checksums
            val inventory = RawLegacyInventoryReader.read(sourceDb)
            val canonicalChecksum = ContainerBackupManifest.computeCanonicalChecksum(
                datasetUuid = containerKind.datasetUuid,
                sourceSnapshotSha256 = inventory.snapshotSha256,
                createdAt = timestamp,
            )

            val appSettingsMap = inventory.settings.associate { it.key to (it.longValue ?: 0L) }
            val sanitizedPrefs = ContainerBackupManifest.sanitizePreferences(preferences)

            // 3. Create manifest
            val manifest = ContainerBackupManifest(
                formatVersion = ContainerBackupManifest.CURRENT_FORMAT_VERSION,
                sourceSchemaVersion = sourceDb.getVersion(),
                foundationVersion = containerKind.foundationVersion,
                datasetMode = containerKind.mode,
                datasetUuid = containerKind.datasetUuid,
                createdAt = timestamp,
                sourceSnapshotSha256 = inventory.snapshotSha256,
                canonicalChecksum = canonicalChecksum,
                appSettings = appSettingsMap,
                nonSecretPreferences = sanitizedPrefs,
            )

            // 4. Initialize schema on target
            DatabaseSnapshotter.initTargetSchema(sourceDb, targetDb)

            // 5. Copy all rows
            DatabaseSnapshotter.copyAllData(sourceDb, targetDb)

            // 6. Write manifest into target database
            DatabaseSnapshotter.writeManifest(targetDb, manifest)

            // 7. Verify target database
            val integrity = targetDb.querySingle("PRAGMA integrity_check") { it.getText(0) }
            if (integrity != "ok") {
                return BackupCreationResult.Failure("Target database integrity check failed: $integrity")
            }

            OrganizationSchema.enableForeignKeys(targetDb)
            val fkStmt = targetDb.prepareStatement("PRAGMA foreign_key_check")
            val hasFkErrors = fkStmt.step() == StepResult.ROW
            fkStmt.finalize()
            if (hasFkErrors) {
                return BackupCreationResult.Failure("Target database failed foreign key check")
            }

            return BackupCreationResult.Success(manifest = manifest, manifestJson = manifest.toJson())
        } catch (t: Throwable) {
            return BackupCreationResult.Failure("Failed creating backup: ${t.message}", cause = t)
        }
    }
}
