package org.isoron.uhabits.core.models

import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.TruncateField
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EntryNumericalScalingTest {

    @Test
    fun testValidNumericalScaling() {
        assertEquals(0, Entry.toScaledValue(0.0))
        assertEquals(1000, Entry.toScaledValue(1.0))
        assertEquals(15500, Entry.toScaledValue(15.5))
        assertEquals(Int.MAX_VALUE, Entry.toScaledValue(Entry.MAX_NUMERICAL_VALUE))
    }

    @Test
    fun testRoundToIntTieBehaviorMatchesLegacy() {
        // Double.roundToInt() rounds half towards positive infinity.
        // Banker's rounding (kotlin.math.round) rounded half-to-even: 2.5 -> 2, 4.5 -> 4.
        // Legacy (newValue * 1000).roundToInt() gives:
        // 0.0005 * 1000.0 = 0.5 -> 1
        assertEquals(1, Entry.toScaledValue(0.0005))
        // 0.0025 * 1000.0 = 2.5 -> 3
        assertEquals(3, Entry.toScaledValue(0.0025))
        // 0.0045 * 1000.0 = 4.5 -> 5
        assertEquals(5, Entry.toScaledValue(0.0045))
        // 1.5555 * 1000.0 = 1555.5 -> 1556
        assertEquals(1556, Entry.toScaledValue(1.5555))
    }

    @Test
    fun testSentinelConversions() {
        assertEquals(Entry.UNKNOWN, Entry.toScaledValue(Entry.UNKNOWN.toDouble() / 1000.0))
        assertEquals(Entry.SKIP, Entry.toScaledValue(Entry.SKIP.toDouble() / 1000.0))
    }

    @Test
    fun testRejectsNegativeValues() {
        assertFailsWith<IllegalArgumentException> {
            Entry.toScaledValue(-0.002)
        }
        assertFailsWith<IllegalArgumentException> {
            Entry.toScaledValue(-1.0)
        }
        assertFailsWith<IllegalArgumentException> {
            Entry.toScaledValue(-100.0)
        }
    }

    @Test
    fun testRejectsNonFiniteValues() {
        assertFailsWith<IllegalArgumentException> {
            Entry.toScaledValue(Double.NaN)
        }
        assertFailsWith<IllegalArgumentException> {
            Entry.toScaledValue(Double.POSITIVE_INFINITY)
        }
        assertFailsWith<IllegalArgumentException> {
            Entry.toScaledValue(Double.NEGATIVE_INFINITY)
        }
    }

    @Test
    fun testRejectsOutOfRangeValues() {
        // Just above Int.MAX_VALUE / 1000.0
        assertFailsWith<IllegalArgumentException> {
            Entry.toScaledValue(2_147_483.648)
        }
        // Historical audit reproduction: 2,148,000.0
        assertFailsWith<IllegalArgumentException> {
            Entry.toScaledValue(2_148_000.0)
        }
        assertFailsWith<IllegalArgumentException> {
            Entry.toScaledValue(1e10)
        }
        assertFailsWith<IllegalArgumentException> {
            Entry.toScaledValue(1e19)
        }
    }

    @Test
    fun testGroupedSumPreservesLegacyTotalsWithoutSilentClamping() {
        val date1 = LocalDate(2026, 10, 1)
        val date2 = LocalDate(2026, 10, 2)
        val list = listOf(
            Entry(date1, 1_500_000_000),
            Entry(date2, 1_000_000_000)
        )
        // Grouped by month: legacy total must NOT silently clamp to Entry.MAX_SCALED_VALUE (2147483647).
        val grouped = list.groupedSum(truncateField = TruncateField.MONTH, isNumerical = true)
        assertEquals(1, grouped.size)
        assertEquals((1_500_000_000 + 1_000_000_000), grouped[0].value)
    }
}
