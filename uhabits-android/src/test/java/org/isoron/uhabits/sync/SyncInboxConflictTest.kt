/* Copyright (C) 2026 Alinson Santos Xavier and contributors. GPL-3.0-or-later. */
package org.isoron.uhabits.sync

import kotlinx.coroutines.runBlocking
import org.isoron.platform.io.run
import org.isoron.platform.io.runInTransaction
import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.core.database.HabitData
import org.isoron.uhabits.core.database.HabitGoalData
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

    @Test
    fun goalCollisionSameDateDivergentUuidDoesNotDropRemoteUpdate() = runBlocking {
        val harness = SyncCoordinatorNextTest()
        val env = harness.setupDeviceEnvironment(harness.createTestDatabase(),
            SyncCoordinatorNextTest.InMemorySyncBackend(), "goal-collision")
        try {
            val habitId = env.modelFactory.habitRepository.insert(HabitData(name = "Exercise", uuid = "habit-1"))
            val localGoal = HabitGoalData(
                habitId = habitId,
                uuid = "goal-uuid-1",
                effectiveTimestamp = 1000L,
                freqNum = 1,
                freqDen = 1,
                targetType = 0,
                targetValue = 10.0,
                unit = "reps",
                updatedAt = 100L
            )
            env.modelFactory.habitGoalRepository.upsert(localGoal)

            val remoteEvent = RemoteSyncEvent(
                logId = 42L,
                opUuid = "remote-op-42",
                entityType = "habit_goal",
                entityUuid = "goal-uuid-2",
                operationType = "upsert",
                payloadJson = """{"habit_uuid":"habit-1","effective_timestamp":1000,"freq_num":1,"freq_den":1,"target_type":0,"target_value":25.0,"unit":"reps"}""",
                createdAt = 200L,
                deviceId = "foreign"
            )

            val skipped = env.coordinator.applyRemoteEvents(listOf(remoteEvent))
            assertEquals(1, skipped)

            val existing = env.modelFactory.habitGoalRepository.findByUuid("goal-uuid-1")!!
            assertEquals("goal-uuid-1", existing.uuid)
            assertEquals(10.0, existing.targetValue, 0.001)

            val inboxRecords = env.modelFactory.syncInboxRepository.findByEntity("habit_goal", "goal-uuid-2")
            assertEquals(1, inboxRecords.size)
            assertEquals("GOAL_COLLISION", inboxRecords[0].deferredReason)
            assertEquals("PENDING", inboxRecords[0].status)
        } finally {
            env.coordinator.dispose()
            env.db.close()
        }
    }

    @Test
    fun goalCollisionRepeatedProcessingIncrementsAttemptsAndSuspends() = runBlocking {
        val harness = SyncCoordinatorNextTest()
        val env = harness.setupDeviceEnvironment(harness.createTestDatabase(),
            SyncCoordinatorNextTest.InMemorySyncBackend(), "goal-repeated")
        try {
            val habitId = env.modelFactory.habitRepository.insert(HabitData(name = "Exercise", uuid = "habit-1"))
            val localGoal = HabitGoalData(
                habitId = habitId,
                uuid = "goal-uuid-1",
                effectiveTimestamp = 1000L,
                freqNum = 1,
                freqDen = 1,
                targetType = 0,
                targetValue = 10.0,
                unit = "reps",
                updatedAt = 100L
            )
            env.modelFactory.habitGoalRepository.upsert(localGoal)

            val remoteEvent = RemoteSyncEvent(
                logId = 42L,
                opUuid = "remote-op-42",
                entityType = "habit_goal",
                entityUuid = "goal-uuid-2",
                operationType = "upsert",
                payloadJson = """{"habit_uuid":"habit-1","effective_timestamp":1000,"freq_num":1,"freq_den":1,"target_type":0,"target_value":25.0,"unit":"reps"}""",
                createdAt = 200L,
                deviceId = "foreign"
            )

            // Initial apply defers the colliding event
            val skipped = env.coordinator.applyRemoteEvents(listOf(remoteEvent))
            assertEquals(1, skipped)

            var inbox = env.modelFactory.syncInboxRepository.findByEntity("habit_goal", "goal-uuid-2").single()
            assertEquals(0, inbox.attempts)
            assertEquals("PENDING", inbox.status)
            assertEquals("GOAL_COLLISION", inbox.deferredReason)

            // Replay 1..4: each attempt fails-closed and increments attempt count
            for (i in 1..4) {
                val replayed = env.coordinator.replayInbox(maxAttempts = 5, ignoreBackoff = true)
                assertEquals(0, replayed)
                inbox = env.modelFactory.syncInboxRepository.findByEntity("habit_goal", "goal-uuid-2").single()
                assertEquals(i, inbox.attempts)
                assertEquals("PENDING", inbox.status)
            }

            // 5th attempt reaches maxAttempts (5) and marks status as SUSPENDED
            val replayedFinal = env.coordinator.replayInbox(maxAttempts = 5, ignoreBackoff = true)
            assertEquals(0, replayedFinal)
            inbox = env.modelFactory.syncInboxRepository.findByEntity("habit_goal", "goal-uuid-2").single()
            assertEquals(5, inbox.attempts)
            assertEquals("SUSPENDED", inbox.status)

            // Further replays ignore suspended events
            val replayedAfterSuspend = env.coordinator.replayInbox(maxAttempts = 5, ignoreBackoff = true)
            assertEquals(0, replayedAfterSuspend)

            // Original goal remains intact
            val existing = env.modelFactory.habitGoalRepository.findByUuid("goal-uuid-1")!!
            assertEquals("goal-uuid-1", existing.uuid)
            assertEquals(10.0, existing.targetValue, 0.001)
        } finally {
            env.coordinator.dispose()
            env.db.close()
        }
    }

    @Test
    fun goalDistinctHabitsSameEffectiveTimestampDoNotCollide() = runBlocking {
        val harness = SyncCoordinatorNextTest()
        val env = harness.setupDeviceEnvironment(harness.createTestDatabase(),
            SyncCoordinatorNextTest.InMemorySyncBackend(), "distinct-habits")
        try {
            val habit1Id = env.modelFactory.habitRepository.insert(HabitData(name = "Habit One", uuid = "habit-1"))
            val habit2Id = env.modelFactory.habitRepository.insert(HabitData(name = "Habit Two", uuid = "habit-2"))

            val goal1 = HabitGoalData(
                habitId = habit1Id,
                uuid = "goal-h1",
                effectiveTimestamp = 1000L,
                freqNum = 1,
                freqDen = 1,
                targetType = 0,
                targetValue = 10.0,
                unit = "reps",
                updatedAt = 100L
            )
            env.modelFactory.habitGoalRepository.upsert(goal1)

            val remoteEventHabit2 = RemoteSyncEvent(
                logId = 43L,
                opUuid = "remote-op-43",
                entityType = "habit_goal",
                entityUuid = "goal-h2",
                operationType = "upsert",
                payloadJson = """{"habit_uuid":"habit-2","effective_timestamp":1000,"freq_num":2,"freq_den":1,"target_type":0,"target_value":30.0,"unit":"min"}""",
                createdAt = 200L,
                deviceId = "foreign"
            )

            // Distinct habits with same timestamp must succeed without collision
            val skipped = env.coordinator.applyRemoteEvents(listOf(remoteEventHabit2))
            assertEquals(0, skipped)

            val h1Goal = env.modelFactory.habitGoalRepository.findByUuid("goal-h1")!!
            assertEquals(habit1Id, h1Goal.habitId)
            assertEquals(10.0, h1Goal.targetValue, 0.001)

            val h2Goal = env.modelFactory.habitGoalRepository.findByUuid("goal-h2")!!
            assertEquals(habit2Id, h2Goal.habitId)
            assertEquals(30.0, h2Goal.targetValue, 0.001)

            // No inbox deferral for habit 2
            assertEquals(0, env.modelFactory.syncInboxRepository.count())
        } finally {
            env.coordinator.dispose()
            env.db.close()
        }
    }

    @Test
    fun goalSyncTransactionRollbackPreservesDatabaseAndInboxState() = runBlocking {
        val harness = SyncCoordinatorNextTest()
        val env = harness.setupDeviceEnvironment(harness.createTestDatabase(),
            SyncCoordinatorNextTest.InMemorySyncBackend(), "goal-rollback")
        try {
            val habitId = env.modelFactory.habitRepository.insert(HabitData(name = "Habit", uuid = "habit-1"))
            val localGoal = HabitGoalData(
                habitId = habitId,
                uuid = "goal-uuid-1",
                effectiveTimestamp = 1000L,
                freqNum = 1,
                freqDen = 1,
                targetType = 0,
                targetValue = 10.0,
                unit = "reps",
                updatedAt = 100L
            )
            env.modelFactory.habitGoalRepository.upsert(localGoal)

            val collidingRemote = RemoteSyncEvent(
                logId = 44L,
                opUuid = "remote-op-44",
                entityType = "habit_goal",
                entityUuid = "goal-uuid-2",
                operationType = "upsert",
                payloadJson = """{"habit_uuid":"habit-1","effective_timestamp":1000,"freq_num":1,"freq_den":1,"target_type":0,"target_value":50.0,"unit":"reps"}""",
                createdAt = 200L,
                deviceId = "foreign"
            )

            // When an overall transaction aborts, rollback restores exact prior state
            try {
                env.db.runInTransaction {
                    env.coordinator.applyRemoteEvents(listOf(collidingRemote))
                    throw RuntimeException("Simulated sync transaction failure")
                }
            } catch (e: RuntimeException) {
                assertEquals("Simulated sync transaction failure", e.message)
            }

            // Rolled back: inbox should have 0 records, goal 1 unchanged, goal 2 absent
            assertEquals(0, env.modelFactory.syncInboxRepository.count())
            val existing = env.modelFactory.habitGoalRepository.findByUuid("goal-uuid-1")!!
            assertEquals(10.0, existing.targetValue, 0.001)
            assertNull(env.modelFactory.habitGoalRepository.findByUuid("goal-uuid-2"))
        } finally {
            env.coordinator.dispose()
            env.db.close()
        }
    }

    @Test
    fun goalCollisionSameUuidDivergentHabitDefersToInboxAndPreservesOriginal() = runBlocking {
        val harness = SyncCoordinatorNextTest()
        val env = harness.setupDeviceEnvironment(
            harness.createTestDatabase(),
            SyncCoordinatorNextTest.InMemorySyncBackend(),
            "goal-same-uuid-divergent-habit"
        )
        try {
            val habit1Id = env.modelFactory.habitRepository.insert(HabitData(name = "Habit 1", uuid = "habit-1"))
            val habit2Id = env.modelFactory.habitRepository.insert(HabitData(name = "Habit 2", uuid = "habit-2"))

            val localGoalHabit1 = HabitGoalData(
                habitId = habit1Id,
                uuid = "goal-shared-uuid",
                effectiveTimestamp = 1000L,
                freqNum = 1,
                freqDen = 1,
                targetType = 0,
                targetValue = 10.0,
                unit = "reps",
                updatedAt = 100L
            )
            env.modelFactory.habitGoalRepository.upsert(localGoalHabit1)

            val remoteEventHabit2 = RemoteSyncEvent(
                logId = 99L,
                opUuid = "remote-op-99",
                entityType = "habit_goal",
                entityUuid = "goal-shared-uuid",
                operationType = "upsert",
                payloadJson = """{"habit_uuid":"habit-2","effective_timestamp":1000,"freq_num":2,"freq_den":1,"target_type":0,"target_value":30.0,"unit":"min"}""",
                createdAt = 200L,
                deviceId = "foreign"
            )

            val skipped = env.coordinator.applyRemoteEvents(listOf(remoteEventHabit2))
            assertEquals(1, skipped)

            val preserved = env.modelFactory.habitGoalRepository.findByUuid("goal-shared-uuid")!!
            assertEquals(habit1Id, preserved.habitId)
            assertEquals(10.0, preserved.targetValue, 0.001)

            val inbox = env.modelFactory.syncInboxRepository.findByEntity("habit_goal", "goal-shared-uuid")
            assertEquals(1, inbox.size)
            assertEquals("GOAL_COLLISION", inbox[0].deferredReason)
            assertEquals("PENDING", inbox[0].status)
        } finally {
            env.coordinator.dispose()
            env.db.close()
        }
    }

    @Test
    fun goalCollisionSameUuidDivergentDateDefersToInboxAndPreservesOriginal() = runBlocking {
        val harness = SyncCoordinatorNextTest()
        val env = harness.setupDeviceEnvironment(
            harness.createTestDatabase(),
            SyncCoordinatorNextTest.InMemorySyncBackend(),
            "goal-same-uuid-divergent-date"
        )
        try {
            val habitId = env.modelFactory.habitRepository.insert(HabitData(name = "Habit", uuid = "habit-1"))

            val localGoal = HabitGoalData(
                habitId = habitId,
                uuid = "goal-date-uuid",
                effectiveTimestamp = 1000L,
                freqNum = 1,
                freqDen = 1,
                targetType = 0,
                targetValue = 10.0,
                unit = "reps",
                updatedAt = 100L
            )
            env.modelFactory.habitGoalRepository.upsert(localGoal)

            val remoteEventDifferentDate = RemoteSyncEvent(
                logId = 100L,
                opUuid = "remote-op-100",
                entityType = "habit_goal",
                entityUuid = "goal-date-uuid",
                operationType = "upsert",
                payloadJson = """{"habit_uuid":"habit-1","effective_timestamp":2000,"freq_num":1,"freq_den":1,"target_type":0,"target_value":40.0,"unit":"reps"}""",
                createdAt = 200L,
                deviceId = "foreign"
            )

            val skipped = env.coordinator.applyRemoteEvents(listOf(remoteEventDifferentDate))
            assertEquals(1, skipped)

            val preserved = env.modelFactory.habitGoalRepository.findByUuid("goal-date-uuid")!!
            assertEquals(1000L, preserved.effectiveTimestamp)
            assertEquals(10.0, preserved.targetValue, 0.001)

            assertNull(env.modelFactory.habitGoalRepository.findByHabitAndTimestamp(habitId, 2000L))

            val inbox = env.modelFactory.syncInboxRepository.findByEntity("habit_goal", "goal-date-uuid")
            assertEquals(1, inbox.size)
            assertEquals("GOAL_COLLISION", inbox[0].deferredReason)
        } finally {
            env.coordinator.dispose()
            env.db.close()
        }
    }
}
