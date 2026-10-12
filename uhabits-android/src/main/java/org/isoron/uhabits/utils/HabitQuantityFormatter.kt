package org.isoron.uhabits.utils

import org.isoron.uhabits.core.models.isMinuteUnit
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale

/** Display conversion only. Lower bounds must never round upwards into stronger evidence. */
object HabitQuantityFormatter {
    data class Amount(val number: String, val unit: String) {
        override fun toString() = "$number $unit".trim()
    }

    fun format(value: Double, unit: String, lowerBound: Boolean = false,
               locale: Locale = Locale.getDefault(), compact: Boolean = false): Amount {
        val russian = locale.language == "ru"
        val isMinute = unit.isMinuteUnit()
        val liters = unit.trim().lowercase() in setOf("мл", "ml") && value >= 1000
        val scale = if (compact && !isMinute && !liters) when {
            value >= 1e9 -> 1e9
            value >= 1e6 -> 1e6
            value >= 1e3 -> 1e3
            else -> 1.0
        } else 1.0
        val quantity = when { liters -> value / 1000; else -> value / scale }
        val suffix = when (scale) { 1e9 -> "G"; 1e6 -> "M"; 1e3 -> "k"; else -> "" }
        val displayUnit = when {
            liters -> if (russian) "л" else "L"
            else -> unit
        }
        val number = NumberFormat.getNumberInstance(locale).apply {
            maximumFractionDigits = when {
                isMinute && quantity > 0 && quantity < 1 -> 2
                isMinute || scale > 1 -> 1
                liters -> 2
                else -> 3
            }
            roundingMode = if (lowerBound) RoundingMode.DOWN else RoundingMode.HALF_UP
            isGroupingUsed = quantity >= 1000
        }.format(quantity)
        return Amount(number + suffix, displayUnit)
    }
}
