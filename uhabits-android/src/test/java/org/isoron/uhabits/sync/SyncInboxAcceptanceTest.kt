/* Copyright (C) 2026 Alinson Santos Xavier and contributors. GPL-3.0-or-later. */
package org.isoron.uhabits.sync

import kotlinx.coroutines.runBlocking
import org.isoron.platform.io.*
import org.isoron.uhabits.core.DATABASE_VERSION
import org.isoron.uhabits.core.database.HabitData
import org.isoron.uhabits.core.database.HabitRepository
import org.isoron.uhabits.core.userTasks.sqlite.UserTaskSchema
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/** File-backed SQLite acceptance checks; closing every connection models process restart. */
class SyncInboxAcceptanceTest {
    private val harness = SyncCoordinatorNextTest()

    // Use the same JUnit/SQLite infrastructure in a fresh JVM, with no shared connections or caches.
    class RestartProbe {
        @Test
        fun deletedTaskRemainsDeletedInFreshProcess() = runBlocking {
            val path = System.getProperty("sync.inbox.restart.database")
            org.junit.Assume.assumeNotNull(path)
            val db = JavaDatabaseOpener().open(path)
            val env = SyncCoordinatorNextTest().setupDeviceEnvironment(db,
                SyncCoordinatorNextTest.InMemorySyncBackend(), "restart-probe")
            try {
                assertTrue(env.modelFactory.syncTombstoneRepository.isDeleted("user_task", "task", 2000L))
                assertEquals(0, env.coordinator.replayInbox(ignoreBackoff = true))
                assertEquals(0, env.coordinator.applyRemoteEvents(listOf(RemoteSyncEvent(
                    1, "fresh-process-stale", "user_task", "task", "upsert",
                    """{"title":"Must not resurrect"}""", 1000L, "foreign"))))
                assertNull(db.querySingle("SELECT id FROM UserTasks WHERE id='task'") { it.getText(0) })
            } finally { env.coordinator.dispose(); db.close() }
        }
    }

    private fun verifyFreshProcess(file: File) {
        val classpath = System.getProperty("java.class.path")
        assertTrue("JUnit runtime classpath must be available", classpath.isNotEmpty())
        val javaExecutable = if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
        val process = ProcessBuilder(File(System.getProperty("java.home"), "bin/$javaExecutable").absolutePath,
            "-Djava.io.tmpdir=${file.parentFile.absolutePath}",
            "-Dsync.inbox.restart.database=${file.absolutePath}", "-cp", classpath,
            "org.junit.runner.JUnitCore", RestartProbe::class.java.name).apply {
                redirectErrorStream(true)
                environment()["TEMP"] = file.parentFile.absolutePath
                environment()["TMP"] = file.parentFile.absolutePath
            }.start()
        try {
            assertTrue("Fresh JVM must finish", process.waitFor(30, TimeUnit.SECONDS))
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(output, 0, process.exitValue())
            assertTrue(output, output.contains("OK (1 test)"))
        } finally { if (process.isAlive) process.destroyForcibly().waitFor() }
    }

    private suspend fun createDatabase(path: String): Database {
        val db = JavaDatabaseOpener().open(path)
        db.setVersion(8)
        val migrations = listOf(File("../uhabits-core/assets/main/migrations"),
            File("uhabits-core/assets/main/migrations")).first { it.exists() }
        db.migrateTo(DATABASE_VERSION) { File(migrations, "%02d.sql".format(it)).readText() }
        return db
    }

    private fun event(id: Long, type: String, uuid: String, payload: JSONObject,
                      operation: String = "upsert", timestamp: Long = id * 1000) = RemoteSyncEvent(
        logId = id, opUuid = "acceptance-$id", entityType = type, entityUuid = uuid,
        operationType = operation, payloadJson = payload.toString(), createdAt = timestamp,
        deviceId = "remote-device"
    )

    private suspend fun withDatabaseFile(block: suspend (File) -> Unit) {
        val directory = File("build/tmp/sync-inbox-acceptance").apply { mkdirs() }
        val file = File.createTempFile("acceptance-", ".sqlite", directory)
        try { block(file) } finally {
            listOf(file, File(file.path + "-wal"), File(file.path + "-shm"),
                File(file.path + "-journal")).forEach { it.delete() }
        }
    }

    @Test
    fun preInboxFileUpgradePreservesTasksHabitsAndImportsLegacyCursor() = runBlocking {
        withDatabaseFile { file ->
            var db = createDatabase(file.absolutePath)
            UserTaskSchema.createSchema(db)
            HabitRepository(db).insert(HabitData(name = "Existing habit", uuid = "legacy-habit"))
            db.run("INSERT INTO UserTasks(id,title,planned_date,created_at,updated_at) VALUES ('legacy-task','Existing task','2026-10-22',10,10)")
            for (table in listOf("SyncState", "SyncInbox", "SyncTombstones")) {
                assertNull(db.querySingle("SELECT name FROM sqlite_master WHERE name = ?", table) { it.getText(0) })
            }
            db.close()
            db = JavaDatabaseOpener().open(file.absolutePath)
            val env = harness.setupDeviceEnvironment(db, SyncCoordinatorNextTest.InMemorySyncBackend(),
                "upgrade-device", legacyCursor = 73L)
            try {
                for (table in listOf("SyncState", "SyncInbox", "SyncTombstones")) {
                    assertEquals(table, db.querySingle("SELECT name FROM sqlite_master WHERE name = ?", table) { it.getText(0) })
                }
                assertEquals("Existing habit", env.modelFactory.habitRepository.findByUuid("legacy-habit")!!.name)
                assertEquals("Existing task", db.querySingle("SELECT title FROM UserTasks WHERE id='legacy-task'") { it.getText(0) })
                assertEquals("2026-10-22", db.querySingle("SELECT planned_date FROM UserTasks WHERE id='legacy-task'") { it.getText(0) })
                assertEquals(73L, env.modelFactory.syncStateRepository.getCursor())
                env.modelFactory.syncStateRepository.setCursor(80L)
            } finally { env.coordinator.dispose(); db.close() }
            db = JavaDatabaseOpener().open(file.absolutePath)
            val reopened = harness.setupDeviceEnvironment(db, SyncCoordinatorNextTest.InMemorySyncBackend(),
                "upgrade-device", legacyCursor = 73L)
            try { assertEquals(80L, reopened.modelFactory.syncStateRepository.getCursor()) }
            finally { reopened.coordinator.dispose(); db.close() }
        }
    }

