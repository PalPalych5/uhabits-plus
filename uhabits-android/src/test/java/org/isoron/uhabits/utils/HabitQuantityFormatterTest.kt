package org.isoron.uhabits.utils

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class HabitQuantityFormatterTest {
    private val ru = Locale.forLanguageTag("ru-RU")

    @Test fun shortFocusKeepsMinutesAndLowerBoundRoundsDown() {
        assertEquals("0,06 мин", HabitQuantityFormatter.format(0.062, "мин", true, ru).toString())
        assertEquals("0,99 мин", HabitQuantityFormatter.format(0.9999, "мин", true, ru).toString())
        assertEquals("0 мин", HabitQuantityFormatter.format(0.001, "мин", true, ru).toString())
    }

    @Test fun waterUsesLocalizedLitersWithoutChangingQuantity() {
        assertEquals("2 л", HabitQuantityFormatter.format(2000.0, "мл", locale = ru).toString())
        assertEquals("1,8 л", HabitQuantityFormatter.format(1800.0, "мл", locale = ru).toString())
        assertEquals("999 мл", HabitQuantityFormatter.format(999.0, "мл", locale = ru).toString())
    }

    @Test fun explicitZeroAndRealisticMinutesStayReadable() {
        assertEquals("0 мин", HabitQuantityFormatter.format(0.0, "мин", locale = ru).toString())
        assertEquals("34,5 мин", HabitQuantityFormatter.format(34.5, "мин", locale = ru).toString())
        assertEquals("0.06 min", HabitQuantityFormatter.format(0.062, "min", true, Locale.US).toString())
    }
}
