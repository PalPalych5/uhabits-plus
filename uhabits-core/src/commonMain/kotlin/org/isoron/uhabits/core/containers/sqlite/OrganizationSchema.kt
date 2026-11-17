/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.core.containers.sqlite

import org.isoron.platform.io.Database
import org.isoron.platform.io.StepResult
import org.isoron.platform.io.run

object OrganizationSchema {

    private val DDL_STATEMENTS = listOf(
        """
        CREATE TABLE IF NOT EXISTS OrganizationState (
            id INTEGER PRIMARY KEY CHECK(id = 1),
            dataset_uuid TEXT NOT NULL UNIQUE,
            foundation_version INTEGER NOT NULL,
            mode TEXT NOT NULL CHECK(mode IN ('STAGED', 'CONTAINER_LOCAL', 'PRODUCTION_NEXT')),
            cutover_revision INTEGER NOT NULL DEFAULT 1,
            current_revision INTEGER NOT NULL DEFAULT 1,
            cutover_at INTEGER NOT NULL,
            source_schema_version INTEGER NOT NULL,
            source_snapshot_sha256 TEXT NOT NULL,
            source_file_sha256 TEXT,
            source_schema_sha256 TEXT,
            runtime_authority TEXT NOT NULL DEFAULT 'LEGACY' CHECK(runtime_authority IN ('LEGACY', 'NEXT')),
            authority_epoch INTEGER NOT NULL DEFAULT 0,
            conversion_op_uuid TEXT
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

        """
        CREATE TABLE IF NOT EXISTS HabitVisualIdentities (
            habit_uuid TEXT PRIMARY KEY,
            icon_type TEXT NOT NULL CHECK(icon_type IN ('VECTOR', 'EMOJI')),
            icon_value TEXT NOT NULL
        );
        """.trimIndent(),

        "CREATE INDEX IF NOT EXISTS idx_containers_tree ON Containers(parent_id, deleted_at, is_archived, sibling_order, uuid);",
        "CREATE INDEX IF NOT EXISTS idx_containers_active ON Containers(deleted_at, is_archived, uuid);",
        "CREATE INDEX IF NOT EXISTS idx_habit_placements_contents ON HabitPlacements(container_id, local_order, habit_id);",
        "CREATE INDEX IF NOT EXISTS idx_container_history_lookup ON ContainerHistory(container_id, revision DESC);",
        "CREATE INDEX IF NOT EXISTS idx_habit_placement_history_lookup ON HabitPlacementHistory(habit_id, revision DESC);",
        "CREATE INDEX IF NOT EXISTS idx_org_changes_recorded ON OrganizationChanges(recorded_at, revision);",
    )

    fun createSchema(db: Database, targetVersion: Int = org.isoron.uhabits.core.DATABASE_VERSION) {
        for (stmt in DDL_STATEMENTS) {
            db.run(stmt)
        }
        val columns = mutableSetOf<String>()
        val stmt = db.prepareStatement("PRAGMA table_info(OrganizationState)")
        try {
            while (stmt.step() == StepResult.ROW) columns.add(stmt.getText(1))
        } finally {
            stmt.finalize()
        }
        if ("source_file_sha256" !in columns) db.run("ALTER TABLE OrganizationState ADD COLUMN source_file_sha256 TEXT")
        if ("source_schema_sha256" !in columns) db.run("ALTER TABLE OrganizationState ADD COLUMN source_schema_sha256 TEXT")
        if ("runtime_authority" !in columns) db.run("ALTER TABLE OrganizationState ADD COLUMN runtime_authority TEXT NOT NULL DEFAULT 'LEGACY' CHECK(runtime_authority IN ('LEGACY', 'NEXT'))")
        if ("authority_epoch" !in columns) db.run("ALTER TABLE OrganizationState ADD COLUMN authority_epoch INTEGER NOT NULL DEFAULT 0")
        if ("conversion_op_uuid" !in columns) db.run("ALTER TABLE OrganizationState ADD COLUMN conversion_op_uuid TEXT")

        org.isoron.uhabits.core.userTasks.sqlite.UserTaskSchema.createSchema(db, targetVersion = targetVersion)
    }

    fun enableForeignKeys(db: Database) {
        db.run("PRAGMA foreign_keys = ON;")
    }
}
