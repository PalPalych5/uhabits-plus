package org.isoron.uhabits.activities.tasks

import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.common.theme.MainTabsThemeBridge
import org.isoron.uhabits.activities.main.MainActivity
import org.isoron.uhabits.activities.main.MainDestination
import org.isoron.uhabits.core.containers.OrganizationResult
import org.isoron.uhabits.core.ui.ThemeSwitcher
import org.isoron.uhabits.core.userTasks.due.TaskDue
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId

/** Visual-only acceptance, using the existing guarded offline fixture and Activity controller. */
@RunWith(AndroidJUnit4::class)
class TaskDraftVisualTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val title = "Подготовить план на следующую неделю"
    private val sectionName = "Личные проекты и планы на ближайшие месяцы"
    private val notes = "Собрать материалы и согласовать следующие шаги."
    private val subtask = "Проверить список важных дел"
    private val due = TaskDue.Timed.local(LocalDateTime.of(2026, 10, 23, 18, 0)
        .atZone(ZoneId.of("Asia/Novosibirsk")).toInstant().toEpochMilli(), "Asia/Novosibirsk")

    @Test fun threeComparableEditorsWithImeAndThemeChange() {
        val harness = TaskDraftLifecycleTest()
        harness.guard()
        val component = HabitsApplication.component
        val oldTheme = component.preferences.theme
        val result = component.organizationFacade.createContainer(name = sectionName)
        check(result is OrganizationResult.Success)
        val container = result.value.id
        component.preferences.theme = ThemeSwitcher.THEME_LIGHT
        try {
            harness.QaActivityController().use { controller ->
                controller.onActivity { activity ->
                    assertEquals("Selected application locale must be Russian", "ru", TaskDueDisplayFormatter.selectedLocale(activity).language)
                    InstrumentationRegistry.getArguments().getString("expectedWidthDp")?.toInt()?.let { expected ->
                        assertEquals("Editor viewport width in dp", expected, activity.resources.configuration.screenWidthDp)
                    }
                    activity.navigate(MainDestination.TASKS)
                    activity.supportFragmentManager.executePendingTransactions()
                }
                device.taskControl("layoutQuickAddCollapsed").click()
                device.taskControl("etQuickAddTitle").apply {
                    click()
                    text = title
                }
                controller.onActivity { activity ->
                    val tasks = tasks(activity)
                    tasks.childFragmentManager.setFragmentResult("quickDue", TaskDuePickerBottomSheet.encode(due))
                    tasks.childFragmentManager.setFragmentResult("quickContainer", Bundle().apply {
                        putString(TaskContainerPickerBottomSheet.RESULT_CONTAINER_ID, container.value)
                    })
                }
                await { var shown = false; controller.onActivity { shown = imeVisible(it) }; shown }
                val compactDue = requireNotNull(device.taskControl("quickDueLabel").text)
                assertTrue("Due must honor selected Russian locale: $compactDue", compactDue.contains("окт", ignoreCase = true))
                assertFalse("Due must use a localized date", compactDue.contains("2026-10-23"))
                assertTrue(compactDue, compactDue.contains("18:00"))
                controller.onActivity { activity ->
                    val view = tasks(activity).requireView()
                    val composer = taskWorkspaceBounds(view, "tasksQuickAdd")
                    val root = activity.window.decorView
                    val ime = requireNotNull(ViewCompat.getRootWindowInsets(root)).getInsets(WindowInsetsCompat.Type.ime()).bottom
                    val keyboardTop = bounds(root).bottom - ime
                    val tolerance = (8 * root.resources.displayMetrics.density).toInt()
                    assertTrue("Composer must meet the IME: $composer keyboardTop=$keyboardTop", kotlin.math.abs(composer.bottom - keyboardTop) <= tolerance)
                    val navigation = activity.findViewById<View>(R.id.bottomNavigation)
                    assertFalse("Bottom navigation must not separate composer and IME", navigation.isShown && bounds(navigation).height() > 0)
                }
                screenshot("01-quick-add-ime")
                // First Back closes IME and preserves the compact title.
                device.pressBack(); idle()
                controller.onActivity { activity ->
                    assertFalse(imeVisible(activity))
                    assertTrue("Navigation must return after compact IME Back", activity.findViewById<View>(R.id.bottomNavigation).isShown)
                }
                assertEquals(title, device.taskControl("etQuickAddTitle").text)
                device.taskControl("btnQuickAddExpand").click()
                device.enterTaskDraftNotesAndSubtask(notes, subtask)
                device.revealTaskDetailControl("taskDetailNotes", Direction.UP).click()
                await {
                    var visible = false
                    controller.onActivity { activity -> visible = dialogImeVisible(sheet(activity)) }
                    visible
                }
                screenshot("expanded-ime-diagnostic")
                val visibleScroll = device.taskControl("taskDetailScroll").visibleBounds
                val createWithIme = device.taskControl("taskDetailCreate").visibleBounds
                controller.onActivity { activity ->
                    val sheet = sheet(activity)
                    val root = requireNotNull(sheet.dialog?.window?.decorView)
                    val ime = requireNotNull(ViewCompat.getRootWindowInsets(root)).getInsets(WindowInsetsCompat.Type.ime()).bottom
                    val keyboardTop = device.displayHeight - ime
                    assertTrue("Expanded content must retain visible space above IME", visibleScroll.height() > 0 && visibleScroll.top < keyboardTop)
                    assertTrue("Create CTA must remain above expanded IME", createWithIme.height() > 0 && createWithIme.bottom <= keyboardTop)
                }
                device.pressBack()
                await {
                    var hidden = false
                    controller.onActivity { activity -> hidden = !dialogImeVisible(sheet(activity)) }
                    hidden
                }
                assertEquals(title, device.revealTaskDetailControl("taskDetailTitle", Direction.UP).text)
                controller.onActivity { activity ->
                    val sheet = sheet(activity)
                    val frame = bounds(sheet.requireView())
                    val screenHeight = device.displayHeight
                    assertTrue("Create should fill most of the phone", frame.height() >= screenHeight * 0.85)
                }
                assertEquals(compactDue, device.revealTaskDetailControl("taskDetailDue").text)
                val containerLabel = device.revealTaskDetailControl("taskDetailContainerLabel")
                assertEquals(sectionName, containerLabel.text)
                val labelBounds = containerLabel.visibleBounds
                val chipBounds = device.taskControl("taskDetailContainer").visibleBounds
                assertTrue("Long container label must fit within its chip", !labelBounds.isEmpty && chipBounds.contains(labelBounds))
                assertContainerTextFits(chipBounds)
                val createBounds = device.taskControl("taskDetailCreate").visibleBounds
                screenshot("02-create-expanded")
                val rendered = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                assertTrue("CTA bounds must be on screen", Rect(0, 0, rendered.width, rendered.height).contains(createBounds))
                var bluePixels = 0
                val ctaPixels = createBounds.width() * createBounds.height()
                for (y in createBounds.top until createBounds.bottom) for (x in createBounds.left until createBounds.right) {
                    val color = rendered.getPixel(x, y)
                    if (Color.alpha(color) > 200 && Color.blue(color) - Color.red(color) > 30 && Color.blue(color) > Color.green(color)) bluePixels++
                }
                rendered.recycle()
                assertTrue("Create CTA should have a blue fill: $bluePixels/$ctaPixels rendered pixels", bluePixels > ctaPixels / 4)
                device.taskControl("taskDetailCancel").click()
                await {
                    var dismissed = false
                    controller.onActivity { activity ->
                        tasks(activity).childFragmentManager.executePendingTransactions()
                        dismissed = tasks(activity).childFragmentManager.findFragmentByTag("task_detail_create") == null
                    }
                    dismissed
                }
                // Create the single readable fixture through the existing service, then inspect edit mode.
                val saved = component.userTaskService.createTask(title, description = notes, containerId = container, due = due)
                component.userTaskService.createTask(subtask, parentTaskId = saved.id, containerId = container)
                controller.onActivity { activity ->
                    assertFalse(MainTabsThemeBridge.resolve(activity).isDark)
                    TaskDetailBottomSheet.newInstance(saved.id).show(tasks(activity).childFragmentManager, "visual-edit")
                    tasks(activity).childFragmentManager.executePendingTransactions()
                }
                idle()
                var beforeTheme: MainActivity? = null
                controller.onActivity { activity ->
                    beforeTheme = activity
                    component.preferences.theme = ThemeSwitcher.THEME_DARK
                    activity.recreateForThemeChange()
                }
                await {
                    var recreated = false
                    controller.onActivity { activity -> recreated = activity !== beforeTheme && MainTabsThemeBridge.resolve(activity).isDark }
                    recreated
                }
                controller.onActivity { activity ->
                    assertTrue("Theme change must retain Tasks context", tasks(activity).isVisible)
                    val edit = tasks(activity).childFragmentManager.findFragmentByTag("visual-edit") as TaskDetailBottomSheet
                    assertTrue(MainTabsThemeBridge.resolve(edit.requireContext()).isDark)
                }
                assertTrue("Edit actions must remain visible", !device.taskControl("taskDetailEditActions").visibleBounds.isEmpty)
                assertEquals(title, device.revealTaskDetailControl("taskDetailTitle", Direction.UP).text)
                assertEquals(compactDue, device.revealTaskDetailControl("taskDetailDue").text)
                screenshot("03-edit-dark-tasks")
                device.pressBack(); idle()
                await {
                    var dismissed = false
                    controller.onActivity { activity ->
                        tasks(activity).childFragmentManager.executePendingTransactions()
                        dismissed = tasks(activity).childFragmentManager.findFragmentByTag("visual-edit") == null
                    }
                    dismissed
                }
                controller.onActivity { activity ->
                    assertTrue("Back must retain Tasks context", tasks(activity).isVisible)
                    assertTrue("Navigation must return after closing Edit", activity.findViewById<View>(R.id.bottomNavigation).isShown)
                }
            }
        } finally { instrumentation.runOnMainSync { component.preferences.theme = oldTheme } }
    }

    private fun imeVisible(activity: MainActivity) = ViewCompat.getRootWindowInsets(activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
    private fun dialogImeVisible(sheet: TaskDetailBottomSheet) = sheet.dialog?.window?.decorView?.let {
        ViewCompat.getRootWindowInsets(it)?.isVisible(WindowInsetsCompat.Type.ime())
    } == true
    private fun tasks(activity: MainActivity) = activity.supportFragmentManager.findFragmentByTag("main.tasks") as TasksFragment
    private fun sheet(activity: MainActivity) = tasks(activity).childFragmentManager.findFragmentByTag("task_detail_create") as TaskDetailBottomSheet
    private fun bounds(view: View): Rect { val xy = IntArray(2); view.getLocationOnScreen(xy); return Rect(xy[0], xy[1], xy[0] + view.width, xy[1] + view.height) }

    @Suppress("DEPRECATION")
    private fun assertContainerTextFits(chipBounds: Rect) {
        fun findLabel(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.viewIdResourceName == "taskDetailContainerLabel") return node
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                findLabel(child)?.let { return it }
            }
            return null
        }
        val label = requireNotNull(findLabel(requireNotNull(instrumentation.uiAutomation.rootInActiveWindow)))
        val key = AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY
        val args = Bundle().apply {
            putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, 0)
            putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, sectionName.length)
        }
        assertTrue("Container label must expose measured text", label.refreshWithExtraData(key, args))
        val characters = requireNotNull(label.extras.getParcelableArray(key)).filterIsInstance<RectF>()
        assertEquals(sectionName.length, characters.size)
        assertTrue("Long container text must remain inside its chip", characters.all { bounds ->
            bounds.left >= chipBounds.left - 1 && bounds.right <= chipBounds.right + 1 &&
                bounds.top >= chipBounds.top - 1 && bounds.bottom <= chipBounds.bottom + 1
        })
    }

    private fun idle() { instrumentation.waitForIdleSync(); device.waitForIdle() }
    private fun await(condition: () -> Boolean) { val until = SystemClock.uptimeMillis() + 5_000; while (SystemClock.uptimeMillis() < until) { idle(); if (condition()) return; SystemClock.sleep(50) }; error("UI condition did not settle") }
    private fun screenshot(name: String) { val file = File(context.getExternalFilesDir(null), "pr5-visual-qa/$name.png"); check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory); check(device.takeScreenshot(file)) }
}
