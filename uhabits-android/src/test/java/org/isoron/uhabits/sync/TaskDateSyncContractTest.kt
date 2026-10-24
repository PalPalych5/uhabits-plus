/* Copyright (C) 2026 Alinson Santos Xavier and contributors. GPL-3.0-or-later. */
package org.isoron.uhabits.sync

import kotlinx.coroutines.runBlocking
import org.isoron.platform.io.Database
import org.isoron.platform.io.JavaDatabaseOpener
import org.isoron.platform.io.migrateTo
import org.isoron.platform.io.querySingle
import org.isoron.platform.io.run
import org.isoron.platform.io.runInTransaction
import org.isoron.platform.io.setVersion
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.setToday
import org.isoron.uhabits.core.DATABASE_VERSION
import org.isoron.uhabits.core.sync.SyncQueueRecord
import org.isoron.uhabits.core.userTasks.TaskId
import org.isoron.uhabits.core.userTasks.UserTask
import org.isoron.uhabits.core.userTasks.TaskStatus
import org.isoron.uhabits.core.userTasks.due.TaskDue
import org.isoron.uhabits.core.userTasks.due.TaskDueDate
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

/** Offline regressions for unified task due sync wire format. */
class TaskDateSyncContractTest {
    private val harness = SyncCoordinatorNextTest()

    @Before
    fun setUp() {
        setToday(LocalDate(2026, 10, 19))
    }

    private suspend fun database(): Database {
        val migrations = sequenceOf(File("../uhabits-core/assets/main/migrations"),
            File("uhabits-core/assets/main/migrations")).first { it.isDirectory }
        return JavaDatabaseOpener().open(":memory:").apply {
            setVersion(8)
            migrateTo(DATABASE_VERSION) { version -> File(migrations, "%02d.sql".format(version)).readText() }
        }
    }

    private fun apply(record: SyncQueueRecord, target: SyncCoordinatorNextTest.DeviceEnvironment) {
        assertEquals("No event should be skipped", 0, target.coordinator.applyRemoteEvents(listOf(RemoteSyncEvent(
            logId = 1, opUuid = record.opUuid, entityType = record.entityType,
            entityUuid = record.entityUuid, operationType = record.operationType,
            payloadJson = record.payloadJson, createdAt = record.createdAt, deviceId = record.deviceId,
        ))))
    }

    private fun taskRecord(env: SyncCoordinatorNextTest.DeviceEnvironment, id: TaskId) =
        env.modelFactory.syncQueueRepository.findPending().last { it.entityType == "user_task" && it.entityUuid == id.value }

    private fun rawDue(env: SyncCoordinatorNextTest.DeviceEnvironment, id: TaskId): Pair<String, String?> =
        env.db.querySingle("SELECT due_kind, due_date FROM UserTasks WHERE id = ?", id.value) {
            it.getText(0) to it.getTextOrNull(1)
        }!!

    @Test
    fun monotonicCompletionUndoReorderAndDeleteSurviveClockRollback() = runBlocking {
        val source = harness.setupDeviceEnvironment(database(), SyncCoordinatorNextTest.InMemorySyncBackend(), "undo-source")
        val target = harness.setupDeviceEnvironment(database(), SyncCoordinatorNextTest.InMemorySyncBackend(), "undo-target")
        try {
            val queue = org.isoron.uhabits.core.sync.SyncQueueRepository(source.db)
            val sync = org.isoron.uhabits.core.sync.SyncManager(queue,
                org.isoron.uhabits.core.sync.EntryOpRepository(source.db), { "undo-source" }, { 1000L })
            val service = org.isoron.uhabits.core.userTasks.UserTaskServiceImpl(source.taskRepo, { 1000L }, { "clock-rollback" }, sync)
            val before = service.createTask("Clock rollback")
            apply(queue.findPending().last(), target)
            val completed = service.setCompleted(before.id, true)
            apply(queue.findPending().last(), target)
            assertTrue(target.taskRepo.getById(before.id)!!.isCompleted)
            val restored = service.restoreTaskSnapshot(completed, before)!!
            assertEquals(restored.updatedAt, queue.findPending().last().createdAt)
            apply(queue.findPending().last(), target)
            assertEquals(restored, target.taskRepo.getById(before.id))
            service.reorderTasks(listOf(TaskId("missing"), before.id))
            apply(queue.findPending().last(), target)
            assertEquals(1, target.taskRepo.getById(before.id)!!.sortOrder)
            service.deleteTask(before.id, cascadeSubtasks = true)
            apply(queue.findPending().last(), target)
            assertNull(target.taskRepo.getById(before.id))
        } finally {
            source.coordinator.dispose(); source.db.close()
            target.coordinator.dispose(); target.db.close()
        }
    }

