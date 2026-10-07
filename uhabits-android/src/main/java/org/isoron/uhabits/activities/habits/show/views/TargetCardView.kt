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
import android.content.res.Resources
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.view.View
import androidx.appcompat.app.AlertDialog
import java.text.NumberFormat
import org.isoron.uhabits.activities.habits.show.FocusProgressPresentation
import org.isoron.uhabits.core.models.projection.HabitGoalStatus
import org.isoron.platform.gui.toInt
import org.isoron.uhabits.R
import org.isoron.uhabits.core.ui.screens.habits.show.views.TargetCardState
import org.isoron.uhabits.databinding.ShowHabitTargetBinding
import org.isoron.uhabits.utils.StyledResources

class TargetCardView(context: Context, attrs: AttributeSet) : LinearLayout(context, attrs) {
    private val binding = ShowHabitTargetBinding.inflate(LayoutInflater.from(context), this)
    private val sres = StyledResources(context)

    fun setFocusProgress(progress: FocusProgressPresentation?, unavailable: Boolean = false) {
        val visible = progress != null || unavailable
        binding.focusProgress.visibility = if (visible) View.VISIBLE else View.GONE
        binding.targetChart.visibility = if (visible) View.GONE else View.VISIBLE
        if (!visible) return
        binding.title.setText(R.string.focus_credit_progress)
        if (progress == null) {
            binding.focusValue.setText(R.string.focus_credit_unavailable)
            binding.focusGoal.visibility = View.GONE
            binding.focusCoverage.visibility = View.GONE
            binding.focusBreakdown.visibility = View.GONE
            return
        }
        binding.focusGoal.visibility = View.VISIBLE
        binding.focusBreakdown.visibility = View.VISIBLE
        val format = NumberFormat.getNumberInstance().apply { maximumFractionDigits = 3 }
        fun amount(value: Long) = "${format.format(value / 1000.0)} ${progress.unit}"
        val skipped = progress.status == HabitGoalStatus.SKIPPED || progress.status == HabitGoalStatus.EXCLUDED
        val value = progress.total?.let(::amount) ?: resources.getString(
            if (skipped) R.string.focus_credit_day_excluded else R.string.focus_credit_unknown
        )
        binding.focusValue.text = if (progress.lowerBound) "≥ $value" else value
        val target = "${if (progress.atMost) "≤" else "≥"} ${format.format(progress.target)} ${progress.unit}"
        binding.focusGoal.text = resources.getString(R.string.focus_credit_goal, target)
        binding.focusCoverage.visibility = if (skipped || progress.lowerBound) View.VISIBLE else View.GONE
        binding.focusCoverage.setText(if (skipped) R.string.focus_credit_skipped else R.string.focus_credit_incomplete)
        binding.focusBreakdown.setOnClickListener {
            val message = buildString {
                append(resources.getString(R.string.focus_credit_manual)).append("\n").append(amount(progress.manual))
                append("\n\n").append(resources.getString(R.string.focus_credit_focus)).append("\n").append(amount(progress.focus))
                if (progress.excludedFocus != 0L) {
                    append("\n\n").append(resources.getString(R.string.focus_credit_excluded)).append("\n").append(amount(progress.excludedFocus))
                }
                if (progress.lowerBound || progress.total == null) {
                    append("\n\n").append(resources.getString(R.string.focus_credit_incomplete))
                }
                if (skipped) append("\n\n").append(resources.getString(R.string.focus_credit_skipped))
            }
            AlertDialog.Builder(context).setTitle(R.string.focus_credit_breakdown)
                .setMessage(message).setPositiveButton(android.R.string.ok, null).show()
        }
    }

    fun setState(state: TargetCardState) {
        binding.title.setText(R.string.target)
        val androidColor = state.theme.color(state.color).toInt()
        binding.targetChart.setValues(state.values)
        binding.targetChart.setTargets(state.targets)
        binding.targetChart.setLabels(state.intervals.map { intervalToLabel(resources, it) })
        binding.title.setTextColor(sres.getColor(R.attr.contrast100))
        binding.targetChart.setColor(androidColor)
        postInvalidate()
    }

    companion object {
        fun intervalToLabel(resources: Resources, interval: Int) = when (interval) {
            1 -> resources.getString(R.string.today)
            7 -> resources.getString(R.string.week)
            30 -> resources.getString(R.string.month)
            91 -> resources.getString(R.string.quarter)
            else -> resources.getString(R.string.year)
        }
    }
}
