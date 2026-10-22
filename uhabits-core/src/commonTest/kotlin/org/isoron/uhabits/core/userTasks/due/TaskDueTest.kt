/* Copyright (C) 2026 Alinson Santos Xavier and contributors. GPL-3.0-or-later. */
package org.isoron.uhabits.core.userTasks.due

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class TaskDueTest {
    @Test
    fun strictCalendarDateNeverNormalizesOrEncodesTime() {
        for (raw in listOf("2026-02-30", "2026-02-29", "1900-02-29", "2026-13-01", "2026-00-01",
            "2026-01-00", "0000-01-01", "2026-1-01", " 2026-01-01", "2026-01-01T00:00Z")) {
            assertFailsWith<IllegalArgumentException>(raw) { TaskDueDate.parse(raw) }
        }
        assertEquals("2000-02-29", TaskDueDate.parse("2000-02-29").isoDate)
        assertEquals(TaskDueDate.parse("2026-10-19"), TaskDueDate.parse("2026-10-19"))
    }

    @Test
    fun instantPreservesEveryMillisecondAndLegacyZoneUnknown() {
        for (instant in listOf(Long.MIN_VALUE, -1L, 0L, 1792396800123L, Long.MAX_VALUE)) {
            assertEquals(instant, TaskDue.Timed.legacy(instant).epochMillis)
            assertNull(TaskDue.Timed.legacy(instant).originZoneId)
            assertEquals(instant, TaskDue.Timed.local(instant, "Asia/Novosibirsk").epochMillis)
        }
        assertFailsWith<IllegalArgumentException> { TaskDue.Timed.local(0L, "") }
        assertFailsWith<IllegalArgumentException> { TaskDue.Timed.local(0L, " UTC ") }
        assertFailsWith<IllegalArgumentException> { TaskDue.Timed.local(0L, "Not/AZone") }
        assertEquals("UTC", TaskDue.Timed.local(0L, "UTC").originZoneId)
    }

    @Test
    fun absentPatchAndExplicitNoneAreDistinct() {
        val due = TaskDue.Day(TaskDueDate.parse("2026-10-19"))
        assertSame(due, applyTaskDuePatch(due, null))
        assertSame(TaskDue.None, applyTaskDuePatch(due, TaskDue.None))
    }
}
