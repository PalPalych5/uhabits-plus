package org.isoron.uhabits.activities.common.dialogs

import org.isoron.uhabits.core.models.Entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.NumberFormat
import java.util.Locale

class NumericalInputValidatorTest {

    private val usLocale = Locale.US
    private val ruLocale = Locale.forLanguageTag("ru-RU")
    private val deLocale = Locale.GERMANY

    @Test
    fun testValidEntryInputs() {
        val usFormat = NumberFormat.getInstance(usLocale)
        val res1 = NumericalInputValidator.validateEntryInput("0", usFormat)
        assertTrue(res1 is NumericalInputValidator.ValidationResult.Valid)
        assertEquals(0.0, (res1 as NumericalInputValidator.ValidationResult.Valid).value, 0.0001)

        val res2 = NumericalInputValidator.validateEntryInput("15.5", usFormat)
        assertTrue(res2 is NumericalInputValidator.ValidationResult.Valid)
        assertEquals(15.5, (res2 as NumericalInputValidator.ValidationResult.Valid).value, 0.0001)

        val resMax = NumericalInputValidator.validateEntryInput("2147483.647", usFormat)
        assertTrue(resMax is NumericalInputValidator.ValidationResult.Valid)
        assertEquals(Entry.MAX_NUMERICAL_VALUE, (resMax as NumericalInputValidator.ValidationResult.Valid).value, 0.0001)
    }

    @Test
    fun testEmptyInputYieldsUnknownSentinel() {
        val res = NumericalInputValidator.validateEntryInput("")
        assertTrue(res is NumericalInputValidator.ValidationResult.Valid)
        assertEquals(Entry.UNKNOWN.toDouble() / 1000.0, (res as NumericalInputValidator.ValidationResult.Valid).value, 0.0001)

        val resSpaces = NumericalInputValidator.validateEntryInput("   ")
        assertTrue(resSpaces is NumericalInputValidator.ValidationResult.Valid)
        assertEquals(Entry.UNKNOWN.toDouble() / 1000.0, (resSpaces as NumericalInputValidator.ValidationResult.Valid).value, 0.0001)
    }

    @Test
    fun testLocaleDecimalSeparatorFallback() {
        val ruFormat = NumberFormat.getInstance(ruLocale)
        // Russian locale natively uses comma
        val resComma = NumericalInputValidator.validateEntryInput("15,5", ruFormat)
        assertTrue(resComma is NumericalInputValidator.ValidationResult.Valid)
        assertEquals(15.5, (resComma as NumericalInputValidator.ValidationResult.Valid).value, 0.0001)

        // Russian locale with user typing dot should succeed via fallback
        val resDot = NumericalInputValidator.validateEntryInput("15.5", ruFormat)
        assertTrue(resDot is NumericalInputValidator.ValidationResult.Valid)
        assertEquals(15.5, (resDot as NumericalInputValidator.ValidationResult.Valid).value, 0.0001)
    }

    @Test
    fun testLocaleAmbiguityResolvedWithoutGroupingInterference() {
        // In German locale, '.' is normally grouping and ',' is decimal.
        // With grouping disabled, "1.500" must parse as decimal 1.5, NOT 1500.0
        val deFormat = NumberFormat.getInstance(deLocale)
        val resDeDot = NumericalInputValidator.validateEntryInput("1.500", deFormat)
        assertTrue("Expected Valid for '1.500' in German locale", resDeDot is NumericalInputValidator.ValidationResult.Valid)
        assertEquals(1.5, (resDeDot as NumericalInputValidator.ValidationResult.Valid).value, 0.0001)

        val resDeComma = NumericalInputValidator.validateEntryInput("1,500", deFormat)
        assertTrue("Expected Valid for '1,500' in German locale", resDeComma is NumericalInputValidator.ValidationResult.Valid)
        assertEquals(1.5, (resDeComma as NumericalInputValidator.ValidationResult.Valid).value, 0.0001)

        // In US locale, ',' is normally grouping and '.' is decimal.
        // With grouping disabled, "1,500" must parse as decimal 1.5, NOT 1500.0
        val usFormat = NumberFormat.getInstance(usLocale)
        val resUsComma = NumericalInputValidator.validateEntryInput("1,500", usFormat)
        assertTrue("Expected Valid for '1,500' in US locale", resUsComma is NumericalInputValidator.ValidationResult.Valid)
        assertEquals(1.5, (resUsComma as NumericalInputValidator.ValidationResult.Valid).value, 0.0001)
    }

    @Test
    fun testRejectsInvalidFormats() {
        val invalidInputs = listOf("abc", "12..3", "12,3,4", "NaN", "Infinity", "-Infinity")
        for (input in invalidInputs) {
            val res = NumericalInputValidator.validateEntryInput(input)
            assertTrue("Expected InvalidFormat for '$input'", res is NumericalInputValidator.ValidationResult.InvalidFormat)
        }
    }

    @Test
    fun testRejectsNegativeAndOutOfRangeInputs() {
        val negativeInputs = listOf("-0.01", "-1", "-500", "-2147483")
        for (input in negativeInputs) {
            val res = NumericalInputValidator.validateEntryInput(input)
            assertTrue("Expected OutOfRange for '$input'", res is NumericalInputValidator.ValidationResult.OutOfRange)
        }

        val outOfRangeInputs = listOf("2147483.648", "2148000", "10000000", "1e15")
        for (input in outOfRangeInputs) {
            val res = NumericalInputValidator.validateEntryInput(input)
            assertTrue("Expected OutOfRange for '$input'", res is NumericalInputValidator.ValidationResult.OutOfRange)
        }
    }

    @Test
    fun testTargetValueValidationPreservesPreExistingLargeTargets() {
        // Target value must be > 0
        val zero = NumericalInputValidator.validateTargetValue("0")
        assertTrue(zero is NumericalInputValidator.ValidationResult.OutOfRange)

        val negative = NumericalInputValidator.validateTargetValue("-5")
        assertTrue(negative is NumericalInputValidator.ValidationResult.OutOfRange)

        val empty = NumericalInputValidator.validateTargetValue("")
        assertTrue(empty is NumericalInputValidator.ValidationResult.InvalidFormat)

        val invalid = NumericalInputValidator.validateTargetValue("abc")
        assertTrue(invalid is NumericalInputValidator.ValidationResult.InvalidFormat)

        val valid = NumericalInputValidator.validateTargetValue("60.0")
        assertTrue(valid is NumericalInputValidator.ValidationResult.Valid)
        assertEquals(60.0, (valid as NumericalInputValidator.ValidationResult.Valid).value, 0.0001)

        val maxValid = NumericalInputValidator.validateTargetValue("2147483.647")
        assertTrue(maxValid is NumericalInputValidator.ValidationResult.Valid)
        assertEquals(Entry.MAX_NUMERICAL_VALUE, (maxValid as NumericalInputValidator.ValidationResult.Valid).value, 0.0001)

        // Pre-existing accepted target data: targets are unscaled Doubles, so values > MAX_NUMERICAL_VALUE
        // (e.g. 10,000,000 steps or points) must remain valid and not artificially restricted.
        val largeTarget = NumericalInputValidator.validateTargetValue("10000000.0")
        assertTrue("Large target should be valid", largeTarget is NumericalInputValidator.ValidationResult.Valid)
        assertEquals(10_000_000.0, (largeTarget as NumericalInputValidator.ValidationResult.Valid).value, 0.0001)
    }
}
