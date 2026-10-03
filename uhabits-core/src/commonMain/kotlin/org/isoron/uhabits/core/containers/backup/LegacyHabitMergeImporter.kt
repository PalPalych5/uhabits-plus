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

package org.isoron.uhabits.core.containers.backup

import org.isoron.platform.io.Database
import org.isoron.platform.io.queryLong
import org.isoron.platform.io.querySingle
import org.isoron.platform.io.run
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.containers.OrganizationRevision
import org.isoron.uhabits.core.containers.PlacementOrigin
import org.isoron.uhabits.core.containers.migration.RawLegacyInventoryReader
import org.isoron.uhabits.core.containers.sqlite.SQLiteOrganizationStore

sealed interface ImportPlacementPolicy {
    data object Unassigned : ImportPlacementPolicy
    data class SpecificContainer(val containerId: ContainerId) : ImportPlacementPolicy
}

enum class ImportConflictPolicy {
    REJECT_ON_CONFLICT,
    GENERATE_NEW_UUID,
}

sealed interface ImportConflict {
    data class UuidCollision(
        val existingUuid: String,
        val incomingHabitName: String,
        val existingHabitName: String,
    ) : ImportConflict
}

sealed interface MergeImportResult {
    data class Success(
        val importedHabitCount: Int,
        val importedHabitUuids: List<String>,
        val remappedUuids: Map<String, String> = emptyMap(),
    ) : MergeImportResult

    data class Conflict(
        val conflicts: List<ImportConflict>,
    ) : MergeImportResult

    data class Unsupported(
        val reason: String,
    ) : MergeImportResult

    data class Failure(
        val error: String,
        val cause: Throwable? = null,
    ) : MergeImportResult
}

object LegacyHabitMergeImporter {

    private var uuidSuffixCounter = 100

