/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.activities.statistics

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import androidx.core.graphics.ColorUtils
import org.isoron.uhabits.core.preferences.Preferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.preference.PreferenceManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import org.isoron.platform.time.DayOfWeek
import org.isoron.platform.time.JavaLocalDateFormatter
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.getToday
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.common.theme.MainTabsThemeBridge
import org.isoron.uhabits.compose.UHabitComposeColors
import org.isoron.uhabits.compose.UHabitComposeTheme
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
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import java.util.concurrent.CancellationException
import kotlin.math.roundToInt

private typealias StatisticsReportContent = @Composable () -> Unit

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

    private var headerState by mutableStateOf<UnifiedStatisticsHeaderState?>(null)
    private var reportContent by mutableStateOf<StatisticsReportContent?>(null)

    internal var currentTab: UnifiedAnalyticsTab = UnifiedAnalyticsTab.OVERVIEW
    private var currentPeriod: UnifiedPeriod = UnifiedPeriod.WEEK
    private var currentScope: UnifiedScope = UnifiedScope.All
    internal var selectedHabitCadence: HabitCadence = HabitCadence.DAILY
    private var selectedOverviewMetric = UnifiedStatisticsOverviewMetric.HABITS
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
        headerState = null
        reportContent = null
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

    @OptIn(ExperimentalComposeUiApi::class)
    private fun buildLayout(context: Context): View = ComposeView(context).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            val state = headerState ?: return@setContent
            BoxWithConstraints(Modifier.fillMaxSize()) {
                // A weighted MainActivity child can be measured once before its constraints are final.
                if (constraints.hasBoundedWidth && constraints.hasBoundedHeight) {
                    val colors = state.colors
                    val bordered = !colors.isClassicSectionLayout && colors.showCardBorders
                    val horizontal = if (bordered) 16.dp else 0.dp
                    val top = when {
                        colors.isClassicSectionLayout -> 0.dp
                        bordered -> 8.dp
                        else -> 4.dp
                    }
                    UHabitComposeTheme(colors) {
                        Column(
                            Modifier.fillMaxSize()
                                .background(ComposeColor(resolveCanvasBackgroundColor(colors.isClassicSectionLayout)))
                                .semantics { testTagsAsResourceId = true },
                        ) {
                            UnifiedStatisticsHeaderCompose(
                                state = state,
                                pillSurface = ComposeColor(resolvePillSurfaceColor()),
                                pillBorder = ComposeColor(resolvePillBorderColor()),
                                onTab = ::selectTab,
                                onPeriod = ::selectPeriod,
                                onScope = {
                                    UnifiedStatisticsFiltersBottomSheet.show(childFragmentManager, currentScope, component.session.datasetId)
                                },
                                onNavigate = ::navigatePeriod,
                                onRetry = ::refresh,
                            )
                            key(state.tab) {
                                Column(
                                    Modifier.weight(1f).fillMaxWidth().testTag("unified_content")
                                        .verticalScroll(rememberScrollState())
                                        .padding(start = horizontal, end = horizontal, top = top, bottom = 24.dp),
                                ) {
                                    reportContent?.invoke()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    fun selectTab(tab: UnifiedAnalyticsTab) {
        if (tab == currentTab) return
        currentTab = tab
        savePreferencesForTab()
        renderCurrentState()
    }

    private fun navigatePeriod(direction: Int) {
        currentAnchorDate = UnifiedStatisticsStateUtils.navigatePeriod(
            currentPeriod, currentAnchorDate, direction, component.preferences.firstWeekday,
        )
        clampAndRequestUpdate(if (direction < 0) "nav_prev" else "nav_next")
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
        if (view == null) return
        val periodLabel = getString(currentPeriod.labelRes())
        val scopeLabel = when (val scope = currentScope) {
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
        val isLatest = UnifiedStatisticsStateUtils.isLatestAllowedPeriod(
            currentPeriod, currentAnchorDate, lastKnownToday, component.preferences.firstWeekday
        )

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
        val reportUiState = currentUiState
        val status = when (reportUiState) {
            is UnifiedReportUiState.Failure -> reportUiState.message
            is UnifiedReportUiState.Success -> if (reportUiState.isRefreshing) getString(R.string.unified_statistics_state_refreshing) else null
            is UnifiedReportUiState.InitialLoading -> null
        }
        headerState = UnifiedStatisticsHeaderState(
            colors = UHabitComposeColors.fromPalette(palette),
            tab = currentTab,
            period = currentPeriod,
            periodLabel = StatisticsRedesignModels.formatPeriodHeaderLabel(periodLabel, rangeText),
            scopeLabel = scopeLabel,
            canGoNext = !isLatest,
            status = status,
            loading = reportUiState is UnifiedReportUiState.InitialLoading,
        )
    }

    private fun renderCurrentState() {
        if (view == null) return
        childFragmentManager.fragments.filterIsInstance<UnifiedStatisticsDrilldownBottomSheet>().forEach { it.refreshReport() }
        childFragmentManager.fragments.filterIsInstance<FocusSessionDetailBottomSheet>().forEach { it.invalidateReport(currentUiState) }
        childFragmentManager.fragments.filterIsInstance<UnifiedStatisticsCycleDetailBottomSheet>().forEach { it.invalidateReport(currentUiState) }
        updateHeaderLabels()

        reportContent = when (val state = currentUiState) {
            is UnifiedReportUiState.InitialLoading -> null
            is UnifiedReportUiState.Failure -> {
                val previous = state.previousReport?.let(::buildDomainCards)
                val failure = reportMessage(state.message.takeIf { previous == null }, showRetry = true)
                val content: StatisticsReportContent = {
                    previous?.invoke()
                    failure()
                }
                content
            }
            is UnifiedReportUiState.Success -> buildDomainCards(state.report)
        }
    }

    private fun buildDomainCards(report: UnifiedStatisticsReport): StatisticsReportContent = when (currentTab) {
        UnifiedAnalyticsTab.OVERVIEW -> buildOverviewTab(report)
        UnifiedAnalyticsTab.HABITS -> buildHabitsTab(report)
        UnifiedAnalyticsTab.TASKS -> buildTasksTab(report)
        UnifiedAnalyticsTab.FOCUS -> buildFocusTab(report)
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
    private fun buildOverviewTab(report: UnifiedStatisticsReport): StatisticsReportContent {
        val habits = report.habits
        val tasks = report.tasks
        val focus = report.focus
        val chartPalette = palette
        val russian = isRussian
        val columns = mapOf(
            UnifiedStatisticsOverviewMetric.HABITS to habits?.let {
                DiscreteChartBucketer.buildHabitColumns(
                    currentPeriod, HabitCadence.DAILY, it, lastKnownToday, chartPalette, russian,
                    includeUnknownSegment = false,
                )
            }.orEmpty(),
            UnifiedStatisticsOverviewMetric.TASKS to tasks?.let {
                DiscreteChartBucketer.buildTaskColumns(currentPeriod, it, lastKnownToday, russian)
            }.orEmpty(),
            UnifiedStatisticsOverviewMetric.FOCUS to focus?.let {
                DiscreteChartBucketer.buildFocusColumns(currentPeriod, it, lastKnownToday, russian)
            }.orEmpty(),
        )
        val availableMetrics = columns.filterValues { items -> items.any { it.value > 0L } }.keys.toList()
        if (availableMetrics.isNotEmpty() && selectedOverviewMetric !in availableMetrics) {
            selectedOverviewMetric = availableMetrics.first()
        }
        val state = UnifiedStatisticsOverviewState(
            pulse = StatisticsRedesignModels.buildOverviewPulse(report, russian),
            dailyOngoing = (habits?.daily?.ongoingCount ?: 0) > 0,
            hasTasks = tasks != null,
            hasFocus = focus != null,
            dailySatisfied = habits?.daily?.satisfiedCount ?: 0,
            dailyMissed = habits?.daily?.notSatisfiedCount ?: 0,
            metrics = availableMetrics,
            selectedMetric = selectedOverviewMetric,
            canOpenHabitBreakdown = habits != null,
            emptyTasksLabel = if (russian) "В периоде пусто" else "None this period",
        )
        val enabled = !isRefreshingState && currentLoadedReport === report
        val successColor = ComposeColor(resolveToneColor(StatisticsRedesignModels.ProgressTone.SUCCESS))
        val dangerColor = ComposeColor(resolveToneColor(StatisticsRedesignModels.ProgressTone.DANGER))
        return {
            UnifiedStatisticsOverviewCompose(
                state = state,
                successColor = successColor,
                dangerColor = dangerColor,
                onTab = { tab ->
                    if (tab == UnifiedAnalyticsTab.HABITS) selectedHabitCadence = HabitCadence.DAILY
                    selectTab(tab)
                },
                onMetric = { metric ->
                    if (metric != selectedOverviewMetric) {
                        selectedOverviewMetric = metric
                        renderCurrentState()
                    }
                },
                chart = {
                    val metric = state.selectedMetric
                    val (chartTag, unit) = when (metric) {
                        UnifiedStatisticsOverviewMetric.HABITS -> "overviewHabitsChart" to if (russian) "цели" else "goals"
                        UnifiedStatisticsOverviewMetric.TASKS -> "overviewTasksChart" to if (russian) "шт." else "pcs"
                        UnifiedStatisticsOverviewMetric.FOCUS -> "overviewFocusChart" to if (russian) "время" else "time"
                    }
                    UnifiedStatisticsColumnChart(
                        chartTag, columns.getValue(metric), chartPalette, enabled,
                        Modifier.padding(top = 4.dp, bottom = 6.dp),
                        unitLabel = unit,
                        valueFormatter = if (metric == UnifiedStatisticsOverviewMetric.FOCUS) {
                            { value -> UnifiedStatisticsStateUtils.formatCompactDurationMs(value, russian) }
                        } else null,
                    ) { item -> openOverviewDrilldown(report, item, russian) }
                },
            )
        }
    }

    private fun openOverviewDrilldown(report: UnifiedStatisticsReport, item: ChartColumnItem, russian: Boolean) {
        if (isRefreshingState || currentLoadedReport !== report) return
        when (val descriptor = item.descriptor) {
            is HabitsDrilldownDescriptor ->
                UnifiedStatisticsDrilldownBottomSheet.showForHabitCycles(childFragmentManager, item.label, "", descriptor)
            is TasksDrilldownDescriptor ->
                UnifiedStatisticsDrilldownBottomSheet.showForTasks(childFragmentManager, item.label, "", descriptor)
            is FocusDrilldownDescriptor ->
                UnifiedStatisticsDrilldownBottomSheet.showForFocus(
                    childFragmentManager, item.label,
                    UnifiedStatisticsStateUtils.formatExactDurationMs(item.value, russian), descriptor,
                )
            else -> Unit
        }
    }

    // 2. Habits Tab
    private fun buildHabitsTab(report: UnifiedStatisticsReport): StatisticsReportContent {
        val habits = report.habits

        if (habits == null || habits.dataState == StatisticsDataState.UNAVAILABLE) {
            return reportMessage(getString(R.string.unified_statistics_domain_failed), showRetry = true)
        }

        if (habits.dataState == StatisticsDataState.EMPTY && habits.allCycles.isEmpty() && habits.habitSummaries.isEmpty()) {
            return reportMessage(getString(R.string.unified_statistics_habits_empty))
        }

        val activeCadences = listOf(HabitCadence.DAILY, HabitCadence.WEEKLY, HabitCadence.MONTHLY)
            .filter { hasCadenceTabData(habits, it) }.ifEmpty { listOf(HabitCadence.DAILY) }
        if (selectedHabitCadence !in activeCadences) selectedHabitCadence = activeCadences.first()
        val cadence = selectedHabitCadence
        val aggregate = when (cadence) {
            HabitCadence.DAILY -> habits.daily
            HabitCadence.WEEKLY -> habits.weekly
            HabitCadence.MONTHLY -> habits.monthly
            HabitCadence.UNSUPPORTED -> habits.unsupported
        }
        val (label, tag) = when (cadence) {
            HabitCadence.DAILY -> R.string.unified_statistics_cadence_daily to "habitCadenceDailyRow"
            HabitCadence.WEEKLY -> R.string.unified_statistics_cadence_weekly to "habitCadenceWeeklyRow"
            HabitCadence.MONTHLY -> R.string.unified_statistics_cadence_monthly to "habitCadenceMonthlyRow"
            HabitCadence.UNSUPPORTED -> R.string.unified_statistics_cadence_unsupported to "habitCadenceOtherRow"
        }
        val russian = isRussian
        val headline = if (aggregate.knownCount > 0) {
            "${(aggregate.satisfiedCount.toDouble() / aggregate.knownCount * 100).roundToInt()}%"
        } else getString(if (aggregate.ongoingCount > 0) R.string.unified_statistics_habits_ongoing_in_progress
            else R.string.unified_statistics_cadence_no_closed_cycles)
        val fraction = if (aggregate.knownCount == 0) null
            else if (russian) "${aggregate.satisfiedCount} из ${aggregate.knownCount}"
            else "${aggregate.satisfiedCount}/${aggregate.knownCount}"
        val chartPalette = palette
        val columns = DiscreteChartBucketer.buildHabitColumns(
            currentPeriod, cadence, habits, lastKnownToday, chartPalette, russian, includeUnknownSegment = false,
        )
        val trend = StatisticsRedesignModels.buildHabitsTrendSummary(cadence, habits, lastKnownToday, russian)
        val progressCycles = StatisticsRedesignModels.selectCyclesForProgressSection(habits, cadence, limit = Int.MAX_VALUE)
        val preview = progressCycles.take(4).map {
            val row = StatisticsRedesignModels.buildHabitProgressRow(it, report.context, ::formatStatisticsDate, russian)
            row to ComposeColor(resolveToneColor(row.tone))
        }
        val progressTitle = if (report.context.to < report.context.habitToday && habits.ongoingCycles.isEmpty()) {
            R.string.unified_statistics_habits_historical_cycles_title
        } else R.string.unified_statistics_habits_progress_title
        val totalHabitCount = habits.habitSummaries.distinctBy { it.habitUuid }.size
        val enabled = !isRefreshingState && currentLoadedReport === report
        val pillSurface = ComposeColor(resolvePillSurfaceColor())
        val pillBorder = ComposeColor(resolvePillBorderColor())

        fun canOpen() = !isRefreshingState && currentLoadedReport === report

        return {
            UnifiedStatisticsCards {
                UnifiedStatisticsCard(Modifier.testTag("habitCadenceCard")) {
                    UnifiedStatisticsCadences(activeCadences, cadence) { selected ->
                        if (selectedHabitCadence != selected) {
                            selectedHabitCadence = selected
                            renderCurrentState()
                        }
                    }
                    UnifiedStatisticsCadenceHeadline(tag, headline, fraction, enabled) {
                        if (canOpen()) UnifiedStatisticsDrilldownBottomSheet.showForHabitCycles(
                            childFragmentManager, getString(label), "", HabitsDrilldownDescriptor.CadenceCycles(cadence),
                        )
                    }
                    if (columns.any { it.value > 0L }) {
                        UnifiedStatisticsColumnChart("habitCadenceChart", columns, chartPalette, enabled,
                            Modifier.padding(top = 8.dp, bottom = 6.dp), unitLabel = if (russian) "цели" else "goals") { item ->
                            val descriptor = item.descriptor as? HabitsDrilldownDescriptor
                            if (canOpen() && descriptor != null) UnifiedStatisticsDrilldownBottomSheet.showForHabitCycles(
                                childFragmentManager, item.label, "", descriptor,
                            )
                        }
                        UnifiedStatisticsHabitLegend(aggregate.satisfiedCount, aggregate.notSatisfiedCount)
                    } else {
                        UnifiedStatisticsSecondary(stringResource(R.string.unified_statistics_habits_chart_empty),
                            Modifier.testTag("habitCadenceChart").padding(top = 12.dp, bottom = 8.dp))
                    }
                    if (trend != null) UnifiedStatisticsSecondary(trend, Modifier.testTag("habitCadenceTrendSummary").padding(top = 6.dp))
                }
                if (preview.isNotEmpty()) {
                    UnifiedStatisticsCard(Modifier.testTag("habitOngoingSummary")) {
                        UnifiedStatisticsTitle(stringResource(progressTitle), Modifier.padding(bottom = 6.dp))
                        preview.forEachIndexed { index, (row, tone) ->
                            if (index > 0) UnifiedStatisticsReportDivider(Modifier.padding(vertical = 4.dp))
                            UnifiedStatisticsHabitProgressRow(row, chartPalette, tone, enabled) {
                                if (canOpen()) UnifiedStatisticsCycleDetailBottomSheet.show(
                                    childFragmentManager, row.cycleKey, report.context.datasetId, statisticsQuerySignature(report.context),
                                )
                            }
                        }
                        if (progressCycles.size > preview.size && habits.ongoingCycles.isNotEmpty()) {
                            UnifiedStatisticsReportAction(
                                stringResource(R.string.unified_statistics_habits_ongoing_see_all, progressCycles.size),
                                enabled = enabled,
                                onClick = {
                                    if (canOpen()) UnifiedStatisticsDrilldownBottomSheet.showForHabitCycles(
                                        childFragmentManager, getString(progressTitle), "", HabitsDrilldownDescriptor.OngoingCycles,
                                    )
                                },
                            )
                        }
                    }
                }
                UnifiedStatisticsHabitActions(totalHabitCount, enabled, pillSurface, pillBorder,
                    onAll = {
                        if (canOpen()) UnifiedStatisticsDrilldownBottomSheet.showForHabitList(
                            childFragmentManager, getString(R.string.unified_statistics_habits_preview_title), "",
                        )
                    },
                    onCompare = { startActivity(component.intentFactory.startCompareHabitsActivity(requireContext())) },
                )
            }
        }
    }

    // 3. Tasks Tab
    private fun buildTasksTab(report: UnifiedStatisticsReport): StatisticsReportContent {
        val tasks = report.tasks

        if (tasks == null || tasks.dataState == StatisticsDataState.UNAVAILABLE) {
            return reportMessage(getString(R.string.unified_statistics_domain_failed), showRetry = true)
        }

        if (tasks.dataState == StatisticsDataState.EMPTY && tasks.completedCount == 0 && tasks.createdCount == 0 &&
            tasks.dueSummary.totalCount == 0 && tasks.noDueSummary.totalCount == 0
        ) {
            val emptyModel = StatisticsRedesignModels.buildTasksEmptyState(currentScope, currentPeriod, isRussian)
            return {
                UnifiedStatisticsTasksEmptyCompose(emptyModel) {
                    currentScope = UnifiedScope.All
                    savePreferencesForCurrentScope()
                    invalidateDisplayedReport()
                    renderCurrentState()
                    requestReportUpdate("scope_reset_empty_tasks")
                }
            }
        }

        val enabled = !isRefreshingState && currentLoadedReport === report
        val chartPalette = palette
        val overdueCount = tasks.dueSummary.pendingOverdueNowCount
        val taskColumns = DiscreteChartBucketer.buildTaskColumns(currentPeriod, tasks, lastKnownToday, isRussian)
        val title = getString(R.string.unified_statistics_overview_tasks_title)
        val dueSum = tasks.dueSummary
        val noDueSum = tasks.noDueSummary
        val dueSegments = listOf(
            StatisticsCohortSegment(
                key = "COMPLETED",
                count = dueSum.completedCount,
                label = getString(R.string.unified_statistics_due_state_completed),
                color = palette.accent,
                textColor = if (palette.isDark) palette.background else palette.surface,
                descriptor = TasksDrilldownDescriptor.DueCohort(TasksDrilldownDescriptor.DueSegmentFilter.COMPLETED),
            ),
            StatisticsCohortSegment(
                key = "OVERDUE_NOW",
                count = dueSum.pendingOverdueNowCount,
                label = getString(R.string.unified_statistics_due_state_overdue),
                color = 0xFFE53935.toInt(),
                textColor = 0xFFFFFFFF.toInt(),
                descriptor = TasksDrilldownDescriptor.DueCohort(TasksDrilldownDescriptor.DueSegmentFilter.OVERDUE_NOW),
            ),
            StatisticsCohortSegment(
                key = "PENDING_NOT_OVERDUE",
                count = dueSum.pendingNotOverdueNowCount,
                label = getString(R.string.unified_statistics_due_state_pending),
                color = palette.onSurfaceVariant,
                textColor = if (palette.isDark) palette.background else palette.surface,
                descriptor = TasksDrilldownDescriptor.DueCohort(TasksDrilldownDescriptor.DueSegmentFilter.PENDING_NOT_OVERDUE),
            ),
        )
        val noDueSegments = listOf(
            StatisticsCohortSegment(
                key = "COMPLETED",
                count = noDueSum.completedCount,
                label = getString(R.string.unified_statistics_nodue_state_completed),
                color = palette.accent,
                textColor = if (palette.isDark) palette.background else palette.surface,
                descriptor = TasksDrilldownDescriptor.NoDueCohort(TasksDrilldownDescriptor.NoDueSegmentFilter.COMPLETED),
            ),
            StatisticsCohortSegment(
                key = "PENDING",
                count = noDueSum.pendingCount,
                label = getString(R.string.unified_statistics_nodue_state_pending),
                color = palette.onSurfaceVariant,
                textColor = if (palette.isDark) palette.background else palette.surface,
                descriptor = TasksDrilldownDescriptor.NoDueCohort(TasksDrilldownDescriptor.NoDueSegmentFilter.PENDING),
            ),
        )

        fun openTasks(title: String, subtitle: String, descriptor: TasksDrilldownDescriptor) {
            if (isRefreshingState || currentLoadedReport !== report) return
            UnifiedStatisticsDrilldownBottomSheet.showForTasks(childFragmentManager, title, subtitle, descriptor)
        }

        return {
            UnifiedStatisticsCards {
                UnifiedStatisticsCard(
                    Modifier.testTag("unified_tasks_t1_card"),
                    enabled = enabled,
                    onClick = {
                        val (from, to) = UnifiedStatisticsStateUtils.calculatePeriodRange(
                            currentPeriod, currentAnchorDate, lastKnownToday, component.preferences.firstWeekday,
                            report.context.from,
                        )
                        openTasks(title, getString(R.string.unified_statistics_tasks_completed_count, tasks.completedCount),
                            TasksDrilldownDescriptor.CompletedRange(from, to, title))
                    },
                ) {
                    UnifiedStatisticsTitle(title, Modifier.padding(bottom = 8.dp))
                    val metrics: @Composable (Modifier) -> Unit = { metricModifier ->
                        UnifiedStatisticsTaskMetric(tasks.completedCount, stringResource(R.string.unified_statistics_due_state_completed),
                            metricModifier, tag = "unified_tasks_t1")
                        UnifiedStatisticsTaskMetric(tasks.createdCount, if (isRussian) "Создано" else "Created",
                            metricModifier, enabled = enabled, tag = "unified_tasks_created", onClick = {
                                val createdTitle = getString(R.string.unified_statistics_tasks_created_count, tasks.createdCount)
                                openTasks(createdTitle, "${report.context.from} – ${report.context.to}",
                                    TasksDrilldownDescriptor.CreatedTasks(report.context.from, report.context.to, createdTitle))
                            })
                        UnifiedStatisticsTaskMetric(overdueCount, stringResource(R.string.unified_statistics_due_state_overdue),
                            metricModifier, danger = overdueCount > 0)
                    }
                    if (statisticsStacked) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { metrics(Modifier.fillMaxWidth()) }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            metrics(Modifier.weight(1f))
                        }
                    }
                    if (taskColumns.any { it.value > 0L }) {
                        UnifiedStatisticsColumnChart("unified_tasks_t1_chart", taskColumns, chartPalette, enabled,
                            Modifier.padding(top = 10.dp, bottom = 2.dp), unitLabel = if (isRussian) "шт." else "tasks") { item ->
                            val descriptor = item.descriptor as? TasksDrilldownDescriptor
                            if (descriptor != null) openTasks(item.label, item.formattedValue, descriptor)
                        }
                    }
                }
                val dueTitle = stringResource(R.string.unified_statistics_tasks_due_cohort_title)
                UnifiedStatisticsCohortCard(
                    dueTitle, dueSum.totalCount,
                    stringResource(R.string.unified_statistics_tasks_due_breakdown_format,
                        dueSum.completedCount, dueSum.pendingOverdueNowCount, dueSum.pendingNotOverdueNowCount),
                    "unified_tasks_t3_cohort", dueSegments, chartPalette, enabled,
                    onOpen = {
                        openTasks(dueTitle, getString(R.string.unified_statistics_tasks_due_total_format, dueSum.totalCount),
                            TasksDrilldownDescriptor.DueCohort(TasksDrilldownDescriptor.DueSegmentFilter.ALL_DUE))
                    },
                    onSegment = { segment ->
                        openTasks(dueTitle, "${segment.label}: ${segment.count}", segment.descriptor)
                    },
                )
                val noDueTitle = stringResource(R.string.unified_statistics_tasks_nodue_cohort_title)
                UnifiedStatisticsCohortCard(
                    noDueTitle, noDueSum.totalCount,
                    stringResource(R.string.unified_statistics_tasks_nodue_breakdown_format, noDueSum.completedCount, noDueSum.pendingCount),
                    "unified_tasks_t4_cohort", noDueSegments, chartPalette, enabled,
                    onOpen = {
                        openTasks(noDueTitle, getString(R.string.unified_statistics_tasks_nodue_total_format, noDueSum.totalCount),
                            TasksDrilldownDescriptor.NoDueCohort(TasksDrilldownDescriptor.NoDueSegmentFilter.ALL_NO_DUE))
                    },
                    onSegment = { segment ->
                        openTasks(noDueTitle, "${segment.label}: ${segment.count}", segment.descriptor)
                    },
                )
            }
        }
    }

    // 4. Focus Tab
    private fun buildFocusTab(report: UnifiedStatisticsReport): StatisticsReportContent {
        val focus = report.focus

        if (focus == null || focus.dataState == StatisticsDataState.UNAVAILABLE) {
            return reportMessage(getString(R.string.unified_statistics_domain_failed), showRetry = true)
        }

        if (focus.dataState == StatisticsDataState.EMPTY && focus.sessionCount == 0) {
            return reportMessage(getString(R.string.unified_statistics_focus_empty))
        }

        val enabled = !isRefreshingState && currentLoadedReport === report
        val chartPalette = palette
        val russian = isRussian
        val pulse = StatisticsRedesignModels.buildOverviewPulse(report, russian)
        val chart = DiscreteChartBucketer.buildFocusColumns(currentPeriod, focus, lastKnownToday, russian)

        fun openFocus(title: String, subtitle: String, descriptor: FocusDrilldownDescriptor) {
            if (isRefreshingState || currentLoadedReport !== report) return
            UnifiedStatisticsDrilldownBottomSheet.showForFocus(childFragmentManager, title, subtitle, descriptor)
        }

        return {
            UnifiedStatisticsCards {
                val totalLabel = stringResource(R.string.unified_statistics_focus_total_duration_title)
                val sessions = stringResource(R.string.unified_statistics_sessions_pill, focus.sessionCount)
                UnifiedStatisticsCard(
                    Modifier.testTag("unified_focus_f1_card"),
                    enabled = enabled,
                    onClick = {
                        val (from, to) = UnifiedStatisticsStateUtils.calculatePeriodRange(
                            currentPeriod, currentAnchorDate, lastKnownToday, component.preferences.firstWeekday,
                            report.context.from,
                        )
                        openFocus(totalLabel, sessions, FocusDrilldownDescriptor.SessionsRange(from, to, totalLabel))
                    },
                ) {
                    UnifiedStatisticsSecondary(totalLabel, Modifier.padding(bottom = 2.dp))
                    if (pulse.focusHasOverflow) {
                        UnifiedStatisticsSecondary(stringResource(R.string.unified_statistics_overflow))
                    } else {
                        UnifiedStatisticsFocusHeadline(
                            pulse.focusDurationFormatted ?: UnifiedStatisticsStateUtils.formatDurationMs(focus.totalDurationMs, russian),
                            sessions,
                        )
                        pulse.focusAvgPerDayFormatted?.let { UnifiedStatisticsSecondary(it) }
                    }
                    if (chart.any { it.value > 0L }) {
                        UnifiedStatisticsColumnChart(
                            "unified_focus_f1_chart", chart, chartPalette, enabled,
                            Modifier.padding(top = 10.dp, bottom = 2.dp),
                            unitLabel = if (russian) "время" else "time",
                            valueFormatter = { UnifiedStatisticsStateUtils.formatCompactDurationMs(it, russian) },
                        ) { item ->
                            val descriptor = item.descriptor as? FocusDrilldownDescriptor
                            if (descriptor != null) openFocus(item.label,
                                UnifiedStatisticsStateUtils.formatExactDurationMs(item.value, russian), descriptor)
                        }
                    }
                }
                if (StatisticsRedesignModels.shouldShowFocusBreakdownCard(focus.sectionBreakdowns.size)) {
                    val sectionsTitle = stringResource(R.string.unified_statistics_focus_by_section_title)
                    UnifiedStatisticsCard(Modifier.testTag("unified_focus_f3_breakdown")) {
                        UnifiedStatisticsTitle(sectionsTitle, Modifier.padding(bottom = 6.dp))
                        focus.sectionBreakdowns.take(5).forEachIndexed { index, item ->
                            if (index > 0) UnifiedStatisticsReportDivider(Modifier.padding(vertical = 2.dp))
                            val duration = UnifiedStatisticsStateUtils.formatDurationMs(item.durationMs, russian)
                            val sectionRatio = if (focus.totalDurationMs > 0L) {
                                (item.durationMs.toDouble() / focus.totalDurationMs).toFloat().coerceIn(0f, 1f)
                            } else 0f
                            UnifiedStatisticsFocusBreakdownRow("section:${item.category}:${item.containerId?.value}", item.name, duration,
                                enabled, emphasizeTitle = true,
                                onClick = {
                                    openFocus(getString(R.string.unified_statistics_drilldown_section_title, item.name), duration,
                                        FocusDrilldownDescriptor.SectionBranch(item.category, item.containerId))
                                }) {
                                UnifiedStatisticsProgress(sectionRatio, ComposeColor(chartPalette.accent), chartPalette, Modifier.padding(top = 6.dp))
                            }
                        }
                        val count = focus.sectionBreakdowns.size
                        if (count > 5) {
                            UnifiedStatisticsReportAction(stringResource(R.string.unified_statistics_focus_show_all_sections, count), enabled, {
                                openFocus(sectionsTitle, "$count ${sectionsTitle.lowercase()}", FocusDrilldownDescriptor.AllSections)
                            })
                        }
                    }
                }
                if (StatisticsRedesignModels.shouldShowFocusBreakdownCard(focus.targetTypeSummaries.size)) {
                    UnifiedStatisticsCard(Modifier.testTag("unified_focus_f4_breakdown")) {
                        UnifiedStatisticsTitle(stringResource(R.string.unified_statistics_focus_by_source_title), Modifier.padding(bottom = 6.dp))
                        focus.targetTypeSummaries.forEachIndexed { index, item ->
                            if (index > 0) UnifiedStatisticsReportDivider()
                            val duration = UnifiedStatisticsStateUtils.formatDurationMs(item.durationMs, russian)
                            val source = StatisticsRedesignModels.formatFocusTargetSourceLabel(item.targetType, russian)
                            val count = stringResource(R.string.unified_statistics_sessions_pill, item.sessionCount)
                            UnifiedStatisticsFocusBreakdownRow("target-type:${item.targetType?.name ?: "UNKNOWN"}", source, "$duration · $count",
                                enabled,
                                onClick = {
                                    openFocus(getString(R.string.unified_statistics_drilldown_target_title, source), duration,
                                        FocusDrilldownDescriptor.TargetType(item.targetType))
                                })
                        }
                    }
                }
            }
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

    private fun resolveToneColor(tone: StatisticsRedesignModels.ProgressTone): Int {
        return when (tone) {
            StatisticsRedesignModels.ProgressTone.SUCCESS -> 0xFF4CAF50.toInt()
            StatisticsRedesignModels.ProgressTone.ACCENT -> palette.accent
            StatisticsRedesignModels.ProgressTone.WARNING -> 0xFFFF9800.toInt()
            StatisticsRedesignModels.ProgressTone.DANGER -> 0xFFE53935.toInt()
            StatisticsRedesignModels.ProgressTone.NEUTRAL -> palette.onSurfaceVariant
        }
    }

    override fun onCardCornersChanged() {
        if (isAdded && view != null) renderCurrentState()
    }

    override fun onHabitListAppearanceChanged() {
        onCardCornersChanged()
    }

    private fun reportMessage(message: String?, showRetry: Boolean = false): StatisticsReportContent = {
        UnifiedStatisticsReportMessageCompose(message, onRetry = if (showRetry) ::refresh else null)
    }

    private fun selectPeriod(period: UnifiedPeriod) {
        if (period == currentPeriod) return
        currentPeriod = period
        savePreferencesForPeriod()
        invalidateDisplayedReport()
        clampAndRequestUpdate("period_changed")
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
