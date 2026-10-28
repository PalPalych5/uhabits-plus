/*
 * Copyright (C) 2016-2026 Álinson Santos Xavier <git@axavier.org> and contributors
 *
 * This file is part of Loop Habit Tracker.
 *
 * Loop Habit Tracker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 */

package org.isoron.uhabits.activities.statistics

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.preference.PreferenceManager
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.HorizontalScrollView
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageButton
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayout
import org.isoron.platform.time.DayOfWeek
import org.isoron.platform.time.JavaLocalDateFormatter
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.getToday
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.common.theme.MainTabsThemeBridge
import org.isoron.uhabits.activities.common.views.CompactPopupMenu
import org.isoron.uhabits.activities.statistics.views.DiscreteColumnBarChart
import org.isoron.uhabits.activities.statistics.views.SegmentedCohortBarView
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.focus.FocusPrimaryTargetType
import org.isoron.uhabits.core.focus.sqlite.SQLiteFocusSessionRepository
import org.isoron.uhabits.core.models.ModelObservable
import org.isoron.uhabits.core.models.NumericalHabitType
import org.isoron.uhabits.core.statistics.FocusSectionCategory
import org.isoron.uhabits.core.statistics.HabitCadence
import org.isoron.uhabits.core.statistics.HabitCadenceAggregate
import org.isoron.uhabits.core.statistics.HabitCycle
import org.isoron.uhabits.core.statistics.HabitCycleLifecycle
import org.isoron.uhabits.core.statistics.HabitCycleOutcome
import org.isoron.uhabits.core.statistics.HabitSummary
import org.isoron.uhabits.core.statistics.StatisticsDataState
import org.isoron.uhabits.core.statistics.StatisticsIssueCode
import org.isoron.uhabits.core.statistics.StatisticsQueryContext
import org.isoron.uhabits.core.statistics.StatisticsReadIdentity
import org.isoron.uhabits.core.statistics.UnifiedStatisticsReport
import org.isoron.uhabits.core.tasks.Task
import org.isoron.uhabits.core.userTasks.due.TaskDue
import org.isoron.uhabits.inject.HabitsApplicationComponent
import org.isoron.uhabits.utils.InterfaceUtils
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import java.util.concurrent.CancellationException

/**
 * Android unified statistics shell (PR14.2).
 * Hosts four analytic tabs: Overview, Habits, Tasks, Focus.
 * Shared period (DAY/WEEK/MONTH/YEAR/ALL) and section scope (All/Unassigned/Container IDs).
 */
class UnifiedStatisticsFragment : Fragment(), ModelObservable.Listener {

    private val app: HabitsApplication
        get() = requireContext().applicationContext as HabitsApplication

    private val component: HabitsApplicationComponent
        get() = app.component

    private val palette get() = MainTabsThemeBridge.resolve(requireContext())
    private val isRussian get() = Locale.getDefault().language == "ru"

    private lateinit var tabLayout: TabLayout
    private lateinit var btnPeriodSelector: MaterialButton
    private lateinit var btnScopeSelector: MaterialButton
    private lateinit var btnLegacy: AppCompatImageButton
    private lateinit var btnPrevPeriod: AppCompatImageButton
    private lateinit var btnNextPeriod: AppCompatImageButton
    private lateinit var tvDateRange: TextView
    private lateinit var statusBanner: LinearLayout
    private lateinit var tvStatusText: TextView
    private lateinit var contentScrollView: ScrollView
    private lateinit var contentContainer: LinearLayout
    private lateinit var loadingProgressBar: ProgressBar

    private var currentTab: UnifiedAnalyticsTab = UnifiedAnalyticsTab.OVERVIEW
    private var currentPeriod: UnifiedPeriod = UnifiedPeriod.WEEK
    private var currentScope: UnifiedScope = UnifiedScope.All
    private var selectedHabitCadence: HabitCadence = HabitCadence.DAILY
    private var currentAnchorDate: LocalDate = civilToday()
    private var lastKnownToday: LocalDate = civilToday()
    private var lastDatasetId: String = ""

    private fun civilToday(): LocalDate = java.time.LocalDate.now().let { LocalDate(it.year, it.monthValue, it.dayOfMonth) }
    private var observedComponent: HabitsApplicationComponent? = null
    private var observedPreferences: SharedPreferences? = null

    private fun bindReadObservers(target: HabitsApplicationComponent?) {
        observedComponent?.let {
            it.effectiveReadRevision.removeListener(effectiveRevisionListener)
            it.workspaceReadRevision.removeListener(workspaceRevisionListener)
            it.organizationReadRevision.removeListener(organizationRevisionListener)
            it.habitList.observable.removeListener(this)
        }
        observedPreferences?.unregisterOnSharedPreferenceChangeListener(preferenceChangeListener)
        observedComponent = target
        observedPreferences = null
        target?.let {
            it.effectiveReadRevision.addListener(effectiveRevisionListener)
            it.workspaceReadRevision.addListener(workspaceRevisionListener)
            it.organizationReadRevision.addListener(organizationRevisionListener)
            it.habitList.observable.addListener(this)
            observedPreferences = if (it.session.preferencesNamespace.isBlank()) {
                PreferenceManager.getDefaultSharedPreferences(requireContext())
            } else requireContext().getSharedPreferences("uhabits_prefs_${it.session.preferencesNamespace}", Context.MODE_PRIVATE)
            observedPreferences?.registerOnSharedPreferenceChangeListener(preferenceChangeListener)
        }
    }

    @Volatile private var dbVersion: Long = 0L
    @Volatile private var activeReportGeneration: Int = 0

    private var currentUiState: UnifiedReportUiState = UnifiedReportUiState.InitialLoading
    private var currentLoadedReport: UnifiedStatisticsReport? = null
    private var currentLoadedKey: UnifiedReportKey? = null
    private val scopeLabels = mutableMapOf<ContainerId, String>()

    // QA and audit internal state inspection fields
    internal val currentState: UnifiedReportUiState get() = currentUiState
    internal val lastReport: UnifiedStatisticsReport? get() = currentLoadedReport
    internal val activeReport: UnifiedStatisticsReport? get() = currentLoadedReport
    internal var nameSnapshot: StatisticsEntityNameSnapshot = StatisticsEntityNameSnapshot()
    internal val currentPeriodValue: UnifiedPeriod get() = currentPeriod
    internal val currentScopeValue: UnifiedScope get() = currentScope
    internal val currentAnchorValue: LocalDate get() = currentAnchorDate
    internal val isRefreshingState: Boolean get() = pendingReportRunnable != null ||
        ((currentUiState as? UnifiedReportUiState.Success)?.isRefreshing ?: true)
    internal val isLoadingState: Boolean get() = currentUiState is UnifiedReportUiState.InitialLoading

    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingReportRunnable: Runnable? = null
    private var timedDeadlineRunnable: Runnable? = null
    private var midnightRunnable: Runnable? = null

    private val dateFormatter: JavaLocalDateFormatter by lazy {
        JavaLocalDateFormatter(Locale.getDefault())
    }

    private fun dp(value: Float): Int = InterfaceUtils.dpToPixels(requireContext(), value).toInt()

    // -------------------------------------------------------------------------
    // Time & Revision Listeners
    // -------------------------------------------------------------------------

    private val effectiveRevisionListener: () -> Unit = {
        mainHandler.post {
            if (isResumed && !isHidden && isAdded) {
                requestReportUpdate("effective_revision_change")
            }
        }
    }

    private val workspaceRevisionListener: () -> Unit = {
        mainHandler.post {
            if (isResumed && !isHidden && isAdded) {
                requestReportUpdate("workspace_revision_change")
            }
        }
    }

    private val organizationRevisionListener: () -> Unit = {
        mainHandler.post {
            if (isResumed && !isHidden && isAdded) {
                requestReportUpdate("organization_revision_change")
            }
        }
    }

    private val datasetListener: () -> Unit = {
        mainHandler.post {
            if (isAdded && view != null) {
                activeReportGeneration++
                cancelPendingReportUpdate()
                cancelTimedDeadlineCallback()
                currentLoadedReport = null
                currentLoadedKey = null
                currentUiState = UnifiedReportUiState.InitialLoading
                bindReadObservers(null)
                scopeLabels.clear()
                if (!app.isDatasetRebuilding) {
                    bindReadObservers(component)
                    val newDataset = component.session.datasetId
                    if (newDataset != lastDatasetId) {
                        lastDatasetId = newDataset
                        restorePreferencesForDataset(newDataset)
                    }
                    updateHeaderLabels()
                    if (isResumed && !isHidden) requestReportUpdate("dataset_rebuilt", forceRefresh = true)
                }
                renderCurrentState()
            }
        }
    }

