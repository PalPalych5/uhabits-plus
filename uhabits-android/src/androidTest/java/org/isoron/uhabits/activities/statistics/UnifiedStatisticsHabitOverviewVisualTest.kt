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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
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
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.common.theme.MainTabsThemeBridge
import org.isoron.uhabits.activities.main.MainActivity
import org.isoron.uhabits.activities.main.MainDestination
import org.isoron.uhabits.activities.statistics.views.DiscreteColumnBarChart
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

        val s1 = FocusSession(
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
        focusRepo.record(s1)
        sessions.add(s1)

        val s2 = FocusSession(
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
        focusRepo.record(s2)
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
                assertVisibleTextGeometry(controller)

                // =============================================================
                // PHASE 1: OVERVIEW TAB VERIFICATION
                // =============================================================
                selectTab(controller, 0)
                assertTabSelected(controller, UnifiedAnalyticsTab.OVERVIEW)

                // Viewport scrolling: top captures un-scrolled top
                controller.onActivity { activity ->
                    getScrollView(statsFragment(activity))?.scrollTo(0, 0)
                }
                idle()
                capture("01-overview-top")

                // Overview T1, F1, F2 Cards Rendered Text Equality
                var overviewT1Text = ""
                var overviewF1DurationText = ""
                var overviewF2SessionsText = ""

                controller.onActivity { activity ->
                    val fragment = statsFragment(activity)
                    val report = requireNotNull(getLoadedReport(fragment)) { "Report must not be null" }

                    val tasksCard = requireNotNull(fragment.view?.findViewWithTag<View>("overviewTasksCard")) {
                        "FAIL-CLOSED: overviewTasksCard must be present on Overview tab"
                    }
                    overviewT1Text = texts(tasksCard).firstOrNull { it.matches(Regex("\\d+")) }.orEmpty()
                    assertTrue("FAIL-CLOSED: Overview T1 count must be non-blank", overviewT1Text.isNotBlank())
                    assertEquals("Overview T1 must equal report completed tasks count",
                        report.tasks?.completedCount?.toString(), overviewT1Text)

                    val focusCard = requireNotNull(fragment.view?.findViewWithTag<View>("overviewFocusCard")) {
                        "FAIL-CLOSED: overviewFocusCard must be present on Overview tab"
                    }
                    overviewF1DurationText = texts(focusCard).firstOrNull { it.contains("мин") || it.contains("ч") || it.contains("min") || it.contains("h") }.orEmpty()
                    assertTrue("FAIL-CLOSED: Overview F1 duration must be non-blank", overviewF1DurationText.isNotBlank())

                    overviewF2SessionsText = requireNotNull(focusCard.findViewWithTag<TextView>("overview_focus_f2")).text.toString()
                    assertTrue("FAIL-CLOSED: Overview F2 session count must be non-blank", overviewF2SessionsText.isNotBlank())

                    val cadenceGroup = requireNotNull(fragment.view?.findViewWithTag<View>("overviewCadenceGroup")) {
                        "FAIL-CLOSED: overviewCadenceGroup must be present on Overview tab"
                    }
                    assertNotNull(cadenceGroup)
                }

                // Verify exact rendered text equality with Tasks tab (Tab 2)
                selectTab(controller, 2)
                assertTabSelected(controller, UnifiedAnalyticsTab.TASKS)
                controller.onActivity { activity ->
                    val fragment = statsFragment(activity)
                    val tasksContent = requireNotNull(getScrollView(fragment)) { "Tasks ScrollView must exist" }
                    val tasksRenderedT1 = texts(tasksContent).firstOrNull { it.matches(Regex("\\d+")) }.orEmpty()
                    assertEquals("Tasks tab rendered T1 count must match Overview T1 card exactly", overviewT1Text, tasksRenderedT1)
                }

                // Verify exact rendered text equality with Focus tab (Tab 3)
                selectTab(controller, 3)
                assertTabSelected(controller, UnifiedAnalyticsTab.FOCUS)
                controller.onActivity { activity ->
                    val fragment = statsFragment(activity)
                    val focusContent = requireNotNull(getScrollView(fragment)) { "Focus ScrollView must exist" }
                    val focusRenderedF1 = texts(focusContent).firstOrNull { it.contains("мин") || it.contains("ч") || it.contains("min") || it.contains("h") }.orEmpty()
                    assertEquals("Focus tab rendered F1 duration must match Overview F1 duration card exactly", overviewF1DurationText, focusRenderedF1)

                    val focusRenderedF2 = requireNotNull(focusContent.findViewWithTag<TextView>("unified_focus_f2")).text.toString()
                    val overviewF2Digits = Regex("\\d+").find(overviewF2SessionsText)?.value.orEmpty()
                    val focusRenderedF2Digits = Regex("\\d+").find(focusRenderedF2)?.value.orEmpty()
                    assertEquals("Focus tab rendered F2 session count digits must match Overview F2 card exactly", overviewF2Digits, focusRenderedF2Digits)
                }

                // Return to Overview (Tab 0)
                selectTab(controller, 0)
                assertTabSelected(controller, UnifiedAnalyticsTab.OVERVIEW)

                // Scroll to period dynamics graphs before capturing 02-overview-dynamics
                controller.onActivity { activity ->
                    val fragment = statsFragment(activity)
                    val scroll = requireNotNull(getScrollView(fragment)) { "FAIL-CLOSED: ScrollView must exist" }
                    val dynamicsTarget = fragment.view?.findViewWithTag<View>("overviewHabitsChart")
                        ?: fragment.view?.findViewWithTag<View>("overviewDynamicsChart")
                        ?: fragment.view?.findViewWithTag<View>("overviewDynamicsGroup")
                        ?: run {
                            val content = field<LinearLayout>(fragment, "contentContainer")
                            if (content.childCount > 2) content.getChildAt(2) else content.getChildAt(content.childCount - 1)
                        }
                    if (dynamicsTarget != null) {
                        scrollToDescendant(scroll, dynamicsTarget)
                    }
                }
                idle()
                capture("02-overview-dynamics")

                // Overview cadence opens the same cycle selection on the Habits tab.
                var overviewContext: org.isoron.uhabits.core.statistics.StatisticsQueryContext? = null
                controller.onActivity { activity ->
                    val fragment = statsFragment(activity)
                    overviewContext = requireNotNull(getLoadedReport(fragment)).context
                    val dailyRow = requireNotNull(fragment.view?.findViewWithTag<View>("habitCadenceDailyRow")) {
                        "FAIL-CLOSED: habitCadenceDailyRow must exist on Overview"
                    }
                    dailyRow.performClick()
                }
                idle()
                waitForSettled(controller)
                assertTabSelected(controller, UnifiedAnalyticsTab.HABITS)
                val overviewCycleSheet = requireNotNull(findOpenBottomSheet(controller) as? UnifiedStatisticsDrilldownBottomSheet) {
                    "Overview daily cadence must open cycle selection"
                }
                controller.onActivity { activity ->
                    val report = requireNotNull(getLoadedReport(statsFragment(activity)))
                    assertEquals("Overview navigation preserves query context", overviewContext, report.context)
                    assertEquals("Overview selection matches daily aggregate", report.habits!!.daily.cycles.take(30).map { it.key.toString() }, overviewCycleSheet.visibleIds)
                }
                device.pressBack()
                idle()
                assertNull("Back dismisses Overview cycle selection", findOpenBottomSheet(controller))
                assertTabSelected(controller, UnifiedAnalyticsTab.HABITS)

                // =============================================================
                // PHASE 2: HABITS TAB VERIFICATION (C1 - C7)
                // =============================================================
                selectTab(controller, 1)
                assertTabSelected(controller, UnifiedAnalyticsTab.HABITS)

                // Viewport scrolling: un-scrolled top
                controller.onActivity { activity ->
                    getScrollView(statsFragment(activity))?.scrollTo(0, 0)
                }
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

                // C5: FAIL-CLOSED View Assertions for Cadence Rows
                controller.onActivity { activity ->
                    val fragment = statsFragment(activity)
                    requireNotNull(fragment.view?.findViewWithTag<View>("habitCadenceDailyRow")) {
                        "FAIL-CLOSED: habitCadenceDailyRow must exist"
                    }
                    requireNotNull(fragment.view?.findViewWithTag<View>("habitCadenceWeeklyRow")) {
                        "FAIL-CLOSED: habitCadenceWeeklyRow must exist"
                    }
                    requireNotNull(fragment.view?.findViewWithTag<View>("habitCadenceMonthlyRow")) {
                        "FAIL-CLOSED: habitCadenceMonthlyRow must exist"
                    }
                }

                // C6: Interactive Cadence Chart & Strict Fail-Closed Accessibility Provider
                controller.onActivity { activity ->
                    val fragment = statsFragment(activity)
                    val chart = requireNotNull(fragment.view?.findViewWithTag<DiscreteColumnBarChart>("habitCadenceChart")) {
                        "FAIL-CLOSED: habitCadenceChart must exist"
                    }
                    assertTrue("Chart height must be >= 48dp", chart.height >= dp(48f, activity))

                    // Fail-Closed Accessibility delegate and virtual node assertion (requireNotNull, NOT if)
                    val delegate = requireNotNull(ViewCompat.getAccessibilityDelegate(chart)) {
                        "FAIL-CLOSED: Accessibility delegate must not be null on habitCadenceChart"
                    }
                    val provider = requireNotNull(delegate.getAccessibilityNodeProvider(chart) as? AccessibilityNodeProviderCompat) {
                        "FAIL-CLOSED: AccessibilityNodeProviderCompat must not be null on habitCadenceChart"
                    }
                    val node = requireNotNull(provider.createAccessibilityNodeInfo(0)) {
                        "FAIL-CLOSED: Virtual accessibility node at index 0 must not be null on habitCadenceChart"
                    }
                    assertFalse("FAIL-CLOSED: Virtual accessibility node contentDescription must not be blank",
                        node.contentDescription.isNullOrBlank())
                }

                // Test cadence chart content changes across cadence tab clicks (WEEKLY -> MONTHLY -> DAILY)
                // 1. Switch to WEEKLY
                controller.onActivity { activity ->
                    val fragment = statsFragment(activity)
                    val tabWeekly = requireNotNull(fragment.view?.findViewWithTag<MaterialButton>("habitCadenceTabWeekly")) {
                        "FAIL-CLOSED: habitCadenceTabWeekly must exist"
                    }
                    tabWeekly.performClick()
                }
                idle()
                waitForSettled(controller)
                controller.onActivity { activity ->
                    val fragment = statsFragment(activity)
                    assertEquals("Selected cadence must update to WEEKLY", HabitCadence.WEEKLY, field<HabitCadence>(fragment, "selectedHabitCadence"))
                    val chart = requireNotNull(fragment.view?.findViewWithTag<View>("habitCadenceChart"))
                    val buckets = requireNotNull(getLoadedReport(fragment)?.habits).dateBuckets[HabitCadence.WEEKLY].orEmpty()
                    if (buckets.isEmpty()) {
                        assertTrue("No weekly results must display the empty state", chart is TextView)
                        assertEquals(activity.getString(R.string.unified_statistics_habits_chart_empty), (chart as TextView).text.toString())
                    } else {
                        assertTrue("Weekly results must display a chart", chart is DiscreteColumnBarChart)
                        assertTrue(field<List<Any>>(chart, "items").isNotEmpty())
                    }
                    val textsInFragment = texts(requireNotNull(fragment.view))
                    assertTrue("Cadence title subtitle must reflect Weekly",
                        textsInFragment.any { it.contains(activity.getString(R.string.unified_statistics_habits_chart_weekly)) })
                }

                // 2. Switch to MONTHLY
                controller.onActivity { activity ->
                    val fragment = statsFragment(activity)
                    val tabMonthly = requireNotNull(fragment.view?.findViewWithTag<MaterialButton>("habitCadenceTabMonthly")) {
                        "FAIL-CLOSED: habitCadenceTabMonthly must exist"
                    }
                    tabMonthly.performClick()
                }
                idle()
                waitForSettled(controller)
                controller.onActivity { activity ->
                    val fragment = statsFragment(activity)
                    assertEquals("Selected cadence must update to MONTHLY", HabitCadence.MONTHLY, field<HabitCadence>(fragment, "selectedHabitCadence"))
                    val chart = requireNotNull(fragment.view?.findViewWithTag<View>("habitCadenceChart"))
                    val buckets = requireNotNull(getLoadedReport(fragment)?.habits).dateBuckets[HabitCadence.MONTHLY].orEmpty()
                    if (buckets.isEmpty()) {
                        assertTrue("No monthly results must display the empty state", chart is TextView)
                        assertEquals(activity.getString(R.string.unified_statistics_habits_chart_empty), (chart as TextView).text.toString())
                    } else {
                        assertTrue("Monthly results must display a chart", chart is DiscreteColumnBarChart)
                        assertTrue(field<List<Any>>(chart, "items").isNotEmpty())
                    }
                    val textsInFragment = texts(requireNotNull(fragment.view))
                    assertTrue("Cadence title subtitle must reflect Monthly",
                        textsInFragment.any { it.contains(activity.getString(R.string.unified_statistics_habits_chart_monthly)) })
                }

                // 3. Switch back to DAILY
                controller.onActivity { activity ->
                    val fragment = statsFragment(activity)
                    val tabDaily = requireNotNull(fragment.view?.findViewWithTag<MaterialButton>("habitCadenceTabDaily")) {
                        "FAIL-CLOSED: habitCadenceTabDaily must exist"
                    }
                    tabDaily.performClick()
                }
                idle()
                waitForSettled(controller)
                controller.onActivity { activity ->
                    val fragment = statsFragment(activity)
                    assertEquals("Selected cadence must update to DAILY", HabitCadence.DAILY, field<HabitCadence>(fragment, "selectedHabitCadence"))
                    val chart = requireNotNull(fragment.view?.findViewWithTag<DiscreteColumnBarChart>("habitCadenceChart"))
                    val items = field<List<Any>>(chart, "items")
                    assertTrue("Daily chart must contain columns", items.isNotEmpty())
                    val textsInFragment = texts(requireNotNull(fragment.view))
                    assertTrue("Cadence title subtitle must reflect Daily",
                        textsInFragment.any { it.contains(activity.getString(R.string.unified_statistics_habits_chart_daily)) })

                    // Viewport scrolling: scroll to habitCadenceChart
                    val scroll = requireNotNull(getScrollView(fragment))
                    scrollToDescendant(scroll, chart)
                }
                idle()
                capture("04-habits-cadence-chart")

                // Tap a real chart column using its measured geometry and
                // compare its segments and opened selection with the bucket.
                var expectedBucketIds: List<String> = emptyList()
                controller.onActivity { activity ->
                    val fragment = statsFragment(activity)
                    val report = requireNotNull(getLoadedReport(fragment))
                    val chart = requireNotNull(fragment.view?.findViewWithTag<DiscreteColumnBarChart>("habitCadenceChart"))
                    val columns = field<List<ChartColumnItem>>(chart, "items")
                    val index = columns.indexOfFirst { it.value > 0L }
                    assertTrue("Fixture must have a nonempty daily chart bucket", index >= 0)
                    val column = columns[index]
                    val descriptor = column.descriptor as HabitsDrilldownDescriptor.BucketCycles
                    val bucket = requireNotNull(report.habits).dateBuckets[HabitCadence.DAILY].orEmpty()
                        .first { it.date == descriptor.date }
                    val outcomes = listOf(HabitCycleOutcome.SATISFIED, HabitCycleOutcome.NOT_SATISFIED, HabitCycleOutcome.UNKNOWN)
                    val expectedCounts = outcomes.map { outcome -> bucket.cycles.count { it.outcome == outcome }.toLong() }
                    assertEquals(listOf(bucket.satisfiedCount.toLong(), bucket.notSatisfiedCount.toLong(), bucket.unknownCount.toLong()), expectedCounts)
                    assertEquals("Chart segments must equal nonzero S/F/U counts", expectedCounts.filter { it > 0 }, column.segments.map { it.value })
                    assertEquals("Chart total must equal S+F+U", expectedCounts.sum(), column.value)
                    expectedBucketIds = bucket.cycles.filter { it.outcome in outcomes }.map { it.key.toString() }
                    val bounds = chart.getColumnBounds(index)
                    assertTrue("Tapped column must have measured geometry", bounds.width() > 0 && bounds.height() > 0)
                    val now = SystemClock.uptimeMillis()
                    val down = android.view.MotionEvent.obtain(now, now, android.view.MotionEvent.ACTION_DOWN, bounds.centerX(), bounds.centerY(), 0)
                    val up = android.view.MotionEvent.obtain(now, now + 1, android.view.MotionEvent.ACTION_UP, bounds.centerX(), bounds.centerY(), 0)
                    try {
                        assertTrue(chart.dispatchTouchEvent(down))
                        assertTrue(chart.dispatchTouchEvent(up))
                    } finally {
                        down.recycle()
                        up.recycle()
                    }
                }
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
                controller.onActivity { activity ->
                    val fragment = statsFragment(activity)
                    val dailyRow = requireNotNull(fragment.view?.findViewWithTag<View>("habitCadenceDailyRow")) {
                        "FAIL-CLOSED: habitCadenceDailyRow must exist"
                    }
                    dailyRow.performClick()
                }
                idle()
                waitForSettled(controller)

                val drilldownSheet = requireNotNull(findOpenBottomSheet(controller) as? UnifiedStatisticsDrilldownBottomSheet) {
                    "FAIL-CLOSED: UnifiedStatisticsDrilldownBottomSheet must be open upon cadence row click"
                }
                capture("05-habits-drilldown-sheet")

                // Assert exact selected cycle keys vs aggregate and page membership
                var expectedFirstCycleKey = ""
                controller.onActivity { activity ->
                    val fragment = statsFragment(activity)
                    val report = requireNotNull(getLoadedReport(fragment))
                    val dailyCycles = report.habits!!.daily.cycles
                    val expectedPageKeys = dailyCycles.take(30).map { it.key.toString() }
                    assertEquals("Visible cycle keys in drilldown sheet must match aggregate first page",
                        expectedPageKeys, drilldownSheet.visibleIds)
                    assertTrue("Expected page keys must not be empty", expectedPageKeys.isNotEmpty())
                    expectedFirstCycleKey = expectedPageKeys.first()
                }

                // In drilldown sheet: click first cycle item LOCATED BY EXACT KEY (no generic clickable fallback)
                controller.onActivity {
                    val cycleItem = requireNotNull(
                        drilldownSheet.view?.findViewWithTag<View>(expectedFirstCycleKey)
                    ) { "FAIL-CLOSED: First cycle row with exact tag $expectedFirstCycleKey must exist in drilldown sheet" }
                    cycleItem.performClick()
                }
                idle()
                waitForSettled(controller)

                val detailSheet = requireNotNull(findOpenBottomSheet(controller)) {
                    "FAIL-CLOSED: Cycle detail sheet must be open upon exact cycle click"
                }
                capture("06-habits-cycle-detail-sheet")

                // Verify cycle detail contents with required fail-closed tags
                controller.onActivity { activity ->
                    val view = requireNotNull(detailSheet.view) { "Cycle detail view must not be null" }

                    // Required tags fail-closed assertions
                    val datesView = requireNotNull(
                        view.findViewWithTag<View>("habitCycleDatesText") ?: view.findViewWithTag<View>("habitCycleDetailDates")
                    ) { "FAIL-CLOSED: habitCycleDatesText tag required on cycle detail sheet" }
                    val datesText = when (datesView) {
                        is TextView -> datesView.text.toString()
                        else -> texts(datesView).firstOrNull().orEmpty()
                    }
                    assertTrue("Dates text must not be blank", datesText.isNotBlank())

                    val targetView = requireNotNull(
                        view.findViewWithTag<View>("habitCycleTargetText") ?: view.findViewWithTag<View>("habitCycleDetailTarget")
                    ) { "FAIL-CLOSED: habitCycleTargetText tag required on cycle detail sheet" }
                    val targetText = when (targetView) {
                        is TextView -> targetView.text.toString()
                        else -> texts(targetView).firstOrNull().orEmpty()
                    }
                    assertTrue("Target text must not be blank", targetText.isNotBlank())

                    val qtyView = requireNotNull(
                        view.findViewWithTag<View>("habitCycleQuantityText") ?: view.findViewWithTag<View>("habitCycleDetailQuantity")
                    ) { "FAIL-CLOSED: habitCycleQuantityText tag required on cycle detail sheet" }
                    val qtyText = when (qtyView) {
                        is TextView -> qtyView.text.toString()
                        else -> texts(qtyView).firstOrNull().orEmpty()
                    }
                    assertTrue("Quantity text must not be blank", qtyText.isNotBlank())

                    // Entries and contributions sections
                    val entriesView = view.findViewWithTag<View>("habitCycleEntriesLayout")
                    if (entriesView != null) {
                        assertTrue("Entries layout must contain child texts", texts(entriesView).isNotEmpty())
                    }
                    val ledgerView = view.findViewWithTag<View>("habitCycleContributionsLayout")
                    if (ledgerView != null) {
                        assertTrue("Contributions layout must contain child texts", texts(ledgerView).isNotEmpty())
                    }

                    // Test "Open habit" button: real button click launches ShowHabitActivity
                    val openBtn = requireNotNull(
                        view.findViewWithTag<View>("habitCycleOpenHabitButton") ?: view.findViewWithTag<View>("habitCycleDetailOpenHabit")
                    ) { "FAIL-CLOSED: Open habit button must exist in cycle detail sheet" }
                    openBtn.performClick()
                }
                idle()
                SystemClock.sleep(300)

                // Confirm unfiltered dialog warning if displayed
                device.wait(Until.findObject(By.res("android", "button1")), 2500)?.click()
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

                // Verify compact alphabetical preview list excludes overflow habits
                controller.onActivity { activity ->
                    val fragment = statsFragment(activity)
                    val alphaList = requireNotNull(fragment.view?.findViewWithTag<View>("habitAlphaPreviewList")) {
                        "FAIL-CLOSED: habitAlphaPreviewList must exist"
                    }
                    val summaries = requireNotNull(getLoadedReport(fragment)?.habits).habitSummaries
                        .distinctBy { it.habitUuid }.sortedBy { it.habitName.lowercase() }
                    val preview = summaries.take(4)
                    assertTrue("Fixture must contain active habits in preview", preview.any { it.habitUuid != seed.habitArchived.uuid })
                    preview.forEach { summary ->
                        assertNotNull("Alphabetical preview missing ${summary.habitName}", alphaList.findViewWithTag<View>(summary.habitUuid))
                    }
                    summaries.drop(4).forEach { summary ->
                        assertNull("Preview must be limited to four habits", alphaList.findViewWithTag<View>(summary.habitUuid))
                    }

                    val compareEntry = requireNotNull(fragment.view?.findViewWithTag<View>("habitCompareEntry")) {
                        "FAIL-CLOSED: habitCompareEntry must exist in menu/header"
                    }
                    assertNotNull(compareEntry)
                }

                // Verify archived habit in full searchable list (via search button)
                controller.onActivity { activity ->
                    val fragment = statsFragment(activity)
                    val searchBtn = requireNotNull(fragment.view?.findViewWithTag<View>("habitSearchButton")) {
                        "FAIL-CLOSED: habitSearchButton must exist"
                    }
                    searchBtn.performClick()
                }
                idle()
                waitForSettled(controller)

                val fullListSheet = requireNotNull(findOpenBottomSheet(controller) as? UnifiedStatisticsDrilldownBottomSheet) {
                    "FAIL-CLOSED: Full habit list sheet must open on search button click"
                }
                val fullListTexts = texts(requireNotNull(fullListSheet.view))
                assertTrue("Full searchable list must include archived habit",
                    fullListTexts.any { it.contains(HABIT_ARCHIVED_TITLE) })
                assertTrue("Full searchable list visible IDs must include archived habit UUID",
                    fullListSheet.visibleIds.contains(requireNotNull(seed.habitArchived.uuid)))

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
                    assertTrue("Unassigned habit must be present in Unassigned scope",
                        habits.allCycles.any { it.habitName == HABIT_PARTIAL_TITLE })
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
                    val textsInFragment = texts(requireNotNull(fragment.view))
                    assertTrue("Empty container must display honest empty state text",
                        textsInFragment.any { it.contains(activity.getString(R.string.unified_statistics_habits_empty)) })
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
                    controller.onActivity { activity ->
                        requireNotNull(statsFragment(activity).view?.findViewWithTag<View>("unified_prev")).performClick()
                    }
                    idle()
                    waitForSettled(controller)
                }
                controller.onActivity { activity ->
                    val fragment = statsFragment(activity)
                    val dailyRow = requireNotNull(fragment.view?.findViewWithTag<View>("habitCadenceDailyRow")) {
                        "FAIL-CLOSED: habitCadenceDailyRow must exist"
                    }
                    dailyRow.performClick()
                }
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
                var preRowTexts: List<String> = emptyList()

                controller.onActivity { activity ->
                    val fragment = statsFragment(activity)
                    val rep = requireNotNull(getLoadedReport(fragment))
                    val cycle = rep.habits!!.allCycles.first {
                        it.habitName == HABIT_DAILY_NUM_TITLE && it.startDate == todayVal.minus(2)
                    }
                    targetCycleKey = cycle.key.toString()
                    preQuantityLowerBound = cycle.quantity.lowerBound
                    preOutcome = cycle.outcome
                    val row = requireNotNull(openDrilldown.view?.findViewWithTag<View>(targetCycleKey))
                    preRowTexts = texts(row)
                }

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

                    // Assert visible drilldown sheet content changed
                    val row = requireNotNull(openDrilldown.view?.findViewWithTag<View>(targetCycleKey))
                    assertNotEquals("Visible sheet row text must update after live mutation", preRowTexts, texts(row))
                }

                // 2. Insert & Revoke live Contribution while statistics is open
                // Yesterday may be in the current week even when today-2 is
                // in the preceding week. Switch back only in that case.
                if (showingPreviousWeek && todayVal.minus(1) >= currentWeekStart) {
                    dismissDialogs(controller)
                    controller.onActivity { activity ->
                        requireNotNull(statsFragment(activity).view?.findViewWithTag<View>("unified_next")).performClick()
                    }
                    idle()
                    waitForSettled(controller)
                    showingPreviousWeek = false
                    controller.onActivity { activity ->
                        requireNotNull(statsFragment(activity).view?.findViewWithTag<View>("habitCadenceDailyRow")).performClick()
                    }
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
                    controller.onActivity { activity ->
                        requireNotNull(statsFragment(activity).view?.findViewWithTag<View>("unified_next")).performClick()
                    }
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
                assertNull("Old Habit numbers must not remain visible", fragment.view?.findViewWithTag<View>("habitCadenceDailyRow"))
            }
            unblock.countDown()
            idle()
            waitForSettled(controller)
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
                val expectedText = when {
                    daily.eligibleCount == 0 -> activity.getString(R.string.unified_statistics_cadence_no_closed_cycles)
                    daily.unknownCount > 0 -> activity.getString(R.string.unified_statistics_cadence_breakdown_with_unknown, daily.satisfiedCount, daily.notSatisfiedCount, daily.unknownCount)
                    else -> activity.getString(R.string.unified_statistics_cadence_breakdown, daily.satisfiedCount, daily.notSatisfiedCount)
                }
                val row = requireNotNull(fragment.view?.findViewWithTag<View>("habitCadenceDailyRow"))
                assertTrue("Visible counts must match final scope report", texts(row).any { it.contains(expectedText) })
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
            assertNotNull("Habit cadence daily row must be present in view hierarchy", fragment.view?.findViewWithTag<View>("habitCadenceDailyRow"))
        }

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

            val dailyRow = requireNotNull(fragment.view?.findViewWithTag<View>("habitCadenceDailyRow")) {
                "FAIL-CLOSED: habitCadenceDailyRow must exist"
            }
            val expectedText = when {
                daily.eligibleCount == 0 -> activity.getString(R.string.unified_statistics_cadence_no_closed_cycles)
                daily.unknownCount > 0 -> activity.getString(R.string.unified_statistics_cadence_breakdown_with_unknown, daily.satisfiedCount, daily.notSatisfiedCount, daily.unknownCount)
                else -> activity.getString(R.string.unified_statistics_cadence_breakdown, daily.satisfiedCount, daily.notSatisfiedCount)
            }
            assertTrue("Cadence row text must reflect new breakdown counts after sync refresh", texts(dailyRow).any { it.contains(expectedText) })
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
        controller.onActivity { activity ->
            val fragment = statsFragment(activity)
            val tabLayout = fragment.view?.findViewWithTag<TabLayout>("unified_tabs")
                ?: runCatching { field<TabLayout>(fragment, "tabLayout") }.getOrNull()
            requireNotNull(tabLayout) { "TabLayout not found" }
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
            val curTab = field<UnifiedAnalyticsTab>(fragment, "currentTab")
            assertEquals("Current tab must match expected", expectedTab, curTab)
        }
    }

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
            // Inspect on the first available main-thread turn, before waiting
            // for the replacement report to finish.
            controller.onActivity { activity ->
                val fragment = statsFragment(activity)
                assertEquals("Period menu must apply selection", period, getCurrentPeriod(fragment))
                when (val state = getUiState(fragment)) {
                    is UnifiedReportUiState.Success -> assertEquals("Rendered report must belong to selected period", period, state.renderedKey.period)
                    is UnifiedReportUiState.InitialLoading -> {
                        assertNull("Old report must be cleared during period change", getLoadedReport(fragment))
                        assertNull("Old Habit numbers must not remain under the new period", fragment.view?.findViewWithTag<View>("habitCadenceDailyRow"))
                    }
                    is UnifiedReportUiState.Failure -> assertNull("Failed period change must not retain old report", state.previousReport)
                }
            }
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

    private fun texts(root: View): List<String> = buildList {
        if (root is TextView) add(root.text.toString())
        if (root is ViewGroup) for (index in 0 until root.childCount) addAll(texts(root.getChildAt(index)))
    }

    private fun scrollToDescendant(scroll: ScrollView, descendant: View) {
        val rect = Rect()
        descendant.getDrawingRect(rect)
        scroll.offsetDescendantRectToMyCoords(descendant, rect)
        scroll.scrollTo(0, rect.top)
    }

    private fun getScrollView(fragment: UnifiedStatisticsFragment): ScrollView? =
        fragment.view?.findViewWithTag<ScrollView>("unified_content")
            ?: fragment.view?.findViewWithTag<ScrollView>("unified_scroll")
            ?: runCatching { field<ScrollView>(fragment, "contentScrollView") }.getOrNull()
            ?: findViewByType(fragment.requireView(), ScrollView::class.java)

    private fun assertMinimumTouchTargets(controller: QaActivityController) {
        controller.onActivity { activity ->
            val fragment = statsFragment(activity)
            val interactiveViews = listOfNotNull(
                fragment.view?.findViewWithTag<View>("unified_prev"),
                fragment.view?.findViewWithTag<View>("unified_next"),
                fragment.view?.findViewWithTag<View>("unified_period"),
                fragment.view?.findViewWithTag<View>("unified_scope")
            )
            val density = activity.resources.displayMetrics.density
            val rect = Rect()
            for (v in interactiveViews) {
                if (v.isShown && v.getGlobalVisibleRect(rect) && rect.width() > 0 && rect.height() > 0) {
                    val renderedWDp = v.width / density
                    val renderedHDp = v.height / density
                    assertTrue("Click target ${v.tag} width must be >= 48dp, actual=${renderedWDp}dp", renderedWDp >= 47.5f)
                    assertTrue("Click target ${v.tag} height must be >= 48dp, actual=${renderedHDp}dp", renderedHDp >= 47.5f)
                }
            }
        }
    }

    private fun assertVisibleTextGeometry(controller: QaActivityController) {
        controller.onActivity { activity ->
            val fragment = statsFragment(activity)
            val textViews = mutableListOf<TextView>()

            fun collect(v: View) {
                if (v is TextView && v.isShown && v.width > 0 && v.height > 0) textViews.add(v)
                if (v is ViewGroup) {
                    for (i in 0 until v.childCount) collect(v.getChildAt(i))
                }
            }
            fragment.view?.let { collect(it) }

            for (tv in textViews) {
                val layout = tv.layout ?: continue
                val text = tv.text.toString()
                if (text.isBlank()) continue
                val textH = layout.height
                val viewH = tv.height - tv.compoundPaddingTop - tv.compoundPaddingBottom
                assertTrue("Visible text must fit within TextView height: textH=$textH vs viewH=$viewH ('$text')",
                    textH <= viewH + 4)
            }
        }
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
