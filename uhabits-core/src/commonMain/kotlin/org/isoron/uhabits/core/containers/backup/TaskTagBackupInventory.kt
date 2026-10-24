package org.isoron.uhabits.core.containers.backup

import org.isoron.platform.io.Database
import org.isoron.platform.io.StepResult
import org.isoron.uhabits.core.containers.migration.Sha256

/** Inventory includes pending operations so offline tag changes survive backup/restore. */
object TaskTagBackupInventory {
    val columns = linkedMapOf(
        "TaskTagState" to listOf("id", "revision"),
        "TaskTags" to listOf("id", "name", "normalized_name", "updated_at"),
        "TaskTagLinks" to listOf("task_id", "tag_id"),
        "TaskTagOutbox" to listOf("event_id", "entity_type", "entity_id", "operation", "payload", "created_at"),
    )

    fun schemaError(db: Database): String? {
        val tables = DatabaseSnapshotter.listTables(db)
        val present = columns.keys.filter { it in tables }
        if (present.isEmpty()) return null
        if (present.size != columns.size) return "Incomplete task tag schema"
        for ((table, expected) in columns) {
            val actual = mutableSetOf<String>()
            val stmt = db.prepareStatement("PRAGMA table_info($table)")
            try { while (stmt.step() == StepResult.ROW) actual.add(stmt.getText(1)) } finally { stmt.finalize() }
            if (actual != expected.toSet()) return "Unsupported $table columns"
        }
        val checks = listOf(
            "SELECT 1 FROM TaskTagLinks l LEFT JOIN UserTasks t ON t.id=l.task_id LEFT JOIN TaskTags g ON g.id=l.tag_id WHERE t.id IS NULL OR g.id IS NULL LIMIT 1",
            "SELECT 1 FROM TaskTags WHERE length(trim(name)) NOT BETWEEN 1 AND 80 OR normalized_name='' LIMIT 1",
        )
        for (sql in checks) {
            val stmt = db.prepareStatement(sql)
            try { if (stmt.step() == StepResult.ROW) return "Invalid or orphaned task tag data" } finally { stmt.finalize() }
        }
        return null
    }

    fun checksum(db: Database): String {
        require(schemaError(db) == null) { schemaError(db) ?: "Invalid task tags" }
        val tables = DatabaseSnapshotter.listTables(db)
        val canonical = buildString {
            append("TASK_TAGS:v1\n")
            for ((table, names) in columns) {
                if (table == "TaskTagState") continue // Cache revision is not a user fact.
                append(table).append('\n')
                if (table !in tables) continue
                val stmt = db.prepareStatement("SELECT ${names.joinToString()} FROM $table ORDER BY ${names.joinToString()}")
                try {
                    while (stmt.step() == StepResult.ROW) {
                        for (i in names.indices) {
                            val value = stmt.getText(i)
                            append(value.length).append(':').append(value)
                        }
                        append('\n')
                    }
                } finally { stmt.finalize() }
            }
        }
        return Sha256.hexDigest(Sha256.digest(canonical.encodeToByteArray()))
    }

    val emptyChecksum: String get() = Sha256.hexDigest(Sha256.digest(
        "TASK_TAGS:v1\nTaskTags\nTaskTagLinks\nTaskTagOutbox\n".encodeToByteArray()
    ))

    fun copy(source: Database, target: Database) {
        val tables = DatabaseSnapshotter.listTables(source)
        for ((table, names) in columns) {
            if (table !in tables) continue
            val select = source.prepareStatement("SELECT ${names.joinToString()} FROM $table")
            val insert = target.prepareStatement("INSERT ${if(table == "TaskTagState") "OR REPLACE " else ""}INTO $table (${names.joinToString()}) VALUES (${names.joinToString { "?" }})")
            try {
                while (select.step() == StepResult.ROW) {
                    for (i in names.indices) insert.bindText(i + 1, select.getText(i))
                    insert.step(); insert.reset()
                }
            } finally { select.finalize(); insert.finalize() }
        }
    }
}
