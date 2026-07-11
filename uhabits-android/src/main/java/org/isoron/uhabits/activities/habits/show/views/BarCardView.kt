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
import org.isoron.platform.time.JavaLocalDateFormatter
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.common.views.CompactPopupMenu.Entry.Item
import org.isoron.uhabits.core.ui.screens.habits.show.views.BarCardPresenter
import org.isoron.uhabits.core.ui.screens.habits.show.views.BarCardState
import org.isoron.uhabits.core.ui.views.BarChart
import org.isoron.uhabits.databinding.ShowHabitBarBinding
import org.isoron.uhabits.utils.StyledResources
import java.util.Locale

class BarCardView(context: Context, attrs: AttributeSet) : LinearLayout(context, attrs) {

    private var binding = ShowHabitBarBinding.inflate(LayoutInflater.from(context), this)
    private val sres = StyledResources(context)
    private val numericalLabels = resources.getStringArray(R.array.strengthIntervalNames)
    private val boolLabels = resources.getStringArray(R.array.strengthIntervalNamesWithoutDay)
    private var selectedNumericalPosition = 0
    private var selectedBoolPosition = 0

    fun setState(state: BarCardState) {
        val androidColor = state.theme.color(state.color).toInt()
        binding.chart.view = BarChart(state.theme, JavaLocalDateFormatter(Locale.getDefault())).apply {
            series = mutableListOf(state.entries.map { it.value / 1000.0 })
            colors = mutableListOf(theme.color(state.color.paletteIndex))
            axis = state.entries.map { it.date }
        }
        binding.chart.resetDataOffset()
        binding.chart.postInvalidate()

        binding.title.setTextColor(sres.getColor(R.attr.contrast100))
        selectedNumericalPosition = state.numericalSpinnerPosition
        selectedBoolPosition = state.boolSpinnerPosition
        binding.numericalSpinner.text =
            numericalLabels.getOrElse(state.numericalSpinnerPosition) { "" }
        binding.boolSpinner.text = boolLabels.getOrElse(state.boolSpinnerPosition) { "" }
        binding.numericalSpinnerArrow.imageTintList =
            ColorStateList.valueOf(sres.getColor(R.attr.contrast80))
        binding.boolSpinnerArrow.imageTintList =
            ColorStateList.valueOf(sres.getColor(R.attr.contrast80))
        binding.numericalSpinnerContainer.visibility = VISIBLE
        binding.boolSpinnerContainer.visibility = VISIBLE
        if (state.isNumerical) {
            binding.boolSpinnerContainer.visibility = GONE
        } else {
            binding.numericalSpinnerContainer.visibility = GONE
        }
    }

    fun setListener(presenter: BarCardPresenter) {
        binding.boolSpinnerContainer.setOnClickListener {
            showPeriodSelectorPopup(
                anchor = binding.boolSpinnerContainer,
                arrow = binding.boolSpinnerArrow,
                entries = boolLabels.mapIndexed { index, label ->
                    Item(
                        id = index,
                        title = label,
                        selected = index == selectedBoolPosition,
                    )
                },
            ) { position -> presenter.onBoolSpinnerPosition(position) }
        }
        binding.numericalSpinnerContainer.setOnClickListener {
            showPeriodSelectorPopup(
                anchor = binding.numericalSpinnerContainer,
                arrow = binding.numericalSpinnerArrow,
                entries = numericalLabels.mapIndexed { index, label ->
                    Item(
                        id = index,
                        title = label,
                        selected = index == selectedNumericalPosition,
                    )
                },
            ) { position -> presenter.onNumericalSpinnerPosition(position) }
        }
    }
}
