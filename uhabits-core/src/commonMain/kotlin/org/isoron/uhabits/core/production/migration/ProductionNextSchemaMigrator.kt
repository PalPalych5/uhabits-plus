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

import org.isoron.platform.io.Database
import org.isoron.platform.io.StepResult
import org.isoron.platform.io.getVersion
import org.isoron.platform.io.run
import org.isoron.platform.io.setVersion
import org.isoron.uhabits.core.containers.migration.LegacyContainerMigrationPlanner
import org.isoron.uhabits.core.containers.migration.RawLegacyInventoryReader
import org.isoron.uhabits.core.containers.migration.Sha256
import org.isoron.uhabits.core.containers.sqlite.OrganizationSchema
import org.isoron.uhabits.core.contribution.sqlite.ContributionSchema
import org.isoron.uhabits.core.focus.sqlite.FocusSessionSchema
import org.isoron.uhabits.core.focus.sqlite.SQLiteFocusCaptureStore
import org.isoron.uhabits.core.rules.sqlite.SourceRuleSchema
import org.isoron.uhabits.core.userTasks.sqlite.UserTaskSchema

/**
 * Executes the atomic additive versioned schema conversion from v29 to v30 on protected staging
 * or during authorized versioned migration.
 */
object ProductionNextSchemaMigrator {

