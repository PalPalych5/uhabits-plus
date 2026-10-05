/*
 * Copyright (C) 2016-2026 Álinson Santos Xavier <git@axavier.org> and contributors
 *
 * This file is part of Loop Habit Tracker.
 *
 * Loop Habit Tracker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * Loop Habit Tracker is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.isoron.uhabits.activities.main

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.common.theme.MainTabsThemeBridge

/** Global navigation for experimental wide workspaces; Tasks owns its unified tree sidebar. */
class MainWorkspaceSidebar(context: Context) : ScrollView(context) {
    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(24), dp(12), dp(16))
    }
    var onDestination: (MainDestination) -> Unit = {}

    init {
        isFillViewport = true
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(this) { sidebar, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            val cutout = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            sidebar.setPadding(0, maxOf(bars.top, cutout.top), 0, maxOf(bars.bottom, cutout.bottom))
            insets
        }
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun showSelection(current: MainDestination) {
        val palette = MainTabsThemeBridge.resolve(context)
        setBackgroundColor(palette.surface)
        content.removeAllViews()
        content.addView(TextView(context).apply {
            text = "UHabit"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(palette.onSurface)
            setPadding(dp(12), 0, dp(12), dp(24))
        })
        val destinations = listOf(
            Triple(MainDestination.HABITS, R.string.habits_title, R.drawable.ic_nav_habits),
            Triple(MainDestination.TASKS, R.string.tasks_title, R.drawable.ic_nav_tasks),
            Triple(MainDestination.STATISTICS, R.string.reports_title, R.drawable.ic_nav_reports),
            Triple(MainDestination.SETTINGS, R.string.settings, R.drawable.ic_nav_settings)
        )
        destinations.forEach { (destination, title, icon) ->
            val selected = current.bottomItemDestination == destination
            val color = if (selected) palette.accent else palette.onSurfaceVariant
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(52)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                val value = android.util.TypedValue()
                context.theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
                setBackgroundResource(value.resourceId)
                isSelected = selected
                isFocusable = true
                contentDescription = context.getString(title)
                setOnClickListener { onDestination(destination) }
            }
            row.addView(ImageView(context).apply {
                setImageResource(icon)
                setColorFilter(color)
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(24), dp(24)))
            row.addView(TextView(context).apply {
                text = context.getString(title)
                textSize = 15f
                setTextColor(color)
                setTypeface(typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
                setPadding(dp(16), 0, 0, 0)
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            content.addView(row, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
