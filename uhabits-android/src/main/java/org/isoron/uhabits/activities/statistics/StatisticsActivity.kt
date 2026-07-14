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
 * Loop Habit Tracker is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.isoron.uhabits.activities.statistics

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.util.Log
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.view.animation.DecelerateInterpolator
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.doOnPreDraw
import androidx.fragment.app.Fragment
import com.google.android.material.tabs.TabLayout
import org.isoron.platform.time.*
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.AndroidThemeSwitcher
import org.isoron.uhabits.activities.common.theme.MainTabsThemeBridge
import org.isoron.uhabits.activities.main.MainActivity
import org.isoron.uhabits.activities.main.MainDestination
import org.isoron.uhabits.activities.main.MainNavigationHost
import org.isoron.uhabits.activities.common.views.ScoreChart
import org.isoron.uhabits.core.models.*
import org.isoron.uhabits.core.ui.screens.statistics.*
import org.isoron.uhabits.core.ui.screens.statistics.formatStatisticsValue
import org.isoron.uhabits.databinding.ActivityStatisticsBinding
import org.isoron.uhabits.intents.IntentFactory
import org.isoron.platform.gui.toInt
import org.isoron.uhabits.utils.applyToolbarInsets
import org.isoron.uhabits.utils.dp
import org.isoron.uhabits.utils.sres
import java.util.Locale
import kotlin.math.roundToInt

class StatisticsFragment : Fragment(), ModelObservable.Listener {
    private lateinit var themeSwitcher: AndroidThemeSwitcher
    private var viewBinding: ActivityStatisticsBinding? = null
    private val binding get() = viewBinding!!
    private val component
        get() = (requireContext().applicationContext as HabitsApplication).component
    private val sres get() = binding.root.sres
    private val palette get() = MainTabsThemeBridge.resolve(requireContext())

    internal enum class ReportTab { DAY, WEEK, MONTH, YEAR, ALL }
    internal var currentTab = ReportTab.DAY
    private lateinit var currentAnchorDate: LocalDate
    private lateinit var dateFormatter: JavaLocalDateFormatter
    private var activeReportGeneration = 0
    private var hasRenderedStatisticsOnce = false
    private var pendingReportRunnable: Runnable? = null
    private var pendingReportForceRender = false
    private var lastRenderedReportKey: ReportKey? = null
    private val reportRequestHandler = Handler(Looper.getMainLooper())
    private var dbVersion = 0

    private var currentFilters = StatisticsFilterState()

    internal data class ReportKey(
        val tab: ReportTab,
        val start: LocalDate,
        val end: LocalDate,
        val filters: StatisticsFilterState,
        val habitCount: Int,
        val dbVersion: Int
    )

    private fun clampAnchorDateToCurrentPeriod() {
        val firstWeekdayNum = getFirstWeekdayNumberAccordingToLocale()
        currentAnchorDate = clampAnchorDateToLatestAllowed(currentAnchorDate, currentTab, firstWeekdayNum)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        themeSwitcher = AndroidThemeSwitcher(requireActivity(), component.preferences)
        themeSwitcher.apply()

        viewBinding = ActivityStatisticsBinding.inflate(inflater, container, false)
        binding.root.setBackgroundColor(palette.background)
        binding.toolbar.root.apply {
            applyToolbarInsets()
            visibility = View.GONE
            minimumHeight = 0
            layoutParams = layoutParams.apply {
                height = ViewGroup.LayoutParams.WRAP_CONTENT
            }
        }
        binding.tabLayout.setBackgroundColor(palette.background)
        binding.tabLayout.setTabTextColors(palette.onSurfaceVariant, palette.onSurface)
        binding.tabLayout.setSelectedTabIndicatorColor(palette.accent)
        binding.dateNavigationBar.setBackgroundColor(palette.surface)
        binding.filtersScrollView.setBackgroundColor(palette.surface)
        binding.tvDateRange.setTextColor(palette.onSurface)
        binding.btnPrev.imageTintList = ColorStateList.valueOf(palette.onSurface)
        binding.btnNext.imageTintList = ColorStateList.valueOf(palette.onSurface)
        binding.refreshProgressBar.indeterminateTintList = ColorStateList.valueOf(palette.accent)

        val activity = requireActivity() as AppCompatActivity
        activity.window.statusBarColor = palette.background

        dateFormatter = JavaLocalDateFormatter(Locale.getDefault())
        currentTab = ReportTab.values().getOrElse(
            savedInstanceState?.getInt(STATE_TAB) ?: 0
        ) { ReportTab.DAY }
        currentAnchorDate = savedInstanceState?.let {
            LocalDate(
                it.getInt(STATE_YEAR),
                it.getInt(STATE_MONTH),
                it.getInt(STATE_DAY)
            )
        } ?: getToday()
        clampAnchorDateToCurrentPeriod()

        setupTabs()
        setupListeners()
        setupFilters()
        requestReportUpdate("create_view", delayMs = INITIAL_REPORT_DELAY_MS)
        return binding.root
    }

