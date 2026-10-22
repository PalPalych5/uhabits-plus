/* Copyright (C) 2026 Alinson Santos Xavier and contributors. GPL-3.0-or-later. */
package org.isoron.uhabits.sync

import kotlinx.coroutines.runBlocking
import org.isoron.platform.io.Database
import org.isoron.platform.io.JavaDatabaseOpener
import org.isoron.platform.io.migrateTo
import org.isoron.platform.io.querySingle
import org.isoron.platform.io.run
import org.isoron.platform.io.setVersion
import org.isoron.uhabits.core.DATABASE_VERSION
import org.isoron.uhabits.core.models.sqlite.SQLiteHabitList
import org.isoron.uhabits.core.sync.SyncQueueRecord
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SyncInboxTransactionTest {
    private suspend fun database(): Database {
        val migrations = sequenceOf(File("../uhabits-core/assets/main/migrations"),
            File("uhabits-core/assets/main/migrations")).first { it.isDirectory }
        return JavaDatabaseOpener().open(":memory:").apply {
            setVersion(8)
            migrateTo(DATABASE_VERSION) { File(migrations, "%02d.sql".format(it)).readText() }
        }
    }

    private fun event(logId: Long, type: String, id: String, payload: JSONObject) = RemoteSyncEvent(
        logId, "acceptance-op-$logId", type, id, "upsert", payload.toString(), logId * 100, "foreign"
    )

    private class Backend(val events: List<RemoteSyncEvent>) :
        SyncBackend by SyncCoordinatorNextTest.InMemorySyncBackend() {
        override suspend fun pushChanges(config: SupabaseSyncConfig, session: SyncSession,
            deviceId: String, records: List<SyncQueueRecord>) = SyncPushResult(records.mapNotNull { it.queueId })
        override suspend fun pullChanges(config: SupabaseSyncConfig, session: SyncSession,
            lastLogId: Long) = SyncPullResult(events.filter { it.logId > lastLogId },
                events.lastOrNull()?.logId ?: lastLogId)
    }

    @Test
    fun failedCursorWriteRollsBackEveryHandlerAndEmitsNoHabitNotification() = runBlocking {
        val events = listOf(
            event(1, "habit", "transaction-habit", JSONObject().put("name", "Remote habit")),
            event(2, "habit_goal", "transaction-goal", JSONObject().put("habit_uuid", "transaction-habit")
                .put("target_value", 1).put("target_type", 0).put("freq_num", 1)
                .put("freq_den", 1).put("effective_timestamp", 0)),
            event(3, "entry", "transaction-entry", JSONObject().put("habit_uuid", "transaction-habit")
                .put("entry_date", 0).put("value", 2)),
            event(4, "user_task", "transaction-task", JSONObject().put("title", "Remote task")),
            event(5, "app_setting", "stats", JSONObject().put("key", "global_stats_start_timestamp").put("long_value", 100)),
            event(6, "entry_op", "deferred-op", JSONObject().put("habit_uuid", "absent-habit")
                .put("entry_date", 0).put("delta_value", 5).put("op_type", "ADD")),
            event(7, "habit_block", "transaction-block", JSONObject().put("name", "Remote block")),
            event(8, "container", "transaction-container", JSONObject().put("name", "Remote container")),
            event(9, "habit_placement", "transaction-habit", JSONObject().put("habit_uuid", "transaction-habit")
                .put("container_uuid", "transaction-container")),
            event(10, "focus_session", "transaction-focus", JSONObject().put("started_at", 1)
                .put("ended_at", 11).put("duration_ms", 10)),
            event(11, "contribution", "transaction-contribution", JSONObject().put("target_habit_uuid", "transaction-habit")
                .put("source_id", "transaction-focus").put("contributed_value", 10).put("effective_timestamp", 1)),
            event(12, "entry_op", "transaction-op", JSONObject().put("habit_uuid", "transaction-habit")
                .put("entry_date", 1).put("delta_value", 5).put("op_type", "ADD"))
        )
        val env = SyncCoordinatorNextTest().setupDeviceEnvironment(database(), Backend(events), "transaction-device")
        try {
            env.coordinator.bootstrapSyncQueue()
            val field = SyncCoordinator::class.java.getDeclaredField("habitList").apply { isAccessible = true }
            val habitList = field.get(env.coordinator) as SQLiteHabitList
            val notificationTransactions = mutableListOf<Boolean>()
            habitList.observable.addListener {
                // Bootstrap hydration has its own existing notifications before pull.
                // Track only listeners that can observe this remote batch's writes.
                if (env.modelFactory.habitRepository.findByUuid("transaction-habit") != null) {
                    notificationTransactions.add(env.db.inTransaction())
                }
            }
            env.db.run("""CREATE TRIGGER reject_cursor BEFORE INSERT ON SyncState
                WHEN NEW.key = 'pull_cursor' BEGIN SELECT RAISE(ABORT, 'acceptance cursor failure'); END""")
            assertFalse(env.coordinator.runSync(manual = true) is SyncRunResult.Success)
            assertNull(env.modelFactory.habitRepository.findByUuid("transaction-habit"))
            assertNull(env.modelFactory.habitGoalRepository.findByUuid("transaction-goal"))
            assertNull(env.modelFactory.entryRepository.findByUuid("transaction-entry"))
            assertNull(env.modelFactory.habitBlockRepository.findByUuid("transaction-block"))
            assertNull(env.modelFactory.appSettingRepository.getLong("global_stats_start_timestamp"))
            assertNull(env.modelFactory.entryOpRepository.findByOpUuid("transaction-op"))
            for ((table, column, id) in listOf(
                Triple("Containers", "uuid", "transaction-container"),
                Triple("FocusSessions", "id", "transaction-focus"),
                Triple("Contributions", "id", "transaction-contribution")
            )) assertNull(env.db.querySingle("SELECT $column FROM $table WHERE $column = ?", id) { it.getText(0) })
            assertEquals(0L, env.db.querySingle("SELECT COUNT(*) FROM HabitPlacements") { it.getLong(0) })
            assertNull(env.db.querySingle("SELECT id FROM UserTasks WHERE id='transaction-task'") { it.getText(0) })
            assertEquals(0, env.modelFactory.syncInboxRepository.count())
            assertNull(env.modelFactory.syncStateRepository.getCursor())
            assertEquals(0L, env.preferences.syncLastLogId)
            assertTrue("No listener observes rolled-back batch state", notificationTransactions.isEmpty())
            env.db.run("DROP TRIGGER reject_cursor")
            assertTrue(env.coordinator.runSync(manual = true) is SyncRunResult.Success)
            assertEquals(12L, env.modelFactory.syncStateRepository.getCursor())
            assertNotNull(env.modelFactory.habitRepository.findByUuid("transaction-habit"))
            assertTrue(notificationTransactions.isNotEmpty())
            assertTrue("Notifications are emitted after COMMIT", notificationTransactions.none { it })
        } finally { env.coordinator.dispose(); env.db.close() }
    }

    @Test
    fun missingHabitEntryOperationIsDurableAndRetriesSuspendExactlyAtLimit() = runBlocking {
        val operation = event(1, "entry_op", "delayed-op", JSONObject().put("habit_uuid", "delayed-habit")
            .put("entry_date", 0).put("delta_value", 7).put("op_type", "ADD"))
        val env = SyncCoordinatorNextTest().setupDeviceEnvironment(database(), Backend(listOf(operation)), "entry-device")
        try {
            assertTrue(env.coordinator.runSync(manual = true) is SyncRunResult.Success)
            assertEquals(1L, env.modelFactory.syncStateRepository.getCursor())
            var record = env.modelFactory.syncInboxRepository.findByEntity("entry_op", "delayed-op").single()
            assertEquals(1, record.attempts)
            repeat(3) { index ->
                assertEquals(0, env.coordinator.replayInbox(now = 10000L + index, ignoreBackoff = true))
                record = env.modelFactory.syncInboxRepository.findByEntity("entry_op", "delayed-op").single()
                assertEquals(index + 2, record.attempts)
            }
            env.coordinator.applyRemoteEvents(listOf(event(2, "habit", "delayed-habit", JSONObject().put("name", "Parent"))))
            assertEquals(1, env.coordinator.replayInbox(ignoreBackoff = true))
            assertEquals(0, env.modelFactory.syncInboxRepository.count())
            assertNotNull(env.modelFactory.entryOpRepository.findByOpUuid("delayed-op"))
            assertEquals(7, env.modelFactory.entryRepository.findByUuid("delayed-habit:0")!!.value)
            assertEquals(0, env.coordinator.replayInbox(ignoreBackoff = true))

            val missing = operation.copy(logId = 3, opUuid = "missing-op", entityUuid = "missing-op",
                payloadJson = JSONObject(operation.payloadJson).put("habit_uuid", "never-present").toString())
            assertEquals(1, env.coordinator.applyRemoteEvents(listOf(missing)))
            repeat(5) { env.coordinator.replayInbox(ignoreBackoff = true) }
            record = env.modelFactory.syncInboxRepository.findByEntity("entry_op", "missing-op").single()
            assertEquals(5, record.attempts)
            assertEquals("SUSPENDED", record.status)
            env.coordinator.replayInbox(ignoreBackoff = true)
            assertEquals(record, env.modelFactory.syncInboxRepository.findByEntity("entry_op", "missing-op").single())
        } finally { env.coordinator.dispose(); env.db.close() }
    }

    @Test
    fun unsupportedTaskReplayCountsOneAttemptPerCall() = runBlocking {
        val env = SyncCoordinatorNextTest().setupDeviceEnvironment(database(), Backend(emptyList()), "blocked-device")
        try {
            env.db.run("INSERT INTO UserTasks(id,title,planned_date,created_at,updated_at) VALUES ('blocked','Local','bad-date',1,1)")
            assertEquals(1, env.coordinator.applyRemoteEvents(listOf(event(1, "user_task", "blocked", JSONObject().put("title", "Remote")))))
            repeat(5) { index ->
                env.coordinator.replayInbox(ignoreBackoff = true)
                assertEquals(index + 1, env.modelFactory.syncInboxRepository.findByEntity("user_task", "blocked").single().attempts)
            }
            assertEquals("SUSPENDED", env.modelFactory.syncInboxRepository.findByEntity("user_task", "blocked").single().status)
        } finally { env.coordinator.dispose(); env.db.close() }
    }

    @Test
    fun successfulOlderReplayPreservesNewerMalformedTaskEvidence() = runBlocking {
        val env = SyncCoordinatorNextTest().setupDeviceEnvironment(database(), Backend(emptyList()), "evidence-device")
        try {
            env.db.run("INSERT INTO UserTasks(id,title,planned_date,created_at,updated_at) VALUES ('blocked','Local','bad-date',1,1)")
            val older = event(1, "user_task", "blocked", JSONObject().put("title", "Older valid remote"))
            val malformed = event(2, "user_task", "blocked", JSONObject().put("title", "Newer malformed remote")
                .put("planned_date", "malformed-remote-date"))
            assertEquals(2, env.coordinator.applyRemoteEvents(listOf(older, malformed)))
            val evidence = env.modelFactory.syncInboxRepository.findByEntity("user_task", "blocked")
                .single { it.logId == 2L }
            assertEquals("BLOCKED_MALFORMED", evidence.status)
            env.db.run("UPDATE UserTasks SET planned_date=NULL WHERE id='blocked'")
            assertEquals(1, env.coordinator.replayInbox(ignoreBackoff = true))
            assertEquals(listOf(evidence), env.modelFactory.syncInboxRepository.findByEntity("user_task", "blocked"))
        } finally { env.coordinator.dispose(); env.db.close() }
    }
}
