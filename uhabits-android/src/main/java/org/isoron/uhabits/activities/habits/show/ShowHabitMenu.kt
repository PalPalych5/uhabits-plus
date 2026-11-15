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

import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.TooltipCompat
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
        installCompactToolbarActions(menu)
        return true
    }

    private fun installCompactToolbarActions(menu: Menu) {
        val density = activity.resources.displayMetrics.density
        fun px(dp: Int) = (dp * density).toInt()
        val outValue = TypedValue()
        activity.theme.resolveAttribute(androidx.appcompat.R.attr.selectableItemBackgroundBorderless, outValue, true)
        val rippleResId = outValue.resourceId

        fun configureButton(
            menuItemId: Int,
            iconRes: Int,
            titleRes: Int,
            padStartDp: Int,
            padEndDp: Int,
        ) {
            val item = menu.findItem(menuItemId) ?: return
            val btn = AppCompatImageButton(activity).apply {
                id = menuItemId
                setImageResource(iconRes)
                scaleType = ImageView.ScaleType.CENTER
                contentDescription = activity.getString(titleRes)
                TooltipCompat.setTooltipText(this, activity.getString(titleRes))
                if (rippleResId != 0) setBackgroundResource(rippleResId)
                layoutParams = ViewGroup.LayoutParams(px(42), px(48))
                setPaddingRelative(px(padStartDp), 0, px(padEndDp), 0)
                setOnClickListener { performAction(menuItemId) }
            }
            item.actionView = btn
        }

        configureButton(R.id.action_edit_habit, R.drawable.ic_toolbar_edit, R.string.edit, padStartDp = 14, padEndDp = 4)
        configureButton(R.id.action_more_habit, R.drawable.ic_toolbar_more_vertical, R.string.today_show_more, padStartDp = 4, padEndDp = 14)
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
                    destructive = true,
                ),
                CompactPopupMenu.Entry.Divider,
                CompactPopupMenu.Entry.Item(
                    id = R.id.action_delete,
                    title = activity.getString(R.string.delete),
                    destructive = true,
                ),
            ),
            maxWidthDp = 250,
            horizontalMarginDp = if (preferences.isClassicSectionLayout) 8 else 16,
            verticalOffsetDp = if (preferences.isClassicSectionLayout) 4 else 16
        ) { id -> performAction(id) }
    }
}