    override fun onResume() {
        super.onResume()
        component.habitList.observable.addListener(this)
        viewBinding?.let {
            requestReportUpdate("resume", delayMs = INITIAL_REPORT_DELAY_MS)
        }
        (activity as? MainNavigationHost)?.setHabitCreationAvailable(false)
    }

    override fun onPause() {
        component.habitList.observable.removeListener(this)
        cancelPendingReportUpdate()
        super.onPause()
    }

    override fun onModelChange() {
        dbVersion++
        requestReportUpdate("db_change")
    }

    fun refresh() {
        if (viewBinding != null && isAdded) {
            requestReportUpdate("external_refresh", forceRender = true)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_TAB, currentTab.ordinal)
        outState.putInt(STATE_YEAR, currentAnchorDate.year)
        outState.putInt(STATE_MONTH, currentAnchorDate.month)
        outState.putInt(STATE_DAY, currentAnchorDate.day)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroyView() {
        activeReportGeneration++
        cancelPendingReportUpdate()
        hasRenderedStatisticsOnce = false
        lastRenderedReportKey = null
        viewBinding = null
        super.onDestroyView()
    }

    private fun setupTabs() {
        val tabLayout = binding.tabLayout
        tabLayout.addTab(tabLayout.newTab().setText(R.string.reports_tab_day))
        tabLayout.addTab(tabLayout.newTab().setText(R.string.reports_tab_week))
        tabLayout.addTab(tabLayout.newTab().setText(R.string.reports_tab_month))
        tabLayout.addTab(tabLayout.newTab().setText(R.string.reports_tab_year))
        tabLayout.addTab(tabLayout.newTab().setText(R.string.reports_tab_all))
        tabLayout.getTabAt(currentTab.ordinal)?.select()

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                currentTab = when (tab.position) {
                    0 -> ReportTab.DAY
                    1 -> ReportTab.WEEK
                    2 -> ReportTab.MONTH
                    3 -> ReportTab.YEAR
                    4 -> ReportTab.ALL
                    else -> ReportTab.DAY
                }
                clampAnchorDateToCurrentPeriod()
                requestReportUpdate("tab_selected")
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    private fun ReportTab.toStatisticsPeriod(): StatisticsPeriod = when (this) {
        ReportTab.DAY -> StatisticsPeriod.DAY
        ReportTab.WEEK -> StatisticsPeriod.WEEK
        ReportTab.MONTH -> StatisticsPeriod.MONTH
        ReportTab.YEAR -> StatisticsPeriod.YEAR
        ReportTab.ALL -> StatisticsPeriod.ALL
    }

    private fun setupListeners() {
        binding.btnPrev.setOnClickListener {
            navigateDate(-1)
        }
        binding.btnNext.setOnClickListener {
            navigateDate(1)
        }
    }

    private fun setupFilters() {
        binding.btnFilters.setOnClickListener {
            StatisticsFiltersBottomSheet.show(
                fragmentManager = childFragmentManager,
                initialState = currentFilters,
                blocks = component.habitList.getBlocks()
            ) { updated ->
                if (updated == currentFilters) return@show
                currentFilters = updated
                renderActiveFilterChips()
                requestReportUpdate("filter_selected", forceRender = true)
            }
        }
        renderActiveFilterChips()
    }

    private fun renderActiveFilterChips() {
        if (viewBinding == null) return
        binding.activeFilterChips.removeAllViews()

        val statusLabel = when (currentFilters.habitStatus) {
            StatisticsHabitStatusFilter.ACTIVE -> getString(R.string.reports_filter_active_only)
            StatisticsHabitStatusFilter.ARCHIVED -> getString(R.string.reports_filter_archived_only)
            StatisticsHabitStatusFilter.ALL -> getString(R.string.reports_filter_all_habits)
        }
        addActiveFilterChip(statusLabel) {
            currentFilters = currentFilters.copy(habitStatus = StatisticsHabitStatusFilter.ACTIVE)
        }

        currentFilters.sphereId?.let { sphereId ->
            component.habitList.getBlocks().firstOrNull { it.id == sphereId }?.let { block ->
                addActiveFilterChip(getLocalizedBlockName(block)) {
                    currentFilters = currentFilters.copy(sphereId = null)
                }
            }
        }

        if (currentFilters.goalType != StatisticsGoalTypeFilter.ALL) {
            addActiveFilterChip(
                getString(
                    if (currentFilters.goalType == StatisticsGoalTypeFilter.YES_NO) {
                        R.string.reports_filter_boolean_only
                    } else {
                        R.string.reports_filter_numerical_only
                    }
                )
            ) { currentFilters = currentFilters.copy(goalType = StatisticsGoalTypeFilter.ALL) }
        }

        currentFilters.tier?.let { tier ->
            addActiveFilterChip(tierLabel(tier)) {
                currentFilters = currentFilters.copy(tier = null)
            }
        }
    }

    private fun addActiveFilterChip(label: String, reset: () -> Unit) {
        val chip = layoutInflater.inflate(
            R.layout.statistics_filter_chip,
            binding.activeFilterChips,
            false
        ) as com.google.android.material.chip.Chip
        chip.text = label
        chip.isCloseIconVisible = true
        chip.setOnCloseIconClickListener {
            reset()
            renderActiveFilterChips()
            requestReportUpdate("filter_chip_removed", forceRender = true)
        }
        binding.activeFilterChips.addView(chip)
    }

    private fun navigateDate(direction: Int) {
        val firstWeekdayNum = getFirstWeekdayNumberAccordingToLocale()
        if (direction > 0 && isLatestAllowedPeriod(currentAnchorDate, currentTab, firstWeekdayNum)) {
            return
        }
        currentAnchorDate = when (currentTab) {
            ReportTab.DAY -> currentAnchorDate.plus(direction)
            ReportTab.WEEK -> currentAnchorDate.plus(direction * 7)
            ReportTab.MONTH -> {
                var y = currentAnchorDate.year
                var m = currentAnchorDate.month + direction
                if (m < 1) {
                    m = 12
                    y -= 1
                } else if (m > 12) {
                    m = 1
                    y += 1
                }
                LocalDate(y, m, 1)
            }
            ReportTab.YEAR -> {
                LocalDate(currentAnchorDate.year + direction, currentAnchorDate.month, currentAnchorDate.day)
            }
            ReportTab.ALL -> currentAnchorDate
        }
        clampAnchorDateToCurrentPeriod()
        requestReportUpdate("date_navigation")
    }

    private fun getActiveRange(): Pair<LocalDate, LocalDate> {
        val firstWeekdayNum = getFirstWeekdayNumberAccordingToLocale()
        return statisticsActiveRange(currentAnchorDate, currentTab, firstWeekdayNum)
    }

    private fun updateReportHeader(start: LocalDate, end: LocalDate) {
        if (currentTab == ReportTab.ALL) {
            binding.btnPrev.visibility = View.GONE
            binding.btnNext.visibility = View.GONE
            binding.tvDateRange.text = getString(
                R.string.statistics_since_date,
                dateFormatter.longFormat(start)
            )
        } else {
            binding.btnPrev.visibility = View.VISIBLE
            binding.btnNext.visibility = View.VISIBLE
            val rangeText = when (currentTab) {
                ReportTab.DAY -> dateFormatter.longFormat(start)
                ReportTab.WEEK -> "${dateFormatter.longFormat(start)} - ${dateFormatter.longFormat(end)}"
                ReportTab.MONTH -> "${dateFormatter.longMonthName(start)} ${start.year}"
                ReportTab.YEAR -> start.year.toString()
                else -> ""
            }
            binding.tvDateRange.text = rangeText

            val firstWeekdayNum = getFirstWeekdayNumberAccordingToLocale()
            val nextEnabled = !isLatestAllowedPeriod(currentAnchorDate, currentTab, firstWeekdayNum)
            binding.btnNext.isEnabled = nextEnabled
            binding.btnNext.alpha = if (nextEnabled) 1.0f else 0.35f
        }
    }

    private fun showInitialLoading() {
        binding.refreshProgressBar.visibility = View.GONE
        binding.reportContentContainer.alpha = 1f
        binding.reportContentContainer.removeAllViews()
        val loadingText = TextView(requireContext()).apply {
            text = getString(R.string.reports_loading)
            gravity = Gravity.CENTER
            textSize = 15f
            setPadding(0, dp(40f).toInt(), 0, 0)
            setTextColor(palette.onSurfaceVariant)
        }
        binding.reportContentContainer.addView(loadingText)
    }

    private fun showRefreshingState() {
        binding.refreshProgressBar.visibility = View.VISIBLE
        binding.reportContentContainer.alpha = 0.5f
    }

    private fun hideRefreshingState() {
        binding.refreshProgressBar.visibility = View.GONE
        binding.reportContentContainer.alpha = 1f
    }

    private fun requestReportUpdate(
        _reason: String,
        delayMs: Long = REPORT_UPDATE_COALESCE_MS,
        forceRender: Boolean = false
    ) {
        if (viewBinding == null || !isAdded) return
        if (!hasRenderedStatisticsOnce && binding.reportContentContainer.childCount == 0) {
            showInitialLoading()
        }
        pendingReportForceRender = pendingReportForceRender || forceRender
        pendingReportRunnable?.let(reportRequestHandler::removeCallbacks)
        val runnable = Runnable {
            pendingReportRunnable = null
            val shouldForceRender = pendingReportForceRender
            pendingReportForceRender = false
            if (viewBinding == null || !isAdded) return@Runnable
            val key = buildReportKey() ?: return@Runnable
            if (!shouldForceRender && hasRenderedStatisticsOnce && key == lastRenderedReportKey) {
                hideRefreshingState()
                return@Runnable
            }
            updateReport(key, shouldForceRender)
        }
        pendingReportRunnable = runnable
        reportRequestHandler.postDelayed(runnable, delayMs)
    }

    private fun cancelPendingReportUpdate() {
        pendingReportRunnable?.let(reportRequestHandler::removeCallbacks)
        pendingReportRunnable = null
        pendingReportForceRender = false
    }

    private fun buildReportKey(): ReportKey? {
        if (viewBinding == null || !isAdded) return null
        val (start, end) = getActiveRange()
        return ReportKey(
            tab = currentTab,
            start = start,
            end = end,
            filters = currentFilters,
            habitCount = runCatching { component.habitList.size() }.getOrDefault(-1),
            dbVersion = dbVersion
        )
    }

    private fun performViewUpdate(result: StatisticsReportState) {
        val scrollView = binding.reportContentContainer.parent as? ScrollView
        val currentScrollY = scrollView?.scrollY ?: 0

        binding.reportContentContainer.removeAllViews()
        if (result.habits.isEmpty()) {
            addNoDataView(noHabits = true)
        } else if (result.overallProgress == null) {
            addNoDataView(noHabits = false)
        } else {
            renderStatistics(result)
        }

        scrollView?.post {
            if (viewBinding != null) {
                scrollView.scrollTo(0, currentScrollY)
            }
        }
    }

    private fun renderReport(result: StatisticsReportState, generation: Int, fullReveal: Boolean) {
        if (viewBinding == null || !isAdded || generation != activeReportGeneration) return
        hideRefreshingState()
        performViewUpdate(result)
    }

    private fun updateReport(reportKey: ReportKey, forceRender: Boolean) {
        val start = reportKey.start
        val end = reportKey.end
        updateReportHeader(start, end)

        if (!hasRenderedStatisticsOnce) {
            showInitialLoading()
        } else {
            showRefreshingState()
        }

        val habits = component.habitList.toList()
        val taskGeneration = ++activeReportGeneration

        component.taskRunner.execute(object : org.isoron.uhabits.core.tasks.Task {
            private var calculatedData: StatisticsReportState? = null

            override suspend fun doInBackground() {
                calculatedData = StatisticsReportStateBuilder.build(
                    habits = habits,
                    period = reportKey.tab.toStatisticsPeriod(),
                    start = start,
                    end = end,
                    today = getToday(),
                    firstWeekday = component.preferences.firstWeekday,
                    filters = reportKey.filters
                )
            }

            override fun onPostExecute() {
                if (viewBinding == null || !isAdded || taskGeneration != activeReportGeneration) return
                if (reportKey != buildReportKey()) return
                val result = calculatedData ?: return
                updateReportHeader(result.start, result.end)

                val isFirstRender = !hasRenderedStatisticsOnce
                if (isFirstRender) {
                    renderReport(result, taskGeneration, fullReveal = true)
                    hasRenderedStatisticsOnce = true
                } else {
                    renderReport(result, taskGeneration, fullReveal = false)
                }
                lastRenderedReportKey = reportKey
            }
        })
    }

    private fun addNoDataView(noHabits: Boolean) {
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(16f).toInt(), dp(48f).toInt(), dp(16f).toInt(), dp(48f).toInt())
        }
        container.addView(
            TextView(requireContext()).apply {
                text = getString(
                    if (noHabits) R.string.statistics_no_habits_for_filters
                    else R.string.statistics_no_history_for_period
                )
                gravity = Gravity.CENTER
                textSize = 15f
                setTextColor(palette.onSurfaceVariant)
            }
        )
        if (noHabits && currentFilters != StatisticsFilterState()) {
            container.addView(
                TextView(requireContext()).apply {
                    text = getString(R.string.statistics_reset_filters)
                    gravity = Gravity.CENTER
                    textSize = 14f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(palette.accent)
                    minHeight = dp(48f).toInt()
                    setPadding(dp(16f).toInt(), dp(14f).toInt(), dp(16f).toInt(), 0)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        currentFilters = StatisticsFilterState()
                        renderActiveFilterChips()
                        requestReportUpdate("filters_reset", forceRender = true)
                    }
                }
            )
        }
        binding.reportContentContainer.addView(container)
    }

    private fun renderStatistics(result: StatisticsReportState) {
        addSummaryCard(result)
        when (result.period) {
            StatisticsPeriod.DAY -> {
                addHabitsCard(result)
                addInsightsCard(result)
            }
            StatisticsPeriod.WEEK -> {
                addTrendCard(result)
                addRhythmCard(result)
                addHabitsCard(result)
                addFocusBySphereCard(result)
                addInsightsCard(result)
            }
            StatisticsPeriod.MONTH -> {
                addTrendCard(result)
                addCalendarCard(result)
                addRhythmCard(result)
                addStabilityCard(result)
                addHabitsCard(result)
                addFocusBySphereCard(result)
                addInsightsCard(result)
            }
            StatisticsPeriod.YEAR, StatisticsPeriod.ALL -> {
                addTrendCard(result)
                addCalendarCard(result)
                addRhythmCard(result)
                addStabilityCard(result)
                addHabitsCard(result)
                addFocusBySphereCard(result)
                addInsightsCard(result)
            }
        }
    }

    private fun addSummaryCard(result: StatisticsReportState) {
        val titleRes = when (result.period) {
            StatisticsPeriod.DAY -> R.string.statistics_daily_overview_title
            StatisticsPeriod.WEEK -> R.string.statistics_weekly_overview_title
            StatisticsPeriod.MONTH -> R.string.statistics_monthly_overview_title
            StatisticsPeriod.YEAR -> R.string.statistics_yearly_overview_title
            StatisticsPeriod.ALL -> R.string.statistics_all_time_overview_title
        }
        val (card, content) = createCard(getString(titleRes))
        val progress = result.overallProgress ?: 0.0

        val hero = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(120f).toInt()
        }

        val leftColumn = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(summaryText(getString(R.string.statistics_average_progress), 12f, palette.onSurfaceVariant))
            addView(
                summaryText(
                    if (result.period == StatisticsPeriod.DAY) {
                        getString(
                            R.string.statistics_completed_of_total,
                            result.completedHabits,
                            result.habits.size
                        )
                    } else {
                        resources.getQuantityString(
                            R.plurals.statistics_habits_count,
                            result.habits.size,
                            result.habits.size
                        )
                    },
                    16f,
                    palette.onSurface,
                    bold = true
                )
            )
            result.comparisonDelta?.let { delta ->
                val points = (delta * 100).roundToInt()
                val value = if (points > 0) "+$points%" else "$points%"
                addView(
                    summaryText(
                        getString(R.string.statistics_vs_previous_period, value),
                        12f,
                        if (points >= 0) palette.accent else palette.onSurfaceVariant
                    )
                )
            }
            if (result.focusMinutes > 0.0) {
                addView(
                    summaryText(
                        getString(
                            R.string.statistics_focus_total,
                            formatFocusDuration(result.focusMinutes)
                        ),
                        13f,
                        palette.onSurfaceVariant
                    ).apply { setPadding(0, dp(6f).toInt(), 0, 0) }
                )
            }
        }

        val tierDataList = if (component.preferences.isDayTiersEnabled) {
            result.tiers.map { tier ->
                org.isoron.uhabits.activities.statistics.views.StatisticsOverviewRectView.TierData(
                    progress = tier.progress?.toFloat() ?: 0f,
                    label = tierLabel(tier.tier),
                    color = tierColor(tier.tier),
                    trackColor = MainTabsThemeBridge.withAlpha(
                        palette.onSurfaceVariant,
                        if (palette.isPureBlack) 0.14f else 0.18f
                    )
                )
            }
        } else {
            listOf(
                org.isoron.uhabits.activities.statistics.views.StatisticsOverviewRectView.TierData(
                    progress = progress.toFloat(),
                    label = getString(R.string.reports_metric_completion_rate),
                    color = palette.accent,
                    trackColor = MainTabsThemeBridge.withAlpha(
                        palette.onSurfaceVariant,
                        if (palette.isPureBlack) 0.14f else 0.18f
                    )
                )
            )
        }

        val rightColumn = FrameLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(dp(120f).toInt(), dp(120f).toInt()).apply {
                leftMargin = dp(16f).toInt()
            }
            val rectView = org.isoron.uhabits.activities.statistics.views.StatisticsOverviewRectView(requireContext()).apply {
                setData(tierDataList, displayAtZero = areSystemAnimationsEnabled())
                if (areSystemAnimationsEnabled()) {
                    animateProgress(1000L)
                }
            }
            addView(rectView)
        }

        hero.addView(leftColumn)
        hero.addView(rightColumn)
        content.addView(hero)
        binding.reportContentContainer.addView(card)
    }

    private fun summaryText(textValue: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(requireContext()).apply {
            text = textValue
            textSize = size
            setTextColor(color)
            fontFeatureSettings = "tnum"
            if (bold) setTypeface(null, Typeface.BOLD)
        }

    private fun addTrendCard(result: StatisticsReportState) {
        val points = result.trend
        if (points.isEmpty()) return
        val (card, content) = createCard(getString(R.string.reports_chart_completion))

        val currentPoints = points.map { pt ->
            org.isoron.uhabits.activities.statistics.views.StatisticsTrendLineView.TrendPoint(
                date = pt.end,
                progress = pt.progress ?: 0.0,
                label = when (result.period) {
                    StatisticsPeriod.WEEK -> dateFormatter.shortWeekdayName(pt.end.dayOfWeek)
                    StatisticsPeriod.MONTH -> pt.end.day.toString()
                    StatisticsPeriod.YEAR -> dateFormatter.shortMonthName(pt.end)
                    else -> pt.end.toString()
                }
            )
        }

        val trendView = org.isoron.uhabits.activities.statistics.views.StatisticsTrendLineView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(180f).toInt()
            )
            setData(
                current = currentPoints,
                previous = emptyList(),
                accentColor = palette.accent,
                onSurfaceVariant = palette.onSurfaceVariant,
                divider = palette.divider,
                surface = palette.surface
            )
        }
        content.addView(trendView)
        binding.reportContentContainer.addView(card)
    }

    private fun addRhythmCard(result: StatisticsReportState) {
        val values = result.weekdayPattern
        if (values.size != 7 || values.any { it.observations < 4 || it.progress == null }) return
        val (card, content) = createCard(getString(R.string.reports_weekday_frequency))
        val rhythmView = org.isoron.uhabits.activities.statistics.views.StatisticsWeekRhythmView(requireContext()).apply {
            setData(
                values.map { item ->
                    org.isoron.uhabits.activities.statistics.views.StatisticsWeekRhythmView.RhythmItem(
                        dayOfWeek = item.dayOfWeek,
                        progress = item.progress ?: 0.0,
                        label = dateFormatter.shortWeekdayName(item.dayOfWeek)
                    )
                },
                accentColor = palette.accent,
                onSurfaceVariant = palette.onSurfaceVariant,
                divider = palette.divider
            )
        }
        content.addView(rhythmView)
        binding.reportContentContainer.addView(card)
    }

    private fun addStabilityCard(result: StatisticsReportState) {
        if (result.calendar.isEmpty()) return
        val (card, content) = createCard(getString(R.string.reports_metric_best_streak))
        val currentStreak = result.habits.map { it.habit.streaks.getLatest()?.length ?: 0 }.maxOrNull() ?: 0
        val bestStreak = result.habits.map { it.habit.streaks.getBest(1).firstOrNull()?.length ?: 0 }.maxOrNull() ?: 0
        val perfect = result.calendar.count { it.progress != null && it.progress!! >= 1.0 }
        val partial = result.calendar.count { it.progress != null && it.progress!! > 0.0 && it.progress!! < 1.0 }
        val failed = result.calendar.count { it.progress != null && it.progress!! == 0.0 }

        val stabilityView = org.isoron.uhabits.activities.statistics.views.StatisticsStabilityView(requireContext()).apply {
            setData(
                currentStreak = currentStreak,
                bestStreak = bestStreak,
                perfect = perfect,
                partial = partial,
                failed = failed,
                perfectCol = palette.accent,
                partialCol = MainTabsThemeBridge.withAlpha(palette.accent, 0.5f),
                failedCol = palette.divider,
                onSurface = palette.onSurface,
                onSurfaceVariant = palette.onSurfaceVariant
            )
        }
        content.addView(stabilityView)
        binding.reportContentContainer.addView(card)
    }

    private fun addCalendarCard(result: StatisticsReportState) {
        if (result.calendar.isEmpty()) return
        val (card, content) = createCard(
            if (result.period == StatisticsPeriod.ALL) {
                getString(R.string.statistics_calendar_last_twelve_months)
            } else {
                getString(R.string.reports_activity_calendar)
            }
        )
        val calendarStart = if (result.period == StatisticsPeriod.ALL) {
            result.end.minus(364)
        } else {
            result.start
        }
        val calendar = StatisticsCalendarView(requireContext()).apply {
            setData(
                result.calendar.associateBy { it.date },
                calendarStart,
                result.end
            )
        }
        content.addView(
            HorizontalScrollView(requireContext()).apply {
                isHorizontalScrollBarEnabled = false
                addView(calendar)
            }
        )
        binding.reportContentContainer.addView(card)
    }

    private fun addHabitsCard(result: StatisticsReportState) {
        val (card, content) = createCard(getString(R.string.statistics_habits_title))
        result.habits
            .sortedWith(compareBy<StatisticsHabitResult> { it.progress }.thenBy { it.habit.position })
            .forEachIndexed { index, habitResult ->
                if (index > 0) {
                    content.addView(
                        View(requireContext()).apply {
                            layoutParams = LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                dp(1f).toInt().coerceAtLeast(1)
                            )
                            setBackgroundColor(palette.divider)
                        }
                    )
                }
                content.addView(createReportHabitRow(habitResult))
            }
        binding.reportContentContainer.addView(card)
    }

    private fun createReportHabitRow(result: StatisticsHabitResult): View {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(64f).toInt()
            val vertical = dp(9f).toInt()
            setPadding(0, vertical, 0, vertical)
            isClickable = true
            isFocusable = true
            background = selectableItemBackground()
            setOnClickListener {
                startActivity(IntentFactory().startShowHabitActivity(requireContext(), result.habit))
            }
        }
        row.addView(
            View(requireContext()).apply {
                val size = dp(10f).toInt()
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    rightMargin = dp(12f).toInt()
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(themeSwitcher.currentTheme.color(result.habit.color).toInt())
                }
            }
        )
        val texts = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(summaryText(result.habit.name, 15f, palette.onSurface))
            addView(summaryText(formatHabitResult(result), 13f, palette.onSurfaceVariant).apply {
                setPadding(0, dp(2f).toInt(), 0, 0)
            })
        }
        row.addView(texts)
        row.addView(
            summaryText("${(result.progress * 100).roundToInt()}%", 14f, palette.onSurface, bold = true).apply {
                gravity = Gravity.END
                setPadding(dp(12f).toInt(), 0, 0, 0)
            }
        )
        return row
    }

    private fun formatHabitResult(result: StatisticsHabitResult): String {
        val actual = result.actual.formatStatisticsValue()
        val target = result.target.formatStatisticsValue()
        val unit = result.unit.trim()
        val values = if (unit.isEmpty()) "$actual / $target" else "$actual / $target $unit"
        val status = when (result.status) {
            StatisticsResultStatus.PENDING -> getString(R.string.statistics_status_pending)
            StatisticsResultStatus.ON_TRACK -> getString(R.string.statistics_status_on_track)
            StatisticsResultStatus.VIOLATED -> getString(R.string.statistics_status_violated)
            StatisticsResultStatus.MISSED -> getString(R.string.statistics_status_missed)
            StatisticsResultStatus.SKIPPED -> getString(R.string.statistics_status_skipped)
            StatisticsResultStatus.COMPLETED -> null
        }
        return if (status == null) values else "$values · $status"
    }

    private fun addFocusBySphereCard(result: StatisticsReportState) {
        if (!component.preferences.isHabitSpheresEnabled || result.focusBySphere.size < 2) return
        val blocks = component.habitList.getBlocks().associateBy { it.id }
        val maxMinutes = result.focusBySphere.maxOfOrNull { it.minutes } ?: return
        if (maxMinutes <= 0.0) return
        val (card, content) = createCard(getString(R.string.reports_focus_by_sphere))

        val sphereView = org.isoron.uhabits.activities.statistics.views.StatisticsSphereBalanceView(requireContext()).apply {
            setData(
                items = result.focusBySphere.sortedByDescending { it.minutes }.mapNotNull { item ->
                    val block = blocks[item.blockId] ?: return@mapNotNull null
                    org.isoron.uhabits.activities.statistics.views.StatisticsSphereBalanceView.SphereItem(
                        name = getLocalizedBlockName(block),
                        color = themeSwitcher.currentTheme.color(block.color).toInt(),
                        valueText = formatFocusDuration(item.minutes),
                        progress = item.minutes / maxMinutes
                    )
                },
                dividerColor = palette.divider,
                onSurface = palette.onSurface
            )
        }
        content.addView(sphereView)
        binding.reportContentContainer.addView(card)
    }

    private fun addInsightsCard(result: StatisticsReportState) {
        val (card, content) = createCard(getString(R.string.reports_metric_completion_rate))
        val sorted = result.habits.sortedByDescending { it.progress }
        val strong = sorted.filter { it.progress >= 0.8 }
        val attention = sorted.filter { it.progress < 0.5 }

        val insightsView = org.isoron.uhabits.activities.statistics.views.StatisticsHabitInsightsView(requireContext()).apply {
            setData(
                strong = strong.take(4),
                attention = attention.take(4),
                accentColor = palette.accent,
                onSurface = palette.onSurface,
                onSurfaceVariant = palette.onSurfaceVariant,
                divider = palette.divider,
                themeColorResolver = { colorIndex ->
                    themeSwitcher.currentTheme.color(PaletteColor(colorIndex)).toInt()
                }
            )
        }
        content.addView(insightsView)
        binding.reportContentContainer.addView(card)
    }

    private fun selectableItemBackground(): android.graphics.drawable.Drawable? {
        val attributes = requireContext().obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        return try {
            attributes.getDrawable(0)
        } finally {
            attributes.recycle()
        }
    }

    private fun formatFocusDuration(minutes: Double): String {
        val total = minutes.roundToInt().coerceAtLeast(0)
        val hours = total / 60
        val remainder = total % 60
        return when {
            hours == 0 -> getString(R.string.statistics_duration_minutes, remainder)
            remainder == 0 -> getString(R.string.statistics_duration_hours, hours)
            else -> getString(R.string.statistics_duration_hours_minutes, hours, remainder)
        }
    }

    private fun tierLabel(tier: DayTier): String {
        val resId = when (tier) {
            DayTier.MINIMUM -> org.isoron.uhabits.R.string.day_tier_badge_minimum
            DayTier.NORMAL -> org.isoron.uhabits.R.string.day_tier_badge_normal
            DayTier.IDEAL -> org.isoron.uhabits.R.string.day_tier_badge_ideal
            DayTier.OPTIONAL -> org.isoron.uhabits.R.string.day_tier_badge_optional_short
        }
        return getString(resId)
    }

    private fun tierColor(tier: DayTier): Int {
        val paletteColor = when (tier) {
            DayTier.MINIMUM -> PaletteColor(17)
            DayTier.NORMAL -> PaletteColor(5)
            DayTier.IDEAL -> PaletteColor(7)
            DayTier.OPTIONAL -> PaletteColor(13)
        }
        return themeSwitcher.currentTheme.color(paletteColor).toInt()
    }

    private fun createCard(titleText: String): Pair<LinearLayout, LinearLayout> {
        val cardContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(10f).toInt()
                leftMargin = dp(4f).toInt()
                rightMargin = dp(4f).toInt()
            }
            layoutParams = lp
            elevation = 0f
            val padding = dp(14f).toInt()
            setPadding(padding, padding, padding, padding)

            background = GradientDrawable().apply {
                setColor(palette.surface)
                cornerRadius = dp(component.preferences.statisticsCardCornerRadius.toFloat())
                setStroke(dp(1f).toInt().coerceAtLeast(1), palette.border)
            }
        }

        val titleView = TextView(requireContext()).apply {
            text = titleText
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(palette.onSurface)
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(10f).toInt()
            }
            layoutParams = lp
        }

        cardContainer.addView(titleView)

        val contentLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        cardContainer.addView(contentLayout)

        return Pair(cardContainer, contentLayout)
    }

    private fun getLocalizedBlockName(block: HabitBlock): String {
        return block.name
    }

    private fun areSystemAnimationsEnabled(): Boolean {
        return runCatching {
            android.provider.Settings.Global.getFloat(
                requireContext().contentResolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ) != 0f
        }.getOrDefault(true)
    }

    fun onTabTransitionStarted() {}
    fun onTabTransitionEnded() {}

    companion object {
        private const val STATE_TAB = "reports.tab"
        private const val STATE_YEAR = "reports.year"
        private const val STATE_MONTH = "reports.month"
        private const val STATE_DAY = "reports.day"
        private const val REPORT_UPDATE_COALESCE_MS = 80L
        private const val INITIAL_REPORT_DELAY_MS = 80L
    }
}

