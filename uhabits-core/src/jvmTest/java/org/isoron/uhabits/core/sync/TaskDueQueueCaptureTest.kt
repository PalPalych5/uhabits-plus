/* Copyright (C) 2026 Alinson Santos Xavier and contributors. GPL-3.0-or-later. */
package org.isoron.uhabits.core.sync

import kotlinx.coroutines.runBlocking
import org.isoron.platform.io.TestDatabaseHelper
import org.isoron.uhabits.core.models.sqlite.SQLModelFactory
import org.isoron.uhabits.core.userTasks.TaskId
import org.isoron.uhabits.core.userTasks.UserTask
import org.isoron.uhabits.core.userTasks.due.TaskDue
import org.junit.Assert.*
import org.junit.Test

/** Local queue capture only: this does not prove remote delivery or application. */
class TaskDueQueueCaptureTest {
    @Test fun capturePreservesUnknownRawAndExplicitReviewedStateWithTimedZone() = runBlocking {
        val db = TestDatabaseHelper.createEmptyDatabase()
        try {
            val factory = SQLModelFactory(db)
            val task = UserTask(
                id = TaskId("due-capture"), title = "Reviewed task",
                due = TaskDue.Timed.of(1792432800000L, "Asia/Novosibirsk"),
                legacyPlanDate = "not-a-date", legacyPlanReviewPending = false,
                createdAt = 1L, updatedAt = 2L,
            )
            factory.syncManager.enqueueUserTask(task)
            val captured = factory.syncQueueRepository.findPending().single { it.entityUuid == task.id.value }
            assertTrue(captured.payloadJson.contains("\"legacy_plan_raw\":\"not-a-date\""))
            assertTrue(captured.payloadJson.contains("\"legacy_plan_review_pending\":\"0\""))
            assertTrue(captured.payloadJson.contains("\"due_zone\":\"Asia/Novosibirsk\""))
            assertTrue(captured.payloadJson.contains("1792432800000"))
        } finally { db.close() }
    }
}
