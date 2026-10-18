package org.isoron.uhabits.activities.tasks

import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.core.userTasks.TaskId
import org.isoron.uhabits.core.userTasks.UserTask
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone
import java.time.Instant
import java.time.ZoneId

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

    @Test fun timezoneMidnightDstAndYearBoundariesMatchIndependentOracle() {
        val previous = TimeZone.getDefault()
        try {
            for (zone in listOf("UTC", "America/Los_Angeles", "Pacific/Kiritimati", "Asia/Novosibirsk")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                val inputs = listOf("2026-01-01T00:00:00Z", "2026-03-08T09:59:59Z",
                    "2026-03-08T10:00:00Z", "2026-11-01T08:59:59Z", "2026-11-01T09:00:00Z",
                    "2026-12-31T23:59:59Z").mapIndexed { index, instant ->
                    task.copy(id = TaskId("$index"), plannedDate = day,
                        deadline = Instant.parse(instant).toEpochMilli())
                } + task.copy(id = TaskId("undated")) + task.copy(id = TaskId("plan"), plannedDate = day)
                val expected = inputs.flatMap { input ->
                    val plan = input.plannedDate?.let { listOf(TaskCalendarEvent(input, it, TaskCalendarEventKind.PLAN)) }.orEmpty()
                    val due = input.deadline?.let { timestamp ->
                        val d = Instant.ofEpochMilli(timestamp).atZone(ZoneId.of(zone)).toLocalDate()
                        listOf(TaskCalendarEvent(input, LocalDate(d.year, d.monthValue, d.dayOfMonth), TaskCalendarEventKind.DEADLINE))
                    }.orEmpty()
                    plan + due
                }.sortedWith(compareBy<TaskCalendarEvent> { it.day.daysSince2000 }.thenBy { it.task.deadline ?: Long.MAX_VALUE })
                val actual = TaskCalendarEvents.from(inputs)
                assertEquals(zone, expected, actual)
                val grouped = actual.groupBy { it.day }
                actual.map { it.day }.distinct().forEach { date ->
                    assertEquals(actual.filter { it.day == date }, grouped[date])
                }
            }
        } finally {
            TimeZone.setDefault(previous)
        }
    }

    @Test fun equalDeadlineTiesRetainInputAndPlanBeforeDeadlineOrdering() {
        val first = task.copy(id = TaskId("first"), plannedDate = day, deadline = deadline)
        val second = first.copy(id = TaskId("second"))
        val events = TaskCalendarEvents.from(listOf(first, second))
        assertEquals(listOf(first, first, second, second), events.map { it.task })
        assertEquals(listOf(TaskCalendarEventKind.PLAN, TaskCalendarEventKind.DEADLINE,
            TaskCalendarEventKind.PLAN, TaskCalendarEventKind.DEADLINE), events.map { it.kind })
    }
}