/** Compatibility entry point for existing internal intents. */
class StatisticsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(MainActivity.intent(this, MainDestination.STATISTICS))
        finish()
    }
}

data class StatisticsData(
    val start: LocalDate,
    val end: LocalDate,
    val totalDays: Int,
    val completedDays: Int,
    val totalFocusHours: Double,
    val limitViolations: Int,
    val missedGoals: Int,
    val completionPercentage: Int,
    val completedHabitsCount: Int,
    val bestStreak: Int,
    val bestSphereName: String?,
    val worstSphereName: String?,
    val scoreChartData: List<org.isoron.uhabits.core.models.Score>,
    val sphereFocusHours: List<Pair<HabitBlock, Double>>,
    val habitStats: List<HabitCompletionStat>,
    val weekdayFrequency: List<Pair<DayOfWeek, Double>>,
    val heatmapRates: Map<LocalDate, Float>,
    val tiers: List<StatisticsTierProgress>,
    val skippedCount: Int,
    val filteredHabits: List<Habit>
)

data class HabitCompletionStat(
    val habit: Habit,
    val completedDays: Int,
    val totalDays: Int
)

internal fun isLatestAllowedPeriod(
    anchorDate: LocalDate,
    tab: StatisticsFragment.ReportTab,
    firstWeekdayNum: Int
): Boolean {
    val today = getToday()
    return when (tab) {
        StatisticsFragment.ReportTab.DAY -> {
            anchorDate >= today
        }
        StatisticsFragment.ReportTab.WEEK -> {
            val firstWeekday = DayOfWeek.entries[firstWeekdayNum - 1]
            val anchorWeekStart = anchorDate.startOfWeek(firstWeekday)
            val todayWeekStart = today.startOfWeek(firstWeekday)
            anchorWeekStart >= todayWeekStart
        }
        StatisticsFragment.ReportTab.MONTH -> {
            val anchorMonthStart = anchorDate.startOfMonth()
            val todayMonthStart = today.startOfMonth()
            anchorMonthStart >= todayMonthStart
        }
        StatisticsFragment.ReportTab.YEAR -> {
            anchorDate.year >= today.year
        }
        StatisticsFragment.ReportTab.ALL -> true
    }
}

