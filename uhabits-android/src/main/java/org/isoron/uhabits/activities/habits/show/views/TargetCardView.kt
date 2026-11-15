/*
 * Copyright (C) 2016-2026 Álinson Santos Xavier <git@axavier.org> and contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.isoron.uhabits.activities.habits.show.views

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.common.theme.MainTabsThemeBridge
import org.isoron.uhabits.activities.habits.show.FocusProgressPresentation
import org.isoron.uhabits.compose.UHabitComposeColors
import org.isoron.uhabits.compose.UHabitComposeTheme
import org.isoron.uhabits.core.models.projection.GoalPeriodAchievement
import org.isoron.uhabits.core.ui.screens.habits.show.views.TargetCardState

class TargetCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var targetState by mutableStateOf<TargetCardState?>(null)
    private var focusProgress by mutableStateOf<FocusProgressPresentation?>(null)
    private var goalAchievementState by mutableStateOf<GoalPeriodAchievement?>(null)
    private var isUnavailable by mutableStateOf(false)
    private var hasShortTermTargets = false

    init {
        val composeView = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val palette = MainTabsThemeBridge.resolve(context)
                val colors = UHabitComposeColors.fromPalette(palette, cardCornerRadiusDp = palette.statisticsCardCornerRadius, showCardBorders = palette.showHabitCardBorders)
                UHabitComposeTheme(colors) {
                    val ts = targetState
                    if (ts != null) {
                        HabitTargetProgress(
                            progress = focusProgress,
                            state = ts,
                            unavailable = isUnavailable,
                            goalAchievement = goalAchievementState
                        )
                    }
                }
            }
        }
        addView(composeView)
    }

    fun setFocusProgress(progress: FocusProgressPresentation?, unavailable: Boolean = false) {
        this.focusProgress = progress
        this.isUnavailable = unavailable
    }

    fun setGoalAchievement(achievement: GoalPeriodAchievement?) {
        this.goalAchievementState = achievement
    }

    fun setState(state: TargetCardState) {
        this.targetState = state
        val indices = state.intervals.indices.filter { state.intervals[it] <= 7 }
        hasShortTermTargets = indices.isNotEmpty()
    }

    companion object {
        fun intervalToLabel(resources: android.content.res.Resources, interval: Int): String = when (interval) {
            1 -> resources.getString(R.string.today)
            7 -> resources.getString(R.string.week)
            30 -> resources.getString(R.string.month)
            91 -> resources.getString(R.string.quarter)
            else -> resources.getString(R.string.year)
        }
    }
}
