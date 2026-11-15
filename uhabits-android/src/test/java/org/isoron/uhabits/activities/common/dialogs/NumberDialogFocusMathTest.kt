package org.isoron.uhabits.activities.common.dialogs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NumberDialogFocusMathTest {

    @Test
    fun seedsUnifiedTotalWhenEffectiveTotalProvided() {
        val displayMillis = NumberDialog.initialDisplayMillis(
            initialManualMillis = 0,
            effectiveMillis = 10_000,
            activeFocusMillis = 25_000,
            hasContributions = true
        )
        assertEquals(10_000, displayMillis)
        assertEquals("10", NumberDialog.formatMillisForInput(displayMillis))
    }

    @Test
    fun seedsUnifiedTotalFromManualAndFocusFallbackWhenEffectiveOmitted() {
        val displayMillis = NumberDialog.initialDisplayMillis(
            initialManualMillis = 10_000,
            activeFocusMillis = 25_000,
            hasContributions = true
        )
        assertEquals(35_000, displayMillis)
        assertEquals("35", NumberDialog.formatMillisForInput(displayMillis))
    }

    @Test
    fun seedsZeroWhenNoEntriesOrEffectiveTotalExist() {
        val displayMillis = NumberDialog.initialDisplayMillis(
            initialManualMillis = 0,
            activeFocusMillis = 0,
            hasContributions = false
        )
        assertEquals(0, displayMillis)
        assertEquals("0", NumberDialog.formatMillisForInput(displayMillis))
    }

    @Test
    fun savingEditedTotalPassesDesiredTotalIncludingValuesBelowFocus() {
        val increased = NumberDialog.resolveManualSave(enteredTotalMillis = 40_000)
        assertTrue(increased is NumberDialog.CompanionFormat.SaveResolution.Success)
        assertEquals(40.0, (increased as NumberDialog.CompanionFormat.SaveResolution.Success).totalValue, 0.0001)

        val decreasedBelowFocus = NumberDialog.resolveManualSave(enteredTotalMillis = 10_000)
        assertTrue(decreasedBelowFocus is NumberDialog.CompanionFormat.SaveResolution.Success)
        assertEquals(10.0, (decreasedBelowFocus as NumberDialog.CompanionFormat.SaveResolution.Success).totalValue, 0.0001)

        val clearedToZero = NumberDialog.resolveManualSave(enteredTotalMillis = 0)
        assertTrue(clearedToZero is NumberDialog.CompanionFormat.SaveResolution.Success)
        assertEquals(0.0, (clearedToZero as NumberDialog.CompanionFormat.SaveResolution.Success).totalValue, 0.0001)
    }

    @Test
    fun fractionalFocusMillisRoundTripWithoutRoundingLoss() {
        val formatted = NumberDialog.formatMillisForInput(15_333)
        assertTrue(formatted.contains("333"))
    }
}