    fun migrate(
        db: Database,
        cutoverTimestamp: Long,
        datasetUuid: String? = null,
        conversionOpUuid: String? = null,
        baselineOpUuid: String? = null,
        sourceFileSha256: String? = null,
        sourceSchemaSha256: String? = null,
        ownsTransaction: Boolean = true,
        failureInjectionPoint: MigrationFailurePoint? = null,
    ): ProductionNextMigrationResult {
        val currentVersion = db.getVersion()
        if (currentVersion != 29) {
            return ProductionNextMigrationResult.Failure(
                reason = "Database version must be 29 to migrate to 30, but was $currentVersion"
            )
        }

        // 1. Read legacy raw snapshot
        val beforeInventory = try {
            RawLegacyInventoryReader.read(db)
        } catch (t: Throwable) {
            return ProductionNextMigrationResult.Failure(
                reason = "Failed reading legacy inventory: ${t.message}",
                cause = t,
            )
        }

        val effectiveDatasetUuid = datasetUuid
            ?: ("dataset-" + Sha256.hexDigest("next-dataset:" + beforeInventory.snapshotSha256).take(16))
        val effectiveConversionOpUuid = conversionOpUuid
            ?: ("op-" + Sha256.hexDigest("migration-op:" + (sourceFileSha256 ?: beforeInventory.snapshotSha256)).take(16))
        val effectiveBaselineOpUuid = baselineOpUuid
            ?: ("baseline-" + Sha256.hexDigest("baseline-op:" + beforeInventory.snapshotSha256).take(16))

        val plan = LegacyContainerMigrationPlanner.plan(
            inventory = beforeInventory,
            cutoverTimestamp = cutoverTimestamp,
            datasetUuid = effectiveDatasetUuid,
            mode = "PRODUCTION_NEXT",
            sourceFileSha256 = sourceFileSha256,
            sourceSchemaSha256 = sourceSchemaSha256,
            runtimeAuthority = "LEGACY",
            authorityEpoch = 0L,
            conversionOpUuid = effectiveConversionOpUuid,
            baselineOpUuid = effectiveBaselineOpUuid,
        )

        if (!plan.canMigrate) {
            return ProductionNextMigrationResult.Failure(
                reason = "Migration plan contains blocking issues",
                errors = plan.issues.filter { it.isBlocking }.map { it.message },
            )
        }

        val metadata = ProductionNextMetadata(
            datasetUuid = effectiveDatasetUuid,
            foundationVersion = 1,
            mode = "PRODUCTION_NEXT",
            cutoverRevision = 1L,
            currentRevision = 1L,
            cutoverAt = cutoverTimestamp,
            sourceSchemaVersion = 29,
            sourceSnapshotSha256 = beforeInventory.snapshotSha256,
            sourceFileSha256 = sourceFileSha256,
            sourceSchemaSha256 = sourceSchemaSha256,
            runtimeAuthority = "LEGACY",
            authorityEpoch = 0L,
            conversionOpUuid = effectiveConversionOpUuid,
            baselineOpUuid = effectiveBaselineOpUuid,
        )

        try {
            if (ownsTransaction) {
                OrganizationSchema.enableForeignKeys(db)
                db.run("BEGIN IMMEDIATE")
            }

            // Step 1: Install all Next durable DDL
            OrganizationSchema.createSchema(db)
            UserTaskSchema.createSchema(db)
            FocusSessionSchema.createSchema(db)
            // FocusCaptureJournal table
            db.run(
                """
                CREATE TABLE IF NOT EXISTS FocusCaptureJournal (
                    slot INTEGER PRIMARY KEY CHECK(slot = 1),
                    timer_id INTEGER, timer_name TEXT NOT NULL, mode TEXT NOT NULL, phase TEXT NOT NULL,
                    running INTEGER NOT NULL, elapsed INTEGER NOT NULL, display INTEGER NOT NULL,
                    focus_duration INTEGER NOT NULL, break_duration INTEGER NOT NULL,
                    started_at INTEGER NOT NULL, accumulated INTEGER NOT NULL,
                    overtime INTEGER NOT NULL, completion INTEGER NOT NULL, auto_switch INTEGER NOT NULL,
                    observed_at INTEGER NOT NULL, target_kind TEXT, target_id TEXT,
                    target_container_id TEXT, organization_context TEXT,
                    active_id TEXT, active_started_at INTEGER, active_elapsed_offset INTEGER, active_device_id TEXT
                )
                """.trimIndent()
            )
            ContributionSchema.createSchema(db)
            SourceRuleSchema.createSchema(db)

            if (failureInjectionPoint == MigrationFailurePoint.AFTER_DDL) {
                throw InjectedMigrationFailureException(MigrationFailurePoint.AFTER_DDL)
            }

            // Step 2: Insert OrganizationChanges baseline record (rev 1)
            val changeStmt = db.prepareStatement(
                """
                INSERT INTO OrganizationChanges (revision, op_uuid, recorded_at, operation_type, origin, command_payload)
                VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent()
            )
            changeStmt.bindLong(1, plan.baselineRecord.revision.value)
            changeStmt.bindText(2, plan.baselineRecord.opUuid)
            changeStmt.bindLong(3, plan.baselineRecord.recordedAt)
            changeStmt.bindText(4, plan.baselineRecord.operationType)
            changeStmt.bindText(5, plan.baselineRecord.origin)
            changeStmt.bindText(6, plan.baselineRecord.commandPayload)
            changeStmt.step()
            changeStmt.finalize()

            // Step 3: Insert OrganizationState record with production metadata
            val stateStmt = db.prepareStatement(
                """
                INSERT OR REPLACE INTO OrganizationState (
                    id, dataset_uuid, foundation_version, mode, cutover_revision,
                    current_revision, cutover_at, source_schema_version, source_snapshot_sha256,
                    source_file_sha256, source_schema_sha256, runtime_authority, authority_epoch, conversion_op_uuid
                ) VALUES (1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            )
            stateStmt.bindText(1, metadata.datasetUuid)
            stateStmt.bindInt(2, metadata.foundationVersion)
            stateStmt.bindText(3, metadata.mode)
            stateStmt.bindLong(4, metadata.cutoverRevision)
            stateStmt.bindLong(5, metadata.currentRevision)
            stateStmt.bindLong(6, metadata.cutoverAt)
            stateStmt.bindInt(7, metadata.sourceSchemaVersion)
            stateStmt.bindText(8, metadata.sourceSnapshotSha256)
            if (metadata.sourceFileSha256 != null) stateStmt.bindText(9, metadata.sourceFileSha256) else stateStmt.bindNull(9)
            if (metadata.sourceSchemaSha256 != null) stateStmt.bindText(10, metadata.sourceSchemaSha256) else stateStmt.bindNull(10)
            stateStmt.bindText(11, metadata.runtimeAuthority)
            stateStmt.bindLong(12, metadata.authorityEpoch)
            stateStmt.bindText(13, metadata.conversionOpUuid)
            stateStmt.step()
            stateStmt.finalize()

            if (failureInjectionPoint == MigrationFailurePoint.AFTER_METADATA) {
                throw InjectedMigrationFailureException(MigrationFailurePoint.AFTER_METADATA)
            }

            // Step 4: Insert Containers
            val containerStmt = db.prepareStatement(
                """
                INSERT INTO Containers (
                    uuid, parent_id, name, color, icon, sibling_order, is_archived,
                    created_at, updated_at, deleted_at, revision
                ) VALUES (?, NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            )
            val containerCount = plan.containers.size
            for ((index, c) in plan.containers.withIndex()) {
                if (failureInjectionPoint == MigrationFailurePoint.HALFWAY_CONTAINERS && index == containerCount / 2) {
                    containerStmt.finalize()
                    throw InjectedMigrationFailureException(MigrationFailurePoint.HALFWAY_CONTAINERS)
                }
                containerStmt.bindText(1, c.id.value)
                containerStmt.bindText(2, c.name)
                if (c.color != null) containerStmt.bindInt(3, c.color.paletteIndex) else containerStmt.bindNull(3)
                if (c.icon != null) containerStmt.bindText(4, c.icon) else containerStmt.bindNull(4)
                containerStmt.bindInt(5, c.siblingOrder)
                containerStmt.bindInt(6, if (c.isArchived) 1 else 0)
                if (c.createdAt != null) containerStmt.bindLong(7, c.createdAt) else containerStmt.bindNull(7)
                containerStmt.bindLong(8, c.updatedAt)
                if (c.deletedAt != null) containerStmt.bindLong(9, c.deletedAt) else containerStmt.bindNull(9)
                containerStmt.bindLong(10, c.revision.value)
                containerStmt.step()
                containerStmt.reset()
            }
            containerStmt.finalize()

            // Resolve container local IDs
            val containerLocalIds = mutableMapOf<String, Long>()
            val cLookupStmt = db.prepareStatement("SELECT id, uuid FROM Containers")
            while (cLookupStmt.step() == StepResult.ROW) {
                containerLocalIds[cLookupStmt.getText(1)] = cLookupStmt.getLong(0)
            }
            cLookupStmt.finalize()

            // Step 5: Insert LegacyBlockMap
            val mapStmt = db.prepareStatement(
                """
                INSERT INTO LegacyBlockMap (legacy_block_id, legacy_block_uuid, container_id)
                VALUES (?, ?, ?)
                """.trimIndent()
            )
            for (m in plan.legacyBlockMaps) {
                val containerLocalId = containerLocalIds[m.containerId.value]
                    ?: throw IllegalStateException("Local container ID missing for UUID: ${m.containerId.value}")
                mapStmt.bindLong(1, m.legacyBlockId)
                mapStmt.bindText(2, m.legacyBlockUuid)
                mapStmt.bindLong(3, containerLocalId)
                mapStmt.step()
                mapStmt.reset()
            }
            mapStmt.finalize()

            // Resolve habit local IDs
            val habitLocalIds = mutableMapOf<String, Long>()
            val hLookupStmt = db.prepareStatement("SELECT id, uuid FROM Habits")
            while (hLookupStmt.step() == StepResult.ROW) {
                habitLocalIds[hLookupStmt.getText(1)] = hLookupStmt.getLong(0)
            }
            hLookupStmt.finalize()

            // Step 6: Insert HabitPlacements
            val placeStmt = db.prepareStatement(
                """
                INSERT INTO HabitPlacements (habit_id, container_id, local_order, placement_origin, revision)
                VALUES (?, ?, ?, ?, ?)
                """.trimIndent()
            )
            val placementCount = plan.placements.size
            for ((index, p) in plan.placements.withIndex()) {
                if (failureInjectionPoint == MigrationFailurePoint.HALFWAY_PLACEMENTS && index == placementCount / 2) {
                    placeStmt.finalize()
                    throw InjectedMigrationFailureException(MigrationFailurePoint.HALFWAY_PLACEMENTS)
                }
                val habitLocalId = habitLocalIds[p.habit.uuid]
                    ?: throw IllegalStateException("Local habit ID missing for UUID: ${p.habit.uuid}")
                val containerLocalId = p.containerId?.let {
                    containerLocalIds[it.value]
                        ?: throw IllegalStateException("Local container ID missing for placement target: ${it.value}")
                }

                placeStmt.bindLong(1, habitLocalId)
                if (containerLocalId != null) placeStmt.bindLong(2, containerLocalId) else placeStmt.bindNull(2)
                placeStmt.bindInt(3, p.localOrder)
                placeStmt.bindText(4, p.origin.name)
                placeStmt.bindLong(5, p.revision.value)
                placeStmt.step()
                placeStmt.reset()
            }
            placeStmt.finalize()

            // Step 7: Insert ContainerHistory
            val chStmt = db.prepareStatement(
                """
                INSERT INTO ContainerHistory (
                    container_id, revision, parent_id, name, color, icon,
                    sibling_order, is_archived, created_at, updated_at, deleted_at
                ) VALUES (?, ?, NULL, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            )
            for (c in plan.containers) {
                val containerLocalId = containerLocalIds[c.id.value]!!
                chStmt.bindLong(1, containerLocalId)
                chStmt.bindLong(2, c.revision.value)
                chStmt.bindText(3, c.name)
                if (c.color != null) chStmt.bindInt(4, c.color.paletteIndex) else chStmt.bindNull(4)
                if (c.icon != null) chStmt.bindText(5, c.icon) else chStmt.bindNull(5)
                chStmt.bindInt(6, c.siblingOrder)
                chStmt.bindInt(7, if (c.isArchived) 1 else 0)
                if (c.createdAt != null) chStmt.bindLong(8, c.createdAt) else chStmt.bindNull(8)
                chStmt.bindLong(9, c.updatedAt)
                if (c.deletedAt != null) chStmt.bindLong(10, c.deletedAt) else chStmt.bindNull(10)
                chStmt.step()
                chStmt.reset()
            }
            chStmt.finalize()

            // Step 8: Insert HabitPlacementHistory
            val hphStmt = db.prepareStatement(
                """
                INSERT INTO HabitPlacementHistory (habit_id, revision, container_id, local_order, placement_origin)
                VALUES (?, ?, ?, ?, ?)
                """.trimIndent()
            )
            for (p in plan.placements) {
                val habitLocalId = habitLocalIds[p.habit.uuid]!!
                val containerLocalId = p.containerId?.let { containerLocalIds[it.value]!! }

                hphStmt.bindLong(1, habitLocalId)
                hphStmt.bindLong(2, p.revision.value)
                if (containerLocalId != null) hphStmt.bindLong(3, containerLocalId) else hphStmt.bindNull(3)
                hphStmt.bindInt(4, p.localOrder)
                hphStmt.bindText(5, p.origin.name)
                hphStmt.step()
                hphStmt.reset()
            }
            hphStmt.finalize()

            if (failureInjectionPoint == MigrationFailurePoint.AFTER_HISTORIES) {
                throw InjectedMigrationFailureException(MigrationFailurePoint.AFTER_HISTORIES)
            }

            if (failureInjectionPoint == MigrationFailurePoint.AFTER_NEXT_SCHEMAS) {
                throw InjectedMigrationFailureException(MigrationFailurePoint.AFTER_NEXT_SCHEMAS)
            }

            if (failureInjectionPoint == MigrationFailurePoint.BEFORE_COMMIT) {
                throw InjectedMigrationFailureException(MigrationFailurePoint.BEFORE_COMMIT)
            }

            // Set user_version to 30
            db.setVersion(30)

            if (ownsTransaction) {
                db.run("COMMIT")
            }

            return ProductionNextMigrationResult.Success(plan, metadata)
        } catch (t: Throwable) {
            if (ownsTransaction) {
                try {
                    db.run("ROLLBACK")
                } catch (_: Throwable) {}
            }
            return ProductionNextMigrationResult.Failure(
                reason = "Migration transaction failed: ${t.message}",
                cause = t,
            )
        }
    }
}
