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
import org.isoron.uhabits.core.userTasks.toIsoDateString
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

/** Offline regressions for the existing plannedDate wire format; no production migration. */
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

    private fun rawPlan(env: SyncCoordinatorNextTest.DeviceEnvironment, id: TaskId): String? =
        env.db.querySingle("SELECT planned_date FROM UserTasks WHERE id = ?", id.value) { it.getTextOrNull(0) }

    @Test
    fun pendingLegacyPayloadsAreCanonicalizedOnlyForTransportWithStableIdentityAndAcknowledgments() = runBlocking {
        val delegate = SyncCoordinatorNextTest.InMemorySyncBackend()
        val pushes = mutableListOf<List<SyncQueueRecord>>()
        val backend = object : SyncBackend by delegate {
            override suspend fun pushChanges(config: SupabaseSyncConfig, session: SyncSession,
                deviceId: String, records: List<SyncQueueRecord>): SyncPushResult {
                pushes.add(records.toList())
                // First response acknowledges nothing: retry must retain the same operation identities.
                return SyncPushResult(if (pushes.size == 1) emptyList() else records.mapNotNull { it.queueId })
            }
        }
        val env = harness.setupDeviceEnvironment(database(), backend, "queued-legacy")
        try {
            val dates = listOf(LocalDate(1970, 1, 1), LocalDate(2026, 10, 19))
            val tasks = dates.map { env.taskService.createTask("Queued legacy", plannedDate = it, deadline = 1792429200123L) }
            tasks.forEachIndexed { index, task ->
                val old = taskRecord(env, task.id)
                val raw: Any = if (index == 0) dates[index].unixTime else dates[index].unixTime.toString()
                val legacy = JSONObject(old.payloadJson).put("planned_date", raw).toString()
                assertNull("Old receiver loses this numeric day", org.isoron.uhabits.core.userTasks.parseIsoDateString(raw.toString()))
                env.db.run("UPDATE SyncQueue SET payload_json=? WHERE queue_id=?") {
                    bindText(1, legacy); bindLong(2, old.queueId!!)
                }
            }
            env.coordinator.bootstrapSyncQueue()
            val stored = env.modelFactory.syncQueueRepository.findPending()
            assertTrue(env.coordinator.runSync(manual = true) is SyncRunResult.Success)
            assertEquals("No ACK means no queue mutation", stored, env.modelFactory.syncQueueRepository.findPending())
            val sent = pushes.single()
            assertEquals(stored.map { it.queueId }, sent.map { it.queueId })
            stored.zip(sent).forEach { (original, wire) ->
                assertEquals("Only payload may differ on transport", original, wire.copy(payloadJson = original.payloadJson))
            }
            tasks.forEachIndexed { index, task ->
                val payload = JSONObject(sent.last { it.entityUuid == task.id.value }.payloadJson)
                assertEquals(dates[index].toIsoDateString(), payload.getString("planned_date"))
                assertEquals(dates[index], org.isoron.uhabits.core.userTasks.parseIsoDateString(payload.getString("planned_date")))
                assertEquals(1792429200123L, payload.getLong("deadline"))
            }
            assertTrue(env.coordinator.runSync(manual = true) is SyncRunResult.Success)
            assertEquals(sent, pushes[1])
            assertTrue(env.modelFactory.syncQueueRepository.findPending().isEmpty())
            stored.forEach { original ->
                assertEquals(original.payloadJson, env.db.querySingle("SELECT payload_json FROM SyncQueue WHERE queue_id = ?",
                    original.queueId!!.toString()) { it.getText(0) })
                assertNotNull(env.db.querySingle("SELECT pushed_at FROM SyncQueue WHERE queue_id = ?",
                    original.queueId!!.toString()) { it.getLongOrNull(0) })
            }
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

    private fun cursorFixtureEvents(invalid: Boolean): List<RemoteSyncEvent> {
        fun event(logId: Long, type: String, id: String, payload: JSONObject) = RemoteSyncEvent(
            logId, "cursor-op-$logId", type, id, "upsert", payload.toString(), 100, "foreign-cursor")
        return listOf(
            event(1, "user_task", "blocked-task", JSONObject().put("title", "Remote title")
                .put("planned_date", if (invalid) "1792393200000" else "2026-10-19")),
            event(2, "user_task", "later-task", JSONObject().put("title", "Later task").put("planned_date", "2026-10-20")),
            event(3, "habit", "later-habit", JSONObject().put("name", "Later habit")),
            event(4, "container", "later-container", JSONObject().put("name", "Later container")),
        )
    }

    @Test
    fun malformedIncomingTaskDoesNotPinCursorAndAllowsLaterPages() = runBlocking {
        val backend = PagedTaskBackend(cursorFixtureEvents(invalid = true), pageSize = 1)
        val env = harness.setupDeviceEnvironment(database(), backend, "malformed-cursor")
        try {
            repeat(4) { assertTrue(env.coordinator.runSync(manual = true) is SyncRunResult.Success) }
            assertEquals(listOf(0L, 1L, 2L, 3L), backend.cursors)
            assertEquals(listOf(listOf(1L), listOf(2L), listOf(3L), listOf(4L)), backend.deliveries)
            assertNull(env.taskRepo.getById(TaskId("blocked-task")))
            val inboxRecord = env.modelFactory.syncInboxRepository.findByEntity("user_task", "blocked-task").single()
            assertEquals("BLOCKED_MALFORMED", inboxRecord.status)
            assertEquals("MALFORMED_TASK_PAYLOAD", inboxRecord.deferredReason)
            assertNotNull(env.taskRepo.getById(TaskId("later-task")))
            assertNotNull(env.modelFactory.habitRepository.findByUuid("later-habit"))
            assertNotNull(env.db.querySingle("SELECT name FROM Containers WHERE uuid='later-container'") { it.getText(0) })
        } finally { env.coordinator.dispose(); env.db.close() }
    }

    @Test
    fun deferredTaskAllowsLaterPagesAndIsRetriedAfterRepair() = runBlocking {
        val backend = PagedTaskBackend(cursorFixtureEvents(invalid = false), pageSize = 2)
        val env = harness.setupDeviceEnvironment(database(), backend, "deferred-cursor")
        try {
            env.db.run("INSERT INTO UserTasks(id,title,planned_date,created_at,updated_at) VALUES ('blocked-task','Local title','1792393200000',1,1)")
            // Page 1 (events 1 & 2): blocked-task deferred to inbox, later-task applied, cursor -> 2L
            assertTrue(env.coordinator.runSync(manual = true) is SyncRunResult.Success)
            // Page 2 (events 3 & 4): later-habit and later-container applied, cursor -> 4L
            assertTrue(env.coordinator.runSync(manual = true) is SyncRunResult.Success)
            assertEquals(listOf(0L, 2L), backend.cursors)
            assertNotNull(env.taskRepo.getById(TaskId("later-task")))
            assertNotNull(env.modelFactory.habitRepository.findByUuid("later-habit"))
            assertNotNull(env.db.querySingle("SELECT name FROM Containers WHERE uuid='later-container'") { it.getText(0) })
            assertEquals("1792393200000", rawPlan(env, TaskId("blocked-task")))
            assertEquals(1, env.modelFactory.syncInboxRepository.findByEntity("user_task", "blocked-task").size)

            // Repair task locally
            env.taskService.updateTask(TaskId("blocked-task"), clearPlannedDate = true)
            // Make the deferred event newer than the repair to prove replay, rather than stale-event filtering.
            env.db.run("UPDATE UserTasks SET updated_at=1 WHERE id='blocked-task'")
            assertTrue(env.coordinator.runSync(manual = true) is SyncRunResult.Success)
            assertEquals("Remote title", env.taskRepo.getById(TaskId("blocked-task"))!!.title)
            assertTrue(env.modelFactory.syncInboxRepository.findByEntity("user_task", "blocked-task").isEmpty())
            assertEquals(listOf(0L, 2L, 4L), backend.cursors)
        } finally { env.coordinator.dispose(); env.db.close() }
    }

    @Test
    fun crashBeforeCommitRollsBackDomainAndInboxAndLeavesCursorUnchanged() = runBlocking {
        val events = listOf(
            RemoteSyncEvent(1, "op-1", "user_task", "task-crash", "upsert",
                JSONObject().put("title", "Crash task").put("planned_date", "2026-10-19").toString(), 100, "dev-1")
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
                JSONObject().put("title", "Committed task").put("planned_date", "2026-10-19").toString(), 100, "dev-1"),
            RemoteSyncEvent(2, "op-2", "user_task", "task-next-page", "upsert",
                JSONObject().put("title", "Next page task").put("planned_date", "2026-10-20").toString(), 200, "dev-1")
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
            env.db.run("INSERT INTO UserTasks(id,title,planned_date,created_at,updated_at) VALUES ('victim-task','Local title','1792393200000',10,10)")
            val updateEvent = RemoteSyncEvent(1, "upd-1", "user_task", "victim-task", "upsert",
                JSONObject().put("title", "Remote update").put("planned_date", "2026-10-19").toString(), 50, "foreign")
            assertEquals(1, env.coordinator.applyRemoteEvents(listOf(updateEvent)))
            assertEquals(1, env.modelFactory.syncInboxRepository.findByEntity("user_task", "victim-task").size)

            env.modelFactory.syncManager.enqueueUserTaskDelete("victim-task", deletedAt = 100L)
            env.db.run("DELETE FROM UserTasks WHERE id = 'victim-task'")
            assertTrue(env.modelFactory.syncInboxRepository.findByEntity("user_task", "victim-task").isEmpty())
            assertTrue(env.modelFactory.syncTombstoneRepository.isDeleted("user_task", "victim-task", beforeOrAtTimestamp = 100L))

            val staleEvent = RemoteSyncEvent(2, "upd-2", "user_task", "victim-task", "upsert",
                JSONObject().put("title", "Stale resurrect").put("planned_date", "2026-10-19").toString(), 80, "foreign")
            assertEquals(0, env.coordinator.applyRemoteEvents(listOf(staleEvent)))
            assertNull("Deleted task must not be resurrected", env.taskRepo.getById(TaskId("victim-task")))
            assertTrue("Stale event must not re-enter inbox", env.modelFactory.syncInboxRepository.findByEntity("user_task", "victim-task").isEmpty())
        } finally { env.coordinator.dispose(); env.db.close() }
    }

    @Test
    fun damagedRowIsIsolatedUntilExplicitRepair() = runBlocking {
        val backend = SyncCoordinatorNextTest.InMemorySyncBackend()
        val source = harness.setupDeviceEnvironment(database(), backend, "repair-A")
        val target = harness.setupDeviceEnvironment(database(), backend, "repair-B")
        try {
            val bad = source.taskService.createTask("Damaged", plannedDate = LocalDate(2026, 10, 19), deadline = 1792429200123L)
            val queued = taskRecord(source, bad.id)
            source.db.run("UPDATE UserTasks SET planned_date='1792393200000' WHERE id=?") { bindText(1, bad.id.value) }
            val good = source.taskService.createTask("Healthy", plannedDate = LocalDate(2026, 10, 19))
            source.taskService.updateTask(bad.id, title = "Local edit retained")
            assertEquals(2, source.taskRepo.getAll().size)
            source.coordinator.bootstrapSyncQueue()
            assertEquals("1792393200000", rawPlan(source, bad.id))
            assertTrue(source.coordinator.runSync(manual = true) is SyncRunResult.Success)
            assertTrue(target.coordinator.runSync(manual = true) is SyncRunResult.Success)
            assertNotNull(target.taskRepo.getById(good.id))
            assertNull(target.taskRepo.getById(bad.id))
            val incoming = RemoteSyncEvent(logId = 99, opUuid = "remote-conflict", entityType = "user_task",
                entityUuid = bad.id.value, operationType = "upsert",
                payloadJson = JSONObject(queued.payloadJson).put("planned_date", JSONObject.NULL)
                    .put("updated_at", Long.MAX_VALUE).toString(), createdAt = Long.MAX_VALUE, deviceId = "other")
            assertEquals(1, source.coordinator.applyRemoteEvents(listOf(incoming)))
            assertEquals("1792393200000", rawPlan(source, bad.id))
            assertEquals("Local edit retained", source.taskRepo.getById(bad.id)!!.title)
            source.taskService.updateTask(bad.id, clearPlannedDate = true)
            assertTrue(source.coordinator.runSync(manual = true) is SyncRunResult.Success)
            assertTrue(target.coordinator.runSync(manual = true) is SyncRunResult.Success)
            assertNull(target.taskRepo.getById(bad.id)!!.plannedDate)
            assertEquals(1792429200123L, target.taskRepo.getById(bad.id)!!.deadline)
        } finally {
            source.coordinator.dispose(); target.coordinator.dispose()
            source.db.close(); target.db.close()
        }
    }

    @Test
    fun incrementalRoundTripUsesIsoAndPreservesModelDate() = runBlocking {
        val source = harness.setupDeviceEnvironment(database(), SyncCoordinatorNextTest.InMemorySyncBackend(), "date-A")
        val target = harness.setupDeviceEnvironment(database(), SyncCoordinatorNextTest.InMemorySyncBackend(), "date-B")
        try {
            val date = LocalDate(2026, 10, 19)
            for ((name, plan, deadline) in listOf(
                Triple("plan-only", date, null), Triple("both", date, 1792429200000L),
                Triple("deadline-only", null, 1792429200000L), Triple("undated", null, null),
            )) {
                val task = source.taskService.createTask(name, plannedDate = plan, deadline = deadline)
                val record = taskRecord(source, task.id)
                if (plan != null) {
                    assertEquals("2026-10-19", rawPlan(source, task.id))
                    assertEquals("2026-10-19", JSONObject(record.payloadJson).getString("planned_date"))
                }
                apply(record, target)
                val restored = target.taskRepo.getById(task.id)!!
                assertEquals(task.deadline, restored.deadline)
                assertEquals(task.plannedDate, restored.plannedDate)
                if (plan != null) assertEquals("2026-10-19", rawPlan(target, task.id))
            }
        } finally {
            source.coordinator.dispose(); target.coordinator.dispose()
            source.db.close(); target.db.close()
        }
    }

    @Test
    fun unrelatedTitleEditAndResendPreservePlan() = runBlocking {
        val source = harness.setupDeviceEnvironment(database(), SyncCoordinatorNextTest.InMemorySyncBackend(), "edit-A")
        val target = harness.setupDeviceEnvironment(database(), SyncCoordinatorNextTest.InMemorySyncBackend(), "edit-B")
        try {
            val task = source.taskService.createTask("Before title edit", plannedDate = LocalDate(2026, 10, 19))
            apply(taskRecord(source, task.id), target)
            assertNotNull(rawPlan(target, task.id))
            target.taskService.updateTask(task.id, title = "Unrelated edit")
            assertEquals("2026-10-19", rawPlan(target, task.id))
            apply(taskRecord(target, task.id), source)
            assertEquals(task.plannedDate, source.taskRepo.getById(task.id)!!.plannedDate)
            assertEquals("2026-10-19", rawPlan(source, task.id))
        } finally {
            source.coordinator.dispose(); target.coordinator.dispose()
            source.db.close(); target.db.close()
        }
    }

    @Test
    fun bootstrapRoundTripPreservesIsoPlanAndExactDeadline() = runBlocking {
        val source = harness.setupDeviceEnvironment(database(), SyncCoordinatorNextTest.InMemorySyncBackend(), "bootstrap-A")
        val target = harness.setupDeviceEnvironment(database(), SyncCoordinatorNextTest.InMemorySyncBackend(), "bootstrap-B")
        try {
            val task = org.isoron.uhabits.core.userTasks.UserTask(TaskId("offline-date"), "Offline task",
                plannedDate = LocalDate(2026, 10, 19), deadline = 1792429200123L, createdAt = 1, updatedAt = 1)
            source.taskRepo.save(task) // Offline import: intentionally bypass enqueueUserTask.
            source.coordinator.bootstrapSyncQueue()
            val record = taskRecord(source, task.id)
            assertEquals("2026-10-19", JSONObject(record.payloadJson).getString("planned_date"))
            apply(record, target)
            assertEquals(task, target.taskRepo.getById(task.id))
        } finally {
            source.coordinator.dispose(); target.coordinator.dispose()
            source.db.close(); target.db.close()
        }
    }

    @Test
    fun legacyNumericPayloadsAreCanonicalizedBeforeTitleEditAndResend() = runBlocking {
        val source = harness.setupDeviceEnvironment(database(), SyncCoordinatorNextTest.InMemorySyncBackend(), "legacy-A")
        val target = harness.setupDeviceEnvironment(database(), SyncCoordinatorNextTest.InMemorySyncBackend(), "legacy-B")
        try {
            for (date in listOf(LocalDate(1969, 12, 31), LocalDate(1970, 1, 1), LocalDate(2024, 2, 29), LocalDate(2026, 10, 19))) {
                for (asNumber in listOf(false, true)) {
                    val task = source.taskService.createTask("Legacy payload", plannedDate = date, deadline = 1792429200123L)
                    val canonical = taskRecord(source, task.id)
                    val legacy = canonical.copy(payloadJson = JSONObject(canonical.payloadJson)
                        .put("planned_date", if (asNumber) date.unixTime else date.unixTime.toString()).toString())
                    repeat(2) { apply(legacy, target) }
                    assertEquals(date, target.taskRepo.getById(task.id)!!.plannedDate)
                    target.taskService.updateTask(task.id, title = "Title only")
                    val resent = taskRecord(target, task.id)
                    val iso = date.toIsoDateString()
                    assertEquals(iso, JSONObject(resent.payloadJson).getString("planned_date"))
                    apply(resent, source)
                    assertEquals(date, source.taskRepo.getById(task.id)!!.plannedDate)
                    assertEquals(1792429200123L, source.taskRepo.getById(task.id)!!.deadline)
                    assertEquals(iso, rawPlan(target, task.id))
                }
            }
        } finally {
            source.coordinator.dispose(); target.coordinator.dispose()
            source.db.close(); target.db.close()
        }
    }

    @Test
    fun alreadyStoredNumericPlanBootstrapsAndSurvivesLocalTitleEdit() = runBlocking {
        val source = harness.setupDeviceEnvironment(database(), SyncCoordinatorNextTest.InMemorySyncBackend(), "stored-A")
        val target = harness.setupDeviceEnvironment(database(), SyncCoordinatorNextTest.InMemorySyncBackend(), "stored-B")
        try {
            val task = org.isoron.uhabits.core.userTasks.UserTask(TaskId("stored-numeric"), "Old local row",
                plannedDate = LocalDate(2026, 10, 19), deadline = 1792429200123L, createdAt = 1, updatedAt = 1)
            source.taskRepo.save(task)
            source.db.run("UPDATE UserTasks SET planned_date='1792368000000' WHERE id='stored-numeric'")
            assertEquals(task.plannedDate, source.taskRepo.getById(task.id)!!.plannedDate)
            source.coordinator.bootstrapSyncQueue()
            val record = taskRecord(source, task.id)
            assertEquals("2026-10-19", JSONObject(record.payloadJson).getString("planned_date"))
            assertEquals("1792368000000", rawPlan(source, task.id)) // No write on read/bootstrap.
            apply(record, target)
            assertEquals(task, target.taskRepo.getById(task.id))
            source.taskService.updateTask(task.id, title = "Only title changed")
            assertEquals("2026-10-19", rawPlan(source, task.id))
            apply(taskRecord(source, task.id), target)
            assertEquals(task.plannedDate, target.taskRepo.getById(task.id)!!.plannedDate)
            assertEquals(task.deadline, target.taskRepo.getById(task.id)!!.deadline)
        } finally {
            source.coordinator.dispose(); target.coordinator.dispose()
            source.db.close(); target.db.close()
        }
    }

    @Test
    fun unsupportedLegacyInstantIsRejectedWithoutOverwritingExistingTask() = runBlocking {
        val source = harness.setupDeviceEnvironment(database(), SyncCoordinatorNextTest.InMemorySyncBackend(), "invalid-A")
        val target = harness.setupDeviceEnvironment(database(), SyncCoordinatorNextTest.InMemorySyncBackend(), "invalid-B")
        try {
            val task = source.taskService.createTask("Original", plannedDate = LocalDate(2026, 10, 19))
            val record = taskRecord(source, task.id)
            apply(record, target)
            val invalid = record.copy(payloadJson = JSONObject(record.payloadJson)
                .put("title", "Must not overwrite").put("planned_date", "1792393200000").toString())
            org.junit.Assert.assertThrows(AssertionError::class.java) { apply(invalid, target) }
            assertEquals(task, target.taskRepo.getById(task.id))
            assertEquals("2026-10-19", rawPlan(target, task.id))
            val inboxRecord = target.modelFactory.syncInboxRepository.findByEntity("user_task", task.id.value).single()
            assertEquals("BLOCKED_MALFORMED", inboxRecord.status)
            assertEquals("MALFORMED_TASK_PAYLOAD", inboxRecord.deferredReason)
        } finally {
            source.coordinator.dispose(); target.coordinator.dispose()
            source.db.close(); target.db.close()
        }
    }

    @Test
    fun bootstrapAndReceiveDoNotRewriteLenientHistoricalIsoText() = runBlocking {
        val source = harness.setupDeviceEnvironment(database(), SyncCoordinatorNextTest.InMemorySyncBackend(), "iso-A")
        val target = harness.setupDeviceEnvironment(database(), SyncCoordinatorNextTest.InMemorySyncBackend(), "iso-B")
        try {
            source.db.run("INSERT INTO UserTasks(id,title,planned_date,created_at,updated_at) VALUES ('raw-iso','Historical ISO','2026-02-30',1,1)")
            source.coordinator.bootstrapSyncQueue()
            val record = taskRecord(source, TaskId("raw-iso"))
            assertEquals("2026-02-30", JSONObject(record.payloadJson).getString("planned_date"))
            apply(record, target)
            assertEquals("2026-02-30", rawPlan(source, TaskId("raw-iso")))
            assertEquals("2026-02-30", rawPlan(target, TaskId("raw-iso")))
            // Existing ISO interpretation remains unchanged; strict validation belongs to a separate task.
            assertEquals(LocalDate(2026, 3, 2), target.taskRepo.getById(TaskId("raw-iso"))!!.plannedDate)
        } finally {
            source.coordinator.dispose(); target.coordinator.dispose()
            source.db.close(); target.db.close()
        }
    }

    @Test
    fun fullOfflineTwoDeviceSyncAndExplicitClearPreserveDeadline() = runBlocking {
        val backend = SyncCoordinatorNextTest.InMemorySyncBackend()
        val source = harness.setupDeviceEnvironment(database(), backend, "full-A")
        val target = harness.setupDeviceEnvironment(database(), backend, "full-B")
        try {
            val task = source.taskService.createTask("Full sync", plannedDate = LocalDate(2026, 10, 19), deadline = 1792429200123L)
            assertTrue(source.coordinator.runSync(manual = true) is SyncRunResult.Success)
            assertTrue(target.coordinator.runSync(manual = true) is SyncRunResult.Success)
            assertEquals(task.plannedDate, target.taskRepo.getById(task.id)!!.plannedDate)
            target.taskService.updateTask(task.id, title = "Title on device B")
            assertTrue(target.coordinator.runSync(manual = true) is SyncRunResult.Success)
            assertTrue(source.coordinator.runSync(manual = true) is SyncRunResult.Success)
            assertEquals(task.plannedDate, source.taskRepo.getById(task.id)!!.plannedDate)
            target.taskService.updateTask(task.id, clearPlannedDate = true)
            assertTrue(target.coordinator.runSync(manual = true) is SyncRunResult.Success)
            assertTrue(source.coordinator.runSync(manual = true) is SyncRunResult.Success)
            assertNull(source.taskRepo.getById(task.id)!!.plannedDate)
            assertEquals(task.deadline, source.taskRepo.getById(task.id)!!.deadline)
        } finally {
            source.coordinator.dispose(); target.coordinator.dispose()
            source.db.close(); target.db.close()
        }
    }

    @Test
    fun deadlineMillisecondsAndOverdueBoundaryAreUnchanged() = runBlocking {
        val source = harness.setupDeviceEnvironment(database(), SyncCoordinatorNextTest.InMemorySyncBackend(), "deadline-A")
        val target = harness.setupDeviceEnvironment(database(), SyncCoordinatorNextTest.InMemorySyncBackend(), "deadline-B")
        try {
            val now = 1792411200000L
            for (deadline in listOf(null, -1L, 0L, now - 1, now, now + 1, 1792429200123L)) {
                val task = source.taskService.createTask("Exact deadline", plannedDate = LocalDate(2026, 10, 19), deadline = deadline)
                val record = taskRecord(source, task.id)
                apply(record.copy(payloadJson = JSONObject(record.payloadJson).put("planned_date", "1792368000000").toString()), target)
                val restored = target.taskRepo.getById(task.id)!!
                assertEquals(deadline, restored.deadline)
                assertEquals(deadline != null && deadline < now, restored.isHardOverdue(now))
                assertFalse(restored.copy(status = org.isoron.uhabits.core.userTasks.TaskStatus.COMPLETED).isHardOverdue(now))
            }
        } finally {
            source.coordinator.dispose(); target.coordinator.dispose()
            source.db.close(); target.db.close()
        }
    }

    @Test
    fun knownCompatibilityRisk_legacyUpsertDropsAnUnknownFutureDueColumn() = runBlocking {
        val source = harness.setupDeviceEnvironment(database(), SyncCoordinatorNextTest.InMemorySyncBackend(), "old-A")
        val target = harness.setupDeviceEnvironment(database(), SyncCoordinatorNextTest.InMemorySyncBackend(), "future-B")
        try {
            // A hypothetical future column exists only in this in-memory database.
            target.db.run("ALTER TABLE UserTasks ADD COLUMN proposed_due_v1 TEXT")
            val task = source.taskService.createTask("Legacy title", explicitId = TaskId("version-fixture"))
            apply(taskRecord(source, task.id), target)
            target.db.run("UPDATE UserTasks SET proposed_due_v1 = 'date-only:2026-10-19' WHERE id = 'version-fixture'")
            source.taskService.updateTask(task.id, title = "Old client edits only the title")
            apply(taskRecord(source, task.id), target)
            assertEquals("Old client edits only the title", target.taskRepo.getById(task.id)!!.title)
            assertNull("INSERT OR REPLACE does not preserve unknown future fields",
                target.db.querySingle("SELECT proposed_due_v1 FROM UserTasks WHERE id = 'version-fixture'") { it.getTextOrNull(0) })
        } finally {
            source.coordinator.dispose(); target.coordinator.dispose()
            source.db.close(); target.db.close()
        }
    }

    /** Mandatory regression gate, reproduced red before the PR2.1 production fix. */
    @Test
    fun regressionGate_incrementalRoundTripMustPreservePlannedDate() = runBlocking {
        val source = harness.setupDeviceEnvironment(database(), SyncCoordinatorNextTest.InMemorySyncBackend(), "gate-A")
        val target = harness.setupDeviceEnvironment(database(), SyncCoordinatorNextTest.InMemorySyncBackend(), "gate-B")
        try {
            val task = source.taskService.createTask("Preserve calendar date", plannedDate = LocalDate(2026, 10, 19))
            apply(taskRecord(source, task.id), target)
            assertEquals("plannedDate must survive the real outbound/inbound sync path", task.plannedDate,
                target.taskRepo.getById(task.id)!!.plannedDate)
        } finally {
            source.coordinator.dispose(); target.coordinator.dispose()
            source.db.close(); target.db.close()
        }
    }
}
