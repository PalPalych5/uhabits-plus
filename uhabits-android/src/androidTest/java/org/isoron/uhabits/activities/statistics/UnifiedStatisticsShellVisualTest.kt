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
import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.google.android.material.tabs.TabLayout
import org.isoron.platform.time.JavaLocalDateFormatter
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.getToday
import org.isoron.platform.time.setToday
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.common.theme.MainTabsThemeBridge
import org.isoron.uhabits.activities.main.MainActivity
import org.isoron.uhabits.activities.main.MainDestination
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
import org.isoron.uhabits.core.userTasks.TaskStatus
import org.isoron.uhabits.core.userTasks.UserTask
import org.isoron.uhabits.core.userTasks.due.TaskDue
import org.isoron.uhabits.core.userTasks.due.toTaskDueDate
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * PR14.2 independent isolated Android visual and functional QA test for Unified Statistics.
 * Reuses existing disposable offline gates and verifies four analytical tabs, period & scope
 * pickers, runtime accessibility (>=48dp click targets, unclipped text), state preservation,
 * and live invalidation across Light, Dark, AMOLED, Standard, and Narrow 320dp layouts.
 */
@RunWith(AndroidJUnit4::class)
class UnifiedStatisticsShellVisualTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val args = InstrumentationRegistry.getArguments()
    private val context get() = instrumentation.targetContext
    private val component get() = HabitsApplication.component
    private val device = UiDevice.getInstance(instrumentation)
    private val harness = TaskDraftLifecycleTest()

    private val phase = args.getString("phase", "light_standard_100")!!.also {
        check(it.matches(Regex("^(light|dark|amoled)_(standard|narrow320)_(100|200)$")))
    }

    private val outputDir get() = File(context.getExternalFilesDir(null), "unified-statistics-qa").apply {
        check(mkdirs() || isDirectory) { "Failed to create evidence directory: $path" }
    }

    companion object {
        const val ROOT_CONTAINER_WORK = "Работа и проекты"
        const val CHILD_CONTAINER_DEV = "Разработка Loop"
        const val CONTAINER_HEALTH = "Здоровье и спорт"
        const val CONTAINER_EMPTY = "Пустой раздел QA"

        const val HABIT_BOOLEAN_TITLE = "Утренняя разминка"
        const val HABIT_NUMERICAL_TITLE = "Фокусная работа"
        const val HABIT_UNASSIGNED_TITLE = "Чтение документации"

        const val TASK_TIMED_TITLE = "Релиз Unified Statistics"
        const val TASK_COMPLETED_TITLE = "Подготовить отчёт QA"
        const val TASK_OVERDUE_TITLE = "Проверить документацию"
        const val TASK_NODUE_TITLE = "Идея новой визуализации"
    }

    @Test
    fun shellJourney() {
        // 1. Safety and offline disposable gates reuse
        TaskListVisualTest::class.java.getDeclaredMethod("bootstrapDisposableSession")
            .apply { isAccessible = true }.invoke(TaskListVisualTest())
        harness.guard()

        val oldTheme = component.preferences.theme
        val oldBlack = component.preferences.isPureBlackEnabled
        val oldFirstRun = component.preferences.isFirstRun

        try {
            // 2. Setup theme according to phase prefix
            val isAmoled = phase.startsWith("amoled")
            val isDark = phase.startsWith("dark") || isAmoled
            component.preferences.theme = if (isDark) ThemeSwitcher.THEME_DARK else ThemeSwitcher.THEME_LIGHT
            component.preferences.isPureBlackEnabled = isAmoled
            component.preferences.isFirstRun = false

            // Ensure screen is active and keyguard dismissed
            device.wakeUp()
            device.executeShellCommand("wm dismiss-keyguard")
            idle()

            // 3. Seed synthetic habits, tasks, containers, focus, ledger preserving schema 35
            val seedResult = seedSyntheticData()

            // 4. Launch MainActivity with QaActivityController
            QaActivityController().use { controller ->
                navigateToStatistics(controller)

                // 5. Assert actual Activity resources.configuration.fontScale, width dp, and themes
                controller.onActivity { activity ->
                    val config = activity.resources.configuration
                    val expectedFontScale = args.getString("expectedFontScale", "1.0")!!.toFloat()
                    assertEquals("fontScale must match expected", expectedFontScale, config.fontScale, 0.05f)

                    val expectedWidthDp = args.getString("expectedWidthDp", "0")!!.toInt()
                    if (expectedWidthDp == 320) {
                        assertTrue(
                            "Narrow configuration screenWidthDp must be 320dp (allow 1dp), actual=${config.screenWidthDp}",
                            kotlin.math.abs(config.screenWidthDp - 320) <= 1
                        )
                    }

                    val themeBridge = MainTabsThemeBridge.resolve(activity)
                    if (isDark) {
                        assertTrue("Resolved theme bridge must be dark", themeBridge.isDark)
                        assertEquals(ThemeSwitcher.THEME_DARK, component.preferences.theme)
                        if (isAmoled) {
                            assertTrue("Pure black must be enabled", component.preferences.isPureBlackEnabled)
                        } else {
                            assertFalse("Pure black must be disabled", component.preferences.isPureBlackEnabled)
                        }
                    } else {
                        assertFalse("Resolved theme bridge must be light", themeBridge.isDark)
                        assertEquals(ThemeSwitcher.THEME_LIGHT, component.preferences.theme)
                        assertFalse("Pure black must be disabled", component.preferences.isPureBlackEnabled)
                    }
                }

                waitForSettled(controller)

                // 6. Assert click controls >= 48dp
                assertMinimumTouchTargets(controller)

                // 7. Assert no clipped text and no touch overlap on visible views
                assertVisibleTextGeometry(controller)

                // Regression: Cold initial scoped fragment resolution and saved state precedence
                verifyColdInitialScopedFragment(controller, seedResult)

                // Regression: Period ALL header shows resolved history start (not fake today) and neutral while unresolved
                verifyAllPeriodHeaderAndBounds(controller, seedResult)

                // Responsive controlBar verification with long scope title under narrow/200% font scale
                verifyLongScopeHeaderGeometry(controller, seedResult)

                // 8. Capture four analytical tabs:
                // Tab 0: Overview
                selectTab(controller, 0)
                assertTabSelected(controller, UnifiedAnalyticsTab.OVERVIEW)
                capture("01-overview")

                // Tab 1: Habits
                selectTab(controller, 1)
                assertTabSelected(controller, UnifiedAnalyticsTab.HABITS)
                capture("02-habits")

                // Tab 2: Tasks
                selectTab(controller, 2)
                assertTabSelected(controller, UnifiedAnalyticsTab.TASKS)
                capture("03-tasks")

                // Tab 3: Focus
                selectTab(controller, 3)
                assertTabSelected(controller, UnifiedAnalyticsTab.FOCUS)
                capture("04-focus")

                // Return to Overview
                selectTab(controller, 0)

                // 9. Capture open period controls
                openPeriodMenu(controller)
                capture("05-period-picker")
                device.pressBack()
                idle()

                // 10. Capture open scope controls
                openScopeBottomSheet(controller)
                capture("06-scope-picker")
                device.pressBack()
                idle()

                // 11. Assert report context matches latest filters
                verifyScopePickerAndRapidScopeChanges(controller, seedResult)
                controller.onActivity { activity ->
                    val fragment = statsFragment(activity)
                    val report = getLoadedReport(fragment)
                    assertNotNull("Loaded report must not be null", report)
                    val curScope = getCurrentScope(fragment)
                    assertEquals(
                        "Report sectionScope must match current UI scope",
                        curScope.toStatisticsSectionScope(),
                        report!!.context.sectionScope
                    )
                }

                // 12. Runtime navigation away, back, and Activity recreate state preservation
                // Switch to Tasks analytical tab
                selectTab(controller, 2)
                assertTabSelected(controller, UnifiedAnalyticsTab.TASKS)

                // Navigate away to TASKS workspace
                controller.onActivity { activity ->
                    activity.navigate(MainDestination.TASKS)
                    activity.supportFragmentManager.executePendingTransactions()
                }
                idle()

                // Navigate back to STATISTICS
                navigateToStatistics(controller)
                assertTabSelected(controller, UnifiedAnalyticsTab.TASKS)

                // Recreate Activity for configuration / process change simulation
                controller.recreate()
                idle()
                navigateToStatistics(controller)
                assertTabSelected(controller, UnifiedAnalyticsTab.TASKS)

                // 13. Fast period toggles with pending read stale prevention
                selectTab(controller, 0)
                controller.onActivity { activity ->
                    val fragment = statsFragment(activity)
                    val prev = getPrevButton(fragment)
                    val next = getNextButton(fragment)
                    // Rapid clicks: go back twice, forward once (leaves view on past period)
                    prev.performClick()
                    prev.performClick()
                    next.performClick()
                }
                idle()
                waitForSettled(controller)

                controller.onActivity { activity ->
                    val fragment = statsFragment(activity)
                    val report = getLoadedReport(fragment)
                    assertNotNull("Report must settle after fast toggles", report)
                    val anchor = getAnchorDate(fragment)
                    val period = getCurrentPeriod(fragment)
                    val range = UnifiedStatisticsStateUtils.calculatePeriodRange(
                        period, anchor, getToday(), component.preferences.firstWeekday
                    )
                    assertEquals("Report from date must match final settled range", range.first, report!!.context.from)
                    assertEquals("Report to date must match final settled range", range.second, report.context.to)
                }

                // 14. Reset to latest period before inserting, then verify live data mutation invalidation
                controller.onActivity { activity ->
                    val fragment = statsFragment(activity)
                    val next = getNextButton(fragment)
                    next.performClick()
                }
                idle()
                waitForSettled(controller)

                val preMutationCount = getLoadedReportCount(controller)
                val taskNow = System.currentTimeMillis()
                controller.onActivity {
                    val taskRepo = component.userTaskRepository
                    val newTask = component.userTaskService.createTask(
                        "QA Live Invalidation Task ${UUID.randomUUID()}"
                    )
                    taskRepo.save(newTask.copy(status = TaskStatus.COMPLETED, completedAt = taskNow))
                }
                idle()
                waitForSettled(controller)

                controller.onActivity { activity ->
                    val fragment = statsFragment(activity)
                    val report = getLoadedReport(fragment)
                    assertNotNull("Report must reload upon mutation", report)
                    val postTasks = report?.tasks?.completedCount ?: 0
                    assertTrue(
                        "Completed tasks count must increase after current-period mutation: pre=$preMutationCount, post=$postTasks",
                        postTasks > preMutationCount
                    )
                }

                // 15. Real unlinked Focus insertion advances workspaceReadRevision and report count
                var preRevision = 0L
                var preFocusCount = 0
                controller.onActivity { activity ->
                    preRevision = component.workspaceReadRevision.value
                    val report = getLoadedReport(statsFragment(activity))
                    preFocusCount = report?.focus?.sessionCount ?: 0
                }

                val focusNow = System.currentTimeMillis()
                val unlinkedSession = FocusSession(
                    id = FocusSessionId("fs-unified-qa-unlinked-${UUID.randomUUID()}"),
                    startedAt = focusNow - 1_200_000L,
                    endedAt = focusNow,
                    durationMs = 1_200_000L,
                    taskId = null,
                    habit = null,
                    containerId = null,
                    source = FocusSessionSource.TIMER,
                    createdAt = focusNow,
                    updatedAt = focusNow
                )
                val focusRepo = component.focusSessionRepository ?: SQLiteFocusSessionRepository(component.db)
                focusRepo.record(unlinkedSession)

                val postRevision = component.workspaceReadRevision.value
                assertTrue(
                    "workspaceReadRevision must advance after focus insertion: pre=$preRevision, post=$postRevision",
                    postRevision > preRevision
                )

                idle()
                waitForSettled(controller)

                controller.onActivity { activity ->
                    val fragment = statsFragment(activity)
                    val report = getLoadedReport(fragment)
                    assertNotNull("Report must reload after focus insertion", report)
                    val postFocusCount = report?.focus?.sessionCount ?: 0
                    assertTrue(
                        "Focus session count must increase after unlinked focus insertion: pre=$preFocusCount, post=$postFocusCount",
                        postFocusCount > preFocusCount
                    )
                }

                // 16. Explicit empty / error distinction
                if (phase == "light_standard_100") {
                    verifyPendingDatasetReplacement(controller)
                    verifyOpenDialogDatasetSwitchRegression(controller, seedResult)
                    verifyDomainReadFailure(controller)
                }
                controller.onActivity { activity ->
                    val fragment = statsFragment(activity)
                    fragment.applyScopedContainer(seedResult.emptyContainer, includeDescendants = false)
                }
                idle()
                waitForSettled(controller)

                controller.onActivity { activity ->
                    val fragment = statsFragment(activity)
                    val uiState = getUiState(fragment)
                    assertTrue("Empty scope must produce a valid state (not failure)", uiState is UnifiedReportUiState.Success)
                    val statusBanner = getStatusView(fragment)
                    assertTrue("Error banner must be GONE for empty data", statusBanner == null || statusBanner.visibility == View.GONE)
                }
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
    // Synthetic Data Seeding (Schema 35 Preserving)
    // -------------------------------------------------------------------------

    internal data class SeedingResult(
        val rootContainer: ContainerId,
        val childContainer: ContainerId,
        val emptyContainer: ContainerId,
        val booleanHabit: Habit,
        val numericalHabit: Habit,
        val timedTask: UserTask,
    )

    internal fun seedSyntheticData(): SeedingResult {
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

        // 1. Daily Yes/No Habit in root container (constructed via factory.buildHabit())
        var habitBool = habitList.firstOrNull { it.name == HABIT_BOOLEAN_TITLE }
        if (habitBool == null) {
            habitBool = factory.buildHabit().apply {
                uuid = UUID.randomUUID().toString()
                name = HABIT_BOOLEAN_TITLE
                description = "QA Boolean Habit Description"
                frequency = Frequency.DAILY
                type = HabitType.YES_NO
                color = PaletteColor(0)
            }
            habitList.add(habitBool)
        }
        orgFacade.placeHabit(requireNotNull(habitBool.uuid), rootId)
        val entries = habitBool.originalEntries
        entries.add(Entry(today, Entry.YES_MANUAL))
        entries.add(Entry(today.minus(1), Entry.YES_MANUAL))
        entries.add(Entry(today.minus(2), Entry.NO))

        // 2. Daily Numerical Habit (minutes) in child container (constructed via factory.buildHabit())
        var habitNum = habitList.firstOrNull { it.name == HABIT_NUMERICAL_TITLE }
        if (habitNum == null) {
            habitNum = factory.buildHabit().apply {
                uuid = UUID.randomUUID().toString()
                name = HABIT_NUMERICAL_TITLE
                description = "QA Numerical Habit (Minutes)"
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

        // 3. UserTasks seeding (with Schema 35 linked habit columns)
        val taskRepo = component.userTaskRepository
        val taskService = component.userTaskService

        var taskTimed = taskRepo.getRoots().firstOrNull { it.title == TASK_TIMED_TITLE }
        if (taskTimed == null) {
            val zone = ZoneId.of("Asia/Novosibirsk")
            val dueMillis = java.time.LocalDate.of(today.year, today.month, today.day)
                .atTime(15, 0).atZone(zone).toInstant().toEpochMilli()
            taskTimed = taskService.createTask(
                TASK_TIMED_TITLE,
                containerId = childId,
                due = TaskDue.Timed.local(dueMillis, "Asia/Novosibirsk"),
                linkedHabitUuid = habitNum.uuid,
                habitTimeAccrualEnabled = true,
                habitLinkEffectiveFrom = now - 7_200_000L
            )
        }

        var taskCompleted = taskRepo.getRoots().firstOrNull { it.title == TASK_COMPLETED_TITLE }
        if (taskCompleted == null) {
            taskCompleted = taskService.createTask(
                TASK_COMPLETED_TITLE,
                containerId = rootId,
                due = TaskDue.Day(today.toTaskDueDate())
            )
            taskRepo.save(taskCompleted.copy(status = TaskStatus.COMPLETED, completedAt = now - 3_600_000L))
        }

        var taskOverdue = taskRepo.getRoots().firstOrNull { it.title == TASK_OVERDUE_TITLE }
        if (taskOverdue == null) {
            taskOverdue = taskService.createTask(
                TASK_OVERDUE_TITLE,
                containerId = childId,
                due = TaskDue.Day(today.minus(1).toTaskDueDate())
            )
        }

        var taskNoDue = taskRepo.getRoots().firstOrNull { it.title == TASK_NODUE_TITLE }
        if (taskNoDue == null) {
            taskNoDue = taskService.createTask(TASK_NODUE_TITLE, containerId = rootId)
        }

        // 4. Focus Sessions (Real API: no primaryTargetId)
        val focusRepo = component.focusSessionRepository ?: SQLiteFocusSessionRepository(component.db)
        focusRepo.record(
            FocusSession(
                id = FocusSessionId("fs-unified-qa-task-45m"),
                startedAt = now - 2_700_000L,
                endedAt = now,
                durationMs = 2_700_000L,
                taskId = taskTimed.id,
                containerId = childId,
                source = FocusSessionSource.TIMER,
                createdAt = now,
                updatedAt = now
            )
        )
        focusRepo.record(
            FocusSession(
                id = FocusSessionId("fs-unified-qa-container-30m"),
                startedAt = now - 5_400_000L,
                endedAt = now - 3_600_000L,
                durationMs = 1_800_000L,
                containerId = rootId,
                source = FocusSessionSource.TIMER,
                createdAt = now,
                updatedAt = now
            )
        )

        // 5. Ledger Contributions (Schema 35, record method, native Long x1000 scale, timestamps)
        val contribRepo = component.contributionRepository ?: SQLiteContributionRepository(component.db)
        contribRepo.record(
            Contribution(
                id = ContributionId("contrib-unified-qa-45m"),
                targetHabit = HabitRef(requireNotNull(habitNum.uuid)),
                sourceType = ContributionSourceType.FOCUS_SESSION,
                sourceId = "fs-unified-qa-task-45m",
                contributedValue = 45L * 1000L,
                effectiveTimestamp = now,
                createdAt = now,
                updatedAt = now
            )
        )

        return SeedingResult(rootId, childId, emptyId, habitBool, habitNum, taskTimed)
    }

    // -------------------------------------------------------------------------
    // Navigation & Fragment Helpers
    // -------------------------------------------------------------------------

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

    private fun openPeriodMenu(controller: QaActivityController) {
        controller.onActivity { activity ->
            val fragment = statsFragment(activity)
            getPeriodButton(fragment).performClick()
        }
        idle()
        SystemClock.sleep(300)
    }

    private fun openScopeBottomSheet(controller: QaActivityController) {
        controller.onActivity { activity ->
            val fragment = statsFragment(activity)
            getScopeButton(fragment).performClick()
            fragment.childFragmentManager.executePendingTransactions()
        }
        idle()
        val titleText = context.getString(R.string.unified_statistics_filters_title)
        device.wait(Until.hasObject(By.text(titleText)), 3000)
        SystemClock.sleep(300)
    }

    // -------------------------------------------------------------------------
    // View Resolvers (Resilient against View Tags or Field Reflection)
    // -------------------------------------------------------------------------

    private fun getTabLayout(fragment: Fragment): TabLayout =
        fragment.view?.findViewWithTag<TabLayout>("unified_tabs")
            ?: runCatching { field<TabLayout>(fragment, "tabLayout") }.getOrNull()
            ?: requireNotNull(findViewByType(fragment.requireView(), TabLayout::class.java))

    private fun getPeriodButton(fragment: Fragment): View =
        fragment.view?.findViewWithTag<View>("unified_period")
            ?: fragment.view?.findViewWithTag<View>("period")
            ?: runCatching { field<View>(fragment, "btnPeriodSelector") }.getOrNull()
            ?: requireNotNull(findViewByContentOrClass(fragment.requireView(), "period"))

    private fun getScopeButton(fragment: Fragment): View =
        fragment.view?.findViewWithTag<View>("unified_scope")
            ?: fragment.view?.findViewWithTag<View>("scope")
            ?: runCatching { field<View>(fragment, "btnScopeSelector") }.getOrNull()
            ?: requireNotNull(findViewByContentOrClass(fragment.requireView(), "scope"))

    private fun getPrevButton(fragment: Fragment): View =
        fragment.view?.findViewWithTag<View>("unified_prev")
            ?: fragment.view?.findViewWithTag<View>("prev")
            ?: runCatching { field<View>(fragment, "btnPrevPeriod") }.getOrNull()
            ?: requireNotNull(findViewByContent(fragment.requireView(), "Previous"))

    private fun getNextButton(fragment: Fragment): View =
        fragment.view?.findViewWithTag<View>("unified_next")
            ?: fragment.view?.findViewWithTag<View>("next")
            ?: runCatching { field<View>(fragment, "btnNextPeriod") }.getOrNull()
            ?: requireNotNull(findViewByContent(fragment.requireView(), "Next"))

    private fun getLegacyButton(fragment: Fragment): View =
        fragment.view?.findViewWithTag<View>("unified_legacy")
            ?: fragment.view?.findViewWithTag<View>("legacy")
            ?: runCatching { field<View>(fragment, "btnLegacy") }.getOrNull()
            ?: requireNotNull(findViewByContent(fragment.requireView(), "legacy"))

    private fun getDateRangeView(fragment: Fragment): TextView =
        fragment.view?.findViewWithTag<TextView>("unified_date_range")
            ?: runCatching { field<TextView>(fragment, "tvDateRange") }.getOrNull()
            ?: requireNotNull(findViewByType(fragment.requireView(), TextView::class.java))

    private fun getStatusView(fragment: Fragment): View? =
        fragment.view?.findViewWithTag<View>("unified_status")
            ?: fragment.view?.findViewWithTag<View>("status")
            ?: runCatching { field<View>(fragment, "statusBanner") }.getOrNull()

    private fun getContentContainer(fragment: Fragment): ViewGroup =
        fragment.view?.findViewWithTag<ViewGroup>("unified_content")
            ?: fragment.view?.findViewWithTag<ViewGroup>("content")
            ?: runCatching { field<ViewGroup>(fragment, "contentContainer") }.getOrNull()
            ?: requireNotNull(findViewByType(fragment.requireView(), ViewGroup::class.java))

    private fun getCurrentTab(fragment: UnifiedStatisticsFragment): UnifiedAnalyticsTab =
        field(fragment, "currentTab")

    private fun getCurrentPeriod(fragment: UnifiedStatisticsFragment): UnifiedPeriod =
        field(fragment, "currentPeriod")

    private fun getCurrentScope(fragment: UnifiedStatisticsFragment): UnifiedScope =
        field(fragment, "currentScope")

    private fun getAnchorDate(fragment: UnifiedStatisticsFragment): LocalDate =
        field(fragment, "currentAnchorDate")

    private fun getLoadedReport(fragment: UnifiedStatisticsFragment): UnifiedStatisticsReport? =
        field(fragment, "currentLoadedReport")

    private fun getUiState(fragment: UnifiedStatisticsFragment): UnifiedReportUiState =
        field(fragment, "currentUiState")

    private fun getActiveReportGeneration(fragment: UnifiedStatisticsFragment): Int =
        field(fragment, "activeReportGeneration")

    private fun getLoadedReportCount(controller: QaActivityController): Int {
        var count = 0
        controller.onActivity { activity ->
            val report = getLoadedReport(statsFragment(activity))
            count = report?.tasks?.completedCount ?: 0
        }
        return count
    }

    // -------------------------------------------------------------------------
    // View Hierarchy Finders
    // -------------------------------------------------------------------------

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

    private fun findViewByContent(root: View, text: String): View? {
        if (root.contentDescription?.contains(text, ignoreCase = true) == true) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val match = findViewByContent(root.getChildAt(i), text)
                if (match != null) return match
            }
        }
        return null
    }

    private fun findViewByContentOrClass(root: View, hint: String): View? {
        if (root.contentDescription?.contains(hint, ignoreCase = true) == true) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val match = findViewByContentOrClass(root.getChildAt(i), hint)
                if (match != null) return match
            }
        }
        return null
    }

    // -------------------------------------------------------------------------
    // UI & Accessibility Assertions
    // -------------------------------------------------------------------------

    private fun assertMinimumTouchTargets(controller: QaActivityController) {
        controller.onActivity { activity ->
            val fragment = statsFragment(activity)
            val interactiveViews = listOf(
                getPrevButton(fragment),
                getNextButton(fragment),
                getPeriodButton(fragment),
                getScopeButton(fragment),
                getLegacyButton(fragment)
            )
            val density = activity.resources.displayMetrics.density
            val rect = android.graphics.Rect()
            for (v in interactiveViews) {
                assertTrue("View ${v.tag ?: v.javaClass.simpleName} must be shown", v.isShown)
                val isGloballyVisible = v.getGlobalVisibleRect(rect)
                assertTrue(
                    "View ${v.tag ?: v.javaClass.simpleName} must be globally visible on screen",
                    isGloballyVisible && rect.width() > 0 && rect.height() > 0
                )

                val renderedWDp = v.width / density
                val renderedHDp = v.height / density
                val visibleWDp = rect.width() / density
                val visibleHDp = rect.height() / density

                assertTrue(
                    "Click target ${v.tag ?: v.javaClass.simpleName} rendered width must be >= 48dp, actual=${renderedWDp}dp",
                    renderedWDp >= 47.5f
                )
                assertTrue(
                    "Click target ${v.tag ?: v.javaClass.simpleName} rendered height must be >= 48dp, actual=${renderedHDp}dp",
                    renderedHDp >= 47.5f
                )
                assertTrue(
                    "Click target ${v.tag ?: v.javaClass.simpleName} visible width must be >= 48dp, actual=${visibleWDp}dp",
                    visibleWDp >= 47.5f
                )
                assertTrue(
                    "Click target ${v.tag ?: v.javaClass.simpleName} visible height must be >= 48dp, actual=${visibleHDp}dp",
                    visibleHDp >= 47.5f
                )
            }

            val tabLayout = getTabLayout(fragment)
            assertTrue("TabLayout height must be >= 48dp", tabLayout.height / density >= 47.5f)
        }
    }

    private fun assertVisibleTextGeometry(controller: QaActivityController) {
        controller.onActivity { activity ->
            val fragment = statsFragment(activity)
            val content = getContentContainer(fragment)
            val textViews = mutableListOf<TextView>()

            fun collect(v: View) {
                if (v is TextView && v.isShown && v.width > 0 && v.height > 0) textViews.add(v)
                if (v is ViewGroup) {
                    for (i in 0 until v.childCount) collect(v.getChildAt(i))
                }
            }
            collect(content)

            for (tv in textViews) {
                val layout = tv.layout ?: continue
                val text = tv.text.toString()
                if (text.isBlank()) continue

                assertTrue("TextView must have line count > 0: '$text'", layout.lineCount > 0)
                val textH = layout.height
                val viewH = tv.height - tv.compoundPaddingTop - tv.compoundPaddingBottom
                assertTrue(
                    "Visible text must fit within TextView height: textH=$textH vs viewH=$viewH ('$text')",
                    textH <= viewH + 2
                )
            }
        }
    }

    // -------------------------------------------------------------------------
    // Settlement, Sync & Screenshot Helpers
    // -------------------------------------------------------------------------

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

        val target = File(outputDir, "$phase-$name.png")
        check(device.takeScreenshot(target)) { "Failed to capture screenshot: ${target.path}" }

        // Second capture mechanism (PixelCopy) for Habit debt and frame comparison
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

    private fun verifyPendingDatasetReplacement(controller: QaActivityController) {
        harness.guard()
        val application = context.applicationContext as HabitsApplication
        val previous = component.session
        check(previous.isExperimental && !previous.capabilities.syncEnabled && !previous.capabilities.remoteCallsAllowed)

        val oldTimer = component.midnightTimer
        val oldExecutorField = org.isoron.uhabits.core.utils.MidnightTimer::class.java
            .getDeclaredField("executor").apply { isAccessible = true }
        val oldExecutor = requireNotNull(oldExecutorField.get(oldTimer) as? java.util.concurrent.ScheduledExecutorService)

        // A new dataset identity over the SAME guarded disposable file exercises component replacement
        // without opening a production database or creating/replacing any data file.
        val replacement = previous.copy(datasetId = "qa-replacement-${UUID.randomUUID()}")
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
                    check(release.await(15, java.util.concurrent.TimeUnit.SECONDS)) { "Pending reader release timed out" }
                }
            }
            controller.onActivity { statsFragment(it).refresh() }
            assertTrue("Old dataset read must really be in flight", entered.await(8, java.util.concurrent.TimeUnit.SECONDS))
            controller.onActivity {
                val state = getUiState(statsFragment(it))
                assertTrue("Existing context must show refreshing while its reader is held", state is UnifiedReportUiState.Success && state.isRefreshing)
            }
            capture("07-refreshing")
            controller.onActivity { application.switchDatasetSession(replacement) }
            idle()
            waitForSettled(controller)
            controller.onActivity {
                assertEquals(replacement.datasetId, getLoadedReport(statsFragment(it))?.context?.datasetId)
                assertEquals(UnifiedScope.All, getCurrentScope(statsFragment(it)))

                val newTimer = component.midnightTimer
                val newExecutor = requireNotNull(oldExecutorField.get(newTimer) as? java.util.concurrent.ScheduledExecutorService)
                val activeActivityTimer = field<org.isoron.uhabits.core.utils.MidnightTimer?>(it, "activeMidnightTimer")

                assertNotSame("Component MidnightTimer must be replaced on dataset session switch", oldTimer, newTimer)
                assertTrue("Old MidnightTimer executor must be shut down", oldExecutor.isShutdown)
                assertSame("MainActivity activeMidnightTimer must point to new timer", newTimer, activeActivityTimer)
                assertFalse("New MidnightTimer executor must not be shut down", newExecutor.isShutdown)
            }
            release.countDown()
            idle()
            SystemClock.sleep(200)
            controller.onActivity {
                assertEquals("Late old read must never replace the new dataset", replacement.datasetId,
                    getLoadedReport(statsFragment(it))?.context?.datasetId)
            }
        } finally {
            org.isoron.uhabits.database.AndroidSqlTiming.observer = oldObserver
            release.countDown()
            controller.onActivity { application.switchDatasetSession(previous) }
            idle()
            waitForSettled(controller)
            controller.onActivity {
                val restoredTimer = component.midnightTimer
                val restoredExecutor = requireNotNull(oldExecutorField.get(restoredTimer) as? java.util.concurrent.ScheduledExecutorService)
                val activeActivityTimer = field<org.isoron.uhabits.core.utils.MidnightTimer?>(it, "activeMidnightTimer")

                assertSame("MainActivity activeMidnightTimer must bind to restored timer", restoredTimer, activeActivityTimer)
                assertFalse("Restored timer executor must not be shut down", restoredExecutor.isShutdown)
            }
            harness.guard()
        }
    }

    private fun verifyScopePickerAndRapidScopeChanges(controller: QaActivityController, seed: SeedingResult) {
        fun choose(label: String, here: Boolean) {
            openScopeBottomSheet(controller)
            val deadline = SystemClock.uptimeMillis() + 8000
            var selected = false
            while (!selected && SystemClock.uptimeMillis() < deadline) {
                controller.onActivity { activity ->
                    val sheet = statsFragment(activity).childFragmentManager
                        .findFragmentByTag(UnifiedStatisticsFiltersBottomSheet.TAG) ?: return@onActivity
                    val root = sheet.view ?: return@onActivity
                    fun matching(view: View, text: String): View? {
                        if ((view is android.widget.RadioButton || view is com.google.android.material.button.MaterialButton) &&
                            view is TextView && view.text.contains(text)) return view
                        if (view is ViewGroup) for (i in 0 until view.childCount) matching(view.getChildAt(i), text)?.let { return it }
                        return null
                    }
                    val radio = matching(root, label) ?: return@onActivity
                    radio.performClick()
                    if (here) requireNotNull(matching(root, context.getString(R.string.unified_statistics_scope_mode_here))).performClick()
                    requireNotNull(matching(root, context.getString(R.string.unified_statistics_filters_apply))).performClick()
                    selected = true
                }
                if (!selected) SystemClock.sleep(50)
            }
            assertTrue("Actual scope picker must contain $label", selected)
            idle()
            waitForSettled(controller)
        }
        choose(CHILD_CONTAINER_DEV, here = true)
        controller.onActivity {
            val report = requireNotNull(getLoadedReport(statsFragment(it)))
            val scope = report.context.sectionScope as org.isoron.uhabits.core.statistics.StatisticsSectionScope.Container
            assertEquals(seed.childContainer, scope.id)
            assertFalse(scope.includeDescendants)
            assertTrue(report.tasks!!.createdTasks.all { task -> task.containerId == seed.childContainer })
            val fragment = statsFragment(it)
            fragment.applyScopedContainer(seed.rootContainer, true)
            fragment.applyScopedContainer(seed.childContainer, false)
            fragment.applyScopedContainer(seed.rootContainer, false)
        }
        idle()
        waitForSettled(controller)
        controller.onActivity {
            val scope = requireNotNull(getLoadedReport(statsFragment(it))).context.sectionScope
                as org.isoron.uhabits.core.statistics.StatisticsSectionScope.Container
            assertEquals(seed.rootContainer, scope.id)
            assertFalse(scope.includeDescendants)
        }
        choose(context.getString(R.string.unified_statistics_scope_all), here = false)
    }

    private fun verifyDomainReadFailure(controller: QaActivityController) {
        val claimed = java.util.concurrent.atomic.AtomicBoolean()
        val oldObserver = org.isoron.uhabits.database.AndroidSqlTiming.observer
        try {
            org.isoron.uhabits.database.AndroidSqlTiming.observer = { operation, _ ->
                if (operation == "SELECT" && Thread.currentThread().stackTrace.any {
                        it.className.contains("UnifiedStatisticsFragment")
                    } && claimed.compareAndSet(false, true)) error("Synthetic read failure")
            }
            controller.onActivity { statsFragment(it).refresh() }
            idle()
            waitForSettled(controller)
            controller.onActivity {
                val report = requireNotNull(getLoadedReport(statsFragment(it)))
                assertNull("Read failure must not fabricate empty Task data", report.tasks)
                assertTrue(report.readIssues.any { issue -> issue.code == org.isoron.uhabits.core.statistics.StatisticsIssueCode.READ_FAILED })
            }
        } finally {
            org.isoron.uhabits.database.AndroidSqlTiming.observer = oldObserver
            controller.onActivity { statsFragment(it).refresh() }
            idle()
            waitForSettled(controller)
        }
    }

    private fun verifyColdInitialScopedFragment(controller: QaActivityController, seedResult: SeedingResult) {
        controller.onActivity { activity ->
            // 1. Cold instantiation with newInstance arguments:
            val scopedFragment = UnifiedStatisticsFragment.newInstance(
                initialContainerId = seedResult.childContainer.value,
                includeDescendants = false
            )
            activity.supportFragmentManager.beginTransaction()
                .add(scopedFragment, "qa_cold_scoped_test")
                .commitNow()
            try {
                // When attached, onCreate has run restoreState(null, bypassArguments = false)
                val resolvedScope = getCurrentScope(scopedFragment)
                assertEquals(
                    "Cold initial fragment must resolve scope from newInstance arguments",
                    UnifiedScope.Container(seedResult.childContainer, includeDescendants = false),
                    resolvedScope
                )

                // 2. Saved state precedence: if savedInstanceState is present, it must take precedence over arguments
                val savedState = android.os.Bundle().apply {
                    putString("unified.datasetId", component.session.datasetId)
                    putString("unified_scope_type", UnifiedStatisticsFiltersBottomSheet.SCOPE_ALL)
                }
                val restoreMethod = UnifiedStatisticsFragment::class.java.getDeclaredMethod(
                    "restoreState", android.os.Bundle::class.java, Boolean::class.javaPrimitiveType
                ).apply {
                    isAccessible = true
                }
                restoreMethod.invoke(scopedFragment, savedState, false)
                assertEquals(
                    "Saved state must take precedence over newInstance arguments",
                    UnifiedScope.All,
                    getCurrentScope(scopedFragment)
                )

                // 3. Saved state dataset mismatch: bypass arguments and restore preferences only
                restoreMethod.invoke(scopedFragment, null, true)
                assertEquals(
                    "Mismatched saved state must bypass initial arguments and restore preferences",
                    UnifiedScope.All,
                    getCurrentScope(scopedFragment)
                )

                // 4. Dataset reset: restorePreferencesForDataset restores target dataset preference without argument carry-over
                val restoreDatasetMethod = UnifiedStatisticsFragment::class.java.getDeclaredMethod("restorePreferencesForDataset", String::class.java).apply {
                    isAccessible = true
                }
                restoreDatasetMethod.invoke(scopedFragment, "qa-reset-target-dataset")
                assertEquals(
                    "Dataset switch/reset must restore target dataset preferences rather than initial arguments",
                    UnifiedScope.All,
                    getCurrentScope(scopedFragment)
                )
            } finally {
                activity.supportFragmentManager.beginTransaction()
                    .remove(scopedFragment)
                    .commitNowAllowingStateLoss()
            }
        }
    }

    private fun verifyAllPeriodHeaderAndBounds(controller: QaActivityController, seedResult: SeedingResult) {
        val targetCutoff = getToday().minus(45)
        seedResult.booleanHabit.statisticsStartDate = targetCutoff

        controller.onActivity { activity ->
            val fragment = statsFragment(activity)
            // Switch period to ALL
            val fieldPeriod = UnifiedStatisticsFragment::class.java.getDeclaredField("currentPeriod").apply {
                isAccessible = true
            }
            fieldPeriod.set(fragment, UnifiedPeriod.ALL)

            // Clear loaded report to simulate unresolved transition state
            val fieldReport = UnifiedStatisticsFragment::class.java.getDeclaredField("currentLoadedReport").apply {
                isAccessible = true
            }
            fieldReport.set(fragment, null)
            val fieldKey = UnifiedStatisticsFragment::class.java.getDeclaredField("currentLoadedKey").apply {
                isAccessible = true
            }
            fieldKey.set(fragment, null)

            val updateLabelsMethod = UnifiedStatisticsFragment::class.java.getDeclaredMethod("updateHeaderLabels").apply {
                isAccessible = true
            }
            updateLabelsMethod.invoke(fragment)

            // While unresolved, header must display neutral "All Time" and NOT fake "Since <Today>"
            val dateRangeView = getDateRangeView(fragment)
            val unresolvedText = dateRangeView.text.toString()
            assertEquals(
                "While report is unresolved, period ALL header must show neutral All Time",
                context.getString(R.string.reports_tab_all),
                unresolvedText
            )

            // Now trigger actual refresh for period ALL
            fragment.refresh()
        }

        idle()
        waitForSettled(controller)

        controller.onActivity { activity ->
            val fragment = statsFragment(activity)
            val report = requireNotNull(getLoadedReport(fragment)) { "Period ALL report must settle" }
            assertEquals(
                "Period ALL report.context.from must match habit.effectiveStatisticsStartDate()",
                targetCutoff,
                report.context.from
            )

            val dateRangeView = getDateRangeView(fragment)
            val resolvedText = dateRangeView.text.toString()
            val expectedStartFormatted = JavaLocalDateFormatter(java.util.Locale.getDefault()).longFormat(targetCutoff)
            val expectedHeader = context.getString(R.string.unified_statistics_since_date, expectedStartFormatted)
            assertEquals("Settled period ALL header must match resolved history start date", expectedHeader, resolvedText)
            assertFalse(
                "Header must not show fake today start",
                resolvedText.contains(JavaLocalDateFormatter(java.util.Locale.getDefault()).longFormat(getToday()))
            )

            // Revert habit statisticsStartDate and restore period to WEEK
            seedResult.booleanHabit.statisticsStartDate = null
            val fieldPeriod = UnifiedStatisticsFragment::class.java.getDeclaredField("currentPeriod").apply {
                isAccessible = true
            }
            fieldPeriod.set(fragment, UnifiedPeriod.WEEK)
            fragment.refresh()
        }

        idle()
        waitForSettled(controller)
    }

    private fun verifyLongScopeHeaderGeometry(controller: QaActivityController, seedResult: SeedingResult) {
        controller.onActivity { activity ->
            val fragment = statsFragment(activity)
            fragment.applyScopedContainer(seedResult.childContainer, includeDescendants = true)
        }
        idle()
        waitForSettled(controller)

        assertMinimumTouchTargets(controller)

        controller.onActivity { activity ->
            statsFragment(activity).applyScopedContainer(seedResult.rootContainer, includeDescendants = true)
        }
        idle()
        waitForSettled(controller)
    }

    private fun verifyOpenDialogDatasetSwitchRegression(controller: QaActivityController, seedResult: SeedingResult) {
        harness.guard()
        val application = context.applicationContext as HabitsApplication
        val previous = component.session
        check(previous.isExperimental && !previous.capabilities.syncEnabled && !previous.capabilities.remoteCallsAllowed)
        val replacement = previous.copy(datasetId = "qa-sheet-switch-${UUID.randomUUID()}")

        try {
            openScopeBottomSheet(controller)

            controller.onActivity {
                application.switchDatasetSession(replacement)
            }
            idle()

            controller.onActivity { activity ->
                val fragment = statsFragment(activity)
                val sheet = fragment.childFragmentManager.findFragmentByTag(UnifiedStatisticsFiltersBottomSheet.TAG)
                    as? UnifiedStatisticsFiltersBottomSheet

                val staleBundle = android.os.Bundle().apply {
                    putString(UnifiedStatisticsFiltersBottomSheet.EXTRA_DATASET_ID, previous.datasetId)
                    putString(UnifiedStatisticsFiltersBottomSheet.EXTRA_SCOPE_TYPE, UnifiedStatisticsFiltersBottomSheet.SCOPE_CONTAINER)
                    putString(UnifiedStatisticsFiltersBottomSheet.EXTRA_CONTAINER_ID, seedResult.childContainer.value)
                    putBoolean(UnifiedStatisticsFiltersBottomSheet.EXTRA_INCLUDE_DESCENDANTS, false)
                }
                val handleResultMethod = UnifiedStatisticsFragment::class.java.getDeclaredMethod("handleFilterResult", android.os.Bundle::class.java).apply {
                    isAccessible = true
                }
                handleResultMethod.invoke(fragment, staleBundle)

                assertEquals(
                    "Mismatched dataset filter result must be rejected",
                    UnifiedScope.All,
                    getCurrentScope(fragment)
                )

                val nullBundle = android.os.Bundle().apply {
                    putString(UnifiedStatisticsFiltersBottomSheet.EXTRA_SCOPE_TYPE, UnifiedStatisticsFiltersBottomSheet.SCOPE_CONTAINER)
                    putString(UnifiedStatisticsFiltersBottomSheet.EXTRA_CONTAINER_ID, seedResult.childContainer.value)
                    putBoolean(UnifiedStatisticsFiltersBottomSheet.EXTRA_INCLUDE_DESCENDANTS, false)
                }
                handleResultMethod.invoke(fragment, nullBundle)
                assertEquals(
                    "Null dataset filter result must be rejected",
                    UnifiedScope.All,
                    getCurrentScope(fragment)
                )

                sheet?.dismissAllowingStateLoss()
            }
        } finally {
            controller.onActivity { application.switchDatasetSession(previous) }
            idle()
            waitForSettled(controller)
            harness.guard()
        }
    }
}

