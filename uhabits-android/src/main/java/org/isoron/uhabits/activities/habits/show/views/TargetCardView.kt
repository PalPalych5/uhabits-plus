/*
 * Copyright (C) 2016-2026 Álinson Santos Xavier <git@axavier.org> and contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.isoron.uhabits.activities.habits.show.views

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
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
import org.isoron.uhabits.core.models.projection.HabitGoalStatus
import org.isoron.uhabits.core.ui.screens.habits.show.views.TargetCardState
import java.text.NumberFormat

class TargetCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var targetState by mutableStateOf<TargetCardState?>(null)
    private var focusProgress by mutableStateOf<FocusProgressPresentation?>(null)
    private var isUnavailable by mutableStateOf(false)
    private var hasShortTermTargets = false
    private var showBreakdown: (() -> Unit)? = null

    val hasProgressDetails: Boolean get() = showBreakdown != null

    fun showProgressDetails() = showBreakdown?.invoke()

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
                            onShowBreakdown = showBreakdown
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
        showBreakdown = null

        if (progress != null && (progress.focus != 0L || progress.excludedFocus != 0L)) {
            showBreakdown = {
                val exactFormat = NumberFormat.getNumberInstance().apply { maximumFractionDigits = 3 }
                fun exact(value: Long) = "${exactFormat.format(value / 1000.0)} ${progress.unit}"
                val skipped = progress.status == HabitGoalStatus.SKIPPED || progress.status == HabitGoalStatus.EXCLUDED
                val message = buildString {
                    append(resources.getString(R.string.focus_credit_manual)).append("\n").append(exact(progress.manual))
                    append("\n\n").append(resources.getString(R.string.focus_credit_focus)).append("\n").append(exact(progress.focus))
                    if (progress.excludedFocus != 0L) {
                        append("\n\n").append(resources.getString(R.string.focus_credit_excluded)).append("\n").append(exact(progress.excludedFocus))
                    }
                    if (skipped) append("\n\n").append(resources.getString(R.string.focus_credit_skipped))
                }
                AlertDialog.Builder(context).setTitle(R.string.focus_credit_breakdown)
                    .setMessage(message).setPositiveButton(android.R.string.ok, null).show()
            }
        }
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
