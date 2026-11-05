package org.isoron.platform.time

import java.util.Calendar
import java.util.Date
import java.util.GregorianCalendar
import java.util.TimeZone

object DateUtils {
    const val SECOND_LENGTH: Long = 1000
    const val MINUTE_LENGTH: Long = 60 * SECOND_LENGTH
    const val HOUR_LENGTH: Long = 60 * MINUTE_LENGTH
    const val DAY_LENGTH: Long = 24 * HOUR_LENGTH

    private var fixedLocalTime: Long? = null

    @JvmStatic
    fun setFixedLocalTime(value: Long?) {
        fixedLocalTime = value
    }

    @JvmStatic
    var fixedTimeZone: TimeZone? = null
        private set

    fun setFixedTimeZone(value: TimeZone?) {
        fixedTimeZone = value
    }

    fun applyTimezone(
        localTimestamp: Long,
        tz: TimeZone = fixedTimeZone ?: TimeZone.getDefault()
    ): Long {
        return localTimestamp - tz.getOffset(localTimestamp - tz.getOffset(localTimestamp))
    }

    fun removeTimezone(
        timestamp: Long,
        tz: TimeZone = fixedTimeZone ?: TimeZone.getDefault()
    ): Long {
        return timestamp + tz.getOffset(timestamp)
    }

    fun getLocalTime(
        tz: TimeZone = fixedTimeZone ?: TimeZone.getDefault(),
        utcTimeInMillis: Long? = null
    ): Long {
        fixedLocalTime?.let { return it }
        val now = utcTimeInMillis ?: Date().time
        return now + tz.getOffset(now)
    }

    fun getStartOfDay(timestamp: Long): Long = (timestamp / DAY_LENGTH) * DAY_LENGTH

    fun getStartOfDayWithOffset(
        timestamp: Long,
        hourOffset: Int,
        minuteOffset: Int
    ): Long {
        val offset = hourOffset * HOUR_LENGTH + minuteOffset * MINUTE_LENGTH
        return getStartOfDay(timestamp - offset)
    }

    fun getStartOfTomorrowWithOffset(
        hourOffset: Int,
        minuteOffset: Int,
        tz: TimeZone = fixedTimeZone ?: TimeZone.getDefault()
    ): Long = getUpcomingTimeInMillis(hourOffset, minuteOffset, null, tz)

    fun millisecondsUntilTomorrowWithOffset(
        hourOffset: Int,
        minuteOffset: Int,
        tz: TimeZone = fixedTimeZone ?: TimeZone.getDefault()
    ): Long {
        return getStartOfTomorrowWithOffset(hourOffset, minuteOffset, tz) -
            applyTimezone(getLocalTime(tz), tz)
    }

    fun getUpcomingTimeInMillis(
        hour: Int,
        minute: Int,
        days: BooleanArray? = null,
        tz: TimeZone = fixedTimeZone ?: TimeZone.getDefault()
    ): Long {
        if (days != null && days.none { it }) return 0L
        val localTime = getLocalTime(tz)
        val startOfToday = getStartOfDay(localTime)
        val today = LocalDate.fromUnixTime(startOfToday)

        for (dayOffset in 0..7) {
            val candidateStartOfDay = startOfToday + dayOffset * DAY_LENGTH
            if (days != null) {
                val candidateDate = today.plus(dayOffset)
                val weekdayIndex = (candidateDate.dayOfWeek.daysSinceSunday + 1) % 7
                if (!days[weekdayIndex]) continue
            }

            val calendar = GregorianCalendar(TimeZone.getTimeZone("GMT"))
            calendar.timeInMillis = candidateStartOfDay
            calendar.set(Calendar.HOUR_OF_DAY, hour)
            calendar.set(Calendar.MINUTE, minute)
            calendar.set(Calendar.SECOND, 0)
            val time = calendar.timeInMillis

            if (dayOffset == 0 && localTime >= time) {
                continue
            }

            return applyTimezone(time, tz)
        }

        return 0L
    }

    fun getUpcomingTimeInMillis(
        hour: Int,
        minute: Int,
        tz: TimeZone
    ): Long = getUpcomingTimeInMillis(hour, minute, null, tz)
}
