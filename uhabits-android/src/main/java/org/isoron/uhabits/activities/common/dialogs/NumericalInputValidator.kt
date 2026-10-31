package org.isoron.uhabits.activities.common.dialogs

import org.isoron.uhabits.core.models.Entry
import java.text.NumberFormat
import java.text.ParsePosition

object NumericalInputValidator {

    sealed class ValidationResult {
        data class Valid(val value: Double) : ValidationResult()
        object InvalidFormat : ValidationResult()
        object OutOfRange : ValidationResult()
    }

    /**
     * Parses and validates a user input string from NumberDialog.
     * Empty string returns Valid with Entry.UNKNOWN (scaled to double: -0.001) for clearing.
     */
    fun validateEntryInput(
        input: String,
        numberFormat: NumberFormat = NumberFormat.getInstance()
    ): ValidationResult {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) {
            return ValidationResult.Valid(Entry.UNKNOWN.toDouble() / 1000.0)
        }

        val parsed = parseNumber(trimmed, numberFormat) ?: return ValidationResult.InvalidFormat

        if (!parsed.isFinite()) return ValidationResult.InvalidFormat

        // Sentinel pass-through if entered exactly (e.g. via direct value or tests)
        if (parsed == Entry.UNKNOWN.toDouble() / 1000.0 || parsed == Entry.SKIP.toDouble() / 1000.0) {
            return ValidationResult.Valid(parsed)
        }

        if (parsed < 0.0 || parsed > Entry.MAX_NUMERICAL_VALUE) {
            return ValidationResult.OutOfRange
        }

        return ValidationResult.Valid(parsed)
    }

    /**
     * Validates habit target value (e.g. from EditHabitActivity).
     * Must be positive (> 0.0) and finite.
     * Preserves legacy compatibility: does not impose MAX_NUMERICAL_VALUE restriction
     * on habit targets, which are stored as unscaled Doubles.
     */
    fun validateTargetValue(
        input: String,
        numberFormat: NumberFormat = NumberFormat.getInstance()
    ): ValidationResult {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return ValidationResult.InvalidFormat

        val parsed = parseNumber(trimmed, numberFormat) ?: return ValidationResult.InvalidFormat

        if (!parsed.isFinite()) return ValidationResult.InvalidFormat

        if (parsed <= 0.0) {
            return ValidationResult.OutOfRange
        }

        return ValidationResult.Valid(parsed)
    }

    private fun parseNumber(text: String, numberFormat: NumberFormat): Double? {
        val format = (numberFormat.clone() as NumberFormat).apply { isGroupingUsed = false }
        val position = ParsePosition(0)
        val number = format.parse(text, position)
        if (number != null && position.index == text.length) {
            return number.toDouble()
        }
        // Fallback: support dot or comma regardless of locale
        val normalized = text.replace(',', '.')
        return normalized.toDoubleOrNull()
    }
}
