package org.isoron.uhabits.activities.tasks

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
                    activity.navigate(MainDestination.TASKS)
                    activity.supportFragmentManager.executePendingTransactions()
                    val tasks = tasks(activity)
                    tasks.requireView().findViewById<View>(R.id.layoutQuickAddCollapsed).performClick()
                    val input = tasks.requireView().findViewById<EditText>(R.id.etQuickAddTitle)
                    input.setText(title)
                    tasks.childFragmentManager.setFragmentResult("quickDue", TaskDuePickerBottomSheet.encode(due))
                    tasks.childFragmentManager.setFragmentResult("quickContainer", Bundle().apply {
                        putString(TaskContainerPickerBottomSheet.RESULT_CONTAINER_ID, container.value)
                    })
                    input.requestFocus()
                    (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                        .showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
                }
                await { var shown = false; controller.onActivity { shown = imeVisible(it) }; shown }
                var compactDue = ""
                controller.onActivity { activity ->
                    val view = tasks(activity).requireView()
                    compactDue = view.findViewById<TextView>(R.id.quickDue).text.toString()
                    assertTrue("Due must honor selected Russian locale: $compactDue", compactDue.contains("окт", ignoreCase = true))
                    assertFalse("Due must use a localized date", compactDue.contains("2026-10-23"))
                    assertTrue(compactDue, compactDue.contains("18:00"))
                    val composer = bounds(view.findViewById(R.id.layoutQuickAddContainer))
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
                    assertEquals(title, tasks(activity).requireView().findViewById<EditText>(R.id.etQuickAddTitle).text.toString())
                    tasks(activity).requireView().findViewById<View>(R.id.btnQuickAddExpand).performClick()
                    tasks(activity).childFragmentManager.executePendingTransactions()
                }
                idle()
                controller.onActivity { activity ->
                    val sheet = sheet(activity)
                    field<EditText>(sheet, "etDescription").setText("Собрать материалы и согласовать следующие шаги.")
                    field<EditText>(sheet, "etNewSubtask").setText("Проверить список важных дел")
                    invoke(sheet, "addNewSubtask")
                    val notes = field<EditText>(sheet, "etDescription")
                    notes.requestFocus()
                    (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                        .showSoftInput(notes, InputMethodManager.SHOW_IMPLICIT)
                }
                await {
                    var visible = false
                    controller.onActivity { activity -> visible = dialogImeVisible(sheet(activity)) }
                    visible
                }
                screenshot("expanded-ime-diagnostic")
                controller.onActivity { activity ->
                    val sheet = sheet(activity)
                    val root = requireNotNull(sheet.dialog?.window?.decorView)
                    val ime = requireNotNull(ViewCompat.getRootWindowInsets(root)).getInsets(WindowInsetsCompat.Type.ime()).bottom
                    val keyboardTop = device.displayHeight - ime
                    val scroll = sheet.requireView().findViewById<View>(R.id.taskDetailScroll)
                    val visibleScroll = Rect()
                    assertTrue("Expanded content must retain visible space above IME", scroll.getGlobalVisibleRect(visibleScroll) && visibleScroll.height() > 0 && visibleScroll.top < keyboardTop)
                    assertTrue("Create CTA must remain above expanded IME", bounds(field<View>(sheet, "btnDetailCreate")).bottom <= keyboardTop)
                }
                device.pressBack()
                await {
                    var hidden = false
                    controller.onActivity { activity -> hidden = !dialogImeVisible(sheet(activity)) }
                    hidden
                }
                controller.onActivity { activity -> assertEquals(title, field<EditText>(sheet(activity), "etTitle").text.toString()) }
                var createBounds = Rect()
                controller.onActivity { activity ->
                    val sheet = sheet(activity)
                    val frame = bounds(sheet.requireView())
                    val screenHeight = device.displayHeight
                    assertTrue("Create should fill most of the phone", frame.height() >= screenHeight * 0.85)
                    assertEquals(compactDue, field<TextView>(sheet, "tvDueValue").text.toString())
                    val containerLabel = field<TextView>(sheet, "tvContainerValue")
                    assertEquals(sectionName, containerLabel.text.toString())
                    assertTrue("Container label must be within chip", bounds(field<View>(sheet, "chipContainer")).contains(bounds(containerLabel)))
                    val labelLayout = requireNotNull(containerLabel.layout)
                    val line = labelLayout.lineCount - 1
                    assertTrue("Long container must fit or ellipsize", labelLayout.getLineWidth(line) <= containerLabel.width - containerLabel.paddingLeft - containerLabel.paddingRight + 1 || labelLayout.getEllipsisCount(line) > 0)
                    val create = field<android.widget.Button>(sheet, "btnDetailCreate")
                    createBounds = bounds(create)
                }
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
                controller.onActivity { activity ->
                    field<View>(sheet(activity), "btnDetailCollapse").performClick()
                    tasks(activity).childFragmentManager.executePendingTransactions()
                }
                await {
                    var dismissed = false
                    controller.onActivity { activity ->
                        tasks(activity).childFragmentManager.executePendingTransactions()
                        dismissed = tasks(activity).childFragmentManager.findFragmentByTag("task_detail_create") == null
                    }
                    dismissed
                }
                // Create the single readable fixture through the existing service, then inspect edit mode.
                val saved = component.userTaskService.createTask(title, description = "Собрать материалы и согласовать следующие шаги.", containerId = container, due = due)
                component.userTaskService.createTask("Проверить список важных дел", parentTaskId = saved.id, containerId = container)
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
                    assertTrue(tasks(activity).isVisible)
                    assertEquals(View.VISIBLE, field<View>(edit, "topBarEdit").visibility)
                    assertEquals(title, field<EditText>(edit, "etTitle").text.toString())
                    assertEquals(compactDue, field<TextView>(edit, "tvDueValue").text.toString())
                    assertTrue(field<org.isoron.uhabits.activities.settings.SettingsThemePalette>(edit, "palette").isDark)
                }
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
    private fun idle() { instrumentation.waitForIdleSync(); device.waitForIdle() }
    private fun await(condition: () -> Boolean) { val until = SystemClock.uptimeMillis() + 5_000; while (SystemClock.uptimeMillis() < until) { idle(); if (condition()) return; SystemClock.sleep(50) }; error("UI condition did not settle") }
    private fun screenshot(name: String) { val file = File(context.getExternalFilesDir(null), "pr5-visual-qa/$name.png"); check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory); check(device.takeScreenshot(file)) }
    @Suppress("UNCHECKED_CAST") private fun <T> field(instance: Any, name: String): T = instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(instance) as T
    private fun invoke(instance: Any, name: String): Any? = instance.javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(instance)
}
