/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.activities.habits.show

import org.isoron.uhabits.core.commands.CommandRunner
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitList
import org.isoron.uhabits.core.models.ModelObservable
import org.isoron.uhabits.core.preferences.Preferences
import org.isoron.uhabits.core.ui.screens.habits.show.ShowHabitPresenter

sealed class HabitResolutionResult {
    data class Rebound(
        val habit: Habit,
        val presenter: ShowHabitPresenter,
        val habitChanged: Boolean
    ) : HabitResolutionResult()

    data object HabitDeleted : HabitResolutionResult()
    data object SessionMismatched : HabitResolutionResult()
}

class ShowHabitHabitBinding(
    private val commandRunner: CommandRunner,
    private val habitList: HabitList,
    private val preferences: Preferences,
    private val screen: ShowHabitPresenter.Screen
) {
    var habit: Habit? = null
        private set

    var presenter: ShowHabitPresenter? = null
        private set

    fun initialize(
        initialHabit: Habit,
        listener: ModelObservable.Listener? = null,
        isResumed: Boolean = false
    ) {
        habit = initialHabit
        if (isResumed && listener != null) {
            initialHabit.observable.addListener(listener)
        }
        presenter = ShowHabitPresenter(
            habit = initialHabit,
            habitList = habitList,
            preferences = preferences,
            screen = screen,
            commandRunner = commandRunner
        )
    }

    fun resolve(
        isDatasetRebuilding: Boolean = false,
        currentSessionDatasetId: String? = null,
        initialDatasetId: String? = null,
        listener: ModelObservable.Listener? = null,
        isResumed: Boolean = false
    ): HabitResolutionResult {
        if (isDatasetRebuilding || (initialDatasetId != null && currentSessionDatasetId != null && initialDatasetId != currentSessionDatasetId)) {
            return HabitResolutionResult.SessionMismatched
        }

        val current = habit ?: return HabitResolutionResult.HabitDeleted
        val freshHabit = current.id?.let { habitList.getById(it) }
            ?: current.uuid?.let { habitList.getByUUID(it) }

        if (freshHabit == null) {
            return HabitResolutionResult.HabitDeleted
        }

        val habitChanged = (freshHabit !== current)
        if (habitChanged) {
            if (listener != null) {
                current.observable.removeListener(listener)
                if (isResumed) {
                    freshHabit.observable.addListener(listener)
                }
            }
            habit = freshHabit
            presenter = ShowHabitPresenter(
                habit = freshHabit,
                habitList = habitList,
                preferences = preferences,
                screen = screen,
                commandRunner = commandRunner
            )
        }
        return HabitResolutionResult.Rebound(
            habit = freshHabit,
            presenter = presenter!!,
            habitChanged = habitChanged
        )
    }

    fun detach(listener: ModelObservable.Listener? = null) {
        if (listener != null) {
            habit?.observable?.removeListener(listener)
        }
    }
}
