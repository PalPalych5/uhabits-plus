package org.isoron.uhabits.activities.tasks

import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.core.userTasks.TaskId
import org.isoron.uhabits.core.userTasks.UserTask
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class TaskCalendarEventsTest {
    private val day = LocalDate(2026, 10, 7)
    private val deadline = Calendar.getInstance().apply { clear(); set(2026, Calendar.OCTOBER, 7, 15, 45) }.timeInMillis
    private val task = UserTask(TaskId("test"), "Database", createdAt = 0, updatedAt = 0)

    @Test fun sameDayPlanAndDeadlineRemainTwoEvents() {
        val events = TaskCalendarEvents.from(listOf(task.copy(plannedDate = day, deadline = deadline)))
        assertEquals(2, events.size)
        assertEquals(setOf(TaskCalendarEventKind.PLAN, TaskCalendarEventKind.DEADLINE), events.map { it.kind }.toSet())
        assertEquals(listOf(day, day), events.map { it.day })
    }

    @Test fun independentDatesAndUndatedTask() {
        val otherDay = LocalDate(2026, 10, 6)
        val events = TaskCalendarEvents.from(listOf(task.copy(plannedDate = otherDay, deadline = deadline), task))
        assertEquals(listOf(otherDay, day), events.map { it.day })
        assertEquals(listOf(TaskCalendarEventKind.PLAN, TaskCalendarEventKind.DEADLINE), events.map { it.kind })
    }

    @Test fun deadlineOnlyHasNoSyntheticPlan() {
        val events = TaskCalendarEvents.from(listOf(task.copy(deadline = deadline)))
        assertEquals(1, events.size)
        assertEquals(TaskCalendarEventKind.DEADLINE, events.single().kind)
    }
}
