/* Copyright (C) 2026 Alinson Santos Xavier and contributors. GPL-3.0-or-later. */
package org.isoron.uhabits.core.userTasks

import java.io.File
import kotlinx.coroutines.runBlocking
import org.isoron.platform.io.*
import org.isoron.uhabits.core.DATABASE_VERSION
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.containers.backup.*
import org.isoron.uhabits.core.containers.migration.SemanticMigrationFixtures
import org.isoron.uhabits.core.focus.*
import org.isoron.uhabits.core.focus.sqlite.FocusSessionSchema
import org.isoron.uhabits.core.focus.sqlite.SQLiteFocusSessionRepository
import org.isoron.uhabits.core.production.migration.*
import org.isoron.uhabits.core.userTasks.due.*
import org.isoron.uhabits.core.userTasks.sqlite.*
import org.isoron.uhabits.core.userTasks.tags.TaskTagRepository
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class TaskLinkStorageTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun linkFieldsRoundTripAndIndependentUpdatesPreserveClassificationAndDue() = runBlocking {
        val db = TestDatabaseHelper.createEmptyDatabase()
        try {
            UserTaskSchema.createSchema(db)
            val repo = SQLiteUserTaskRepository(db)
            var sequence = 0
            val service = UserTaskServiceImpl(repo, { 100L }, { "task-${++sequence}" })
            val due = TaskDue.Day(TaskDueDate.parse("2026-10-24"))

            // 1. Create task with full link fields and classification
            val original = service.createTask(
                title = "Linked Task",
                due = due,
                important = true,
                urgent = false,
                linkedHabitUuid = "habit-uuid-1",
                habitTimeAccrualEnabled = true,
                habitLinkEffectiveFrom = 1000L,
            )
            assertEquals(original, repo.getById(original.id))
            assertEquals("habit-uuid-1", original.linkedHabitUuid)
            assertTrue(original.habitTimeAccrualEnabled)
            assertEquals(1000L, original.habitLinkEffectiveFrom)
            assertEquals(true, original.important)
            assertEquals(false, original.urgent)

            // 2. Update linked habit uuid -> stamps fresh monotonic cutoff (now = 101L)
            val updated1 = service.updateTask(original.id, linkedHabitUuid = "habit-uuid-2")
            assertEquals("habit-uuid-2", updated1.linkedHabitUuid)
            assertTrue(updated1.habitTimeAccrualEnabled)
            assertEquals(101L, updated1.habitLinkEffectiveFrom)
            assertEquals(true, updated1.important)
            assertEquals(false, updated1.urgent)

            // 3. Update accrual enabled to false -> preserves cutoff
            val updated2 = service.updateTask(original.id, habitTimeAccrualEnabled = false)
            assertEquals("habit-uuid-2", updated2.linkedHabitUuid)
            assertFalse(updated2.habitTimeAccrualEnabled)
            assertEquals(101L, updated2.habitLinkEffectiveFrom)
            assertEquals(true, updated2.important)

            // 4. Update effective from
            val updated3 = service.updateTask(original.id, habitLinkEffectiveFrom = 2000L)
            assertEquals("habit-uuid-2", updated3.linkedHabitUuid)
            assertFalse(updated3.habitTimeAccrualEnabled)
            assertEquals(2000L, updated3.habitLinkEffectiveFrom)

            // 5. Clear link and effective from
            val cleared = service.updateTask(original.id, clearLinkedHabit = true, clearHabitLinkEffectiveFrom = true)
            assertNull(cleared.linkedHabitUuid)
            assertFalse(cleared.habitTimeAccrualEnabled)
            assertNull(cleared.habitLinkEffectiveFrom)
            assertEquals(true, cleared.important)
            assertEquals(false, cleared.urgent)
            assertEquals(due, cleared.due)

            // 6. restoreTaskSnapshot verifies undo/rebase
            val restored = service.restoreTaskSnapshot(cleared, original)!!
            assertEquals(original.linkedHabitUuid, restored.linkedHabitUuid)
            assertEquals(original.habitTimeAccrualEnabled, restored.habitTimeAccrualEnabled)
            assertEquals(original.habitLinkEffectiveFrom, restored.habitLinkEffectiveFrom)
            assertEquals(original.important, restored.important)
            assertEquals(original.urgent, restored.urgent)
            assertEquals(original.due, restored.due)
        } finally { db.close() }
    }

    @Test
    fun additiveInstalledFileMigration34To35PreservesDataAndSurvivesReopen() = runBlocking {
        val file = File(temp.newFolder(), "installed_v34_to_v35.db")
        val opener = JavaDatabaseOpener()
        var db = opener.open(file.absolutePath)
        try {
            UserTaskSchema.createSchema(db, targetVersion = 34)
            FocusSessionSchema.createSchema(db)

            // Seed v34 parent task with Due, tags, and classification
            db.run(
                """
                INSERT INTO UserTasks(id, title, created_at, updated_at, due_kind, due_date, important, urgent)
                VALUES('task-legacy', 'Legacy Task', 1, 2, 'DAY', '2026-10-24', 1, 0)
                """.trimIndent()
            )
            // Seed v34 subtask with parent reference, timed due, and reversed classification
            db.run(
                """
                INSERT INTO UserTasks(id, title, parent_task_id, created_at, updated_at, due_kind, due_time_millis, due_zone, status, completed_at, important, urgent)
                VALUES('task-subtask', 'Subtask One', 'task-legacy', 3, 4, 'TIMED', 1792753200000, 'Asia/Novosibirsk', 'COMPLETED', 5, 0, 1)
                """.trimIndent()
            )

            var tagSequence = 0
            val tags = TaskTagRepository(db, { "tag-${++tagSequence}" }, { 3L })
            val tag = tags.create("Work")
            tags.setTags(TaskId("task-legacy"), setOf(tag.id))

            // Seed v34 FocusSession attached to legacy task
            val focusRepo = SQLiteFocusSessionRepository(db)
            val focusSession = FocusSession(
                id = FocusSessionId("fs-legacy-1"),
                startedAt = 1000L,
                endedAt = 3000L,
                durationMs = 2000L,
                taskId = TaskId("task-legacy"),
                primaryTargetType = FocusPrimaryTargetType.TASK,
                source = FocusSessionSource.TIMER,
                createdAt = 3000L,
                updatedAt = 3000L,
            )
            focusRepo.record(focusSession)
            db.setVersion(34)

            // Migrate 34 -> 35
            db.runInTransaction { db.migrateTo(35) { TestDatabaseHelper.loadMigrationSQL(it) } }
            assertEquals(35, db.getVersion())

            // Ensure idempotency of ensureV35Columns
            UserTaskSchema.ensureV35Columns(db)
            UserTaskSchema.ensureV35Columns(db)

            val repo = SQLiteUserTaskRepository(db)

            // Verify parent task preserved
            val legacy = repo.getById(TaskId("task-legacy"))!!
            assertEquals("Legacy Task", legacy.title)
            assertEquals(true, legacy.important)
            assertEquals(false, legacy.urgent)
            assertEquals(TaskDue.Day(TaskDueDate.parse("2026-10-24")), legacy.due)
            assertNull(legacy.linkedHabitUuid)
            assertFalse(legacy.habitTimeAccrualEnabled)
            assertNull(legacy.habitLinkEffectiveFrom)
            assertEquals(setOf(tag.id), tags.getTagIds(legacy.id))

            // Verify subtask preserved
            val subtasks = repo.getSubtasks(TaskId("task-legacy"))
            assertEquals(1, subtasks.size)
            val subtask = subtasks.single()
            assertEquals(TaskId("task-subtask"), subtask.id)
            assertEquals(TaskId("task-legacy"), subtask.parentTaskId)
            assertEquals("Subtask One", subtask.title)
            assertTrue(subtask.isCompleted)
            assertEquals(5L, subtask.completedAt)
            assertEquals(false, subtask.important)
            assertEquals(true, subtask.urgent)
            assertEquals(TaskDue.Timed.of(1792753200000L, "Asia/Novosibirsk"), subtask.due)
            assertNull(subtask.linkedHabitUuid)
            assertFalse(subtask.habitTimeAccrualEnabled)
            assertNull(subtask.habitLinkEffectiveFrom)

            // Verify FocusSession preserved
            assertEquals(listOf(focusSession), focusRepo.getByTask(TaskId("task-legacy")))
            assertEquals(2000L, focusRepo.getTotalDurationByTask(TaskId("task-legacy")))

            // Save new task with link fields on migrated database
            val service = UserTaskServiceImpl(repo, { 4L })
            val linked = service.createTask(
                title = "New Linked Task",
                linkedHabitUuid = "habit-999",
                habitTimeAccrualEnabled = true,
                habitLinkEffectiveFrom = 5000L,
                important = true,
                urgent = false,
            )

            // Assert legacy UserTaskSqlWriter upsert does not reset local link or classification
            UserTaskSqlWriter.upsert(db, linked.id.value) {
                bindText(1, linked.id.value)
                bindText(2, "Remote Updated Title")
                bindText(3, "Remote description")
                bindNull(4)
                bindText(5, "PENDING")
                bindText(6, "NONE")
                for (i in 7..11) bindNull(i)
                bindInt(12, 0)
                bindLong(13, 4L)
                bindLong(14, 6L)
                bindNull(15)
                bindInt(16, 0)
            }

            val updatedLinked = repo.getById(linked.id)!!
            assertEquals("Remote Updated Title", updatedLinked.title)
            assertEquals("Remote description", updatedLinked.description)
            assertEquals("habit-999", updatedLinked.linkedHabitUuid)
            assertTrue(updatedLinked.habitTimeAccrualEnabled)
            assertEquals(5000L, updatedLinked.habitLinkEffectiveFrom)
            assertEquals(true, updatedLinked.important)
            assertEquals(false, updatedLinked.urgent)

            // Close and reopen database: verify persistence across session boundary
            db.close()
            db = opener.open(file.absolutePath)

            val reopenedRepo = SQLiteUserTaskRepository(db)
            val reopenedTags = TaskTagRepository(db, { "tag-${++tagSequence}" }, { 10L })
            val reopenedFocusRepo = SQLiteFocusSessionRepository(db)

            val reloadedLegacy = reopenedRepo.getById(TaskId("task-legacy"))!!
            assertEquals(true, reloadedLegacy.important)
            assertEquals(false, reloadedLegacy.urgent)
            assertEquals(TaskDue.Day(TaskDueDate.parse("2026-10-24")), reloadedLegacy.due)
            assertNull(reloadedLegacy.linkedHabitUuid)
            assertEquals(setOf(tag.id), reopenedTags.getTagIds(reloadedLegacy.id))

            val reloadedSubtasks = reopenedRepo.getSubtasks(TaskId("task-legacy"))
            assertEquals(1, reloadedSubtasks.size)
            val reloadedSub = reloadedSubtasks.single()
            assertEquals("Subtask One", reloadedSub.title)
            assertTrue(reloadedSub.isCompleted)
            assertEquals(false, reloadedSub.important)
            assertEquals(true, reloadedSub.urgent)
            assertEquals(TaskDue.Timed.of(1792753200000L, "Asia/Novosibirsk"), reloadedSub.due)
            assertNull(reloadedSub.linkedHabitUuid)

            assertEquals(listOf(focusSession), reopenedFocusRepo.getByTask(TaskId("task-legacy")))
            assertEquals(2000L, reopenedFocusRepo.getTotalDurationByTask(TaskId("task-legacy")))

            val reloadedLinked = reopenedRepo.getById(linked.id)!!
            assertEquals("Remote Updated Title", reloadedLinked.title)
            assertEquals("habit-999", reloadedLinked.linkedHabitUuid)
            assertTrue(reloadedLinked.habitTimeAccrualEnabled)
            assertEquals(5000L, reloadedLinked.habitLinkEffectiveFrom)
            assertEquals(true, reloadedLinked.important)
            assertEquals(false, reloadedLinked.urgent)

            assertEquals("ok", db.queryString("PRAGMA integrity_check"))
        } finally { db.close() }
    }

    @Test
    fun backupRestorePreservesTaskLinkFieldsAndSurvivesRoundtrip() = runBlocking {
        val source = TestDatabaseHelper.createEmptyDatabase()
        val backup = TestDatabaseHelper.createEmptyDatabase()
        val staging = TestDatabaseHelper.createEmptyDatabase()
        val destination = TestDatabaseHelper.createEmptyDatabase()
        try {
            SemanticMigrationFixtures.seedAllFixtures(source)
            assertTrue(ProductionNextSchemaMigrator.migrate(source, 1000000L) is ProductionNextMigrationResult.Success)
            source.migrateTo(DATABASE_VERSION) { TestDatabaseHelper.loadMigrationSQL(it) }

            val repo = SQLiteUserTaskRepository(source)
            var sequence = 0
            val service = UserTaskServiceImpl(repo, { 100L }, { "task-${++sequence}" })

            // Create varied tasks: unlinked, linked without accrual, linked with accrual, with/without effective date
            service.createTask("Plain Task", important = true, urgent = false)
            service.createTask(
                "Linked No Accrual",
                linkedHabitUuid = "habit-uuid-alpha",
                habitTimeAccrualEnabled = false,
                habitLinkEffectiveFrom = null,
                important = false,
                urgent = true,
            )
            service.createTask(
                "Linked With Accrual",
                linkedHabitUuid = "habit-uuid-beta",
                habitTimeAccrualEnabled = true,
                habitLinkEffectiveFrom = 1792432800000L,
                due = TaskDue.Day(TaskDueDate.parse("2026-10-25")),
            )

            val expected = repo.getAll()
            assertEquals(3, expected.size)

            assertTrue(ContainerBackupService.createBackup(source, backup, 2000000L) is BackupCreationResult.Success)

            repeat(2) {
                val result = ContainerRestoreService.restore(backup, staging, destination)
                assertTrue(result is RestoreResult.Success, result.toString())
                val restored = SQLiteUserTaskRepository(destination).getAll()
                assertEquals(expected, restored)
            }
        } finally {
            source.close()
            backup.close()
            staging.close()
            destination.close()
        }
    }

    @Test
    fun getTotalDurationByTaskAggregatesCorrectlyInSqliteAndMemory() = runBlocking {
        val db = TestDatabaseHelper.createEmptyDatabase()
        try {
            FocusSessionSchema.createSchema(db)
            val sqliteRepo = SQLiteFocusSessionRepository(db)
            val memoryRepo = org.isoron.uhabits.core.focus.memory.MemoryFocusSessionRepository()

            val task1 = TaskId("task-1")
            val task2 = TaskId("task-2")
            val task3 = TaskId("task-3")

            assertEquals(0L, sqliteRepo.getTotalDurationByTask(task1))
            assertEquals(0L, memoryRepo.getTotalDurationByTask(task1))

            val session1 = FocusSession(
                id = FocusSessionId("fs-1"),
                startedAt = 1000L,
                endedAt = 2000L,
                durationMs = 900L,
                taskId = task1,
                source = FocusSessionSource.TIMER,
                createdAt = 2000L,
                updatedAt = 2000L,
            )
            val session2 = FocusSession(
                id = FocusSessionId("fs-2"),
                startedAt = 3000L,
                endedAt = 5000L,
                durationMs = 1800L,
                taskId = task1,
                source = FocusSessionSource.TIMER,
                createdAt = 5000L,
                updatedAt = 5000L,
            )
            val session3 = FocusSession(
                id = FocusSessionId("fs-3"),
                startedAt = 6000L,
                endedAt = 8000L,
                durationMs = 1500L,
                taskId = task2,
                source = FocusSessionSource.MANUAL,
                createdAt = 8000L,
                updatedAt = 8000L,
            )
            val sessionNoTask = FocusSession(
                id = FocusSessionId("fs-4"),
                startedAt = 9000L,
                endedAt = 10000L,
                durationMs = 1000L,
                taskId = null,
                habit = HabitRef("habit-uuid"),
                source = FocusSessionSource.TIMER,
                createdAt = 10000L,
                updatedAt = 10000L,
            )

            listOf(session1, session2, session3, sessionNoTask).forEach {
                sqliteRepo.record(it)
                memoryRepo.record(it)
            }

            assertEquals(2700L, sqliteRepo.getTotalDurationByTask(task1))
            assertEquals(2700L, memoryRepo.getTotalDurationByTask(task1))

            assertEquals(1500L, sqliteRepo.getTotalDurationByTask(task2))
            assertEquals(1500L, memoryRepo.getTotalDurationByTask(task2))

            assertEquals(0L, sqliteRepo.getTotalDurationByTask(task3))
            assertEquals(0L, memoryRepo.getTotalDurationByTask(task3))
        } finally { db.close() }
    }

    @Test
    fun testMixedPrimaryTargetAggregateDurationExcludesNonTaskTargets() = runBlocking {
        val db = TestDatabaseHelper.createEmptyDatabase()
        try {
            FocusSessionSchema.createSchema(db)
            val sqliteRepo = SQLiteFocusSessionRepository(db)
            val memoryRepo = org.isoron.uhabits.core.focus.memory.MemoryFocusSessionRepository()

            val taskId = TaskId("task-focus-target")

            // Session 1: primaryTargetType == TASK (should be included)
            val taskSession = FocusSession(
                id = FocusSessionId("fs-task-primary"),
                startedAt = 1000L,
                endedAt = 2000L,
                durationMs = 1000L,
                taskId = taskId,
                primaryTargetType = FocusPrimaryTargetType.TASK,
                source = FocusSessionSource.TIMER,
                createdAt = 2000L,
                updatedAt = 2000L,
            )

            // Session 2: primaryTargetType == HABIT, but includes taskId as context (must be excluded)
            val habitSessionWithTask = FocusSession(
                id = FocusSessionId("fs-habit-primary-with-task"),
                startedAt = 3000L,
                endedAt = 5000L,
                durationMs = 2000L,
                taskId = taskId,
                habit = HabitRef("habit-uuid"),
                primaryTargetType = FocusPrimaryTargetType.HABIT,
                source = FocusSessionSource.TIMER,
                createdAt = 5000L,
                updatedAt = 5000L,
            )

            // Session 3: primaryTargetType == CONTAINER, but includes taskId as context (must be excluded)
            val containerSessionWithTask = FocusSession(
                id = FocusSessionId("fs-container-primary-with-task"),
                startedAt = 6000L,
                endedAt = 9000L,
                durationMs = 3000L,
                taskId = taskId,
                containerId = ContainerId("container-id"),
                primaryTargetType = FocusPrimaryTargetType.CONTAINER,
                source = FocusSessionSource.TIMER,
                createdAt = 9000L,
                updatedAt = 9000L,
            )

            listOf(taskSession, habitSessionWithTask, containerSessionWithTask).forEach {
                sqliteRepo.record(it)
                memoryRepo.record(it)
            }

            // Both repos must aggregate ONLY the TASK primary target session (1000L), excluding 2000L and 3000L
            assertEquals(1000L, sqliteRepo.getTotalDurationByTask(taskId))
            assertEquals(1000L, memoryRepo.getTotalDurationByTask(taskId))
        } finally { db.close() }
    }
}
