/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.activities.statistics

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import androidx.core.graphics.ColorUtils
import org.isoron.uhabits.core.preferences.Preferences
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
import org.isoron.uhabits.activities.statistics.views.WeeklyLimitProgressView
import org.isoron.uhabits.compose.UHabitElevationAndBorders
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
import org.isoron.uhabits.core.statistics.HabitCycleStatisticsResult
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
import kotlin.math.roundToInt

/**
 * Android unified statistics shell (PR14.2).
 * Hosts four analytic tabs: Overview, Habits, Tasks, Focus.
 * Shared period (DAY/WEEK/MONTH/YEAR/ALL) and section scope (All/Unassigned/Container IDs).
 */
class UnifiedStatisticsFragment : Fragment(), ModelObservable.Listener, Preferences.Listener {

    private val app: HabitsApplication
        get() = requireContext().applicationContext as HabitsApplication

    private val component: HabitsApplicationComponent
        get() = app.component

    private val palette get() = MainTabsThemeBridge.resolve(requireContext())
    private val isRussian get() = Locale.getDefault().language == "ru"

    private lateinit var tabLayout: TabLayout
    private lateinit var rootContainer: LinearLayout
    private lateinit var headerShellContainer: LinearLayout
    private lateinit var btnPeriodSelector: View
    private lateinit var btnScopeSelector: MaterialButton
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
    private var selectedOverviewMetric: OverviewDynamicsMetric = OverviewDynamicsMetric.HABITS
    private var currentAnchorDate: LocalDate = civilToday()
    private var lastKnownToday: LocalDate = civilToday()
    private var lastDatasetId: String = ""

    private enum class OverviewDynamicsMetric { HABITS, TASKS, FOCUS }

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
        component.preferences.addListener(this)

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
        component.preferences.removeListener(this)

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

    private fun resolveCanvasBackgroundColor(isClassic: Boolean): Int {
        return if (isClassic) {
            if (palette.isPureBlack) palette.background else palette.surface
        } else {
            palette.background
        }
    }

