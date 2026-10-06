package org.isoron.platform.time

import java.util.TimeZone

actual fun computeToday(hourOffset: Int, minuteOffset: Int): LocalDate {
    return localDateAt(System.currentTimeMillis(), hourOffset, minuteOffset)
}

actual fun localDateAt(epochMillis: Long, hourOffset: Int, minuteOffset: Int): LocalDate {
    validateDayOffset(hourOffset, minuteOffset)
    val tz = TimeZone.getDefault()
    val localMillis = epochMillis + tz.getOffset(epochMillis)
    val offsetMillis = hourOffset * 3600000L + minuteOffset * 60000L
    val adjustedMillis = localMillis - offsetMillis
    val daysSinceEpoch = Math.floorDiv(adjustedMillis, 86400000L)
    val daysSince2000 = (daysSinceEpoch - 10957).toInt()
    return LocalDate(daysSince2000)
}

actual fun startOfLocalDay(date: LocalDate, hourOffset: Int, minuteOffset: Int): Long {
    validateDayOffset(hourOffset, minuteOffset)
    val localBoundary = java.time.LocalDate.of(date.year, date.month, date.day)
        .atTime(hourOffset, minuteOffset)
    val zone = TimeZone.getDefault().toZoneId()
    // If the clock skips the requested boundary, the habit date begins at the jump.
    if (zone.rules.getValidOffsets(localBoundary).isEmpty()) {
        return zone.rules.getTransition(localBoundary).instant.toEpochMilli()
    }
    return localBoundary.atZone(zone).toInstant().toEpochMilli()
}