    @Test
    fun rawHistoryAndReviewDecisionRoundTripOfflineWithoutResurfacing() = runBlocking {
        val backend = SyncCoordinatorNextTest.InMemorySyncBackend()
        val source = harness.setupDeviceEnvironment(database(), backend, "review-source")
        val target = harness.setupDeviceEnvironment(database(), backend, "review-target")
        try {
            val original = UserTask(
                id = TaskId("raw-review"), title = "Raw review", due = TaskDue.None,
                legacyPlanDate = "not-a-date", legacyPlanReviewPending = true,
                createdAt = 100L, updatedAt = 100L,
            )
            source.modelFactory.syncManager.enqueueUserTask(original)
            apply(taskRecord(source, original.id), target)
            val pending = target.taskRepo.getById(original.id)!!
            assertEquals("not-a-date", pending.legacyPlanDate)
            assertTrue(pending.hasPendingLegacyPlan)

            val reviewed = original.copy(legacyPlanReviewPending = false, updatedAt = 200L)
            source.modelFactory.syncManager.enqueueUserTask(reviewed)
            apply(taskRecord(source, original.id), target)
            val accepted = target.taskRepo.getById(original.id)!!
            assertEquals("not-a-date", accepted.legacyPlanDate)
            assertFalse(accepted.hasPendingLegacyPlan)
            assertFalse(accepted.legacyPlanReviewPending)
        } finally {
            source.coordinator.dispose(); source.db.close()
            target.coordinator.dispose(); target.db.close()
        }
    }

    @Test
    fun legacyPayloadWithoutReviewFieldsPreservesReviewedHistory() = runBlocking {
        val target = harness.setupDeviceEnvironment(database(), SyncCoordinatorNextTest.InMemorySyncBackend(), "legacy-review")
        try {
            val original = UserTask(
                id = TaskId("reviewed-history"), title = "Reviewed", due = TaskDue.None,
                legacyPlanDate = "2026-10-19", legacyPlanReviewPending = false,
                createdAt = 100L, updatedAt = 100L,
            )
            target.taskRepo.save(original)
            val skipped = target.coordinator.applyRemoteEvents(listOf(RemoteSyncEvent(
                logId = 1L, opUuid = "legacy-no-review-fields", entityType = "user_task",
                entityUuid = original.id.value, operationType = "upsert",
                payloadJson = JSONObject().put("title", "Legacy edit").put("due_kind", "NONE").toString(),
                createdAt = 200L, deviceId = "foreign-legacy",
            )))
            assertEquals(0, skipped)
            val actual = target.taskRepo.getById(original.id)!!
            assertEquals("Legacy edit", actual.title)
            assertEquals("2026-10-19", actual.legacyPlanDate)
            assertFalse(actual.legacyPlanReviewPending)
            assertFalse(actual.hasPendingLegacyPlan)
        } finally { target.coordinator.dispose(); target.db.close() }
    }

