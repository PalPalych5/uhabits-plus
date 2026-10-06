package org.isoron.uhabits.activities.habits.show.timer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.core.containers.migration.RawLegacyInventoryReader
import org.isoron.uhabits.core.focus.FocusPrimaryTargetType
import org.isoron.uhabits.core.focus.sqlite.SQLiteFocusSessionRepository
import org.isoron.uhabits.core.userTasks.TaskId
import org.isoron.uhabits.core.userTasks.UserTask
import org.isoron.uhabits.core.userTasks.sqlite.SQLiteUserTaskRepository
import org.isoron.uhabits.inject.HabitsApplicationComponent
import org.isoron.uhabits.inject.create
import org.isoron.uhabits.utils.DatabaseUtils
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Real Android manager and backup pipeline, operating only on an isolated dataset copy. */
@RunWith(AndroidJUnit4::class)
class FocusRuntimeBridgeAndroidTest {
    @Test fun experimentalManagerCapturesAllTargetsAndFullBackupDiscardsActiveRuntime() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as HabitsApplication
        val original = HabitsApplication.component
        assertTrue("Run against the disposable experimental QA dataset", original.session.isExperimental)
        val name = "pr12-runtime-${UUID.randomUUID()}.db"
        val temporary = context.getDatabasePath(name)
        val backup = File(context.cacheDir, "$name.backup")
        // Application's existing test mode clears its selected DB before this test starts.
        // Require an external disposable fixture; the QA harness preserves originals first.
        val fixture = File(requireNotNull(InstrumentationRegistry.getArguments().getString("focusFixture")))
        fixture.copyTo(temporary)
        val session = original.session.copy(databaseFilename = name, preferencesNamespace = "pr12_runtime_test")
        val component = HabitsApplicationComponent::class.create(appContext = context, dbFile = temporary, session = session)
        fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
        try {
            DatabaseUtils.closeDatabase()
            DatabaseUtils.initializeDatabase(context, name)
            main { HabitsApplication.component = component }
            val manager = component.timerSessionManager
            val facts = SQLiteFocusSessionRepository(component.db)
            val baseline = facts.getAll().size
            val legacyBefore = RawLegacyInventoryReader.read(component.db).snapshotSha256
            val habit = component.habitList.first { it.isNumerical }
            main { assertTrue(manager.startOrPause(habit)) }
            Thread.sleep(180)
            main { assertTrue(manager.startOrPause(habit)) }
            val paused = manager.snapshot().elapsedMillis
            Thread.sleep(220)
            assertEquals(paused, manager.snapshot().elapsedMillis)
            main { assertTrue(manager.startOrPause(habit)) }
            Thread.sleep(150)
            main { manager.finish(habit); manager.finish(habit) }
            assertEquals(baseline + 1, facts.getAll().size)
            val habitFact = facts.getAll().last()
            assertEquals(FocusPrimaryTargetType.HABIT, habitFact.primaryTargetType)
            assertEquals(habit.uuid, habitFact.habit?.uuid)
            assertTrue(habitFact.durationMs < habitFact.endedAt - habitFact.startedAt)

            val task = UserTask(TaskId("pr12-task"), "Bridge work", createdAt = 1, updatedAt = 1)
            val taskRepo = SQLiteUserTaskRepository(component.db)
            taskRepo.save(task)
            main { assertTrue(manager.startTask(task.id)) }
            Thread.sleep(120)
            main { manager.finishCurrent() }
            assertEquals(task, taskRepo.getById(task.id))
            val container = component.organizationFacade.containerQueries!!.roots().first()
            main { assertTrue(manager.startContainer(container.id)) }
            Thread.sleep(120)
            main { manager.finishCurrent() }
            assertEquals(baseline + 3, facts.getAll().size)
            assertEquals(legacyBefore, RawLegacyInventoryReader.read(component.db).snapshotSha256)
            assertEquals(setOf(FocusPrimaryTargetType.HABIT, FocusPrimaryTargetType.TASK, FocusPrimaryTargetType.CONTAINER),
                facts.getAll().takeLast(3).map { it.primaryTargetType }.toSet())
            assertTrue(facts.getAll().takeLast(3).all { it.organizationContext != null })
            assertFalse(manager.acceptsDatasetCallback("production"))
            assertFalse(manager.acceptsDatasetCallback(null))

            // Historical work remains finishable even if its live Habit is removed.
            main { assertTrue(manager.startOrPause(habit)) }
            Thread.sleep(100)
            main {
                component.habitList.remove(habit)
                assertTrue(manager.pauseOrResumeCurrent())
                assertTrue(manager.pauseOrResumeCurrent())
                manager.finishCurrent()
            }
            assertEquals(baseline + 4, facts.getAll().size)
            assertEquals(habit.uuid, facts.getAll().last().habit?.uuid)

            main { assertTrue(manager.startTask(task.id)) }
            Thread.sleep(100)
            main { manager.resetCurrent() }
            assertEquals(baseline + 4, facts.getAll().size)
            main { assertTrue(manager.startContainer(container.id)) }
            DatabaseUtils.createDatabaseSnapshot(context, backup)
            val backupDb = android.database.sqlite.SQLiteDatabase.openDatabase(backup.path, null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY)
            backupDb.use { db ->
                db.rawQuery("SELECT count(*) FROM FocusSessions", null).use { assertTrue(it.moveToFirst()); assertEquals(baseline + 4, it.getInt(0)) }
                db.rawQuery("SELECT count(*) FROM sqlite_master WHERE name='FocusCaptureJournal'", null)
                    .use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
            }
            assertTrue(DatabaseUtils.validateDatabaseBackup(backup).isValid)
            DatabaseUtils.restoreDatabaseFromBackup(context, backup)
            val restored = android.database.sqlite.SQLiteDatabase.openDatabase(temporary.path, null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY)
            restored.use { db ->
                db.rawQuery("SELECT count(*) FROM FocusSessions", null).use { assertTrue(it.moveToFirst()); assertEquals(baseline + 4, it.getInt(0)) }
                db.rawQuery("SELECT count(*) FROM sqlite_master WHERE name='FocusCaptureJournal'", null)
                    .use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
            }
            assertTrue(context.getSharedPreferences(session.preferencesNamespace + "_timer", 0).all.isEmpty())
        } finally {
            main { runCatching { component.timerSessionManager.dispose() }; HabitsApplication.component = original }
            runCatching { component.db.close() }
            DatabaseUtils.closeDatabase()
            DatabaseUtils.initializeDatabase(context, original.session.databaseFilename)
            context.getSharedPreferences(session.preferencesNamespace + "_timer", 0).edit().clear().commit()
            backup.delete()
            listOf(temporary, File(temporary.path + "-wal"), File(temporary.path + "-shm"), File(temporary.path + "-journal")).forEach { it.delete() }
        }
    }
}
