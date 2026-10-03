/*
 * Copyright (C) 2016-2026 Álinson Santos Xavier <git@axavier.org>
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

package org.isoron.uhabits.core.containers.session

import org.isoron.uhabits.core.containers.facade.OrganizationAuthorityMode

/**
 * Represents the active data session determining which database, preferences
 * namespace, organization authority, and capabilities are in use.
 *
 * A [DatasetSession] is resolved **before** building dependent runtime
 * components (DI graph, repositories, sync coordinator, etc.).
 *
 * Two minimal modes exist:
 * - [DatasetMode.LEGACY_PRODUCTION]: the original production database and full capabilities.
 * - [DatasetMode.CONTAINER_LOCAL_EXPERIMENTAL]: an isolated copy with restricted capabilities.
 */
data class DatasetSession(
    val datasetId: String,
    val mode: DatasetMode,
    val databaseFilename: String,
    val preferencesNamespace: String,
    val organizationAuthority: OrganizationAuthorityMode,
    val capabilities: DatasetCapabilities,
    val foundationVersion: Int = 0,
) {
    val isExperimental: Boolean
        get() = mode == DatasetMode.CONTAINER_LOCAL_EXPERIMENTAL
}

/**
 * Minimal dataset modes for PR6. Not scattered boolean flags.
 */
enum class DatasetMode {
    /** Original production database with full sync, reminders, widgets, timer. */
    LEGACY_PRODUCTION,
    /** Isolated experimental copy: no sync, no timer, restricted jobs. */
    CONTAINER_LOCAL_EXPERIMENTAL,
}

/**
 * Explicit capability flags for the current dataset session.
 * Each flag controls whether a specific subsystem is permitted to operate.
 * Capabilities are determined by [DatasetMode] at session creation time.
 */
data class DatasetCapabilities(
    val syncEnabled: Boolean = true,
    val remoteCallsAllowed: Boolean = true,
    val timerEnabled: Boolean = true,
    val widgetsEnabled: Boolean = true,
    val remindersEnabled: Boolean = true,
    val backgroundJobsEnabled: Boolean = true,
    val backupEnabled: Boolean = true,
) {
    companion object {
        /** Full production capabilities. */
        val PRODUCTION = DatasetCapabilities()

        /** Experimental: everything that could mutate wrong dataset is blocked. */
        val EXPERIMENTAL = DatasetCapabilities(
            syncEnabled = false,
            remoteCallsAllowed = false,
            timerEnabled = false,
            widgetsEnabled = false,
            remindersEnabled = false,
            backgroundJobsEnabled = false,
            backupEnabled = true, // experimental backup/restore is allowed
        )
    }
}

/**
 * Persisted metadata about an experimental dataset, used for restart validation.
 */
data class ExperimentalDatasetMetadata(
    val datasetId: String,
    val databaseFilename: String,
    val foundationVersion: Int,
    val sourceSnapshotSha256: String,
    val createdAtMillis: Long,
    val organizationSchemaVersion: Int = 1,
)

/**
 * Result of attempting to activate an experimental session.
 */
sealed interface DatasetActivationResult {
    data class Success(val session: DatasetSession) : DatasetActivationResult
    data class Blocked(val reason: String) : DatasetActivationResult
    data class ValidationFailed(val reason: String) : DatasetActivationResult
    data class MigrationFailed(val reason: String) : DatasetActivationResult
}

/**
 * Result of validating an experimental dataset before opening.
 */
sealed interface DatasetValidationResult {
    data object Valid : DatasetValidationResult
    data class Invalid(val reason: String) : DatasetValidationResult
    data class Missing(val reason: String) : DatasetValidationResult
}
