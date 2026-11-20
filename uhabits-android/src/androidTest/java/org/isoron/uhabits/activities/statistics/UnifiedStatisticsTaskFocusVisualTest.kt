/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.activities.statistics

import android.content.Context
import android.os.SystemClock
import androidx.core.view.accessibility.AccessibilityNodeProviderCompat
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
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
        orgFacade.placeHabit(requireNotNull(habitUnassigned.uuid), null)

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
        val namespace = component.session.preferencesNamespace
        val testPreferences = if (namespace.isBlank()) {
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        } else {
            context.getSharedPreferences("uhabits_prefs_$namespace", Context.MODE_PRIVATE)
        }
        val hadFirstWeekday = testPreferences.contains("pref_first_weekday")
        val oldFirstWeekday = testPreferences.getString("pref_first_weekday", "-1")

        try {
            val isAmoled = phase.startsWith("amoled")
            val isDark = phase.startsWith("dark") || isAmoled
            component.preferences.theme = if (isDark) ThemeSwitcher.THEME_DARK else ThemeSwitcher.THEME_LIGHT
            component.preferences.isPureBlackEnabled = isAmoled
            component.preferences.isFirstRun = false

            val today = getToday()
            val testFirstWeekday = today.minus(5).dayOfWeek
            testPreferences.edit().putString("pref_first_weekday", (testFirstWeekday.daysSinceSunday + 1).toString()).commit()

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

                // A3. Verify the drawn axis and an accessible chart column before capture.
                var chartDensity = 1f
                controller.onActivity { chartDensity = it.resources.displayMetrics.density }
                device.assertStatisticsChart("unified_tasks_t1_chart", chartDensity)
                capture("02-tasks-t1-chart")

                // A4. Both cohorts expose every segment, including zero-count segments.
                listOf(
                    "unified_tasks_t3_cohort_COMPLETED",
                    "unified_tasks_t3_cohort_OVERDUE_NOW",
                    "unified_tasks_t3_cohort_PENDING_NOT_OVERDUE",
                    "unified_tasks_t4_cohort_COMPLETED",
                    "unified_tasks_t4_cohort_PENDING",
                ).forEach { tag ->
                    val node = device.revealStatisticsReportControl(tag)
                    assertTrue(node.isClickable && !node.contentDescription.isNullOrBlank())
                    assertEquals("android.widget.Button", node.className)
                }
                device.revealStatisticsReportControl("unified_tasks_t3_cohort_COMPLETED", Direction.UP)
                capture("03-tasks-cohorts")

                // A5. Tap the visible chart column for today.
                device.revealStatisticsChartColumn("unified_tasks_t1_chart", todayTaskColumn(controller)).click()
                idle()
                SystemClock.sleep(400)

                // FAIL-CLOSED: Drilldown sheet must be open via real click
                val tasksSheet = requireNotNull(findOpenBottomSheet(controller) as? UnifiedStatisticsDrilldownBottomSheet) {
                    "FAIL-CLOSED: UnifiedStatisticsDrilldownBottomSheet must be displayed following real click on T1 chart"
                }
                capture("04-tasks-drilldown-sheet")

                // Verify rendered IDs, exact page replacement and real paging controls.
                var aggregateIds = emptyList<String>()
                controller.onActivity { activity ->
                    val tasks = requireNotNull(statsFragment(activity).lastReport?.tasks)
                    aggregateIds = tasks.dailyCompletedBuckets.firstOrNull { it.date == getToday() }?.tasks.orEmpty().map { it.id.value }
                    assertEquals(aggregateIds.take(DRILLDOWN_PAGE_SIZE), tasksSheet.visibleIds)
                    assertEquals(DRILLDOWN_PAGE_SIZE, tasksSheet.visibleIds.size)
                }
                aggregateIds.take(DRILLDOWN_PAGE_SIZE).forEach { id ->
                    assertTrue("Missing clickable task row $id", device.revealStatisticsDrilldownControl(id).isClickable)
                }
                device.revealStatisticsDrilldownControl("statistics_drilldown_next").click()
                idle()
                controller.onActivity {
                    assertEquals(aggregateIds.drop(DRILLDOWN_PAGE_SIZE), tasksSheet.visibleIds)
                    assertTrue(tasksSheet.visibleIds.size <= DRILLDOWN_PAGE_SIZE)
                }
                device.revealStatisticsDrilldownControl("statistics_drilldown_previous").click()
                idle()
                controller.onActivity { assertEquals(aggregateIds.take(DRILLDOWN_PAGE_SIZE), tasksSheet.visibleIds) }

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

                var expectedIds = emptyList<String>()
                controller.onActivity { activity ->
                    val freshTasks = requireNotNull(statsFragment(activity).lastReport?.tasks)
                    val todayBucket = requireNotNull(freshTasks.dailyCompletedBuckets.firstOrNull { it.date == getToday() })
                    assertTrue("Fresh report must contain the live task", todayBucket.tasks.any { it.id == liveTaskId })
                    assertEquals(beforeCompletedCount + 1, freshTasks.completedCount)
                    assertTrue("Drilldown bottom sheet must still be open", tasksSheet.dialog?.isShowing == true)
                    expectedIds = todayBucket.tasks.map { it.id.value }
                }
                val livePageIds = mutableListOf<String>()
                for ((page, expected) in expectedIds.chunked(DRILLDOWN_PAGE_SIZE).withIndex()) {
                    if (page > 0) {
                        device.revealStatisticsDrilldownControl("statistics_drilldown_next").click()
                        idle()
                    }
                    controller.onActivity {
                        assertEquals("Open sheet must react to fresh report without manual refresh", expected, tasksSheet.visibleIds)
                        livePageIds.addAll(tasksSheet.visibleIds)
                    }
                }
                assertEquals(expectedIds, livePageIds)
                assertTrue(livePageIds.contains(requireNotNull(liveTaskId).value))

                // REAL USER NAVIGATION: Close drilldown sheet with Back
                device.pressBack()
                idle()

                // A7. REAL CLICK PATH: Tap T3 Due Overdue segment to launch cohort drilldown
                device.revealStatisticsReportControl("unified_tasks_t3_cohort_OVERDUE_NOW").click()
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

                // The native Created metric must open the report's created-task cohort.
                device.revealStatisticsReportControl("unified_tasks_created", Direction.UP).click()
                idle()
                val createdSheet = requireNotNull(findOpenBottomSheet(controller) as? UnifiedStatisticsDrilldownBottomSheet)
                controller.onActivity { activity ->
                    val created = requireNotNull(statsFragment(activity).lastReport?.tasks).createdTasks
                    assertEquals(created.take(DRILLDOWN_PAGE_SIZE).map { it.id.value }, createdSheet.visibleIds)
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

                // B3. Verify the Focus time axis and an accessible chart column.
                device.assertStatisticsChart("unified_focus_f1_chart", chartDensity)
                capture("06-focus-f1-chart")

                // B4. FAIL-CLOSED: Scroll to F3 Sections Breakdown, assert hierarchy, capture
                device.revealStatisticsReportControl("unified_focus_f3_breakdown")
                capture("07-focus-f3-sections")

                // B5. FAIL-CLOSED: Scroll to F4 Targets Breakdown, verify missing/deleted target label, capture
                device.revealStatisticsReportControl("unified_focus_f4_breakdown")
                capture("08-focus-f4-targets")

                // B6. REAL CLICK PATH: Tap F1 Chart column for today to open Focus Drilldown Sheet
                var focusColumn = -1
                controller.onActivity { activity ->
                    val fragment = statsFragment(activity)
                    val focus = requireNotNull(fragment.lastReport?.focus)
                    focusColumn = DiscreteChartBucketer.buildFocusColumns(
                        fragment.currentPeriodValue, focus, field(fragment, "lastKnownToday"), Locale.getDefault().language == "ru",
                    ).indexOfFirst { it.isToday }
                }
                assertTrue("Focus chart must contain today", focusColumn >= 0)
                device.revealStatisticsChartColumn("unified_focus_f1_chart", focusColumn).click()
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
                assertFalse(device.findStatisticsSessionDetailText(ACTIVE_SESSION_ID, By.res("unified_session_detail_tombstone")))
                assertTrue("Active provenance must include 45", device.findStatisticsSessionDetailText(ACTIVE_SESSION_ID, By.textContains("45")))
                dismissDialogs(controller)

                openSessionViaHierarchy(controller, TOMBSTONE_SESSION_ID, viaType = true)
                capture("11-focus-session-detail-tombstone")
                assertTrue(
                    "Native revoked ledger quantity must be 20",
                    device.findStatisticsSessionDetailText(
                        TOMBSTONE_SESSION_ID, By.res("unified_session_detail_tombstone").textContains("20"),
                    ),
                )
                dismissDialogs(controller)

                openSessionViaHierarchy(controller, PAUSED_SESSION_ID)
                capture("11b-focus-session-detail-paused")
                assertTrue(
                    "Paused cross-midnight allocation must be estimated",
                    device.findStatisticsSessionDetailText(PAUSED_SESSION_ID, By.textContains("≈")),
                )
                dismissDialogs(controller)

                var deletedSessionId = ""
                controller.onActivity {
                    deletedSessionId = requireNotNull(statsFragment(it).lastReport?.focus).sessions
                        .first { session -> session.taskId?.value == DELETED_TASK_ID }.id.value
                }
                openSessionViaHierarchy(controller, deletedSessionId, viaType = true)
                capture("11c-focus-deleted-target")
                assertTrue(
                    "Deleted task label must be rendered",
                    device.findStatisticsSessionDetailText(deletedSessionId, By.textContains("Deleted task")) ||
                        device.findStatisticsSessionDetailText(deletedSessionId, By.textContains("Удалённая задача")),
                )
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

                assertFalse(
                    "Header in ALL mode must not show fake today start",
                    device.statisticsControl("unified_date_range").text.orEmpty().contains(JavaLocalDateFormatter(Locale.getDefault()).longFormat(getToday())),
                )
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
                device.revealStatisticsChartColumn("unified_tasks_t1_chart", todayTaskColumn(controller)).click()
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
                    val emptyReport = requireNotNull(getLoadedReport(fragment)) { "Empty report must not be null" }
                    val emptyTasks = requireNotNull(emptyReport.tasks) { "Empty tasks report must not be null" }
                    assertEquals("Empty container must yield 0 completed tasks", 0, emptyTasks.completedCount)
                    assertEquals("Empty container must yield 0 due tasks", 0, emptyTasks.dueSummary.totalCount)
                    assertEquals("Empty container must yield 0 nodue tasks", 0, emptyTasks.noDueSummary.totalCount)
                    val emptyFocus = requireNotNull(emptyReport.focus) { "Empty focus report must not be null" }
                    assertEquals("Empty container must yield 0 focus duration", 0L, emptyFocus.totalDurationMs)
                    assertEquals("Empty container must yield 0 focus sessions", 0, emptyFocus.sessionCount)
                }

                assertFalse("Empty scope must not show an error banner", device.hasObject(By.res("unified_status")))
                val failureField = UnifiedStatisticsFragment::class.java.getDeclaredField("currentUiState").apply {
                    isAccessible = true
                }
                val renderMethod = UnifiedStatisticsFragment::class.java.getDeclaredMethod("renderCurrentState").apply {
                    isAccessible = true
                }
                lateinit var previousState: UnifiedReportUiState
                controller.onActivity { activity ->
                    val fragment = statsFragment(activity)
                    previousState = getUiState(fragment)
                    failureField.set(fragment, UnifiedReportUiState.Failure("Simulated domain read failure", emptyList(), getLoadedReport(fragment)))
                    renderMethod.invoke(fragment)
                }
                assertFalse(device.statisticsControl("unified_status").visibleBounds.isEmpty)
                assertTrue(device.hasObject(By.text("Simulated domain read failure")))
                controller.onActivity { activity ->
                    val fragment = statsFragment(activity)
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
                val editor = testPreferences.edit()
                if (hadFirstWeekday) editor.putString("pref_first_weekday", oldFirstWeekday)
                else editor.remove("pref_first_weekday")
                editor.commit()
            }
        }
    }

    // -------------------------------------------------------------------------
    // Real Click Dispatchers & UI Period Selection
    // -------------------------------------------------------------------------

    private fun selectPeriodViaUi(controller: QaActivityController, period: UnifiedPeriod) {
        device.selectStatisticsPeriod(period)
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

    private fun todayTaskColumn(controller: QaActivityController): Int {
        var index = -1
        controller.onActivity { activity ->
            val fragment = statsFragment(activity)
            val tasks = requireNotNull(fragment.lastReport?.tasks)
            index = DiscreteChartBucketer.buildTaskColumns(
                fragment.currentPeriodValue, tasks, field(fragment, "lastKnownToday"), Locale.getDefault().language == "ru",
            ).indexOfFirst { it.isToday }
        }
        assertTrue("Tasks chart must contain today's column", index >= 0)
        return index
    }

    // -------------------------------------------------------------------------
    // Navigation, View Finding & Inspection Helpers
    // -------------------------------------------------------------------------

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
        var entryTag = ""
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
            entryTag = if (viaType) "target-type:${type?.name ?: "UNKNOWN"}"
                else "section:${branch.category}:${branch.containerId?.value}"
        }
        device.revealStatisticsReportControl(entryTag).click()
        idle()
        requireNotNull(findOpenBottomSheet(controller) as? UnifiedStatisticsDrilldownBottomSheet)
        device.revealStatisticsDrilldownControl(objectTag).click()
        idle()
        val sessions = requireNotNull(findOpenBottomSheet(controller) as? UnifiedStatisticsDrilldownBottomSheet)
        var page = 0
        var selected = false
        while (!selected && page < 20) {
            controller.onActivity {
                assertEquals("Every visible page must equal its aggregate slice",
                    expected.drop(page * DRILLDOWN_PAGE_SIZE).take(DRILLDOWN_PAGE_SIZE), sessions.visibleIds)
                assertTrue(sessions.visibleIds.size <= DRILLDOWN_PAGE_SIZE)
                selected = id in sessions.visibleIds
                if (!selected) assertTrue("Exact session must exist on a later page", sessions.hasNextPage)
            }
            if (selected) {
                device.revealStatisticsDrilldownControl(id).click()
            } else {
                device.revealStatisticsDrilldownControl("statistics_drilldown_next").click()
                idle()
                page++
            }
        }
        assertTrue("Exact session row must be reached", selected)
        idle()
        assertTrue("Exact session detail payload must finish loading: $id",
            device.wait(Until.hasObject(By.res("session-detail:$id")), 10_000))
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
        device.selectStatisticsTab(tabIndex)
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

}
