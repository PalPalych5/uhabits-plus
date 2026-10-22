package org.isoron.uhabits.activities.tasks

import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.core.userTasks.TaskId
import org.isoron.uhabits.core.userTasks.UserTask
import org.isoron.uhabits.core.userTasks.due.TaskDue
import org.isoron.uhabits.core.userTasks.due.TaskDueDate
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

    @Test fun dayDueProducesPlanEvent() {
        val events = TaskCalendarEvents.from(listOf(task.copy(due = TaskDue.Day(TaskDueDate.from(LocalDate(2026, 10, 7))))))
        assertEquals(1, events.size)
        assertEquals(TaskCalendarEventKind.PLAN, events.single().kind)
        assertEquals(day, events.single().day)
    }

    @Test fun timedDueProducesDeadlineEvent() {
        val events = TaskCalendarEvents.from(listOf(task.copy(due = TaskDue.Timed.of(deadline))))
        assertEquals(1, events.size)
        assertEquals(TaskCalendarEventKind.DEADLINE, events.single().kind)
        assertEquals(day, events.single().day)
    }

    @Test fun undatedTaskProducesNoEvent() {
        val events = TaskCalendarEvents.from(listOf(task.copy(due = TaskDue.None)))
        assertEquals(0, events.size)
    }

    @Test fun timezoneMidnightDstAndYearBoundariesMatchIndependentOracle() {
        val previous = TimeZone.getDefault()
        try {
            for (zone in listOf("UTC", "America/Los_Angeles", "Pacific/Kiritimati", "Asia/Novosibirsk")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                val inputs = listOf("2026-01-01T00:00:00Z", "2026-03-08T09:59:59Z",
                    "2026-03-08T10:00:00Z", "2026-11-01T08:59:59Z", "2026-11-01T09:00:00Z",
                    "2026-12-31T23:59:59Z").mapIndexed { index, instant ->
                    task.copy(id = TaskId("$index"), due = TaskDue.Timed.of(Instant.parse(instant).toEpochMilli()))
                } + task.copy(id = TaskId("undated"), due = TaskDue.None) + task.copy(id = TaskId("plan"), due = TaskDue.Day(TaskDueDate.from(LocalDate(2026, 10, 7))))
                val expected = inputs.flatMap { input ->
                    when (val due = input.due) {
                        is TaskDue.None -> emptyList()
                        is TaskDue.Day -> listOf(TaskCalendarEvent(input, due.date.toLocalDate(), TaskCalendarEventKind.PLAN))
                        is TaskDue.Timed -> {
                            val d = Instant.ofEpochMilli(due.epochMillis).atZone(ZoneId.of(zone)).toLocalDate()
                            listOf(TaskCalendarEvent(input, LocalDate(d.year, d.monthValue, d.dayOfMonth), TaskCalendarEventKind.DEADLINE))
                        }
                    }
                }.sortedWith(compareBy<TaskCalendarEvent> { it.day.daysSince2000 }.thenBy { (it.task.due as? TaskDue.Timed)?.epochMillis ?: Long.MAX_VALUE })
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
}
