package org.isoron.uhabits.core.userTasks.tags

import kotlinx.coroutines.test.runTest
import org.isoron.platform.io.TestDatabaseHelper
import org.isoron.platform.io.queryLong
import org.isoron.platform.io.run
import org.isoron.platform.io.runInTransaction
import org.isoron.platform.io.setVersion
import org.isoron.uhabits.core.containers.backup.DatabaseSnapshotter
import org.isoron.uhabits.core.containers.backup.TaskTagBackupInventory
import org.isoron.uhabits.core.userTasks.TaskId
import org.isoron.uhabits.core.userTasks.UserTask
import org.isoron.uhabits.core.userTasks.due.TaskDue
import org.isoron.uhabits.core.userTasks.due.TaskDueDate
import org.isoron.uhabits.core.userTasks.sqlite.SQLiteUserTaskRepository
import org.isoron.uhabits.core.userTasks.sqlite.UserTaskSchema
import kotlin.test.*

class TaskTagRepositoryTest {
    @Test fun globalManyToManyRenameDeduplicateAndDelete() = runTest {
        val db = TestDatabaseHelper.createEmptyDatabase()
        try {
            UserTaskSchema.createSchema(db)
            TaskTagSchema.createSchema(db)
            var sequence = 0
            val repo = TaskTagRepository(db, { "id-${++sequence}" }, { 100L })
            val tasks = SQLiteUserTaskRepository(db)
            val one = UserTask(TaskId("one"), "One", createdAt=1, updatedAt=1)
            val two = one.copy(id=TaskId("two"), title="Two")
            tasks.saveAll(listOf(one, two))
            val work = repo.create("  Work  ")
            val urgent = repo.create("Urgent")
            assertEquals(work, repo.create("work"))
            repo.setTags(one.id, setOf(work.id, urgent.id))
            repo.setTags(two.id, setOf(work.id))
            assertEquals(setOf(one.id, two.id), repo.getTaskIds(setOf(work.id)))
            assertEquals(setOf(one.id), repo.getTaskIds(setOf(work.id, urgent.id)))
            assertEquals(emptySet(), repo.getTaskIds(setOf("missing")))
            assertFailsWith<IllegalArgumentException> { repo.rename(work.id, "URGENT") }
            repo.rename(work.id, "Shared work")
            assertTrue(repo.getTags(one.id).any { it.id == work.id && it.name == "Shared work" })
            assertTrue(repo.getTags(two.id).any { it.name == "Shared work" })
            repo.delete(work.id)
            assertEquals(setOf(urgent.id), repo.getTagIds(one.id))
            assertEquals(emptySet(), repo.getTagIds(two.id))
            assertEquals(listOf(one,two), tasks.getAll())
            assertNull(TaskTagBackupInventory.schemaError(db))
        } finally { db.close() }
    }

    @Test fun taskDeletionAndInvalidAssignmentCannotLeaveOrphans() = runTest {
        val db=TestDatabaseHelper.createEmptyDatabase()
        try {
            UserTaskSchema.createSchema(db); TaskTagSchema.createSchema(db)
            var sequence=0
            val repo=TaskTagRepository(db, { "id-${++sequence}" }, { 1L })
            val tasks=SQLiteUserTaskRepository(db)
            val task=UserTask(TaskId("one"), "One",createdAt=1,updatedAt=1)
            tasks.save(task)
            val tag=repo.create("Tag")
            repo.setTags(task.id,setOf(tag.id))
            val pending=db.queryLong("SELECT COUNT(*) FROM TaskTagOutbox")
            assertFailsWith<IllegalArgumentException> { repo.setTags(task.id,setOf("missing")) }
            assertEquals(setOf(tag.id),repo.getTagIds(task.id))
            assertEquals(pending,db.queryLong("SELECT COUNT(*) FROM TaskTagOutbox"))
            db.run("PRAGMA foreign_keys=OFF")
            tasks.delete(task.id)
            assertEquals(0L,db.queryLong("SELECT COUNT(*) FROM TaskTagLinks"))
        } finally { db.close() }
    }

    @Test fun creationAndLinksRollbackWithOutboxAndCallerTransaction() = runTest {
        val db=TestDatabaseHelper.createEmptyDatabase()
        try {
            UserTaskSchema.createSchema(db); TaskTagSchema.createSchema(db)
            var sequence=0
            val repo=TaskTagRepository(db, { "id-${++sequence}" }, { 1L })
            val tasks=SQLiteUserTaskRepository(db)
            val tag=repo.create("Tag")
            val pending=db.queryLong("SELECT COUNT(*) FROM TaskTagOutbox")
            assertFails {
                db.runInTransaction {
                    tasks.save(UserTask(TaskId("draft"),"Draft",createdAt=1,updatedAt=1))
                    repo.setTags(TaskId("draft"),setOf(tag.id))
                    error("fail after links")
                }
            }
            assertNull(tasks.getById(TaskId("draft")))
            assertEquals(0L,db.queryLong("SELECT COUNT(*) FROM TaskTagLinks"))
            assertEquals(pending,db.queryLong("SELECT COUNT(*) FROM TaskTagOutbox"))
        } finally { db.close() }
    }

    @Test fun additiveMigrationAndSnapshotPreserveDueLegacyAndPendingTagFacts() = runTest {
        val source=TestDatabaseHelper.createEmptyDatabase()
        val backup=TestDatabaseHelper.createEmptyDatabase()
        val restored=TestDatabaseHelper.createEmptyDatabase()
        try {
            UserTaskSchema.createSchema(source,targetVersion=32)
            source.setVersion(32)
            val tasks=SQLiteUserTaskRepository(source)
            val task=UserTask(TaskId("old"),"Existing",due=TaskDue.Day(TaskDueDate.parse("2026-10-24")),legacyPlanDate="raw",legacyPlanReviewPending=false,createdAt=1,updatedAt=2)
            tasks.save(task)
            TaskTagSchema.createSchema(source)
            source.setVersion(33)
            var sequence=0
            val repo=TaskTagRepository(source,{ "id-${++sequence}" },{3L})
            val tag=repo.create("Persistent")
            repo.setTags(task.id,setOf(tag.id))
            assertEquals(task,tasks.getById(task.id))
            DatabaseSnapshotter.initTargetSchema(source,backup)
            DatabaseSnapshotter.copyAllData(source,backup)
            assertEquals(TaskTagBackupInventory.checksum(source),TaskTagBackupInventory.checksum(backup))
            DatabaseSnapshotter.initTargetSchema(backup,restored)
            DatabaseSnapshotter.copyAllData(backup,restored)
            assertEquals(task,SQLiteUserTaskRepository(restored).getById(task.id))
            assertEquals(listOf(tag),TaskTagRepository(restored,{"unused"},{0L}).getTags(task.id))
            assertEquals(TaskTagBackupInventory.checksum(source),TaskTagBackupInventory.checksum(restored))
        } finally { source.close(); backup.close(); restored.close() }
    }
}
