/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.core.production.migration

import org.isoron.uhabits.core.containers.migration.MigrationPlan

/**
 * Injection points for failure testing across the migration boundary.
 */
enum class MigrationFailurePoint {
    AFTER_DDL,
    AFTER_METADATA,
    HALFWAY_CONTAINERS,
    HALFWAY_PLACEMENTS,
    AFTER_HISTORIES,
    AFTER_NEXT_SCHEMAS,
    BEFORE_COMMIT,
}

class InjectedMigrationFailureException(
    val point: MigrationFailurePoint
) : RuntimeException("Simulated crash/failure at point $point")

/**
 * Metadata recorded for production Next schema migration.
 */
data class ProductionNextMetadata(
    val datasetUuid: String,
    val foundationVersion: Int = 1,
    val mode: String = "PRODUCTION_NEXT",
    val cutoverRevision: Long = 1L,
    val currentRevision: Long = 1L,
    val cutoverAt: Long,
    val sourceSchemaVersion: Int = 29,
    val sourceSnapshotSha256: String,
    val sourceFileSha256: String? = null,
    val sourceSchemaSha256: String? = null,
    val runtimeAuthority: String = "LEGACY",
    val authorityEpoch: Long = 0L,
    val conversionOpUuid: String,
    val baselineOpUuid: String,
)

sealed interface ProductionNextMigrationResult {
    data class Success(
        val plan: MigrationPlan,
        val metadata: ProductionNextMetadata,
    ) : ProductionNextMigrationResult

    data class Failure(
        val reason: String,
        val cause: Throwable? = null,
        val errors: List<String> = emptyList(),
    ) : ProductionNextMigrationResult
}
