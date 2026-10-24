package org.isoron.uhabits.core.userTasks.tags

import org.isoron.platform.io.Database
import org.isoron.platform.io.PreparedStatement
import org.isoron.platform.io.StepResult
import org.isoron.platform.io.runInTransaction
import org.isoron.platform.io.queryLong
import org.isoron.platform.io.run
import org.isoron.uhabits.core.userTasks.TaskId

data class TaskTag(val id: String, val name: String)

/** Global, dataset-scoped tags. Every mutation and its durable pending sync fact commit together. */
class TaskTagRepository(
    private val db: Database,
    private val idGenerator: () -> String,
    private val clock: () -> Long,
) {
    val revision: Long get() = db.queryLong("SELECT revision FROM TaskTagState WHERE id=1")
    fun getAll(): List<TaskTag> = readTags("SELECT id, name FROM TaskTags ORDER BY normalized_name, id")

    fun getTags(taskId: TaskId): List<TaskTag> = readTags(
        "SELECT t.id, t.name FROM TaskTags t JOIN TaskTagLinks l ON l.tag_id=t.id WHERE l.task_id=? ORDER BY t.normalized_name, t.id",
        taskId.value,
    )

    fun getTagIds(taskId: TaskId): Set<String> = getTags(taskId).map { it.id }.toSet()

    fun getTaskTags(): Map<TaskId, List<TaskTag>> {
        val result = linkedMapOf<TaskId, MutableList<TaskTag>>()
        query("SELECT l.task_id,t.id,t.name FROM TaskTagLinks l JOIN TaskTags t ON t.id=l.tag_id ORDER BY t.normalized_name,t.id") {
            result.getOrPut(TaskId(it.getText(0))) { mutableListOf() }.add(TaskTag(it.getText(1),it.getText(2)))
        }
        return result
    }

    fun getTaskIds(tagIds: Set<String>): Set<TaskId> {
        if (tagIds.isEmpty()) return emptySet()
        val result = mutableSetOf<TaskId>()
        query("SELECT task_id FROM TaskTagLinks WHERE tag_id IN (${tagIds.joinToString { "?" }}) GROUP BY task_id HAVING COUNT(DISTINCT tag_id) = ${tagIds.size}", *tagIds.toTypedArray()) {
            result.add(TaskId(it.getText(0)))
        }
        return result
    }

    fun create(name: String): TaskTag = db.runInTransaction {
        val display = cleanName(name)
        val existing = readTags("SELECT id,name FROM TaskTags WHERE normalized_name=?", display.lowercase()).firstOrNull()
        if (existing != null) return@runInTransaction existing
        val tag = TaskTag(idGenerator(), display)
        val now = clock()
        execute("INSERT INTO TaskTags(id,name,normalized_name,updated_at) VALUES(?,?,?,?)") {
            bindText(1, tag.id); bindText(2, tag.name); bindText(3, display.lowercase()); bindLong(4, now)
        }
        capture("task_tag", tag.id, "upsert", "{\"name\":${json(tag.name)}}", now)
        tag
    }

    fun rename(id: String, name: String): TaskTag = db.runInTransaction {
        val display = cleanName(name)
        require(readTags("SELECT id,name FROM TaskTags WHERE id=?", id).isNotEmpty()) { "Tag no longer exists" }
        require(readTags("SELECT id,name FROM TaskTags WHERE normalized_name=? AND id<>?", display.lowercase(), id).isEmpty()) { "Tag already exists" }
        val now = clock()
        execute("UPDATE TaskTags SET name=?,normalized_name=?,updated_at=? WHERE id=?") {
            bindText(1, display); bindText(2, display.lowercase()); bindLong(3, now); bindText(4, id)
        }
        capture("task_tag", id, "upsert", "{\"name\":${json(display)}}", now)
        TaskTag(id, display)
    }

    fun delete(id: String) = db.runInTransaction {
        if (readTags("SELECT id,name FROM TaskTags WHERE id=?", id).isEmpty()) return@runInTransaction
        execute("DELETE FROM TaskTagLinks WHERE tag_id=?") { bindText(1, id) }
        execute("DELETE FROM TaskTags WHERE id=?") { bindText(1, id) }
        capture("task_tag", id, "delete", "{}", clock())
    }

    fun setTags(taskId: TaskId, ids: Set<String>) = db.runInTransaction {
        var exists = false
        query("SELECT id FROM UserTasks WHERE id=?", taskId.value) { exists = true }
        require(exists) { "Task no longer exists" }
        val available = getAll().map { it.id }.toSet()
        require(available.containsAll(ids)) { "A selected tag no longer exists" }
        if (getTagIds(taskId) == ids) return@runInTransaction
        execute("DELETE FROM TaskTagLinks WHERE task_id=?") { bindText(1, taskId.value) }
        for (id in ids) execute("INSERT INTO TaskTagLinks(task_id,tag_id) VALUES(?,?)") {
            bindText(1, taskId.value); bindText(2, id)
        }
        capture("task_tag_links", taskId.value, "upsert", "{\"tag_ids\":[${ids.sorted().joinToString { json(it) }}]}", clock())
    }

    private fun capture(type: String, id: String, op: String, payload: String, now: Long) {
        db.run("UPDATE TaskTagState SET revision=revision+1 WHERE id=1")
        execute("INSERT INTO TaskTagOutbox(event_id,entity_type,entity_id,operation,payload,created_at) VALUES(?,?,?,?,?,?)") {
            bindText(1, idGenerator()); bindText(2, type); bindText(3, id); bindText(4, op); bindText(5, payload); bindLong(6, now)
        }
    }

    private fun readTags(sql: String, vararg args: String): List<TaskTag> = buildList {
        query(sql, *args) { add(TaskTag(it.getText(0), it.getText(1))) }
    }

    private fun query(sql: String, vararg args: String, block: (PreparedStatement) -> Unit) {
        val stmt = db.prepareStatement(sql)
        try {
            args.forEachIndexed { i, arg -> stmt.bindText(i + 1, arg) }
            while (stmt.step() == StepResult.ROW) block(stmt)
        } finally { stmt.finalize() }
    }

    private fun execute(sql: String, bind: PreparedStatement.() -> Unit) {
        val stmt = db.prepareStatement(sql)
        try { stmt.bind(); stmt.step() } finally { stmt.finalize() }
    }

    companion object {
        fun cleanName(name: String): String = name.trim().removePrefix("#").trim().replace(Regex("\\s+"), " ").also {
            require(it.isNotEmpty() && it.length <= 80 && it.none { c -> c.isISOControl() }) { "Tag name must contain 1–80 characters" }
        }

        private fun json(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""
    }
}