    @Test
    fun pendingDuePayloadsPreserveIdentityAndQueueSafeguards() = runBlocking {
        val delegate = SyncCoordinatorNextTest.InMemorySyncBackend()
        val pushes = mutableListOf<List<SyncQueueRecord>>()
        val backend = object : SyncBackend by delegate {
            override suspend fun pushChanges(config: SupabaseSyncConfig, session: SyncSession,
                deviceId: String, records: List<SyncQueueRecord>): SyncPushResult {
                pushes.add(records.toList())
                return SyncPushResult(if (pushes.size == 1) emptyList() else records.mapNotNull { it.queueId })
            }
        }
        val env = harness.setupDeviceEnvironment(database(), backend, "queued-due")
        try {
            val tasks = listOf(
                env.taskService.createTask("Undated", due = TaskDue.None),
                env.taskService.createTask("Day task", due = TaskDue.Day(TaskDueDate.from(LocalDate(2026, 10, 19)))),
                env.taskService.createTask("Timed task", due = TaskDue.Timed.of(1792429200123L))
            )
            val stored = env.modelFactory.syncQueueRepository.findPending()
            assertEquals(3, stored.size)

            // Verify payload format
            val p0 = JSONObject(taskRecord(env, tasks[0].id).payloadJson)
            assertEquals("NONE", p0.getString("due_kind"))
            assertTrue(p0.isNull("due_date"))

            val p1 = JSONObject(taskRecord(env, tasks[1].id).payloadJson)
            assertEquals("DAY", p1.getString("due_kind"))
            assertEquals("2026-10-19", p1.getString("due_date"))

            val p2 = JSONObject(taskRecord(env, tasks[2].id).payloadJson)
            assertEquals("TIMED", p2.getString("due_kind"))
            assertEquals(1792429200123L, p2.getLong("due_time_millis"))

            assertTrue(env.coordinator.runSync(manual = true) is SyncRunResult.Success)
            assertEquals("No ACK means no queue mutation", stored, env.modelFactory.syncQueueRepository.findPending())

            val sent = pushes.single()
            assertEquals(stored.map { it.queueId }, sent.map { it.queueId })

            // Second push acknowledges all
            assertTrue(env.coordinator.runSync(manual = true) is SyncRunResult.Success)
            assertEquals(sent, pushes[1])
            assertTrue(env.modelFactory.syncQueueRepository.findPending().isEmpty())
        } finally { env.coordinator.dispose(); env.db.close() }
    }

    private class PagedTaskBackend(val events: List<RemoteSyncEvent>, val pageSize: Int) :
        SyncBackend by SyncCoordinatorNextTest.InMemorySyncBackend() {
        val cursors = mutableListOf<Long>()
        val deliveries = mutableListOf<List<Long>>()
        override suspend fun pushChanges(config: SupabaseSyncConfig, session: SyncSession,
            deviceId: String, records: List<SyncQueueRecord>) = SyncPushResult(records.mapNotNull { it.queueId })
        override suspend fun pullChanges(config: SupabaseSyncConfig, session: SyncSession, lastLogId: Long): SyncPullResult {
            cursors.add(lastLogId)
            val page = events.filter { it.logId > lastLogId }.take(pageSize)
            deliveries.add(page.map { it.logId })
            return SyncPullResult(page, page.lastOrNull()?.logId ?: lastLogId)
        }
    }

    private fun cursorFixtureEvents(): List<RemoteSyncEvent> {
        fun event(logId: Long, type: String, id: String, payload: JSONObject) = RemoteSyncEvent(
            logId, "cursor-op-$logId", type, id, "upsert", payload.toString(), 100, "foreign-cursor")
        return listOf(
            event(1, "user_task", "first-task", JSONObject().put("title", "First task")
                .put("due_kind", "DAY").put("due_date", "2026-10-19")),
            event(2, "user_task", "second-task", JSONObject().put("title", "Second task").put("due_kind", "NONE")),
            event(3, "habit", "later-habit", JSONObject().put("name", "Later habit")),
            event(4, "container", "later-container", JSONObject().put("name", "Later container")),
        )
    }

