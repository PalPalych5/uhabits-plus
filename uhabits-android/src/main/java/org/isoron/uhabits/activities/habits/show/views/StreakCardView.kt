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
import org.isoron.uhabits.activities.common.theme.MainTabsThemeBridge
import org.isoron.uhabits.compose.UHabitComposeColors
import org.isoron.uhabits.compose.UHabitComposeTheme
import org.isoron.uhabits.core.ui.screens.habits.show.views.StreakCardState

class StreakCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var cardState by mutableStateOf<StreakCardState?>(null)

    init {
        val composeView = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val palette = MainTabsThemeBridge.resolve(context)
                val colors = UHabitComposeColors.fromPalette(palette, cardCornerRadiusDp = palette.statisticsCardCornerRadius, showCardBorders = palette.showHabitCardBorders)
                UHabitComposeTheme(colors) {
                    val current = cardState
                    if (current != null) {
                        HabitStreakSummary(state = current)
                    }
                }
            }
        }
        addView(composeView)
    }

    fun setState(state: StreakCardState) {
        this.cardState = state
    }
}
