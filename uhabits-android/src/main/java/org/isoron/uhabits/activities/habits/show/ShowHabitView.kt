/*
 * Copyright (C) 2016-2026 Álinson Santos Xavier <git@axavier.org> and contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.isoron.uhabits.activities.habits.show

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowInsetsControllerCompat
import org.isoron.platform.gui.toInt
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.common.theme.MainTabsThemeBridge
import org.isoron.uhabits.activities.habits.show.timer.PomodoroAlertIssue
import org.isoron.uhabits.activities.habits.show.timer.PomodoroCompletionNotifier
import org.isoron.uhabits.activities.habits.show.timer.TimerSessionManager
import org.isoron.uhabits.activities.habits.show.views.TimerCardView
import org.isoron.uhabits.activities.settings.SettingsThemePalette
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.isMinuteUnit
import org.isoron.uhabits.core.ui.screens.habits.show.ShowHabitPresenter
import org.isoron.uhabits.core.ui.screens.habits.show.ShowHabitState
import org.isoron.uhabits.databinding.ShowHabitBinding
import org.isoron.uhabits.utils.applyBottomInset
import org.isoron.uhabits.utils.applyToolbarInsets

data class ShowHabitSurfaceSpec(
    val canvasColor: Int,
    val toolbarColor: Int,
    val statusBarColor: Int,
    val navigationBarColor: Int,
    val dividerColor: Int,
    val showToolbarDivider: Boolean,
    val iconColor: Int,
    val isLightSystemBars: Boolean
)

fun resolveShowHabitSurfaceSpec(
    palette: SettingsThemePalette,
    isClassicSectionLayout: Boolean
): ShowHabitSurfaceSpec {
    val classicCanvas = if (palette.isPureBlack) Color.BLACK else palette.surface
    val canvasColor = if (isClassicSectionLayout) classicCanvas else palette.background
    return ShowHabitSurfaceSpec(
        canvasColor = canvasColor,
        toolbarColor = canvasColor,
        statusBarColor = canvasColor,
        navigationBarColor = canvasColor,
        dividerColor = palette.divider,
        showToolbarDivider = isClassicSectionLayout,
        iconColor = palette.onSurface,
        isLightSystemBars = !palette.isDark
    )
}

class ShowHabitView(context: Context) : FrameLayout(context) {
    private val binding = ShowHabitBinding.inflate(LayoutInflater.from(context))
    private var habitUnit: String = ""
    private var isTimerEnabled: Boolean = false
    private var isNumerical: Boolean = false
    private var currentFocusProgress: FocusProgressPresentation? = null

    var toolbarForegroundColor: Int = Color.WHITE
        private set
    private var currentTitleColor: Int = Color.WHITE

    init {
        binding.toolbar.applyToolbarInsets()
        addView(binding.root)
    }

    private fun setupCohesiveToolbar(data: ShowHabitState) {
        val activity = context as? AppCompatActivity
        val app = context.applicationContext as? org.isoron.uhabits.HabitsApplication
        val isClassic = app?.component?.preferences?.isClassicSectionLayout ?: false
        val palette = MainTabsThemeBridge.resolve(context)
        val surfaceSpec = resolveShowHabitSurfaceSpec(palette, isClassic)

        val iconColor = surfaceSpec.iconColor
        val titleColor = surfaceSpec.iconColor

        toolbarForegroundColor = iconColor
        currentTitleColor = titleColor

        binding.container.setBackgroundColor(surfaceSpec.canvasColor)
        binding.scrollView.setBackgroundColor(surfaceSpec.canvasColor)
        binding.toolbarDivider.setBackgroundColor(surfaceSpec.dividerColor)
        binding.toolbarDivider.visibility = if (surfaceSpec.showToolbarDivider) VISIBLE else GONE

        binding.toolbar.elevation = 0f
        binding.toolbar.title = data.title
        binding.toolbar.setBackgroundColor(surfaceSpec.toolbarColor)
        binding.toolbar.setTitleTextColor(titleColor)

        if (activity != null) {
            activity.window.statusBarColor = surfaceSpec.statusBarColor
            activity.window.navigationBarColor = surfaceSpec.navigationBarColor
            activity.setSupportActionBar(binding.toolbar)
            activity.supportActionBar?.setDisplayHomeAsUpEnabled(true)
            activity.supportActionBar?.setHomeAsUpIndicator(R.drawable.ic_settings_back)
            val insetsController = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
            insetsController.isAppearanceLightStatusBars = surfaceSpec.isLightSystemBars
            insetsController.isAppearanceLightNavigationBars = surfaceSpec.isLightSystemBars
        }
        binding.toolbar.setTitleTextColor(currentTitleColor)
        binding.toolbar.navigationIcon?.setTint(iconColor)
        binding.toolbar.overflowIcon?.setTint(iconColor)
        for (i in 0 until binding.toolbar.menu.size()) {
            val item = binding.toolbar.menu.getItem(i)
            item.icon?.setTint(iconColor)
            (item.actionView as? ImageView)?.imageTintList = ColorStateList.valueOf(iconColor)
        }
    }

    fun harmonizeToolbar(onSurfaceColor: Int) {
        binding.toolbar.setTitleTextColor(currentTitleColor)
        binding.toolbar.navigationIcon?.setTint(toolbarForegroundColor)
        binding.toolbar.overflowIcon?.setTint(toolbarForegroundColor)
        for (i in 0 until binding.toolbar.menu.size()) {
            val item = binding.toolbar.menu.getItem(i)
            item.icon?.setTint(toolbarForegroundColor)
            (item.actionView as? ImageView)?.imageTintList = ColorStateList.valueOf(toolbarForegroundColor)
        }
    }

    fun setState(data: ShowHabitState) {
        isNumerical = data.isNumerical
        setupCohesiveToolbar(data)

        binding.subtitleCard.setHabitState(
            state = data,
            focusProgress = currentFocusProgress
        )

        // Maintain state on legacy views for backward-compatibility and tests,
        // while concealing duplicate frames in favor of the unified Editorial Header.
        binding.overviewCard.setState(data.overview)
        binding.notesCard.setState(data.notes)
        binding.targetCard.setState(data.target)
        binding.targetCard.setGoalAchievement(data.goalAchievement)
        binding.overviewCard.visibility = if (!data.isNumerical) VISIBLE else GONE
        binding.notesCard.visibility = GONE
        binding.targetCard.visibility = if (data.isNumerical) VISIBLE else GONE

        binding.streakCard.setState(data.streaks)
        binding.scoreCard.setState(data.scores)
        binding.frequencyCard.setState(data.frequency)
        binding.historyCard.setState(data.history)
        binding.historyCard.visibility = VISIBLE
        binding.barCard.setState(data.bar)
        binding.scoreCard.visibility = if (data.scoreUnavailable) GONE else VISIBLE
        binding.streakCard.visibility = if (data.streakUnavailable) GONE else VISIBLE
        binding.barCard.visibility = if (data.quantityUnavailable) GONE else VISIBLE
        binding.frequencyCard.visibility = if (data.quantityUnavailable) GONE else VISIBLE

        // Suppress redundant effective-readincomplete notice in normal operation; only show when quantity is genuinely unavailable.
        val showNotice = data.quantityUnavailable
        binding.effectiveReadNotice.visibility = if (showNotice) VISIBLE else GONE
        val provenance = when {
            data.quantityUnavailable -> org.isoron.uhabits.R.string.effective_read_quantity_unavailable
            data.hasIncompleteCoverage -> org.isoron.uhabits.R.string.effective_read_incomplete
            else -> org.isoron.uhabits.R.string.effective_read_provenance
        }
        binding.effectiveReadNotice.setText(when {
            data.quantityUnavailable -> R.string.effective_read_summary_unavailable
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

        val app = context.applicationContext as? org.isoron.uhabits.HabitsApplication
        val isClassic = app?.component?.preferences?.isClassicSectionLayout ?: false
        val density = resources.displayMetrics.density
        val sideMargin = if (isClassic) 0 else (16 * density).toInt()
        val bottomMargin = if (isClassic) 0 else (10 * density).toInt()
        val bottomPad = (32 * density).toInt()
        val topPad = if (isClassic) 0 else (12 * density).toInt()
        binding.linearLayout.setPadding(0, topPad, 0, bottomPad)
        for (i in 0 until binding.linearLayout.childCount) {
            val child = binding.linearLayout.getChildAt(i)
            (child.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.apply {
                leftMargin = sideMargin
                rightMargin = sideMargin
                marginStart = sideMargin
                marginEnd = sideMargin
                topMargin = 0
                this.bottomMargin = bottomMargin
            }
        }

        binding.linearLayout.applyBottomInset()
    }

    /** Integrity failure must not leave a previous/manual-only result visible. Editing stays manual. */
    fun setEffectiveReadUnavailable() {
        listOf(
            binding.overviewCard, binding.targetCard, binding.scoreCard, binding.streakCard,
            binding.historyCard, binding.barCard, binding.frequencyCard
        ).forEach { it.visibility = GONE }
        binding.effectiveReadNotice.visibility = VISIBLE
        binding.effectiveReadNotice.setText(org.isoron.uhabits.R.string.effective_read_unavailable)
        binding.effectiveReadNotice.setOnClickListener(null)
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

    val timerCard: TimerCardView get() = binding.timerCard

    fun updateHabit(habit: Habit) {
        habitUnit = habit.unit
        isTimerEnabled = habit.timerEnabled
        binding.timerCard.updateHabit(habit)
    }

    fun refreshTimerAlertHealth() = binding.timerCard.refreshAlertHealth()

    fun resetCalendarEditMode() = binding.historyCard.resetEditMode()

    fun setFocusProgress(progress: FocusProgressPresentation?, unavailable: Boolean = false) {
        this.currentFocusProgress = progress
        binding.targetCard.setFocusProgress(progress, unavailable)
        binding.targetCard.visibility = if (isNumerical) VISIBLE else GONE
        binding.subtitleCard.setFocusProgress(progress)
    }
}
