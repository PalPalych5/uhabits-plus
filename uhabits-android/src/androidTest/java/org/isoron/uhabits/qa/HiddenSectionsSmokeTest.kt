package org.isoron.uhabits.qa

import android.provider.Settings
import android.view.View
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.activities.main.MainActivity
import org.isoron.uhabits.activities.main.MainDestination
import org.isoron.uhabits.activities.tasks.TasksFragment
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Run with TaskDraftQaRunner on its disposable offline fixture only. */
@RunWith(AndroidJUnit4::class)
class HiddenSectionsSmokeTest {
    @Test fun hideSearchAndRestore() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val avd = InstrumentationRegistry.getArguments().getString("authorizedAvd").orEmpty()
        check(avd.matches(Regex("UHabit_Temp_[a-f0-9]{32}")))
        check(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim() == avd)
        check(Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1)
        check(Settings.Global.getInt(context.contentResolver, Settings.Global.WIFI_ON, 0) == 0)
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("ru-RU"))
        val component = HabitsApplication.component
        val variant = InstrumentationRegistry.getArguments().getString("sectionsVariant") ?: "light"
        require(variant in setOf("light", "dark", "amoled"))
        val theme = if (variant == "light") 0 else 1
        component.preferences.theme = theme
        component.preferences.isPureBlackEnabled = variant == "amoled"
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putInt("pref_theme", theme).putBoolean("pref_pure_black", variant == "amoled").commit()
        val suffix = UUID.randomUUID().toString().take(6)
        val parent = component.organizationFacade.createContainer("Учёба $suffix").getOrThrow()
        val child = component.organizationFacade.createContainer("Университет $suffix", parentId = parent.id).getOrThrow()
        val output = File(context.getExternalFilesDir(null), "hidden-sections-smoke")
        check(output.mkdirs() || output.isDirectory)
        ActivityScenario.launch<MainActivity>(MainActivity.intent(context, MainDestination.TASKS)).use { scenario ->
            fun fragment(activity: MainActivity) = activity.supportFragmentManager.findFragmentByTag("main.tasks") as TasksFragment
            fun openDrawer() { scenario.onActivity { fragment(it).openNavigation() }; instrumentation.waitForIdleSync() }
            fun waitForRow(id: String): View {
                val deadline = android.os.SystemClock.uptimeMillis() + 10000
                var row: View? = null
                while (row == null && android.os.SystemClock.uptimeMillis() < deadline) {
                    scenario.onActivity { row = fragment(it).requireView().findViewWithTag("container_row_$id") }
                    if (row == null) android.os.SystemClock.sleep(100)
                }
                return requireNotNull(row)
            }
            fun clickText(text: String) {
                requireNotNull(device.wait(Until.findObject(By.text(text)), 5000)) { "Missing $text" }.click()
                instrumentation.waitForIdleSync()
            }
            openDrawer()
            val row = waitForRow(parent.id.value)
            scenario.onActivity { row.requestRectangleOnScreen(android.graphics.Rect(0, 0, row.width, row.height), true) }
            instrumentation.waitForIdleSync()
            scenario.onActivity { assertTrue(row.performLongClick()) }
            clickText("Скрыть из задач")
            openDrawer()
            scenario.onActivity { fragment(it).requireView().findViewWithTag<View>("manage_hidden_sections_btn").performClick() }
            clickText("Скрытые разделы")
            assertNotNull(device.wait(Until.findObject(By.text("Учёба $suffix")), 5000))
            assertNotNull(device.wait(Until.findObject(By.text("Университет $suffix")), 5000))
            android.os.SystemClock.sleep(2000) // Allow dialog and system-bar transitions to settle before inspection.
            assertTrue(device.takeScreenshot(File(output, "$variant.png")))
            val search = requireNotNull(device.findObject(By.clazz("android.widget.EditText")))
            search.click()
            val keyboardDeadline = android.os.SystemClock.uptimeMillis() + 5000
            var keyboardShown = false
            while (!keyboardShown && android.os.SystemClock.uptimeMillis() < keyboardDeadline) {
                val state = device.executeShellCommand("dumpsys input_method")
                keyboardShown = state.contains("mInputShown=true") || state.contains("isInputViewShown=true")
                if (!keyboardShown) android.os.SystemClock.sleep(100)
            }
            assertTrue("IME did not appear for search", keyboardShown)
            search.text = "Университет $suffix"
            instrumentation.waitForIdleSync()
            assertNull(device.findObject(By.desc("Показать: Учёба $suffix")))
            assertNotNull(device.findObject(By.text("Университет $suffix")))
            requireNotNull(device.wait(Until.findObject(By.desc("Очистить")), 5000)) { "Missing clear action" }.click()
            instrumentation.waitForIdleSync()
            assertNotNull(device.wait(Until.findObject(By.text("Учёба $suffix")), 5000))
            search.text = "Университет $suffix"
            instrumentation.waitForIdleSync()
            device.pressBack() // Close IME before interacting with Snackbar.
            instrumentation.waitForIdleSync()
            clickText("Показать путь")
            instrumentation.waitForIdleSync()
            assertTrue(component.organizationFacade.availableContainers().any { it.key == child.id.value })
            requireNotNull(device.wait(Until.findObject(By.text(java.util.regex.Pattern.compile("отменить", java.util.regex.Pattern.CASE_INSENSITIVE or java.util.regex.Pattern.UNICODE_CASE))), 5000)) { "Missing Undo action" }.click()
            assertNotNull(device.wait(Until.findObject(By.text("Университет $suffix")), 5000))
            clickText("Показать путь")
            device.pressBack()
            if (device.hasObject(By.text("Скрытые разделы"))) device.pressBack()
            openDrawer()
            waitForRow(parent.id.value)
            waitForRow(child.id.value)
        }
    }
}
