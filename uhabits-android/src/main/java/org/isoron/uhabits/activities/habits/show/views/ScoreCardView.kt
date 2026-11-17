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
import org.isoron.uhabits.core.ui.screens.habits.show.views.ScoreCardPresenter
import org.isoron.uhabits.core.ui.screens.habits.show.views.ScoreCardState

class ScoreCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var cardState by mutableStateOf<ScoreCardState?>(null)
    private var presenter: ScoreCardPresenter? = null
    private val labels = resources.getStringArray(R.array.strengthIntervalNames)
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
                        HabitStatisticsSection(
                            state = current,
                            periodLabel = labels.getOrElse(current.spinnerPosition) { "" },
                            onSelectPeriod = { anchorRect ->
                                CompactPopupMenu.show(
                                    anchor = this@ScoreCardView,
                                    anchorRectOnScreen = anchorRect,
                                    entries = labels.mapIndexed { index, label ->
                                        CompactPopupMenu.Entry.Item(
                                            id = index,
                                            title = label,
                                            selected = index == current.spinnerPosition
                                        )
                                    },
                                    onItemClick = { pos -> presenter?.onSpinnerPosition(pos) }
                                )
                            }
                        )
                    }
                }
            }
        }
        addView(composeView)
    }

    fun setState(state: ScoreCardState) {
        this.cardState = state
    }

    fun setListener(presenter: ScoreCardPresenter) {
        this.presenter = presenter
    }
}
