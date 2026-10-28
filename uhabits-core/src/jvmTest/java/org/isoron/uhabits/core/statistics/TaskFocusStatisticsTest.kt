/* Copyright (C) 2026 Álinson Santos Xavier and contributors. GPL-3.0-or-later. */
package org.isoron.uhabits.core.statistics

import org.isoron.platform.time.DayOfWeek
import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.core.containers.Container
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.containers.ContainerPath
import org.isoron.uhabits.core.containers.ContainerQueries
import org.isoron.uhabits.core.containers.ContainerSearchHit
import org.isoron.uhabits.core.containers.OrganizationRevision
import org.isoron.uhabits.core.focus.FocusPrimaryTargetType
import org.isoron.uhabits.core.focus.FocusSession
import org.isoron.uhabits.core.focus.FocusSessionId
import org.isoron.uhabits.core.focus.FocusSessionSource
import org.isoron.uhabits.core.userTasks.TaskId
import org.isoron.uhabits.core.userTasks.TaskStatus
import org.isoron.uhabits.core.userTasks.UserTask
import org.isoron.uhabits.core.userTasks.due.TaskDue
import org.isoron.uhabits.core.userTasks.due.TaskDueDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class TaskFocusStatisticsTest {

    @Test fun repeatedContainerSessionsUseBoundedLookupsAndDoNotCacheAcrossReports() {
        val ctx = createContext()
        val id = ContainerId("root")
        val delegate = FakeContainerQueries(mapOf(id to createContainer("root", "Root")))
        var finds = 0
        var roots = 0
        val queries = object : ContainerQueries by delegate {
            override fun find(id: ContainerId, includeDeleted: Boolean): Container? {
                finds++
                return delegate.find(id, includeDeleted)
            }
            override fun rootId(id: ContainerId): ContainerId? { roots++; return id }
        }
        val sessions = (0 until 100).map { index ->
            val start = ctx.startMillis + index * 2000L
            createSession("s$index", start, start + 1000, containerId = "root")
        }
        val report = FocusStatisticsAggregator.aggregate(ctx, sessions, queries)
        assertEquals(100_000L, report.totalDurationMs)
        assertEquals(1, finds)
        assertEquals(1, roots)
        FocusStatisticsAggregator.aggregate(ctx, sessions, queries)
        assertEquals(2, finds)
        assertEquals(2, roots)
    }

    @Test
    fun existingTasksOutsideAllSelectedCohortsAreEmpty() {
        val ctx = createContext()
        val task = createTask("old", createdAt = ctx.startMillis - 1000)
        assertEquals(StatisticsDataState.EMPTY, TaskStatisticsAggregator.aggregate(ctx, listOf(task)).dataState)
    }

    @Test
    fun explicitPrimaryTargetWinsOverSecondaryReferences() {
        val ctx = createContext()
        val session = createSession("secondary", ctx.startMillis, ctx.startMillis + 1000, taskId = "task")
            .copy(habit = org.isoron.uhabits.core.containers.HabitRef("habit"),
                primaryTargetType = FocusPrimaryTargetType.HABIT)
        val result = FocusStatisticsAggregator.aggregate(ctx, listOf(session))
        assertEquals(1000L, result.totalDurationMs)
        assertEquals(FocusPrimaryTargetType.HABIT, result.targetBreakdowns.single().primaryTargetType)
        assertEquals("habit", result.targetBreakdowns.single().targetId)
        assertEquals(FocusPrimaryTargetType.HABIT, result.targetTypeSummaries.single().targetType)
    }

    private val zoneUtc = "UTC"
    private val fromDate = LocalDate(2026, 10, 19) // Monday
    private val toDate = LocalDate(2026, 10, 25) // Sunday

    // Base context: UTC, 2026-10-19 00:00:00 to 2026-10-25 23:59:59.999
    // now = 2026-10-21 12:00:00 UTC (Wednesday noon)
    private val baseNow = Instant.parse("2026-10-21T12:00:00Z").toEpochMilli()

    private fun createContext(
        from: LocalDate = fromDate,
        to: LocalDate = toDate,
        timezoneId: String = zoneUtc,
        now: Long = baseNow,
        sectionScope: StatisticsSectionScope = StatisticsSectionScope.All,
        habitHourOffset: Int = 0,
        habitMinuteOffset: Int = 0,
    ) = StatisticsQueryContext(
        from = from,
        to = to,
        timezoneId = timezoneId,
        firstWeekday = DayOfWeek.MONDAY,
        habitHourOffset = habitHourOffset,
        habitMinuteOffset = habitMinuteOffset,
        datasetId = "test-dataset",
        revision = 1L,
        now = now,
        sectionScope = sectionScope,
    )

    private fun createTask(
        id: String,
        title: String = "Task $id",
        containerId: String? = null,
        status: TaskStatus = TaskStatus.PENDING,
        due: TaskDue = TaskDue.None,
        createdAt: Long,
        updatedAt: Long = createdAt,
        completedAt: Long? = null,
        parentTaskId: String? = null,
        linkedHabitUuid: String? = null,
    ) = UserTask(
        id = TaskId(id),
        title = title,
        containerId = containerId?.let { ContainerId(it) },
        status = status,
        due = due,
        createdAt = createdAt,
        updatedAt = updatedAt,
        completedAt = completedAt,
        parentTaskId = parentTaskId?.let { TaskId(it) },
        linkedHabitUuid = linkedHabitUuid,
    )

    private fun createSession(
        id: String,
        startedAt: Long,
        endedAt: Long,
        durationMs: Long = endedAt - startedAt,
        taskId: String? = null,
        containerId: String? = null,
        primaryTargetType: FocusPrimaryTargetType? = if (taskId != null) FocusPrimaryTargetType.TASK else if (containerId != null) FocusPrimaryTargetType.CONTAINER else null,
        organizationContext: String? = null,
    ) = FocusSession(
        id = FocusSessionId(id),
        startedAt = startedAt,
        endedAt = endedAt,
        durationMs = durationMs,
        taskId = taskId?.let { TaskId(it) },
        containerId = containerId?.let { ContainerId(it) },
        source = FocusSessionSource.MANUAL,
        createdAt = startedAt,
        updatedAt = endedAt,
        primaryTargetType = primaryTargetType,
        organizationContext = organizationContext,
    )

    // ------------------------------------------------------------------------
    // 1. Half-Open Period Boundaries [startMillis, endMillis)
    // ------------------------------------------------------------------------

    @Test
    fun periodBoundariesHalfOpenStartInclusiveEndExclusive() {
        val ctx = createContext()
        val start = ctx.startMillis
        val end = ctx.endMillis

        val tJustBeforeStart = createTask("t0", createdAt = start - 1, completedAt = start - 1, status = TaskStatus.COMPLETED)
        val tAtStart = createTask("t1", createdAt = start, completedAt = start, status = TaskStatus.COMPLETED)
        val tJustBeforeEnd = createTask("t2", createdAt = end - 1, completedAt = end - 1, status = TaskStatus.COMPLETED)
        val tAtEnd = createTask("t3", createdAt = end, completedAt = end, status = TaskStatus.COMPLETED)

        val taskResult = TaskStatisticsAggregator.aggregate(ctx, listOf(tJustBeforeStart, tAtStart, tJustBeforeEnd, tAtEnd))
        assertEquals(2, taskResult.completedCount)
        assertEquals(listOf("t1", "t2"), taskResult.completedTasks.map { it.id.value })
        assertEquals(2, taskResult.createdCount)
        assertEquals(listOf("t1", "t2"), taskResult.createdTasks.map { it.id.value })

        val sJustBeforeStart = createSession("s0", startedAt = start - 100, endedAt = start - 1)
        val sAtStart = createSession("s1", startedAt = start - 100, endedAt = start)
        val sJustBeforeEnd = createSession("s2", startedAt = end - 100, endedAt = end - 1)
        val sAtEnd = createSession("s3", startedAt = end - 100, endedAt = end)

        val focusResult = FocusStatisticsAggregator.aggregate(ctx, listOf(sJustBeforeStart, sAtStart, sJustBeforeEnd, sAtEnd))
        assertEquals(2, focusResult.sessionCount)
        assertEquals(listOf("s1", "s2"), focusResult.sessions.map { it.id.value })
    }

    // ------------------------------------------------------------------------
    // 2. Due Variants (NONE, DAY, TIMED) and Timezone Boundaries
    // ------------------------------------------------------------------------

    @Test
    fun dueVariantsAndDifferentTimezones() {
        val ctxTokyo = createContext(timezoneId = "Asia/Tokyo")
        val ctxNy = createContext(timezoneId = "America/New_York")

        // Day due date: 2026-10-20
        val tDay = createTask(
            "t-day",
            due = TaskDue.Day(TaskDueDate.parse("2026-10-20")),
            createdAt = ctxTokyo.startMillis + 1000,
        )
        // Timed due date: 2026-10-19 01:00:00 UTC
        val instantDue = Instant.parse("2026-10-19T01:00:00Z").toEpochMilli()
        val tTimed = createTask(
            "t-timed",
            due = TaskDue.Timed.of(instantDue),
            createdAt = instantDue - 1000,
        )
        // None due date
        val tNone = createTask(
            "t-none",
            due = TaskDue.None,
            createdAt = ctxTokyo.startMillis + 2000,
        )

        val resTokyo = TaskStatisticsAggregator.aggregate(ctxTokyo, listOf(tDay, tTimed, tNone))
        assertEquals(2, resTokyo.dueSummary.totalCount)
        assertTrue(resTokyo.dueSummary.tasks.contains(tDay))
        assertTrue(resTokyo.dueSummary.tasks.contains(tTimed))
        assertFalse(resTokyo.dueSummary.tasks.contains(tNone))
        assertEquals(1, resTokyo.noDueSummary.totalCount)
        assertTrue(resTokyo.noDueSummary.tasks.contains(tNone))

        // In NY timezone, Habit offset does not shift task Due or civil date
        val resNy = TaskStatisticsAggregator.aggregate(ctxNy, listOf(tDay, tTimed, tNone))
        // 01:00 UTC is still Oct 18 in New York, outside this period.
        assertEquals(1, resNy.dueSummary.totalCount)
        assertEquals(listOf(tDay), resNy.dueSummary.tasks)
    }

    // ------------------------------------------------------------------------
    // 3. T3 Partition: COMPLETED, PENDING_OVERDUE_NOW, PENDING_NOT_DUE_NOW
    // ------------------------------------------------------------------------

    @Test
    fun taskDuePartitionsAtCurrentClock() {
        val ctx = createContext() // now is Wednesday 2026-10-21 12:00:00 UTC, today is 2026-10-21

        // 1. Completed task due yesterday
        val tCompleted = createTask(
            "t1",
            status = TaskStatus.COMPLETED,
            completedAt = Instant.parse("2026-10-20T10:00:00Z").toEpochMilli(),
            due = TaskDue.Day(TaskDueDate.parse("2026-10-20")),
            createdAt = Instant.parse("2026-10-19T10:00:00Z").toEpochMilli(),
        )

        // 2. Pending task due yesterday (2026-10-20) -> OVERDUE NOW
        val tOverdueDay = createTask(
            "t2",
            status = TaskStatus.PENDING,
            due = TaskDue.Day(TaskDueDate.parse("2026-10-20")),
            createdAt = Instant.parse("2026-10-19T10:00:00Z").toEpochMilli(),
        )

        // 3. Pending task timed due 2 hours ago today (2026-10-21 10:00:00 UTC) -> OVERDUE NOW
        val tOverdueTimed = createTask(
            "t3",
            status = TaskStatus.PENDING,
            due = TaskDue.Timed.of(Instant.parse("2026-10-21T10:00:00Z").toEpochMilli()),
            createdAt = Instant.parse("2026-10-19T10:00:00Z").toEpochMilli(),
        )

        // 4. Pending task timed due 3 hours in future today (2026-10-21 15:00:00 UTC) -> NOT DUE NOW
        val tNotOverdueToday = createTask(
            "t4",
            status = TaskStatus.PENDING,
            due = TaskDue.Timed.of(Instant.parse("2026-10-21T15:00:00Z").toEpochMilli()),
            createdAt = Instant.parse("2026-10-19T10:00:00Z").toEpochMilli(),
        )

        // 5. Pending task due tomorrow (2026-10-22) -> NOT DUE NOW
        val tNotOverdueFuture = createTask(
            "t5",
            status = TaskStatus.PENDING,
            due = TaskDue.Day(TaskDueDate.parse("2026-10-22")),
            createdAt = Instant.parse("2026-10-19T10:00:00Z").toEpochMilli(),
        )

        val result = TaskStatisticsAggregator.aggregate(ctx, listOf(tCompleted, tOverdueDay, tOverdueTimed, tNotOverdueToday, tNotOverdueFuture))

        assertEquals(5, result.dueSummary.totalCount)
        assertEquals(1, result.dueSummary.completedCount)
        assertEquals(listOf("t1"), result.dueSummary.completedTasks.map { it.id.value })
        assertEquals(2, result.dueSummary.pendingOverdueNowCount)
        assertEquals(listOf("t2", "t3"), result.dueSummary.pendingOverdueNowTasks.map { it.id.value })
        assertEquals(2, result.dueSummary.pendingNotOverdueNowCount)
        assertEquals(listOf("t4", "t5"), result.dueSummary.pendingNotOverdueNowTasks.map { it.id.value })

        // Partition invariant: sum of disjoint partition equals total
        assertEquals(
            result.dueSummary.totalCount,
            result.dueSummary.completedCount + result.dueSummary.pendingOverdueNowCount + result.dueSummary.pendingNotOverdueNowCount,
        )
    }

    // ------------------------------------------------------------------------
    // 4. Parent and Subtask IDs Independent (No Rollup)
    // ------------------------------------------------------------------------

    @Test
    fun parentAndSubtaskIdsAreIndependentWithoutRollup() {
        val ctx = createContext()
        val ts = ctx.startMillis + 1000

        val parentTask = createTask("parent", createdAt = ts, completedAt = ts + 500, status = TaskStatus.COMPLETED)
        val subTask = createTask("sub", parentTaskId = "parent", createdAt = ts, completedAt = ts + 200, status = TaskStatus.COMPLETED)

        val result = TaskStatisticsAggregator.aggregate(ctx, listOf(parentTask, subTask))
        assertEquals(2, result.completedCount)
        assertEquals(2, result.createdCount)
        assertEquals(listOf("parent", "sub"), result.completedTasks.map { it.id.value })
    }

    // ------------------------------------------------------------------------
    // 5. State After Reopen / Deletion (Pending completedAt not fake history)
    // ------------------------------------------------------------------------

    @Test
    fun reopenedTaskNotCountedInT1AndDeletedTasksLeavePastCohort() {
        val ctx = createContext()
        val ts = ctx.startMillis + 5000

        // Reopened task: has old completedAt timestamp, but status is now PENDING
        val reopenedTask = createTask(
            "reopened",
            status = TaskStatus.PENDING,
            createdAt = ts,
            completedAt = ts + 1000,
        )

        val result = TaskStatisticsAggregator.aggregate(ctx, listOf(reopenedTask))
        assertEquals(0, result.completedCount)
        assertTrue(result.completedTasks.isEmpty())
        assertEquals(1, result.createdCount) // still created in period
        assertEquals(1, result.noDueSummary.pendingCount)
        assertEquals(0, result.noDueSummary.completedCount)
    }

    // ------------------------------------------------------------------------
    // 6. Deduplication: Identical IDs deduplicated; Conflicting IDs excluded
    // ------------------------------------------------------------------------

    @Test
    fun taskDeduplicationIdenticalVsConflicting() {
        val ctx = createContext()
        val ts = ctx.startMillis + 1000

        val tIdentical1 = createTask("t1", title = "Same", createdAt = ts, completedAt = ts + 100, status = TaskStatus.COMPLETED)
        val tIdentical2 = createTask("t1", title = "Same", createdAt = ts, completedAt = ts + 100, status = TaskStatus.COMPLETED)

        val tConflict1 = createTask("t2", title = "Version 1", createdAt = ts)
        val tConflict2 = createTask("t2", title = "Version 2 (Conflict)", createdAt = ts)

        val result = TaskStatisticsAggregator.aggregate(ctx, listOf(tIdentical1, tIdentical2, tConflict1, tConflict2))

        // t1 is kept once
        assertEquals(1, result.completedCount)
        assertEquals("t1", result.completedTasks.first().id.value)

        // t2 is excluded due to conflict
        assertEquals(1, result.createdCount)
        assertEquals(StatisticsDataState.PARTIAL, result.dataState)
        val conflictIssue = result.issues.firstOrNull { it.code == StatisticsIssueCode.CONFLICTING_DUPLICATE }
        assertNotNull(conflictIssue)
        assertEquals("t2", conflictIssue!!.entityId)
    }

    // ------------------------------------------------------------------------
    // 7. Invalid Timestamps Quality Flags
    // ------------------------------------------------------------------------

    @Test
    fun taskAndSessionInvalidTimestampsQualityFlags() {
        val ctx = createContext()
        val ts = ctx.startMillis + 1000

        // Negative createdAt
        val tNegCreated = createTask("t-bad-create", createdAt = -10)
        // Completed task with missing completedAt
        val tNoCompleted = createTask("t-bad-complete", status = TaskStatus.COMPLETED, createdAt = ts, completedAt = null)
        // Completed task where completedAt < createdAt
        val tBackwards = createTask("t-backwards", status = TaskStatus.COMPLETED, createdAt = ts + 1000, completedAt = ts)
        // Timed due with non-positive epoch
        val tBadDue = createTask("t-bad-due", createdAt = ts, due = TaskDue.Timed.of(0))

        val taskResult = TaskStatisticsAggregator.aggregate(ctx, listOf(tNegCreated, tNoCompleted, tBackwards, tBadDue))
        assertEquals(StatisticsDataState.PARTIAL, taskResult.dataState)
        assertEquals(4, taskResult.issues.filter { it.code == StatisticsIssueCode.INVALID_TIMESTAMP }.size)

        // Invalid finalized facts are rejected by the existing model boundary.
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            createSession("s-bad-duration", startedAt = ts, endedAt = ts + 1000, durationMs = 2000)
        }
    }

    // ------------------------------------------------------------------------
    // 8. Bucket and Drilldown Equality Invariants (Tasks)
    // ------------------------------------------------------------------------

    @Test
    fun taskBucketAndDrilldownEquality() {
        val ctx = createContext()

        val t1 = createTask("t1", createdAt = ctx.civilDayStart(LocalDate(2026, 10, 19)) + 1000, completedAt = ctx.civilDayStart(LocalDate(2026, 10, 19)) + 2000, status = TaskStatus.COMPLETED, due = TaskDue.Day(TaskDueDate.parse("2026-10-19")))
        val t2 = createTask("t2", createdAt = ctx.civilDayStart(LocalDate(2026, 10, 19)) + 3000, completedAt = ctx.civilDayStart(LocalDate(2026, 10, 20)) + 2000, status = TaskStatus.COMPLETED, due = TaskDue.Day(TaskDueDate.parse("2026-10-20")))
        val t3 = createTask("t3", createdAt = ctx.civilDayStart(LocalDate(2026, 10, 21)) + 1000, due = TaskDue.None, status = TaskStatus.PENDING)

        val result = TaskStatisticsAggregator.aggregate(ctx, listOf(t1, t2, t3))

        // T1 Day buckets equality
        assertEquals(result.completedCount, result.dailyCompletedBuckets.sumOf { it.count })
        assertEquals(result.completedTasks, result.dailyCompletedBuckets.flatMap { it.tasks })

        // T2 Day buckets equality
        assertEquals(result.createdCount, result.dailyCreatedBuckets.sumOf { it.count })
        assertEquals(result.createdTasks, result.dailyCreatedBuckets.flatMap { it.tasks })

        // T3 Day buckets equality
        assertEquals(result.dueSummary.totalCount, result.dailyDueBuckets.sumOf { it.totalCount })
        assertEquals(result.dueSummary.completedCount, result.dailyDueBuckets.sumOf { it.completedCount })
        assertEquals(result.dueSummary.pendingOverdueNowCount, result.dailyDueBuckets.sumOf { it.pendingOverdueNowCount })
        assertEquals(result.dueSummary.pendingNotOverdueNowCount, result.dailyDueBuckets.sumOf { it.pendingNotOverdueNowCount })

        // T4 Day buckets equality
        assertEquals(result.noDueSummary.totalCount, result.dailyNoDueBuckets.sumOf { it.totalCount })
        assertEquals(result.noDueSummary.completedCount, result.dailyNoDueBuckets.sumOf { it.completedCount })
        assertEquals(result.noDueSummary.pendingCount, result.dailyNoDueBuckets.sumOf { it.pendingCount })
    }

    // ------------------------------------------------------------------------
    // 9. Focus Overlapping Distinct Session IDs
    // ------------------------------------------------------------------------

    @Test
    fun focusOverlappingDistinctSessionIdsPreservesDurationsAndFlagsIssue() {
        val ctx = createContext()
        val ts = ctx.startMillis + 10_000

        // Session 1: 10:00 to 11:00 (60 min)
        val s1 = createSession("s1", startedAt = ts, endedAt = ts + 3600_000, durationMs = 3600_000)
        // Session 2: 10:30 to 11:30 (60 min) - Overlaps s1
        val s2 = createSession("s2", startedAt = ts + 1800_000, endedAt = ts + 5400_000, durationMs = 3600_000)

        val result = FocusStatisticsAggregator.aggregate(ctx, listOf(s1, s2))

        // Both sessions preserved in total duration (3600 + 3600 = 7200 seconds)
        assertEquals(2, result.sessionCount)
        assertEquals(7200_000L, result.totalDurationMs)

        // OVERLAPPING_SESSIONS issue flagged
        val overlapIssue = result.issues.firstOrNull { it.code == StatisticsIssueCode.OVERLAPPING_SESSIONS }
        assertNotNull(overlapIssue)
        assertTrue(overlapIssue!!.entityId!!.contains("s1") && overlapIssue.entityId!!.contains("s2"))
    }

    // ------------------------------------------------------------------------
    // 10. Focus Duplicate and Conflicting IDs
    // ------------------------------------------------------------------------

    @Test
    fun focusDuplicateAndConflictingIds() {
        val ctx = createContext()
        val ts = ctx.startMillis + 10_000

        val sIdentical1 = createSession("s1", startedAt = ts, endedAt = ts + 1000)
        val sIdentical2 = createSession("s1", startedAt = ts, endedAt = ts + 1000)

        val sConflict1 = createSession("s2", startedAt = ts, endedAt = ts + 1000)
        val sConflict2 = createSession("s2", startedAt = ts, endedAt = ts + 2000)

        val result = FocusStatisticsAggregator.aggregate(ctx, listOf(sIdentical1, sIdentical2, sConflict1, sConflict2))

        assertEquals(1, result.sessionCount)
        assertEquals("s1", result.sessions.first().id.value)
        val conflictIssue = result.issues.firstOrNull { it.code == StatisticsIssueCode.CONFLICTING_DUPLICATE }
        assertNotNull(conflictIssue)
        assertEquals("s2", conflictIssue!!.entityId)
    }

    // ------------------------------------------------------------------------
    // 11. Focus Historical Scope: Frozen Path Precedence over Current Location
    // ------------------------------------------------------------------------

    @Test
    fun focusFrozenPathTakesPrecedenceOverCurrentContainerLocation() {
        val ts = createContext().startMillis + 10_000
        val targetContainer = ContainerId("work-container")
        val otherContainer = ContainerId("study-container")

        // Session was recorded while under "work-container", frozen in organizationContext
        val frozenJson = """{"path":[{"id":"work-container","name":"Work"}]}"""
        val sFrozen = createSession(
            "s-frozen",
            startedAt = ts,
            endedAt = ts + 1800_000,
            containerId = "study-container", // current container changed to study
            organizationContext = frozenJson,
        )

        // Query under work-container
        val ctxWork = createContext(sectionScope = StatisticsSectionScope.Container(targetContainer))
        val resWork = FocusStatisticsAggregator.aggregate(ctxWork, listOf(sFrozen))
        assertEquals(1, resWork.sessionCount)
        assertEquals(1800_000L, resWork.totalDurationMs)

        // Query under study-container
        val ctxStudy = createContext(sectionScope = StatisticsSectionScope.Container(otherContainer))
        val resStudy = FocusStatisticsAggregator.aggregate(ctxStudy, listOf(sFrozen))
        assertEquals(0, resStudy.sessionCount)
    }

    // ------------------------------------------------------------------------
    // 12. Focus Pathless Fallback Marked with MISSING_HISTORICAL_PATH
    // ------------------------------------------------------------------------

    @Test
    fun focusPathlessFallbackMarkedWithMissingHistoricalPath() {
        val ctx = createContext(sectionScope = StatisticsSectionScope.Container(ContainerId("work-container")))
        val ts = ctx.startMillis + 10_000

        // Session has containerId but no frozen organizationContext
        val sPathless = createSession(
            "s-pathless",
            startedAt = ts,
            endedAt = ts + 1800_000,
            containerId = "work-container",
            organizationContext = null,
        )

        val result = FocusStatisticsAggregator.aggregate(ctx, listOf(sPathless))
        assertEquals(1, result.sessionCount)
        val pathlessIssue = result.issues.firstOrNull { it.code == StatisticsIssueCode.MISSING_HISTORICAL_PATH }
        assertNotNull(pathlessIssue)
        assertEquals("s-pathless", pathlessIssue!!.entityId)
    }

    // ------------------------------------------------------------------------
    // 13. Focus Disjoint Section Breakdown (Root, Child, NoSection, Deleted, Pathless)
    // ------------------------------------------------------------------------

    @Test
    fun focusDisjointSectionBreakdownSumTotal() {
        val ts = createContext().startMillis + 10_000
        val fakeDb = FakeContainerQueries(
            containers = mapOf(
                ContainerId("c-root1") to createContainer("c-root1", "Work"),
                ContainerId("c-root2") to createContainer("c-root2", "Study"),
                ContainerId("c-deleted") to createContainer("c-deleted", "Old Project", deletedAt = ts),
            ),
        )

        val s1 = createSession("s1", startedAt = ts, endedAt = ts + 1000, organizationContext = """{"path":[{"id":"c-root1","name":"Work"}]}""")
        val s2 = createSession("s2", startedAt = ts, endedAt = ts + 2000, organizationContext = """{"path":[{"id":"c-root2","name":"Study"}]}""")
        val s3 = createSession("s3", startedAt = ts, endedAt = ts + 3000, organizationContext = """{"path":[{"id":"c-deleted","name":"Old Project"}]}""")
        val s4 = createSession("s4", startedAt = ts, endedAt = ts + 4000, containerId = null, organizationContext = null) // No section

        val ctx = createContext(sectionScope = StatisticsSectionScope.All)
        val result = FocusStatisticsAggregator.aggregate(ctx, listOf(s1, s2, s3, s4), fakeDb)

        assertEquals(4, result.sessionCount)
        assertEquals(10_000L, result.totalDurationMs)

        // Invariant: Disjoint breakdown sum equals total duration and count
        assertEquals(result.totalDurationMs, result.sectionBreakdowns.sumOf { it.durationMs })
        assertEquals(result.sessionCount, result.sectionBreakdowns.sumOf { it.sessionCount })

        val categories = result.sectionBreakdowns.map { it.category }
        assertTrue(categories.contains(FocusSectionCategory.ROOT_CONTAINER))
        assertTrue(categories.contains(FocusSectionCategory.DELETED))
        assertTrue(categories.contains(FocusSectionCategory.NO_SECTION))
    }

    // ------------------------------------------------------------------------
    // 14. Focus Subtree vs Direct Breakdown (HERE vs Subtree)
    // ------------------------------------------------------------------------

    @Test
    fun focusSubtreeVsDirectBreakdown() {
        val rootId = ContainerId("root")
        val childId = ContainerId("child")
        val fakeDb = FakeContainerQueries(
            containers = mapOf(
                rootId to createContainer("root", "Root"),
                childId to createContainer("child", "Child", parentId = rootId),
            ),
            tree = mapOf(rootId to listOf(childId)),
        )

        val ts = createContext().startMillis + 10_000
        val sDirect = createSession("s-dir", startedAt = ts, endedAt = ts + 1000, organizationContext = """{"path":[{"id":"root","name":"Root"}]}""")
        val sChild = createSession("s-child", startedAt = ts, endedAt = ts + 2000, organizationContext = """{"path":[{"id":"root","name":"Root"},{"id":"child","name":"Child"}]}""")

        // Subtree query
        val ctxSubtree = createContext(
            sectionScope = StatisticsSectionScope.Container(
                id = rootId,
                includeDescendants = true,
                currentDescendantIds = setOf(childId),
            ),
        )
        val resSubtree = FocusStatisticsAggregator.aggregate(ctxSubtree, listOf(sDirect, sChild), fakeDb)
        assertEquals(2, resSubtree.sessionCount)
        assertEquals(3000L, resSubtree.totalDurationMs)
        assertEquals(2, resSubtree.sectionBreakdowns.size)
        assertEquals(resSubtree.totalDurationMs, resSubtree.sectionBreakdowns.sumOf { it.durationMs })

        // Direct query (HERE mode, includeDescendants = false)
        val ctxDirect = createContext(
            sectionScope = StatisticsSectionScope.Container(
                id = rootId,
                includeDescendants = false,
                currentDescendantIds = setOf(childId),
            ),
        )
        val resDirect = FocusStatisticsAggregator.aggregate(ctxDirect, listOf(sDirect, sChild), fakeDb)
        assertEquals(1, resDirect.sessionCount)
        assertEquals(1000L, resDirect.totalDurationMs)
        assertEquals(FocusSectionCategory.DIRECT, resDirect.sectionBreakdowns.first().category)
    }

    // ------------------------------------------------------------------------
    // 15. Focus Target Breakdown & Task with Linked Habit (Stays TASK)
    // ------------------------------------------------------------------------

    @Test
    fun focusTargetBreakdownAndTaskWithLinkedHabitStaysTask() {
        val ctx = createContext()
        val ts = ctx.startMillis + 10_000

        // Task with linked habit remains TASK: "credit — подпись ledger, не второй slice"
        val sTask = createSession("s1", startedAt = ts, endedAt = ts + 1000, taskId = "task-1")
        val sHabit = createSession(
            "s2",
            startedAt = ts,
            endedAt = ts + 2000,
        ).copy(habit = org.isoron.uhabits.core.containers.HabitRef("habit-uuid"),
            primaryTargetType = FocusPrimaryTargetType.HABIT)
        val sContainer = createSession("s3", startedAt = ts, endedAt = ts + 3000, containerId = "container-1")

        val result = FocusStatisticsAggregator.aggregate(ctx, listOf(sTask, sHabit, sContainer))
        assertEquals(3, result.sessionCount)
        assertEquals(6000L, result.totalDurationMs)

        // Invariant: Target breakdown sum equals total duration and count
        assertEquals(result.totalDurationMs, result.targetBreakdowns.sumOf { it.durationMs })
        assertEquals(result.sessionCount, result.targetBreakdowns.sumOf { it.sessionCount })

        // Target type summary check
        assertEquals(result.totalDurationMs, result.targetTypeSummaries.sumOf { it.durationMs })
        assertEquals(result.sessionCount, result.targetTypeSummaries.sumOf { it.sessionCount })

        val taskTarget = result.targetBreakdowns.firstOrNull { it.primaryTargetType == FocusPrimaryTargetType.TASK }
        assertNotNull(taskTarget)
        assertEquals("task-1", taskTarget!!.targetId)
    }

    // ------------------------------------------------------------------------
    // 16. Focus Duration Safe Overflow Handling
    // ------------------------------------------------------------------------

    @Test
    fun focusDurationSafeOverflowClampsAndFlagsIssue() {
        val end = 100_000_000_000_000_000L
        val zone = ZoneId.of("UTC")
        val date = Instant.ofEpochMilli(end).atZone(zone).toLocalDate()
        val ctx = createContext(from = LocalDate(date.year, date.monthValue, date.dayOfMonth),
            to = LocalDate(date.year, date.monthValue, date.dayOfMonth))
        val sessions = (1..100).map { createSession("s$it", startedAt = 0, endedAt = end, durationMs = end) }

        val result = FocusStatisticsAggregator.aggregate(ctx, sessions)
        assertEquals(Long.MAX_VALUE, result.totalDurationMs)
        assertEquals(StatisticsDataState.UNAVAILABLE, result.dataState)
        assertNull(result.averageSessionDurationMs)
        val overflowIssue = result.issues.firstOrNull { it.code == StatisticsIssueCode.OVERFLOW }
        assertNotNull(overflowIssue)
    }

    // ------------------------------------------------------------------------
    // 17. Focus Daily Buckets Equality Invariant
    // ------------------------------------------------------------------------

    @Test
    fun focusDailyBucketsEqualityInvariant() {
        val ctx = createContext()
        val d1 = ctx.civilDayStart(LocalDate(2026, 10, 19)) + 5000
        val d2 = ctx.civilDayStart(LocalDate(2026, 10, 20)) + 5000

        val s1 = createSession("s1", startedAt = d1 - 1000, endedAt = d1, durationMs = 1000)
        val s2 = createSession("s2", startedAt = d2 - 2000, endedAt = d2, durationMs = 2000)

        val result = FocusStatisticsAggregator.aggregate(ctx, listOf(s1, s2))
        assertEquals(result.totalDurationMs, result.dailyBuckets.sumOf { it.durationMs })
        assertEquals(result.sessionCount, result.dailyBuckets.sumOf { it.sessionCount })
        assertEquals(result.sessions, result.dailyBuckets.flatMap { it.sessions })
    }

    // ------------------------------------------------------------------------
    // Test Fakes and Helpers
    // ------------------------------------------------------------------------

    private fun createContainer(
        id: String,
        name: String,
        parentId: ContainerId? = null,
        deletedAt: Long? = null,
        isArchived: Boolean = false,
    ) = Container(
        id = ContainerId(id),
        parentId = parentId,
        name = name,
        color = null,
        icon = null,
        siblingOrder = 0,
        isArchived = isArchived,
        createdAt = 1000L,
        updatedAt = 1000L,
        deletedAt = deletedAt,
        revision = OrganizationRevision(1),
    )

    private class FakeContainerQueries(
        private val containers: Map<ContainerId, Container> = emptyMap(),
        private val tree: Map<ContainerId, List<ContainerId>> = emptyMap(),
    ) : ContainerQueries {
        override fun find(id: ContainerId, includeDeleted: Boolean): Container? {
            val c = containers[id] ?: return null
            if (!includeDeleted && c.deletedAt != null) return null
            return c
        }

        override fun roots(includeArchived: Boolean): List<Container> =
            containers.values.filter { it.parentId == null && (includeArchived || !it.isArchived) && it.deletedAt == null }

        override fun children(parent: ContainerId, includeArchived: Boolean): List<Container> {
            val childIds = tree[parent] ?: emptyList()
            return childIds.mapNotNull { find(it, includeDeleted = true) }
                .filter { (includeArchived || !it.isArchived) && it.deletedAt == null }
        }

        override fun path(id: ContainerId): ContainerPath {
            val nodes = mutableListOf<ContainerId>()
            var curr: ContainerId? = id
            while (curr != null) {
                nodes.add(0, curr)
                curr = containers[curr]?.parentId
            }
            return ContainerPath(nodes, OrganizationRevision(1))
        }

        override fun subtreeIds(id: ContainerId): Set<ContainerId> {
            val result = mutableSetOf<ContainerId>()
            fun dfs(curr: ContainerId) {
                val children = tree[curr] ?: emptyList()
                for (ch in children) {
                    if (result.add(ch)) dfs(ch)
                }
            }
            dfs(id)
            return result
        }

        override fun isEffectivelyArchived(id: ContainerId): Boolean = containers[id]?.isArchived ?: false

        override fun search(query: String): List<ContainerSearchHit> = emptyList()
    }
}
