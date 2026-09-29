/*
 * Copyright (C) 2016-2026 Álinson Santos Xavier <git@axavier.org>
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

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.style.RelativeSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.Spanned
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.ColorUtils
import androidx.fragment.app.Fragment
import com.google.android.material.tabs.TabLayout
import org.isoron.uhabits.activities.common.views.CompactPopupMenu
import org.isoron.uhabits.activities.habits.show.views.showPeriodSelectorPopup
import org.isoron.platform.gui.toInt
import org.isoron.platform.time.*
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.AndroidThemeSwitcher
import org.isoron.uhabits.activities.common.theme.MainTabsThemeBridge
import org.isoron.uhabits.activities.main.MainActivity
import org.isoron.uhabits.activities.main.MainDestination
import org.isoron.uhabits.activities.main.MainNavigationHost
import org.isoron.uhabits.activities.statistics.views.StatisticsMonthCalendarView
import org.isoron.uhabits.activities.statistics.views.StatisticsPageTransitionHost
import org.isoron.uhabits.activities.statistics.views.StatisticsPercentageBarChart
import org.isoron.uhabits.activities.statistics.views.StatisticsWeekRhythmView
import org.isoron.uhabits.core.models.*
import org.isoron.uhabits.core.ui.screens.statistics.*
import org.isoron.uhabits.core.ui.screens.statistics.formatStatisticsValue
import org.isoron.uhabits.databinding.ActivityStatisticsBinding
import org.isoron.uhabits.intents.IntentFactory
import org.isoron.uhabits.utils.InterfaceUtils
import org.isoron.uhabits.utils.applyToolbarInsets
import org.isoron.uhabits.utils.dp
import java.util.Locale
import kotlin.math.roundToInt

class StatisticsFragment : Fragment(), ModelObservable.Listener {
    private fun dp(value: Float): Float = InterfaceUtils.dpToPixels(requireContext(), value)
    private lateinit var themeSwitcher: AndroidThemeSwitcher
    private var viewBinding: ActivityStatisticsBinding? = null
    private val binding get() = viewBinding!!
    private val component
        get() = (requireContext().applicationContext as HabitsApplication).component
    private val palette get() = MainTabsThemeBridge.resolve(requireContext())

    internal enum class ReportTab { DAY, WEEK, MONTH, YEAR, ALL }
    internal var currentTab = ReportTab.DAY
    private lateinit var currentAnchorDate: LocalDate
    private lateinit var dateFormatter: JavaLocalDateFormatter
    @Volatile private var activeReportGeneration = 0
    private var hasRenderedStatisticsOnce = false
    private var pendingReportRunnable: Runnable? = null
    private var pendingReportForceRender = false
    private var lastRenderedReportKey: ReportKey? = null
    private val reportRequestHandler = Handler(Looper.getMainLooper())
    private var dbVersion = 0
    private var pendingTransitionDirection = 0
    private var loadingIndicatorRunnable: Runnable? = null

    private var currentFilters = StatisticsFilterState()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        childFragmentManager.setFragmentResultListener(
            StatisticsFiltersBottomSheet.RESULT_KEY, this
        ) { _, result ->
            val updated = currentFilters.copy(
                sphereId = result.getLong("sphere_id", Long.MIN_VALUE).takeUnless { it == Long.MIN_VALUE },
                habitStatus = enumValueOrDefault(result.getString("habit_status"), StatisticsHabitStatusFilter.ACTIVE),
                goalType = enumValueOrDefault(result.getString("goal_type"), StatisticsGoalTypeFilter.ALL)
            )
            if (updated != currentFilters) {
                currentFilters = updated
                renderActiveFilterChips()
                requestReportUpdate("filter_selected", forceRender = true)
            }
        }
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String?, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == value } ?: fallback

    internal data class ReportKey(
        val tab: ReportTab,
        val start: LocalDate,
        val end: LocalDate,
        val filters: StatisticsFilterState,
        val habitCount: Int,
        val dbVersion: Int
    )

    private fun clampAnchorDateToCurrentPeriod() {
        val firstWeekday = component.preferences.firstWeekday
        currentAnchorDate = clampAnchorDateToLatestAllowed(currentAnchorDate, currentTab, firstWeekday)
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
        binding.tabLayout.tabRippleColor = ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
        binding.dateNavigationBar.setBackgroundColor(palette.surface)
        binding.filtersScrollView.setBackgroundColor(palette.surface)
        binding.filterScope.setTextColor(palette.onSurfaceVariant)
        binding.btnFilters.setTextColor(palette.accent)
        binding.btnFilters.iconTint = ColorStateList.valueOf(palette.accent)
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
        currentFilters = savedInstanceState?.let { state ->
            StatisticsFilterState(
                sphereId = state.getLong(STATE_FILTER_SPHERE, Long.MIN_VALUE).takeUnless { it == Long.MIN_VALUE },
                habitStatus = enumValueOrDefault(state.getString(STATE_FILTER_STATUS), StatisticsHabitStatusFilter.ACTIVE),
                goalType = enumValueOrDefault(state.getString(STATE_FILTER_GOAL), StatisticsGoalTypeFilter.ALL),
                tierScope = enumValueOrDefault(state.getString(STATE_FILTER_TIER_SCOPE), DayTierScope.MINIMUM),
                dayTiersEnabled = component.preferences.isDayTiersEnabled,
                spheresEnabled = component.preferences.isHabitSpheresEnabled
            )
        } ?: StatisticsFilterState(
            dayTiersEnabled = component.preferences.isDayTiersEnabled,
            spheresEnabled = component.preferences.isHabitSpheresEnabled
        )
        clampAnchorDateToCurrentPeriod()

        setupTabs()
        setupListeners()
        setupScopeSelector()
        setupFilters()
        requestReportUpdate("create_view", delayMs = 0L)
        return binding.root
    }

    override fun onResume() {
        super.onResume()
        component.habitList.observable.addListener(this)
        viewBinding?.let {
            currentFilters = currentFilters.copy(
                dayTiersEnabled = component.preferences.isDayTiersEnabled,
                spheresEnabled = component.preferences.isHabitSpheresEnabled
            )
            setupScopeSelector()
            requestReportUpdate("resume", delayMs = 0L, forceRender = true)
        }
        (activity as? MainNavigationHost)?.setHabitCreationAvailable(false)
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && viewBinding != null) {
            currentFilters = currentFilters.copy(
                dayTiersEnabled = component.preferences.isDayTiersEnabled,
                spheresEnabled = component.preferences.isHabitSpheresEnabled
            )
            setupScopeSelector()
            requestReportUpdate("hidden_change", delayMs = 0L, forceRender = true)
        }
    }

    override fun onPause() {
        component.habitList.observable.removeListener(this)
        cancelPendingReportUpdate()
        super.onPause()
    }

    override fun onModelChange() {
        dbVersion++
        requestReportUpdate("db_change", delayMs = 0L, forceRender = true)
    }

    fun refresh() {
        if (viewBinding != null && isAdded) {
            requestReportUpdate("external_refresh", delayMs = 0L, forceRender = true)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_TAB, currentTab.ordinal)
        outState.putInt(STATE_YEAR, currentAnchorDate.year)
        outState.putInt(STATE_MONTH, currentAnchorDate.month)
        outState.putInt(STATE_DAY, currentAnchorDate.day)
        outState.putLong(STATE_FILTER_SPHERE, currentFilters.sphereId ?: Long.MIN_VALUE)
        outState.putString(STATE_FILTER_STATUS, currentFilters.habitStatus.name)
        outState.putString(STATE_FILTER_GOAL, currentFilters.goalType.name)
        outState.putString(STATE_FILTER_TIER_SCOPE, currentFilters.tierScope.name)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroyView() {
        activeReportGeneration++
        cancelPendingReportUpdate()
        cancelRefreshingIndicator()
        hasRenderedStatisticsOnce = false
        lastRenderedReportKey = null
        viewBinding = null
        super.onDestroyView()
    }

    private fun setupTabs() {
        val tabLayout = binding.tabLayout
        tabLayout.removeAllTabs()
        tabLayout.addTab(tabLayout.newTab().setText(R.string.reports_tab_day))
        tabLayout.addTab(tabLayout.newTab().setText(R.string.reports_tab_week))
        tabLayout.addTab(tabLayout.newTab().setText(R.string.reports_tab_month))
        tabLayout.addTab(tabLayout.newTab().setText(R.string.reports_tab_year))
        tabLayout.addTab(tabLayout.newTab().setText(R.string.reports_tab_all))
        tabLayout.getTabAt(currentTab.ordinal)?.select()

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                val newTab = when (tab.position) {
                    0 -> ReportTab.DAY
                    1 -> ReportTab.WEEK
                    2 -> ReportTab.MONTH
                    3 -> ReportTab.YEAR
                    4 -> ReportTab.ALL
                    else -> ReportTab.DAY
                }
                if (newTab != currentTab) {
                    pendingTransitionDirection = if (newTab.ordinal > currentTab.ordinal) 1 else -1
                    currentTab = newTab
                    clampAnchorDateToCurrentPeriod()
                    requestReportUpdate("tab_selected")
                }
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
        binding.btnPrev.background = null
        binding.btnNext.background = null
        quietPressFeedback(binding.btnPrev)
        quietPressFeedback(binding.btnNext)
        binding.btnPrev.setOnClickListener {
            navigateDate(-1)
        }
        binding.btnNext.setOnClickListener {
            navigateDate(1)
        }
        binding.tabLayout.onHorizontalSwipe = { direction ->
            binding.tabLayout.getTabAt((currentTab.ordinal + direction).coerceIn(0, ReportTab.entries.lastIndex))?.select()
        }
        binding.pageTransitionHost.onHorizontalSwipe = { direction ->
            navigateDate(direction)
        }
    }

    private fun setupScopeSelector() {
        if (!component.preferences.isDayTiersEnabled) {
            binding.scopeSelectorContainer.visibility = View.GONE
            return
        }
        binding.scopeSelectorContainer.visibility = View.VISIBLE
        binding.tvScopeLabel.setTextColor(palette.onSurfaceVariant)
        binding.tvScopeValue.setTextColor(palette.onSurface)
        binding.scopeSelectorArrow.imageTintList = ColorStateList.valueOf(palette.onSurfaceVariant)
        quietPressFeedback(binding.scopeSelectorContainer)

        updateScopeSelectorUi()

        binding.scopeSelectorContainer.setOnClickListener {
            val scopes = DayTierScope.entries
            val entries = scopes.mapIndexed { index, scope ->
                CompactPopupMenu.Entry.Item(
                    id = index,
                    title = scopeLabel(scope),
                    selected = scope == currentFilters.tierScope
                )
            }
            showPeriodSelectorPopup(
                anchor = binding.scopeSelectorContainer,
                arrow = binding.scopeSelectorArrow,
                entries = entries,
                onItemClick = { index ->
                    val selectedScope = scopes[index]
                    if (selectedScope != currentFilters.tierScope) {
                        currentFilters = currentFilters.copy(tierScope = selectedScope)
                        updateScopeSelectorUi()
                        requestReportUpdate("scope_change", forceRender = true)
                    }
                }
            )
        }
    }

    private fun updateScopeSelectorUi() {
        if (viewBinding == null) return
        binding.tvScopeValue.text = scopeLabel(currentFilters.tierScope)
    }

    private fun scopeLabel(scope: DayTierScope): String = when (scope) {
        DayTierScope.MINIMUM -> getString(R.string.tier_scope_minimum)
        DayTierScope.UP_TO_NORMAL -> getString(R.string.tier_scope_up_to_normal)
        DayTierScope.UP_TO_IDEAL -> getString(R.string.tier_scope_up_to_ideal)
        DayTierScope.ALL -> getString(R.string.tier_scope_all)
    }

    private fun setupFilters() {
        binding.btnCompare.setTextColor(palette.accent)
        binding.btnCompare.iconTint = ColorStateList.valueOf(palette.accent)
        binding.btnCompare.rippleColor = ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
        quietPressFeedback(binding.btnCompare)
        binding.btnCompare.setOnClickListener {
            val initialPeriod = when (currentTab) {
                ReportTab.DAY, ReportTab.WEEK -> "WEEK"
                ReportTab.MONTH -> "MONTH"
                ReportTab.YEAR -> "YEAR"
                ReportTab.ALL -> "ALL"
            }
            val intent = IntentFactory().startCompareHabitsActivity(requireContext()).apply {
                putExtra("initial_period", initialPeriod)
            }
            startActivity(intent)
        }

        binding.btnFilters.rippleColor = ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
        quietPressFeedback(binding.btnFilters)
        quietPressFeedback(binding.filterScope)
        binding.btnFilters.setOnClickListener {
            StatisticsFiltersBottomSheet.show(
                fragmentManager = childFragmentManager,
                initialState = currentFilters,
                blocks = component.habitList.getBlocks(),
                spheresEnabled = component.preferences.isHabitSpheresEnabled
            )
        }
        binding.filterScope.setOnClickListener { binding.btnFilters.performClick() }
        renderActiveFilterChips()
    }

    private fun renderActiveFilterChips() {
        if (viewBinding == null) return
        val activeFiltersCount = listOfNotNull(
            currentFilters.sphereId,
            currentFilters.habitStatus.takeIf { it != StatisticsHabitStatusFilter.ACTIVE },
            currentFilters.goalType.takeIf { it != StatisticsGoalTypeFilter.ALL }
        ).size

        val labels = mutableListOf<String>()
        currentFilters.sphereId?.let { sphereId ->
            component.habitList.getBlocks().firstOrNull { it.id == sphereId }?.let { block ->
                labels.add(block.name)
            }
        }
        if (currentFilters.habitStatus != StatisticsHabitStatusFilter.ACTIVE) {
            labels.add(when (currentFilters.habitStatus) {
                StatisticsHabitStatusFilter.ARCHIVED -> getString(R.string.reports_filter_archived_only)
                StatisticsHabitStatusFilter.ALL -> getString(R.string.reports_filter_all_habits)
                else -> ""
            })
        }
        if (currentFilters.goalType != StatisticsGoalTypeFilter.ALL) {
            labels.add(getString(
                if (currentFilters.goalType == StatisticsGoalTypeFilter.YES_NO) R.string.reports_filter_boolean_only
                else R.string.reports_filter_numerical_only
            ))
        }

        if (activeFiltersCount > 0) {
            val scopeText = labels.joinToString(" · ")
            binding.filterScope.text = scopeText
            binding.filterScope.contentDescription = scopeText
            binding.filterScope.visibility = View.VISIBLE
            binding.btnFilters.text = getString(R.string.statistics_filter_count, activeFiltersCount)
        } else {
            binding.filterScope.visibility = View.GONE
            binding.btnFilters.text = getString(R.string.statistics_filters_button)
        }
    }

    private fun navigateDate(direction: Int) {
        val firstWeekday = component.preferences.firstWeekday
        if (direction > 0 && isLatestAllowedPeriod(currentAnchorDate, currentTab, firstWeekday)) {
            return
        }
        pendingTransitionDirection = direction
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
        val firstWeekday = component.preferences.firstWeekday
        return statisticsActiveRange(currentAnchorDate, currentTab, firstWeekday)
    }

    private fun updateReportHeader(start: LocalDate, end: LocalDate) {
        if (currentTab == ReportTab.ALL) {
            binding.btnPrev.visibility = View.INVISIBLE
            binding.btnNext.visibility = View.INVISIBLE
            binding.tvDateRange.text = getString(R.string.statistics_since_date, dateFormatter.longFormat(start))
        } else {
            binding.btnPrev.visibility = View.VISIBLE
            binding.btnNext.visibility = View.VISIBLE
            val rangeText = when (currentTab) {
                ReportTab.DAY -> dateFormatter.longFormat(start)
                ReportTab.WEEK -> "${dateFormatter.longFormat(start)} - ${dateFormatter.longFormat(start.plus(6))}"
                ReportTab.MONTH -> "${dateFormatter.longMonthName(start)} ${start.year}"
                ReportTab.YEAR -> start.year.toString()
                else -> ""
            }
            binding.tvDateRange.text = rangeText

            val firstWeekday = component.preferences.firstWeekday
            val nextEnabled = !isLatestAllowedPeriod(currentAnchorDate, currentTab, firstWeekday)
            binding.btnNext.isEnabled = nextEnabled
            binding.btnNext.alpha = if (nextEnabled) 1.0f else 0.35f
        }
    }

    private fun createPageView(): Pair<ScrollView, LinearLayout> {
        val scrollView = ScrollView(requireContext()).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        val contentContainer = LinearLayout(requireContext()).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f).toInt(), 0, dp(16f).toInt(), dp(24f).toInt())
        }
        scrollView.addView(contentContainer)
        return Pair(scrollView, contentContainer)
    }

    private fun showInitialLoading() {
        cancelRefreshingIndicator()
        val (pageScroll, pageContainer) = createPageView()
        val loadingText = TextView(requireContext()).apply {
            text = getString(R.string.reports_loading)
            gravity = Gravity.CENTER
            textSize = 15f
            setPadding(0, dp(48f).toInt(), 0, 0)
            setTextColor(palette.onSurfaceVariant)
        }
        pageContainer.addView(loadingText)
        binding.pageTransitionHost.showInitialPage(pageScroll)
    }

    private fun cancelRefreshingIndicator() {
        loadingIndicatorRunnable?.let(reportRequestHandler::removeCallbacks)
        loadingIndicatorRunnable = null
        if (viewBinding != null) {
            binding.refreshProgressBar.visibility = View.GONE
        }
    }

    private fun requestReportUpdate(
        _reason: String,
        delayMs: Long = REPORT_UPDATE_COALESCE_MS,
        forceRender: Boolean = false
    ) {
        if (viewBinding == null || !isAdded) return
        activeReportGeneration++
        if (!hasRenderedStatisticsOnce && binding.pageTransitionHost.getCurrentPage() == null) {
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
                cancelRefreshingIndicator()
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
        val updatedFilters = currentFilters.copy(
            dayTiersEnabled = component.preferences.isDayTiersEnabled,
            spheresEnabled = component.preferences.isHabitSpheresEnabled
        )
        currentFilters = updatedFilters
        return ReportKey(
            tab = currentTab,
            start = start,
            end = end,
            filters = updatedFilters,
            habitCount = runCatching { component.habitList.size() }.getOrDefault(-1),
            dbVersion = dbVersion
        )
    }

    private fun performViewUpdate(result: StatisticsReportState, previous: StatisticsReportState?) {
        val (pageScroll, pageContainer) = createPageView()
        if (result.matchingHabits == 0) {
            addNoDataView(pageContainer, noHabits = true)
        } else {
            renderStatisticsReport(pageContainer, result, previous)
        }

        val direction = pendingTransitionDirection
        pendingTransitionDirection = 0

        if (!hasRenderedStatisticsOnce || binding.pageTransitionHost.getCurrentPage() == null) {
            binding.pageTransitionHost.showInitialPage(pageScroll)
        } else {
            binding.pageTransitionHost.transitionToPage(pageScroll, direction)
        }
    }

    private fun renderReport(result: StatisticsReportState, previous: StatisticsReportState?, generation: Int) {
        if (viewBinding == null || !isAdded || generation != activeReportGeneration) return
        cancelRefreshingIndicator()
        performViewUpdate(result, previous)
    }

    private fun updateReport(reportKey: ReportKey, forceRender: Boolean) {
        val start = reportKey.start
        val end = reportKey.end
        updateReportHeader(start, end)

        if (!hasRenderedStatisticsOnce) {
            showInitialLoading()
        }

        val habits = component.habitList.toList()
        val firstWeekday = component.preferences.firstWeekday
        val taskGeneration = ++activeReportGeneration

        component.taskRunner.execute(object : org.isoron.uhabits.core.tasks.Task {
            private var calculatedData: StatisticsReportState? = null
            private var previousData: StatisticsReportState? = null

            override suspend fun doInBackground() {
                calculatedData = StatisticsReportStateBuilder.build(
                    habits = habits,
                    period = reportKey.tab.toStatisticsPeriod(),
                    start = start,
                    end = end,
                    today = getToday(),
                    firstWeekday = firstWeekday,
                    filters = reportKey.filters,
                    shouldCancel = { taskGeneration != activeReportGeneration }
                )
                if (reportKey.tab == ReportTab.WEEK || reportKey.tab == ReportTab.MONTH) {
                    val previousPeriodEnd = start.minus(1)
                    val previousStart = if (reportKey.tab == ReportTab.WEEK) start.minus(7)
                        else LocalDate(previousPeriodEnd.year, previousPeriodEnd.month, 1)
                    val comparableEnd = if (end.isNewerThan(getToday())) getToday() else end
                    val comparableDays = start.daysUntil(comparableEnd) + 1
                    val previousEnd = previousStart.plus(
                        (comparableDays - 1).coerceAtLeast(0).coerceAtMost(previousStart.daysUntil(previousPeriodEnd))
                    )
                    previousData = StatisticsReportStateBuilder.build(
                        habits = habits,
                        period = reportKey.tab.toStatisticsPeriod(),
                        start = previousStart,
                        end = previousEnd,
                        today = getToday(),
                        firstWeekday = firstWeekday,
                        filters = reportKey.filters,
                        shouldCancel = { taskGeneration != activeReportGeneration }
                    )
                }
            }

            override fun onPostExecute() {
                if (viewBinding == null || !isAdded || taskGeneration != activeReportGeneration) return
                if (reportKey != buildReportKey()) return
                val result = calculatedData ?: return
                updateReportHeader(result.start, result.end)

                renderReport(result, previousData, taskGeneration)
                hasRenderedStatisticsOnce = true
                lastRenderedReportKey = reportKey
            }
        })
    }

    private fun addNoDataView(parentContainer: LinearLayout, noHabits: Boolean) {
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
        parentContainer.addView(container)
    }

    private fun renderStatisticsReport(
        container: LinearLayout,
        report: StatisticsReportState,
        previous: StatisticsReportState?
    ) {
        when (report.period) {
            StatisticsPeriod.DAY -> {
                addDayOverviewSection(container, report)
                if (report.dayTiersEnabled) {
                    addTierBarsSection(container, report)
                }
            }
            StatisticsPeriod.WEEK -> {
                addDailyCompletionSection(container, report)
                if (report.dayTiersEnabled) {
                    addTierBarsSection(container, report)
                }
                if (report.spheresEnabled) {
                    addSphereSection(container, report)
                }
                addHabitChangesSection(container, report)
            }
            StatisticsPeriod.MONTH -> {
                addMonthCalendarSection(container, report)
                if (report.dayTiersEnabled) {
                    addTierBarsSection(container, report)
                }
                addWeekdayRhythmSection(container, report)
                if (report.spheresEnabled) {
                    addSphereSection(container, report)
                }
                addHabitChangesSection(container, report)
            }
            StatisticsPeriod.YEAR -> {
                addYearMonthlyBarsSection(container, report)
                if (report.dayTiersEnabled) {
                    addTierBarsSection(container, report)
                }
                addWeekdayRhythmSection(container, report)
                if (report.spheresEnabled) {
                    addSphereSection(container, report)
                }
            }
            StatisticsPeriod.ALL -> {
                addAllTimeHistorySection(container, report)
                if (report.dayTiersEnabled) {
                    addTierBarsSection(container, report)
                }
                if (report.spheresEnabled) {
                    addSphereSection(container, report)
                }
                addWeekdayRhythmSection(container, report)
                addHabitStabilitySection(container, report)
            }
        }
    }

    private fun addDayOverviewSection(container: LinearLayout, report: StatisticsReportState) {
        val (card, content) = createSection(
            titleText = getString(R.string.statistics_day_title_default),
            secondaryText = report.overallProgress?.let { "${(it * 100).roundToInt()}%" }
        )
        content.addView(summaryText(getString(R.string.statistics_average_progress), 11f, palette.onSurfaceVariant))
        val listContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
        }
        val habitsToDisplay = report.habits.filter {
            !report.dayTiersEnabled || report.tierScope.includes(it.habit.dayTier)
        }
        habitsToDisplay.forEachIndexed { index, habitResult ->
            if (index > 0) listContainer.addView(createDivider())
            listContainer.addView(createReportHabitRow(habitResult))
        }
        content.addView(listContainer)
        container.addView(card)
    }

    private fun periodComparison(report: StatisticsReportState): String? {
        val delta = report.comparisonDelta ?: return null
        val previous = when (report.period) {
            StatisticsPeriod.WEEK -> R.string.statistics_previous_week
            StatisticsPeriod.MONTH -> R.string.statistics_previous_month
            StatisticsPeriod.YEAR -> R.string.statistics_previous_year
            else -> return null
        }
        val points = (delta * 100).roundToInt()
        val arrow = when {
            points > 0 -> "↑"
            points < 0 -> "↓"
            else -> "="
        }
        val absPoints = kotlin.math.abs(points)
        return "$arrow $absPoints ${getString(R.string.statistics_percentage_points)}\n${getString(previous)}"
    }

    private fun chartMetricLabel(report: StatisticsReportState): String {
        if (!report.dayTiersEnabled) {
            return getString(R.string.statistics_average_progress)
        }
        return when (report.tierScope) {
            DayTierScope.MINIMUM -> getString(R.string.statistics_minimum_metric)
            DayTierScope.UP_TO_NORMAL -> "${getString(R.string.statistics_scope_label)} ${getString(R.string.tier_scope_up_to_normal)}"
            DayTierScope.UP_TO_IDEAL -> "${getString(R.string.statistics_scope_label)} ${getString(R.string.tier_scope_up_to_ideal)}"
            DayTierScope.ALL -> "${getString(R.string.statistics_scope_label)} ${getString(R.string.tier_scope_all)}"
        }
    }

    private fun addHabitChangesSection(container: LinearLayout, report: StatisticsReportState) {
        val changes = report.habitChanges
        if (changes.isEmpty()) return
        val titleRes = if (report.period == StatisticsPeriod.MONTH) {
            R.string.statistics_changes_month_title
        } else {
            R.string.statistics_changes_week_title
        }
        val (card, content) = createSection(getString(titleRes))
        content.addView(summaryText(getString(R.string.statistics_habit_changes_subtitle), 11f, palette.onSurfaceVariant))
        changes.forEach { item ->
            val points = item.deltaPoints
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                minimumHeight = dp(48f).toInt()
                quietPressFeedback(this)
                setOnClickListener {
                    startActivity(IntentFactory().startShowHabitActivity(requireContext(), item.habit))
                }
            }
            val titleRow = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
            titleRow.addView(summaryText("●", 12f, themeSwitcher.currentTheme.color(item.habit.color).toInt()))
            titleRow.addView(summaryText(item.habit.name, 13f, palette.onSurface).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(8f).toInt(), 0, dp(8f).toInt(), 0)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(titleRow)
            val values = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
            values.addView(summaryText("${(item.previousProgress * 100).roundToInt()}% → ${(item.currentProgress * 100).roundToInt()}%",
                12f, palette.onSurfaceVariant), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val sign = if (points > 0) "+" else ""
            values.addView(summaryText("$sign$points ${getString(R.string.statistics_percentage_points)}",
                12f, palette.onSurfaceVariant))
            row.addView(values)
            content.addView(row)
        }
        container.addView(card)
    }

    private fun createSection(
        titleText: String? = null,
        secondaryText: String? = null
    ): Pair<LinearLayout, LinearLayout> {
        val sectionContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(6f).toInt()
            }
            layoutParams = lp
            elevation = 0f
            setBackgroundColor(palette.surface)
            setPadding(dp(16f).toInt(), dp(10f).toInt(), dp(16f).toInt(), dp(10f).toInt())
        }

        if (titleText != null || secondaryText != null) {
            val headerRow = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(6f).toInt()
                }
                layoutParams = lp
            }

            if (titleText != null) {
                val titleView = TextView(requireContext()).apply {
                    text = titleText
                    textSize = 14f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(palette.onSurface)
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f
                    )
                }
                headerRow.addView(titleView)
            }

            if (secondaryText != null) {
                val secondaryView = TextView(requireContext()).apply {
                    text = secondaryText
                    textSize = 12f
                    setTextColor(palette.onSurfaceVariant)
                    gravity = Gravity.END
                    setPadding(dp(8f).toInt(), 0, 0, 0)
                }
                headerRow.addView(secondaryView)
            }

            sectionContainer.addView(headerRow)
        }

        val contentLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        sectionContainer.addView(contentLayout)

        return Pair(sectionContainer, contentLayout)
    }

    private fun formatClosedDaysSecondary(report: StatisticsReportState): String? {
        return when (report.period) {
            StatisticsPeriod.DAY -> null
            StatisticsPeriod.WEEK, StatisticsPeriod.MONTH -> {
                if (report.eligibleFinishedDays > 0) {
                    getString(R.string.statistics_finished_days_closed, report.fullyClosedDays, report.eligibleFinishedDays)
                } else null
            }
            StatisticsPeriod.YEAR -> {
                if (report.eligibleFinishedDays > 0) {
                    getString(R.string.statistics_finished_days_closed, report.fullyClosedDays, report.eligibleFinishedDays)
                } else if (report.fullyClosedDays > 0) {
                    getString(R.string.statistics_fully_closed_days_stat, report.fullyClosedDays)
                } else null
            }
            StatisticsPeriod.ALL -> {
                if (report.fullyClosedDays > 0) {
                    getString(R.string.statistics_fully_closed_days_stat, report.fullyClosedDays)
                } else null
            }
        }
    }



    private fun addTierBarsSection(container: LinearLayout, report: StatisticsReportState) {
        val title = if (report.period == StatisticsPeriod.DAY) {
            getString(R.string.statistics_day_title_by_level)
        } else {
            getString(R.string.statistics_by_level)
        }
        val (card, content) = createSection(title)

        val tiers = listOf(DayTier.MINIMUM, DayTier.NORMAL, DayTier.IDEAL, DayTier.OPTIONAL)
        tiers.forEachIndexed { index, tier ->
            val progress = report.tierProgress[tier]
            val counts = report.tierCounts[tier]

            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                val topPadding = if (index == 0) 0 else dp(6f).toInt()
                val bottomPadding = if (index == tiers.size - 1) 0 else dp(4f).toInt()
                setPadding(0, topPadding, 0, bottomPadding)
            }

            val headerRow = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val dot = View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(dp(8f).toInt(), dp(8f).toInt()).apply {
                    rightMargin = dp(8f).toInt()
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(tierColor(tier))
                }
            }
            val nameView = summaryText(tierLabel(tier), 13f, palette.onSurfaceVariant).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val pctStr = progress?.let { "${(it * 100).roundToInt()}%" } ?: "—"
            val textStr = if (report.period == StatisticsPeriod.DAY && progress != null && counts != null && counts.second > 0) {
                "$pctStr · ${counts.first}/${counts.second}"
            } else {
                pctStr
            }
            val valueView = summaryText(
                textStr,
                13f,
                if (progress != null) palette.onSurface else palette.onSurfaceVariant,
                bold = progress != null
            )

            headerRow.addView(dot)
            headerRow.addView(nameView)
            headerRow.addView(valueView)
            row.addView(headerRow)

            val barTrack = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(4f).toInt()
                ).apply {
                    topMargin = dp(4f).toInt()
                }
                background = GradientDrawable().apply {
                    setColor(ColorUtils.blendARGB(palette.surface, palette.onSurfaceVariant, 0.14f))
                    cornerRadius = dp(2f)
                }
            }

            val fillWeight = progress?.toFloat()?.coerceIn(0f, 1f) ?: 0f
            if (fillWeight > 0f) {
                val fillView = View(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, fillWeight)
                    background = GradientDrawable().apply {
                        setColor(tierColor(tier))
                        cornerRadius = dp(2f)
                    }
                }
                barTrack.addView(fillView)
            }
            if (fillWeight < 1f) {
                val spacer = View(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f - fillWeight)
                }
                barTrack.addView(spacer)
            }

            row.addView(barTrack)
            content.addView(row)
        }

        container.addView(card)
    }

    private fun addDailyCompletionSection(container: LinearLayout, report: StatisticsReportState) {
        val (card, content) = createSection(
            titleText = getString(R.string.statistics_by_day),
            secondaryText = periodComparison(report)
        )
        content.addView(summaryText(getString(R.string.statistics_average_progress), 11f, palette.onSurfaceVariant))

        val daysByDate = report.dailyProgress.associateBy { it.date }
        val barChart = StatisticsPercentageBarChart(requireContext()).apply {
            val items = (0..6).map { offset ->
                val date = report.start.plus(offset)
                val dp = daysByDate[date]
                val isToday = dp?.isToday ?: (date == getToday())
                val isFuture = dp?.isFuture ?: date.isNewerThan(getToday())
                val progress = if (isFuture) null else dp?.progress?.toDouble()
                StatisticsPercentageBarChart.BarItem(
                    progress = progress,
                    label = dateFormatter.shortWeekdayName(date.dayOfWeek),
                    isToday = isToday,
                    isFuture = isFuture
                )
            }
            setData(
                items = items,
                barColor = palette.accent,
                onSurfaceVariant = palette.onSurfaceVariant,
                dividerColor = palette.divider
            )
        }
        content.addView(barChart)
        container.addView(card)
    }

    private fun addMonthCalendarSection(container: LinearLayout, report: StatisticsReportState) {
        val (card, content) = createSection(
            titleText = getString(R.string.statistics_activity_calendar),
            secondaryText = periodComparison(report)
        )

        val calendarView = StatisticsMonthCalendarView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setData(
                monthStart = report.start,
                monthEnd = report.end,
                firstWeekday = component.preferences.firstWeekday,
                days = report.dailyProgress,
                averageProgress = report.overallProgress,
                surfaceColor = palette.surface,
                onSurfaceColor = palette.onSurface,
                onSurfaceVariantColor = palette.onSurfaceVariant,
                accentColor = palette.accent,
                dividerColor = palette.divider
            )
        }
        content.addView(calendarView)
        container.addView(card)
    }

    private fun addWeekdayRhythmSection(container: LinearLayout, report: StatisticsReportState) {
        val hasData = report.weekdayRhythm.size == 7 && report.weekdayRhythm.any { it.observations >= 4 && it.progress != null }
        if (!hasData) return

        val (card, content) = createSection(getString(R.string.statistics_by_weekday))
        val rhythmView = StatisticsWeekRhythmView(requireContext()).apply {
            val items = report.weekdayRhythm.map { item ->
                StatisticsWeekRhythmView.RhythmItem(
                    dayOfWeek = item.dayOfWeek,
                    progress = item.progress,
                    label = dateFormatter.shortWeekdayName(item.dayOfWeek)
                )
            }
            setData(
                data = items,
                accentColor = palette.accent,
                onSurfaceVariant = palette.onSurfaceVariant,
                divider = palette.divider
            )
        }
        content.addView(rhythmView)
        container.addView(card)
    }

    private fun addYearMonthlyBarsSection(container: LinearLayout, report: StatisticsReportState) {
        val buckets = report.trendBuckets
        if (buckets.none { it.progress != null }) return
        val (card, content) = createSection(
            getString(R.string.statistics_by_month), periodComparison(report)
        )
        content.addView(summaryText(getString(R.string.statistics_average_progress), 11f, palette.onSurfaceVariant))
        val barChart = StatisticsPercentageBarChart(requireContext()).apply {
            val items = buckets.map { bucket ->
                val isFuture = bucket.start.isNewerThan(getToday())
                val isCurrent = !isFuture && !bucket.end.isOlderThan(getToday())
                StatisticsPercentageBarChart.BarItem(
                    progress = if (isFuture) null else bucket.progress?.toDouble(),
                    label = dateFormatter.shortMonthName(bucket.start),
                    isToday = isCurrent,
                    isFuture = isFuture
                )
            }
            setData(
                items = items,
                barColor = palette.accent,
                onSurfaceVariant = palette.onSurfaceVariant,
                dividerColor = palette.divider
            )
        }
        content.addView(barChart)
        container.addView(card)
    }

    private fun addAllTimeHistorySection(container: LinearLayout, report: StatisticsReportState) {
        val buckets = report.trendBuckets
        if (buckets.count { it.progress != null } < 2) return
        val (card, content) = createSection(getString(R.string.statistics_long_term_history))
        content.addView(summaryText(chartMetricLabel(report), 11f, palette.onSurfaceVariant))
        val chart = org.isoron.uhabits.activities.statistics.views.StatisticsBucketChartView(requireContext()).apply {
            setData(buckets, buckets.map {
                if (it.start.month == 1 && it.end.month == 12) it.start.year.toString()
                else dateFormatter.shortMonthName(it.start)
            }, palette.accent, palette.onSurfaceVariant)
        }
        if (buckets.size > 12) {
            content.addView(HorizontalScrollView(requireContext()).apply {
                isHorizontalScrollBarEnabled = false
                addView(chart, ViewGroup.LayoutParams(dp((buckets.size * 30).toFloat()).toInt(), dp(104f).toInt()))
            })
        } else content.addView(chart)
        container.addView(card)
    }

    private fun addHabitStabilitySection(container: LinearLayout, report: StatisticsReportState) {
        val items = report.habitStability
        if (items.isEmpty()) return

        val (card, content) = createSection(getString(R.string.statistics_habit_stability_title))

        val listContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
        }

        val maxInitial = 5
        val initialItems = if (items.size > maxInitial) items.take(maxInitial) else items
        val remainingItems = if (items.size > maxInitial) items.drop(maxInitial) else emptyList()

        initialItems.forEachIndexed { index, item ->
            if (index > 0) {
                listContainer.addView(createDivider())
            }
            listContainer.addView(createStabilityRow(item))
        }

        if (remainingItems.isNotEmpty()) {
            val expandedContainer = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
            }
            remainingItems.forEach { item ->
                expandedContainer.addView(createDivider())
                expandedContainer.addView(createStabilityRow(item))
            }

            val toggle = summaryText(
                getString(R.string.statistics_show_more_habits, remainingItems.size),
                13f,
                palette.accent,
                bold = true
            ).apply {
                minHeight = dp(40f).toInt()
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                quietPressFeedback(this)
                setOnClickListener {
                    val isExpanded = expandedContainer.visibility == View.VISIBLE
                    expandedContainer.visibility = if (isExpanded) View.GONE else View.VISIBLE
                    text = if (isExpanded) {
                        getString(R.string.statistics_show_more_habits, remainingItems.size)
                    } else {
                        getString(R.string.statistics_show_less_habits)
                    }
                }
            }
            listContainer.addView(expandedContainer)
            listContainer.addView(toggle)
        }

        content.addView(listContainer)
        container.addView(card)
    }

    private fun createDivider(): View = View(requireContext()).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(1f).toInt().coerceAtLeast(1)
        )
        setBackgroundColor(palette.divider)
    }

    private fun createStabilityRow(item: StatisticsHabitStabilityItem): View {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            val verticalPadding = dp(6f).toInt()
            setPadding(0, verticalPadding, 0, verticalPadding)
            minimumHeight = dp(42f).toInt()
            isClickable = true
            isFocusable = true
            quietPressFeedback(this)
            setOnClickListener {
                startActivity(IntentFactory().startShowHabitActivity(requireContext(), item.habit))
            }
        }

        val topRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val dot = View(requireContext()).apply {
            val size = dp(8f).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                rightMargin = dp(8f).toInt()
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(themeSwitcher.currentTheme.color(item.habit.color).toInt())
            }
        }
        val nameView = summaryText(item.habit.name, 13f, palette.onSurface).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val scorePercent = "${(item.score * 100).roundToInt()}%"
        val scoreView = summaryText(scorePercent, 13f, palette.onSurface, bold = true).apply {
            setPadding(dp(8f).toInt(), 0, 0, 0)
        }

        topRow.addView(dot)
        topRow.addView(nameView)
        topRow.addView(scoreView)
        row.addView(topRow)

        val barTrack = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(4f).toInt()
            ).apply {
                topMargin = dp(4f).toInt()
            }
            background = GradientDrawable().apply {
                setColor(ColorUtils.blendARGB(palette.surface, palette.onSurfaceVariant, 0.14f))
                cornerRadius = dp(2f)
            }
        }

        val habitColor = themeSwitcher.currentTheme.color(item.habit.color).toInt()
        val fillWeight = item.score.toFloat().coerceIn(0f, 1f)
        if (fillWeight > 0f) {
            val fillView = View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, fillWeight)
                background = GradientDrawable().apply {
                    setColor(habitColor)
                    cornerRadius = dp(2f)
                }
            }
            barTrack.addView(fillView)
        }
        if (fillWeight < 1f) {
            val spacer = View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f - fillWeight)
            }
            barTrack.addView(spacer)
        }

        row.addView(barTrack)
        return row
    }

    private fun addSphereSection(container: LinearLayout, report: StatisticsReportState) {
        if (!component.preferences.isHabitSpheresEnabled) return
        if (currentFilters.sphereId != null) return

        val meaningfulSpheres = report.sphereProgress.filter { it.blockId != null }
        if (meaningfulSpheres.size <= 1) return

        val blocks = component.habitList.getBlocks().associateBy { it.id }
        val (card, content) = createSection(getString(R.string.statistics_by_sphere))

        val ordered = report.sphereProgress.sortedWith(
            compareByDescending<StatisticsSphereProgress> { it.progress != null }
                .thenByDescending { it.progress ?: -1.0 }
        )

        val listContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
        }

        val maxInitial = 5
        val initialSpheres = if (ordered.size > maxInitial) ordered.take(maxInitial) else ordered
        val remainingSpheres = if (ordered.size > maxInitial) ordered.drop(maxInitial) else emptyList()

        initialSpheres.forEachIndexed { index, sphereItem ->
            listContainer.addView(createSphereRow(sphereItem, blocks, index == 0, index == initialSpheres.size - 1 && remainingSpheres.isEmpty()))
        }

        if (remainingSpheres.isNotEmpty()) {
            val expandedContainer = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
            }
            remainingSpheres.forEachIndexed { index, sphereItem ->
                expandedContainer.addView(createSphereRow(sphereItem, blocks, isFirst = false, isLast = index == remainingSpheres.size - 1))
            }

            val toggle = summaryText(
                getString(R.string.statistics_show_more_habits, remainingSpheres.size),
                13f,
                palette.accent,
                bold = true
            ).apply {
                minHeight = dp(40f).toInt()
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                quietPressFeedback(this)
                setOnClickListener {
                    val isExpanded = expandedContainer.visibility == View.VISIBLE
                    expandedContainer.visibility = if (isExpanded) View.GONE else View.VISIBLE
                    text = if (isExpanded) {
                        getString(R.string.statistics_show_more_habits, remainingSpheres.size)
                    } else {
                        getString(R.string.statistics_show_less_habits)
                    }
                }
            }
            listContainer.addView(expandedContainer)
            listContainer.addView(toggle)
        }

        content.addView(listContainer)
        container.addView(card)
    }

    private fun createSphereRow(
        sphereItem: StatisticsSphereProgress,
        blocks: Map<Long?, HabitBlock>,
        isFirst: Boolean,
        isLast: Boolean
    ): View {
        val block = sphereItem.blockId?.let(blocks::get)
        val sphereName = block?.name ?: getString(R.string.statistics_no_sphere)
        val sphereColor = block?.let { themeSwitcher.currentTheme.color(it.color).toInt() } ?: palette.onSurfaceVariant

        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            val topPadding = if (isFirst) 0 else dp(6f).toInt()
            val bottomPadding = if (isLast) 0 else dp(4f).toInt()
            setPadding(0, topPadding, 0, bottomPadding)
        }

        val topRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val dot = View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(dp(8f).toInt(), dp(8f).toInt()).apply {
                rightMargin = dp(8f).toInt()
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(sphereColor)
            }
        }

        val nameView = summaryText(sphereName, 13f, palette.onSurfaceVariant).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        val progress = sphereItem.progress
        val valueView = summaryText(
            progress?.let { "${(it * 100).roundToInt()}%" } ?: "—",
            13f,
            if (progress != null) palette.onSurface else palette.onSurfaceVariant,
            bold = progress != null
        )

        topRow.addView(dot)
        topRow.addView(nameView)
        topRow.addView(valueView)
        row.addView(topRow)

        val barTrack = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(4f).toInt()
            ).apply {
                topMargin = dp(4f).toInt()
            }
            background = GradientDrawable().apply {
                setColor(ColorUtils.blendARGB(palette.surface, palette.onSurfaceVariant, 0.14f))
                cornerRadius = dp(2f)
            }
        }

        val fillWeight = progress?.toFloat()?.coerceIn(0f, 1f) ?: 0f
        if (fillWeight > 0f) {
            val fillView = View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, fillWeight)
                background = GradientDrawable().apply {
                    setColor(sphereColor)
                    cornerRadius = dp(2f)
                }
            }
            barTrack.addView(fillView)
        }
        if (fillWeight < 1f) {
            val spacer = View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f - fillWeight)
            }
            barTrack.addView(spacer)
        }

        row.addView(barTrack)
        return row
    }

    private fun createReportHabitRow(result: StatisticsHabitResult): View {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(44f).toInt()
            val vertical = dp(6f).toInt()
            setPadding(0, vertical, 0, vertical)
            isClickable = true
            isFocusable = true
            quietPressFeedback(this)
            setOnClickListener {
                startActivity(IntentFactory().startShowHabitActivity(requireContext(), result.habit))
            }
        }
        row.addView(
            View(requireContext()).apply {
                val size = dp(8f).toInt()
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    rightMargin = dp(8f).toInt()
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
            addView(summaryText(result.habit.name, 13f, palette.onSurface).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            addView(summaryText(formatHabitResult(result), 11f, palette.onSurfaceVariant).apply {
                setPadding(0, dp(1f).toInt(), 0, 0)
            })
        }
        row.addView(texts)
        row.addView(
            summaryText("${(result.progress * 100).roundToInt()}%", 13f, palette.onSurface, bold = true).apply {
                gravity = Gravity.END
                setPadding(dp(8f).toInt(), 0, 0, 0)
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

    private fun summaryText(textValue: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(requireContext()).apply {
            text = textValue
            textSize = size
            setTextColor(color)
            fontFeatureSettings = "tnum"
            if (bold) setTypeface(null, Typeface.BOLD)
        }

    private fun quietPressFeedback(view: View) {
        view.setOnTouchListener { touched, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> touched.alpha = 0.62f
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> touched.alpha = 1f
            }
            false
        }
    }

    private fun tierLabel(tier: DayTier): String {
        val resId = when (tier) {
            DayTier.MINIMUM -> R.string.day_tier_badge_minimum
            DayTier.NORMAL -> R.string.day_tier_badge_normal
            DayTier.IDEAL -> R.string.day_tier_badge_ideal
            DayTier.OPTIONAL -> R.string.day_tier_badge_optional_short
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

    fun onTabTransitionStarted() {}
    fun onTabTransitionEnded() {}

    companion object {
        private const val STATE_TAB = "reports.tab"
        private const val STATE_YEAR = "reports.year"
        private const val STATE_MONTH = "reports.month"
        private const val STATE_DAY = "reports.day"
        private const val STATE_FILTER_SPHERE = "reports.filter.sphere"
        private const val STATE_FILTER_STATUS = "reports.filter.status"
        private const val STATE_FILTER_GOAL = "reports.filter.goal"
        private const val STATE_FILTER_TIER = "reports.filter.tier"
        private const val STATE_FILTER_TIER_SCOPE = "reports.filter.tier_scope"
        private const val REPORT_UPDATE_COALESCE_MS = 80L
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

internal fun isLatestAllowedPeriod(
    anchorDate: LocalDate,
    tab: StatisticsFragment.ReportTab,
    firstWeekday: DayOfWeek
): Boolean {
    val today = getToday()
    return when (tab) {
        StatisticsFragment.ReportTab.DAY -> {
            anchorDate >= today
        }
        StatisticsFragment.ReportTab.WEEK -> {
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
    firstWeekday: DayOfWeek
): Pair<LocalDate, LocalDate> {
    val today = getToday()
    return when (tab) {
        StatisticsFragment.ReportTab.DAY -> Pair(anchorDate, anchorDate)
        StatisticsFragment.ReportTab.WEEK -> {
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
}

internal fun clampAnchorDateToLatestAllowed(
    anchorDate: LocalDate,
    tab: StatisticsFragment.ReportTab,
    firstWeekday: DayOfWeek
): LocalDate {
    val today = getToday()
    if (!isLatestAllowedPeriod(anchorDate, tab, firstWeekday)) {
        return anchorDate
    }
    return today
}
