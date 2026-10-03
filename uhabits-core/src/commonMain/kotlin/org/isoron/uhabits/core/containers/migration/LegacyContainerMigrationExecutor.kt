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

package org.isoron.uhabits.core.containers.migration

import org.isoron.platform.io.Database
import org.isoron.platform.io.StepResult
import org.isoron.platform.io.run
import org.isoron.uhabits.core.containers.sqlite.OrganizationSchema

sealed interface MigrationExecutionResult {
    data class Success(val plan: MigrationPlan) : MigrationExecutionResult
    data class Failure(
        val issues: List<MigrationIssue>,
        val cause: Throwable? = null,
    ) : MigrationExecutionResult
}

object LegacyContainerMigrationExecutor {

    fun execute(db: Database, plan: MigrationPlan): MigrationExecutionResult {
        if (!plan.canMigrate) {
            return MigrationExecutionResult.Failure(plan.issues)
        }

        // Verify source DB snapshot checksum
        val currentInventory = RawLegacyInventoryReader.read(db)
        if (currentInventory.snapshotSha256 != plan.sourceSnapshotSha256) {
            return MigrationExecutionResult.Failure(
                listOf(
                    MigrationIssue.SourceChecksumMismatch(
                        expectedSha256 = plan.sourceSnapshotSha256,
                        actualSha256 = currentInventory.snapshotSha256,
                    )
                )
            )
        }

        try {
            db.run("BEGIN IMMEDIATE")

            // 1. Create Organization Schema & enable foreign keys
            OrganizationSchema.createSchema(db)
            OrganizationSchema.enableForeignKeys(db)

            // 2. Insert OrganizationChanges baseline record
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

            // 3. Insert Containers
            val containerStmt = db.prepareStatement(
                """
                INSERT INTO Containers (
                    uuid, parent_id, name, color, icon, sibling_order, is_archived,
                    created_at, updated_at, deleted_at, revision
                ) VALUES (?, NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            )

            for (c in plan.containers) {
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

            // 4. Insert LegacyBlockMap
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

            // 5. Insert HabitPlacements
            val placeStmt = db.prepareStatement(
                """
                INSERT INTO HabitPlacements (habit_id, container_id, local_order, placement_origin, revision)
                VALUES (?, ?, ?, ?, ?)
                """.trimIndent()
            )
            for (p in plan.placements) {
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

            // 6. Insert ContainerHistory
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

            // 7. Insert HabitPlacementHistory
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

            // 8. Insert OrganizationState
            val state = plan.organizationState
            val stateStmt = db.prepareStatement(
                """
                INSERT OR REPLACE INTO OrganizationState (
                    id, dataset_uuid, foundation_version, mode, cutover_revision,
                    current_revision, cutover_at, source_schema_version, source_snapshot_sha256
                ) VALUES (1, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            )
            stateStmt.bindText(1, state.datasetUuid)
            stateStmt.bindInt(2, state.foundationVersion)
            stateStmt.bindText(3, state.mode)
            stateStmt.bindLong(4, state.cutoverRevision.value)
            stateStmt.bindLong(5, state.currentRevision.value)
            stateStmt.bindLong(6, state.cutoverAt)
            stateStmt.bindInt(7, state.sourceSchemaVersion)
            stateStmt.bindText(8, state.sourceSnapshotSha256)
            stateStmt.step()
            stateStmt.finalize()

            db.run("COMMIT")
            return MigrationExecutionResult.Success(plan)
        } catch (t: Throwable) {
            try {
                db.run("ROLLBACK")
            } catch (_: Throwable) {}
            return MigrationExecutionResult.Failure(
                issues = listOf(MigrationIssue.ImpossibleBaseline("Transaction failed: ${t.message}")),
                cause = t,
            )
        }
    }
}
