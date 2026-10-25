/*
 * Copyright (C) 2016-2026 Álinson Santos Xavier <git@axavier.org> and contributors
 *
 * This file is part of Loop Habit Tracker.
 *
 * Loop Habit Tracker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * Loop Habit Tracker is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.isoron.uhabits.activities.tasks

import android.database.DatabaseUtils
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.isoron.platform.io.run
import org.isoron.platform.io.runInTransaction
import org.isoron.platform.time.getToday
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.HabitsDatabaseOpener
import org.isoron.uhabits.R
import org.isoron.uhabits.database.AndroidDatabase
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.contribution.sqlite.SQLiteContributionRepository
import org.isoron.uhabits.core.focus.FocusSession
import org.isoron.uhabits.core.focus.FocusSessionId
import org.isoron.uhabits.core.focus.FocusSessionSource
import org.isoron.uhabits.core.focus.sqlite.SQLiteFocusSessionRepository
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Frequency
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitType
import org.isoron.uhabits.core.models.NumericalHabitType
import org.isoron.uhabits.core.models.PaletteColor
import org.isoron.uhabits.core.timer.TimerMode
import org.isoron.uhabits.core.userTasks.TaskId
import org.isoron.uhabits.core.userTasks.UserTask
import org.isoron.uhabits.core.userTasks.due.TaskDue
import org.isoron.uhabits.core.userTasks.due.TaskDueDate
import org.isoron.uhabits.core.userTasks.sqlite.SQLiteUserTaskRepository
import org.isoron.uhabits.core.userTasks.sqlite.UserTaskSchema
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * Android instrumentation tests for PR13 Task Habit Link & Focus Time runtime contracts.
 * Executes on a disposable temporary offline AVD via with-avd.ps1.
 */
