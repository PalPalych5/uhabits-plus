/* Copyright (C) 2026 Alinson Santos Xavier and contributors. GPL-3.0-or-later. */
package org.isoron.uhabits.sync

import kotlinx.coroutines.runBlocking
import org.isoron.platform.io.Database
import org.isoron.platform.io.JavaDatabaseOpener
import org.isoron.platform.io.migrateTo
import org.isoron.platform.io.querySingle
import org.isoron.platform.io.run
import org.isoron.platform.io.setVersion
import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.core.DATABASE_VERSION
import org.isoron.uhabits.core.sync.SyncQueueRecord
import org.isoron.uhabits.core.userTasks.TaskId
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Offline characterization of the current wire contract; no production repair or migration. */
class TaskDateSyncContractTest {
    private val harness = SyncCoordinatorNextTest()

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
    fun knownDefect_incrementalRoundTripStoresUnixTextAndLosesModelDate() = runBlocking {
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
                    assertEquals(plan.unixTime.toString(), JSONObject(record.payloadJson).getString("planned_date"))
                }
                apply(record, target)
                val restored = target.taskRepo.getById(task.id)!!
                assertEquals(task.deadline, restored.deadline)
                assertNull("Known defect: the production ISO reader cannot decode Unix text", restored.plannedDate)
                if (plan != null) assertEquals(plan.unixTime.toString(), rawPlan(target, task.id))
            }
        } finally {
            source.coordinator.dispose(); target.coordinator.dispose()
            source.db.close(); target.db.close()
        }
    }

    @Test
    fun knownDefect_unrelatedTitleEditErasesThePreservedRawPlan() = runBlocking {
        val source = harness.setupDeviceEnvironment(database(), SyncCoordinatorNextTest.InMemorySyncBackend(), "edit-A")
        val target = harness.setupDeviceEnvironment(database(), SyncCoordinatorNextTest.InMemorySyncBackend(), "edit-B")
        try {
            val task = source.taskService.createTask("Before title edit", plannedDate = LocalDate(2026, 10, 19))
            apply(taskRecord(source, task.id), target)
            assertNotNull(rawPlan(target, task.id))
            target.taskService.updateTask(task.id, title = "Unrelated edit")
            assertNull("The decoded null is written back even without an explicit date clear", rawPlan(target, task.id))
            apply(taskRecord(target, task.id), source)
            assertNull(source.taskRepo.getById(task.id)!!.plannedDate)
            assertNull(rawPlan(source, task.id))
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

    /** PR3 correctness gate. This MUST fail on the current production serializer. */
    @Test
    fun regressionGate_incrementalRoundTripMustPreservePlannedDate() = runBlocking {
        org.junit.Assume.assumeTrue("Known production defect; set UHABIT_PR2_ASSERT_FIXED_SYNC=1 to reproduce the red gate",
            System.getenv("UHABIT_PR2_ASSERT_FIXED_SYNC") == "1")
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
