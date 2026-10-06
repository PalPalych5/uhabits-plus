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

package org.isoron.uhabits.core.focus.sqlite

import org.isoron.platform.io.Database
import org.isoron.platform.io.run

/** Opt-in schema only: not part of production migrations or Android capture wiring. */
object FocusSessionSchema {
    fun createSchema(db: Database) {
        db.run(
            """
            CREATE TABLE IF NOT EXISTS FocusSessions (
                id TEXT PRIMARY KEY NOT NULL CHECK(trim(id) <> ''),
                started_at INTEGER NOT NULL CHECK(started_at >= 0),
                ended_at INTEGER NOT NULL CHECK(ended_at > started_at),
                duration_ms INTEGER NOT NULL CHECK(duration_ms > 0 AND duration_ms <= ended_at - started_at),
                task_id TEXT CHECK(task_id IS NULL OR trim(task_id) <> ''),
                habit_uuid TEXT CHECK(habit_uuid IS NULL OR trim(habit_uuid) <> ''),
                container_id TEXT CHECK(container_id IS NULL OR trim(container_id) <> ''),
                source TEXT NOT NULL CHECK(source IN ('MANUAL', 'TIMER')),
                device_id TEXT CHECK(device_id IS NULL OR trim(device_id) <> ''),
                created_at INTEGER NOT NULL CHECK(created_at >= 0),
                updated_at INTEGER NOT NULL CHECK(updated_at >= created_at),
                revision INTEGER NOT NULL CHECK(revision = 1)
            )
            """.trimIndent()
        )
        db.run("CREATE INDEX IF NOT EXISTS idx_focus_sessions_task ON FocusSessions(task_id, started_at, id)")
        db.run("CREATE INDEX IF NOT EXISTS idx_focus_sessions_started ON FocusSessions(started_at, id)")
    }
}
