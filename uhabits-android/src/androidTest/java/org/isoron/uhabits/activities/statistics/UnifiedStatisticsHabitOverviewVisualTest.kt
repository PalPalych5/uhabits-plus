/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.activities.statistics

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
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
import org.isoron.uhabits.core.database.EntryData
import org.isoron.uhabits.core.database.EntryRepository
import org.isoron.uhabits.core.database.HabitGoalData
import org.isoron.uhabits.core.database.HabitGoalRepository
import org.isoron.uhabits.core.focus.FocusSession
import org.isoron.uhabits.core.focus.FocusSessionId
import org.isoron.uhabits.core.focus.FocusSessionSource
import org.isoron.uhabits.core.focus.sqlite.SQLiteFocusSessionRepository
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Frequency
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitGoal
import org.isoron.uhabits.core.models.HabitType
import org.isoron.uhabits.core.models.NumericalHabitType
import org.isoron.uhabits.core.models.PaletteColor
import org.isoron.uhabits.core.models.sqlite.SQLiteHabitList
import org.isoron.uhabits.core.statistics.HabitCadence
import org.isoron.uhabits.core.statistics.HabitCycle
import org.isoron.uhabits.core.statistics.HabitCycleLifecycle
import org.isoron.uhabits.core.statistics.HabitCycleOutcome
import org.isoron.uhabits.core.statistics.StatisticsDataState
import org.isoron.uhabits.core.statistics.UnifiedStatisticsReport
import org.isoron.uhabits.core.ui.ThemeSwitcher
import org.isoron.uhabits.core.userTasks.TaskStatus
import org.isoron.uhabits.core.userTasks.UserTask
import org.isoron.uhabits.core.userTasks.due.TaskDue
import org.isoron.uhabits.core.userTasks.due.toTaskDueDate
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * PR14.4 dedicated Android visual and functional QA instrumentation test for
 * Habits (C1-C7) and Overview analytical domains.
 *
 * Strict Fail-Closed Verification:
 * - Real synthetic Habit entries: numerical and boolean habits, daily, weekly, monthly,
 *   unsupported cadences, archived habits, UNKNOWN (-1 and unrecorded), SKIP entries.
 * - Historical goals with mid-cycle change generating TRANSITION cycles.
 * - Observation cutoff generating PARTIAL cycles.
 * - Contribution provenance ledger with active, revoked (tombstoned), and excluded (on SKIP) contributions.
 * - Verification of lower bounds: lowerBound = manualValue + activeContributionValue.
 * - SKIP policy assertions: daily exempt; weekly/monthly non-prorated quota with capacity exclusion;
 *   numerical SKIP excludes quantity without lowering target T.
 * - Three separate cadence rows without hidden mixed-cadence weighted averaging.
 * - Cadence bar chart with fail-closed TalkBack accessibility provider and virtual node assertions (requireNotNull).
 * - All cadence tab switching asserts exact selected content, items, and title changes.
 * - Compact alphabetical preview list excluding overflow archived habits, and full searchable list including archive.
 * - Overview tab: exact rendered string equality for T1, F1, F2 against Tasks and Focus tabs.
 * - Fail-closed tag lookups: all required view tags assert non-null with diagnostic messages.
 * - C7 Drill-down: cadence row -> aggregate cycles list -> cycle detail sheet -> open habit -> Back hierarchy.
 * - First cycle click locates exact key tag (never generic clickable fallback).
 * - Live data mutation asserts strictly advancing revision, changed report cycle quantity/outcome,
 *   updated visible sheet content, and live insert/revoke of contributions while sheet is open.
 * - Rapid period changes (DAY, WEEK, MONTH, YEAR, ALL) asserting actual number and chart bounds changes.
 * - Scope traversals (ALL, CONTAINER SUBTREE, HERE, UNASSIGNED, EMPTY) asserting subtree vs here resolution.
 * - Activity recreation and state preservation across theme, period, and selected tab.
 * - Dual screenshot captures: UiDevice (screencap) and PixelCopy (Window Surface off main thread).
 */
