/* Copyright (C) 2026 Alinson Santos Xavier and contributors. GPL-3.0-or-later. */
package org.isoron.uhabits.core.sync

import org.isoron.platform.io.TestDatabaseHelper
import org.junit.Assert.*
import org.junit.Test

import kotlinx.coroutines.runBlocking

class SyncInboxRepositoryTest {

    @Test
    fun localDeleteBeforeCoordinatorCreatesDurableTombstone() = runBlocking {
        val db = TestDatabaseHelper.createEmptyDatabase()
        try {
            val factory = org.isoron.uhabits.core.models.sqlite.SQLModelFactory(db)
            factory.syncManager.enqueueUserTaskDelete("deleted-before-coordinator", 200L)
            assertEquals(200L, factory.syncTombstoneRepository.getDeletedAt("user_task", "deleted-before-coordinator"))
            assertEquals(0, factory.syncInboxRepository.count())
        } finally { db.close() }
    }

    @Test
    fun schemaCreationIsIdempotent() = runBlocking {
        val db = TestDatabaseHelper.createEmptyDatabase()
        try {
            SyncInboxSchema.createSchema(db)
            SyncInboxSchema.createSchema(db) // should not throw
        } finally {
            db.close()
        }
    }

    @Test
    fun syncStateRepositoryTracksCursorAndAccount() = runBlocking {
        val db = TestDatabaseHelper.createEmptyDatabase()
        try {
            SyncInboxSchema.createSchema(db)
            val stateRepo = SyncStateRepository(db)

            assertNull(stateRepo.getCursor())
            assertNull(stateRepo.getAccountUserId())

            stateRepo.setCursor(42L, now = 1000L)
            assertEquals(42L, stateRepo.getCursor())

            stateRepo.setAccountUserId("user-123", now = 1000L)
            assertEquals("user-123", stateRepo.getAccountUserId())

            stateRepo.clear(now = 2000L)
            assertEquals(0L, stateRepo.getCursor())
            assertEquals("user-123", stateRepo.getAccountUserId()) // account is preserved across clear cursor
        } finally {
            db.close()
        }
    }

    @Test
    fun syncTombstoneRepositoryProtectsAgainstResurrection() = runBlocking {
        val db = TestDatabaseHelper.createEmptyDatabase()
        try {
            SyncInboxSchema.createSchema(db)
            val tombstoneRepo = SyncTombstoneRepository(db)

            assertFalse(tombstoneRepo.isDeleted("user_task", "task-1", beforeOrAtTimestamp = 100L))
            assertNull(tombstoneRepo.getDeletedAt("user_task", "task-1"))

            tombstoneRepo.recordTombstone("user_task", "task-1", deletedAt = 200L)
            assertEquals(200L, tombstoneRepo.getDeletedAt("user_task", "task-1"))

            // An older event (timestamp 150 <= 200) is recognized as deleted
            assertTrue(tombstoneRepo.isDeleted("user_task", "task-1", beforeOrAtTimestamp = 150L))
            // Exactly matching timestamp (200 <= 200) is also recognized as deleted
            assertTrue(tombstoneRepo.isDeleted("user_task", "task-1", beforeOrAtTimestamp = 200L))
            // A newer event (timestamp 250 > 200) is NOT recognized as deleted
            assertFalse(tombstoneRepo.isDeleted("user_task", "task-1", beforeOrAtTimestamp = 250L))

            // Different entity type is not affected
            assertFalse(tombstoneRepo.isDeleted("focus_session", "task-1", beforeOrAtTimestamp = 150L))

            tombstoneRepo.clearAll()
            assertFalse(tombstoneRepo.isDeleted("user_task", "task-1", beforeOrAtTimestamp = 150L))
        } finally {
            db.close()
        }
    }

    @Test
    fun syncInboxRepositoryLifecycleAndBackoff() = runBlocking {
        val db = TestDatabaseHelper.createEmptyDatabase()
        try {
            SyncInboxSchema.createSchema(db)
            val inboxRepo = SyncInboxRepository(db)

            val record1 = SyncInboxRecord(
                logId = 1L,
                deviceId = "dev-1",
                opUuid = "op-1",
                entityType = "user_task",
                entityUuid = "task-1",
                operationType = "upsert",
                payloadJson = "{\"title\":\"Task 1\"}",
                createdAt = 100L,
                receivedAt = 105L,
                deferredReason = "MALFORMED_TASK_PAYLOAD",
                status = "BLOCKED_MALFORMED"
            )
            val record2 = SyncInboxRecord(
                logId = 2L,
                deviceId = "dev-1",
                opUuid = "op-2",
                entityType = "user_task",
                entityUuid = "task-2",
                operationType = "upsert",
                payloadJson = "{\"title\":\"Task 2\"}",
                createdAt = 200L,
                receivedAt = 205L,
                deferredReason = "UNSUPPORTED_TASK_PLAN",
                status = "PENDING"
            )

            inboxRepo.insertOrUpdate(record1)
            inboxRepo.insertOrUpdate(record2)

            assertEquals(2, inboxRepo.count())

            // findPending returns only PENDING records, not BLOCKED_MALFORMED
            val pending = inboxRepo.findPending(now = 300L)
            assertEquals(1, pending.size)
            assertEquals("task-2", pending[0].entityUuid)

            // Simulate attempt 1
            inboxRepo.recordAttempt(logId = 2L, now = 300L)
            val afterAttempt1 = inboxRepo.findByEntity("user_task", "task-2").single()
            assertEquals(1, afterAttempt1.attempts)
            assertEquals(300L, afterAttempt1.lastAttemptAt)

            // Immediately checking pending with now = 305L should return empty due to backoff
            // backoff for attempt 1: (1 << 1) * 10000 = 20000ms
            val immediatelyAfter = inboxRepo.findPending(now = 305L, backoffBaseMs = 10_000L)
            assertTrue(immediatelyAfter.isEmpty())

            // After backoff duration expires (now = 300L + 25000L)
            val afterBackoff = inboxRepo.findPending(now = 300L + 25_000L, backoffBaseMs = 10_000L)
            assertEquals(1, afterBackoff.size)

            // Purge for entity removes all records for that entity
            inboxRepo.purgeForEntity("user_task", "task-2")
            assertEquals(1, inboxRepo.count())
            assertTrue(inboxRepo.findByEntity("user_task", "task-2").isEmpty())
            assertEquals(1, inboxRepo.findByEntity("user_task", "task-1").size)

            // Clear all
            inboxRepo.clearAll()
            assertEquals(0, inboxRepo.count())
        } finally {
            db.close()
        }
    }
}
