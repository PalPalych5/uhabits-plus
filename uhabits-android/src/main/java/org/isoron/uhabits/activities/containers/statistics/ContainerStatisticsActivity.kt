/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.activities.containers.statistics

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.WindowCompat
import org.isoron.platform.gui.toInt
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.getToday
import org.isoron.uhabits.BaseExceptionHandler
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.AndroidThemeSwitcher
import org.isoron.uhabits.activities.common.theme.MainTabsThemeBridge
import org.isoron.uhabits.activities.main.MainActivity
import org.isoron.uhabits.activities.main.MainDestination
import org.isoron.uhabits.compose.UHabitComposeColors
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.containers.statistics.ContainerScopeMode
import java.util.Locale

class ContainerStatisticsActivity : AppCompatActivity() {

    private lateinit var themeSwitcher: AndroidThemeSwitcher

    private var targetContainerId: ContainerId = ContainerId("")
    private var currentRangeType: ContainerRangeType = ContainerRangeType.WEEK
    private var currentScope: ContainerScopeMode = ContainerScopeMode.SUBTREE
    private var anchorDate: LocalDate = getToday()

    private var composeColorsState by mutableStateOf<UHabitComposeColors?>(null)
    private var uiState by mutableStateOf<ContainerStatisticsUiState?>(null)

    private val app get() = application as HabitsApplication
    private val appComponent get() = app.component
    private val facade get() = appComponent.organizationFacade
    private val cQueries get() = facade.containerQueries
    private val statsService get() = appComponent.containerStatisticsService
    private val prefs get() = appComponent.preferences

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

