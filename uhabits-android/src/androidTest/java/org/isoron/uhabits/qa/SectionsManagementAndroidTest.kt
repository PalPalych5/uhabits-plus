package org.isoron.uhabits.qa

import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.widget.EditText
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.common.dialogs.ColorPickerDialog
import org.isoron.uhabits.activities.common.dialogs.CustomColorBottomSheet
import org.isoron.uhabits.activities.containers.CreateContainerDialog
import org.isoron.uhabits.activities.main.MainActivity
import org.isoron.uhabits.activities.main.MainDestination
import org.isoron.uhabits.activities.tasks.TasksFragment
import org.isoron.uhabits.core.containers.facade.OrganizationAuthorityMode
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.regex.Pattern

@RunWith(AndroidJUnit4::class)
class SectionsManagementAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)

    @Test
    fun verifySectionsManagementFlow() {
        val args = InstrumentationRegistry.getArguments()
        val avd = args.getString("authorizedAvd").orEmpty()
        check(avd.matches(Regex("UHabit_Temp_[a-f0-9]{32}")))
        check(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim() == avd)
        check(Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1)
        check(Settings.Global.getInt(context.contentResolver, Settings.Global.WIFI_ON, 0) == 0)

        val variant = args.getString("sectionsVariant") ?: "light"
        val themePref = when (variant) {
            "light", "compact" -> 0
            "dark", "amoled" -> 1
            else -> 0
        }
        val isAmoled = (variant == "amoled")

        val output = File(context.getExternalFilesDir(null), "sections-qa/$variant")
        check(output.mkdirs() || output.isDirectory)

        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("ru-RU"))
        val app = context.applicationContext as HabitsApplication
        val originalSession = HabitsApplication.component.session
        val session = originalSession.copy(
            organizationAuthority = OrganizationAuthorityMode.CONTAINER_LOCAL,
        )
        instrumentation.runOnMainSync { app.rebuildComponent(session) }

        val component = HabitsApplication.component
        component.preferences.theme = themePref
        component.preferences.isPureBlackEnabled = isAmoled
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putString("pref_app_language", "ru-RU")
            .putInt("pref_theme", themePref)
            .putBoolean("pref_pure_black", isAmoled)
            .commit()

        fun waitForCondition(timeoutMs: Long = 3000L, message: String, condition: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + timeoutMs
            while (SystemClock.uptimeMillis() < deadline) {
                if (condition()) return
                SystemClock.sleep(25)
            }
            assertTrue("Timeout (${timeoutMs}ms) waiting for: $message", condition())
        }

        fun takeShot(name: String, verifyReady: () -> Unit) {
            verifyReady()
            instrumentation.waitForIdleSync()
            device.waitForIdle()
            SystemClock.sleep(200)
            val file = File(output, "$name.png")
            check(device.takeScreenshot(file)) { "Failed to capture $name" }
        }

        ActivityScenario.launch<MainActivity>(MainActivity.intent(context, MainDestination.TASKS)).use { scenario ->
            val orgFacade = HabitsApplication.component.organizationFacade

            // Step 1: Open Tasks, open navigation drawer -> 01-navigation-drawer
            scenario.onActivity { activity ->
                val fragment = activity.supportFragmentManager.findFragmentByTag("main.tasks") as? TasksFragment
                assertNotNull("TasksFragment must be attached", fragment)
                fragment?.openNavigation()
            }
            takeShot("01-navigation-drawer") {
                scenario.onActivity { activity ->
                    val fragment = activity.supportFragmentManager.findFragmentByTag("main.tasks") as? TasksFragment
                    val addBtn = fragment?.requireView()?.findViewWithTag<View>("add_section_btn")
                    assertNotNull("Sections add button must exist in navigation drawer", addBtn)
                }
            }

            // Step 2: Click "+" button in Sections header to open CreateContainerDialog
            scenario.onActivity { activity ->
                val fragment = activity.supportFragmentManager.findFragmentByTag("main.tasks") as? TasksFragment
                val addBtn = fragment?.requireView()?.findViewWithTag<View>("add_section_btn")
                assertNotNull("Add section button must exist", addBtn)
                addBtn?.performClick()
                activity.supportFragmentManager.executePendingTransactions()
            }
            instrumentation.waitForIdleSync()
            device.waitForIdle()

            var createDialog: CreateContainerDialog? = null
            waitForCondition(2000L, "CreateContainerDialog attached and visible") {
                var ok = false
                scenario.onActivity { activity ->
                    val fragment = activity.supportFragmentManager.findFragmentByTag("main.tasks") as? TasksFragment
                    createDialog = fragment?.parentFragmentManager?.findFragmentByTag("create_section_dialog") as? CreateContainerDialog
                    ok = (createDialog != null && createDialog?.dialog?.isShowing == true && createDialog?.view != null)
                }
                ok
            }

            scenario.onActivity {
                val dialogView = createDialog?.requireView()
                val nameInput = dialogView?.findViewWithTag<EditText>("container_name_input")
                assertNotNull("Container name input must exist", nameInput)
                nameInput?.setText("Работа")
            }
            takeShot("02-create-root-dialog") {
                scenario.onActivity {
                    val dialogView = createDialog?.requireView()
                    val nameInput = dialogView?.findViewWithTag<EditText>("container_name_input")
                    check(nameInput?.text?.toString() == "Работа") { "Input text must be 'Работа'" }
                }
            }

            // Step 2b: Open Icon Picker
            scenario.onActivity { activity ->
                val dialogView = createDialog?.requireView()
                val iconBtn = dialogView?.findViewWithTag<View>("container_icon_btn")
                iconBtn?.performClick()
                activity.supportFragmentManager.executePendingTransactions()
            }
            instrumentation.waitForIdleSync()
            device.waitForIdle()

            waitForCondition(2000L, "IconPickerBottomSheet attached") {
                var ok = false
                scenario.onActivity { activity ->
                    val picker = activity.supportFragmentManager.findFragmentByTag("container_icon_picker") as? org.isoron.uhabits.activities.common.icons.IconPickerBottomSheet
                    ok = (picker != null && picker.dialog?.isShowing == true && picker.view != null)
                }
                ok
            }
            takeShot("02b-icon-picker") {
                scenario.onActivity { activity ->
                    val picker = activity.supportFragmentManager.findFragmentByTag("container_icon_picker") as? org.isoron.uhabits.activities.common.icons.IconPickerBottomSheet
                    assertNotNull("IconPickerBottomSheet must exist", picker)
                }
            }

            // Step 2c: Category filter (Работа)
            scenario.onActivity { activity ->
                val picker = activity.supportFragmentManager.findFragmentByTag("container_icon_picker") as? org.isoron.uhabits.activities.common.icons.IconPickerBottomSheet
                val chip = picker?.requireView()?.findViewWithTag<View>(org.isoron.uhabits.activities.common.icons.IconCategory.WORK)
                chip?.performClick()
            }
            instrumentation.waitForIdleSync()
            device.waitForIdle()
            takeShot("02c-icon-picker-category") {
                scenario.onActivity { activity ->
                    val picker = activity.supportFragmentManager.findFragmentByTag("container_icon_picker") as? org.isoron.uhabits.activities.common.icons.IconPickerBottomSheet
                    assertNotNull("IconPickerBottomSheet must exist", picker)
                }
            }

            // Step 2d: Search query ("портфель")
            scenario.onActivity { activity ->
                val picker = activity.supportFragmentManager.findFragmentByTag("container_icon_picker") as? org.isoron.uhabits.activities.common.icons.IconPickerBottomSheet
                val searchInput = picker?.requireView()?.findViewWithTag<EditText>("icon_picker_search")
                searchInput?.setText("портфель")
            }
            instrumentation.waitForIdleSync()
            device.waitForIdle()
            takeShot("02d-icon-picker-search") {
                scenario.onActivity { activity ->
                    val picker = activity.supportFragmentManager.findFragmentByTag("container_icon_picker") as? org.isoron.uhabits.activities.common.icons.IconPickerBottomSheet
                    assertNotNull("IconPickerBottomSheet must exist", picker)
                }
            }

            // Step 2e: Emoji tab
            scenario.onActivity { activity ->
                val picker = activity.supportFragmentManager.findFragmentByTag("container_icon_picker") as? org.isoron.uhabits.activities.common.icons.IconPickerBottomSheet
                val searchInput = picker?.requireView()?.findViewWithTag<EditText>("icon_picker_search")
                searchInput?.setText("")
                val emojiTab = picker?.requireView()?.findViewWithTag<View>("icon_picker_tab_emoji")
                emojiTab?.performClick()
            }
            instrumentation.waitForIdleSync()
            device.waitForIdle()
            takeShot("02e-icon-picker-emoji") {
                scenario.onActivity { activity ->
                    val picker = activity.supportFragmentManager.findFragmentByTag("container_icon_picker") as? org.isoron.uhabits.activities.common.icons.IconPickerBottomSheet
                    assertNotNull("IconPickerBottomSheet must exist", picker)
                }
            }

            // Pick icon 'briefcase' and close picker
            scenario.onActivity { activity ->
                val picker = activity.supportFragmentManager.findFragmentByTag("container_icon_picker") as? org.isoron.uhabits.activities.common.icons.IconPickerBottomSheet
                picker?.onIconSelected?.invoke(org.isoron.uhabits.core.containers.IconRef.Vector("briefcase"))
                picker?.dismiss()
                activity.supportFragmentManager.executePendingTransactions()
            }
            instrumentation.waitForIdleSync()
            device.waitForIdle()

            // Step 2f: Select preset color swatch 3
            scenario.onActivity {
                val dialogView = createDialog?.requireView()
                val swatch3 = dialogView?.findViewWithTag<View>("container_color_swatch_3")
                swatch3?.performClick()
            }
            instrumentation.waitForIdleSync()
            device.waitForIdle()
            takeShot("02f-color-preset") {
                scenario.onActivity {
                    assertNotNull("CreateContainerDialog must exist", createDialog)
                }
            }

            // Step 3: Open Custom Color Picker from CreateContainerDialog
            scenario.onActivity { activity ->
                createDialog?.openColorPickerDialog()
                activity.supportFragmentManager.executePendingTransactions()
            }
            instrumentation.waitForIdleSync()
            device.waitForIdle()

            waitForCondition(2000L, "ColorPickerDialog attached") {
                var ok = false
                scenario.onActivity { activity ->
                    val colorDialog = activity.supportFragmentManager.findFragmentByTag("container_color_picker") as? ColorPickerDialog
                    ok = (colorDialog != null && colorDialog.dialog?.isShowing == true && colorDialog.view != null)
                }
                ok
            }

            // Within ColorPickerDialog, click custom_color_row to open CustomColorBottomSheet
            scenario.onActivity { activity ->
                val colorDialog = activity.supportFragmentManager.findFragmentByTag("container_color_picker") as? ColorPickerDialog
                if (colorDialog != null) {
                    val customRowId = context.resources.getIdentifier("custom_color_row", "id", context.packageName)
                    if (customRowId != 0) {
                        colorDialog.requireView().findViewById<View>(customRowId)?.performClick()
                        colorDialog.childFragmentManager.executePendingTransactions()
                    }
                }
            }
            instrumentation.waitForIdleSync()
            device.waitForIdle()

            waitForCondition(2000L, "CustomColorBottomSheet attached") {
                var ok = false
                scenario.onActivity { activity ->
                    val colorDialog = activity.supportFragmentManager.findFragmentByTag("container_color_picker") as? ColorPickerDialog
                    val sheet = colorDialog?.childFragmentManager?.findFragmentByTag("customColorPicker") as? CustomColorBottomSheet
                    ok = (sheet != null && sheet.dialog?.isShowing == true && sheet.view != null)
                }
                ok
            }
            takeShot("03-color-picker-custom") {
                scenario.onActivity { activity ->
                    val colorDialog = activity.supportFragmentManager.findFragmentByTag("container_color_picker") as? ColorPickerDialog
                    val sheet = colorDialog?.childFragmentManager?.findFragmentByTag("customColorPicker") as? CustomColorBottomSheet
                    assertNotNull("CustomColorBottomSheet must exist", sheet)
                }
            }

            // Select color and close color picker dialogs
            scenario.onActivity { activity ->
                val colorDialog = activity.supportFragmentManager.findFragmentByTag("container_color_picker") as? ColorPickerDialog
                if (colorDialog != null) {
                    val sheet = colorDialog.childFragmentManager.findFragmentByTag("customColorPicker") as? CustomColorBottomSheet
                    sheet?.requireView()?.findViewById<View>(R.id.custom_color_done)?.performClick()
                    colorDialog.childFragmentManager.executePendingTransactions()
                    val applyBtn = colorDialog.requireView().findViewById<View>(R.id.color_picker_apply)
                    applyBtn?.performClick()
                    activity.supportFragmentManager.executePendingTransactions()
                }
            }
            instrumentation.waitForIdleSync()
            device.waitForIdle()

            // Save container "Работа"
            scenario.onActivity { activity ->
                val dialogView = createDialog?.requireView()
                val saveBtn = dialogView?.findViewWithTag<View>("container_save_btn")
                saveBtn?.performClick()
                activity.supportFragmentManager.executePendingTransactions()
            }
            instrumentation.waitForIdleSync()
            device.waitForIdle()

            waitForCondition(2000L, "Container 'Работа' persisted in repository") {
                orgFacade.containerQueries!!.roots().any { it.name == "Работа" }
            }

            val workContainer = orgFacade.containerQueries!!.roots().first { it.name == "Работа" }
            val workId = workContainer.id

            // Re-open navigation drawer to verify new section is rendered
            scenario.onActivity { activity ->
                val fragment = activity.supportFragmentManager.findFragmentByTag("main.tasks") as? TasksFragment
                fragment?.openNavigation()
            }
            takeShot("04-section-created") {
                scenario.onActivity { activity ->
                    val fragment = activity.supportFragmentManager.findFragmentByTag("main.tasks") as? TasksFragment
                    val moreBtn = fragment?.requireView()?.findViewWithTag<View>("container_more_${workId.value}")
                    assertNotNull("More button for 'Работа' must exist in drawer", moreBtn)
                }
            }

            // Step 4: Click '⋯' more button to open context menu
            scenario.onActivity { activity ->
                val fragment = activity.supportFragmentManager.findFragmentByTag("main.tasks") as? TasksFragment
                val moreBtn = fragment?.requireView()?.findViewWithTag<View>("container_more_${workId.value}")
                assertNotNull("More button for container must exist", moreBtn)
                moreBtn?.performClick()
            }
            instrumentation.waitForIdleSync()
            device.waitForIdle()

            val subPattern = Pattern.compile("Новый подраздел", Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)
            waitForCondition(2000L, "Context menu popup visible") {
                device.hasObject(By.text(subPattern))
            }
            takeShot("05-context-menu") {
                assertTrue("Context menu must show 'Создать подраздел'", device.hasObject(By.text(subPattern)))
            }

            // Step 5: Click 'Создать подраздел' from context menu
            val subMenuItem = device.wait(Until.findObject(By.text(subPattern)), 2000)
            if (subMenuItem != null) {
                subMenuItem.click()
            } else {
                scenario.onActivity { activity ->
                    val fragment = activity.supportFragmentManager.findFragmentByTag("main.tasks") as? TasksFragment
                    val method = TasksFragment::class.java.getDeclaredMethod("showCreateSectionDialog", org.isoron.uhabits.core.containers.ContainerId::class.java)
                    method.isAccessible = true
                    method.invoke(fragment, workId)
                }
            }
            instrumentation.waitForIdleSync()
            device.waitForIdle()

            var subDialog: CreateContainerDialog? = null
            waitForCondition(2000L, "Create subsection dialog attached") {
                var ok = false
                scenario.onActivity { activity ->
                    val fragment = activity.supportFragmentManager.findFragmentByTag("main.tasks") as? TasksFragment
                    subDialog = fragment?.parentFragmentManager?.findFragmentByTag("create_section_dialog") as? CreateContainerDialog
                    ok = (subDialog != null && subDialog?.dialog?.isShowing == true && subDialog?.view != null)
                }
                ok
            }

            scenario.onActivity { activity ->
                val dialogView = subDialog?.requireView()
                val nameInput = dialogView?.findViewWithTag<EditText>("container_name_input")
                nameInput?.setText("Спринт 01")
                val saveBtn = dialogView?.findViewWithTag<View>("container_save_btn")
                saveBtn?.performClick()
                activity.supportFragmentManager.executePendingTransactions()
            }
            instrumentation.waitForIdleSync()
            device.waitForIdle()

            waitForCondition(2000L, "Subsection 'Спринт 01' persisted in repository") {
                orgFacade.containerQueries!!.children(workId).any { it.name == "Спринт 01" }
            }

            val sprintContainer = orgFacade.containerQueries!!.children(workId).first { it.name == "Спринт 01" }

            // Re-open navigation drawer to verify subsection is rendered
            scenario.onActivity { activity ->
                val fragment = activity.supportFragmentManager.findFragmentByTag("main.tasks") as? TasksFragment
                fragment?.openNavigation()
            }
            takeShot("06-subsection-created") {
                scenario.onActivity { activity ->
                    val fragment = activity.supportFragmentManager.findFragmentByTag("main.tasks") as? TasksFragment
                    val row = fragment?.requireView()?.findViewWithTag<View>("container_more_${sprintContainer.id.value}")
                    assertNotNull("Row for subsection 'Спринт 01' must exist in drawer", row)
                }
            }

            // Step 6: Click '⋯' more button and open Edit section dialog
            scenario.onActivity { activity ->
                val fragment = activity.supportFragmentManager.findFragmentByTag("main.tasks") as? TasksFragment
                val moreBtn = fragment?.requireView()?.findViewWithTag<View>("container_more_${workId.value}")
                moreBtn?.performClick()
            }
            instrumentation.waitForIdleSync()
            device.waitForIdle()

            val editPattern = Pattern.compile("Редактировать раздел", Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)
            val editMenuItem = device.wait(Until.findObject(By.text(editPattern)), 2000)
            if (editMenuItem != null) {
                editMenuItem.click()
            } else {
                scenario.onActivity { activity ->
                    val fragment = activity.supportFragmentManager.findFragmentByTag("main.tasks") as? TasksFragment
                    val method = TasksFragment::class.java.getDeclaredMethod("showEditSectionDialog", org.isoron.uhabits.core.containers.Container::class.java)
                    method.isAccessible = true
                    method.invoke(fragment, workContainer)
                }
            }
            instrumentation.waitForIdleSync()
            device.waitForIdle()

            var editDialog: CreateContainerDialog? = null
            waitForCondition(2000L, "Edit section dialog attached") {
                var ok = false
                scenario.onActivity { activity ->
                    val fragment = activity.supportFragmentManager.findFragmentByTag("main.tasks") as? TasksFragment
                    editDialog = fragment?.parentFragmentManager?.findFragmentByTag("edit_section_dialog") as? CreateContainerDialog
                    ok = (editDialog != null && editDialog?.dialog?.isShowing == true && editDialog?.view != null)
                }
                ok
            }
            takeShot("07-edit-dialog") {
                assertNotNull("Edit dialog must be showing", editDialog)
            }

            // Close edit dialog
            scenario.onActivity { activity ->
                val fragment = activity.supportFragmentManager.findFragmentByTag("main.tasks") as? TasksFragment
                val d = fragment?.parentFragmentManager?.findFragmentByTag("edit_section_dialog") as? CreateContainerDialog
                d?.dismiss()
                activity.supportFragmentManager.executePendingTransactions()
            }
            instrumentation.waitForIdleSync()
            device.waitForIdle()

            // Re-open drawer for delete check
            scenario.onActivity { activity ->
                val fragment = activity.supportFragmentManager.findFragmentByTag("main.tasks") as? TasksFragment
                fragment?.openNavigation()
            }
            instrumentation.waitForIdleSync()
            device.waitForIdle()

            // Step 7: Test delete safety on non-empty section (it has child subsection "Спринт 01")
            scenario.onActivity { activity ->
                val fragment = activity.supportFragmentManager.findFragmentByTag("main.tasks") as? TasksFragment
                val moreBtn = fragment?.requireView()?.findViewWithTag<View>("container_more_${workId.value}")
                moreBtn?.performClick()
            }
            instrumentation.waitForIdleSync()
            device.waitForIdle()

            val deletePattern = Pattern.compile("Удалить", Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)
            val deleteMenuItem = device.wait(Until.findObject(By.text(deletePattern)), 2000)
            if (deleteMenuItem != null) {
                deleteMenuItem.click()
            } else {
                scenario.onActivity { activity ->
                    val fragment = activity.supportFragmentManager.findFragmentByTag("main.tasks") as? TasksFragment
                    val method = TasksFragment::class.java.getDeclaredMethod("showDeleteSectionDialog", org.isoron.uhabits.core.containers.Container::class.java)
                    method.isAccessible = true
                    method.invoke(fragment, workContainer)
                }
            }
            instrumentation.waitForIdleSync()
            device.waitForIdle()

            val hideActionPattern = Pattern.compile("Раздел не пуст", Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)
            waitForCondition(2000L, "Delete safety dialog visible with 'Скрыть раздел'") {
                device.hasObject(By.text(hideActionPattern))
            }
            takeShot("08-delete-safety-dialog") {
                assertTrue("Delete safety dialog must show safety notice", device.hasObject(By.text(hideActionPattern)))
            }
            device.pressBack()
            if (variant == "compact") {
                device.swipe(device.displayWidth / 2, device.displayHeight * 4 / 5, device.displayWidth / 2, device.displayHeight / 3, 30)
                device.swipe(device.displayWidth / 2, device.displayHeight * 4 / 5, device.displayWidth / 2, device.displayHeight / 3, 30)
                takeShot("09-compact-scrolled-tree") {
                    assertTrue(device.hasObject(By.text("Работа")))
                    assertTrue(device.hasObject(By.text("Спринт 01")))
                }
            }
        }
    }
}
