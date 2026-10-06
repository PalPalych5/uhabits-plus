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

package org.isoron.uhabits.core.rules.sqlite

import org.isoron.platform.io.Database
import org.isoron.platform.io.run

/** Opt-in schema for SourceRules; used in experimental dataset only, never by production migrations. */
object SourceRuleSchema {

    fun createSchema(db: Database) {
        db.run("SAVEPOINT migrate_source_rules_schema")
        try {
            db.run(
                """
                CREATE TABLE IF NOT EXISTS SourceRules (
                    id TEXT PRIMARY KEY NOT NULL CHECK(trim(id) <> ''),
                    source_kind TEXT NOT NULL CHECK(source_kind IN ('FOCUS_SESSION')),
                    source_container_id TEXT NOT NULL CHECK(trim(source_container_id) <> ''),
                    include_descendants INTEGER NOT NULL CHECK(include_descendants IN (0, 1)),
                    target_habit_uuid TEXT NOT NULL CHECK(trim(target_habit_uuid) <> ''),
                    value_mapping TEXT NOT NULL CHECK(value_mapping IN ('FOCUS_DURATION')),
                    is_enabled INTEGER NOT NULL CHECK(is_enabled IN (0, 1)),
                    effective_from INTEGER NOT NULL CHECK(effective_from >= 0),
                    created_at INTEGER NOT NULL CHECK(created_at >= 0),
                    updated_at INTEGER NOT NULL CHECK(updated_at >= created_at),
                    removed_at INTEGER CHECK(removed_at IS NULL OR removed_at >= created_at),
                    UNIQUE (source_kind, source_container_id, target_habit_uuid)
                )
                """.trimIndent()
            )

            db.run("CREATE INDEX IF NOT EXISTS idx_source_rules_target ON SourceRules(target_habit_uuid, id)")
            db.run("CREATE INDEX IF NOT EXISTS idx_source_rules_container ON SourceRules(source_container_id, id)")
            db.run("CREATE INDEX IF NOT EXISTS idx_source_rules_active ON SourceRules(is_enabled, removed_at)")
            db.run("RELEASE SAVEPOINT migrate_source_rules_schema")
        } catch (error: Throwable) {
            try { db.run("ROLLBACK TO SAVEPOINT migrate_source_rules_schema") }
            finally { db.run("RELEASE SAVEPOINT migrate_source_rules_schema") }
            throw error
        }
    }
}
