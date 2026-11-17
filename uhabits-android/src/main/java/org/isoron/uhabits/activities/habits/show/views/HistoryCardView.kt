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
import org.isoron.uhabits.core.ui.screens.habits.show.views.HistoryCardPresenter
import org.isoron.uhabits.core.ui.screens.habits.show.views.HistoryCardState
import org.isoron.uhabits.core.ui.views.OnDateClickedListener

class HistoryCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var cardState by mutableStateOf<HistoryCardState?>(null)
    private var presenter: HistoryCardPresenter? = null

    /**
     * Compact inline calendar is strictly read-only for visualization and scrolling.
     * Pressing "Edit" opens the expanded [org.isoron.uhabits.activities.common.dialogs.HistoryEditorDialog].
     */
    private val readOnlyDateClickListener = object : OnDateClickedListener {
        override fun onDateShortPress(date: org.isoron.platform.time.LocalDate) = Unit
        override fun onDateLongPress(date: org.isoron.platform.time.LocalDate) = Unit
    }

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
                        HabitHistoryCalendar(
                            state = current,
                            onEditClicked = { presenter?.onClickEditButton() },
                            onDateClickedListener = readOnlyDateClickListener
                        )
                    }
                }
            }
        }
        addView(composeView)
    }

    fun setState(state: HistoryCardState) {
        this.cardState = state
    }

    fun setListener(presenter: HistoryCardPresenter) {
        this.presenter = presenter
    }

    fun resetEditMode() = Unit
}
