/*
 * Copyright (C) 2016-2026 Álinson Santos Xavier <git@axavier.org> and contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.isoron.uhabits.activities.habits.show.views

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.isoron.uhabits.activities.common.theme.MainTabsThemeBridge
import org.isoron.uhabits.compose.UHabitComposeColors
import org.isoron.uhabits.compose.UHabitComposeTheme
import org.isoron.uhabits.core.ui.screens.habits.show.views.NotesCardState

class NotesCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var cardState by mutableStateOf<NotesCardState?>(null)

    init {
        val composeView = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val palette = MainTabsThemeBridge.resolve(context)
                val colors = UHabitComposeColors.fromPalette(palette, cardCornerRadiusDp = palette.statisticsCardCornerRadius, showCardBorders = palette.showHabitCardBorders)
                UHabitComposeTheme(colors) {
                    val desc = cardState?.description
                    if (!desc.isNullOrEmpty()) {
                        HabitSectionCard {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(width = 3.dp, height = 20.dp)
                                        .clip(CircleShape)
                                        .background(Color(colors.accent))
                                )
                                Spacer(Modifier.width(10.dp))
                                BasicText(
                                    text = desc,
                                    style = TextStyle(
                                        color = Color(colors.foreground).copy(alpha = 0.9f),
                                        fontSize = 14.sp,
                                        lineHeight = 20.sp
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
        addView(composeView)
    }

    fun setState(state: NotesCardState) {
        this.cardState = state
        visibility = if (state.description.isEmpty()) GONE else VISIBLE
    }
}
