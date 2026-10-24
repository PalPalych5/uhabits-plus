package org.isoron.uhabits.core.userTasks

import java.io.File
import kotlinx.coroutines.runBlocking
import org.isoron.platform.io.JavaDatabaseOpener
import org.isoron.platform.io.TestDatabaseHelper
import org.isoron.platform.io.getVersion
import org.isoron.platform.io.migrateTo
import org.isoron.platform.io.queryString
import org.isoron.platform.io.run
import org.isoron.platform.io.runInTransaction
import org.isoron.platform.io.setVersion
import org.isoron.uhabits.core.userTasks.sqlite.UserTaskSchema
import org.isoron.uhabits.core.userTasks.tags.TaskTagRepository
import org.isoron.uhabits.core.userTasks.tags.TaskTagSchema
import org.isoron.uhabits.core.userTasks.tags.TaskTagSyncGate
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class TaskTagMigrationTest {
    @get:Rule val temp=TemporaryFolder()

    @Test fun upgradesInstalledFileWithoutResetAndPersistsAfterReopen() = runBlocking {
        val file=File(temp.newFolder(),"installed-dev.db")
        val opener=JavaDatabaseOpener()
        var db=opener.open(file.absolutePath)
        try {
            UserTaskSchema.createSchema(db,targetVersion=32)
            db.run("CREATE TABLE Habits(id INTEGER PRIMARY KEY, name TEXT NOT NULL)")
            db.run("INSERT INTO Habits VALUES(42,'Keep habit')")
            db.run("INSERT INTO UserTasks(id,title,created_at,updated_at,planned_date,legacy_plan_review_pending,due_kind,due_date) VALUES('keep','Keep task',1,2,'raw-legacy',0,'DAY','2026-10-24')")
            db.setVersion(32)
            db.runInTransaction { db.migrateTo(33) { TestDatabaseHelper.loadMigrationSQL(it) } }
            assertEquals(33,db.getVersion())
            TaskTagSchema.createSchema(db)
            var sequence=0
            val repo=TaskTagRepository(db,{"persist-${++sequence}"},{3L})
            val tag=repo.create("Across restarts")
            repo.setTags(TaskId("keep"),setOf(tag.id))
            assertTrue(TaskTagSyncGate.hasPendingChanges(db))
            val revision=repo.revision
            db.close()
            db=opener.open(file.absolutePath)
            val reopened=TaskTagRepository(db,{"unused"},{4L})
            assertEquals(setOf(tag.id),reopened.getTagIds(TaskId("keep")))
            assertEquals(revision,reopened.revision)
            assertEquals("Keep habit",db.queryString("SELECT name FROM Habits WHERE id=42"))
            assertEquals("Keep task|DAY|2026-10-24|raw-legacy|0",db.queryString("SELECT title||'|'||due_kind||'|'||due_date||'|'||planned_date||'|'||legacy_plan_review_pending FROM UserTasks WHERE id='keep'"))
            assertTrue(TaskTagSyncGate.hasPendingChanges(db))
        } finally { db.close() }
    }
}
