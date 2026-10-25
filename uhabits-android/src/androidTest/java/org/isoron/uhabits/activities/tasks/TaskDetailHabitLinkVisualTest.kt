/*
 * Copyright (C) 2016-2026 Álinson Santos Xavier <git@axavier.org> and contributors
 *
 * This file is part of Loop Habit Tracker.
 *
 * Loop Habit Tracker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * Loop Habit Tracker is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.isoron.uhabits.activities.tasks

import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.CompoundButton
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
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
                        val sFocus = openSheet(controller, taskFocus.id)
                        controller.onActivity {
                            sFocus.requireView().findViewById<View>(R.id.btnHabitLinkAction).performClick()
                        }
                        idle()
                        device.wait(Until.hasObject(By.text("Выберите привычку")), 4000)
                        val btnNegative = device.findObject(By.res("android:id/button2"))
                        assertNotNull("Negative button must exist in picker dialog", btnNegative)
                        assertTrue(
                            "Negative button must display localized 'Отмена', actual='${btnNegative.text}'",
                            btnNegative.text.equals("Отмена", ignoreCase = true)
                        )
                        capture("03-habit-selection-dialog-light")
                        device.pressBack()
                        idle()
                        dismissSheet(controller)
                    }
                    phase.startsWith("light") -> {
                        // 1. Task with focus time: Затрачено: 1 ч 25 мин
                        val sFocus = openSheet(controller, taskFocus.id)
                        controller.onActivity {
                            val tvFocus = sFocus.requireView().findViewById<TextView>(R.id.tvDetailFocus)
                            assertEquals("Затрачено: 1 ч 25 мин", tvFocus.text.toString())
                        }
                        capture("01-task-focus-time")

                        // 3. Habit selection picker dialog inspection
                        controller.onActivity {
                            sFocus.requireView().findViewById<View>(R.id.btnHabitLinkAction).performClick()
                        }
                        idle()
                        var pickerShown = device.wait(Until.hasObject(By.text("Выберите привычку")), 3000)
                        if (!pickerShown) {
                            device.findObject(By.text("Привязать"))?.click()
                            idle()
                            pickerShown = device.wait(Until.hasObject(By.text("Выберите привычку")), 5000)
                        }
                        assertTrue("Picker dialog must be shown", pickerShown)
                        val btnNegative = device.findObject(By.res("android:id/button2"))
                        assertNotNull("Negative button must exist in picker dialog", btnNegative)
                        assertTrue(
                            "Negative button must display localized 'Отмена', actual='${btnNegative.text}'",
                            btnNegative.text.equals("Отмена", ignoreCase = true)
                        )
                        assertTrue("Work habit must be present in picker", device.hasObject(By.text("Глубокая работа (мин)")))
                        assertTrue("English habit must be present in picker", device.hasObject(By.text("Английский язык (минуты)")))
                        assertFalse("Water habit must be filtered out", device.hasObject(By.text("Выпить воды (мл)")))
                        capture("03-habit-selection-dialog")
                        device.pressBack()
                        idle()
                        dismissSheet(controller)

                        // 2. Task with 0 time: Затрачено: 0 мин
                        val sZero = openSheet(controller, taskZero.id)
                        controller.onActivity {
                            val tvFocus = sZero.requireView().findViewById<TextView>(R.id.tvDetailFocus)
                            assertEquals("Затрачено: 0 мин", tvFocus.text.toString())
                        }
                        capture("02-task-zero-time")

                        // Verify committed Pomodoro focus fact updates visible summary via FocusCreditController observer
                        val focusRepo = SQLiteFocusSessionRepository(component.db)
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
                        instrumentation.runOnMainSync {
                            component.focusCreditController?.notifyChanged()
                        }
                        idle()
                        await {
                            var updated = false
                            controller.onActivity {
                                val tvFocus = sZero.requireView().findViewById<TextView>(R.id.tvDetailFocus)
                                val text = tvFocus.text.toString()
                                updated = text == "Затрачено: 1 мин"
                            }
                            updated
                        }
                        controller.onActivity {
                            val tvFocus = sZero.requireView().findViewById<TextView>(R.id.tvDetailFocus)
                            assertEquals("Затрачено: 1 мин", tvFocus.text.toString())
                        }
                        capture("02b-pomodoro-live-summary-updated")
                        dismissSheet(controller)

                        // 4. Linked habit with accrual enabled
                        val sAccrual = openSheet(controller, taskLinkedAccrual.id)
                        controller.onActivity {
                            val tvTitle = sAccrual.requireView().findViewById<TextView>(R.id.tvHabitLinkTitle)
                            val tvSub = sAccrual.requireView().findViewById<TextView>(R.id.tvHabitLinkSubtitle)
                            val sw = sAccrual.requireView().findViewById<CompoundButton>(R.id.switchHabitAccrual)
                            assertEquals("Глубокая работа", tvTitle.text.toString())
                            assertTrue("Subtitle must contain 'Учёт времени включён'", tvSub.text.toString().contains("Учёт времени включён"))
                            assertTrue("Switch must be checked", sw.isChecked)
                        }
                        capture("04-linked-habit-accrual-enabled")

                        // 4b. Test Activity Recreation / Restart on open sheet
                        controller.recreate()
                        idle()
                        val sRecreated = requireNotNull(sheet(controller.currentActivity(), "visual-task-detail"))
                        controller.onActivity {
                            val tvTitle = sRecreated.requireView().findViewById<TextView>(R.id.tvHabitLinkTitle)
                            val sw = sRecreated.requireView().findViewById<CompoundButton>(R.id.switchHabitAccrual)
                            val tvFocus = sRecreated.requireView().findViewById<TextView>(R.id.tvDetailFocus)
                            assertEquals("Глубокая работа", tvTitle.text.toString())
                            assertTrue("Switch must survive recreate", sw.isChecked)
                            assertEquals("Затрачено: 50 мин", tvFocus.text.toString())
                        }
                        capture("07-sheet-after-recreate")
                        dismissSheet(controller)

                        // 5. Linked habit with accrual disabled
                        val sNoAccrual = openSheet(controller, taskLinkedNoAccrual.id)
                        controller.onActivity {
                            val tvTitle = sNoAccrual.requireView().findViewById<TextView>(R.id.tvHabitLinkTitle)
                            val tvSub = sNoAccrual.requireView().findViewById<TextView>(R.id.tvHabitLinkSubtitle)
                            val sw = sNoAccrual.requireView().findViewById<CompoundButton>(R.id.switchHabitAccrual)
                            assertEquals("Английский язык", tvTitle.text.toString())
                            assertTrue("Subtitle must contain 'Учёт времени отключён'", tvSub.text.toString().contains("Учёт времени отключён"))
                            assertFalse("Switch must not be checked", sw.isChecked)
                        }
                        capture("05-linked-habit-accrual-disabled")
                        dismissSheet(controller)

                        // 6. Interactive picker selection, switch toggle, and unlinking
                        val sInteractive = openSheet(controller, taskInteractive.id)
                        controller.onActivity {
                            sInteractive.requireView().findViewById<View>(R.id.btnHabitLinkAction).performClick()
                        }
                        idle()
                        device.wait(Until.hasObject(By.text("Выберите привычку")), 4000)
                        device.findObject(By.text("Глубокая работа (мин)"))?.click()
                        idle()
                        await {
                            var titleMatch = false
                            controller.onActivity {
                                val title = sInteractive.requireView().findViewById<TextView>(R.id.tvHabitLinkTitle)
                                titleMatch = title.text.toString() == "Глубокая работа"
                            }
                            titleMatch
                        }
                        controller.onActivity {
                            val sw = sInteractive.requireView().findViewById<CompoundButton>(R.id.switchHabitAccrual)
                            assertTrue("Accrual defaults to enabled when linked", sw.isChecked)
                        }
                        capture("06-interactive-linked-after-picker")

                        // Interactive unlink
                        controller.onActivity {
                            sInteractive.requireView().findViewById<View>(R.id.btnHabitUnlink).performClick()
                        }
                        idle()
                        await {
                            var unlinked = false
                            controller.onActivity {
                                val sub = sInteractive.requireView().findViewById<TextView>(R.id.tvHabitLinkSubtitle)
                                unlinked = sub.text.toString() == "Не привязана"
                            }
                            unlinked
                        }
                        dismissSheet(controller)
                    }
                    phase.startsWith("dark") -> {
                        val sAccrual = openSheet(controller, taskLinkedAccrual.id)
                        capture("08-theme-dark-linked")
                        dismissSheet(controller)

                        val sFocus = openSheet(controller, taskFocus.id)
                        capture("09-theme-dark-focus-time")
                        controller.onActivity {
                            sFocus.requireView().findViewById<View>(R.id.btnHabitLinkAction).performClick()
                        }
                        idle()
                        device.wait(Until.hasObject(By.text("Выберите привычку")), 4000)
                        capture("10-theme-dark-dialog")
                        device.pressBack()
                        idle()
                        dismissSheet(controller)
                    }
                    phase.startsWith("amoled") -> {
                        val sAccrual = openSheet(controller, taskLinkedAccrual.id)
                        capture("11-theme-amoled-linked")
                        dismissSheet(controller)

                        val sFocus = openSheet(controller, taskFocus.id)
                        controller.onActivity {
                            sFocus.requireView().findViewById<View>(R.id.btnHabitLinkAction).performClick()
                        }
                        idle()
                        device.wait(Until.hasObject(By.text("Выберите привычку")), 4000)
                        capture("12-theme-amoled-dialog")
                        device.pressBack()
                        idle()
                        dismissSheet(controller)
                    }
                    phase.startsWith("narrow") -> {
                        controller.onActivity { activity ->
                            val activityFontScale = activity.resources.configuration.fontScale
                            assertTrue("Activity resources.configuration.fontScale must be >= 1.25f, was $activityFontScale", activityFontScale >= 1.25f)
                        }
                        val sAccrual = openSheet(controller, taskLinkedAccrual.id)
                        capture("13-narrow-font13-linked")
                        dismissSheet(controller)

                        val sFocus = openSheet(controller, taskFocus.id)
                        capture("14-narrow-font13-focus-time")
                        controller.onActivity {
                            sFocus.requireView().findViewById<View>(R.id.btnHabitLinkAction).performClick()
                        }
                        idle()
                        device.wait(Until.hasObject(By.text("Выберите привычку")), 4000)
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

    private fun openSheet(controller: QaActivityController, taskId: TaskId, tag: String = "visual-task-detail"): TaskDetailBottomSheet {
        controller.onActivity { activity ->
            val existing = tasks(activity).childFragmentManager.findFragmentByTag(tag)
            if (existing is TaskDetailBottomSheet) {
                existing.dismiss()
                tasks(activity).childFragmentManager.executePendingTransactions()
            }
            TaskDetailBottomSheet.newInstance(taskId).show(tasks(activity).childFragmentManager, tag)
            tasks(activity).childFragmentManager.executePendingTransactions()
        }
        idle()
        await {
            var ready = false
            controller.onActivity { activity ->
                val s = sheet(activity, tag)
                if (s != null && s.isAdded && s.view != null) {
                    val focusView = s.view?.findViewById<TextView>(R.id.tvDetailFocus)
                    ready = focusView?.text?.toString()?.startsWith("Затрачено:") == true
                }
            }
            ready
        }
        controller.onActivity { activity ->
            val s = sheet(activity, tag)
            val scroll = s?.view?.findViewById<NestedScrollView>(R.id.taskDetailScroll)
            val card = s?.view?.findViewById<View>(R.id.cardHabitLink)
            if (scroll != null && card != null) {
                scroll.smoothScrollTo(0, (card.top - 120).coerceAtLeast(0))
            }
        }
        idle()
        var result: TaskDetailBottomSheet? = null
        controller.onActivity { activity -> result = sheet(activity, tag) }
        return requireNotNull(result)
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

        fun currentActivity(): MainActivity {
            awaitResumed()
            var current: MainActivity? = null
            instrumentation.runOnMainSync {
                current = resumed().firstOrNull()
            }
            return requireNotNull(current) { "No resumed MainActivity" }
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
