package org.isoron.uhabits.activities.habits.show.timer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ActivityScenario
import androidx.lifecycle.Lifecycle
import android.os.Bundle
import android.os.Build
import android.os.ParcelFileDescriptor
import android.view.View
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.main.MainActivity
import org.isoron.uhabits.activities.main.MainDestination
import org.isoron.uhabits.activities.tasks.TaskDetailBottomSheet
import org.isoron.uhabits.activities.tasks.TaskFocusBottomSheet
import org.isoron.uhabits.activities.tasks.TasksFragment
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.containers.OrganizationResult
import org.isoron.uhabits.core.containers.migration.RawLegacyInventoryReader
import org.isoron.uhabits.core.contribution.sqlite.SQLiteContributionRepository
import org.isoron.uhabits.core.focus.FocusPrimaryTargetType
import org.isoron.uhabits.core.focus.sqlite.SQLiteFocusCaptureStore
import org.isoron.uhabits.core.focus.sqlite.SQLiteFocusSessionRepository
import org.isoron.uhabits.core.models.HabitType
import org.isoron.uhabits.core.timer.TimerMode
import org.isoron.uhabits.core.timer.PomodoroPhase
import org.isoron.uhabits.core.userTasks.TaskId
import org.isoron.uhabits.core.userTasks.UserTask
import org.isoron.uhabits.inject.HabitsApplicationComponent
import org.isoron.uhabits.inject.create
import org.isoron.uhabits.utils.DatabaseUtils
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** External disposable fixture required: application test mode clears the selected database. */
@RunWith(AndroidJUnit4::class)
class TaskFocusAndroidTest {
    private class Fixture {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val original = HabitsApplication.component
        val name = "pr17-task-focus-${UUID.randomUUID()}.db"
        val file = context.getDatabasePath(name)
        val session = original.session.copy(databaseFilename = name, preferencesNamespace = name)
        lateinit var component: HabitsApplicationComponent

        init {
            assertTrue("Requires preserved, disposable experimental dataset", original.session.isExperimental)
            File(requireNotNull(InstrumentationRegistry.getArguments().getString("focusFixture"))).copyTo(file)
            open()
        }

        // Real services resolve the global component in onCreate: drain their startup while
        // the fixture component is still selected, before reset/dispose/replacement.
        fun main(action: () -> Unit) {
            instrumentation.runOnMainSync(action)
            instrumentation.waitForIdleSync()
        }
        private fun open() {
            main { TimerForegroundService.stop(context) }
            DatabaseUtils.closeDatabase()
            DatabaseUtils.initializeDatabase(context, name)
            component = HabitsApplicationComponent::class.create(appContext = context, dbFile = file, session = session)
            main { HabitsApplication.component = component }
        }

        fun reopen() {
            main { component.timerSessionManager.dispose(); component.focusCreditController?.dispose() }
            main { TimerForegroundService.stop(context) }
            component.db.close()
            open()
        }

        fun close() {
            main {
                component.timerSessionManager.dispose()
                component.focusCreditController?.dispose()
            }
            main { TimerForegroundService.stop(context) }
            main { HabitsApplication.component = original }
            component.db.close()
            DatabaseUtils.closeDatabase()
            DatabaseUtils.initializeDatabase(context, original.session.databaseFilename)
            context.getSharedPreferences("uhabits_prefs_${session.preferencesNamespace}", 0).edit().clear().commit()
            context.getSharedPreferences(session.preferencesNamespace + "_timer", 0).edit().clear().commit()
            listOf(file, File(file.path + "-journal"), File(file.path + "-wal"), File(file.path + "-shm")).forEach { it.delete() }
        }

        fun task(id: String, container: ContainerId?): UserTask {
            val now = System.currentTimeMillis()
            return UserTask(TaskId(id), "Prepare lab $id", containerId = container, createdAt = now, updatedAt = now)
                .also { component.userTaskRepository.save(it) }
        }
    }

