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

import android.content.Context
import android.view.LayoutInflater
import android.widget.FrameLayout
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.isMinuteUnit
import org.isoron.uhabits.activities.habits.show.timer.TimerSessionManager
import org.isoron.uhabits.activities.habits.show.timer.PomodoroAlertIssue
import org.isoron.uhabits.activities.habits.show.timer.PomodoroCompletionNotifier
import org.isoron.uhabits.core.ui.screens.habits.show.ShowHabitPresenter
import org.isoron.uhabits.core.ui.screens.habits.show.ShowHabitState
import org.isoron.uhabits.databinding.ShowHabitBinding
import org.isoron.uhabits.utils.applyBottomInset
import org.isoron.uhabits.utils.applyToolbarInsets
import org.isoron.uhabits.utils.setupToolbar
import org.isoron.platform.gui.toInt
import androidx.appcompat.app.AlertDialog
import org.isoron.uhabits.R
import android.view.View

class ShowHabitView(context: Context) : FrameLayout(context) {
    private val binding = ShowHabitBinding.inflate(LayoutInflater.from(context))
    private var habitUnit: String = ""
    private var isTimerEnabled: Boolean = false

    init {
        binding.toolbar.applyToolbarInsets()
        addView(binding.root)
    }

    fun setState(data: ShowHabitState) {
        setupToolbar(
            binding.toolbar,
            title = data.title,
            color = data.color,
            theme = data.theme,
            useHabitColor = false
        )
        binding.subtitleCard.setState(data.subtitle)
        binding.overviewCard.setState(data.overview)
        binding.notesCard.setState(data.notes)
        binding.targetCard.setState(data.target)
        binding.streakCard.setState(data.streaks)
        binding.scoreCard.setState(data.scores)
        binding.frequencyCard.setState(data.frequency)
        binding.historyCard.setState(data.history)
        binding.historyCard.visibility = VISIBLE
        binding.targetCard.visibility = if (data.isNumerical) VISIBLE else GONE
        binding.barCard.setState(data.bar)
        binding.scoreCard.visibility = if (data.scoreUnavailable) GONE else VISIBLE
        binding.streakCard.visibility = if (data.streakUnavailable) GONE else VISIBLE
        binding.barCard.visibility = if (data.quantityUnavailable) GONE else VISIBLE
        binding.frequencyCard.visibility = if (data.quantityUnavailable) GONE else VISIBLE
        // Target's Long-based current-period row remains supported when legacy Int charts are gated.
        binding.effectiveReadNotice.visibility = if (data.hasAutomaticQuantity || data.quantityUnavailable) VISIBLE else GONE
        val provenance = when {
            data.quantityUnavailable -> org.isoron.uhabits.R.string.effective_read_quantity_unavailable
            data.hasIncompleteCoverage -> org.isoron.uhabits.R.string.effective_read_incomplete
            else -> org.isoron.uhabits.R.string.effective_read_provenance
        }
        binding.effectiveReadNotice.setText(when {
            data.quantityUnavailable && !data.hasAutomaticQuantity -> R.string.effective_read_summary_unavailable
            data.hasIncompleteCoverage -> R.string.effective_read_summary_incomplete
            else -> R.string.effective_read_summary
        })
        binding.effectiveReadNotice.setOnClickListener {
            AlertDialog.Builder(context).setTitle(R.string.focus_credit_breakdown)
                .setMessage(provenance).setPositiveButton(android.R.string.ok, null).show()
        }

        binding.timerCard.setColor(data.theme.color(data.color).toInt())
        val isTimerVisible = data.isNumerical && isTimerEnabled && habitUnit.isMinuteUnit()
        binding.timerCard.visibility = if (isTimerVisible) View.VISIBLE else View.GONE

        binding.overviewCard.visibility = if (data.isNumerical) GONE else VISIBLE
        binding.linearLayout.applyBottomInset()
    }

    /** Integrity failure must not leave a previous/manual-only result visible. Editing stays manual. */
    fun setEffectiveReadUnavailable() {
        listOf(binding.overviewCard, binding.targetCard, binding.scoreCard, binding.streakCard,
            binding.historyCard, binding.barCard, binding.frequencyCard).forEach { it.visibility = GONE }
        binding.effectiveReadNotice.visibility = VISIBLE
        binding.effectiveReadNotice.setText(org.isoron.uhabits.R.string.effective_read_unavailable)
    }

    fun setListener(presenter: ShowHabitPresenter) {
        binding.scoreCard.setListener(presenter.scoreCardPresenter)
        binding.historyCard.setListener(presenter.historyCardPresenter)
        binding.barCard.setListener(presenter.barCardPresenter)
    }

    fun initTimer(
        habit: Habit,
        manager: TimerSessionManager,
        completionNotifier: PomodoroCompletionNotifier,
        requestNotificationPermission: (onReady: () -> Unit) -> Unit,
        onFixAlertIssue: (PomodoroAlertIssue) -> Unit
    ) {
        habitUnit = habit.unit
        isTimerEnabled = habit.timerEnabled
        binding.timerCard.setNotificationPermissionRequester(requestNotificationPermission)
        binding.timerCard.setAlertHealth(completionNotifier::health, onFixAlertIssue)
        binding.timerCard.setHabit(habit, manager)
    }

    fun refreshTimerAlertHealth() = binding.timerCard.refreshAlertHealth()

    fun setFocusProgress(progress: FocusProgressPresentation?, unavailable: Boolean = false) =
        binding.targetCard.setFocusProgress(progress, unavailable)
}
