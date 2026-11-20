package org.isoron.uhabits.activities.tasks

import android.database.sqlite.SQLiteDatabase
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.isoron.uhabits.BuildConfig
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.activities.main.MainActivity
import org.isoron.uhabits.activities.main.MainDestination
import org.isoron.uhabits.activities.common.theme.MainTabsThemeBridge
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.userTasks.TaskId
import org.isoron.uhabits.core.userTasks.due.TaskDue
import org.isoron.uhabits.core.ui.ThemeSwitcher
import org.isoron.uhabits.utils.DemoDataGenerator
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.Closeable
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

/** Explicitly opt-in: only an already provisioned, marked offline disposable development AVD. */
@RunWith(AndroidJUnit4::class)
class TaskDraftLifecycleTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val prefix = "PR5-${UUID.randomUUID()}"
    private val millis = LocalDateTime.of(2026, 10, 23, 18, 0)
        .atZone(ZoneId.of("Asia/Novosibirsk")).toInstant().toEpochMilli()
    private val due = TaskDue.Timed.local(millis, "Asia/Novosibirsk")

    @Test
    fun actualCreateDoubleTapAndChildFailureAreAtomic() {
        guard()
        SQLiteDatabase.openDatabase(HabitsApplication.component.dbFile.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            QaActivityController().use { scenario ->
                scenario.onActivity { activity ->
                    activity.navigate(MainDestination.TASKS)
                    activity.supportFragmentManager.executePendingTransactions()
                }
                for (quick in listOf(false, true)) {
                    val title = "$prefix-failure-$quick"
                    val baseline = counts(db)
                    val trigger = "pr5_child_failure_${if (quick) "quick" else "create"}"
                    db.execSQL("CREATE TRIGGER $trigger BEFORE INSERT ON UserTasks WHEN NEW.title='$title-child' BEGIN SELECT RAISE(ABORT, 'PR5 injected child failure'); END")
                    try {
                        scenario.onActivity { activity ->
                            val tasks = tasks(activity)
                            val draft = TaskDraft(title = title, due = due, subtasks = listOf(TaskDraft.DraftSubtask("$title-child")))
                            TaskDetailBottomSheet.newInstanceForCreate(draft, HabitsApplication.component.session.datasetId)
                                .show(tasks.childFragmentManager, "task_detail_create")
                            tasks.childFragmentManager.executePendingTransactions()
                        }
                        idle()
                        if (quick) {
                            device.taskControl("taskDetailCancel").click()
                            idle()
                            assertEditorDismissed(scenario, "Atomic Quick Add collapse")
                        }
                        val submitTag = if (quick) "btnQuickAddSubmit" else "taskDetailCreate"
                        val titleTag = if (quick) "etQuickAddTitle" else "taskDetailTitle"
                        val actualTitle = device.taskControl(titleTag).text
                        evidence("submitting quick=$quick expected=$title actual=$actualTitle")
                        assertEquals(title, actualTitle)
                        device.taskControl(submitTag).click()
                        idle()
                        assertTrue("Failed save must allow retry", device.taskControl(submitTag).isEnabled)
                        evidence("failure quick=$quick immediate=${counts(db)} triggers=${count(db, "SELECT COUNT(*) FROM sqlite_master WHERE type='trigger'")}")
                        assertEquals("Parent, child and queue must roll back", baseline, counts(db))
                    } finally {
                        db.execSQL("DROP TRIGGER IF EXISTS $trigger")
                        scenario.onActivity { findSheet(it)?.dismissNow(); flushFragments(it) }
                        idle()
                    }
                }
                scenario.onActivity { activity ->
                    val tasks = tasks(activity)
                    TaskDetailBottomSheet.newInstanceForCreate(TaskDraft(title = "$prefix-double-create", due = due), HabitsApplication.component.session.datasetId)
                        .show(tasks.childFragmentManager, "task_detail_create")
                    tasks.childFragmentManager.executePendingTransactions()
                }
                doubleTap(device.taskControl("taskDetailCreate"))
                idle()
                assertEditorDismissed(scenario, "Double-tap Create")
                db.rawQuery("SELECT COUNT(*) FROM UserTasks WHERE title=?", arrayOf("$prefix-double-create")).use {
                    assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0))
                }
            }
        }
    }

    @Test
    fun actualEditorsPreserveDraftAndSubmitOneExactTimedTask() {
        guard()
        val oldTheme = HabitsApplication.component.preferences.theme
        try {
        HabitsApplication.component.preferences.theme = ThemeSwitcher.THEME_LIGHT
        val section = HabitsApplication.component.organizationFacade.createContainer(name = "$prefix-section")
        check(section is org.isoron.uhabits.core.containers.OrganizationResult.Success) { section.toString() }
        val container = section.value.id.value
        SQLiteDatabase.openDatabase(HabitsApplication.component.dbFile.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            val baseline = counts(db)
            evidence("baseline UserTasks=${baseline.first} SyncQueue=${baseline.second}")
            QaActivityController().use { scenario ->
                scenario.onActivity { activity ->
                    activity.navigate(MainDestination.TASKS)
                    activity.supportFragmentManager.executePendingTransactions()
                }
                device.taskControl("layoutQuickAddCollapsed").click()
                idle(); screenshot("00-compact-empty")
                device.taskControl("etQuickAddTitle").text = prefix
                scenario.onActivity { activity ->
                    val tasks = tasks(activity)
                    tasks.childFragmentManager.setFragmentResult("quickDue", TaskDuePickerBottomSheet.encode(due))
                    tasks.childFragmentManager.setFragmentResult("quickContainer", Bundle().apply {
                        putString(TaskContainerPickerBottomSheet.RESULT_CONTAINER_ID, container)
                    })
                }
                idle(); screenshot("01-compact-filled")
                expand(scenario)
                device.enterTaskDraftNotesAndSubtask("PR5 lifecycle notes", "$prefix-child")
                idle(); screenshot("02-expanded-filled")
                assertDraft(scenario, container)
                assertEquals(baseline, counts(db))

                device.taskControl("taskDetailCancel").click()
                assertEditorDismissed(scenario, "Collapse")
                idle(); screenshot("03-compact-collapsed")
                assertEquals(baseline, counts(db))
                expand(scenario); assertDraft(scenario, container)
                hideKeyboard(scenario)
                device.pressBack(); idle()
                // The keyboard may consume the first Back; dismiss the editor with a second Back.
                var needsBack = false
                scenario.onActivity { activity ->
                    flushFragments(activity)
                    needsBack = findSheet(activity)?.dialog?.isShowing == true
                }
                if (needsBack) { device.pressBack(); idle() }
                assertEditorDismissed(scenario, "Back")
                idle(); expand(scenario); assertDraft(scenario, container)

                hideKeyboard(scenario)
                // Actual bottom-sheet swipe using its current on-screen bounds.
                var bounds: IntArray? = null
                scenario.onActivity { activity ->
                    findSheet(activity)?.requireView()?.let { view ->
                        val xy = IntArray(2); view.getLocationOnScreen(xy)
                        bounds = intArrayOf(xy[0] + view.width / 2, xy[1] + 15, xy[1] + view.height - 10)
                    }
                }
                requireNotNull(bounds).let { device.swipe(it[0], it[1], it[0], it[2], 25) }
                idle()
                scenario.onActivity { activity ->
                    flushFragments(activity)
                    check(findSheet(activity) == null) { "Swipe did not dismiss editor" }
                }
                expand(scenario); assertDraft(scenario, container)
                scenario.recreate(); idle(); assertDraft(scenario, container)
                screenshot("04-expanded-recreated")
                scenario.onActivity { activity ->
                    assertFalse(MainTabsThemeBridge.resolve(activity).isDark)
                    HabitsApplication.component.preferences.theme = ThemeSwitcher.THEME_DARK
                    activity.recreateForThemeChange()
                }
                idle(); assertDraft(scenario, container)
                scenario.onActivity { activity -> assertTrue(MainTabsThemeBridge.resolve(activity).isDark) }
                screenshot("05-expanded-dark")
                val beforeSubmit = counts(db)
                evidence("beforeSubmit UserTasks=${beforeSubmit.first} SyncQueue=${beforeSubmit.second}")
                assertEquals(baseline, beforeSubmit)

                device.taskControl("taskDetailCancel").click()
                assertEditorDismissed(scenario, "Collapse before Submit")
                idle()
                doubleTap(device.taskControl("btnQuickAddSubmit"))
                idle()
                var savedTaskId: String? = null
                db.rawQuery("SELECT id,due_kind,due_time_millis,due_zone,description,container_id FROM UserTasks WHERE title=?", arrayOf(prefix)).use { c ->
                    assertEquals(1, c.count); assertTrue(c.moveToFirst())
                    val taskId = c.getString(0)
                    savedTaskId = taskId
                    assertEquals("TIMED", c.getString(1)); assertEquals(millis, c.getLong(2))
                    assertEquals("Asia/Novosibirsk", c.getString(3))
                    assertEquals("PR5 lifecycle notes", c.getString(4)); assertEquals(container, c.getString(5))
                    db.rawQuery("SELECT title FROM UserTasks WHERE parent_task_id=?", arrayOf(taskId)).use { child ->
                        assertEquals(1, child.count); assertTrue(child.moveToFirst()); assertEquals("$prefix-child", child.getString(0))
                        evidence("parentId=$taskId due_kind=${c.getString(1)} due_time_millis=${c.getLong(2)} due_zone=${c.getString(3)} description=${c.getString(4)} container_id=${c.getString(5)} childCount=${child.count} childTitle=${child.getString(0)}")
                    }
                    File(context.getExternalFilesDir(null), "pr5-final-qa/result.txt").appendText("taskId=$taskId due=TIMED millis=$millis zone=Asia/Novosibirsk\n")
                }
                scenario.onActivity { activity ->
                    TaskDetailBottomSheet.newInstance(TaskId(requireNotNull(savedTaskId)))
                        .show(tasks(activity).childFragmentManager, "task_detail_saved_qa")
                    tasks(activity).childFragmentManager.executePendingTransactions()
                }
                idle(); screenshot("06-existing-edit")
                scenario.onActivity { activity ->
                    (tasks(activity).childFragmentManager.findFragmentByTag("task_detail_saved_qa") as? TaskDetailBottomSheet)?.dismissNow()
                }
                idle()
            }
        }
        } finally {
            instrumentation.runOnMainSync { HabitsApplication.component.preferences.theme = oldTheme }
        }
    }

    internal fun guard() {
        check(BuildConfig.DEBUG)
        val authorizedAvd = InstrumentationRegistry.getArguments().getString("authorizedAvd")
        check(authorizedAvd != null && authorizedAvd.startsWith("UHabit_Temp_"))
        check(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim() == authorizedAvd)
        check(Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1)
        check(Settings.Global.getInt(context.contentResolver, Settings.Global.WIFI_ON, 0) == 0)
        val component = HabitsApplication.component
        val session = component.session
        val marker = File(context.filesDir, "${DemoDataGenerator.DISPOSABLE_MARKER}-${session.datasetId}")
        check(session.isExperimental && session.isNextArchitecture && !session.capabilities.syncEnabled && !session.capabilities.remoteCallsAllowed)
        check(marker.isFile && marker.readText().trim() == session.databaseFilename)
        check(component.dbFile.canonicalFile == context.getDatabasePath(session.databaseFilename).canonicalFile)
        check(session.databaseFilename != org.isoron.uhabits.core.DATABASE_FILENAME)
    }

    private fun counts(db: SQLiteDatabase): Pair<Long, Long> =
        count(db, "SELECT COUNT(*) FROM UserTasks") to count(db, "SELECT COUNT(*) FROM SyncQueue")
    private fun count(db: SQLiteDatabase, sql: String): Long = db.rawQuery(sql, null).use { it.moveToFirst(); it.getLong(0) }
    private fun evidence(line: String) {
        val file = File(context.getExternalFilesDir(null), "pr5-final-qa/sql-evidence.txt")
        check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
        file.appendText("$prefix $line\n")
    }
    private fun idle() { instrumentation.waitForIdleSync(); device.waitForIdle() }
    private fun hideKeyboard(scenario: QaActivityController) {
        scenario.onActivity { activity ->
            val token = sheet(activity).requireView().windowToken
            (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(token, 0)
        }
        idle()
    }
    private fun screenshot(name: String) {
        val file = File(context.getExternalFilesDir(null), "pr5-final-qa/$name.png")
        check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
        check(device.takeScreenshot(file))
    }
    private fun tasks(activity: MainActivity) = activity.supportFragmentManager.findFragmentByTag("main.tasks") as TasksFragment
    private fun findSheet(activity: MainActivity) = tasks(activity).childFragmentManager.findFragmentByTag("task_detail_create") as? TaskDetailBottomSheet
    private fun sheet(activity: MainActivity) = requireNotNull(findSheet(activity))
    private fun expand(scenario: QaActivityController) {
        scenario.onActivity { activity ->
            flushFragments(activity)
            check(findSheet(activity)?.dialog?.isShowing != true) { "Editor dialog is still showing before Expand" }
            check(findSheet(activity) == null) { "Editor already open" }
        }
        device.taskControl("btnQuickAddExpand").click()
        device.taskControl("taskDetailCreate")
        idle()
    }
    private fun flushFragments(activity: MainActivity) {
        activity.supportFragmentManager.executePendingTransactions()
        tasks(activity).childFragmentManager.executePendingTransactions()
    }
    private fun assertEditorDismissed(scenario: QaActivityController, action: String) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            var dismissed = false
            scenario.onActivity { activity ->
                flushFragments(activity)
                dismissed = findSheet(activity) == null
            }
            if (dismissed) return
            SystemClock.sleep(30)
        }
        error("$action did not dismiss the editor within 5 seconds")
    }
    private fun assertDraft(scenario: QaActivityController, container: String) {
        assertEquals(prefix, device.revealTaskDetailControl("taskDetailTitle", Direction.UP).text)
        scenario.onActivity { activity ->
            val saved = Bundle().also(sheet(activity)::onSaveInstanceState)
            val draft = TaskDraft.fromBundle(requireNotNull(saved.getBundle("draftBundle")))
            assertEquals(prefix, draft.title); assertEquals("PR5 lifecycle notes", draft.description)
            assertEquals(due, draft.due); assertEquals(ContainerId(container), draft.containerId)
            assertEquals(listOf(TaskDraft.DraftSubtask("$prefix-child")), draft.subtasks)
        }
    }
    private fun doubleTap(control: UiObject2) {
        val center = control.visibleCenter
        repeat(2) {
            val down = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, center.x.toFloat(), center.y.toFloat(), 0)
                try {
                    event.source = InputDevice.SOURCE_TOUCHSCREEN
                    check(instrumentation.uiAutomation.injectInputEvent(event, false))
                } finally {
                    event.recycle()
                }
            }
        }
    }

    /** Follow the app's own theme-restart Activity, rather than retaining a stale launch instance. */
    internal inner class QaActivityController : Closeable {
        init {
            instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            awaitResumed()
        }

        fun onActivity(action: (MainActivity) -> Unit) {
            awaitResumed()
            instrumentation.runOnMainSync {
                val current = resumed()
                check(current.size == 1) { "Expected one current resumed MainActivity, found ${current.size}" }
                action(current.single())
            }
        }

        fun recreate() {
            var previous: MainActivity? = null
            onActivity { activity -> previous = activity; activity.recreate() }
            awaitResumed(previous)
        }

        private fun resumed(): List<MainActivity> = ActivityLifecycleMonitorRegistry.getInstance()
            .getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>()
            .filter { !it.isFinishing && !it.isDestroyed }

        private fun awaitResumed(previous: MainActivity? = null) {
            val deadline = SystemClock.uptimeMillis() + 15_000
            while (SystemClock.uptimeMillis() < deadline) {
                var ready = false
                instrumentation.runOnMainSync {
                    val current = resumed()
                    ready = current.size == 1 && current.single() !== previous
                }
                if (ready) return
                SystemClock.sleep(30)
            }
            error("No current resumed MainActivity appeared within 15 seconds")
        }

        override fun close() {
            val deadline = SystemClock.uptimeMillis() + 15_000
            while (SystemClock.uptimeMillis() < deadline) {
                var remaining = 0
                instrumentation.runOnMainSync {
                    val activities = Stage.values().filter { it != Stage.DESTROYED }.flatMap { stage ->
                        ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage)
                    }.filterIsInstance<MainActivity>().distinct().filter { !it.isDestroyed }
                    remaining = activities.size
                    activities.forEach { activity -> if (!activity.isFinishing) activity.finish() }
                }
                if (remaining == 0) return
                SystemClock.sleep(30)
            }
            error("MainActivity instances did not finish within 15 seconds")
        }
    }
}

internal fun UiDevice.taskControl(tag: String): UiObject2 =
    requireNotNull(wait(Until.findObject(By.res(tag)), 5_000)) { "Missing task control: $tag" }

internal fun UiDevice.revealTaskDetailControl(tag: String, direction: Direction = Direction.DOWN): UiObject2 {
    val selector = By.res(tag)
    val scroll = taskControl("taskDetailScroll")
    val reverse = if (direction == Direction.DOWN) Direction.UP else Direction.DOWN
    for (scrollDirection in listOf(direction, reverse)) {
        for (attempt in 1..6) {
            findObject(selector)?.takeUnless { it.visibleBounds.isEmpty }?.let { return it }
            if (!scroll.scroll(scrollDirection, 0.65f)) break
        }
    }
    return taskControl(tag).also { check(!it.visibleBounds.isEmpty) { "$tag is outside the task detail viewport" } }
}

internal fun UiDevice.enterTaskDraftNotesAndSubtask(notes: String, subtask: String) {
    revealTaskDetailControl("taskDetailNotes", Direction.UP).text = notes
    revealTaskDetailControl("taskDetailSubtaskInput").text = subtask
    revealTaskDetailControl("taskDetailAddSubtask").click()
}
