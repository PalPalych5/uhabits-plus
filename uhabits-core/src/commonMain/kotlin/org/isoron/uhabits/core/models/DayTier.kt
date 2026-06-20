package org.isoron.uhabits.core.models

enum class DayTier {
    MINIMUM,
    NORMAL,
    IDEAL,
    OPTIONAL;

    companion object {
        fun fromString(value: String): DayTier = entries.firstOrNull { it.name == value } ?: NORMAL
    }
}
