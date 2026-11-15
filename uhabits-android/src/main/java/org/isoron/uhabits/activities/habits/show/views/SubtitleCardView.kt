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
import org.isoron.uhabits.activities.habits.show.FocusProgressPresentation
import org.isoron.uhabits.compose.UHabitComposeColors
import org.isoron.uhabits.compose.UHabitComposeTheme
import org.isoron.uhabits.core.ui.screens.habits.show.ShowHabitState
import org.isoron.uhabits.core.ui.screens.habits.show.views.NotesCardState
import org.isoron.uhabits.core.ui.screens.habits.show.views.SubtitleCardState

class SubtitleCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var subtitleState by mutableStateOf<SubtitleCardState?>(null)
    private var notesState by mutableStateOf<NotesCardState?>(null)
    private var fullHabitState by mutableStateOf<ShowHabitState?>(null)
    private var focusProgressState by mutableStateOf<FocusProgressPresentation?>(null)

    init {
        val composeView = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val palette = MainTabsThemeBridge.resolve(context)
                val colors = UHabitComposeColors.fromPalette(
                    palette,
                    cardCornerRadiusDp = palette.statisticsCardCornerRadius,
                    showCardBorders = palette.showHabitCardBorders
                )
                UHabitComposeTheme(colors) {
                    val habitState = fullHabitState
                    if (habitState != null) {
                        HabitDetailsEditorialHeader(
                            state = habitState,
                            focusProgress = focusProgressState
                        )
                    } else {
                        val currentSubtitle = subtitleState
                        if (currentSubtitle != null) {
                            HabitDetailsHeader(
                                state = currentSubtitle,
                                notes = notesState
                            )
                        }
                    }
                }
            }
        }
        addView(composeView)
    }

    fun setHabitState(
        state: ShowHabitState,
        focusProgress: FocusProgressPresentation? = null
    ) {
        this.fullHabitState = state
        this.focusProgressState = focusProgress
    }

    fun setFocusProgress(progress: FocusProgressPresentation?) {
        this.focusProgressState = progress
    }

    fun setState(state: SubtitleCardState) {
        this.subtitleState = state
    }

    fun setNotes(notes: NotesCardState) {
        this.notesState = notes
    }
}
