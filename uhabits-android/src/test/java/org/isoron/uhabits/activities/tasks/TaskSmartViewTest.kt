package org.isoron.uhabits.activities.tasks

import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.userTasks.TaskId
import org.isoron.uhabits.core.userTasks.TaskStatus
import org.isoron.uhabits.core.userTasks.UserTask
import org.isoron.uhabits.core.userTasks.due.TaskDue
import org.isoron.uhabits.core.userTasks.due.TaskDueDate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskSmartViewTest {
    private val today = LocalDate(2026, 10, 4)
    private val yesterday = LocalDate(2026, 10, 3)
    private val tomorrow = LocalDate(2026, 10, 5)
    // 2026-10-04 12:00:00 UTC = 1791115200000L
    private val now = 1791115200000L
    private val task = UserTask(TaskId("task"), "Study indexes", createdAt = 0, updatedAt = 0)

    @Test
    fun inboxExcludesOrganizedTasksEvenWithoutDates() {
        assertFalse(matches(TaskSmartView.INBOX, task.copy(containerId = ContainerId("study"))))
        assertTrue(matches(TaskSmartView.INBOX, task))
    }

    @Test
    fun inboxIncludesUnassignedTasksRegardlessOfDue() {
        assertTrue(matches(TaskSmartView.INBOX, task.copy(due = TaskDue.Day(TaskDueDate.from(tomorrow)))))
        assertTrue(matches(TaskSmartView.INBOX, task.copy(due = TaskDue.Timed.of(now + 3600000L))))
        assertTrue(matches(TaskSmartView.INBOX, task.copy(due = TaskDue.Day(TaskDueDate.from(yesterday)))))
    }

    @Test
    fun completedTasksOnlyAppearInCompletedView() {
        val completed = task.copy(
            status = TaskStatus.COMPLETED,
            completedAt = now,
            due = TaskDue.Day(TaskDueDate.from(today)),
        )
        for (view in TaskSmartView.entries) {
            if (view == TaskSmartView.COMPLETED) assertTrue(matches(view, completed))
            else assertFalse(matches(view, completed))
        }
        assertFalse(matches(TaskSmartView.COMPLETED, task))
    }

    @Test
    fun todayStrictlyIncludesTasksDueToday() {
        val dueTodayDay = task.copy(due = TaskDue.Day(TaskDueDate.from(today)))
        assertTrue(matches(TaskSmartView.TODAY, dueTodayDay))

        // Timed due today
        val dueTodayTimed = task.copy(due = TaskDue.Timed.of(now + 1000L))
        assertTrue(matches(TaskSmartView.TODAY, dueTodayTimed))

        // None does not match today
        assertFalse(matches(TaskSmartView.TODAY, task))

        // Yesterday does NOT match today (it's overdue!)
        val dueYesterday = task.copy(due = TaskDue.Day(TaskDueDate.from(yesterday)))
        assertFalse(matches(TaskSmartView.TODAY, dueYesterday))
        assertTrue(matches(TaskSmartView.OVERDUE, dueYesterday))

        // Tomorrow does NOT match today (it's upcoming!)
        val dueTomorrow = task.copy(due = TaskDue.Day(TaskDueDate.from(tomorrow)))
        assertFalse(matches(TaskSmartView.TODAY, dueTomorrow))
        assertTrue(matches(TaskSmartView.UPCOMING, dueTomorrow))
    }

    @Test
    fun overdueIncludesOnlyOverdueTasks() {
        val overdueDay = task.copy(due = TaskDue.Day(TaskDueDate.from(yesterday)))
        assertTrue(matches(TaskSmartView.OVERDUE, overdueDay))

        val overdueTimed = task.copy(due = TaskDue.Timed.of(now - 1000L))
        assertTrue(matches(TaskSmartView.OVERDUE, overdueTimed))

        assertFalse(matches(TaskSmartView.OVERDUE, task))
        assertFalse(matches(TaskSmartView.OVERDUE, task.copy(due = TaskDue.Day(TaskDueDate.from(today)))))
        assertFalse(matches(TaskSmartView.OVERDUE, task.copy(due = TaskDue.Day(TaskDueDate.from(tomorrow)))))
    }

    @Test
    fun upcomingIncludesOnlyFutureTasks() {
        val futureDay = task.copy(due = TaskDue.Day(TaskDueDate.from(tomorrow)))
        assertTrue(matches(TaskSmartView.UPCOMING, futureDay))

        val futureTimed = task.copy(due = TaskDue.Timed.of(now + 86400000L * 2))
        assertTrue(matches(TaskSmartView.UPCOMING, futureTimed))

        assertFalse(matches(TaskSmartView.UPCOMING, task))
        assertFalse(matches(TaskSmartView.UPCOMING, task.copy(due = TaskDue.Day(TaskDueDate.from(today)))))
        assertFalse(matches(TaskSmartView.UPCOMING, task.copy(due = TaskDue.Day(TaskDueDate.from(yesterday)))))
    }

    @Test
    fun allIncludesOrganizedUnscheduledTasks() {
        assertTrue(matches(TaskSmartView.ALL, task.copy(containerId = ContainerId("study"))))
    }

    private fun matches(view: TaskSmartView, task: UserTask): Boolean =
        view.matches(task, today, now)
}
