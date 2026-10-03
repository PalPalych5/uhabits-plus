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
import org.isoron.platform.io.queryLong
import org.isoron.platform.io.querySingle
import org.isoron.uhabits.core.containers.sqlite.OrganizationSchema

sealed interface MigrationValidationResult {
    data class Valid(val plan: MigrationPlan) : MigrationValidationResult
    data class Invalid(val errors: List<String>) : MigrationValidationResult
}

object LegacyContainerMigrationValidator {

    fun validate(
        db: Database,
        beforeInventory: RawLegacyInventory,
        plan: MigrationPlan,
    ): MigrationValidationResult {
        val errors = mutableListOf<String>()

        // 1. SQLite integrity check
        try {
            val integrity = db.querySingle("PRAGMA integrity_check") { it.getText(0) } ?: ""
            if (integrity != "ok") {
                errors.add("SQLite integrity_check failed: $integrity")
            }
        } catch (t: Throwable) {
            errors.add("Failed to run integrity_check: ${t.message}")
        }

        // 2. Foreign keys check
        try {
            OrganizationSchema.enableForeignKeys(db)
            val fkStmt = db.prepareStatement("PRAGMA foreign_key_check")
            var fkFailures = 0
            while (fkStmt.step() == StepResult.ROW) {
                fkFailures++
                val table = fkStmt.getText(0)
                val rowid = fkStmt.getLong(1)
                val targetTable = fkStmt.getText(2)
                errors.add("Foreign key violation in table '$table' at rowid=$rowid pointing to '$targetTable'")
            }
            fkStmt.finalize()
        } catch (t: Throwable) {
            errors.add("Failed to run foreign_key_check: ${t.message}")
        }

        // 3. Organization schema tables validation
        try {
            val containerCount = db.queryLong("SELECT count(*) FROM Containers")
            if (containerCount != plan.containers.size.toLong()) {
                errors.add("Container count mismatch: DB has $containerCount but plan expected ${plan.containers.size}")
            }

            val distinctContainerUuids = db.queryLong("SELECT count(DISTINCT uuid) FROM Containers")
            if (distinctContainerUuids != containerCount) {
                errors.add("Duplicate container UUIDs detected: $distinctContainerUuids distinct vs $containerCount total")
            }

            val mapCount = db.queryLong("SELECT count(*) FROM LegacyBlockMap")
            if (mapCount != plan.legacyBlockMaps.size.toLong()) {
                errors.add("LegacyBlockMap count mismatch: DB has $mapCount but plan expected ${plan.legacyBlockMaps.size}")
            }

            val placementCount = db.queryLong("SELECT count(*) FROM HabitPlacements")
            if (placementCount != beforeInventory.habits.size.toLong()) {
                errors.add("Habit placement count mismatch: DB has $placementCount but legacy habits count was ${beforeInventory.habits.size}")
            }

            val containerHistoryCount = db.queryLong("SELECT count(*) FROM ContainerHistory")
            if (containerHistoryCount != containerCount) {
                errors.add("ContainerHistory count mismatch: $containerHistoryCount vs $containerCount containers")
            }

            val placementHistoryCount = db.queryLong("SELECT count(*) FROM HabitPlacementHistory")
            if (placementHistoryCount != placementCount) {
                errors.add("HabitPlacementHistory count mismatch: $placementHistoryCount vs $placementCount placements")
            }

            val changesCount = db.queryLong("SELECT count(*) FROM OrganizationChanges")
            if (changesCount != 1L) {
                errors.add("OrganizationChanges expected exactly 1 baseline row, but found $changesCount")
            }

            // Verify OrganizationState
            val stateStmt = db.prepareStatement(
                "SELECT dataset_uuid, foundation_version, mode, current_revision, cutover_revision, source_snapshot_sha256 FROM OrganizationState WHERE id = 1"
            )
            if (stateStmt.step() == StepResult.ROW) {
                val currentRev = stateStmt.getLong(3)
                val cutoverRev = stateStmt.getLong(4)
                val storedSha = stateStmt.getText(5)

                if (currentRev != 1L || cutoverRev != 1L) {
                    errors.add("OrganizationState revisions invalid: current=$currentRev, cutover=$cutoverRev")
                }
                if (storedSha != beforeInventory.snapshotSha256) {
                    errors.add("OrganizationState source_snapshot_sha256 mismatch: stored='$storedSha' vs expected='${beforeInventory.snapshotSha256}'")
                }
            } else {
                errors.add("OrganizationState row with id=1 is missing")
            }
            stateStmt.finalize()

            // Verify dense ordering of root containers
            val rootOrderStmt = db.prepareStatement("SELECT sibling_order FROM Containers ORDER BY sibling_order ASC")
            var expectedSibling = 0
            while (rootOrderStmt.step() == StepResult.ROW) {
                val actualSibling = rootOrderStmt.getInt(0)
                if (actualSibling != expectedSibling) {
                    errors.add("Container sibling_order gap or duplicate: expected $expectedSibling, actual was $actualSibling")
                    break
                }
                expectedSibling++
            }
            rootOrderStmt.finalize()

        } catch (t: Throwable) {
            errors.add("Failed validating organization tables: ${t.message}")
        }

        // 4. Legacy Data Invariance Validation (Before vs After)
        try {
            val afterInventory = RawLegacyInventoryReader.read(db)

            if (afterInventory.snapshotSha256 != beforeInventory.snapshotSha256) {
                errors.add("Legacy data checksum changed after migration! Before: ${beforeInventory.snapshotSha256}, After: ${afterInventory.snapshotSha256}")
            }

            if (beforeInventory.blocks != afterInventory.blocks) {
                errors.add("HabitBlocks table was altered during migration")
            }
            if (beforeInventory.habits != afterInventory.habits) {
                errors.add("Habits table was altered during migration")
            }
            if (beforeInventory.extensions != afterInventory.extensions) {
                errors.add("HabitExtensions table was altered during migration")
            }
            if (beforeInventory.goals != afterInventory.goals) {
                errors.add("HabitGoals table was altered during migration")
            }
            if (beforeInventory.repetitions != afterInventory.repetitions) {
                errors.add("Repetitions table was altered during migration")
            }
            if (beforeInventory.entryOps != afterInventory.entryOps) {
                errors.add("EntryOps table was altered during migration")
            }
            if (beforeInventory.syncQueue != afterInventory.syncQueue) {
                errors.add("SyncQueue table was altered during migration")
            }
            if (beforeInventory.settings != afterInventory.settings) {
                errors.add("AppSettings table was altered during migration")
            }

            // Verify that only expected organization tables were added
            val afterTables = mutableSetOf<String>()
            val tblStmt = db.prepareStatement("SELECT name FROM sqlite_master WHERE type = 'table'")
            while (tblStmt.step() == StepResult.ROW) {
                afterTables.add(tblStmt.getText(0))
            }
            tblStmt.finalize()

            val allowedNewTables = setOf(
                "OrganizationState",
                "OrganizationChanges",
                "Containers",
                "HabitPlacements",
                "ContainerHistory",
                "HabitPlacementHistory",
                "LegacyBlockMap",
                "sqlite_sequence",
            )

            val legacyTableNames = if (beforeInventory.existingTables.isNotEmpty()) {
                beforeInventory.existingTables + setOf("sqlite_sequence")
            } else {
                setOf(
                    "Habits",
                    "HabitBlocks",
                    "HabitExtensions",
                    "HabitGoals",
                    "Repetitions",
                    "EntryOps",
                    "SyncQueue",
                    "AppSettings",
                    "Checkmarks",
                    "Streak",
                    "Score",
                    "Events",
                    "sqlite_sequence",
                )
            }

            for (tbl in afterTables) {
                if (!legacyTableNames.contains(tbl) && !allowedNewTables.contains(tbl)) {
                    errors.add("Unexpected table found in database: '$tbl'")
                }
            }

            for (tbl in legacyTableNames) {
                if (tbl != "sqlite_sequence" && !afterTables.contains(tbl)) {
                    errors.add("Legacy table '$tbl' was dropped during migration")
                }
            }
        } catch (t: Throwable) {
            errors.add("Failed verifying legacy data invariance: ${t.message}")
        }

        return if (errors.isEmpty()) {
            MigrationValidationResult.Valid(plan)
        } else {
            MigrationValidationResult.Invalid(errors)
        }
    }
}
