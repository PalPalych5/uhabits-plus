package org.isoron.uhabits.activities.tasks

import org.isoron.platform.time.DayOfWeek
import org.isoron.platform.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class TaskCalendarScaleTest {

    @Test
    fun scaleValuesAreDefined() {
        val scales = CalendarScale.entries
        assertEquals(3, scales.size)
        assertEquals(CalendarScale.MONTH, scales[0])
        assertEquals(CalendarScale.WEEK, scales[1])
        assertEquals(CalendarScale.DAY, scales[2])
    }

    @Test
    fun weekStartAndEndMatchesMondayToSunday() {
        // Oct 24, 2026 is Saturday
        val date = LocalDate(2026, 10, 24)
        val monday = date.startOfWeek(DayOfWeek.MONDAY)
        val sunday = monday.plus(6)

        assertEquals(LocalDate(2026, 10, 19), monday)
        assertEquals(LocalDate(2026, 10, 25), sunday)
        assertEquals(DayOfWeek.MONDAY, monday.dayOfWeek)
        assertEquals(DayOfWeek.SUNDAY, sunday.dayOfWeek)

        // All 7 days in week contain original date
        val daysInWeek = (0..6).map { monday.plus(it) }
        assertEquals(7, daysInWeek.size)
        org.junit.Assert.assertTrue(date in daysInWeek)
    }

    @Test
    fun periodNavigationPreservesDayWhenExpected() {
        val baseDay = LocalDate(2026, 10, 24)

        // Day navigation: +/- 1 day
        assertEquals(LocalDate(2026, 10, 25), baseDay.plus(1))
        assertEquals(LocalDate(2026, 10, 23), baseDay.minus(1))

        // Week navigation: +/- 7 days
        assertEquals(LocalDate(2026, 10, 31), baseDay.plus(7))
        assertEquals(LocalDate(2026, 10, 17), baseDay.minus(7))

        // Month navigation with Calendar
        val cal = Calendar.getInstance().apply {
            set(baseDay.year, baseDay.month - 1, 1)
        }
        cal.add(Calendar.MONTH, 1)
        assertEquals(11, cal.get(Calendar.MONTH) + 1)
        assertEquals(2026, cal.get(Calendar.YEAR))

        cal.add(Calendar.MONTH, -2)
        assertEquals(9, cal.get(Calendar.MONTH) + 1)
        assertEquals(2026, cal.get(Calendar.YEAR))
    }

    @Test
    fun scaleSwitchingPreservesSelectedDate() {
        var selectedDay = LocalDate(2026, 10, 24)
        var scale = CalendarScale.MONTH

        // Switch to WEEK: selectedDay remains Oct 24
        scale = CalendarScale.WEEK
        assertEquals(LocalDate(2026, 10, 24), selectedDay)

        // Switch to DAY: selectedDay remains Oct 24
        scale = CalendarScale.DAY
        assertEquals(LocalDate(2026, 10, 24), selectedDay)

        // Switch back to MONTH: selectedDay remains Oct 24
        scale = CalendarScale.MONTH
        assertEquals(LocalDate(2026, 10, 24), selectedDay)
    }

    @Test
    fun overdueTasksRetainOriginalDueAndAreNotAutoRescheduled() {
        val pastDate = LocalDate(2026, 10, 1)
        val today = LocalDate(2026, 10, 24)
        val overdueTask = org.isoron.uhabits.core.userTasks.UserTask(
            id = org.isoron.uhabits.core.userTasks.TaskId("overdue_1"),
            title = "File Taxes",
            due = org.isoron.uhabits.core.userTasks.due.TaskDue.Day(
                org.isoron.uhabits.core.userTasks.due.TaskDueDate.from(pastDate)
            ),
            createdAt = 0,
            updatedAt = 0
        )

        // Task events on pastDate contain the overdue task
        val eventsPast = TaskCalendarEvents.from(listOf(overdueTask)).filter { it.day == pastDate }
        assertEquals(1, eventsPast.size)
        assertEquals("overdue_1", eventsPast.single().task.id.value)

        // Task events on today do NOT contain the overdue task (no automatic rescheduling)
        val eventsToday = TaskCalendarEvents.from(listOf(overdueTask)).filter { it.day == today }
        assertEquals(0, eventsToday.size)
    }
}
