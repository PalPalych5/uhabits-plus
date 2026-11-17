/* SPDX-License-Identifier: GPL-3.0-or-later */

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
