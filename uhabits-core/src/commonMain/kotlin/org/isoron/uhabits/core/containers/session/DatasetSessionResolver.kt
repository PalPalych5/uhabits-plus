/* SPDX-License-Identifier: GPL-3.0-or-later */

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

    /** Production Next session — uses production DB with full UHabit Next capabilities. */
    fun productionNext(
        datasetUuid: String = "production",
        authorityEpoch: Long = 1L
    ): DatasetSession = DatasetSession(
        datasetId = datasetUuid,
        mode = DatasetMode.PRODUCTION_NEXT,
        databaseFilename = DATABASE_FILENAME,
        preferencesNamespace = "",
        organizationAuthority = OrganizationAuthorityMode.CONTAINER_LOCAL,
        capabilities = DatasetCapabilities.PRODUCTION_NEXT,
        foundationVersion = 1,
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
