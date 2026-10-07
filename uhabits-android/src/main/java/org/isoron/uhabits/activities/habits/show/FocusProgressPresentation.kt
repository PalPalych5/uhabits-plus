package org.isoron.uhabits.activities.habits.show

import org.isoron.uhabits.core.models.projection.EffectiveHabitGoal
import org.isoron.uhabits.core.models.projection.HabitGoalStatus

/** Presentation keeps missing coverage distinct from a measured zero. No quantity calculation here. */
data class FocusProgressPresentation(
    val total: Long?,
    val lowerBound: Boolean,
    val manual: Long,
    val focus: Long,
    val excludedFocus: Long,
    val status: HabitGoalStatus,
    val unit: String,
    val target: Double,
    val atMost: Boolean,
) {
    companion object {
        fun from(goal: EffectiveHabitGoal) = FocusProgressPresentation(
            total = goal.value.totalValue,
            lowerBound = goal.value.totalValue != null && !goal.value.hasCompleteCoverage,
            manual = goal.value.manualValue,
            focus = goal.value.contributionValue,
            excludedFocus = goal.value.excludedContributionValue,
            status = goal.status,
            unit = goal.goal.unit,
            target = goal.goal.targetValue,
            atMost = goal.goal.targetType == org.isoron.uhabits.core.models.NumericalHabitType.AT_MOST,
        )
    }
}
