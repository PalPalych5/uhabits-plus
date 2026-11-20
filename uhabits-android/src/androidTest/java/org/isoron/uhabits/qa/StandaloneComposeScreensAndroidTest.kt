/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.qa

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.github.appintro.R as IntroResources
import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.BuildConfig
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.blocks.ManageBlocksActivity
import org.isoron.uhabits.activities.common.dialogs.NumberDialog
import org.isoron.uhabits.activities.common.dialogs.CustomDialogs
import org.isoron.uhabits.activities.containers.statistics.ContainerStatisticsActivity
import org.isoron.uhabits.activities.intro.IntroActivity
import org.isoron.uhabits.activities.main.MainActivity
import org.isoron.uhabits.activities.main.MainDestination
import org.isoron.uhabits.core.containers.session.DatasetSessionFactory
import org.isoron.uhabits.utils.DemoDataGenerator
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.text.DecimalFormatSymbols
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class StandaloneComposeScreensAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val component get() = HabitsApplication.component

    @Before
    fun requireOwnedOfflineFixture() {
        val avd = InstrumentationRegistry.getArguments().getString("authorizedAvd").orEmpty()
        check(BuildConfig.DEBUG && avd.matches(Regex("UHabit_Temp_[a-f0-9]{32}")))
        check(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim() == avd)
        check(Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1)
        check(Settings.Global.getInt(context.contentResolver, Settings.Global.WIFI_ON, 0) == 0)
        val session = component.session
        val marker = File(context.filesDir, "${DemoDataGenerator.DISPOSABLE_MARKER}-${session.datasetId}")
        check(session.isExperimental && marker.isFile && marker.readText().trim() == session.databaseFilename)
    }

    @Test
    fun firstLaunchSlidesRestoreAndCanBeSkipped() {
        ActivityScenario.launch<IntroActivity>(Intent(context, IntroActivity::class.java)).use { scenario ->
            val first = label(scenario, R.string.intro_title_1)
            val second = label(scenario, R.string.intro_title_2)
            val next = label(scenario, IntroResources.string.app_intro_next_button)
            val skip = label(scenario, IntroResources.string.app_intro_skip_button)
            node(By.text(first))
            node(By.desc(next)).click()
            node(By.text(second))
            scenario.recreate()
            node(By.text(second))
            capture("intro-resumed-second-slide")
            device.pressBack()
            node(By.text(first))
            node(By.desc(skip)).click()
            check(device.wait(Until.gone(By.text(first)), 5_000L))
        }
    }

    @Test
    fun nextBlocksAreReadOnly() {
        val intent = Intent(context, ManageBlocksActivity::class.java)
        ActivityScenario.launch<ManageBlocksActivity>(intent).use { scenario ->
            node(By.text(label(scenario, R.string.manage_blocks)))
            val add = label(scenario, R.string.add_block)
            check(!device.hasObject(By.desc(add)) && !device.hasObject(By.text(add)))
        }
    }

    @Test
    fun legacyBlocksSaveAndRenderAfterRecreation() {
        val app = context.applicationContext as HabitsApplication
        val original = component.session
        val suffix = UUID.randomUUID().toString().take(8)
        val legacy = DatasetSessionFactory.production().copy(
            datasetId = "compose-legacy-$suffix",
            databaseFilename = "compose-legacy-$suffix.db",
            preferencesNamespace = "compose-legacy-$suffix",
        )
        instrumentation.runOnMainSync { app.rebuildComponent(legacy) }
        try {
            val name = "Compose QA block $suffix"
            val intent = Intent(context, ManageBlocksActivity::class.java)
            ActivityScenario.launch<ManageBlocksActivity>(intent).use { scenario ->
                node(By.desc(label(scenario, R.string.add_block))).click()
                node(By.clazz("android.widget.EditText")).text = name
                node(By.text(label(scenario, R.string.save))).click()
                node(By.text(name))
                scenario.recreate()
                node(By.text(name))
                capture("blocks-added")
            }
        } finally {
            instrumentation.runOnMainSync { app.rebuildComponent(original) }
            context.deleteDatabase(legacy.databaseFilename)
            context.deleteSharedPreferences("uhabits_prefs_${legacy.preferencesNamespace}")
        }
    }

    @Test
    fun containerStatisticsRestoresTheSelectedRange() {
        val container = component.organizationFacade.createContainer("Compose QA stats").getOrThrow()
        val intent = Intent(context, ContainerStatisticsActivity::class.java)
            .putExtra(ContainerStatisticsActivity.EXTRA_CONTAINER_ID, container.id.value)
        ActivityScenario.launch<ContainerStatisticsActivity>(intent).use { scenario ->
            node(By.res("container_statistics_root"))
            node(By.res("btnRangeMonth")).click()
            node(By.res("btnRangeMonth").selected(true))
            scenario.recreate()
            node(By.res("btnRangeMonth").selected(true))
            capture("container-statistics-month")
        }
    }

    @Test
    fun numericEditorValidatesAndSubmitsTheLocalizedDecimalViaEnter() {
        val saved = AtomicReference<Pair<Double, String>>()
        val intent = MainActivity.intent(context, MainDestination.HABITS)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                NumberDialog().apply {
                    arguments = Bundle().apply {
                        putDouble("value", 12.0)
                        putString("notes", "Synthetic note")
                    }
                    onToggle = { amount, notes -> saved.set(amount to notes) }
                }.showNow(activity.supportFragmentManager, "numberDialog")
            }
            val selector = By.res("habitNumberValue")
            val field = node(selector)
            check(field.text == "12")
            val separator = DecimalFormatSymbols.getInstance().decimalSeparator
            field.text = "1${separator}2${separator}3"
            val saveLabel = label(scenario, R.string.dialog_action_save_description)
            node(By.desc(saveLabel)).click()
            node(By.text(label(scenario, R.string.validation_invalid_number_format)))
            check(saved.get() == null)
            node(selector).text = "5${separator}25"
            capture("numeric-editor")
            check(device.pressEnter())
            check(device.wait(Until.gone(selector), 5_000L))
            check(saved.get() == (5.25 to "Synthetic note"))
        }
    }

    @Test
    fun dateAndTimeDialogActionsReturnThePlatformSelection() {
        val chosenDate = AtomicReference<LocalDate>()
        val chosenTime = AtomicReference<Pair<Int, Int>>()
        val initialDate = LocalDate(2026, 6, 15)
        val intent = MainActivity.intent(context, MainDestination.HABITS)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            val confirm = label(scenario, android.R.string.ok)
            scenario.onActivity { activity ->
                CustomDialogs.showDatePickerDialog(activity, "QA calendar", initialDate) { chosenDate.set(it) }
            }
            node(By.text("QA calendar"))
            node(By.text(confirm)).click()
            check(device.wait(Until.gone(By.text("QA calendar")), 5_000L))
            check(chosenDate.get() == initialDate)

            scenario.onActivity { activity ->
                CustomDialogs.showTimePickerDialog(activity, "QA time", 16, 35, true) { hour, minute ->
                    chosenTime.set(hour to minute)
                }
            }
            node(By.text("QA time"))
            node(By.text(confirm)).click()
            check(device.wait(Until.gone(By.text("QA time")), 5_000L))
            check(chosenTime.get() == (16 to 35))
        }
    }

    private fun node(selector: BySelector): UiObject2 =
        checkNotNull(device.wait(Until.findObject(selector), 5_000L)) { "UI element missing: $selector" }

    private fun <A : ComponentActivity> label(scenario: ActivityScenario<A>, textRes: Int): String {
        var text = ""
        scenario.onActivity { text = it.getString(textRes) }
        return text
    }

    private fun capture(name: String) {
        val output = File(context.getExternalFilesDir(null), "compose-migration")
        check(output.mkdirs() || output.isDirectory)
        check(device.takeScreenshot(File(output, "$name.png")))
    }
}
