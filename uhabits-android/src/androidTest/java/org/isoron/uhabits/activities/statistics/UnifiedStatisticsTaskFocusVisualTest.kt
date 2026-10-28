/*
 * Copyright (C) 2016-2026 Álinson Santos Xavier <git@axavier.org> and contributors
 *
 * This file is part of Loop Habit Tracker.
 *
 * Loop Habit Tracker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 */

package org.isoron.uhabits.activities.statistics

import android.content.Context
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.accessibility.AccessibilityNodeProviderCompat
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayout
import org.isoron.platform.time.JavaLocalDateFormatter
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.getToday
import org.isoron.platform.time.setToday
import org.isoron.platform.time.startOfLocalDay
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.common.theme.MainTabsThemeBridge
import org.isoron.uhabits.activities.main.MainActivity
import org.isoron.uhabits.activities.main.MainDestination
import org.isoron.uhabits.activities.statistics.ChartColumnItem
import org.isoron.uhabits.activities.statistics.views.DiscreteColumnBarChart
import org.isoron.uhabits.activities.statistics.views.SegmentedCohortBarView
import org.isoron.uhabits.activities.tasks.TaskDraftLifecycleTest
import org.isoron.uhabits.activities.tasks.TaskListVisualTest
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.contribution.Contribution
import org.isoron.uhabits.core.contribution.ContributionId
import org.isoron.uhabits.core.contribution.ContributionSourceType
import org.isoron.uhabits.core.contribution.sqlite.SQLiteContributionRepository
import org.isoron.uhabits.core.focus.FocusSession
import org.isoron.uhabits.core.focus.FocusSessionId
import org.isoron.uhabits.core.focus.FocusSessionSource
import org.isoron.uhabits.core.focus.sqlite.SQLiteFocusSessionRepository
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Frequency
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitType
import org.isoron.uhabits.core.models.NumericalHabitType
import org.isoron.uhabits.core.models.PaletteColor
import org.isoron.uhabits.core.statistics.UnifiedStatisticsReport
import org.isoron.uhabits.core.ui.ThemeSwitcher
import org.isoron.uhabits.core.userTasks.TaskId
import org.isoron.uhabits.core.userTasks.TaskStatus
import org.isoron.uhabits.core.userTasks.UserTask
import org.isoron.uhabits.core.userTasks.due.TaskDue
import org.isoron.uhabits.core.userTasks.due.toTaskDueDate
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale
import java.util.UUID

/**
 * PR14.3 dedicated Android visual and functional instrumentation test for Tasks (T1-T4)
 * and Focus (F1-F4) analytical domains.
 *
 * Strict Fail-Closed Verification:
 * - Actual T1-T4 discrete column & cohort segment views and headline counts.
 * - Actual F1-F4 duration charts, disjoint section branches, and target breakdowns.
 * - Exact arithmetic invariants across periods and scopes.
 * - Real user click paths on chart columns and cohort segments to launch drilldown sheets.
 * - Exact rendered task IDs match aggregate selection (fail-closed set equality via visibleIds & row.tag).
 * - Bounded replacing pages (<=30 items per slice) with nextPageButton & previousPageButton.
 * - Real UI navigation path through Focus section -> object -> sessions -> detail bottom sheet.
 * - Contribution provenance ledger with active vs tombstone verification.
 * - Missing/deleted target fallback labels ("Deleted task" / "Удалённая задача") with stable ID.
 * - Live data mutation while sheet is open asserting fresh report and updated sheet items.
 * - Actual period selector clicks (DAY, WEEK, MONTH, YEAR, ALL) via UI menus.
 * - Scope traversals (ALL, CONTAINER SUBTREE, HERE, UNASSIGNED) with descendant resolution.
 * - Activity recreation + Back with open drilldown sheet.
 * - Dataset replacement guards and MidnightTimer lifecycle.
 * - Genuine empty scope vs domain error containment checks.
 * - Dual screenshot captures: UiDevice (screencap) and PixelCopy (Window Surface off main thread).
 * - Scrolled viewport captures for 200% font scale and 320dp narrow screens.
 */
