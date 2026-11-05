package org.isoron.uhabits.core.production.migration

import kotlinx.coroutines.runBlocking
import org.isoron.platform.io.JavaDatabaseOpener
import org.isoron.platform.io.TestDatabaseHelper
import org.isoron.platform.io.getVersion
import org.isoron.platform.io.setVersion
import org.isoron.platform.io.migrateTo
import org.isoron.platform.io.queryString
import org.isoron.platform.io.run
import org.isoron.uhabits.core.containers.migration.SemanticMigrationFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ExportV30FixtureTest {

    private val opener = JavaDatabaseOpener()

    @Test
    fun exportV30FixtureForEmulator() = runBlocking {
        val targetFile = File("D:/DevData/Temp/v30_dev_test.db")
        if (targetFile.exists()) targetFile.delete()
        targetFile.parentFile.mkdirs()

        val db = opener.open(targetFile.absolutePath)
        try {
            db.setVersion(8)
            db.migrateTo(29) { TestDatabaseHelper.loadMigrationSQL(it) }
            SemanticMigrationFixtures.seedAllFixtures(db)

            val migResult = ProductionNextSchemaMigrator.migrate(
                db = db,
                cutoverTimestamp = 1700000000000L,
                sourceFileSha256 = "aa".repeat(32),
                sourceSchemaSha256 = "bb".repeat(32),
                runtimeAuthority = "NEXT",
                authorityEpoch = 1L,
            )
            assertTrue(migResult is ProductionNextMigrationResult.Success)
            assertEquals(30, db.getVersion())

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

            addTask("t-deadline-only", "Deadline Only", deadline = 1792432800000L)
            addTask("t-plan-future", "Future Plan Only", plannedDate = "2029-12-31")
            addTask("t-plan-past", "Past Plan Only", plannedDate = "2020-01-15")
            addTask("t-both-future", "Both Future", plannedDate = "2029-12-31", deadline = 1792432800000L)
            addTask("t-both-conflict", "Both Conflicting", plannedDate = "2020-01-15", deadline = 1792432800000L)
            addTask("t-corrupt-plan", "Corrupt Plan", plannedDate = "not-a-date")
            addTask("t-undated", "Undated")
            addTask("t-completed", "Completed Task", status = "COMPLETED", deadline = 1792432800000L, completedAt = 1700000500000L)
            addTask("t-subtask", "Subtask", parentTaskId = "t-deadline-only", sortOrder = 1)
            insertTask.finalize()

            db.run(
                """
                INSERT INTO FocusSessions (
                    id, started_at, ended_at, duration_ms, task_id, source, created_at, updated_at, revision
                ) VALUES ('session-1', 1700000000000, 1700001500000, 1500000, 't-deadline-only', 'TIMER', 1700000000000, 1700001500000, 1)
                """.trimIndent()
            )
            db.run(
                """
                INSERT INTO FocusSessions (
                    id, started_at, ended_at, duration_ms, task_id, source, created_at, updated_at, revision
                ) VALUES ('session-2', 1700002000000, 1700005000000, 3000000, 't-plan-future', 'TIMER', 1700002000000, 1700005000000, 1)
                """.trimIndent()
            )

            db.run(
                """
                INSERT INTO SyncQueue (op_uuid, entity_type, entity_uuid, operation_type, payload_json, created_at, device_id)
                VALUES ('q-task-legacy', 'user_task', 't-plan-future', 'upsert', '{"id":"t-plan-future","title":"Future Plan Only","planned_date":"2029-12-31"}', 1700000000000, 'dev-1')
                """.trimIndent()
            )
            db.run(
                """
                INSERT INTO SyncQueue (op_uuid, entity_type, entity_uuid, operation_type, payload_json, created_at, device_id)
                VALUES ('q-habit-update', 'habit', 'h-uuid-1', 'upsert', '{"id":"h-uuid-1","name":"Чтение"}', 1700000000000, 'dev-1')
                """.trimIndent()
            )

            assertEquals("ok", db.queryString("PRAGMA integrity_check"))
            assertEquals(30, db.getVersion())
        } finally {
            db.close()
        }
    }
}
