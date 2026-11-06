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
import org.isoron.uhabits.activities.statistics.StatisticsActivity
import org.isoron.uhabits.activities.today.TodayActivity
import org.isoron.uhabits.activities.habits.edit.HabitTypeDialog
import org.isoron.uhabits.activities.habits.show.ShowHabitActivity
import org.isoron.uhabits.activities.blocks.ManageBlocksActivity
import org.isoron.uhabits.activities.about.AboutActivity
import org.isoron.uhabits.activities.statistics.UnifiedStatisticsFragment
import org.isoron.uhabits.activities.statistics.UnifiedStatisticsDrilldownBottomSheet
import org.isoron.uhabits.activities.statistics.TasksDrilldownDescriptor
import org.isoron.uhabits.activities.statistics.FocusDrilldownDescriptor
import org.isoron.uhabits.core.models.HabitType
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
        fun clickText(label: String) {
            QaEmulatorUi.clearSystemUiError()
            val pattern = java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(label),
                java.util.regex.Pattern.CASE_INSENSITIVE or java.util.regex.Pattern.UNICODE_CASE)
            val target = device.wait(Until.findObject(By.text(pattern)), 5000)
                ?: device.findObject(By.desc(pattern))
                ?: device.findObject(By.descContains(label))
                ?: error("No text selector: $label")
            target.click(); device.waitForIdle()
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
            val toggle = device.wait(Until.findObject(By.descContains("Представление")), 5000)
                ?: error("Calendar representation selector unavailable")
            toggle.click(); device.waitForIdle()
            clickText("Календарь")
            device.waitForIdle()
            capture("tasks", "calendar-day-overview")
            val returnToggle = device.wait(Until.findObject(By.descContains("Представление")), 5000)
            returnToggle?.click(); device.waitForIdle()
            clickText("Список")
            device.waitForIdle()
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
        device.waitForIdle()
        attempt("statistics", "date-range-navigation") {
            val prev = device.wait(Until.findObject(By.descContains("Previous period")), 5000)
                ?: device.findObject(By.descContains("Предыдущий"))
                ?: error("Previous period button unavailable")
            prev.click(); device.waitForIdle()
            capture("statistics", "previous-period")
            val next = device.wait(Until.findObject(By.descContains("Next period")), 5000)
                ?: device.findObject(By.descContains("Следующий"))
                ?: error("Next period button unavailable")
            next.click(); device.waitForIdle()
            capture("statistics", "current-period")
        }
        attempt("statistics", "filters") {
            val scopeBtn = device.wait(Until.findObject(By.descContains("sections")), 5000)
                ?: device.findObject(By.descContains("раздел"))
                ?: device.findObject(By.textContains("SECTIONS"))
                ?: device.findObject(By.textContains("РАЗДЕЛ"))
                ?: error("Scope/Filter selector unavailable")
            scopeBtn.click(); device.waitForIdle()
            capture("statistics", "filters")
            device.pressBack(); device.waitForIdle()
        }
        attempt("statistics", "scope-selector") {
            val periodBtn = device.wait(Until.findObject(By.text("WEEK")), 5000)
                ?: device.findObject(By.text("НЕДЕЛЯ"))
                ?: device.findObject(By.textContains("Week"))
                ?: device.findObject(By.textContains("Недел"))
                ?: error("Period selector button unavailable")
            periodBtn.click(); device.waitForIdle()
            capture("statistics", "scope-selector")
            device.pressBack(); device.waitForIdle()
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
        // --- Area 1: Habit Creation Dialog & Forms ---
        attempt("habits", "type-selector-dialog") {
            scenario.onActivity {
                HabitTypeDialog.newInstance().show(it.supportFragmentManager, "capture-habit-type")
            }
            device.waitForIdle()
            capture("habits", "type-selector-dialog")
            device.pressBack(); device.waitForIdle()
        }
        attempt("habits", "creation-boolean") {
            scenario.onActivity {
                it.startActivity(Intent(context, EditHabitActivity::class.java).putExtra("habitType", HabitType.YES_NO.value))
            }
            device.waitForIdle()
            capture("habits", "creation-boolean")
            device.pressBack(); device.waitForIdle()
        }
        attempt("habits", "creation-numerical") {
            scenario.onActivity {
                it.startActivity(Intent(context, EditHabitActivity::class.java).putExtra("habitType", HabitType.NUMERICAL.value))
            }
            device.waitForIdle()
            capture("habits", "creation-numerical")
            device.pressBack(); device.waitForIdle()
        }
        val numHabit = component.habitList.find { it.type == HabitType.NUMERICAL }
        if (numHabit != null) {
            attempt("habits", "numerical-details") {
                scenario.onActivity {
                    it.startActivity(Intent(context, ShowHabitActivity::class.java).putExtra("habitId", numHabit.id!!))
                }
                device.waitForIdle()
                capture("habits", "numerical-details")
                device.swipe(600, 1700, 600, 650, 20)
                device.waitForIdle()
                capture("habits", "numerical-history-charts")
                device.pressBack(); device.waitForIdle()
            }
            attempt("habits", "editing-pickers") {
                scenario.onActivity {
                    it.startActivity(Intent(context, EditHabitActivity::class.java).putExtra("habitId", numHabit.id!!))
                }
                device.waitForIdle()
                try {
                    clickId("colorButton"); capture("habits", "color-picker-dialog"); device.pressBack()
                    clickId("numericalFrequencyPicker"); capture("habits", "frequency-picker-dialog"); device.pressBack()
                    clickId("targetTypePicker"); capture("habits", "target-type-dialog"); device.pressBack()
                    clickId("dayTierPicker"); capture("habits", "day-tier-dialog"); device.pressBack()
                    clickId("habitBlockPicker"); capture("habits", "sphere-picker-dialog"); device.pressBack()
                } finally {
                    device.pressBack(); device.waitForIdle()
                }
            }
        }
        attempt("habits", "manage-spheres") {
            scenario.onActivity {
                it.startActivity(Intent(context, ManageBlocksActivity::class.java))
            }
            device.waitForIdle()
            capture("habits", "manage-spheres")
            device.pressBack(); device.waitForIdle()
        }

        // --- Area 2: Tasks Extended States ---
        scenario.onActivity { it.navigate(MainDestination.TASKS) }
        device.waitForIdle()
        attempt("tasks", "matrix-view") {
            val toggle = device.wait(Until.findObject(By.descContains("Представление")), 5000)
                ?: error("Matrix representation selector unavailable")
            toggle.click(); device.waitForIdle()
            clickText("Матрица")
            device.waitForIdle()
            capture("tasks", "matrix-view")
            val returnToggle = device.wait(Until.findObject(By.descContains("Представление")), 5000)
            returnToggle?.click(); device.waitForIdle()
            clickText("Список")
            device.waitForIdle()
        }
        attempt("tasks", "tag-filter-sheet") {
            clickId("tvTasksTagFilter")
            capture("tasks", "tag-filter-sheet")
            device.pressBack(); device.waitForIdle()
        }
        attempt("tasks", "list-overflow-menu") {
            clickId("btnTasksOverflow")
            capture("tasks", "list-overflow-menu")
            device.pressBack(); device.waitForIdle()
        }
        attempt("tasks", "selection-mode") {
            val firstTask = device.findObject(By.res(context.packageName, "tvTaskTitle"))
            firstTask?.longClick(); device.waitForIdle()
            capture("tasks", "selection-mode")
            val closeSelection = device.findObject(By.res(context.packageName, "btnSelectionClose"))
            closeSelection?.click(); device.waitForIdle()
        }

        // --- Area 3: Unified Statistics Tabs & Drilldowns ---
        scenario.onActivity { it.navigate(MainDestination.STATISTICS) }
        device.waitForIdle()
        attempt("statistics", "tab-habits") {
            val tab = device.findObject(By.text("HABITS")) ?: device.findObject(By.text("ПРИВЫЧКИ"))
            tab?.click(); device.waitForIdle()
            capture("statistics", "tab-habits")
        }
        attempt("statistics", "tab-tasks") {
            val tab = device.findObject(By.text("TASKS")) ?: device.findObject(By.text("ЗАДАЧИ"))
            tab?.click(); device.waitForIdle()
            capture("statistics", "tab-tasks")
        }
        attempt("statistics", "tab-focus") {
            val tab = device.findObject(By.text("FOCUS")) ?: device.findObject(By.text("ФОКУС"))
            tab?.click(); device.waitForIdle()
            capture("statistics", "tab-focus")
            val overview = device.findObject(By.text("OVERVIEW")) ?: device.findObject(By.text("ОБЗОР"))
            overview?.click(); device.waitForIdle()
        }
        attempt("statistics", "drilldown-tasks") {
            scenario.onActivity { activity ->
                val fragment = activity.supportFragmentManager.fragments.firstOrNull { it is UnifiedStatisticsFragment }
                    as? UnifiedStatisticsFragment
                val report = fragment?.activeReport
                val tasks = report?.tasks
                if (fragment != null && report != null && tasks != null) {
                    UnifiedStatisticsDrilldownBottomSheet.showForTasks(
                        fragment.childFragmentManager,
                        "Задачи",
                        "Завершено: ${tasks.completedCount}",
                        TasksDrilldownDescriptor.CompletedRange(
                            report.context.from, report.context.to, "Задачи"
                        )
                    )
                }
            }
            device.waitForIdle()
            capture("statistics", "drilldown-tasks")
            device.pressBack(); device.waitForIdle()
        }
        attempt("statistics", "drilldown-focus") {
            scenario.onActivity { activity ->
                val fragment = activity.supportFragmentManager.fragments.firstOrNull { it is UnifiedStatisticsFragment }
                    as? UnifiedStatisticsFragment
                val report = fragment?.activeReport
                val focus = report?.focus
                if (fragment != null && report != null && focus != null) {
                    UnifiedStatisticsDrilldownBottomSheet.showForFocus(
                        fragment.childFragmentManager,
                        "Сессии фокуса",
                        "Сессий: ${focus.sessionCount}",
                        FocusDrilldownDescriptor.SessionsRange(
                            report.context.from, report.context.to, "Сессии"
                        )
                    )
                }
            }
            device.waitForIdle()
            capture("statistics", "drilldown-focus")
            device.pressBack(); device.waitForIdle()
        }

        // --- Area 4: Settings Dialogs & Screens ---
        scenario.onActivity { it.navigate(MainDestination.SETTINGS) }
        device.waitForIdle()
        attempt("settings", "dialog-first-weekday") {
            section(SettingsSectionId.APPEARANCE)
            clickText(context.getString(R.string.first_day_of_the_week))
            capture("settings", "dialog-first-weekday")
            device.pressBack()
            device.pressBack(); device.waitForIdle()
        }
        attempt("settings", "dialog-card-rounding") {
            section(SettingsSectionId.APPEARANCE)
            clickText(context.getString(R.string.pref_card_rounding_title))
            capture("settings", "dialog-card-rounding")
            device.pressBack()
            device.pressBack(); device.waitForIdle()
        }
        attempt("settings", "dialog-day-tier-order") {
            section(SettingsSectionId.HABITS)
            clickText(context.getString(R.string.pref_day_tier_sort_order_title))
            capture("settings", "dialog-day-tier-order")
            device.pressBack()
            device.pressBack(); device.waitForIdle()
        }
        attempt("settings", "dialog-pomodoro-focus") {
            section(SettingsSectionId.POMODORO)
            clickText(context.getString(R.string.pref_pomodoro_default_focus_title))
            capture("settings", "dialog-pomodoro-focus")
            device.pressBack()
            device.pressBack(); device.waitForIdle()
        }
        attempt("settings", "about-screen") {
            scenario.onActivity { it.startActivity(Intent(context, AboutActivity::class.java)) }
            device.waitForIdle()
            capture("settings", "about-screen")
            device.pressBack(); device.waitForIdle()
        }
    }
}
