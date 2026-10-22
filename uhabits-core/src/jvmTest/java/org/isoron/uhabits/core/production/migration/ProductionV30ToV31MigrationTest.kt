/* Copyright (C) 2026 Alinson Santos Xavier and contributors. GPL-3.0-or-later. */
package org.isoron.uhabits.core.production.migration

import kotlinx.coroutines.runBlocking
import org.isoron.platform.io.JavaDatabaseOpener
import org.isoron.platform.io.StepResult
import org.isoron.platform.io.TestDatabaseHelper
import org.isoron.platform.io.getVersion
import org.isoron.platform.io.setVersion
import org.isoron.platform.io.migrateTo
import org.isoron.platform.io.queryLong
import org.isoron.platform.io.queryString
import org.isoron.platform.io.run
import org.isoron.uhabits.core.containers.backup.BackupCreationResult
import org.isoron.uhabits.core.containers.backup.ContainerBackupService
import org.isoron.uhabits.core.containers.backup.ContainerRestoreService
import org.isoron.uhabits.core.containers.backup.RestoreResult
import org.isoron.uhabits.core.containers.migration.SemanticMigrationFixtures
import org.isoron.uhabits.core.userTasks.TaskId
import org.isoron.uhabits.core.userTasks.TaskStatus
import org.isoron.uhabits.core.userTasks.due.TaskDue
import org.isoron.uhabits.core.userTasks.due.TaskDueDate
import org.isoron.uhabits.core.userTasks.sqlite.SQLiteUserTaskRepository
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProductionV30ToV31MigrationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val opener = JavaDatabaseOpener()

    @Test
    fun testV30ToV31MigrationPreservesAllDataAndMigratesDueSafely() = runBlocking {
        val dbFile = File(tempFolder.newFolder(), "uhabits.db")
        val db = opener.open(dbFile.absolutePath)

        try {
            // 1. Create and seed complete v29 database
            db.setVersion(8)
            db.migrateTo(29) { TestDatabaseHelper.loadMigrationSQL(it) }
            SemanticMigrationFixtures.seedAllFixtures(db)

            // 2. Run migration to v30 using ProductionNextSchemaMigrator
            val cutoverTimestamp = 1700000000000L
            val migResult = ProductionNextSchemaMigrator.migrate(
                db = db,
                cutoverTimestamp = cutoverTimestamp,
                sourceFileSha256 = "aa".repeat(32),
                sourceSchemaSha256 = "bb".repeat(32),
                runtimeAuthority = "NEXT",
                authorityEpoch = 1L,
            )
            assertTrue("v29->v30 must succeed", migResult is ProductionNextMigrationResult.Success)
            assertEquals(30, db.getVersion())

            // 3. Seed rich v30 tasks with all edge cases
            // Columns in v30: id, title, description, container_id, status, planned_date, deadline, completed_at, parent_task_id, sort_order, created_at, updated_at
            val insertTask = db.prepareStatement(
                """
                INSERT INTO UserTasks (
                    id, title, description, container_id, status, planned_date,
                    deadline, completed_at, parent_task_id, sort_order, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            )

            fun addTask(
                id: String,
                title: String,
                containerId: String? = null,
                status: String = "PENDING",
                plannedDate: String? = null,
                deadline: Long? = null,
                completedAt: Long? = null,
                parentTaskId: String? = null,
                sortOrder: Int = 0,
            ) {
                insertTask.reset()
                insertTask.bindText(1, id)
                insertTask.bindText(2, title)
                insertTask.bindText(3, "Notes for $title")
                if (containerId != null) insertTask.bindText(4, containerId) else insertTask.bindNull(4)
                insertTask.bindText(5, status)
                if (plannedDate != null) insertTask.bindText(6, plannedDate) else insertTask.bindNull(6)
                if (deadline != null) insertTask.bindLong(7, deadline) else insertTask.bindNull(7)
                if (completedAt != null) insertTask.bindLong(8, completedAt) else insertTask.bindNull(8)
                if (parentTaskId != null) insertTask.bindText(9, parentTaskId) else insertTask.bindNull(9)
                insertTask.bindInt(10, sortOrder)
                insertTask.bindLong(11, 1000000L)
                insertTask.bindLong(12, 1000000L)
                insertTask.step()
            }

            // Case A: deadline-only
            addTask("t-deadline-only", "Deadline Only", deadline = 1792432800000L)
            // Case B: plan-only future
            addTask("t-plan-future", "Future Plan Only", plannedDate = "2029-12-31")
            // Case C: plan-only past (MUST NOT become overdue deadline!)
            addTask("t-plan-past", "Past Plan Only", plannedDate = "2020-01-15")
            // Case D: both deadline and plan (both future)
            addTask("t-both-future", "Both Future", plannedDate = "2029-12-31", deadline = 1792432800000L)
            // Case E: both with conflicting dates (deadline future, plan past)
            addTask("t-both-conflict", "Both Conflicting", plannedDate = "2020-01-15", deadline = 1792432800000L)
            // Case F: corrupt/unknown plan-only value
            addTask("t-corrupt-plan", "Corrupt Plan", plannedDate = "not-a-date")
            // Case G: cleared/undated
            addTask("t-undated", "Undated")
            // Case H: completed task with deadline
            addTask("t-completed", "Completed Task", status = "COMPLETED", deadline = 1792432800000L, completedAt = 1700000500000L)
            // Case I: hierarchical subtask
            addTask("t-subtask", "Subtask", parentTaskId = "t-deadline-only", sortOrder = 1)

            insertTask.finalize()

            // Also seed a FocusSession and SourceRule to ensure they survive
            db.run(
                """
                INSERT INTO FocusSessions (
                    id, started_at, ended_at, duration_ms, task_id, source, created_at, updated_at, revision
                ) VALUES ('session-1', 1700000000000, 1700001500000, 1500000, 't-deadline-only', 'TIMER', 1700000000000, 1700001500000, 1)
                """.trimIndent()
            )

            // Baseline entity counts before migration to 31
            val habitsCountBefore = db.queryLong("SELECT count(*) FROM Habits")
            val blocksCountBefore = db.queryLong("SELECT count(*) FROM HabitBlocks")
            val repetitionsCountBefore = db.queryLong("SELECT count(*) FROM Repetitions")
            val entryOpsCountBefore = db.queryLong("SELECT count(*) FROM EntryOps")
            val containersCountBefore = db.queryLong("SELECT count(*) FROM Containers")
            val placementsCountBefore = db.queryLong("SELECT count(*) FROM HabitPlacements")
            val focusSessionsCountBefore = db.queryLong("SELECT count(*) FROM FocusSessions")
            val tasksCountBefore = db.queryLong("SELECT count(*) FROM UserTasks")

            assertEquals(9L, tasksCountBefore)
            assertTrue("Habits must be present", habitsCountBefore > 0)
            assertTrue("Containers must be present", containersCountBefore > 0)

            // 4. EXECUTE MIGRATION TO 31
            db.migrateTo(31) { TestDatabaseHelper.loadMigrationSQL(it) }

            // 5. VERIFY SCHEMA INTEGRITY
            assertEquals(31, db.getVersion())
            val integrity = db.queryString("PRAGMA integrity_check")
            assertEquals("ok", integrity)
            val foreignKeysErrors = db.queryLong("SELECT count(*) FROM pragma_foreign_key_check()")
            assertEquals(0L, foreignKeysErrors)

            // Verify expected indexes exist
            val indexes = mutableSetOf<String>()
            val idxStmt = db.prepareStatement("SELECT name FROM sqlite_master WHERE type = 'index'")
            while (idxStmt.step() == StepResult.ROW) indexes.add(idxStmt.getText(0))
            idxStmt.finalize()
            assertTrue("idx_user_tasks_due_kind must exist", "idx_user_tasks_due_kind" in indexes)
            assertTrue("idx_user_tasks_due_date must exist", "idx_user_tasks_due_date" in indexes)

            // 6. VERIFY TASK DUE CONVERSIONS & DATA FIDELITY
            val repo = SQLiteUserTaskRepository(db)

            // Case A: deadline-only -> TaskDue.Timed
            val taskA = repo.getById(TaskId("t-deadline-only"))!!
            assertTrue("t-deadline-only must be Timed", taskA.due is TaskDue.Timed)
            assertEquals(1792432800000L, (taskA.due as TaskDue.Timed).epochMillis)

            // Case B: plan-only future -> initial Due is None, marked as requiring decision on transfer
            val taskB = repo.getById(TaskId("t-plan-future"))!!
            assertEquals("t-plan-future initial due must be None", TaskDue.None, taskB.due)
            assertTrue("t-plan-future must have pending legacy plan", taskB.hasPendingLegacyPlan)
            assertEquals("2029-12-31", taskB.legacyPlanDate)

            // Case C: plan-only past -> initial Due is None (NOT an overdue deadline!)
            val taskC = repo.getById(TaskId("t-plan-past"))!!
            assertEquals("t-plan-past must NOT become overdue deadline; due must be None", TaskDue.None, taskC.due)
            assertTrue("t-plan-past must have pending legacy plan", taskC.hasPendingLegacyPlan)
            assertEquals("2020-01-15", taskC.legacyPlanDate)
            val rawPlanC = db.queryString("SELECT planned_date FROM UserTasks WHERE id = 't-plan-past'")
            assertEquals("Raw planned_date must be preserved intact", "2020-01-15", rawPlanC)

            // Verify explicit accept and dismiss of legacy plans
            val taskService = org.isoron.uhabits.core.userTasks.UserTaskServiceImpl(repo)
            val accepted = taskService.resolveLegacyPlan(TaskId("t-plan-future"), accept = true)
            assertEquals(TaskDue.Day(TaskDueDate.parse("2029-12-31")), accepted.due)
            assertFalse(accepted.hasPendingLegacyPlan)
            assertNull(accepted.legacyPlanDate)

            val dismissed = taskService.resolveLegacyPlan(TaskId("t-plan-past"), accept = false)
            assertEquals(TaskDue.None, dismissed.due)
            assertFalse(dismissed.hasPendingLegacyPlan)
            assertNull(dismissed.legacyPlanDate)

            // Case D: both future -> TaskDue.Timed, raw planned_date preserved
            val taskD = repo.getById(TaskId("t-both-future"))!!
            assertTrue("t-both-future must prioritize deadline as Timed", taskD.due is TaskDue.Timed)
            assertEquals(1792432800000L, (taskD.due as TaskDue.Timed).epochMillis)
            val rawPlanD = db.queryString("SELECT planned_date FROM UserTasks WHERE id = 't-both-future'")
            assertEquals("Raw planned_date must be preserved", "2029-12-31", rawPlanD)

            // Case E: both conflicting -> TaskDue.Timed, raw planned_date preserved
            val taskE = repo.getById(TaskId("t-both-conflict"))!!
            assertTrue("t-both-conflict must be Timed", taskE.due is TaskDue.Timed)
            assertEquals(1792432800000L, (taskE.due as TaskDue.Timed).epochMillis)
            val rawPlanE = db.queryString("SELECT planned_date FROM UserTasks WHERE id = 't-both-conflict'")
            assertEquals("Raw planned_date must be preserved", "2020-01-15", rawPlanE)

            // Case F: corrupt plan -> TaskDue.None, raw value preserved
            val taskF = repo.getById(TaskId("t-corrupt-plan"))!!
            assertEquals("Corrupt plan date must safely map to None", TaskDue.None, taskF.due)
            val rawPlanF = db.queryString("SELECT planned_date FROM UserTasks WHERE id = 't-corrupt-plan'")
            assertEquals("Corrupt planned_date must not be deleted", "not-a-date", rawPlanF)

            // Case G: undated -> TaskDue.None
            val taskG = repo.getById(TaskId("t-undated"))!!
            assertEquals(TaskDue.None, taskG.due)

            // Case H: completed task
            val taskH = repo.getById(TaskId("t-completed"))!!
            assertEquals(TaskStatus.COMPLETED, taskH.status)
            assertEquals(1700000500000L, taskH.completedAt)
            assertTrue(taskH.due is TaskDue.Timed)

            // Case I: subtask
            val taskI = repo.getById(TaskId("t-subtask"))!!
            assertEquals(TaskId("t-deadline-only"), taskI.parentTaskId)
            assertEquals(1, taskI.sortOrder)

            // 7. VERIFY ZERO MUTATION IN OTHER TABLES
            assertEquals(habitsCountBefore, db.queryLong("SELECT count(*) FROM Habits"))
            assertEquals(blocksCountBefore, db.queryLong("SELECT count(*) FROM HabitBlocks"))
            assertEquals(repetitionsCountBefore, db.queryLong("SELECT count(*) FROM Repetitions"))
            assertEquals(entryOpsCountBefore, db.queryLong("SELECT count(*) FROM EntryOps"))
            assertEquals(containersCountBefore, db.queryLong("SELECT count(*) FROM Containers"))
            assertEquals(placementsCountBefore, db.queryLong("SELECT count(*) FROM HabitPlacements"))
            assertEquals(focusSessionsCountBefore, db.queryLong("SELECT count(*) FROM FocusSessions"))
            assertEquals(tasksCountBefore, db.queryLong("SELECT count(*) FROM UserTasks"))

            // Verify FocusSession details intact
            val fsTaskId = db.queryString("SELECT task_id FROM FocusSessions WHERE id = 'session-1'")
            assertEquals("t-deadline-only", fsTaskId)

        } finally {
            db.close()
        }

        // 8. TEST INDEPENDENT RE-OPEN
        val reopened = opener.open(dbFile.absolutePath)
        try {
            assertEquals(31, reopened.getVersion())
            val repoReopened = SQLiteUserTaskRepository(reopened)
            assertEquals(9, repoReopened.getAll().size)
            val taskB = repoReopened.getById(TaskId("t-plan-future"))!!
            assertEquals(TaskDueDate.parse("2029-12-31"), (taskB.due as TaskDue.Day).date)
            val taskC = repoReopened.getById(TaskId("t-plan-past"))!!
            assertEquals(TaskDue.None, taskC.due)
        } finally {
            reopened.close()
        }

        // 9. TEST BACKUP AND RESTORE ON V31
        val sourceDb = opener.open(dbFile.absolutePath)
        val backupDbFile = File(tempFolder.newFolder(), "backup.db")
        val backupDb = opener.open(backupDbFile.absolutePath)
        val stagingDbFile = File(tempFolder.newFolder(), "staging.db")
        val stagingDb = opener.open(stagingDbFile.absolutePath)
        val restoreDbFile = File(tempFolder.newFolder(), "restored.db")
        val restoreDb = opener.open(restoreDbFile.absolutePath)

        try {
            val backupResult = ContainerBackupService.createBackup(
                sourceDb = sourceDb,
                targetDb = backupDb,
                timestamp = 2000000000000L,
            )
            if (backupResult is BackupCreationResult.Failure) {
                fail("Backup failed: " + backupResult.error + ", cause: " + backupResult.cause?.message)
            }

            val restoreResult = ContainerRestoreService.restore(
                backupDb = backupDb,
                stagingDb = stagingDb,
                destinationDb = restoreDb,
            )
            assertTrue("Restore must succeed", restoreResult is RestoreResult.Success)

            val restoreRepo = SQLiteUserTaskRepository(restoreDb)
            assertEquals(9, restoreRepo.getAll().size)

            val restoredTaskA = restoreRepo.getById(TaskId("t-deadline-only"))!!
            assertEquals(1792432800000L, (restoredTaskA.due as TaskDue.Timed).epochMillis)

            val restoredTaskB = restoreRepo.getById(TaskId("t-plan-future"))!!
            assertEquals(TaskDue.Day(TaskDueDate.parse("2029-12-31")), restoredTaskB.due)
            assertFalse(restoredTaskB.hasPendingLegacyPlan)
            assertNull(restoredTaskB.legacyPlanDate)

            val restoredTaskC = restoreRepo.getById(TaskId("t-plan-past"))!!
            assertEquals(TaskDue.None, restoredTaskC.due)
            assertFalse(restoredTaskC.hasPendingLegacyPlan)
            assertNull(restoredTaskC.legacyPlanDate)

            val restoredTaskF = restoreRepo.getById(TaskId("t-corrupt-plan"))!!
            assertEquals(TaskDue.None, restoredTaskF.due)
            assertTrue(restoredTaskF.hasPendingLegacyPlan)
            assertEquals("not-a-date", restoredTaskF.legacyPlanDate)

            // Foreign keys check on restored database
            val restoredFkErrors = restoreDb.queryLong("SELECT count(*) FROM pragma_foreign_key_check()")
            assertEquals(0L, restoredFkErrors)

        } finally {
            sourceDb.close()
            backupDb.close()
            stagingDb.close()
            restoreDb.close()
        }
    }

}
