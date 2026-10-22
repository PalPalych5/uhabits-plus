package org.isoron.uhabits.sync

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import org.isoron.platform.io.run
import org.isoron.uhabits.core.containers.session.DatasetSessionFactory
import org.junit.Assert.*
import org.junit.Test

class SyncInboxContextTest {
    private val harness = SyncCoordinatorNextTest()

    @Test
    fun accountSwitchDuringSuspendedPullCannotApplyPreviousAccountEvents() = runBlocking {
        val pullStarted = CompletableDeferred<Unit>()
        val releasePull = CompletableDeferred<Unit>()
        val base = SyncCoordinatorNextTest.InMemorySyncBackend()
        val backend = object : SyncBackend by base {
            override suspend fun signIn(config: SupabaseSyncConfig, email: String, password: String) =
                SyncSession("token-2", "refresh-2", "user-2", email, 9999999999L)

            override suspend fun pullChanges(config: SupabaseSyncConfig, session: SyncSession, lastLogId: Long): SyncPullResult {
                assertEquals("user-1", session.userId)
                pullStarted.complete(Unit)
                releasePull.await()
                return SyncPullResult(listOf(
                    RemoteSyncEvent(1, "old-valid", "user_task", "previous-account-task", "upsert",
                        """{"title":"Previous account","updated_at":100}""", 100, "remote"),
                    RemoteSyncEvent(2, "old-invalid", "user_task", "previous-account-invalid", "upsert",
                        """{"title":"Previous account invalid","planned_date":"broken"}""", 101, "remote")
                ), 2)
            }
        }
        val db = harness.createTestDatabase()
        val env = harness.setupDeviceEnvironment(db, backend, "device")
        val running = async { env.coordinator.runSync(manual = true) }
        try {
            withTimeout(10000) { pullStarted.await() }
            assertTrue(env.coordinator.signIn("second@example.com", "password") is SyncRunResult.Success)
            releasePull.complete(Unit)
            withTimeout(10000) { running.await() }
            assertEquals("user-2", env.modelFactory.syncStateRepository.getAccountUserId())
            assertNull(env.taskRepo.getById(org.isoron.uhabits.core.userTasks.TaskId("previous-account-task")))
            assertEquals(0, env.modelFactory.syncInboxRepository.count())
            assertEquals(0L, env.modelFactory.syncStateRepository.getCursor())
        } finally {
            releasePull.complete(Unit)
            running.cancel()
            env.coordinator.dispose()
            db.close()
        }
    }

    private fun seed(env: SyncCoordinatorNextTest.DeviceEnvironment) {
        env.modelFactory.syncStateRepository.setAccountUserId("user-1")
        env.modelFactory.syncStateRepository.setCursor(42)
        env.db.run("INSERT INTO SyncTombstones VALUES ('user_task', 'deleted-task', 100)")
        env.db.run("""
            INSERT INTO SyncInbox (log_id, device_id, op_uuid, entity_type, entity_uuid,
                operation_type, payload_json, created_at, received_at, deferred_reason)
            VALUES (43, 'remote', 'op-old', 'user_task', 'old-task', 'UPSERT',
                '{"id":"old-task","title":"Old account","updated_at":100}', 100, 101, 'UNSUPPORTED_TASK_PLAN')
        """.trimIndent())
    }

    @Test
    fun signInToDifferentAccountClearsOldDeliveryContext() = runBlocking {
        val base = SyncCoordinatorNextTest.InMemorySyncBackend()
        val backend = object : SyncBackend by base {
            override suspend fun signIn(config: SupabaseSyncConfig, email: String, password: String) =
                SyncSession("token", "refresh", "user-2", email, 9999999999L)
        }
        val db = harness.createTestDatabase()
        val env = harness.setupDeviceEnvironment(db, backend, "device")
        try {
            seed(env)
            assertTrue(env.coordinator.signIn("second@example.com", "password") is SyncRunResult.Success)
            assertEquals("user-2", env.modelFactory.syncStateRepository.getAccountUserId())
            assertEquals(0L, env.modelFactory.syncStateRepository.getCursor())
            assertEquals(0L, env.preferences.syncLastLogId)
            assertEquals(0, env.modelFactory.syncInboxRepository.count())
            assertNull(env.modelFactory.syncTombstoneRepository.getDeletedAt("user_task", "deleted-task"))
            assertEquals(0, env.coordinator.replayInbox(ignoreBackoff = true))
        } finally { env.coordinator.dispose(); db.close() }
    }

    @Test
    fun distinctDatasetDatabaseDoesNotReplayPreviousInbox() = runBlocking {
        val backend = SyncCoordinatorNextTest.InMemorySyncBackend()
        val oldDb = harness.createTestDatabase()
        val newDb = harness.createTestDatabase()
        val old = harness.setupDeviceEnvironment(oldDb, backend, "device", DatasetSessionFactory.productionNext("old"))
        val fresh = harness.setupDeviceEnvironment(newDb, backend, "device", DatasetSessionFactory.productionNext("new"))
        try {
            seed(old)
            old.coordinator.dispose()
            assertEquals(0, fresh.coordinator.replayInbox(ignoreBackoff = true))
            assertEquals(0, fresh.modelFactory.syncInboxRepository.count())
            assertEquals(1, old.modelFactory.syncInboxRepository.count())
            assertNull(fresh.taskRepo.getById(org.isoron.uhabits.core.userTasks.TaskId("old-task")))
            assertNull(fresh.modelFactory.syncStateRepository.getCursor())
        } finally { old.coordinator.dispose(); fresh.coordinator.dispose(); oldDb.close(); newDb.close() }
    }

    @Test
    fun restoreReviewClearsDeliveryStateBeforeSyncResumes() = runBlocking {
        val db = harness.createTestDatabase()
        val env = harness.setupDeviceEnvironment(db, SyncCoordinatorNextTest.InMemorySyncBackend(), "device")
        try {
            seed(env)
            env.coordinator.markRestoreNeedsReview("Restored")
            assertTrue(env.preferences.isSyncReviewRequired)
            assertEquals(0L, env.modelFactory.syncStateRepository.getCursor())
            assertEquals(0, env.modelFactory.syncInboxRepository.count())
            assertNull(env.modelFactory.syncTombstoneRepository.getDeletedAt("user_task", "deleted-task"))
            env.coordinator.confirmSyncReview()
            assertEquals(0, env.coordinator.replayInbox(ignoreBackoff = true))
        } finally { env.coordinator.dispose(); db.close() }
    }
}
