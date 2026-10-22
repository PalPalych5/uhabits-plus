/* Copyright (C) 2026 Alinson Santos Xavier and contributors. GPL-3.0-or-later. */
package org.isoron.uhabits.sync

import kotlinx.coroutines.runBlocking
import org.isoron.platform.io.run
import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.core.userTasks.TaskId
import org.isoron.uhabits.core.userTasks.due.TaskDue
import org.isoron.uhabits.core.userTasks.due.TaskDueDate
import org.junit.Assert.*
import org.junit.Test

class SyncInboxConflictTest {
    @Test
    fun localUpdateNewerThanRemoteIgnoresStaleRemote() = runBlocking {
        val harness = SyncCoordinatorNextTest()
        val env = harness.setupDeviceEnvironment(harness.createTestDatabase(),
            SyncCoordinatorNextTest.InMemorySyncBackend(), "local-newer")
        try {
            // Local task updated at t=200
            env.db.run("INSERT INTO UserTasks(id,title,due_kind,due_date,created_at,updated_at) VALUES ('conflict','Local newer','DAY','2026-10-22',100,200)")
            // Stale remote event created at t=100
            val remote = RemoteSyncEvent(1, "stale-remote", "user_task", "conflict", "upsert",
                """{"title":"Old remote","due_kind":"DAY","due_date":"2026-10-19"}""", 100, "foreign")
            assertEquals(0, env.coordinator.applyRemoteEvents(listOf(remote)))
            val local = env.taskRepo.getById(TaskId("conflict"))!!
            assertEquals("Local newer", local.title)
            assertEquals(TaskDue.Day(TaskDueDate.from(LocalDate(2026, 10, 22))), local.due)
        } finally { env.coordinator.dispose(); env.db.close() }
    }

    @Test
    fun newerDeferredRemoteUpdateUsesExistingLastWriterWins() = runBlocking {
        val harness = SyncCoordinatorNextTest()
        val env = harness.setupDeviceEnvironment(harness.createTestDatabase(),
            SyncCoordinatorNextTest.InMemorySyncBackend(), "remote-newer")
        try {
            // Local task updated at t=100
            env.db.run("INSERT INTO UserTasks(id,title,due_kind,due_date,created_at,updated_at) VALUES ('conflict','Local older','DAY','2026-10-19',50,100)")
            // Newer remote event created at t=200
            val remote = RemoteSyncEvent(1, "newer-remote", "user_task", "conflict", "upsert",
                """{"title":"Newer remote","due_kind":"DAY","due_date":"2026-10-23"}""", 200, "foreign")
            assertEquals(0, env.coordinator.applyRemoteEvents(listOf(remote)))
            val result = env.taskRepo.getById(TaskId("conflict"))!!
            assertEquals("Newer remote", result.title)
            assertEquals(TaskDue.Day(TaskDueDate.from(LocalDate(2026, 10, 23))), result.due)
        } finally { env.coordinator.dispose(); env.db.close() }
    }
}
