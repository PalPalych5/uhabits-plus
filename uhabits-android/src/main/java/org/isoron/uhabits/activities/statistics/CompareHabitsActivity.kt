/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.activities.statistics

import android.graphics.Color
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.WindowCompat
import org.isoron.platform.gui.toInt
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.getToday
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.AndroidThemeSwitcher
import org.isoron.uhabits.activities.common.theme.MainTabsThemeBridge
import org.isoron.uhabits.compose.UHabitComposeColors
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.projection.EffectiveHabitReadSnapshot
import org.isoron.uhabits.core.models.projection.EffectiveReadInvalidatedException
import org.isoron.uhabits.core.models.projection.EffectiveReadRevision
import org.isoron.uhabits.core.models.projection.HabitGoalStatus
import org.isoron.uhabits.core.models.projection.HabitProjectionIntegrityException
import org.isoron.uhabits.core.ui.screens.statistics.CompareHabitsPresenter
import org.isoron.uhabits.core.ui.screens.statistics.ComparePeriod
import org.isoron.uhabits.core.ui.screens.statistics.StatisticsReportStateBuilder
import java.text.DecimalFormat
import kotlin.math.roundToInt

/**
 * Dedicated full-screen analysis activity for comparing 2 to 4 habits:
 * - Deterministic, guaranteed distinct series palette
 * - Configurable comparison period (Week, Month, 3 Months, Year, All Time)
 * - Section 1: Overview (Loop score, period changes, period completion, streak)
 * - Section 2: Stability (Multi-series Loop Score chart with interactive legend & scrub inspection)
 * - Section 3: Performance (Normalized History chart toward own target)
 * - Section 4: Calendar (Small multiples with identical time windows)
 * - Section 5: Streaks (Latest & record streaks using horizontal Loop streak bars)
 * - Section 6: Frequency (Small multiples weekday frequency where data permits)
 */
class CompareHabitsActivity : AppCompatActivity() {

    companion object {
        val SERIES_COLORS = intArrayOf(
            Color.parseColor("#00BFA5"), // Emerald / Teal
            Color.parseColor("#FF6E40"), // Coral / Orange
            Color.parseColor("#7C4DFF"), // Deep Purple
            Color.parseColor("#FFB300"), // Amber
        )
    }

    private lateinit var themeSwitcher: AndroidThemeSwitcher

    private val selectedHabits = mutableListOf<Habit>()
    private var availableHabits: List<Habit> = emptyList()
    private var currentPeriod = ComparePeriod.MONTH
    private var effectiveRevisionListener: (() -> Unit)? = null
    private var observedRevision: EffectiveReadRevision? = null
    private var showPickerState by mutableStateOf(false)
    private var chipsScrollToEndToken by mutableIntStateOf(0)
    private var composeColorsState by mutableStateOf<UHabitComposeColors?>(null)
    private var uiState by mutableStateOf<CompareHabitsUiState?>(null)

    internal var renderedSnapshots: Map<HabitRef, EffectiveHabitReadSnapshot> = emptyMap()
        private set
    internal var renderedRange: Pair<LocalDate, LocalDate>? = null
        private set

    internal fun renderedGoalText(habitId: Long): String? {
        val ready = uiState?.content as? CompareContentState.Ready ?: return null
        return ready.overviewRows.firstOrNull { it.habitId == habitId }?.goalText
    }

    internal fun renderedCalendarGoalStatus(habitId: Long, date: LocalDate): HabitGoalStatus? {
        val ready = uiState?.content as? CompareContentState.Ready ?: return null
        return ready.calendars.firstOrNull { it.habitId == habitId }?.goalStatusOn(date)
    }