        if (savedInstanceState != null) {
            currentRangeType = savedInstanceState.getString(STATE_RANGE_TYPE)?.let {
                runCatching { ContainerRangeType.valueOf(it) }.getOrNull()
            } ?: ContainerRangeType.WEEK
            currentScope = savedInstanceState.getString(STATE_SCOPE)?.let {
                runCatching { ContainerScopeMode.valueOf(it) }.getOrNull()
            } ?: ContainerScopeMode.SUBTREE
            if (savedInstanceState.containsKey(STATE_ANCHOR_YEAR)) {
                anchorDate = LocalDate(
                    savedInstanceState.getInt(STATE_ANCHOR_YEAR),
                    savedInstanceState.getInt(STATE_ANCHOR_MONTH),
                    savedInstanceState.getInt(STATE_ANCHOR_DAY),
                )
            }
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        refreshState()

        val composeView = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val colors = composeColorsState ?: return@setContent
                val state = uiState ?: return@setContent
                ContainerStatisticsScreen(
                    state = state,
                    colors = colors,
                    onBack = { finish() },
                    onSelectRange = { range ->
                        if (currentRangeType != range) {
                            currentRangeType = range
                            refreshState()
                        }
                    },
                    onSelectScope = { scope ->
                        if (currentScope != scope) {
                            currentScope = scope
                            refreshState()
                        }
                    },
                    onPrevRange = {
                        anchorDate = ContainerStatisticsLogic.stepAnchor(currentRangeType, anchorDate, -1)
                        refreshState()
                    },
                    onNextRange = {
                        anchorDate = ContainerStatisticsLogic.stepAnchor(currentRangeType, anchorDate, 1)
                        refreshState()
                    },
                    onOpenUnifiedStatistics = { openUnifiedStatistics() },
                )
            }
        }
        setContentView(composeView)
    }

    override fun onResume() {
        super.onResume()
        if (targetContainerId.value.isNotEmpty()) {
            refreshState()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_RANGE_TYPE, currentRangeType.name)
        outState.putString(STATE_SCOPE, currentScope.name)
        outState.putInt(STATE_ANCHOR_YEAR, anchorDate.year)
        outState.putInt(STATE_ANCHOR_MONTH, anchorDate.month)
        outState.putInt(STATE_ANCHOR_DAY, anchorDate.day)
        super.onSaveInstanceState(outState)
    }

    private fun openUnifiedStatistics() {
        val intent = MainActivity.intent(
            this,
            MainDestination.STATISTICS,
            statisticsContainerId = targetContainerId.value,
            statisticsIncludeDescendants = (currentScope == ContainerScopeMode.SUBTREE),
        )
        startActivity(intent)
    }

    private fun refreshState() {
        val palette = MainTabsThemeBridge.resolve(this)
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = !palette.isDark
        insetsController.isAppearanceLightNavigationBars = !palette.isDark

        composeColorsState = UHabitComposeColors.fromPalette(
            palette = palette,
            cardCornerRadiusDp = palette.statisticsCardCornerRadius,
            showCardBorders = palette.showHabitCardBorders,
        )

        val container = cQueries?.find(targetContainerId, includeDeleted = true)
        val containerName = container?.name ?: getString(R.string.container_statistics_title)
        val range = ContainerStatisticsLogic.computeRange(currentRangeType, anchorDate)
        val dateRangeLabel = ContainerStatisticsLogic.formatDateRange(range)
        val service = statsService
        val canOpenUnified = appComponent.session.isNextArchitecture

        if (service == null) {
            uiState = ContainerStatisticsUiState(
                title = containerName,
                subtitle = getString(R.string.focus_credit_unavailable),
                rangeType = currentRangeType,
                scopeMode = currentScope,
                dateRangeLabel = dateRangeLabel,
                serviceUnavailable = true,
                canOpenUnified = canOpenUnified,
                focusIsEmpty = true,
                focusTotalDuration = ContainerStatisticsLogic.formatDuration(0L),
                focusSessionCountText = getString(R.string.container_statistics_focus_no_sessions),
                focusSubtreeBreakdownText = null,
                focusDailyBars = emptyList(),
                focusChildRows = emptyList(),
                tasksCompletedCount = 0,
                tasksPendingCount = 0,
                tasksOverdueCount = 0,
                tasksIsEmpty = true,
                habitsActiveCountText = getString(R.string.container_statistics_habits_active_count, 0),
                habitsReachedChipText = "",
                habitsIncompleteChipText = "",
                habitsUnknownChipText = "",
                habitRows = emptyList(),
                habitsIsEmpty = true,
            )
            return
        }

        val report = service.getReport(targetContainerId, range, currentScope)
        val subtitle = when (currentScope) {
            ContainerScopeMode.SUBTREE -> getString(R.string.container_statistics_scope_subtree)
            ContainerScopeMode.HERE -> getString(R.string.container_statistics_scope_here)
        }

        val focus = report.focus
        val focusIsEmpty = focus.totalDurationMs == 0L && focus.sessionCount == 0
        val focusTotalDuration = ContainerStatisticsLogic.formatDuration(if (focusIsEmpty) 0L else focus.totalDurationMs)
        val focusSessionCountText = when {
            focusIsEmpty -> getString(R.string.container_statistics_focus_no_sessions)
            focus.sessionCount == 1 -> getString(R.string.container_statistics_focus_one_session)
            else -> getString(R.string.container_statistics_focus_sessions_format, focus.sessionCount)
        }
        val focusSubtreeBreakdownText =
            if (!focusIsEmpty && currentScope == ContainerScopeMode.SUBTREE && focus.childBreakdown.isNotEmpty()) {
                "${getString(R.string.container_statistics_focus_direct_breakdown, ContainerStatisticsLogic.formatDuration(focus.directDurationMs))} • " +
                    getString(R.string.container_statistics_focus_descendant_breakdown, ContainerStatisticsLogic.formatDuration(focus.descendantDurationMs))
            } else {
                null
            }

        val maxBucketDuration = focus.dailyBuckets.maxOfOrNull { it.durationMs }?.coerceAtLeast(1L) ?: 1L
        val focusDailyBars = if (!focusIsEmpty) {
            focus.dailyBuckets.map { bucket ->
                ContainerDailyBarUiModel(
                    date = bucket.date,
                    label = ContainerStatisticsLogic.formatDayLabel(bucket.date, focus.dailyBuckets.size),
                    durationMs = bucket.durationMs,
                    heightRatio = ContainerStatisticsLogic.computeBarHeightRatio(bucket.durationMs, maxBucketDuration),
                    formattedDuration = ContainerStatisticsLogic.formatDuration(bucket.durationMs),
                )
            }
        } else {
            emptyList()
        }

        val focusChildRows =
            if (!focusIsEmpty && currentScope == ContainerScopeMode.SUBTREE && focus.childBreakdown.isNotEmpty()) {
                focus.childBreakdown.map { child ->
                    ContainerChildBreakdownUiModel(
                        id = child.containerId.value,
                        name = child.containerName,
                        durationText = ContainerStatisticsLogic.formatDuration(child.durationMs),
                    )
                }
            } else {
                emptyList()
            }

        val tasks = report.tasks
        val tasksIsEmpty = tasks.completedCount == 0 && tasks.pendingCount == 0 && tasks.overdueCount == 0

        val habits = report.habits
        val habitsIsEmpty = habits.totalHabitCount == 0 || habits.habitRows.isEmpty()
        val habitRows = habits.habitRows.map { row ->
            val habit = appComponent.habitList.getByUUID(row.habitRef.uuid)
            val habitColor = habit?.let { themeSwitcher.currentTheme.color(it.color).toInt() } ?: palette.accent
            val progressText = if (row.isNumerical) {
                val currentVal = row.totalValue ?: 0L
                val targetVal = row.targetValue ?: 0.0
                val unitStr = row.unit?.takeIf { it.isNotEmpty() }?.let { " $it" } ?: ""
                "$currentVal / ${ContainerStatisticsLogic.formatNumber(targetVal)}$unitStr"
            } else {
                "${row.completedDaysCount} / ${row.totalDaysCount} d"
            }
            val focusContributionText = if (row.contributionValue > 0L) {
                val valueStr = when {
                    row.unit.equals("min", ignoreCase = true) ->
                        ContainerStatisticsLogic.formatDuration(row.contributionValue * 60000L)
                    row.unit.equals("s", ignoreCase = true) || row.unit.equals("sec", ignoreCase = true) ->
                        ContainerStatisticsLogic.formatDuration(row.contributionValue * 1000L)
                    row.unit.equals("h", ignoreCase = true) || row.unit.equals("hours", ignoreCase = true) ->
                        ContainerStatisticsLogic.formatDuration(row.contributionValue * 3600000L)
                    else -> "${row.contributionValue} ${row.unit.orEmpty()}".trim()
                }
                getString(R.string.container_statistics_habits_focus_contribution, valueStr)
            } else {
                null
            }
            ContainerHabitRowUiModel(
                uuid = row.habitRef.uuid,
                name = row.name,
                colorArgb = habitColor,
                goalStatus = row.goalStatus,
                progressText = progressText,
                focusContributionText = focusContributionText,
            )
        }

        uiState = ContainerStatisticsUiState(
            title = containerName,
            subtitle = subtitle,
            rangeType = currentRangeType,
            scopeMode = currentScope,
            dateRangeLabel = dateRangeLabel,
            serviceUnavailable = false,
            canOpenUnified = canOpenUnified,
            focusIsEmpty = focusIsEmpty,
            focusTotalDuration = focusTotalDuration,
            focusSessionCountText = focusSessionCountText,
            focusSubtreeBreakdownText = focusSubtreeBreakdownText,
            focusDailyBars = focusDailyBars,
            focusChildRows = focusChildRows,
            tasksCompletedCount = tasks.completedCount,
            tasksPendingCount = tasks.pendingCount,
            tasksOverdueCount = tasks.overdueCount,
            tasksIsEmpty = tasksIsEmpty,
            habitsActiveCountText = getString(R.string.container_statistics_habits_active_count, habits.totalHabitCount),
            habitsReachedChipText = "${habits.reachedCount} ${getString(R.string.container_statistics_habits_reached).lowercase(Locale.getDefault())}",
            habitsIncompleteChipText = "${habits.incompleteCount} ${getString(R.string.container_statistics_habits_incomplete).lowercase(Locale.getDefault())}",
            habitsUnknownChipText = "${habits.unknownCount} ${getString(R.string.container_statistics_habits_unknown).lowercase(Locale.getDefault())}",
            habitRows = habitRows,
            habitsIsEmpty = habitsIsEmpty,
        )
    }

    companion object {
        const val EXTRA_CONTAINER_ID = "org.isoron.uhabits.CONTAINER_ID"
        private const val STATE_RANGE_TYPE = "container_stats_range_type"
        private const val STATE_SCOPE = "container_stats_scope"
        private const val STATE_ANCHOR_YEAR = "container_stats_anchor_year"
        private const val STATE_ANCHOR_MONTH = "container_stats_anchor_month"
        private const val STATE_ANCHOR_DAY = "container_stats_anchor_day"

        fun start(context: Context, containerId: ContainerId) {
            val intent = Intent(context, ContainerStatisticsActivity::class.java).apply {
                putExtra(EXTRA_CONTAINER_ID, containerId.value)
            }
            context.startActivity(intent)
        }
    }
}
