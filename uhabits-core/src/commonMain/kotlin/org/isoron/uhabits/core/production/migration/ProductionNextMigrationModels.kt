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
