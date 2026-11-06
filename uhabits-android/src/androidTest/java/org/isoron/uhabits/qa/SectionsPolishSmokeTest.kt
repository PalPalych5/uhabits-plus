package org.isoron.uhabits.qa

import android.content.Context
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

/** Synthetic dataset only; TaskDraftQaRunner checks the disposable offline AVD. */
@RunWith(AndroidJUnit4::class)
class SectionsPolishSmokeTest {
    @Test fun taskOnlyDrawerAndHiddenList() {
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
        val facade = component.organizationFacade
        val habitSectionIds = facade.availableContainers().map { it.key }.toSet()
        check(habitSectionIds.isNotEmpty())
        check(context.getSharedPreferences("task_section_visibility_${component.session.datasetId}", Context.MODE_PRIVATE)
            .edit().putStringSet("hidden", habitSectionIds).commit())
        val study = facade.createContainer("Учёба").getOrThrow()
        val work = facade.createContainer("Работа").getOrThrow()
        val projects = facade.createContainer("Проекты").getOrThrow()
        val output = File(context.getExternalFilesDir(null), "sections-polish-smoke")
        check(output.mkdirs() || output.isDirectory)
        ActivityScenario.launch<MainActivity>(MainActivity.intent(context, MainDestination.TASKS)).use { scenario ->
            fun fragment(activity: MainActivity) = activity.supportFragmentManager.findFragmentByTag("main.tasks") as TasksFragment
            scenario.onActivity { fragment(it).openNavigation() }
            val deadline = android.os.SystemClock.uptimeMillis() + 10000
            var studyRow: View? = null
            while (studyRow == null && android.os.SystemClock.uptimeMillis() < deadline) {
                scenario.onActivity { studyRow = fragment(it).requireView().findViewWithTag("container_row_${study.id.value}") }
                if (studyRow == null) android.os.SystemClock.sleep(100)
            }
            assertNotNull(studyRow)
            scenario.onActivity {
                val root = fragment(it).requireView()
                assertNotNull(root.findViewWithTag<View>("container_row_${work.id.value}"))
                assertNotNull(root.findViewWithTag<View>("container_row_${projects.id.value}"))
                habitSectionIds.forEach { id -> assertNull(root.findViewWithTag<View>("container_row_$id")) }
            }
            instrumentation.waitForIdleSync()
            assertTrue(device.takeScreenshot(File(output, "drawer.png")))
            scenario.onActivity { fragment(it).requireView().findViewWithTag<View>("manage_hidden_sections_btn").performClick() }
            requireNotNull(device.wait(Until.findObject(By.text("Скрытые разделы")), 5000)).click()
            assertNotNull(device.wait(Until.findObject(By.text("Скрытые разделы")), 5000))
            instrumentation.waitForIdleSync()
            assertTrue(device.takeScreenshot(File(output, "hidden-sections.png")))
        }
    }
}
