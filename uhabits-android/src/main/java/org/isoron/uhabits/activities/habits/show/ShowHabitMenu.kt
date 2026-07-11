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

package org.isoron.uhabits.activities.habits.show

import android.view.Menu
import android.view.MenuItem
import android.view.View
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.common.views.CompactPopupMenu
import org.isoron.uhabits.core.preferences.Preferences
import org.isoron.uhabits.core.ui.screens.habits.show.ShowHabitMenuPresenter

class ShowHabitMenu(
    val activity: ShowHabitActivity,
    val presenter: ShowHabitMenuPresenter,
    val preferences: Preferences
) {
    fun onCreateOptionsMenu(menu: Menu): Boolean {
        activity.menuInflater.inflate(R.menu.show_habit, menu)
        return true
    }

    fun onOptionsItemSelected(item: MenuItem): Boolean {
        return performAction(item.itemId)
    }

    private fun performAction(itemId: Int): Boolean {
        when (itemId) {
            R.id.action_edit_habit -> {
                presenter.onEditHabit()
                return true
            }
            R.id.action_more_habit -> {
                showOverflowMenu(activity.findViewById(itemId) ?: activity.window.decorView)
                return true
            }
            R.id.action_archive_habit -> {
                presenter.onArchiveHabits()
                return true
            }

            R.id.action_unarchive_habit -> {
                presenter.onUnarchiveHabits()
                return true
            }
            R.id.action_delete -> {
                presenter.onDeleteHabit()
                return true
            }
            R.id.action_soft_reset_statistics -> {
                presenter.onSoftResetStatistics()
                return true
            }
            R.id.action_hard_reset_statistics -> {
                presenter.onHardResetStatistics()
                return true
            }
            R.id.action_randomize -> {
                presenter.onRandomize()
                return true
            }
            R.id.export -> {
                presenter.onExportCSV()
                return true
            }
        }
        return false
    }

    private fun showOverflowMenu(anchor: View) {
        val archiveItem = if (presenter.canArchive()) {
            CompactPopupMenu.Entry.Item(
                id = R.id.action_archive_habit,
                title = activity.getString(R.string.archive),
            )
        } else {
            CompactPopupMenu.Entry.Item(
                id = R.id.action_unarchive_habit,
                title = activity.getString(R.string.unarchive),
                visible = presenter.canUnarchive(),
            )
        }
        CompactPopupMenu.show(
            anchor = anchor,
            entries = listOf(
                CompactPopupMenu.Entry.Item(
                    id = R.id.export,
                    title = activity.getString(R.string.export),
                ),
                archiveItem,
                CompactPopupMenu.Entry.Divider,
                CompactPopupMenu.Entry.Item(
                    id = R.id.action_soft_reset_statistics,
                    title = activity.getString(R.string.start_statistics_over),
                ),
                CompactPopupMenu.Entry.Item(
                    id = R.id.action_hard_reset_statistics,
                    title = activity.getString(R.string.reset_statistics),
                ),
                CompactPopupMenu.Entry.Item(
                    id = R.id.action_randomize,
                    title = "Randomize",
                    visible = preferences.isDeveloper,
                ),
                CompactPopupMenu.Entry.Divider,
                CompactPopupMenu.Entry.Item(
                    id = R.id.action_delete,
                    title = activity.getString(R.string.delete),
                    destructive = true,
                ),
            ),
            maxWidthDp = 250
        ) { id -> performAction(id) }
    }
}