    @Test fun detailLaunchReusesTimerAndKeepsTasksWorkspaceAndDetail() {
        val f = Fixture()
        try {
            val task = f.task("detail-launch", null)
            f.main { f.component.preferences.isFirstRun = false }
            assertTrue(f.context.getSharedPreferences("uhabits_prefs_${f.session.preferencesNamespace}", 0)
                .edit().putBoolean("pref_first_run", false).commit())
            if (Build.VERSION.SDK_INT >= 33) {
                ParcelFileDescriptor.AutoCloseInputStream(f.instrumentation.uiAutomation.executeShellCommand(
                    "pm grant ${f.context.packageName} android.permission.POST_NOTIFICATIONS"
                )).use { it.readBytes() }
            }
            // MainActivity consumes incoming actions; an explicit action-free Intent keeps
            // ActivityScenario lifecycle matching stable during cleanup.
            ActivityScenario.launch<MainActivity>(android.content.Intent(f.context, MainActivity::class.java)).use { scenario ->
                scenario.moveToState(Lifecycle.State.RESUMED)
                scenario.onActivity { activity -> activity.navigate(MainDestination.TASKS) }
                f.instrumentation.waitForIdleSync()
                lateinit var workspace: TasksFragment
                lateinit var detail: TaskDetailBottomSheet
                val before = Bundle()
                scenario.onActivity { activity ->
                    workspace = activity.supportFragmentManager.findFragmentByTag("main.tasks") as TasksFragment
                    workspace.onSaveInstanceState(before)
                    // Restored fragment arguments from an old dataset must be rejected before prepare/start.
                    val stale = TaskFocusBottomSheet.newInstance(task.id, task.title).apply {
                        requireArguments().putString("datasetId", "previous-dataset")
                    }
                    stale.showNow(workspace.childFragmentManager, TaskFocusBottomSheet.TAG)
                }
                f.instrumentation.waitForIdleSync()
                scenario.onActivity {
                    workspace.childFragmentManager.executePendingTransactions()
                    assertFalse(f.component.timerSessionManager.snapshot().hasActiveSession)
                    assertNull(f.component.timerSessionManager.currentTarget)
                    assertNull(workspace.childFragmentManager.findFragmentByTag(TaskFocusBottomSheet.TAG))
                    detail = TaskDetailBottomSheet.newInstance(task.id)
                    detail.showNow(workspace.childFragmentManager, "task_detail")
                    val action = detail.requireView().findViewById<View>(R.id.rowDetailFocus)
                    assertEquals(View.VISIBLE, action.visibility)
                    assertTrue(action.performClick())
                }
                f.instrumentation.waitForIdleSync()
                scenario.onActivity {
                    val timerSheet = workspace.childFragmentManager.findFragmentByTag(TaskFocusBottomSheet.TAG) as TaskFocusBottomSheet
                    assertFalse("Preparation must not start work", f.component.timerSessionManager.snapshot().hasActiveSession)
                    assertNull(f.component.timerSessionManager.currentTarget)
                    val confirm = findStartButton(timerSheet.requireView())
                    assertTrue(confirm.performClick())
                    assertTrue(f.component.timerSessionManager.isTaskTarget(task.id))
                    assertTrue(f.component.timerSessionManager.snapshot().isRunning)
                    assertSame(detail, workspace.childFragmentManager.findFragmentByTag("task_detail"))
                    timerSheet.dismiss()
                }
                f.instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    assertSame(workspace, activity.supportFragmentManager.findFragmentByTag("main.tasks"))
                    val after = Bundle()
                    workspace.onSaveInstanceState(after)
                    for (key in listOf("filter", "calendarMode", "selectedDay", "calendarMonth")) {
                        assertEquals("Task context $key", before.get(key), after.get(key))
                    }
                    assertTrue(detail.isAdded)
                    f.component.timerSessionManager.resetCurrent()
                    detail.dismiss()
                }
            }
            assertFalse(f.component.userTaskRepository.getById(task.id)!!.isCompleted)
        } finally { f.close() }
    }

    private fun findStartButton(root: View): View {
        if (root is android.widget.Button && root.text.toString() == "Начать") return root
        if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) {
                runCatching { findStartButton(root.getChildAt(i)) }.getOrNull()?.let { return it }
            }
        }
        error("Focus confirmation button missing")
    }

    @Test fun idleTaskPomodoroConfigurationAndBreakReuseCanonicalRuntime() {
        val f = Fixture()
        try {
            val task = f.task("pomodoro", null)
            val timer = f.component.timerSessionManager
            val facts = SQLiteFocusSessionRepository(f.component.db)
            val baseline = facts.getAll().size
            f.main {
                assertTrue(timer.prepareTask(task.id, task.title))
                assertFalse(timer.snapshot().hasActiveSession)
                assertTrue(timer.updateTaskDurations(task.id, task.title, 2, 1))
                assertEquals(2, timer.taskDurations(task.id).focusMinutes)
                assertEquals(1, timer.taskDurations(task.id).breakMinutes)
                assertEquals(120_000L, timer.snapshot().focusDurationMillis)
                assertEquals(60_000L, timer.snapshot().breakDurationMillis)
                assertFalse(timer.updateTaskDurations(task.id, task.title, 0, 1))
                assertTrue(timer.switchTaskMode(task.id, task.title, TimerMode.POMODORO))
                assertTrue(timer.toggleTask(task.id, task.title))
            }
            Thread.sleep(160)
            f.main { assertTrue(timer.toggleTask(task.id, task.title)) }
            val paused = timer.snapshot().elapsedMillis
            Thread.sleep(100)
            assertEquals(paused, timer.snapshot().elapsedMillis)
            f.main { assertTrue(timer.toggleTask(task.id, task.title)) }
            Thread.sleep(120)
            f.main { timer.takeBreakCurrent() }
            assertEquals(PomodoroPhase.BREAK, timer.snapshot().phase)
            val fact = facts.getAll().single { it.taskId == task.id }
            assertTrue(fact.durationMs > 0)
            Thread.sleep(120)
            f.main { timer.finishCurrent(); timer.finishCurrent() }
            assertEquals(baseline + 1, facts.getAll().size)
            assertEquals(task, f.component.userTaskRepository.getById(task.id))
            f.main { assertTrue(timer.startTask(task.id, TimerMode.POMODORO)) }
            f.main { timer.resetCurrent() }
            assertEquals(baseline + 1, facts.getAll().size)
        } finally { f.close() }
    }

    @Test fun taskSubtreeFocusCreditsOnceWithoutChangingTaskOrManualData() {
        val f = Fixture()
        try {
            val c = f.component
            val root = c.organizationFacade.containerQueries!!.roots().first()
            val child = (c.organizationFacade.createContainer("PR17 Databases", root.id) as OrganizationResult.Success).value
            val habit = c.modelFactory.buildHabit().apply { name = "PR17 Study"; type = HabitType.NUMERICAL; unit = "min" }
            val task = f.task("descendant", child.id)
            val target = HabitRef(habit.uuid!!)
            f.main { c.habitList.add(habit); c.focusCreditController!!.configure(habit, root.id, true) }
            val manualBefore = RawLegacyInventoryReader.read(c.db).snapshotSha256
            val facts = SQLiteFocusSessionRepository(c.db)
            val beforeCount = facts.getAll().size
            val timer = c.timerSessionManager
            f.main { assertTrue(timer.prepareTask(task.id, task.title)); assertTrue(timer.startTask(task.id)) }
            // Exceed legacy numerical rounding threshold so an accidental direct write is observable.
            Thread.sleep(3300)
            f.main { assertTrue(timer.pauseOrResumeCurrent()) }
            val elapsed = timer.snapshot().elapsedMillis
            Thread.sleep(140)
            assertEquals(elapsed, timer.snapshot().elapsedMillis)
            f.main { assertTrue(timer.pauseOrResumeCurrent()) }
            Thread.sleep(160)
            f.main { timer.finishCurrent(); timer.finishCurrent() }
            val fact = facts.getAll().single { it.taskId == task.id }
            assertEquals(beforeCount + 1, facts.getAll().size)
            assertEquals(FocusPrimaryTargetType.TASK, fact.primaryTargetType)
            assertEquals(child.id, fact.containerId)
            assertTrue(fact.organizationContext!!.contains(root.id.value))
            assertTrue(fact.durationMs < fact.endedAt - fact.startedAt)
            val credit = SQLiteContributionRepository(c.db).getByHabit(target).single()
            assertEquals(fact.id.value, credit.sourceId)
            assertTrue(credit.contributedValue > 0)
            f.main { assertEquals(credit.contributedValue, c.focusCreditController!!.goalProgress(habit).value.contributionValue) }
            assertEquals(task, c.userTaskRepository.getById(task.id))
            assertEquals(manualBefore, RawLegacyInventoryReader.read(c.db).snapshotSha256)
            f.main { c.focusCreditController!!.startRecovery() }
            f.instrumentation.waitForIdleSync()
            assertEquals(1, SQLiteContributionRepository(c.db).getByHabit(target).size)
        } finally { f.close() }
    }

    @Test fun directUnassignedMovedAndDeletedTargetsPreserveCapturedMeaning() {
        val f = Fixture()
        try {
            val c = f.component
            val root = c.organizationFacade.containerQueries!!.roots().first()
            val child = (c.organizationFacade.createContainer("PR17 movable", root.id) as OrganizationResult.Success).value
            val elsewhere = (c.organizationFacade.createContainer("PR17 elsewhere") as OrganizationResult.Success).value
            val habit = c.modelFactory.buildHabit().apply { name = "PR17 scope"; type = HabitType.NUMERICAL; unit = "min" }
            val target = HabitRef(habit.uuid!!)
            f.main { c.habitList.add(habit); c.focusCreditController!!.configure(habit, root.id, true) }
            val direct = f.task("direct", root.id)
            val unassigned = f.task("unassigned", null)
            val moved = f.task("moved-deleted", child.id)
            val timer = c.timerSessionManager
            for (task in listOf(direct, unassigned)) {
                f.main { assertTrue(timer.startTask(task.id)) }
                Thread.sleep(100)
                f.main { timer.finishCurrent() }
            }
            assertEquals(1, SQLiteContributionRepository(c.db).getByHabit(target).size)
            val unassignedFact = SQLiteFocusSessionRepository(c.db).getAll().single { it.taskId == unassigned.id }
            assertNull(unassignedFact.containerId)
            f.main { assertTrue(timer.startTask(moved.id)) }
            val frozen = timer.currentTarget
            f.main {
                assertTrue(c.organizationFacade.moveContainer(child.id, elsewhere.id) is OrganizationResult.Success)
                c.userTaskRepository.delete(moved.id)
            }
            Thread.sleep(120)
            f.main { assertTrue(timer.pauseOrResumeCurrent()); assertTrue(timer.pauseOrResumeCurrent()); timer.finishCurrent() }
            val movedFact = SQLiteFocusSessionRepository(c.db).getAll().single { it.taskId == moved.id }
            assertEquals(frozen!!.organizationContext, movedFact.organizationContext)
            assertEquals(child.id, movedFact.containerId)
            assertNull(c.userTaskRepository.getById(moved.id))
            assertEquals(2, SQLiteContributionRepository(c.db).getByHabit(target).size)
            assertFalse(c.userTaskRepository.getById(direct.id)!!.isCompleted)
        } finally { f.close() }
    }

    @Test fun recoveryKeepsCaptureIdentityAndConflictCancelAndStaleCallbacksAreSafe() {
        val f = Fixture()
        try {
            val source = f.component.organizationFacade.containerQueries!!.roots().first()
            val task = f.task("recover", source.id)
            val other = f.task("conflict", source.id)
            val habit = f.component.modelFactory.buildHabit().apply { name = "PR17 recovery"; type = HabitType.NUMERICAL; unit = "min" }
            val target = HabitRef(habit.uuid!!)
            f.main { f.component.habitList.add(habit); f.component.focusCreditController!!.configure(habit, source.id, true) }
            val oldTimer = f.component.timerSessionManager
            f.main { assertTrue(oldTimer.startTask(task.id, TimerMode.STOPWATCH)) }
            Thread.sleep(120)
            val before = requireNotNull(SQLiteFocusCaptureStore(f.component.db).load())
            f.main {
                assertFalse(oldTimer.startTask(other.id))
                assertFalse(oldTimer.prepareTask(other.id, other.title))
                assertTrue(oldTimer.isTaskTarget(task.id))
                assertTrue(oldTimer.prepareTask(task.id, task.title))
                assertTrue(oldTimer.snapshot().isRunning)
            }
            f.reopen()
            val timer = f.component.timerSessionManager
            val after = requireNotNull(SQLiteFocusCaptureStore(f.component.db).load())
            assertEquals(before.active!!.id, after.active!!.id)
            assertEquals(before.target, after.target)
            assertTrue(timer.isTaskTarget(task.id))
            assertTrue(timer.snapshot().elapsedMillis >= before.timer.elapsedMillis)
            f.main {
                assertFalse(oldTimer.startTask(task.id))
                assertFalse(oldTimer.acceptsDatasetCallback(f.session.datasetId))
                timer.finishCurrent(); timer.finishCurrent()
            }
            val fact = SQLiteFocusSessionRepository(f.component.db).getAll().single { it.taskId == task.id }
            assertEquals(before.active!!.id, fact.id)
            assertEquals(1, SQLiteContributionRepository(f.component.db).getByHabit(target).size)
            assertEquals(task, f.component.userTaskRepository.getById(task.id))
            val count = SQLiteFocusSessionRepository(f.component.db).getAll().size
            f.main { assertTrue(timer.startTask(other.id)) }
            Thread.sleep(80)
            f.main { timer.resetCurrent() }
            assertEquals(count, SQLiteFocusSessionRepository(f.component.db).getAll().size)
            assertEquals(29, org.isoron.uhabits.core.DATABASE_VERSION)
        } finally { f.close() }
    }
}