    fun importLegacyHabits(
        sourceDb: Database,
        targetDb: Database,
        targetPlacement: ImportPlacementPolicy,
        conflictPolicy: ImportConflictPolicy = ImportConflictPolicy.REJECT_ON_CONFLICT,
        timestamp: Long = 0L,
    ): MergeImportResult {
        try {
            // 1. Inspect source
            val inspection = ContainerBackupRouter.inspect(sourceDb)
            when (val kind = inspection.kind) {
                is DatasetKind.ExperimentalContainer -> {
                    return MergeImportResult.Unsupported(
                        "Merge-import of Container datasets is not supported in Foundation. " +
                            "Use full restore instead to prevent ambiguous container tree merging."
                    )
                }
                is DatasetKind.UnsupportedNewer -> {
                    return MergeImportResult.Unsupported("Source database has unsupported version: ${kind.reason}")
                }
                is DatasetKind.Malformed -> {
                    return MergeImportResult.Failure("Source database is malformed: ${kind.reason}")
                }
                is DatasetKind.LegacyProduction -> {
                    // Valid legacy dataset -> proceed to import
                }
            }

            // 2. Validate target placement container if specific
            val targetContainerId = when (targetPlacement) {
                is ImportPlacementPolicy.Unassigned -> null
                is ImportPlacementPolicy.SpecificContainer -> {
                    val exists = targetDb.querySingle(
                        "SELECT id FROM Containers WHERE uuid = ? AND deleted_at IS NULL",
                        targetPlacement.containerId.value
                    ) { it.getLong(0) }
                    if (exists == null) {
                        return MergeImportResult.Failure(
                            "Target container '${targetPlacement.containerId.value}' does not exist in target database"
                        )
                    }
                    targetPlacement.containerId
                }
            }

            // 3. Read source and target inventories
            val sourceInventory = RawLegacyInventoryReader.read(sourceDb)
            val targetInventory = RawLegacyInventoryReader.read(targetDb)

            val existingUuids = targetInventory.habits.associate { (it.uuid ?: "") to it.name }

            // 4. Check for UUID collisions
            val collisions = mutableListOf<ImportConflict>()
            for (h in sourceInventory.habits) {
                val u = h.uuid ?: ""
                if (u.isNotBlank() && existingUuids.containsKey(u)) {
                    collisions.add(
                        ImportConflict.UuidCollision(
                            existingUuid = u,
                            incomingHabitName = h.name,
                            existingHabitName = existingUuids[u] ?: "",
                        )
                    )
                }
            }

            if (collisions.isNotEmpty() && conflictPolicy == ImportConflictPolicy.REJECT_ON_CONFLICT) {
                return MergeImportResult.Conflict(collisions)
            }

            // 5. Perform atomic merge import
            val store = SQLiteOrganizationStore(targetDb)
            val currentState = store.readState()
            val targetContainerLocalId = targetContainerId?.let { store.getLocalContainerId(it) }

            // Calculate starting local_order in target container
            val currentMaxOrder = if (targetContainerLocalId != null) {
                targetDb.queryLong(
                    "SELECT COALESCE(MAX(local_order), -1) FROM HabitPlacements WHERE container_id = $targetContainerLocalId"
                ).toInt()
            } else {
                targetDb.queryLong(
                    "SELECT COALESCE(MAX(local_order), -1) FROM HabitPlacements WHERE container_id IS NULL"
                ).toInt()
            }
            var nextLocalOrder = currentMaxOrder + 1

            val importedUuids = mutableListOf<String>()
            val remappedUuids = mutableMapOf<String, String>()

            targetDb.run("BEGIN IMMEDIATE")
            try {
                for (sourceHabit in sourceInventory.habits) {
                    val originalUuid = sourceHabit.uuid ?: "gen-h-${++uuidSuffixCounter}"
                    val effectiveUuid = if (existingUuids.containsKey(originalUuid)) {
                        val remapped = "$originalUuid-imported-${++uuidSuffixCounter}"
                        remappedUuids[originalUuid] = remapped
                        remapped
                    } else {
                        originalUuid
                    }

                    // 5a. Insert Habit
                    val insertHabitStmt = targetDb.prepareStatement(
                        """
                        INSERT INTO Habits (
                            uuid, name, description, question, freq_num, freq_den, color, position,
                            reminder_hour, reminder_min, reminder_days, highlight, archived, type,
                            target_value, target_type, unit, updated_at, deleted_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """.trimIndent()
                    )
                    insertHabitStmt.bindText(1, effectiveUuid)
                    insertHabitStmt.bindText(2, sourceHabit.name)
                    insertHabitStmt.bindText(3, sourceHabit.description)
                    insertHabitStmt.bindText(4, sourceHabit.question)
                    insertHabitStmt.bindInt(5, sourceHabit.freqNum)
                    insertHabitStmt.bindInt(6, sourceHabit.freqDen)
                    insertHabitStmt.bindInt(7, sourceHabit.color)
                    insertHabitStmt.bindInt(8, sourceHabit.position)
                    val rh = sourceHabit.reminderHour; if (rh != null) insertHabitStmt.bindInt(9, rh) else insertHabitStmt.bindNull(9)
                    val rm = sourceHabit.reminderMin; if (rm != null) insertHabitStmt.bindInt(10, rm) else insertHabitStmt.bindNull(10)
                    insertHabitStmt.bindInt(11, sourceHabit.reminderDays)
                    insertHabitStmt.bindInt(12, sourceHabit.highlight)
                    insertHabitStmt.bindInt(13, if (sourceHabit.archived) 1 else 0)
                    insertHabitStmt.bindInt(14, sourceHabit.type)
                    insertHabitStmt.bindReal(15, sourceHabit.targetValue)
                    insertHabitStmt.bindInt(16, sourceHabit.targetType)
                    insertHabitStmt.bindText(17, sourceHabit.unit)
                    insertHabitStmt.bindLong(18, sourceHabit.updatedAt)
                    val del = sourceHabit.deletedAt; if (del != null) insertHabitStmt.bindLong(19, del) else insertHabitStmt.bindNull(19)
                    insertHabitStmt.step()
                    insertHabitStmt.finalize()

                    val newHabitId = targetDb.querySingle("SELECT id FROM Habits WHERE uuid = ?", effectiveUuid) {
                        it.getLong(0)
                    } ?: error("Failed to retrieve generated habit id")

                    // 5b. Insert HabitExtension
                    val insertExtStmt = targetDb.prepareStatement(
                        """
                        INSERT OR REPLACE INTO HabitExtensions (
                            habit_id, day_tier, timer_enabled, block_id, stats_start_timestamp
                        ) VALUES (?, 'NORMAL', 0, NULL, NULL)
                        """.trimIndent()
                    )
                    insertExtStmt.bindLong(1, newHabitId)
                    insertExtStmt.step()
                    insertExtStmt.finalize()

                    // 5c. Insert HabitPlacement
                    val insertPlacementStmt = targetDb.prepareStatement(
                        """
                        INSERT OR REPLACE INTO HabitPlacements (
                            habit_id, container_id, local_order, placement_origin, revision
                        ) VALUES (?, ?, ?, ?, ?)
                        """.trimIndent()
                    )
                    insertPlacementStmt.bindLong(1, newHabitId)
                    if (targetContainerLocalId != null) insertPlacementStmt.bindLong(2, targetContainerLocalId) else insertPlacementStmt.bindNull(2)
                    insertPlacementStmt.bindInt(3, nextLocalOrder++)
                    insertPlacementStmt.bindText(4, PlacementOrigin.UNKNOWN_LEGACY.name)
                    insertPlacementStmt.bindLong(5, currentState.currentRevision.value)
                    insertPlacementStmt.step()
                    insertPlacementStmt.finalize()

                    // 5d. Insert HabitPlacementHistory
                    val insertPlHistStmt = targetDb.prepareStatement(
                        """
                        INSERT OR REPLACE INTO HabitPlacementHistory (
                            habit_id, revision, container_id, local_order, placement_origin
                        ) VALUES (?, ?, ?, ?, ?)
                        """.trimIndent()
                    )
                    insertPlHistStmt.bindLong(1, newHabitId)
                    insertPlHistStmt.bindLong(2, currentState.currentRevision.value)
                    if (targetContainerLocalId != null) insertPlHistStmt.bindLong(3, targetContainerLocalId) else insertPlHistStmt.bindNull(3)
                    insertPlHistStmt.bindInt(4, nextLocalOrder - 1)
                    insertPlHistStmt.bindText(5, PlacementOrigin.UNKNOWN_LEGACY.name)
                    insertPlHistStmt.step()
                    insertPlHistStmt.finalize()

                    // 5e. Copy Repetitions for this habit
                    val repSelect = sourceDb.prepareStatement(
                        "SELECT timestamp, value, notes, uuid, updated_at, deleted_at FROM Repetitions WHERE habit = ?"
                    )
                    repSelect.bindLong(1, sourceHabit.id)
                    val repInsert = targetDb.prepareStatement(
                        """
                        INSERT OR REPLACE INTO Repetitions (
                            habit, timestamp, value, notes, uuid, updated_at, deleted_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?)
                        """.trimIndent()
                    )
                    while (repSelect.step() == org.isoron.platform.io.StepResult.ROW) {
                        repInsert.bindLong(1, newHabitId)
                        repInsert.bindLong(2, repSelect.getLong(0))
                        repInsert.bindInt(3, repSelect.getInt(1))
                        val n = repSelect.getTextOrNull(2); if (n != null) repInsert.bindText(4, n) else repInsert.bindNull(4)
                        val u = repSelect.getTextOrNull(3); if (u != null) repInsert.bindText(5, u) else repInsert.bindNull(5)
                        repInsert.bindLong(6, repSelect.getLong(4))
                        val d = repSelect.getLongOrNull(5); if (d != null) repInsert.bindLong(7, d) else repInsert.bindNull(7)
                        repInsert.step()
                        repInsert.reset()
                    }
                    repSelect.finalize()
                    repInsert.finalize()

                    importedUuids.add(effectiveUuid)
                }

                targetDb.run("COMMIT")
                return MergeImportResult.Success(
                    importedHabitCount = importedUuids.size,
                    importedHabitUuids = importedUuids,
                    remappedUuids = remappedUuids,
                )
            } catch (t: Throwable) {
                try { targetDb.run("ROLLBACK") } catch (_: Throwable) {}
                return MergeImportResult.Failure("Transaction failed during merge import: ${t.message}", cause = t)
            }
        } catch (t: Throwable) {
            return MergeImportResult.Failure("Unexpected error during merge import: ${t.message}", cause = t)
        }
    }
}
