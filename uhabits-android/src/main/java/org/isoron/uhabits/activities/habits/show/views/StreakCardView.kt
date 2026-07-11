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
package org.isoron.uhabits.activities.habits.show.views

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import org.isoron.platform.gui.toInt
import org.isoron.uhabits.R
import org.isoron.uhabits.core.ui.screens.habits.show.views.StreakCardState
import org.isoron.uhabits.databinding.ShowHabitStreakBinding
import org.isoron.uhabits.utils.StyledResources

class StreakCardView(context: Context, attrs: AttributeSet) : LinearLayout(context, attrs) {
    private val binding = ShowHabitStreakBinding.inflate(LayoutInflater.from(context), this)
    private val sres = StyledResources(context)

    fun setState(state: StreakCardState) {
        val androidColor = state.theme.color(state.color).toInt()
        binding.title.setTextColor(sres.getColor(R.attr.contrast100))
        binding.latestStreakLabel.setTextColor(sres.getColor(R.attr.contrast60))
        binding.bestStreaksLabel.setTextColor(sres.getColor(R.attr.contrast60))
        val sharedMaxLength = (listOfNotNull(state.latestStreak) + state.bestStreaks)
            .maxOfOrNull { it.length }
            ?.toLong()
        val hasLatestStreak = state.latestStreak != null
        val hasBestStreaks = state.bestStreaks.isNotEmpty()
        binding.latestStreakLabel.visibility = if (hasLatestStreak) View.VISIBLE else View.GONE
        binding.latestStreakChart.visibility = if (hasLatestStreak) View.VISIBLE else View.GONE
        binding.bestStreaksLabel.visibility = if (hasBestStreaks) View.VISIBLE else View.GONE
        binding.streakChart.visibility = if (hasBestStreaks) View.VISIBLE else View.GONE
        binding.latestStreakChart.setColor(androidColor)
        binding.latestStreakChart.setMaxLengthOverride(sharedMaxLength)
        binding.latestStreakChart.setStreaks(listOfNotNull(state.latestStreak))
        binding.streakChart.setColor(androidColor)
        binding.streakChart.setMaxLengthOverride(sharedMaxLength)
        binding.streakChart.setStreaks(state.bestStreaks)
        postInvalidate()
    }
}
