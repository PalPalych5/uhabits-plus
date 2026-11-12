/*
 * Copyright (C) 2016-2026 Álinson Santos Xavier <git@axavier.org> and contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.isoron.uhabits.activities.habits.list.views

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import org.isoron.uhabits.activities.common.theme.MainTabsThemeBridge
import org.isoron.uhabits.compose.UHabitComposeColors
import org.isoron.uhabits.compose.UHabitComposeTheme

class EmptyListView(context: Context) : FrameLayout(context) {
    private val palette get() = MainTabsThemeBridge.resolve(context)
    private var currentMode by mutableStateOf(HabitListEmptyMode.EMPTY)

    // Legacy compatibility views preserved for ABI safety
    var textTextView: TextView = TextView(context)
    var iconTextView: TextView = TextView(context)

    init {
        val composeView = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
            setContent {
                val colors = UHabitComposeColors.fromPalette(palette)
                UHabitComposeTheme(colors = colors) {
                    HabitListEmptyState(mode = currentMode, colors = colors)
                }
            }
        }
        addView(composeView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        visibility = View.GONE
    }

    fun showDone() {
        currentMode = HabitListEmptyMode.ALL_DONE
        visibility = View.VISIBLE
    }

    fun showEmpty() {
        currentMode = HabitListEmptyMode.EMPTY
        visibility = View.VISIBLE
    }

    fun showNotFound() {
        currentMode = HabitListEmptyMode.SEARCH_NOT_FOUND
        visibility = View.VISIBLE
    }

    fun hide() {
        visibility = View.GONE
    }
}
