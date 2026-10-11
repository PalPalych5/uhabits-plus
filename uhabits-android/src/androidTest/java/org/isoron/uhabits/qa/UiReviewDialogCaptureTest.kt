package org.isoron.uhabits.qa

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.containers.ContainersActivity
import org.isoron.uhabits.activities.containers.CreateContainerDialog
import org.isoron.uhabits.activities.main.MainActivity
import org.isoron.uhabits.activities.tasks.TaskDetailBottomSheet
import org.isoron.uhabits.core.containers.session.DatasetCapabilities
import org.isoron.uhabits.core.containers.session.DatasetMode
import org.isoron.uhabits.core.userTasks.TaskId
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.regex.Pattern

/** Supplement an existing capture run; confirmation is cancelled, never accepted. */
@RunWith(AndroidJUnit4::class)
class UiReviewDialogCaptureTest {
    @Test fun captureValidationAndConfirmation() {
        val ins = InstrumentationRegistry.getInstrumentation()
        val context = ins.targetContext
        val device = UiDevice.getInstance(ins)
        val fixture = UiReviewCaptureTest.initializeFixture()
        val app = context.applicationContext as HabitsApplication
        ins.runOnMainSync { app.rebuildComponent(fixture.session.copy(mode = DatasetMode.PRODUCTION_NEXT,
            capabilities = DatasetCapabilities.PRODUCTION_NEXT.copy(syncEnabled = false, remoteCallsAllowed = false,
                backgroundJobsEnabled = false, widgetsEnabled = false, remindersEnabled = false))) }
        val component = HabitsApplication.component
        val output = File(context.getExternalFilesDir(null), "ui-review")
        val file = File(output, "capture-manifest.json")
        check(file.isFile) { "Run primary capture first" }
        val manifest = JSONObject(file.readText())
        val captures = manifest.getJSONArray("captures")
        var sequence = captures.length()
        fun capture(variant: String, group: String, route: String) {
            val directory = File(output, "$variant/$group").apply { mkdirs() }
            val stem = "%03d-%s".format(++sequence, route)
            device.waitForIdle()
            device.dumpWindowHierarchy(File(directory, "$stem.xml"))
            Thread.sleep(1000)
            check(device.currentPackageName == context.packageName)
            val png = File(directory, "$stem.png")
            check(device.takeScreenshot(png))
            captures.put(JSONObject().put("screen", group).put("route", route).put("theme", variant)
                .put("fontScale", if (variant == "large-font") 1.3 else 1.0).put("status", "captured")
                .put("image", png.relativeTo(output).invariantSeparatorsPath)
                .put("width", device.displayWidth).put("height", device.displayHeight)
                .put("stateSource", "Native empty-name error or asserted deletion confirmation; cancelled without mutation"))
            file.writeText(manifest.toString(2))
        }
        fun click(label: String) {
            val pattern = Pattern.compile(Pattern.quote(label), Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)
            checkNotNull(device.wait(Until.findObject(By.text(pattern)), 5000)).click()
            device.waitForIdle()
        }
        fun errors(view: View): List<EditText> = when (view) {
            is EditText -> listOf(view).filter { it.error != null }
            is ViewGroup -> (0 until view.childCount).flatMap { errors(view.getChildAt(it)) }
            else -> emptyList()
        }
        for ((variant, theme, scale) in listOf(Triple("light", 2, "1.0"), Triple("dark", 1, "1.0"), Triple("large-font", 2, "1.3"))) {
            component.preferences.theme = theme
            device.executeShellCommand("settings put system font_scale $scale")
            val containerCount = component.organizationFacade.containerQueries!!.roots().size
            ActivityScenario.launch<ContainersActivity>(ContainersActivity.intent(context)).use { scenario ->
                lateinit var dialog: CreateContainerDialog
                scenario.onActivity { activity ->
                    dialog = CreateContainerDialog().apply { containerQueries = component.organizationFacade.containerQueries }
                    dialog.show(activity.supportFragmentManager, "qa-validation")
                    activity.supportFragmentManager.executePendingTransactions()
                }
                click(context.getString(R.string.containers_create))
                ins.runOnMainSync { check(errors(dialog.requireDialog().window!!.decorView).isNotEmpty()) }
                check(component.organizationFacade.containerQueries!!.roots().size == containerCount)
                capture(variant, "containers", "empty-name-validation")
                device.pressBack()
            }
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
                val id = TaskId("qa-task-1")
                check(component.userTaskRepository.getById(id) != null)
                scenario.onActivity { TaskDetailBottomSheet.newInstance(id).show(it.supportFragmentManager, "qa-confirmation") }
                checkNotNull(device.wait(Until.findObject(By.res(Pattern.compile(".*:id/btnDetailMore"))), 5000)).click()
                click("Удалить задачу")
                check(device.wait(Until.hasObject(By.text("Удалить задачу?")), 5000))
                capture(variant, "tasks", "delete-confirmation-cancelled")
                device.pressBack()
                check(component.userTaskRepository.getById(id) != null)
                device.pressBack()
            }
        }
        device.executeShellCommand("settings put system font_scale 1.0")
    }
}
