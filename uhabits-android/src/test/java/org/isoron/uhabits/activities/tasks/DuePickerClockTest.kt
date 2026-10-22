package org.isoron.uhabits.activities.tasks

import org.junit.Assert.*
import org.junit.Test
import org.isoron.uhabits.core.userTasks.due.TaskDue
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class DuePickerClockTest {
    @Test fun calendarDateUsesWidgetZoneAfterDeviceZoneChange() {
        val date = LocalDate.of(2026, 10, 22)
        for (zone in listOf(ZoneId.of("UTC"), ZoneId.of("Asia/Novosibirsk"), ZoneId.of("America/Los_Angeles"))) {
            val millis = DuePickerClock.calendarMillis(date, zone)
            assertEquals(date, Instant.ofEpochMilli(millis).atZone(zone).toLocalDate())
        }
    }

    @Test fun midnightAndEighteenAreExplicitLocalTimes() {
        val zone = ZoneId.of("Asia/Novosibirsk")
        val date = LocalDate.of(2026, 10, 22)
        for (hour in listOf(0, 18)) {
            val due = DuePickerClock.resolve(date, LocalTime.of(hour, 0), zone)!!
            val actual = Instant.ofEpochMilli(due.epochMillis).atZone(zone)
            assertEquals(date, actual.toLocalDate())
            assertEquals(LocalTime.of(hour, 0), actual.toLocalTime())
            assertEquals(zone.id, due.originZoneId)
        }
    }

    @Test fun dstGapIsRejectedRatherThanSilentlyMovingTheTime() {
        assertNull(DuePickerClock.resolve(LocalDate.of(2026, 3, 29), LocalTime.of(2, 30), ZoneId.of("Europe/Berlin")))
    }

    @Test fun newDstOverlapUsesEarlierOccurrence() {
        val due = DuePickerClock.resolve(LocalDate.of(2026, 10, 25), LocalTime.of(2, 30), ZoneId.of("Europe/Berlin"))!!
        assertEquals(Instant.parse("2026-10-25T00:30:00Z").toEpochMilli(), due.epochMillis)
    }

    @Test fun editingExistingOverlapRetainsLaterOffset() {
        val original = TaskDue.Timed.of(Instant.parse("2026-10-25T01:30:00Z").toEpochMilli(), "Europe/Berlin")
        val due = DuePickerClock.resolve(LocalDate.of(2026, 10, 25), LocalTime.of(2, 45), ZoneId.of("Europe/Berlin"), original)!!
        assertEquals(Instant.parse("2026-10-25T01:45:00Z").toEpochMilli(), due.epochMillis)
    }

    @Test fun changingZoneChangesLocalResolutionButKeepsOrigin() {
        val date = LocalDate.of(2026, 10, 22)
        val time = LocalTime.MIDNIGHT
        val utc = DuePickerClock.resolve(date, time, ZoneId.of("UTC"))!!
        val local = DuePickerClock.resolve(date, time, ZoneId.of("Asia/Novosibirsk"))!!
        assertEquals(7 * 60 * 60 * 1000L, utc.epochMillis - local.epochMillis)
        assertEquals("Asia/Novosibirsk", local.originZoneId)
    }
}
