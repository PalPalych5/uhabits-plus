package org.isoron.uhabits.core.userTasks

import java.io.File
import kotlinx.coroutines.runBlocking
import org.isoron.platform.io.*
import org.isoron.uhabits.core.containers.backup.*
import org.isoron.uhabits.core.containers.migration.SemanticMigrationFixtures
import org.isoron.uhabits.core.production.migration.*
import org.isoron.uhabits.core.userTasks.due.*
import org.isoron.uhabits.core.userTasks.sqlite.*
import org.isoron.uhabits.core.userTasks.tags.TaskTagRepository
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class TaskClassificationStorageTest {
    @get:Rule val temp = TemporaryFolder()
    private val values = listOf<Boolean?>(null, false, true)

    @Test fun allNinePairsRoundTripAndIndependentUpdatesRespectUndo() = runBlocking {
        val db = TestDatabaseHelper.createEmptyDatabase()
        try {
            UserTaskSchema.createSchema(db)
            val repo = SQLiteUserTaskRepository(db)
            var sequence = 0
            val service = UserTaskServiceImpl(repo, { 10L }, { "task-${++sequence}" })
            val due = TaskDue.Day(TaskDueDate.parse("2026-10-24"))
            for (important in values) for (urgent in values) {
                val original = service.createTask("Pair", due = due, important = important, urgent = urgent)
                assertEquals(original, repo.getById(original.id))
                val first = service.updateTask(original.id, important = true)
                assertEquals(urgent, first.urgent)
                val second = service.updateTask(original.id, urgent = false)
                assertEquals(true, second.important)
                val cleared = service.updateTask(original.id, clearImportant = true)
                assertNull(cleared.important)
                assertEquals(false, cleared.urgent)
                assertEquals(due, cleared.due)
                assertNull(service.restoreTaskSnapshot(first, original))
                val restored = service.restoreTaskSnapshot(cleared, original)!!
                assertEquals(important, restored.important)
                assertEquals(urgent, restored.urgent)
                assertEquals(due, restored.due)
                assertNull(service.updateTask(original.id, clearUrgent = true).urgent)
            }
            assertEquals(9, repo.getAll().size)
        } finally { db.close() }
    }

    @Test fun additiveInstalledFileMigrationPreservesDataAndSurvivesReopen() = runBlocking {
        val file = File(temp.newFolder(), "installed.db")
        val opener = JavaDatabaseOpener()
        var db = opener.open(file.absolutePath)
        try {
            UserTaskSchema.createSchema(db, targetVersion = 33)
            db.run("INSERT INTO UserTasks(id,title,created_at,updated_at,due_kind,due_date) VALUES('parent','Parent',1,2,'DAY','2026-10-24')")
            db.run("INSERT INTO UserTasks(id,title,created_at,updated_at,parent_task_id,status,completed_at) VALUES('child','Child',1,2,'parent','COMPLETED',2)")
            var tagSequence = 0
            val tags = TaskTagRepository(db, { "tag-${++tagSequence}" }, { 3L })
            val tag = tags.create("Keep")
            tags.setTags(TaskId("parent"), setOf(tag.id))
            db.setVersion(33)
            db.runInTransaction { db.migrateTo(34) { TestDatabaseHelper.loadMigrationSQL(it) } }
            assertEquals(34, db.getVersion())
            UserTaskSchema.ensureV34Columns(db) // idempotent schema initializer
            val service = UserTaskServiceImpl(SQLiteUserTaskRepository(db), { 4L })
            assertNull(SQLiteUserTaskRepository(db).getById(TaskId("parent"))!!.important)
            service.updateTask(TaskId("parent"), important = false, urgent = true)
            db.close()
            db = opener.open(file.absolutePath)
            val repo = SQLiteUserTaskRepository(db)
            val parent = repo.getById(TaskId("parent"))!!
            assertEquals(false, parent.important)
            assertEquals(true, parent.urgent)
            assertEquals(TaskDue.Day(TaskDueDate.parse("2026-10-24")), parent.due)
            assertEquals(setOf(tag.id), TaskTagRepository(db, { "unused" }, { 5L }).getTagIds(parent.id))
            val child = repo.getSubtasks(parent.id).single()
            assertTrue(child.isCompleted)
            assertEquals(2L, child.completedAt)
            assertNull(child.important)
            assertNull(child.urgent)
            assertEquals("ok", db.queryString("PRAGMA integrity_check"))
        } finally { db.close() }
    }

    @Test fun oldSyncWriterUpdatesFactsWithoutOwningClassificationOrDeletingLinks() = runBlocking {
        val db = TestDatabaseHelper.createEmptyDatabase()
        try {
            UserTaskSchema.createSchema(db)
            val repo = SQLiteUserTaskRepository(db)
            val task = UserTask(TaskId("keep"), "Keep", createdAt = 1, updatedAt = 2, important = true, urgent = false)
            repo.save(task)
            var tagSequence = 0
            val tags = TaskTagRepository(db, { "tag-${++tagSequence}" }, { 3L })
            val tag = tags.create("Keep")
            tags.setTags(task.id, setOf(tag.id))
            repeat(2) {
                UserTaskSqlWriter.upsert(db, task.id.value) {
                    bindText(1, task.id.value); bindText(2, "Remote edit"); bindText(3, "")
                    bindNull(4); bindText(5, "PENDING"); bindText(6, "NONE")
                    for (i in 7..11) bindNull(i)
                    bindInt(12, 0); bindLong(13, 1); bindLong(14, 3); bindNull(15); bindInt(16, 0)
                }
                val actual = repo.getById(task.id)!!
                assertEquals("Remote edit", actual.title)
                assertEquals(true, actual.important)
                assertEquals(false, actual.urgent)
                assertEquals(setOf(tag.id), tags.getTagIds(task.id))
            }
        } finally { db.close() }
    }

    @Test fun backupRestorePreservesEveryPairDueTagsAndChildrenRepeatedly() = runBlocking {
        val source = TestDatabaseHelper.createEmptyDatabase()
        val backup = TestDatabaseHelper.createEmptyDatabase()
        val staging = TestDatabaseHelper.createEmptyDatabase()
        val destination = TestDatabaseHelper.createEmptyDatabase()
        try {
            SemanticMigrationFixtures.seedAllFixtures(source)
            assertTrue(ProductionNextSchemaMigrator.migrate(source, 1000000L) is ProductionNextMigrationResult.Success)
            source.migrateTo(34) { TestDatabaseHelper.loadMigrationSQL(it) }
            val repo = SQLiteUserTaskRepository(source)
            var sequence = 0
            val service = UserTaskServiceImpl(repo, { 100L }, { "backup-${++sequence}" })
            var tagSequence = 0
            val tags = TaskTagRepository(source, { "tag-${++tagSequence}" }, { 100L })
            val tag = tags.create("Backup")
            for (important in values) for (urgent in values) {
                val parent = service.createTask("Pair", important = important, urgent = urgent,
                    due = TaskDue.Timed.of(1792753200000L, "Asia/Novosibirsk"))
                tags.setTags(parent.id, setOf(tag.id))
                service.setCompleted(service.createTask("Child", parentTaskId = parent.id).id, true)
            }
            val expected = repo.getAll()
            assertTrue(ContainerBackupService.createBackup(source, backup, 2000000L) is BackupCreationResult.Success)
            repeat(2) {
                val result = ContainerRestoreService.restore(backup, staging, destination)
                assertTrue(result is RestoreResult.Success, result.toString())
                assertEquals(expected, SQLiteUserTaskRepository(destination).getAll())
                val restoredTags = TaskTagRepository(destination, { "unused" }, { 200L })
                expected.filter { it.parentTaskId == null }.forEach {
                    assertEquals(setOf(tag.id), restoredTags.getTagIds(it.id))
                }
            }
        } finally { source.close(); backup.close(); staging.close(); destination.close() }
    }
}
