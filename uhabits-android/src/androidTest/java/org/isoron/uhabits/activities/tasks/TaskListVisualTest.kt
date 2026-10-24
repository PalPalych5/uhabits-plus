package org.isoron.uhabits.activities.tasks

import android.graphics.Rect
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.core.view.ViewCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.UiDevice
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.main.MainActivity
import org.isoron.uhabits.activities.main.MainDestination
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.containers.session.DatasetSessionFactory
import org.isoron.uhabits.core.containers.session.ExperimentalDatasetMetadata
import org.isoron.uhabits.utils.DemoDataGenerator
import org.isoron.uhabits.core.ui.ThemeSwitcher
import org.isoron.uhabits.core.userTasks.UserTask
import org.isoron.uhabits.core.userTasks.due.TaskDue
import org.isoron.uhabits.core.userTasks.due.TaskDueDate
import org.isoron.platform.time.setToday
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/** Explicit opt-in only; the reused PR5 guard verifies temporary AVD, offline and disposable DB. */
@RunWith(AndroidJUnit4::class)
class TaskListVisualTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val component get() = HabitsApplication.component
    private val phase = InstrumentationRegistry.getArguments().getString("phase", "after")!!
    private val today = LocalDate.now(ZoneId.of("Asia/Novosibirsk"))
    private val zone = ZoneId.of("Asia/Novosibirsk")

    @Test fun captureListsAndVerifyExistingActions() {
        check(phase.matches(Regex("[a-zA-Z0-9_-]+")))
        setToday(org.isoron.platform.time.LocalDate(today.year, today.monthValue, today.dayOfMonth))
        val harness = TaskDraftLifecycleTest()
        bootstrapDisposableSession()
        harness.guard()
        val parentName = "Работа и проекты"
        val childName = "Разработка приложения и долгосрочные планы команды"
        val queries = requireNotNull(component.organizationFacade.containerQueries)
        val parent = queries.roots().find { it.name == parentName }?.id
            ?: component.organizationFacade.createContainer(parentName).getOrThrow().id
        val child = queries.children(parent).find { it.name == childName }?.id
            ?: component.organizationFacade.createContainer(childName, parentId = parent).getOrThrow().id
        val third = queries.children(child).find { it.name == "Прототипирование" }?.id
            ?: component.organizationFacade.createContainer("Прототипирование", parentId = child).getOrThrow().id
        val fourth = queries.children(third).find { it.name == "Списки и группировки" }?.id
            ?: component.organizationFacade.createContainer("Списки и группировки", parentId = third).getOrThrow().id
        val empty = queries.roots().find { it.name == "Идеи на будущее" }?.id
            ?: component.organizationFacade.createContainer("Идеи на будущее").getOrThrow().id
        fun day(offset: Long) = TaskDue.Day(TaskDueDate.parse(today.plusDays(offset).toString()))
        fun timed(offset: Long, hour: Int) = TaskDue.Timed.local(today.plusDays(offset).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli(), zone.id)
        fun seed(title: String, due: TaskDue = TaskDue.None, container: ContainerId? = null, completed: Boolean = false): UserTask {
            val old = component.userTaskRepository.getRoots().find { it.title == title }
            val task = old ?: component.userTaskService.createTask(title, containerId = container, due = due)
            if (task.due != due) component.userTaskService.updateTask(task.id, due = due)
            if (task.isCompleted != completed) return component.userTaskService.setCompleted(task.id, completed)
            return component.userTaskRepository.getById(task.id) ?: task
        }
        val noDue = seed("Разобрать входящие идеи")
        seed("Отправить документы на согласование", day(-1), parent)
        seed("Обсудить макеты с командой", timed(-1, 15), child)
        val dueToday = seed("Подготовить план на следующую неделю и согласовать приоритеты с командой разработки", day(0), child)
        seed("Проверить заметки к встрече", timed(0, 23), parent)
        seed("Заказать материалы для рабочего места", day(1))
        seed("Изучить новые идеи для интерфейса", container = parent)
        seed("Собрать обратную связь по прототипу", day(3), child, completed = true)
        seed("Обновить список материалов", container = parent, completed = true)
        seed("Проверить вложенный раздел четвёртого уровня", container = fourth)
        seed("Обсудить следующий этап", day(2), parent)
        seed("Проверить точный срок", timed(2, 14), child)
        seed("Подготовить итоговый отчёт", day(10), child)
        val yesterday = seed("Завершено вчера", completed = true)
        component.userTaskRepository.save(yesterday.copy(completedAt = today.minusDays(1).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()))
        val unknown = seed("Историческая задача без даты завершения", day(-7), completed = true)
        component.userTaskRepository.save(unknown.copy(completedAt = null))
        val oldTheme = component.preferences.theme
        val oldBlack = component.preferences.isPureBlackEnabled
        try {
            component.preferences.theme = ThemeSwitcher.THEME_LIGHT
            component.preferences.isPureBlackEnabled = false
            harness.QaActivityController().use { controller ->
                fun select(view: TaskSmartView, container: ContainerId? = null) {
                    controller.onActivity { activity ->
                        activity.navigate(MainDestination.TASKS)
                        activity.supportFragmentManager.executePendingTransactions()
                        val fragment = tasks(activity)
                        set(fragment, "activeSmartView", view)
                        // ContainerId is an inline value class; the backing JVM field is String.
                        set(fragment, "selectedContainerFilter", container?.value)
                        invoke(fragment, "loadTasks")
                    }
                    idle()
                    await { var ready = false; controller.onActivity { ready = field<Any?>(tasks(it), "loadJob") == null || field<kotlinx.coroutines.Job?>(tasks(it), "loadJob")?.isActive != true }; ready }
                    controller.onActivity { tasks(it).requireView().findViewById<RecyclerView>(R.id.rvTasks).scrollToPosition(0) }
                    idle()
                }
                select(TaskSmartView.ALL)
                screenshot("01-all-light")
                if (phase != "before") controller.onActivity { activity ->
                    val groups = field<Map<String, Pair<TaskSectionAdapter, TasksAdapter>>>(tasks(activity), "groupAdapters")
                    val ids = groups.values.flatMap { field<List<UserTask>>(it.second, "tasks") }.map { it.id }
                    assertEquals("Direct container groups must never duplicate tasks", ids.size, ids.distinct().size)
                    assertTrue("Fourth-level container must have its own direct group", "container:${fourth.value}" in groups)
                }
                if (phase != "before") {
                    clickSection(controller, "Просроченные")
                    controller.onActivity { assertFalse(field<Boolean>(tasks(it), "overdueExpanded")) }
                    clickSection(controller, "Просроченные")
                    controller.onActivity { assertTrue(field<Boolean>(tasks(it), "overdueExpanded")) }
                    clickSection(controller, "Завершённые")
                    controller.onActivity { assertTrue(field<Boolean>(tasks(it), "completedExpanded")) }
                    screenshot("01b-completed-expanded-light")
                    // Scroll to bottom to verify that the second completed task is completely visible when scrolled:
                    controller.onActivity { activity ->
                        val recycler = tasks(activity).requireView().findViewById<RecyclerView>(R.id.rvTasks)
                        recycler.scrollToPosition(recycler.adapter!!.itemCount - 1)
                    }
                    idle()
                    screenshot("01c-completed-scrolled-bottom-light")
                    controller.onActivity { activity ->
                        val fragment = tasks(activity)
                        val quickAdd = fragment.requireView().findViewById<View>(R.id.layoutQuickAddContainer)
                        val row1 = findRow(fragment, "Обновить список материалов")
                        val row2 = findRow(fragment, "Собрать обратную связь по прототипу")
                        assertNotNull("First completed row must be attached", row1)
                        assertNotNull("Second completed row must be attached", row2)
                        val quickAddRect = Rect()
                        assertTrue(quickAdd.getGlobalVisibleRect(quickAddRect))
                        val row2Rect = Rect()
                        assertTrue("Second completed row must be visible when scrolled to bottom", row2!!.getGlobalVisibleRect(row2Rect))
                        assertTrue("Second completed row must not be overlapped by quick-add bar: row2Bottom=${row2Rect.bottom} quickAddTop=${quickAddRect.top}", row2Rect.bottom <= quickAddRect.top)
                    }
                    controller.recreate()
                    select(TaskSmartView.ALL)
                    controller.onActivity { assertTrue("Expanded completed section survives recreation", field<Boolean>(tasks(it), "completedExpanded")) }
                    clickSection(controller, "Завершённые")
                }
                select(TaskSmartView.TODAY)
                screenshot("02-today-light")
                if (phase != "before") controller.onActivity { activity ->
                    val fragment = tasks(activity)
                    val active = field<List<UserTask>>(field<Any>(fragment, "adapter"), "tasks")
                    val overdue = field<List<UserTask>>(field<Any>(fragment, "overdueAdapter"), "tasks")
                    assertTrue("Today and overdue must not duplicate task IDs", active.map { it.id }.intersect(overdue.map { it.id }.toSet()).isEmpty())
                    assertTrue("DAY today must remain active for the whole day", active.any { it.id == dueToday.id })
                    assertTrue("NONE must not enter Today", (active + overdue).none { it.id == noDue.id })
                }
                select(TaskSmartView.ALL, parent)
                controller.onActivity { activity ->
                    val fragment = tasks(activity)
                    val active = field<List<UserTask>>(field<Any>(fragment, "adapter"), "tasks")
                    val overdue = field<List<UserTask>>(field<Any>(fragment, "overdueAdapter"), "tasks")
                    val scope = queries.subtreeIds(parent) + parent
                    val expected = component.userTaskRepository.getRoots().filter { !it.isCompleted && it.containerId in scope }.map { it.id }.toSet()
                    val rendered = (active + overdue).map { it.id }.toSet()
                    assertEquals("Parent filter must include exactly its active descendant roots", expected, rendered)
                    assertTrue("Child-container DAY task must appear in parent scope", dueToday.id in rendered)
                    assertFalse("Unassigned task must not enter a container scope", noDue.id in rendered)
                }
                screenshot("03-container-light")
                select(TaskSmartView.ALL, child)
                controller.onActivity { activity ->
                    val title = tasks(activity).requireView().findViewById<TextView>(R.id.tvTasksTitle)
                    assertEquals(childName, title.text.toString())
                    assertEquals("Long section title must stay on one line", 1, title.maxLines)
                    val layout = requireNotNull(title.layout)
                    assertEquals("Long section title must not grow the header", 1, layout.lineCount)
                    assertTrue("Long section title must fit or ellipsize", layout.getLineWidth(0) <= title.width - title.paddingLeft - title.paddingRight + 1 || layout.getEllipsisCount(0) > 0)
                    val actual = Rect()
                    assertTrue("Long section title must remain visible", title.getGlobalVisibleRect(actual))
                    assertEquals("Long section title must not be clipped vertically", title.height, actual.height())
                }
                screenshot("03b-child-long-title-light")
                select(TaskSmartView.ALL, fourth)
                screenshot("03c-fourth-level-light")
                select(TaskSmartView.COMPLETED)
                screenshot("04-completed-light")
                if (phase != "before") controller.onActivity { activity ->
                    val groups = field<Map<String, Pair<TaskSectionAdapter, TasksAdapter>>>(tasks(activity), "groupAdapters")
                    assertTrue("Undated completions must be separate", "completed:unknown" in groups)
                    assertEquals(listOf(unknown.id), field<List<UserTask>>(groups.getValue("completed:unknown").second, "tasks").map { it.id })
                    val adapter = groups.getValue("completed:unknown").second
                    val recycler = tasks(activity).requireView().findViewById<RecyclerView>(R.id.rvTasks)
                    val holder = adapter.onCreateViewHolder(recycler, 0)
                    // Rebind one real row holder to catch stale completion text as well as invalid dates.
                    for (completedAt in listOf(yesterday.completedAt, 0L, -1L, null)) {
                        holder.bind(unknown.copy(completedAt = completedAt))
                        val metadata = holder.itemView.findViewById<TextView>(R.id.tvTaskMeta)
                        val hasCompletion = completedAt != null && completedAt > 0L
                        assertEquals("Completion date visibility for $completedAt", hasCompletion,
                            metadata.text.toString().contains("Выполнено "))
                        assertEquals("Spoken completion date visibility for $completedAt", hasCompletion,
                            metadata.contentDescription.toString().contains("Выполнено "))
                    }
                }
                select(TaskSmartView.UPCOMING)
                screenshot("04b-upcoming-light")
                if (phase != "before") controller.onActivity { activity ->
                    val groups = field<Map<String, Pair<TaskSectionAdapter, TasksAdapter>>>(tasks(activity), "groupAdapters")
                    assertTrue("Upcoming fixture must span at least three local dates", groups.size >= 3)
                    assertTrue(groups.keys.all { it.startsWith("due:") })
                }
                select(TaskSmartView.ALL, empty)
                screenshot("05-empty-light")
                select(TaskSmartView.INBOX)
                screenshot("06-inbox-light")

                // Click the actual list checkbox. A checkbox action must not open the editor.
                controller.onActivity { activity ->
                    val fragment = tasks(activity)
                    val row = findRow(fragment, noDue.title)
                    assertNotNull("Inbox fixture must be attached", row)
                    row!!.findViewById<View>(R.id.btnTaskCheck).performClick()
                    fragment.childFragmentManager.executePendingTransactions()
                    assertNull(fragment.childFragmentManager.findFragmentByTag("task_detail"))
                }
                idle()
                assertTrue(component.userTaskRepository.getById(noDue.id)!!.isCompleted)
                select(TaskSmartView.COMPLETED)
                controller.onActivity { activity ->
                    val fragment = tasks(activity)
                    val row = findRow(fragment, noDue.title)
                    assertNotNull("Completed fixture must be attached", row)
                    row!!.findViewById<View>(R.id.btnTaskCheck).performClick()
                }
                idle()
                assertFalse(component.userTaskRepository.getById(noDue.id)!!.isCompleted)

                // Open PR5 editor through its row and edit Due through the same picker result seam.
                select(TaskSmartView.TODAY)
                controller.onActivity { activity ->
                    val fragment = tasks(activity)
                    val row = findRow(fragment, dueToday.title)
                    assertNotNull("Today's DAY fixture must be visible", row)
                    row!!.performClick()
                    fragment.childFragmentManager.executePendingTransactions()
                    val sheet = fragment.childFragmentManager.findFragmentByTag("task_detail") as TaskDetailBottomSheet
                    assertEquals(dueToday.title, field<EditText>(sheet, "etTitle").text.toString())
                    sheet.childFragmentManager.setFragmentResult("detailDue", TaskDuePickerBottomSheet.encode(TaskDue.None).apply { putBoolean(TaskDuePickerBottomSheet.RESULT_EDITED, true) })
                }
                idle()
                assertEquals(TaskDue.None, component.userTaskRepository.getById(dueToday.id)!!.due)
                device.pressBack(); idle()
                // Restore only this synthetic task, preserving a comparable fixture for AFTER captures.
                component.userTaskService.updateTask(dueToday.id, due = day(0))

                for ((name, black) in listOf("dark" to false, "amoled" to true)) {
                    component.preferences.theme = ThemeSwitcher.THEME_DARK
                    component.preferences.isPureBlackEnabled = black
                    controller.recreate()
                    select(TaskSmartView.ALL)
                    screenshot("07-all-$name")
                    select(TaskSmartView.TODAY)
                    screenshot("08-today-$name")
                    select(TaskSmartView.UPCOMING)
                    screenshot("08b-upcoming-$name")
                    select(TaskSmartView.COMPLETED)
                    screenshot("08c-completed-$name")
                }
                val oldFontScale = context.resources.configuration.fontScale
                try {
                    device.executeShellCommand("settings put system font_scale 1.3")
                    device.executeShellCommand("wm size 720x1280")
                    device.executeShellCommand("wm density 320")
                    idle()
                    for ((name, theme, black) in listOf(Triple("light", ThemeSwitcher.THEME_LIGHT, false),
                        Triple("dark", ThemeSwitcher.THEME_DARK, false), Triple("amoled", ThemeSwitcher.THEME_DARK, true))) {
                        component.preferences.theme = theme
                        component.preferences.isPureBlackEnabled = black
                        controller.recreate()
                        select(TaskSmartView.ALL)
                        if (phase != "before") controller.onActivity { assertVisibleListTextGeometry(it) }
                        screenshot("11-all-font130-$name")
                        select(TaskSmartView.UPCOMING)
                        if (phase != "before") controller.onActivity { assertVisibleListTextGeometry(it) }
                        screenshot("12-upcoming-font130-$name")
                        select(TaskSmartView.COMPLETED)
                        if (phase != "before") controller.onActivity { assertVisibleListTextGeometry(it) }
                        screenshot("13-completed-font130-$name")
                    }
                } finally {
                    device.executeShellCommand("settings put system font_scale $oldFontScale")
                    device.executeShellCommand("wm size reset")
                    device.executeShellCommand("wm density reset")
                    idle()
                }
                if (InstrumentationRegistry.getArguments().getString("longList") == "true") {
                    captureLongList(controller)
                }
            }
        } finally {
            instrumentation.runOnMainSync { component.preferences.theme = oldTheme; component.preferences.isPureBlackEnabled = oldBlack }
        }
    }

    @Test fun captureLongListAndVerifyChrome() {
        check(phase.matches(Regex("[a-zA-Z0-9_-]+")))
        val harness = TaskDraftLifecycleTest()
        bootstrapDisposableSession()
        harness.guard()
        val oldTheme = component.preferences.theme
        val oldBlack = component.preferences.isPureBlackEnabled
        try {
            harness.QaActivityController().use { captureLongList(it) }
        } finally {
            instrumentation.runOnMainSync { component.preferences.theme = oldTheme; component.preferences.isPureBlackEnabled = oldBlack }
        }
    }

    private fun bootstrapDisposableSession() {
        val authorizedAvd = InstrumentationRegistry.getArguments().getString("authorizedAvd")
        if (authorizedAvd?.startsWith("UHabit_Temp_") != true ||
            device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim() != authorizedAvd) return
        if (Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 1 ||
            Settings.Global.getInt(context.contentResolver, Settings.Global.WIFI_ON, 0) != 0) {
            device.executeShellCommand("svc wifi disable")
            device.executeShellCommand("cmd connectivity airplane-mode enable")
            device.executeShellCommand("settings put global airplane_mode_on 1")
            device.executeShellCommand("settings put global wifi_on 0")
            val deadline = SystemClock.uptimeMillis() + 5_000
            while (SystemClock.uptimeMillis() < deadline &&
                (Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 1 ||
                    Settings.Global.getInt(context.contentResolver, Settings.Global.WIFI_ON, 0) != 0)) {
                device.executeShellCommand("settings put global airplane_mode_on 1")
                device.executeShellCommand("settings put global wifi_on 0")
                SystemClock.sleep(100)
            }
        }
        if (component.session.isExperimental) return
        val app = context.applicationContext as HabitsApplication
        val metadata = ExperimentalDatasetMetadata(
            datasetId = "qa-temp-pr7",
            databaseFilename = DatasetSessionFactory.EXPERIMENTAL_DB_FILENAME,
            foundationVersion = 1,
            sourceSnapshotSha256 = "",
            createdAtMillis = System.currentTimeMillis()
        )
        val session = DatasetSessionFactory.experimental(metadata)
        File(context.filesDir, DemoDataGenerator.DISPOSABLE_MARKER + '-' + session.datasetId)
            .writeText(session.databaseFilename)
        instrumentation.runOnMainSync { app.switchDatasetSession(session) }
        val stmt = component.db.prepareStatement(
            "UPDATE OrganizationState SET dataset_uuid = ?, mode = 'CONTAINER_LOCAL' WHERE id = 1"
        )
        stmt.bindText(1, session.datasetId)
        stmt.step()
        stmt.finalize()
        component.preferences.isFirstRun = false
        app.sessionManager.persistExperimentalSelection(metadata)
    }

    private fun assertVisibleListTextGeometry(activity: MainActivity) {
        val recycler = tasks(activity).requireView().findViewById<RecyclerView>(R.id.rvTasks)
        val sectionTitleId = context.resources.getIdentifier("tvSectionTitle", "id", context.packageName)
        val visibleText = texts(recycler).filter {
            (it.id == R.id.tvTaskTitle || (sectionTitleId != 0 && it.id == sectionTitleId) ||
                ViewCompat.isAccessibilityHeading(it)) && it.isShown && it.getGlobalVisibleRect(Rect())
        }
        assertTrue("Fixture must expose task titles", visibleText.any { it.id == R.id.tvTaskTitle })
        assertTrue("Fixture must expose section headings", visibleText.any { it.id != R.id.tvTaskTitle })
        val bounds = visibleText.map { text ->
            val label = text.text.toString()
            val layout = requireNotNull(text.layout) { "Missing text layout: $label" }
            assertTrue("Text must have rendered lines: $label", layout.lineCount > 0)
            assertTrue("Text must fit vertically: $label", layout.height <= text.height - text.compoundPaddingTop - text.compoundPaddingBottom)
            for (line in 0 until layout.lineCount) {
                assertTrue("Text must fit horizontally or ellipsize: $label",
                    layout.getLineWidth(line) <= text.width - text.compoundPaddingLeft - text.compoundPaddingRight + 1 ||
                        layout.getEllipsisCount(line) > 0)
            }
            val position = IntArray(2)
            text.getLocationOnScreen(position)
            val full = Rect(position[0], position[1], position[0] + text.width, position[1] + text.height)
            val visible = Rect()
            assertTrue(text.getGlobalVisibleRect(visible))
            // RecyclerView may deliberately expose a partial row at either viewport edge.
            val viewport = Rect()
            assertTrue(recycler.getGlobalVisibleRect(viewport))
            val expected = Rect(full)
            assertTrue(expected.intersect(viewport))
            assertEquals("Text must not be clipped inside its visible row: $label", expected, visible)
            full
        }
        for (i in bounds.indices) for (j in i + 1 until bounds.size) {
            assertFalse("Section headings and task titles must not overlap", Rect.intersects(bounds[i], bounds[j]))
        }
    }

    private fun captureLongList(controller: TaskDraftLifecycleTest.QaActivityController) {
        val existingTitles = component.userTaskRepository.getRoots().map { it.title }.toSet()
        repeat(100) {
            val title = "Проверка прокрутки · задача ${(it + 1).toString().padStart(3, '0')}"
            if (title !in existingTitles) component.userTaskService.createTask(title)
        }
        component.preferences.theme = ThemeSwitcher.THEME_LIGHT
        component.preferences.isPureBlackEnabled = false
        controller.recreate()
        controller.onActivity { activity ->
            activity.navigate(MainDestination.TASKS)
            activity.supportFragmentManager.executePendingTransactions()
            val fragment = tasks(activity)
            set(fragment, "activeSmartView", TaskSmartView.ALL)
            set(fragment, "selectedContainerFilter", null)
            invoke(fragment, "loadTasks")
        }
        await {
            var loaded = false
            controller.onActivity {
                val fragment = tasks(it)
                loaded = field<kotlinx.coroutines.Job?>(fragment, "loadJob")?.isActive != true &&
                    fragment.requireView().findViewById<RecyclerView>(R.id.rvTasks).adapter!!.itemCount >= 100
            }
            loaded
        }
        idle()
        SystemClock.sleep(1_000)
        controller.onActivity { activity ->
            val recycler = tasks(activity).requireView().findViewById<RecyclerView>(R.id.rvTasks)
            recycler.scrollToPosition(recycler.adapter!!.itemCount - 1)
        }
        idle()
        SystemClock.sleep(1_000)
        controller.onActivity { activity ->
            assertChromeVisible(activity)
            assertListContinuityAndClearance(activity)
        }
        screenshot("09-long-list-bottom")
        controller.onActivity { tasks(it).requireView().findViewById<RecyclerView>(R.id.rvTasks).scrollToPosition(0) }
        idle()
        SystemClock.sleep(1_000)
        controller.onActivity { activity ->
            assertChromeVisible(activity)
            assertListContinuityAndClearance(activity)
        }
        screenshot("10-long-list-top")
    }

    private fun assertListContinuityAndClearance(activity: MainActivity) {
        val fragment = tasks(activity)
        val recycler = fragment.requireView().findViewById<RecyclerView>(R.id.rvTasks)
        val quickAdd = fragment.requireView().findViewById<View>(R.id.layoutQuickAddContainer)
        val quickAddRect = Rect()
        assertTrue(quickAdd.getGlobalVisibleRect(quickAddRect))
        val count = recycler.childCount
        assertTrue("Recycler must have children", count > 0)
        for (i in 0 until count - 1) {
            val childA = recycler.getChildAt(i)
            val childB = recycler.getChildAt(i + 1)
            assertEquals("Adjacent rows must be contiguous with no gaps or overlaps", childA.bottom, childB.top)
        }
        val lastChild = recycler.getChildAt(count - 1)
        val lastRect = Rect()
        if (lastChild.getGlobalVisibleRect(lastRect)) {
            assertTrue("Last child must not be overlapped by quick add bar: ${lastRect.bottom} vs ${quickAddRect.top}", lastRect.bottom <= quickAddRect.top)
        }
    }

    private fun findRow(fragment: TasksFragment, title: String): View? {
        val recycler = fragment.requireView().findViewById<RecyclerView>(R.id.rvTasks)
        return (0 until recycler.childCount).map(recycler::getChildAt).find { it.findViewById<TextView>(R.id.tvTaskTitle)?.text?.toString() == title }
    }
    private fun clickSection(controller: TaskDraftLifecycleTest.QaActivityController, label: String) {
        var count = 0
        controller.onActivity { count = tasks(it).requireView().findViewById<RecyclerView>(R.id.rvTasks).adapter!!.itemCount }
        for (position in 0 until count) {
            controller.onActivity { tasks(it).requireView().findViewById<RecyclerView>(R.id.rvTasks).scrollToPosition(position) }
            idle()
            var clicked = false
            controller.onActivity { activity ->
                val recycler = tasks(activity).requireView().findViewById<RecyclerView>(R.id.rvTasks)
                val header = texts(recycler).firstOrNull { it.text.toString().startsWith(label) }
                if (header != null) {
                    if (label == "Завершённые") assertFalse("Completed heading must not show a count", header.text.any(Char::isDigit))
                    header.performClick()
                    clicked = true
                }
            }
            if (clicked) { idle(); return }
        }
        error("Section header was not found: $label")
    }
    private fun texts(view: View): List<TextView> = when (view) {
        is TextView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
        else -> emptyList()
    }
    private fun assertChromeVisible(activity: MainActivity) {
        val screen = Rect()
        assertTrue(activity.window.decorView.getGlobalVisibleRect(screen))
        fun fullyVisible(view: View, parentBounds: Rect, label: String): Rect {
            val visible = Rect()
            assertTrue("$label must be shown with nonzero bounds", view.isShown && view.alpha > 0f && view.width > 0 && view.height > 0)
            assertTrue("$label must have a visible global rectangle", view.getGlobalVisibleRect(visible))
            assertTrue("$label must fit within its visible parent: $visible vs $parentBounds", parentBounds.contains(visible))
            // The selected navigation icon scales up. Compare transformed dimensions rather
            // than the untransformed layout rectangle; allow one pixel for raster rounding.
            val expectedWidth = view.width * kotlin.math.abs(view.scaleX)
            val expectedHeight = view.height * kotlin.math.abs(view.scaleY)
            assertTrue("$label must not be clipped horizontally: $visible expected=$expectedWidth", kotlin.math.abs(visible.width() - expectedWidth) <= 1f)
            assertTrue("$label must not be clipped vertically: $visible expected=$expectedHeight", kotlin.math.abs(visible.height() - expectedHeight) <= 1f)
            return visible
        }
        fullyVisible(tasks(activity).requireView().findViewById(R.id.tvTasksTitle), screen, "Tasks title")
        val navigation = fullyVisible(activity.findViewById(R.id.bottomNavigation), screen, "Bottom navigation")
        fullyVisible(activity.findViewById(R.id.iconTasks), navigation, "Tasks navigation icon")
        fullyVisible(activity.findViewById(R.id.labelTasks), navigation, "Tasks navigation label")
    }
    private fun tasks(activity: MainActivity) = activity.supportFragmentManager.findFragmentByTag("main.tasks") as TasksFragment
    private fun idle() { instrumentation.waitForIdleSync(); device.waitForIdle(); SystemClock.sleep(150) }
    private fun await(condition: () -> Boolean) { val deadline = SystemClock.uptimeMillis() + 10_000; while (SystemClock.uptimeMillis() < deadline) { if (condition()) return; SystemClock.sleep(50) }; error("List did not settle") }
    private fun screenshot(name: String) {
        if (InstrumentationRegistry.getArguments().getString("resurface") == "true") {
            device.pressRecentApps()
            SystemClock.sleep(500)
            device.click(device.displayWidth / 2, device.displayHeight / 2)
            await { device.currentPackageName == context.packageName }
            idle()
        }
        instrumentation.runOnMainSync {
            fun invalidateTree(view: View) {
                view.invalidate()
                if (view is ViewGroup) for (index in 0 until view.childCount) invalidateTree(view.getChildAt(index))
            }
            val activities = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MainActivity>().filter { !it.isFinishing && !it.isDestroyed }
            check(activities.size == 1) { "Expected one resumed MainActivity before screenshot" }
            val decor = activities.single().window.decorView
            invalidateTree(decor)
            decor.postInvalidateOnAnimation()
        }
        idle()
        SystemClock.sleep(500)
        val file = File(context.getExternalFilesDir(null), "pr7/$phase/$name.png")
        check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
        check(device.takeScreenshot(file))
    }
    private fun set(instance: Any, name: String, value: Any?) { instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(instance, value) }
    @Suppress("UNCHECKED_CAST") private fun <T> field(instance: Any, name: String): T = instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(instance) as T
    private fun invoke(instance: Any, name: String): Any? = instance.javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(instance)
}
