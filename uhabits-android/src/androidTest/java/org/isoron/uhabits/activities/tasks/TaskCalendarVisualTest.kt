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

import android.os.SystemClock
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.getToday
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.main.MainActivity
import org.isoron.uhabits.activities.main.MainDestination
import org.isoron.uhabits.core.models.Frequency
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.PaletteColor
import org.isoron.uhabits.core.models.Reminder
import org.isoron.uhabits.core.models.WeekdayList
import org.isoron.uhabits.core.ui.ThemeSwitcher
import org.isoron.uhabits.core.userTasks.UserTask
import org.isoron.uhabits.core.userTasks.due.TaskDue
import org.isoron.uhabits.core.userTasks.due.TaskDueDate
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.ZoneId
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.io.Closeable
import android.content.Intent

/** Run explicitly with TaskDraftQaRunner on an owned, offline temporary AVD. */
@RunWith(AndroidJUnit4::class)
class TaskCalendarVisualTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val args = InstrumentationRegistry.getArguments()
    private val context get() = instrumentation.targetContext
    private val component get() = HabitsApplication.component
    private val device = UiDevice.getInstance(instrumentation)
    private val harness = TaskDraftLifecycleTest()
    private val phase = args.getString("phase", "light")!!.also { check(it.matches(Regex("[a-zA-Z0-9_-]+"))) }
    private val output get() = File(context.getExternalFilesDir(null), "pr11/$phase").apply { check(mkdirs() || isDirectory) }

    private val zone = ZoneId.of("Asia/Novosibirsk")

    @Test
    fun calendarJourneys() {
        TaskListVisualTest::class.java.getDeclaredMethod("bootstrapDisposableSession").apply { isAccessible = true }.invoke(TaskListVisualTest())
        harness.guard()

        val oldTheme = component.preferences.theme
        val oldBlack = component.preferences.isPureBlackEnabled

        try {
            component.preferences.theme = if (phase.startsWith("light") || phase.startsWith("narrow")) ThemeSwitcher.THEME_LIGHT else ThemeSwitcher.THEME_DARK
            component.preferences.isPureBlackEnabled = phase.startsWith("amoled")
            component.preferences.isFirstRun = false

            device.wakeUp()
            device.executeShellCommand("wm dismiss-keyguard")
            device.executeShellCommand("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
            idle()

            val today = getToday()
            val jToday = java.time.LocalDate.of(today.year, today.month, today.day)

            // 1. Seed Habits
            seedHabits()

            // 2. Seed Tasks with specific calendar dues
            val tasks = seedTasks(today, jToday)

            CalendarQaActivityController().use { controller ->
                // Navigate to Tasks and enable Calendar mode
                openCalendar(controller)
                idle()

                // Check Calendar panel is visible
                controller.onActivity { activity ->
                    val frag = fragment(activity)
                    val panel = field<View>(frag, "calendarPanel")
                    assertEquals(View.VISIBLE, panel.visibility)
                    assertEquals(CalendarScale.MONTH, field<CalendarScale>(frag, "calendarScale"))
                }

                // --- 1. MONTH VIEW ---
                capture("01-month-populated")

                // Click tomorrow in month view to select it and update agenda below
                val tomorrow = today.plus(1)
                controller.onActivity { activity ->
                    val frag = fragment(activity)
                    val calController = field<TaskCalendarViewController?>(frag, "calendarViewController")
                    assertNotNull("Controller must be attached", calController)
                    calController?.let {
                        it.selectedDay = tomorrow
                        it.render()
                    }
                }
                idle()
                capture("01b-month-selected-tomorrow")

                // --- 2. WEEK VIEW ---
                controller.onActivity { activity ->
                    val frag = fragment(activity)
                    set(frag, "calendarScale", CalendarScale.WEEK)
                    val cal = field<TaskCalendarViewController?>(frag, "calendarViewController")
                    cal?.scale = CalendarScale.WEEK
                    cal?.render()
                }
                idle()
                controller.onActivity { activity ->
                    val frag = fragment(activity)
                    assertEquals(CalendarScale.WEEK, field<CalendarScale>(frag, "calendarScale"))
                }
                capture("02-week-view")

                // Scroll week timeline down a bit
                controller.onActivity { activity ->
                    val frag = fragment(activity)
                    val panel = field<android.widget.LinearLayout>(frag, "calendarPanel")
                    findScrollView(panel)?.smoothScrollTo(0, 400)
                }
                idle()
                capture("02b-week-scrolled")

                // --- 3. DAY VIEW ---
                controller.onActivity { activity ->
                    val frag = fragment(activity)
                    // Reset selected day back to today for day view
                    set(frag, "selectedDay", today)
                    set(frag, "calendarScale", CalendarScale.DAY)
                    val cal = field<TaskCalendarViewController?>(frag, "calendarViewController")
                    cal?.selectedDay = today
                    cal?.scale = CalendarScale.DAY
                    cal?.render()
                }
                idle()
                controller.onActivity { activity ->
                    val frag = fragment(activity)
                    assertEquals(CalendarScale.DAY, field<CalendarScale>(frag, "calendarScale"))
                    assertEquals(today, field<LocalDate>(frag, "selectedDay"))
                }
                capture("03-day-view")

                // Scroll day timeline to timed events (~10:00 - 15:00)
                controller.onActivity { activity ->
                    val frag = fragment(activity)
                    val panel = field<android.widget.LinearLayout>(frag, "calendarPanel")
                    findScrollView(panel)?.smoothScrollTo(0, 600)
                }
                idle()
                capture("03b-day-scrolled")

                // --- 4. EMPTY DAY ---
                val farFuture = today.plus(35)
                controller.onActivity { activity ->
                    val frag = fragment(activity)
                    set(frag, "selectedDay", farFuture)
                    val calController = field<TaskCalendarViewController?>(frag, "calendarViewController")
                    calController?.selectedDay = farFuture
                    calController?.render()
                }
                idle()
                capture("04-day-empty")

                // Return to Today via Today button logic
                controller.onActivity { activity ->
                    val frag = fragment(activity)
                    set(frag, "selectedDay", today)
                    val calController = field<TaskCalendarViewController?>(frag, "calendarViewController")
                    calController?.selectedDay = today
                    calController?.render()
                }
                idle()
                capture("04b-day-today-restored")

                // --- 5. SCALE SWITCH PRESERVES SELECTED DATE ---
                val targetDay = today.plus(2)
                controller.onActivity { activity ->
                    val frag = fragment(activity)
                    set(frag, "selectedDay", targetDay)
                    val c = field<TaskCalendarViewController?>(frag, "calendarViewController")!!
                    c.selectedDay = targetDay
                    // Switch Day -> Week -> Month -> Day
                    c.scale = CalendarScale.WEEK
                    assertEquals(targetDay, c.selectedDay)
                    c.scale = CalendarScale.MONTH
                    assertEquals(targetDay, c.selectedDay)
                    c.scale = CalendarScale.DAY
                    assertEquals(targetDay, c.selectedDay)
                    c.render()
                }
                idle()

                // --- 6. SWITCHING BETWEEN MODES (List, Calendar, Matrix) ---
                // Switch to List
                controller.onActivity { activity ->
                    val frag = fragment(activity)
                    set(frag, "calendarMode", false)
                    set(frag, "matrixMode", false)
                    frag.loadTasks()
                }
                idle(); waitLoad(controller)
                capture("05-mode-list")

                // Switch to Matrix
                controller.onActivity { activity ->
                    val frag = fragment(activity)
                    set(frag, "calendarMode", false)
                    set(frag, "matrixMode", true)
                    frag.loadTasks()
                }
                idle(); waitLoad(controller)
                capture("06-mode-matrix")

                // Switch back to Calendar in Week mode
                controller.onActivity { activity ->
                    val frag = fragment(activity)
                    set(frag, "calendarMode", true)
                    set(frag, "matrixMode", false)
                    set(frag, "calendarScale", CalendarScale.WEEK)
                    set(frag, "selectedDay", today)
                    frag.loadTasks()
                }
                idle(); waitLoad(controller)
                capture("07-mode-calendar-restored")

                // --- 7. ACTIVITY RECREATION ---
                controller.recreate()
                idle()
                controller.onActivity { activity ->
                    val frag = fragment(activity)
                    assertTrue("Calendar mode must survive recreation", field<Boolean>(frag, "calendarMode"))
                    assertEquals("Week scale must survive recreation", CalendarScale.WEEK, field<CalendarScale>(frag, "calendarScale"))
                    assertEquals("Selected day must survive recreation", today, field<LocalDate>(frag, "selectedDay"))
                }
                capture("08-recreated")
            }

            evidence("phase=$phase today=$today tasks=${tasks.size} calendarScale=MONTH,WEEK,DAY verified")
        } finally {
            component.preferences.theme = oldTheme
            component.preferences.isPureBlackEnabled = oldBlack
        }
    }

    @Test
    fun targetedVisualVerification() {
        TaskListVisualTest::class.java.getDeclaredMethod("bootstrapDisposableSession").apply { isAccessible = true }.invoke(TaskListVisualTest())
        harness.guard()

        val oldTheme = component.preferences.theme
        val oldBlack = component.preferences.isPureBlackEnabled

        try {
            component.preferences.theme = if (phase.contains("dark")) ThemeSwitcher.THEME_DARK else ThemeSwitcher.THEME_LIGHT
            component.preferences.isPureBlackEnabled = phase.contains("amoled")
            component.preferences.isFirstRun = false

            device.wakeUp()
            device.executeShellCommand("wm dismiss-keyguard")
            device.executeShellCommand("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
            idle()

            val density = context.resources.displayMetrics.density
            fun dp(px: Float): Int = (px * density).toInt()

            val today = getToday()
            val jToday = java.time.LocalDate.of(today.year, today.month, today.day)
            val weekStart = today.startOfWeek(org.isoron.platform.time.DayOfWeek.MONDAY)

            seedHabits()
            val tasks = seedTasks(today, jToday)

            CalendarQaActivityController().use { controller ->
                openCalendar(controller)
                idle()

                if (phase.contains("narrow")) {
                    // --- Scenario 6: Narrow Screen 1.3x Week View ---
                    controller.onActivity { activity ->
                        val frag = fragment(activity)
                        set(frag, "calendarScale", CalendarScale.WEEK)
                        set(frag, "selectedDay", today)
                        val cal = field<TaskCalendarViewController?>(frag, "calendarViewController")
                        cal?.scale = CalendarScale.WEEK
                        cal?.selectedDay = today
                        cal?.render()
                    }
                    idle(); waitLoad(controller)
                    capture("06-narrow-font13-week")
                } else {
                    // --- Scenario 1: Beginning of Week View (Monday selected) ---
                    controller.onActivity { activity ->
                        val frag = fragment(activity)
                        set(frag, "calendarScale", CalendarScale.WEEK)
                        set(frag, "selectedDay", weekStart)
                        val cal = field<TaskCalendarViewController?>(frag, "calendarViewController")
                        cal?.scale = CalendarScale.WEEK
                        cal?.selectedDay = weekStart
                        cal?.render()
                    }
                    idle(); waitLoad(controller)
                    capture("01-week-start")

                    // --- Scenario 2: Week Scrolled Horizontally (Friday-Sunday visible) ---
                    controller.onActivity { activity ->
                        val frag = fragment(activity)
                        val panel = field<android.widget.LinearLayout>(frag, "calendarPanel")
                        val hScrolls = mutableListOf<android.widget.HorizontalScrollView>()
                        fun findHScrolls(v: View) {
                            if (v is android.widget.HorizontalScrollView) hScrolls.add(v)
                            if (v is android.view.ViewGroup) {
                                for (i in 0 until v.childCount) findHScrolls(v.getChildAt(i))
                            }
                        }
                        findHScrolls(panel)
                        for (hs in hScrolls) {
                            hs.smoothScrollTo(dp(300f), 0)
                        }
                    }
                    idle()
                    capture("02-week-scrolled-horiz")

                    // --- Scenario 3: Week Scrolled Vertically (Header & All-Day Stay Pinned) ---
                    controller.onActivity { activity ->
                        val frag = fragment(activity)
                        val panel = field<android.widget.LinearLayout>(frag, "calendarPanel")
                        val hScrolls = mutableListOf<android.widget.HorizontalScrollView>()
                        fun findHScrolls(v: View) {
                            if (v is android.widget.HorizontalScrollView) hScrolls.add(v)
                            if (v is android.view.ViewGroup) {
                                for (i in 0 until v.childCount) findHScrolls(v.getChildAt(i))
                            }
                        }
                        findHScrolls(panel)
                        for (hs in hScrolls) {
                            hs.scrollTo(0, 0)
                        }
                        findScrollView(panel)?.smoothScrollTo(0, dp(400f))
                    }
                    idle()
                    capture("03-week-scrolled-vert")

                    // --- Scenario 4: Day View with Pinned All-Day Section & Timeline Scrolled to Current Hour ---
                    controller.onActivity { activity ->
                        val frag = fragment(activity)
                        set(frag, "calendarScale", CalendarScale.DAY)
                        set(frag, "selectedDay", today)
                        val cal = field<TaskCalendarViewController?>(frag, "calendarViewController")
                        cal?.scale = CalendarScale.DAY
                        cal?.selectedDay = today
                        cal?.render()
                    }
                    idle(); waitLoad(controller)
                    capture("04-day-allday-visible")

                    // --- Scenario 5: Calendar on a Different Date (Title shows "Календарь", not "Сегодня") ---
                    val tomorrow = today.plus(1)
                    controller.onActivity { activity ->
                        val frag = fragment(activity)
                        set(frag, "selectedDay", tomorrow)
                        val cal = field<TaskCalendarViewController?>(frag, "calendarViewController")
                        cal?.selectedDay = tomorrow
                        cal?.render()
                        val updateSmartView = frag.javaClass.getDeclaredMethod("updateSmartViewLabel")
                        updateSmartView.isAccessible = true
                        updateSmartView.invoke(frag)
                    }
                    idle(); waitLoad(controller)
                    capture("05-calendar-different-date")
                }
            }

            evidence("targeted verification passed: phase=$phase")
        } finally {
            component.preferences.theme = oldTheme
            component.preferences.isPureBlackEnabled = oldBlack
        }
    }

    private fun seedHabits() {
        val list = component.habitList
        val factory = component.sqlModelFactory

        // 1. Daily habit (Run in the morning)
        val dailyName = "PR11 Утренняя пробежка"
        if (list.none { it.name == dailyName }) {
            val habit = factory.buildHabit().apply {
                uuid = java.util.UUID.randomUUID().toString()
                name = dailyName
                frequency = Frequency.DAILY
                color = PaletteColor(1) // Blue
            }
            list.add(habit)
        }

        // 2. Weekday habit with reminder (English practice: Mon, Wed, Fri at 19:30)
        val englishName = "PR11 Английский язык"
        if (list.none { it.name == englishName }) {
            val weekdays = BooleanArray(7) { false }
            weekdays[2] = true // Monday
            weekdays[4] = true // Wednesday
            weekdays[6] = true // Friday
            val habit = factory.buildHabit().apply {
                uuid = java.util.UUID.randomUUID().toString()
                name = englishName
                frequency = Frequency(3, 7)
                color = PaletteColor(4) // Green
                reminder = Reminder(19, 30, WeekdayList(weekdays))
            }
            list.add(habit)
        }

        // 3. Weekly habit without reminder days (Reading: 3 times a week, should be omitted from calendar)
        val readingName = "PR11 Чтение книги"
        if (list.none { it.name == readingName }) {
            val habit = factory.buildHabit().apply {
                uuid = java.util.UUID.randomUUID().toString()
                name = readingName
                frequency = Frequency(3, 7)
                color = PaletteColor(6) // Orange
            }
            list.add(habit)
        }
    }

    private fun seedTasks(today: LocalDate, jToday: java.time.LocalDate): List<UserTask> {
        val repo = component.userTaskRepository
        val service = component.userTaskService

        fun timed(jDate: java.time.LocalDate, hour: Int, minute: Int): TaskDue.Timed {
            val millis = jDate.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
            return TaskDue.Timed.local(millis, zone.id)
        }

        fun dayDue(jDate: java.time.LocalDate): TaskDue.Day {
            return TaskDue.Day(TaskDueDate.parse(jDate.toString()))
        }

        fun seed(title: String, due: TaskDue, important: Boolean? = null, urgent: Boolean? = null): UserTask {
            val existing = repo.getRoots().find { it.title == title }
            return if (existing == null) {
                service.createTask(title, due = due, important = important, urgent = urgent)
            } else {
                service.updateTask(existing.id, due = due, important = important, urgent = urgent)
            }
        }

        return listOf(
            // Today All-Day
            seed("PR11 Подготовить релиз календаря", dayDue(jToday), important = true, urgent = true),
            // Today Timed 10:30
            seed("PR11 Синхронизация с командой", timed(jToday, 10, 30), important = true, urgent = false),
            // Today Timed 15:00
            seed("PR11 Демо планировщика", timed(jToday, 15, 0), important = false, urgent = true),
            // Tomorrow All-Day
            seed("PR11 Ретроспектива PR11", dayDue(jToday.plusDays(1))),
            // Tomorrow Timed 14:00
            seed("PR11 Планирование спринта", timed(jToday.plusDays(1), 14, 0)),
            // Day After Tomorrow Timed 11:30
            seed("PR11 Архитектурное ревью", timed(jToday.plusDays(2), 11, 30)),
            // Overdue task
            seed("PR11 Долг по тестам", dayDue(jToday.minusDays(3)), important = true)
        )
    }

    private fun openCalendar(controller: CalendarQaActivityController) {
        controller.onActivity { activity ->
            activity.navigate(MainDestination.TASKS)
            activity.supportFragmentManager.executePendingTransactions()
            val frag = fragment(activity)
            set(frag, "calendarMode", true)
            set(frag, "matrixMode", false)
            set(frag, "calendarScale", CalendarScale.MONTH)
            set(frag, "selectedDay", getToday())
        }
        idle(); waitLoad(controller)
        controller.onActivity { fragment(it).loadTasks() }
        idle(); waitLoad(controller)
    }

    private fun findScrollView(root: View): ScrollView? {
        if (root is ScrollView) return root
        if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) {
                val found = findScrollView(root.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }

    private fun waitLoad(controller: CalendarQaActivityController) {
        val until = SystemClock.uptimeMillis() + 10000
        while (SystemClock.uptimeMillis() < until) {
            var busy = false
            controller.onActivity { busy = field<kotlinx.coroutines.Job?>(fragment(it), "loadJob")?.isActive == true }
            if (!busy) break
            SystemClock.sleep(50)
        }
        idle()
    }

    inner class CalendarQaActivityController : Closeable {
        init {
            device.wakeUp()
            device.executeShellCommand("wm dismiss-keyguard")
            device.executeShellCommand("input keyevent 82")
            SystemClock.sleep(300)
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
            var dump = ""
            while (SystemClock.uptimeMillis() < deadline) {
                var ready = false
                instrumentation.runOnMainSync {
                    val current = resumed()
                    ready = current.isNotEmpty() && current.first() !== previous
                    if (!ready) {
                        val all = Stage.values().flatMap { stage ->
                            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage).map {
                                "${it.javaClass.simpleName}@${it.hashCode()}:$stage:finishing=${it.isFinishing}"
                            }
                        }
                        dump = all.joinToString(", ")
                    }
                }
                if (ready) return
                device.wakeUp()
                device.executeShellCommand("wm dismiss-keyguard")
                SystemClock.sleep(100)
            }
            val focus = device.executeShellCommand("dumpsys window | grep mCurrentFocus")
            error("No current resumed MainActivity appeared within 20s. Stages: $dump. Focus: $focus")
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

    private fun fragment(activity: MainActivity) =
        activity.supportFragmentManager.findFragmentByTag("main.tasks") as TasksFragment

    private fun idle() {
        instrumentation.waitForIdleSync()
        device.waitForIdle()
        SystemClock.sleep(250)
    }

    private fun capture(name: String) {
        idle()
        SystemClock.sleep(400)
        val file = File(output, "$name.png")
        check(device.takeScreenshot(file)) { "Failed to take screenshot: ${file.absolutePath}" }
    }

    private fun evidence(line: String) {
        File(output, "evidence.txt").appendText("$line\n")
    }

    private fun set(instance: Any, name: String, value: Any?) {
        instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(instance, value)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(instance: Any, name: String): T =
        instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(instance) as T
}
