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

package org.isoron.uhabits.activities.containers.statistics

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import org.isoron.platform.gui.toInt
import org.isoron.platform.time.DayOfWeek
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.getToday
import org.isoron.uhabits.BaseExceptionHandler
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.AndroidThemeSwitcher
import org.isoron.uhabits.activities.common.theme.MainTabsThemeBridge
import org.isoron.uhabits.activities.settings.SettingsThemePalette
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.containers.statistics.ChildContainerFocusBreakdown
import org.isoron.uhabits.core.containers.statistics.ContainerActivityReport
import org.isoron.uhabits.core.containers.statistics.ContainerScopeMode
import org.isoron.uhabits.core.containers.statistics.ContainerStatsRange
import org.isoron.uhabits.core.containers.statistics.FocusDailyBucket
import org.isoron.uhabits.core.containers.statistics.HabitActivityRow
import org.isoron.uhabits.core.models.projection.HabitGoalStatus
import org.isoron.uhabits.utils.applyRootViewInsets
import org.isoron.uhabits.utils.applyToolbarInsets
import java.util.Locale

class ContainerStatisticsActivity : AppCompatActivity() {

    private enum class RangeType { DAY, WEEK, MONTH }

    private lateinit var toolbar: Toolbar
    private lateinit var themeSwitcher: AndroidThemeSwitcher

    private lateinit var cardFilters: LinearLayout
    private lateinit var btnRangeDay: TextView
    private lateinit var btnRangeWeek: TextView
    private lateinit var btnRangeMonth: TextView
    private lateinit var btnScopeSubtree: TextView
    private lateinit var btnScopeHere: TextView
    private lateinit var btnPrevRange: ImageButton
    private lateinit var btnNextRange: ImageButton
    private lateinit var tvDateRangeLabel: TextView

    private lateinit var cardFocus: LinearLayout
    private lateinit var tvFocusTitle: TextView
    private lateinit var tvFocusSessionCount: TextView
    private lateinit var tvFocusTotalDuration: TextView
    private lateinit var tvFocusSubtreeBreakdown: TextView
    private lateinit var layoutFocusDailyTrend: LinearLayout
    private lateinit var tvDailyTrendHeader: TextView
    private lateinit var layoutDailyBars: LinearLayout
    private lateinit var layoutSubcontainersBreakdown: LinearLayout
    private lateinit var tvSubcontainersBreakdownHeader: TextView
    private lateinit var containerChildBreakdownRows: LinearLayout
    private lateinit var tvFocusEmpty: TextView
    private lateinit var tvFocusFootnote: TextView

    private lateinit var cardTasks: LinearLayout
    private lateinit var tvTasksTitle: TextView
    private lateinit var badgeCompleted: LinearLayout
    private lateinit var tvTasksCompletedCount: TextView
    private lateinit var tvTasksCompletedLabel: TextView
    private lateinit var badgePending: LinearLayout
    private lateinit var tvTasksPendingCount: TextView
    private lateinit var tvTasksPendingLabel: TextView
    private lateinit var badgeOverdue: LinearLayout
    private lateinit var tvTasksOverdueCount: TextView
    private lateinit var tvTasksOverdueLabel: TextView
    private lateinit var tvTasksEmpty: TextView
    private lateinit var tvTasksFootnote: TextView

    private lateinit var cardHabits: LinearLayout
    private lateinit var tvHabitsTitle: TextView
    private lateinit var tvHabitsActiveCount: TextView
    private lateinit var layoutHabitsSummaryChips: LinearLayout
    private lateinit var chipHabitsReached: TextView
    private lateinit var chipHabitsIncomplete: TextView
    private lateinit var chipHabitsUnknown: TextView
    private lateinit var layoutHabitRows: LinearLayout
    private lateinit var tvHabitsEmpty: TextView

    private var targetContainerId: ContainerId = ContainerId("")
    private var currentRangeType: RangeType = RangeType.WEEK
    private var currentScope: ContainerScopeMode = ContainerScopeMode.SUBTREE
    private var anchorDate: LocalDate = getToday()

