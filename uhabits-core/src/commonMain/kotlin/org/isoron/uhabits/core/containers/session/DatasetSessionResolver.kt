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

import org.isoron.uhabits.core.DATABASE_FILENAME
import org.isoron.uhabits.core.containers.facade.OrganizationAuthorityMode

/**
 * Resolves the [DatasetSession] from persisted state, performing lightweight
 * validation before opening.
 *
 * This is a pure core contract. Android implementation provides actual
 * persistence and file validation.
 */
interface DatasetSessionResolver {
    /**
     * Returns the currently persisted session selection.
     * Does NOT validate the dataset — call [validateExperimental] before opening.
     */
    fun resolvedSession(): DatasetSession

    /**
     * Validates that the experimental dataset file exists, passes integrity
     * check, and has consistent metadata.
     */
    fun validateExperimental(metadata: ExperimentalDatasetMetadata): DatasetValidationResult

    /**
     * Persists the selection of an experimental dataset.
     */
    fun persistExperimentalSelection(metadata: ExperimentalDatasetMetadata)

    /**
     * Clears experimental selection, returning to production on next restart.
     */
    fun clearExperimentalSelection()

    /**
     * Returns persisted experimental metadata, if any.
     */
    fun loadExperimentalMetadata(): ExperimentalDatasetMetadata?
}

/**
 * Factory for creating the default production session.
 */
object DatasetSessionFactory {

    /** Default production session — uses production DB with full capabilities. */
    fun production(): DatasetSession = DatasetSession(
        datasetId = "production",
        mode = DatasetMode.LEGACY_PRODUCTION,
        databaseFilename = DATABASE_FILENAME,
        preferencesNamespace = "",
        organizationAuthority = OrganizationAuthorityMode.LEGACY,
        capabilities = DatasetCapabilities.PRODUCTION,
    )

    /** Experimental session from validated metadata. */
    fun experimental(metadata: ExperimentalDatasetMetadata): DatasetSession = DatasetSession(
        datasetId = metadata.datasetId,
        mode = DatasetMode.CONTAINER_LOCAL_EXPERIMENTAL,
        databaseFilename = metadata.databaseFilename,
        preferencesNamespace = "experimental_${metadata.datasetId}",
        organizationAuthority = OrganizationAuthorityMode.CONTAINER_LOCAL,
        capabilities = DatasetCapabilities.EXPERIMENTAL,
        foundationVersion = metadata.foundationVersion,
    )

    /** Experimental database filename derived from production name. */
    const val EXPERIMENTAL_DB_FILENAME = "uhabits-container-experimental.db"
}
