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

package org.isoron.uhabits.core.production.staging

import org.isoron.platform.io.JavaDatabaseOpener
import org.isoron.uhabits.core.containers.migration.RawLegacyInventoryReader
import org.isoron.uhabits.core.production.migration.MigrationFailurePoint
import org.isoron.uhabits.core.production.migration.ProductionNextMetadata
import org.isoron.uhabits.core.production.migration.ProductionNextMigrationResult
import org.isoron.uhabits.core.production.migration.ProductionNextMigrationValidator
import org.isoron.uhabits.core.production.migration.ProductionNextSchemaMigrator
import org.isoron.uhabits.core.production.migration.ProductionValidationResult
import org.isoron.uhabits.core.production.preflight.ProductionPreflightReport
import org.isoron.uhabits.core.production.preflight.ProductionPreflightService
import org.isoron.uhabits.core.production.preflight.ProductionSourceFingerprint
import org.isoron.uhabits.core.production.preflight.TimerMigrationReadiness
import org.isoron.uhabits.core.production.recovery.ProductionRecoveryGate
import java.io.File
import java.io.IOException

sealed interface StagingMigrationResult {
    data class Success(
        val report: ProductionPreflightReport,
        val metadata: ProductionNextMetadata,
        val stagingFile: File,
        val sourceUnchanged: Boolean,
    ) : StagingMigrationResult

    data class Failure(
        val stage: String,
        val reason: String,
        val errors: List<String> = emptyList(),
        val cause: Throwable? = null,
        val sourceUnchanged: Boolean = true,
    ) : StagingMigrationResult
}

/**
 * Service that manages the protected staging migration flow for PR22:
 * 1. Verify PR21 source freshness, recovery bundle, and timer readiness.
 * 2. Create disposable staging copy (source database is never mutated).
 * 3. Execute atomic versioned migration (v29 -> v30) on the staging copy.
 * 4. Run full semantic validation before and after database close/reopen.
 * 5. Report comprehensive result without swapping staging into production.
 */
