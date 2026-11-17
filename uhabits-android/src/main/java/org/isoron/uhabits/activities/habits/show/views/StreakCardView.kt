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
import org.isoron.uhabits.activities.common.views.CompactPopupMenu
import org.isoron.uhabits.compose.UHabitComposeColors
import org.isoron.uhabits.compose.UHabitComposeTheme
import org.isoron.uhabits.core.ui.screens.habits.show.views.StreakCardState
import org.isoron.uhabits.core.ui.screens.habits.show.views.StreakPeriod

class StreakCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var cardState by mutableStateOf<StreakCardState?>(null)
    private var selectedPeriod by mutableStateOf(StreakPeriod.ALL)
    private val periodOptions = listOf(
        StreakPeriod.WEEK to R.string.reports_tab_week,
        StreakPeriod.MONTH to R.string.reports_tab_month,
        StreakPeriod.ALL to R.string.reports_tab_all
    )
    private var composeColors by mutableStateOf(resolveComposeColors())

    private fun resolveComposeColors(): UHabitComposeColors {
        val palette = MainTabsThemeBridge.resolve(context)
        return UHabitComposeColors.fromPalette(
            palette,
            cardCornerRadiusDp = palette.habitsCardCornerRadius,
            showCardBorders = palette.showHabitCardBorders
        )
    }

    fun updateColors(colors: UHabitComposeColors = resolveComposeColors()) {
        composeColors = colors
    }

    init {
        val composeView = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                UHabitComposeTheme(composeColors) {
                    val current = cardState
                    if (current != null) {
                        val activeLabelRes = periodOptions.firstOrNull { it.first == selectedPeriod }?.second
                            ?: R.string.reports_tab_all
                        HabitStreakSummary(
                            state = current,
                            selectedPeriod = selectedPeriod,
                            periodLabel = context.getString(activeLabelRes),
                            onSelectPeriod = { anchorRect ->
                                CompactPopupMenu.show(
                                    anchor = this@StreakCardView,
                                    anchorRectOnScreen = anchorRect,
                                    entries = periodOptions.mapIndexed { index, (period, labelRes) ->
                                        CompactPopupMenu.Entry.Item(
                                            id = index,
                                            title = context.getString(labelRes),
                                            selected = period == selectedPeriod
                                        )
                                    },
                                    onItemClick = { index ->
                                        periodOptions.getOrNull(index)?.first?.let { selectedPeriod = it }
                                    }
                                )
                            }
                        )
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
