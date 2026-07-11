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
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import org.isoron.platform.gui.toInt
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.common.views.CompactPopupMenu.Entry.Item
import org.isoron.uhabits.core.ui.screens.habits.show.views.ScoreCardPresenter
import org.isoron.uhabits.core.ui.screens.habits.show.views.ScoreCardState
import org.isoron.uhabits.databinding.ShowHabitScoreBinding
import org.isoron.uhabits.utils.StyledResources

class ScoreCardView(context: Context, attrs: AttributeSet) : LinearLayout(context, attrs) {
    private var binding = ShowHabitScoreBinding.inflate(LayoutInflater.from(context), this)
    private val sres = StyledResources(context)
    private val labels = resources.getStringArray(R.array.strengthIntervalNames)
    private var selectedPosition = 0

    fun setState(state: ScoreCardState) {
        val androidColor = state.theme.color(state.color).toInt()
        selectedPosition = state.spinnerPosition
        binding.title.setTextColor(sres.getColor(R.attr.contrast100))
        binding.periodSelector.text = labels.getOrElse(state.spinnerPosition) { "" }
        binding.periodSelectorArrow.imageTintList =
            ColorStateList.valueOf(sres.getColor(R.attr.contrast80))
        binding.scoreView.setScores(state.scores)
        binding.scoreView.reset()
        binding.scoreView.setBucketSize(state.bucketSize)
        binding.scoreView.setColor(androidColor)
    }

    fun setListener(presenter: ScoreCardPresenter) {
        binding.periodSelectorContainer.setOnClickListener {
            showPeriodSelectorPopup(
                anchor = binding.periodSelectorContainer,
                arrow = binding.periodSelectorArrow,
                entries = labels.mapIndexed { index, label ->
                    Item(
                        id = index,
                        title = label,
                        selected = index == selectedPosition,
                    )
                },
            ) { position -> presenter.onSpinnerPosition(position) }
        }
    }
}
