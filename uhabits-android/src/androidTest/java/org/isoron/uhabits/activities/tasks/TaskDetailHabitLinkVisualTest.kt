/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.activities.tasks

import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.activities.main.MainActivity
import org.isoron.uhabits.activities.main.MainDestination
import org.isoron.uhabits.core.focus.FocusSession
import org.isoron.uhabits.core.focus.FocusSessionId
import org.isoron.uhabits.core.focus.FocusSessionSource
import org.isoron.uhabits.core.focus.sqlite.SQLiteFocusSessionRepository
import org.isoron.uhabits.core.models.Frequency
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitType
import org.isoron.uhabits.core.models.NumericalHabitType
import org.isoron.uhabits.core.models.PaletteColor
import org.isoron.uhabits.core.timer.TimerMode
import org.isoron.uhabits.core.ui.ThemeSwitcher
import org.isoron.uhabits.core.userTasks.TaskId
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.io.File
import java.util.UUID

/**
 * Visual acceptance for Task Detail Habit Link & Focus Time display.
 * Runs on disposable API 35 AVD via with-avd.ps1.
 */
@RunWith(AndroidJUnit4::class)
class TaskDetailHabitLinkVisualTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val args = InstrumentationRegistry.getArguments()
    private val context get() = instrumentation.targetContext
    private val component get() = HabitsApplication.component
    private val device = UiDevice.getInstance(instrumentation)
    private val harness = TaskDraftLifecycleTest()
    private val phase = args.getString("phase", "light")!!.also { check(it.matches(Regex("[a-zA-Z0-9_-]+"))) }
    private val output get() = File(context.getExternalFilesDir(null), "habit-link-qa").apply { check(mkdirs() || isDirectory) }

    companion object {
        const val TITLE_FOCUS = "Подготовить квартальный отчёт"
        const val TITLE_ZERO = "Оформить заявку на командировку"
        const val TITLE_LINKED_ACCRUAL = "Чтение технической документации"
        const val TITLE_LINKED_NO_ACCRUAL = "Проверка почты и планирование"
        const val TITLE_INTERACTIVE = "Интерактивная привязка к привычке"

        const val HABIT_WORK = "Глубокая работа"
        const val HABIT_ENGLISH = "Английский язык"
        const val HABIT_WATER = "Выпить воды"
        const val HABIT_EXERCISE = "Утренняя зарядка"
    }

    @Test
    fun habitLinkJourneys() {
        TaskListVisualTest::class.java.getDeclaredMethod("bootstrapDisposableSession")
            .apply { isAccessible = true }.invoke(TaskListVisualTest())
        harness.guard()

        val oldTheme = component.preferences.theme
        val oldBlack = component.preferences.isPureBlackEnabled

        try {
            component.preferences.theme = if (phase.startsWith("light") || phase.startsWith("narrow")) {
                ThemeSwitcher.THEME_LIGHT
            } else {
                ThemeSwitcher.THEME_DARK
            }
            component.preferences.isPureBlackEnabled = phase.startsWith("amoled")
            component.preferences.isFirstRun = false

            // Assert actual theme configuration
            when {
                phase.startsWith("light") -> {
                    assertEquals(ThemeSwitcher.THEME_LIGHT, component.preferences.theme)
                    assertFalse(component.preferences.isPureBlackEnabled)
                }
                phase.startsWith("dark") -> {
                    assertEquals(ThemeSwitcher.THEME_DARK, component.preferences.theme)
                    assertFalse(component.preferences.isPureBlackEnabled)
                }
                phase.startsWith("amoled") -> {
                    assertEquals(ThemeSwitcher.THEME_DARK, component.preferences.theme)
                    assertTrue(component.preferences.isPureBlackEnabled)
                }
                phase.startsWith("narrow") -> {
                    val fontScale = runCatching {
                        android.provider.Settings.System.getFloat(context.contentResolver, android.provider.Settings.System.FONT_SCALE)
                    }.getOrDefault(context.resources.configuration.fontScale)
                    assertTrue("font_scale should be elevated", fontScale >= 1.25f)
                }
            }

            device.wakeUp()
            device.executeShellCommand("wm dismiss-keyguard")
            device.executeShellCommand("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
            idle()

            seedData()

            val taskRepo = component.userTaskRepository
            val taskFocus = requireNotNull(taskRepo.getRoots().firstOrNull { it.title == TITLE_FOCUS })
            val taskZero = requireNotNull(taskRepo.getRoots().firstOrNull { it.title == TITLE_ZERO })
            val taskLinkedAccrual = requireNotNull(taskRepo.getRoots().firstOrNull { it.title == TITLE_LINKED_ACCRUAL })
            val taskLinkedNoAccrual = requireNotNull(taskRepo.getRoots().firstOrNull { it.title == TITLE_LINKED_NO_ACCRUAL })
            val taskInteractive = requireNotNull(taskRepo.getRoots().firstOrNull { it.title == TITLE_INTERACTIVE })

            QaActivityController().use { controller ->
                navigateToTasks(controller)

                when {
                    phase == "light-picker" -> {
                        openSheet(controller, taskFocus.id)
                        openHabitPicker()
                        assertPickerCancel()
                        capture("03-habit-selection-dialog-light")
                        device.pressBack()
                        idle()
                        dismissSheet(controller)
                    }
                    phase.startsWith("light") -> {
                        openSheet(controller, taskFocus.id)
                        assertFocusSummary("Затрачено: 1 ч 25 мин")
                        capture("01-task-focus-time")

                        openHabitPicker()
                        assertPickerCancel()
                        assertTrue("Work habit must be present in picker", device.hasObject(By.text("Глубокая работа (мин)")))
                        assertTrue("English habit must be present in picker", device.hasObject(By.text("Английский язык (минуты)")))
                        assertFalse("Water habit must be filtered out", device.hasObject(By.text("Выпить воды (мл)")))
                        capture("03-habit-selection-dialog")
                        device.pressBack()
                        idle()
                        assertNull("Cancel must leave the task unlinked", taskRepo.getById(taskFocus.id)?.linkedHabitUuid)
                        dismissSheet(controller)

                        openSheet(controller, taskZero.id)
                        val focusRepo = SQLiteFocusSessionRepository(component.db)
                        assertEquals("Zero-time task has no recorded focus", 0L, focusRepo.getTotalDurationByTask(taskZero.id))
                        assertFocusSummary("Запустить сессию фокуса")
                        capture("02-task-zero-time")

                        // Refresh the open editor after a committed Pomodoro focus fact.
                        val pomodoroNow = System.currentTimeMillis()
                        focusRepo.record(
                            FocusSession(
                                id = FocusSessionId("fs-pomodoro-live-${UUID.randomUUID()}"),
                                startedAt = pomodoroNow - 65_000L,
                                endedAt = pomodoroNow,
                                durationMs = 65_000L,
                                taskId = taskZero.id,
                                source = FocusSessionSource.TIMER,
                                createdAt = pomodoroNow,
                                updatedAt = pomodoroNow
                            )
                        )
                        instrumentation.runOnMainSync { component.focusCreditController?.notifyChanged() }
                        assertFocusSummary("Затрачено: 1 мин")
                        capture("02b-pomodoro-live-summary-updated")
                        dismissSheet(controller)

                        openSheet(controller, taskLinkedAccrual.id)
                        assertLinkedHabit(HABIT_WORK, true)
                        capture("04-linked-habit-accrual-enabled")

                        controller.recreate()
                        idle()
                        controller.onActivity { assertNotNull("Detail must survive recreation", sheet(it)) }
                        assertFocusSummary("Затрачено: 50 мин")
                        assertLinkedHabit(HABIT_WORK, true)
                        capture("07-sheet-after-recreate")
                        dismissSheet(controller)

                        openSheet(controller, taskLinkedNoAccrual.id)
                        assertLinkedHabit(HABIT_ENGLISH, false)
                        capture("05-linked-habit-accrual-disabled")
                        dismissSheet(controller)

                        openSheet(controller, taskInteractive.id)
                        openHabitPicker()
                        requireNotNull(device.wait(Until.findObject(By.text("Глубокая работа (мин)")), 5_000)).click()
                        assertLinkedHabit(HABIT_WORK, true)
                        val workHabit = component.habitList.first { it.name == HABIT_WORK }
                        await { taskRepo.getById(taskInteractive.id)?.linkedHabitUuid == workHabit.uuid }
                        val originalBoundary = requireNotNull(taskRepo.getById(taskInteractive.id)?.habitLinkEffectiveFrom)
                        capture("06-interactive-linked-after-picker")

                        device.revealTaskDetailControl("taskDetailHabitAccrual").click()
                        assertLinkedHabit(HABIT_WORK, false)
                        await { taskRepo.getById(taskInteractive.id)?.habitTimeAccrualEnabled == false }
                        device.revealTaskDetailControl("taskDetailHabitAccrual").click()
                        assertLinkedHabit(HABIT_WORK, true)
                        await { taskRepo.getById(taskInteractive.id)?.habitTimeAccrualEnabled == true }
                        assertTrue(requireNotNull(taskRepo.getById(taskInteractive.id)?.habitLinkEffectiveFrom) >= originalBoundary)

                        device.revealTaskDetailControl("taskDetailHabitUnlink").click()
                        device.revealTaskDetailControl("taskDetailHabitLink")
                        assertFalse(device.hasObject(By.res("taskDetailLinkedHabit")))
                        await {
                            val task = requireNotNull(taskRepo.getById(taskInteractive.id))
                            task.linkedHabitUuid == null && !task.habitTimeAccrualEnabled && task.habitLinkEffectiveFrom == null
                        }
                        dismissSheet(controller)
                    }
                    phase.startsWith("dark") -> {
                        openSheet(controller, taskLinkedAccrual.id)
                        assertLinkedHabit(HABIT_WORK, true)
                        capture("08-theme-dark-linked")
                        dismissSheet(controller)

                        openSheet(controller, taskFocus.id)
                        assertFocusSummary("Затрачено: 1 ч 25 мин")
                        capture("09-theme-dark-focus-time")
                        openHabitPicker()
                        capture("10-theme-dark-dialog")
                        device.pressBack()
                        idle()
                        dismissSheet(controller)
                    }
                    phase.startsWith("amoled") -> {
                        openSheet(controller, taskLinkedAccrual.id)
                        assertLinkedHabit(HABIT_WORK, true)
                        capture("11-theme-amoled-linked")
                        dismissSheet(controller)

                        openSheet(controller, taskFocus.id)
                        openHabitPicker()
                        capture("12-theme-amoled-dialog")
                        device.pressBack()
                        idle()
                        dismissSheet(controller)
                    }
                    phase.startsWith("narrow") -> {
                        controller.onActivity { activity ->
                            val configuration = activity.resources.configuration
                            assertTrue("Activity font scale must be >= 1.25f", configuration.fontScale >= 1.25f)
                            args.getString("expectedWidthDp")?.toInt()?.let { expected ->
                                assertEquals("Compact viewport width in dp", expected, configuration.screenWidthDp)
                            }
                        }
                        openSheet(controller, taskLinkedAccrual.id)
                        assertLinkedHabit(HABIT_WORK, true)
                        capture("13-narrow-font13-linked")
                        dismissSheet(controller)

                        openSheet(controller, taskFocus.id)
                        assertFocusSummary("Затрачено: 1 ч 25 мин")
                        capture("14-narrow-font13-focus-time")
                        openHabitPicker()
                        capture("15-narrow-font13-dialog")
                        device.pressBack()
                        idle()
                        dismissSheet(controller)
                    }
                }
            }
        } finally {
            instrumentation.runOnMainSync {
                component.preferences.theme = oldTheme
                component.preferences.isPureBlackEnabled = oldBlack
            }
        }
    }

    private fun seedData() {
        val habitList = component.habitList
        val factory = component.sqlModelFactory

        var habitWork = habitList.firstOrNull { it.name == HABIT_WORK }
        if (habitWork == null) {
            habitWork = factory.buildHabit().apply {
                uuid = UUID.randomUUID().toString()
                name = HABIT_WORK
                type = HabitType.NUMERICAL
                targetType = NumericalHabitType.AT_LEAST
                targetValue = 60.0
                unit = "мин"
                color = PaletteColor(1) // Blue
                frequency = Frequency.DAILY
            }
            habitList.add(habitWork)
        }

        var habitEnglish = habitList.firstOrNull { it.name == HABIT_ENGLISH }
        if (habitEnglish == null) {
            habitEnglish = factory.buildHabit().apply {
                uuid = UUID.randomUUID().toString()
                name = HABIT_ENGLISH
                type = HabitType.NUMERICAL
                targetType = NumericalHabitType.AT_LEAST
                targetValue = 30.0
                unit = "минуты"
                color = PaletteColor(3) // Green
                frequency = Frequency.DAILY
            }
            habitList.add(habitEnglish)
        }

        var habitWater = habitList.firstOrNull { it.name == HABIT_WATER }
        if (habitWater == null) {
            habitWater = factory.buildHabit().apply {
                uuid = UUID.randomUUID().toString()
                name = HABIT_WATER
                type = HabitType.NUMERICAL
                targetType = NumericalHabitType.AT_LEAST
                targetValue = 2000.0
                unit = "мл"
                color = PaletteColor(4)
                frequency = Frequency.DAILY
            }
            habitList.add(habitWater)
        }

        var habitExercise = habitList.firstOrNull { it.name == HABIT_EXERCISE }
        if (habitExercise == null) {
            habitExercise = factory.buildHabit().apply {
                uuid = UUID.randomUUID().toString()
                name = HABIT_EXERCISE
                type = HabitType.YES_NO
                color = PaletteColor(2)
                frequency = Frequency.DAILY
            }
            habitList.add(habitExercise)
        }

        val taskRepo = component.userTaskRepository
        val taskService = component.userTaskService
        val now = System.currentTimeMillis()

        var taskFocus = taskRepo.getRoots().firstOrNull { it.title == TITLE_FOCUS }
        if (taskFocus == null) {
            taskFocus = taskService.createTask(TITLE_FOCUS)
            val focusRepo = SQLiteFocusSessionRepository(component.db)
            focusRepo.record(
                FocusSession(
                    id = FocusSessionId("fs-focus-85min"),
                    startedAt = now - 5_100_000L,
                    endedAt = now,
                    durationMs = 5_100_000L,
                    taskId = taskFocus.id,
                    source = FocusSessionSource.TIMER,
                    createdAt = now,
                    updatedAt = now
                )
            )
        }

        var taskZero = taskRepo.getRoots().firstOrNull { it.title == TITLE_ZERO }
        if (taskZero == null) {
            taskZero = taskService.createTask(TITLE_ZERO)
        }

        var taskLinkedAccrual = taskRepo.getRoots().firstOrNull { it.title == TITLE_LINKED_ACCRUAL }
        if (taskLinkedAccrual == null) {
            taskLinkedAccrual = taskService.createTask(
                TITLE_LINKED_ACCRUAL,
                linkedHabitUuid = habitWork.uuid,
                habitTimeAccrualEnabled = true,
                habitLinkEffectiveFrom = now - 86400000L
            )
            val focusRepo = SQLiteFocusSessionRepository(component.db)
            focusRepo.record(
                FocusSession(
                    id = FocusSessionId("fs-focus-50min"),
                    startedAt = now - 3_000_000L,
                    endedAt = now,
                    durationMs = 3_000_000L,
                    taskId = taskLinkedAccrual.id,
                    source = FocusSessionSource.TIMER,
                    createdAt = now,
                    updatedAt = now
                )
            )
        }

        var taskLinkedNoAccrual = taskRepo.getRoots().firstOrNull { it.title == TITLE_LINKED_NO_ACCRUAL }
        if (taskLinkedNoAccrual == null) {
            taskLinkedNoAccrual = taskService.createTask(
                TITLE_LINKED_NO_ACCRUAL,
                linkedHabitUuid = habitEnglish.uuid,
                habitTimeAccrualEnabled = false,
                habitLinkEffectiveFrom = null
            )
        }

        var taskInteractive = taskRepo.getRoots().firstOrNull { it.title == TITLE_INTERACTIVE }
        if (taskInteractive == null) {
            taskInteractive = taskService.createTask(TITLE_INTERACTIVE)
        }
    }

    private fun tasks(activity: MainActivity) =
        activity.supportFragmentManager.findFragmentByTag("main.tasks") as TasksFragment

    private fun sheet(activity: MainActivity, tag: String = "visual-task-detail") =
        tasks(activity).childFragmentManager.findFragmentByTag(tag) as? TaskDetailBottomSheet

    private fun navigateToTasks(controller: QaActivityController) {
        controller.onActivity { activity ->
            activity.navigate(MainDestination.TASKS)
            activity.supportFragmentManager.executePendingTransactions()
        }
        idle()
    }

    private fun openSheet(controller: QaActivityController, taskId: TaskId, tag: String = "visual-task-detail") {
        controller.onActivity { activity ->
            val existing = tasks(activity).childFragmentManager.findFragmentByTag(tag)
            if (existing is TaskDetailBottomSheet) {
                existing.dismiss()
                tasks(activity).childFragmentManager.executePendingTransactions()
            }
            TaskDetailBottomSheet.newInstance(taskId).show(tasks(activity).childFragmentManager, tag)
            tasks(activity).childFragmentManager.executePendingTransactions()
        }
        device.revealTaskDetailControl("taskDetailFocusSummary")
    }

    private fun assertFocusSummary(expected: String) {
        device.revealTaskDetailControl("taskDetailFocusSummary")
        assertTrue("Expected focus summary: $expected", device.wait(
            Until.hasObject(By.res("taskDetailFocusSummary").text(expected)),
            5_000,
        ))
    }

    private fun openHabitPicker() {
        device.revealTaskDetailControl("taskDetailHabitLink").click()
        assertTrue("Habit picker must open", device.wait(Until.hasObject(By.text("Выберите привычку")), 5_000))
    }

    private fun assertPickerCancel() {
        val cancel = requireNotNull(device.findObject(By.res("android:id/button2")))
        assertTrue("Picker cancel must be localized, actual='${cancel.text}'", cancel.text.equals("Отмена", ignoreCase = true))
    }

    private fun assertLinkedHabit(expected: String, accrual: Boolean) {
        val title = device.revealTaskDetailControl("taskDetailLinkedHabit", Direction.UP)
        assertEquals(expected, title.text)
        device.revealTaskDetailControl("taskDetailHabitAccrualSummary")
        val expectedSummary = if (accrual) "Учёт времени включён" else "Без начисления"
        assertTrue("Linked habit must show accrual state", device.wait(
            Until.hasObject(By.res("taskDetailHabitAccrualSummary").textContains(expectedSummary)),
            5_000,
        ))
        val toggle = device.revealTaskDetailControl("taskDetailHabitAccrual")
        assertTrue("Accrual must expose a checkable switch", toggle.isCheckable)
        assertEquals("Accrual switch state", accrual, toggle.isChecked)
    }

    private fun dismissSheet(controller: QaActivityController, tag: String = "visual-task-detail") {
        controller.onActivity { activity ->
            val s = sheet(activity, tag)
            s?.dismiss()
            tasks(activity).childFragmentManager.executePendingTransactions()
        }
        idle()
        await {
            var dismissed = false
            controller.onActivity { activity ->
                tasks(activity).childFragmentManager.executePendingTransactions()
                dismissed = sheet(activity, tag) == null
            }
            dismissed
        }
    }

    private fun idle() {
        instrumentation.waitForIdleSync()
        device.waitForIdle()
        SystemClock.sleep(250)
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 8_000
        while (SystemClock.uptimeMillis() < deadline) {
            idle()
            if (condition()) return
            SystemClock.sleep(50)
        }
        error("Condition not met within 8 seconds")
    }

    private fun capture(name: String) {
        idle()
        SystemClock.sleep(300)
        val file = File(output, "$name.png")
        check(device.takeScreenshot(file)) { "Failed to take screenshot: ${file.absolutePath}" }
    }

    internal inner class QaActivityController : Closeable {
        init {
            instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            awaitResumed()
        }

        fun onActivity(action: (MainActivity) -> Unit) {
            awaitResumed()
            instrumentation.runOnMainSync {
                val current = resumed()
                check(current.isNotEmpty()) { "Expected at least one resumed MainActivity" }
                action(current.first())
            }
        }

        fun recreate() {
            var previous: MainActivity? = null
            onActivity { activity -> previous = activity; activity.recreate() }
            awaitResumed(previous)
        }

        private fun resumed(): List<MainActivity> = ActivityLifecycleMonitorRegistry.getInstance()
            .getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>()
            .filter { !it.isFinishing && !it.isDestroyed }

        private fun awaitResumed(previous: MainActivity? = null) {
            val deadline = SystemClock.uptimeMillis() + 20_000
            while (SystemClock.uptimeMillis() < deadline) {
                var ready = false
                instrumentation.runOnMainSync {
                    val current = resumed()
                    ready = current.isNotEmpty() && current.first() !== previous
                }
                if (ready) return
                device.wakeUp()
                device.executeShellCommand("wm dismiss-keyguard")
                SystemClock.sleep(100)
            }
            error("No current resumed MainActivity appeared within 20s")
        }

        override fun close() {
            instrumentation.runOnMainSync {
                Stage.values().flatMap { stage ->
                    ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage)
                }.filterIsInstance<MainActivity>().distinct().forEach {
                    if (!it.isFinishing) it.finish()
                }
            }
        }
    }
}
