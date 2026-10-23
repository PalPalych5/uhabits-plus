package org.isoron.uhabits.activities.tasks

import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.core.userTasks.due.TaskDue
import org.isoron.uhabits.core.userTasks.due.TaskDueDate
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

class TaskDueDisplayFormatterTest {
    @Test
    fun compactLabelsUseSelectedLocaleAndTwentyFourHourTime() {
        val day = TaskDue.Day(TaskDueDate.from(LocalDate(2026, 10, 23)))
        val time = LocalDateTime.of(2026, 10, 23, 18, 0)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val timed = TaskDue.Timed.local(time, ZoneId.systemDefault().id)
        assertEquals("23 Oct", TaskDueDisplayFormatter.format(day, Locale.US))
        assertEquals("23 Oct, 18:00", TaskDueDisplayFormatter.format(timed, Locale.US))
        assertEquals("23 окт.", TaskDueDisplayFormatter.format(day, Locale.forLanguageTag("ru-RU")))
        assertEquals("23 окт., 18:00", TaskDueDisplayFormatter.format(timed, Locale.forLanguageTag("ru-RU")))
        assertEquals("Задать срок", TaskDueDisplayFormatter.format(TaskDue.None, Locale.US))
    }
}
