/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.core.containers.backup

sealed interface DatasetKind {
    data class ExperimentalContainer(
        val datasetUuid: String,
        val foundationVersion: Int,
        val mode: String,
        val manifest: ContainerBackupManifest?,
    ) : DatasetKind

    data class LegacyProduction(
        val schemaVersion: Int,
    ) : DatasetKind

    data class UnsupportedNewer(
        val reason: String,
    ) : DatasetKind

    data class Malformed(
        val reason: String,
        val missingTables: List<String> = emptyList(),
    ) : DatasetKind
}

data class BackupInspectionResult(
    val kind: DatasetKind,
    val tableNames: Set<String>,
    val databaseVersion: Int,
    val manifest: ContainerBackupManifest? = null,
)
