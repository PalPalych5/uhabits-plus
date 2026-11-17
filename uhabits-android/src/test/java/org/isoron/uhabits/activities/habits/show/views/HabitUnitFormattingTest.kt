/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.activities.habits.show.views

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class HabitUnitFormattingTest {

    @Test
    fun formatsTargetQuantityWithExplicitUnit() {
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale("ru", "RU"))
            val formatted = formatTargetQuantity(2000.0, "мл")
            assertTrue("Expected formatted quantity to end with 'мл', was '$formatted'", formatted.endsWith("мл"))
            assertTrue("Expected formatted quantity to contain '2' and '000', was '$formatted'", formatted.contains("2") && formatted.contains("000"))
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    @Test
    fun formatsTargetQuantityWithMinutesUnit() {
        val formatted = formatTargetQuantity(15.0, "мин")
        assertEquals("15 мин", formatted)
    }

    @Test
    fun formatsTargetQuantityWithoutUnit() {
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            val formatted = formatTargetQuantity(500.0, "")
            assertEquals("500", formatted)
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    @Test
    fun formatsFractionalTargetQuantity() {
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            val formatted = formatTargetQuantity(2.5, "l")
            assertEquals("2.5 l", formatted)
        } finally {
            Locale.setDefault(originalLocale)
        }
    }
}
