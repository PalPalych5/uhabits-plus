/* Copyright (C) 2026 Alinson Santos Xavier and contributors. GPL-3.0-or-later. */
package org.isoron.uhabits.core.userTasks.due.sqlite

import org.isoron.platform.io.Database
import org.isoron.platform.io.StepResult
import org.isoron.platform.io.querySingle
import org.isoron.platform.io.run
import org.isoron.platform.io.runInTransaction

/** Detached proposal only. Production database installation deliberately does not call this. */
object TaskDueSchema {
    private val columns = mapOf(
        "TaskDueV1" to listOf("task_id:TEXT:1:1", "version:INTEGER:1:0", "kind:TEXT:1:0", "day:TEXT:0:0", "instant_ms:INTEGER:0:0", "origin_zone:TEXT:0:0", "proposed_day:TEXT:0:0", "review:TEXT:1:0"),
        "TaskDueLegacySourceV1" to listOf("source_id:INTEGER:0:1", "task_id:TEXT:1:0", "original:INTEGER:1:0", "raw_plan:TEXT:0:0", "deadline_ms:INTEGER:0:0", "updated_at:INTEGER:1:0", "source_format:TEXT:1:0", "source_revision:TEXT:0:0", "source_operation_id:TEXT:0:0", "numeric_plan_encoding:TEXT:1:0")
    )

    /** Refuse future physical shapes even when they still advertise envelope version 1. */
    internal fun validateSchema(db: Database, allowMissing: Boolean = false) {
        for ((table, expected) in columns) {
            val kind = db.querySingle("SELECT type FROM sqlite_master WHERE name = ?", table) { it.getText(0) }
            if (kind == null && allowMissing) continue
            require(kind == "table") { "Missing or unsupported Due table $table" }
            val actual = mutableListOf<String>()
            val stmt = db.prepareStatement("PRAGMA table_info($table)")
            try {
                while (stmt.step() == StepResult.ROW) {
                    require(stmt.getTextOrNull(4) == null) { "Unsupported Due column default in $table" }
                    actual.add("${stmt.getText(1)}:${stmt.getText(2).uppercase()}:${stmt.getInt(3)}:${stmt.getInt(5)}")
                }
            } finally { stmt.finalize() }
            require(actual == expected) { "Unsupported Due physical schema $table" }
        }
    }

    fun createSchema(db: Database) = db.runInTransaction {
        // Validate every existing table before creating any missing companion table/index.
        validateSchema(db, allowMissing = true)
        // No FK to UserTask: legacy INSERT OR REPLACE must not cascade-delete Due provenance.
        // Wiring requires explicit task/dataset deletion and backup lifecycle handling.
        db.run("""CREATE TABLE IF NOT EXISTS TaskDueV1 (
            task_id TEXT PRIMARY KEY NOT NULL, version INTEGER NOT NULL CHECK(version = 1),
            kind TEXT NOT NULL CHECK(kind IN ('NONE','DAY','TIMED')), day TEXT,
            instant_ms INTEGER, origin_zone TEXT, proposed_day TEXT, review TEXT NOT NULL,
            CHECK((kind = 'NONE' AND day IS NULL AND instant_ms IS NULL AND origin_zone IS NULL)
               OR (kind = 'DAY' AND day IS NOT NULL AND instant_ms IS NULL AND origin_zone IS NULL)
               OR (kind = 'TIMED' AND day IS NULL AND instant_ms IS NOT NULL))
        )""")
        db.run("""CREATE TABLE IF NOT EXISTS TaskDueLegacySourceV1 (
            source_id INTEGER PRIMARY KEY, task_id TEXT NOT NULL, original INTEGER NOT NULL,
            raw_plan TEXT, deadline_ms INTEGER, updated_at INTEGER NOT NULL,
            source_format TEXT NOT NULL, source_revision TEXT, source_operation_id TEXT,
            numeric_plan_encoding TEXT NOT NULL, CHECK(original IN (0,1))
        )""")
        db.run("CREATE UNIQUE INDEX IF NOT EXISTS task_due_original ON TaskDueLegacySourceV1(task_id) WHERE original = 1")
    }
}