    @Test
    fun pagedTaskDeliveryAdvancesCursorAndAppliesDomainState() = runBlocking {
        val backend = PagedTaskBackend(cursorFixtureEvents(), pageSize = 2)
        val env = harness.setupDeviceEnvironment(database(), backend, "paged-cursor")
        try {
            assertTrue(env.coordinator.runSync(manual = true) is SyncRunResult.Success)
            assertEquals(listOf(0L), backend.cursors)
            assertEquals(2L, env.modelFactory.syncStateRepository.getCursor())
            assertNotNull(env.taskRepo.getById(TaskId("first-task")))
            assertNotNull(env.taskRepo.getById(TaskId("second-task")))

            assertTrue(env.coordinator.runSync(manual = true) is SyncRunResult.Success)
            assertEquals(listOf(0L, 2L), backend.cursors)
            assertEquals(4L, env.modelFactory.syncStateRepository.getCursor())
            assertNotNull(env.modelFactory.habitRepository.findByUuid("later-habit"))
            assertNotNull(env.db.querySingle("SELECT name FROM Containers WHERE uuid='later-container'") { it.getText(0) })
        } finally { env.coordinator.dispose(); env.db.close() }
    }

    @Test
    fun crashBeforeCommitRollsBackDomainAndInboxAndLeavesCursorUnchanged() = runBlocking {
        val events = listOf(
            RemoteSyncEvent(1, "op-1", "user_task", "task-crash", "upsert",
                JSONObject().put("title", "Crash task").put("due_kind", "DAY").put("due_date", "2026-10-19").toString(), 100, "dev-1")
        )
        val backend = PagedTaskBackend(events, pageSize = 1)
        val env = harness.setupDeviceEnvironment(database(), backend, "crash-before-commit")
        try {
            val cursorBefore = env.modelFactory.syncStateRepository.getCursor()
            try {
                env.db.runInTransaction {
                    env.coordinator.applyRemoteEvents(events)
                    env.modelFactory.syncStateRepository.setCursor(1L)
                    throw RuntimeException("Simulated crash before COMMIT")
                }
            } catch (_: RuntimeException) {}

            assertEquals(cursorBefore, env.modelFactory.syncStateRepository.getCursor())
            assertNull(env.taskRepo.getById(TaskId("task-crash")))
            assertTrue(env.modelFactory.syncInboxRepository.findByEntity("user_task", "task-crash").isEmpty())

            assertTrue(env.coordinator.runSync(manual = true) is SyncRunResult.Success)
            assertEquals(1L, env.modelFactory.syncStateRepository.getCursor())
            assertNotNull(env.taskRepo.getById(TaskId("task-crash")))
        } finally { env.coordinator.dispose(); env.db.close() }
    }

    @Test
    fun crashAfterCommitPreservesDurableSqliteCursorAndReconcilesPreferences() = runBlocking {
        val events = listOf(
            RemoteSyncEvent(1, "op-1", "user_task", "task-after-crash", "upsert",
                JSONObject().put("title", "Committed task").put("due_kind", "DAY").put("due_date", "2026-10-19").toString(), 100, "dev-1"),
            RemoteSyncEvent(2, "op-2", "user_task", "task-next-page", "upsert",
                JSONObject().put("title", "Next page task").put("due_kind", "NONE").toString(), 200, "dev-1")
        )
        val backend = PagedTaskBackend(events, pageSize = 1)
        val env = harness.setupDeviceEnvironment(database(), backend, "crash-after-commit")
        try {
            assertTrue(env.coordinator.runSync(manual = true) is SyncRunResult.Success)
            assertEquals(1L, env.modelFactory.syncStateRepository.getCursor())
            assertEquals(1L, env.preferences.syncLastLogId)

            // Simulate crash where Preferences was wiped/reverted to 0
            env.preferences.syncLastLogId = 0L

            assertTrue(env.coordinator.runSync(manual = true) is SyncRunResult.Success)
            assertEquals(listOf(0L, 1L), backend.cursors)
            assertEquals(2L, env.modelFactory.syncStateRepository.getCursor())
            assertEquals(2L, env.preferences.syncLastLogId)
            assertNotNull(env.taskRepo.getById(TaskId("task-next-page")))
        } finally { env.coordinator.dispose(); env.db.close() }
    }

