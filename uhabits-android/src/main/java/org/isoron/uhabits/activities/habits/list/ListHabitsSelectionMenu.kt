/*
 * Copyright (C) 2016-2025 Álinson Santos Xavier <git@axavier.org>
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

package org.isoron.uhabits.activities.habits.list

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import me.tatarka.inject.annotations.Inject
import org.isoron.platform.time.getToday
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.habits.list.views.HabitCardListAdapter
import org.isoron.uhabits.activities.habits.list.views.HabitCardListController
import org.isoron.uhabits.core.commands.CommandRunner
import org.isoron.uhabits.core.preferences.Preferences
import org.isoron.uhabits.core.ui.NotificationTray
import org.isoron.uhabits.core.ui.screens.habits.list.ListHabitsSelectionMenuBehavior
import org.isoron.uhabits.inject.ActivityContext
import org.isoron.uhabits.inject.ActivityScope

@Inject
@ActivityScope
class ListHabitsSelectionMenu(
    @ActivityContext context: Context,
    private val listAdapter: HabitCardListAdapter,
    var commandRunner: CommandRunner,
    private val prefs: Preferences,
    private val behavior: ListHabitsSelectionMenuBehavior,
    private val listController: Lazy<HabitCardListController>,
    private val notificationTray: NotificationTray
) {

    // Revision observed by the Compose toolbar when selection or its eligibility changes.
    var selectionRevision by mutableIntStateOf(0)
        private set

    val selectedCount get() = listAdapter.selected.size
    fun canEdit() = behavior.canEdit()
    fun canArchive() = behavior.canArchive()
    fun canUnarchive() = behavior.canUnarchive()
    fun canNotify() = prefs.isDeveloper

    val activity = (context as AppCompatActivity)

    var onSelectionModeChanged: (Boolean) -> Unit = {}

    fun onSelectionStart() {
        selectionRevision++
        onSelectionModeChanged(true)
    }

    fun onSelectionChange() {
        selectionRevision++
    }

    fun onSelectionFinish() {
        selectionRevision++
        onSelectionModeChanged(false)
    }

    fun cancelSelection() {
        listController.value.onSelectionFinished()
    }

    fun onActionItemClicked(itemId: Int): Boolean {
        when (itemId) {
            R.id.action_edit_habit -> {
                behavior.onEditHabits()
                return true
            }

            R.id.action_archive_habit -> {
                behavior.onArchiveHabits()
                return true
            }

            R.id.action_unarchive_habit -> {
                behavior.onUnarchiveHabits()
                return true
            }

            R.id.action_delete -> {
                behavior.onDeleteHabits()
                return true
            }

            R.id.action_color -> {
                behavior.onChangeColor()
                return true
            }

            R.id.action_notify -> {
                for (h in listAdapter.selected)
                    notificationTray.show(h, getToday(), 0)
                return true
            }

            else -> return false
        }
    }
}
