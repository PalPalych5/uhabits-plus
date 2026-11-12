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
import org.isoron.uhabits.core.ui.screens.habits.show.views.BarCardPresenter
import org.isoron.uhabits.core.ui.screens.habits.show.views.BarCardState

class BarCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var cardState by mutableStateOf<BarCardState?>(null)
    private var presenter: BarCardPresenter? = null
    private val numericalLabels = resources.getStringArray(R.array.strengthIntervalNames)
    private val boolLabels = resources.getStringArray(R.array.strengthIntervalNamesWithoutDay)

    init {
        val composeView = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val palette = MainTabsThemeBridge.resolve(context)
                val colors = UHabitComposeColors.fromPalette(palette, cardCornerRadiusDp = palette.statisticsCardCornerRadius, showCardBorders = palette.showHabitCardBorders)
                UHabitComposeTheme(colors) {
                    val current = cardState
                    if (current != null) {
                        val labels = if (current.isNumerical) numericalLabels else boolLabels
                        val position = if (current.isNumerical) current.numericalSpinnerPosition else current.boolSpinnerPosition
                        HabitBarHistorySection(
                            state = current,
                            periodLabel = labels.getOrElse(position) { "" },
                            onSelectPeriod = {
                                CompactPopupMenu.show(
                                    anchor = this@BarCardView,
                                    entries = labels.mapIndexed { index, label ->
                                        CompactPopupMenu.Entry.Item(
                                            id = index,
                                            title = label,
                                            selected = index == position
                                        )
                                    },
                                    onItemClick = { pos ->
                                        if (current.isNumerical) presenter?.onNumericalSpinnerPosition(pos)
                                        else presenter?.onBoolSpinnerPosition(pos)
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

    fun setState(state: BarCardState) {
        this.cardState = state
    }

    fun setListener(presenter: BarCardPresenter) {
        this.presenter = presenter
    }
}