    private val preferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "pref_first_weekday" || key == "pref_midnight_delay") {
            mainHandler.post {
                if (isResumed && !isHidden && isAdded) {
                    clampAnchorDate()
                    currentLoadedReport = null
                    currentLoadedKey = null
                    currentUiState = UnifiedReportUiState.InitialLoading
                    updateHeaderLabels()
                    renderCurrentState()
                    requestReportUpdate("preference_change", forceRefresh = true)
                    scheduleMidnightCallback()
                }
            }
        }
    }

    private val timeChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val today = civilToday()
            lastKnownToday = today
            clampAnchorDate()
            invalidateDisplayedReport()
            updateHeaderLabels()
            renderCurrentState()
            requestReportUpdate("midnight_or_time_change", forceRefresh = true)
            scheduleMidnightCallback()
        }
    }

    override fun onModelChange() {
        dbVersion++
        mainHandler.post {
            if (isResumed && !isHidden && isAdded) {
                requestReportUpdate("habit_list_change")
            }
        }
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lastKnownToday = civilToday()
        lastDatasetId = component.session.datasetId

        childFragmentManager.setFragmentResultListener(
            UnifiedStatisticsFiltersBottomSheet.RESULT_KEY, this
        ) { _, bundle ->
            handleFilterResult(bundle)
        }

        val savedDatasetId = savedInstanceState?.getString("unified.datasetId")
        val isMismatchedSavedDataset = savedInstanceState != null && savedDatasetId != component.session.datasetId
        val validSavedState = savedInstanceState?.takeIf { !isMismatchedSavedDataset }
        restoreState(validSavedState, bypassArguments = isMismatchedSavedDataset)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return buildLayout(requireContext())
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        updateHeaderLabels()
        renderCurrentState()
    }

    override fun onStart() {
        super.onStart()
        val currentDataset = component.session.datasetId
        if (currentDataset != lastDatasetId) {
            lastDatasetId = currentDataset
            currentLoadedReport = null
            currentLoadedKey = null
            currentUiState = UnifiedReportUiState.InitialLoading
            restorePreferencesForDataset(currentDataset)
        }

        // Subscribe to app dataset rebuild events
        app.addDatasetListener(datasetListener)

        bindReadObservers(component)

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
        }
        requireContext().registerReceiver(timeChangedReceiver, filter)

        scheduleMidnightCallback()

        if (!app.isDatasetRebuilding) {
            requestReportUpdate("fragment_start")
        }
    }

    override fun onStop() {
        super.onStop()
        app.removeDatasetListener(datasetListener)

        bindReadObservers(null)

        try {
            requireContext().unregisterReceiver(timeChangedReceiver)
        } catch (_: Exception) {}

        cancelMidnightCallback()
        cancelTimedDeadlineCallback()

        activeReportGeneration++
        cancelPendingReportUpdate()
    }

    override fun onPause() {
        activeReportGeneration++
        cancelPendingReportUpdate()
        cancelTimedDeadlineCallback()
        cancelMidnightCallback()
        super.onPause()
    }

    override fun onDestroyView() {
        activeReportGeneration++
        cancelPendingReportUpdate()
        cancelTimedDeadlineCallback()
        cancelMidnightCallback()
        super.onDestroyView()
    }

    override fun onResume() {
        super.onResume()
        val today = civilToday()
        if (today != lastKnownToday) {
            lastKnownToday = today
            clampAnchorDate()
            requestReportUpdate("resume_new_date", forceRefresh = true)
        }
        updateHeaderLabels()
        requestReportUpdate("fragment_resume", forceRefresh = true)
        scheduleMidnightCallback()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("unified.datasetId", component.session.datasetId)
        outState.putString(KEY_TAB, currentTab.name)
        outState.putString(KEY_PERIOD, currentPeriod.name)
        outState.putInt(KEY_ANCHOR_YEAR, currentAnchorDate.year)
        outState.putInt(KEY_ANCHOR_MONTH, currentAnchorDate.month)
        outState.putInt(KEY_ANCHOR_DAY, currentAnchorDate.day)
        when (val scope = currentScope) {
            is UnifiedScope.All -> {
                outState.putString(KEY_SCOPE_TYPE, UnifiedStatisticsFiltersBottomSheet.SCOPE_ALL)
            }
            is UnifiedScope.Unassigned -> {
                outState.putString(KEY_SCOPE_TYPE, UnifiedStatisticsFiltersBottomSheet.SCOPE_UNASSIGNED)
            }
            is UnifiedScope.Container -> {
                outState.putString(KEY_SCOPE_TYPE, UnifiedStatisticsFiltersBottomSheet.SCOPE_CONTAINER)
                outState.putString(KEY_SCOPE_CONTAINER_ID, scope.id.value)
                outState.putBoolean(KEY_SCOPE_INCLUDE_DESCENDANTS, scope.includeDescendants)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Public Host Navigation API (MainActivity casts)
    // -------------------------------------------------------------------------

    fun refresh() {
        requestReportUpdate("host_explicit_refresh", forceRefresh = true)
    }

    fun onTabTransitionStarted() {}

    fun onTabTransitionEnded() {
        if (isAdded && isResumed) {
            requestReportUpdate("host_tab_transition_ended")
        }
    }

    fun applyScopedAll() {
        currentScope = UnifiedScope.All
        savePreferencesForCurrentScope()
        currentLoadedReport = null
        currentLoadedKey = null
        currentUiState = UnifiedReportUiState.InitialLoading
        updateHeaderLabels()
        renderCurrentState()
        requestReportUpdate("scoped_all_applied")
    }

    fun applyScopedUnassigned() {
        currentScope = UnifiedScope.Unassigned
        savePreferencesForCurrentScope()
        currentLoadedReport = null
        currentLoadedKey = null
        currentUiState = UnifiedReportUiState.InitialLoading
        updateHeaderLabels()
        renderCurrentState()
        requestReportUpdate("scoped_unassigned_applied")
    }

    fun applyScopedContainer(containerId: ContainerId, includeDescendants: Boolean) {
        currentScope = UnifiedScope.Container(containerId, includeDescendants)
        savePreferencesForCurrentScope()
        // Reset old mismatched report immediately when filter changes
        currentLoadedReport = null
        currentLoadedKey = null
        currentUiState = UnifiedReportUiState.InitialLoading
        updateHeaderLabels()
        renderCurrentState()
        requestReportUpdate("scoped_container_applied")
    }

    // -------------------------------------------------------------------------
    // UI Layout Construction
    // -------------------------------------------------------------------------

    private fun buildLayout(context: Context): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.background)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // 1. Analytic Tabs (Overview, Habits, Tasks, Focus)
        tabLayout = TabLayout(context).apply {
            tag = "unified_tabs"
            tabMode = TabLayout.MODE_SCROLLABLE
            setBackgroundColor(palette.surface)
            setTabTextColors(palette.onSurfaceVariant, palette.accent)
            setSelectedTabIndicatorColor(palette.accent)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48f)
            )

            addTab(newTab().setText(R.string.unified_statistics_tab_overview))
            addTab(newTab().setText(R.string.unified_statistics_tab_habits))
            addTab(newTab().setText(R.string.unified_statistics_tab_tasks))
            addTab(newTab().setText(R.string.unified_statistics_tab_focus))

            getTabAt(currentTab.ordinal)?.select()

            addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) {
                    val newTab = when (tab.position) {
                        0 -> UnifiedAnalyticsTab.OVERVIEW
                        1 -> UnifiedAnalyticsTab.HABITS
                        2 -> UnifiedAnalyticsTab.TASKS
                        3 -> UnifiedAnalyticsTab.FOCUS
                        else -> UnifiedAnalyticsTab.OVERVIEW
                    }
                    if (newTab != currentTab) {
                        currentTab = newTab
                        savePreferencesForTab()
                        // Single loaded report reused when switching analytic tabs!
                        renderCurrentState()
                    }
                }
                override fun onTabUnselected(tab: TabLayout.Tab) {}
                override fun onTabReselected(tab: TabLayout.Tab) {}
            })
        }
        root.addView(tabLayout)

        // 2. Control Bar (Period selector, Scope selector, Legacy button)
        val config = context.resources.configuration
        val isNarrowOrLargeFont = config.screenWidthDp < 360 || config.fontScale >= 1.3f

        btnPeriodSelector = MaterialButton(
            context, null, com.google.android.material.R.attr.borderlessButtonStyle
        ).apply {
            tag = "unified_period"
            minHeight = dp(48f)
            setTextColor(palette.onSurface)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(12f), 0, dp(12f), 0)
            setOnClickListener { showPeriodSelectorMenu(it) }
        }

        btnScopeSelector = MaterialButton(
            context, null, com.google.android.material.R.attr.borderlessButtonStyle
        ).apply {
            tag = "unified_scope"
            minHeight = dp(48f)
            setTextColor(palette.onSurface)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(12f), 0, dp(12f), 0)
            setOnClickListener {
                UnifiedStatisticsFiltersBottomSheet.show(childFragmentManager, currentScope, component.session.datasetId)
            }
        }

        // Legacy Statistics Action
        btnLegacy = AppCompatImageButton(context).apply {
            tag = "unified_legacy"
            minimumWidth = dp(48f)
            minimumHeight = dp(48f)
            setImageResource(R.drawable.ic_nav_reports)
            background = null
            contentDescription = getString(R.string.unified_statistics_action_legacy_report)
            imageTintList = ColorStateList.valueOf(palette.onSurfaceVariant)
            setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
            setOnClickListener {
                startActivity(Intent(context, StatisticsActivity::class.java))
            }
        }

        val controlBar = if (isNarrowOrLargeFont) {
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12f), dp(4f), dp(12f), dp(2f))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                val topRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }
                topRow.addView(btnPeriodSelector)
                topRow.addView(View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
                })
                topRow.addView(btnLegacy)
                addView(topRow)

                btnScopeSelector.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(2f)
                }
                addView(btnScopeSelector)
            }
        } else {
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12f), dp(8f), dp(12f), dp(4f))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                addView(btnPeriodSelector)
                btnScopeSelector.layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                ).apply {
                    marginStart = dp(4f)
                    marginEnd = dp(4f)
                }
                addView(btnScopeSelector)
                addView(btnLegacy)
            }
        }

        root.addView(controlBar)

        // 3. Date Navigation Bar (Prev, Date Range, Next)
        val dateNavBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), dp(2f), dp(12f), dp(4f))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        btnPrevPeriod = AppCompatImageButton(context).apply {
            tag = "unified_prev"
            setImageResource(R.drawable.ic_arrow_up_dark)
            rotation = -90f
            background = null
            contentDescription = getString(R.string.unified_statistics_prev_period)
            imageTintList = ColorStateList.valueOf(palette.onSurface)
            setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
            setOnClickListener {
                currentAnchorDate = UnifiedStatisticsStateUtils.navigatePeriod(
                    currentPeriod, currentAnchorDate, -1, component.preferences.firstWeekday
                )
                clampAndRequestUpdate("nav_prev")
            }
        }
        dateNavBar.addView(btnPrevPeriod)

        tvDateRange = TextView(context).apply {
            tag = "unified_date_range"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(palette.onSurface)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        dateNavBar.addView(tvDateRange)

        btnNextPeriod = AppCompatImageButton(context).apply {
            tag = "unified_next"
            setImageResource(R.drawable.ic_arrow_down_dark)
            rotation = -90f
            background = null
            contentDescription = getString(R.string.unified_statistics_next_period)
            imageTintList = ColorStateList.valueOf(palette.onSurface)
            setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
            setOnClickListener {
                val nextDate = UnifiedStatisticsStateUtils.navigatePeriod(
                    currentPeriod, currentAnchorDate, 1, component.preferences.firstWeekday
                )
                currentAnchorDate = UnifiedStatisticsStateUtils.clampAnchorDateToLatest(
                    currentPeriod, nextDate, lastKnownToday, component.preferences.firstWeekday
                )
                clampAndRequestUpdate("nav_next")
            }
        }
        dateNavBar.addView(btnNextPeriod)

        root.addView(dateNavBar)

        // 4. Status / Refreshing / Error Banner
        statusBanner = LinearLayout(context).apply {
            tag = "unified_status"
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(16f), dp(4f), dp(16f), dp(4f))
            setBackgroundColor(MainTabsThemeBridge.withAlpha(palette.accent, 0.12f))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        tvStatusText = TextView(context).apply {
            textSize = 13f
            setTextColor(palette.onSurface)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        statusBanner.addView(tvStatusText)

        val btnRetry = MaterialButton(
            context, null, com.google.android.material.R.attr.borderlessButtonStyle
        ).apply {
            text = getString(R.string.unified_statistics_state_retry)
            setTextColor(palette.accent)
            setOnClickListener { refresh() }
        }
        statusBanner.addView(btnRetry)
        root.addView(statusBanner)

        // 5. Loading Progress Bar
        loadingProgressBar = ProgressBar(context).apply {
            isIndeterminate = true
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
                topMargin = dp(32f)
            }
        }
        root.addView(loadingProgressBar)

        // 6. Content Scroll View
        contentScrollView = ScrollView(context).apply {
            tag = "unified_content"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        contentContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(8f), dp(16f), dp(24f))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        contentScrollView.addView(contentContainer)
        root.addView(contentScrollView)

        return root
    }

    // -------------------------------------------------------------------------
    // Report Data Updating & Worker Confinement
    // -------------------------------------------------------------------------

    private fun clampAnchorDate() {
        currentAnchorDate = UnifiedStatisticsStateUtils.clampAnchorDateToLatest(
            currentPeriod, currentAnchorDate, lastKnownToday, component.preferences.firstWeekday
        )
    }

    private fun clampAndRequestUpdate(reason: String) {
        clampAnchorDate()
        savePreferencesForAnchor()
        invalidateDisplayedReport()
        updateHeaderLabels()
        renderCurrentState()
        requestReportUpdate(reason)
    }

    private fun invalidateDisplayedReport() {
        currentLoadedReport = null
        currentLoadedKey = null
        currentUiState = UnifiedReportUiState.InitialLoading
    }

    private fun requestReportUpdate(reason: String, forceRefresh: Boolean = false) {
        activeReportGeneration++
        cancelPendingReportUpdate()
        cancelTimedDeadlineCallback()

        val runnable = Runnable {
            pendingReportRunnable = null
            executeBackgroundReport(forceRefresh)
        }
        pendingReportRunnable = runnable
        mainHandler.post(runnable)
    }

    private fun cancelPendingReportUpdate() {
        pendingReportRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingReportRunnable = null
    }

    private fun captureRevisionTuple(targetComponent: HabitsApplicationComponent): StatisticsRevisionTuple {
        return StatisticsRevisionTuple(
            datasetId = targetComponent.session.datasetId,
            effectiveRevision = targetComponent.effectiveReadRevision.value,
            workspaceRevision = targetComponent.workspaceReadRevision.value,
            organizationRevision = targetComponent.organizationReadRevision.value,
            habitListVersion = dbVersion,
        )
    }

    private fun executeBackgroundReport(forceRefresh: Boolean) {
        if (!isAdded || view == null || !isResumed || isHidden || app.isDatasetRebuilding) return

        val targetApp = app
        val targetComponent = targetApp.component
        val calendarKey = "${ZoneId.systemDefault().id}|${targetComponent.preferences.firstWeekday}|${targetComponent.preferences.midnightDelayHours}|${civilToday()}"
        val startTuple = captureRevisionTuple(targetComponent)
        val generation = ++activeReportGeneration
        val requestedPeriod = currentPeriod
        val requestedAnchor = currentAnchorDate
        val requestedScope = currentScope
        val firstWeekday = targetComponent.preferences.firstWeekday
        val midnightOffsetHours = targetComponent.preferences.midnightDelayHours
        val today = lastKnownToday

        val prelimRange = UnifiedStatisticsStateUtils.calculatePeriodRange(
            requestedPeriod, requestedAnchor, today, firstWeekday,
            currentLoadedReport?.context?.from?.takeIf { currentLoadedKey?.period == UnifiedPeriod.ALL }
        )
        val prelimKey = UnifiedReportKey(
            requestedPeriod, prelimRange.first, prelimRange.second, requestedScope, startTuple, calendarKey
        )

        if (!forceRefresh && currentLoadedKey == prelimKey && currentLoadedReport != null &&
            UnifiedStatisticsStateUtils.canReuseAt(currentLoadedReport!!, System.currentTimeMillis())) {
            currentUiState = UnifiedReportUiState.Success(
                report = currentLoadedReport!!,
                isRefreshing = false,
                renderedKey = currentLoadedKey!!,
            )
            scheduleTimedDueDeadline(currentLoadedReport!!)
            renderCurrentState()
            return
        }

        val sameContext = currentLoadedKey?.let {
            it.period == prelimKey.period && it.from == prelimKey.from && it.to == prelimKey.to &&
                it.scope == prelimKey.scope && it.calendarKey == prelimKey.calendarKey
        } == true
        if (!sameContext) {
            currentLoadedReport = null
            currentLoadedKey = null
        }
        val hadPrevious = currentLoadedReport != null
        if (hadPrevious) {
            currentUiState = UnifiedReportUiState.Success(
                report = currentLoadedReport!!,
                isRefreshing = true,
                renderedKey = currentLoadedKey ?: prelimKey,
            )
        } else {
            currentUiState = UnifiedReportUiState.InitialLoading
        }
        renderCurrentState()

        val task = object : Task {
            @Volatile private var isCancelled = false
            private var generatedReport: UnifiedStatisticsReport? = null
            private var readError: Throwable? = null
            private var finalKey: UnifiedReportKey? = null
            private var generatedScopeLabel: String? = null
            private var generatedNameSnapshot: StatisticsEntityNameSnapshot? = null

            override fun cancel() {
                isCancelled = true
            }

            override fun isCanceled(): Boolean = isCancelled

            override suspend fun doInBackground() {
                if (isCancelled || generation != activeReportGeneration || targetApp.isDatasetRebuilding) return

                val zone = ZoneId.systemDefault()
                val zoneId = zone.id
                if (requestedScope is UnifiedScope.Container) {
                    generatedScopeLabel = runCatching {
                        targetComponent.organizationFacade.containerQueries?.find(requestedScope.id)?.name
                    }.getOrNull()
                }

                val earliestObs: LocalDate? = try {
                    if (requestedPeriod == UnifiedPeriod.ALL) {
                        resolveEarliestObservationOffMainThread(targetComponent, zone, midnightOffsetHours)
                    } else null
                } catch (c: CancellationException) {
                    throw c
                } catch (error: Exception) {
                    readError = error
                    return
                }

                val (fromDate, toDate) = UnifiedStatisticsStateUtils.calculatePeriodRange(
                    requestedPeriod, requestedAnchor, today, firstWeekday, earliestObs
                )

                val context = StatisticsQueryContext(
                    from = fromDate,
                    to = toDate,
                    timezoneId = zoneId,
                    firstWeekday = firstWeekday,
                    habitHourOffset = midnightOffsetHours,
                    habitMinuteOffset = 0,
                    datasetId = startTuple.datasetId,
                    revision = startTuple.effectiveRevision,
                    now = System.currentTimeMillis(),
                    sectionScope = requestedScope.toStatisticsSectionScope(),
                )

                val key = UnifiedReportKey(
                    period = requestedPeriod,
                    from = fromDate,
                    to = toDate,
                    scope = requestedScope,
                    revisionTuple = startTuple,
                    calendarKey = calendarKey,
                )

                if (isCancelled || generation != activeReportGeneration || targetApp.isDatasetRebuilding) return
                if (targetApp.component !== targetComponent ||
                    !startTuple.isMatching(captureRevisionTuple(targetApp.component))
                ) return

                try {
                    val service = targetComponent.unifiedStatisticsReadSource()
                    val report = service.read(
                        request = context,
                        currentIdentity = {
                            val currentComp = targetApp.component
                            val isRebuilding = targetApp.isDatasetRebuilding
                            if (currentComp !== targetComponent || isRebuilding) {
                                StatisticsReadIdentity("mismatched_component", -1L)
                            } else {
                                val currentTuple = captureRevisionTuple(currentComp)
                                if (!startTuple.isMatching(currentTuple)) {
                                    StatisticsReadIdentity("mismatched_tuple", -1L)
                                } else {
                                    StatisticsReadIdentity(startTuple.datasetId, startTuple.effectiveRevision)
                                }
                            }
                        },
                        shouldCancel = {
                            isCancelled || generation != activeReportGeneration ||
                                targetApp.isDatasetRebuilding ||
                                targetApp.component !== targetComponent ||
                                !startTuple.isMatching(captureRevisionTuple(targetApp.component))
                        }
                    )

                    if (!isCancelled && generation == activeReportGeneration && !targetApp.isDatasetRebuilding &&
                        targetApp.component === targetComponent &&
                        startTuple.isMatching(captureRevisionTuple(targetApp.component))
                    ) {
                        generatedReport = report
                        finalKey = key
                        generatedNameSnapshot = runCatching {
                            val tasksMap = buildMap {
                                report.tasks?.let { result ->
                                    (result.completedTasks + result.createdTasks + result.dueSummary.tasks + result.noDueSummary.tasks)
                                        .forEach { put(it.id.value, it.title) }
                                }
                                // One bulk read also resolves historical targets outside Task cohorts.
                                if (report.focus?.sessions?.any { session -> session.taskId?.let { it.value !in this } == true } == true)
                                    targetComponent.userTaskRepository.getAll().forEach { put(it.id.value, it.title) }
                            }
                            val habitsMap = mutableMapOf<String, String>()
                            for (h in targetComponent.habitList) {
                                val u = h.uuid ?: h.id?.toString()
                                if (u != null) habitsMap[u] = h.name
                            }
                            val containersMap = org.isoron.uhabits.core.containers.sqlite.SQLiteOrganizationStore(targetComponent.db)
                                .getAllContainers(includeDeleted = true).associate { it.id.value to it.name }
                            StatisticsEntityNameSnapshot(tasksMap, habitsMap, containersMap)
                        }.getOrNull()
                    }
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    if (!isCancelled && generation == activeReportGeneration) {
                        readError = t
                    }
                }
            }

            override fun onPostExecute() {
                if (isCancelled || generation != activeReportGeneration || targetApp.isDatasetRebuilding) return
                if (targetApp.component !== targetComponent ||
                    !startTuple.isMatching(captureRevisionTuple(targetApp.component))
                ) return

                val report = generatedReport
                val key = finalKey
                val error = readError

                if (report != null && key != null) {
                    currentLoadedReport = report
                    currentLoadedKey = key
                    generatedNameSnapshot?.let { nameSnapshot = it }
                    currentUiState = UnifiedReportUiState.Success(
                        report = report,
                        isRefreshing = false,
                        renderedKey = key,
                    )
                    if (requestedScope is UnifiedScope.Container) generatedScopeLabel?.let { scopeLabels[requestedScope.id] = it }
                    updateHeaderLabels()
                    scheduleTimedDueDeadline(report)
                } else if (error != null) {
                    currentUiState = UnifiedReportUiState.Failure(
                        message = error.message ?: getString(R.string.unified_statistics_state_failed),
                        issues = (error as? org.isoron.uhabits.core.statistics.StatisticsReadInvalidatedException)?.let { emptyList() } ?: emptyList(),
                        previousReport = currentLoadedReport,
                    )
                }
                renderCurrentState()
            }
        }

        targetComponent.taskRunner.execute(task)
    }

    private fun resolveEarliestObservationOffMainThread(
        targetComponent: HabitsApplicationComponent,
        zone: ZoneId,
        midnightDelayHours: Int,
    ): LocalDate? {
        val dates = mutableListOf<LocalDate>()

        // 1. Habit entries and explicit statistics start date (Canon C1)
        targetComponent.habitList.toList().forEach { habit ->
            habit.effectiveStatisticsStartDate()?.let { dates.add(it) }
            habit.originalEntries.getEarliestDate()?.let { dates.add(it) }
        }

        // 2. Contribution ledger
        val habits = targetComponent.habitList.toList()
        val refs = habits.mapNotNull { it.uuid?.let { u -> HabitRef(u) } }.toSet()
        if (refs.isNotEmpty()) {
            val ledger = targetComponent.contributionRepository?.getByHabitsInRange(
                refs, Long.MIN_VALUE, Long.MAX_VALUE, includeRemoved = true
            )
            val earliestLedgerTs = ledger?.minOfOrNull { it.effectiveTimestamp }
            if (earliestLedgerTs != null) {
                val d = Instant.ofEpochMilli(earliestLedgerTs).atZone(zone)
                    .toLocalDateTime().minusHours(midnightDelayHours.toLong())
                    .toLocalDate()
                dates.add(LocalDate(d.year, d.monthValue, d.dayOfMonth))
            }
        }

        // 3. User tasks (created, completed, due)
        targetComponent.userTaskRepository.getAll().filter { it.createdAt > 0 }.forEach { task ->
            val created = Instant.ofEpochMilli(task.createdAt).atZone(zone).toLocalDate()
            dates.add(LocalDate(created.year, created.monthValue, created.dayOfMonth))

            task.completedAt?.takeIf { it > 0 }?.let { compTs ->
                val comp = Instant.ofEpochMilli(compTs).atZone(zone).toLocalDate()
                dates.add(LocalDate(comp.year, comp.monthValue, comp.dayOfMonth))
            }

            when (val due = task.due) {
                is TaskDue.Day -> dates.add(due.date.toLocalDate())
                is TaskDue.Timed -> {
                    val dt = Instant.ofEpochMilli(due.epochMillis).atZone(zone).toLocalDate()
                    dates.add(LocalDate(dt.year, dt.monthValue, dt.dayOfMonth))
                }
                TaskDue.None, null -> {}
            }
        }

        // 4. Focus sessions
        run {
            val focusSessions = SQLiteFocusSessionRepository(targetComponent.db)
                .getByTimeRange(0L, Long.MAX_VALUE)
            focusSessions.minOfOrNull { it.endedAt }?.let { endedTs ->
                val fd = Instant.ofEpochMilli(endedTs).atZone(zone).toLocalDate()
                dates.add(LocalDate(fd.year, fd.monthValue, fd.dayOfMonth))
            }
        }

        return dates.minOrNull()
    }

    private fun scheduleTimedDueDeadline(report: UnifiedStatisticsReport) {
        cancelTimedDeadlineCallback()
        val tasksResult = report.tasks ?: return
        val now = System.currentTimeMillis()
        val nextDeadline = tasksResult.dueSummary.tasks
            .mapNotNull { (it.due as? TaskDue.Timed)?.epochMillis }
            .filter { it > now }
            .minOrNull() ?: return

        val delay = (nextDeadline - now + 500L).coerceAtLeast(1000L)
        val runnable = Runnable {
            if (isResumed && !isHidden && isAdded) {
                requestReportUpdate("timed_due_deadline_reached", forceRefresh = true)
            }
        }
        timedDeadlineRunnable = runnable
        mainHandler.postDelayed(runnable, delay)
    }

    private fun cancelTimedDeadlineCallback() {
        timedDeadlineRunnable?.let { mainHandler.removeCallbacks(it) }
        timedDeadlineRunnable = null
    }

    private fun scheduleMidnightCallback() {
        cancelMidnightCallback()
        if (!isResumed || isHidden || !isAdded || app.isDatasetRebuilding) return
        val now = System.currentTimeMillis()
        val date = civilToday()
        val calendar = StatisticsQueryContext(date, date, ZoneId.systemDefault().id,
            component.preferences.firstWeekday, component.preferences.midnightDelayHours, 0,
            component.session.datasetId, 0, now)
        val nextBoundary = UnifiedStatisticsStateUtils.nextDayBoundary(calendar, now)
        val delay = (nextBoundary - now).coerceAtLeast(0L)
        val runnable = Runnable {
            if (isResumed && !isHidden && isAdded) {
                lastKnownToday = civilToday()
                clampAnchorDate()
                invalidateDisplayedReport()
                updateHeaderLabels()
                renderCurrentState()
                requestReportUpdate("midnight_boundary_callback", forceRefresh = true)
            }
            scheduleMidnightCallback()
        }
        midnightRunnable = runnable
        mainHandler.postDelayed(runnable, (delay + 500L).coerceAtLeast(1000L))
    }

    private fun cancelMidnightCallback() {
        midnightRunnable?.let { mainHandler.removeCallbacks(it) }
        midnightRunnable = null
    }

    // -------------------------------------------------------------------------
    // UI Rendering
    // -------------------------------------------------------------------------

    private fun updateHeaderLabels() {
        if (!::btnPeriodSelector.isInitialized) return

        btnPeriodSelector.text = when (currentPeriod) {
            UnifiedPeriod.DAY -> getString(R.string.unified_statistics_period_day)
            UnifiedPeriod.WEEK -> getString(R.string.unified_statistics_period_week)
            UnifiedPeriod.MONTH -> getString(R.string.unified_statistics_period_month)
            UnifiedPeriod.YEAR -> getString(R.string.unified_statistics_period_year)
            UnifiedPeriod.ALL -> getString(R.string.unified_statistics_period_all)
        }

        btnScopeSelector.text = when (val scope = currentScope) {
            is UnifiedScope.All -> getString(R.string.unified_statistics_scope_all)
            is UnifiedScope.Unassigned -> getString(R.string.unified_statistics_scope_unassigned)
            is UnifiedScope.Container -> {
                val name = scopeLabels[scope.id] ?: scope.id.value
                if (scope.includeDescendants) {
                    getString(R.string.unified_statistics_scope_subtree_format, name)
                } else {
                    getString(R.string.unified_statistics_scope_here_format, name)
                }
            }
        }
        btnScopeSelector.contentDescription = btnScopeSelector.text

        val isAll = currentPeriod == UnifiedPeriod.ALL
        btnPrevPeriod.visibility = if (isAll) View.INVISIBLE else View.VISIBLE
        btnNextPeriod.visibility = if (isAll) View.INVISIBLE else View.VISIBLE

        val isLatest = UnifiedStatisticsStateUtils.isLatestAllowedPeriod(
            currentPeriod, currentAnchorDate, lastKnownToday, component.preferences.firstWeekday
        )
        btnNextPeriod.isEnabled = !isLatest
        btnNextPeriod.alpha = if (!isLatest) 1.0f else 0.35f

        val resolvedAllFrom = currentLoadedReport?.takeIf { currentLoadedKey?.period == UnifiedPeriod.ALL }?.context?.from
        val (start, end) = UnifiedStatisticsStateUtils.calculatePeriodRange(
            currentPeriod, currentAnchorDate, lastKnownToday, component.preferences.firstWeekday, resolvedAllFrom
        )
        tvDateRange.text = when (currentPeriod) {
            UnifiedPeriod.DAY -> dateFormatter.longFormat(start)
            UnifiedPeriod.WEEK -> "${dateFormatter.longFormat(start)} — ${dateFormatter.longFormat(end)}"
            UnifiedPeriod.MONTH -> "${dateFormatter.longMonthName(start)} ${start.year}"
            UnifiedPeriod.YEAR -> start.year.toString()
            UnifiedPeriod.ALL -> {
                if (resolvedAllFrom != null) {
                    getString(R.string.unified_statistics_since_date, dateFormatter.longFormat(resolvedAllFrom))
                } else {
                    getString(R.string.reports_tab_all)
                }
            }
        }
    }

    private fun renderCurrentState() {
        if (!::contentContainer.isInitialized) return
        childFragmentManager.fragments.filterIsInstance<UnifiedStatisticsDrilldownBottomSheet>().forEach { it.refreshReport() }
        childFragmentManager.fragments.filterIsInstance<FocusSessionDetailBottomSheet>().forEach { it.invalidateReport(currentUiState) }
        childFragmentManager.fragments.filterIsInstance<UnifiedStatisticsCycleDetailBottomSheet>().forEach { it.invalidateReport(currentUiState) }
        updateHeaderLabels()

        when (val state = currentUiState) {
            is UnifiedReportUiState.InitialLoading -> {
                statusBanner.visibility = View.GONE
                loadingProgressBar.visibility = View.VISIBLE
                contentContainer.removeAllViews()
            }

            is UnifiedReportUiState.Failure -> {
                loadingProgressBar.visibility = View.GONE
                statusBanner.visibility = View.VISIBLE
                tvStatusText.text = state.message

                contentContainer.removeAllViews()
                if (state.previousReport != null) {
                    renderDomainCards(state.previousReport)
                } else {
                    renderEmptyOrError(state.message)
                }
                contentContainer.addView(createRetryButton())
            }

            is UnifiedReportUiState.Success -> {
                loadingProgressBar.visibility = View.GONE
                if (state.isRefreshing) {
                    statusBanner.visibility = View.VISIBLE
                    tvStatusText.text = getString(R.string.unified_statistics_state_refreshing)
                } else {
                    statusBanner.visibility = View.GONE
                }

                contentContainer.removeAllViews()
                renderDomainCards(state.report)
            }
        }
    }

    private fun renderDomainCards(report: UnifiedStatisticsReport) {
        if (report.context.habitHourOffset != 0) {
            contentContainer.addView(createSecondaryInfo(requireContext(), getString(
                R.string.unified_statistics_calendar_offset, report.context.habitHourOffset)))
        }
        when (currentTab) {
            UnifiedAnalyticsTab.OVERVIEW -> renderOverviewTab(report)
            UnifiedAnalyticsTab.HABITS -> renderHabitsTab(report)
            UnifiedAnalyticsTab.TASKS -> renderTasksTab(report)
            UnifiedAnalyticsTab.FOCUS -> renderFocusTab(report)
        }
    }

    // 1. Overview Tab
    private fun renderOverviewTab(report: UnifiedStatisticsReport) {
        val context = requireContext()
        val overviewLayout = LinearLayout(context).apply {
            tag = "habitOverviewLayout"
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // Habit goals card (tag: overviewCadenceGroup)
        val habitCard = createCardView(context)
        val habitLayout = LinearLayout(context).apply {
            tag = "overviewCadenceGroup"
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(16f), dp(16f), dp(16f))
        }
        habitLayout.addView(createSectionHeader(context, getString(R.string.unified_statistics_overview_habits_title)))
        habitLayout.addView(createSectionSubtitle(context, getString(R.string.unified_statistics_overview_habits_subtitle)))

        val habits = report.habits
        if (habits != null) {
            fun openCadence(cadence: HabitCadence, title: String) {
                if (isRefreshingState || currentLoadedReport !== report) return
                selectedHabitCadence = cadence
                tabLayout.getTabAt(UnifiedAnalyticsTab.HABITS.ordinal)?.select()
                UnifiedStatisticsDrilldownBottomSheet.showForHabitCycles(
                    childFragmentManager, title, "", HabitsDrilldownDescriptor.CadenceCycles(cadence)
                )
            }
            habitLayout.addView(createCadenceRow(
                context, getString(R.string.unified_statistics_cadence_daily), habits.daily, "habitCadenceDailyRow"
            ) {
                openCadence(HabitCadence.DAILY, getString(R.string.unified_statistics_cadence_daily))
            })
            habitLayout.addView(createCadenceRow(
                context, getString(R.string.unified_statistics_cadence_weekly), habits.weekly, "habitCadenceWeeklyRow"
            ) {
                openCadence(HabitCadence.WEEKLY, getString(R.string.unified_statistics_cadence_weekly))
            })
            habitLayout.addView(createCadenceRow(
                context, getString(R.string.unified_statistics_cadence_monthly), habits.monthly, "habitCadenceMonthlyRow"
            ) {
                openCadence(HabitCadence.MONTHLY, getString(R.string.unified_statistics_cadence_monthly))
            })
            if (habits.dataState == StatisticsDataState.PARTIAL) {
                habitLayout.addView(createSecondaryInfo(context, getString(R.string.unified_statistics_state_partial)))
            }
        } else {
            habitLayout.addView(createEmptyText(context, getString(R.string.unified_statistics_domain_failed)))
        }
        habitCard.addView(habitLayout)
        overviewLayout.addView(habitCard)

        // Summary row: Tasks & Focus
        val config = context.resources.configuration
        val isNarrowOrLargeFont = config.screenWidthDp < 360 || config.fontScale >= 1.3f
        val summaryRow = LinearLayout(context).apply {
            orientation = if (isNarrowOrLargeFont) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(12f)
            }
        }

        // Tasks overview card (tag: overviewTasksCard)
        val tasksCard = createCardView(context).apply {
            tag = "overviewTasksCard"
            isClickable = true
            isFocusable = true
            layoutParams = if (isNarrowOrLargeFont) {
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(12f)
                }
            } else {
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(6f)
                }
            }
            setOnClickListener {
                tabLayout.getTabAt(UnifiedAnalyticsTab.TASKS.ordinal)?.select()
            }
        }
        val tasksLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(16f), dp(16f), dp(16f))
        }
        tasksLayout.addView(createSectionSubtitle(context, getString(R.string.unified_statistics_overview_tasks_title)))
        val tasks = report.tasks
        if (tasks != null) {
            tasksLayout.addView(createStatNumber(context, tasks.completedCount.toString()))
            tasksLayout.addView(createSecondaryInfo(context, getString(R.string.unified_statistics_tasks_created_count, tasks.createdCount)))
            if (tasks.dataState == StatisticsDataState.PARTIAL) {
                tasksLayout.addView(createSecondaryInfo(context, getString(R.string.unified_statistics_state_partial)))
            }
        } else {
            tasksLayout.addView(createSecondaryInfo(context, getString(R.string.unified_statistics_domain_failed)))
        }
        tasksCard.addView(tasksLayout)
        summaryRow.addView(tasksCard)

        // Focus overview card (tag: overviewFocusCard)
        val focusCard = createCardView(context).apply {
            tag = "overviewFocusCard"
            isClickable = true
            isFocusable = true
            layoutParams = if (isNarrowOrLargeFont) {
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            } else {
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(6f)
                }
            }
            setOnClickListener {
                tabLayout.getTabAt(UnifiedAnalyticsTab.FOCUS.ordinal)?.select()
            }
        }
        val focusLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(16f), dp(16f), dp(16f))
        }
        focusLayout.addView(createSectionSubtitle(context, getString(R.string.unified_statistics_overview_focus_title)))
        val focus = report.focus
        if (focus != null) {
            val hasOverflow = focus.totalDurationMs == Long.MAX_VALUE ||
                focus.issues.any { it.code == StatisticsIssueCode.OVERFLOW }
            if (hasOverflow) {
                focusLayout.addView(createSecondaryInfo(context, getString(R.string.unified_statistics_overflow)))
            } else {
                focusLayout.addView(createStatNumber(context, UnifiedStatisticsStateUtils.formatDurationMs(focus.totalDurationMs, isRussian)))
                focusLayout.addView(createSecondaryInfo(context, getString(R.string.unified_statistics_overview_focus_sessions_format, focus.sessionCount)).apply { tag = "overview_focus_f2" })
            }
            if (focus.dataState == StatisticsDataState.PARTIAL) {
                focusLayout.addView(createSecondaryInfo(context, getString(R.string.unified_statistics_state_partial)))
            }
        } else {
            focusLayout.addView(createSecondaryInfo(context, getString(R.string.unified_statistics_domain_failed)))
        }
        focusCard.addView(focusLayout)
        summaryRow.addView(focusCard)
        overviewLayout.addView(summaryRow)

        // Period Dynamics Section
        val dynamicsCard = createCardView(context).apply {
            (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(12f)
        }
        val dynamicsLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(16f), dp(16f), dp(16f))
        }
        dynamicsLayout.addView(createSectionHeader(context, getString(R.string.unified_statistics_overview_dynamics_title)))
        dynamicsLayout.addView(createSectionSubtitle(context, getString(R.string.unified_statistics_overview_dynamics_subtitle)))

        // 1. Habit daily goals chart
        if (habits != null) {
            val habitColumns = DiscreteChartBucketer.buildHabitColumns(
                currentPeriod, HabitCadence.DAILY, habits, lastKnownToday, palette, isRussian
            )
            if (habitColumns.isNotEmpty()) {
                val chartTitle = TextView(context).apply {
                    text = getString(R.string.unified_statistics_overview_dynamics_habits)
                    textSize = 13f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(palette.onSurfaceVariant)
                    setPadding(0, dp(8f), 0, dp(4f))
                }
                dynamicsLayout.addView(chartTitle)

                val habitChart = DiscreteColumnBarChart(context).apply {
                    tag = "overviewHabitsChart"
                    isEnabled = !isRefreshingState
                    setData(habitColumns, palette, unitLabel = if (isRussian) "цели" else "goals") { _, item ->
                        if (isRefreshingState || currentLoadedReport !== report) return@setData
                        val desc = item.descriptor as? HabitsDrilldownDescriptor ?: return@setData
                        UnifiedStatisticsDrilldownBottomSheet.showForHabitCycles(
                            childFragmentManager, item.label, "", desc
                        )
                    }
                }
                dynamicsLayout.addView(wrapScrollableChart(
                    context, habitChart, habitColumns, topMarginDp = 0f, bottomMarginDp = 8f
                ))
            }
        } else {
            dynamicsLayout.addView(createSecondaryInfo(context, getString(R.string.unified_statistics_domain_failed)))
        }

        // 2. Tasks completed chart
        if (tasks != null) {
            val taskColumns = DiscreteChartBucketer.buildTaskColumns(
                currentPeriod, tasks, lastKnownToday, isRussian
            )
            if (taskColumns.isNotEmpty()) {
                val taskChartTitle = TextView(context).apply {
                    text = getString(R.string.unified_statistics_overview_dynamics_tasks)
                    textSize = 13f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(palette.onSurfaceVariant)
                    setPadding(0, dp(8f), 0, dp(4f))
                }
                dynamicsLayout.addView(taskChartTitle)

                val taskChart = DiscreteColumnBarChart(context).apply {
                    tag = "overviewTasksChart"
                    isEnabled = !isRefreshingState
                    setData(taskColumns, palette, unitLabel = if (isRussian) "шт." else "pcs") { _, item ->
                        if (isRefreshingState || currentLoadedReport !== report) return@setData
                        val desc = item.descriptor as? TasksDrilldownDescriptor ?: return@setData
                        UnifiedStatisticsDrilldownBottomSheet.showForTasks(
                            childFragmentManager, item.label, "", desc
                        )
                    }
                }
                dynamicsLayout.addView(wrapScrollableChart(
                    context, taskChart, taskColumns, topMarginDp = 0f, bottomMarginDp = 8f
                ))
            }
        }

        // 3. Focus duration chart
        if (focus != null) {
            val focusColumns = DiscreteChartBucketer.buildFocusColumns(
                currentPeriod, focus, lastKnownToday, isRussian
            )
            if (focusColumns.isNotEmpty()) {
                val focusChartTitle = TextView(context).apply {
                    text = getString(R.string.unified_statistics_overview_dynamics_focus)
                    textSize = 13f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(palette.onSurfaceVariant)
                    setPadding(0, dp(8f), 0, dp(4f))
                }
                dynamicsLayout.addView(focusChartTitle)

                val focusChart = DiscreteColumnBarChart(context).apply {
                    tag = "overviewFocusChart"
                    isEnabled = !isRefreshingState
                    setData(focusColumns, palette, unitLabel = if (isRussian) "время" else "time",
                        valueFormatter = { UnifiedStatisticsStateUtils.formatExactDurationMs(it, isRussian) }) { _, item ->
                        if (isRefreshingState || currentLoadedReport !== report) return@setData
                        val desc = item.descriptor as? FocusDrilldownDescriptor ?: return@setData
                        UnifiedStatisticsDrilldownBottomSheet.showForFocus(
                            childFragmentManager, item.label, "", desc
                        )
                    }
                }
                dynamicsLayout.addView(wrapScrollableChart(
                    context, focusChart, focusColumns, topMarginDp = 0f, bottomMarginDp = 8f
                ))
            }
        }

        if (habits?.dataState == StatisticsDataState.PARTIAL || (habits?.daily?.unknownCount ?: 0) > 0) {
            dynamicsLayout.addView(createSecondaryInfo(context, getString(R.string.unified_statistics_overview_dynamics_unknown_notice)))
        }

        dynamicsCard.addView(dynamicsLayout)
        overviewLayout.addView(dynamicsCard)

        contentContainer.addView(overviewLayout)
    }

    // 2. Habits Tab
    private fun renderHabitsTab(report: UnifiedStatisticsReport) {
        val context = requireContext()
        val habits = report.habits

        if (habits == null || habits.dataState == StatisticsDataState.UNAVAILABLE) {
            contentContainer.addView(createEmptyText(context, getString(R.string.unified_statistics_domain_failed)))
            contentContainer.addView(createRetryButton())
            return
        }

        if (habits.dataState == StatisticsDataState.EMPTY && habits.allCycles.isEmpty() && habits.habitSummaries.isEmpty()) {
            contentContainer.addView(createEmptyText(context, getString(R.string.unified_statistics_habits_empty)))
            return
        }

        // 1. Cadence Summaries Card
        val card = createCardView(context)
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(16f), dp(16f), dp(16f))
        }

        val stackHeader = context.resources.configuration.let { it.screenWidthDp < 360 || it.fontScale >= 1.3f }
        val headerRow = LinearLayout(context).apply {
            orientation = if (stackHeader) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = if (stackHeader) Gravity.START else Gravity.CENTER_VERTICAL
        }
        val headerTitle = createSectionHeader(context, getString(R.string.unified_statistics_habits_chart_title)).apply {
            layoutParams = if (stackHeader) {
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            } else {
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
        }
        headerRow.addView(headerTitle)

        // Compare single entry (tag: habitCompareEntry)
        val btnCompare = MaterialButton(context, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            tag = "habitCompareEntry"
            text = getString(R.string.unified_statistics_action_compare_habits)
            setTextColor(palette.accent)
            minHeight = dp(48f)
            setOnClickListener {
                startActivity(app.component.intentFactory.startCompareHabitsActivity(context))
            }
        }
        headerRow.addView(btnCompare)
        layout.addView(headerRow)

        layout.addView(createCadenceRow(
            context, getString(R.string.unified_statistics_cadence_daily), habits.daily, "habitCadenceDailyRow"
        ) {
            if (isRefreshingState || currentLoadedReport !== report) return@createCadenceRow
            UnifiedStatisticsDrilldownBottomSheet.showForHabitCycles(
                childFragmentManager, getString(R.string.unified_statistics_cadence_daily), "",
                HabitsDrilldownDescriptor.CadenceCycles(HabitCadence.DAILY)
            )
        })
        layout.addView(createCadenceRow(
            context, getString(R.string.unified_statistics_cadence_weekly), habits.weekly, "habitCadenceWeeklyRow"
        ) {
            if (isRefreshingState || currentLoadedReport !== report) return@createCadenceRow
            UnifiedStatisticsDrilldownBottomSheet.showForHabitCycles(
                childFragmentManager, getString(R.string.unified_statistics_cadence_weekly), "",
                HabitsDrilldownDescriptor.CadenceCycles(HabitCadence.WEEKLY)
            )
        })
        layout.addView(createCadenceRow(
            context, getString(R.string.unified_statistics_cadence_monthly), habits.monthly, "habitCadenceMonthlyRow"
        ) {
            if (isRefreshingState || currentLoadedReport !== report) return@createCadenceRow
            UnifiedStatisticsDrilldownBottomSheet.showForHabitCycles(
                childFragmentManager, getString(R.string.unified_statistics_cadence_monthly), "",
                HabitsDrilldownDescriptor.CadenceCycles(HabitCadence.MONTHLY)
            )
        })

        if (habits.unsupported.unsupportedCount > 0) {
            layout.addView(createSecondaryInfo(context, getString(
                R.string.unified_statistics_unsupported_count, habits.unsupported.unsupportedCount)))
        }
        if (habits.dataState == StatisticsDataState.PARTIAL) {
            layout.addView(createSecondaryInfo(context, getString(R.string.unified_statistics_state_partial)))
        }
        card.addView(layout)
        contentContainer.addView(card)

        // 2. Adaptive Cadence Chart Card
        val chartCard = createCardView(context).apply {
            (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(12f)
        }
        val chartLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(16f), dp(16f), dp(16f))
        }

        // Cadence Switcher Tabs
        val cadenceSwitcher = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8f)
            }
        }

        fun createCadenceTabButton(cadence: HabitCadence, label: String, tagStr: String): MaterialButton {
            val isSelected = selectedHabitCadence == cadence
            return MaterialButton(context, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                tag = tagStr
                text = label
                minHeight = dp(48f)
                isAllCaps = false
                setTextColor(if (isSelected) palette.accent else palette.onSurfaceVariant)
                setTypeface(typeface, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
                background = GradientDrawable().apply {
                    setColor(if (isSelected) palette.surfaceVariant else Color.TRANSPARENT)
                    cornerRadius = dp(8f).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    if (selectedHabitCadence != cadence) {
                        selectedHabitCadence = cadence
                        renderCurrentState()
                    }
                }
            }
        }

        cadenceSwitcher.addView(createCadenceTabButton(HabitCadence.DAILY, getString(R.string.unified_statistics_cadence_daily), "habitCadenceTabDaily"))
        cadenceSwitcher.addView(createCadenceTabButton(HabitCadence.WEEKLY, getString(R.string.unified_statistics_cadence_weekly), "habitCadenceTabWeekly"))
        cadenceSwitcher.addView(createCadenceTabButton(HabitCadence.MONTHLY, getString(R.string.unified_statistics_cadence_monthly), "habitCadenceTabMonthly"))
        chartLayout.addView(cadenceSwitcher)

        val cadenceTitleStr = when (selectedHabitCadence) {
            HabitCadence.DAILY -> getString(R.string.unified_statistics_habits_chart_daily)
            HabitCadence.WEEKLY -> getString(R.string.unified_statistics_habits_chart_weekly)
            HabitCadence.MONTHLY -> getString(R.string.unified_statistics_habits_chart_monthly)
            else -> getString(R.string.unified_statistics_habits_chart_daily)
        }
        val dateRangeStr = "${formatStatisticsDate(report.context.from)} — ${formatStatisticsDate(report.context.to)}"
        chartLayout.addView(createSectionSubtitle(context, "$cadenceTitleStr · $dateRangeStr"))

        val columns = DiscreteChartBucketer.buildHabitColumns(
            currentPeriod, selectedHabitCadence, habits, lastKnownToday, palette, isRussian
        )

        if (columns.isNotEmpty()) {
            val chartView = DiscreteColumnBarChart(context).apply {
                tag = "habitCadenceChart"
                isEnabled = !isRefreshingState
                setData(columns, palette, unitLabel = if (isRussian) "цели" else "goals") { _, item ->
                    if (isRefreshingState || currentLoadedReport !== report) return@setData
                    val desc = item.descriptor as? HabitsDrilldownDescriptor ?: return@setData
                    UnifiedStatisticsDrilldownBottomSheet.showForHabitCycles(
                        childFragmentManager, item.label, "", desc
                    )
                }
            }
            chartLayout.addView(wrapScrollableChart(
                context, chartView, columns, topMarginDp = 8f, bottomMarginDp = 4f
            ))

            val activeAgg = when (selectedHabitCadence) {
                HabitCadence.DAILY -> habits.daily
                HabitCadence.WEEKLY -> habits.weekly
                HabitCadence.MONTHLY -> habits.monthly
                else -> habits.unsupported
            }
            val legendText = getString(
                R.string.unified_statistics_habits_chart_legend,
                activeAgg.satisfiedCount,
                activeAgg.notSatisfiedCount,
                activeAgg.unknownCount
            )
            chartLayout.addView(createSecondaryInfo(context, legendText))
        } else {
            val emptyChartTv = createSecondaryInfo(context, getString(R.string.unified_statistics_habits_chart_empty)).apply {
                tag = "habitCadenceChart"
                setPadding(0, dp(16f), 0, dp(16f))
            }
            chartLayout.addView(emptyChartTv)
        }

        chartCard.addView(chartLayout)
        contentContainer.addView(chartCard)

        // 3. Compact Ongoing Cycles Card (tag: habitOngoingSummary)
        if (habits.ongoingCycles.isNotEmpty()) {
            val ongoingCard = createCardView(context).apply {
                tag = "habitOngoingSummary"
                (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(12f)
            }
            val ongoingLayout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16f), dp(16f), dp(16f), dp(16f))
            }
            val ongoingTitle = if (report.context.to < report.context.habitToday)
                R.string.unified_statistics_habits_historical_cycles_title
            else R.string.unified_statistics_habits_ongoing_title
            ongoingLayout.addView(createSectionHeader(context, getString(ongoingTitle)))

            val previewOngoing = habits.ongoingCycles.take(3)
            previewOngoing.forEach { cycle ->
                val ongoingRow = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    isClickable = true
                    isFocusable = true
                    tag = cycle.key.toString()
                    minimumHeight = dp(48f)
                    background = GradientDrawable().apply {
                        setColor(palette.surfaceVariant)
                        cornerRadius = dp(8f).toFloat()
                    }
                    setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = dp(6f)
                    }
                    setOnClickListener {
                        UnifiedStatisticsCycleDetailBottomSheet.show(
                            childFragmentManager,
                            cycle.key.toString(),
                            report.context.datasetId,
                            statisticsQuerySignature(report.context)
                        )
                    }
                }

                val titleTv = TextView(context).apply {
                    text = cycle.habitName
                    textSize = 15f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(palette.onSurface)
                }
                ongoingRow.addView(titleTv)

                val contextStr = if (cycle.contextTruncatedTo != null) {
                    getString(R.string.unified_statistics_habits_ongoing_context_format, "${formatStatisticsDate(cycle.startDate)} — ${formatStatisticsDate(cycle.endDate)}", cycle.contextTruncatedTo?.let(::formatStatisticsDate).orEmpty())
                } else {
                    "${formatStatisticsDate(cycle.startDate)} — ${formatStatisticsDate(cycle.endDate)}"
                }

                val datesTv = TextView(context).apply {
                    text = contextStr
                    textSize = 13f
                    setTextColor(palette.onSurfaceVariant)
                    setPadding(0, dp(1f), 0, dp(2f))
                }
                ongoingRow.addView(datesTv)

                val q = cycle.quantity
                val goal = cycle.goal

                val targetText: String
                val actualText: String
                if (q.isNumerical) {
                    val isTargetFinite = goal.targetValue.isFinite() && goal.targetValue >= 0.0
                    if (!isTargetFinite || cycle.outcome == HabitCycleOutcome.UNAVAILABLE) {
                        targetText = getString(R.string.unified_statistics_cycle_target_format, getString(R.string.unified_statistics_domain_failed))
                        actualText = getString(R.string.unified_statistics_habits_ongoing_actual_format, getString(R.string.unified_statistics_domain_failed))
                    } else {
                        val targetNum = java.math.BigDecimal.valueOf(goal.targetValue).stripTrailingZeros().toPlainString()
                        val targetWithUnit = if (goal.unit.isNotBlank()) "$targetNum ${goal.unit}" else targetNum
                        val targetOp = if (goal.targetType == NumericalHabitType.AT_MOST) "≤" else "≥"
                        targetText = getString(R.string.unified_statistics_cycle_target_format, "$targetOp $targetWithUnit")

                        val actualBound = if (q.lowerBound < 0L) {
                            getString(R.string.unified_statistics_domain_failed)
                        } else {
                            val lowerDecimal = java.math.BigDecimal.valueOf(q.lowerBound, 3).stripTrailingZeros().toPlainString()
                            val actualValStr = if (q.unit.isNotBlank()) "$lowerDecimal ${q.unit}" else lowerDecimal
                            (if (q.hasCompleteCoverage) "" else "≥") + actualValStr
                        }
                        actualText = getString(R.string.unified_statistics_habits_ongoing_actual_format, actualBound)
                    }
                } else {
                    val quota = goal.frequency.numerator
                    val quotaDesc = when (cycle.cadence) {
                        HabitCadence.DAILY -> if (isRussian) "1 раз в день" else "1 time/day"
                        HabitCadence.WEEKLY -> if (isRussian) "≥$quota раз в неделю" else "≥$quota times/week"
                        HabitCadence.MONTHLY -> if (isRussian) "≥$quota раз в месяц" else "≥$quota times/month"
                        else -> "≥$quota"
                    }
                    targetText = getString(R.string.unified_statistics_cycle_target_format, quotaDesc)

                    val actualCount = q.booleanManualCount
                    val actualBound = (if (q.hasCompleteCoverage) "" else "≥") +
                        (if (isRussian) "$actualCount выполнено" else "$actualCount completed")
                    actualText = getString(R.string.unified_statistics_habits_ongoing_actual_format, actualBound)
                }

                val progressTv = TextView(context).apply {
                    text = "$targetText · $actualText"
                    textSize = 13f
                    setTextColor(palette.onSurface)
                    setPadding(0, dp(1f), 0, dp(2f))
                }
                ongoingRow.addView(progressTv)

                val (statusStr, statusColor) = when {
                    cycle.outcome == HabitCycleOutcome.UNAVAILABLE -> {
                        getString(R.string.unified_statistics_cycle_outcome_unavailable) to palette.onSurfaceVariant
                    }
                    cycle.lifecycle == HabitCycleLifecycle.PARTIAL -> {
                        getString(R.string.unified_statistics_cycle_lifecycle_partial) to palette.onSurfaceVariant
                    }
                    cycle.lifecycle == HabitCycleLifecycle.TRANSITION -> {
                        getString(R.string.unified_statistics_cycle_lifecycle_transition) to palette.onSurfaceVariant
                    }
                    isHistoricalCycleSlice(cycle, report.context) -> {
                        getString(R.string.unified_statistics_habits_historical_cycle_status) to palette.onSurfaceVariant
                    }
                    q.isNumerical && goal.targetType == NumericalHabitType.AT_MOST -> {
                        val limitValNative = q.targetValueNative ?: (goal.targetValue * 1000).toLong()
                        if (q.lowerBound > limitValNative) {
                            getString(R.string.unified_statistics_habits_ongoing_over_limit) to 0xFFE53935.toInt()
                        } else {
                            getString(R.string.unified_statistics_habits_ongoing_within_limit) to 0xFF4CAF50.toInt()
                        }
                    }
                    else -> {
                        val isReached = if (q.isNumerical) {
                            val targetValNative = q.targetValueNative ?: (goal.targetValue * 1000).toLong()
                            q.lowerBound >= targetValNative
                        } else {
                            q.booleanManualCount >= goal.frequency.numerator
                        }
                        if (isReached) {
                            getString(R.string.unified_statistics_habits_ongoing_reached) to 0xFF4CAF50.toInt()
                        } else {
                            getString(R.string.unified_statistics_habits_ongoing_in_progress) to palette.accent
                        }
                    }
                }

                val statusTv = TextView(context).apply {
                    text = statusStr
                    textSize = 13f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(statusColor)
                    setPadding(0, dp(1f), 0, dp(1f))
                }
                ongoingRow.addView(statusTv)

                if (!q.hasCompleteCoverage || q.unknownDays > 0) {
                    val warningText = if (q.unknownDays > 0) {
                        getString(R.string.unified_statistics_habits_ongoing_coverage_warning, q.unknownDays)
                    } else {
                        getString(R.string.unified_statistics_habits_ongoing_coverage_warning_generic)
                    }
                    val warningTv = TextView(context).apply {
                        text = "⚠ $warningText"
                        textSize = 12f
                        setTextColor(0xFFFF9800.toInt())
                        setPadding(0, dp(1f), 0, 0)
                    }
                    ongoingRow.addView(warningTv)
                }
                ongoingLayout.addView(ongoingRow)
            }

            if (habits.ongoingCycles.size > 3) {
                val seeAllOngoing = MaterialButton(context, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                    text = getString(R.string.unified_statistics_habits_ongoing_see_all, habits.ongoingCycles.size)
                    setTextColor(palette.accent)
                    minHeight = dp(48f)
                    setOnClickListener {
                        UnifiedStatisticsDrilldownBottomSheet.showForHabitCycles(
                            childFragmentManager, getString(ongoingTitle), "",
                            HabitsDrilldownDescriptor.OngoingCycles
                        )
                    }
                }
                ongoingLayout.addView(seeAllOngoing)
            }

            ongoingCard.addView(ongoingLayout)
            contentContainer.addView(ongoingCard)
        }

        // 4. Alphabetical Habit Preview Card (tag: habitAlphaPreviewList)
        val habitsListCard = createCardView(context).apply {
            tag = "habitAlphaPreviewList"
            (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(12f)
        }
        val habitsListLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(16f), dp(16f), dp(16f))
        }

        val habitsHeaderRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val habitsTitle = createSectionHeader(context, getString(R.string.unified_statistics_habits_preview_title)).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        habitsHeaderRow.addView(habitsTitle)

        val btnSearch = MaterialButton(context, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            tag = "habitSearchButton"
            text = getString(R.string.unified_statistics_habits_search_button)
            setTextColor(palette.accent)
            minHeight = dp(48f)
            setOnClickListener {
                UnifiedStatisticsDrilldownBottomSheet.showForHabitList(
                    childFragmentManager, getString(R.string.unified_statistics_habits_preview_title), ""
                )
            }
        }
        habitsHeaderRow.addView(btnSearch)
        habitsListLayout.addView(habitsHeaderRow)

        val groupedSummaries = habits.habitSummaries.groupBy { it.habitUuid }
        val sortedHabitGroups = groupedSummaries.values.sortedBy { it.first().habitName.lowercase() }
        val previewHabitGroups = sortedHabitGroups.take(4)

        previewHabitGroups.forEach { summariesForHabit ->
            val firstSummary = summariesForHabit.first()
            val habitUuid = firstSummary.habitUuid
            val habitName = firstSummary.habitName

            val habitRow = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                isClickable = true
                isFocusable = true
                tag = habitUuid
                minimumHeight = dp(48f)
                background = GradientDrawable().apply {
                    setColor(palette.surfaceVariant)
                    cornerRadius = dp(8f).toFloat()
                }
                setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(6f)
                }
                setOnClickListener {
                    UnifiedStatisticsDrilldownBottomSheet.showForHabitCycles(
                        childFragmentManager, habitName, "",
                        HabitsDrilldownDescriptor.HabitCycles(habitUuid, habitName)
                    )
                }
            }

            val titleRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val titleTv = TextView(context).apply {
                text = habitName
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(palette.onSurface)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            titleRow.addView(titleTv)

            val chevronTv = TextView(context).apply {
                text = "›"
                textSize = 18f
                setTextColor(palette.onSurfaceVariant)
            }
            titleRow.addView(chevronTv)
            habitRow.addView(titleRow)

            val cadenceLines = summariesForHabit.map { summary ->
                val cadenceStr = when (summary.cadence) {
                    HabitCadence.DAILY -> getString(R.string.unified_statistics_cadence_daily)
                    HabitCadence.WEEKLY -> getString(R.string.unified_statistics_cadence_weekly)
                    HabitCadence.MONTHLY -> getString(R.string.unified_statistics_cadence_monthly)
                    HabitCadence.UNSUPPORTED -> getString(R.string.unified_statistics_cadence_unsupported)
                }
                val resultStr = if (summary.eligibleCount == 0) {
                    getString(R.string.unified_statistics_cadence_no_closed_cycles)
                } else if (summary.unknownCount > 0) {
                    getString(
                        R.string.unified_statistics_cadence_result_with_unknown,
                        summary.satisfiedCount,
                        summary.knownCount,
                        summary.unknownCount
                    )
                } else {
                    getString(
                        R.string.unified_statistics_cadence_result_format,
                        summary.satisfiedCount,
                        summary.knownCount
                    )
                }
                "$cadenceStr · $resultStr"
            }

            val metaTv = TextView(context).apply {
                text = cadenceLines.joinToString("\n")
                textSize = 13f
                setTextColor(palette.onSurfaceVariant)
                setPadding(0, dp(2f), 0, 0)
            }
            habitRow.addView(metaTv)
            habitsListLayout.addView(habitRow)
        }

        val allHabitsButton = MaterialButton(context, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            tag = "habitAllHabitsButton"
            text = if (sortedHabitGroups.isNotEmpty()) {
                getString(R.string.unified_statistics_habits_all_button, sortedHabitGroups.size)
            } else {
                getString(R.string.unified_statistics_habits_all_button_no_count)
            }
            setTextColor(palette.accent)
            minHeight = dp(48f)
            setOnClickListener {
                UnifiedStatisticsDrilldownBottomSheet.showForHabitList(
                    childFragmentManager, getString(R.string.unified_statistics_habits_preview_title), ""
                )
            }
        }
        habitsListLayout.addView(allHabitsButton)

        habitsListCard.addView(habitsListLayout)
        contentContainer.addView(habitsListCard)
    }

    // 3. Tasks Tab
    private fun renderTasksTab(report: UnifiedStatisticsReport) {
        val context = requireContext()
        val tasks = report.tasks

        if (tasks == null || tasks.dataState == StatisticsDataState.UNAVAILABLE) {
            contentContainer.addView(createEmptyText(context, getString(R.string.unified_statistics_domain_failed)))
            contentContainer.addView(createRetryButton())
            return
        }

        if (tasks.dataState == StatisticsDataState.EMPTY && tasks.completedCount == 0 && tasks.createdCount == 0 &&
            tasks.dueSummary.totalCount == 0 && tasks.noDueSummary.totalCount == 0
        ) {
            contentContainer.addView(createEmptyText(context, getString(R.string.unified_statistics_tasks_empty)))
            return
        }

        val isRefreshing = isRefreshingState

        // T1 & T2 Headline Card
        val flowCard = createCardView(context).apply {
            isClickable = !isRefreshing
            isFocusable = !isRefreshing
            setOnClickListener {
                if (isRefreshingState || currentLoadedReport !== report) return@setOnClickListener
                val (from, to) = UnifiedStatisticsStateUtils.calculatePeriodRange(
                    currentPeriod, currentAnchorDate, lastKnownToday, component.preferences.firstWeekday,
                    report.context.from
                )
                UnifiedStatisticsDrilldownBottomSheet.showForTasks(
                    childFragmentManager,
                    getString(R.string.unified_statistics_overview_tasks_title),
                    getString(R.string.unified_statistics_tasks_completed_count, tasks.completedCount),
                    TasksDrilldownDescriptor.CompletedRange(from, to, getString(R.string.unified_statistics_overview_tasks_title))
                )
            }
        }
        val flowLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(16f), dp(16f), dp(16f))
        }
        flowLayout.addView(createSectionHeader(context, getString(R.string.unified_statistics_overview_tasks_title)))
        flowLayout.addView(createStatNumber(context, tasks.completedCount.toString()))
        flowLayout.addView(MaterialButton(context, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            tag = "unified_tasks_created"
            text = getString(R.string.unified_statistics_tasks_created_count, tasks.createdCount)
            isAllCaps = false
            minHeight = dp(48f)
            setTextColor(palette.onSurfaceVariant)
            isEnabled = !isRefreshing
            setOnClickListener {
                if (!isRefreshingState) UnifiedStatisticsDrilldownBottomSheet.showForTasks(childFragmentManager,
                    text.toString(), "${report.context.from} – ${report.context.to}",
                    TasksDrilldownDescriptor.CreatedTasks(report.context.from, report.context.to, text.toString()))
            }
        })
        if (tasks.dataState == StatisticsDataState.PARTIAL) {
            flowLayout.addView(createSecondaryInfo(context, getString(R.string.unified_statistics_state_partial)))
        }

        // T1 Daily / Bucket Interactive Chart
        val taskColumns = DiscreteChartBucketer.buildTaskColumns(currentPeriod, tasks, lastKnownToday, isRussian)
        if (taskColumns.isNotEmpty()) {
            val chartView = DiscreteColumnBarChart(context).apply {
                tag = "unified_tasks_t1_chart"
                isEnabled = !isRefreshing
                setData(taskColumns, palette, unitLabel = if (isRussian) "шт." else "tasks") { _, item ->
                    if (isRefreshingState || currentLoadedReport !== report) return@setData
                    val desc = item.descriptor as? TasksDrilldownDescriptor ?: return@setData
                    UnifiedStatisticsDrilldownBottomSheet.showForTasks(
                        childFragmentManager,
                        item.label,
                        item.formattedValue,
                        desc
                    )
                }
            }
            flowLayout.addView(wrapScrollableChart(
                context, chartView, taskColumns, topMarginDp = 12f, bottomMarginDp = 4f
            ))
        }

        flowCard.addView(flowLayout)
        contentContainer.addView(flowCard)

        // T3: Due Cohort Card
        val dueSum = tasks.dueSummary
        val dueCard = createCardView(context).apply {
            (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(12f)
            isClickable = !isRefreshing
            isFocusable = !isRefreshing
            setOnClickListener {
                if (isRefreshingState || currentLoadedReport !== report) return@setOnClickListener
                UnifiedStatisticsDrilldownBottomSheet.showForTasks(
                    childFragmentManager,
                    getString(R.string.unified_statistics_tasks_due_cohort_title),
                    getString(R.string.unified_statistics_tasks_due_total_format, dueSum.totalCount),
                    TasksDrilldownDescriptor.DueCohort(TasksDrilldownDescriptor.DueSegmentFilter.ALL_DUE)
                )
            }
        }
        val dueLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(16f), dp(16f), dp(16f))
        }
        dueLayout.addView(createSectionHeader(context, getString(R.string.unified_statistics_tasks_due_cohort_title)))

        val timeFormat = SimpleDateFormat("dd MMMM HH:mm", Locale.getDefault())
        val nowStr = timeFormat.format(Date(report.context.now))
        dueLayout.addView(createSecondaryInfo(context, getString(R.string.unified_statistics_tasks_due_state_now, nowStr)))
        dueLayout.addView(createSecondaryInfo(context, getString(R.string.unified_statistics_tasks_due_total_format, dueSum.totalCount)))

        // T3 3-segment interactive proportion bar
        val dueSegments = listOf(
            SegmentedCohortBarView.Segment(
                key = "COMPLETED",
                count = dueSum.completedCount,
                label = getString(R.string.unified_statistics_due_state_completed),
                color = palette.accent,
                textColor = if (palette.isDark) palette.background else palette.surface,
                descriptor = TasksDrilldownDescriptor.DueCohort(TasksDrilldownDescriptor.DueSegmentFilter.COMPLETED),
            ),
            SegmentedCohortBarView.Segment(
                key = "OVERDUE_NOW",
                count = dueSum.pendingOverdueNowCount,
                label = getString(R.string.unified_statistics_due_state_overdue),
                color = palette.onSurfaceVariant,
                textColor = if (palette.isDark) palette.background else palette.surface,
                descriptor = TasksDrilldownDescriptor.DueCohort(TasksDrilldownDescriptor.DueSegmentFilter.OVERDUE_NOW),
            ),
            SegmentedCohortBarView.Segment(
                key = "PENDING_NOT_OVERDUE",
                count = dueSum.pendingNotOverdueNowCount,
                label = getString(R.string.unified_statistics_due_state_pending),
                color = palette.onSurface,
                textColor = palette.background,
                descriptor = TasksDrilldownDescriptor.DueCohort(TasksDrilldownDescriptor.DueSegmentFilter.PENDING_NOT_OVERDUE),
            ),
        )
        val dueBarView = SegmentedCohortBarView(context).apply {
            tag = "unified_tasks_t3_cohort"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(8f)
                bottomMargin = dp(8f)
            }
            isEnabled = !isRefreshing
            setSegments(dueSegments, dueSum.totalCount, palette) { seg ->
                if (isRefreshingState || currentLoadedReport !== report) return@setSegments
                val desc = seg.descriptor as? TasksDrilldownDescriptor ?: return@setSegments
                UnifiedStatisticsDrilldownBottomSheet.showForTasks(
                    childFragmentManager,
                    getString(R.string.unified_statistics_tasks_due_cohort_title),
                    "${seg.label}: ${seg.count}",
                    desc
                )
            }
        }
        dueLayout.addView(dueBarView)

        dueLayout.addView(createSecondaryInfo(
            context,
            getString(R.string.unified_statistics_tasks_due_breakdown_format, dueSum.completedCount, dueSum.pendingOverdueNowCount, dueSum.pendingNotOverdueNowCount)
        ))

        val dueDisclaimer = TextView(context).apply {
            text = getString(R.string.unified_statistics_tasks_due_disclaimer)
            textSize = 12f
            setTextColor(palette.onSurfaceVariant)
            setTypeface(typeface, Typeface.ITALIC)
            setPadding(0, dp(4f), 0, 0)
        }
        dueLayout.addView(dueDisclaimer)

        dueCard.addView(dueLayout)
        contentContainer.addView(dueCard)

        // T4: No Due Cohort Card
        val noDueSum = tasks.noDueSummary
        val noDueCard = createCardView(context).apply {
            (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(12f)
            isClickable = !isRefreshing
            isFocusable = !isRefreshing
            setOnClickListener {
                if (isRefreshingState || currentLoadedReport !== report) return@setOnClickListener
                UnifiedStatisticsDrilldownBottomSheet.showForTasks(
                    childFragmentManager,
                    getString(R.string.unified_statistics_tasks_nodue_cohort_title),
                    getString(R.string.unified_statistics_tasks_nodue_total_format, noDueSum.totalCount),
                    TasksDrilldownDescriptor.NoDueCohort(TasksDrilldownDescriptor.NoDueSegmentFilter.ALL_NO_DUE)
                )
            }
        }
        val noDueLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(16f), dp(16f), dp(16f))
        }
        noDueLayout.addView(createSectionHeader(context, getString(R.string.unified_statistics_tasks_nodue_cohort_title)))
        noDueLayout.addView(createSecondaryInfo(context, getString(R.string.unified_statistics_tasks_nodue_total_format, noDueSum.totalCount)))

        // T4 2-segment interactive proportion bar
        val noDueSegments = listOf(
            SegmentedCohortBarView.Segment(
                key = "COMPLETED",
                count = noDueSum.completedCount,
                label = getString(R.string.unified_statistics_nodue_state_completed),
                color = palette.accent,
                textColor = if (palette.isDark) palette.background else palette.surface,
                descriptor = TasksDrilldownDescriptor.NoDueCohort(TasksDrilldownDescriptor.NoDueSegmentFilter.COMPLETED),
            ),
            SegmentedCohortBarView.Segment(
                key = "PENDING",
                count = noDueSum.pendingCount,
                label = getString(R.string.unified_statistics_nodue_state_pending),
                color = palette.onSurfaceVariant,
                textColor = if (palette.isDark) palette.background else palette.surface,
                descriptor = TasksDrilldownDescriptor.NoDueCohort(TasksDrilldownDescriptor.NoDueSegmentFilter.PENDING),
            ),
        )
        val noDueBarView = SegmentedCohortBarView(context).apply {
            tag = "unified_tasks_t4_cohort"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(8f)
                bottomMargin = dp(8f)
            }
            isEnabled = !isRefreshing
            setSegments(noDueSegments, noDueSum.totalCount, palette) { seg ->
                if (isRefreshingState || currentLoadedReport !== report) return@setSegments
                val desc = seg.descriptor as? TasksDrilldownDescriptor ?: return@setSegments
                UnifiedStatisticsDrilldownBottomSheet.showForTasks(
                    childFragmentManager,
                    getString(R.string.unified_statistics_tasks_nodue_cohort_title),
                    "${seg.label}: ${seg.count}",
                    desc
                )
            }
        }
        noDueLayout.addView(noDueBarView)

        noDueLayout.addView(createSecondaryInfo(
            context,
            getString(R.string.unified_statistics_tasks_nodue_breakdown_format, noDueSum.completedCount, noDueSum.pendingCount)
        ))
        noDueCard.addView(noDueLayout)
        contentContainer.addView(noDueCard)

        // Informational button: Why is there no "On-time" indicator?
        val whyNoOnTimeButton = MaterialButton(context, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = getString(R.string.unified_statistics_why_no_ontime_button)
            setTextColor(palette.accent)
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(12f)
                gravity = Gravity.CENTER_HORIZONTAL
            }
            setOnClickListener {
                showWhyNoOnTimeDialog()
            }
        }
        contentContainer.addView(whyNoOnTimeButton)
    }

    private fun showWhyNoOnTimeDialog() {
        val context = requireContext()
        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle(R.string.unified_statistics_why_no_ontime_title)
            .setMessage(R.string.unified_statistics_why_no_ontime_body)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    // 4. Focus Tab
    private fun renderFocusTab(report: UnifiedStatisticsReport) {
        val context = requireContext()
        val focus = report.focus

        if (focus == null || focus.dataState == StatisticsDataState.UNAVAILABLE) {
            contentContainer.addView(createEmptyText(context, getString(R.string.unified_statistics_domain_failed)))
            contentContainer.addView(createRetryButton())
            return
        }

        if (focus.dataState == StatisticsDataState.EMPTY && focus.sessionCount == 0) {
            contentContainer.addView(createEmptyText(context, getString(R.string.unified_statistics_focus_empty)))
            return
        }

        val isRefreshing = isRefreshingState

        // F1 & F2 Totals Card
        val totalsCard = createCardView(context).apply {
            isClickable = !isRefreshing
            isFocusable = !isRefreshing
            setOnClickListener {
                if (isRefreshingState || currentLoadedReport !== report) return@setOnClickListener
                val (from, to) = UnifiedStatisticsStateUtils.calculatePeriodRange(
                    currentPeriod, currentAnchorDate, lastKnownToday, component.preferences.firstWeekday,
                    report.context.from
                )
                UnifiedStatisticsDrilldownBottomSheet.showForFocus(
                    childFragmentManager,
                    getString(R.string.unified_statistics_focus_total_duration_title),
                    "${focus.sessionCount} ${getString(R.string.unified_statistics_focus_sessions_count_title).lowercase()}",
                    FocusDrilldownDescriptor.SessionsRange(from, to, getString(R.string.unified_statistics_focus_total_duration_title))
                )
            }
        }
        val totalsLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(16f), dp(16f), dp(16f))
        }
        totalsLayout.addView(createSectionHeader(context, getString(R.string.unified_statistics_focus_total_duration_title)))

        val hasOverflow = focus.totalDurationMs == Long.MAX_VALUE ||
            focus.issues.any { it.code == StatisticsIssueCode.OVERFLOW }
        if (hasOverflow) {
            totalsLayout.addView(createSecondaryInfo(context, getString(R.string.unified_statistics_overflow)))
        } else {
            totalsLayout.addView(createStatNumber(context, UnifiedStatisticsStateUtils.formatDurationMs(focus.totalDurationMs, isRussian)))
            totalsLayout.addView(createSecondaryInfo(
                context,
                getString(R.string.unified_statistics_focus_sessions_count_title) + ": " + focus.sessionCount
            ).apply { tag = "unified_focus_f2" })
            totalsLayout.addView(createSecondaryInfo(context, getString(R.string.unified_statistics_focus_chart_subtitle)))
        }
        if (focus.dataState == StatisticsDataState.PARTIAL) {
            totalsLayout.addView(createSecondaryInfo(context, getString(R.string.unified_statistics_state_partial)))
        }

        // F1 Daily / Bucket Interactive Chart
        val focusColumns = DiscreteChartBucketer.buildFocusColumns(currentPeriod, focus, lastKnownToday, isRussian)
        if (focusColumns.isNotEmpty()) {
            val chartView = DiscreteColumnBarChart(context).apply {
                tag = "unified_focus_f1_chart"
                isEnabled = !isRefreshing
                setData(focusColumns, palette, unitLabel = if (isRussian) "время" else "time",
                    valueFormatter = { UnifiedStatisticsStateUtils.formatExactDurationMs(it, isRussian) }) { _, item ->
                    if (isRefreshingState || currentLoadedReport !== report) return@setData
                    val desc = item.descriptor as? FocusDrilldownDescriptor ?: return@setData
                    UnifiedStatisticsDrilldownBottomSheet.showForFocus(
                        childFragmentManager,
                        item.label,
                        item.formattedValue,
                        desc
                    )
                }
            }
            totalsLayout.addView(wrapScrollableChart(
                context, chartView, focusColumns, topMarginDp = 12f, bottomMarginDp = 4f
            ))
        }

        totalsCard.addView(totalsLayout)
        contentContainer.addView(totalsCard)

        // F3: By Section (Limited branch preview up to 5 + all)
        if (focus.sectionBreakdowns.isNotEmpty()) {
            val sectionsCard = createCardView(context).apply {
                tag = "unified_focus_f3_breakdown"
                (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(12f)
            }
            val sectionsLayout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16f), dp(16f), dp(16f), dp(16f))
            }
            sectionsLayout.addView(createSectionHeader(context, getString(R.string.unified_statistics_focus_by_section_title)))

            val previewItems = focus.sectionBreakdowns.take(5)
            previewItems.forEach { item ->
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    isClickable = !isRefreshing
                    isFocusable = !isRefreshing
                    minimumHeight = dp(48f)
                    tag = "section:${item.category}:${item.containerId?.value}"
                    setPadding(dp(4f), dp(8f), dp(4f), dp(8f))
                    setOnClickListener {
                        if (isRefreshingState || currentLoadedReport !== report) return@setOnClickListener
                        UnifiedStatisticsDrilldownBottomSheet.showForFocus(
                            childFragmentManager,
                            getString(R.string.unified_statistics_drilldown_section_title, item.name),
                            UnifiedStatisticsStateUtils.formatDurationMs(item.durationMs, isRussian),
                            FocusDrilldownDescriptor.SectionBranch(item.category, item.containerId)
                        )
                    }
                }
                val nameView = TextView(context).apply {
                    text = item.name
                    textSize = 15f
                    setTextColor(palette.onSurface)
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                }
                val durationView = TextView(context).apply {
                    text = UnifiedStatisticsStateUtils.formatDurationMs(item.durationMs, isRussian)
                    textSize = 14f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(palette.onSurfaceVariant)
                }
                row.addView(nameView)
                row.addView(durationView)
                row.addView(ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 1000
                    progress = if (focus.totalDurationMs > 0) (item.durationMs.toDouble() / focus.totalDurationMs * 1000).toInt() else 0
                    progressTintList = ColorStateList.valueOf(palette.accent)
                    progressBackgroundTintList = ColorStateList.valueOf(palette.surfaceVariant)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6f)).apply { topMargin = dp(6f) }
                })
                sectionsLayout.addView(row)
            }

            if (focus.sectionBreakdowns.size > 5) {
                val showAllBtn = MaterialButton(context, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                    text = getString(R.string.unified_statistics_focus_show_all_sections, focus.sectionBreakdowns.size)
                    setTextColor(palette.accent)
                    setOnClickListener {
                        if (isRefreshingState || currentLoadedReport !== report) return@setOnClickListener
                        val (from, to) = UnifiedStatisticsStateUtils.calculatePeriodRange(
                            currentPeriod, currentAnchorDate, lastKnownToday, component.preferences.firstWeekday,
                            report.context.from
                        )
                        UnifiedStatisticsDrilldownBottomSheet.showForFocus(
                            childFragmentManager,
                            getString(R.string.unified_statistics_focus_by_section_title),
                            "${focus.sectionBreakdowns.size} ${getString(R.string.unified_statistics_focus_by_section_title).lowercase()}",
                            FocusDrilldownDescriptor.AllSections
                        )
                    }
                }
                sectionsLayout.addView(showAllBtn)
            }

            sectionsCard.addView(sectionsLayout)
            contentContainer.addView(sectionsCard)
        }

        // F4: By Target Type
        if (focus.targetTypeSummaries.isNotEmpty()) {
            val targetCard = createCardView(context).apply {
                tag = "unified_focus_f4_breakdown"
                (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(12f)
            }
            val targetLayout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16f), dp(16f), dp(16f), dp(16f))
            }
            targetLayout.addView(createSectionHeader(context, getString(R.string.unified_statistics_focus_by_target_title)))

            focus.targetTypeSummaries.forEach { item ->
                val durationStr = UnifiedStatisticsStateUtils.formatDurationMs(item.durationMs, isRussian)
                val lineText = when (item.targetType) {
                    FocusPrimaryTargetType.HABIT ->
                        getString(R.string.unified_statistics_focus_target_habits, durationStr, item.sessionCount)
                    FocusPrimaryTargetType.TASK ->
                        getString(R.string.unified_statistics_focus_target_tasks, durationStr, item.sessionCount)
                    FocusPrimaryTargetType.CONTAINER ->
                        getString(R.string.unified_statistics_focus_target_containers, durationStr, item.sessionCount)
                    null ->
                        getString(R.string.unified_statistics_focus_target_unassigned, durationStr, item.sessionCount)
                }
                val row = TextView(context).apply {
                    text = lineText
                    tag = "target-type:${item.targetType?.name ?: "UNKNOWN"}"
                    textSize = 14f
                    setTextColor(palette.onSurface)
                    isClickable = !isRefreshing
                    isFocusable = !isRefreshing
                    setPadding(dp(4f), dp(6f), dp(4f), dp(6f))
                    setOnClickListener {
                        if (isRefreshingState || currentLoadedReport !== report) return@setOnClickListener
                        val title = when (item.targetType) {
                            FocusPrimaryTargetType.HABIT -> getString(R.string.unified_statistics_tab_habits)
                            FocusPrimaryTargetType.TASK -> getString(R.string.unified_statistics_tab_tasks)
                            FocusPrimaryTargetType.CONTAINER -> getString(R.string.unified_statistics_scope_all)
                            null -> getString(R.string.unified_statistics_scope_unassigned)
                        }
                        UnifiedStatisticsDrilldownBottomSheet.showForFocus(
                            childFragmentManager,
                            getString(R.string.unified_statistics_drilldown_target_title, title),
                            durationStr,
                            FocusDrilldownDescriptor.TargetType(item.targetType)
                        )
                    }
                }
                targetLayout.addView(row)
            }
            targetCard.addView(targetLayout)
            contentContainer.addView(targetCard)
        }

        // The existing history calendar remains available in the Tasks workspace.
        // An endedAt-selected session list is not a calendar-allocation query.
    }

    // -------------------------------------------------------------------------
    // View Helpers
    // -------------------------------------------------------------------------

    private fun createCardView(context: Context): FrameLayout {
        return FrameLayout(context).apply {
            background = GradientDrawable().apply {
                setColor(palette.surface)
                cornerRadius = dp(12f).toFloat()
                if (palette.isPureBlack) {
                    setStroke(dp(1f), palette.border)
                }
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8f)
            }
        }
    }

    private fun createSectionHeader(context: Context, title: String): TextView {
        return TextView(context).apply {
            text = title
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(palette.onSurface)
            setPadding(0, 0, 0, dp(4f))
        }
    }

    private fun createSectionSubtitle(context: Context, subtitle: String): TextView {
        return TextView(context).apply {
            text = subtitle
            textSize = 13f
            setTextColor(palette.onSurfaceVariant)
            setPadding(0, 0, 0, dp(8f))
        }
    }

    private fun createStatNumber(context: Context, number: String): TextView {
        return TextView(context).apply {
            text = number
            textSize = 28f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(palette.onSurface)
            setPadding(0, 0, 0, dp(4f))
        }
    }

    private fun createSecondaryInfo(context: Context, info: String): TextView {
        return TextView(context).apply {
            text = info
            textSize = 14f
            setTextColor(palette.onSurfaceVariant)
            setPadding(0, dp(2f), 0, dp(2f))
        }
    }

    private fun wrapScrollableChart(
        context: Context,
        chart: DiscreteColumnBarChart,
        columns: List<ChartColumnItem>,
        topMarginDp: Float = 0f,
        bottomMarginDp: Float = 8f
    ): HorizontalScrollView {
        chart.setPadding(0, 0, 0, dp(10f))
        return HorizontalScrollView(context).apply {
            isFillViewport = true
            isScrollbarFadingEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(topMarginDp)
                bottomMargin = dp(bottomMarginDp)
            }
            addView(chart, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            post {
                val todayIdx = columns.indexOfFirst { it.isToday }
                if (todayIdx >= 0) {
                    chart.ensureColumnVisible(todayIdx)
                } else {
                    fullScroll(View.FOCUS_RIGHT)
                }
            }
        }
    }

    private fun createEmptyText(context: Context, text: String): TextView {
        return TextView(context).apply {
            this.text = text
            textSize = 15f
            setTextColor(palette.onSurfaceVariant)
            gravity = Gravity.CENTER
            setPadding(dp(16f), dp(32f), dp(16f), dp(32f))
        }
    }

    private fun createCadenceRow(
        context: Context,
        label: String,
        aggregate: HabitCadenceAggregate,
        rowTag: String? = null,
        onClick: (() -> Unit)? = null,
    ): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            if (rowTag != null) tag = rowTag
            setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
            if (onClick != null) {
                isClickable = true
                isFocusable = true
                minimumHeight = dp(48f)
                background = GradientDrawable().apply {
                    setColor(palette.surfaceVariant)
                    cornerRadius = dp(6f).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(4f)
                }
                setOnClickListener { onClick() }
            }
        }
        val topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val labelView = TextView(context).apply {
            text = label
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(palette.onSurface)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        topRow.addView(labelView)
        if (onClick != null) {
            val chevron = TextView(context).apply {
                text = "›"
                textSize = 18f
                setTextColor(palette.onSurfaceVariant)
            }
            topRow.addView(chevron)
        }
        row.addView(topRow)

        val lines = mutableListOf<String>()

        // 1. Closed cycles outcome (achieved S, failed F, unknown U) - separately shown, no false zero
        if (aggregate.eligibleCount == 0) {
            lines.add(getString(R.string.unified_statistics_cadence_no_closed_cycles))
        } else if (aggregate.unknownCount > 0) {
            lines.add(
                getString(
                    R.string.unified_statistics_cadence_breakdown_with_unknown,
                    aggregate.satisfiedCount,
                    aggregate.notSatisfiedCount,
                    aggregate.unknownCount
                )
            )
        } else {
            lines.add(
                getString(
                    R.string.unified_statistics_cadence_breakdown,
                    aggregate.satisfiedCount,
                    aggregate.notSatisfiedCount
                )
            )
        }

        // 2. Exempt / capacity counts - separately shown, no mixed category, no false zero
        if (aggregate.exemptCount > 0 && aggregate.capacityExcludedCount > 0) {
            lines.add(
                getString(
                    R.string.unified_statistics_cadence_exempt_and_capacity,
                    aggregate.exemptCount,
                    aggregate.capacityExcludedCount
                )
            )
        } else if (aggregate.exemptCount > 0) {
            lines.add(
                getString(
                    R.string.unified_statistics_cadence_exempt_only,
                    aggregate.exemptCount
                )
            )
        } else if (aggregate.capacityExcludedCount > 0) {
            lines.add(
                getString(
                    R.string.unified_statistics_cadence_capacity_only,
                    aggregate.capacityExcludedCount
                )
            )
        }

        // 3. Partial / transition counts - separately shown, no mixed category, no false zero
        if (aggregate.partialCount > 0 && aggregate.transitionCount > 0) {
            lines.add(
                getString(
                    R.string.unified_statistics_cadence_partial_and_transition,
                    aggregate.partialCount,
                    aggregate.transitionCount
                )
            )
        } else if (aggregate.partialCount > 0) {
            lines.add(
                getString(
                    R.string.unified_statistics_cadence_partial_only,
                    aggregate.partialCount
                )
            )
        } else if (aggregate.transitionCount > 0) {
            lines.add(
                getString(
                    R.string.unified_statistics_cadence_transition_only,
                    aggregate.transitionCount
                )
            )
        }

        // 4. Ongoing count - separately shown, no false zero
        if (aggregate.ongoingCount > 0) {
            lines.add(
                getString(
                    if (currentLoadedReport?.context?.let { it.to < it.habitToday } == true)
                        R.string.unified_statistics_cadence_historical_count
                    else R.string.unified_statistics_cadence_ongoing_count,
                    aggregate.ongoingCount
                )
            )
        }

        // 5. Unavailable count - separately shown, no false zero
        if (aggregate.unavailableCount > 0) {
            lines.add(
                getString(
                    R.string.unified_statistics_cadence_unavailable_count,
                    aggregate.unavailableCount
                )
            )
        }

        val valueView = TextView(context).apply {
            textSize = 14f
            text = lines.joinToString("\n")
            setTextColor(if (aggregate.eligibleCount == 0) palette.onSurfaceVariant else palette.accent)
        }
        row.addView(valueView)
        return row
    }



    private fun renderEmptyOrError(message: String) {
        val tv = createEmptyText(requireContext(), message)
        contentContainer.addView(tv)
    }

    private fun createRetryButton() = MaterialButton(requireContext(), null,
        com.google.android.material.R.attr.borderlessButtonStyle).apply {
        tag = "unified_retry"
        text = getString(R.string.unified_statistics_state_retry)
        minHeight = dp(48f)
        setTextColor(palette.accent)
        setOnClickListener { refresh() }
    }

    private fun showPeriodSelectorMenu(anchor: View) {
        val periods = listOf(
            UnifiedPeriod.DAY to R.string.unified_statistics_period_day,
            UnifiedPeriod.WEEK to R.string.unified_statistics_period_week,
            UnifiedPeriod.MONTH to R.string.unified_statistics_period_month,
            UnifiedPeriod.YEAR to R.string.unified_statistics_period_year,
            UnifiedPeriod.ALL to R.string.unified_statistics_period_all,
        )
        val entries = periods.mapIndexed { index, (period, stringResId) ->
            CompactPopupMenu.Entry.Item(
                id = index,
                title = getString(stringResId),
                selected = period == currentPeriod
            )
        }
        CompactPopupMenu.show(anchor, entries) { clickedId ->
            val (newPeriod, _) = periods.getOrNull(clickedId) ?: return@show
            if (newPeriod != currentPeriod) {
                currentPeriod = newPeriod
                savePreferencesForPeriod()
                // Reset old report so mismatched date range is not displayed under new header
                currentLoadedReport = null
                currentLoadedKey = null
                currentUiState = UnifiedReportUiState.InitialLoading
                clampAndRequestUpdate("period_changed")
            }
        }
    }

    private fun handleFilterResult(bundle: Bundle) {
        val bundleDatasetId = bundle.getString(UnifiedStatisticsFiltersBottomSheet.EXTRA_DATASET_ID)
        val currentDatasetId = component.session.datasetId
        if (app.isDatasetRebuilding || bundleDatasetId.isNullOrBlank() || bundleDatasetId != currentDatasetId) {
            // Reject null or mismatched dataset ID or during dataset rebuild
            return
        }
        val scopeType = bundle.getString(UnifiedStatisticsFiltersBottomSheet.EXTRA_SCOPE_TYPE) ?: return
        val containerIdStr = bundle.getString(UnifiedStatisticsFiltersBottomSheet.EXTRA_CONTAINER_ID)
        val includeDescendants = bundle.getBoolean(UnifiedStatisticsFiltersBottomSheet.EXTRA_INCLUDE_DESCENDANTS, true)

        val newScope = when (scopeType) {
            UnifiedStatisticsFiltersBottomSheet.SCOPE_ALL -> UnifiedScope.All
            UnifiedStatisticsFiltersBottomSheet.SCOPE_UNASSIGNED -> UnifiedScope.Unassigned
            UnifiedStatisticsFiltersBottomSheet.SCOPE_CONTAINER -> {
                if (containerIdStr != null) {
                    UnifiedScope.Container(ContainerId(containerIdStr), includeDescendants)
                } else {
                    UnifiedScope.All
                }
            }
            else -> UnifiedScope.All
        }

        if (newScope != currentScope) {
            currentScope = newScope
            savePreferencesForCurrentScope()
            // Reset old mismatched report immediately
            currentLoadedReport = null
            currentLoadedKey = null
            currentUiState = UnifiedReportUiState.InitialLoading
            updateHeaderLabels()
            renderCurrentState()
            requestReportUpdate("scope_changed")
        }
    }

    // -------------------------------------------------------------------------
    // Persistence (Bundle and Namespaced Preferences)
    // -------------------------------------------------------------------------

    private fun restoreState(savedInstanceState: Bundle?, bypassArguments: Boolean = false) {
        val datasetId = component.session.datasetId
        val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val prefix = "unified_stats_${datasetId}_"

        if (savedInstanceState != null) {
            currentTab = runCatching {
                UnifiedAnalyticsTab.valueOf(savedInstanceState.getString(KEY_TAB, UnifiedAnalyticsTab.OVERVIEW.name))
            }.getOrDefault(UnifiedAnalyticsTab.OVERVIEW)

            currentPeriod = runCatching {
                UnifiedPeriod.valueOf(savedInstanceState.getString(KEY_PERIOD, UnifiedPeriod.WEEK.name))
            }.getOrDefault(UnifiedPeriod.WEEK)

            val y = savedInstanceState.getInt(KEY_ANCHOR_YEAR, lastKnownToday.year)
            val m = savedInstanceState.getInt(KEY_ANCHOR_MONTH, lastKnownToday.month)
            val d = savedInstanceState.getInt(KEY_ANCHOR_DAY, lastKnownToday.day)
            currentAnchorDate = LocalDate(y, m, d)

            val scopeType = savedInstanceState.getString(KEY_SCOPE_TYPE, UnifiedStatisticsFiltersBottomSheet.SCOPE_ALL)
            val cid = savedInstanceState.getString(KEY_SCOPE_CONTAINER_ID)
            val incDesc = savedInstanceState.getBoolean(KEY_SCOPE_INCLUDE_DESCENDANTS, true)
            currentScope = resolveScope(scopeType, cid, incDesc)
        } else {
            currentTab = runCatching {
                UnifiedAnalyticsTab.valueOf(prefs.getString("${prefix}tab", UnifiedAnalyticsTab.OVERVIEW.name)!!)
            }.getOrDefault(UnifiedAnalyticsTab.OVERVIEW)

            currentPeriod = runCatching {
                UnifiedPeriod.valueOf(prefs.getString("${prefix}period", UnifiedPeriod.WEEK.name)!!)
            }.getOrDefault(UnifiedPeriod.WEEK)

            val y = prefs.getInt("${prefix}anchor_y", lastKnownToday.year)
            val m = prefs.getInt("${prefix}anchor_m", lastKnownToday.month)
            val d = prefs.getInt("${prefix}anchor_d", lastKnownToday.day)
            currentAnchorDate = LocalDate(y, m, d)

            val args = arguments
            if (!bypassArguments && args != null && args.containsKey(KEY_SCOPE_TYPE)) {
                val scopeType = args.getString(KEY_SCOPE_TYPE, UnifiedStatisticsFiltersBottomSheet.SCOPE_ALL)
                val cid = args.getString(KEY_SCOPE_CONTAINER_ID)
                val incDesc = args.getBoolean(KEY_SCOPE_INCLUDE_DESCENDANTS, true)
                currentScope = resolveScope(scopeType, cid, incDesc)
            } else {
                val scopeType = prefs.getString("${prefix}scope_type", UnifiedStatisticsFiltersBottomSheet.SCOPE_ALL)
                val cid = prefs.getString("${prefix}scope_cid", null)
                val incDesc = prefs.getBoolean("${prefix}scope_inc_desc", true)
                currentScope = resolveScope(scopeType, cid, incDesc)
            }
        }

        clampAnchorDate()
    }

    private fun resolveScope(scopeType: String?, containerIdStr: String?, includeDescendants: Boolean): UnifiedScope {
        return when (scopeType) {
            UnifiedStatisticsFiltersBottomSheet.SCOPE_ALL -> UnifiedScope.All
            UnifiedStatisticsFiltersBottomSheet.SCOPE_UNASSIGNED -> UnifiedScope.Unassigned
            UnifiedStatisticsFiltersBottomSheet.SCOPE_CONTAINER -> {
                if (containerIdStr != null) {
                    val cid = ContainerId(containerIdStr)
                    UnifiedScope.Container(cid, includeDescendants)
                } else {
                    UnifiedScope.All
                }
            }
            else -> UnifiedScope.All
        }
    }

    private fun restorePreferencesForDataset(datasetId: String) {
        val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val prefix = "unified_stats_${datasetId}_"

        currentTab = runCatching {
            UnifiedAnalyticsTab.valueOf(prefs.getString("${prefix}tab", UnifiedAnalyticsTab.OVERVIEW.name)!!)
        }.getOrDefault(UnifiedAnalyticsTab.OVERVIEW)

        currentPeriod = runCatching {
            UnifiedPeriod.valueOf(prefs.getString("${prefix}period", UnifiedPeriod.WEEK.name)!!)
        }.getOrDefault(UnifiedPeriod.WEEK)

        val y = prefs.getInt("${prefix}anchor_y", lastKnownToday.year)
        val m = prefs.getInt("${prefix}anchor_m", lastKnownToday.month)
        val d = prefs.getInt("${prefix}anchor_d", lastKnownToday.day)
        currentAnchorDate = LocalDate(y, m, d)

        val scopeType = prefs.getString("${prefix}scope_type", UnifiedStatisticsFiltersBottomSheet.SCOPE_ALL)
        val cid = prefs.getString("${prefix}scope_cid", null)
        val incDesc = prefs.getBoolean("${prefix}scope_inc_desc", true)
        currentScope = resolveScope(scopeType, cid, incDesc)
    }

    private fun savePreferencesForTab() {
        val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val prefix = "unified_stats_${component.session.datasetId}_"
        prefs.edit().putString("${prefix}tab", currentTab.name).apply()
    }

    private fun savePreferencesForPeriod() {
        val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val prefix = "unified_stats_${component.session.datasetId}_"
        prefs.edit().putString("${prefix}period", currentPeriod.name).apply()
    }

    private fun savePreferencesForAnchor() {
        val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val prefix = "unified_stats_${component.session.datasetId}_"
        prefs.edit()
            .putInt("${prefix}anchor_y", currentAnchorDate.year)
            .putInt("${prefix}anchor_m", currentAnchorDate.month)
            .putInt("${prefix}anchor_d", currentAnchorDate.day)
            .apply()
    }

    private fun savePreferencesForCurrentScope() {
        val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val prefix = "unified_stats_${component.session.datasetId}_"
        val editor = prefs.edit()
        when (val scope = currentScope) {
            is UnifiedScope.All -> {
                editor.putString("${prefix}scope_type", UnifiedStatisticsFiltersBottomSheet.SCOPE_ALL)
                editor.remove("${prefix}scope_cid")
            }
            is UnifiedScope.Unassigned -> {
                editor.putString("${prefix}scope_type", UnifiedStatisticsFiltersBottomSheet.SCOPE_UNASSIGNED)
                editor.remove("${prefix}scope_cid")
            }
            is UnifiedScope.Container -> {
                editor.putString("${prefix}scope_type", UnifiedStatisticsFiltersBottomSheet.SCOPE_CONTAINER)
                editor.putString("${prefix}scope_cid", scope.id.value)
                editor.putBoolean("${prefix}scope_inc_desc", scope.includeDescendants)
            }
        }
        editor.apply()
    }

    companion object {
        private const val PREFS_NAME = "unified_statistics_prefs"

        private const val KEY_TAB = "unified_tab"
        private const val KEY_PERIOD = "unified_period"
        private const val KEY_ANCHOR_YEAR = "unified_anchor_year"
        private const val KEY_ANCHOR_MONTH = "unified_anchor_month"
        private const val KEY_ANCHOR_DAY = "unified_anchor_day"
        private const val KEY_SCOPE_TYPE = "unified_scope_type"
        private const val KEY_SCOPE_CONTAINER_ID = "unified_scope_cid"
        private const val KEY_SCOPE_INCLUDE_DESCENDANTS = "unified_scope_inc_desc"

        fun newInstance(
            initialContainerId: String? = null,
            includeDescendants: Boolean = true,
        ): UnifiedStatisticsFragment {
            return UnifiedStatisticsFragment().apply {
                if (initialContainerId != null) {
                    arguments = Bundle().apply {
                        putString(KEY_SCOPE_TYPE, UnifiedStatisticsFiltersBottomSheet.SCOPE_CONTAINER)
                        putString(KEY_SCOPE_CONTAINER_ID, initialContainerId)
                        putBoolean(KEY_SCOPE_INCLUDE_DESCENDANTS, includeDescendants)
                    }
                }
            }
        }
    }
}
