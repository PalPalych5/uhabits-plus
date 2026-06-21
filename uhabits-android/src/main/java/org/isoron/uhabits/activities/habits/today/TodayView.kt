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
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
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
import org.isoron.uhabits.core.ui.views.DarkTheme
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
    private val onCreateHabit: () -> Unit,
    private val onRefresh: () -> Unit
) : LinearLayout(context) {
    private val toolbar = buildToolbar()
    private val content = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(dp(16f).toInt(), dp(12f).toInt(), dp(16f).toInt(), dp(32f).toInt())
    }

    private val isDark: Boolean get() = currentTheme() is DarkTheme

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
        activateToolbar()
    }

    fun activateToolbar() {
        activity.setSupportActionBar(toolbar)
        activity.supportActionBar?.setDisplayHomeAsUpEnabled(false)
    }

    fun setState(state: TodayScreenState) {
        content.removeAllViews()
        if (state.totalCount == 0) {
            addEmptyState()
            return
        }

        addSummaryCard(state)
        addMotivations(state.motivations)
        if (preferences.isDayTiersEnabled) {
            addRemainingSection(state.remaining)
        }
        if (preferences.isHabitSpheresEnabled) {
            state.sections.forEach { addSectionCard(it) }
        } else {
            val allItems = state.sections.flatMap { it.items }
            addFlatHabitsCard(allItems)
        }
    }

    // ── Empty state ───────────────────────────────────────────────────────────

    private fun addEmptyState() {
        val container = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = dp(48f).toInt()
            }
        }
        val emoji = TextView(context).apply {
            text = "📋"
            textSize = 48f
            gravity = Gravity.CENTER
        }
        val title = textView(
            resources.getString(R.string.today_empty_title),
            size = 18f, bold = true
        ).apply {
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = dp(12f).toInt()
            }
        }
        val subtitle = textView(
            resources.getString(R.string.today_empty_subtitle),
            size = 14f, bold = false, muted = true
        ).apply {
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = dp(8f).toInt()
                bottomMargin = dp(4f).toInt()
            }
        }
        val createButton = Button(context).apply {
            text = resources.getString(R.string.today_create_habit)
            isAllCaps = false
            setOnClickListener { onCreateHabit() }
            layoutParams = LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                topMargin = dp(16f).toInt()
            }
        }
        container.addView(emoji)
        container.addView(title)
        container.addView(subtitle)
        container.addView(createButton)
        content.addView(container)
    }

    // ── Summary card ──────────────────────────────────────────────────────────

    private fun addSummaryCard(state: TodayScreenState) {
        val card = buildCard(cornerRadius = 16f, elevation = 3f).apply {
            layoutParams = LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                bottomMargin = dp(16f).toInt()
            }
        }
        val inner = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(16f).toInt(), dp(16f).toInt(), dp(16f).toInt(), dp(16f).toInt())
        }

        // Main counter: "Выполнено 1/5"
        inner.addView(
            textView(
                resources.getString(
                    R.string.today_summary_completed,
                    state.completedCount,
                    state.totalCount
                ),
                size = 24f, bold = true
            )
        )

        // Focus time row (always shown)
        val focusStr = resources.getString(
            R.string.today_summary_focus_minutes,
            state.focusMinutes.formatTodayValue()
        )
        inner.addView(
            textView(focusStr, size = 13f, bold = false, muted = true).apply {
                layoutParams = LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                    topMargin = dp(4f).toInt()
                }
            }
        )

        // Remaining habits (only when > 0 and tiers enabled)
        val remainingCount = state.remaining.size
        if (preferences.isDayTiersEnabled && remainingCount > 0) {
            inner.addView(
                textView(
                    resources.getString(R.string.today_summary_remaining_habits, remainingCount),
                    size = 13f, bold = false, muted = true
                )
            )
        }

        val showTiers = preferences.isDayTiersEnabled &&
            (state.minimum.totalCount > 0 || state.normal.totalCount > 0 || state.ideal.totalCount > 0)

        // Divider
        if (showTiers) {
            inner.addView(dividerView().apply {
                layoutParams = LayoutParams(MATCH_PARENT, dp(1f).toInt()).apply {
                    topMargin = dp(12f).toInt()
                    bottomMargin = dp(10f).toInt()
                }
            })

            // Tier rows (skip if totalCount == 0)
            if (state.minimum.totalCount > 0) {
                inner.addView(tierRow(R.string.today_tier_minimum, state.minimum))
            }
            if (state.normal.totalCount > 0) {
                inner.addView(tierRow(R.string.today_tier_normal, state.normal))
            }
            if (state.ideal.totalCount > 0) {
                inner.addView(tierRow(R.string.today_tier_ideal, state.ideal))
            }
        }

        card.addView(inner)
        content.addView(card)
    }

    private fun addFlatHabitsCard(items: List<TodayHabitItem>) {
        if (items.isEmpty()) return

        val card = buildCard(cornerRadius = 12f, elevation = 2f).apply {
            layoutParams = LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = dp(12f).toInt()
            }
        }
        val inner = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(14f).toInt(), dp(12f).toInt(), dp(14f).toInt(), dp(12f).toInt())
        }

        val headerRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleView = textView(resources.getString(R.string.habits_title), size = 15f, bold = true).apply {
            layoutParams = LayoutParams(0, WRAP_CONTENT, 1f)
        }

        val countable = items.filter { it.status != TodayHabitStatus.SKIPPED }
        val completedCount = countable.count { it.isCompleted }
        val totalCount = countable.size

        val progressBadge = buildBadge(
            text = "$completedCount/$totalCount",
            completed = completedCount >= totalCount && totalCount > 0
        )

        headerRow.addView(titleView)
        headerRow.addView(progressBadge)
        inner.addView(headerRow)

        items.forEach { item ->
            inner.addView(dividerView().apply {
                layoutParams = LayoutParams(MATCH_PARENT, dp(1f).toInt()).apply {
                    topMargin = dp(8f).toInt()
                    bottomMargin = dp(4f).toInt()
                }
            })
            inner.addView(habitRowView(item, sectionColor = null))
        }

        card.addView(inner)
        content.addView(card)
    }

    private fun tierRow(labelResId: Int, progress: TodayTierProgress): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = dp(3f).toInt()
            }
        }
        val label = textView(
            resources.getString(labelResId, progress.completedCount, progress.totalCount),
            size = 12f, bold = false, muted = true
        ).apply {
            layoutParams = LayoutParams(0, WRAP_CONTENT, 1f)
        }
        val isComplete = progress.completedCount >= progress.totalCount && progress.totalCount > 0
        val badge = buildBadge(
            text = if (isComplete) "✓" else "${progress.completedCount}/${progress.totalCount}",
            completed = isComplete
        )
        row.addView(label)
        row.addView(badge)
        return row
    }

    // ── "Remaining to minimum" section ────────────────────────────────────────

    private fun addRemainingSection(items: List<TodayHabitItem>) {
        val header = textView(
            resources.getString(R.string.today_remaining),
            size = 13f, bold = true
        ).apply {
            layoutParams = LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = dp(4f).toInt()
                bottomMargin = dp(4f).toInt()
            }
            alpha = 0.7f
        }
        content.addView(header)

        if (items.isEmpty()) {
            content.addView(
                textView(
                    resources.getString(R.string.today_empty_remaining),
                    size = 13f, bold = false, muted = true
                ).apply {
                    layoutParams = LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                        bottomMargin = dp(12f).toInt()
                    }
                }
            )
        } else {
            items.forEach { content.addView(habitRowView(it, sectionColor = null)) }
        }
    }

    // ── Section (sphere) card ─────────────────────────────────────────────────

    private fun addSectionCard(section: TodaySectionState) {
        val sectionKey = section.blockId?.let { "block_$it" } ?: "block_null"
        val isCollapsed = preferences.isTodaySectionCollapsed(sectionKey)
        val sectionColor = section.color.toFixedAndroidColor()

        // Outer card with an inset color accent
        val cardWrapper = FrameLayout(context).apply {
            layoutParams = LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = dp(12f).toInt()
            }
        }

        // Keep the accent inside the card bounds so it never intersects the
        // card stroke or its rounded corners.
        val stripe = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(dp(3f).toInt(), MATCH_PARENT).apply {
                gravity = Gravity.START
                marginStart = dp(4f).toInt()
                topMargin = dp(8f).toInt()
                bottomMargin = dp(8f).toInt()
            }
            background = GradientDrawable().apply {
                setColor(sectionColor)
                cornerRadius = dp(1.5f)
            }
            translationZ = dp(3f)
        }

        val card = buildCard(cornerRadius = 12f, elevation = 2f).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }
        val inner = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(14f).toInt(), dp(12f).toInt(), dp(14f).toInt(), dp(12f).toInt())
        }

        // Section header
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

        val headerRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            setOnClickListener {
                preferences.setTodaySectionCollapsed(sectionKey, !isCollapsed)
                onRefresh()
            }
        }

        val indicator = if (isCollapsed) "▸ " else "▾ "
        val titleView = textView("$indicator$sectionName", size = 15f, bold = true).apply {
            setTextColor(sectionColor)
            layoutParams = LayoutParams(0, WRAP_CONTENT, 1f)
        }

        // Section progress badge: "1/3"
        val progressBadge = buildBadge(
            text = "${section.completedCount}/${section.totalCount}",
            completed = section.completedCount >= section.totalCount && section.totalCount > 0,
            colorInt = sectionColor
        )

        // Focus minutes for section (only if > 0)
        val focusMinsText = if (section.focusMinutes > 0) {
            textView(
                "${section.focusMinutes.formatTodayValue()} мин",
                size = 12f, bold = false, muted = true
            ).apply {
                layoutParams = LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                    marginStart = dp(8f).toInt()
                }
            }
        } else null

        headerRow.addView(titleView)
        headerRow.addView(progressBadge)
        focusMinsText?.let { headerRow.addView(it) }
        inner.addView(headerRow)

        // Habit rows (if not collapsed)
        if (!isCollapsed) {
            section.items.forEach { item ->
                inner.addView(dividerView().apply {
                    layoutParams = LayoutParams(MATCH_PARENT, dp(1f).toInt()).apply {
                        topMargin = dp(8f).toInt()
                        bottomMargin = dp(4f).toInt()
                    }
                })
                inner.addView(habitRowView(item, sectionColor = sectionColor))
            }
        }

        card.addView(inner)
        cardWrapper.addView(card)
        cardWrapper.addView(stripe)
        content.addView(cardWrapper)
    }

    // ── Habit row ─────────────────────────────────────────────────────────────

    private fun habitRowView(item: TodayHabitItem, sectionColor: Int?): View {
        val row = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(4f).toInt(), dp(6f).toInt(), dp(4f).toInt(), dp(6f).toInt())
            item.habitId?.let { id ->
                isClickable = true
                isFocusable = true
                setOnClickListener { onHabitClick(id) }
                // Ripple-like background
                val rippleColor = if (isDark) 0x10FFFFFF.toInt() else 0x10000000.toInt()
                background = GradientDrawable().apply {
                    setColor(0)
                    cornerRadius = dp(8f)
                }
                foreground = android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(rippleColor),
                    null, null
                )
            }
        }

        // Name + badge row
        val nameRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val nameView = textView(item.name, size = 14f, bold = true).apply {
            layoutParams = LayoutParams(0, WRAP_CONTENT, 1f)
        }
        val statusBadge = buildStatusBadge(item, sectionColor)

        nameRow.addView(nameView)
        nameRow.addView(statusBadge)
        row.addView(nameRow)

        // Progress line for numerical habits
        if (item.habitType == HabitType.NUMERICAL && item.status != TodayHabitStatus.SKIPPED) {
            val current = if (item.isLimitHabit) (item.periodProgressActual ?: 0.0) else (item.currentValue ?: 0.0)
            val target = if (item.isLimitHabit) (item.periodProgressTarget ?: 0.0) else (item.targetValue ?: 0.0)
            val unit = item.unit

            val progressRow = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                    topMargin = dp(4f).toInt()
                }
            }

            val periodStr = if (item.isLimitHabit) {
                val pText = when (item.periodLabel) {
                    org.isoron.uhabits.core.ui.screens.habits.today.PeriodLabel.WEEK -> resources.getString(R.string.per_week)
                    org.isoron.uhabits.core.ui.screens.habits.today.PeriodLabel.MONTH -> resources.getString(R.string.per_month)
                    else -> resources.getString(R.string.per_day)
                }
                " $pText"
            } else {
                ""
            }

            val progressLabel = textView(
                "${current.formatTodayValue()} / ${target.formatTodayValue()} $unit$periodStr".trim(),
                size = 12f, bold = false, muted = true
            ).apply {
                layoutParams = LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                    marginEnd = dp(8f).toInt()
                }
            }

            val progressBar = ProgressBar(
                context, null,
                android.R.attr.progressBarStyleHorizontal
            ).apply {
                layoutParams = LayoutParams(0, dp(4f).toInt(), 1f)
                max = 1000
                val progressFraction = if (target > 0) (current / target).coerceIn(0.0, 1.0) else 0.0
                progress = (progressFraction * 1000).toInt()
                val barColor = sectionColor ?: item.color.toFixedAndroidColor()
                progressDrawable = GradientDrawable().apply {
                    setColor(barColor)
                    cornerRadius = dp(2f)
                }.let { filled ->
                    val track = GradientDrawable().apply {
                        setColor(if (isDark) 0x20FFFFFF.toInt() else 0x15000000.toInt())
                        cornerRadius = dp(2f)
                    }
                    // Use LayerDrawable to mimic progress bar with track + fill
                    val ld = android.graphics.drawable.LayerDrawable(
                        arrayOf(track, filled)
                    )
                    ld.setId(0, android.R.id.background)
                    ld.setId(1, android.R.id.progress)
                    ld
                }
            }

            progressRow.addView(progressLabel)
            progressRow.addView(progressBar)
            row.addView(progressRow)
        } else if (item.habitType == HabitType.YES_NO && item.status == TodayHabitStatus.UNKNOWN) {
            // No-data note for boolean habits
            row.addView(
                textView(
                    resources.getString(R.string.today_status_unknown),
                    size = 12f, bold = false, muted = true
                ).apply {
                    layoutParams = LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                        topMargin = dp(2f).toInt()
                    }
                }
            )
        }

        // Notes
        if (item.notes.isNotBlank()) {
            row.addView(
                textView(item.notes, size = 12f, bold = false, muted = true).apply {
                    layoutParams = LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                        topMargin = dp(2f).toInt()
                    }
                }
            )
        }

        return row
    }

    // ── Status badge ──────────────────────────────────────────────────────────

    private fun buildStatusBadge(item: TodayHabitItem, sectionColor: Int?): TextView {
        val status = item.status
        val isAtMost = item.isLimitHabit

        val (label, bgAlpha, textAlpha) = when (status) {
            TodayHabitStatus.COMPLETED -> {
                if (isAtMost) {
                    Triple(resources.getString(R.string.today_status_within_limit), 0.15f, 1.0f)
                } else {
                    Triple("✓ " + resources.getString(R.string.today_status_done), 0.15f, 1.0f)
                }
            }
            TodayHabitStatus.REMAINING -> Triple("○ " + resources.getString(R.string.today_status_remaining), 0.08f, 0.7f)
            TodayHabitStatus.UNKNOWN   -> Triple("— " + resources.getString(R.string.today_status_unknown), 0.06f, 0.5f)
            TodayHabitStatus.SKIPPED   -> Triple("⊘ " + resources.getString(R.string.today_status_skipped), 0.06f, 0.5f)
            TodayHabitStatus.EXCEEDED  -> {
                if (isAtMost) {
                    Triple(resources.getString(R.string.today_status_limit_exceeded), 0.15f, 1.0f)
                } else {
                    Triple("⚠ " + resources.getString(R.string.today_status_exceeded), 0.15f, 1.0f)
                }
            }
        }

        val baseColor = when (status) {
            TodayHabitStatus.COMPLETED -> 0xFF4CAF50.toInt()  // green
            TodayHabitStatus.REMAINING -> sectionColor ?: item.color.toFixedAndroidColor()
            TodayHabitStatus.UNKNOWN   -> 0xFF9E9E9E.toInt()  // gray
            TodayHabitStatus.SKIPPED   -> 0xFF9E9E9E.toInt()  // gray
            TodayHabitStatus.EXCEEDED  -> 0xFFFF5722.toInt()  // orange-red
        }

        val bgColor = applyAlpha(baseColor, bgAlpha)

        return TextView(context).apply {
            text = label
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(applyAlpha(baseColor, textAlpha))
            setPadding(dp(7f).toInt(), dp(3f).toInt(), dp(7f).toInt(), dp(3f).toInt())
            background = GradientDrawable().apply {
                setColor(bgColor)
                cornerRadius = dp(10f)
            }
            layoutParams = LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                marginStart = dp(8f).toInt()
            }
        }
    }

    // ── Small rounded badge (tier / section progress) ─────────────────────────

    private fun buildBadge(text: String, completed: Boolean, colorInt: Int? = null): TextView {
        val baseColor = colorInt ?: if (completed) 0xFF4CAF50.toInt() else 0xFF9E9E9E.toInt()
        val bgColor = applyAlpha(baseColor, if (completed) 0.15f else 0.1f)
        return TextView(context).apply {
            this.text = text
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(baseColor)
            setPadding(dp(6f).toInt(), dp(2f).toInt(), dp(6f).toInt(), dp(2f).toInt())
            background = GradientDrawable().apply {
                setColor(bgColor)
                cornerRadius = dp(8f)
            }
            layoutParams = LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                marginStart = dp(6f).toInt()
            }
        }
    }

    // ── Card container ────────────────────────────────────────────────────────

    private fun buildCard(cornerRadius: Float, elevation: Float): LinearLayout {
        val bgColor = if (isDark) 0x10FFFFFF.toInt() else 0x08000000.toInt()
        return LinearLayout(context).apply {
            orientation = VERTICAL
            background = GradientDrawable().apply {
                setColor(bgColor)
                this.cornerRadius = dp(cornerRadius)
                setStroke(
                    dp(0.5f).toInt(),
                    if (isDark) 0x18FFFFFF.toInt() else 0x12000000.toInt()
                )
            }
            this.elevation = dp(elevation)
        }
    }

    // ── Divider ───────────────────────────────────────────────────────────────

    private fun dividerView(): View = View(context).apply {
        setBackgroundColor(if (isDark) 0x15FFFFFF.toInt() else 0x10000000.toInt())
    }

    // ── Motivations (existing, unchanged) ─────────────────────────────────────

    private fun addMotivations(motivations: List<String>) {
        val filtered = if (preferences.isDayTiersEnabled) motivations else motivations.filter { !it.startsWith("minimum_completed") }
        if (filtered.isEmpty()) return

        val container = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = dp(4f).toInt()
                bottomMargin = dp(8f).toInt()
            }
        }

        filtered.forEach { rawMotivation ->
            val formatted = formatMotivation(rawMotivation)
            if (formatted.isBlank()) return@forEach

            val parts = rawMotivation.split("|")
            val type = parts[0]
            val emoji = when (type) {
                "minimum_completed" -> "🎉"
                "streak_milestone"  -> "🔥"
                "comeback"          -> "✨"
                else                -> "🌟"
            }

            val bgColor = if (isDark) 0x20FFFFFF.toInt() else 0x10000000.toInt()
            val card = LinearLayout(context).apply {
                orientation = HORIZONTAL
                setPadding(dp(12f).toInt(), dp(12f).toInt(), dp(12f).toInt(), dp(12f).toInt())
                layoutParams = LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                    bottomMargin = dp(8f).toInt()
                }
                background = GradientDrawable().apply {
                    setColor(bgColor)
                    cornerRadius = dp(8f)
                    setStroke(dp(1f).toInt(), if (isDark) 0x15FFFFFF.toInt() else 0x15000000.toInt())
                }
            }

            val emojiView = TextView(context).apply {
                text = emoji
                textSize = 18f
                layoutParams = LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                    rightMargin = dp(10f).toInt()
                }
            }

            val textView = TextView(context).apply {
                text = formatted
                textSize = 14f
                setTypeface(null, Typeface.ITALIC)
                setTextColor(sres.getColor(android.R.attr.textColorPrimary))
                layoutParams = LayoutParams(0, WRAP_CONTENT, 1f)
            }

            card.addView(emojiView)
            card.addView(textView)
            container.addView(card)
        }

        content.addView(container)
    }

    private fun formatMotivation(motivation: String): String {
        val parts = motivation.split("|")
        val key = parts[0]
        return when (key) {
            "minimum_completed" -> resources.getString(R.string.motivation_minimum_completed)
            "streak_milestone" -> {
                val name = parts[1]
                val length = parts[2].toInt()
                resources.getString(R.string.motivation_streak_milestone, name, length)
            }
            "comeback" -> {
                val name = parts[1]
                resources.getString(R.string.motivation_comeback, name)
            }
            else -> ""
        }
    }

    // ── Text helpers ──────────────────────────────────────────────────────────

    private fun textView(
        text: String, size: Float, bold: Boolean, muted: Boolean = false
    ): TextView = TextView(context).apply {
        this.text = text
        setTextSize(size)
        setTextColor(
            if (muted) sres.getColor(R.attr.contrast60)
            else sres.getColor(android.R.attr.textColorPrimary)
        )
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    // ── Color helpers ─────────────────────────────────────────────────────────

    private fun applyAlpha(color: Int, alpha: Float): Int {
        val a = (alpha * 255).toInt().coerceIn(0, 255)
        return (color and 0x00FFFFFF) or (a shl 24)
    }
}
