package org.isoron.uhabits.core.production.preflight

import org.isoron.platform.io.Database
import org.isoron.platform.io.PreparedStatement
import org.isoron.platform.io.StepResult
import org.isoron.uhabits.core.containers.migration.RawLegacyInventoryReader
import org.isoron.uhabits.core.containers.migration.Sha256

/** Only SELECT/read PRAGMA statements. Caller owns a coherent read-only connection/snapshot. */
object ProductionPreflightService {
    private val legacy = setOf("HabitBlocks", "Habits", "HabitExtensions", "HabitGoals", "Repetitions", "EntryOps", "SyncQueue", "AppSettings")
    private val auxiliary = setOf("android_metadata", "Events") // Events retained by numbered legacy v19 migration.
    private val next = setOf("OrganizationState", "OrganizationChanges", "Containers", "ContainerHistory", "HabitPlacements", "HabitPlacementHistory", "LegacyBlockMap", "HabitVisualIdentities", "UserTasks", "FocusSessions", "FocusCaptureJournal", "Contributions", "SourceRules", "BackupManifest")

    fun inspect(db: Database, databasePath: String, fileSha256: String,
                timer: TimerMigrationReadiness = TimerMigrationReadiness.READY,
                identityMarkers: Map<String, String> = emptyMap(),
                recoveryBundleVerified: Boolean = false): ProductionPreflightReport {
        val issues = mutableListOf<PreflightIssue>()
        val schema = mutableListOf<PhysicalSchemaObject>()
        val columns = linkedMapOf<String, List<String>>()
        val counts = linkedMapOf<String, Long>()
        var version = -1
        var foreignKeys = false
        var journal = "unknown"
        var integrity = emptyList<String>()
        var fk = emptyList<String>()
        var semanticHash = ""
        fun issue(category: PreflightIssueCategory, table: String? = null, detail: String = "") { issues.add(PreflightIssue(category, table, detail)) }
        try {
            version = rows(db, "PRAGMA user_version") { it.getInt(0) }.single()
            if (version != 29) issue(PreflightIssueCategory.UnsupportedDatabaseVersion, detail = version.toString())
            foreignKeys = rows(db, "PRAGMA foreign_keys") { it.getInt(0) }.single() != 0
            journal = rows(db, "PRAGMA journal_mode") { it.getText(0) }.single()
            integrity = rows(db, "PRAGMA integrity_check") { it.getText(0) }
            if (integrity != listOf("ok")) issue(PreflightIssueCategory.IntegrityCheckFailed, detail = integrity.joinToString())
            try {
                fk = rows(db, "PRAGMA foreign_key_check") { frame(listOf(it.getText(0), it.getLongOrNull(1)?.toString(), it.getText(2), it.getInt(3).toString())) }
                if (fk.isNotEmpty()) issue(PreflightIssueCategory.ForeignKeyCheckFailed, detail = fk.joinToString())
            } catch (e: Exception) {
                fk = listOf(e.message ?: "foreign-key metadata invalid")
                issue(PreflightIssueCategory.ForeignKeyCheckFailed, detail = fk.joinToString())
            }
            schema.addAll(rows(db, "SELECT type,name,tbl_name,sql FROM sqlite_master ORDER BY type,name") {
                PhysicalSchemaObject(it.getText(0), it.getText(1), it.getText(2), it.getTextOrNull(3))
            })
            val tables = schema.filter { it.type == "table" }.map { it.name }.toSet()
            for (name in legacy - tables) issue(PreflightIssueCategory.MissingRequiredLegacyTable, name)
            val semantic = StringBuilder()
            for (name in tables.sorted()) {
                val cols = rows(db, "PRAGMA table_info(${quote(name)})") { it.getText(1) }
                columns[name] = cols
                counts[name] = rows(db, "SELECT COUNT(*) FROM ${quote(name)}") { it.getLong(0) }.single()
                if (name in next) issue(if (counts[name] == 0L) PreflightIssueCategory.UnexpectedNextTable else PreflightIssueCategory.NonEmptyUnexpectedNextTable, name, counts[name].toString())
                else if (name !in legacy && name !in auxiliary && !name.startsWith("sqlite_")) issue(PreflightIssueCategory.UnexpectedSchemaObject, name)
                if (name in legacy || name in auxiliary) {
                    // Length frames and SQLite value/type representations avoid PR3 delimiter collisions,
                    // include extra physical columns, preserve null vs empty, and retain duplicate rows.
                    val values = rows(db, "SELECT ${cols.joinToString(",") { "typeof(${quote(it)}),hex(CAST(${quote(it)} AS BLOB))" }} FROM ${quote(name)}") { stmt ->
                        frame((0 until cols.size * 2).map { stmt.getTextOrNull(it) })
                    }.sorted()
                    semantic.append(frame(listOf(name, frame(cols), frame(values))))
                }
            }
            semanticHash = Sha256.hexDigest(semantic.toString())
            for (obj in schema.filter { it.type in listOf("trigger", "view") }) issue(PreflightIssueCategory.UnexpectedSchemaObject, obj.name)
            if (legacy.all { it in tables }) {
                try {
                    val raw = RawLegacyInventoryReader.read(db)
                    fun ids(table: String, values: List<String?>) {
                        if (values.any { it.isNullOrBlank() }) issue(PreflightIssueCategory.BlankPortableUuid, table)
                        if (values.filterNotNull().groupingBy { it }.eachCount().any { it.value > 1 }) issue(PreflightIssueCategory.DuplicatePortableUuid, table)
                    }
                    ids("Habits", raw.habits.map { it.uuid }); ids("HabitBlocks", raw.blocks.map { it.uuid })
                    ids("HabitGoals", raw.goals.map { it.uuid }); ids("Repetitions", raw.repetitions.map { it.uuid })
                    ids("EntryOps", raw.entryOps.map { it.opUuid }); ids("SyncQueue", raw.syncQueue.map { it.opUuid })
                    val habits = raw.habits.map { it.id }.toSet()
                    val habitUuids = raw.habits.mapNotNull { it.uuid }.toSet()
                    val blocks = raw.blocks.groupBy { it.id }
                    for (e in raw.extensions) {
                        if (e.habitId !in habits) issue(PreflightIssueCategory.OrphanHabitExtension, "HabitExtensions", e.habitId.toString())
                        if (e.blockId != null && e.blockId !in blocks) issue(PreflightIssueCategory.DanglingBlockReference, "HabitExtensions", e.habitId.toString())
                        if (e.blockId != null && blocks[e.blockId]?.any { it.deletedAt != null } == true && raw.habits.any { it.id == e.habitId && it.deletedAt == null })
                            issue(PreflightIssueCategory.ActiveHabitInDeletedBlock, "HabitExtensions", e.habitId.toString())
                    }
                    raw.goals.filter { it.habitId !in habits }.forEach { issue(PreflightIssueCategory.OrphanHabitGoal, "HabitGoals", it.id.toString()) }
                    raw.repetitions.filter { it.habitId !in habits }.forEach { issue(PreflightIssueCategory.OrphanRepetition, "Repetitions", it.habitId.toString()) }
                    raw.entryOps.filter { it.habitUuid !in habitUuids }.forEach { issue(PreflightIssueCategory.OrphanEntryOp, "EntryOps", it.opUuid) }
                    fun duplicate(table: String, keys: List<String>) { if (keys.groupingBy { it }.eachCount().any { it.value > 1 }) issue(PreflightIssueCategory.MalformedLegacyRow, table, "duplicate natural key") }
                    duplicate("Habits", raw.habits.map { it.id.toString() })
                    duplicate("HabitBlocks", raw.blocks.map { it.id.toString() })
                    duplicate("HabitGoals", raw.goals.map { it.id.toString() })
                    duplicate("SyncQueue", raw.syncQueue.map { it.queueId.toString() })
                    duplicate("AppSettings", raw.settings.map { it.key })
                    duplicate("HabitExtensions", raw.extensions.map { it.habitId.toString() })
                    duplicate("HabitGoals", raw.goals.map { "${it.habitId}:${it.effectiveTimestamp}" })
                    duplicate("Repetitions", raw.repetitions.map { "${it.habitId}:${it.timestamp}" })
                    if (raw.habits.any { it.freqNum <= 0 || it.freqDen <= 0 || !it.targetValue.isFinite() } || raw.goals.any { it.freqNum <= 0 || it.freqDen <= 0 || !it.targetValue.isFinite() }) issue(PreflightIssueCategory.MalformedLegacyRow, detail = "invalid frequency/quantity")
                    for ((table, cols) in columns.filterKeys { it in legacy }) {
                        for (col in cols.filter { it in setOf("freq_num", "freq_den", "color", "position", "reminder_hour", "reminder_min", "reminder_days", "highlight", "archived", "is_archived", "timer_enabled", "type", "target_type", "value", "delta_value") }) {
                            val bad = rows(db, "SELECT COUNT(*) FROM ${quote(table)} WHERE ${quote(col)} < -2147483648 OR ${quote(col)} > 2147483647") { it.getLong(0) }.single()
                            if (bad > 0) issue(PreflightIssueCategory.MalformedLegacyRow, table, "$col exceeds legacy Int range: $bad rows")
                        }
                        for (col in cols.filter { it in setOf("archived", "is_archived", "timer_enabled") }) {
                            val bad = rows(db, "SELECT COUNT(*) FROM ${quote(table)} WHERE ${quote(col)} NOT IN (0,1)") { it.getLong(0) }.single()
                            if (bad > 0) issue(PreflightIssueCategory.MalformedLegacyRow, table, "$col must be 0 or 1: $bad rows")
                        }
                        for (col in cols.filter { it in setOf("uuid", "op_uuid", "habit_uuid", "entity_uuid", "device_id", "key") }) {
                            val bad = rows(db, "SELECT COUNT(*) FROM ${quote(table)} WHERE typeof(${quote(col)}) != 'text' OR trim(${quote(col)}) = ''") { it.getLong(0) }.single()
                            if (bad > 0) issue(PreflightIssueCategory.MalformedLegacyRow, table, "$col must be nonblank text: $bad rows")
                        }
                        val requiredIds = cols.filter { it in setOf("id", "habit_id", "habit", "timestamp", "effective_timestamp", "queue_id", "entry_timestamp", "freq_num", "freq_den", "color", "position", "updated_at", "created_at", "delta_value", "target_type", "type", "archived", "is_archived", "timer_enabled", "highlight", "reminder_days", "value") }
                        for (col in requiredIds) {
                            val bad = rows(db, "SELECT COUNT(*) FROM ${quote(table)} WHERE typeof(${quote(col)}) != 'integer'") { it.getLong(0) }.single()
                            if (bad > 0) issue(PreflightIssueCategory.MalformedLegacyRow, table, "$col must be integer: $bad rows")
                        }
                        for (col in cols.filter { it in setOf("deleted_at", "block_id", "stats_start_timestamp", "reminder_hour", "reminder_min", "pushed_at", "failed_at", "long_value") }) {
                            val bad = rows(db, "SELECT COUNT(*) FROM ${quote(table)} WHERE typeof(${quote(col)}) NOT IN ('integer','null')") { it.getLong(0) }.single()
                            if (bad > 0) issue(PreflightIssueCategory.MalformedLegacyRow, table, "$col must be integer or null: $bad rows")
                        }
                        if ("target_value" in cols) {
                            val bad = rows(db, "SELECT COUNT(*) FROM ${quote(table)} WHERE typeof(target_value) NOT IN ('integer','real')") { it.getLong(0) }.single()
                            if (bad > 0) issue(PreflightIssueCategory.MalformedLegacyRow, table, "target_value must be numeric: $bad rows")
                        }
                    }
                } catch (e: Exception) { issue(PreflightIssueCategory.MalformedLegacyRow, detail = e.message ?: "raw inventory unavailable") }
            }
        } catch (e: Exception) { issue(PreflightIssueCategory.InspectionFailed, detail = e.message ?: "database unavailable") }
        if (timer != TimerMigrationReadiness.READY) issue(PreflightIssueCategory.LegacyTimerActive, detail = timer.name)
        if (!recoveryBundleVerified) issue(PreflightIssueCategory.RecoveryBundleUnavailable)
        if (fileSha256.length != 64 || fileSha256.any { it !in "0123456789abcdef" }) issue(PreflightIssueCategory.InspectionFailed, detail = "verified file SHA-256 required")
        val schemaHash = Sha256.hexDigest(frame(schema.map { frame(listOf(it.type, it.name, it.table, it.createSql)) }))
        return ProductionPreflightReport(databasePath, ProductionSourceFingerprint(version, fileSha256, semanticHash, schemaHash, counts.toMap(), identityMarkers.entries.sortedBy { it.key }.associate { it.key to it.value }), schema, columns, integrity, fk, foreignKeys, journal, timer, issues, if (!foreignKeys) listOf("Connection foreign_keys disabled; foreign_key_check was still performed") else emptyList())
    }

    private fun quote(value: String) = "\"${value.replace("\"", "\"\"")}\""
    private fun frame(values: List<String?>): String = values.joinToString("") { if (it == null) "-1:" else "${it.length}:$it" }
    private fun <T> rows(db: Database, sql: String, read: (PreparedStatement) -> T): List<T> {
        val stmt = db.prepareStatement(sql)
        try { val result = mutableListOf<T>(); while (stmt.step() == StepResult.ROW) result.add(read(stmt)); return result }
        finally { stmt.finalize() }
    }
}
