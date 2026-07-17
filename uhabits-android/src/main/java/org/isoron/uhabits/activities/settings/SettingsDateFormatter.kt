package org.isoron.uhabits.activities.settings

import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.toGregorianCalendar
import java.text.DateFormat
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone

internal object SettingsDateFormatter {
    private val utc: TimeZone = TimeZone.getTimeZone("UTC")

    fun formatDate(date: LocalDate, locale: Locale): String =
        DateFormat.getDateInstance(DateFormat.LONG, locale).apply {
            timeZone = utc
        }.format(date.toGregorianCalendar().time)

    fun formatHour(hour: Int, locale: Locale): String {
        val calendar = GregorianCalendar(utc, locale).apply {
            clear()
            set(2026, Calendar.JANUARY, 1, hour, 0)
        }
        return DateFormat.getTimeInstance(DateFormat.SHORT, locale).apply {
            timeZone = utc
        }.format(calendar.time)
    }
}
