/*
 * Copyright (C) 2016-2026 Álinson Santos Xavier <git@axavier.org> and contributors
 *
 * This file is part of Loop Habit Tracker.
 *
 * Loop Habit Tracker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * Loop Habit Tracker is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <http://www.gnu.org/licenses/>.
 */

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