    @Test
    fun deletePurgesInboxAndTombstoneBlocksResurrection() = runBlocking {
        val backend = SyncCoordinatorNextTest.InMemorySyncBackend()
        val env = harness.setupDeviceEnvironment(database(), backend, "tombstone-test")
        try {
            env.db.run("INSERT INTO UserTasks(id,title,due_kind,due_date,created_at,updated_at) VALUES ('victim-task','Local title','DAY','2026-10-19',10,10)")
            env.modelFactory.syncManager.enqueueUserTaskDelete("victim-task", deletedAt = 100L)
            env.db.run("DELETE FROM UserTasks WHERE id = 'victim-task'")
            assertTrue(env.modelFactory.syncInboxRepository.findByEntity("user_task", "victim-task").isEmpty())
            assertTrue(env.modelFactory.syncTombstoneRepository.isDeleted("user_task", "victim-task", beforeOrAtTimestamp = 100L))

            val staleEvent = RemoteSyncEvent(2, "upd-2", "user_task", "victim-task", "upsert",
                JSONObject().put("title", "Stale resurrect").put("due_kind", "DAY").put("due_date", "2026-10-19").toString(), 80, "foreign")
            assertEquals(0, env.coordinator.applyRemoteEvents(listOf(staleEvent)))
            assertNull("Deleted task must not be resurrected", env.taskRepo.getById(TaskId("victim-task")))
            assertTrue("Stale event must not re-enter inbox", env.modelFactory.syncInboxRepository.findByEntity("user_task", "victim-task").isEmpty())
        } finally { env.coordinator.dispose(); env.db.close() }
    }

    @Test
    fun incrementalRoundTripPreservesDueContract() = runBlocking {
        val source = harness.setupDeviceEnvironment(database(), SyncCoordinatorNextTest.InMemorySyncBackend(), "due-A")
        val target = harness.setupDeviceEnvironment(database(), SyncCoordinatorNextTest.InMemorySyncBackend(), "due-B")
        try {
            val date = LocalDate(2026, 10, 19)
            val cases = listOf(
                "none" to TaskDue.None,
                "day" to TaskDue.Day(TaskDueDate.from(date)),
                "timed" to TaskDue.Timed.of(1792429200000L)
            )
            for ((name, expectedDue) in cases) {
                val task = source.taskService.createTask(name, due = expectedDue)
                val record = taskRecord(source, task.id)
                apply(record, target)
                val restored = target.taskRepo.getById(task.id)!!
                assertEquals(expectedDue, restored.due)
            }
        } finally {
            source.coordinator.dispose(); target.coordinator.dispose()
            source.db.close(); target.db.close()
        }
    }

    @Test
    fun unrelatedTitleEditAndResendPreserveDue() = runBlocking {
        val source = harness.setupDeviceEnvironment(database(), SyncCoordinatorNextTest.InMemorySyncBackend(), "edit-A")
        val target = harness.setupDeviceEnvironment(database(), SyncCoordinatorNextTest.InMemorySyncBackend(), "edit-B")
        try {
            val expectedDue = TaskDue.Day(TaskDueDate.from(LocalDate(2026, 10, 19)))
            val task = source.taskService.createTask("Before title edit", due = expectedDue)
            apply(taskRecord(source, task.id), target)
            assertEquals("DAY" to "2026-10-19", rawDue(target, task.id))

            target.taskService.updateTask(task.id, title = "Unrelated edit")
            assertEquals("DAY" to "2026-10-19", rawDue(target, task.id))
            apply(taskRecord(target, task.id), source)
            assertEquals(expectedDue, source.taskRepo.getById(task.id)!!.due)
            assertEquals("DAY" to "2026-10-19", rawDue(source, task.id))
        } finally {
            source.coordinator.dispose(); target.coordinator.dispose()
            source.db.close(); target.db.close()
        }
    }

