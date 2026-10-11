package org.isoron.uhabits.qa

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.isoron.uhabits.activities.main.MainActivity
import org.isoron.uhabits.activities.main.MainDestination
import org.isoron.uhabits.activities.habits.edit.EditHabitActivity
import org.isoron.uhabits.activities.containers.ContainersActivity
import org.isoron.uhabits.activities.containers.CreateContainerDialog
import org.isoron.uhabits.activities.tasks.TaskDetailBottomSheet
import org.isoron.uhabits.core.userTasks.TaskId
import org.isoron.uhabits.inject.HabitsApplicationComponent
import org.isoron.uhabits.activities.settings.SettingsSectionFragment
import org.isoron.uhabits.activities.settings.SettingsSectionId
import org.isoron.uhabits.activities.containers.statistics.ContainerStatisticsActivity
import org.isoron.uhabits.activities.today.TodayActivity
import org.isoron.uhabits.R

/** Additional real rendered states; all mutations remain inside the verified disposable fixture. */
object UiReviewExtraCoverage {
    fun capture(scenario: ActivityScenario<MainActivity>, component: HabitsApplicationComponent,
                capture: (String, String) -> Unit, attempt: (String, String, () -> Unit) -> Unit) {
        val ins = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(ins)
        val context = ins.targetContext
        fun clickId(id: String) {
            val view = device.wait(Until.findObject(By.res(context.packageName, id)), 5000)
                ?: error("Missing rendered control: $id")
            view.click(); device.waitForIdle()
        }
        attempt("habits", "editing") {
            val habit = component.habitList.getByPosition(0)
            scenario.onActivity { it.startActivity(Intent(context, EditHabitActivity::class.java).putExtra("habitId", habit.id!!)) }
            run {
                capture("habits", "editing")
                device.pressBack()
            }
        }
        attempt("containers", "overview-create-edit") {
            scenario.onActivity { it.startActivity(ContainersActivity.intent(context)) }
            device.waitForIdle()
            run {
                capture("containers", "overview")
                ins.runOnMainSync {
                    val activity = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<ContainersActivity>().single()
                    CreateContainerDialog().apply {
                        containerQueries = component.organizationFacade.containerQueries
                    }.show(activity.supportFragmentManager, "capture-create")
                }
                capture("containers", "creation")
                device.pressBack()
                ins.runOnMainSync {
                    val activity = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<ContainersActivity>().single()
                    val existing = component.organizationFacade.containerQueries!!.roots().first()
                    CreateContainerDialog().apply {
                        containerToEdit = existing
                        containerQueries = component.organizationFacade.containerQueries
                    }.show(activity.supportFragmentManager, "capture-edit")
                }
                capture("containers", "editing")
                device.pressBack(); device.pressBack()
            }
        }
        scenario.onActivity { it.navigate(MainDestination.TASKS) }
        attempt("tasks", "calendar-day-overview") {
            val toggle = device.wait(Until.findObject(By.desc("Показать календарь")), 5000)
                ?: error("Calendar representation selector unavailable")
            toggle.click(); capture("tasks", "calendar-day-overview")
            device.findObject(By.desc("Показать список"))?.click()
        }
        attempt("tasks", "day-overview") {
            scenario.onActivity { it.startActivity(TodayActivity.createIntent(context)) }
            run {
                capture("tasks", "day-overview")
                device.pressBack()
            }
        }
        attempt("tasks", "completion-reopening-subtasks") {
            scenario.onActivity {
                TaskDetailBottomSheet.newInstance(TaskId("qa-task-1"))
                    .show(it.supportFragmentManager, "capture-completion")
            }
            device.waitForIdle()
            clickId("btnDetailCheck"); capture("tasks", "completion-toggled")
            clickId("btnDetailCheck"); capture("tasks", "completion-restored")
            clickId("btnSubtaskCheck"); capture("tasks", "subtask-completion-toggled")
            clickId("btnSubtaskCheck"); capture("tasks", "subtask-completion-restored")
            clickId("btnDetailMore"); capture("tasks", "detail-overflow")
            device.pressBack(); device.pressBack()
        }
        attempt("containers", "focus-time-summary") {
            scenario.onActivity {
                ContainerStatisticsActivity.start(it, component.organizationFacade.containerQueries!!.roots().first().id)
            }
            capture("containers", "focus-time-summary")
            device.pressBack()
        }
        scenario.onActivity { it.navigate(MainDestination.STATISTICS) }
        attempt("statistics", "date-range-navigation") {
            clickId("btnPrev"); capture("statistics", "previous-period")
            clickId("btnNext"); capture("statistics", "current-period")
        }
        attempt("statistics", "filters") {
            clickId("btnFilters"); capture("statistics", "filters")
            device.pressBack()
        }
        attempt("statistics", "scope-selector") {
            clickId("scopeSelectorContainer"); capture("statistics", "scope-selector")
            device.pressBack()
        }
        scenario.onActivity { it.navigate(MainDestination.SETTINGS) }
        fun section(id: SettingsSectionId) {
            scenario.onActivity { activity ->
                val host = activity.supportFragmentManager.fragments.filterIsInstance<SettingsSectionFragment>().first()
                host.openSettingsSection(id)
                host.childFragmentManager.executePendingTransactions()
            }
        }
        attempt("settings", "restore-picker-empty") {
            section(SettingsSectionId.DATA)
            val restore = device.wait(Until.findObject(By.text(context.getString(R.string.restore_backup))), 5000)
                ?: error("Restore preference unavailable")
            restore.click(); capture("settings", "restore-picker-empty")
            device.pressBack(); device.pressBack()
        }
    }
}