// -------------------------------------------------------------------------
// Shared Internal QA Activity Controller & PixelCopy Mechanisms
// -------------------------------------------------------------------------

internal class QaActivityController(
    private val instrumentation: android.app.Instrumentation = InstrumentationRegistry.getInstrumentation()
) : Closeable {
    private val context get() = instrumentation.targetContext
    private val device get() = UiDevice.getInstance(instrumentation)

    init {
        instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
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
        onActivity { activity ->
            previous = activity
            activity.recreate()
        }
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
        error("MainActivity did not resume within 20s")
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

data class PixelCopyResult(
    val success: Boolean,
    val copyResult: Int? = null,
    val error: Throwable? = null,
)

internal fun capturePixelCopy(window: android.view.Window, targetFile: File): PixelCopyResult {
    val decor = window.decorView
    val width = decor.width
    val height = decor.height
    if (width <= 0 || height <= 0) {
        return PixelCopyResult(false, error = IllegalStateException("Decor view has invalid size: ${width}x${height}"))
    }

    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val latch = CountDownLatch(1)
    val copyThread = HandlerThread("PixelCopyBackgroundThread").apply { start() }
    val copyHandler = Handler(copyThread.looper)

    var pixelResult = -1
    var writeError: Throwable? = null

    try {
        PixelCopy.request(window, bitmap, { result ->
            pixelResult = result
            if (result == PixelCopy.SUCCESS) {
                try {
                    FileOutputStream(targetFile).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                } catch (t: Throwable) {
                    writeError = t
                }
            }
            latch.countDown()
        }, copyHandler)

        val completed = latch.await(5, TimeUnit.SECONDS)
        if (!completed) {
            return PixelCopyResult(false, pixelResult, TimeoutException("PixelCopy timed out after 5s"))
        }
        if (pixelResult != PixelCopy.SUCCESS) {
            return PixelCopyResult(false, pixelResult, IllegalStateException("PixelCopy failed with status code $pixelResult"))
        }
        if (writeError != null) {
            return PixelCopyResult(false, pixelResult, writeError)
        }
        return PixelCopyResult(true, pixelResult)
    } catch (t: Throwable) {
        return PixelCopyResult(false, pixelResult, t)
    } finally {
        copyThread.quitSafely()
    }
}