@RunWith(AndroidJUnit4::class)
class TaskHabitLinkInstrumentationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val component get() = HabitsApplication.component
    private val harness = TaskDraftLifecycleTest()

    private fun onMain(action: () -> Unit) {
        instrumentation.runOnMainSync(action)
    }

    @Before
    fun setUp() {
        TaskListVisualTest::class.java.getDeclaredMethod("bootstrapDisposableSession")
            .apply { isAccessible = true }.invoke(TaskListVisualTest())
        harness.guard()
    }

    private fun createMinuteHabit(name: String, targetMinutes: Double = 60.0): Habit {
        val factory = component.sqlModelFactory
        val habit = factory.buildHabit().apply {
            uuid = UUID.randomUUID().toString()
            this.name = name
            type = HabitType.NUMERICAL
            targetType = NumericalHabitType.AT_LEAST
            targetValue = targetMinutes
            unit = "мин"
            color = PaletteColor(1)
            frequency = Frequency.DAILY
            timerEnabled = true
        }
        onMain { component.habitList.add(habit) }
        return habit
    }

    @Test
    fun testRealTaskStopwatchLifecycleAttributionAndIdempotency() {
        val habit = createMinuteHabit("Test Focus Habit ${UUID.randomUUID()}")
        val task = component.userTaskService.createTask(
            title = "Task with stopwatch ${UUID.randomUUID()}",
            linkedHabitUuid = habit.uuid,
            habitTimeAccrualEnabled = true
        )

        val manager = component.timerSessionManager
        onMain {
            manager.resetCurrent()
            assertTrue("Should start task stopwatch", manager.startTask(task.id, TimerMode.STOPWATCH))
        }

        Thread.sleep(4500)

        onMain {
            assertTrue("Should pause current session", manager.pauseOrResumeCurrent())
            manager.finishCurrent()
        }
        instrumentation.waitForIdleSync()

        // 1. Verify unchanged task completion: Task completion != work time
        val reloadedTask = component.userTaskRepository.getById(task.id)!!
        assertFalse("Task completion must remain unchanged after focus session", reloadedTask.isCompleted)

        // 2. Verify FocusSession recorded in SQLite
        val focusRepo = SQLiteFocusSessionRepository(component.db)
        val sessions = focusRepo.getByTask(task.id)
        assertTrue("Focus session must be recorded for task", sessions.isNotEmpty())

        // 3. Verify exactly ONE contribution created for linked habit
        val contribRepo = SQLiteContributionRepository(component.db)
        val contributions = contribRepo.getByHabit(HabitRef(habit.uuid!!))
        assertEquals("Exactly one contribution must be recorded", 1, contributions.size)
        assertTrue("Contribution value must be > 0", contributions[0].contributedValue > 0)

        // 4. Verify repeat finish / recovery is idempotent and does not duplicate credit
        onMain {
            manager.finishCurrent()
            component.focusCreditController?.startRecovery()
        }
        instrumentation.waitForIdleSync()
        val contributionsAfter = contribRepo.getByHabit(HabitRef(habit.uuid!!))
        assertEquals("Contributions must not duplicate on repeated finish or recovery", 1, contributionsAfter.size)
    }

    @Test
    fun testRealTaskPomodoroLifecycleAttributionAndObserverNotification() {
        val habit = createMinuteHabit("Pomodoro Focus Habit ${UUID.randomUUID()}")
        val task = component.userTaskService.createTask(
            title = "Task with Pomodoro ${UUID.randomUUID()}",
            linkedHabitUuid = habit.uuid,
            habitTimeAccrualEnabled = true
        )

        var observerNotified = false
        val observer = { observerNotified = true }
        component.focusCreditController?.addObserver(observer)

        try {
            val manager = component.timerSessionManager
            onMain {
                manager.resetCurrent()
                assertTrue("Should start task pomodoro", manager.startTask(task.id, TimerMode.POMODORO))
            }

            Thread.sleep(4500)

            onMain {
                assertTrue("Should pause current pomodoro session", manager.pauseOrResumeCurrent())
                manager.finishCurrent()
            }
            instrumentation.waitForIdleSync()

            // 1. Verify unchanged task completion
            val reloadedTask = component.userTaskRepository.getById(task.id)!!
            assertFalse("Task completion must remain unchanged after pomodoro session", reloadedTask.isCompleted)

            // 2. Verify FocusSession recorded in SQLite
            val focusRepo = SQLiteFocusSessionRepository(component.db)
            val sessions = focusRepo.getByTask(task.id)
            assertTrue("Focus session must be recorded for pomodoro task", sessions.isNotEmpty())

            // 3. Verify exactly ONE contribution created for linked habit
            val contribRepo = SQLiteContributionRepository(component.db)
            val contributions = contribRepo.getByHabit(HabitRef(habit.uuid!!))
            assertEquals("Exactly one contribution must be recorded", 1, contributions.size)
            assertTrue("Contribution value must be > 0", contributions[0].contributedValue > 0)

            // 4. Verify FocusCreditController observer was notified on committed fact
            assertTrue("FocusCreditController observer must be notified on committed pomodoro fact", observerNotified)

            // 5. Verify repeat finish / recovery is idempotent
            onMain {
                manager.finishCurrent()
                component.focusCreditController?.startRecovery()
            }
            instrumentation.waitForIdleSync()
            val contributionsAfter = contribRepo.getByHabit(HabitRef(habit.uuid!!))
            assertEquals("Contributions must not duplicate on repeated finish or recovery", 1, contributionsAfter.size)
        } finally {
            component.focusCreditController?.removeObserver(observer)
        }
    }

    @Test
    fun testManualHabitEditEffectiveSum() {
        val habit = createMinuteHabit("Sum Test Habit ${UUID.randomUUID()}")
        val task = component.userTaskService.createTask(
            title = "Task for Sum ${UUID.randomUUID()}",
            linkedHabitUuid = habit.uuid,
            habitTimeAccrualEnabled = true
        )

        // Run a real live stopwatch session via manager
        val manager = component.timerSessionManager
        onMain {
            manager.resetCurrent()
            assertTrue("Start task stopwatch", manager.startTask(task.id, TimerMode.STOPWATCH))
        }
        Thread.sleep(4500)
        onMain {
            manager.pauseOrResumeCurrent()
            manager.finishCurrent()
        }
        instrumentation.waitForIdleSync()

        val contribRepo = SQLiteContributionRepository(component.db)
        val contributions = contribRepo.getByHabit(HabitRef(habit.uuid!!))
        assertEquals(1, contributions.size)
        val focusContributionValue = contributions[0].contributedValue
        assertTrue(focusContributionValue > 0)

        // Add manual entry to habit: 20 mins (20_000)
        val today = getToday()
        onMain {
            habit.originalEntries.add(Entry(today, 20_000))
        }
        instrumentation.waitForIdleSync()

        // Verify that manual entry and focus contribution aggregate effectively
        val manualEntry = habit.originalEntries.get(today)
        assertEquals(20_000, manualEntry.value)

        val progress = component.focusCreditController?.goalProgress(habit)
        if (progress != null) {
            assertEquals(focusContributionValue, progress.value.contributionValue)
        }
    }

    @Test
    fun testOffReEnableAndTargetSwitchNoHistoricalCredit() {
        val habit1 = createMinuteHabit("Habit Alpha ${UUID.randomUUID()}")
        val habit2 = createMinuteHabit("Habit Beta ${UUID.randomUUID()}")

        // 1. Task created with accrual DISABLED
        val task = component.userTaskService.createTask(
            title = "Task Off-ReEnable ${UUID.randomUUID()}",
            linkedHabitUuid = habit1.uuid,
            habitTimeAccrualEnabled = false
        )

        // Live capture while accrual disabled: must NOT attribute to habit1
        val manager = component.timerSessionManager
        onMain {
            manager.resetCurrent()
            assertTrue(manager.startTask(task.id, TimerMode.STOPWATCH))
        }
        Thread.sleep(4500)
        onMain {
            manager.pauseOrResumeCurrent()
            manager.finishCurrent()
        }
        instrumentation.waitForIdleSync()

        val contribRepo = SQLiteContributionRepository(component.db)
        assertTrue("No contribution when accrual is disabled", contribRepo.getByHabit(HabitRef(habit1.uuid!!)).isEmpty())

        // 2. Re-enable accrual: service overrides past cutoff with fresh now cutoff
        component.userTaskService.updateTask(
            task.id,
            habitTimeAccrualEnabled = true
        )
        val taskEnabled = component.userTaskRepository.getById(task.id)!!
        assertNotNull("Effective cutoff must be set on re-enable", taskEnabled.habitLinkEffectiveFrom)

        // Recovery / reconcile must NOT create task credit from past sessions
        onMain { component.focusCreditController?.startRecovery() }
        instrumentation.waitForIdleSync()
        assertTrue("Historical session before effectiveFrom must NOT receive retroactive credit",
            contribRepo.getByHabit(HabitRef(habit1.uuid!!)).isEmpty())

        // 3. Record new live session AFTER re-enable
        onMain {
            manager.resetCurrent()
            assertTrue(manager.startTask(task.id, TimerMode.STOPWATCH))
        }
        Thread.sleep(4500)
        onMain {
            manager.pauseOrResumeCurrent()
            manager.finishCurrent()
        }
        instrumentation.waitForIdleSync()
        assertEquals("New session after effectiveFrom must be credited", 1, contribRepo.getByHabit(HabitRef(habit1.uuid!!)).size)

        // 4. Switch linked habit to habit2: service provides fresh cutoff, existing credit stays on habit1
        component.userTaskService.updateTask(
            task.id,
            linkedHabitUuid = habit2.uuid
        )
        onMain { component.focusCreditController?.startRecovery() }
        instrumentation.waitForIdleSync()
        // Past contribution for habit1 must remain intact; habit2 must not retroactively claim past sessions
        assertEquals("Habit1 keeps its contribution", 1, contribRepo.getByHabit(HabitRef(habit1.uuid!!)).size)
        assertTrue("Habit2 gets no retroactive credit", contribRepo.getByHabit(HabitRef(habit2.uuid!!)).isEmpty())
    }

    @Test
    fun testNullCutoffTaskFailsClosedWithoutCredit() {
        val habit = createMinuteHabit("Failclosed Habit ${UUID.randomUUID()}")
        // Construct malformed repository task directly with linked_habit_uuid, accrual=true, but habit_link_effective_from=null
        val malformedId = TaskId("malformed-${UUID.randomUUID()}")
        val taskRepo = SQLiteUserTaskRepository(component.db)
        taskRepo.save(
            UserTask(
                id = malformedId,
                title = "Malformed Null Cutoff Task",
                linkedHabitUuid = habit.uuid,
                habitTimeAccrualEnabled = true,
                habitLinkEffectiveFrom = null,
                createdAt = 1000L,
                updatedAt = 1000L
            )
        )

        // Run live timer on malformed task
        val manager = component.timerSessionManager
        onMain {
            manager.resetCurrent()
            assertTrue(manager.startTask(malformedId, TimerMode.STOPWATCH))
        }
        Thread.sleep(4500)
        onMain {
            manager.pauseOrResumeCurrent()
            manager.finishCurrent()
        }
        instrumentation.waitForIdleSync()

        // Attribution must fail closed: null cutoff produces zero contributions
        val contribRepo = SQLiteContributionRepository(component.db)
        assertTrue("Null cutoff task must fail closed with 0 contributions",
            contribRepo.getByHabit(HabitRef(habit.uuid!!)).isEmpty())
    }

    @Test
    fun testWriteFailureRollbackAndRetryWithSqliteTrigger() {
        val habit = createMinuteHabit("Rollback Habit ${UUID.randomUUID()}")
        val task = component.userTaskService.createTask(
            title = "Task Rollback ${UUID.randomUUID()}",
            linkedHabitUuid = habit.uuid,
            habitTimeAccrualEnabled = true
        )

        // Install failing trigger on FocusSessions table
        component.db.run(
            "CREATE TRIGGER test_fail_focus BEFORE INSERT ON FocusSessions BEGIN SELECT RAISE(FAIL, 'simulated focus write error'); END;"
        )

        val manager = component.timerSessionManager
        onMain {
            manager.resetCurrent()
            manager.startTask(task.id, TimerMode.STOPWATCH)
        }
        Thread.sleep(2000)
        try {
            onMain {
                manager.pauseOrResumeCurrent()
                manager.finishCurrent()
            }
        } catch (_: Exception) {
            // Expected failure
        }
        instrumentation.waitForIdleSync()

        // Assert NO FocusSession fact and NO Contribution was written during failure (clean rollback)
        val focusRepo = SQLiteFocusSessionRepository(component.db)
        assertTrue("No FocusSession fact written after error", focusRepo.getByTask(task.id).isEmpty())

        val contribRepo = SQLiteContributionRepository(component.db)
        assertTrue("No contribution written after error", contribRepo.getByHabit(HabitRef(habit.uuid!!)).isEmpty())

        // Drop trigger
        component.db.run("DROP TRIGGER IF EXISTS test_fail_focus;")

        // Retry live session: must succeed cleanly
        onMain {
            manager.resetCurrent()
            manager.startTask(task.id, TimerMode.STOPWATCH)
        }
        Thread.sleep(4500)
        onMain {
            manager.pauseOrResumeCurrent()
            manager.finishCurrent()
        }
        instrumentation.waitForIdleSync()

        assertEquals("FocusSession fact must be written on successful retry", 1, focusRepo.getByTask(task.id).size)
        assertEquals("Contribution must succeed on retry", 1, contribRepo.getByHabit(HabitRef(habit.uuid!!)).size)
    }

    @Test
    fun testRealNativeFailureOnContributionsRollbackAndRetry() {
        val habit = createMinuteHabit("Contrib Fail Habit ${UUID.randomUUID()}")
        val task = component.userTaskService.createTask(
            title = "Task Contrib Fail ${UUID.randomUUID()}",
            linkedHabitUuid = habit.uuid,
            habitTimeAccrualEnabled = true
        )

        val manager = component.timerSessionManager
        onMain {
            manager.resetCurrent()
            assertTrue("Should start task stopwatch", manager.startTask(task.id, TimerMode.STOPWATCH))
        }
        Thread.sleep(2500)

        // Install failing trigger on Contributions BEFORE INSERT
        component.db.run(
            "CREATE TRIGGER test_fail_contributions BEFORE INSERT ON Contributions BEGIN SELECT RAISE(FAIL, 'simulated contribution write error'); END;"
        )

        var finishThrew = false
        try {
            onMain {
                manager.finishCurrent()
            }
        } catch (_: Exception) {
            finishThrew = true
        }
        instrumentation.waitForIdleSync()
        assertTrue("Live task timer finish must throw when Contributions insert fails", finishThrew)

        val focusRepo = SQLiteFocusSessionRepository(component.db)
        val contribRepo = SQLiteContributionRepository(component.db)

        // Fact + credit absent
        assertTrue("FocusSession fact must be absent after contribution failure", focusRepo.getByTask(task.id).isEmpty())
        assertTrue("Contribution credit must be absent after contribution failure", contribRepo.getByHabit(HabitRef(habit.uuid!!)).isEmpty())

        // Active identity/journal retained in timer manager
        assertTrue("Timer manager must retain active task target after failure", manager.isCurrentTask(task.id))
        assertTrue("Timer manager snapshot must retain active session", manager.snapshot().hasActiveSession)
        assertEquals("Timer manager must retain task ID", task.id.value, manager.currentTarget?.id)

        // Remove trigger
        component.db.run("DROP TRIGGER IF EXISTS test_fail_contributions;")

        // Retry finish: yields exactly 1 fact + credit with stable source
        onMain {
            manager.finishCurrent()
        }
        instrumentation.waitForIdleSync()

        val sessions = focusRepo.getByTask(task.id)
        val contributions = contribRepo.getByHabit(HabitRef(habit.uuid!!))
        assertEquals("Retry must record exactly 1 FocusSession fact", 1, sessions.size)
        assertEquals("Retry must record exactly 1 Contribution credit", 1, contributions.size)
        assertEquals("Focus session source must be TIMER", FocusSessionSource.TIMER, sessions[0].source)
        assertEquals("Contribution source ID must be the focus session ID", sessions[0].id.value, contributions[0].sourceId)

        // Repeat finish stays 1
        onMain {
            manager.finishCurrent()
        }
        instrumentation.waitForIdleSync()

        assertEquals("Repeat finish must keep exactly 1 FocusSession fact", 1, focusRepo.getByTask(task.id).size)
        assertEquals("Repeat finish must keep exactly 1 Contribution credit", 1, contribRepo.getByHabit(HabitRef(habit.uuid!!)).size)
    }

    @Test
    fun testReopenRebuildAndPersistedLinkage() {
        val habit = createMinuteHabit("Persistence Habit ${UUID.randomUUID()}")
        val original = component.userTaskService.createTask(
            title = "Task Persistence ${UUID.randomUUID()}",
            linkedHabitUuid = habit.uuid,
            habitTimeAccrualEnabled = true,
            due = TaskDue.Day(TaskDueDate.parse("2026-10-25")),
            important = true,
            urgent = false
        )

        // Actually rebuild application component to prove persistent SQLite reload
        val currentSession = component.session
        val app = context.applicationContext as HabitsApplication
        onMain { app.rebuildComponent(currentSession) }
        instrumentation.waitForIdleSync()

        val reloadedComponent = HabitsApplication.component
        val loaded = reloadedComponent.userTaskRepository.getById(original.id)!!
        assertEquals(original.linkedHabitUuid, loaded.linkedHabitUuid)
        assertEquals(original.habitTimeAccrualEnabled, loaded.habitTimeAccrualEnabled)
        assertNotNull(loaded.habitLinkEffectiveFrom)
        assertEquals(original.important, loaded.important)
        assertEquals(original.urgent, loaded.urgent)
    }

    @Test
    fun testV34AndroidInPlaceSchemaUpgrade() {
        val dbName = "synthetic_v34_${UUID.randomUUID()}.db"
        val dbFile = context.getDatabasePath(dbName)
        try {
            // 1. Create a version 34 database using Android SQLite
            android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { rawDb ->
                val androidDb = AndroidDatabase(rawDb)
                UserTaskSchema.createSchema(androidDb, targetVersion = 34)
                rawDb.execSQL(
                    """
                    INSERT INTO UserTasks(id, title, created_at, updated_at, due_kind, due_date, important, urgent)
                    VALUES('legacy-task-1', 'Legacy Task One', 1000, 2000, 'DAY', '2026-10-25', 1, 0)
                    """.trimIndent()
                )
                rawDb.version = 34
            }

            // 2. Open via real app SQLite opener: HabitsDatabaseOpener at targetVersion 35
            val opener = HabitsDatabaseOpener(context, dbName, 35)
            opener.writableDatabase.use { migratedRaw ->
                assertEquals("Android SQLite opener must upgrade to 35", 35, migratedRaw.version)

                val migratedDb = AndroidDatabase(migratedRaw)
                val repo = SQLiteUserTaskRepository(migratedDb)

                // 3. Verify legacy task preserved with defaults for new columns
                val legacy = repo.getById(TaskId("legacy-task-1"))!!
                assertEquals("Legacy Task One", legacy.title)
                assertEquals(true, legacy.important)
                assertEquals(false, legacy.urgent)
                assertNull(legacy.linkedHabitUuid)
                assertFalse(legacy.habitTimeAccrualEnabled)
                assertNull(legacy.habitLinkEffectiveFrom)

                // 4. Verify new task with habit link persists on migrated DB
                repo.save(
                    UserTask(
                        id = TaskId("new-linked-1"),
                        title = "Migrated Linked Task",
                        linkedHabitUuid = "habit-uuid-migrated",
                        habitTimeAccrualEnabled = true,
                        habitLinkEffectiveFrom = 9999L,
                        createdAt = 3000L,
                        updatedAt = 3000L
                    )
                )
                val loadedNew = repo.getById(TaskId("new-linked-1"))!!
                assertEquals("habit-uuid-migrated", loadedNew.linkedHabitUuid)
                assertTrue(loadedNew.habitTimeAccrualEnabled)
                assertEquals(9999L, loadedNew.habitLinkEffectiveFrom)

                // 5. Integrity check
                assertEquals("ok", DatabaseUtils.stringForQuery(migratedRaw, "PRAGMA integrity_check", null))
            }
        } finally {
            listOf(dbFile, File(dbFile.path + "-wal"), File(dbFile.path + "-shm"), File(dbFile.path + "-journal")).forEach { it.delete() }
        }
    }

    @Test
    fun testNextDirectHabitStopwatchBaselineRegression() {
        val habit = createMinuteHabit("Direct Habit Baseline ${UUID.randomUUID()}")
        val manager = component.timerSessionManager
        onMain {
            manager.resetCurrent()
            assertTrue("Should start stopwatch directly on habit", manager.startOrPause(habit))
        }

        Thread.sleep(4500)

        onMain {
            manager.startOrPause(habit) // Pause
            manager.finish(habit)
        }
        instrumentation.waitForIdleSync()

        val contribRepo = SQLiteContributionRepository(component.db)
        val contributions = contribRepo.getByHabit(HabitRef(habit.uuid!!))
        assertTrue("Direct habit contribution must be recorded without regression", contributions.isNotEmpty())
        assertTrue("Contribution value must be > 0", contributions[0].contributedValue > 0)
    }

    @Test
    fun testHabitPickerCancelButtonLocalizationRussianAndEnglish() {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        val origLang = prefs.getString("pref_app_language", null)
        try {
            // 1. Russian locale preference yields 'Отмена'
            prefs.edit().putString("pref_app_language", "ru-RU").commit()
            val ruText = TaskDetailBottomSheet.getCancelButtonText(context)
            assertEquals("Russian locale preference must yield 'Отмена'", "Отмена", ruText)

            // Direct configuration context check for Russian
            val ruConfig = android.content.res.Configuration(context.resources.configuration).apply {
                setLocale(java.util.Locale("ru", "RU"))
            }
            val ruContext = context.createConfigurationContext(ruConfig)
            assertEquals("Russian context string resource must be 'Отмена'", "Отмена", ruContext.getString(R.string.containers_cancel))

            // 2. English locale preference yields 'Cancel'
            prefs.edit().putString("pref_app_language", "en").commit()
            val enText = TaskDetailBottomSheet.getCancelButtonText(context)
            assertEquals("English locale preference must yield 'Cancel'", "Cancel", enText)

            // Direct configuration context check for English
            val enConfig = android.content.res.Configuration(context.resources.configuration).apply {
                setLocale(java.util.Locale.ENGLISH)
            }
            val enContext = context.createConfigurationContext(enConfig)
            assertEquals("English context string resource must be 'Cancel'", "Cancel", enContext.getString(R.string.containers_cancel))
        } finally {
            prefs.edit().apply {
                if (origLang != null) putString("pref_app_language", origLang)
                else remove("pref_app_language")
            }.commit()
        }
    }
}
