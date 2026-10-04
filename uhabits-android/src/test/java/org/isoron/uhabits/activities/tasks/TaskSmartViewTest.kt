package org.isoron.uhabits.activities.tasks

import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.userTasks.TaskId
import org.isoron.uhabits.core.userTasks.TaskStatus
import org.isoron.uhabits.core.userTasks.UserTask
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskSmartViewTest {
    private val today = LocalDate(2026, 10, 4)
    private val yesterday = LocalDate(2026, 10, 3)
    private val tomorrow = LocalDate(2026, 10, 5)
    private val now = 1_000L
    private val task = UserTask(TaskId("task"), "Study indexes", createdAt = 0, updatedAt = 0)

    @Test
    fun inboxExcludesOrganizedTasksEvenWithoutDates() {
        assertFalse(matches(TaskSmartView.INBOX, task.copy(containerId = ContainerId("study"))))
        assertTrue(matches(TaskSmartView.INBOX, task))
    }

    @Test
    fun inboxIncludesUnassignedTasksRegardlessOfPlanning() {
        assertTrue(matches(TaskSmartView.INBOX, task.copy(plannedDate = tomorrow)))
        assertTrue(matches(TaskSmartView.INBOX, task.copy(deadline = now + 1)))
        assertTrue(matches(TaskSmartView.INBOX, task.copy(plannedDate = yesterday, deadline = now - 1)))
    }

    @Test
    fun completedTasksOnlyAppearInCompletedView() {
        val completed = task.copy(
            status = TaskStatus.COMPLETED,
            completedAt = now,
            plannedDate = today,
            deadline = now - 1,
        )
        for (view in TaskSmartView.entries) {
            if (view == TaskSmartView.COMPLETED) assertTrue(matches(view, completed))
            else assertFalse(matches(view, completed))
        }
        assertFalse(matches(TaskSmartView.COMPLETED, task))
    }

    @Test
    fun todayIncludesCurrentPlanMissedPlanAndHardOverdue() {
        assertTrue(matches(TaskSmartView.TODAY, task.copy(plannedDate = today)))
        val missedPlan = task.copy(plannedDate = yesterday)
        assertTrue(matches(TaskSmartView.TODAY, missedPlan))
        assertTrue(missedPlan.isMissedPlan(today, now))
        assertFalse(missedPlan.isHardOverdue(now))
        val overdue = task.copy(plannedDate = tomorrow, deadline = now - 1)
        assertTrue(matches(TaskSmartView.TODAY, overdue))
        assertTrue(overdue.isHardOverdue(now))
        assertFalse(overdue.isMissedPlan(today, now))
        assertFalse(matches(TaskSmartView.TODAY, task))
        assertFalse(matches(TaskSmartView.TODAY, task.copy(plannedDate = tomorrow)))
        assertFalse(matches(TaskSmartView.TODAY, task.copy(deadline = now + 1)))
    }

    @Test
    fun upcomingUsesFuturePlanOrFutureDeadlineWithoutMissedPlan() {
        assertTrue(matches(TaskSmartView.UPCOMING, task.copy(plannedDate = tomorrow)))
        assertTrue(matches(TaskSmartView.UPCOMING, task.copy(deadline = now + 1)))
        assertTrue(matches(TaskSmartView.UPCOMING, task.copy(plannedDate = today, deadline = now + 1)))
        assertFalse(matches(TaskSmartView.UPCOMING, task.copy(plannedDate = yesterday, deadline = now + 1)))
        assertFalse(matches(TaskSmartView.UPCOMING, task.copy(plannedDate = today)))
        assertFalse(matches(TaskSmartView.UPCOMING, task.copy(deadline = now)))
        assertFalse(matches(TaskSmartView.UPCOMING, task))
    }

    @Test
    fun allIncludesOrganizedUnscheduledTasks() {
        assertTrue(matches(TaskSmartView.ALL, task.copy(containerId = ContainerId("study"))))
    }

    private fun matches(view: TaskSmartView, candidate: UserTask): Boolean =
        view.matches(candidate, today, now)
}