    private fun buildLayout(context: Context): View {
        val prefs = component.preferences
        val isClassic = prefs.isClassicSectionLayout
        val canvasBg = resolveCanvasBackgroundColor(isClassic)

        rootContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(canvasBg)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        headerShellContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(canvasBg)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // 1. Analytic Tabs (Overview, Habits, Tasks, Focus)
        tabLayout = TabLayout(context).apply {
            tag = "unified_tabs"
            tabMode = TabLayout.MODE_SCROLLABLE
            setBackgroundColor(Color.TRANSPARENT)
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
            val strip = getChildAt(0) as? ViewGroup
            if (strip != null) {
                val hPad = if (context.resources.configuration.screenWidthDp < 360) dp(9f) else dp(12f)
                for (i in 0 until strip.childCount) {
                    val tabView = strip.getChildAt(i)
                    tabView.minimumWidth = 0
                    tabView.setPadding(hPad, tabView.paddingTop, hPad, tabView.paddingBottom)
                }
            }

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
                        contentScrollView.scrollTo(0, 0)
                    }
                }
                override fun onTabUnselected(tab: TabLayout.Tab) {}
                override fun onTabReselected(tab: TabLayout.Tab) {}
            })
        }
        headerShellContainer.addView(tabLayout)
        headerShellContainer.addView(createHairlineDivider(context))

        // 2. Combined Period + Date Navigator & Compact Scope Filter (single row on standard screens;
        //    cleanly stacked two-row group on <380dp narrow viewports or >=130% font scale).
        val config = context.resources.configuration
        val isNarrowOrLargeFont = config.screenWidthDp < 380 || config.fontScale >= 1.3f

        fun styleFilterPill(button: MaterialButton) {
            button.minHeight = dp(48f)
            button.insetTop = dp(7f)
            button.insetBottom = dp(7f)
            button.isAllCaps = false
            button.textSize = 13f
            button.setTypeface(button.typeface, Typeface.BOLD)
            button.setTextColor(palette.onSurface)
            button.maxLines = 1
            button.ellipsize = android.text.TextUtils.TruncateAt.END
            button.backgroundTintList = ColorStateList.valueOf(resolvePillSurfaceColor())
            button.strokeWidth = dp(1f)
            button.strokeColor = ColorStateList.valueOf(resolvePillBorderColor())
            button.cornerRadius = dp(18f)
            button.setPadding(dp(12f), 0, dp(12f), 0)
        }

        btnPrevPeriod = AppCompatImageButton(context).apply {
            tag = "unified_prev"
            setImageResource(R.drawable.ic_arrow_up_dark)
            rotation = -90f
            background = null
            contentDescription = getString(R.string.unified_statistics_prev_period)
            imageTintList = ColorStateList.valueOf(palette.onSurfaceVariant)
            minimumWidth = dp(48f)
            minimumHeight = dp(48f)
            setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
            setOnClickListener {
                currentAnchorDate = UnifiedStatisticsStateUtils.navigatePeriod(
                    currentPeriod, currentAnchorDate, -1, component.preferences.firstWeekday
                )
                clampAndRequestUpdate("nav_prev")
            }
        }

        tvDateRange = TextView(context).apply {
            tag = "unified_date_range"
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL
            maxLines = if (isNarrowOrLargeFont) 2 else 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(palette.onSurface)
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        }

        val periodPillVisual = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), dp(7f), dp(10f), dp(7f))
            background = GradientDrawable().apply {
                setColor(resolvePillSurfaceColor())
                setStroke(dp(1f), resolvePillBorderColor())
                cornerRadius = dp(18f).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            addView(tvDateRange)
            addView(TextView(context).apply {
                text = "▾"
                textSize = 11f
                setTextColor(palette.onSurfaceVariant)
                setPadding(dp(6f), 0, 0, 0)
            })
        }

        // Wrapper preserves strict >=48dp touch height while keeping the visible pill ~34dp tall.
        btnPeriodSelector = FrameLayout(context).apply {
            tag = "unified_period"
            isClickable = true
            isFocusable = true
            minimumHeight = dp(48f)
            foregroundGravity = Gravity.CENTER_VERTICAL
            (this as FrameLayout).addView(periodPillVisual, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL
            ))
            setOnClickListener { showPeriodSelectorMenu(it) }
        }

        btnNextPeriod = AppCompatImageButton(context).apply {
            tag = "unified_next"
            setImageResource(R.drawable.ic_arrow_down_dark)
            rotation = -90f
            background = null
            contentDescription = getString(R.string.unified_statistics_next_period)
            imageTintList = ColorStateList.valueOf(palette.onSurfaceVariant)
            minimumWidth = dp(48f)
            minimumHeight = dp(48f)
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

        btnScopeSelector = MaterialButton(
            context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            tag = "unified_scope"
            styleFilterPill(this)
            setOnClickListener {
                UnifiedStatisticsFiltersBottomSheet.show(childFragmentManager, currentScope, component.session.datasetId)
            }
        }

        val datePeriodCluster = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(btnPrevPeriod)
            addView(btnPeriodSelector.apply {
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                )
            })
            addView(btnNextPeriod)
        }
        // Center/constrain pill nicely inside its flex slot on narrow screens or expand smoothly
        (btnPeriodSelector as FrameLayout).getChildAt(0).layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER_VERTICAL
        )

        val controlsBar = if (isNarrowOrLargeFont) {
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8f), dp(2f), dp(8f), dp(4f))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                addView(datePeriodCluster, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ))
                btnScopeSelector.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = dp(4f)
                    marginEnd = dp(4f)
                }
                addView(btnScopeSelector)
            }
        } else {
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(6f), dp(2f), dp(12f), dp(2f))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                addView(datePeriodCluster, LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                ))
                btnScopeSelector.maxWidth = dp(148f)
                btnScopeSelector.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = dp(4f)
                }
                addView(btnScopeSelector)
            }
        }

        headerShellContainer.addView(controlsBar)
        if (isClassic) {
            headerShellContainer.addView(createHairlineDivider(context))
        }
        rootContainer.addView(headerShellContainer)

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
        rootContainer.addView(statusBanner)

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
        rootContainer.addView(loadingProgressBar)

        // 6. Content Scroll View
        contentScrollView = ScrollView(context).apply {
            tag = "unified_content"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        val showBorders = prefs.showHabitCardBorders
        contentContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val padH = if (isClassic || !showBorders) 0 else dp(16f)
            val padT = if (isClassic) 0 else if (!showBorders) dp(4f) else dp(8f)
            setPadding(padH, padT, padH, dp(24f))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        contentScrollView.addView(contentContainer)
        rootContainer.addView(contentScrollView)

        return rootContainer
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

        val periodLabel = when (currentPeriod) {
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
        btnPrevPeriod.visibility = if (isAll) View.GONE else View.VISIBLE
        btnNextPeriod.visibility = if (isAll) View.GONE else View.VISIBLE

        val isLatest = UnifiedStatisticsStateUtils.isLatestAllowedPeriod(
            currentPeriod, currentAnchorDate, lastKnownToday, component.preferences.firstWeekday
        )
        btnNextPeriod.isEnabled = !isLatest
        btnNextPeriod.alpha = if (!isLatest) 1.0f else 0.35f

        val resolvedAllFrom = currentLoadedReport?.takeIf { currentLoadedKey?.period == UnifiedPeriod.ALL }?.context?.from
        val (start, end) = UnifiedStatisticsStateUtils.calculatePeriodRange(
            currentPeriod, currentAnchorDate, lastKnownToday, component.preferences.firstWeekday, resolvedAllFrom
        )
        val rangeText: String? = when (currentPeriod) {
            UnifiedPeriod.DAY -> {
                if (start.year == lastKnownToday.year) {
                    StatisticsRedesignModels.formatCompactDateRange(start, start, lastKnownToday.year, isRussian)
                } else {
                    dateFormatter.longFormat(start)
                }
            }
            UnifiedPeriod.WEEK -> StatisticsRedesignModels.formatCompactDateRange(
                start = start,
                end = end,
                referenceYear = lastKnownToday.year,
                isRussian = isRussian,
                alwaysIncludeYear = false
            )
            UnifiedPeriod.MONTH -> {
                val monthName = dateFormatter.longMonthName(start)
                if (start.year == lastKnownToday.year) monthName else "$monthName ${start.year}"
            }
            UnifiedPeriod.YEAR -> start.year.toString()
            UnifiedPeriod.ALL -> {
                resolvedAllFrom?.let {
                    getString(R.string.unified_statistics_since_date, dateFormatter.longFormat(it))
                }
            }
        }
        tvDateRange.text = StatisticsRedesignModels.formatPeriodHeaderLabel(periodLabel, rangeText)
        btnPeriodSelector.contentDescription = tvDateRange.text
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
        when (currentTab) {
            UnifiedAnalyticsTab.OVERVIEW -> renderOverviewTab(report)
            UnifiedAnalyticsTab.HABITS -> renderHabitsTab(report)
            UnifiedAnalyticsTab.TASKS -> renderTasksTab(report)
            UnifiedAnalyticsTab.FOCUS -> renderFocusTab(report)
        }
    }

    private fun hasCadenceRowData(aggregate: HabitCadenceAggregate): Boolean =
        aggregate.eligibleCount > 0 || aggregate.ongoingCount > 0

    private fun hasCadenceTabData(habits: HabitCycleStatisticsResult, cadence: HabitCadence): Boolean {
        val agg = when (cadence) {
            HabitCadence.DAILY -> habits.daily
            HabitCadence.WEEKLY -> habits.weekly
            HabitCadence.MONTHLY -> habits.monthly
            HabitCadence.UNSUPPORTED -> habits.unsupported
        }
        return hasCadenceRowData(agg) ||
            habits.dateBuckets[cadence].orEmpty().any {
                it.satisfiedCount + it.notSatisfiedCount + it.unknownCount > 0
            } ||
            habits.habitSummaries.any { it.cadence == cadence }
    }

    // 1. Overview Tab
    private fun renderOverviewTab(report: UnifiedStatisticsReport) {
        val context = requireContext()
        val pulse = StatisticsRedesignModels.buildOverviewPulse(report, isRussian)
        val config = context.resources.configuration
        val isNarrowOrLargeFont = config.screenWidthDp < 360 || config.fontScale >= 1.3f

        val overviewLayout = LinearLayout(context).apply {
            tag = "habitOverviewLayout"
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // 1. Compact Pulse Hero Card (never mixes daily/weekly/limit targets)
        val habits = report.habits
        val pulseCard = createCardView(context)
        val pulseLayout = LinearLayout(context).apply {
            tag = "overviewCadenceGroup"
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
        }

        val dailyHeroRow = LinearLayout(context).apply {
            tag = "habitCadenceDailyRow"
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48f)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (isRefreshingState || currentLoadedReport !== report) return@setOnClickListener
                selectedHabitCadence = HabitCadence.DAILY
                tabLayout.getTabAt(UnifiedAnalyticsTab.HABITS.ordinal)?.select()
            }
        }
        val dailyLeftCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        dailyLeftCol.addView(createSectionSubtitle(context, getString(R.string.unified_statistics_pulse_daily_label)).apply {
            setPadding(0, 0, 0, dp(2f))
        })
        if (pulse.dailyCompletionPercent != null && pulse.dailyFractionLabel != null) {
            val pctLine = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.BOTTOM
            }
            pctLine.addView(TextView(context).apply {
                text = "${pulse.dailyCompletionPercent}%"
                textSize = 30f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(palette.onSurface)
            })
            pctLine.addView(TextView(context).apply {
                text = pulse.dailyFractionLabel
                textSize = 13f
                setTextColor(palette.onSurfaceVariant)
                setPadding(dp(8f), 0, 0, dp(4f))
            })
            dailyLeftCol.addView(pctLine)
        } else {
            dailyLeftCol.addView(TextView(context).apply {
                text = if ((habits?.daily?.ongoingCount ?: 0) > 0) {
                    getString(R.string.unified_statistics_habits_ongoing_in_progress)
                } else {
                    getString(R.string.unified_statistics_pulse_daily_empty)
                }
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(palette.onSurfaceVariant)
            })
        }
        dailyHeroRow.addView(dailyLeftCol)
        dailyHeroRow.addView(TextView(context).apply {
            text = "›"
            textSize = 20f
            setTextColor(palette.onSurfaceVariant)
        })
        pulseLayout.addView(dailyHeroRow)

        pulseLayout.addView(createHairlineDivider(context, topMarginDp = 8f, bottomMarginDp = 10f))

        // Compact Tasks & Focus 2-column strip inside Pulse Hero
        val kpiRow = LinearLayout(context).apply {
            orientation = if (isNarrowOrLargeFont) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val tasksKpi = LinearLayout(context).apply {
            tag = "overviewTasksCard"
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            minimumHeight = dp(48f)
            layoutParams = if (isNarrowOrLargeFont) {
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(8f)
                }
            } else {
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(12f)
                }
            }
            setOnClickListener {
                tabLayout.getTabAt(UnifiedAnalyticsTab.TASKS.ordinal)?.select()
            }
        }
        tasksKpi.addView(TextView(context).apply {
            text = getString(R.string.unified_statistics_overview_tasks_title)
            textSize = 12f
            setTextColor(palette.onSurfaceVariant)
        })
        val tasks = report.tasks
        if (tasks != null) {
            tasksKpi.addView(TextView(context).apply {
                text = pulse.tasksCompletedCount.toString()
                textSize = 20f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(palette.onSurface)
                setPadding(0, dp(2f), 0, dp(1f))
            })
            val tasksSub = when {
                pulse.tasksOverdueCount > 0 ->
                    getString(R.string.unified_statistics_pulse_tasks_overdue, pulse.tasksOverdueCount)
                pulse.tasksCreatedCount > 0 ->
                    getString(R.string.unified_statistics_pulse_tasks_created, pulse.tasksCreatedCount)
                else ->
                    if (isRussian) "В периоде пусто" else "None this period"
            }
            tasksKpi.addView(TextView(context).apply {
                text = tasksSub
                textSize = 12f
                setTextColor(if (pulse.tasksOverdueCount > 0) 0xFFE53935.toInt() else palette.onSurfaceVariant)
            })
        } else {
            tasksKpi.addView(TextView(context).apply {
                text = "—"
                textSize = 18f
                setTextColor(palette.onSurfaceVariant)
            })
        }
        kpiRow.addView(tasksKpi)

        val focusKpi = LinearLayout(context).apply {
            tag = "overviewFocusCard"
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            minimumHeight = dp(48f)
            layoutParams = if (isNarrowOrLargeFont) {
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            } else {
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(12f)
                }
            }
            setOnClickListener {
                tabLayout.getTabAt(UnifiedAnalyticsTab.FOCUS.ordinal)?.select()
            }
        }
        focusKpi.addView(TextView(context).apply {
            text = getString(R.string.unified_statistics_overview_focus_title)
            textSize = 12f
            setTextColor(palette.onSurfaceVariant)
        })
        val focus = report.focus
        if (focus != null && !pulse.focusHasOverflow) {
            focusKpi.addView(TextView(context).apply {
                text = pulse.focusDurationFormatted ?: "0"
                textSize = 20f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(palette.onSurface)
                setPadding(0, dp(2f), 0, dp(1f))
            })
            val focusMetaParts = buildList {
                add(getString(R.string.unified_statistics_sessions_pill, pulse.focusSessionCount))
                pulse.focusAvgPerDayFormatted?.let(::add)
            }
            focusKpi.addView(TextView(context).apply {
                tag = "overview_focus_f2"
                text = focusMetaParts.joinToString(" · ")
                textSize = 12f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(palette.onSurfaceVariant)
            })
        } else {
            focusKpi.addView(TextView(context).apply {
                text = if (pulse.focusHasOverflow) getString(R.string.unified_statistics_overflow) else "—"
                textSize = 16f
                setTextColor(palette.onSurfaceVariant)
            })
        }
        kpiRow.addView(focusKpi)
        pulseLayout.addView(kpiRow)

        pulseCard.addView(pulseLayout)
        overviewLayout.addView(pulseCard)

        // 2. Single Interactive Period Dynamics Card (Habits | Tasks | Focus switcher)
        val habitColumns = if (habits != null) {
            DiscreteChartBucketer.buildHabitColumns(
                currentPeriod, HabitCadence.DAILY, habits, lastKnownToday, palette, isRussian,
                includeUnknownSegment = false
            )
        } else emptyList()
        val taskColumns = if (tasks != null) {
            DiscreteChartBucketer.buildTaskColumns(
                currentPeriod, tasks, lastKnownToday, isRussian
            )
        } else emptyList()
        val focusColumns = if (focus != null) {
            DiscreteChartBucketer.buildFocusColumns(
                currentPeriod, focus, lastKnownToday, isRussian
            )
        } else emptyList()

        val hasHabitChart = habitColumns.any { it.value > 0L }
        val hasTaskChart = taskColumns.any { it.value > 0L }
        val hasFocusChart = focusColumns.any { it.value > 0L }

        val availableMetrics = buildList {
            if (hasHabitChart) add(OverviewDynamicsMetric.HABITS)
            if (hasTaskChart) add(OverviewDynamicsMetric.TASKS)
            if (hasFocusChart) add(OverviewDynamicsMetric.FOCUS)
        }

        if (availableMetrics.isNotEmpty()) {
            if (selectedOverviewMetric !in availableMetrics) {
                selectedOverviewMetric = availableMetrics.first()
            }

            val dynamicsCard = createCardView(context, topMarginDp = 10f)
            val dynamicsLayout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
            }

            dynamicsLayout.addView(createSectionHeader(context, getString(R.string.unified_statistics_overview_dynamics_title)).apply {
                setPadding(0, 0, 0, dp(8f))
            })

            if (availableMetrics.size > 1) {
                val switcherRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = dp(8f)
                    }
                }
                availableMetrics.forEachIndexed { idx, metric ->
                    val isSelected = selectedOverviewMetric == metric
                    val labelRes = when (metric) {
                        OverviewDynamicsMetric.HABITS -> R.string.unified_statistics_dynamics_metric_habits
                        OverviewDynamicsMetric.TASKS -> R.string.unified_statistics_dynamics_metric_tasks
                        OverviewDynamicsMetric.FOCUS -> R.string.unified_statistics_dynamics_metric_focus
                    }
                    switcherRow.addView(MaterialButton(
                        context, null, com.google.android.material.R.attr.borderlessButtonStyle
                    ).apply {
                        text = getString(labelRes)
                        minHeight = dp(48f)
                        insetTop = dp(7f)
                        insetBottom = dp(7f)
                        isAllCaps = false
                        textSize = 13f
                        setTypeface(typeface, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
                        setTextColor(if (isSelected) palette.accent else palette.onSurfaceVariant)
                        backgroundTintList = ColorStateList.valueOf(
                            if (isSelected) resolvePillSurfaceColor() else Color.TRANSPARENT
                        )
                        strokeWidth = if (isSelected) dp(1f) else 0
                        strokeColor = ColorStateList.valueOf(resolvePillBorderColor())
                        cornerRadius = dp(12f)
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                            if (idx > 0) marginStart = dp(4f)
                        }
                        setOnClickListener {
                            if (selectedOverviewMetric != metric) {
                                selectedOverviewMetric = metric
                                renderCurrentState()
                            }
                        }
                    })
                }
                dynamicsLayout.addView(switcherRow)
            }

            when (selectedOverviewMetric) {
                OverviewDynamicsMetric.HABITS -> {
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
                        context, habitChart, habitColumns, topMarginDp = 4f, bottomMarginDp = 6f
                    ))
                    val dailyAgg = habits?.daily
                    if (dailyAgg != null) {
                        val legendRow = LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            setPadding(0, dp(2f), 0, 0)
                        }
                        legendRow.addView(createLegendDotItem(
                            context, 0xFF4CAF50.toInt(),
                            getString(R.string.unified_statistics_legend_done, dailyAgg.satisfiedCount)
                        ))
                        if (dailyAgg.notSatisfiedCount > 0) {
                            legendRow.addView(createLegendDotItem(
                                context, 0xFFE53935.toInt(),
                                getString(R.string.unified_statistics_legend_missed, dailyAgg.notSatisfiedCount)
                            ).apply {
                                setPadding(dp(16f), 0, 0, 0)
                            })
                        }
                        dynamicsLayout.addView(legendRow)
                    }
                }
                OverviewDynamicsMetric.TASKS -> {
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
                        context, taskChart, taskColumns, topMarginDp = 4f, bottomMarginDp = 4f
                    ))
                }
                OverviewDynamicsMetric.FOCUS -> {
                    val focusChart = DiscreteColumnBarChart(context).apply {
                        tag = "overviewFocusChart"
                        isEnabled = !isRefreshingState
                        setData(focusColumns, palette, unitLabel = if (isRussian) "время" else "time",
                            valueFormatter = { UnifiedStatisticsStateUtils.formatCompactDurationMs(it, isRussian) }) { _, item ->
                            if (isRefreshingState || currentLoadedReport !== report) return@setData
                            val desc = item.descriptor as? FocusDrilldownDescriptor ?: return@setData
                            UnifiedStatisticsDrilldownBottomSheet.showForFocus(
                                childFragmentManager,
                                item.label,
                                UnifiedStatisticsStateUtils.formatExactDurationMs(item.value, isRussian),
                                desc
                            )
                        }
                    }
                    dynamicsLayout.addView(wrapScrollableChart(
                        context, focusChart, focusColumns, topMarginDp = 4f, bottomMarginDp = 4f
                    ))
                }
            }

            dynamicsCard.addView(dynamicsLayout)
            overviewLayout.addView(dynamicsCard)
        }

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

        // 1. Cadence Switcher + Headline KPI + Interactive Chart Card
        val chartCard = createCardView(context)
        val chartLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
        }

        val activeCadences = listOf(HabitCadence.DAILY, HabitCadence.WEEKLY, HabitCadence.MONTHLY)
            .filter { hasCadenceTabData(habits, it) }
            .ifEmpty { listOf(HabitCadence.DAILY) }
        if (selectedHabitCadence !in activeCadences) {
            selectedHabitCadence = activeCadences.first()
        }

        if (activeCadences.size > 1) {
            val cadenceSwitcher = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(10f)
                }
            }
            activeCadences.forEachIndexed { idx, cadence ->
                val isSelected = selectedHabitCadence == cadence
                val (labelRes, tagStr) = when (cadence) {
                    HabitCadence.DAILY -> R.string.unified_statistics_cadence_daily to "habitCadenceTabDaily"
                    HabitCadence.WEEKLY -> R.string.unified_statistics_cadence_weekly to "habitCadenceTabWeekly"
                    HabitCadence.MONTHLY -> R.string.unified_statistics_cadence_monthly to "habitCadenceTabMonthly"
                    HabitCadence.UNSUPPORTED -> R.string.unified_statistics_cadence_unsupported to "habitCadenceTabUnsupported"
                }
                cadenceSwitcher.addView(MaterialButton(
                    context, null, com.google.android.material.R.attr.borderlessButtonStyle
                ).apply {
                    tag = tagStr
                    text = getString(labelRes)
                    minHeight = dp(48f)
                    insetTop = dp(7f)
                    insetBottom = dp(7f)
                    isAllCaps = false
                    textSize = 13f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(if (isSelected) palette.accent else palette.onSurfaceVariant)
                    setTypeface(typeface, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
                    backgroundTintList = ColorStateList.valueOf(
                        if (isSelected) resolvePillSurfaceColor() else Color.TRANSPARENT
                    )
                    strokeWidth = if (isSelected) dp(1f) else 0
                    strokeColor = ColorStateList.valueOf(resolvePillBorderColor())
                    cornerRadius = dp(12f)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (idx > 0) marginStart = dp(4f)
                    }
                    setOnClickListener {
                        if (selectedHabitCadence != cadence) {
                            selectedHabitCadence = cadence
                            renderCurrentState()
                        }
                    }
                })
            }
            chartLayout.addView(cadenceSwitcher)
        }

        val activeAgg = when (selectedHabitCadence) {
            HabitCadence.DAILY -> habits.daily
            HabitCadence.WEEKLY -> habits.weekly
            HabitCadence.MONTHLY -> habits.monthly
            else -> habits.unsupported
        }
        val activeCadenceLabel = when (selectedHabitCadence) {
            HabitCadence.DAILY -> getString(R.string.unified_statistics_cadence_daily)
            HabitCadence.WEEKLY -> getString(R.string.unified_statistics_cadence_weekly)
            HabitCadence.MONTHLY -> getString(R.string.unified_statistics_cadence_monthly)
            else -> getString(R.string.unified_statistics_cadence_unsupported)
        }
        val activeCadenceTag = when (selectedHabitCadence) {
            HabitCadence.DAILY -> "habitCadenceDailyRow"
            HabitCadence.WEEKLY -> "habitCadenceWeeklyRow"
            HabitCadence.MONTHLY -> "habitCadenceMonthlyRow"
            else -> "habitCadenceOtherRow"
        }

        val headlineRow = LinearLayout(context).apply {
            tag = activeCadenceTag
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48f)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (isRefreshingState || currentLoadedReport !== report) return@setOnClickListener
                UnifiedStatisticsDrilldownBottomSheet.showForHabitCycles(
                    childFragmentManager, activeCadenceLabel, "",
                    HabitsDrilldownDescriptor.CadenceCycles(selectedHabitCadence)
                )
            }
        }
        val headlineLeft = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        if (activeAgg.knownCount > 0) {
            val pct = ((activeAgg.satisfiedCount.toDouble() / activeAgg.knownCount.toDouble()) * 100.0).roundToInt()
            headlineLeft.addView(TextView(context).apply {
                text = "$pct%"
                textSize = 28f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(palette.onSurface)
            })
            val fractionStr = if (isRussian) {
                "${activeAgg.satisfiedCount} из ${activeAgg.knownCount}"
            } else {
                "${activeAgg.satisfiedCount}/${activeAgg.knownCount}"
            }
            headlineLeft.addView(TextView(context).apply {
                text = fractionStr
                textSize = 13f
                setTextColor(palette.onSurfaceVariant)
                setPadding(dp(8f), 0, 0, dp(3f))
            })
        } else {
            headlineLeft.addView(TextView(context).apply {
                text = if (activeAgg.ongoingCount > 0) {
                    getString(R.string.unified_statistics_habits_ongoing_in_progress)
                } else {
                    getString(R.string.unified_statistics_cadence_no_closed_cycles)
                }
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(palette.onSurfaceVariant)
            })
        }
        headlineRow.addView(headlineLeft)
        headlineRow.addView(TextView(context).apply {
            text = "›"
            textSize = 20f
            setTextColor(palette.onSurfaceVariant)
        })
        chartLayout.addView(headlineRow)

        val columns = DiscreteChartBucketer.buildHabitColumns(
            currentPeriod, selectedHabitCadence, habits, lastKnownToday, palette, isRussian,
            includeUnknownSegment = false
        )

        if (columns.any { it.value > 0L }) {
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
                context, chartView, columns, topMarginDp = 8f, bottomMarginDp = 6f
            ))

            val legendRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(2f), 0, 0)
            }
            legendRow.addView(createLegendDotItem(
                context, 0xFF4CAF50.toInt(),
                getString(R.string.unified_statistics_legend_done, activeAgg.satisfiedCount)
            ))
            if (activeAgg.notSatisfiedCount > 0) {
                legendRow.addView(createLegendDotItem(
                    context, 0xFFE53935.toInt(),
                    getString(R.string.unified_statistics_legend_missed, activeAgg.notSatisfiedCount)
                ).apply {
                    setPadding(dp(16f), 0, 0, 0)
                })
            }
            chartLayout.addView(legendRow)
        } else {
            val emptyChartTv = createSecondaryInfo(context, getString(R.string.unified_statistics_habits_chart_empty)).apply {
                tag = "habitCadenceChart"
                setPadding(0, dp(12f), 0, dp(8f))
            }
            chartLayout.addView(emptyChartTv)
        }

        StatisticsRedesignModels.buildHabitsTrendSummary(
            selectedHabitCadence, habits, lastKnownToday, isRussian
        )?.let { trendText ->
            chartLayout.addView(TextView(context).apply {
                tag = "habitCadenceTrendSummary"
                text = trendText
                textSize = 12.5f
                setTextColor(palette.onSurfaceVariant)
                setPadding(0, dp(6f), 0, 0)
            })
        }

        chartCard.addView(chartLayout)
        contentContainer.addView(chartCard)

        // 2. Concise Weekly Goals & Limits Summary (tag: habitOngoingSummary — excludes noisy daily cycles)
        val previewCycles = StatisticsRedesignModels.selectCyclesForProgressSection(
            habits, selectedHabitCadence, limit = 4
        )
        val totalProgressCycles = StatisticsRedesignModels.selectCyclesForProgressSection(
            habits, selectedHabitCadence, limit = Int.MAX_VALUE
        ).size
        if (previewCycles.isNotEmpty()) {
            val ongoingCard = createCardView(context, topMarginDp = 10f).apply {
                tag = "habitOngoingSummary"
            }
            val ongoingLayout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
            }
            val ongoingTitleRes = if (report.context.to < report.context.habitToday && habits.ongoingCycles.isEmpty()) {
                R.string.unified_statistics_habits_historical_cycles_title
            } else {
                R.string.unified_statistics_habits_progress_title
            }
            ongoingLayout.addView(createSectionHeader(context, getString(ongoingTitleRes)).apply {
                setPadding(0, 0, 0, dp(6f))
            })

            previewCycles.forEachIndexed { index, cycle ->
                if (index > 0) {
                    ongoingLayout.addView(createHairlineDivider(context, topMarginDp = 4f, bottomMarginDp = 4f))
                }
                val model = StatisticsRedesignModels.buildHabitProgressRow(
                    cycle = cycle,
                    context = report.context,
                    dateFormatter = ::formatStatisticsDate,
                    isRussian = isRussian
                )
                val toneColor = resolveToneColor(model.tone)
                val statusTextColor = if (model.tone == StatisticsRedesignModels.ProgressTone.NEUTRAL) {
                    palette.onSurfaceVariant
                } else {
                    toneColor
                }

                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    isClickable = true
                    isFocusable = true
                    tag = model.cycleKey
                    minimumHeight = dp(48f)
                    setPadding(0, dp(8f), 0, dp(8f))
                    setOnClickListener {
                        UnifiedStatisticsCycleDetailBottomSheet.show(
                            childFragmentManager,
                            model.cycleKey,
                            report.context.datasetId,
                            statisticsQuerySignature(report.context)
                        )
                    }
                }

                val topLine = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                topLine.addView(TextView(context).apply {
                    text = model.habitName
                    textSize = 15f
                    maxLines = if (context.resources.configuration.fontScale >= 1.5f) 2 else 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(palette.onSurface)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginEnd = dp(8f)
                    }
                })
                topLine.addView(TextView(context).apply {
                    text = model.headlineValue
                    textSize = 14f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(palette.onSurface)
                })
                row.addView(topLine)

                row.addView(WeeklyLimitProgressView(context).apply {
                    setState(
                        progressRatio = model.progressRatio,
                        fillColor = toneColor,
                        palette = palette,
                        showLimitMarker = model.isAtMost
                    )
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = dp(6f)
                        bottomMargin = dp(6f)
                    }
                })

                val bottomLine = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                bottomLine.addView(TextView(context).apply {
                    text = model.statusLabel
                    textSize = 12.5f
                    if (model.tone != StatisticsRedesignModels.ProgressTone.NEUTRAL) {
                        setTypeface(typeface, Typeface.BOLD)
                    }
                    setTextColor(statusTextColor)
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (model.showDateRange && model.dateRangeLabel.isNotBlank()) {
                            marginEnd = dp(8f)
                        }
                    }
                })
                if (model.showDateRange && model.dateRangeLabel.isNotBlank()) {
                    bottomLine.addView(TextView(context).apply {
                        text = model.dateRangeLabel
                        textSize = 12f
                        setTextColor(palette.onSurfaceVariant)
                    })
                }
                row.addView(bottomLine)
                ongoingLayout.addView(row)
            }

            if (totalProgressCycles > previewCycles.size && habits.ongoingCycles.isNotEmpty()) {
                ongoingLayout.addView(MaterialButton(context, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                    text = getString(R.string.unified_statistics_habits_ongoing_see_all, totalProgressCycles)
                    setTextColor(palette.accent)
                    isAllCaps = false
                    minHeight = dp(48f)
                    setOnClickListener {
                        UnifiedStatisticsDrilldownBottomSheet.showForHabitCycles(
                            childFragmentManager, getString(ongoingTitleRes), "",
                            HabitsDrilldownDescriptor.OngoingCycles
                        )
                    }
                })
            }

            ongoingCard.addView(ongoingLayout)
            contentContainer.addView(ongoingCard)
        }

        // 3. Compact Habits Drilldown & Compare Action Strip (never duplicates the Habits home list)
        val totalHabitCount = habits.habitSummaries.distinctBy { it.habitUuid }.size
        val config = context.resources.configuration
        val isCompactActionLayout = config.screenWidthDp < 360 || config.fontScale >= 1.3f

        val habitsActionsCard = createCardView(context, topMarginDp = 10f).apply {
            tag = "habitAlphaPreviewList"
        }
        val actionsRow = LinearLayout(context).apply {
            orientation = if (isCompactActionLayout) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = if (isCompactActionLayout) Gravity.START else Gravity.CENTER_VERTICAL
            setPadding(dp(16f), dp(10f), dp(16f), dp(10f))
        }
        actionsRow.addView(MaterialButton(context, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            tag = "habitAllHabitsButton"
            text = if (totalHabitCount > 0) {
                getString(R.string.unified_statistics_habits_all_button, totalHabitCount)
            } else {
                getString(R.string.unified_statistics_habits_all_button_no_count)
            }
            isAllCaps = false
            setTextColor(palette.accent)
            minHeight = dp(48f)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(0, 0, dp(8f), 0)
            layoutParams = if (isCompactActionLayout) {
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            } else {
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            setOnClickListener {
                UnifiedStatisticsDrilldownBottomSheet.showForHabitList(
                    childFragmentManager, getString(R.string.unified_statistics_habits_preview_title), ""
                )
            }
        })
        actionsRow.addView(MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            tag = "habitCompareEntry"
            text = getString(R.string.unified_statistics_action_compare_habits)
            isAllCaps = false
            textSize = 13f
            setTextColor(palette.accent)
            backgroundTintList = ColorStateList.valueOf(resolvePillSurfaceColor())
            strokeWidth = dp(1f)
            strokeColor = ColorStateList.valueOf(resolvePillBorderColor())
            cornerRadius = dp(16f)
            minHeight = dp(48f)
            insetTop = dp(7f)
            insetBottom = dp(7f)
            setPadding(dp(14f), 0, dp(14f), 0)
            setOnClickListener {
                startActivity(app.component.intentFactory.startCompareHabitsActivity(context))
            }
        })
        habitsActionsCard.addView(actionsRow)
        contentContainer.addView(habitsActionsCard)
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
            val emptyModel = StatisticsRedesignModels.buildTasksEmptyState(currentScope, currentPeriod, isRussian)
            val emptyCard = createCardView(context).apply {
                tag = "unified_tasks_empty_card"
            }
            val emptyLayout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(18f), dp(18f), dp(18f), dp(18f))
            }
            emptyLayout.addView(TextView(context).apply {
                text = emptyModel.title
                textSize = 15.5f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(palette.onSurface)
                gravity = Gravity.CENTER
            })
            emptyLayout.addView(TextView(context).apply {
                text = emptyModel.subtitle
                textSize = 13f
                setTextColor(palette.onSurfaceVariant)
                gravity = Gravity.CENTER
                setPadding(0, dp(4f), 0, 0)
            })
            if (emptyModel.showResetScopeButton) {
                emptyLayout.addView(MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = getString(R.string.unified_statistics_scope_all)
                    isAllCaps = false
                    setTextColor(palette.accent)
                    backgroundTintList = ColorStateList.valueOf(resolvePillSurfaceColor())
                    strokeWidth = dp(1f)
                    strokeColor = ColorStateList.valueOf(resolvePillBorderColor())
                    cornerRadius = dp(16f)
                    minHeight = dp(48f)
                    insetTop = dp(7f)
                    insetBottom = dp(7f)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = dp(10f)
                    }
                    setOnClickListener {
                        currentScope = UnifiedScope.All
                        savePreferencesForCurrentScope()
                        currentLoadedReport = null
                        currentLoadedKey = null
                        currentUiState = UnifiedReportUiState.InitialLoading
                        updateHeaderLabels()
                        renderCurrentState()
                        requestReportUpdate("scope_reset_empty_tasks")
                    }
                })
            }
            emptyCard.addView(emptyLayout)
            contentContainer.addView(emptyCard)
            return
        }

        val isRefreshing = isRefreshingState

        // T1 & T2 Headline Card + Compact 3-KPI Strip
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
            setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
        }
        flowLayout.addView(createSectionHeader(context, getString(R.string.unified_statistics_overview_tasks_title)).apply {
            setPadding(0, 0, 0, dp(8f))
        })

        val kpiRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fun addTaskKpi(
            value: String,
            label: String,
            color: Int = palette.onSurface,
            tagStr: String? = null,
            onClick: (() -> Unit)? = null
        ) {
            val col = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                if (tagStr != null) tag = tagStr
                if (onClick != null) {
                    isClickable = !isRefreshing
                    isFocusable = !isRefreshing
                    minimumHeight = dp(48f)
                    setOnClickListener { onClick() }
                }
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            col.addView(TextView(context).apply {
                text = value
                textSize = 22f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(color)
            })
            col.addView(TextView(context).apply {
                text = label
                textSize = 12f
                setTextColor(palette.onSurfaceVariant)
            })
            kpiRow.addView(col)
        }
        addTaskKpi(
            value = tasks.completedCount.toString(),
            label = getString(R.string.unified_statistics_due_state_completed)
        )
        addTaskKpi(
            value = tasks.createdCount.toString(),
            label = if (isRussian) "Создано" else "Created",
            tagStr = "unified_tasks_created",
            onClick = {
                if (!isRefreshingState) {
                    val createdTitle = getString(R.string.unified_statistics_tasks_created_count, tasks.createdCount)
                    UnifiedStatisticsDrilldownBottomSheet.showForTasks(
                        childFragmentManager, createdTitle, "${report.context.from} – ${report.context.to}",
                        TasksDrilldownDescriptor.CreatedTasks(report.context.from, report.context.to, createdTitle)
                    )
                }
            }
        )
        val overdueCount = tasks.dueSummary.pendingOverdueNowCount
        addTaskKpi(
            value = overdueCount.toString(),
            label = getString(R.string.unified_statistics_due_state_overdue),
            color = if (overdueCount > 0) 0xFFE53935.toInt() else palette.onSurface
        )
        flowLayout.addView(kpiRow)

        val taskColumns = DiscreteChartBucketer.buildTaskColumns(currentPeriod, tasks, lastKnownToday, isRussian)
        if (taskColumns.any { it.value > 0L }) {
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
                context, chartView, taskColumns, topMarginDp = 10f, bottomMarginDp = 2f
            ))
        }

        flowCard.addView(flowLayout)
        contentContainer.addView(flowCard)

        // T3: Due Cohort Card
        val dueSum = tasks.dueSummary
        val dueCard = createCardView(context, topMarginDp = 10f).apply {
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
            setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
        }
        val dueHeader = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        dueHeader.addView(createSectionHeader(context, getString(R.string.unified_statistics_tasks_due_cohort_title)).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(0, 0, 0, 0)
        })
        dueHeader.addView(createCompactBadgePill(context, "${dueSum.totalCount}"))
        dueLayout.addView(dueHeader)

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
                color = 0xFFE53935.toInt(),
                textColor = Color.WHITE,
                descriptor = TasksDrilldownDescriptor.DueCohort(TasksDrilldownDescriptor.DueSegmentFilter.OVERDUE_NOW),
            ),
            SegmentedCohortBarView.Segment(
                key = "PENDING_NOT_OVERDUE",
                count = dueSum.pendingNotOverdueNowCount,
                label = getString(R.string.unified_statistics_due_state_pending),
                color = palette.onSurfaceVariant,
                textColor = if (palette.isDark) palette.background else palette.surface,
                descriptor = TasksDrilldownDescriptor.DueCohort(TasksDrilldownDescriptor.DueSegmentFilter.PENDING_NOT_OVERDUE),
            ),
        )
        val dueBarView = SegmentedCohortBarView(context).apply {
            tag = "unified_tasks_t3_cohort"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(10f)
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
        dueCard.addView(dueLayout)
        contentContainer.addView(dueCard)

        // T4: No Due Cohort Card
        val noDueSum = tasks.noDueSummary
        val noDueCard = createCardView(context, topMarginDp = 10f).apply {
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
            setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
        }
        val noDueHeader = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        noDueHeader.addView(createSectionHeader(context, getString(R.string.unified_statistics_tasks_nodue_cohort_title)).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(0, 0, 0, 0)
        })
        noDueHeader.addView(createCompactBadgePill(context, "${noDueSum.totalCount}"))
        noDueLayout.addView(noDueHeader)

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
                topMargin = dp(10f)
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
        val pulse = StatisticsRedesignModels.buildOverviewPulse(report, isRussian)

        // F1 & F2 Headline + Interactive Chart Card
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
                    getString(R.string.unified_statistics_sessions_pill, focus.sessionCount),
                    FocusDrilldownDescriptor.SessionsRange(from, to, getString(R.string.unified_statistics_focus_total_duration_title))
                )
            }
        }
        val totalsLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
        }
        totalsLayout.addView(createSectionSubtitle(context, getString(R.string.unified_statistics_focus_total_duration_title)).apply {
            setPadding(0, 0, 0, dp(2f))
        })

        if (pulse.focusHasOverflow) {
            totalsLayout.addView(createSecondaryInfo(context, getString(R.string.unified_statistics_overflow)))
        } else {
            val heroRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, dp(4f))
            }
            heroRow.addView(TextView(context).apply {
                text = pulse.focusDurationFormatted ?: UnifiedStatisticsStateUtils.formatDurationMs(focus.totalDurationMs, isRussian)
                textSize = 28f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(palette.onSurface)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            heroRow.addView(createCompactBadgePill(
                context,
                getString(R.string.unified_statistics_sessions_pill, focus.sessionCount),
                palette.onSurface
            ).apply {
                tag = "unified_focus_f2"
            })
            totalsLayout.addView(heroRow)

            pulse.focusAvgPerDayFormatted?.let { avgStr ->
                totalsLayout.addView(TextView(context).apply {
                    text = avgStr
                    textSize = 13f
                    setTextColor(palette.onSurfaceVariant)
                })
            }
        }

        val focusColumns = DiscreteChartBucketer.buildFocusColumns(currentPeriod, focus, lastKnownToday, isRussian)
        if (focusColumns.any { it.value > 0L }) {
            val chartView = DiscreteColumnBarChart(context).apply {
                tag = "unified_focus_f1_chart"
                isEnabled = !isRefreshing
                setData(focusColumns, palette, unitLabel = if (isRussian) "время" else "time",
                    valueFormatter = { UnifiedStatisticsStateUtils.formatCompactDurationMs(it, isRussian) }) { _, item ->
                    if (isRefreshingState || currentLoadedReport !== report) return@setData
                    val desc = item.descriptor as? FocusDrilldownDescriptor ?: return@setData
                    UnifiedStatisticsDrilldownBottomSheet.showForFocus(
                        childFragmentManager,
                        item.label,
                        UnifiedStatisticsStateUtils.formatExactDurationMs(item.value, isRussian),
                        desc
                    )
                }
            }
            totalsLayout.addView(wrapScrollableChart(
                context, chartView, focusColumns, topMarginDp = 10f, bottomMarginDp = 2f
            ))
        }

        totalsCard.addView(totalsLayout)
        contentContainer.addView(totalsCard)

        // F3: By Section (clean proportional bars)
        if (StatisticsRedesignModels.shouldShowFocusBreakdownCard(focus.sectionBreakdowns.size)) {
            val sectionsCard = createCardView(context, topMarginDp = 10f).apply {
                tag = "unified_focus_f3_breakdown"
            }
            val sectionsLayout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
            }
            sectionsLayout.addView(createSectionHeader(context, getString(R.string.unified_statistics_focus_by_section_title)).apply {
                setPadding(0, 0, 0, dp(6f))
            })

            val previewItems = focus.sectionBreakdowns.take(5)
            previewItems.forEachIndexed { idx, item ->
                if (idx > 0) {
                    sectionsLayout.addView(createHairlineDivider(context, topMarginDp = 2f, bottomMarginDp = 2f))
                }
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    isClickable = !isRefreshing
                    isFocusable = !isRefreshing
                    minimumHeight = dp(48f)
                    tag = "section:${item.category}:${item.containerId?.value}"
                    setPadding(0, dp(8f), 0, dp(8f))
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
                val topLine = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                topLine.addView(TextView(context).apply {
                    text = item.name
                    textSize = 15f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(palette.onSurface)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginEnd = dp(8f)
                    }
                })
                topLine.addView(TextView(context).apply {
                    text = UnifiedStatisticsStateUtils.formatDurationMs(item.durationMs, isRussian)
                    textSize = 14f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(palette.onSurfaceVariant)
                })
                row.addView(topLine)

                val sectionRatio = if (focus.totalDurationMs > 0L) {
                    (item.durationMs.toDouble() / focus.totalDurationMs.toDouble()).toFloat().coerceIn(0f, 1f)
                } else 0f
                row.addView(WeeklyLimitProgressView(context).apply {
                    setState(
                        progressRatio = sectionRatio,
                        fillColor = palette.accent,
                        palette = palette,
                        showLimitMarker = false
                    )
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = dp(6f)
                    }
                })
                sectionsLayout.addView(row)
            }

            if (focus.sectionBreakdowns.size > 5) {
                sectionsLayout.addView(MaterialButton(context, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                    text = getString(R.string.unified_statistics_focus_show_all_sections, focus.sectionBreakdowns.size)
                    isAllCaps = false
                    setTextColor(palette.accent)
                    minHeight = dp(48f)
                    setOnClickListener {
                        if (isRefreshingState || currentLoadedReport !== report) return@setOnClickListener
                        UnifiedStatisticsDrilldownBottomSheet.showForFocus(
                            childFragmentManager,
                            getString(R.string.unified_statistics_focus_by_section_title),
                            "${focus.sectionBreakdowns.size} ${getString(R.string.unified_statistics_focus_by_section_title).lowercase()}",
                            FocusDrilldownDescriptor.AllSections
                        )
                    }
                })
            }

            sectionsCard.addView(sectionsLayout)
            contentContainer.addView(sectionsCard)
        }

        // F4: Clean Focus Source Breakdown
        if (StatisticsRedesignModels.shouldShowFocusBreakdownCard(focus.targetTypeSummaries.size)) {
            val targetCard = createCardView(context, topMarginDp = 10f).apply {
                tag = "unified_focus_f4_breakdown"
            }
            val targetLayout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
            }
            targetLayout.addView(createSectionHeader(context, getString(R.string.unified_statistics_focus_by_source_title)).apply {
                setPadding(0, 0, 0, dp(6f))
            })

            focus.targetTypeSummaries.forEachIndexed { idx, item ->
                if (idx > 0) {
                    targetLayout.addView(createHairlineDivider(context))
                }
                val durationStr = UnifiedStatisticsStateUtils.formatDurationMs(item.durationMs, isRussian)
                val sourceTitle = StatisticsRedesignModels.formatFocusTargetSourceLabel(item.targetType, isRussian)
                val sessionsStr = getString(R.string.unified_statistics_sessions_pill, item.sessionCount)

                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    tag = "target-type:${item.targetType?.name ?: "UNKNOWN"}"
                    isClickable = !isRefreshing
                    isFocusable = !isRefreshing
                    minimumHeight = dp(48f)
                    setPadding(0, dp(8f), 0, dp(8f))
                    setOnClickListener {
                        if (isRefreshingState || currentLoadedReport !== report) return@setOnClickListener
                        UnifiedStatisticsDrilldownBottomSheet.showForFocus(
                            childFragmentManager,
                            getString(R.string.unified_statistics_drilldown_target_title, sourceTitle),
                            durationStr,
                            FocusDrilldownDescriptor.TargetType(item.targetType)
                        )
                    }
                }
                row.addView(TextView(context).apply {
                    text = sourceTitle
                    textSize = 15f
                    setTextColor(palette.onSurface)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginEnd = dp(8f)
                    }
                })
                row.addView(TextView(context).apply {
                    text = "$durationStr · $sessionsStr"
                    textSize = 13.5f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(palette.onSurfaceVariant)
                })
                targetLayout.addView(row)
            }
            targetCard.addView(targetLayout)
            contentContainer.addView(targetCard)
        }
    }

    // -------------------------------------------------------------------------
    // View Helpers
    // -------------------------------------------------------------------------

    private fun resolveCardSurfaceColor(isClassic: Boolean, showBorders: Boolean): Int {
        return when {
            palette.isPureBlack && !isClassic && showBorders ->
                UHabitElevationAndBorders.AMOLED_BORDERED_CARD_SURFACE_ARGB.toInt()
            palette.isPureBlack -> palette.background
            else -> palette.surface
        }
    }

    private fun resolveCardBorderColor(): Int {
        return if (palette.isPureBlack) {
            UHabitElevationAndBorders.AMOLED_CARD_BORDER_ARGB.toInt()
        } else {
            palette.border
        }
    }

    private fun resolveHairlineDividerColor(): Int {
        return when {
            palette.isPureBlack -> UHabitElevationAndBorders.AMOLED_DIVIDER_ARGB.toInt()
            palette.isDark -> ColorUtils.setAlphaComponent(0xFFFFFFFF.toInt(), (255 * 0.09f).toInt())
            else -> ColorUtils.setAlphaComponent(0xFF000000.toInt(), (255 * 0.08f).toInt())
        }
    }

    private fun resolvePillSurfaceColor(): Int {
        return when {
            palette.isPureBlack -> 0xFF12151C.toInt()
            palette.isDark -> ColorUtils.blendARGB(palette.surface, palette.onSurface, 0.07f)
            else -> ColorUtils.blendARGB(palette.surface, palette.onSurface, 0.04f)
        }
    }

    private fun resolvePillBorderColor(): Int {
        return when {
            palette.isPureBlack -> UHabitElevationAndBorders.AMOLED_CARD_BORDER_ARGB.toInt()
            palette.isDark -> ColorUtils.setAlphaComponent(palette.onSurface, 28)
            else -> palette.border
        }
    }

    private fun createHairlineDivider(context: Context, topMarginDp: Float = 0f, bottomMarginDp: Float = 0f): View {
        return View(context).apply {
            setBackgroundColor(resolveHairlineDividerColor())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(1f).coerceAtLeast(1)
            ).apply {
                if (topMarginDp > 0f) topMargin = dp(topMarginDp)
                if (bottomMarginDp > 0f) bottomMargin = dp(bottomMarginDp)
            }
        }
    }

    private fun resolveToneColor(tone: StatisticsRedesignModels.ProgressTone): Int {
        return when (tone) {
            StatisticsRedesignModels.ProgressTone.SUCCESS -> 0xFF4CAF50.toInt()
            StatisticsRedesignModels.ProgressTone.ACCENT -> palette.accent
            StatisticsRedesignModels.ProgressTone.WARNING -> 0xFFFF9800.toInt()
            StatisticsRedesignModels.ProgressTone.DANGER -> 0xFFE53935.toInt()
            StatisticsRedesignModels.ProgressTone.NEUTRAL -> palette.onSurfaceVariant
        }
    }

    private fun createLegendDotItem(context: Context, color: Int, text: String): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                }
                layoutParams = LinearLayout.LayoutParams(dp(8f), dp(8f)).apply {
                    marginEnd = dp(6f)
                }
            })
            addView(TextView(context).apply {
                this.text = text
                textSize = 12f
                setTextColor(palette.onSurfaceVariant)
            })
        }
    }

    private fun createCompactBadgePill(context: Context, label: String, color: Int = palette.onSurfaceVariant): TextView {
        return TextView(context).apply {
            text = label
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color)
            setPadding(dp(10f), dp(4f), dp(10f), dp(4f))
            background = GradientDrawable().apply {
                setColor(resolvePillSurfaceColor())
                setStroke(dp(1f), resolvePillBorderColor())
                cornerRadius = dp(12f).toFloat()
            }
        }
    }

    private fun createCardView(context: Context, topMarginDp: Float = 0f): FrameLayout {
        val prefs = component.preferences
        val isClassic = prefs.isClassicSectionLayout
        val showBorders = prefs.showHabitCardBorders
        val isBorderedCard = !isClassic && showBorders
        val radius = if (isBorderedCard) dp(prefs.statisticsCardCornerRadius.toFloat().coerceAtLeast(0f)).toFloat() else 0f

        return FrameLayout(context).apply {
            background = GradientDrawable().apply {
                setColor(resolveCardSurfaceColor(isClassic, showBorders))
                cornerRadius = radius
                if (isBorderedCard) {
                    setStroke(dp(1f), resolveCardBorderColor())
                }
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = if (isClassic || !showBorders) 0 else if (topMarginDp > 0f) dp(topMarginDp) else dp(8f)
            }
            if (isClassic || !showBorders) {
                val divider = View(context).apply {
                    setBackgroundColor(resolveHairlineDividerColor())
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(1f).coerceAtLeast(1),
                        Gravity.BOTTOM
                    )
                }
                addView(divider)
            }
        }
    }

    override fun onCardCornersChanged() {
        if (!isAdded) return
        val isClassic = component.preferences.isClassicSectionLayout
        val showBorders = component.preferences.showHabitCardBorders
        val canvasColor = resolveCanvasBackgroundColor(isClassic)
        if (::rootContainer.isInitialized) {
            rootContainer.setBackgroundColor(canvasColor)
        }
        if (::headerShellContainer.isInitialized) {
            headerShellContainer.setBackgroundColor(canvasColor)
        }
        val padH = if (isClassic || !showBorders) 0 else dp(16f)
        val padT = if (isClassic) 0 else if (!showBorders) dp(4f) else dp(8f)
        contentContainer.setPadding(padH, padT, padH, dp(24f))
        renderCurrentState()
    }

    override fun onHabitListAppearanceChanged() {
        onCardCornersChanged()
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
            isScrollbarFadingEnabled = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(topMarginDp)
                bottomMargin = dp(bottomMarginDp)
            }
            addView(chart, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            post {
                if (chart.width > width) {
                    val todayIdx = columns.indexOfFirst { it.isToday }
                    if (todayIdx >= 0) {
                        chart.ensureColumnVisible(todayIdx)
                    } else {
                        fullScroll(View.FOCUS_RIGHT)
                    }
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
            if (aggregate.ongoingCount == 0) {
                lines.add(getString(R.string.unified_statistics_cadence_no_closed_cycles))
            }
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
            setTextColor(palette.onSurfaceVariant)
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
