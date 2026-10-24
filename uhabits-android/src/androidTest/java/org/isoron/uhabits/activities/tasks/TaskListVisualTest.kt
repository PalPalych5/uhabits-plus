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

    @Test fun verifyPr8QuickActionsMultiSelectionAndUndo() {
        check(phase.matches(Regex("[a-zA-Z0-9_-]+")))
        setToday(org.isoron.platform.time.LocalDate(today.year, today.monthValue, today.dayOfMonth))
        val harness = TaskDraftLifecycleTest()
        bootstrapDisposableSession()
        harness.guard()

        // Clean existing tasks in disposable DB for deterministic PR8 verification
        for (root in component.userTaskRepository.getRoots()) {
            component.userTaskService.deleteTask(root.id, cascadeSubtasks = true)
        }

        val queries = requireNotNull(component.organizationFacade.containerQueries)
        val workName = "Работа и проекты"
        val homeName = "Дом и личное"
        val work = queries.roots().find { it.name == workName }?.id
            ?: component.organizationFacade.createContainer(workName).getOrThrow().id
        val home = queries.roots().find { it.name == homeName }?.id
            ?: component.organizationFacade.createContainer(homeName).getOrThrow().id

        fun day(offset: Long) = TaskDue.Day(TaskDueDate.parse(today.plusDays(offset).toString()))
        fun timed(offset: Long, hour: Int, minute: Int = 0) =
            TaskDue.Timed.local(today.plusDays(offset).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli(), zone.id)

        val tInbox = component.userTaskService.createTask("Разобрать почту и входящие заметки", due = day(0))
        val tWorkToday = component.userTaskService.createTask("Согласовать архитектуру спринта", containerId = work, due = timed(0, 18, 30))
        val tOverdue = component.userTaskService.createTask("Отправить квартальный отчёт", containerId = work, due = day(-1))
        val tUpcoming1 = component.userTaskService.createTask("Подготовить демо для ревью", containerId = work, due = day(1))
        val tUpcoming2 = component.userTaskService.createTask("Заказать книги для чтения", containerId = home, due = timed(2, 14, 0))
        val tSubParent = component.userTaskService.createTask("Собрать дорожную карту релиза", containerId = work, due = day(0))
        val tSubChild = component.userTaskService.createTask("Проверить чеклист миграции", parentTaskId = tSubParent.id, containerId = work)

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
                        set(fragment, "selectedContainerFilter", container?.value)
                        invoke(fragment, "loadTasks")
                    }
                    idle()
                    await {
                        var ready = false
                        controller.onActivity {
                            ready = field<Any?>(tasks(it), "loadJob") == null ||
                                field<kotlinx.coroutines.Job?>(tasks(it), "loadJob")?.isActive != true
                        }
                        ready
                    }
                    controller.onActivity { tasks(it).requireView().findViewById<RecyclerView>(R.id.rvTasks).scrollToPosition(0) }
                    idle()
                }

                // 1. Capture visual swipe previews (Right = Срок, Left = Раздел) and verify real swipe opens pickers
                select(TaskSmartView.TODAY)
                var previewDecoration: RecyclerView.ItemDecoration? = null
                controller.onActivity { activity ->
                    val fragment = tasks(activity)
                    val recycler = fragment.requireView().findViewById<RecyclerView>(R.id.rvTasks)
                    val row = requireNotNull(findRow(fragment, tInbox.title))
                    val holder = recycler.getChildViewHolder(row)
                    val callback = field<TaskSwipeCallback>(fragment, "swipeCallback")
                    val decoration = object : RecyclerView.ItemDecoration() {
                        override fun onDraw(c: android.graphics.Canvas, parent: RecyclerView, state: RecyclerView.State) {
                            callback.onChildDraw(
                                c,
                                parent,
                                holder,
                                TaskThemeUtils.dp(activity, 108f),
                                0f,
                                androidx.recyclerview.widget.ItemTouchHelper.ACTION_STATE_SWIPE,
                                true,
                            )
                        }
                    }
                    previewDecoration = decoration
                    recycler.addItemDecoration(decoration)
                    recycler.invalidate()
                }
                screenshot("pr8-01-swipe-right-due-light")
                controller.onActivity { activity ->
                    val fragment = tasks(activity)
                    val recycler = fragment.requireView().findViewById<RecyclerView>(R.id.rvTasks)
                    previewDecoration?.let { recycler.removeItemDecoration(it) }
                    val row = requireNotNull(findRow(fragment, tInbox.title))
                    row.translationX = 0f
                    val holder = recycler.getChildViewHolder(row)
                    val callback = field<TaskSwipeCallback>(fragment, "swipeCallback")
                    val decoration = object : RecyclerView.ItemDecoration() {
                        override fun onDraw(c: android.graphics.Canvas, parent: RecyclerView, state: RecyclerView.State) {
                            callback.onChildDraw(
                                c,
                                parent,
                                holder,
                                -TaskThemeUtils.dp(activity, 108f),
                                0f,
                                androidx.recyclerview.widget.ItemTouchHelper.ACTION_STATE_SWIPE,
                                true,
                            )
                        }
                    }
                    previewDecoration = decoration
                    recycler.addItemDecoration(decoration)
                    recycler.invalidate()
                }
                screenshot("pr8-02-swipe-left-container-light")
                controller.onActivity { activity ->
                    val fragment = tasks(activity)
                    val recycler = fragment.requireView().findViewById<RecyclerView>(R.id.rvTasks)
                    previewDecoration?.let { recycler.removeItemDecoration(it) }
                    previewDecoration = null
                    findRow(fragment, tInbox.title)?.translationX = 0f
                    recycler.invalidate()
                }
                idle()

                // 2. Real swipe right in UPCOMING opens TaskDuePickerBottomSheet & moves task to new date group without duplication
                select(TaskSmartView.UPCOMING)
                var rowRect = Rect()
                controller.onActivity { activity ->
                    val row = requireNotNull(findRow(tasks(activity), tUpcoming1.title))
                    assertTrue(row.getGlobalVisibleRect(rowRect))
                }
                val swipeStartX = rowRect.left + rowRect.width() / 5
                val swipeEndX = rowRect.right - rowRect.width() / 5
                device.swipe(swipeStartX, rowRect.centerY(), swipeEndX, rowRect.centerY(), 18)
                await {
                    var opened = false
                    controller.onActivity { activity ->
                        val fragment = tasks(activity)
                        fragment.childFragmentManager.executePendingTransactions()
                        opened = fragment.childFragmentManager.findFragmentByTag(TasksFragment.REQUEST_KEY_ACTION_DUE) is TaskDuePickerBottomSheet
                    }
                    opened
                }
                // First, dismiss the picker without changes and verify the row snaps back and can be swiped a second time!
                controller.onActivity { activity ->
                    val fragment = tasks(activity)
                    val sheet = fragment.childFragmentManager.findFragmentByTag(TasksFragment.REQUEST_KEY_ACTION_DUE) as? TaskDuePickerBottomSheet
                    assertNotNull("Swipe right must open TaskDuePickerBottomSheet", sheet)
                    sheet?.dismissAllowingStateLoss()
                    fragment.childFragmentManager.executePendingTransactions()
                }
                idle()
                controller.onActivity { activity ->
                    val row = requireNotNull(findRow(tasks(activity), tUpcoming1.title))
                    assertEquals("Row must snap back to 0 translationX when picker is dismissed", 0f, row.translationX, 0.5f)
                    assertTrue(row.getGlobalVisibleRect(rowRect))
                }
                // Swipe the exact same row a second time ("повторные быстрые действия")
                device.swipe(rowRect.left + rowRect.width() / 5, rowRect.centerY(), rowRect.right - rowRect.width() / 5, rowRect.centerY(), 18)
                await {
                    var opened = false
                    controller.onActivity { activity ->
                        val fragment = tasks(activity)
                        fragment.childFragmentManager.executePendingTransactions()
                        opened = fragment.childFragmentManager.findFragmentByTag(TasksFragment.REQUEST_KEY_ACTION_DUE) is TaskDuePickerBottomSheet
                    }
                    opened
                }
                screenshot("pr8-03-swipe-due-picker-open-light")
                controller.onActivity { activity ->
                    val fragment = tasks(activity)
                    val sheet = fragment.childFragmentManager.findFragmentByTag(TasksFragment.REQUEST_KEY_ACTION_DUE) as? TaskDuePickerBottomSheet
                    sheet?.dismissAllowingStateLoss()
                    fragment.childFragmentManager.setFragmentResult(
                        TasksFragment.REQUEST_KEY_ACTION_DUE,
                        TaskDuePickerBottomSheet.encode(day(5)).apply {
                            putBoolean(TaskDuePickerBottomSheet.RESULT_EDITED, true)
                        },
                    )
                }
                idle()
                await { component.userTaskRepository.getById(tUpcoming1.id)?.due == day(5) }
                controller.onActivity { activity ->
                    val groups = field<Map<String, Pair<TaskSectionAdapter, TasksAdapter>>>(tasks(activity), "groupAdapters")
                    val allIds = groups.values.flatMap { field<List<UserTask>>(it.second, "tasks") }.map { it.id }
                    assertEquals("Task must move to new date group in UPCOMING without duplication", allIds.size, allIds.distinct().size)
                    assertTrue(tUpcoming1.id in allIds)
                }
                // Undo the swipe due change via performUndo
                controller.onActivity { activity ->
                    assertEquals(1, tasks(activity).performUndo())
                }
                idle()
                assertEquals(day(1), component.userTaskRepository.getById(tUpcoming1.id)?.due)

                // 3. Real swipe left in ALL opens TaskContainerPickerBottomSheet & moves task to section without duplication
                select(TaskSmartView.ALL)
                controller.onActivity { activity ->
                    val row = requireNotNull(findRow(tasks(activity), tInbox.title))
                    assertTrue(row.getGlobalVisibleRect(rowRect))
                }
                device.swipe(rowRect.right - rowRect.width() / 5, rowRect.centerY(), rowRect.left + rowRect.width() / 5, rowRect.centerY(), 18)
                await {
                    var opened = false
                    controller.onActivity { activity ->
                        val fragment = tasks(activity)
                        fragment.childFragmentManager.executePendingTransactions()
                        opened = fragment.childFragmentManager.findFragmentByTag(TasksFragment.REQUEST_KEY_ACTION_CONTAINER) is TaskContainerPickerBottomSheet
                    }
                    opened
                }
                controller.onActivity { activity ->
                    val fragment = tasks(activity)
                    fragment.childFragmentManager.executePendingTransactions()
                    val sheet = fragment.childFragmentManager.findFragmentByTag(TasksFragment.REQUEST_KEY_ACTION_CONTAINER) as? TaskContainerPickerBottomSheet
                    assertNotNull("Swipe left must open TaskContainerPickerBottomSheet", sheet)
                    sheet?.dismissAllowingStateLoss()
                    fragment.childFragmentManager.setFragmentResult(
                        TasksFragment.REQUEST_KEY_ACTION_CONTAINER,
                        android.os.Bundle().apply {
                            putString(TaskContainerPickerBottomSheet.RESULT_CONTAINER_ID, home.value)
                        },
                    )
                }
                idle()
                assertEquals(home, component.userTaskRepository.getById(tInbox.id)?.containerId)
                controller.onActivity { activity ->
                    val groups = field<Map<String, Pair<TaskSectionAdapter, TasksAdapter>>>(tasks(activity), "groupAdapters")
                    val allIds = groups.values.flatMap { field<List<UserTask>>(it.second, "tasks") }.map { it.id }
                    assertEquals("Task must move to container group in ALL without duplication", allIds.size, allIds.distinct().size)
                    val homeTasks = field<List<UserTask>>(groups.getValue("container:${home.value}").second, "tasks").map { it.id }
                    assertTrue(tInbox.id in homeTasks)
                }
                // Undo container move
                controller.onActivity { activity ->
                    assertEquals(1, tasks(activity).performUndo())
                }
                idle()
                assertNull(component.userTaskRepository.getById(tInbox.id)?.containerId)

                // 4. Checkbox completion shows Undo Snackbar and restores exact state; guarded against newer edits
                select(TaskSmartView.TODAY)
                controller.onActivity { activity ->
                    val fragment = tasks(activity)
                    val row = requireNotNull(findRow(fragment, tWorkToday.title))
                    row.findViewById<View>(R.id.btnTaskCheck).performClick()
                }
                idle()
                assertTrue(component.userTaskRepository.getById(tWorkToday.id)!!.isCompleted)
                screenshot("pr8-04-checkbox-undo-snackbar-light")
                controller.onActivity { activity ->
                    assertEquals(1, tasks(activity).performUndo())
                }
                idle()
                val restoredTimed = component.userTaskRepository.getById(tWorkToday.id)!!
                assertFalse(restoredTimed.isCompleted)
                assertNull(restoredTimed.completedAt)
                assertEquals(timed(0, 18, 30), restoredTimed.due)

                // Guarded Undo: complete -> edit task -> performUndo must NOT overwrite newer edit
                controller.onActivity { activity ->
                    val fragment = tasks(activity)
                    val row = requireNotNull(findRow(fragment, tWorkToday.title))
                    row.findViewById<View>(R.id.btnTaskCheck).performClick()
                }
                idle()
                val modifiedTitle = "Согласовать архитектуру спринта (обновлено)"
                component.userTaskService.updateTask(tWorkToday.id, title = modifiedTitle)
                controller.onActivity { activity ->
                    assertEquals("Stale undo must be skipped when task has newer user edit", 0, tasks(activity).performUndo())
                }
                idle()
                val afterStaleUndo = component.userTaskRepository.getById(tWorkToday.id)!!
                assertTrue(afterStaleUndo.isCompleted)
                assertEquals(modifiedTitle, afterStaleUndo.title)
                // Restore tWorkToday to pending & original title for subsequent checks
                component.userTaskService.updateTask(tWorkToday.id, title = "Согласовать архитектуру спринта")
                component.userTaskService.setCompleted(tWorkToday.id, false)

                // 5. Multi-selection across different sections/groups, Activity recreation, Back button, and bulk actions
                select(TaskSmartView.ALL)
                controller.onActivity { activity ->
                    val fragment = tasks(activity)
                    val rowOverdue = requireNotNull(findRow(fragment, tOverdue.title))
                    val rowInbox = requireNotNull(findRow(fragment, tInbox.title))
                    val rowWork = requireNotNull(findRow(fragment, tWorkToday.title))
                    rowOverdue.performLongClick()
                    rowInbox.performClick()
                    rowWork.findViewById<View>(R.id.btnTaskCheck).performClick()
                    val selectionBar = fragment.requireView().findViewById<View>(R.id.tasksSelectionBar)
                    val countText = fragment.requireView().findViewById<TextView>(R.id.tvSelectionCount)
                    assertEquals(View.VISIBLE, selectionBar.visibility)
                    assertEquals("Выбрано: 3", countText.text.toString())
                }
                idle()
                screenshot("pr8-05-multiselect-light")

                // Activity recreation preserves selection mode
                controller.recreate()
                idle()
                await {
                    var ready = false
                    controller.onActivity {
                        ready = field<Any?>(tasks(it), "loadJob") == null ||
                            field<kotlinx.coroutines.Job?>(tasks(it), "loadJob")?.isActive != true
                    }
                    ready
                }
                controller.onActivity { activity ->
                    val fragment = tasks(activity)
                    val selectionBar = fragment.requireView().findViewById<View>(R.id.tasksSelectionBar)
                    val countText = fragment.requireView().findViewById<TextView>(R.id.tvSelectionCount)
                    assertEquals("Selection bar must survive Activity recreation", View.VISIBLE, selectionBar.visibility)
                    assertEquals("Выбрано: 3", countText.text.toString())
                }

                // Back button exits selection mode first without finishing Activity
                device.pressBack()
                idle()
                controller.onActivity { activity ->
                    assertFalse(activity.isFinishing)
                    val fragment = tasks(activity)
                    val selectionBar = fragment.requireView().findViewById<View>(R.id.tasksSelectionBar)
                    assertEquals("Back press must exit selection mode first", View.GONE, selectionBar.visibility)
                }

                // Select 2 tasks from different groups and perform bulk move to home + bulk due + bulk complete + Undo
                controller.onActivity { activity ->
                    val fragment = tasks(activity)
                    requireNotNull(findRow(fragment, tInbox.title)).performLongClick()
                    requireNotNull(findRow(fragment, tOverdue.title)).performClick()
                    fragment.requireView().findViewById<View>(R.id.btnSelectionContainer).performClick()
                    fragment.childFragmentManager.executePendingTransactions()
                    val sheet = fragment.childFragmentManager.findFragmentByTag(TasksFragment.REQUEST_KEY_ACTION_CONTAINER) as? TaskContainerPickerBottomSheet
                    assertNotNull(sheet)
                    sheet?.dismissAllowingStateLoss()
                    fragment.childFragmentManager.setFragmentResult(
                        TasksFragment.REQUEST_KEY_ACTION_CONTAINER,
                        android.os.Bundle().apply {
                            putString(TaskContainerPickerBottomSheet.RESULT_CONTAINER_ID, home.value)
                        },
                    )
                }
                idle()
                assertEquals(home, component.userTaskRepository.getById(tInbox.id)?.containerId)
                assertEquals(home, component.userTaskRepository.getById(tOverdue.id)?.containerId)
                // Undo bulk container move
                controller.onActivity { activity ->
                    assertEquals(2, tasks(activity).performUndo())
                }
                idle()
                assertNull(component.userTaskRepository.getById(tInbox.id)?.containerId)
                assertEquals(work, component.userTaskRepository.getById(tOverdue.id)?.containerId)

                // Bulk complete + Undo
                controller.onActivity { activity ->
                    val fragment = tasks(activity)
                    requireNotNull(findRow(fragment, tInbox.title)).performLongClick()
                    requireNotNull(findRow(fragment, tWorkToday.title)).performClick()
                    fragment.requireView().findViewById<View>(R.id.btnSelectionComplete).performClick()
                }
                idle()
                assertTrue(component.userTaskRepository.getById(tInbox.id)!!.isCompleted)
                assertTrue(component.userTaskRepository.getById(tWorkToday.id)!!.isCompleted)
                controller.onActivity { activity ->
                    assertEquals(2, tasks(activity).performUndo())
                }
                idle()
                assertFalse(component.userTaskRepository.getById(tInbox.id)!!.isCompleted)
                assertFalse(component.userTaskRepository.getById(tWorkToday.id)!!.isCompleted)

                // Bulk delete confirmation dialog + cascade subtask deletion
                controller.onActivity { activity ->
                    val fragment = tasks(activity)
                    requireNotNull(findRow(fragment, tSubParent.title)).performLongClick()
                    fragment.requireView().findViewById<View>(R.id.btnSelectionDelete).performClick()
                }
                idle()
                screenshot("pr8-06-delete-confirm-light")
                controller.onActivity { activity ->
                    val dialog = field<androidx.appcompat.app.AlertDialog?>(tasks(activity), "deleteConfirmationDialog")
                    assertNotNull("Delete must require confirmation dialog", dialog)
                    assertTrue(dialog!!.isShowing)
                    dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).performClick()
                }
                idle()
                assertNull("Parent task must be deleted", component.userTaskRepository.getById(tSubParent.id))
                assertNull("Child subtask must be cascade-deleted", component.userTaskRepository.getById(tSubChild.id))

                // 6. Dark and AMOLED themes + 1.3x font scale on 720x1280 small screen in selection mode
                for ((name, black) in listOf("dark" to false, "amoled" to true)) {
                    component.preferences.theme = ThemeSwitcher.THEME_DARK
                    component.preferences.isPureBlackEnabled = black
                    controller.recreate()
                    select(TaskSmartView.ALL)
                    controller.onActivity { activity ->
                        val fragment = tasks(activity)
                        requireNotNull(findRow(fragment, tInbox.title)).performLongClick()
                        requireNotNull(findRow(fragment, tWorkToday.title)).performClick()
                    }
                    idle()
                    screenshot("pr8-07-multiselect-$name")
                    controller.onActivity { tasks(it).clearSelection() }
                    idle()
                }

                val oldFontScale = context.resources.configuration.fontScale
                try {
                    device.executeShellCommand("settings put system font_scale 1.3")
                    device.executeShellCommand("wm size 720x1280")
                    device.executeShellCommand("wm density 320")
                    idle()
                    component.preferences.theme = ThemeSwitcher.THEME_LIGHT
                    component.preferences.isPureBlackEnabled = false
                    controller.recreate()
                    select(TaskSmartView.ALL)
                    controller.onActivity { activity ->
                        val fragment = tasks(activity)
                        requireNotNull(findRow(fragment, tInbox.title)).performLongClick()
                        assertEquals("More button must be visible for single selection", View.VISIBLE, fragment.requireView().findViewById<View>(R.id.btnSelectionMore).visibility)
                        requireNotNull(findRow(fragment, tWorkToday.title)).performClick()
                        assertEquals("More button must hide when multiple tasks are selected", View.GONE, fragment.requireView().findViewById<View>(R.id.btnSelectionMore).visibility)
                    }
                    idle()
                    controller.onActivity { activity ->
                        val fragment = tasks(activity)
                        val countView = fragment.requireView().findViewById<TextView>(R.id.tvSelectionCount)
                        val layout = requireNotNull(countView.layout) { "tvSelectionCount layout must be measured after idle()" }
                        assertEquals(1, layout.lineCount)
                        assertEquals("Selection count must not be ellipsized on 360dp @ 1.3x font scale", 0, layout.getEllipsisCount(0))
                    }
                    screenshot("pr8-08-multiselect-small-font130-light")
                    controller.onActivity { tasks(it).clearSelection() }
                } finally {
                    device.executeShellCommand("settings put system font_scale $oldFontScale")
                    device.executeShellCommand("wm size reset")
                    device.executeShellCommand("wm density reset")
                    idle()
                }
            }
        } finally {
            instrumentation.runOnMainSync {
                component.preferences.theme = oldTheme
                component.preferences.isPureBlackEnabled = oldBlack
            }
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
