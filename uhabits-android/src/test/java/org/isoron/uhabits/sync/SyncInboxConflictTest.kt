/* Copyright (C) 2026 Alinson Santos Xavier and contributors. GPL-3.0-or-later. */
package org.isoron.uhabits.sync

import kotlinx.coroutines.runBlocking
import org.isoron.platform.io.run
import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.core.userTasks.TaskId
import org.junit.Assert.*
import org.junit.Test

class SyncInboxConflictTest {
    @Test
    fun localDateRepairSurvivesOlderDeferredRemoteUpdate() = runBlocking {
        val harness = SyncCoordinatorNextTest()
        val env = harness.setupDeviceEnvironment(harness.createTestDatabase(),
            SyncCoordinatorNextTest.InMemorySyncBackend(), "local-repair")
        try {
            env.db.run("INSERT INTO UserTasks(id,title,planned_date,created_at,updated_at) VALUES ('repair','Local','1792393200000',1,1)")
            val remote = RemoteSyncEvent(1, "deferred-repair", "user_task", "repair", "upsert",
                """{"title":"Old remote","planned_date":"2026-10-19"}""", 100, "foreign")
            assertEquals(1, env.coordinator.applyRemoteEvents(listOf(remote)))
            assertEquals(1, env.modelFactory.syncInboxRepository.count())
            val repaired = env.taskService.updateTask(TaskId("repair"), title = "Repaired locally",
                plannedDate = LocalDate(2026, 10, 22))
            assertTrue(repaired.updatedAt > remote.createdAt)
            assertEquals(1, env.coordinator.replayInbox(ignoreBackoff = true))
            assertEquals(repaired, env.taskRepo.getById(repaired.id))
            assertEquals(0, env.modelFactory.syncInboxRepository.count())
            assertTrue(env.modelFactory.syncQueueRepository.findPending().any { it.entityUuid == "repair" })
        } finally { env.coordinator.dispose(); env.db.close() }
    }

    @Test
    fun newerDeferredRemoteUpdateUsesExistingLastWriterWinsAfterRepair() = runBlocking {
        val harness = SyncCoordinatorNextTest()
        val env = harness.setupDeviceEnvironment(harness.createTestDatabase(),
            SyncCoordinatorNextTest.InMemorySyncBackend(), "newer-remote")
        try {
            env.db.run("INSERT INTO UserTasks(id,title,planned_date,created_at,updated_at) VALUES ('repair','Local','1792393200000',1,1)")
            val future = System.currentTimeMillis() + 60_000
            val remote = RemoteSyncEvent(1, "future-repair", "user_task", "repair", "upsert",
                """{"title":"Newer remote","planned_date":"2026-10-23"}""", future, "foreign")
            assertEquals(1, env.coordinator.applyRemoteEvents(listOf(remote)))
            val repaired = env.taskService.updateTask(TaskId("repair"), clearPlannedDate = true)
            assertTrue(repaired.updatedAt < future)
            assertEquals(1, env.coordinator.replayInbox(ignoreBackoff = true))
            val result = env.taskRepo.getById(TaskId("repair"))!!
            assertEquals("Newer remote", result.title)
            assertEquals(LocalDate(2026, 10, 23), result.plannedDate)
            assertEquals(0, env.modelFactory.syncInboxRepository.count())
        } finally { env.coordinator.dispose(); env.db.close() }
    }
}