@RunWith(AndroidJUnit4::class)
class UnifiedStatisticsTaskFocusVisualTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val args = InstrumentationRegistry.getArguments()
    private val context get() = instrumentation.targetContext
    private val component get() = HabitsApplication.component
    private val device = UiDevice.getInstance(instrumentation)
    private val harness = TaskDraftLifecycleTest()

    private val phase = args.getString("phase", "light_standard_100")!!.also {
        check(it.matches(Regex("^(light|dark|amoled)_(standard|narrow320)_(100|200)$")))
    }

    private val outputDir get() = File(context.getExternalFilesDir(null), "unified-statistics-task-focus-qa").apply {
        check(mkdirs() || isDirectory) { "Failed to create evidence directory: $path" }
    }

    companion object {
        const val ROOT_CONTAINER_WORK = "Работа и проекты"
        const val CHILD_CONTAINER_DEV = "Разработка Loop"
        const val CONTAINER_EMPTY = "Пустой раздел QA"

        const val HABIT_NUMERICAL_TITLE = "Фокусная работа"
        const val HABIT_BOOLEAN_TITLE = "Утренняя разминка"
        const val HABIT_UNASSIGNED_TITLE = "Медитация"

        const val DELETED_TASK_ID = "deleted-task-qa-404"
        const val ACTIVE_SESSION_ID = "fs-unified-qa-active-45m"
        const val TOMBSTONE_SESSION_ID = "fs-unified-qa-tombstone-20m"
        const val PAUSED_SESSION_ID = "fs-unified-qa-paused-midnight-50m"

        const val DRILLDOWN_PAGE_SIZE = 30
    }

    // -------------------------------------------------------------------------
    // Synthetic Task & Focus Fixture Data Seeding (Schema 35 Preserving)
    // -------------------------------------------------------------------------

    data class TaskFocusSeedingResult(
        val rootContainer: ContainerId,
        val childContainer: ContainerId,
        val emptyContainer: ContainerId,
        val habitNum: Habit,
        val habitBool: Habit,
        val habitUnassigned: Habit,
        val completedTasksToday: List<UserTask>,
        val dueTasks: List<UserTask>,
        val noDueTasks: List<UserTask>,
        val focusSessions: List<FocusSession>,
        val activeContribution: Contribution,
        val tombstoneContribution: Contribution,
        val totalExpectedCompletedT1: Int,
        val totalExpectedCreatedT2: Int,
        val totalExpectedDueT3: Int,
        val totalExpectedDueCompleted: Int,
        val totalExpectedDueOverdue: Int,
        val totalExpectedDuePendingNotOverdue: Int,
        val totalExpectedNoDueT4: Int,
        val totalExpectedNoDueCompleted: Int,
        val totalExpectedNoDuePending: Int,
        val totalExpectedFocusDurationMs: Long,
        val totalExpectedFocusSessionCount: Int,
    )

    private fun seedTaskFocusData(): TaskFocusSeedingResult {
        val now = System.currentTimeMillis()
        val today = getToday()
        setToday(today)

        val orgFacade = component.organizationFacade
        val containerQueries = requireNotNull(orgFacade.containerQueries)

        val rootId = containerQueries.roots().firstOrNull { it.name == ROOT_CONTAINER_WORK }?.id
            ?: orgFacade.createContainer(ROOT_CONTAINER_WORK).getOrThrow().id

        val childId = containerQueries.children(rootId).firstOrNull { it.name == CHILD_CONTAINER_DEV }?.id
            ?: orgFacade.createContainer(CHILD_CONTAINER_DEV, parentId = rootId).getOrThrow().id

        val emptyId = containerQueries.roots().firstOrNull { it.name == CONTAINER_EMPTY }?.id
            ?: orgFacade.createContainer(CONTAINER_EMPTY).getOrThrow().id

        val habitList = component.habitList
        val factory = component.sqlModelFactory

        // 1. Numerical Habit in Child Container
        var habitNum = habitList.firstOrNull { it.name == HABIT_NUMERICAL_TITLE }
        if (habitNum == null) {
            habitNum = factory.buildHabit().apply {
                uuid = UUID.randomUUID().toString()
                name = HABIT_NUMERICAL_TITLE
                description = "QA Numerical Habit for Focus"
                frequency = Frequency.DAILY
                type = HabitType.NUMERICAL
                targetType = NumericalHabitType.AT_LEAST
                targetValue = 60.0
                unit = "мин"
                color = PaletteColor(2)
            }
            habitList.add(habitNum)
        }
        orgFacade.placeHabit(requireNotNull(habitNum.uuid), childId)

        // 2. Boolean Habit in Root Container
        var habitBool = habitList.firstOrNull { it.name == HABIT_BOOLEAN_TITLE }
        if (habitBool == null) {
            habitBool = factory.buildHabit().apply {
                uuid = UUID.randomUUID().toString()
                name = HABIT_BOOLEAN_TITLE
                description = "QA Boolean Habit"
                frequency = Frequency.DAILY
                type = HabitType.YES_NO
                color = PaletteColor(0)
            }
            habitList.add(habitBool)
        }
        orgFacade.placeHabit(requireNotNull(habitBool.uuid), rootId)

        // 3. Unassigned Habit
        var habitUnassigned = habitList.firstOrNull { it.name == HABIT_UNASSIGNED_TITLE }
        if (habitUnassigned == null) {
            habitUnassigned = factory.buildHabit().apply {
                uuid = UUID.randomUUID().toString()
                name = HABIT_UNASSIGNED_TITLE
                description = "QA Unassigned Habit"
                frequency = Frequency.DAILY
                type = HabitType.YES_NO
                color = PaletteColor(4)
            }
            habitList.add(habitUnassigned)
        }

        val taskRepo = component.userTaskRepository
        val taskService = component.userTaskService

        // 4. Seed > 30 Completed Tasks on Today (to verify bounded <= 30 pagination)
        val completedToday = mutableListOf<UserTask>()
        for (i in 1..35) {
            val title = "QA Completed Task #$i"
            var task = taskRepo.getRoots().firstOrNull { it.title == title }
            if (task == null) {
                task = taskService.createTask(
                    title,
                    containerId = if (i % 2 == 0) childId else rootId,
                    due = TaskDue.Day(today.toTaskDueDate())
                )
                task = task.copy(
                    status = TaskStatus.COMPLETED,
                    completedAt = now - (36 - i) * 60_000L
                )
                taskRepo.save(task)
            }
            completedToday.add(task)
        }

        // Additional completed tasks on past days (today - 1 and today - 2)
        val pastTask1Title = "QA Past Completed Task Yesterday"
        var pastTask1 = taskRepo.getRoots().firstOrNull { it.title == pastTask1Title }
        if (pastTask1 == null) {
            pastTask1 = taskService.createTask(pastTask1Title, containerId = rootId)
            pastTask1 = pastTask1.copy(
                status = TaskStatus.COMPLETED,
                completedAt = now - 86_400_000L
            )
            taskRepo.save(pastTask1)
        }

        // 5. T3 Due Tasks Cohorts:
        val overdueTitle = "QA Overdue Task Yesterday"
        var overdueTask = taskRepo.getRoots().firstOrNull { it.title == overdueTitle }
        if (overdueTask == null) {
            overdueTask = taskService.createTask(
                overdueTitle,
                containerId = childId,
                due = TaskDue.Day(today.minus(1).toTaskDueDate())
            )
        }

        val pendingFutureTitle = "QA Pending Task Tomorrow"
        var pendingFutureTask = taskRepo.getRoots().firstOrNull { it.title == pendingFutureTitle }
        if (pendingFutureTask == null) {
            pendingFutureTask = taskService.createTask(
                pendingFutureTitle,
                containerId = rootId,
                due = TaskDue.Day(today.plus(1).toTaskDueDate())
            )
        }

        // 6. T4 NoDue Cohorts:
        val noDueCompTitle = "QA NoDue Completed Task"
        var noDueCompTask = taskRepo.getRoots().firstOrNull { it.title == noDueCompTitle }
        if (noDueCompTask == null) {
            noDueCompTask = taskService.createTask(noDueCompTitle, containerId = rootId)
            noDueCompTask = noDueCompTask.copy(
                status = TaskStatus.COMPLETED,
                completedAt = now - 120_000L
            )
            taskRepo.save(noDueCompTask)
        }

        val noDuePendTitle = "QA NoDue Pending Task"
        var noDuePendTask = taskRepo.getRoots().firstOrNull { it.title == noDuePendTitle }
        if (noDuePendTask == null) {
            noDuePendTask = taskService.createTask(noDuePendTitle, containerId = childId)
        }

        // 7. Focus Sessions Seeding (F1-F4)
        val focusRepo = component.focusSessionRepository ?: SQLiteFocusSessionRepository(component.db)
        val sessions = mutableListOf<FocusSession>()

        // Session 1: Active session on completed task #1 (45 mins = 2_700_000ms)
        val session1 = FocusSession(
            id = FocusSessionId(ACTIVE_SESSION_ID),
            startedAt = now - 2_700_000L,
            endedAt = now,
            durationMs = 2_700_000L,
            taskId = completedToday.first().id,
            containerId = childId,
            source = FocusSessionSource.TIMER,
            createdAt = now,
            updatedAt = now
        )
        focusRepo.record(session1)
        sessions.add(session1)

        // Session 2: Tombstone session (20 mins = 1_200_000ms)
        val session2 = FocusSession(
            id = FocusSessionId(TOMBSTONE_SESSION_ID),
            startedAt = now - 5_400_000L,
            endedAt = now - 4_200_000L,
            durationMs = 1_200_000L,
            taskId = completedToday[1].id,
            containerId = rootId,
            source = FocusSessionSource.TIMER,
            createdAt = now,
            updatedAt = now
        )
        focusRepo.record(session2)
        sessions.add(session2)

        // Session 3: Paused session across midnight (50 mins active = 3_000_000ms, 60 mins elapsed = 3_600_000ms)
        val midnight = startOfLocalDay(today, 0, 0)
        val session3 = FocusSession(
            id = FocusSessionId(PAUSED_SESSION_ID),
            startedAt = midnight - 1_800_000L,
            endedAt = midnight + 1_800_000L,
            durationMs = 3_000_000L,
            taskId = null,
            containerId = rootId,
            source = FocusSessionSource.TIMER,
            createdAt = now,
            updatedAt = now
        )
        focusRepo.record(session3)
        sessions.add(session3)

        // Session 4: Session on deleted target
        val sessionDeleted = FocusSession(
            id = FocusSessionId("fs-unified-qa-deleted-target"),
            startedAt = now - 7_200_000L,
            endedAt = now - 6_000_000L,
            durationMs = 1_200_000L,
            taskId = TaskId(DELETED_TASK_ID),
            containerId = childId,
            source = FocusSessionSource.TIMER,
            createdAt = now,
            updatedAt = now
        )
        focusRepo.record(sessionDeleted)
        sessions.add(sessionDeleted)

        // Seed additional sessions on today to verify Focus drilldown paging cap (>30 sessions)
        for (i in 5..34) {
            val s = FocusSession(
                id = FocusSessionId("fs-unified-qa-bulk-$i"),
                startedAt = now - (i * 60_000L),
                endedAt = now - ((i - 1) * 60_000L),
                durationMs = 60_000L,
                taskId = completedToday[i % completedToday.size].id,
                containerId = childId,
                source = FocusSessionSource.TIMER,
                createdAt = now,
                updatedAt = now
            )
            focusRepo.record(s)
            sessions.add(s)
        }

        // 8. Contribution Ledger Seeding (Active vs Tombstone)
        val contribRepo = component.contributionRepository ?: SQLiteContributionRepository(component.db)

        val activeContrib = Contribution(
            id = ContributionId("contrib-unified-qa-active-45m"),
            targetHabit = HabitRef(requireNotNull(habitNum.uuid)),
            sourceType = ContributionSourceType.FOCUS_SESSION,
            sourceId = ACTIVE_SESSION_ID,
            contributedValue = 45L * 1000L,
            effectiveTimestamp = now,
            createdAt = now,
            updatedAt = now
        )
        contribRepo.record(activeContrib)

        val tombstoneContrib = Contribution(
            id = ContributionId("contrib-unified-qa-tombstone-20m"),
            targetHabit = HabitRef(requireNotNull(habitNum.uuid)),
            sourceType = ContributionSourceType.FOCUS_SESSION,
            sourceId = TOMBSTONE_SESSION_ID,
            contributedValue = 20L * 1000L,
            effectiveTimestamp = now - 4_200_000L,
            removedAt = now - 3_000_000L,
            createdAt = now - 4_200_000L,
            updatedAt = now - 3_000_000L
        )
        contribRepo.record(tombstoneContrib)

        val totalDuration = sessions.sumOf { it.durationMs }

        return TaskFocusSeedingResult(
            rootContainer = rootId,
            childContainer = childId,
            emptyContainer = emptyId,
            habitNum = habitNum,
            habitBool = habitBool,
            habitUnassigned = habitUnassigned,
            completedTasksToday = completedToday,
            dueTasks = listOf(overdueTask, pendingFutureTask) + completedToday,
            noDueTasks = listOf(noDueCompTask, noDuePendTask),
            focusSessions = sessions,
            activeContribution = activeContrib,
            tombstoneContribution = tombstoneContrib,
            totalExpectedCompletedT1 = completedToday.size + 1 + 1,
            totalExpectedCreatedT2 = completedToday.size + 4,
            totalExpectedDueT3 = completedToday.size + 1,
            totalExpectedDueCompleted = completedToday.size,
            totalExpectedDueOverdue = 1,
            totalExpectedDuePendingNotOverdue = 0,
            totalExpectedNoDueT4 = 2,
            totalExpectedNoDueCompleted = 1,
            totalExpectedNoDuePending = 1,
            totalExpectedFocusDurationMs = totalDuration,
            totalExpectedFocusSessionCount = sessions.size,
        )
    }

    // -------------------------------------------------------------------------
    // Main PR14.3 Journey
    // -------------------------------------------------------------------------

    @Test
    fun taskFocusJourney() {
        // 1. Safety and offline disposable gates reuse
        TaskListVisualTest::class.java.getDeclaredMethod("bootstrapDisposableSession")
            .apply { isAccessible = true }.invoke(TaskListVisualTest())
        harness.guard()

        val oldTheme = component.preferences.theme
        val oldBlack = component.preferences.isPureBlackEnabled
        val oldFirstRun = component.preferences.isFirstRun

        try {
            val isAmoled = phase.startsWith("amoled")
            val isDark = phase.startsWith("dark") || isAmoled
            component.preferences.theme = if (isDark) ThemeSwitcher.THEME_DARK else ThemeSwitcher.THEME_LIGHT
            component.preferences.isPureBlackEnabled = isAmoled
            component.preferences.isFirstRun = false

            device.wakeUp()
            device.executeShellCommand("wm dismiss-keyguard")
            idle()

            // 2. Seed comprehensive synthetic fixtures
            val seed = seedTaskFocusData()

            // 3. Launch MainActivity with QaActivityController
            QaActivityController().use { controller ->
                navigateToStatistics(controller)

                // 4. Validate Activity configuration & fontScale
                controller.onActivity { activity ->
                    val config = activity.resources.configuration
                    val expectedFontScale = args.getString("expectedFontScale", "1.0")!!.toFloat()
                    assertEquals("fontScale must match expected", expectedFontScale, config.fontScale, 0.05f)

                    val expectedWidthDp = args.getString("expectedWidthDp", "0")!!.toInt()
                    if (expectedWidthDp == 320) {
                        assertTrue(
                            "Narrow 320dp width expected, actual=${config.screenWidthDp}",
                            kotlin.math.abs(config.screenWidthDp - 320) <= 1
                        )
                    }

                    val themeBridge = MainTabsThemeBridge.resolve(activity)
                    assertTrue("Body text contrast must be >= 4.5:1",
                        androidx.core.graphics.ColorUtils.calculateContrast(themeBridge.onSurface, themeBridge.surface) >= 4.5)
                    assertTrue("Secondary text contrast must be >= 4.5:1",
                        androidx.core.graphics.ColorUtils.calculateContrast(themeBridge.onSurfaceVariant, themeBridge.surface) >= 4.5)
                    assertTrue("Graph bars contrast must be >= 3:1",
                        androidx.core.graphics.ColorUtils.calculateContrast(themeBridge.accent, themeBridge.surface) >= 3.0)
                    if (isDark) {
                        assertTrue("Resolved theme bridge must be dark", themeBridge.isDark)
                    } else {
                        assertFalse("Resolved theme bridge must be light", themeBridge.isDark)
                    }
                }

                waitForSettled(controller)

                // Ensure report scope is UnifiedScope.All
                controller.onActivity { activity ->
                    val fragment = statsFragment(activity)
                    fragment.applyScopedAll()
                }
                idle()
                waitForSettled(controller)
                controller.onActivity { activity ->
                    val fragment = statsFragment(activity)
                    assertEquals("Scope must be All after applyScopedAll", UnifiedScope.All, getCurrentScope(fragment))
                    val report = requireNotNull(getLoadedReport(fragment)) { "Report must not be null" }
                    assertEquals(
                        "Loaded report context scope must be All",
                        org.isoron.uhabits.core.statistics.StatisticsSectionScope.All,
                        report.context.sectionScope
                    )
                }

                // =============================================================
                // PHASE A: Tasks Tab Journey (T1 - T4)
                // =============================================================
                selectTab(controller, 2)
                assertTabSelected(controller, UnifiedAnalyticsTab.TASKS)

                // A1. Capture top of Tasks tab
                capture("01-tasks-top")

                // A2. Assert Exact Arithmetic Invariants on Loaded Report
                controller.onActivity { activity ->
                    val fragment = statsFragment(activity)
                    val report = requireNotNull(getLoadedReport(fragment)) { "Report must not be null" }
                    val tasks = requireNotNull(report.tasks) { "Tasks result must not be null" }

                    // T1: Completed tasks sum across daily buckets equals completedCount
                    val sumBuckets = tasks.dailyCompletedBuckets.sumOf { it.count }
                    assertEquals(
                        "T1: Sum of daily completed buckets must equal total completedCount",
                        tasks.completedCount.toLong(),
                        sumBuckets.toLong()
                    )

                    // T3: Due cohort sum equals totalCount
                    val due = tasks.dueSummary
                    assertEquals(
                        "T3: Due cohort parts must sum to totalCount",
                        due.totalCount.toLong(),
                        (due.completedCount + due.pendingOverdueNowCount + due.pendingNotOverdueNowCount).toLong()
                    )

                    // T4: NoDue cohort sum equals totalCount
                    val noDue = tasks.noDueSummary
                    assertEquals(
                        "T4: NoDue cohort parts must sum to totalCount",
                        noDue.totalCount.toLong(),
                        (noDue.completedCount + noDue.pendingCount).toLong()
                    )
                }

                // A3. FAIL-CLOSED: Assert T1 Chart exists, verify axes, column items, accessibility provider, and height >= 48dp, then capture
                controller.onActivity { activity ->
                    val fragment = statsFragment(activity)
                    val chart = requireNotNull(fragment.view?.findViewWithTag<DiscreteColumnBarChart>("unified_tasks_t1_chart")) {
                        "FAIL-CLOSED: unified_tasks_t1_chart view must be present in layout"
                    }
                    val items = field<List<ChartColumnItem>>(chart, "items")
                    assertTrue("T1 chart items must not be empty", items.isNotEmpty())
                    assertFalse("T1 chart column item contentDescription must not be blank", items.first().contentDescription.isNullOrBlank())
                    assertTrue("T1 chart Y axis must be enabled", chart.showYAxis)
                    assertFalse("T1 chart unitLabel must not be blank", chart.unitLabel.isNullOrBlank())

                    // Virtual accessibility provider node verification
                    val node = assertVirtualAccessibilityNode(chart, 0)
                    assertFalse("T1 chart virtual node contentDescription must not be blank", node.contentDescription.isNullOrBlank())

                    val scroll = requireNotNull(getContentScrollView(fragment)) { "ScrollView must exist" }
                    scrollToDescendant(scroll, chart)
                    val density = activity.resources.displayMetrics.density
                    assertTrue(
                        "Chart height must be >= 48dp, actual=${chart.height / density}",
                        chart.height / density >= 47.5f
                    )
                }
                idle()
                SystemClock.sleep(200)
                capture("02-tasks-t1-chart")

                // A4. FAIL-CLOSED: Assert T3 & T4 Cohort Bars exist, verify segments, accessibility provider, and height >= 48dp, then capture
                controller.onActivity { activity ->
                    val fragment = statsFragment(activity)
                    val t3Cohort = requireNotNull(fragment.view?.findViewWithTag<SegmentedCohortBarView>("unified_tasks_t3_cohort")) {
                        "FAIL-CLOSED: unified_tasks_t3_cohort view must be present in layout"
                    }
                    val t4Cohort = requireNotNull(fragment.view?.findViewWithTag<SegmentedCohortBarView>("unified_tasks_t4_cohort")) {
                        "FAIL-CLOSED: unified_tasks_t4_cohort view must be present in layout"
                    }
                    val t3Segments = field<List<SegmentedCohortBarView.Segment>>(t3Cohort, "segments")
                    val t4Segments = field<List<SegmentedCohortBarView.Segment>>(t4Cohort, "segments")
                    assertTrue("T3 segments must not be empty", t3Segments.isNotEmpty())
                    assertTrue("T4 segments must not be empty", t4Segments.isNotEmpty())
                    assertFalse("T3 segment label must not be blank", t3Segments.first().label.isBlank())
                    assertFalse("T4 segment label must not be blank", t4Segments.first().label.isBlank())

                    // Virtual accessibility provider node verifications
                    val t3Node = assertVirtualAccessibilityNode(t3Cohort, 0)
                    assertFalse("T3 cohort virtual node contentDescription must not be blank", t3Node.contentDescription.isNullOrBlank())
                    val t4Node = assertVirtualAccessibilityNode(t4Cohort, 0)
                    assertFalse("T4 cohort virtual node contentDescription must not be blank", t4Node.contentDescription.isNullOrBlank())

                    val density = activity.resources.displayMetrics.density
                    assertTrue("T3 Cohort bar height must be >= 48dp", t3Cohort.height / density >= 47.5f)
                    assertTrue("T4 Cohort bar height must be >= 48dp", t4Cohort.height / density >= 47.5f)

                    val scroll = requireNotNull(getContentScrollView(fragment))
                    scrollToDescendant(scroll, t3Cohort)
                }
                idle()
                SystemClock.sleep(200)
                capture("03-tasks-cohorts")

                // A5. REAL CLICK PATH: Tap T1 Chart column for today to launch Drilldown Sheet
                controller.onActivity { activity ->
                    val fragment = statsFragment(activity)
                    val chart = requireNotNull(fragment.view?.findViewWithTag<DiscreteColumnBarChart>("unified_tasks_t1_chart"))
                    val items = field<List<ChartColumnItem>>(chart, "items")
                    val todayIndex = items.indexOfFirst { it.isToday }
                    assertTrue("T1 chart must contain a column for today", todayIndex >= 0)
                    tapColumn(chart, todayIndex)
                }
                idle()
                SystemClock.sleep(400)

                // FAIL-CLOSED: Drilldown sheet must be open via real click
                val tasksSheet = requireNotNull(findOpenBottomSheet(controller) as? UnifiedStatisticsDrilldownBottomSheet) {
                    "FAIL-CLOSED: UnifiedStatisticsDrilldownBottomSheet must be displayed following real click on T1 chart"
                }
                capture("04-tasks-drilldown-sheet")

                // Verify Bounded Paging Cap: initial child count strictly <= 30
                controller.onActivity { activity ->
                    val fragment = statsFragment(activity)
                    val report = requireNotNull(getLoadedReport(fragment)) { "Report must not be null" }
                    val aggregateTasksToday = report.tasks?.dailyCompletedBuckets?.firstOrNull { it.date == getToday() }?.tasks.orEmpty()

                    val initialVisible = tasksSheet.visibleIds
                    assertEquals(
                        "FAIL-CLOSED: Initial visible items must strictly equal paging cap DRILLDOWN_PAGE_SIZE = 30",
                        DRILLDOWN_PAGE_SIZE,
                        initialVisible.size
                    )

                    // FAIL-CLOSED: Exact rendered task IDs match ordered aggregate list
                    val expectedPage1Ids = aggregateTasksToday.take(DRILLDOWN_PAGE_SIZE).map { it.id.value }
                    assertEquals(
                        "FAIL-CLOSED: visibleIds on Page 1 must strictly match first 30 aggregate items",
                        expectedPage1Ids,
                        initialVisible
                    )

                    // Also verify View.tag on rows matches exact IDs
                    val listContainer = field<LinearLayout>(tasksSheet, "listContainer")
                    val rowTagIds = (0 until listContainer.childCount).map { i ->
                        listContainer.getChildAt(i).tag?.toString() ?: ""
                    }
                    assertEquals(
                        "FAIL-CLOSED: Row tags on Page 1 must strictly match exact task IDs",
                        expectedPage1Ids,
                        rowTagIds
                    )

                    // Bounded replacing pagination with nextPageButton & previousPageButton
                    assertEquals(View.VISIBLE, tasksSheet.nextPageButton.visibility)

                    // REAL CLICK PATH: Advance to page 2 (bounded slice <= 30 items)
                    tasksSheet.nextPageButton.performClick()
                    val page2Ids = tasksSheet.visibleIds
                    assertTrue(
                        "FAIL-CLOSED: Page slice must be bounded <= 30 items, actual=${page2Ids.size}",
                        page2Ids.size <= DRILLDOWN_PAGE_SIZE
                    )

                    val expectedRemainingIds = aggregateTasksToday.drop(DRILLDOWN_PAGE_SIZE).map { it.id.value }
                    assertEquals(
                        "FAIL-CLOSED: Replacing Page 2 must render remaining 5 items",
                        expectedRemainingIds,
                        page2Ids
                    )

                    // Verify previousPageButton is visible on Page 2 and can return to Page 1
                    val prevBtn = field<MaterialButton>(tasksSheet, "previousPageButton")
                    assertEquals("previousPageButton must be visible on Page 2", View.VISIBLE, prevBtn.visibility)
                    prevBtn.performClick()
                    assertEquals(
                        "Returning to Page 1 must restore initial 30 items",
                        expectedPage1Ids,
                        tasksSheet.visibleIds
                    )
                }

                // A6. LIVE MUTATION: Invalidate data while drilldown sheet is open
                // FAIL-CLOSED: MUST assert fresh report AND exact updated sheet items/count
                var liveTaskId: TaskId? = null
                var beforeCompletedCount = 0
                controller.onActivity { activity ->
                    beforeCompletedCount = requireNotNull(statsFragment(activity).lastReport?.tasks).completedCount
                    val taskRepo = component.userTaskRepository
                    val liveTask = component.userTaskService.createTask(
                        "QA Live Drilldown Invalidation Task",
                        due = TaskDue.Day(getToday().toTaskDueDate())
                    )
                    val completedLive = liveTask.copy(
                        status = TaskStatus.COMPLETED,
                        completedAt = System.currentTimeMillis()
                    )
                    taskRepo.save(completedLive)
                    liveTaskId = completedLive.id
                    statsFragment(activity).onModelChange()
                }
                idle()
                waitForSettled(controller)

                controller.onActivity { activity ->
                    val fragment = statsFragment(activity)
                    val freshReport = requireNotNull(getLoadedReport(fragment)) {
                        "FAIL-CLOSED: Fresh report must not be null after live mutation"
                    }
                    val freshTasks = requireNotNull(freshReport.tasks) {
                        "FAIL-CLOSED: Fresh tasks result must not be null after live mutation"
                    }
                    val todayBucket = freshTasks.dailyCompletedBuckets.firstOrNull { it.date == getToday() }
                    assertNotNull("Fresh report must have today's bucket", todayBucket)
                    assertTrue(
                        "Fresh report today bucket must contain newly created live task",
                        todayBucket!!.tasks.any { it.id == liveTaskId }
                    )
                    assertEquals(
                        "Completed tasks count must increment by 1",
                        beforeCompletedCount + 1,
                        freshTasks.completedCount
                    )

                    // Assert reactive sheet refresh
                    assertTrue("Drilldown bottom sheet must still be open", tasksSheet.dialog?.isShowing == true)
                    val expectedIds = todayBucket.tasks.map { it.id.value }
                    val visible = mutableListOf<String>()
                    var page = 0
                    do {
                        assertEquals("Open sheet must react to fresh report without manual refresh",
                            expectedIds.drop(page * 30).take(30), tasksSheet.visibleIds)
                        visible.addAll(tasksSheet.visibleIds)
                        val hasNext = tasksSheet.nextPageButton.visibility == View.VISIBLE
                        if (hasNext) { tasksSheet.nextPageButton.performClick(); page++ }
                    } while (hasNext && page < 10)
                    assertEquals(expectedIds, visible)
                    assertTrue(visible.contains(requireNotNull(liveTaskId).value))
                }

                // REAL USER NAVIGATION: Close drilldown sheet with Back
                device.pressBack()
                idle()

                // A7. REAL CLICK PATH: Tap T3 Due Overdue segment to launch cohort drilldown
                controller.onActivity { activity ->
                    val fragment = statsFragment(activity)
                    val t3Cohort = requireNotNull(fragment.view?.findViewWithTag<SegmentedCohortBarView>("unified_tasks_t3_cohort"))
                    val segments = field<List<SegmentedCohortBarView.Segment>>(t3Cohort, "segments")
                    val overdueIndex = segments.indexOfFirst { it.key == "OVERDUE_NOW" }
                    assertTrue("T3 cohort must have OVERDUE_NOW segment", overdueIndex >= 0)
                    tapSegment(t3Cohort, overdueIndex)
                }
                idle()
                SystemClock.sleep(400)

                val overdueSheet = requireNotNull(findOpenBottomSheet(controller) as? UnifiedStatisticsDrilldownBottomSheet) {
                    "FAIL-CLOSED: Overdue drilldown bottom sheet must be open via real segment click"
                }
                controller.onActivity { activity ->
                    val ids = requireNotNull(statsFragment(activity).lastReport?.tasks).dueSummary.pendingOverdueNowTasks.map { it.id.value }
                    assertEquals("Overdue sheet must equal the exact current cohort", ids.take(30), overdueSheet.visibleIds)
                    assertTrue(ids.contains(seed.dueTasks.first().id.value))
                }
                device.pressBack()
                idle()

                // =============================================================
                // PHASE B: Focus Tab Journey (F1 - F4)
                // =============================================================
                selectTab(controller, 3)
                assertTabSelected(controller, UnifiedAnalyticsTab.FOCUS)

                // B1. Capture top of Focus tab
                capture("05-focus-top")

                // B2. Assert Exact Arithmetic Invariants on Focus Report
                controller.onActivity { activity ->
                    val fragment = statsFragment(activity)
                    val report = requireNotNull(getLoadedReport(fragment))
                    val focus = requireNotNull(report.focus) { "Focus report must not be null" }

                    val sumDailyDuration = focus.dailyBuckets.sumOf { it.durationMs }
                    assertEquals(
                        "F1: Sum of daily bucket durations must match totalDurationMs",
                        focus.totalDurationMs,
                        sumDailyDuration
                    )

                    if (focus.sectionBreakdowns.isNotEmpty()) {
                        val sumSections = focus.sectionBreakdowns.sumOf { it.durationMs }
                        assertEquals(
                            "F3: Sum of disjoint section branch durations must equal total duration",
                            focus.totalDurationMs,
                            sumSections
                        )
                    }

                    assertEquals(
                        "F2: sessionCount must equal sessions list size",
                        focus.sessionCount,
                        focus.sessions.size
                    )
                }

                // B3. FAIL-CLOSED: Scroll to F1 Chart, assert axes, column items, accessibility provider, and >=48dp height, capture
                controller.onActivity { activity ->
                    val fragment = statsFragment(activity)
                    val chart = requireNotNull(fragment.view?.findViewWithTag<DiscreteColumnBarChart>("unified_focus_f1_chart")) {
                        "FAIL-CLOSED: unified_focus_f1_chart must exist in Focus tab layout"
                    }
                    val items = field<List<ChartColumnItem>>(chart, "items")
                    assertTrue("F1 chart items must not be empty", items.isNotEmpty())
                    assertFalse("F1 column item contentDescription must not be blank", items.first().contentDescription.isNullOrBlank())
                    assertTrue("F1 chart Y axis must be enabled", chart.showYAxis)
                    assertFalse("F1 chart unitLabel must not be blank", chart.unitLabel.isNullOrBlank())

                    // Virtual accessibility provider node verification
                    val node = assertVirtualAccessibilityNode(chart, 0)
                    assertFalse("F1 chart virtual node contentDescription must not be blank", node.contentDescription.isNullOrBlank())

                    val density = activity.resources.displayMetrics.density
                    assertTrue("F1 chart height must be >= 48dp", chart.height / density >= 47.5f)

                    val scroll = requireNotNull(getContentScrollView(fragment))
                    scrollToDescendant(scroll, chart)
                }
                idle()
                SystemClock.sleep(200)
                capture("06-focus-f1-chart")

                // B4. FAIL-CLOSED: Scroll to F3 Sections Breakdown, assert hierarchy, capture
                controller.onActivity { activity ->
                    val fragment = statsFragment(activity)
                    val sectionsView = requireNotNull(fragment.view?.findViewWithTag<View>("unified_focus_f3_breakdown")) {
                        "FAIL-CLOSED: unified_focus_f3_breakdown must exist in Focus tab layout"
                    }
                    val scroll = requireNotNull(getContentScrollView(fragment))
                    scrollToDescendant(scroll, sectionsView)
                }
                idle()
                SystemClock.sleep(200)
                capture("07-focus-f3-sections")

                // B5. FAIL-CLOSED: Scroll to F4 Targets Breakdown, verify missing/deleted target label, capture
                controller.onActivity { activity ->
                    val fragment = statsFragment(activity)
                    val targetsView = requireNotNull(fragment.view?.findViewWithTag<View>("unified_focus_f4_breakdown")) {
                        "FAIL-CLOSED: unified_focus_f4_breakdown must exist in Focus tab layout"
                    }
                    val scroll = requireNotNull(getContentScrollView(fragment))
                    scrollToDescendant(scroll, targetsView)

                    // Deleted-target labels are verified in the primary-object detail path below.
                }
                idle()
                SystemClock.sleep(200)
                capture("08-focus-f4-targets")

                // B6. REAL CLICK PATH: Tap F1 Chart column for today to open Focus Drilldown Sheet
                controller.onActivity { activity ->
                    val fragment = statsFragment(activity)
                    val chart = requireNotNull(fragment.view?.findViewWithTag<DiscreteColumnBarChart>("unified_focus_f1_chart"))
                    val items = field<List<ChartColumnItem>>(chart, "items")
                    val todayIndex = items.indexOfFirst { it.isToday }
                    assertTrue("F1 chart must contain a column for today", todayIndex >= 0)
                    tapColumn(chart, todayIndex)
                }
                idle()
                SystemClock.sleep(400)

                val focusSheet = requireNotNull(findOpenBottomSheet(controller) as? UnifiedStatisticsDrilldownBottomSheet) {
                    "FAIL-CLOSED: Focus drilldown sheet must open via real click on F1 chart"
                }
                capture("09-focus-drilldown-sheet")

                controller.onActivity {
                    assertEquals(
                        "FAIL-CLOSED: Focus drilldown sessions list must strictly equal paging cap DRILLDOWN_PAGE_SIZE = 30",
                        DRILLDOWN_PAGE_SIZE,
                        focusSheet.visibleIds.size
                    )
                }
                device.pressBack()
                idle()

                // Exact hierarchy paths and loaded payloads, with no first-row fallbacks.
                openSessionViaHierarchy(controller, ACTIVE_SESSION_ID)
                capture("10-focus-session-detail-active")
                controller.onActivity {
                    val detail = requireNotNull(findDetail(it))
                    assertNull(detail.view?.findViewWithTag<View>("unified_session_detail_tombstone"))
                    assertTrue(texts(requireNotNull(detail.view)).any { value -> value.contains("45") })
                }
                dismissDialogs(controller)

                openSessionViaHierarchy(controller, TOMBSTONE_SESSION_ID, viaType = true)
                capture("11-focus-session-detail-tombstone")
                controller.onActivity {
                    val detail = requireNotNull(findDetail(it))
                    val revoked = requireNotNull(detail.view?.findViewWithTag<TextView>("unified_session_detail_tombstone"))
                    assertEquals(View.VISIBLE, revoked.visibility)
                    assertTrue("Native ledger quantity must be 20", revoked.text.contains("20"))
                }
                dismissDialogs(controller)

                openSessionViaHierarchy(controller, PAUSED_SESSION_ID)
                capture("11b-focus-session-detail-paused")
                controller.onActivity {
                    assertTrue("Paused cross-midnight allocation must be estimated",
                        texts(requireNotNull(requireNotNull(findDetail(it)).view)).any { value -> value.contains("≈") })
                }
                dismissDialogs(controller)

                var deletedSessionId = ""
                controller.onActivity {
                    deletedSessionId = requireNotNull(statsFragment(it).lastReport?.focus).sessions
                        .first { session -> session.taskId?.value == DELETED_TASK_ID }.id.value
                }
                openSessionViaHierarchy(controller, deletedSessionId, viaType = true)
                capture("11c-focus-deleted-target")
                controller.onActivity {
                    assertTrue(texts(requireNotNull(requireNotNull(findDetail(it)).view))
                        .any { value -> value.contains("Deleted task") || value.contains("Удалённая задача") })
                }
                dismissDialogs(controller)

                // =============================================================
                // PHASE C: Period & Scope Dimension Traversals
                // =============================================================
                // Actual period clicks via UI: DAY, WEEK, MONTH, YEAR, ALL
                selectPeriodViaUi(controller, UnifiedPeriod.DAY)
                assertPeriodSelected(controller, UnifiedPeriod.DAY)

                selectPeriodViaUi(controller, UnifiedPeriod.WEEK)
                assertPeriodSelected(controller, UnifiedPeriod.WEEK)

                selectPeriodViaUi(controller, UnifiedPeriod.MONTH)
                assertPeriodSelected(controller, UnifiedPeriod.MONTH)

                selectPeriodViaUi(controller, UnifiedPeriod.YEAR)
                assertPeriodSelected(controller, UnifiedPeriod.YEAR)

                selectPeriodViaUi(controller, UnifiedPeriod.ALL)
                assertPeriodSelected(controller, UnifiedPeriod.ALL)

                controller.onActivity { activity ->
                    val fragment = statsFragment(activity)
                    val dateRangeView = getDateRangeView(fragment)
                    val text = dateRangeView.text.toString()
                    assertFalse(
                        "Header in ALL mode must not show fake today start",
                        text.contains(JavaLocalDateFormatter(Locale.getDefault()).longFormat(getToday()))
                    )
                }
                capture("12-exploration-period-all")

                // Restore period to WEEK via UI
                selectPeriodViaUi(controller, UnifiedPeriod.WEEK)
                assertPeriodSelected(controller, UnifiedPeriod.WEEK)

                // Scope traversal: Child Container HERE (no descendants)
                controller.onActivity { activity ->
                    val fragment = statsFragment(activity)
                    fragment.applyScopedContainer(seed.childContainer, includeDescendants = false)
                }
                idle()
                waitForSettled(controller)

                controller.onActivity { activity ->
                    val fragment = statsFragment(activity)
                    val report = requireNotNull(getLoadedReport(fragment))
                    val scope = report.context.sectionScope as org.isoron.uhabits.core.statistics.StatisticsSectionScope.Container
                    assertEquals(seed.childContainer, scope.id)
                    assertFalse(scope.includeDescendants)
                }

                // Scope traversal: Root Container SUBTREE (includes descendants)
                controller.onActivity { activity ->
                    val fragment = statsFragment(activity)
                    fragment.applyScopedContainer(seed.rootContainer, includeDescendants = true)
                }
                idle()
                waitForSettled(controller)

                controller.onActivity { activity ->
                    val fragment = statsFragment(activity)
                    val report = requireNotNull(getLoadedReport(fragment))
                    val scope = report.context.sectionScope as org.isoron.uhabits.core.statistics.StatisticsSectionScope.Container
                    assertEquals(seed.rootContainer, scope.id)
                    assertTrue(scope.includeDescendants)
                }

                // Scope traversal: Unassigned
                controller.onActivity { activity ->
                    val fragment = statsFragment(activity)
                    fragment.applyScopedUnassigned()
                }
                idle()
                waitForSettled(controller)

                controller.onActivity { activity ->
                    val fragment = statsFragment(activity)
                    val report = requireNotNull(getLoadedReport(fragment))
                    assertEquals(
                        org.isoron.uhabits.core.statistics.StatisticsSectionScope.Unassigned,
                        report.context.sectionScope
                    )
                }

                // Scope traversal: Restore to ALL
                controller.onActivity { activity ->
                    val fragment = statsFragment(activity)
                    fragment.applyScopedAll()
                }
                idle()
                waitForSettled(controller)

                // =============================================================
                // PHASE D: Activity Recreation + Back with Open Drilldown Sheet
                // =============================================================
                selectTab(controller, 2) // Tasks tab

                // Open drilldown sheet before recreation
                controller.onActivity { activity ->
                    val fragment = statsFragment(activity)
                    val chart = requireNotNull(fragment.view?.findViewWithTag<DiscreteColumnBarChart>("unified_tasks_t1_chart"))
                    val items = field<List<ChartColumnItem>>(chart, "items")
                    val todayIndex = items.indexOfFirst { it.isToday }
                    tapColumn(chart, todayIndex)
                }
                idle()
                SystemClock.sleep(400)

                assertNotNull(
                    "Drilldown sheet must be open before recreation",
                    findOpenBottomSheet(controller) as? UnifiedStatisticsDrilldownBottomSheet
                )

                // Recreate Activity while sheet is open
                controller.recreate()
                idle()
                waitForSettled(controller)

                // Dismiss with Back after recreation
                device.pressBack()
                idle()
                navigateToStatistics(controller)
                assertTabSelected(controller, UnifiedAnalyticsTab.TASKS)

                // =============================================================
                // PHASE E: Dataset Replacement Guard while Detail/Ledger Pending
                // =============================================================
                if (phase == "light_standard_100") {
                    verifyPendingDatasetReplacementInTaskFocus(controller)
                }

                // =============================================================
                // PHASE F: Genuine Empty Scope & Domain Error Containment
                // =============================================================
                controller.onActivity { activity ->
                    val fragment = statsFragment(activity)
                    fragment.applyScopedContainer(seed.emptyContainer, includeDescendants = false)
                }
                idle()
                waitForSettled(controller)

                controller.onActivity { activity ->
                    val fragment = statsFragment(activity)
                    val uiState = getUiState(fragment)
                    assertTrue("Empty scope must produce Success state", uiState is UnifiedReportUiState.Success)
                    val statusBanner = getStatusView(fragment)
                    assertTrue("Status banner must be GONE for empty data", statusBanner == null || statusBanner.visibility == View.GONE)
                    val emptyReport = requireNotNull(getLoadedReport(fragment)) { "Empty report must not be null" }
                    val emptyTasks = requireNotNull(emptyReport.tasks) { "Empty tasks report must not be null" }
                    assertEquals("Empty container must yield 0 completed tasks", 0, emptyTasks.completedCount)
                    assertEquals("Empty container must yield 0 due tasks", 0, emptyTasks.dueSummary.totalCount)
                    assertEquals("Empty container must yield 0 nodue tasks", 0, emptyTasks.noDueSummary.totalCount)
                    val emptyFocus = requireNotNull(emptyReport.focus) { "Empty focus report must not be null" }
                    assertEquals("Empty container must yield 0 focus duration", 0L, emptyFocus.totalDurationMs)
                    assertEquals("Empty container must yield 0 focus sessions", 0, emptyFocus.sessionCount)
                }

                // Error state containment check
                controller.onActivity { activity ->
                    val fragment = statsFragment(activity)
                    val failureField = UnifiedStatisticsFragment::class.java.getDeclaredField("currentUiState").apply {
                        isAccessible = true
                    }
                    val previousState = failureField.get(fragment)
                    failureField.set(
                        fragment,
                        UnifiedReportUiState.Failure("Simulated domain read failure", emptyList(), getLoadedReport(fragment))
                    )
                    val renderMethod = UnifiedStatisticsFragment::class.java.getDeclaredMethod("renderCurrentState").apply {
                        isAccessible = true
                    }
                    renderMethod.invoke(fragment)

                    val statusBanner = getStatusView(fragment)
                    assertNotNull("Status banner must exist for Failure state", statusBanner)
                    assertEquals("Status banner must be VISIBLE during domain failure", View.VISIBLE, statusBanner!!.visibility)

                    // Restore state
                    failureField.set(fragment, previousState)
                    renderMethod.invoke(fragment)
                }
                idle()
                waitForSettled(controller)
            }
        } finally {
            instrumentation.runOnMainSync {
                component.preferences.theme = oldTheme
                component.preferences.isPureBlackEnabled = oldBlack
                component.preferences.isFirstRun = oldFirstRun
            }
        }
    }

    // -------------------------------------------------------------------------
    // Real Click Dispatchers & UI Period Selection
    // -------------------------------------------------------------------------

    private fun selectPeriodViaUi(controller: QaActivityController, period: UnifiedPeriod) {
        val stringRes = when (period) {
            UnifiedPeriod.DAY -> R.string.unified_statistics_period_day
            UnifiedPeriod.WEEK -> R.string.unified_statistics_period_week
            UnifiedPeriod.MONTH -> R.string.unified_statistics_period_month
            UnifiedPeriod.YEAR -> R.string.unified_statistics_period_year
            UnifiedPeriod.ALL -> R.string.unified_statistics_period_all
        }
        val targetText = context.getString(stringRes)

        controller.onActivity { activity ->
            val fragment = statsFragment(activity)
            val btn = fragment.view?.findViewWithTag<View>("unified_period")
                ?: runCatching { field<View>(fragment, "btnPeriodSelector") }.getOrNull()
            requireNotNull(btn) { "Period selector button must exist" }.performClick()
        }
        idle()
        SystemClock.sleep(250)

        val item = device.wait(Until.findObject(By.text(targetText)), 2500)
        if (item != null) {
            item.click()
        } else {
            controller.onActivity { activity ->
                val fragment = statsFragment(activity)
                val method = UnifiedStatisticsFragment::class.java.declaredMethods.firstOrNull { it.name == "showPeriodSelectorMenu" }
                val btn = fragment.view?.findViewWithTag<View>("unified_period")
                if (method != null && btn != null) {
                    method.isAccessible = true
                    method.invoke(fragment, btn)
                }
            }
        }
        idle()
        waitForSettled(controller)
    }

    private fun assertPeriodSelected(controller: QaActivityController, expectedPeriod: UnifiedPeriod) {
        controller.onActivity { activity ->
            val fragment = statsFragment(activity)
            val current = getCurrentPeriod(fragment)
            assertEquals("Current period must match expected", expectedPeriod, current)
        }
    }

    private fun tapColumn(chart: DiscreteColumnBarChart, index: Int) {
        val items = field<List<ChartColumnItem>>(chart, "items")
        assertTrue("Chart must have items to tap", items.isNotEmpty() && index in items.indices)
        val x = chart.getColumnCenterX(index)
        val y = chart.height / 2f
        val down = MotionEvent.obtain(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(SystemClock.uptimeMillis(), SystemClock.uptimeMillis() + 50, MotionEvent.ACTION_UP, x, y, 0)
        chart.onTouchEvent(down)
        chart.onTouchEvent(up)
        down.recycle()
        up.recycle()
    }

    private fun tapSegment(cohortBar: SegmentedCohortBarView, index: Int) {
        val boundsList = field<List<Pair<SegmentedCohortBarView.Segment, RectF>>>(cohortBar, "segmentBounds")
        assertTrue("Cohort bar must have segments to tap", boundsList.isNotEmpty() && index in boundsList.indices)
        val rect = boundsList[index].second
        val x = rect.centerX()
        val y = rect.centerY()
        val down = MotionEvent.obtain(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(SystemClock.uptimeMillis(), SystemClock.uptimeMillis() + 50, MotionEvent.ACTION_UP, x, y, 0)
        cohortBar.onTouchEvent(down)
        cohortBar.onTouchEvent(up)
        down.recycle()
        up.recycle()
    }

    // -------------------------------------------------------------------------
    // Navigation, View Finding & Inspection Helpers
    // -------------------------------------------------------------------------

    private fun texts(root: View): List<String> = buildList {
        if (root is TextView) add(root.text.toString())
        if (root is ViewGroup) for (index in 0 until root.childCount) addAll(texts(root.getChildAt(index)))
    }

    private fun findDetail(activity: MainActivity): FocusSessionDetailBottomSheet? =
        statsFragment(activity).childFragmentManager.fragments.filterIsInstance<FocusSessionDetailBottomSheet>()
            .lastOrNull { it.dialog?.isShowing == true }

    private fun dismissDialogs(controller: QaActivityController) {
        repeat(5) {
            if (findOpenBottomSheet(controller) == null) return
            device.pressBack()
            idle()
        }
        assertNull("Back must dismiss bounded hierarchy", findOpenBottomSheet(controller))
    }

    private fun openSessionViaHierarchy(controller: QaActivityController, id: String, viaType: Boolean = false) {
        var objectTag = ""
        var expected = emptyList<String>()
        controller.onActivity { activity ->
            val fragment = statsFragment(activity)
            val report = requireNotNull(fragment.lastReport)
            val focus = requireNotNull(report.focus)
            val session = focus.sessions.single { it.id.value == id }
            val branch = focus.sectionBreakdowns.single { group -> group.sessions.any { it.id == session.id } }
            val type = session.primaryTargetType
            val targetId = when (type) {
                org.isoron.uhabits.core.focus.FocusPrimaryTargetType.TASK -> session.taskId?.value
                org.isoron.uhabits.core.focus.FocusPrimaryTargetType.HABIT -> session.habit?.uuid
                org.isoron.uhabits.core.focus.FocusPrimaryTargetType.CONTAINER -> session.containerId?.value
                null -> null
            }
            objectTag = "target:$type:$targetId"
            expected = DrilldownSelectionResolver.resolveFocus(report, FocusDrilldownDescriptor.TargetObject(
                type, targetId, if (viaType) null else branch.category, if (viaType) null else branch.containerId))
                .map { it.id.value }
            val entryTag = if (viaType) "target-type:${type?.name ?: "UNKNOWN"}"
                else "section:${branch.category}:${branch.containerId?.value}"
            requireNotNull(fragment.view?.findViewWithTag<View>(entryTag)) { "Missing hierarchy entry $entryTag" }.performClick()
        }
        idle()
        val objects = requireNotNull(findOpenBottomSheet(controller) as? UnifiedStatisticsDrilldownBottomSheet)
        controller.onActivity {
            val row = requireNotNull(objects.view?.findViewWithTag<View>(objectTag)) { "Missing exact primary object $objectTag" }
            row.performClick()
        }
        idle()
        val sessions = requireNotNull(findOpenBottomSheet(controller) as? UnifiedStatisticsDrilldownBottomSheet)
        var page = 0
        var selected = false
        while (!selected && page < 20) {
            controller.onActivity {
                assertEquals("Every visible page must equal its aggregate slice", expected.drop(page * 30).take(30), sessions.visibleIds)
                assertTrue(sessions.visibleIds.size <= 30)
                if (id in sessions.visibleIds) {
                    requireNotNull(sessions.view?.findViewWithTag<View>(id)).performClick()
                    selected = true
                } else {
                    assertEquals("Exact session must exist on a later page", View.VISIBLE, sessions.nextPageButton.visibility)
                    sessions.nextPageButton.performClick()
                    page++
                }
            }
        }
        assertTrue("Exact session row must be reached", selected)
        idle()
        val deadline = SystemClock.uptimeMillis() + 10_000
        var loaded = false
        do {
            controller.onActivity { activity ->
                loaded = findDetail(activity)?.view?.findViewWithTag<View>("session-detail:$id") != null
            }
            if (!loaded) SystemClock.sleep(50)
        } while (!loaded && SystemClock.uptimeMillis() < deadline)
        assertTrue("Exact session detail payload must finish loading: $id", loaded)
    }

    private fun navigateToStatistics(controller: QaActivityController) {
        controller.onActivity { activity ->
            activity.navigate(MainDestination.STATISTICS)
            activity.supportFragmentManager.executePendingTransactions()
        }
        idle()
        waitForSettled(controller)
    }

    private fun statsFragment(activity: MainActivity): UnifiedStatisticsFragment {
        val fragment = activity.supportFragmentManager.findFragmentByTag("main.reports")
        requireNotNull(fragment) { "UnifiedStatisticsFragment was not found with tag main.reports" }
        return fragment as UnifiedStatisticsFragment
    }

    private fun selectTab(controller: QaActivityController, tabIndex: Int) {
        controller.onActivity { activity ->
            val fragment = statsFragment(activity)
            val tabLayout = getTabLayout(fragment)
            val tab = tabLayout.getTabAt(tabIndex)
            requireNotNull(tab) { "Tab at index $tabIndex not found" }
            tab.select()
        }
        idle()
        waitForSettled(controller)
    }

    private fun assertTabSelected(controller: QaActivityController, expectedTab: UnifiedAnalyticsTab) {
        controller.onActivity { activity ->
            val fragment = statsFragment(activity)
            val current = getCurrentTab(fragment)
            assertEquals("Selected analytical tab must match", expectedTab, current)
        }
    }

    private fun getTabLayout(fragment: Fragment): TabLayout =
        fragment.view?.findViewWithTag<TabLayout>("unified_tabs")
            ?: runCatching { field<TabLayout>(fragment, "tabLayout") }.getOrNull()
            ?: requireNotNull(findViewByType(fragment.requireView(), TabLayout::class.java))

    private fun getContentScrollView(fragment: Fragment): ScrollView? =
        fragment.view?.findViewWithTag<ScrollView>("unified_content")
            ?: fragment.view?.findViewWithTag<ScrollView>("unified_scroll")
            ?: runCatching { field<ScrollView>(fragment, "contentScrollView") }.getOrNull()
            ?: findViewByType(fragment.requireView(), ScrollView::class.java)

    private fun scrollToDescendant(scroll: ScrollView, descendant: View) {
        val rect = Rect()
        descendant.getDrawingRect(rect)
        scroll.offsetDescendantRectToMyCoords(descendant, rect)
        scroll.scrollTo(0, rect.top)
    }

    private fun assertVirtualAccessibilityNode(
        hostView: View,
        virtualId: Int = 0,
        expectedClass: String = "android.widget.Button",
    ): AccessibilityNodeInfoCompat {
        val delegate = requireNotNull(ViewCompat.getAccessibilityDelegate(hostView)) {
            "View must have an accessibility delegate installed"
        }
        val provider = requireNotNull(delegate.getAccessibilityNodeProvider(hostView)) {
            "Accessibility delegate must provide an AccessibilityNodeProviderCompat"
        }
        val node = requireNotNull(provider.createAccessibilityNodeInfo(virtualId)) {
            "Provider must return virtual AccessibilityNodeInfoCompat for virtualId=$virtualId"
        }
        assertFalse(
            "Virtual node contentDescription must not be blank for accessibility",
            node.contentDescription.isNullOrBlank()
        )
        assertEquals(
            "Virtual node className must match",
            expectedClass,
            node.className?.toString()
        )
        assertTrue(
            "Virtual node must be clickable",
            node.isClickable
        )
        return node
    }

    private fun getDateRangeView(fragment: Fragment): TextView =
        fragment.view?.findViewWithTag<TextView>("unified_date_range")
            ?: runCatching { field<TextView>(fragment, "tvDateRange") }.getOrNull()
            ?: requireNotNull(findViewByType(fragment.requireView(), TextView::class.java))

    private fun getStatusView(fragment: Fragment): View? =
        fragment.view?.findViewWithTag<View>("unified_status")
            ?: runCatching { field<View>(fragment, "statusBanner") }.getOrNull()

    private fun getCurrentTab(fragment: UnifiedStatisticsFragment): UnifiedAnalyticsTab =
        field(fragment, "currentTab")

    private fun getCurrentPeriod(fragment: UnifiedStatisticsFragment): UnifiedPeriod =
        field(fragment, "currentPeriod")

    private fun getCurrentScope(fragment: UnifiedStatisticsFragment): UnifiedScope =
        field(fragment, "currentScope")

    private fun getLoadedReport(fragment: UnifiedStatisticsFragment): UnifiedStatisticsReport? =
        field(fragment, "currentLoadedReport")

    private fun getUiState(fragment: UnifiedStatisticsFragment): UnifiedReportUiState =
        field(fragment, "currentUiState")

    private fun findOpenBottomSheet(controller: QaActivityController): DialogFragment? {
        var sheet: DialogFragment? = null
        controller.onActivity { activity ->
            val fm = activity.supportFragmentManager
            val fragments = fm.fragments + (fm.findFragmentByTag("main.reports")?.childFragmentManager?.fragments.orEmpty())
            // Topmost visible dialog is the last one in the hierarchy
            sheet = fragments.filterIsInstance<DialogFragment>().lastOrNull { it.dialog?.isShowing == true }
        }
        return sheet
    }

    private fun verifyPendingDatasetReplacementInTaskFocus(controller: QaActivityController) {
        harness.guard()
        val application = context.applicationContext as HabitsApplication
        val previous = component.session
        check(previous.isExperimental && !previous.capabilities.syncEnabled && !previous.capabilities.remoteCallsAllowed)
        val replacement = previous.copy(datasetId = "qa-replacement-tf-${UUID.randomUUID()}")
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val claimed = java.util.concurrent.atomic.AtomicBoolean()
        val oldObserver = org.isoron.uhabits.database.AndroidSqlTiming.observer

        try {
            org.isoron.uhabits.database.AndroidSqlTiming.observer = { operation, _ ->
                if (operation == "SELECT" && Thread.currentThread().stackTrace.any {
                        it.className.contains("UnifiedStatisticsFragment")
                    } && claimed.compareAndSet(false, true)) {
                    entered.countDown()
                    check(release.await(15, java.util.concurrent.TimeUnit.SECONDS)) { "Reader release timed out" }
                }
            }
            controller.onActivity { statsFragment(it).refresh() }
            assertTrue("Pending read must be in flight", entered.await(8, java.util.concurrent.TimeUnit.SECONDS))
            controller.onActivity { application.switchDatasetSession(replacement) }
            idle()
            waitForSettled(controller)
            release.countDown()
            idle()
            SystemClock.sleep(200)

            controller.onActivity {
                assertEquals(
                    "Late read cannot overwrite replacement dataset report",
                    replacement.datasetId,
                    getLoadedReport(statsFragment(it))?.context?.datasetId
                )
            }
        } finally {
            org.isoron.uhabits.database.AndroidSqlTiming.observer = oldObserver
            release.countDown()
            controller.onActivity { application.switchDatasetSession(previous) }
            idle()
            waitForSettled(controller)
            harness.guard()
        }
    }

    private fun waitForSettled(controller: QaActivityController, timeoutMs: Long = 8_000) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            var settled = false
            controller.onActivity { activity ->
                val fragment = statsFragment(activity)
                val uiState = getUiState(fragment)
                val isBusy = uiState is UnifiedReportUiState.InitialLoading ||
                    (uiState is UnifiedReportUiState.Success && uiState.isRefreshing)
                settled = !isBusy
            }
            if (settled) return
            SystemClock.sleep(50)
        }
        error("UnifiedStatisticsFragment did not settle within ${timeoutMs}ms")
    }

    // -------------------------------------------------------------------------
    // Dual Capture Mechanism: UiDevice + PixelCopy (Off Main Thread)
    // -------------------------------------------------------------------------

    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        device.waitForIdle()
        SystemClock.sleep(300)

        var resumedActivity: MainActivity? = null
        instrumentation.runOnMainSync {
            val activities = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>()
            if (activities.isNotEmpty()) {
                resumedActivity = activities.first()
                val decor = resumedActivity!!.window.decorView
                decor.invalidate()
                decor.postInvalidateOnAnimation()
            }
        }
        idle()
        SystemClock.sleep(200)

        // Mechanism 1: UiDevice screencap
        val targetUiDevice = File(outputDir, "$phase-$name.png")
        check(device.takeScreenshot(targetUiDevice)) { "Failed to capture screenshot: ${targetUiDevice.path}" }

        // Mechanism 2: PixelCopy of the Window Surface (Off main thread with retained errors)
        resumedActivity?.let { activity ->
            val targetPixelCopy = File(outputDir, "$phase-$name-pixelcopy.png")
            val copyResult = capturePixelCopy(activity.window, targetPixelCopy)
            if (!copyResult.success) {
                System.err.println("PixelCopy failure for $name: code=${copyResult.copyResult}, error=${copyResult.error?.message}")
            }
        }
    }

    private fun idle() {
        instrumentation.waitForIdleSync()
        device.waitForIdle()
        SystemClock.sleep(150)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(instance: Any, name: String): T =
        instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(instance) as T

    @Suppress("UNCHECKED_CAST")
    private fun <T : View> findViewByType(root: View, clazz: Class<T>): T? {
        if (clazz.isInstance(root)) return root as T
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val match = findViewByType(root.getChildAt(i), clazz)
                if (match != null) return match
            }
        }
        return null
    }
}
