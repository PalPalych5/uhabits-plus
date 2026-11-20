package org.isoron.uhabits.activities.tasks

import android.graphics.Rect
import android.os.SystemClock
import android.provider.Settings
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.main.MainActivity
import org.isoron.uhabits.activities.main.MainDestination
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.containers.session.DatasetSessionFactory
import org.isoron.uhabits.core.containers.session.ExperimentalDatasetMetadata
import org.isoron.uhabits.core.preferences.Preferences
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
                    controller.onActivity { tasks(it).taskListState.requestScrollToItem(0) }
                    idle()
                }
                select(TaskSmartView.ALL)
                screenshot("01-all-light")
                if (phase != "before") controller.onActivity { activity ->
                    val groups = renderedGroups(tasks(activity))
                    val ids = groups.values.flatten().map { it.id }
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
                    controller.onActivity { scrollBottom(tasks(it)) }
                    idle()
                    screenshot("01c-completed-scrolled-bottom-light")
                    val row1 = visibleTask("Обновить список материалов")
                    val row2 = visibleTask("Собрать обратную связь по прототипу")
                    assertNotNull("First completed row must be visible", row1)
                    assertNotNull("Second completed row must be visible", row2)
                    val row2Rect = requireNotNull(row2).visibleBounds
                    controller.onActivity { activity ->
                        val fragment = tasks(activity)
                        val quickAddRect = taskWorkspaceBounds(fragment.requireView(), "tasksQuickAdd")
                        assertFalse(quickAddRect.isEmpty)
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
                    val items = listItems(fragment)
                    val activeStart = items.indexOfFirst { it.key == "header:active" }
                    assertTrue("Fixture must expose an active heading after overdue", activeStart > 0)
                    val overdue = items.take(activeStart).filterIsInstance<TaskListItem.Row>().map { it.task }
                    val active = items.drop(activeStart).filterIsInstance<TaskListItem.Row>().map { it.task }
                    assertTrue("Today and overdue must not duplicate task IDs", active.map { it.id }.intersect(overdue.map { it.id }.toSet()).isEmpty())
                    assertTrue("DAY today must remain active for the whole day", active.any { it.id == dueToday.id })
                    assertTrue("NONE must not enter Today", (active + overdue).none { it.id == noDue.id })
                }
                select(TaskSmartView.ALL, parent)
                controller.onActivity { activity ->
                    val fragment = tasks(activity)
                    val scope = queries.subtreeIds(parent) + parent
                    val expected = component.userTaskRepository.getRoots().filter { !it.isCompleted && it.containerId in scope }.map { it.id }.toSet()
                    val rendered = renderedRows(fragment).map { it.id }.toSet()
                    assertEquals("Parent filter must include exactly its active descendant roots", expected, rendered)
                    assertTrue("Child-container DAY task must appear in parent scope", dueToday.id in rendered)
                    assertFalse("Unassigned task must not enter a container scope", noDue.id in rendered)
                }
                screenshot("03-container-light")
                select(TaskSmartView.ALL, child)
                val longTitle = tagged("tvTasksTitle")
                assertEquals(childName, longTitle.text)
                assertTrue("Long section title must remain visible", longTitle.visibleBounds.height() > 0)
                screenshot("03b-child-long-title-light")
                select(TaskSmartView.ALL, fourth)
                screenshot("03c-fourth-level-light")
                select(TaskSmartView.COMPLETED)
                screenshot("04-completed-light")
                if (phase != "before") {
                    controller.onActivity { activity ->
                        val groups = renderedGroups(tasks(activity))
                        assertEquals(listOf(unknown.id), groups.getValue("completed:unknown").map { it.id })
                    }
                    val markerRu = "Выполнено"
                    val markerEn = "Completed"
                    for (completedAt in listOf(yesterday.completedAt, 0L, -1L, null)) {
                        component.userTaskRepository.save(unknown.copy(completedAt = completedAt))
                        controller.onActivity { tasks(it).refresh() }
                        await {
                            var reflected = false
                            controller.onActivity { activity ->
                                reflected = renderedRows(tasks(activity)).any { it.id == unknown.id && it.completedAt == completedAt }
                            }
                            reflected
                        }
                        idle()
                        val hasCompletion = completedAt != null && completedAt > 0L
                        val desc = row(controller, unknown).contentDescription.orEmpty()
                        val containsMarker = desc.contains(markerRu) || desc.contains(markerEn)
                        assertEquals("Spoken completion date visibility for $completedAt", hasCompletion, containsMarker)
                    }
                }
                select(TaskSmartView.UPCOMING)
                screenshot("04b-upcoming-light")
                if (phase != "before") controller.onActivity { activity ->
                    val groups = renderedGroups(tasks(activity))
                    assertTrue("Upcoming fixture must span at least three local dates", groups.size >= 3)
                    assertTrue(groups.keys.all { it.startsWith("due:") })
                }
                select(TaskSmartView.ALL, empty)
                screenshot("05-empty-light")
                select(TaskSmartView.INBOX)
                screenshot("06-inbox-light")

                // Click the actual list checkbox. A checkbox action must not open the editor.
                clickCheckbox(controller, noDue, R.string.task_row_a11y_complete_title)
                controller.onActivity { activity ->
                    val fragment = tasks(activity)
                    fragment.childFragmentManager.executePendingTransactions()
                    assertNull(fragment.childFragmentManager.findFragmentByTag("task_detail"))
                }
                idle()
                assertTrue(component.userTaskRepository.getById(noDue.id)!!.isCompleted)
                select(TaskSmartView.COMPLETED)
                clickCheckbox(controller, noDue, R.string.task_row_a11y_reopen_title)
                idle()
                assertFalse(component.userTaskRepository.getById(noDue.id)!!.isCompleted)

                // Open PR5 editor through its row and edit Due through the same picker result seam.
                select(TaskSmartView.TODAY)
                row(controller, dueToday).click()
                idle()
                controller.onActivity { activity ->
                    val fragment = tasks(activity)
                    fragment.childFragmentManager.executePendingTransactions()
                    val sheet = fragment.childFragmentManager.findFragmentByTag("task_detail") as TaskDetailBottomSheet
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
                        if (phase != "before") assertVisibleListGeometry(controller)
                        screenshot("11-all-font130-$name")
                        select(TaskSmartView.UPCOMING)
                        if (phase != "before") assertVisibleListGeometry(controller)
                        screenshot("12-upcoming-font130-$name")
                        select(TaskSmartView.COMPLETED)
                        if (phase != "before") assertVisibleListGeometry(controller)
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
        component.userTaskService.createTask("Заказать книги для чтения", containerId = home, due = timed(2, 14, 0))
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
                    controller.onActivity { tasks(it).taskListState.requestScrollToItem(0) }
                    idle()
                }

                // Capture the real Compose reveal and cancel before committing either action.
                select(TaskSmartView.TODAY)
                val previewBounds = row(controller, tInbox).visibleBounds
                captureSwipePreview(previewBounds, towardEnd = true, "pr8-01-swipe-right-due-light")
                captureSwipePreview(previewBounds, towardEnd = false, "pr8-02-swipe-left-container-light")
                assertNoActionPicker(controller)

                val shortDistance = minOf(previewBounds.width() / 10, TaskThemeUtils.dpInt(context, 24f))
                val x = previewBounds.centerX()
                val y = previewBounds.centerY()
                assertTrue(device.swipe(x, y, x + shortDistance, y, 65))
                idle()
                assertNoActionPicker(controller)

                // 2. Real swipe right in UPCOMING opens TaskDuePickerBottomSheet & moves task to new date group without duplication
                select(TaskSmartView.UPCOMING)
                var rowRect = row(controller, tUpcoming1).visibleBounds
                val originalBounds = Rect(rowRect)
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
                rowRect = row(controller, tUpcoming1).visibleBounds
                assertEquals("Row must settle back after the picker is dismissed", originalBounds, rowRect)
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
                    val groups = renderedGroups(tasks(activity))
                    val allIds = groups.values.flatten().map { it.id }
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
                rowRect = row(controller, tInbox).visibleBounds
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
                    val groups = renderedGroups(tasks(activity))
                    val allIds = groups.values.flatten().map { it.id }
                    assertEquals("Task must move to container group in ALL without duplication", allIds.size, allIds.distinct().size)
                    val homeTasks = groups.getValue("container:${home.value}").map { it.id }
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
                clickCheckbox(controller, tWorkToday, R.string.task_row_a11y_complete_title)
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
                clickCheckbox(controller, tWorkToday, R.string.task_row_a11y_complete_title)
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
                row(controller, tOverdue).longClick()
                idle()
                val selectedBounds = row(controller, tInbox).visibleBounds
                assertTrue(device.swipe(
                    selectedBounds.left + selectedBounds.width() / 5,
                    selectedBounds.centerY(),
                    selectedBounds.right - selectedBounds.width() / 5,
                    selectedBounds.centerY(),
                    18,
                ))
                idle()
                assertNoActionPicker(controller)
                assertSelectionCount(1)
                row(controller, tInbox).click()
                idle()
                clickCheckbox(controller, tWorkToday, R.string.task_row_a11y_select_title)
                assertSelectionCount(3)
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
                assertSelectionCount(3)

                // Back button exits selection mode first without finishing Activity
                device.pressBack()
                idle()
                controller.onActivity { assertFalse(it.isFinishing) }
                assertFalse("Back press must exit selection mode first", device.hasObject(By.res("tvSelectionCount")))

                // Select 2 tasks from different groups and perform bulk move to home + bulk due + bulk complete + Undo
                row(controller, tInbox).longClick()
                idle()
                row(controller, tOverdue).click()
                idle()
                tagged("btnSelectionContainer").click()
                idle()
                controller.onActivity { activity ->
                    val fragment = tasks(activity)
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
                row(controller, tInbox).longClick()
                idle()
                row(controller, tWorkToday).click()
                idle()
                tagged("btnSelectionComplete").click()
                idle()
                assertTrue(component.userTaskRepository.getById(tInbox.id)!!.isCompleted)
                assertTrue(component.userTaskRepository.getById(tWorkToday.id)!!.isCompleted)
                controller.onActivity { activity ->
                    assertEquals(2, tasks(activity).performUndo())
                }
                idle()
                assertFalse(component.userTaskRepository.getById(tInbox.id)!!.isCompleted)
                assertFalse(component.userTaskRepository.getById(tWorkToday.id)!!.isCompleted)

                // 6. Dark and AMOLED themes + 1.3x font scale on 720x1280 small screen in selection mode
                for ((name, black) in listOf("dark" to false, "amoled" to true)) {
                    component.preferences.theme = ThemeSwitcher.THEME_DARK
                    component.preferences.isPureBlackEnabled = black
                    controller.recreate()
                    select(TaskSmartView.ALL)
                    row(controller, tInbox).longClick()
                    idle()
                    row(controller, tWorkToday).click()
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
                    row(controller, tInbox).longClick()
                    idle()
                    tagged("btnSelectionMore").click()
                    idle()
                    assertTrue("Single selection must open quick actions",
                        device.hasObject(By.text(context.getString(R.string.task_quick_menu_change_due))))
                    device.pressBack()
                    idle()
                    assertSelectionCount(1)
                    row(controller, tWorkToday).click()
                    idle()
                    assertFalse("More must hide for multiple selected tasks", device.hasObject(By.res("btnSelectionMore")))
                    assertSelectionCount(2)
                    screenshot("pr8-08-multiselect-small-font130-light")
                    controller.onActivity { tasks(it).clearSelection() }
                } finally {
                    device.executeShellCommand("settings put system font_scale $oldFontScale")
                    device.executeShellCommand("wm size reset")
                    device.executeShellCommand("wm density reset")
                    idle()
                }

                captureCompactClassicSelection(controller, tInbox, tWorkToday) { select(TaskSmartView.ALL) }

                // Confirming selection deletion must cascade to subtasks.
                select(TaskSmartView.ALL)
                row(controller, tSubParent).longClick()
                idle()
                tagged("btnSelectionDelete").click()
                idle()
                screenshot("pr8-06-delete-confirm-light")
                val deleteButton = device.findObject(By.text(context.getString(R.string.delete)))
                requireNotNull(deleteButton) { "Delete must require confirmation" }.click()
                idle()
                assertNull("Parent task must be deleted", component.userTaskRepository.getById(tSubParent.id))
                assertNull("Child subtask must be cascade-deleted", component.userTaskRepository.getById(tSubChild.id))
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

    private fun assertVisibleListGeometry(controller: TaskDraftLifecycleTest.QaActivityController) {
        val viewport = tagged("tasksList").visibleBounds
        var visibleKeys = emptyList<String>()
        controller.onActivity { activity ->
            val info = tasks(activity).taskListState.layoutInfo
            visibleKeys = info.visibleItemsInfo
                .filter { it.offset >= info.viewportStartOffset && it.offset + it.size <= info.viewportEndOffset }
                .map { it.key as String }
        }
        assertTrue("Fixture must expose task titles", visibleKeys.any { it.startsWith("task:") })
        assertTrue("Fixture must expose section headings", visibleKeys.any { !it.startsWith("task:") })
        val bounds = visibleKeys.map { key ->
            val node = tagged(key)
            val bounds = node.visibleBounds
            assertFalse("A fully visible list entry must have nonzero geometry: $key", bounds.isEmpty)
            assertTrue("A fully visible list entry must fit within the viewport: $key", viewport.contains(bounds))
            bounds
        }
        bounds.zipWithNext { previous, next ->
            assertFalse("Section headings and task rows must not overlap", Rect.intersects(previous, next))
        }
    }

    private fun captureCompactClassicSelection(
        controller: TaskDraftLifecycleTest.QaActivityController,
        first: UserTask,
        second: UserTask,
        selectAll: () -> Unit,
    ) {
        val oldFontScale = context.resources.configuration.fontScale
        val oldMode = component.preferences.sectionLayoutMode
        try {
            device.executeShellCommand("settings put system font_scale 2.0")
            device.executeShellCommand("wm size 640x1280")
            device.executeShellCommand("wm density 320")
            component.preferences.sectionLayoutMode = Preferences.SECTION_LAYOUT_CLASSIC
            controller.recreate()
            selectAll()
            assertVisibleListGeometry(controller)
            screenshot("pr8-09-list-compact-classic-font200-light")
            row(controller, first).longClick()
            idle()
            clickCheckbox(controller, second, R.string.task_row_a11y_select_title)
            assertSelectionCount(2)
            screenshot("pr8-10-multiselect-compact-classic-font200-light")
            controller.onActivity { tasks(it).clearSelection() }
        } finally {
            component.preferences.sectionLayoutMode = oldMode
            device.executeShellCommand("settings put system font_scale $oldFontScale")
            device.executeShellCommand("wm size reset")
            device.executeShellCommand("wm density reset")
            idle()
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
                    listItems(fragment).size >= 100
            }
            loaded
        }
        idle()
        SystemClock.sleep(1_000)
        verifyScrollAnchor(controller)
        controller.onActivity { scrollBottom(tasks(it)) }
        idle()
        SystemClock.sleep(1_000)
        assertChromeVisible(controller)
        controller.onActivity { assertListContinuityAndClearance(it) }
        screenshot("09-long-list-bottom")
        controller.onActivity { tasks(it).taskListState.requestScrollToItem(0) }
        idle()
        SystemClock.sleep(1_000)
        assertChromeVisible(controller)
        controller.onActivity { assertListContinuityAndClearance(it) }
        screenshot("10-long-list-top")
    }

    private fun assertListContinuityAndClearance(activity: MainActivity) {
        val fragment = tasks(activity)
        val listRect = taskWorkspaceBounds(fragment.requireView(), "tasksList")
        val quickAddRect = taskWorkspaceBounds(fragment.requireView(), "tasksQuickAdd")
        assertFalse(quickAddRect.isEmpty)
        assertFalse(listRect.isEmpty)
        val visibleItems = fragment.taskListState.layoutInfo.visibleItemsInfo
        assertTrue("Lazy list must have visible entries", visibleItems.isNotEmpty())
        visibleItems.zipWithNext { previous, next ->
            assertEquals("Adjacent rows must be contiguous with no gaps or overlaps", previous.offset + previous.size, next.offset)
        }
        assertTrue("List viewport must clear the quick add bar", listRect.bottom <= quickAddRect.top)
    }

    private fun verifyScrollAnchor(controller: TaskDraftLifecycleTest.QaActivityController) {
        fun anchor(): Pair<Any, Int>? {
            var result: Pair<Any, Int>? = null
            controller.onActivity { activity ->
                val state = tasks(activity).taskListState
                result = state.layoutInfo.visibleItemsInfo
                    .firstOrNull { it.index == state.firstVisibleItemIndex }
                    ?.let { it.key to state.firstVisibleItemScrollOffset }
            }
            return result
        }
        controller.onActivity { tasks(it).taskListState.requestScrollToItem(15, 12) }
        idle()
        val before = requireNotNull(anchor())
        assertTrue("Scroll anchor should be a task identity", before.first.toString().startsWith("task:"))
        val inbox = component.userTaskRepository.getRoots().filter { !it.isCompleted && it.containerId == null }
        val inserted = component.userTaskService.createTask("QA scroll anchor insertion")
        component.userTaskRepository.save(inserted.copy(sortOrder = inbox.minOf { it.sortOrder } - 1))
        controller.onActivity { tasks(it).refresh() }
        await {
            var added = false
            controller.onActivity { added = renderedGroups(tasks(it))["inbox"]?.firstOrNull()?.id == inserted.id }
            added
        }
        idle()
        assertEquals("Inserting an earlier row must preserve the first visible task and offset", before, anchor())
        controller.recreate()
        idle()
        await { anchor() == before }
    }

    private fun listItems(fragment: TasksFragment) = fragment.taskListContent?.items.orEmpty()

    private fun renderedRows(fragment: TasksFragment) =
        listItems(fragment).filterIsInstance<TaskListItem.Row>().map { it.task }

    private fun renderedGroups(fragment: TasksFragment): Map<String, List<UserTask>> {
        val groups = linkedMapOf<String, MutableList<UserTask>>()
        var rows: MutableList<UserTask>? = null
        for (item in listItems(fragment)) {
            when (item) {
                is TaskListItem.Header -> {
                    rows = item.key.takeIf { it.startsWith("group:") }
                        ?.let { groups.getOrPut(it.removePrefix("group:")) { mutableListOf() } }
                }
                is TaskListItem.Row -> rows?.add(item.task)
            }
        }
        return groups
    }

    private fun scrollBottom(fragment: TasksFragment) {
        fragment.taskListState.requestScrollToItem(listItems(fragment).lastIndex)
    }

    private fun tagged(tag: String): UiObject2 =
        requireNotNull(device.findObject(By.res(tag))) { "Compose semantics node is missing: $tag" }

    private fun visibleTask(title: String): UiObject2? =
        device.findObject(By.descStartsWith(title))

    private fun row(controller: TaskDraftLifecycleTest.QaActivityController, task: UserTask): UiObject2 {
        val key = "task:${task.id.value}"
        device.findObject(By.res(key))?.let { return it }
        reveal(controller, key)
        return tagged(key)
    }

    private fun clickCheckbox(
        controller: TaskDraftLifecycleTest.QaActivityController,
        task: UserTask,
        @androidx.annotation.StringRes label: Int,
    ) {
        row(controller, task)
        requireNotNull(device.findObject(By.desc(context.getString(label, task.title)))) {
            "Task checkbox is missing for ${task.title}"
        }.click()
        idle()
    }

    private fun reveal(controller: TaskDraftLifecycleTest.QaActivityController, key: String) {
        controller.onActivity {
            val fragment = tasks(it)
            val position = listItems(fragment).indexOfFirst { item -> item.key == key }
            check(position >= 0) { "Entry is absent from list: $key" }
            fragment.taskListState.requestScrollToItem(position)
        }
        idle()
    }

    private fun clickSection(controller: TaskDraftLifecycleTest.QaActivityController, label: String) {
        lateinit var key: String
        controller.onActivity {
            key = listItems(tasks(it)).filterIsInstance<TaskListItem.Header>()
                .first { header -> header.title == label }.key
        }
        reveal(controller, key)
        val header = tagged(key)
        if (label == "Завершённые") assertFalse("Completed heading must not show a count", header.contentDescription.orEmpty().any(Char::isDigit))
        header.click()
        idle()
    }

    private fun assertSelectionCount(count: Int) {
        val label = tagged("tvSelectionCount")
        assertEquals(context.getString(R.string.task_selection_count_desc, count), label.contentDescription)
        assertTrue("Selection count must have visible geometry", label.visibleBounds.width() > 0)
    }

    private fun assertNoActionPicker(controller: TaskDraftLifecycleTest.QaActivityController) {
        controller.onActivity { activity ->
            val manager = tasks(activity).childFragmentManager
            manager.executePendingTransactions()
            assertNull(manager.findFragmentByTag(TasksFragment.REQUEST_KEY_ACTION_DUE))
            assertNull(manager.findFragmentByTag(TasksFragment.REQUEST_KEY_ACTION_CONTAINER))
        }
    }

    private fun captureSwipePreview(bounds: Rect, towardEnd: Boolean, name: String) {
        val x = bounds.centerX().toFloat()
        val y = bounds.centerY().toFloat()
        val direction = if (towardEnd) 1 else -1
        val distance = minOf(bounds.width() / 3f, TaskThemeUtils.dp(context, 108f))
        val down = SystemClock.uptimeMillis()
        fun inject(action: Int, position: Float) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, position, y, 0)
            try {
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                check(instrumentation.uiAutomation.injectInputEvent(event, true))
            } finally {
                event.recycle()
            }
        }
        inject(MotionEvent.ACTION_DOWN, x)
        try {
            repeat(6) {
                SystemClock.sleep(16)
                inject(MotionEvent.ACTION_MOVE, x + direction * distance * (it + 1) / 6)
            }
            screenshot(name, allowResurface = false)
        } finally {
            inject(MotionEvent.ACTION_CANCEL, x + direction * distance)
            idle()
        }
    }
    private fun assertChromeVisible(controller: TaskDraftLifecycleTest.QaActivityController) {
        val screen = Rect()
        val navigationBounds = Rect()
        var taskLabel = ""
        controller.onActivity { activity ->
            taskLabel = activity.getString(R.string.tasks_title)
            assertTrue(activity.window.decorView.getGlobalVisibleRect(screen))
            val navigation = activity.findViewById<View>(R.id.bottomNavigation)
            assertTrue("Bottom navigation must be shown", navigation.isShown && navigation.alpha > 0f)
            assertTrue(navigation.getGlobalVisibleRect(navigationBounds))
            assertEquals(navigation.width, navigationBounds.width())
            assertEquals(navigation.height, navigationBounds.height())
            assertTrue(screen.contains(navigationBounds))
        }
        val titleBounds = tagged("tvTasksTitle").visibleBounds
        assertTrue("Tasks title must fit on screen", !titleBounds.isEmpty && screen.contains(titleBounds))
        val tab = tagged("btnNavTasks")
        assertTrue("Tasks navigation must be selected", tab.isSelected)
        assertTrue("Tasks navigation label must be visible", tab.text == taskLabel || tab.hasObject(By.text(taskLabel)))
        assertTrue("Tasks navigation must fit within its visible parent", navigationBounds.contains(tab.visibleBounds))
    }
    private fun tasks(activity: MainActivity) = activity.supportFragmentManager.findFragmentByTag("main.tasks") as TasksFragment
    private fun idle() { instrumentation.waitForIdleSync(); device.waitForIdle(); SystemClock.sleep(150) }
    private fun await(condition: () -> Boolean) { val deadline = SystemClock.uptimeMillis() + 10_000; while (SystemClock.uptimeMillis() < deadline) { if (condition()) return; SystemClock.sleep(50) }; error("List did not settle") }
    private fun screenshot(name: String, allowResurface: Boolean = true) {
        if (allowResurface && InstrumentationRegistry.getArguments().getString("resurface") == "true") {
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
