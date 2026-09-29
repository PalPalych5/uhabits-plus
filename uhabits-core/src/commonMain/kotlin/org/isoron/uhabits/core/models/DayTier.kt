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

enum class DayTierScope {
    MINIMUM,
    UP_TO_NORMAL,
    UP_TO_IDEAL,
    ALL;

    fun includes(tier: DayTier): Boolean = when (this) {
        MINIMUM -> tier == DayTier.MINIMUM
        UP_TO_NORMAL -> tier == DayTier.MINIMUM || tier == DayTier.NORMAL
        UP_TO_IDEAL -> tier == DayTier.MINIMUM || tier == DayTier.NORMAL || tier == DayTier.IDEAL
        ALL -> true
    }

    val matchingTiers: Set<DayTier>
        get() = when (this) {
            MINIMUM -> setOf(DayTier.MINIMUM)
            UP_TO_NORMAL -> setOf(DayTier.MINIMUM, DayTier.NORMAL)
            UP_TO_IDEAL -> setOf(DayTier.MINIMUM, DayTier.NORMAL, DayTier.IDEAL)
            ALL -> DayTier.entries.toSet()
        }

    companion object {
        fun fromString(value: String?): DayTierScope =
            entries.firstOrNull { it.name == value } ?: MINIMUM
    }
}
