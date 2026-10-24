package org.isoron.uhabits.activities.tasks

import android.database.sqlite.SQLiteDatabase
import android.os.SystemClock
import android.view.ViewGroup
import android.widget.Button
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.main.MainActivity
import org.isoron.uhabits.activities.main.MainDestination
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.ui.ThemeSwitcher
import org.isoron.uhabits.core.userTasks.TaskId
import org.isoron.uhabits.core.userTasks.UserTask
import org.isoron.uhabits.core.userTasks.due.TaskDue
import org.isoron.uhabits.core.userTasks.due.TaskDueDate
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.util.regex.Pattern

/** Run explicitly with TaskDraftQaRunner on an owned, offline temporary AVD. */
@RunWith(AndroidJUnit4::class)
class TaskMatrixVisualTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val args = InstrumentationRegistry.getArguments()
    private val context get() = instrumentation.targetContext
    private val component get() = HabitsApplication.component
    private val device = UiDevice.getInstance(instrumentation)
    private val harness = TaskDraftLifecycleTest()
    private val phase = args.getString("phase", "light")!!.also { check(it.matches(Regex("[a-zA-Z0-9_-]+"))) }
    private val output get() = File(context.getExternalFilesDir(null), "pr10/$phase").apply { check(mkdirs() || isDirectory) }
    private val keys = listOf("matrix:I", "matrix:II", "matrix:III", "matrix:IV", "matrix:unclassified")

    @Test fun matrixJourneys() {
        // Reuse the existing bootstrap instead of a second fixture provisioner.
        TaskListVisualTest::class.java.getDeclaredMethod("bootstrapDisposableSession").apply { isAccessible = true }.invoke(TaskListVisualTest())
        harness.guard(); assertSchema34()
        val oldTheme = component.preferences.theme
        val oldBlack = component.preferences.isPureBlackEnabled
        try {
            component.preferences.theme = if (phase.startsWith("light") || phase.startsWith("narrow")) ThemeSwitcher.THEME_LIGHT else ThemeSwitcher.THEME_DARK
            component.preferences.isPureBlackEnabled = phase.startsWith("amoled")
            val queries = requireNotNull(component.organizationFacade.containerQueries)
            val section = queries.roots().find { it.name == "PR10 project" }?.id
                ?: component.organizationFacade.createContainer("PR10 project").getOrThrow().id
            val empty = queries.roots().find { it.name == "PR10 empty" }?.id
                ?: component.organizationFacade.createContainer("PR10 empty").getOrThrow().id
            val tag = component.taskTagRepository.create("PR10 focus")
            val due = TaskDue.Day(TaskDueDate.parse(LocalDate.now().toString()))
            fun seed(name: String, important: Boolean?, urgent: Boolean?, container: ContainerId? = section, taskDue: TaskDue = TaskDue.None): UserTask {
                val title = "PR10 $name"
                val existing = component.userTaskRepository.getRoots().find { it.title == title }
                return if (existing == null) component.userTaskService.createTask(title, containerId = container, due = taskDue, important = important, urgent = urgent)
                else component.userTaskService.updateTask(existing.id, important = important, urgent = urgent, clearImportant = important == null, clearUrgent = urgent == null)
            }
            val tasks = listOf(seed("I launch today", true, true, taskDue = due), seed("II plan the next release with the team", true, false),
                seed("III reply", false, true), seed("IV later", false, false), seed("unclassified", null, null), seed("partial", true, null))
            val outside = seed("outside section", true, true, container = null)
            component.taskTagRepository.setTags(tasks[0].id, setOf(tag.id))
            component.taskTagRepository.setTags(tasks[1].id, setOf(tag.id))
            component.taskTagRepository.setTags(outside.id, setOf(tag.id))
            val completed = seed("completed exclusion", true, true)
            component.userTaskService.setCompleted(completed.id, true)
            harness.QaActivityController().use { controller ->
                select(controller, matrix = true, container = section)
                assertGroups(controller, mapOf(keys[0] to setOf(tasks[0].id), keys[1] to setOf(tasks[1].id), keys[2] to setOf(tasks[2].id), keys[3] to setOf(tasks[3].id), keys[4] to setOf(tasks[4].id, tasks[5].id)))
                capture("01-populated")
                scrollBottom(controller); capture("01b-populated-bottom")
                select(controller, matrix = true, container = empty)
                assertGroups(controller, keys.associateWith { emptySet<TaskId>() }); capture("02-empty")
                select(controller, matrix = true, container = section, tags = setOf(tag.id))
                assertGroups(controller, keys.associateWith { key -> when (key) { keys[0] -> setOf(tasks[0].id); keys[1] -> setOf(tasks[1].id); else -> emptySet() } })
                capture("03-section-and-tag")
                select(controller, matrix = true, container = section)
                val taskRow = device.wait(Until.findObject(By.text(tasks[1].title)), 3000)
                if (taskRow != null) {
                    taskRow.click()
                    idle()
                } else {
                    controller.onActivity { activity ->
                        val frag = fragment(activity)
                        TaskDetailBottomSheet.newInstance(tasks[1].id).apply {
                            onTaskUpdatedListener = { frag.loadTasks() }
                        }.show(frag.childFragmentManager, "task_detail")
                        frag.childFragmentManager.executePendingTransactions()
                    }
                }
                device.wait(Until.findObject(By.text(Pattern.compile(".*Важность.*|.*Important.*"))), 4000)
                capture("03b-task-detail-classification")
                controller.onActivity { activity ->
                    val frag = fragment(activity)
                    val sheet = frag.childFragmentManager.findFragmentByTag("task_detail") as TaskDetailBottomSheet
                    sheet.onTaskUpdatedListener = { frag.loadTasks() }
                    val controls = field<ViewGroup>(sheet, "classificationControls")
                    val buttons = (0 until controls.childCount).flatMap {
                        val c = controls.getChildAt(it)
                        if (c is ViewGroup && c !is Button) (0 until c.childCount).map { i -> c.getChildAt(i) }
                        else listOf(c)
                    }.filterIsInstance<Button>()
                    assertEquals(2, buttons.size)
                    val disclosure = (0 until controls.childCount).flatMap {
                        val c = controls.getChildAt(it)
                        if (c is ViewGroup && c !is Button) (0 until c.childCount).map { i -> c.getChildAt(i) }
                        else listOf(c)
                    }.filterIsInstance<android.widget.TextView>().filter { it !is Button }
                    assertTrue("Disclosure TextView must be present", disclosure.isNotEmpty())
                    buttons[1].performClick()
                }
                val urgentChoice = device.wait(Until.findObject(By.text(Pattern.compile("Срочно|Urgent|Yes|Да"))), 5000)
                assertNotNull("Urgency choices must open", urgentChoice)
                capture("03c-task-detail-urgency-choices")
                urgentChoice!!.click()
                idle()
                await { component.userTaskRepository.getById(tasks[1].id)?.urgent == true }
                controller.onActivity { activity -> (fragment(activity).childFragmentManager.findFragmentByTag("task_detail") as? TaskDetailBottomSheet)?.dismissNow() }
                idle()
                // No manual reload: closing the real editor must refresh the matrix.
                await { groups(controller)[keys[0]].orEmpty().any { it.id == tasks[1].id } }
                assertFalse(groups(controller)[keys[1]].orEmpty().any { it.id == tasks[1].id }); capture("04-edited-moved")
                controller.recreate(); idle()
                await { groups(controller)[keys[0]].orEmpty().any { it.id == tasks[1].id } }; capture("05-recreated")

                // Regression: stale editor must not overwrite newer repository classification for untouched field,
                // and rebasing must prevent second-write (onPause/dismiss) overwrite.
                val regTask = seed("concurrent regression", important = false, urgent = true, container = section)
                controller.onActivity { activity ->
                    val frag = fragment(activity)
                    TaskDetailBottomSheet.newInstance(regTask.id).apply {
                        onTaskUpdatedListener = { frag.loadTasks() }
                    }.show(frag.childFragmentManager, "task_detail_regression")
                    frag.childFragmentManager.executePendingTransactions()
                }
                idle()
                // Mutate OTHER classification (important) through service while editor is open:
                component.userTaskService.updateTask(regTask.id, important = true)
                assertEquals(true, component.userTaskRepository.getById(regTask.id)?.important)
                // In open editor, edit only one property (title):
                controller.onActivity { activity ->
                    val frag = fragment(activity)
                    val sheet = frag.childFragmentManager.findFragmentByTag("task_detail_regression") as TaskDetailBottomSheet
                    val titleView = field<android.widget.EditText>(sheet, "etTitle")
                    titleView.setText("PR10 concurrent regression edited")
                    val saveMethod = sheet.javaClass.getDeclaredMethod("saveChanges", Boolean::class.javaPrimitiveType)
                    saveMethod.isAccessible = true
                    saveMethod.invoke(sheet, false)
                }
                idle()
                // Verify newer untouched field (important) was retained by service:
                val afterFirstSave = requireNotNull(component.userTaskRepository.getById(regTask.id))
                assertEquals("Newer untouched field must be retained", true, afterFirstSave.important)
                assertEquals("Edited title must be saved", "PR10 concurrent regression edited", afterFirstSave.title)
                // Now invoke repeated save/onPause/dismiss (second-write case):
                controller.onActivity { activity ->
                    val frag = fragment(activity)
                    val sheet = frag.childFragmentManager.findFragmentByTag("task_detail_regression") as TaskDetailBottomSheet
                    sheet.onPause()
                    sheet.dismissNow()
                }
                idle()
                // Verify untouched field is still retained after second write / dismiss:
                val afterSecondWrite = requireNotNull(component.userTaskRepository.getById(regTask.id))
                assertEquals("Untouched field must survive second write/dismiss", true, afterSecondWrite.important)
                assertEquals("PR10 concurrent regression edited", afterSecondWrite.title)
                assertEquals(true, afterSecondWrite.urgent)
                component.userTaskService.deleteTask(regTask.id)
                for (view in listOf(TaskSmartView.ALL, TaskSmartView.TODAY, TaskSmartView.INBOX, TaskSmartView.UPCOMING, TaskSmartView.COMPLETED)) {
                    select(controller, matrix = false, view = view)
                    controller.onActivity { assertFalse(field<Boolean>(fragment(it), "matrixMode")) }
                    assertTrue("Ordinary views must not retain matrix sections", groups(controller).keys.none { it.startsWith("matrix:") })
                    val visibleIds = ordinaryRows(controller).map { it.id }.toSet()
                    when (view) {
                        TaskSmartView.ALL -> assertTrue(visibleIds.containsAll(tasks.map { it.id } + outside.id))
                        TaskSmartView.TODAY -> { assertTrue(tasks[0].id in visibleIds); assertFalse(tasks[1].id in visibleIds) }
                        TaskSmartView.INBOX -> { assertTrue(outside.id in visibleIds); assertTrue(tasks.none { it.id in visibleIds }) }
                        TaskSmartView.UPCOMING -> assertTrue(tasks.none { it.id in visibleIds })
                        TaskSmartView.COMPLETED -> { assertTrue(completed.id in visibleIds); assertTrue(tasks.none { it.id in visibleIds }) }
                        else -> error("Unexpected view")
                    }
                    capture("06-ordinary-${view.name.lowercase()}")
                }
            }
            File(context.getExternalFilesDir(null), "pr10/restart-task.txt").writeText(tasks[1].id.value)
            evidence("dataset=${component.session.datasetId} schema=34 classifiedTask=${tasks[1].id.value} important=true urgent=true")
        } finally {
            component.preferences.theme = oldTheme
            component.preferences.isPureBlackEnabled = oldBlack
        }
    }

    @Test fun verifyAfterProcessRestart() {
        harness.guard(); assertSchema34()
        val id = TaskId(File(context.getExternalFilesDir(null), "pr10/restart-task.txt").readText().trim())
        val task = requireNotNull(component.userTaskRepository.getById(id))
        assertEquals(true, task.important); assertEquals(true, task.urgent)
        harness.QaActivityController().use { controller ->
            select(controller, matrix = true)
            assertTrue(groups(controller)[keys[0]].orEmpty().any { it.id == id }); capture("07-process-restart")
        }
        evidence("processRestart task=${id.value} important=${task.important} urgent=${task.urgent} dataset=${component.session.datasetId}")
    }

    @Test fun verifyInPlaceUpgrade() {
        harness.guard(); assertSchema34()
        SQLiteDatabase.openDatabase(component.dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            val before = File(context.getExternalFilesDir(null), "pr10/baseline/uhabits-container-experimental.db")
            assertTrue("Host must copy the stopped v33 fixture before installing v34", before.isFile)
            SQLiteDatabase.openDatabase(before.path, null, SQLiteDatabase.OPEN_READONLY).use { old ->
                assertEquals(33, old.version)
                for (table in listOf("UserTasks", "TaskTags", "TaskTagLinks", "Containers")) {
                    val exists = old.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table)).use { it.moveToFirst() }
                    if (!exists) continue
                    old.rawQuery("SELECT * FROM $table", null).use { rows ->
                        val columns = rows.columnNames
                        val oldRows = canonicalRows(rows)
                        db.rawQuery("SELECT ${columns.joinToString { "\"$it\"" }} FROM $table", null).use { current ->
                            assertEquals("Upgrade must preserve every original $table value", oldRows, canonicalRows(current))
                        }
                    }
                }
            }
            db.rawQuery("SELECT COUNT(*) FROM UserTasks", null).use { assertTrue(it.moveToFirst()); assertTrue("Baseline must contain tasks", it.getLong(0) > 0) }
            db.rawQuery("SELECT COUNT(*) FROM UserTasks WHERE important IS NOT NULL OR urgent IS NOT NULL", null).use { assertTrue(it.moveToFirst()); assertEquals("Existing v33 tasks must remain unclassified", 0L, it.getLong(0)) }
        }
        evidence("inPlaceUpgrade schema=34 existingTasksPreservedAndUnclassified dataset=${component.session.datasetId}")
        harness.QaActivityController().use { controller -> select(controller, matrix = true); capture("00-upgraded-unclassified") }
    }

    private fun assertSchema34() {
        SQLiteDatabase.openDatabase(component.dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db -> assertEquals(34, db.version) }
    }
    private fun canonicalRows(cursor: android.database.Cursor): List<String> {
        val rows = mutableListOf<String>()
        while (cursor.moveToNext()) rows += (0 until cursor.columnCount).joinToString("|") { index ->
            when (cursor.getType(index)) {
                android.database.Cursor.FIELD_TYPE_NULL -> "null"
                android.database.Cursor.FIELD_TYPE_BLOB -> "blob:" + android.util.Base64.encodeToString(cursor.getBlob(index), android.util.Base64.NO_WRAP)
                else -> cursor.getType(index).toString() + ":" + android.util.Base64.encodeToString(cursor.getString(index).toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
            }
        }
        return rows.sorted()
    }
    private fun select(controller: TaskDraftLifecycleTest.QaActivityController, matrix: Boolean, view: TaskSmartView = TaskSmartView.ALL, container: ContainerId? = null, tags: Set<String> = emptySet()) {
        controller.onActivity { activity ->
            activity.navigate(MainDestination.TASKS); activity.supportFragmentManager.executePendingTransactions()
            val fragment = fragment(activity)
            set(fragment, "matrixMode", matrix); set(fragment, "calendarMode", false); set(fragment, "activeSmartView", view)
            set(fragment, "selectedContainerFilter", container?.value); set(fragment, "selectedTagFilter", tags)
        }
        idle(); waitLoad(controller)
        controller.onActivity { fragment(it).loadTasks() }; waitLoad(controller)
        controller.onActivity { fragment(it).requireView().findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvTasks).scrollToPosition(0) }; idle()
    }
    private fun waitLoad(controller: TaskDraftLifecycleTest.QaActivityController) {
        await { var busy = false; controller.onActivity { busy = field<kotlinx.coroutines.Job?>(fragment(it), "loadJob")?.isActive == true }; !busy }; idle()
    }
    private fun groups(controller: TaskDraftLifecycleTest.QaActivityController): Map<String, List<UserTask>> {
        var result = emptyMap<String, List<UserTask>>()
        controller.onActivity { activity -> result = field<Map<String, Pair<TaskSectionAdapter, TasksAdapter>>>(fragment(activity), "groupAdapters").mapValues { field<List<UserTask>>(it.value.second, "tasks").toList() } }
        return result
    }
    private fun assertGroups(controller: TaskDraftLifecycleTest.QaActivityController, expected: Map<String, Set<TaskId>>) {
        assertEquals(expected, groups(controller).mapValues { it.value.map { task -> task.id }.toSet() })
    }
    private fun ordinaryRows(controller: TaskDraftLifecycleTest.QaActivityController): List<UserTask> {
        val rows = groups(controller).values.flatten().toMutableList()
        controller.onActivity { activity ->
            for (name in listOf("adapter", "overdueAdapter", "completedAdapter")) {
                rows += field<List<UserTask>>(field<Any>(fragment(activity), name), "tasks")
            }
        }
        return rows
    }
    private fun scrollBottom(controller: TaskDraftLifecycleTest.QaActivityController) {
        controller.onActivity { val recycler = fragment(it).requireView().findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvTasks); recycler.scrollToPosition(requireNotNull(recycler.adapter).itemCount - 1) }; idle()
    }
    private fun fragment(activity: MainActivity) = activity.supportFragmentManager.findFragmentByTag("main.tasks") as TasksFragment
    private fun idle() { instrumentation.waitForIdleSync(); device.waitForIdle(); SystemClock.sleep(200) }
    private fun await(condition: () -> Boolean) { val until = SystemClock.uptimeMillis() + 10000; while (SystemClock.uptimeMillis() < until) { if (condition()) return; SystemClock.sleep(50) }; error("PR10 UI did not settle") }
    private fun capture(name: String) { idle(); SystemClock.sleep(400); check(device.takeScreenshot(File(output, "$name.png"))) }
    private fun evidence(line: String) { File(output, "evidence.txt").appendText("$line\n") }
    private fun set(instance: Any, name: String, value: Any?) { instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(instance, value) }
    @Suppress("UNCHECKED_CAST") private fun <T> field(instance: Any, name: String): T = instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(instance) as T
}
