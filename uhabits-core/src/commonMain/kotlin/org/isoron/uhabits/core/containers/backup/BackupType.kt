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
