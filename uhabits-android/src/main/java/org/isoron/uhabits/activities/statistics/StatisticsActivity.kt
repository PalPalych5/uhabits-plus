/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.isoron.uhabits.activities.statistics

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import org.isoron.platform.gui.toInt
import org.isoron.platform.time.DayOfWeek
import org.isoron.platform.time.JavaLocalDateFormatter
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.getToday
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.AndroidThemeSwitcher
import org.isoron.uhabits.activities.common.theme.MainTabsThemeBridge
import org.isoron.uhabits.activities.containers.statistics.ContainerStatisticsActivity
import org.isoron.uhabits.activities.main.MainActivity
import org.isoron.uhabits.activities.main.MainDestination
import org.isoron.uhabits.activities.main.MainNavigationHost
import org.isoron.uhabits.compose.UHabitComposeColors
import org.isoron.uhabits.compose.UHabitComposeTheme
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.models.DayTier
import org.isoron.uhabits.core.models.DayTierScope
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.ModelObservable
import org.isoron.uhabits.core.models.PaletteColor
import org.isoron.uhabits.core.ui.screens.statistics.StatisticsFilterState
import org.isoron.uhabits.core.ui.screens.statistics.StatisticsGoalTypeFilter
import org.isoron.uhabits.core.ui.screens.statistics.StatisticsHabitResult
import org.isoron.uhabits.core.ui.screens.statistics.StatisticsHabitStatusFilter
import org.isoron.uhabits.core.ui.screens.statistics.StatisticsPeriod
import org.isoron.uhabits.core.ui.screens.statistics.StatisticsReportState
import org.isoron.uhabits.core.ui.screens.statistics.StatisticsReportStateBuilder
import org.isoron.uhabits.core.ui.screens.statistics.StatisticsResultStatus
import org.isoron.uhabits.core.ui.screens.statistics.StatisticsSphereProgress
import org.isoron.uhabits.intents.IntentFactory
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class StatisticsFragment : Fragment(), ModelObservable.Listener {
    private data class FocusTotal(
        val containerId: ContainerId?,
        val name: String,
        val durationMs: Long
    )

    private var focusTotals: List<FocusTotal> = emptyList()
    private lateinit var themeSwitcher: AndroidThemeSwitcher
    private var isViewCreated = false
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
    private var isObservingData = false
    private var pageSequence = 0L

    private var composeColorsState by mutableStateOf<UHabitComposeColors?>(null)
    private var headerState by mutableStateOf(LegacyStatisticsHeaderState())
    private var pageSnapshotState by mutableStateOf(LegacyStatisticsPageSnapshot())

    internal val isObservingDataState: Boolean get() = isObservingData
    internal val activeGeneration: Int get() = activeReportGeneration
    internal val lastRenderedKey: ReportKey? get() = lastRenderedReportKey

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
                refreshHeaderState()
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
        val dbVersion: Int,
        val effectiveRevision: Long = 0L,
        val calendar: org.isoron.uhabits.core.models.projection.EffectiveReadCalendarContext? = null
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
        refreshThemeState()

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
        isViewCreated = true
        pageSequence = 0L
        pageSnapshotState = LegacyStatisticsPageSnapshot(
            pageId = 0L,
            body = LegacyStatisticsBodyState.Loading,
            transitionDirection = 0
        )
        refreshHeaderState()

        val applyTopStatusBarInset = !component.session.isNextArchitecture
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val colors = composeColorsState ?: UHabitComposeColors.fromPalette(
                    palette = palette,
                    cardCornerRadiusDp = palette.statisticsCardCornerRadius
                )
                UHabitComposeTheme(colors = colors) {
                    LegacyStatisticsScreen(
                        header = headerState,
                        pageSnapshot = pageSnapshotState,
                        applyTopStatusBarInset = applyTopStatusBarInset,
                        shortWeekdayName = { dow -> dateFormatter.shortWeekdayName(dow) },
                        shortMonthName = { date -> dateFormatter.shortMonthName(date) },
                        onSelectTab = ::handleTabSelected,
                        onTabSwipe = ::handleTabSwipe,
                        onDateNavigate = ::navigateDate,
                        onSelectTierScope = ::handleTierScopeSelected,
                        onOpenCompare = ::openCompareHabits,
                        onOpenFilters = ::openFiltersSheet,
                        onResetFilters = ::resetFilters,
                        onOpenHabit = ::openHabitDetails,
                        onOpenContainerStatistics = ::openContainerStatistics,
                        colors = colors
                    )
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        updateObserverRegistration()
    }

    private fun refreshThemeState() {
        val ctx = context ?: return
        val act = activity ?: return
        themeSwitcher = AndroidThemeSwitcher(act, component.preferences)
        themeSwitcher.apply()
        val currentPalette = MainTabsThemeBridge.resolve(ctx)
        composeColorsState = UHabitComposeColors.fromPalette(
            palette = currentPalette,
            cardCornerRadiusDp = currentPalette.statisticsCardCornerRadius
        )
        act.window.statusBarColor = currentPalette.background
    }

    private fun updateObserverRegistration() {
        val shouldObserve = isResumed && !isHidden && isViewCreated
        if (shouldObserve == isObservingData) return
        isObservingData = shouldObserve
        if (shouldObserve) {
            component.habitList.observable.addListener(this)
            component.effectiveReadRevision.addListener(effectiveRevisionListener)
        } else {
            component.effectiveReadRevision.removeListener(effectiveRevisionListener)
            component.habitList.observable.removeListener(this)
            activeReportGeneration++
            cancelPendingReportUpdate()
            cancelRefreshingIndicator()
        }
    }

    private val effectiveRevisionListener: () -> Unit = {
        activity?.runOnUiThread {
            if (isResumed && !isHidden && isViewCreated) {
                requestReportUpdate("effective_revision_change", delayMs = 0L, forceRender = true)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateObserverRegistration()
        if (isViewCreated) {
            refreshThemeState()
            currentFilters = currentFilters.copy(
                dayTiersEnabled = component.preferences.isDayTiersEnabled,
                spheresEnabled = component.preferences.isHabitSpheresEnabled
            )
            refreshHeaderState()
            requestReportUpdate("resume", delayMs = 0L, forceRender = true)
        }
        (activity as? MainNavigationHost)?.setHabitCreationAvailable(false)
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        updateObserverRegistration()
        if (!hidden && isViewCreated) {
            refreshThemeState()
            currentFilters = currentFilters.copy(
                dayTiersEnabled = component.preferences.isDayTiersEnabled,
                spheresEnabled = component.preferences.isHabitSpheresEnabled
            )
            refreshHeaderState()
            requestReportUpdate("hidden_change", delayMs = 0L, forceRender = true)
        }
    }

    override fun onPause() {
        updateObserverRegistration()
        super.onPause()
    }

    override fun onModelChange() {
        dbVersion++
        activity?.runOnUiThread {
            if (isResumed && !isHidden && isViewCreated) {
                requestReportUpdate("db_change", delayMs = 0L, forceRender = true)
            }
        }
    }

    fun refresh() {
        if (isViewCreated && isAdded && isResumed && !isHidden) {
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
        if (isObservingData) {
            isObservingData = false
            component.effectiveReadRevision.removeListener(effectiveRevisionListener)
            component.habitList.observable.removeListener(this)
        }
        activeReportGeneration++
        cancelPendingReportUpdate()
        cancelRefreshingIndicator()
        hasRenderedStatisticsOnce = false
        lastRenderedReportKey = null
        isViewCreated = false
        super.onDestroyView()
    }

    private fun ReportTab.toStatisticsPeriod(): StatisticsPeriod = when (this) {
        ReportTab.DAY -> StatisticsPeriod.DAY
        ReportTab.WEEK -> StatisticsPeriod.WEEK
        ReportTab.MONTH -> StatisticsPeriod.MONTH
        ReportTab.YEAR -> StatisticsPeriod.YEAR
        ReportTab.ALL -> StatisticsPeriod.ALL
    }

    private fun handleTabSelected(newTab: ReportTab) {
        if (newTab == currentTab) return
        pendingTransitionDirection = if (newTab.ordinal > currentTab.ordinal) 1 else -1
        currentTab = newTab
        clampAnchorDateToCurrentPeriod()
        refreshHeaderState()
        requestReportUpdate("tab_selected")
    }

    private fun handleTabSwipe(direction: Int) {
        val nextIndex = (currentTab.ordinal + direction).coerceIn(0, ReportTab.entries.lastIndex)
        handleTabSelected(ReportTab.entries[nextIndex])
    }

    private fun handleTierScopeSelected(selectedScope: DayTierScope) {
        if (selectedScope == currentFilters.tierScope) return
        currentFilters = currentFilters.copy(tierScope = selectedScope)
        refreshHeaderState()
        requestReportUpdate("scope_change", forceRender = true)
    }

    private fun openCompareHabits() {
        val intent = IntentFactory().startCompareHabitsActivity(requireContext()).apply {
            putExtra("initial_period", resolveCompareInitialPeriod(currentTab))
        }
        startActivity(intent)
    }

    private fun openFiltersSheet() {
        StatisticsFiltersBottomSheet.show(
            fragmentManager = childFragmentManager,
            initialState = currentFilters,
            blocks = component.habitList.getBlocks(),
            spheresEnabled = component.preferences.isHabitSpheresEnabled
        )
    }

    private fun resetFilters() {
        currentFilters = StatisticsFilterState(
            dayTiersEnabled = component.preferences.isDayTiersEnabled,
            spheresEnabled = component.preferences.isHabitSpheresEnabled
        )
        refreshHeaderState()
        requestReportUpdate("filters_reset", forceRender = true)
    }

    private fun openHabitDetails(habit: Habit) {
        startActivity(IntentFactory().startShowHabitActivity(requireContext(), habit))
    }

    private fun openContainerStatistics(containerId: ContainerId) {
        ContainerStatisticsActivity.start(requireContext(), containerId)
    }

    private fun navigateDate(direction: Int) {
        val firstWeekday = component.preferences.firstWeekday
        val nextAnchor = computeNextLegacyAnchorDate(currentAnchorDate, currentTab, direction, firstWeekday)
            ?: return
        pendingTransitionDirection = direction
        currentAnchorDate = nextAnchor
        refreshHeaderState()
        requestReportUpdate("date_navigation")
    }

    private fun getActiveRange(): Pair<LocalDate, LocalDate> {
        val firstWeekday = component.preferences.firstWeekday
        return statisticsActiveRange(currentAnchorDate, currentTab, firstWeekday)
    }

    private fun refreshHeaderState(rangeOverride: Pair<LocalDate, LocalDate>? = null) {
        if (!isViewCreated || !isAdded) return
        val (start, end) = rangeOverride ?: getActiveRange()
        val firstWeekday = component.preferences.firstWeekday
        val rangeText = formatRangeHeaderText(start, end)
        val showDateArrows = currentTab != ReportTab.ALL
        val nextEnabled = showDateArrows && !isLatestAllowedPeriod(currentAnchorDate, currentTab, firstWeekday)
        val blockNamesById = component.habitList.getBlocks()
            .mapNotNull { block -> block.id?.let { it to block.name } }
            .toMap()
        val (activeCount, scopeText) = buildLegacyActiveFilterSummary(
            filters = currentFilters,
            blockNamesById = blockNamesById,
            archivedLabel = getString(R.string.reports_filter_archived_only),
            allHabitsLabel = getString(R.string.reports_filter_all_habits),
            booleanOnlyLabel = getString(R.string.reports_filter_boolean_only),
            numericalOnlyLabel = getString(R.string.reports_filter_numerical_only)
        )
        headerState = headerState.copy(
            selectedTab = currentTab,
            dateRangeText = rangeText,
            showDateArrows = showDateArrows,
            nextEnabled = nextEnabled,
            dayTiersEnabled = component.preferences.isDayTiersEnabled,
            selectedTierScope = currentFilters.tierScope,
            activeFiltersCount = activeCount,
            activeFilterScopeText = scopeText
        )
    }

    private fun formatRangeHeaderText(start: LocalDate, end: LocalDate): String = when (currentTab) {
        ReportTab.ALL -> getString(R.string.statistics_since_date, dateFormatter.longFormat(start))
        ReportTab.DAY -> dateFormatter.longFormat(start)
        ReportTab.WEEK -> "${dateFormatter.longFormat(start)} - ${dateFormatter.longFormat(start.plus(6))}"
        ReportTab.MONTH -> "${dateFormatter.longMonthName(start)} ${start.year}"
        ReportTab.YEAR -> start.year.toString()
    }

    private fun showInitialLoading() {
        cancelRefreshingIndicator()
        pageSnapshotState = LegacyStatisticsPageSnapshot(
            pageId = pageSequence,
            body = LegacyStatisticsBodyState.Loading,
            transitionDirection = 0
        )
    }

    private fun cancelRefreshingIndicator() {
        loadingIndicatorRunnable?.let(reportRequestHandler::removeCallbacks)
        loadingIndicatorRunnable = null
        if (isViewCreated && headerState.isRefreshing) {
            headerState = headerState.copy(isRefreshing = false)
        }
    }

    private fun requestReportUpdate(
        _reason: String,
        delayMs: Long = REPORT_UPDATE_COALESCE_MS,
        forceRender: Boolean = false
    ) {
        if (!isViewCreated || !isAdded || !isResumed || isHidden) return
        activeReportGeneration++
        if (!hasRenderedStatisticsOnce && pageSnapshotState.body !is LegacyStatisticsBodyState.Loading) {
            showInitialLoading()
        }
        pendingReportForceRender = pendingReportForceRender || forceRender
        pendingReportRunnable?.let(reportRequestHandler::removeCallbacks)
        val runnable = Runnable {
            pendingReportRunnable = null
            val shouldForceRender = pendingReportForceRender
            pendingReportForceRender = false
            if (!isViewCreated || !isAdded || !isResumed || isHidden) return@Runnable
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
        if (!isViewCreated || !isAdded) return null
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
            dbVersion = dbVersion,
            effectiveRevision = component.effectiveReadRevision.value,
            calendar = component.effectiveHabitReads?.currentCalendar
        )
    }

    private fun hasActiveUserFilters(): Boolean =
        currentFilters.sphereId != null ||
            currentFilters.habitStatus != StatisticsHabitStatusFilter.ACTIVE ||
            currentFilters.goalType != StatisticsGoalTypeFilter.ALL ||
            currentFilters.tierScope != DayTierScope.MINIMUM

    private fun renderReport(result: StatisticsReportState, previous: StatisticsReportState?, generation: Int) {
        if (!isViewCreated || !isAdded || !isResumed || isHidden || generation != activeReportGeneration) return
        cancelRefreshingIndicator()
        val body = buildBodyState(result, previous)
        val direction = pendingTransitionDirection
        pendingTransitionDirection = 0
        pageSequence++
        pageSnapshotState = LegacyStatisticsPageSnapshot(
            pageId = pageSequence,
            body = body,
            transitionDirection = if (hasRenderedStatisticsOnce) direction else 0
        )
    }

    private fun updateReport(reportKey: ReportKey, forceRender: Boolean) {
        val start = reportKey.start
        val end = reportKey.end
        refreshHeaderState(start to end)

        if (!hasRenderedStatisticsOnce) {
            showInitialLoading()
        }

        val habits = component.habitList.toList()
        val firstWeekday = component.preferences.firstWeekday
        val taskGeneration = ++activeReportGeneration

        component.taskRunner.execute(object : org.isoron.uhabits.core.tasks.Task {
            private var calculatedData: StatisticsReportState? = null
            private var previousData: StatisticsReportState? = null
            private var calculatedFocusTotals: List<FocusTotal> = emptyList()

            override suspend fun doInBackground() {
                try {
                    if (taskGeneration != activeReportGeneration) return
                    if (component.session.isNextArchitecture) {
                        fun midnight(date: LocalDate) = java.util.Calendar.getInstance().apply {
                            clear()
                            set(date.year, date.month - 1, date.day)
                        }.timeInMillis
                        val sessions = org.isoron.uhabits.core.focus.sqlite.SQLiteFocusSessionRepository(component.db)
                            .getByTimeRange(midnight(start), midnight(end.plus(1)))
                        if (taskGeneration != activeReportGeneration) return
                        calculatedFocusTotals = sessions.groupBy { it.containerId }.map { (id, facts) ->
                            val name = id?.let { component.organizationFacade.containerQueries?.find(it, includeDeleted = true)?.name }
                                ?: if (id == null) "Без раздела" else "Удалённый раздел"
                            FocusTotal(id, name, facts.sumOf { it.durationMs })
                        }.sortedByDescending { it.durationMs }
                    }
                    if (taskGeneration != activeReportGeneration) return
                    val snapshots = component.effectiveHabitReads?.snapshots(habits, start, end)
                    if (taskGeneration != activeReportGeneration) return
                    calculatedData = StatisticsReportStateBuilder.build(
                        habits = habits,
                        period = reportKey.tab.toStatisticsPeriod(),
                        start = start,
                        end = end,
                        today = getToday(),
                        firstWeekday = firstWeekday,
                        filters = reportKey.filters,
                        snapshots = snapshots,
                        shouldCancel = { taskGeneration != activeReportGeneration }
                    )
                    if (taskGeneration != activeReportGeneration) return
                    if (reportKey.tab == ReportTab.WEEK || reportKey.tab == ReportTab.MONTH) {
                        val previousPeriodEnd = start.minus(1)
                        val previousStart = if (reportKey.tab == ReportTab.WEEK) start.minus(7)
                            else LocalDate(previousPeriodEnd.year, previousPeriodEnd.month, 1)
                        val comparableEnd = if (end.isNewerThan(getToday())) getToday() else end
                        val comparableDays = start.daysUntil(comparableEnd) + 1
                        val previousEnd = previousStart.plus(
                            (comparableDays - 1).coerceAtLeast(0).coerceAtMost(previousStart.daysUntil(previousPeriodEnd))
                        )
                        if (taskGeneration != activeReportGeneration) return
                        val prevSnapshots = component.effectiveHabitReads?.snapshots(habits, previousStart, previousEnd)
                        if (taskGeneration != activeReportGeneration) return
                        previousData = StatisticsReportStateBuilder.build(
                            habits = habits,
                            period = reportKey.tab.toStatisticsPeriod(),
                            start = previousStart,
                            end = previousEnd,
                            today = getToday(),
                            firstWeekday = firstWeekday,
                            filters = reportKey.filters,
                            snapshots = prevSnapshots,
                            shouldCancel = { taskGeneration != activeReportGeneration }
                        )
                    }
                } catch (_: org.isoron.uhabits.core.models.projection.EffectiveReadInvalidatedException) {
                    calculatedData = null
                    previousData = null
                }
            }

            override fun onPostExecute() {
                val currentKey = buildReportKey()
                if (!isViewCreated || !isAdded || !isResumed || isHidden || taskGeneration != activeReportGeneration) return
                if (reportKey != currentKey) return
                val result = calculatedData ?: return
                focusTotals = calculatedFocusTotals
                refreshHeaderState(result.start to result.end)

                renderReport(result, previousData, taskGeneration)
                hasRenderedStatisticsOnce = true
                lastRenderedReportKey = reportKey
            }
        })
    }

    private fun buildBodyState(
        report: StatisticsReportState,
        @Suppress("UNUSED_PARAMETER") previous: StatisticsReportState?
    ): LegacyStatisticsBodyState {
        val focusSection = if (component.session.isNextArchitecture) buildFocusSectionUi() else null
        if (report.matchingHabits == 0) {
            return LegacyStatisticsBodyState.Empty(
                noHabits = true,
                canResetFilters = hasActiveUserFilters(),
                focusSection = focusSection
            )
        }

        val sections = mutableListOf<LegacyStatisticsSectionUi>()
        if (focusSection != null) {
            sections.add(LegacyStatisticsSectionUi.Focus(focusSection))
        }

        when (report.period) {
            StatisticsPeriod.DAY -> {
                sections.add(buildDayOverviewSection(report))
                if (report.dayTiersEnabled) {
                    sections.add(buildTierBarsSection(report))
                }
            }

            StatisticsPeriod.WEEK -> {
                sections.add(buildDailyCompletionSection(report))
                if (report.dayTiersEnabled) {
                    sections.add(buildTierBarsSection(report))
                }
                if (report.spheresEnabled) {
                    buildSphereSection(report)?.let(sections::add)
                }
                buildHabitChangesSection(report)?.let(sections::add)
            }

            StatisticsPeriod.MONTH -> {
                sections.add(buildMonthCalendarSection(report))
                if (report.dayTiersEnabled) {
                    sections.add(buildTierBarsSection(report))
                }
                buildWeekdayRhythmSection(report)?.let(sections::add)
                if (report.spheresEnabled) {
                    buildSphereSection(report)?.let(sections::add)
                }
                buildHabitChangesSection(report)?.let(sections::add)
            }

            StatisticsPeriod.YEAR -> {
                buildYearMonthlyBarsSection(report)?.let(sections::add)
                if (report.dayTiersEnabled) {
                    sections.add(buildTierBarsSection(report))
                }
                buildWeekdayRhythmSection(report)?.let(sections::add)
                if (report.spheresEnabled) {
                    buildSphereSection(report)?.let(sections::add)
                }
            }

            StatisticsPeriod.ALL -> {
                buildAllTimeHistorySection(report)?.let(sections::add)
                if (report.dayTiersEnabled) {
                    sections.add(buildTierBarsSection(report))
                }
                if (report.spheresEnabled) {
                    buildSphereSection(report)?.let(sections::add)
                }
                buildWeekdayRhythmSection(report)?.let(sections::add)
                buildHabitStabilitySection(report)?.let(sections::add)
            }
        }

        return LegacyStatisticsBodyState.Report(sections = sections)
    }

    private fun buildFocusSectionUi(): LegacyFocusSectionUi = LegacyFocusSectionUi(
        totalDurationLabel = formatLegacyFocusDuration(focusTotals.sumOf { it.durationMs }),
        rows = focusTotals.map { total ->
            LegacyFocusRowUi(
                containerId = total.containerId,
                name = total.name,
                durationLabel = formatLegacyFocusDuration(total.durationMs)
            )
        }
    )

    private fun buildDayOverviewSection(report: StatisticsReportState): LegacyStatisticsSectionUi.DayOverview {
        val habitsToDisplay = report.habits.filter {
            !report.dayTiersEnabled || report.tierScope.includes(it.habit.dayTier)
        }
        return LegacyStatisticsSectionUi.DayOverview(
            title = getString(R.string.statistics_day_title_default),
            overallPercentText = report.overallProgress?.let { "${(it * 100).roundToInt()}%" },
            subtitle = getString(R.string.statistics_average_progress),
            habits = habitsToDisplay.map { result ->
                LegacyDayHabitRowUi(
                    habit = result.habit,
                    name = result.habit.name,
                    colorArgb = themeSwitcher.currentTheme.color(result.habit.color).toInt(),
                    detailText = formatHabitResult(result),
                    percentText = "${(result.progress * 100).roundToInt()}%"
                )
            }
        )
    }

    private fun buildTierBarsSection(report: StatisticsReportState): LegacyStatisticsSectionUi.TierBars {
        val title = if (report.period == StatisticsPeriod.DAY) {
            getString(R.string.statistics_day_title_by_level)
        } else {
            getString(R.string.statistics_by_level)
        }
        val tiers = listOf(DayTier.MINIMUM, DayTier.NORMAL, DayTier.IDEAL, DayTier.OPTIONAL)
        val rows = tiers.map { tier ->
            val progress = report.tierProgress[tier]
            val counts = report.tierCounts[tier]
            val pctStr = progress?.let { "${(it * 100).roundToInt()}%" } ?: "—"
            val valueText = if (report.period == StatisticsPeriod.DAY && progress != null && counts != null && counts.second > 0) {
                "$pctStr · ${counts.first}/${counts.second}"
            } else {
                pctStr
            }
            LegacyTierBarRowUi(
                tier = tier,
                label = tierLabel(tier),
                colorArgb = tierColor(tier),
                valueText = valueText,
                hasProgress = progress != null,
                progressFraction = progress?.toFloat()?.coerceIn(0f, 1f) ?: 0f
            )
        }
        return LegacyStatisticsSectionUi.TierBars(title = title, rows = rows)
    }

    private fun buildDailyCompletionSection(report: StatisticsReportState): LegacyStatisticsSectionUi.PercentageBar {
        val daysByDate = report.dailyProgress.associateBy { it.date }
        val today = getToday()
        val items = (0..6).map { offset ->
            val date = report.start.plus(offset)
            val dp = daysByDate[date]
            val isToday = dp?.isToday ?: (date == today)
            val isFuture = dp?.isFuture ?: date.isNewerThan(today)
            LegacyPercentageBarItem(
                progress = if (isFuture) null else dp?.progress,
                label = dateFormatter.shortWeekdayName(date.dayOfWeek),
                isToday = isToday,
                isFuture = isFuture
            )
        }
        return LegacyStatisticsSectionUi.PercentageBar(
            title = getString(R.string.statistics_by_day),
            comparisonText = periodComparison(report),
            subtitle = getString(R.string.statistics_average_progress),
            items = items
        )
    }

    private fun buildMonthCalendarSection(report: StatisticsReportState): LegacyStatisticsSectionUi.MonthCalendar =
        LegacyStatisticsSectionUi.MonthCalendar(
            LegacyMonthCalendarSectionUi(
                title = getString(R.string.statistics_activity_calendar),
                comparisonText = periodComparison(report),
                monthStart = report.start,
                monthEnd = report.end,
                firstWeekday = component.preferences.firstWeekday,
                days = report.dailyProgress,
                averageProgress = report.overallProgress
            )
        )

    private fun buildWeekdayRhythmSection(report: StatisticsReportState): LegacyStatisticsSectionUi.WeekdayRhythm? {
        val hasData = report.weekdayRhythm.size == 7 &&
            report.weekdayRhythm.any { it.observations >= 4 && it.progress != null }
        if (!hasData) return null
        val items = report.weekdayRhythm.map { item ->
            LegacyWeekRhythmItem(
                dayOfWeek = item.dayOfWeek,
                progress = item.progress,
                label = dateFormatter.shortWeekdayName(item.dayOfWeek)
            )
        }
        return LegacyStatisticsSectionUi.WeekdayRhythm(
            title = getString(R.string.statistics_by_weekday),
            items = items
        )
    }

    private fun buildYearMonthlyBarsSection(report: StatisticsReportState): LegacyStatisticsSectionUi.PercentageBar? {
        val buckets = report.trendBuckets
        if (buckets.none { it.progress != null }) return null
        val today = getToday()
        val items = buckets.map { bucket ->
            val isFuture = bucket.start.isNewerThan(today)
            val isCurrent = !isFuture && !bucket.end.isOlderThan(today)
            LegacyPercentageBarItem(
                progress = if (isFuture) null else bucket.progress,
                label = dateFormatter.shortMonthName(bucket.start),
                isToday = isCurrent,
                isFuture = isFuture
            )
        }
        return LegacyStatisticsSectionUi.PercentageBar(
            title = getString(R.string.statistics_by_month),
            comparisonText = periodComparison(report),
            subtitle = getString(R.string.statistics_average_progress),
            items = items
        )
    }

    private fun buildAllTimeHistorySection(report: StatisticsReportState): LegacyStatisticsSectionUi.AllTimeHistory? {
        val buckets = report.trendBuckets
        if (buckets.count { it.progress != null } < 2) return null
        val items = buckets.map { bucket ->
            val label = if (bucket.start.month == 1 && bucket.end.month == 12) {
                bucket.start.year.toString()
            } else {
                dateFormatter.shortMonthName(bucket.start)
            }
            LegacyBucketItem(label = label, progress = bucket.progress)
        }
        return LegacyStatisticsSectionUi.AllTimeHistory(
            title = getString(R.string.statistics_long_term_history),
            subtitle = chartMetricLabel(report),
            items = items
        )
    }

    private fun buildSphereSection(report: StatisticsReportState): LegacyStatisticsSectionUi.Spheres? {
        if (!component.preferences.isHabitSpheresEnabled) return null
        if (currentFilters.sphereId != null) return null

        val meaningfulSpheres = report.sphereProgress.filter { it.blockId != null }
        if (meaningfulSpheres.size <= 1) return null

        val blocks = component.habitList.getBlocks().associateBy { it.id }
        val ordered = report.sphereProgress.sortedWith(
            compareByDescending<StatisticsSphereProgress> { it.progress != null }
                .thenByDescending { it.progress ?: -1.0 }
        )
        val fallbackColor = palette.onSurfaceVariant
        val rows = ordered.map { sphereItem ->
            val block = sphereItem.blockId?.let(blocks::get)
            val sphereName = block?.name ?: getString(R.string.statistics_no_sphere)
            val sphereColor = block?.let { themeSwitcher.currentTheme.color(it.color).toInt() } ?: fallbackColor
            val progress = sphereItem.progress
            LegacySphereRowUi(
                blockId = sphereItem.blockId,
                name = sphereName,
                colorArgb = sphereColor,
                valueText = progress?.let { "${(it * 100).roundToInt()}%" } ?: "—",
                hasProgress = progress != null,
                progressFraction = progress?.toFloat()?.coerceIn(0f, 1f) ?: 0f
            )
        }
        return LegacyStatisticsSectionUi.Spheres(
            title = getString(R.string.statistics_by_sphere),
            rows = rows
        )
    }

    private fun buildHabitChangesSection(report: StatisticsReportState): LegacyStatisticsSectionUi.HabitChanges? {
        val changes = report.habitChanges
        if (changes.isEmpty()) return null
        val titleRes = if (report.period == StatisticsPeriod.MONTH) {
            R.string.statistics_changes_month_title
        } else {
            R.string.statistics_changes_week_title
        }
        val ppLabel = getString(R.string.statistics_percentage_points)
        val rows = changes.map { item ->
            val sign = if (item.deltaPoints > 0) "+" else ""
            LegacyHabitChangeRowUi(
                habit = item.habit,
                name = item.habit.name,
                colorArgb = themeSwitcher.currentTheme.color(item.habit.color).toInt(),
                transitionText = "${(item.previousProgress * 100).roundToInt()}% → ${(item.currentProgress * 100).roundToInt()}%",
                deltaText = "$sign${item.deltaPoints} $ppLabel"
            )
        }
        return LegacyStatisticsSectionUi.HabitChanges(
            title = getString(titleRes),
            subtitle = getString(R.string.statistics_habit_changes_subtitle),
            rows = rows
        )
    }

    private fun buildHabitStabilitySection(report: StatisticsReportState): LegacyStatisticsSectionUi.HabitStability? {
        val items = report.habitStability
        if (items.isEmpty()) return null
        val rows = items.map { item ->
            LegacyHabitStabilityRowUi(
                habit = item.habit,
                name = item.habit.name,
                colorArgb = themeSwitcher.currentTheme.color(item.habit.color).toInt(),
                scoreText = "${(item.score * 100).roundToInt()}%",
                progressFraction = item.score.toFloat().coerceIn(0f, 1f)
            )
        }
        return LegacyStatisticsSectionUi.HabitStability(
            title = getString(R.string.statistics_habit_stability_title),
            rows = rows
        )
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
        val absPoints = abs(points)
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

    private fun formatHabitResult(result: StatisticsHabitResult): String {
        val statusLabel = when (result.status) {
            StatisticsResultStatus.PENDING -> getString(R.string.statistics_status_pending)
            StatisticsResultStatus.ON_TRACK -> getString(R.string.statistics_status_on_track)
            StatisticsResultStatus.VIOLATED -> getString(R.string.statistics_status_violated)
            StatisticsResultStatus.MISSED -> getString(R.string.statistics_status_missed)
            StatisticsResultStatus.SKIPPED -> getString(R.string.statistics_status_skipped)
            StatisticsResultStatus.COMPLETED -> null
        }
        return formatLegacyHabitResult(result, statusLabel)
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
