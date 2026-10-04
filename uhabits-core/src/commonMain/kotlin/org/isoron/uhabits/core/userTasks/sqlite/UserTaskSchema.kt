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
import org.isoron.platform.io.run

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

    fun createSchema(db: Database) {
        for (stmt in DDL_STATEMENTS) {
            db.run(stmt)
        }
    }
}
