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

import org.isoron.platform.time.DayOfWeek
import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.focus.FocusPrimaryTargetType
import org.isoron.uhabits.core.focus.FocusSession
import org.isoron.uhabits.core.focus.FocusSessionId
import org.isoron.uhabits.core.statistics.FocusDailyBucket
import org.isoron.uhabits.core.statistics.FocusSectionBreakdownItem
import org.isoron.uhabits.core.statistics.FocusSectionCategory
import org.isoron.uhabits.core.statistics.FocusStatisticsResult
import org.isoron.uhabits.core.statistics.FocusTargetBreakdownItem
import org.isoron.uhabits.core.statistics.StatisticsDataState
import org.isoron.uhabits.core.statistics.StatisticsQueryContext
import org.isoron.uhabits.core.statistics.StatisticsSectionScope
import org.isoron.uhabits.core.statistics.TaskDailyBucket
import org.isoron.uhabits.core.statistics.TaskDueCohortSummary
import org.isoron.uhabits.core.statistics.TaskNoDueCohortSummary
import org.isoron.uhabits.core.statistics.TaskStatisticsResult
import org.isoron.uhabits.core.statistics.UnifiedStatisticsReport
import org.isoron.uhabits.core.userTasks.TaskId
import org.isoron.uhabits.core.userTasks.TaskStatus
import org.isoron.uhabits.core.userTasks.UserTask
import org.isoron.uhabits.core.userTasks.due.TaskDue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedStatisticsStateTest {

    @Test
    fun formatMaximumDurationDoesNotOverflow() {
        assertFalse(UnifiedStatisticsStateUtils.formatDurationMs(Long.MAX_VALUE).contains("-"))
    }

    private val today = LocalDate(2026, 10, 26) // Monday

    @Test
    fun testCalculatePeriodRange_day() {
        val range = UnifiedStatisticsStateUtils.calculatePeriodRange(
            UnifiedPeriod.DAY, today, today, DayOfWeek.MONDAY
        )
        assertEquals(today, range.first)
        assertEquals(today, range.second)
    }

    @Test
    fun testCalculatePeriodRange_week_mondayFirst() {
        val range = UnifiedStatisticsStateUtils.calculatePeriodRange(
            UnifiedPeriod.WEEK, today, today, DayOfWeek.MONDAY
        )
        assertEquals(LocalDate(2026, 10, 26), range.first) // Monday
        assertEquals(LocalDate(2026, 11, 1), range.second) // Sunday
    }

    @Test
    fun testCalculatePeriodRange_week_sundayFirst() {
        val range = UnifiedStatisticsStateUtils.calculatePeriodRange(
            UnifiedPeriod.WEEK, today, today, DayOfWeek.SUNDAY
        )
        assertEquals(LocalDate(2026, 10, 25), range.first) // Sunday
        assertEquals(LocalDate(2026, 10, 31), range.second) // Saturday
    }

    @Test
    fun testCalculatePeriodRange_month_standardAndLeapYear() {
        val feb2026 = LocalDate(2026, 2, 15)
        val range2026 = UnifiedStatisticsStateUtils.calculatePeriodRange(
            UnifiedPeriod.MONTH, feb2026, today, DayOfWeek.MONDAY
        )
        assertEquals(LocalDate(2026, 2, 1), range2026.first)
        assertEquals(LocalDate(2026, 2, 28), range2026.second)

        val feb2024Leap = LocalDate(2024, 2, 10)
        val range2024 = UnifiedStatisticsStateUtils.calculatePeriodRange(
            UnifiedPeriod.MONTH, feb2024Leap, today, DayOfWeek.MONDAY
        )
        assertEquals(LocalDate(2024, 2, 1), range2024.first)
        assertEquals(LocalDate(2024, 2, 29), range2024.second)
    }

    @Test
    fun testCalculatePeriodRange_year() {
        val range = UnifiedStatisticsStateUtils.calculatePeriodRange(
            UnifiedPeriod.YEAR, today, today, DayOfWeek.MONDAY
        )
        assertEquals(LocalDate(2026, 1, 1), range.first)
        assertEquals(LocalDate(2026, 12, 31), range.second)
    }

    @Test
    fun testCalculatePeriodRange_all_withEarliestObservation() {
        val earliest = LocalDate(2024, 6, 1)
        val range = UnifiedStatisticsStateUtils.calculatePeriodRange(
            UnifiedPeriod.ALL, today, today, DayOfWeek.MONDAY, earliest
        )
        assertEquals(earliest, range.first)
        assertEquals(today, range.second)
    }

    @Test
    fun testCalculatePeriodRange_all_withoutEarliestObservation_defaultsToToday() {
        val range = UnifiedStatisticsStateUtils.calculatePeriodRange(
            UnifiedPeriod.ALL, today, today, DayOfWeek.MONDAY, null
        )
        assertEquals(today, range.first)
        assertEquals(today, range.second)
    }

    @Test
    fun testCalculatePeriodRange_all_futureEarliestClampedToToday() {
        val future = LocalDate(2027, 1, 1)
        val range = UnifiedStatisticsStateUtils.calculatePeriodRange(
            UnifiedPeriod.ALL, today, today, DayOfWeek.MONDAY, future
        )
        assertEquals(today, range.first)
        assertEquals(today, range.second)
    }

    @Test
    fun testNavigatePeriod_day() {
        val prev = UnifiedStatisticsStateUtils.navigatePeriod(UnifiedPeriod.DAY, today, -1, DayOfWeek.MONDAY)
        val next = UnifiedStatisticsStateUtils.navigatePeriod(UnifiedPeriod.DAY, today, 1, DayOfWeek.MONDAY)
        assertEquals(LocalDate(2026, 10, 25), prev)
        assertEquals(LocalDate(2026, 10, 27), next)
    }

    @Test
    fun testNavigatePeriod_week() {
        val prev = UnifiedStatisticsStateUtils.navigatePeriod(UnifiedPeriod.WEEK, today, -1, DayOfWeek.MONDAY)
        val next = UnifiedStatisticsStateUtils.navigatePeriod(UnifiedPeriod.WEEK, today, 1, DayOfWeek.MONDAY)
        assertEquals(LocalDate(2026, 10, 19), prev)
        assertEquals(LocalDate(2026, 11, 2), next)
    }

    @Test
    fun testNavigatePeriod_month_withMonthEndAndYearWrapping() {
        val jan31 = LocalDate(2026, 1, 31)
        val feb = UnifiedStatisticsStateUtils.navigatePeriod(UnifiedPeriod.MONTH, jan31, 1, DayOfWeek.MONDAY)
        assertEquals(LocalDate(2026, 2, 28), feb)

        val jan15 = LocalDate(2026, 1, 15)
        val prevYear = UnifiedStatisticsStateUtils.navigatePeriod(UnifiedPeriod.MONTH, jan15, -1, DayOfWeek.MONDAY)
        assertEquals(LocalDate(2025, 12, 15), prevYear)
    }

    @Test
    fun testNavigatePeriod_year_withLeapYearWrapping() {
        val leapDay = LocalDate(2024, 2, 29)
        val nextYear = UnifiedStatisticsStateUtils.navigatePeriod(UnifiedPeriod.YEAR, leapDay, 1, DayOfWeek.MONDAY)
        assertEquals(LocalDate(2025, 2, 28), nextYear)
    }

    @Test
    fun testNavigatePeriod_all_doesNotNavigate() {
        val prev = UnifiedStatisticsStateUtils.navigatePeriod(UnifiedPeriod.ALL, today, -1, DayOfWeek.MONDAY)
        val next = UnifiedStatisticsStateUtils.navigatePeriod(UnifiedPeriod.ALL, today, 1, DayOfWeek.MONDAY)
        assertEquals(today, prev)
        assertEquals(today, next)
    }

    @Test
    fun testIsLatestAllowedPeriod_and_clampAnchorDate() {
        // DAY
        assertTrue(UnifiedStatisticsStateUtils.isLatestAllowedPeriod(UnifiedPeriod.DAY, today, today, DayOfWeek.MONDAY))
        assertTrue(UnifiedStatisticsStateUtils.isLatestAllowedPeriod(UnifiedPeriod.DAY, today.plus(1), today, DayOfWeek.MONDAY))
        assertFalse(UnifiedStatisticsStateUtils.isLatestAllowedPeriod(UnifiedPeriod.DAY, today.minus(1), today, DayOfWeek.MONDAY))

        val clampedDay = UnifiedStatisticsStateUtils.clampAnchorDateToLatest(
            UnifiedPeriod.DAY, today.plus(5), today, DayOfWeek.MONDAY
        )
        assertEquals(today, clampedDay)

        // WEEK
        assertTrue(UnifiedStatisticsStateUtils.isLatestAllowedPeriod(UnifiedPeriod.WEEK, today, today, DayOfWeek.MONDAY))
        assertFalse(UnifiedStatisticsStateUtils.isLatestAllowedPeriod(UnifiedPeriod.WEEK, today.minus(7), today, DayOfWeek.MONDAY))

        // MONTH
        assertTrue(UnifiedStatisticsStateUtils.isLatestAllowedPeriod(UnifiedPeriod.MONTH, today, today, DayOfWeek.MONDAY))
        assertFalse(UnifiedStatisticsStateUtils.isLatestAllowedPeriod(UnifiedPeriod.MONTH, LocalDate(2026, 9, 15), today, DayOfWeek.MONDAY))

        // YEAR
        assertTrue(UnifiedStatisticsStateUtils.isLatestAllowedPeriod(UnifiedPeriod.YEAR, today, today, DayOfWeek.MONDAY))
        assertFalse(UnifiedStatisticsStateUtils.isLatestAllowedPeriod(UnifiedPeriod.YEAR, LocalDate(2025, 6, 1), today, DayOfWeek.MONDAY))

        // ALL
        assertTrue(UnifiedStatisticsStateUtils.isLatestAllowedPeriod(UnifiedPeriod.ALL, today, today, DayOfWeek.MONDAY))
    }

    @Test
    fun testStatisticsRevisionTuple_exactComparisonPreventsSumCollisions() {
        val tupleA = StatisticsRevisionTuple("dataset-1", 1L, 2L, 0L, 0L)
        val tupleB = StatisticsRevisionTuple("dataset-1", 2L, 1L, 0L, 0L)
        val tupleAClone = StatisticsRevisionTuple("dataset-1", 1L, 2L, 0L, 0L)

        // Summing revisions: 1 + 2 == 2 + 1, but tuple comparison MUST distinguish them
        assertTrue(tupleA.isMatching(tupleAClone))
        assertFalse(tupleA.isMatching(tupleB))

        val tupleDiffDataset = StatisticsRevisionTuple("dataset-2", 1L, 2L, 0L, 0L)
        assertFalse(tupleA.isMatching(tupleDiffDataset))
    }

    @Test
    fun testUnifiedScope_conversions() {
        val scopeAll = UnifiedScope.All
        assertEquals(StatisticsSectionScope.All, scopeAll.toStatisticsSectionScope())
        assertEquals(scopeAll, UnifiedScope.fromStatisticsSectionScope(StatisticsSectionScope.All))

        val scopeUnassigned = UnifiedScope.Unassigned
        assertEquals(StatisticsSectionScope.Unassigned, scopeUnassigned.toStatisticsSectionScope())
        assertEquals(scopeUnassigned, UnifiedScope.fromStatisticsSectionScope(StatisticsSectionScope.Unassigned))

        val containerId = ContainerId("work-123")
        val scopeContainerSubtree = UnifiedScope.Container(containerId, includeDescendants = true)
        val coreContainerSubtree = scopeContainerSubtree.toStatisticsSectionScope()
        assertTrue(coreContainerSubtree is StatisticsSectionScope.Container)
        assertEquals(containerId, (coreContainerSubtree as StatisticsSectionScope.Container).id)
        assertTrue(coreContainerSubtree.includeDescendants)
        assertEquals(scopeContainerSubtree, UnifiedScope.fromStatisticsSectionScope(coreContainerSubtree))

        val scopeContainerHere = UnifiedScope.Container(containerId, includeDescendants = false)
        val coreContainerHere = scopeContainerHere.toStatisticsSectionScope() as StatisticsSectionScope.Container
        assertFalse(coreContainerHere.includeDescendants)
        assertEquals(scopeContainerHere, UnifiedScope.fromStatisticsSectionScope(coreContainerHere))
    }

    @Test
    fun testFormatDurationMs() {
        assertEquals("0 min", UnifiedStatisticsStateUtils.formatDurationMs(0L, isRussian = false))
        assertEquals("0 мин", UnifiedStatisticsStateUtils.formatDurationMs(0L, isRussian = true))

        assertEquals("45 min", UnifiedStatisticsStateUtils.formatDurationMs(45 * 60_000L, isRussian = false))
        assertEquals("45 мин", UnifiedStatisticsStateUtils.formatDurationMs(45 * 60_000L, isRussian = true))

        assertEquals("2 h", UnifiedStatisticsStateUtils.formatDurationMs(120 * 60_000L, isRussian = false))
        assertEquals("2 ч", UnifiedStatisticsStateUtils.formatDurationMs(120 * 60_000L, isRussian = true))

        assertEquals("2 h 15 min", UnifiedStatisticsStateUtils.formatDurationMs(135 * 60_000L, isRussian = false))
        assertEquals("2 ч 15 мин", UnifiedStatisticsStateUtils.formatDurationMs(135 * 60_000L, isRussian = true))
    }

    private fun createTask(id: String, status: TaskStatus = TaskStatus.COMPLETED): UserTask {
        return UserTask(
            id = TaskId(id),
            title = "Task $id",
            status = status,
            createdAt = 1000L,
            updatedAt = 1000L,
        )
    }

    private fun createFocusSession(id: String, durationMs: Long, endedAt: Long = 1_000_000L): FocusSession {
        return FocusSession(
            id = FocusSessionId(id),
            startedAt = endedAt - durationMs,
            endedAt = endedAt,
            durationMs = durationMs,
            source = org.isoron.uhabits.core.focus.FocusSessionSource.MANUAL,
            createdAt = endedAt,
            updatedAt = endedAt,
        )
    }

    @Test
    fun testDrilldownSelectionResolver_tasks() {
        val task1 = createTask("t1", TaskStatus.COMPLETED)
        val task2 = createTask("t2", TaskStatus.COMPLETED)
        val taskDueCompleted = createTask("t3_due_c", TaskStatus.COMPLETED)
        val taskDueOverdue = createTask("t3_due_o", TaskStatus.PENDING)
        val taskDuePending = createTask("t3_due_p", TaskStatus.PENDING)
        val taskNoDueCompleted = createTask("t4_nodue_c", TaskStatus.COMPLETED)
        val taskNoDuePending = createTask("t4_nodue_p", TaskStatus.PENDING)

        val date1 = LocalDate(2026, 10, 20)
        val date2 = LocalDate(2026, 10, 21)

        val taskResult = TaskStatisticsResult(
            completedCount = 2,
            completedTasks = listOf(task1, task2),
            dailyCompletedBuckets = listOf(
                TaskDailyBucket(date = date1, count = 1, tasks = listOf(task1)),
                TaskDailyBucket(date = date2, count = 1, tasks = listOf(task2)),
            ),
            createdCount = 2,
            createdTasks = listOf(task1, task2),
            dailyCreatedBuckets = emptyList(),
            dueSummary = TaskDueCohortSummary(
                totalCount = 3,
                completedCount = 1,
                pendingOverdueNowCount = 1,
                pendingNotOverdueNowCount = 1,
                tasks = listOf(taskDueCompleted, taskDueOverdue, taskDuePending),
                completedTasks = listOf(taskDueCompleted),
                pendingOverdueNowTasks = listOf(taskDueOverdue),
                pendingNotOverdueNowTasks = listOf(taskDuePending),
            ),
            dailyDueBuckets = emptyList(),
            noDueSummary = TaskNoDueCohortSummary(
                totalCount = 2,
                completedCount = 1,
                pendingCount = 1,
                tasks = listOf(taskNoDueCompleted, taskNoDuePending),
                completedTasks = listOf(taskNoDueCompleted),
                pendingTasks = listOf(taskNoDuePending),
            ),
            dailyNoDueBuckets = emptyList(),
            dataState = StatisticsDataState.COMPLETE,
        )

        val queryContext = StatisticsQueryContext(
            from = date1,
            to = date2,
            timezoneId = "UTC",
            firstWeekday = DayOfWeek.MONDAY,
            habitHourOffset = 0,
            habitMinuteOffset = 0,
            datasetId = "test",
            revision = 1L,
            now = 2000L,
            sectionScope = StatisticsSectionScope.All,
        )

        val report = UnifiedStatisticsReport(
            context = queryContext,
            habits = null,
            tasks = taskResult,
            focus = null,
            readIssues = emptyList(),
        )

        // Completed day
        val day1Tasks = DrilldownSelectionResolver.resolveTasks(report, TasksDrilldownDescriptor.CompletedDay(date1))
        assertEquals(listOf(task1), day1Tasks)

        // Completed range
        val rangeTasks = DrilldownSelectionResolver.resolveTasks(
            report, TasksDrilldownDescriptor.CompletedRange(date1, date2, "Range")
        )
        assertEquals(listOf(task1, task2), rangeTasks)

        // Due cohort
        val dueAll = DrilldownSelectionResolver.resolveTasks(
            report, TasksDrilldownDescriptor.DueCohort(TasksDrilldownDescriptor.DueSegmentFilter.ALL_DUE)
        )
        assertEquals(3, dueAll.size)

        val dueCompleted = DrilldownSelectionResolver.resolveTasks(
            report, TasksDrilldownDescriptor.DueCohort(TasksDrilldownDescriptor.DueSegmentFilter.COMPLETED)
        )
        assertEquals(listOf(taskDueCompleted), dueCompleted)

        val dueOverdue = DrilldownSelectionResolver.resolveTasks(
            report, TasksDrilldownDescriptor.DueCohort(TasksDrilldownDescriptor.DueSegmentFilter.OVERDUE_NOW)
        )
        assertEquals(listOf(taskDueOverdue), dueOverdue)

        val duePending = DrilldownSelectionResolver.resolveTasks(
            report, TasksDrilldownDescriptor.DueCohort(TasksDrilldownDescriptor.DueSegmentFilter.PENDING_NOT_OVERDUE)
        )
        assertEquals(listOf(taskDuePending), duePending)

        // NoDue cohort
        val noDueAll = DrilldownSelectionResolver.resolveTasks(
            report, TasksDrilldownDescriptor.NoDueCohort(TasksDrilldownDescriptor.NoDueSegmentFilter.ALL_NO_DUE)
        )
        assertEquals(2, noDueAll.size)

        val noDueCompleted = DrilldownSelectionResolver.resolveTasks(
            report, TasksDrilldownDescriptor.NoDueCohort(TasksDrilldownDescriptor.NoDueSegmentFilter.COMPLETED)
        )
        assertEquals(listOf(taskNoDueCompleted), noDueCompleted)

        val noDuePending = DrilldownSelectionResolver.resolveTasks(
            report, TasksDrilldownDescriptor.NoDueCohort(TasksDrilldownDescriptor.NoDueSegmentFilter.PENDING)
        )
        assertEquals(listOf(taskNoDuePending), noDuePending)
    }

    @Test
    fun testDrilldownSelectionResolver_focus() {
        val session1 = createFocusSession("s1", 60_000L)
        val session2 = createFocusSession("s2", 120_000L)
        val date1 = LocalDate(2026, 10, 20)
        val date2 = LocalDate(2026, 10, 21)

        val containerId = ContainerId("work")
        val focusResult = FocusStatisticsResult(
            totalDurationMs = 180_000L,
            sessionCount = 2,
            averageSessionDurationMs = 90_000L,
            sessions = listOf(session1, session2),
            dailyBuckets = listOf(
                FocusDailyBucket(date = date1, durationMs = 60_000L, sessionCount = 1, sessions = listOf(session1)),
                FocusDailyBucket(date = date2, durationMs = 120_000L, sessionCount = 1, sessions = listOf(session2)),
            ),
            sectionBreakdowns = listOf(
                FocusSectionBreakdownItem(
                    category = FocusSectionCategory.ROOT_CONTAINER,
                    containerId = containerId,
                    name = "Work",
                    durationMs = 180_000L,
                    sessionCount = 2,
                    sessions = listOf(session1, session2),
                )
            ),
            targetBreakdowns = listOf(
                FocusTargetBreakdownItem(
                    primaryTargetType = FocusPrimaryTargetType.TASK,
                    targetId = "t1",
                    name = "Report",
                    durationMs = 60_000L,
                    sessionCount = 1,
                    sessions = listOf(session1),
                )
            ),
            targetTypeSummaries = emptyList(),
            dataState = StatisticsDataState.COMPLETE,
        )

        val queryContext = StatisticsQueryContext(
            from = date1,
            to = date2,
            timezoneId = "UTC",
            firstWeekday = DayOfWeek.MONDAY,
            habitHourOffset = 0,
            habitMinuteOffset = 0,
            datasetId = "test",
            revision = 1L,
            now = 2000L,
            sectionScope = StatisticsSectionScope.All,
        )

        val report = UnifiedStatisticsReport(
            context = queryContext,
            habits = null,
            tasks = null,
            focus = focusResult,
            readIssues = emptyList(),
        )

        // Day sessions
        val day1Sessions = DrilldownSelectionResolver.resolveFocus(
            report, FocusDrilldownDescriptor.DaySessions(date1)
        )
        assertEquals(listOf(session1), day1Sessions)

        // Sessions range
        val rangeSessions = DrilldownSelectionResolver.resolveFocus(
            report, FocusDrilldownDescriptor.SessionsRange(date1, date2, "Range")
        )
        assertEquals(listOf(session1, session2), rangeSessions)

        // Section branch
        val branchSessions = DrilldownSelectionResolver.resolveFocus(
            report, FocusDrilldownDescriptor.SectionBranch(FocusSectionCategory.ROOT_CONTAINER, containerId)
        )
        assertEquals(listOf(session1, session2), branchSessions)

        // Target object
        val targetSessions = DrilldownSelectionResolver.resolveFocus(
            report, FocusDrilldownDescriptor.TargetObject(FocusPrimaryTargetType.TASK, "t1")
        )
        assertEquals(listOf(session1), targetSessions)
    }

    @Test
    fun testDiscreteChartBucketer_tasks() {
        val date1 = LocalDate(2026, 10, 19)
        val date2 = LocalDate(2026, 10, 20)
        val taskResult = TaskStatisticsResult(
            completedCount = 5,
            completedTasks = emptyList(),
            dailyCompletedBuckets = listOf(
                TaskDailyBucket(date = date1, count = 2, tasks = emptyList()),
                TaskDailyBucket(date = date2, count = 3, tasks = emptyList()),
            ),
            createdCount = 0,
            createdTasks = emptyList(),
            dailyCreatedBuckets = emptyList(),
            dueSummary = TaskDueCohortSummary(0, 0, 0, 0),
            dailyDueBuckets = emptyList(),
            noDueSummary = TaskNoDueCohortSummary(0, 0, 0),
            dailyNoDueBuckets = emptyList(),
            dataState = StatisticsDataState.COMPLETE,
        )

        val columns = DiscreteChartBucketer.buildTaskColumns(
            period = UnifiedPeriod.WEEK,
            result = taskResult,
            today = date2,
            isRussian = true,
        )

        assertEquals(2, columns.size)
        assertEquals("2", columns[0].formattedValue)
        assertEquals("3", columns[1].formattedValue)
        assertFalse(columns[0].isToday)
        assertTrue(columns[1].isToday)
    }

    @Test
    fun testDiscreteChartBucketer_yearAggregates12Months() {
        // Generate daily buckets across 365 days of 2026
        val dailyBuckets = (1..365).map { dayOfYear ->
            // Simple mapping: 12 months with 1 completion per day
            val month = ((dayOfYear - 1) / 31).coerceIn(0, 11) + 1
            val day = ((dayOfYear - 1) % 28) + 1
            TaskDailyBucket(date = LocalDate(2026, month, day), count = 1)
        }

        val taskResult = TaskStatisticsResult(
            completedCount = 365,
            completedTasks = emptyList(),
            dailyCompletedBuckets = dailyBuckets,
            createdCount = 0,
            createdTasks = emptyList(),
            dailyCreatedBuckets = emptyList(),
            dueSummary = TaskDueCohortSummary(0, 0, 0, 0),
            dailyDueBuckets = emptyList(),
            noDueSummary = TaskNoDueCohortSummary(0, 0, 0),
            dailyNoDueBuckets = emptyList(),
            dataState = StatisticsDataState.COMPLETE,
        )

        val yearColumns = DiscreteChartBucketer.buildTaskColumns(
            period = UnifiedPeriod.YEAR,
            result = taskResult,
            today = LocalDate(2026, 10, 26),
            isRussian = false,
        )

        assertEquals(12, yearColumns.size)
        assertEquals("Jan", yearColumns[0].label)
        assertEquals("Dec", yearColumns[11].label)
        // Sum of all month buckets equals total completedCount
        assertEquals(365L, yearColumns.sumOf { it.value })
    }

    @Test
    fun testStatisticsEntityNameSnapshot_resolvesNamesAndFallbacks() {
        val snapshot = StatisticsEntityNameSnapshot(
            taskTitles = mapOf("task-1" to "Buy milk"),
            habitNames = mapOf("habit-1" to "Exercise"),
            containerNames = mapOf("c-1" to "Personal"),
        )

        assertEquals("Buy milk", snapshot.resolveTaskName("task-1"))
        assertEquals("Deleted task (deleted-)", snapshot.resolveTaskName("deleted-task-id", isRussian = false))
        assertEquals("Удалённая задача (deleted-)", snapshot.resolveTaskName("deleted-task-id", isRussian = true))
        assertEquals("No task", snapshot.resolveTaskName(null, isRussian = false))
        assertEquals("Без задачи", snapshot.resolveTaskName(null, isRussian = true))

        assertEquals("Exercise", snapshot.resolveHabitName("habit-1"))
        assertEquals("Deleted habit (deleted-)", snapshot.resolveHabitName("deleted-habit-uuid", isRussian = false))
        assertEquals("Удалённая привычка (deleted-)", snapshot.resolveHabitName("deleted-habit-uuid", isRussian = true))

        assertEquals("Personal", snapshot.resolveContainerName("c-1"))
        assertEquals("Deleted section (deleted-)", snapshot.resolveContainerName("deleted-c-id", isRussian = false))
        assertEquals("Удалённый раздел (deleted-)", snapshot.resolveContainerName("deleted-c-id", isRussian = true))
    }
}
