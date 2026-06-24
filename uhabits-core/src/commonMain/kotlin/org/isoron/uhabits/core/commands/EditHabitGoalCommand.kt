package org.isoron.uhabits.core.commands

import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitGoal
import org.isoron.uhabits.core.models.HabitList
import org.isoron.uhabits.core.models.HabitNotFoundException

enum class GoalApplyScope {
    FROM_DATE,
    ENTIRE_HISTORY
}

data class EditHabitGoalCommand(
    val habitList: HabitList,
    val habitId: Long,
    val modified: Habit,
    val applyScope: GoalApplyScope,
    val effectiveDate: LocalDate
) : Command {
    override fun run() {
        val habit = habitList.getById(habitId) ?: throw HabitNotFoundException()

        val newGoal = HabitGoal(
            effectiveDate = effectiveDate,
            frequency = modified.frequency,
            targetType = modified.targetType,
            targetValue = modified.targetValue,
            unit = modified.unit
        )

        val oldHistory = habit.normalizedGoalHistory()
        val newHistory = when (applyScope) {
            GoalApplyScope.ENTIRE_HISTORY -> mutableListOf(newGoal)
            GoalApplyScope.FROM_DATE -> {
                val kept = oldHistory.filter { it.effectiveDate.isOlderThan(effectiveDate) }.toMutableList()
                kept.removeAll { it.effectiveDate == effectiveDate }
                kept.add(newGoal)
                kept
            }
        }

        habit.copyFrom(modified)
        habit.goalHistory = newHistory.sortedBy { it.effectiveDate }.toMutableList()
        habitList.update(habit)
        habit.observable.notifyListeners()
        habit.recompute()
        habitList.resort()
    }
}