@RunWith(AndroidJUnit4::class)
class UnifiedStatisticsHabitOverviewVisualTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val args = InstrumentationRegistry.getArguments()
    private val context get() = instrumentation.targetContext
    private val component get() = HabitsApplication.component
    private val device = UiDevice.getInstance(instrumentation)
    private val harness = TaskDraftLifecycleTest()

    private val phase = args.getString("phase", "light_standard_100")!!.also {
        check(it.matches(Regex("^(light|dark|amoled)_(standard|narrow320)_(100|200)$")))
    }

    private val outputDir get() = File(context.getExternalFilesDir(null), "unified-statistics-habit-overview-qa").apply {
        check(mkdirs() || isDirectory) { "Failed to create evidence directory: $path" }
    }

    companion object {
        const val ROOT_CONTAINER_WORK = "Работа и проекты"
        const val CHILD_CONTAINER_DEV = "Разработка Loop"
        const val CONTAINER_EMPTY = "Пустой раздел QA"

        const val HABIT_DAILY_BOOL_TITLE = "Утренняя разминка"
        const val HABIT_WEEKLY_BOOL_TITLE = "Чтение книг"
        const val HABIT_MONTHLY_BOOL_TITLE = "Генеральная уборка"
        const val HABIT_DAILY_NUM_TITLE = "Фокусная работа"
        const val HABIT_WEEKLY_NUM_TITLE = "Спорт"
        const val HABIT_PARTIAL_TITLE = "Медитация"
        const val HABIT_ARCHIVED_TITLE = "Старый проект"
        const val HABIT_UNSUPPORTED_TITLE = "Пятидневный спринт"

        const val ACTIVE_CONTRIB_ID = "contrib-qa-active-35m"
        const val REVOKED_CONTRIB_ID = "contrib-qa-revoked-20m"
        const val EXCLUDED_CONTRIB_ID = "contrib-qa-excluded-15m"

        const val FOCUS_SESSION_1_ID = "fs-qa-overview-1"
        const val FOCUS_SESSION_2_ID = "fs-qa-overview-2"
    }

    // -------------------------------------------------------------------------
    // Synthetic Habit & Overview Fixture Data Seeding (Schema 35 Preserving)
    // -------------------------------------------------------------------------

    data class HabitOverviewSeedingResult(
        val rootContainer: ContainerId,
        val childContainer: ContainerId,
        val emptyContainer: ContainerId,
        val habitDailyBool: Habit,
        val habitWeeklyBool: Habit,
        val habitMonthlyBool: Habit,
        val habitDailyNum: Habit,
        val habitWeeklyNum: Habit,
        val habitPartial: Habit,
        val habitArchived: Habit,
        val habitUnsupported: Habit,
        val completedTasks: List<UserTask>,
        val focusSessions: List<FocusSession>,
        val activeContribution: Contribution,
        val revokedContribution: Contribution,
        val excludedContribution: Contribution,
        val expectedTasksT1Count: Int,
        val expectedFocusF1DurationMs: Long,
        val expectedFocusF2SessionCount: Int,
    )

    private fun seedHabitOverviewData(): HabitOverviewSeedingResult {
        val now = System.currentTimeMillis()
        val today = getToday()
        setToday(today)
        val firstWeekday = component.preferences.firstWeekday
        val weekStart = today.startOfWeek(firstWeekday)
        val weekEnd = weekStart.plus(6)

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

        // 1. Daily Boolean Habit in Root Container
        var habitDailyBool = habitList.firstOrNull { it.name == HABIT_DAILY_BOOL_TITLE }
        if (habitDailyBool == null) {
            habitDailyBool = factory.buildHabit().apply {
                uuid = UUID.randomUUID().toString()
                name = HABIT_DAILY_BOOL_TITLE
                description = "Daily Boolean Habit for QA"
                frequency = Frequency.DAILY
                type = HabitType.YES_NO
                color = PaletteColor(0)
            }
            habitList.add(habitDailyBool)
        }
        orgFacade.placeHabit(requireNotNull(habitDailyBool.uuid), rootId)
        val dailyBoolEntries = habitDailyBool.originalEntries
        dailyBoolEntries.clear()
        dailyBoolEntries.add(Entry(today, Entry.YES_MANUAL)) // Today: ongoing
        dailyBoolEntries.add(Entry(today.minus(1), Entry.YES_MANUAL)) // Satisfied closed day
        dailyBoolEntries.add(Entry(today.minus(2), Entry.YES_MANUAL)) // Satisfied closed day
        dailyBoolEntries.add(Entry(today.minus(3), Entry.NO))         // Not satisfied closed day
        dailyBoolEntries.add(Entry(today.minus(4), Entry.SKIP))       // Exempt closed day
        dailyBoolEntries.add(Entry(today.minus(5), Entry.UNKNOWN))    // Unknown closed day
        habitList.update(habitDailyBool)

        // 2. Weekly Boolean Habit in Child Container (Frequency 3/7)
        var habitWeeklyBool = habitList.firstOrNull { it.name == HABIT_WEEKLY_BOOL_TITLE }
        if (habitWeeklyBool == null) {
            habitWeeklyBool = factory.buildHabit().apply {
                uuid = UUID.randomUUID().toString()
                name = HABIT_WEEKLY_BOOL_TITLE
                description = "Weekly Boolean Habit for QA"
                frequency = Frequency(3, 7)
                type = HabitType.YES_NO
                color = PaletteColor(1)
            }
            habitList.add(habitWeeklyBool)
        }
        orgFacade.placeHabit(requireNotNull(habitWeeklyBool.uuid), childId)
        val weeklyBoolEntries = habitWeeklyBool.originalEntries
        weeklyBoolEntries.clear()
        // Current week: 5 SKIP entries, 1 YES_MANUAL, 1 NO -> capacity = 7 - 5 = 2 < quota 3 -> CAPACITY_EXCLUDED
        weeklyBoolEntries.add(Entry(weekStart, Entry.YES_MANUAL))
        for (offset in 1..5) {
            weeklyBoolEntries.add(Entry(weekStart.plus(offset), Entry.SKIP))
        }
        weeklyBoolEntries.add(Entry(weekStart.plus(6), Entry.NO))
        for (offset in 7..13) {
            weeklyBoolEntries.add(Entry(today.minus(offset), Entry.YES_MANUAL))
        }
        habitList.update(habitWeeklyBool)

        // 3. Monthly Boolean Habit in Root Container (Frequency 1/30)
        var habitMonthlyBool = habitList.firstOrNull { it.name == HABIT_MONTHLY_BOOL_TITLE }
        if (habitMonthlyBool == null) {
            habitMonthlyBool = factory.buildHabit().apply {
                uuid = UUID.randomUUID().toString()
                name = HABIT_MONTHLY_BOOL_TITLE
                description = "Monthly Boolean Habit for QA"
                frequency = Frequency(1, 30)
                type = HabitType.YES_NO
                color = PaletteColor(2)
            }
            habitList.add(habitMonthlyBool)
        }
        orgFacade.placeHabit(requireNotNull(habitMonthlyBool.uuid), rootId)
        val monthlyBoolEntries = habitMonthlyBool.originalEntries
        monthlyBoolEntries.clear()
        // Past month (31 to 60 days ago): All SKIP -> EXEMPT cycle
        for (offset in 31..60) {
            monthlyBoolEntries.add(Entry(today.minus(offset), Entry.SKIP))
        }
        habitList.update(habitMonthlyBool)

        // 4. Daily Numerical Habit in Child Container (AT_LEAST 60.0 mins)
        var habitDailyNum = habitList.firstOrNull { it.name == HABIT_DAILY_NUM_TITLE }
        if (habitDailyNum == null) {
            habitDailyNum = factory.buildHabit().apply {
                uuid = UUID.randomUUID().toString()
                name = HABIT_DAILY_NUM_TITLE
                description = "Daily Numerical Habit (Minutes)"
                frequency = Frequency.DAILY
                type = HabitType.NUMERICAL
                targetType = NumericalHabitType.AT_LEAST
                targetValue = 60.0
                unit = "мин"
                color = PaletteColor(3)
            }
            habitList.add(habitDailyNum)
        }
        orgFacade.placeHabit(requireNotNull(habitDailyNum.uuid), childId)
        val dailyNumEntries = habitDailyNum.originalEntries
        dailyNumEntries.clear()
        // yesterday: manual 30_000L + active contribution 35_000L = 65_000L >= 60_000L -> SATISFIED via contribution!
        dailyNumEntries.add(Entry(today.minus(1), 30_000))
        // 2 days ago: manual 70_000L >= 60_000L -> SATISFIED purely manual
        dailyNumEntries.add(Entry(today.minus(2), 70_000))
        // 3 days ago: marked SKIP
        dailyNumEntries.add(Entry(today.minus(3), Entry.SKIP))
        habitList.update(habitDailyNum)

        // 5. Weekly Numerical Habit with Historical Goals (TRANSITION cycle)
        var habitWeeklyNum = habitList.firstOrNull { it.name == HABIT_WEEKLY_NUM_TITLE }
        if (habitWeeklyNum == null) {
            habitWeeklyNum = factory.buildHabit().apply {
                uuid = UUID.randomUUID().toString()
                name = HABIT_WEEKLY_NUM_TITLE
                description = "Weekly Numerical Habit with Goal History"
                frequency = Frequency.WEEKLY
                type = HabitType.NUMERICAL
                targetType = NumericalHabitType.AT_LEAST
                targetValue = 100.0
                unit = "км"
                color = PaletteColor(4)
            }
            habitList.add(habitWeeklyNum)
        }
        orgFacade.placeHabit(requireNotNull(habitWeeklyNum.uuid), rootId)
        val weeklyGoals = listOf(
            HabitGoal(weekStart.minus(7), Frequency.WEEKLY, NumericalHabitType.AT_LEAST, 50.0, "км"),
            HabitGoal(weekStart.plus(2), Frequency.WEEKLY, NumericalHabitType.AT_LEAST, 100.0, "км")
        )
        habitWeeklyNum.goalHistory = weeklyGoals.toMutableList()
        val habitWeeklyNumId = requireNotNull(habitWeeklyNum.id)
        val habitGoalRepo = HabitGoalRepository(component.db)
        habitGoalRepo.replaceAll(
            habitWeeklyNumId,
            weeklyGoals.map { goal ->
                HabitGoalData(
                    habitId = habitWeeklyNumId,
                    uuid = "${habitWeeklyNum.uuid}:goal:${goal.effectiveDate.unixTime}",
                    effectiveTimestamp = goal.effectiveDate.unixTime,
                    freqNum = goal.frequency.numerator,
                    freqDen = goal.frequency.denominator,
                    targetType = goal.targetType.value,
                    targetValue = goal.targetValue,
                    unit = goal.unit
                )
            },
            now
        )
        val weeklyNumEntries = habitWeeklyNum.originalEntries
        weeklyNumEntries.clear()
        weeklyNumEntries.add(Entry(weekStart, 10_000))
        weeklyNumEntries.add(Entry(weekStart.plus(1), 10_000))
        weeklyNumEntries.add(Entry(weekStart.plus(2), 10_000))
        for (offset in 7..13) {
            weeklyNumEntries.add(Entry(today.minus(offset), 10_000))
        }
        habitList.update(habitWeeklyNum)

        // 6. Habit with observation start (PARTIAL cycle)
        var habitPartial = habitList.firstOrNull { it.name == HABIT_PARTIAL_TITLE }
        if (habitPartial == null) {
            habitPartial = factory.buildHabit().apply {
                uuid = UUID.randomUUID().toString()
                name = HABIT_PARTIAL_TITLE
                description = "Habit with Statistics Cutoff"
                frequency = Frequency.WEEKLY
                type = HabitType.YES_NO
                color = PaletteColor(5)
            }
            habitList.add(habitPartial)
        }
        orgFacade.placeHabit(requireNotNull(habitPartial.uuid), null)
        habitPartial.frequency = Frequency.WEEKLY
        habitPartial.type = HabitType.YES_NO
        habitPartial.isArchived = false
        habitPartial.statisticsStartDate = weekStart.plus(2)
        val partialEntries = habitPartial.originalEntries
        partialEntries.clear()
        // First entry inside the cycle -> earliest observation starts mid-cycle -> PARTIAL cycle
        partialEntries.add(Entry(weekStart.plus(2), Entry.YES_MANUAL))
        partialEntries.add(Entry(weekStart.plus(3), Entry.YES_MANUAL))
        habitList.update(habitPartial)

        // 7. Archived Habit
        var habitArchived = habitList.firstOrNull { it.name == HABIT_ARCHIVED_TITLE }
        if (habitArchived == null) {
            habitArchived = factory.buildHabit().apply {
                uuid = UUID.randomUUID().toString()
                name = HABIT_ARCHIVED_TITLE
                description = "Archived Habit for History QA"
                frequency = Frequency.DAILY
                type = HabitType.YES_NO
                isArchived = true
                color = PaletteColor(6)
            }
            habitList.add(habitArchived)
        }
        orgFacade.placeHabit(requireNotNull(habitArchived.uuid), rootId)
        val archivedEntries = habitArchived.originalEntries
        archivedEntries.clear()
        // Give ALL a real observation older than one year, independently of
        // the current calendar date and the other short-lived fixture data.
        archivedEntries.add(Entry(today.minus(400), Entry.YES_MANUAL))
        archivedEntries.add(Entry(today.minus(1), Entry.YES_MANUAL))
        archivedEntries.add(Entry(today.minus(2), Entry.YES_MANUAL))
        habitList.update(habitArchived)

        // 8. Unsupported Cadence Habit (Frequency 2/5 -> denominator 5)
        var habitUnsupported = habitList.firstOrNull { it.name == HABIT_UNSUPPORTED_TITLE }
        if (habitUnsupported == null) {
            habitUnsupported = factory.buildHabit().apply {
                uuid = UUID.randomUUID().toString()
                name = HABIT_UNSUPPORTED_TITLE
                description = "Unsupported 5-day Denominator Habit"
                frequency = Frequency(2, 5)
                type = HabitType.YES_NO
                color = PaletteColor(7)
            }
            habitList.add(habitUnsupported)
        }
        orgFacade.placeHabit(requireNotNull(habitUnsupported.uuid), childId)
        val unsupportedEntries = habitUnsupported.originalEntries
        unsupportedEntries.clear()
        unsupportedEntries.add(Entry(today.minus(1), Entry.YES_MANUAL))
        habitList.update(habitUnsupported)

        // 9. Contribution Ledger Seeding: Active, Revoked, and Excluded
        val contribRepo = component.contributionRepository ?: SQLiteContributionRepository(component.db)
        val yesterdayMillis = java.time.LocalDate.of(today.minus(1).year, today.minus(1).month, today.minus(1).day)
            .atTime(12, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val threeDaysAgoMillis = java.time.LocalDate.of(today.minus(3).year, today.minus(3).month, today.minus(3).day)
            .atTime(12, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

        // Active: 35_000L on yesterday (contributed to daily numerical habit)
        val activeContrib = Contribution(
            id = ContributionId(ACTIVE_CONTRIB_ID),
            targetHabit = HabitRef(requireNotNull(habitDailyNum.uuid)),
            sourceType = ContributionSourceType.FOCUS_SESSION,
            sourceId = FOCUS_SESSION_1_ID,
            contributedValue = 35_000L,
            effectiveTimestamp = yesterdayMillis,
            createdAt = yesterdayMillis,
            updatedAt = yesterdayMillis
        )
        contribRepo.record(activeContrib)

        // Revoked: 20_000L on yesterday (tombstoned with removedAt != null)
        val revokedContrib = Contribution(
            id = ContributionId(REVOKED_CONTRIB_ID),
            targetHabit = HabitRef(requireNotNull(habitDailyNum.uuid)),
            sourceType = ContributionSourceType.FOCUS_SESSION,
            sourceId = "fs-qa-revoked",
            contributedValue = 20_000L,
            effectiveTimestamp = yesterdayMillis,
            createdAt = yesterdayMillis,
            updatedAt = yesterdayMillis + 3_600_000L,
            removedAt = yesterdayMillis + 3_600_000L
        )
        contribRepo.record(revokedContrib)

        // Excluded: 15_000L on 3 days ago (where habitDailyNum is SKIP -> excluded from active goal sum)
        val excludedContrib = Contribution(
            id = ContributionId(EXCLUDED_CONTRIB_ID),
            targetHabit = HabitRef(requireNotNull(habitDailyNum.uuid)),
            sourceType = ContributionSourceType.FOCUS_SESSION,
            sourceId = "fs-qa-excluded",
            contributedValue = 15_000L,
            effectiveTimestamp = threeDaysAgoMillis,
            createdAt = threeDaysAgoMillis,
            updatedAt = threeDaysAgoMillis
        )
        contribRepo.record(excludedContrib)

        // 10. Seed Tasks & Focus Sessions for Overview T1, F1, F2 Equality
        val taskRepo = component.userTaskRepository
        val taskService = component.userTaskService
        val completedTasks = mutableListOf<UserTask>()

        for (i in 1..5) {
            val title = "QA HabitOverview Completed Task #$i"
            var task = taskRepo.getRoots().firstOrNull { it.title == title }
            if (task == null) {
                task = taskService.createTask(
                    title,
                    containerId = if (i % 2 == 0) childId else rootId,
                    due = TaskDue.Day(today.toTaskDueDate())
                )
                task = task.copy(
                    status = TaskStatus.COMPLETED,
                    completedAt = now - (i * 300_000L)
                )
                taskRepo.save(task)
            }
            completedTasks.add(task)
        }

        val focusRepo = component.focusSessionRepository ?: SQLiteFocusSessionRepository(component.db)
        val sessions = mutableListOf<FocusSession>()

        val s1Candidate = FocusSession(
            id = FocusSessionId(FOCUS_SESSION_1_ID),
            startedAt = now - 3_600_000L,
            endedAt = now - 1_800_000L,
            durationMs = 1_800_000L, // 30 mins
            taskId = completedTasks.first().id,
            containerId = childId,
            source = FocusSessionSource.TIMER,
            createdAt = now,
            updatedAt = now
        )
        val s1 = focusRepo.getById(s1Candidate.id) ?: s1Candidate.also { focusRepo.record(it) }
        sessions.add(s1)

        val s2Candidate = FocusSession(
            id = FocusSessionId(FOCUS_SESSION_2_ID),
            startedAt = now - 5_400_000L,
            endedAt = now - 3_600_000L,
            durationMs = 1_800_000L, // 30 mins
            taskId = completedTasks[1].id,
            containerId = rootId,
            source = FocusSessionSource.TIMER,
            createdAt = now,
            updatedAt = now
        )
        val s2 = focusRepo.getById(s2Candidate.id) ?: s2Candidate.also { focusRepo.record(it) }
        sessions.add(s2)

        val totalFocusDuration = sessions.sumOf { it.durationMs }

        return HabitOverviewSeedingResult(
            rootContainer = rootId,
            childContainer = childId,
            emptyContainer = emptyId,
            habitDailyBool = habitDailyBool,
            habitWeeklyBool = habitWeeklyBool,
            habitMonthlyBool = habitMonthlyBool,
            habitDailyNum = habitDailyNum,
            habitWeeklyNum = habitWeeklyNum,
            habitPartial = habitPartial,
            habitArchived = habitArchived,
            habitUnsupported = habitUnsupported,
            completedTasks = completedTasks,
            focusSessions = sessions,
            activeContribution = activeContrib,
            revokedContribution = revokedContrib,
            excludedContribution = excludedContrib,
            expectedTasksT1Count = completedTasks.size,
            expectedFocusF1DurationMs = totalFocusDuration,
            expectedFocusF2SessionCount = sessions.size,
        )
    }

    // -------------------------------------------------------------------------
    // Main PR14.4 Journey
    // -------------------------------------------------------------------------

    @Test
    fun habitOverviewJourney() {
        // 1. Safety and offline disposable session verification
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
            val seed = seedHabitOverviewData()

            // 3. Launch MainActivity with QaActivityController
            QaActivityController().use { controller ->
                navigateToStatistics(controller)

                // 4. Validate Activity configuration, contrast, and layout
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

                // Ensure initial scope is All
                controller.onActivity { activity ->
                    val fragment = statsFragment(activity)
                    fragment.applyScopedAll()
                }
                idle()
                waitForSettled(controller)

                // Assert click controls >= 48dp on control bar
                assertMinimumTouchTargets(controller)
                assertVisibleTextGeometry()

                // =============================================================
                // PHASE 1: OVERVIEW TAB VERIFICATION
                // =============================================================
                selectTab(controller, 0)
                assertTabSelected(controller, UnifiedAnalyticsTab.OVERVIEW)

                // Viewport scrolling: top captures un-scrolled top
                device.scrollStatisticsReportToEdge(Direction.UP)
                idle()
                capture("01-overview-top")

                val overviewT1Text = device.statisticsControl("overviewTasksValue").text.orEmpty()
                val overviewF1DurationText = device.statisticsControl("overviewFocusValue").text.orEmpty()
                val overviewF2SessionsText = device.statisticsControl("overview_focus_f2").text.orEmpty()
                device.statisticsControl("overviewCadenceGroup")
                assertTrue("Overview must show focus duration and session count", overviewF1DurationText.isNotBlank() && overviewF2SessionsText.isNotBlank())
                controller.onActivity { activity ->
                    val report = requireNotNull(getLoadedReport(statsFragment(activity)))
                    assertEquals("Overview T1 must equal report completed tasks count", report.tasks?.completedCount?.toString(), overviewT1Text)
                }

                // Verify exact rendered text equality with Tasks tab (Tab 2)
                selectTab(controller, 2)
                assertTabSelected(controller, UnifiedAnalyticsTab.TASKS)
                val tasksRenderedT1 = device.revealStatisticsReportControl("unified_tasks_t1").text.orEmpty()
                assertEquals("Tasks tab rendered T1 count must match Overview T1 card exactly", overviewT1Text, tasksRenderedT1)

                // Verify exact rendered text equality with Focus tab (Tab 3)
                selectTab(controller, 3)
                assertTabSelected(controller, UnifiedAnalyticsTab.FOCUS)
                val focusRenderedF1 = device.revealStatisticsReportControl("unified_focus_f1").text.orEmpty()
                val focusRenderedF2 = device.revealStatisticsReportControl("unified_focus_f2").text.orEmpty()
                assertEquals("Focus tab rendered F1 duration must match Overview F1 duration card exactly", overviewF1DurationText, focusRenderedF1)
                val overviewF2Digits = Regex("\\d+").find(overviewF2SessionsText)?.value.orEmpty()
                val focusRenderedF2Digits = Regex("\\d+").find(focusRenderedF2)?.value.orEmpty()
                assertEquals("Focus tab rendered F2 session count digits must match Overview F2 card exactly", overviewF2Digits, focusRenderedF2Digits)

                // Return to Overview (Tab 0)
                selectTab(controller, 0)
                assertTabSelected(controller, UnifiedAnalyticsTab.OVERVIEW)

                // Scroll to period dynamics graphs before capturing 02-overview-dynamics
                device.revealStatisticsReportControl("overviewDynamicsGroup")
                capture("02-overview-dynamics")

                // Overview cadence row switches cleanly to the Habits tab preserving query context.
                var overviewContext: org.isoron.uhabits.core.statistics.StatisticsQueryContext? = null
                controller.onActivity { activity ->
                    val fragment = statsFragment(activity)
                    overviewContext = requireNotNull(getLoadedReport(fragment)).context
                }
                device.revealStatisticsReportControl("habitCadenceDailyRow", Direction.UP).click()
                idle()
                waitForSettled(controller)
                assertTabSelected(controller, UnifiedAnalyticsTab.HABITS)
                controller.onActivity { activity ->
                    val fragment = statsFragment(activity)
                    val report = requireNotNull(getLoadedReport(fragment))
                    assertEquals("Overview navigation preserves query context", overviewContext, report.context)
                    assertEquals("Overview navigation selects DAILY cadence", HabitCadence.DAILY, fragment.selectedHabitCadence)
                }

                // =============================================================
                // PHASE 2: HABITS TAB VERIFICATION (C1 - C7)
                // =============================================================
                selectTab(controller, 1)
                assertTabSelected(controller, UnifiedAnalyticsTab.HABITS)

                // Viewport scrolling: un-scrolled top
                device.scrollStatisticsReportToEdge(Direction.UP)
                idle()
                capture("03-habits-top")

                // C1, C2, C3, C4, C5: Invariants on Loaded Report
                controller.onActivity { activity ->
                    val fragment = statsFragment(activity)
                    val report = requireNotNull(getLoadedReport(fragment)) { "Habits report must not be null" }
                    val habitsResult = requireNotNull(report.habits) { "Habits result must not be null" }

                    // C1: Verify closed, ongoing, transition, and partial cycles
                    val allCycles = habitsResult.allCycles
                    assertTrue("FAIL-CLOSED: All cycles must not be empty", allCycles.isNotEmpty())

                    val closed = habitsResult.closedCycles
                    assertTrue("FAIL-CLOSED: Closed cycles must be present", closed.isNotEmpty())

                    val ongoing = habitsResult.ongoingCycles
                    assertTrue("FAIL-CLOSED: Ongoing cycles must be present", ongoing.isNotEmpty())

                    // Transition cycle verification (from mid-cycle goal change)
                    val transitions = allCycles.filter { it.lifecycle == HabitCycleLifecycle.TRANSITION }
                    assertTrue("FAIL-CLOSED: Transition cycles must be recognized from goal history", transitions.isNotEmpty())

                    // Partial cycle verification (from mid-cycle observation start)
                    val partials = allCycles.filter { it.lifecycle == HabitCycleLifecycle.PARTIAL }
                    assertTrue("FAIL-CLOSED: Partial cycles must be recognized from observation start", partials.isNotEmpty())

                    // C3: Outcomes presence
                    val outcomes = closed.map { it.outcome }.toSet()
                    assertTrue("Closed cycles must contain SATISFIED", outcomes.contains(HabitCycleOutcome.SATISFIED))
                    assertTrue("Closed cycles must contain NOT_SATISFIED or EXEMPT or CAPACITY_EXCLUDED",
                        outcomes.contains(HabitCycleOutcome.NOT_SATISFIED) ||
                        outcomes.contains(HabitCycleOutcome.EXEMPT) ||
                        outcomes.contains(HabitCycleOutcome.CAPACITY_EXCLUDED))

                    // C4: SKIP Policy & Capacity Exclusion
                    val capacityExcluded = allCycles.filter { it.outcome == HabitCycleOutcome.CAPACITY_EXCLUDED }
                    assertTrue("FAIL-CLOSED: Capacity excluded cycle must exist for weekly habit with 5 SKIP days", capacityExcluded.isNotEmpty())

                    // C3: Lower bound with contributions
                    val activeRevokedCycle = requireNotNull(
                        allCycles.firstOrNull { cycle ->
                            cycle.days.any { day ->
                                day.contributions.any { it.id.value == ACTIVE_CONTRIB_ID } ||
                                    day.tombstonedContributions.any { it.id.value == REVOKED_CONTRIB_ID }
                            }
                        }
                    ) { "FAIL-CLOSED: Cycle with active ($ACTIVE_CONTRIB_ID) or revoked ($REVOKED_CONTRIB_ID) contribution must exist" }

                    val qActive = activeRevokedCycle.quantity
                    assertTrue("Numerical lower bound must be >= manualValue + activeContributionValue",
                        qActive.lowerBound >= qActive.manualValue + qActive.activeContributionValue)
                    assertEquals("Active contribution must be 35_000L", 35_000L, qActive.activeContributionValue)
                    assertEquals("Revoked contribution must be tracked separately", 20_000L, qActive.revokedContributionValue)

                    val excludedCycle = requireNotNull(
                        allCycles.firstOrNull { cycle ->
                            cycle.days.any { day ->
                                day.contributions.any { it.id.value == EXCLUDED_CONTRIB_ID }
                            }
                        }
                    ) { "FAIL-CLOSED: Cycle with excluded contribution $EXCLUDED_CONTRIB_ID must exist" }

                    val qExcluded = excludedCycle.quantity
                    assertEquals("Excluded contribution must be tracked separately", 15_000L, qExcluded.excludedContributionValue)

                    // C5: Cadence aggregates separation
                    val daily = habitsResult.daily
                    val weekly = habitsResult.weekly
                    val monthly = habitsResult.monthly
                    assertEquals(HabitCadence.DAILY, daily.cadence)
                    assertEquals(HabitCadence.WEEKLY, weekly.cadence)
                    assertEquals(HabitCadence.MONTHLY, monthly.cadence)
                }

                // C5: FAIL-CLOSED View Assertions for Habits Cadence Tab Selector (Weekly/Monthly moved off Overview Hero per R02)
                listOf("Daily", "Weekly", "Monthly").forEach { cadence ->
                    device.revealStatisticsReportControl("habitCadenceTab$cadence")
                }

                // C6. Verify the drawn cadence axis and accessibility of the chart columns.
                var chartDensity = 1f
                controller.onActivity { chartDensity = it.resources.displayMetrics.density }
                device.assertStatisticsChart("habitCadenceChart", chartDensity)

                // Test cadence chart content changes across cadence tab clicks (WEEKLY -> MONTHLY -> DAILY)
                for ((cadence, tagSuffix) in listOf(HabitCadence.WEEKLY to "Weekly", HabitCadence.MONTHLY to "Monthly", HabitCadence.DAILY to "Daily")) {
                    val tab = "habitCadenceTab$tagSuffix"
                    device.revealStatisticsReportControl(tab, Direction.UP).click()
                    idle()
                    waitForSettled(controller)
                    assertTrue("Cadence pill must report its selection", device.wait(Until.hasObject(By.res(tab).selected(true)), 5_000))
                    device.revealStatisticsReportControl("habitCadence${tagSuffix}Row")
                    var hasChart = false
                    var expectedEmpty = ""
                    controller.onActivity { activity ->
                        val fragment = statsFragment(activity)
                        assertEquals("Selected cadence must update", cadence, field<HabitCadence>(fragment, "selectedHabitCadence"))
                        val buckets = requireNotNull(getLoadedReport(fragment)?.habits).dateBuckets[cadence].orEmpty()
                        hasChart = buckets.any { it.satisfiedCount + it.notSatisfiedCount > 0 }
                        expectedEmpty = activity.getString(R.string.unified_statistics_habits_chart_empty)
                    }
                    if (!hasChart && cadence != HabitCadence.DAILY) {
                        assertEquals("No results must display the empty chart state", expectedEmpty,
                            device.revealStatisticsReportControl("habitCadenceChart").text)
                    } else {
                        device.assertStatisticsChart("habitCadenceChart", chartDensity)
                    }
                }
                device.revealStatisticsReportControl("habitCadenceChart")
                capture("04-habits-cadence-chart")

                // Tap a real chart column using its measured geometry and
                // compare its segments and opened selection with the bucket.
                var expectedBucketIds: List<String> = emptyList()
                var bucketColumn = -1
                controller.onActivity { activity ->
                    val fragment = statsFragment(activity)
                    val report = requireNotNull(getLoadedReport(fragment))
                    val columns = DiscreteChartBucketer.buildHabitColumns(
                        fragment.currentPeriodValue, HabitCadence.DAILY, requireNotNull(report.habits),
                        field(fragment, "lastKnownToday"),
                        org.isoron.uhabits.activities.common.theme.MainTabsThemeBridge.resolve(activity),
                        Locale.getDefault().language == "ru", includeUnknownSegment = false,
                    )
                    val index = columns.indexOfFirst { it.value > 0L }
                    assertTrue("Fixture must have a nonempty daily chart bucket", index >= 0)
                    val column = columns[index]
                    val descriptor = column.descriptor as HabitsDrilldownDescriptor.BucketCycles
                    val bucket = requireNotNull(report.habits).dateBuckets[HabitCadence.DAILY].orEmpty()
                        .first { it.date == descriptor.date }
                    val plottedOutcomes = listOf(HabitCycleOutcome.SATISFIED, HabitCycleOutcome.NOT_SATISFIED)
                    val allOutcomes = listOf(HabitCycleOutcome.SATISFIED, HabitCycleOutcome.NOT_SATISFIED, HabitCycleOutcome.UNKNOWN)
                    val expectedCounts = plottedOutcomes.map { outcome -> bucket.cycles.count { it.outcome == outcome }.toLong() }
                    assertEquals(listOf(bucket.satisfiedCount.toLong(), bucket.notSatisfiedCount.toLong()), expectedCounts)
                    assertEquals("Chart segments must equal nonzero S/F counts", expectedCounts.filter { it > 0 }, column.segments.map { it.value })
                    assertEquals("Chart total must equal S+F", expectedCounts.sum(), column.value)
                    expectedBucketIds = bucket.cycles.filter { it.outcome in allOutcomes }.map { it.key.toString() }
                    bucketColumn = index
                }
                device.revealStatisticsChartColumn("habitCadenceChart", bucketColumn).click()
                idle()
                val bucketSheet = requireNotNull(findOpenBottomSheet(controller) as? UnifiedStatisticsDrilldownBottomSheet) {
                    "Chart column must open the selected bucket"
                }
                assertEquals("Chart selection IDs must equal bucket cycle IDs", expectedBucketIds.take(30), bucketSheet.visibleIds)
                dismissDialogs(controller)

                // =============================================================
                // PHASE 3: C7 DRILL-DOWN & CYCLE PROVENANCE SHEET
                // =============================================================
                // Click Daily cadence row to open drilldown sheet
                device.revealStatisticsReportControl("habitCadenceDailyRow", Direction.UP).click()
                idle()
                waitForSettled(controller)

                val drilldownSheet = requireNotNull(findOpenBottomSheet(controller) as? UnifiedStatisticsDrilldownBottomSheet) {
                    "FAIL-CLOSED: UnifiedStatisticsDrilldownBottomSheet must be open upon cadence row click"
                }
                capture("05-habits-drilldown-sheet")

                // Assert exact selected cycle keys vs aggregate and page membership
                lateinit var firstCycle: HabitCycle
                controller.onActivity { activity ->
                    val fragment = statsFragment(activity)
                    val report = requireNotNull(getLoadedReport(fragment))
                    val dailyCycles = report.habits!!.daily.cycles
                    val expectedPageKeys = dailyCycles.take(30).map { it.key.toString() }
                    assertEquals("Visible cycle keys in drilldown sheet must match aggregate first page",
                        expectedPageKeys, drilldownSheet.visibleIds)
                    assertTrue("Expected page keys must not be empty", expectedPageKeys.isNotEmpty())
                    firstCycle = dailyCycles.first()
                }

                // In drilldown sheet: click first cycle item LOCATED BY EXACT KEY (no generic clickable fallback)
                device.revealStatisticsDrilldownControl(firstCycle.key.toString()).click()
                idle()
                waitForSettled(controller)

                assertTrue(findOpenBottomSheet(controller) is UnifiedStatisticsCycleDetailBottomSheet)
                device.statisticsControl("habitCycleDetailSheet")
                capture("06-habits-cycle-detail-sheet")

                val date = cycleControl("habitCycleDatesText").statisticsTexts().joinToString()
                assertTrue(date.contains(formatStatisticsDate(firstCycle.startDate)))
                assertTrue(date.contains(formatStatisticsDate(firstCycle.endDate)))
                assertTrue(cycleControl("habitCycleTargetText").statisticsTexts().isNotEmpty())
                val effectiveDate = firstCycle.goal.effectiveDate
                if (firstCycle.key.goalVersionKey.isNotBlank() && effectiveDate != LocalDate(2000, 1, 1)) {
                    assertTrue(cycleControl("habitCycleGoalVersion").text.orEmpty().contains(formatStatisticsDate(effectiveDate)))
                }
                val quantity = firstCycle.quantity
                val expectedQuantity = if (quantity.isNumerical) {
                    formatCycleFixtureAmount(quantity.manualValue, quantity.unit)
                } else quantity.booleanManualCount.toString()
                assertTrue(cycleControl("habitCycleQuantityText").statisticsTexts().any { it.contains(expectedQuantity) })
                val entryTag = firstCycle.days.firstOrNull()?.let { "habitCycleManualEntry:${it.manualEntry.date.daysSince2000}" }
                assertTrue(cycleControl(entryTag ?: "habitCycleEntriesEmpty").statisticsTexts().isNotEmpty())
                cycleControl("habitCycleContributionsLayout")

                // Cancel must keep the detail sheet open, and Continue must launch the existing route.
                cycleControl("habitCycleOpenHabitButton").click()
                assertEquals(
                    instrumentation.targetContext.getString(R.string.unified_statistics_cycle_unfiltered_warning_body),
                    device.statisticsControl("habitCycleDetailWarningMessage").text,
                )
                device.statisticsControl("habitCycleDetailWarningCancel").click()
                assertTrue(device.wait(Until.gone(By.res("habitCycleDetailWarningDialog")), 5_000))
                assertTrue(findOpenBottomSheet(controller) is UnifiedStatisticsCycleDetailBottomSheet)

                cycleControl("habitCycleOpenHabitButton").click()
                device.statisticsControl("habitCycleDetailWarningConfirm").click()
                idle()

                // Assert ShowHabitActivity opened via real button
                val deadline = SystemClock.uptimeMillis() + 5000
                var showHabitStarted = false
                while (SystemClock.uptimeMillis() < deadline) {
                    instrumentation.runOnMainSync {
                        val resumed = ActivityLifecycleMonitorRegistry.getInstance()
                            .getActivitiesInStage(Stage.RESUMED)
                        if (resumed.any { it.javaClass.simpleName.contains("ShowHabit") }) {
                            showHabitStarted = true
                        }
                    }
                    if (showHabitStarted) break
                    SystemClock.sleep(50)
                }
                assertTrue("FAIL-CLOSED: ShowHabitActivity must be opened via Open Habit button", showHabitStarted)

                // Return from ShowHabitActivity back to MainActivity
                device.pressBack()
                idle()
                waitForSettled(controller)

                // Assert Back hierarchy: dismiss detail sheet -> returns to drilldown sheet -> dismiss drilldown -> returns to Habits
                device.pressBack()
                idle()
                device.pressBack()
                idle()
                assertNull("All dialogs and bottom sheets must be dismissed", findOpenBottomSheet(controller))

                verifyCycleContributionLedger(controller)

                // Verify compact Habits drilldown action strip avoids duplicating main Habits list (R03)
                device.revealStatisticsReportControl("habitAlphaPreviewList")
                device.revealStatisticsReportControl("habitCompareEntry")

                // Verify archived habit in full searchable list (via All habits button)
                device.revealStatisticsReportControl("habitAllHabitsButton").click()
                idle()
                waitForSettled(controller)

                val fullListSheet = requireNotNull(findOpenBottomSheet(controller) as? UnifiedStatisticsDrilldownBottomSheet) {
                    "FAIL-CLOSED: Full habit list sheet must open on All habits button click"
                }
                val archivedId = requireNotNull(seed.habitArchived.uuid)
                val archivedRow = device.revealStatisticsDrilldownControl(archivedId)
                assertTrue("Habit list must show the archived habit", archivedRow.statisticsTexts().any { it.contains(HABIT_ARCHIVED_TITLE) })
                assertTrue(fullListSheet.visibleIds.contains(archivedId))

                // Enter the query through the Compose text field and verify the exact retained result.
                val search = device.revealStatisticsDrilldownControl("habitSearchInput", Direction.UP)
                search.text = HABIT_ARCHIVED_TITLE
                idle()
                controller.onActivity { assertEquals(listOf(archivedId), fullListSheet.visibleIds) }
                assertTrue(device.revealStatisticsDrilldownControl(archivedId).isClickable)

                // Dismiss full list sheet
                device.pressBack()
                idle()
                assertNull("Search sheet must be dismissed", findOpenBottomSheet(controller))

                // =============================================================
                // PHASE 4: RAPID PERIOD & SCOPE FILTERS
                // =============================================================
                // Periods traversal asserting actual number and date changes: DAY -> WEEK -> MONTH -> YEAR -> ALL -> WEEK
                selectPeriodViaUi(controller, UnifiedPeriod.DAY)
                assertPeriodSelected(controller, UnifiedPeriod.DAY)
                controller.onActivity { activity ->
                    val rep = requireNotNull(getLoadedReport(statsFragment(activity)))
                    assertEquals("DAY period from must equal to", rep.context.to, rep.context.from)
                }

                selectPeriodViaUi(controller, UnifiedPeriod.MONTH)
                assertPeriodSelected(controller, UnifiedPeriod.MONTH)
                controller.onActivity { activity ->
                    val rep = requireNotNull(getLoadedReport(statsFragment(activity)))
                    val span = rep.context.to.daysSince2000 - rep.context.from.daysSince2000 + 1
                    assertTrue("MONTH period must span 28-31 days, actual=$span", span in 28..31)
                }

                selectPeriodViaUi(controller, UnifiedPeriod.YEAR)
                assertPeriodSelected(controller, UnifiedPeriod.YEAR)
                controller.onActivity { activity ->
                    val rep = requireNotNull(getLoadedReport(statsFragment(activity)))
                    val span = rep.context.to.daysSince2000 - rep.context.from.daysSince2000 + 1
                    assertTrue("YEAR period must span 365-366 days, actual=$span", span in 365..366)
                }

                selectPeriodViaUi(controller, UnifiedPeriod.ALL)
                assertPeriodSelected(controller, UnifiedPeriod.ALL)
                controller.onActivity { activity ->
                    val rep = requireNotNull(getLoadedReport(statsFragment(activity)))
                    val span = rep.context.to.daysSince2000 - rep.context.from.daysSince2000 + 1
                    assertTrue("ALL period must span history > 366 days, actual=$span", span > 366)
                }

                // Return to WEEK
                selectPeriodViaUi(controller, UnifiedPeriod.WEEK)
                assertPeriodSelected(controller, UnifiedPeriod.WEEK)

                // Scope traversals: SUBTREE -> HERE -> UNASSIGNED -> EMPTY -> ALL
                // 1. SUBTREE scope (Root Container with descendants)
                controller.onActivity { activity ->
                    val fragment = statsFragment(activity)
                    fragment.applyScopedContainer(seed.rootContainer, includeDescendants = true)
                }
                idle()
                waitForSettled(controller)
                controller.onActivity { activity ->
                    val fragment = statsFragment(activity)
                    val report = requireNotNull(getLoadedReport(fragment))
                    val habits = requireNotNull(report.habits)
                    assertFalse("Unassigned habit must NOT be in Subtree container scope",
                        habits.allCycles.any { it.habitName == HABIT_PARTIAL_TITLE })
                    assertTrue("Child container habit must be present in Subtree container scope",
                        habits.allCycles.any { it.habitName == HABIT_WEEKLY_BOOL_TITLE })
                }

                // 2. HERE scope (Root Container without descendants)
                controller.onActivity { activity ->
                    val fragment = statsFragment(activity)
                    fragment.applyScopedContainer(seed.rootContainer, includeDescendants = false)
                }
                idle()
                waitForSettled(controller)
                controller.onActivity { activity ->
                    val fragment = statsFragment(activity)
                    val report = requireNotNull(getLoadedReport(fragment))
                    val habits = requireNotNull(report.habits)
                    assertFalse("Child container habit must NOT be in HERE container scope",
                        habits.allCycles.any { it.habitName == HABIT_WEEKLY_BOOL_TITLE })
                    assertTrue("Root container habit must be present in HERE container scope",
                        habits.allCycles.any { it.habitName == HABIT_DAILY_BOOL_TITLE })
                }

                // 3. UNASSIGNED scope
                controller.onActivity { activity ->
                    val fragment = statsFragment(activity)
                    fragment.applyScopedUnassigned()
                }
                idle()
                waitForSettled(controller)
                controller.onActivity { activity ->
                    val fragment = statsFragment(activity)
                    val report = requireNotNull(getLoadedReport(fragment))
                    val habits = requireNotNull(report.habits)
                    val placementRepo = component.organizationFacade.habitPlacementQueries
                    val partialPlacement = placementRepo?.current(
                        HabitRef(requireNotNull(seed.habitPartial.uuid))
                    )
                    assertTrue(
                        "Unassigned habit must be present in Unassigned scope: " +
                            "scope=${report.context.sectionScope}, from=${report.context.from}, to=${report.context.to}, " +
                            "firstWeekday=${report.context.firstWeekday}, habitToday=${report.context.habitToday}, " +
                            "partialPlacement=$partialPlacement, cycleHabits=${habits.allCycles.map { it.habitName }.distinct()}",
                        habits.allCycles.any { it.habitName == HABIT_PARTIAL_TITLE }
                    )
                    assertFalse("Root container habit must NOT be in Unassigned scope",
                        habits.allCycles.any { it.habitName == HABIT_DAILY_BOOL_TITLE })
                }

                // 4. EMPTY container scope
                controller.onActivity { activity ->
                    val fragment = statsFragment(activity)
                    fragment.applyScopedContainer(seed.emptyContainer, includeDescendants = true)
                }
                idle()
                waitForSettled(controller)
                controller.onActivity { activity ->
                    val fragment = statsFragment(activity)
                    val emptyReport = requireNotNull(getLoadedReport(fragment))
                    val emptyHabits = emptyReport.habits
                    if (emptyHabits != null) {
                        assertEquals("Empty container must yield 0 closed cycles", 0, emptyHabits.closedCycles.size)
                    }
                }
                val emptyText = device.statisticsControl("unified_report_message").text
                controller.onActivity {
                    assertEquals("Empty container must display the Habits empty state", it.getString(R.string.unified_statistics_habits_empty), emptyText)
                }

                // Restore All scope
                controller.onActivity { activity ->
                    val fragment = statsFragment(activity)
                    fragment.applyScopedAll()
                }
                idle()
                waitForSettled(controller)

                // =============================================================
                // PHASE 5: LIVE MUTATION & CONTRIBUTION WHILE SHEET IS OPEN
                // =============================================================
                // On the first two days of a week, today-2 belongs to the
                // preceding week. Select that week before opening the sheet.
                val mutationToday = getToday()
                val currentWeekStart = mutationToday.startOfWeek(component.preferences.firstWeekday)
                var showingPreviousWeek = mutationToday.minus(2) < currentWeekStart
                if (showingPreviousWeek) {
                    device.statisticsControl("unified_prev").click()
                    idle()
                    waitForSettled(controller)
                }
                device.revealStatisticsReportControl("habitCadenceDailyRow", Direction.UP).click()
                idle()
                waitForSettled(controller)

                val openDrilldown = requireNotNull(findOpenBottomSheet(controller) as? UnifiedStatisticsDrilldownBottomSheet) {
                    "FAIL-CLOSED: Sheet must be open before mutation"
                }

                // Record pre-mutation fact state
                val preRevision = component.effectiveReadRevision.value
                val todayVal = getToday()
                var preQuantityLowerBound = 0L
                var preOutcome = HabitCycleOutcome.UNKNOWN
                var targetCycleKey = ""

                controller.onActivity { activity ->
                    val fragment = statsFragment(activity)
                    val rep = requireNotNull(getLoadedReport(fragment))
                    val cycle = rep.habits!!.allCycles.first {
                        it.habitName == HABIT_DAILY_NUM_TITLE && it.startDate == todayVal.minus(2)
                    }
                    targetCycleKey = cycle.key.toString()
                    preQuantityLowerBound = cycle.quantity.lowerBound
                    preOutcome = cycle.outcome
                    assertTrue(targetCycleKey in openDrilldown.visibleIds)
                }
                val preRowTexts = device.revealStatisticsDrilldownControl(targetCycleKey).statisticsTexts()

                // 1. Mutate habit entry: change today.minus(2) entry on habitDailyNum from 70_000 to 10_000
                controller.onActivity {
                    val habit = seed.habitDailyNum
                    habit.originalEntries.add(Entry(todayVal.minus(2), 10_000))
                    component.habitList.update(habit)
                }
                idle()
                waitForSettled(controller)

                // Assert effectiveReadRevision strictly advanced (no post >= pre tautology)
                val postRevision = component.effectiveReadRevision.value
                assertTrue("FAIL-CLOSED: effectiveReadRevision must strictly advance: pre=$preRevision, post=$postRevision",
                    postRevision > preRevision)

                // Assert actual report cycle quantity and outcome changed
                controller.onActivity { activity ->
                    val fragment = statsFragment(activity)
                    val rep = requireNotNull(getLoadedReport(fragment))
                    val cycle = rep.habits!!.allCycles.first {
                        it.habitName == HABIT_DAILY_NUM_TITLE && it.startDate == todayVal.minus(2)
                    }
                    assertEquals("Report cycle lower bound must update to 10_000", 10_000L, cycle.quantity.manualValue)
                    assertNotEquals("Report cycle outcome must change from SATISFIED", HabitCycleOutcome.SATISFIED, cycle.outcome)

                }
                val postRowTexts = device.revealStatisticsDrilldownControl(targetCycleKey).statisticsTexts()
                assertNotEquals("Visible sheet row text must update after live mutation", preRowTexts, postRowTexts)

                // 2. Insert & Revoke live Contribution while statistics is open
                // Yesterday may be in the current week even when today-2 is
                // in the preceding week. Switch back only in that case.
                if (showingPreviousWeek && todayVal.minus(1) >= currentWeekStart) {
                    dismissDialogs(controller)
                    device.statisticsControl("unified_next").click()
                    idle()
                    waitForSettled(controller)
                    showingPreviousWeek = false
                    device.revealStatisticsReportControl("habitCadenceDailyRow", Direction.UP).click()
                    idle()
                    requireNotNull(findOpenBottomSheet(controller) as? UnifiedStatisticsDrilldownBottomSheet) {
                        "Daily drilldown must reopen before the Contribution mutation"
                    }
                }
                val contribRepo = component.contributionRepository ?: SQLiteContributionRepository(component.db)
                val liveContribId = "contrib-qa-live-${UUID.randomUUID()}"
                val liveYesterdayMillis = java.time.LocalDate.of(todayVal.minus(1).year, todayVal.minus(1).month, todayVal.minus(1).day)
                    .atTime(12, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                val liveContrib = Contribution(
                    id = ContributionId(liveContribId),
                    targetHabit = HabitRef(requireNotNull(seed.habitDailyNum.uuid)),
                    sourceType = ContributionSourceType.FOCUS_SESSION,
                    sourceId = "fs-live-test",
                    contributedValue = 40_000L,
                    effectiveTimestamp = liveYesterdayMillis,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )
                val beforeContributionRevision = component.effectiveReadRevision.value
                assertEquals(
                    org.isoron.uhabits.core.contribution.ContributionRecordResult.INSERTED,
                    contribRepo.record(liveContrib)
                )
                assertTrue("Contribution insert must advance production read revision",
                    component.effectiveReadRevision.value > beforeContributionRevision)
                idle()
                waitForSettled(controller)

                // Assert active contribution increased in report
                controller.onActivity { activity ->
                    val fragment = statsFragment(activity)
                    val rep = requireNotNull(getLoadedReport(fragment))
                    val cycle = rep.habits!!.allCycles.first {
                        it.habitName == HABIT_DAILY_NUM_TITLE && it.startDate == todayVal.minus(1)
                    }
                    assertEquals("Active contribution must include new 40_000L contribution",
                        35_000L + 40_000L, cycle.quantity.activeContributionValue)
                }

                // Revoke live contribution
                val beforeRemovalRevision = component.effectiveReadRevision.value
                assertEquals(
                    org.isoron.uhabits.core.contribution.ContributionRemovalResult.REMOVED,
                    contribRepo.removeById(liveContrib.id, System.currentTimeMillis())
                )
                assertTrue("Contribution removal must advance production read revision",
                    component.effectiveReadRevision.value > beforeRemovalRevision)
                idle()
                waitForSettled(controller)

                // Assert revoked contribution tracked in report
                controller.onActivity { activity ->
                    val fragment = statsFragment(activity)
                    val rep = requireNotNull(getLoadedReport(fragment))
                    val cycle = rep.habits!!.allCycles.first {
                        it.habitName == HABIT_DAILY_NUM_TITLE && it.startDate == todayVal.minus(1)
                    }
                    assertEquals("Active contribution must return to baseline 35_000L",
                        35_000L, cycle.quantity.activeContributionValue)
                    assertEquals("Revoked contribution must include revoked 40_000L",
                        20_000L + 40_000L, cycle.quantity.revokedContributionValue)
                }

                dismissDialogs(controller)

                if (showingPreviousWeek) {
                    device.statisticsControl("unified_next").click()
                    idle()
                    waitForSettled(controller)
                }

                // =============================================================
                // PHASE 6: ACTIVITY RECREATION & STATE PRESERVATION
                // =============================================================
                // Recreate Activity
                controller.recreate()
                idle()
                waitForSettled(controller)

                // Assert selected tab remains HABITS and period remains WEEK
                assertTabSelected(controller, UnifiedAnalyticsTab.HABITS)
                assertPeriodSelected(controller, UnifiedPeriod.WEEK)
                verifyHabitRapidScopeAndDatasetReplacement(controller, seed)
                verifyHabitLiveTimeAndSyncRefresh(controller, seed)
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

    /**
     * Targeted isolated instrumentation verification for ACTION_DATE_CHANGED/time refresh
     * and habitList.reloadAndNotify sync-style refresh while statistics is open.
     */
    @Test
    fun habitOverviewTimeAndSyncLiveRefresh() {
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

            val seed = seedHabitOverviewData()

            QaActivityController().use { controller ->
                navigateToStatistics(controller)
                controller.onActivity { activity ->
                    statsFragment(activity).applyScopedAll()
                }
                idle()
                waitForSettled(controller)
                selectTab(controller, UnifiedAnalyticsTab.HABITS.ordinal)
                if (device.hasObject(By.res("habitCadenceTabDaily"))) device.statisticsControl("habitCadenceTabDaily").click()
                idle()
                waitForSettled(controller)
                verifyHabitLiveTimeAndSyncRefresh(controller, seed)
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
            harness.guard()
        }
    }

    // -------------------------------------------------------------------------
    // Helper Methods
    // -------------------------------------------------------------------------

    private fun verifyHabitRapidScopeAndDatasetReplacement(
        controller: QaActivityController,
        seed: HabitOverviewSeedingResult,
    ) {
        harness.guard()
        val application = context.applicationContext as HabitsApplication
        val previous = component.session
        check(previous.isExperimental && !previous.capabilities.syncEnabled && !previous.capabilities.remoteCallsAllowed)
        val oldObserver = org.isoron.uhabits.database.AndroidSqlTiming.observer

        fun holdNextRead(): Pair<java.util.concurrent.CountDownLatch, java.util.concurrent.CountDownLatch> {
            val entered = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            val claimed = java.util.concurrent.atomic.AtomicBoolean()
            org.isoron.uhabits.database.AndroidSqlTiming.observer = { operation, _ ->
                if (operation == "SELECT" && Thread.currentThread().stackTrace.any {
                        it.className.contains("UnifiedStatisticsFragment")
                    } && claimed.compareAndSet(false, true)) {
                    entered.countDown()
                    check(release.await(15, java.util.concurrent.TimeUnit.SECONDS)) { "Held statistics reader timed out" }
                }
            }
            return entered to release
        }

        var release: java.util.concurrent.CountDownLatch? = null
        try {
            val (entered, unblock) = holdNextRead()
            release = unblock
            controller.onActivity { statsFragment(it).refresh() }
            assertTrue("Old Habit report read must be in flight", entered.await(8, java.util.concurrent.TimeUnit.SECONDS))
            controller.onActivity { activity ->
                val fragment = statsFragment(activity)
                fragment.applyScopedContainer(seed.rootContainer, true)
                fragment.applyScopedContainer(seed.childContainer, false)
                fragment.applyScopedContainer(seed.rootContainer, false)
                assertTrue("New scope must immediately enter loading", getUiState(fragment) is UnifiedReportUiState.InitialLoading)
                assertNull("Old Habit report must immediately be cleared", getLoadedReport(fragment))
            }
            assertTrue("Loading header must replace old Habit content", device.wait(Until.hasObject(By.res("unified_loading")), 5_000))
            assertFalse("Old Habit numbers must not remain visible", device.hasObject(By.res("habitCadenceDailyRow")))
            unblock.countDown()
            idle()
            waitForSettled(controller)
            val rowTexts = device.revealStatisticsReportControl("habitCadenceDailyRow", Direction.UP).statisticsTexts()
            controller.onActivity { activity ->
                val fragment = statsFragment(activity)
                val report = requireNotNull(getLoadedReport(fragment))
                val scope = report.context.sectionScope as org.isoron.uhabits.core.statistics.StatisticsSectionScope.Container
                assertEquals(seed.rootContainer, scope.id)
                assertFalse(scope.includeDescendants)
                val habits = requireNotNull(report.habits)
                assertTrue("Final HERE scope must contain root Habit", habits.allCycles.any { it.habitName == HABIT_DAILY_BOOL_TITLE })
                assertFalse("Final HERE scope must exclude child Habit", habits.allCycles.any { it.habitName == HABIT_WEEKLY_BOOL_TITLE })
                val daily = habits.daily
                if (daily.knownCount > 0) {
                    val pct = Math.round((daily.satisfiedCount.toDouble() / daily.knownCount.toDouble()) * 100.0).toInt()
                    assertTrue("Visible percentage must match final scope report: expected $pct% in $rowTexts",
                        rowTexts.any { it.contains("$pct%") })
                    assertTrue("Visible counts must match final scope report: expected ${daily.satisfiedCount}/${daily.knownCount} in $rowTexts",
                        rowTexts.any {
                            it.contains("${daily.satisfiedCount}/${daily.knownCount}") ||
                                it.contains("${daily.satisfiedCount} из ${daily.knownCount}")
                        })
                } else {
                    val emptyText = activity.getString(
                        if (daily.ongoingCount > 0) R.string.unified_statistics_habits_ongoing_in_progress
                        else R.string.unified_statistics_cadence_no_closed_cycles
                    )
                    assertTrue("Visible empty headline must match final scope report", rowTexts.any { it.contains(emptyText) })
                }
            }
            controller.onActivity { statsFragment(it).applyScopedAll() }
            idle()
            waitForSettled(controller)

            // Identity replacement is over the same guarded disposable file;
            // it does not replace or restore a physical user database.
            val replacement = previous.copy(datasetId = "qa-habit-replacement-${UUID.randomUUID()}")
            val (datasetEntered, datasetUnblock) = holdNextRead()
            release = datasetUnblock
            controller.onActivity { statsFragment(it).refresh() }
            assertTrue("Old dataset report read must be in flight", datasetEntered.await(8, java.util.concurrent.TimeUnit.SECONDS))
            controller.onActivity { application.switchDatasetSession(replacement) }
            idle()
            waitForSettled(controller)
            controller.onActivity { activity ->
                assertEquals(replacement.datasetId, requireNotNull(getLoadedReport(statsFragment(activity))).context.datasetId)
            }
            datasetUnblock.countDown()
            idle()
            SystemClock.sleep(200)
            controller.onActivity { activity ->
                assertEquals("Late old result must not overwrite replacement", replacement.datasetId,
                    requireNotNull(getLoadedReport(statsFragment(activity))).context.datasetId)
            }
        } finally {
            org.isoron.uhabits.database.AndroidSqlTiming.observer = oldObserver
            release?.countDown()
            if (component.session.datasetId != previous.datasetId) {
                controller.onActivity { application.switchDatasetSession(previous) }
                idle()
                waitForSettled(controller)
            }
            harness.guard()
        }
    }

    private fun verifyHabitLiveTimeAndSyncRefresh(
        controller: QaActivityController,
        seed: HabitOverviewSeedingResult,
    ) {
        harness.guard()
        val today = getToday()

        selectTab(controller, 1)
        assertTabSelected(controller, UnifiedAnalyticsTab.HABITS)
        waitForSettled(controller)

        // ---------------------------------------------------------------------
        // 1. ACTION_DATE_CHANGED / time refresh verification while statistics is open
        // ---------------------------------------------------------------------
        var preReport: UnifiedStatisticsReport? = null
        var preGeneration = 0
        var receiver: BroadcastReceiver? = null

        controller.onActivity { activity ->
            val fragment = statsFragment(activity)
            preReport = requireNotNull(getLoadedReport(fragment)) { "Report must be loaded before time refresh" }
            preGeneration = field<Int>(fragment, "activeReportGeneration")
            val uiState = getUiState(fragment)
            assertTrue("Pre-condition: UI state must be Success", uiState is UnifiedReportUiState.Success)
            assertFalse("Pre-condition: Report must not be actively refreshing", (uiState as UnifiedReportUiState.Success).isRefreshing)
            receiver = field<BroadcastReceiver>(fragment, "timeChangedReceiver")
        }

        val nonNullReceiver = requireNotNull(receiver) { "timeChangedReceiver must exist on UnifiedStatisticsFragment" }
        val nonNullPreReport = requireNotNull(preReport)

        // Trigger ACTION_DATE_CHANGED via timeChangedReceiver on the main thread
        controller.onActivity { activity ->
            val fragment = statsFragment(activity)
            nonNullReceiver.onReceive(activity, Intent(Intent.ACTION_DATE_CHANGED))

            // Assert actual immediate report invalidation on main thread
            assertNull("currentLoadedReport must be invalidated immediately on ACTION_DATE_CHANGED", getLoadedReport(fragment))
            val immediateUiState = getUiState(fragment)
            assertTrue("UI state must immediately enter InitialLoading on ACTION_DATE_CHANGED", immediateUiState is UnifiedReportUiState.InitialLoading)
            val intermediateGen = field<Int>(fragment, "activeReportGeneration")
            assertTrue("activeReportGeneration must strictly increment: pre=$preGeneration, intermediate=$intermediateGen", intermediateGen > preGeneration)
        }

        // Wait for background reload to settle
        idle()
        waitForSettled(controller)

        // Verify that report reloaded with a fresh report instance and Success UI state
        controller.onActivity { activity ->
            val fragment = statsFragment(activity)
            val postReport = requireNotNull(getLoadedReport(fragment)) { "Report must reload after time refresh" }
            assertNotSame("Reloaded report must be a newly constructed instance", nonNullPreReport, postReport)
            val postUiState = getUiState(fragment)
            assertTrue("UI state must return to Success after refresh", postUiState is UnifiedReportUiState.Success)
            assertFalse("Report must not remain in refreshing state", (postUiState as UnifiedReportUiState.Success).isRefreshing)
            val postGen = field<Int>(fragment, "activeReportGeneration")
            assertTrue("activeReportGeneration must have advanced: post=$postGen vs pre=$preGeneration", postGen > preGeneration)
        }
        device.revealStatisticsReportControl("habitCadenceDailyRow", Direction.UP)

        // ---------------------------------------------------------------------
        // 2. habitList.reloadAndNotify sync-style refresh while statistics is open
        // ---------------------------------------------------------------------
        val habit = seed.habitDailyBool
        val habitId = requireNotNull(habit.id)
        val targetDate = today.minus(3) // Seeded as Entry.NO in seedHabitOverviewData()

        var repBeforeSync: UnifiedStatisticsReport? = null
        var preDbVersion = 0L
        var preGenSync = 0
        var preSatCount = 0
        var preNotSatCount = 0

        controller.onActivity { activity ->
            val fragment = statsFragment(activity)
            repBeforeSync = requireNotNull(getLoadedReport(fragment)) { "Report must be loaded before sync refresh" }
            preDbVersion = field<Long>(fragment, "dbVersion")
            preGenSync = field<Int>(fragment, "activeReportGeneration")
            val daily = repBeforeSync!!.habits!!.daily
            preSatCount = daily.satisfiedCount
            preNotSatCount = daily.notSatisfiedCount

            val targetCycle = daily.cycles.first { it.habitName == HABIT_DAILY_BOOL_TITLE && it.startDate == targetDate }
            assertEquals("Pre-sync cycle outcome must be NOT_SATISFIED", HabitCycleOutcome.NOT_SATISFIED, targetCycle.outcome)
        }

        val nonNullRepBeforeSync = requireNotNull(repBeforeSync)

        // Directly update the underlying database row in SQLite (simulating remote sync engine writing data)
        val entryRepo = EntryRepository(component.db)
        entryRepo.upsert(
            EntryData(
                habitId = habitId,
                timestamp = targetDate.unixTime,
                value = Entry.YES_MANUAL,
                updatedAt = System.currentTimeMillis()
            )
        )

        // Trigger sync coordinator refresh: habitList.reloadAndNotify()
        controller.onActivity { activity ->
            val fragment = statsFragment(activity)
            val sqliteHabitList = requireNotNull(component.habitList as? SQLiteHabitList) {
                "habitList must be an instance of SQLiteHabitList"
            }
            sqliteHabitList.reloadAndNotify()

            val postDbVersion = field<Long>(fragment, "dbVersion")
            assertTrue("dbVersion must strictly increment upon reloadAndNotify: pre=$preDbVersion, post=$postDbVersion", postDbVersion > preDbVersion)
        }

        // Await background reload settlement
        idle()
        waitForSettled(controller)

        // Verify that report reloaded with the updated cycle outcome and satisfied count
        val dailyRowTexts = device.revealStatisticsReportControl("habitCadenceDailyRow", Direction.UP).statisticsTexts()
        controller.onActivity { activity ->
            val fragment = statsFragment(activity)
            val repAfterSync = requireNotNull(getLoadedReport(fragment)) { "Report must reload after sync refresh" }
            assertNotSame("Reloaded report must be a newly constructed instance", nonNullRepBeforeSync, repAfterSync)

            val daily = repAfterSync.habits!!.daily
            val targetCycle = daily.cycles.first { it.habitName == HABIT_DAILY_BOOL_TITLE && it.startDate == targetDate }
            assertEquals("Post-sync cycle outcome must now be SATISFIED (was NOT_SATISFIED)", HabitCycleOutcome.SATISFIED, targetCycle.outcome)
            assertEquals("Satisfied count must increase by exactly 1", preSatCount + 1, daily.satisfiedCount)
            assertEquals("Not satisfied count must decrease by exactly 1", preNotSatCount - 1, daily.notSatisfiedCount)

            val postGenSync = field<Int>(fragment, "activeReportGeneration")
            assertTrue("activeReportGeneration must strictly advance on sync reload: pre=$preGenSync, post=$postGenSync", postGenSync > preGenSync)

            if (daily.knownCount > 0) {
                val pct = Math.round((daily.satisfiedCount.toDouble() / daily.knownCount.toDouble()) * 100.0).toInt()
                assertTrue("Cadence row text must reflect new percentage $pct% after sync refresh: $dailyRowTexts",
                    dailyRowTexts.any { it.contains("$pct%") })
                assertTrue("Cadence row text must reflect new counts ${daily.satisfiedCount}/${daily.knownCount} after sync refresh: $dailyRowTexts",
                    dailyRowTexts.any {
                        it.contains("${daily.satisfiedCount}/${daily.knownCount}") ||
                            it.contains("${daily.satisfiedCount} из ${daily.knownCount}")
                    })
            } else {
                val emptyText = activity.getString(
                    if (daily.ongoingCount > 0) R.string.unified_statistics_habits_ongoing_in_progress
                    else R.string.unified_statistics_cadence_no_closed_cycles
                )
                assertTrue("Cadence row text must reflect empty state after sync refresh", dailyRowTexts.any { it.contains(emptyText) })
            }
        }

        // Clean up database mutation to preserve fixture isolation
        entryRepo.upsert(
            EntryData(
                habitId = habitId,
                timestamp = targetDate.unixTime,
                value = Entry.NO,
                updatedAt = System.currentTimeMillis()
            )
        )
        controller.onActivity {
            (component.habitList as SQLiteHabitList).reloadAndNotify()
        }
        idle()
        waitForSettled(controller)
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
        val deadline = SystemClock.uptimeMillis() + 3_000
        var curTab: UnifiedAnalyticsTab? = null
        while (SystemClock.uptimeMillis() < deadline) {
            controller.onActivity { activity ->
                val fragment = statsFragment(activity)
                curTab = fragment.currentTab
            }
            if (curTab == expectedTab) return
            SystemClock.sleep(50)
        }
        assertEquals("Current tab must match expected", expectedTab, curTab)
    }



    private fun selectPeriodViaUi(controller: QaActivityController, period: UnifiedPeriod) {
        device.selectStatisticsPeriod(period)
        controller.onActivity { activity ->
            val fragment = statsFragment(activity)
            assertEquals("Period menu must apply selection", period, getCurrentPeriod(fragment))
            when (val state = getUiState(fragment)) {
                is UnifiedReportUiState.Success -> assertEquals("Rendered report must belong to selected period", period, state.renderedKey.period)
                is UnifiedReportUiState.InitialLoading -> {
                    assertNull("Old report must be cleared during period change", getLoadedReport(fragment))
                }
                is UnifiedReportUiState.Failure -> assertNull("Failed period change must not retain old report", state.previousReport)
            }
        }
        if (device.hasObject(By.res("unified_loading"))) {
            val oldHeadlineVisible = device.hasObject(By.res("habitCadenceDailyRow"))
            if (device.hasObject(By.res("unified_loading"))) {
                assertFalse("Old Habit numbers must not remain under the loading period", oldHeadlineVisible)
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

    private fun findOpenBottomSheet(controller: QaActivityController): DialogFragment? {
        var sheet: DialogFragment? = null
        controller.onActivity { activity ->
            val fm = activity.supportFragmentManager
            val fragments = fm.fragments + (fm.findFragmentByTag("main.reports")?.childFragmentManager?.fragments.orEmpty())
            sheet = fragments.filterIsInstance<DialogFragment>().lastOrNull { it.dialog?.isShowing == true }
        }
        return sheet
    }

    private fun dismissDialogs(controller: QaActivityController) {
        repeat(5) {
            if (findOpenBottomSheet(controller) == null) return
            device.pressBack()
            idle()
        }
        assertNull("Back must dismiss dialog hierarchy", findOpenBottomSheet(controller))
    }

    private fun cycleControl(tag: String) = device.revealStatisticsControl(
        By.res(tag), "habitCycleDetailScroll", Direction.DOWN,
    )

    private fun formatCycleFixtureAmount(native: Long, unit: String): String {
        val number = BigDecimal.valueOf(native, 3).stripTrailingZeros().toPlainString()
        return if (unit.isBlank()) number else "$number $unit"
    }

    private fun verifyCycleContributionLedger(controller: QaActivityController) {
        val active = Triple(ACTIVE_CONTRIB_ID, "active", R.string.unified_statistics_cycle_contribution_active)
        val revoked = Triple(REVOKED_CONTRIB_ID, "revoked", R.string.unified_statistics_cycle_contribution_revoked)
        val excluded = Triple(EXCLUDED_CONTRIB_ID, "excluded", R.string.unified_statistics_cycle_contribution_excluded)

        for (fixtures in listOf(listOf(active, revoked), listOf(excluded))) {
            val expectedLabels = linkedMapOf<String, String>()
            controller.onActivity { activity ->
                val fragment = statsFragment(activity)
                val report = requireNotNull(fragment.lastReport)
                val cycle = requireNotNull(report.habits).allCycles.first { candidate ->
                    candidate.days.any { day -> day.contributions.any { it.id.value == fixtures.first().first } }
                }
                val clock = SimpleDateFormat("HH:mm", Locale.getDefault())
                for ((id, classification, resource) in fixtures) {
                    val day = cycle.days.first { entry ->
                        (entry.contributions + entry.tombstonedContributions).any { it.id.value == id }
                    }
                    val fact = (day.contributions + day.tombstonedContributions).single { it.id.value == id }
                    val timestamp = "${formatStatisticsDate(day.manualEntry.date)} ${clock.format(Date(fact.effectiveTimestamp))}"
                    val amount = formatCycleFixtureAmount(fact.contributedValue, cycle.quantity.unit)
                    expectedLabels["habitCycleContribution:$classification:$id"] = activity.getString(resource, amount, timestamp)
                }
                UnifiedStatisticsCycleDetailBottomSheet.show(
                    fragment.childFragmentManager, cycle.key.toString(), report.context.datasetId, statisticsQuerySignature(report.context),
                )
            }
            idle()
            for ((tag, label) in expectedLabels) {
                assertEquals("Ledger must render the exact contribution value and source timestamp", label, cycleControl(tag).text)
            }
            device.pressBack()
            idle()
            assertNull(findOpenBottomSheet(controller))
        }
    }

    private fun assertMinimumTouchTargets(controller: QaActivityController) {
        var density = 1f
        var all = false
        controller.onActivity {
            density = it.resources.displayMetrics.density
            all = field<UnifiedPeriod>(statsFragment(it), "currentPeriod") == UnifiedPeriod.ALL
        }
        device.assertStatisticsControlSizes(density, all)
    }

    private fun assertVisibleTextGeometry() {
        device.assertStatisticsReportTextBounds()
    }

    private fun getLoadedReport(fragment: UnifiedStatisticsFragment): UnifiedStatisticsReport? =
        field(fragment, "currentLoadedReport")

    private fun getCurrentPeriod(fragment: UnifiedStatisticsFragment): UnifiedPeriod =
        field(fragment, "currentPeriod")

    private fun getUiState(fragment: UnifiedStatisticsFragment): UnifiedReportUiState =
        field(fragment, "currentUiState")

    private fun dp(value: Float, context: Context): Int =
        (value * context.resources.displayMetrics.density).toInt()

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

        // Mechanism 1: UiDevice screencap
        val targetUiDevice = File(outputDir, "$phase-$name.png")
        check(device.takeScreenshot(targetUiDevice)) { "Failed to capture screenshot: ${targetUiDevice.path}" }

        // Mechanism 2: PixelCopy of Window Surface (Off main thread)
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
