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

import org.isoron.uhabits.core.containers.migration.RawLegacyInventory

/**
 * Explicit domain separation of backup recovery domains as required by PR4:
 * 1. Database data (SQLite tables and rows)
 * 2. Dataset metadata (manifest, identity, foundation version)
 * 3. Recoverable preferences (non-secret settings)
 * 4. Account / auth secrets (explicitly excluded / omitted from backups)
 */
sealed interface RecoveryDomain {
    data class DatabaseData(
        val inventory: RawLegacyInventory,
        val tableNames: Set<String>,
    ) : RecoveryDomain

    data class DatasetMetadata(
        val datasetUuid: String,
        val foundationVersion: Int,
        val mode: String,
        val cutoverRevision: Long,
        val currentRevision: Long,
        val cutoverAt: Long,
        val sourceSnapshotSha256: String,
    ) : RecoveryDomain

    data class RecoverablePreferences(
        val appSettings: Map<String, Long>,
        val uiPreferences: Map<String, String>,
    ) : RecoveryDomain

    data class ExcludedSecrets(
        val reason: String = "Auth tokens, credentials, and private keys must never be stored in backups",
        val omittedKeys: List<String> = emptyList(),
    ) : RecoveryDomain
}

data class ComprehensiveRecoveryPackage(
    val databaseData: RecoveryDomain.DatabaseData,
    val datasetMetadata: RecoveryDomain.DatasetMetadata,
    val recoverablePreferences: RecoveryDomain.RecoverablePreferences,
    val excludedSecrets: RecoveryDomain.ExcludedSecrets = RecoveryDomain.ExcludedSecrets(),
)
