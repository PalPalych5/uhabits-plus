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
import org.isoron.platform.io.run
import org.isoron.platform.io.setVersion
import org.isoron.uhabits.core.containers.migration.RawLegacyInventoryReader
import org.isoron.uhabits.core.containers.sqlite.OrganizationSchema

object DatabaseSnapshotter {

    fun listTables(db: Database): Set<String> {
        val result = mutableSetOf<String>()
        val stmt = db.prepareStatement("SELECT name FROM sqlite_master WHERE type = 'table'")
        while (stmt.step() == StepResult.ROW) {
            result.add(stmt.getText(0))
        }
        stmt.finalize()
        return result
    }

    fun initTargetSchema(sourceDb: Database, targetDb: Database) {
        val sourceTables = listTables(sourceDb)
        val sourceVersion = sourceDb.getVersion()
        targetDb.setVersion(sourceVersion)

        // 1. Create Organization Schema if present in source
        if (sourceTables.contains("OrganizationState")) {
            OrganizationSchema.createSchema(targetDb)
        } else if (sourceTables.contains("UserTasks")) {
            org.isoron.uhabits.core.userTasks.sqlite.UserTaskSchema.createSchema(targetDb)
        }

        // 2. Create Legacy tables if not already created
        createLegacyTablesIfNeeded(targetDb, sourceTables)
        if (sourceTables.contains("FocusSessions")) {
            require(FocusBackupInventory.schemaError(sourceDb) == null) { "Unsupported FocusSessions schema" }
            org.isoron.uhabits.core.focus.sqlite.FocusSessionSchema.createSchema(targetDb)
        }
        if (sourceTables.contains("Contributions")) {
            require(ContributionBackupInventory.schemaError(sourceDb) == null) { "Unsupported Contributions schema" }
            org.isoron.uhabits.core.contribution.sqlite.ContributionSchema.createSchema(targetDb)
        }
        if (sourceTables.contains("SourceRules")) {
            require(SourceRuleBackupInventory.schemaError(sourceDb) == null) { "Unsupported SourceRules schema" }
            org.isoron.uhabits.core.rules.sqlite.SourceRuleSchema.createSchema(targetDb)
        }

        // 3. Create BackupManifest table
        targetDb.run(
            """
            CREATE TABLE IF NOT EXISTS BackupManifest (
                id INTEGER PRIMARY KEY CHECK(id = 1),
                manifest_json TEXT NOT NULL
            );
            """.trimIndent()
        )
    }

