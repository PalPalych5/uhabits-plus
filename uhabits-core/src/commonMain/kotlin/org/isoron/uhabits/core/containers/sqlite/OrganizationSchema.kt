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

package org.isoron.uhabits.core.containers.sqlite

import org.isoron.platform.io.Database
import org.isoron.platform.io.run

object OrganizationSchema {

    private val DDL_STATEMENTS = listOf(
        "PRAGMA foreign_keys = ON;",

        """
        CREATE TABLE IF NOT EXISTS OrganizationState (
            id INTEGER PRIMARY KEY CHECK(id = 1),
            dataset_uuid TEXT NOT NULL UNIQUE,
            foundation_version INTEGER NOT NULL,
            mode TEXT NOT NULL CHECK(mode IN ('STAGED', 'CONTAINER_LOCAL')),
            cutover_revision INTEGER NOT NULL DEFAULT 1,
            current_revision INTEGER NOT NULL DEFAULT 1,
            cutover_at INTEGER NOT NULL,
            source_schema_version INTEGER NOT NULL,
            source_snapshot_sha256 TEXT NOT NULL
        );
        """.trimIndent(),

        """
        CREATE TABLE IF NOT EXISTS OrganizationChanges (
            revision INTEGER PRIMARY KEY,
            op_uuid TEXT NOT NULL UNIQUE,
            recorded_at INTEGER NOT NULL,
            operation_type TEXT NOT NULL,
            origin TEXT NOT NULL,
            command_payload TEXT NOT NULL
        );
        """.trimIndent(),

        """
        CREATE TABLE IF NOT EXISTS Containers (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            uuid TEXT NOT NULL UNIQUE,
            parent_id INTEGER NULL REFERENCES Containers(id) ON DELETE RESTRICT,
            name TEXT NOT NULL CHECK(trim(name) <> ''),
            color INTEGER NULL,
            icon TEXT NULL,
            sibling_order INTEGER NOT NULL CHECK(sibling_order >= 0),
            is_archived INTEGER NOT NULL CHECK(is_archived IN (0, 1)),
            created_at INTEGER NULL,
            updated_at INTEGER NOT NULL,
            deleted_at INTEGER NULL,
            revision INTEGER NOT NULL REFERENCES OrganizationChanges(revision),
            CHECK(parent_id IS NULL OR parent_id <> id)
        );
        """.trimIndent(),

        """
        CREATE TABLE IF NOT EXISTS HabitPlacements (
            habit_id INTEGER PRIMARY KEY REFERENCES Habits(id) ON DELETE RESTRICT,
            container_id INTEGER NULL REFERENCES Containers(id) ON DELETE RESTRICT,
            local_order INTEGER NOT NULL CHECK(local_order >= 0),
            placement_origin TEXT NOT NULL CHECK(placement_origin IN ('MIGRATION_SNAPSHOT', 'USER_CHANGE', 'IMPORT_SNAPSHOT', 'UNKNOWN_LEGACY')),
            revision INTEGER NOT NULL REFERENCES OrganizationChanges(revision)
        );
        """.trimIndent(),

        """
        CREATE TABLE IF NOT EXISTS ContainerHistory (
            container_id INTEGER NOT NULL REFERENCES Containers(id) ON DELETE RESTRICT,
            revision INTEGER NOT NULL REFERENCES OrganizationChanges(revision),
            parent_id INTEGER NULL REFERENCES Containers(id) ON DELETE RESTRICT,
            name TEXT NOT NULL,
            color INTEGER NULL,
            icon TEXT NULL,
            sibling_order INTEGER NOT NULL,
            is_archived INTEGER NOT NULL,
            created_at INTEGER NULL,
            updated_at INTEGER NOT NULL,
            deleted_at INTEGER NULL,
            PRIMARY KEY(container_id, revision)
        );
        """.trimIndent(),

        """
        CREATE TABLE IF NOT EXISTS HabitPlacementHistory (
            habit_id INTEGER NOT NULL REFERENCES Habits(id) ON DELETE RESTRICT,
            revision INTEGER NOT NULL REFERENCES OrganizationChanges(revision),
            container_id INTEGER NULL REFERENCES Containers(id) ON DELETE RESTRICT,
            local_order INTEGER NOT NULL,
            placement_origin TEXT NOT NULL CHECK(placement_origin IN ('MIGRATION_SNAPSHOT', 'USER_CHANGE', 'IMPORT_SNAPSHOT', 'UNKNOWN_LEGACY')),
            PRIMARY KEY(habit_id, revision)
        );
        """.trimIndent(),

        """
        CREATE TABLE IF NOT EXISTS LegacyBlockMap (
            legacy_block_id INTEGER PRIMARY KEY REFERENCES HabitBlocks(id) ON DELETE RESTRICT,
            legacy_block_uuid TEXT NOT NULL UNIQUE,
            container_id INTEGER NOT NULL UNIQUE REFERENCES Containers(id) ON DELETE RESTRICT
        );
        """.trimIndent(),

        "CREATE INDEX IF NOT EXISTS idx_containers_tree ON Containers(parent_id, deleted_at, is_archived, sibling_order, uuid);",
        "CREATE INDEX IF NOT EXISTS idx_containers_active ON Containers(deleted_at, is_archived, uuid);",
        "CREATE INDEX IF NOT EXISTS idx_habit_placements_contents ON HabitPlacements(container_id, local_order, habit_id);",
        "CREATE INDEX IF NOT EXISTS idx_container_history_lookup ON ContainerHistory(container_id, revision DESC);",
        "CREATE INDEX IF NOT EXISTS idx_habit_placement_history_lookup ON HabitPlacementHistory(habit_id, revision DESC);",
        "CREATE INDEX IF NOT EXISTS idx_org_changes_recorded ON OrganizationChanges(recorded_at, revision);",
    )

    fun createSchema(db: Database) {
        for (stmt in DDL_STATEMENTS) {
            db.run(stmt)
        }
    }

    fun enableForeignKeys(db: Database) {
        db.run("PRAGMA foreign_keys = ON;")
    }
}
