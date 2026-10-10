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

package org.isoron.uhabits.core.production.cutover

import org.isoron.platform.io.Database
import org.isoron.platform.io.getVersion
import org.isoron.platform.io.queryLong
import org.isoron.uhabits.core.containers.migration.RawLegacyInventoryReader
import org.isoron.uhabits.core.production.migration.ProductionNextMetadata
import org.isoron.uhabits.core.production.migration.ProductionNextMigrationResult
import org.isoron.uhabits.core.production.migration.ProductionNextMigrationValidator
import org.isoron.uhabits.core.production.migration.ProductionNextSchemaMigrator
import org.isoron.uhabits.core.production.migration.ProductionValidationResult

/**
 * Result of executing the production cutover from legacy v29 to UHabit Next v30.
 */
sealed interface ProductionCutoverResult {
    data class Success(
        val metadata: ProductionNextMetadata,
        val cutoverTimestamp: Long,
        val sourceSnapshotSha256: String,
        val habitsCount: Long,
        val containersCount: Long,
        val placementsCount: Long,
    ) : ProductionCutoverResult

    data class Failure(
        val stage: String,
        val reason: String,
        val errors: List<String> = emptyList(),
        val cause: Throwable? = null,
    ) : ProductionCutoverResult
}

/**
 * Core service executing protected local production cutover from v29 to v30.
 *
 * Invariants:
 * 1. Safe additive migration from v29 to v30.
 * 2. 100% preservation of legacy Habits, Repetitions, Extensions, Goals, SyncQueue, EntryOps.
 * 3. Atomic activation of Next runtime authority ('NEXT') and epoch.
 * 4. Full semantic validation before committing cutover.
 */
class ProductionCutoverService {

    fun executeCutover(
        db: Database,
        cutoverTimestamp: Long = System.currentTimeMillis(),
        datasetUuid: String? = null,
        ownsTransaction: Boolean = true,
    ): ProductionCutoverResult {
        // 1. Verify preconditions: database version must be exactly 29
        val version = db.getVersion()
        if (version != 29) {
            return ProductionCutoverResult.Failure(
                stage = "PREFLIGHT",
                reason = "Database version must be 29 to execute cutover, but was $version"
            )
        }

        // 2. Read legacy snapshot before conversion
        val beforeInventory = try {
            RawLegacyInventoryReader.read(db)
        } catch (t: Throwable) {
            return ProductionCutoverResult.Failure(
                stage = "PREFLIGHT",
                reason = "Failed reading legacy inventory: ${t.message}",
                cause = t
            )
        }

        // 3. Execute atomic migration to v30 with runtimeAuthority = 'NEXT', authorityEpoch = 1L
        val migrationResult = ProductionCutoverGate.withCutoverAuthorization {
            ProductionNextSchemaMigrator.migrate(
                db = db,
                cutoverTimestamp = cutoverTimestamp,
                datasetUuid = datasetUuid,
                ownsTransaction = ownsTransaction,
                runtimeAuthority = ProductionCutoverGate.AUTHORITY_NEXT,
                authorityEpoch = 1L,
            )
        }

        if (migrationResult is ProductionNextMigrationResult.Failure) {
            return ProductionCutoverResult.Failure(
                stage = "MIGRATION",
                reason = migrationResult.reason,
                errors = migrationResult.errors,
                cause = migrationResult.cause
            )
        }

        val successMigration = migrationResult as ProductionNextMigrationResult.Success

        // 4. Validate resulting database
        val validation = ProductionNextMigrationValidator.validate(
            db = db,
            beforeInventory = beforeInventory,
            plan = successMigration.plan,
            expectedMetadata = successMigration.metadata,
        )

        if (validation is ProductionValidationResult.Invalid) {
            return ProductionCutoverResult.Failure(
                stage = "VALIDATION",
                reason = "Migrated database failed semantic validation",
                errors = validation.errors
            )
        }

        // 5. Query verified entity counts
        val habitsCount = db.queryLong("SELECT count(*) FROM Habits")
        val containersCount = db.queryLong("SELECT count(*) FROM Containers")
        val placementsCount = db.queryLong("SELECT count(*) FROM HabitPlacements")

        return ProductionCutoverResult.Success(
            metadata = successMigration.metadata,
            cutoverTimestamp = cutoverTimestamp,
            sourceSnapshotSha256 = beforeInventory.snapshotSha256,
            habitsCount = habitsCount,
            containersCount = containersCount,
            placementsCount = placementsCount,
        )
    }
}