    @Test
    fun deferredTaskSequenceDeleteAndRestartCannotResurrectTask() = runBlocking {
        withDatabaseFile { file ->
            var db = createDatabase(file.absolutePath)
            var env = harness.setupDeviceEnvironment(db, SyncCoordinatorNextTest.InMemorySyncBackend(), "device")
            db.run("INSERT INTO UserTasks(id,title,planned_date,created_at,updated_at) VALUES ('task','Original','bad-date',1,1)")
            val first = event(1, "user_task", "task", JSONObject().put("title", "First"))
            val second = event(2, "user_task", "task", JSONObject().put("title", "Second"))
            try {
                assertEquals(2, env.coordinator.applyRemoteEvents(listOf(first, second)))
                assertEquals(2, env.modelFactory.syncInboxRepository.count())
                db.run("UPDATE UserTasks SET planned_date=NULL WHERE id='task'")
                assertEquals(2, env.coordinator.replayInbox(ignoreBackoff = true))
                assertEquals("Second", db.querySingle("SELECT title FROM UserTasks WHERE id='task'") { it.getText(0) })
                env.coordinator.applyRemoteEvents(listOf(event(3, "user_task", "task", JSONObject(), "delete")))
                assertEquals(0, env.modelFactory.syncInboxRepository.count())
            } finally { env.coordinator.dispose(); db.close() }
            verifyFreshProcess(file)
            db = JavaDatabaseOpener().open(file.absolutePath)
            env = harness.setupDeviceEnvironment(db, SyncCoordinatorNextTest.InMemorySyncBackend(), "device")
            try {
                assertEquals(0, env.coordinator.replayInbox(ignoreBackoff = true))
                assertEquals(0, env.coordinator.applyRemoteEvents(listOf(first, second)))
                assertNull(db.querySingle("SELECT id FROM UserTasks WHERE id='task'") { it.getText(0) })
                assertTrue(env.modelFactory.syncTombstoneRepository.isDeleted("user_task", "task", 2000L))
            } finally { env.coordinator.dispose(); db.close() }
        }
    }

    @Test
    fun placementAndEntryDeferredUntilHabitArrivesSurviveRestart() = runBlocking {
        withDatabaseFile { file ->
            var db = createDatabase(file.absolutePath)
            var env = harness.setupDeviceEnvironment(db, SyncCoordinatorNextTest.InMemorySyncBackend(), "device")
            try {
                val placement = event(1, "habit_placement", "parent", JSONObject()
                    .put("habit_uuid", "parent").put("container_uuid", JSONObject.NULL)
                    .put("local_order", 7).put("origin", "USER_CHANGE"))
                val entry = event(2, "entry", "entry", JSONObject().put("habit_uuid", "parent")
                    .put("entry_date", 1700000000000L).put("value", 2000).put("notes", "Retained"))
                val entryOp = event(4, "entry_op", "entry-operation", JSONObject().put("habit_uuid", "parent")
                    .put("entry_date", 1700000000001L).put("delta_value", 3000).put("op_type", "ADD"))
                assertEquals(3, env.coordinator.applyRemoteEvents(listOf(placement, entry, entryOp)))
                assertEquals(3, env.modelFactory.syncInboxRepository.count())
            } finally { env.coordinator.dispose(); db.close() }
            db = JavaDatabaseOpener().open(file.absolutePath)
            env = harness.setupDeviceEnvironment(db, SyncCoordinatorNextTest.InMemorySyncBackend(), "device")
            try {
                assertEquals(3, env.modelFactory.syncInboxRepository.count())
                env.coordinator.applyRemoteEvents(listOf(event(3, "habit", "parent", JSONObject()
                    .put("name", "Parent").put("color", 1).put("type", 0)
                    .put("freq_num", 1).put("freq_den", 1).put("target_value", 0.0)
                    .put("target_type", 0).put("unit", "").put("block_uuid", JSONObject.NULL), "create")))
                assertEquals(3, env.coordinator.replayInbox(ignoreBackoff = true))
                assertEquals(0, env.modelFactory.syncInboxRepository.count())
                assertEquals(7, db.querySingle("SELECT local_order FROM HabitPlacements") { it.getInt(0) })
                val savedEntry = env.modelFactory.entryRepository.findByUuid("entry")!!
                assertEquals(2000, savedEntry.value)
                assertEquals("Retained", savedEntry.notes)
                assertEquals(env.modelFactory.habitRepository.findByUuid("parent")!!.id, savedEntry.habitId)
                assertNotNull(env.modelFactory.entryOpRepository.findByOpUuid("entry-operation"))
                assertEquals(3000, env.modelFactory.entryRepository.findByUuid("parent:1700000000001")!!.value)
            } finally { env.coordinator.dispose(); db.close() }
        }
    }
}
