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

import android.graphics.Rect
import android.view.View
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.common.views.CompactPopupMenu
import org.isoron.uhabits.core.preferences.Preferences
import org.isoron.uhabits.core.ui.screens.habits.show.ShowHabitMenuPresenter

internal class ShowHabitMenu(
    private val activity: ShowHabitActivity,
    private val presenter: ShowHabitMenuPresenter,
    private val preferences: Preferences,
) {
    fun edit() = presenter.onEditHabit()

    fun showOverflowMenu(anchor: View, anchorInWindow: Rect?) {
        val anchorOnScreen = anchorInWindow?.let { bounds ->
            val windowLocation = IntArray(2).also(anchor::getLocationInWindow)
            val screenLocation = IntArray(2).also(anchor::getLocationOnScreen)
            Rect(bounds).apply {
                offset(screenLocation[0] - windowLocation[0], screenLocation[1] - windowLocation[1])
            }
        }
        val archiveItem = if (presenter.canArchive()) {
            CompactPopupMenu.Entry.Item(
                id = R.string.archive,
                title = activity.getString(R.string.archive),
            )
        } else {
            CompactPopupMenu.Entry.Item(
                id = R.string.unarchive,
                title = activity.getString(R.string.unarchive),
                visible = presenter.canUnarchive(),
            )
        }
        CompactPopupMenu.show(
            anchor = anchor,
            entries = listOf(
                CompactPopupMenu.Entry.Item(
                    id = R.string.export,
                    title = activity.getString(R.string.export),
                ),
                archiveItem,
                CompactPopupMenu.Entry.Divider,
                CompactPopupMenu.Entry.Item(
                    id = R.string.start_statistics_over,
                    title = activity.getString(R.string.start_statistics_over),
                ),
                CompactPopupMenu.Entry.Item(
                    id = R.string.reset_statistics,
                    title = activity.getString(R.string.reset_statistics),
                    destructive = true,
                ),
                CompactPopupMenu.Entry.Divider,
                CompactPopupMenu.Entry.Item(
                    id = R.string.delete,
                    title = activity.getString(R.string.delete),
                    destructive = true,
                ),
            ),
            maxWidthDp = 250,
            horizontalMarginDp = if (preferences.isClassicSectionLayout) 8 else 16,
            verticalOffsetDp = if (preferences.isClassicSectionLayout) 4 else 16,
            anchorRectOnScreen = anchorOnScreen,
        ) { id ->
            when (id) {
                R.string.export -> presenter.onExportCSV()
                R.string.archive -> presenter.onArchiveHabits()
                R.string.unarchive -> presenter.onUnarchiveHabits()
                R.string.start_statistics_over -> presenter.onSoftResetStatistics()
                R.string.reset_statistics -> presenter.onHardResetStatistics()
                R.string.delete -> presenter.onDeleteHabit()
            }
        }
    }
}
