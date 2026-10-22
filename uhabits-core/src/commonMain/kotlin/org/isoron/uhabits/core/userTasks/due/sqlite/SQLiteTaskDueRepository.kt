/* Copyright (C) 2026 Alinson Santos Xavier and contributors. GPL-3.0-or-later. */
package org.isoron.uhabits.core.userTasks.due.sqlite

import org.isoron.platform.io.Database
import org.isoron.platform.io.PreparedStatement
import org.isoron.platform.io.StepResult
import org.isoron.platform.io.querySingle
import org.isoron.platform.io.runInTransaction
import org.isoron.uhabits.core.userTasks.due.*

/** Opt-in storage; not connected to tasks, sync, snapshots, or production migration. */
class SQLiteTaskDueRepository(private val db: Database) {
    fun find(taskId: String): LegacyTaskDuePlan? {
        val sources = readSources(taskId)
        return db.querySingle("SELECT version, kind, day, instant_ms, origin_zone, proposed_day, review FROM TaskDueV1 WHERE task_id = ?", taskId) { row ->
            require(row.getInt(0) == 1) { "Unsupported Due version" }
            val day = row.getTextOrNull(2)
            val instant = row.getLongOrNull(3)
            val zone = row.getTextOrNull(4)
            val due = when (row.getText(1)) {
                "NONE" -> {
                    require(day == null && instant == null && zone == null) { "Contradictory NONE Due fields" }
                    TaskDue.None
                }
                "DAY" -> {
                    require(day != null && instant == null && zone == null) { "Contradictory DAY Due fields" }
                    TaskDue.Day(TaskDueDate.parse(day))
                }
                "TIMED" -> {
                    require(day == null && instant != null) { "Contradictory TIMED Due fields" }
                    zone?.let { TaskDue.Timed.local(instant, it) } ?: TaskDue.Timed.legacy(instant)
                }
                else -> error("Unsupported Due kind")
            }
            LegacyTaskDuePlan(
                due = due, source = sources.first ?: error("Missing immutable Due source"),
                proposedDay = row.getTextOrNull(5)?.let { TaskDueDate.parse(it) },
                review = row.getText(6).split(',').filter { it.isNotEmpty() }.map { LegacyTaskDueReview.valueOf(it) }.toSet(),
                pendingSources = sources.second
            )
        }
    }

    /** One batch transaction: an interrupted iteration leaves no partially migrated tasks. */
    fun migrate(sources: Iterable<LegacyTaskDueSnapshot>) = db.runInTransaction {
        TaskDueSchema.validateSchema(db)
        for (source in sources) {
            val previous = find(source.taskId)
            val next = LegacyTaskDuePlanner.plan(source, previous)
            if (previous != next) {
                writePlan(next, existing = previous != null)
                if (previous == null) insertSource(next.source, true)
                for (pending in next.pendingSources - (previous?.pendingSources ?: emptySet())) {
                    insertSource(pending, false)
                }
            }
        }
    }

    /** Explicit NONE is durable and cannot trigger a future legacy fallback. */
    fun setDue(taskId: String, due: TaskDue) = db.runInTransaction {
        TaskDueSchema.validateSchema(db)
        val existing = find(taskId) ?: error("Due source must be imported before editing")
        writePlan(existing.copy(due = due), existing = true)
    }

    private fun writePlan(plan: LegacyTaskDuePlan, existing: Boolean) {
        require(plan.version == 1)
        val stmt = db.prepareStatement(if (existing)
            "UPDATE TaskDueV1 SET task_id=?,version=?,kind=?,day=?,instant_ms=?,origin_zone=?,proposed_day=?,review=? WHERE task_id=?"
        else "INSERT INTO TaskDueV1 (task_id,version,kind,day,instant_ms,origin_zone,proposed_day,review) VALUES (?,?,?,?,?,?,?,?)")
        try {
            stmt.bindText(1, plan.source.taskId)
            stmt.bindInt(2, plan.version)
            when (val due = plan.due) {
                TaskDue.None -> { stmt.bindText(3, "NONE"); stmt.bindNull(4); stmt.bindNull(5); stmt.bindNull(6) }
                is TaskDue.Day -> { stmt.bindText(3, "DAY"); stmt.bindText(4, due.date.isoDate); stmt.bindNull(5); stmt.bindNull(6) }
                is TaskDue.Timed -> { stmt.bindText(3, "TIMED"); stmt.bindNull(4); stmt.bindLong(5, due.epochMillis); stmt.textOrNull(6, due.originZoneId) }
            }
            stmt.textOrNull(7, plan.proposedDay?.isoDate)
            stmt.bindText(8, plan.review.map { it.name }.sorted().joinToString(","))
            if (existing) stmt.bindText(9, plan.source.taskId)
            stmt.step()
        } finally { stmt.finalize() }
    }

    private fun insertSource(source: LegacyTaskDueSnapshot, original: Boolean) {
        val stmt = db.prepareStatement("INSERT INTO TaskDueLegacySourceV1 (task_id,original,raw_plan,deadline_ms,updated_at,source_format,source_revision,source_operation_id,numeric_plan_encoding) VALUES (?,?,?,?,?,?,?,?,?)")
        try {
            stmt.bindText(1, source.taskId); stmt.bindInt(2, if (original) 1 else 0)
            stmt.textOrNull(3, source.rawPlannedDate)
            source.deadlineMillis?.let { stmt.bindLong(4, it) } ?: stmt.bindNull(4)
            stmt.bindLong(5, source.updatedAt); stmt.bindText(6, source.sourceFormat)
            stmt.textOrNull(7, source.sourceRevision); stmt.textOrNull(8, source.sourceOperationId)
            stmt.bindText(9, source.numericPlanEncoding.name); stmt.step()
        } finally { stmt.finalize() }
    }

    private fun readSources(taskId: String): Pair<LegacyTaskDueSnapshot?, Set<LegacyTaskDueSnapshot>> {
        var original: LegacyTaskDueSnapshot? = null
        val pending = mutableSetOf<LegacyTaskDueSnapshot>()
        val stmt = db.prepareStatement("SELECT original,raw_plan,deadline_ms,updated_at,source_format,source_revision,source_operation_id,numeric_plan_encoding FROM TaskDueLegacySourceV1 WHERE task_id = ? ORDER BY source_id")
        try {
            stmt.bindText(1, taskId)
            while (stmt.step() == StepResult.ROW) {
                val source = LegacyTaskDueSnapshot(taskId, stmt.getTextOrNull(1), stmt.getLongOrNull(2), stmt.getLong(3), stmt.getText(4), stmt.getTextOrNull(5), stmt.getTextOrNull(6), LegacyPlanEncoding.valueOf(stmt.getText(7)))
                if (stmt.getInt(0) == 1) original = source else pending.add(source)
            }
        } finally { stmt.finalize() }
        return original to pending
    }

    private fun PreparedStatement.textOrNull(index: Int, value: String?) {
        if (value == null) bindNull(index) else bindText(index, value)
    }
}
