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
import org.isoron.platform.io.StepResult
import org.isoron.platform.io.getVersion
import org.isoron.platform.io.querySingle
import org.isoron.uhabits.core.DATABASE_VERSION

object ContainerBackupRouter {

    val REQUIRED_ORGANIZATION_TABLES = listOf(
        "OrganizationState",
        "OrganizationChanges",
        "Containers",
        "HabitPlacements",
        "ContainerHistory",
        "HabitPlacementHistory",
        "LegacyBlockMap",
    )

    fun inspect(db: Database, externalManifest: ContainerBackupManifest? = null): BackupInspectionResult {
        // 1. SQLite integrity check
        try {
            val integrity = db.querySingle("PRAGMA integrity_check") { it.getText(0) }
            if (integrity != "ok") {
                return BackupInspectionResult(
                    kind = DatasetKind.Malformed("SQLite integrity check failed: $integrity"),
                    tableNames = emptySet(),
                    databaseVersion = 0,
                )
            }
        } catch (t: Throwable) {
            return BackupInspectionResult(
                kind = DatasetKind.Malformed("Could not run integrity_check: ${t.message}"),
                tableNames = emptySet(),
                databaseVersion = 0,
            )
        }

        val dbVersion = db.getVersion()
        val tables = listTables(db)

        // 2. Check if legacy core tables exist
        if (!tables.contains("Habits")) {
            return BackupInspectionResult(
                kind = DatasetKind.Malformed("Missing core table 'Habits'"),
                tableNames = tables,
                databaseVersion = dbVersion,
            )
        }

        // 3. Check for unsupported future SQLite schema version
        if (dbVersion > DATABASE_VERSION) {
            return BackupInspectionResult(
                kind = DatasetKind.UnsupportedNewer("Database schema version $dbVersion is higher than supported $DATABASE_VERSION"),
                tableNames = tables,
                databaseVersion = dbVersion,
            )
        }

        // 4. Resolve manifest if embedded in DB and not supplied externally
        val manifest = externalManifest ?: readEmbeddedManifest(db, tables)

        if (manifest != null) {
            if (manifest.formatVersion > ContainerBackupManifest.CURRENT_FORMAT_VERSION) {
                return BackupInspectionResult(
                    kind = DatasetKind.UnsupportedNewer("Backup format version ${manifest.formatVersion} is higher than supported ${ContainerBackupManifest.CURRENT_FORMAT_VERSION}"),
                    tableNames = tables,
                    databaseVersion = dbVersion,
                    manifest = manifest,
                )
            }
            if (manifest.foundationVersion > ContainerBackupManifest.CURRENT_FOUNDATION_VERSION) {
                return BackupInspectionResult(
                    kind = DatasetKind.UnsupportedNewer("Backup foundation version ${manifest.foundationVersion} is higher than supported ${ContainerBackupManifest.CURRENT_FOUNDATION_VERSION}"),
                    tableNames = tables,
                    databaseVersion = dbVersion,
                    manifest = manifest,
                )
            }
        }

        // 5. Check Organization tables
        val presentOrgTables = REQUIRED_ORGANIZATION_TABLES.filter { tables.contains(it) }
        val missingOrgTables = REQUIRED_ORGANIZATION_TABLES.filter { !tables.contains(it) }

        if (presentOrgTables.isEmpty()) {
            // Pure legacy backup
            return BackupInspectionResult(
                kind = DatasetKind.LegacyProduction(schemaVersion = dbVersion),
                tableNames = tables,
                databaseVersion = dbVersion,
                manifest = manifest,
            )
        }

        if (missingOrgTables.isNotEmpty()) {
            // Incomplete/corrupted organization schema
            return BackupInspectionResult(
                kind = DatasetKind.Malformed(
                    reason = "Incomplete organization schema: missing tables $missingOrgTables",
                    missingTables = missingOrgTables,
                ),
                tableNames = tables,
                databaseVersion = dbVersion,
                manifest = manifest,
            )
        }

        // 6. Organization schema is fully present -> validate OrganizationState
        val stateStmt = db.prepareStatement(
            "SELECT dataset_uuid, foundation_version, mode FROM OrganizationState WHERE id = 1"
        )
        val stateRow = if (stateStmt.step() == StepResult.ROW) {
            Triple(stateStmt.getText(0), stateStmt.getInt(1), stateStmt.getText(2))
        } else {
            null
        }
        stateStmt.finalize()

        if (stateRow == null) {
            return BackupInspectionResult(
                kind = DatasetKind.Malformed("OrganizationState table exists but row with id=1 is missing"),
                tableNames = tables,
                databaseVersion = dbVersion,
                manifest = manifest,
            )
        }

        val (datasetUuid, foundationVersion, mode) = stateRow

        if (foundationVersion > ContainerBackupManifest.CURRENT_FOUNDATION_VERSION) {
            return BackupInspectionResult(
                kind = DatasetKind.UnsupportedNewer("OrganizationState foundation_version $foundationVersion > ${ContainerBackupManifest.CURRENT_FOUNDATION_VERSION}"),
                tableNames = tables,
                databaseVersion = dbVersion,
                manifest = manifest,
            )
        }

        return BackupInspectionResult(
            kind = DatasetKind.ExperimentalContainer(
                datasetUuid = datasetUuid,
                foundationVersion = foundationVersion,
                mode = mode,
                manifest = manifest,
            ),
            tableNames = tables,
            databaseVersion = dbVersion,
            manifest = manifest,
        )
    }

    private fun listTables(db: Database): Set<String> {
        val result = mutableSetOf<String>()
        val stmt = db.prepareStatement("SELECT name FROM sqlite_master WHERE type = 'table'")
        while (stmt.step() == StepResult.ROW) {
            result.add(stmt.getText(0))
        }
        stmt.finalize()
        return result
    }

    private fun readEmbeddedManifest(db: Database, tables: Set<String>): ContainerBackupManifest? {
        if (!tables.contains("BackupManifest")) return null
        return try {
            val json = db.querySingle("SELECT manifest_json FROM BackupManifest WHERE id = 1") {
                it.getText(0)
            } ?: return null
            ContainerBackupManifest.fromJson(json)
        } catch (_: Throwable) {
            null
        }
    }
}