    private fun createLegacyTablesIfNeeded(db: Database, sourceTables: Set<String>) {
        if (sourceTables.contains("Habits")) {
            db.run(
                """
                CREATE TABLE IF NOT EXISTS Habits (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    uuid TEXT NOT NULL,
                    name TEXT NOT NULL,
                    description TEXT NOT NULL DEFAULT '',
                    question TEXT NOT NULL DEFAULT '',
                    freq_num INTEGER NOT NULL DEFAULT 1,
                    freq_den INTEGER NOT NULL DEFAULT 1,
                    color INTEGER NOT NULL DEFAULT 0,
                    position INTEGER NOT NULL DEFAULT 0,
                    reminder_hour INTEGER,
                    reminder_min INTEGER,
                    reminder_days INTEGER NOT NULL DEFAULT 0,
                    highlight INTEGER NOT NULL DEFAULT 0,
                    archived INTEGER NOT NULL DEFAULT 0,
                    type INTEGER NOT NULL DEFAULT 0,
                    target_value REAL NOT NULL DEFAULT 0.0,
                    target_type INTEGER NOT NULL DEFAULT 0,
                    unit TEXT NOT NULL DEFAULT '',
                    updated_at INTEGER NOT NULL DEFAULT 0,
                    deleted_at INTEGER
                );
                """.trimIndent()
            )
        }

        if (sourceTables.contains("HabitBlocks")) {
            db.run(
                """
                CREATE TABLE IF NOT EXISTS HabitBlocks (
                    id INTEGER PRIMARY KEY,
                    uuid TEXT NOT NULL,
                    name TEXT NOT NULL,
                    color INTEGER NOT NULL,
                    icon TEXT,
                    position INTEGER NOT NULL,
                    is_archived INTEGER NOT NULL DEFAULT 0,
                    updated_at INTEGER NOT NULL DEFAULT 0,
                    deleted_at INTEGER
                );
                """.trimIndent()
            )
        }

        if (sourceTables.contains("HabitExtensions")) {
            db.run(
                """
                CREATE TABLE IF NOT EXISTS HabitExtensions (
                    habit_id INTEGER PRIMARY KEY,
                    day_tier TEXT NOT NULL DEFAULT 'NORMAL',
                    timer_enabled INTEGER NOT NULL DEFAULT 0,
                    block_id INTEGER,
                    stats_start_timestamp INTEGER
                );
                """.trimIndent()
            )
        }

        if (sourceTables.contains("HabitGoals")) {
            db.run(
                """
                CREATE TABLE IF NOT EXISTS HabitGoals (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    habit_id INTEGER NOT NULL,
                    effective_timestamp INTEGER NOT NULL,
                    freq_num INTEGER NOT NULL,
                    freq_den INTEGER NOT NULL,
                    target_type INTEGER NOT NULL,
                    target_value REAL NOT NULL,
                    unit TEXT NOT NULL DEFAULT '',
                    uuid TEXT,
                    updated_at INTEGER NOT NULL DEFAULT 0,
                    deleted_at INTEGER
                );
                """.trimIndent()
            )
        }

        if (sourceTables.contains("Repetitions")) {
            db.run(
                """
                CREATE TABLE IF NOT EXISTS Repetitions (
                    habit INTEGER NOT NULL,
                    timestamp INTEGER NOT NULL,
                    value INTEGER NOT NULL,
                    notes TEXT,
                    uuid TEXT,
                    updated_at INTEGER NOT NULL DEFAULT 0,
                    deleted_at INTEGER
                );
                """.trimIndent()
            )
        }

        if (sourceTables.contains("EntryOps")) {
            db.run(
                """
                CREATE TABLE IF NOT EXISTS EntryOps (
                    op_uuid TEXT PRIMARY KEY,
                    habit_uuid TEXT NOT NULL,
                    entry_timestamp INTEGER NOT NULL,
                    delta_value INTEGER NOT NULL,
                    op_type TEXT NOT NULL,
                    notes TEXT,
                    device_id TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    deleted_at INTEGER
                );
                """.trimIndent()
            )
        }

        if (sourceTables.contains("SyncQueue")) {
            db.run(
                """
                CREATE TABLE IF NOT EXISTS SyncQueue (
                    queue_id INTEGER PRIMARY KEY AUTOINCREMENT,
                    op_uuid TEXT NOT NULL UNIQUE,
                    entity_type TEXT NOT NULL,
                    entity_uuid TEXT NOT NULL,
                    operation_type TEXT NOT NULL,
                    payload_json TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    device_id TEXT NOT NULL,
                    pushed_at INTEGER,
                    failed_at INTEGER,
                    failure_reason TEXT
                );
                """.trimIndent()
            )
        }

        if (sourceTables.contains("AppSettings")) {
            db.run(
                """
                CREATE TABLE IF NOT EXISTS AppSettings (
                    key TEXT PRIMARY KEY,
                    long_value INTEGER
                );
                """.trimIndent()
            )
        }
    }

    fun buildManifest(
        sourceDb: Database,
        timestamp: Long,
        preferences: Map<String, String> = emptyMap()
    ): ContainerBackupManifest {
        val inspection = ContainerBackupRouter.inspect(sourceDb)
        val containerKind = inspection.kind as? DatasetKind.ExperimentalContainer
            ?: throw IllegalStateException("Source database is not an experimental container dataset: ${inspection.kind}")

        val inventory = RawLegacyInventoryReader.read(sourceDb)
        val focusChecksum = FocusBackupInventory.checksum(sourceDb)
        val contributionChecksum = ContributionBackupInventory.checksum(sourceDb)
        val sourceRulesChecksum = SourceRuleBackupInventory.checksum(sourceDb)
        val canonicalChecksum = ContainerBackupManifest.computeCanonicalChecksum(
            datasetUuid = containerKind.datasetUuid,
            sourceSnapshotSha256 = inventory.snapshotSha256,
            createdAt = timestamp,
            foundationVersion = containerKind.foundationVersion,
            focusSessionsSha256 = focusChecksum,
            contributionsSha256 = contributionChecksum,
            sourceRulesSha256 = sourceRulesChecksum,
        )

        val appSettingsMap = inventory.settings.associate { it.key to (it.longValue ?: 0L) }
        val sanitizedPrefs = ContainerBackupManifest.sanitizePreferences(preferences)

        return ContainerBackupManifest(
            formatVersion = ContainerBackupManifest.CURRENT_FORMAT_VERSION,
            sourceSchemaVersion = sourceDb.getVersion(),
            foundationVersion = containerKind.foundationVersion,
            datasetMode = containerKind.mode,
            datasetUuid = containerKind.datasetUuid,
            createdAt = timestamp,
            sourceSnapshotSha256 = inventory.snapshotSha256,
            canonicalChecksum = canonicalChecksum,
            appSettings = appSettingsMap,
            nonSecretPreferences = sanitizedPrefs,
            focusSessionsSha256 = focusChecksum,
            contributionsSha256 = contributionChecksum,
            sourceRulesSha256 = sourceRulesChecksum,
        )
    }

