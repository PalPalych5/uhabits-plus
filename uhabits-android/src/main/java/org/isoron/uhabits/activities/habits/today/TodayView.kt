/*
 * Copyright (C) 2016-2025 Álinson Santos Xavier <git@axavier.org>
 *
 * This file is part of Loop Habit Tracker.
 *
 * Loop Habit Tracker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * Loop Habit Tracker is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.isoron.uhabits.activities.habits.today

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.isoron.platform.gui.toInt
import org.isoron.uhabits.R
import org.isoron.uhabits.core.models.HabitType
import org.isoron.uhabits.core.models.PaletteColor
import org.isoron.uhabits.core.preferences.Preferences
import org.isoron.uhabits.core.ui.screens.habits.today.TodayHabitItem
import org.isoron.uhabits.core.ui.screens.habits.today.TodayHabitStatus
import org.isoron.uhabits.core.ui.screens.habits.today.TodayScreenState
import org.isoron.uhabits.core.ui.screens.habits.today.TodaySectionState
import org.isoron.uhabits.core.ui.screens.habits.today.TodayTierProgress
import org.isoron.uhabits.core.ui.screens.habits.today.formatTodayValue
import org.isoron.uhabits.utils.InterfaceUtils
import org.isoron.uhabits.utils.StyledResources
import org.isoron.uhabits.utils.applyToolbarInsets
import org.isoron.uhabits.utils.buildToolbar
import org.isoron.uhabits.utils.currentTheme
import org.isoron.uhabits.utils.dp
import org.isoron.uhabits.utils.sres
import org.isoron.uhabits.utils.toFixedAndroidColor

class TodayView(
    private val activity: AppCompatActivity,
    context: Context,
    private val preferences: Preferences,
    private val onHabitClick: (Long) -> Unit,
    private val onRefresh: () -> Unit
) : LinearLayout(context) {
    private val toolbar = buildToolbar()
    private val content = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(dp(16f).toInt(), dp(14f).toInt(), dp(16f).toInt(), dp(24f).toInt())
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(sres.getColor(R.attr.windowBackgroundColor))
        setupToolbar()
        addView(toolbar, MATCH_PARENT, WRAP_CONTENT)
        addView(
            ScrollView(context).apply {
                addView(content, MATCH_PARENT, WRAP_CONTENT)
            },
            MATCH_PARENT,
            MATCH_PARENT
        )
    }

    private fun setupToolbar() {
        toolbar.elevation = InterfaceUtils.dpToPixels(context, 2f)
        toolbar.title = resources.getString(R.string.today)
        val res = StyledResources(context)
        val toolbarColor = if (!res.getBoolean(R.attr.useHabitColorAsPrimary)) {
            res.getColor(R.attr.colorPrimary)
        } else {
            currentTheme().color(PaletteColor(17)).toInt()
        }
        toolbar.background = ColorDrawable(toolbarColor)
        toolbar.applyToolbarInsets()
        activity.window.statusBarColor = toolbarColor
        activity.setSupportActionBar(toolbar)
        activity.supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    fun setState(state: TodayScreenState) {
        content.removeAllViews()
        if (state.totalCount == 0) {
            content.addView(bodyText(resources.getString(R.string.today_empty)))
            return
        }

        addSummary(state)
        addRemaining(state.remaining)
        state.sections.forEach { addSection(it) }
    }

    private fun addSummary(state: TodayScreenState) {
        content.addView(
            titleText(
                resources.getString(
                    R.string.today_summary_completed,
                    state.completedCount,
                    state.totalCount
                )
            )
        )
        val summaryDetails = listOf(
            resources.getString(
                R.string.today_summary_focus_minutes,
                state.focusMinutes.formatTodayValue()
            ),
            resources.getString(R.string.today_summary_remaining, state.remaining.size)
        ).joinToString("  |  ")
        content.addView(bodyText(summaryDetails, muted = true))
        content.addView(tierText(R.string.today_tier_minimum, state.minimum))
        content.addView(tierText(R.string.today_tier_normal, state.normal))
        content.addView(tierText(R.string.today_tier_ideal, state.ideal))
    }

    private fun tierText(labelResId: Int, progress: TodayTierProgress): TextView = bodyText(
        resources.getString(labelResId, progress.completedCount, progress.totalCount),
        muted = true
    )

    private fun addRemaining(items: List<TodayHabitItem>) {
        content.addView(sectionTitle(resources.getString(R.string.today_remaining), topMargin = 24f))
        if (items.isEmpty()) {
            content.addView(bodyText(resources.getString(R.string.today_empty_remaining)))
            return
        }
        items.forEach { content.addView(rowView(it)) }
    }

    private fun addSection(section: TodaySectionState) {
        val sectionKey = section.blockId?.let { "block_$it" } ?: "block_null"
        val isCollapsed = preferences.isTodaySectionCollapsed(sectionKey)
        val indicator = if (isCollapsed) "▸ " else "▾ "
        val sectionName = when (section.blockId) {
            1L -> resources.getString(R.string.today_section_intellect)
            2L -> resources.getString(R.string.today_section_speech)
            3L -> resources.getString(R.string.today_section_body)
            4L -> resources.getString(R.string.today_section_care)
            5L -> resources.getString(R.string.today_section_routine)
            6L -> resources.getString(R.string.today_section_limits)
            7L -> resources.getString(R.string.today_section_other)
            else -> section.blockName
        }
        val title = indicator + resources.getString(
            R.string.today_section_title,
            sectionName,
            section.completedCount,
            section.totalCount,
            section.focusMinutes.formatTodayValue()
        )
        val titleView = sectionTitle(title, topMargin = 24f, color = section.color.toFixedAndroidColor())
        titleView.isClickable = true
        titleView.setOnClickListener {
            preferences.setTodaySectionCollapsed(sectionKey, !isCollapsed)
            onRefresh()
        }
        content.addView(titleView)
        if (!isCollapsed) {
            section.items.forEach { content.addView(rowView(it)) }
        }
    }

    private fun rowView(item: TodayHabitItem): View {
        return LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(16f).toInt(), dp(10f).toInt(), 0, dp(10f).toInt())
            item.habitId?.let { id ->
                isClickable = true
                setOnClickListener { onHabitClick(id) }
            }
            addView(bodyText(item.name, bold = true))
            addView(bodyText(item.subtitle()))
            if (item.notes.isNotBlank()) {
                addView(bodyText(item.notes, muted = true))
            }
        }
    }

    private fun TodayHabitItem.subtitle(): String {
        val status = resources.getString(
            when (this.status) {
                TodayHabitStatus.COMPLETED -> R.string.today_status_done
                TodayHabitStatus.REMAINING -> R.string.today_status_remaining
                TodayHabitStatus.UNKNOWN -> R.string.today_status_unknown
                TodayHabitStatus.SKIPPED -> R.string.today_status_skipped
                TodayHabitStatus.EXCEEDED -> R.string.today_status_exceeded
            }
        )
        if (habitType != HabitType.NUMERICAL) return status
        if (this.status == TodayHabitStatus.SKIPPED) return status
        val current = currentValue?.formatTodayValue() ?: "0"
        val target = targetValue?.formatTodayValue() ?: "0"
        return resources.getString(R.string.today_numerical_progress, current, target, unit, status)
    }

    private fun titleText(text: String): TextView {
        return textView(text, size = 20f, bold = true).apply {
            setPadding(0, 0, 0, dp(4f).toInt())
        }
    }

    private fun sectionTitle(text: String, topMargin: Float, color: Int? = null): TextView {
        return textView(text, size = 16f, bold = true).apply {
            color?.let { setTextColor(it) }
            layoutParams = LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                this.topMargin = dp(topMargin).toInt()
            }
        }
    }

    private fun bodyText(text: String, bold: Boolean = false, muted: Boolean = false): TextView {
        return textView(text, size = 14f, bold = bold, muted = muted)
    }

    private fun textView(text: String, size: Float, bold: Boolean, muted: Boolean = false): TextView {
        return TextView(context).apply {
            this.text = text
            setTextSize(size)
            setTextColor(
                if (muted) {
                    sres.getColor(R.attr.contrast60)
                } else {
                    sres.getColor(android.R.attr.textColorPrimary)
                }
            )
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }
    }
}
