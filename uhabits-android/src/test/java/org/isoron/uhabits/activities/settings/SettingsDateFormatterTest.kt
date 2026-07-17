package org.isoron.uhabits.activities.settings

import org.isoron.platform.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class SettingsDateFormatterTest {
    @Test
    fun formatsRussianDateWithoutInternalLocalDateRepresentation() {
        assertEquals(
            "29 июня 2026 г.",
            SettingsDateFormatter.formatDate(LocalDate(2026, 6, 29), Locale.forLanguageTag("ru-RU"))
        )
    }

    @Test
    fun formatsNewDayHourUsingLocale() {
        assertEquals("03:00", SettingsDateFormatter.formatHour(3, Locale.forLanguageTag("ru-RU")))
        assertEquals("3:00 AM", SettingsDateFormatter.formatHour(3, Locale.US))
    }
}
