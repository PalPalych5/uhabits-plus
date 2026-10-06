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

package org.isoron.uhabits.core.contribution.sqlite

import org.isoron.platform.io.Database
import org.isoron.platform.io.StepResult
import org.isoron.platform.io.run

/** Opt-in schema for Contribution ledger; used in experimental dataset only, never by production migrations. */
object ContributionSchema {

    fun createSchema(db: Database) {
        db.run("SAVEPOINT migrate_contribution_schema")
        try {
            db.run(
                """
                CREATE TABLE IF NOT EXISTS Contributions (
                    id TEXT PRIMARY KEY NOT NULL CHECK(trim(id) <> ''),
                    target_habit_uuid TEXT NOT NULL CHECK(trim(target_habit_uuid) <> ''),
                    source_type TEXT NOT NULL CHECK(source_type IN ('FOCUS_SESSION')),
                    source_id TEXT NOT NULL CHECK(trim(source_id) <> ''),
                    contributed_value INTEGER NOT NULL CHECK(contributed_value >= 0),
                    effective_timestamp INTEGER NOT NULL CHECK(effective_timestamp >= 0),
                    created_at INTEGER NOT NULL CHECK(created_at >= 0),
                    updated_at INTEGER NOT NULL CHECK(updated_at >= created_at),
                    removed_at INTEGER CHECK(removed_at IS NULL OR removed_at >= created_at),
                    source_rule_id TEXT CHECK(source_rule_id IS NULL OR trim(source_rule_id) <> ''),
                    UNIQUE (source_type, source_id, target_habit_uuid)
                )
                """.trimIndent()
            )

            val columns = mutableSetOf<String>()
            val stmt = db.prepareStatement("PRAGMA table_info(Contributions)")
            try {
                while (stmt.step() == StepResult.ROW) columns.add(stmt.getText(1))
            } finally {
                stmt.finalize()
            }
            if ("source_rule_id" !in columns) {
                db.run("ALTER TABLE Contributions ADD COLUMN source_rule_id TEXT CHECK(source_rule_id IS NULL OR trim(source_rule_id) <> '')")
            }

            db.run("CREATE INDEX IF NOT EXISTS idx_contributions_habit ON Contributions(target_habit_uuid, effective_timestamp, id)")
            db.run("CREATE INDEX IF NOT EXISTS idx_contributions_source ON Contributions(source_type, source_id, id)")
            db.run("CREATE INDEX IF NOT EXISTS idx_contributions_rule ON Contributions(source_rule_id)")
            db.run("RELEASE SAVEPOINT migrate_contribution_schema")
        } catch (error: Throwable) {
            try { db.run("ROLLBACK TO SAVEPOINT migrate_contribution_schema") }
            finally { db.run("RELEASE SAVEPOINT migrate_contribution_schema") }
            throw error
        }
    }
}