class ProductionStagingMigrationService(
    private val opener: JavaDatabaseOpener = JavaDatabaseOpener()
) {

    fun executeStagedMigration(
        sourceFile: File,
        bundleDir: File,
        stagingFile: File,
        cutoverTimestamp: Long,
        expectedFingerprint: ProductionSourceFingerprint? = null,
        timerReadiness: TimerMigrationReadiness = TimerMigrationReadiness.READY,
        identityMarkers: Map<String, String> = emptyMap(),
        failureInjectionPoint: MigrationFailurePoint? = null,
    ): StagingMigrationResult {
        if (!sourceFile.exists()) {
            return StagingMigrationResult.Failure("PREFLIGHT", "Source database file does not exist: ${sourceFile.path}")
        }

        val sourceBytesBefore = sourceFile.readBytes()
        val sourceShaBefore = ProductionRecoveryGate.fileSha256(sourceFile)

        // 1. Check sidecars
        val sidecar = listOf("-wal", "-journal").any { File(sourceFile.path + it).let { f -> f.exists() && f.length() > 0 } }
        if (sidecar) {
            return StagingMigrationResult.Failure(
                "PREFLIGHT",
                "Source database has active WAL or journal sidecars; must be cleanly closed before migration"
            )
        }

        // 2. Fresh preflight inspection on source file
        val preflightReport = try {
            val db = opener.open(sourceFile.absolutePath)
            try {
                ProductionPreflightService.inspect(
                    db = db,
                    databasePath = sourceFile.path,
                    fileSha256 = sourceShaBefore,
                    timer = timerReadiness,
                    identityMarkers = identityMarkers,
                    recoveryBundleVerified = true,
                )
            } finally {
                db.close()
            }
        } catch (t: Throwable) {
            return StagingMigrationResult.Failure("PREFLIGHT", "Preflight inspection failed: ${t.message}", cause = t)
        }

        if (!preflightReport.sourceEligible) {
            return StagingMigrationResult.Failure(
                "PREFLIGHT",
                "Source database is not eligible for migration: ${preflightReport.blockingIssues}",
                errors = preflightReport.blockingIssues.map { "${it.category}: ${it.detail}" },
            )
        }

        if (expectedFingerprint != null && preflightReport.fingerprint != expectedFingerprint) {
            return StagingMigrationResult.Failure(
                "FRESHNESS",
                "Source fingerprint changed since approval: actual=${preflightReport.fingerprint} vs expected=$expectedFingerprint"
            )
        }

        // 3. Verify recovery bundle
        val manifestFile = File(bundleDir, "manifest")
        if (!manifestFile.exists()) {
            return StagingMigrationResult.Failure("RECOVERY_BUNDLE", "Recovery bundle manifest is missing at ${manifestFile.path}")
        }
        val bundleSourceFile = File(bundleDir, "legacy-v29.db")
        if (!bundleSourceFile.exists()) {
            return StagingMigrationResult.Failure("RECOVERY_BUNDLE", "Recovery bundle database is missing at ${bundleSourceFile.path}")
        }
        val bundleSha = ProductionRecoveryGate.fileSha256(bundleSourceFile)
        if (bundleSha != sourceShaBefore) {
            return StagingMigrationResult.Failure("RECOVERY_BUNDLE", "Recovery bundle SHA mismatch with source: bundle=$bundleSha vs source=$sourceShaBefore")
        }

        // 4. Create protected staging copy
        stagingFile.parentFile?.mkdirs()
        if (stagingFile.exists()) stagingFile.delete()
        sourceFile.copyTo(stagingFile, overwrite = true)

        val stagingSha = ProductionRecoveryGate.fileSha256(stagingFile)
        if (stagingSha != sourceShaBefore) {
            return StagingMigrationResult.Failure("STAGING_COPY", "Staging copy SHA mismatch with source")
        }

        val beforeInventory = try {
            val db = opener.open(stagingFile.absolutePath)
            try {
                RawLegacyInventoryReader.read(db)
            } finally {
                db.close()
            }
        } catch (t: Throwable) {
            return StagingMigrationResult.Failure("INVENTORY", "Failed reading staging inventory before migration: ${t.message}", cause = t)
        }

        // 5. Execute versioned migration on staging copy
        val migrationResult = try {
            val db = opener.open(stagingFile.absolutePath)
            try {
                ProductionNextSchemaMigrator.migrate(
                    db = db,
                    cutoverTimestamp = cutoverTimestamp,
                    datasetUuid = null,
                    sourceFileSha256 = sourceShaBefore,
                    sourceSchemaSha256 = preflightReport.fingerprint.schemaSha256,
                    ownsTransaction = true,
                    failureInjectionPoint = failureInjectionPoint,
                )
            } finally {
                db.close()
            }
        } catch (t: Throwable) {
            val sourceUnchanged = sourceFile.readBytes().contentEquals(sourceBytesBefore)
            return StagingMigrationResult.Failure(
                "MIGRATION_EXECUTION",
                "Migration execution threw exception: ${t.message}",
                cause = t,
                sourceUnchanged = sourceUnchanged,
            )
        }

        val (plan, metadata) = when (migrationResult) {
            is ProductionNextMigrationResult.Success -> migrationResult.plan to migrationResult.metadata
            is ProductionNextMigrationResult.Failure -> {
                val sourceUnchanged = sourceFile.readBytes().contentEquals(sourceBytesBefore)
                return StagingMigrationResult.Failure(
                    "MIGRATION_EXECUTION",
                    migrationResult.reason,
                    errors = migrationResult.errors,
                    cause = migrationResult.cause,
                    sourceUnchanged = sourceUnchanged,
                )
            }
        }

        // 6. Semantic validation (first pass)
        val validationResult = try {
            val db = opener.open(stagingFile.absolutePath)
            try {
                ProductionNextMigrationValidator.validate(
                    db = db,
                    beforeInventory = beforeInventory,
                    plan = plan,
                    expectedMetadata = metadata,
                )
            } finally {
                db.close()
            }
        } catch (t: Throwable) {
            val sourceUnchanged = sourceFile.readBytes().contentEquals(sourceBytesBefore)
            return StagingMigrationResult.Failure("VALIDATION", "Semantic validator failed: ${t.message}", cause = t, sourceUnchanged = sourceUnchanged)
        }

        if (validationResult is ProductionValidationResult.Invalid) {
            val sourceUnchanged = sourceFile.readBytes().contentEquals(sourceBytesBefore)
            return StagingMigrationResult.Failure("VALIDATION", "Semantic validation failed", errors = validationResult.errors, sourceUnchanged = sourceUnchanged)
        }

        // 7. Close and reopen to verify persistence across sessions
        val postReopenValidation = try {
            val db = opener.open(stagingFile.absolutePath)
            try {
                ProductionNextMigrationValidator.validate(
                    db = db,
                    beforeInventory = beforeInventory,
                    plan = plan,
                    expectedMetadata = metadata,
                )
            } finally {
                db.close()
            }
        } catch (t: Throwable) {
            val sourceUnchanged = sourceFile.readBytes().contentEquals(sourceBytesBefore)
            return StagingMigrationResult.Failure("REOPEN_VALIDATION", "Reopen validation failed: ${t.message}", cause = t, sourceUnchanged = sourceUnchanged)
        }

        if (postReopenValidation is ProductionValidationResult.Invalid) {
            val sourceUnchanged = sourceFile.readBytes().contentEquals(sourceBytesBefore)
            return StagingMigrationResult.Failure("REOPEN_VALIDATION", "Post-reopen validation failed", errors = postReopenValidation.errors, sourceUnchanged = sourceUnchanged)
        }

        val sourceUnchanged = sourceFile.readBytes().contentEquals(sourceBytesBefore)
        if (!sourceUnchanged) {
            return StagingMigrationResult.Failure("SOURCE_PARITY", "CRITICAL: Source file was mutated during staging migration!", sourceUnchanged = false)
        }

        return StagingMigrationResult.Success(
            report = preflightReport,
            metadata = metadata,
            stagingFile = stagingFile,
            sourceUnchanged = true,
        )
    }
}