    @Test
    fun bootstrapRoundTripPreservesDue() = runBlocking {
        val source = harness.setupDeviceEnvironment(database(), SyncCoordinatorNextTest.InMemorySyncBackend(), "bootstrap-A")
        val target = harness.setupDeviceEnvironment(database(), SyncCoordinatorNextTest.InMemorySyncBackend(), "bootstrap-B")
        try {
            val task = UserTask(TaskId("offline-date"), "Offline task",
                due = TaskDue.Day(TaskDueDate.from(LocalDate(2026, 10, 19))), createdAt = 1, updatedAt = 1)
            source.taskRepo.save(task)
            source.coordinator.bootstrapSyncQueue()
            val record = taskRecord(source, task.id)
            val p = JSONObject(record.payloadJson)
            assertEquals("DAY", p.getString("due_kind"))
            assertEquals("2026-10-19", p.getString("due_date"))
            apply(record, target)
            assertEquals(task, target.taskRepo.getById(task.id))
        } finally {
            source.coordinator.dispose(); target.coordinator.dispose()
            source.db.close(); target.db.close()
        }
    }

    @Test
    fun legacyDevFallbackParsesPlannedDateAndDeadline() = runBlocking {
        val target = harness.setupDeviceEnvironment(database(), SyncCoordinatorNextTest.InMemorySyncBackend(), "fallback-dev")
        try {
            // 1. Old planned_date only -> DAY
            val eventPlan = RemoteSyncEvent(1, "op-plan", "user_task", "dev-task-plan", "upsert",
                JSONObject().put("title", "Legacy planned").put("planned_date", "2026-10-19").toString(), 100, "dev-old")
            target.coordinator.applyRemoteEvents(listOf(eventPlan))
            val taskPlan = target.taskRepo.getById(TaskId("dev-task-plan"))!!
            assertEquals(TaskDue.None, taskPlan.due)
            org.junit.Assert.assertTrue(taskPlan.hasPendingLegacyPlan)
            assertEquals("2026-10-19", taskPlan.legacyPlanDate)

            // 2. Old deadline only -> TIMED
            val eventDeadline = RemoteSyncEvent(2, "op-dl", "user_task", "dev-task-dl", "upsert",
                JSONObject().put("title", "Legacy deadline").put("deadline", 1792429200123L).toString(), 200, "dev-old")
            target.coordinator.applyRemoteEvents(listOf(eventDeadline))
            val taskDeadline = target.taskRepo.getById(TaskId("dev-task-dl"))!!
            val timed = taskDeadline.due as TaskDue.Timed
            assertEquals(1792429200123L, timed.epochMillis)

            // 3. Neither -> NONE
            val eventNone = RemoteSyncEvent(3, "op-none", "user_task", "dev-task-none", "upsert",
                JSONObject().put("title", "Legacy plain").toString(), 300, "dev-old")
            target.coordinator.applyRemoteEvents(listOf(eventNone))
            val taskNone = target.taskRepo.getById(TaskId("dev-task-none"))!!
            assertEquals(TaskDue.None, taskNone.due)
        } finally { target.coordinator.dispose(); target.db.close() }
    }

    @Test
    fun timedDueOverdueBoundary() = runBlocking {
        val now = 1792411200000L
        val today = LocalDate(2026, 10, 19)
        for (millis in listOf(-1L, 0L, now - 1, now, now + 1, 1792429200123L)) {
            val task = UserTask(TaskId("t"), "Boundary", due = TaskDue.Timed.of(millis), createdAt = 1, updatedAt = 1)
            assertEquals(millis < now, task.isOverdue(today, now))
            assertFalse(task.copy(status = TaskStatus.COMPLETED).isOverdue(today, now))
        }
    }

    @Test
    fun regressionGate_incrementalRoundTripMustPreserveDue() = runBlocking {
        val source = harness.setupDeviceEnvironment(database(), SyncCoordinatorNextTest.InMemorySyncBackend(), "gate-A")
        val target = harness.setupDeviceEnvironment(database(), SyncCoordinatorNextTest.InMemorySyncBackend(), "gate-B")
        try {
            val expectedDue = TaskDue.Day(TaskDueDate.from(LocalDate(2026, 10, 19)))
            val task = source.taskService.createTask("Preserve due date", due = expectedDue)
            apply(taskRecord(source, task.id), target)
            assertEquals("TaskDue must survive the real outbound/inbound sync path", expectedDue,
                target.taskRepo.getById(task.id)!!.due)
        } finally {
            source.coordinator.dispose(); target.coordinator.dispose()
            source.db.close(); target.db.close()
        }
    }
}