    private val component get() = (application as HabitsApplication).component
    private val palette get() = MainTabsThemeBridge.resolve(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        themeSwitcher = AndroidThemeSwitcher(this, component.preferences)
        themeSwitcher.apply()

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        parseInitialIntent(savedInstanceState)
        showPickerState = selectedHabits.isEmpty() && savedInstanceState == null
        rebuildComparison()

        val composeView = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val colors = composeColorsState ?: return@setContent
                val state = uiState ?: return@setContent
                CompareHabitsScreen(
                    state = state.copy(
                        showPicker = showPickerState,
                        chipsScrollToEndToken = chipsScrollToEndToken,
                    ),
                    colors = colors,
                    onBack = { finish() },
                    onRemoveHabit = { habitId ->
                        selectedHabits.removeAll { it.id == habitId }
                        rebuildComparison()
                    },
                    onOpenPicker = {
                        showPickerState = true
                    },
                    onDismissPicker = {
                        showPickerState = false
                    },
                    onConfirmPickerSelection = { selectedIds ->
                        selectedHabits.clear()
                        selectedIds.distinct()
                            .mapNotNull { id -> availableHabits.firstOrNull { it.id == id } }
                            .take(4)
                            .forEach { selectedHabits.add(it) }
                        showPickerState = false
                        chipsScrollToEndToken++
                        rebuildComparison()
                    },
                    onSelectPeriod = { period ->
                        if (period != currentPeriod) {
                            currentPeriod = period
                            rebuildComparison()
                        }
                    },
                )
            }
        }
        setContentView(composeView)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLongArray("selected_habit_ids", selectedHabits.mapNotNull { it.id }.toLongArray())
        outState.putString("comparison_period", currentPeriod.name)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        val revision = component.effectiveReadRevision
        observedRevision = revision
        effectiveRevisionListener = {
            runOnUiThread {
                if (observedRevision === revision && !isFinishing && !isDestroyed) {
                    rebuildComparison()
                }
            }
        }.also { listener ->
            revision.addListener(listener)
        }
        // Refresh changes made while this Activity was paused, including calendar preferences.
        rebuildComparison()
    }

    override fun onPause() {
        super.onPause()
        effectiveRevisionListener?.let { observedRevision?.removeListener(it) }
        observedRevision = null
        effectiveRevisionListener = null
    }

    private fun parseInitialIntent(savedInstanceState: Bundle? = null) {
        val allowed = intent.getLongArrayExtra("allowed_habit_ids")?.toSet()
        val allActive = component.habitList.filter {
            !it.isArchived && (allowed == null || it.id?.let(allowed::contains) == true)
        }
        availableHabits = allActive
        val habitIds = (savedInstanceState?.getLongArray("selected_habit_ids")
            ?: intent.getLongArrayExtra("initial_habit_ids"))?.toList()
        if (!habitIds.isNullOrEmpty()) {
            habitIds.distinct().mapNotNull { id -> allActive.firstOrNull { it.id == id } }
                .take(4)
                .forEach { selectedHabits.add(it) }
        }

        val initialPeriodStr = savedInstanceState?.getString("comparison_period")
            ?: intent.getStringExtra("initial_period")
        currentPeriod = when (initialPeriodStr) {
            "WEEK" -> ComparePeriod.WEEK
            "MONTH" -> ComparePeriod.MONTH
            "3MONTHS", "THREE_MONTHS" -> ComparePeriod.THREE_MONTHS
            "YEAR" -> ComparePeriod.YEAR
            "ALL" -> ComparePeriod.ALL
            else -> ComparePeriod.MONTH
        }
    }

    private fun formatQuantity(value: Double): String = DecimalFormat("0.##").format(value)

    private fun rebuildComparison() {
        val currentPalette = palette
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = !currentPalette.isDark
        insetsController.isAppearanceLightNavigationBars = !currentPalette.isDark

        composeColorsState = UHabitComposeColors.fromPalette(
            palette = currentPalette,
            cardCornerRadiusDp = currentPalette.statisticsCardCornerRadius,
            showCardBorders = currentPalette.showHabitCardBorders,
        )

        val blocks = component.habitList.getBlocks().associateBy { it.id }
        val spheresEnabled = component.preferences.isHabitSpheresEnabled
        val pickerHabits = availableHabits.mapNotNull { habit ->
            val id = habit.id ?: return@mapNotNull null
            val block = blocks[habit.blockId]
            ComparePickerHabitUiModel(
                habitId = id,
                name = habit.name,
                habitColorArgb = themeSwitcher.currentTheme.color(habit.color).toInt(),
                blockId = habit.blockId,
                blockName = block?.name,
                blockColorArgb = block?.let { themeSwitcher.currentTheme.color(it.color).toInt() },
            )
        }
        val selectedChips = selectedHabits.mapIndexed { index, habit ->
            CompareSelectedChipUiModel(
                habitId = habit.id ?: 0L,
                name = habit.name,
                seriesColorArgb = SERIES_COLORS[index % SERIES_COLORS.size],
            )
        }

        renderedSnapshots = emptyMap()
        renderedRange = null

        if (selectedHabits.size < 2) {
            uiState = CompareHabitsUiState(
                selectedChips = selectedChips,
                currentPeriod = currentPeriod,
                chipsScrollToEndToken = chipsScrollToEndToken,
                content = CompareContentState.MinHabitsWarning,
                pickerHabits = pickerHabits,
                spheresEnabled = spheresEnabled,
                showPicker = showPickerState,
            )
            return
        }

        val today = getToday()
        val queryFrom = CompareHabitsPresenter.getQueryStartDate(today, currentPeriod)
        val snapshots = try {
            component.effectiveHabitReads?.snapshots(selectedHabits, queryFrom, today)
        } catch (_: HabitProjectionIntegrityException) {
            uiState = CompareHabitsUiState(
                selectedChips = selectedChips,
                currentPeriod = currentPeriod,
                chipsScrollToEndToken = chipsScrollToEndToken,
                content = CompareContentState.Unavailable,
                pickerHabits = pickerHabits,
                spheresEnabled = spheresEnabled,
                showPicker = showPickerState,
            )
            return
        } catch (_: EffectiveReadInvalidatedException) {
            uiState = CompareHabitsUiState(
                selectedChips = selectedChips,
                currentPeriod = currentPeriod,
                chipsScrollToEndToken = chipsScrollToEndToken,
                content = CompareContentState.Unavailable,
                pickerHabits = pickerHabits,
                spheresEnabled = spheresEnabled,
                showPicker = showPickerState,
            )
            return
        }

        val startDate = CompareHabitsPresenter.getStartDateForPeriod(selectedHabits, today, currentPeriod, snapshots)
        renderedSnapshots = snapshots.orEmpty()
        renderedRange = startDate to today

        val firstWeekday = component.preferences.firstWeekday

        // 1. Overview rows
        val overviewRows = selectedHabits.mapIndexed { i, habit ->
            val seriesColor = SERIES_COLORS[i % SERIES_COLORS.size]
            val snapshot = habit.uuid?.let { u -> snapshots?.get(HabitRef(u)) }
            val metricState = CompareHabitsPresenter.buildOverviewMetrics(habit, startDate, today, currentPeriod, snapshot)
            val delta = metricState.periodDelta?.let { (it * 100).roundToInt() }
            val currStreak = metricState.streakLength ?: 0
            val goalText = if (habit.isNumerical && snapshot?.hasContributions == true) {
                val goal = try {
                    snapshot.goalAchievementOn(today).progress
                } catch (_: HabitProjectionIntegrityException) {
                    null
                }
                if (goal == null) {
                    getString(R.string.effective_read_unavailable)
                } else {
                    val prefix = if (goal.value.hasCompleteCoverage) "" else "≥"
                    val actual = goal.value.totalValue?.let { formatQuantity(it / 1000.0) } ?: "—"
                    "$prefix$actual / ${formatQuantity(goal.goal.targetValue)} ${goal.goal.unit}"
                }
            } else {
                null
            }

            CompareOverviewRowUiModel(
                habitId = habit.id ?: 0L,
                name = habit.name,
                seriesColorArgb = seriesColor,
                stabilityText = metricState.scoreToday?.let { "${(it * 100).roundToInt()}%" } ?: "—",
                changeText = if (delta != null) "${if (delta > 0) "+" else ""}$delta п.п." else "—",
                completionText = metricState.periodCompletion?.let { "${(it * 100).roundToInt()}%" } ?: "—",
                streakText = if (currStreak > 0) "$currStreak" else "—",
                goalText = goalText,
            )
        }

        // 2. Stability series
        val stabilitySeries = selectedHabits.mapIndexed { i, habit ->
            val color = SERIES_COLORS[i % SERIES_COLORS.size]
            val snapshot = habit.uuid?.let { u -> snapshots?.get(HabitRef(u)) }
            val scores = CompareHabitsPresenter.buildStabilitySeries(habit, startDate, today, snapshot)
            CompareLineSeriesUiModel(habit.id ?: 0L, habit.name, color, scores)
        }

        // 3. Execution series
        val executionSeries = selectedHabits.mapIndexed { i, habit ->
            val color = SERIES_COLORS[i % SERIES_COLORS.size]
            val snapshot = habit.uuid?.let { u -> snapshots?.get(HabitRef(u)) }
            val points = CompareHabitsPresenter.buildExecutionSeries(habit, startDate, today, snapshot)
            CompareLineSeriesUiModel(habit.id ?: 0L, habit.name, color, points)
        }

        // 4. Calendar small multiples
        val calendarStart = if (currentPeriod == ComparePeriod.MONTH) today.startOfMonth() else startDate
        val calendarEnd = if (currentPeriod == ComparePeriod.MONTH) {
            calendarStart.plus(calendarStart.monthLength - 1)
        } else {
            today
        }
        val calendarFirstWeekday = if (currentPeriod == ComparePeriod.WEEK) calendarStart.dayOfWeek else firstWeekday
        val calendarMonthLayout = currentPeriod == ComparePeriod.MONTH || currentPeriod == ComparePeriod.WEEK
        val calendars = selectedHabits.mapIndexed { i, habit ->
            val seriesColor = SERIES_COLORS[i % SERIES_COLORS.size]
            val snapshot = habit.uuid?.let { u -> snapshots?.get(HabitRef(u)) }
            CompareChartLogic.buildCalendarGrid(
                habit = habit,
                seriesColorArgb = seriesColor,
                from = calendarStart,
                to = calendarEnd,
                today = today,
                firstWeekday = calendarFirstWeekday,
                monthLayout = calendarMonthLayout,
                snapshot = snapshot,
            )
        }

        // 5. Streaks comparison
        val streakStates = selectedHabits.map { h ->
            val snapshot = h.uuid?.let { u -> snapshots?.get(HabitRef(u)) }
            CompareHabitsPresenter.buildStreakState(h, snapshot)
        }
        val sharedMax = streakStates
            .flatMap { listOfNotNull(it.latestStreak, it.recordStreak) }
            .maxOfOrNull { it.length }?.toLong()?.coerceAtLeast(1L) ?: 1L
        val streakRows = selectedHabits.mapIndexed { i, habit ->
            val st = streakStates[i]
            CompareStreakRowUiModel(
                habitId = habit.id ?: 0L,
                name = habit.name,
                seriesColorArgb = SERIES_COLORS[i % SERIES_COLORS.size],
                isExact = st.isExact,
                latestStreak = st.latestStreak,
                recordStreak = st.recordStreak,
            )
        }

        // 6. Frequency comparison
        val habitsWithHistory = selectedHabits.filter {
            val snapshot = it.uuid?.let { u -> snapshots?.get(HabitRef(u)) }
            val habitStart = StatisticsReportStateBuilder.getHabitStartDate(it, today, snapshot)
            habitStart.daysUntil(today) >= 14
        }
        val frequencyRows = habitsWithHistory.map { habit ->
            val seriesColor = SERIES_COLORS[selectedHabits.indexOf(habit) % SERIES_COLORS.size]
            val snapshot = habit.uuid?.let { u -> snapshots?.get(HabitRef(u)) }
            val frequency = CompareHabitsPresenter.buildFrequency(habit, snapshot)
            CompareFrequencyRowUiModel(
                habitId = habit.id ?: 0L,
                name = habit.name,
                seriesColorArgb = seriesColor,
                isNumerical = habit.isNumerical,
                firstWeekday = firstWeekday,
                frequency = frequency,
                isUnavailable = snapshot?.hasContributions == true && frequency.isEmpty(),
            )
        }

        uiState = CompareHabitsUiState(
            selectedChips = selectedChips,
            currentPeriod = currentPeriod,
            chipsScrollToEndToken = chipsScrollToEndToken,
            content = CompareContentState.Ready(
                rangeStart = startDate,
                rangeEnd = today,
                overviewRows = overviewRows,
                stabilitySeries = stabilitySeries,
                executionSeries = executionSeries,
                calendars = calendars,
                sharedMaxStreak = sharedMax,
                streakRows = streakRows,
                frequencyRows = frequencyRows,
            ),
            pickerHabits = pickerHabits,
            spheresEnabled = spheresEnabled,
            showPicker = showPickerState,
        )
    }
}