internal fun statisticsActiveRange(
    anchorDate: LocalDate,
    tab: StatisticsFragment.ReportTab,
    firstWeekdayNum: Int
): Pair<LocalDate, LocalDate> {
    val today = getToday()
    val range = when (tab) {
        StatisticsFragment.ReportTab.DAY -> Pair(anchorDate, anchorDate)
        StatisticsFragment.ReportTab.WEEK -> {
            val firstWeekday = DayOfWeek.entries[firstWeekdayNum - 1]
            val start = anchorDate.startOfWeek(firstWeekday)
            Pair(start, start.plus(6))
        }
        StatisticsFragment.ReportTab.MONTH -> {
            val start = anchorDate.startOfMonth()
            Pair(start, start.plus(start.monthLength - 1))
        }
        StatisticsFragment.ReportTab.YEAR -> {
            val start = LocalDate(anchorDate.year, 1, 1)
            Pair(start, LocalDate(anchorDate.year, 12, 31))
        }
        StatisticsFragment.ReportTab.ALL -> Pair(LocalDate(1970, 1, 1), today)
    }
    val clampedEnd = minOf(range.second, today)
    val clampedStart = if (range.first.isNewerThan(clampedEnd)) clampedEnd else range.first
    return Pair(clampedStart, clampedEnd)
}

internal fun clampAnchorDateToLatestAllowed(
    anchorDate: LocalDate,
    tab: StatisticsFragment.ReportTab,
    firstWeekdayNum: Int
): LocalDate {
    val today = getToday()
    if (!isLatestAllowedPeriod(anchorDate, tab, firstWeekdayNum)) {
        return anchorDate
    }
    return today
}
