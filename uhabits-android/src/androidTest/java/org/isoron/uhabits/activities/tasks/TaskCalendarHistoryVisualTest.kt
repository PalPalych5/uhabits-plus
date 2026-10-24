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
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.UiDevice
import org.isoron.platform.time.DayOfWeek
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.getToday
import org.isoron.platform.time.startOfLocalDay
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.activities.main.MainActivity
import org.isoron.uhabits.activities.main.MainDestination
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.focus.FocusPrimaryTargetType
import org.isoron.uhabits.core.focus.FocusSession
import org.isoron.uhabits.core.focus.FocusSessionId
import org.isoron.uhabits.core.focus.FocusSessionSource
import org.isoron.uhabits.core.models.Frequency
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.PaletteColor
import org.isoron.uhabits.core.ui.ThemeSwitcher
import org.isoron.uhabits.core.userTasks.TaskId
import org.isoron.uhabits.core.userTasks.UserTask
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.io.File
import java.util.UUID

/**
 * Isolated visual and functional verification for PR 12 (History Calendar).
 * Runs on wrapper-owned temporary AVD and captures the 9 required evidence PNGs.
 */
@RunWith(AndroidJUnit4::class)
class TaskCalendarHistoryVisualTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val args = InstrumentationRegistry.getArguments()
    private val context get() = instrumentation.targetContext
    private val component get() = HabitsApplication.component
    private val device = UiDevice.getInstance(instrumentation)
    private val harness = TaskDraftLifecycleTest()
    private val phase = args.getString("phase", "light")!!.also { check(it.matches(Regex("[a-zA-Z0-9_-]+"))) }
    private val output get() = File(context.getExternalFilesDir(null), "pr12/$phase").apply { check(mkdirs() || isDirectory) }

    /** Bounded follow-up: synthetic in-memory calendar input; real Android layout and taps. */
    @Test
    fun finalHistoryDiagnostics() {
        TaskListVisualTest::class.java.getDeclaredMethod("bootstrapDisposableSession").apply { isAccessible = true }.invoke(TaskListVisualTest())
        harness.guard()
        val previousZone = java.util.TimeZone.getDefault()
        val oldTheme = component.preferences.theme
        val oldBlack = component.preferences.isPureBlackEnabled
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Europe/Berlin"))
            component.preferences.theme = ThemeSwitcher.THEME_LIGHT
            component.preferences.isPureBlackEnabled = false
            component.preferences.isFirstRun = false
            val diagnosticTasks = (1..4).map { seedTask("PR12 diagnostic $it") }
            fun instant(value: String) = java.time.OffsetDateTime.parse(value).toInstant().toEpochMilli()
            fun session(id: String, start: String, end: String, taskIndex: Int, duration: Long? = null): FocusSession {
                val from = instant(start)
                val to = instant(end)
                return FocusSession(FocusSessionId(id), from, to, duration ?: (to - from),
                    taskId = diagnosticTasks[taskIndex].id, source = FocusSessionSource.TIMER,
                    createdAt = from, updatedAt = to)
            }
            val fallDay = LocalDate(2026, 10, 25)
            val repeated = listOf(
                session("pr12_dst_first", "2026-10-25T02:15:00+02:00", "2026-10-25T02:45:00+02:00", 0),
                session("pr12_dst_second", "2026-10-25T02:15:00+01:00", "2026-10-25T02:45:00+01:00", 1)
            )
            CalendarQaActivityController().use { controller ->
                openHistoryCalendar(controller)
                fun render(day: LocalDate, scale: CalendarScale, sessions: List<FocusSession>, scrollDp: Int = 0) {
                    controller.onActivity { activity ->
                        val cal = requireNotNull(field<TaskCalendarViewController?>(fragment(activity), "calendarViewController"))
                        cal.mode = CalendarMode.HISTORY
                        cal.scale = scale
                        cal.selectedDay = day
                        cal.updateData(diagnosticTasks, emptyList(), emptyMap(), emptyMap(), sessions)
                        cal.render()
                    }
                    idle()
                    controller.onActivity { activity ->
                        findScrollView(field<LinearLayout>(fragment(activity), "calendarPanel"))?.scrollTo(
                            0, (scrollDp * context.resources.displayMetrics.density).toInt())
                    }
                    idle()
                }
                fun views(root: View): List<View> = listOf(root) +
                    if (root is android.view.ViewGroup) (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else emptyList()
                fun panelViews(): List<View> {
                    var result = emptyList<View>()
                    controller.onActivity { result = views(field<LinearLayout>(fragment(it), "calendarPanel")) }
                    return result
                }
                fun windowViews(): List<View> {
                    var result = emptyList<View>()
                    instrumentation.runOnMainSync {
                        result = android.view.inspector.WindowInspector.getGlobalWindowViews().flatMap { views(it) }
                    }
                    return result
                }
                fun clickVisible(view: View, require48: Boolean = false) {
                    val rect = android.graphics.Rect()
                    instrumentation.runOnMainSync {
                        assertTrue("Target must be visible", view.getGlobalVisibleRect(rect))
                        // getGlobalVisibleRect is relative to the ViewRoot; dialogs have an
                        // independent window origin. Native input requires screen coordinates.
                        val rootOrigin = IntArray(2)
                        view.rootView.getLocationOnScreen(rootOrigin)
                        rect.offset(rootOrigin[0], rootOrigin[1])
                    }
                    if (require48) {
                        val min = (48 * context.resources.displayMetrics.density).toInt()
                        assertTrue("Actual hit width ${rect.width()} < $min", rect.width() >= min)
                        assertTrue("Actual hit height ${rect.height()} < $min", rect.height() >= min)
                    }
                    assertTrue(device.click(rect.centerX(), rect.centerY()))
                    idle()
                }
                render(fallDay, CalendarScale.DAY, repeated)
                // Compare rendered bounds, rather than just the projector's timestamp ordering.
                val blocks = panelViews().filter { it.tag?.toString()?.contains("pr12_dst_") == true }
                assertEquals("Both repeated-hour records need accessible blocks", 2, blocks.size)
                val bounds = blocks.map { view -> android.graphics.Rect().also { rect ->
                    instrumentation.runOnMainSync { assertTrue(view.getGlobalVisibleRect(rect)) }
                } }.sortedBy { it.top }
                assertTrue("Repeated local intervals must not overlap visually", bounds[0].bottom <= bounds[1].top)
                val dayText = panelViews().filterIsInstance<TextView>().joinToString("\n") { it.text }
                assertTrue(dayText.contains("+02"))
                assertTrue(dayText.contains("+01"))
                capture("pr12_10_berlin_repeated_hour_day")
                render(fallDay, CalendarScale.WEEK, repeated)
                controller.onActivity { activity ->
                    views(field<LinearLayout>(fragment(activity), "calendarPanel"))
                        .filterIsInstance<HorizontalScrollView>().forEach { it.fullScroll(View.FOCUS_RIGHT) }
                }
                idle()
                val weekBlocks = panelViews().filter { it.tag?.toString()?.contains("pr12_dst_") == true }
                assertEquals(2, weekBlocks.size)
                val weekBounds = weekBlocks.map { view -> android.graphics.Rect().also { rect ->
                    instrumentation.runOnMainSync { assertTrue("DST week record must be visible", view.getGlobalVisibleRect(rect)) }
                } }.sortedBy { it.top }
                assertTrue("Repeated hour must also remain distinct in WEEK", weekBounds[0].bottom <= weekBounds[1].top)
                capture("pr12_11_berlin_repeated_hour_week")

                val ordinary = LocalDate(2026, 10, 26)
                val crowded = (0..3).map { index -> session("pr12_overlap_$index",
                    "2026-10-26T10:00:00+01:00", "2026-10-26T11:00:00+01:00", index) }
                for (crowdedScale in listOf(CalendarScale.DAY, CalendarScale.WEEK)) {
                render(ordinary, crowdedScale, crowded, 9 * 56)
                val disclosure = panelViews().firstOrNull { it.tag?.toString()?.startsWith("history_cluster:") == true }
                    ?: error("Crowded intervals require an accessible session disclosure")
                clickVisible(disclosure, true)
                if (crowdedScale == CalendarScale.DAY) capture("pr12_12_four_sessions_list_narrow13")
                // Select each row with native input, close detail, then reopen the disclosure.
                for (index in diagnosticTasks.indices) {
                    val row = windowViews().firstOrNull { it.tag == "history_session:pr12_overlap_$index" }
                        ?: error("Missing accessible session row $index")
                    clickVisible(row, true)
                    assertTrue("Wrong session details", device.findObject(androidx.test.uiautomator.UiSelector().text(diagnosticTasks[index].title)).exists())
                    if (index == 3 && crowdedScale == CalendarScale.DAY) capture("pr12_13_fourth_session_opened")
                    device.pressBack(); idle()
                    // Dialog implementations may retain the list or replace it.
                    if (index != diagnosticTasks.lastIndex && windowViews().none { it.tag == "history_session:pr12_overlap_${index + 1}" }) {
                        clickVisible(disclosure, true)
                    }
                }
                if (windowViews().any { it.tag == "history_session:pr12_overlap_0" }) {
                    device.pressBack(); idle()
                }
                evidence("$crowdedScale: four session rows opened by real coordinate taps; all targets >=48dp")
                }
                val paused = session("pr12_paused_midnight", "2026-10-26T23:00:00+01:00",
                    "2026-10-27T01:00:00+01:00", 0, 60 * 60_000L)
                render(ordinary, CalendarScale.MONTH, listOf(paused))
                assertTrue("Month must disclose estimated day allocation", panelViews().filterIsInstance<TextView>()
                    .any { it.text.contains("≈") || it.text.contains("оцен", ignoreCase = true) || it.text.contains("estimat", ignoreCase = true) })
                capture("pr12_14_paused_midnight_estimate")
                render(ordinary, CalendarScale.DAY, listOf(paused), 22 * 56)
                assertTrue("Day must disclose estimated allocation", panelViews().filterIsInstance<TextView>()
                    .any { it.text.contains("≈") || it.text.contains("оцен", ignoreCase = true) || it.text.contains("estimat", ignoreCase = true) })
                val pausedBlock = panelViews().first { it.tag?.toString()?.contains("pr12_paused_midnight") == true }
                clickVisible(pausedBlock)
                capture("pr12_15_original_duration_and_limitation")
                evidence("Berlin repeated-hour block bounds distinct; real >=48dp disclosure and four row taps; fourth detail opened; paused midnight original duration=3600000ms")
                device.pressBack(); idle()
                val shortPause = session("pr12_subminute_pause", "2026-10-26T23:00:00+01:00",
                    "2026-10-27T01:00:00.500+01:00", 0, 120 * 60_000L)
                render(ordinary, CalendarScale.DAY, listOf(shortPause), 22 * 56)
                clickVisible(panelViews().first { it.tag == "pr12_subminute_pause" })
                val detailText = windowViews().filterIsInstance<TextView>().joinToString("\n") { it.text }
                assertTrue("Even a subminute pause must disclose estimated daily allocation",
                    detailText.contains("оцен", ignoreCase = true) || detailText.contains("estimated", ignoreCase = true))
                device.pressBack()
            }
        } finally {
            java.util.TimeZone.setDefault(previousZone)
            component.preferences.theme = oldTheme
            component.preferences.isPureBlackEnabled = oldBlack
        }
    }

    @Test
    fun calendarHistoryJourneys() {
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

            val density = context.resources.displayMetrics.density
            fun dp(px: Float): Int = (px * density).toInt()

            val today = getToday()
            val todayStart = startOfLocalDay(today, 0, 0)
            val tomorrow = today.plus(1)
            val weekStart = today.startOfWeek(DayOfWeek.MONDAY)

            // 1. Seed base entities (Task, Habit, Container)
            val habit = seedHabit()
            val task = seedTask("PR12 Реализация истории таймера")
            val containerId = seedContainer("Разработка PR12")

            // 2. Seed synthetic FocusSessions
            seedHistorySessions(today, todayStart, task, habit, containerId)

            CalendarQaActivityController().use { controller ->
                openHistoryCalendar(controller)
                idle(); waitLoad(controller)

                // Verify Calendar panel is visible and in History mode
                controller.onActivity { activity ->
                    val frag = fragment(activity)
                    val panel = field<View>(frag, "calendarPanel")
                    assertEquals(View.VISIBLE, panel.visibility)
                    assertEquals(CalendarMode.HISTORY, field<CalendarMode>(frag, "calendarViewMode"))
                }

                if (phase.contains("narrow")) {
                    // --- 8. NARROW SCREEN 1.3X SCALE ---
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

                    // Scroll day timeline to afternoon events (11:00 - 16:00)
                    controller.onActivity { activity ->
                        val frag = fragment(activity)
                        val panel = field<LinearLayout>(frag, "calendarPanel")
                        findScrollView(panel)?.smoothScrollTo(0, dp(600f))
                    }
                    idle()
                    capture("pr12_08_narrow_13x_history")
                    evidence("pr12_08_narrow_13x_history captured")
                    return
                }

                if (phase.contains("dark") || phase.contains("amoled")) {
                    // --- 9. DARK / AMOLED THEME RENDERING ---
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

                    controller.onActivity { activity ->
                        val frag = fragment(activity)
                        val panel = field<LinearLayout>(frag, "calendarPanel")
                        findScrollView(panel)?.smoothScrollTo(0, dp(600f))
                    }
                    idle()
                    capture("pr12_09_dark_amoled_history")
                    evidence("pr12_09_dark_amoled_history captured")
                    return
                }

                // =========================================================================
                // FULL LIGHT RUN (Screenshots 01 - 07)
                // =========================================================================

                // --- 1. DAY VIEW: SEVERAL FOCUS SESSIONS ---
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

                // Scroll day timeline to 09:00 - 14:00
                controller.onActivity { activity ->
                    val frag = fragment(activity)
                    val panel = field<LinearLayout>(frag, "calendarPanel")
                    findScrollView(panel)?.smoothScrollTo(0, dp(450f))
                }
                idle()
                capture("pr12_01_day_focus_sessions")
                evidence("pr12_01_day_focus_sessions captured")

                // --- 2. DAY VIEW: OVERLAPPING INTERVALS IN LANE LAYOUT ---
                // Scroll to 14:00 - 17:00 where overlapping sessions reside
                controller.onActivity { activity ->
                    val frag = fragment(activity)
                    val panel = field<LinearLayout>(frag, "calendarPanel")
                    findScrollView(panel)?.smoothScrollTo(0, dp(750f))
                }
                idle()
                capture("pr12_02_day_overlapping")
                evidence("pr12_02_day_overlapping captured")

                // --- 3. DAY VIEW: MIDNIGHT SPLIT (Visual partition across midnight) ---
                // Scroll to 22:30 - 24:00 to show session starting at 23:00 ending tomorrow
                controller.onActivity { activity ->
                    val frag = fragment(activity)
                    val panel = field<LinearLayout>(frag, "calendarPanel")
                    findScrollView(panel)?.smoothScrollTo(0, dp(1200f))
                }
                idle()
                capture("pr12_03_day_midnight_split")
                evidence("pr12_03_day_midnight_split captured")

                // --- 4. WEEK VIEW: ACTUAL INTERVALS ACROSS DAYS ---
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

                // Scroll week timeline to 10:00 - 16:00
                controller.onActivity { activity ->
                    val frag = fragment(activity)
                    val panel = field<LinearLayout>(frag, "calendarPanel")
                    findScrollView(panel)?.smoothScrollTo(0, dp(550f))
                }
                idle()
                capture("pr12_04_week_intervals")
                evidence("pr12_04_week_intervals captured")

                // --- 5. MONTH VIEW: AGGREGATED HISTORY ---
                controller.onActivity { activity ->
                    val frag = fragment(activity)
                    set(frag, "calendarScale", CalendarScale.MONTH)
                    set(frag, "selectedDay", today)
                    val cal = field<TaskCalendarViewController?>(frag, "calendarViewController")
                    cal?.scale = CalendarScale.MONTH
                    cal?.selectedDay = today
                    cal?.render()
                }
                idle(); waitLoad(controller)
                capture("pr12_05_month_history")
                evidence("pr12_05_month_history captured")

                // --- 6. EMPTY HISTORY DAY ---
                val emptyDay = today.plus(15)
                controller.onActivity { activity ->
                    val frag = fragment(activity)
                    set(frag, "calendarScale", CalendarScale.DAY)
                    set(frag, "selectedDay", emptyDay)
                    val cal = field<TaskCalendarViewController?>(frag, "calendarViewController")
                    cal?.scale = CalendarScale.DAY
                    cal?.selectedDay = emptyDay
                    cal?.render()
                }
                idle(); waitLoad(controller)
                capture("pr12_06_empty_history_day")
                evidence("pr12_06_empty_history_day captured")

                // --- 7. DUAL MODE SWITCHER (Planning vs History) ---
                // Reset to today in Day scale
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

                // Scroll to top to cleanly present the control bar
                controller.onActivity { activity ->
                    val frag = fragment(activity)
                    val panel = field<LinearLayout>(frag, "calendarPanel")
                    findScrollView(panel)?.smoothScrollTo(0, 0)
                }
                idle()
                capture("pr12_07_mode_switcher")
                evidence("pr12_07_mode_switcher captured")
            }
        } finally {
            component.preferences.theme = oldTheme
            component.preferences.isPureBlackEnabled = oldBlack
        }
    }

    private fun seedHabit(): Habit {
        val list = component.habitList
        val factory = component.sqlModelFactory
        val habitName = "PR12 Английский язык"
        val existing = list.find { it.name == habitName }
        if (existing != null) return existing

        val habit = factory.buildHabit().apply {
            uuid = UUID.randomUUID().toString()
            name = habitName
            frequency = Frequency.DAILY
            color = PaletteColor(4) // Green
        }
        list.add(habit)
        return habit
    }

    private fun seedTask(title: String): UserTask {
        val repo = component.userTaskRepository
        val service = component.userTaskService
        val existing = repo.getRoots().find { it.title == title }
        return existing ?: service.createTask(title)
    }

    private fun seedContainer(name: String): ContainerId {
        val facade = component.organizationFacade
        val queries = requireNotNull(facade.containerQueries)
        val existing = queries.roots().find { it.name == name }
        if (existing != null) return existing.id
        return facade.createContainer(name).getOrThrow().id
    }

    private fun seedHistorySessions(
        today: LocalDate,
        todayStart: Long,
        task: UserTask,
        habit: Habit,
        containerId: ContainerId
    ) {
        val repo = component.focusSessionRepository ?: return

        fun s(
            id: String,
            startOffsetMs: Long,
            endOffsetMs: Long,
            durationMs: Long,
            taskId: TaskId? = null,
            habitRef: HabitRef? = null,
            contId: ContainerId? = null,
            orgCtx: String? = null
        ) {
            val session = FocusSession(
                id = FocusSessionId(id),
                startedAt = todayStart + startOffsetMs,
                endedAt = todayStart + endOffsetMs,
                durationMs = durationMs,
                taskId = taskId,
                habit = habitRef,
                containerId = contId,
                source = FocusSessionSource.TIMER,
                createdAt = todayStart + startOffsetMs,
                updatedAt = todayStart + endOffsetMs,
                revision = 1L,
                organizationContext = orgCtx
            )
            repo.record(session)
        }

        val habitRef = HabitRef(habit.uuid ?: UUID.randomUUID().toString())
        val taskId = TaskId(task.id.value)

        // 1. Daytime Task session (09:00 - 10:30, 1h 30m)
        s("fs_01_task", 9 * 3600_000L, 10 * 3600_000L + 30 * 60_000L, 90 * 60_000L, taskId = taskId)

        // 2. Daytime Habit session (11:00 - 12:00, 1h)
        s("fs_02_habit", 11 * 3600_000L, 12 * 3600_000L, 60 * 60_000L, habitRef = habitRef)

        // 3. Daytime Container session (14:00 - 15:30, 1h 30m)
        s("fs_03_container", 14 * 3600_000L, 15 * 3600_000L + 30 * 60_000L, 90 * 60_000L, contId = containerId)

        // 4. Overlapping Task session (14:30 - 16:00, 1h 30m) - overlaps with fs_03_container
        s("fs_04_overlap", 14 * 3600_000L + 30 * 60_000L, 16 * 3600_000L, 90 * 60_000L, taskId = taskId)

        // 5. Midnight crossing session (23:00 today to 01:30 tomorrow, 2h 30m)
        s("fs_05_midnight", 23 * 3600_000L, 25 * 3600_000L + 30 * 60_000L, 150 * 60_000L, taskId = taskId)

        // 6. Yesterday session (10:00 - 11:30)
        s("fs_06_yesterday", -24 * 3600_000L + 10 * 3600_000L, -24 * 3600_000L + 11 * 3600_000L + 30 * 60_000L, 90 * 60_000L, habitRef = habitRef)

        // 7. Tomorrow session (15:00 - 16:30)
        s("fs_07_tomorrow", 24 * 3600_000L + 15 * 3600_000L, 24 * 3600_000L + 16 * 3600_000L + 30 * 60_000L, 90 * 60_000L, contId = containerId)
    }

    private fun openHistoryCalendar(controller: CalendarQaActivityController) {
        controller.onActivity { activity ->
            activity.navigate(MainDestination.TASKS)
            activity.supportFragmentManager.executePendingTransactions()
            val frag = fragment(activity)
            set(frag, "calendarMode", true)
            set(frag, "matrixMode", false)
            set(frag, "calendarViewMode", CalendarMode.HISTORY)
            set(frag, "calendarScale", CalendarScale.DAY)
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