    fun writeManifest(db: Database, manifest: ContainerBackupManifest) {
        db.run(
            """
            CREATE TABLE IF NOT EXISTS BackupManifest (
                id INTEGER PRIMARY KEY CHECK(id = 1),
                manifest_json TEXT NOT NULL
            );
            """.trimIndent()
        )
        val stmt = db.prepareStatement(
            "INSERT OR REPLACE INTO BackupManifest (id, manifest_json) VALUES (1, ?)"
        )
        stmt.bindText(1, manifest.toJson())
        stmt.step()
        stmt.finalize()
    }

    fun readManifest(db: Database): ContainerBackupManifest? {
        val tables = listTables(db)
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

    fun copyAllData(sourceDb: Database, targetDb: Database) {
        val sourceTables = listTables(sourceDb)

        try {
            targetDb.run("PRAGMA foreign_keys = OFF;")
            targetDb.run("BEGIN IMMEDIATE")

            val targetTables = listTables(targetDb)
            val tablesToClear = listOf(
                "BackupManifest",
                "FocusCaptureJournal",
                "SourceRules",
                "Contributions",
                "FocusSessions",
                "UserTasks",
                "HabitPlacementHistory",
                "ContainerHistory",
                "HabitPlacements",
                "LegacyBlockMap",
                "HabitVisualIdentities",
                "Containers",
                "OrganizationChanges",
                "OrganizationState",
                "SyncQueue",
                "EntryOps",
                "Repetitions",
                "HabitGoals",
                "HabitExtensions",
                "HabitBlocks",
                "Habits",
                "AppSettings",
            )
            for (tbl in tablesToClear) {
                if (targetTables.contains(tbl)) {
                    targetDb.run("DELETE FROM $tbl;")
                }
            }

            if (sourceTables.contains("Habits")) copyHabits(sourceDb, targetDb)
            if (sourceTables.contains("HabitBlocks")) copyHabitBlocks(sourceDb, targetDb)
            if (sourceTables.contains("HabitExtensions")) copyHabitExtensions(sourceDb, targetDb)
            if (sourceTables.contains("HabitGoals")) copyHabitGoals(sourceDb, targetDb)
            if (sourceTables.contains("Repetitions")) copyRepetitions(sourceDb, targetDb)
            if (sourceTables.contains("EntryOps")) copyEntryOps(sourceDb, targetDb)
            if (sourceTables.contains("SyncQueue")) copySyncQueue(sourceDb, targetDb)
            if (sourceTables.contains("AppSettings")) copyAppSettings(sourceDb, targetDb)

            if (sourceTables.contains("OrganizationChanges")) copyOrganizationChanges(sourceDb, targetDb)
            if (sourceTables.contains("OrganizationState")) copyOrganizationState(sourceDb, targetDb)
            if (sourceTables.contains("Containers")) copyContainers(sourceDb, targetDb)
            if (sourceTables.contains("HabitPlacements")) copyHabitPlacements(sourceDb, targetDb)
            if (sourceTables.contains("ContainerHistory")) copyContainerHistory(sourceDb, targetDb)
            if (sourceTables.contains("HabitPlacementHistory")) copyHabitPlacementHistory(sourceDb, targetDb)
            if (sourceTables.contains("LegacyBlockMap")) copyLegacyBlockMap(sourceDb, targetDb)
            if (sourceTables.contains("HabitVisualIdentities")) copyHabitVisualIdentities(sourceDb, targetDb)
            if (sourceTables.contains("UserTasks")) copyUserTasks(sourceDb, targetDb)
            if (sourceTables.contains("FocusSessions")) {
                val repository = org.isoron.uhabits.core.focus.sqlite.SQLiteFocusSessionRepository(targetDb)
                FocusBackupInventory.read(sourceDb).forEach(repository::record)
            }
            if (sourceTables.contains("Contributions")) {
                val repository = org.isoron.uhabits.core.contribution.sqlite.SQLiteContributionRepository(targetDb)
                ContributionBackupInventory.read(sourceDb).forEach(repository::record)
            }
            if (sourceTables.contains("SourceRules")) {
                val repository = org.isoron.uhabits.core.rules.sqlite.SQLiteSourceRuleRepository(targetDb)
                SourceRuleBackupInventory.read(sourceDb).forEach(repository::record)
            }

            if (sourceTables.contains("BackupManifest")) copyBackupManifest(sourceDb, targetDb)

            targetDb.run("COMMIT")
        } catch (t: Throwable) {
            try { targetDb.run("ROLLBACK") } catch (_: Throwable) {}
            throw t
        } finally {
            targetDb.run("PRAGMA foreign_keys = ON;")
        }
    }

    private fun copyHabits(sourceDb: Database, targetDb: Database) {
        val select = sourceDb.prepareStatement(
            """
            SELECT id, uuid, name, description, question, freq_num, freq_den, color, position,
                   reminder_hour, reminder_min, reminder_days, highlight, archived, type,
                   target_value, target_type, unit, updated_at, deleted_at
            FROM Habits
            """.trimIndent()
        )
        val insert = targetDb.prepareStatement(
            """
            INSERT OR REPLACE INTO Habits (
                id, uuid, name, description, question, freq_num, freq_den, color, position,
                reminder_hour, reminder_min, reminder_days, highlight, archived, type,
                target_value, target_type, unit, updated_at, deleted_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        )
        while (select.step() == StepResult.ROW) {
            insert.bindLong(1, select.getLong(0))
            val uuid = select.getTextOrNull(1); if (uuid != null) insert.bindText(2, uuid) else insert.bindNull(2)
            insert.bindText(3, select.getText(2))
            val desc = select.getTextOrNull(3); if (desc != null) insert.bindText(4, desc) else insert.bindNull(4)
            val q = select.getTextOrNull(4); if (q != null) insert.bindText(5, q) else insert.bindNull(5)
            insert.bindInt(6, select.getInt(5))
            insert.bindInt(7, select.getInt(6))
            insert.bindInt(8, select.getInt(7))
            insert.bindInt(9, select.getInt(8))
            val rh = select.getIntOrNull(9); if (rh != null) insert.bindInt(10, rh) else insert.bindNull(10)
            val rm = select.getIntOrNull(10); if (rm != null) insert.bindInt(11, rm) else insert.bindNull(11)
            val rd = select.getIntOrNull(11); if (rd != null) insert.bindInt(12, rd) else insert.bindNull(12)
            val hl = select.getIntOrNull(12); if (hl != null) insert.bindInt(13, hl) else insert.bindNull(13)
            insert.bindInt(14, select.getInt(13))
            insert.bindInt(15, select.getInt(14))
            insert.bindReal(16, select.getReal(15))
            insert.bindInt(17, select.getInt(16))
            val unit = select.getTextOrNull(17); if (unit != null) insert.bindText(18, unit) else insert.bindText(18, "")
            insert.bindLong(19, select.getLong(18))
            val del = select.getLongOrNull(19); if (del != null) insert.bindLong(20, del) else insert.bindNull(20)
            insert.step()
            insert.reset()
        }
        select.finalize()
        insert.finalize()
    }

    private fun copyHabitBlocks(sourceDb: Database, targetDb: Database) {
        val select = sourceDb.prepareStatement(
            "SELECT id, uuid, name, color, icon, position, is_archived, updated_at, deleted_at FROM HabitBlocks"
        )
        val insert = targetDb.prepareStatement(
            """
            INSERT OR REPLACE INTO HabitBlocks (
                id, uuid, name, color, icon, position, is_archived, updated_at, deleted_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        )
        while (select.step() == StepResult.ROW) {
            insert.bindLong(1, select.getLong(0))
            val uuid = select.getTextOrNull(1) ?: "block-${select.getLong(0)}"
            insert.bindText(2, uuid)
            insert.bindText(3, select.getText(2))
            insert.bindInt(4, select.getInt(3))
            val icon = select.getTextOrNull(4); if (icon != null) insert.bindText(5, icon) else insert.bindNull(5)
            insert.bindInt(6, select.getInt(5))
            insert.bindInt(7, select.getInt(6))
            insert.bindLong(8, select.getLong(7))
            val del = select.getLongOrNull(8); if (del != null) insert.bindLong(9, del) else insert.bindNull(9)
            insert.step()
            insert.reset()
        }
        select.finalize()
        insert.finalize()
    }

    private fun copyHabitExtensions(sourceDb: Database, targetDb: Database) {
        val select = sourceDb.prepareStatement(
            "SELECT habit_id, day_tier, timer_enabled, block_id, stats_start_timestamp FROM HabitExtensions"
        )
        val insert = targetDb.prepareStatement(
            """
            INSERT OR REPLACE INTO HabitExtensions (
                habit_id, day_tier, timer_enabled, block_id, stats_start_timestamp
            ) VALUES (?, ?, ?, ?, ?)
            """.trimIndent()
        )
        while (select.step() == StepResult.ROW) {
            insert.bindLong(1, select.getLong(0))
            insert.bindText(2, select.getText(1))
            insert.bindInt(3, select.getInt(2))
            val bId = select.getLongOrNull(3); if (bId != null) insert.bindLong(4, bId) else insert.bindNull(4)
            val st = select.getLongOrNull(4); if (st != null) insert.bindLong(5, st) else insert.bindNull(5)
            insert.step()
            insert.reset()
        }
        select.finalize()
        insert.finalize()
    }

    private fun copyHabitGoals(sourceDb: Database, targetDb: Database) {
        val select = sourceDb.prepareStatement(
            """
            SELECT id, habit_id, effective_timestamp, freq_num, freq_den, target_type, target_value, unit, uuid, updated_at, deleted_at
            FROM HabitGoals
            """.trimIndent()
        )
        val insert = targetDb.prepareStatement(
            """
            INSERT OR REPLACE INTO HabitGoals (
                id, habit_id, effective_timestamp, freq_num, freq_den, target_type, target_value, unit, uuid, updated_at, deleted_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        )
        while (select.step() == StepResult.ROW) {
            insert.bindLong(1, select.getLong(0))
            insert.bindLong(2, select.getLong(1))
            insert.bindLong(3, select.getLong(2))
            insert.bindInt(4, select.getInt(3))
            insert.bindInt(5, select.getInt(4))
            insert.bindInt(6, select.getInt(5))
            insert.bindReal(7, select.getReal(6))
            val unit = select.getTextOrNull(7); if (unit != null) insert.bindText(8, unit) else insert.bindText(8, "")
            val u = select.getTextOrNull(8); if (u != null) insert.bindText(9, u) else insert.bindNull(9)
            insert.bindLong(10, select.getLong(9))
            val del = select.getLongOrNull(10); if (del != null) insert.bindLong(11, del) else insert.bindNull(11)
            insert.step()
            insert.reset()
        }
        select.finalize()
        insert.finalize()
    }

    private fun copyRepetitions(sourceDb: Database, targetDb: Database) {
        val select = sourceDb.prepareStatement(
            "SELECT habit, timestamp, value, notes, uuid, updated_at, deleted_at FROM Repetitions"
        )
        val insert = targetDb.prepareStatement(
            """
            INSERT OR REPLACE INTO Repetitions (
                habit, timestamp, value, notes, uuid, updated_at, deleted_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        )
        while (select.step() == StepResult.ROW) {
            insert.bindLong(1, select.getLong(0))
            insert.bindLong(2, select.getLong(1))
            insert.bindInt(3, select.getInt(2))
            val notes = select.getTextOrNull(3); if (notes != null) insert.bindText(4, notes) else insert.bindNull(4)
            val u = select.getTextOrNull(4); if (u != null) insert.bindText(5, u) else insert.bindNull(5)
            insert.bindLong(6, select.getLong(5))
            val del = select.getLongOrNull(6); if (del != null) insert.bindLong(7, del) else insert.bindNull(7)
            insert.step()
            insert.reset()
        }
        select.finalize()
        insert.finalize()
    }

    private fun copyEntryOps(sourceDb: Database, targetDb: Database) {
        val select = sourceDb.prepareStatement(
            """
            SELECT op_uuid, habit_uuid, entry_timestamp, delta_value, op_type, notes, device_id, created_at, deleted_at
            FROM EntryOps
            """.trimIndent()
        )
        val insert = targetDb.prepareStatement(
            """
            INSERT OR REPLACE INTO EntryOps (
                op_uuid, habit_uuid, entry_timestamp, delta_value, op_type, notes, device_id, created_at, deleted_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        )
        while (select.step() == StepResult.ROW) {
            insert.bindText(1, select.getText(0))
            insert.bindText(2, select.getText(1))
            insert.bindLong(3, select.getLong(2))
            insert.bindInt(4, select.getInt(3))
            insert.bindText(5, select.getText(4))
            val notes = select.getTextOrNull(5); if (notes != null) insert.bindText(6, notes) else insert.bindNull(6)
            insert.bindText(7, select.getText(6))
            insert.bindLong(8, select.getLong(7))
            val del = select.getLongOrNull(8); if (del != null) insert.bindLong(9, del) else insert.bindNull(9)
            insert.step()
            insert.reset()
        }
        select.finalize()
        insert.finalize()
    }

    private fun copySyncQueue(sourceDb: Database, targetDb: Database) {
        val select = sourceDb.prepareStatement(
            """
            SELECT queue_id, op_uuid, entity_type, entity_uuid, operation_type, payload_json, created_at, device_id, pushed_at, failed_at, failure_reason
            FROM SyncQueue
            """.trimIndent()
        )
        val insert = targetDb.prepareStatement(
            """
            INSERT OR REPLACE INTO SyncQueue (
                queue_id, op_uuid, entity_type, entity_uuid, operation_type, payload_json, created_at, device_id, pushed_at, failed_at, failure_reason
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        )
        while (select.step() == StepResult.ROW) {
            insert.bindLong(1, select.getLong(0))
            insert.bindText(2, select.getText(1))
            insert.bindText(3, select.getText(2))
            insert.bindText(4, select.getText(3))
            insert.bindText(5, select.getText(4))
            insert.bindText(6, select.getText(5))
            insert.bindLong(7, select.getLong(6))
            insert.bindText(8, select.getText(7))
            val pushed = select.getLongOrNull(8); if (pushed != null) insert.bindLong(9, pushed) else insert.bindNull(9)
            val failed = select.getLongOrNull(9); if (failed != null) insert.bindLong(10, failed) else insert.bindNull(10)
            val reason = select.getTextOrNull(10); if (reason != null) insert.bindText(11, reason) else insert.bindNull(11)
            insert.step()
            insert.reset()
        }
        select.finalize()
        insert.finalize()
    }

    private fun copyAppSettings(sourceDb: Database, targetDb: Database) {
        val select = sourceDb.prepareStatement("SELECT key, long_value FROM AppSettings")
        val insert = targetDb.prepareStatement("INSERT OR REPLACE INTO AppSettings (key, long_value) VALUES (?, ?)")
        while (select.step() == StepResult.ROW) {
            insert.bindText(1, select.getText(0))
            val lv = select.getLongOrNull(1); if (lv != null) insert.bindLong(2, lv) else insert.bindNull(2)
            insert.step()
            insert.reset()
        }
        select.finalize()
        insert.finalize()
    }

    private fun copyOrganizationChanges(sourceDb: Database, targetDb: Database) {
        val select = sourceDb.prepareStatement(
            "SELECT revision, op_uuid, recorded_at, operation_type, origin, command_payload FROM OrganizationChanges"
        )
        val insert = targetDb.prepareStatement(
            """
            INSERT OR REPLACE INTO OrganizationChanges (
                revision, op_uuid, recorded_at, operation_type, origin, command_payload
            ) VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent()
        )
        while (select.step() == StepResult.ROW) {
            insert.bindLong(1, select.getLong(0))
            insert.bindText(2, select.getText(1))
            insert.bindLong(3, select.getLong(2))
            insert.bindText(4, select.getText(3))
            insert.bindText(5, select.getText(4))
            insert.bindText(6, select.getText(5))
            insert.step()
            insert.reset()
        }
        select.finalize()
        insert.finalize()
    }

    private fun copyOrganizationState(sourceDb: Database, targetDb: Database) {
        val cols = mutableSetOf<String>()
        val colStmt = sourceDb.prepareStatement("PRAGMA table_info(OrganizationState)")
        try { while (colStmt.step() == StepResult.ROW) cols.add(colStmt.getText(1)) }
        finally { colStmt.finalize() }
        val hasExtended = "runtime_authority" in cols

        val selectSql = if (hasExtended) {
            """
            SELECT id, dataset_uuid, foundation_version, mode, cutover_revision, current_revision, cutover_at, source_schema_version, source_snapshot_sha256,
                   source_file_sha256, source_schema_sha256, runtime_authority, authority_epoch, conversion_op_uuid
            FROM OrganizationState
            """.trimIndent()
        } else {
            """
            SELECT id, dataset_uuid, foundation_version, mode, cutover_revision, current_revision, cutover_at, source_schema_version, source_snapshot_sha256
            FROM OrganizationState
            """.trimIndent()
        }
        val insertSql = if (hasExtended) {
            """
            INSERT OR REPLACE INTO OrganizationState (
                id, dataset_uuid, foundation_version, mode, cutover_revision, current_revision, cutover_at, source_schema_version, source_snapshot_sha256,
                source_file_sha256, source_schema_sha256, runtime_authority, authority_epoch, conversion_op_uuid
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        } else {
            """
            INSERT OR REPLACE INTO OrganizationState (
                id, dataset_uuid, foundation_version, mode, cutover_revision, current_revision, cutover_at, source_schema_version, source_snapshot_sha256
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        }
        val select = sourceDb.prepareStatement(selectSql)
        val insert = targetDb.prepareStatement(insertSql)
        while (select.step() == StepResult.ROW) {
            insert.bindLong(1, select.getLong(0))
            insert.bindText(2, select.getText(1))
            insert.bindLong(3, select.getLong(2))
            insert.bindText(4, select.getText(3))
            insert.bindLong(5, select.getLong(4))
            insert.bindLong(6, select.getLong(5))
            insert.bindLong(7, select.getLong(6))
            insert.bindLong(8, select.getLong(7))
            insert.bindText(9, select.getText(8))
            if (hasExtended) {
                if (select.getTextOrNull(9) != null) insert.bindText(10, select.getText(9)) else insert.bindNull(10)
                if (select.getTextOrNull(10) != null) insert.bindText(11, select.getText(10)) else insert.bindNull(11)
                insert.bindText(12, select.getText(11))
                insert.bindLong(13, select.getLong(12))
                if (select.getTextOrNull(13) != null) insert.bindText(14, select.getText(13)) else insert.bindNull(14)
            }
            insert.step()
            insert.reset()
        }
        select.finalize()
        insert.finalize()
    }

    private fun copyContainers(sourceDb: Database, targetDb: Database) {
        val select = sourceDb.prepareStatement(
            """
            SELECT id, uuid, parent_id, name, color, icon, sibling_order, is_archived, created_at, updated_at, deleted_at, revision
            FROM Containers
            """.trimIndent()
        )
        val insert = targetDb.prepareStatement(
            """
            INSERT OR REPLACE INTO Containers (
                id, uuid, parent_id, name, color, icon, sibling_order, is_archived, created_at, updated_at, deleted_at, revision
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        )
        while (select.step() == StepResult.ROW) {
            insert.bindLong(1, select.getLong(0))
            insert.bindText(2, select.getText(1))
            val pid = select.getLongOrNull(2); if (pid != null) insert.bindLong(3, pid) else insert.bindNull(3)
            insert.bindText(4, select.getText(3))
            val color = select.getIntOrNull(4); if (color != null) insert.bindInt(5, color) else insert.bindNull(5)
            val icon = select.getTextOrNull(5); if (icon != null) insert.bindText(6, icon) else insert.bindNull(6)
            insert.bindInt(7, select.getInt(6))
            insert.bindInt(8, select.getInt(7))
            val cr = select.getLongOrNull(8); if (cr != null) insert.bindLong(9, cr) else insert.bindNull(9)
            insert.bindLong(10, select.getLong(9))
            val del = select.getLongOrNull(10); if (del != null) insert.bindLong(11, del) else insert.bindNull(11)
            insert.bindLong(12, select.getLong(11))
            insert.step()
            insert.reset()
        }
        select.finalize()
        insert.finalize()
    }

    private fun copyHabitPlacements(sourceDb: Database, targetDb: Database) {
        val select = sourceDb.prepareStatement(
            "SELECT habit_id, container_id, local_order, placement_origin, revision FROM HabitPlacements"
        )
        val insert = targetDb.prepareStatement(
            """
            INSERT OR REPLACE INTO HabitPlacements (
                habit_id, container_id, local_order, placement_origin, revision
            ) VALUES (?, ?, ?, ?, ?)
            """.trimIndent()
        )
        while (select.step() == StepResult.ROW) {
            insert.bindLong(1, select.getLong(0))
            val cid = select.getLongOrNull(1); if (cid != null) insert.bindLong(2, cid) else insert.bindNull(2)
            insert.bindInt(3, select.getInt(2))
            insert.bindText(4, select.getText(3))
            insert.bindLong(5, select.getLong(4))
            insert.step()
            insert.reset()
        }
        select.finalize()
        insert.finalize()
    }

    private fun copyContainerHistory(sourceDb: Database, targetDb: Database) {
        val select = sourceDb.prepareStatement(
            """
            SELECT container_id, revision, parent_id, name, color, icon, sibling_order, is_archived, created_at, updated_at, deleted_at
            FROM ContainerHistory
            """.trimIndent()
        )
        val insert = targetDb.prepareStatement(
            """
            INSERT OR REPLACE INTO ContainerHistory (
                container_id, revision, parent_id, name, color, icon, sibling_order, is_archived, created_at, updated_at, deleted_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        )
        while (select.step() == StepResult.ROW) {
            insert.bindLong(1, select.getLong(0))
            insert.bindLong(2, select.getLong(1))
            val pid = select.getLongOrNull(2); if (pid != null) insert.bindLong(3, pid) else insert.bindNull(3)
            insert.bindText(4, select.getText(3))
            val color = select.getIntOrNull(4); if (color != null) insert.bindInt(5, color) else insert.bindNull(5)
            val icon = select.getTextOrNull(5); if (icon != null) insert.bindText(6, icon) else insert.bindNull(6)
            insert.bindInt(7, select.getInt(6))
            insert.bindInt(8, select.getInt(7))
            val cr = select.getLongOrNull(8); if (cr != null) insert.bindLong(9, cr) else insert.bindNull(9)
            insert.bindLong(10, select.getLong(9))
            val del = select.getLongOrNull(10); if (del != null) insert.bindLong(11, del) else insert.bindNull(11)
            insert.step()
            insert.reset()
        }
        select.finalize()
        insert.finalize()
    }

    private fun copyHabitPlacementHistory(sourceDb: Database, targetDb: Database) {
        val select = sourceDb.prepareStatement(
            "SELECT habit_id, revision, container_id, local_order, placement_origin FROM HabitPlacementHistory"
        )
        val insert = targetDb.prepareStatement(
            """
            INSERT OR REPLACE INTO HabitPlacementHistory (
                habit_id, revision, container_id, local_order, placement_origin
            ) VALUES (?, ?, ?, ?, ?)
            """.trimIndent()
        )
        while (select.step() == StepResult.ROW) {
            insert.bindLong(1, select.getLong(0))
            insert.bindLong(2, select.getLong(1))
            val cid = select.getLongOrNull(2); if (cid != null) insert.bindLong(3, cid) else insert.bindNull(3)
            insert.bindInt(4, select.getInt(3))
            insert.bindText(5, select.getText(4))
            insert.step()
            insert.reset()
        }
        select.finalize()
        insert.finalize()
    }

    private fun copyLegacyBlockMap(sourceDb: Database, targetDb: Database) {
        val select = sourceDb.prepareStatement(
            "SELECT legacy_block_id, legacy_block_uuid, container_id FROM LegacyBlockMap"
        )
        val insert = targetDb.prepareStatement(
            """
            INSERT OR REPLACE INTO LegacyBlockMap (
                legacy_block_id, legacy_block_uuid, container_id
            ) VALUES (?, ?, ?)
            """.trimIndent()
        )
        while (select.step() == StepResult.ROW) {
            insert.bindLong(1, select.getLong(0))
            insert.bindText(2, select.getText(1))
            insert.bindLong(3, select.getLong(2))
            insert.step()
            insert.reset()
        }
        select.finalize()
        insert.finalize()
    }

    private fun copyBackupManifest(sourceDb: Database, targetDb: Database) {
        val select = sourceDb.prepareStatement("SELECT id, manifest_json FROM BackupManifest")
        val insert = targetDb.prepareStatement("INSERT OR REPLACE INTO BackupManifest (id, manifest_json) VALUES (?, ?)")
        while (select.step() == StepResult.ROW) {
            insert.bindLong(1, select.getLong(0))
            insert.bindText(2, select.getText(1))
            insert.step()
            insert.reset()
        }
        select.finalize()
        insert.finalize()
    }

    private fun copyHabitVisualIdentities(sourceDb: Database, targetDb: Database) {
        val select = sourceDb.prepareStatement(
            "SELECT habit_uuid, icon_type, icon_value FROM HabitVisualIdentities"
        )
        val insert = targetDb.prepareStatement(
            """
            INSERT OR REPLACE INTO HabitVisualIdentities (
                habit_uuid, icon_type, icon_value
            ) VALUES (?, ?, ?)
            """.trimIndent()
        )
        while (select.step() == StepResult.ROW) {
            insert.bindText(1, select.getText(0))
            insert.bindText(2, select.getText(1))
            insert.bindText(3, select.getText(2))
            insert.step()
            insert.reset()
        }
        select.finalize()
        insert.finalize()
    }

    private fun copyUserTasks(sourceDb: Database, targetDb: Database) {
        val select = sourceDb.prepareStatement(
            """
            SELECT id, title, description, container_id, status, planned_date,
                   deadline, completed_at, parent_task_id, sort_order, created_at, updated_at
            FROM UserTasks
            """.trimIndent()
        )
        val insert = targetDb.prepareStatement(
            """
            INSERT OR REPLACE INTO UserTasks (
                id, title, description, container_id, status, planned_date,
                deadline, completed_at, parent_task_id, sort_order, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        )
        while (select.step() == StepResult.ROW) {
            insert.bindText(1, select.getText(0))
            insert.bindText(2, select.getText(1))
            insert.bindText(3, select.getText(2))
            val cid = select.getTextOrNull(3); if (cid != null) insert.bindText(4, cid) else insert.bindNull(4)
            insert.bindText(5, select.getText(4))
            val pdate = select.getTextOrNull(5); if (pdate != null) insert.bindText(6, pdate) else insert.bindNull(6)
            val dline = select.getLongOrNull(6); if (dline != null) insert.bindLong(7, dline) else insert.bindNull(7)
            val compAt = select.getLongOrNull(7); if (compAt != null) insert.bindLong(8, compAt) else insert.bindNull(8)
            val pid = select.getTextOrNull(8); if (pid != null) insert.bindText(9, pid) else insert.bindNull(9)
            insert.bindInt(10, select.getInt(9))
            insert.bindLong(11, select.getLong(10))
            insert.bindLong(12, select.getLong(11))
            insert.step()
            insert.reset()
        }
        select.finalize()
        insert.finalize()
    }
}
