package org.isoron.uhabits.core.models

import org.isoron.platform.time.LocalDate

data class HabitGoal(
    val effectiveDate: LocalDate,
    val frequency: Frequency,
    val targetType: NumericalHabitType,
    val targetValue: Double,
    val unit: String
)
