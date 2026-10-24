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

package org.isoron.uhabits.core.userTasks.sqlite

import org.isoron.platform.io.Database
import org.isoron.platform.io.StepResult
import org.isoron.platform.io.run
import org.isoron.uhabits.core.DATABASE_VERSION

object UserTaskSchema {
    val DDL_STATEMENTS = listOf(
        """
        CREATE TABLE IF NOT EXISTS UserTasks (
            id TEXT PRIMARY KEY NOT NULL,
            title TEXT NOT NULL CHECK(trim(title) <> ''),
            description TEXT NOT NULL DEFAULT '',
            container_id TEXT,
            status TEXT NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING', 'COMPLETED')),
            planned_date TEXT,
            deadline INTEGER,
            completed_at INTEGER,
            parent_task_id TEXT,
            sort_order INTEGER NOT NULL DEFAULT 0,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL
        );
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS idx_user_tasks_container ON UserTasks(container_id);",
        "CREATE INDEX IF NOT EXISTS idx_user_tasks_parent ON UserTasks(parent_task_id);",
        "CREATE INDEX IF NOT EXISTS idx_user_tasks_status ON UserTasks(status);",
        "CREATE INDEX IF NOT EXISTS idx_user_tasks_planned ON UserTasks(planned_date);",
        "CREATE INDEX IF NOT EXISTS idx_user_tasks_order ON UserTasks(parent_task_id, sort_order);"
    )

    fun createSchema(db: Database, targetVersion: Int = DATABASE_VERSION) {
        for (stmt in DDL_STATEMENTS) {
            db.run(stmt)
        }
        if (targetVersion >= 31) {
            ensureV31Columns(db)
        }
        if (targetVersion >= 32) ensureV32Columns(db)
        if (targetVersion >= 33) {
            org.isoron.uhabits.core.userTasks.tags.TaskTagSchema.createSchema(db)
        }
    }

    fun ensureV31Columns(db: Database) {
        val columns = mutableSetOf<String>()
        val pragma = db.prepareStatement("PRAGMA table_info(UserTasks)")
        try {
            while (pragma.step() == StepResult.ROW) {
                columns.add(pragma.getText(1))
            }
        } finally {
            pragma.finalize()
        }

        if ("due_kind" !in columns) {
            db.run("ALTER TABLE UserTasks ADD COLUMN due_kind TEXT NOT NULL DEFAULT 'NONE'")
            db.run("ALTER TABLE UserTasks ADD COLUMN due_date TEXT")
            db.run("ALTER TABLE UserTasks ADD COLUMN due_time_millis INTEGER")
            db.run("ALTER TABLE UserTasks ADD COLUMN due_zone TEXT")

            if ("deadline" in columns) {
                db.run("UPDATE UserTasks SET due_kind = 'TIMED', due_time_millis = deadline WHERE deadline IS NOT NULL AND deadline > 0")
            }

            db.run("DROP INDEX IF EXISTS idx_user_tasks_planned")
            db.run("CREATE INDEX IF NOT EXISTS idx_user_tasks_due_kind ON UserTasks(due_kind);")
            db.run("CREATE INDEX IF NOT EXISTS idx_user_tasks_due_date ON UserTasks(due_date);")
        }
    }

    fun ensureV32Columns(db: Database) {
        val stmt = db.prepareStatement("PRAGMA table_info(UserTasks)")
        var hasReviewState = false
        try {
            while (stmt.step() == StepResult.ROW) {
                if (stmt.getText(1) == "legacy_plan_review_pending") hasReviewState = true
            }
        } finally { stmt.finalize() }
        if (!hasReviewState) {
            db.run("ALTER TABLE UserTasks ADD COLUMN legacy_plan_review_pending INTEGER NOT NULL DEFAULT 0 CHECK(legacy_plan_review_pending IN (0, 1))")
            db.run("UPDATE UserTasks SET legacy_plan_review_pending = 1 WHERE due_kind = 'NONE' AND planned_date IS NOT NULL")
        }
    }
}
