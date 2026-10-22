/* Copyright (C) 2026 Alinson Santos Xavier and contributors. GPL-3.0-or-later. */
package org.isoron.uhabits.core.userTasks.sqlite

import org.isoron.platform.io.Database
import org.isoron.platform.io.PreparedStatement
import org.isoron.platform.io.querySingle
import org.isoron.platform.io.runInTransaction

object UserTaskSqlWriter {
    fun upsert(db: Database, taskId: String, bind: PreparedStatement.() -> Unit) {
        db.runInTransaction {
            val exists = db.querySingle("SELECT 1 FROM UserTasks WHERE id = ?", taskId) { true } ?: false
            val sql = if (exists) {
                """UPDATE UserTasks SET id=?, title=?, description=?, container_id=?, status=?,
                    due_kind=?, due_date=?, due_time_millis=?, due_zone=?,
                    completed_at=?, parent_task_id=?, sort_order=?,
                    created_at=?, updated_at=?, planned_date=?, legacy_plan_review_pending=? WHERE id=?"""
            } else {
                """INSERT INTO UserTasks (id, title, description, container_id, status,
                    due_kind, due_date, due_time_millis, due_zone,
                    completed_at, parent_task_id, sort_order, created_at, updated_at, planned_date, legacy_plan_review_pending)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"""
            }
            val stmt = db.prepareStatement(sql)
            try {
                stmt.bind()
                if (exists) stmt.bindText(17, taskId)
                stmt.step()
            } finally { stmt.finalize() }
        }
    }
}
