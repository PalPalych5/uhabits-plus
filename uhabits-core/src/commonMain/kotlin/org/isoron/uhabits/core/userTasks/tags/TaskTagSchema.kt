package org.isoron.uhabits.core.userTasks.tags

import org.isoron.platform.io.Database
import org.isoron.platform.io.run

/** Additive v33 schema. Task and Habit columns are deliberately untouched. */
object TaskTagSchema {
    val statements = listOf(
        "CREATE TABLE IF NOT EXISTS TaskTagState (id INTEGER PRIMARY KEY CHECK(id=1), revision INTEGER NOT NULL)",
        "INSERT OR IGNORE INTO TaskTagState(id,revision) VALUES(1,0)",
        """CREATE TABLE IF NOT EXISTS TaskTags (
            id TEXT PRIMARY KEY NOT NULL CHECK(length(id) > 0),
            name TEXT NOT NULL CHECK(length(trim(name)) BETWEEN 1 AND 80),
            normalized_name TEXT NOT NULL UNIQUE,
            updated_at INTEGER NOT NULL
        )""".trimIndent(),
        """CREATE TABLE IF NOT EXISTS TaskTagLinks (
            task_id TEXT NOT NULL REFERENCES UserTasks(id) ON DELETE CASCADE,
            tag_id TEXT NOT NULL REFERENCES TaskTags(id) ON DELETE CASCADE,
            PRIMARY KEY(task_id, tag_id)
        )""".trimIndent(),
        "CREATE INDEX IF NOT EXISTS idx_task_tag_links_tag ON TaskTagLinks(tag_id, task_id)",
        """CREATE TABLE IF NOT EXISTS TaskTagOutbox (
            event_id TEXT PRIMARY KEY NOT NULL,
            entity_type TEXT NOT NULL CHECK(entity_type IN ('task_tag','task_tag_links')),
            entity_id TEXT NOT NULL,
            operation TEXT NOT NULL CHECK(operation IN ('upsert','delete')),
            payload TEXT NOT NULL,
            created_at INTEGER NOT NULL
        )""".trimIndent(),
    )

    fun createSchema(db: Database) {
        statements.forEach(db::run)
        // SQLite connections may intentionally disable FKs during restore. Keep normal
        // task/tag deletes consistent on those connections too, including sync deletes.
        db.run("""CREATE TRIGGER IF NOT EXISTS task_tags_delete_links AFTER DELETE ON TaskTags
            BEGIN DELETE FROM TaskTagLinks WHERE tag_id = OLD.id; END""")
        db.run("""CREATE TRIGGER IF NOT EXISTS task_delete_tag_links AFTER DELETE ON UserTasks
            BEGIN DELETE FROM TaskTagLinks WHERE task_id = OLD.id; END""")
    }
}
