package org.isoron.uhabits.activities.statistics

import org.isoron.platform.time.DayOfWeek
import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.focus.*
import org.isoron.uhabits.core.statistics.*
import org.isoron.uhabits.core.userTasks.TaskId
import org.junit.Assert.*
import org.junit.Test

class TaskFocusDrilldownTest {
    @Test fun resumeAcrossTimedDueInvalidatesCachedCurrentStateWithoutDatabaseMutation() {
        val due = context.startMillis + 120_000
        val query = context.copy(now = due - 10)
        val task = org.isoron.uhabits.core.userTasks.UserTask(TaskId("timed"), "Timed",
            createdAt = context.startMillis, updatedAt = context.startMillis,
            due = org.isoron.uhabits.core.userTasks.due.TaskDue.Timed.of(due))
        val snapshot = UnifiedStatisticsReport(query, null,
            TaskStatisticsAggregator.aggregate(query, listOf(task)), null, emptyList())
        assertTrue(UnifiedStatisticsStateUtils.canReuseAt(snapshot, due - 1))
        assertFalse(UnifiedStatisticsStateUtils.canReuseAt(snapshot, due + 1))
        assertFalse(UnifiedStatisticsStateUtils.canReuseAt(snapshot, due - 20))
    }
    @Test fun sessionDurationRetainsSecondsAndFractionalSeconds() {
        assertEquals("1 min 30 s", UnifiedStatisticsStateUtils.formatExactDurationMs(90_000))
        assertEquals("0.5 s", UnifiedStatisticsStateUtils.formatExactDurationMs(500))
        assertEquals("1 ч 2 мин 3 с", UnifiedStatisticsStateUtils.formatExactDurationMs(3_723_000, true))
    }
    private val date = LocalDate(2026, 10, 19)
    private val context = StatisticsQueryContext(date, date.plus(6), "UTC", DayOfWeek.MONDAY,
        0, 0, "fixture", 1, 1_800_000_000_000, StatisticsSectionScope.All)
    private fun session(id: String, offset: Long, duration: Long, container: String) = FocusSession(
        FocusSessionId(id), context.startMillis + offset, context.startMillis + offset + duration,
        duration, taskId = TaskId("same-task"), containerId = ContainerId(container),
        source = FocusSessionSource.TIMER, createdAt = context.startMillis, updatedAt = context.startMillis,
    )
    private val first get() = session("first", 0, 30_000, "a")
    private val second get() = session("second", 86_400_000, 90_000, "b")
    private fun report(sessions: List<FocusSession>) = UnifiedStatisticsReport(context, null, null,
        FocusStatisticsAggregator.aggregate(context, sessions), emptyList())

    @Test fun shortFocusColumnsKeepExactMillisecondsAcrossScales() {
        val result = report(listOf(first, second)).focus!!
        for (period in UnifiedPeriod.entries) {
            val columns = DiscreteChartBucketer.buildFocusColumns(period, result, date)
            assertEquals("$period", 120_000L, columns.sumOf { it.value })
            assertTrue(columns.any { it.value > 0 })
        }
        assertTrue(DiscreteChartBucketer.buildFocusColumns(UnifiedPeriod.WEEK, result, date)
            .first().formattedValue.contains("30"))
    }

    @Test fun sameTargetInTwoBranchesKeepsBranchIntersectionAndFreshSelection() {
        val report = report(listOf(first, second))
        val branch = report.focus!!.sectionBreakdowns.single { it.sessions.contains(first) }
        val descriptor = FocusDrilldownDescriptor.TargetObject(FocusPrimaryTargetType.TASK,
            "same-task", branch.category, branch.containerId)
        assertEquals(listOf("first"), DrilldownSelectionResolver.resolveFocus(report, descriptor).map { it.id.value })
        assertEquals(listOf("first"), report.focus!!.targetsWithin(branch.sessions).single().sessions.map { it.id.value })
        assertEquals(30_000L, report.focus!!.targetsWithin(branch.sessions).single().durationMs)
        val fresh = report(listOf(second))
        assertTrue(DrilldownSelectionResolver.resolveFocus(fresh, descriptor).isEmpty())
        assertEquals(90_000L, fresh.focus!!.targetsWithin(fresh.focus!!.sessions).sumOf { it.durationMs })
    }

    @Test fun allAndTypeSelectionsUseUniquePrimarySessionIds() {
        val report = report(listOf(first, first, second))
        assertEquals(setOf("first", "second"), DrilldownSelectionResolver.resolveFocus(report,
            FocusDrilldownDescriptor.AllSections).map { it.id.value }.toSet())
        assertEquals(2, DrilldownSelectionResolver.resolveFocus(report,
            FocusDrilldownDescriptor.TargetType(FocusPrimaryTargetType.TASK)).size)
        assertTrue(DrilldownSelectionResolver.resolveFocus(report,
            FocusDrilldownDescriptor.TargetType(FocusPrimaryTargetType.HABIT)).isEmpty())
    }
}