    private val app get() = application as HabitsApplication
    private val appComponent get() = app.component
    private val facade get() = appComponent.organizationFacade
    private val cQueries get() = facade.containerQueries
    private val statsService get() = appComponent.containerStatisticsService
    private val prefs get() = appComponent.preferences

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        themeSwitcher = AndroidThemeSwitcher(this, prefs)
        themeSwitcher.apply()
        BaseExceptionHandler.install(this)

        val rawContainerId = intent.getStringExtra(EXTRA_CONTAINER_ID)
        if (rawContainerId.isNullOrEmpty()) {
            finish()
            return
        }
        targetContainerId = ContainerId(rawContainerId)

        setContentView(R.layout.activity_container_statistics)
        findViewById<View>(R.id.coordinatorLayout).applyRootViewInsets()

        initViews()
        setupListeners()
        applyTheme()
        renderReport()
    }

    private fun initViews() {
        toolbar = findViewById(R.id.toolbar)
        toolbar.applyToolbarInsets()
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)

        cardFilters = findViewById(R.id.cardFilters)
        btnRangeDay = findViewById(R.id.btnRangeDay)
        btnRangeWeek = findViewById(R.id.btnRangeWeek)
        btnRangeMonth = findViewById(R.id.btnRangeMonth)
        btnScopeSubtree = findViewById(R.id.btnScopeSubtree)
        btnScopeHere = findViewById(R.id.btnScopeHere)
        btnPrevRange = findViewById(R.id.btnPrevRange)
        btnNextRange = findViewById(R.id.btnNextRange)
        tvDateRangeLabel = findViewById(R.id.tvDateRangeLabel)

        cardFocus = findViewById(R.id.cardFocus)
        tvFocusTitle = findViewById(R.id.tvFocusTitle)
        tvFocusSessionCount = findViewById(R.id.tvFocusSessionCount)
        tvFocusTotalDuration = findViewById(R.id.tvFocusTotalDuration)
        tvFocusSubtreeBreakdown = findViewById(R.id.tvFocusSubtreeBreakdown)
        layoutFocusDailyTrend = findViewById(R.id.layoutFocusDailyTrend)
        tvDailyTrendHeader = findViewById(R.id.tvDailyTrendHeader)
        layoutDailyBars = findViewById(R.id.layoutDailyBars)
        layoutSubcontainersBreakdown = findViewById(R.id.layoutSubcontainersBreakdown)
        tvSubcontainersBreakdownHeader = findViewById(R.id.tvSubcontainersBreakdownHeader)
        containerChildBreakdownRows = findViewById(R.id.containerChildBreakdownRows)
        tvFocusEmpty = findViewById(R.id.tvFocusEmpty)
        tvFocusFootnote = findViewById(R.id.tvFocusFootnote)

        cardTasks = findViewById(R.id.cardTasks)
        tvTasksTitle = findViewById(R.id.tvTasksTitle)
        badgeCompleted = findViewById(R.id.badgeCompleted)
        tvTasksCompletedCount = findViewById(R.id.tvTasksCompletedCount)
        tvTasksCompletedLabel = findViewById(R.id.tvTasksCompletedLabel)
        badgePending = findViewById(R.id.badgePending)
        tvTasksPendingCount = findViewById(R.id.tvTasksPendingCount)
        tvTasksPendingLabel = findViewById(R.id.tvTasksPendingLabel)
        badgeOverdue = findViewById(R.id.badgeOverdue)
        tvTasksOverdueCount = findViewById(R.id.tvTasksOverdueCount)
        tvTasksOverdueLabel = findViewById(R.id.tvTasksOverdueLabel)
        tvTasksEmpty = findViewById(R.id.tvTasksEmpty)
        tvTasksFootnote = findViewById(R.id.tvTasksFootnote)

        cardHabits = findViewById(R.id.cardHabits)
        tvHabitsTitle = findViewById(R.id.tvHabitsTitle)
        tvHabitsActiveCount = findViewById(R.id.tvHabitsActiveCount)
        layoutHabitsSummaryChips = findViewById(R.id.layoutHabitsSummaryChips)
        chipHabitsReached = findViewById(R.id.chipHabitsReached)
        chipHabitsIncomplete = findViewById(R.id.chipHabitsIncomplete)
        chipHabitsUnknown = findViewById(R.id.chipHabitsUnknown)
        layoutHabitRows = findViewById(R.id.layoutHabitRows)
        tvHabitsEmpty = findViewById(R.id.tvHabitsEmpty)
    }

    private fun setupListeners() {
        toolbar.setNavigationOnClickListener { finish() }

        btnRangeDay.setOnClickListener {
            currentRangeType = RangeType.DAY
            updateFilterSelectors()
            renderReport()
        }
        btnRangeWeek.setOnClickListener {
            currentRangeType = RangeType.WEEK
            updateFilterSelectors()
            renderReport()
        }
        btnRangeMonth.setOnClickListener {
            currentRangeType = RangeType.MONTH
            updateFilterSelectors()
            renderReport()
        }

        btnScopeSubtree.setOnClickListener {
            currentScope = ContainerScopeMode.SUBTREE
            updateFilterSelectors()
            renderReport()
        }
        btnScopeHere.setOnClickListener {
            currentScope = ContainerScopeMode.HERE
            updateFilterSelectors()
            renderReport()
        }

        btnPrevRange.setOnClickListener {
            anchorDate = when (currentRangeType) {
                RangeType.DAY -> anchorDate.minus(1)
                RangeType.WEEK -> anchorDate.minus(7)
                RangeType.MONTH -> {
                    val prevMonth = if (anchorDate.month == 1) 12 else anchorDate.month - 1
                    val prevYear = if (anchorDate.month == 1) anchorDate.year - 1 else anchorDate.year
                    LocalDate(prevYear, prevMonth, 1)
                }
            }
            renderReport()
        }

        btnNextRange.setOnClickListener {
            anchorDate = when (currentRangeType) {
                RangeType.DAY -> anchorDate.plus(1)
                RangeType.WEEK -> anchorDate.plus(7)
                RangeType.MONTH -> {
                    val nextMonth = if (anchorDate.month == 12) 1 else anchorDate.month + 1
                    val nextYear = if (anchorDate.month == 12) anchorDate.year + 1 else anchorDate.year
                    LocalDate(nextYear, nextMonth, 1)
                }
            }
            renderReport()
        }
    }

    private fun applyTheme() {
        val palette = MainTabsThemeBridge.resolve(this)
        findViewById<View>(R.id.coordinatorLayout).setBackgroundColor(palette.background)
        toolbar.setTitleTextColor(palette.onSurface)
        toolbar.setSubtitleTextColor(palette.onSurfaceVariant)

        // Cards background and corners
        listOf(cardFilters, cardFocus, cardTasks, cardHabits).forEach { card ->
            card.background = GradientDrawable().apply {
                setColor(palette.surface)
                cornerRadius = dp(14f)
                setStroke(dp(1f).toInt(), palette.divider)
            }
        }

        // Stepper buttons
        btnPrevRange.setColorFilter(palette.onSurface)
        btnNextRange.setColorFilter(palette.onSurface)
        tvDateRangeLabel.setTextColor(palette.onSurface)

        // Focus section typography
        tvFocusTitle.setTextColor(palette.onSurface)
        tvFocusSessionCount.setTextColor(palette.onSurfaceVariant)
        tvFocusTotalDuration.setTextColor(palette.accent)
        tvFocusSubtreeBreakdown.setTextColor(palette.onSurfaceVariant)
        tvDailyTrendHeader.setTextColor(palette.onSurfaceVariant)
        tvSubcontainersBreakdownHeader.setTextColor(palette.onSurfaceVariant)
        tvFocusEmpty.setTextColor(palette.onSurfaceVariant)
        tvFocusFootnote.setTextColor(palette.onSurfaceVariant)

        // Tasks section typography
        tvTasksTitle.setTextColor(palette.onSurface)
        tvTasksCompletedCount.setTextColor(palette.onSurface)
        tvTasksCompletedLabel.setTextColor(palette.onSurfaceVariant)
        tvTasksPendingCount.setTextColor(palette.onSurface)
        tvTasksPendingLabel.setTextColor(palette.onSurfaceVariant)
        tvTasksOverdueCount.setTextColor(palette.accent)
        tvTasksOverdueLabel.setTextColor(palette.onSurfaceVariant)
        tvTasksEmpty.setTextColor(palette.onSurfaceVariant)
        tvTasksFootnote.setTextColor(palette.onSurfaceVariant)

        listOf(badgeCompleted, badgePending, badgeOverdue).forEach { badge ->
            badge.background = GradientDrawable().apply {
                setColor(palette.surfaceVariant)
                cornerRadius = dp(10f)
            }
        }

        // Habits section typography
        tvHabitsTitle.setTextColor(palette.onSurface)
        tvHabitsActiveCount.setTextColor(palette.onSurfaceVariant)
        tvHabitsEmpty.setTextColor(palette.onSurfaceVariant)

        listOf(chipHabitsReached, chipHabitsIncomplete, chipHabitsUnknown).forEach { chip ->
            chip.background = GradientDrawable().apply {
                setColor(palette.surfaceVariant)
                cornerRadius = dp(6f)
            }
            chip.setTextColor(palette.onSurfaceVariant)
        }

        updateFilterSelectors()
    }

    private fun updateFilterSelectors() {
        val palette = MainTabsThemeBridge.resolve(this)

        // Range selector tabs
        listOf(
            btnRangeDay to (currentRangeType == RangeType.DAY),
            btnRangeWeek to (currentRangeType == RangeType.WEEK),
            btnRangeMonth to (currentRangeType == RangeType.MONTH)
        ).forEach { (button, isSelected) ->
            button.background = GradientDrawable().apply {
                if (isSelected) {
                    setColor(palette.accent)
                    cornerRadius = dp(8f)
                } else {
                    setColor(Color.TRANSPARENT)
                }
            }
            button.setTextColor(if (isSelected) palette.background else palette.onSurfaceVariant)
        }

        // Scope selector tabs
        listOf(
            btnScopeSubtree to (currentScope == ContainerScopeMode.SUBTREE),
            btnScopeHere to (currentScope == ContainerScopeMode.HERE)
        ).forEach { (button, isSelected) ->
            button.background = GradientDrawable().apply {
                if (isSelected) {
                    setColor(palette.surfaceVariant)
                    cornerRadius = dp(8f)
                    setStroke(dp(1f).toInt(), palette.accent)
                } else {
                    setColor(Color.TRANSPARENT)
                }
            }
            button.setTextColor(if (isSelected) palette.accent else palette.onSurfaceVariant)
        }
    }

    private fun computeRange(): ContainerStatsRange {
        return when (currentRangeType) {
            RangeType.DAY -> ContainerStatsRange.day(anchorDate)
            RangeType.WEEK -> ContainerStatsRange.week(anchorDate, DayOfWeek.MONDAY)
            RangeType.MONTH -> ContainerStatsRange.month(anchorDate)
        }
    }

    private fun renderReport() {
        val container = cQueries?.find(targetContainerId, includeDeleted = true)
        val containerName = container?.name ?: getString(R.string.container_statistics_title)
        toolbar.title = containerName

        val range = computeRange()
        tvDateRangeLabel.text = formatDateRange(range)

        val service = statsService
        if (service == null) {
            toolbar.subtitle = getString(R.string.focus_credit_unavailable)
            return
        }

        val report = service.getReport(targetContainerId, range, currentScope)
        toolbar.subtitle = when (currentScope) {
            ContainerScopeMode.SUBTREE -> getString(R.string.container_statistics_scope_subtree)
            ContainerScopeMode.HERE -> getString(R.string.container_statistics_scope_here)
        }

        renderFocusSection(report)
        renderTasksSection(report)
        renderHabitsSection(report)
    }

    private fun renderFocusSection(report: ContainerActivityReport) {
        val focus = report.focus
        val palette = MainTabsThemeBridge.resolve(this)

        if (focus.totalDurationMs == 0L && focus.sessionCount == 0) {
            tvFocusTotalDuration.text = formatDuration(0L)
            tvFocusSessionCount.text = getString(R.string.container_statistics_focus_no_sessions)
            tvFocusSubtreeBreakdown.visibility = View.GONE
            layoutFocusDailyTrend.visibility = View.GONE
            layoutSubcontainersBreakdown.visibility = View.GONE
            tvFocusEmpty.visibility = View.VISIBLE
            return
        }

        tvFocusEmpty.visibility = View.GONE
        tvFocusTotalDuration.text = formatDuration(focus.totalDurationMs)
        tvFocusSessionCount.text = if (focus.sessionCount == 1) {
            getString(R.string.container_statistics_focus_one_session)
        } else {
            getString(R.string.container_statistics_focus_sessions_format, focus.sessionCount)
        }

        if (currentScope == ContainerScopeMode.SUBTREE && focus.childBreakdown.isNotEmpty()) {
            tvFocusSubtreeBreakdown.visibility = View.VISIBLE
            tvFocusSubtreeBreakdown.text = "${getString(R.string.container_statistics_focus_direct_breakdown, formatDuration(focus.directDurationMs))} • ${getString(R.string.container_statistics_focus_descendant_breakdown, formatDuration(focus.descendantDurationMs))}"
        } else {
            tvFocusSubtreeBreakdown.visibility = View.GONE
        }

        // Daily trend bars
        layoutDailyBars.removeAllViews()
        if (focus.dailyBuckets.isNotEmpty()) {
            layoutFocusDailyTrend.visibility = View.VISIBLE
            val maxBucketDuration = focus.dailyBuckets.maxOfOrNull { it.durationMs }?.coerceAtLeast(1L) ?: 1L

            focus.dailyBuckets.forEach { bucket ->
                val barContainer = LinearLayout(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, MATCH_PARENT, 1f)
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    setPadding(dp(2f).toInt(), 0, dp(2f).toInt(), 0)
                }

                val barView = View(this).apply {
                    val heightRatio = (bucket.durationMs.toFloat() / maxBucketDuration).coerceIn(0.05f, 1f)
                    val barHeight = if (bucket.durationMs > 0) (dp(44f) * heightRatio).toInt() else dp(4f).toInt()
                    layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, barHeight)
                    background = GradientDrawable().apply {
                        setColor(if (bucket.durationMs > 0) palette.accent else palette.surfaceVariant)
                        cornerRadius = dp(3f)
                    }
                }

                val labelView = TextView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
                    text = formatDayLabel(bucket.date)
                    textSize = 10f
                    gravity = Gravity.CENTER
                    setTextColor(palette.onSurfaceVariant)
                    setPadding(0, dp(4f).toInt(), 0, 0)
                }

                barContainer.addView(barView)
                barContainer.addView(labelView)
                layoutDailyBars.addView(barContainer)
            }
        } else {
            layoutFocusDailyTrend.visibility = View.GONE
        }

        // Subcontainers breakdown rows
        containerChildBreakdownRows.removeAllViews()
        if (currentScope == ContainerScopeMode.SUBTREE && focus.childBreakdown.isNotEmpty()) {
            layoutSubcontainersBreakdown.visibility = View.VISIBLE
            focus.childBreakdown.forEach { child ->
                val rowView = createChildBreakdownRow(child, focus.totalDurationMs, palette)
                containerChildBreakdownRows.addView(rowView)
            }
        } else {
            layoutSubcontainersBreakdown.visibility = View.GONE
        }
    }

    private fun createChildBreakdownRow(
        child: ChildContainerFocusBreakdown,
        totalDurationMs: Long,
        palette: SettingsThemePalette
    ): View {
        val row = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4f).toInt(), 0, dp(4f).toInt())
        }

        val nameView = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            text = child.containerName
            textSize = 13f
            setTextColor(palette.onSurface)
        }

        val durationView = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
            text = formatDuration(child.durationMs)
            textSize = 12f
            setTextColor(palette.accent)
            setPadding(dp(8f).toInt(), 0, 0, 0)
        }

        row.addView(nameView)
        row.addView(durationView)
        return row
    }

    private fun renderTasksSection(report: ContainerActivityReport) {
        val tasks = report.tasks
        tvTasksCompletedCount.text = tasks.completedCount.toString()
        tvTasksPendingCount.text = tasks.pendingCount.toString()
        tvTasksOverdueCount.text = tasks.overdueCount.toString()

        if (tasks.completedCount == 0 && tasks.pendingCount == 0 && tasks.overdueCount == 0) {
            tvTasksEmpty.visibility = View.VISIBLE
        } else {
            tvTasksEmpty.visibility = View.GONE
        }
    }

    private fun renderHabitsSection(report: ContainerActivityReport) {
        val habits = report.habits
        val palette = MainTabsThemeBridge.resolve(this)

        tvHabitsActiveCount.text = getString(R.string.container_statistics_habits_active_count, habits.totalHabitCount)
        chipHabitsReached.text = "${habits.reachedCount} ${getString(R.string.container_statistics_habits_reached).lowercase(Locale.getDefault())}"
        chipHabitsIncomplete.text = "${habits.incompleteCount} ${getString(R.string.container_statistics_habits_incomplete).lowercase(Locale.getDefault())}"
        chipHabitsUnknown.text = "${habits.unknownCount} ${getString(R.string.container_statistics_habits_unknown).lowercase(Locale.getDefault())}"

        layoutHabitRows.removeAllViews()
        if (habits.totalHabitCount == 0 || habits.habitRows.isEmpty()) {
            tvHabitsEmpty.visibility = View.VISIBLE
            layoutHabitsSummaryChips.visibility = View.GONE
        } else {
            tvHabitsEmpty.visibility = View.GONE
            layoutHabitsSummaryChips.visibility = View.VISIBLE

            habits.habitRows.forEach { row ->
                val habit = appComponent.habitList.getByUUID(row.habitRef.uuid)
                val rowView = LayoutInflater.from(this).inflate(R.layout.item_container_stat_habit, layoutHabitRows, false)

                val colorView = rowView.findViewById<View>(R.id.viewHabitColor)
                val nameView = rowView.findViewById<TextView>(R.id.tvHabitName)
                val badgeView = rowView.findViewById<TextView>(R.id.tvHabitGoalBadge)
                val progressView = rowView.findViewById<TextView>(R.id.tvHabitProgress)
                val focusContribView = rowView.findViewById<TextView>(R.id.tvHabitFocusContribution)

                val habitColor = habit?.let { themeSwitcher.currentTheme.color(it.color).toInt() } ?: palette.accent
                colorView.background = GradientDrawable().apply {
                    setColor(habitColor)
                    cornerRadius = dp(5f)
                }

                nameView.text = row.name
                nameView.setTextColor(palette.onSurface)

                when (row.goalStatus) {
                    HabitGoalStatus.SATISFIED -> {
                        badgeView.text = getString(R.string.container_statistics_habits_reached)
                        badgeView.background = GradientDrawable().apply {
                            setColor(Color.parseColor("#2E7D32")) // Material Green
                            cornerRadius = dp(4f)
                        }
                        badgeView.setTextColor(Color.WHITE)
                    }
                    HabitGoalStatus.NOT_SATISFIED -> {
                        badgeView.text = getString(R.string.container_statistics_habits_incomplete)
                        badgeView.background = GradientDrawable().apply {
                            setColor(Color.parseColor("#E65100")) // Material Orange
                            cornerRadius = dp(4f)
                        }
                        badgeView.setTextColor(Color.WHITE)
                    }
                    else -> {
                        badgeView.text = getString(R.string.container_statistics_habits_unknown)
                        badgeView.background = GradientDrawable().apply {
                            setColor(palette.surfaceVariant)
                            cornerRadius = dp(4f)
                        }
                        badgeView.setTextColor(palette.onSurfaceVariant)
                    }
                }

                progressView.setTextColor(palette.onSurfaceVariant)
                if (row.isNumerical) {
                    val currentVal = row.totalValue ?: 0L
                    val targetVal = row.targetValue ?: 0.0
                    val unitStr = row.unit?.takeIf { it.isNotEmpty() }?.let { " $it" } ?: ""
                    progressView.text = "${currentVal} / ${formatNumber(targetVal)}$unitStr"
                } else {
                    progressView.text = "${row.completedDaysCount} / ${row.totalDaysCount} d"
                }

                if (row.contributionValue > 0L) {
                    focusContribView.visibility = View.VISIBLE
                    val valueStr = if (row.unit.equals("min", ignoreCase = true)) {
                        formatDuration(row.contributionValue * 60000L)
                    } else if (row.unit.equals("s", ignoreCase = true) || row.unit.equals("sec", ignoreCase = true)) {
                        formatDuration(row.contributionValue * 1000L)
                    } else if (row.unit.equals("h", ignoreCase = true) || row.unit.equals("hours", ignoreCase = true)) {
                        formatDuration(row.contributionValue * 3600000L)
                    } else {
                        "${row.contributionValue} ${row.unit.orEmpty()}".trim()
                    }
                    focusContribView.text = getString(R.string.container_statistics_habits_focus_contribution, valueStr)
                    focusContribView.setTextColor(palette.accent)
                } else {
                    focusContribView.visibility = View.GONE
                }

                layoutHabitRows.addView(rowView)
            }
        }
    }

    private fun formatNumber(value: Double): String {
        return if (value % 1.0 == 0.0) {
            value.toLong().toString()
        } else {
            String.format(Locale.getDefault(), "%.1f", value)
        }
    }

    private fun formatDuration(millis: Long): String {
        val totalMinutes = millis / 60000
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        val isRussian = Locale.getDefault().language == "ru"
        val hUnit = if (isRussian) "ч" else "h"
        val mUnit = if (isRussian) "мин" else "m"
        return if (hours > 0) {
            if (minutes > 0) "${hours} $hUnit ${minutes} $mUnit" else "${hours} $hUnit"
        } else {
            "${minutes} $mUnit"
        }
    }

    private fun formatDayLabel(date: LocalDate): String {
        val isRussian = Locale.getDefault().language == "ru"
        return when (date.dayOfWeek) {
            DayOfWeek.MONDAY -> if (isRussian) "Пн" else "Mo"
            DayOfWeek.TUESDAY -> if (isRussian) "Вт" else "Tu"
            DayOfWeek.WEDNESDAY -> if (isRussian) "Ср" else "We"
            DayOfWeek.THURSDAY -> if (isRussian) "Чт" else "Th"
            DayOfWeek.FRIDAY -> if (isRussian) "Пт" else "Fr"
            DayOfWeek.SATURDAY -> if (isRussian) "Сб" else "Sa"
            DayOfWeek.SUNDAY -> if (isRussian) "Вс" else "Su"
        }
    }

    private fun formatDateRange(range: ContainerStatsRange): String {
        return if (range.from == range.to) {
            "${range.from.day} ${monthName(range.from.month)} ${range.from.year}"
        } else if (range.from.year == range.to.year) {
            "${range.from.day} ${monthName(range.from.month)} — ${range.to.day} ${monthName(range.to.month)} ${range.to.year}"
        } else {
            "${range.from.day} ${monthName(range.from.month)} ${range.from.year} — ${range.to.day} ${monthName(range.to.month)} ${range.to.year}"
        }
    }

    private fun monthName(month: Int): String {
        val isRussian = Locale.getDefault().language == "ru"
        return when (month) {
            1 -> if (isRussian) "янв" else "Jan"
            2 -> if (isRussian) "фев" else "Feb"
            3 -> if (isRussian) "мар" else "Mar"
            4 -> if (isRussian) "апр" else "Apr"
            5 -> if (isRussian) "май" else "May"
            6 -> if (isRussian) "июн" else "Jun"
            7 -> if (isRussian) "июл" else "Jul"
            8 -> if (isRussian) "авг" else "Aug"
            9 -> if (isRussian) "сен" else "Sep"
            10 -> if (isRussian) "окт" else "Oct"
            11 -> if (isRussian) "ноя" else "Nov"
            12 -> if (isRussian) "дек" else "Dec"
            else -> ""
        }
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        if (appComponent.session.isNextArchitecture) {
            menu.add(0, MENU_OPEN_UNIFIED, 0, R.string.container_statistics_action_open_unified)
                .setIcon(R.drawable.ic_nav_reports)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        }
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == MENU_OPEN_UNIFIED) {
            val intent = org.isoron.uhabits.activities.main.MainActivity.intent(
                this,
                org.isoron.uhabits.activities.main.MainDestination.STATISTICS,
                statisticsContainerId = targetContainerId.value,
                statisticsIncludeDescendants = (currentScope == ContainerScopeMode.SUBTREE)
            )
            startActivity(intent)
            return true
        }
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    companion object {
        private const val MENU_OPEN_UNIFIED = 0x5101
        const val EXTRA_CONTAINER_ID = "org.isoron.uhabits.CONTAINER_ID"

        fun start(context: Context, containerId: ContainerId) {
            val intent = Intent(context, ContainerStatisticsActivity::class.java).apply {
                putExtra(EXTRA_CONTAINER_ID, containerId.value)
            }
            context.startActivity(intent)
        }
    }
}
