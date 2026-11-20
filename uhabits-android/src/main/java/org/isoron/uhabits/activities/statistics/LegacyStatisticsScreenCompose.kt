/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.isoron.uhabits.activities.statistics

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.isoron.platform.time.DayOfWeek
import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.R
import org.isoron.uhabits.compose.LocalUHabitComposeColors
import org.isoron.uhabits.compose.UHabitComposeColors
import org.isoron.uhabits.compose.components.UHabitCardDivider
import org.isoron.uhabits.compose.components.UHabitSectionCard
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.models.DayTier
import org.isoron.uhabits.core.models.DayTierScope
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.ui.screens.statistics.StatisticsDailyProgress
import org.isoron.uhabits.core.ui.screens.statistics.StatisticsFilterState
import org.isoron.uhabits.core.ui.screens.statistics.StatisticsGoalTypeFilter
import org.isoron.uhabits.core.ui.screens.statistics.StatisticsHabitResult
import org.isoron.uhabits.core.ui.screens.statistics.StatisticsHabitStatusFilter
import org.isoron.uhabits.core.ui.screens.statistics.formatStatisticsValue
import kotlin.math.abs

internal data class LegacyFocusRowUi(
    val containerId: ContainerId?,
    val name: String,
    val durationLabel: String
)

internal data class LegacyFocusSectionUi(
    val totalDurationLabel: String,
    val rows: List<LegacyFocusRowUi>
)

internal data class LegacyDayHabitRowUi(
    val habit: Habit,
    val name: String,
    val colorArgb: Int,
    val detailText: String,
    val percentText: String
)

internal data class LegacyTierBarRowUi(
    val tier: DayTier,
    val label: String,
    val colorArgb: Int,
    val valueText: String,
    val hasProgress: Boolean,
    val progressFraction: Float
)

internal data class LegacySphereRowUi(
    val blockId: Long?,
    val name: String,
    val colorArgb: Int,
    val valueText: String,
    val hasProgress: Boolean,
    val progressFraction: Float
)

internal data class LegacyHabitChangeRowUi(
    val habit: Habit,
    val name: String,
    val colorArgb: Int,
    val transitionText: String,
    val deltaText: String
)

internal data class LegacyHabitStabilityRowUi(
    val habit: Habit,
    val name: String,
    val colorArgb: Int,
    val scoreText: String,
    val progressFraction: Float
)

internal data class LegacyMonthCalendarSectionUi(
    val title: String,
    val comparisonText: String?,
    val monthStart: LocalDate,
    val monthEnd: LocalDate,
    val firstWeekday: DayOfWeek,
    val days: List<StatisticsDailyProgress>,
    val averageProgress: Double?
)

internal sealed class LegacyStatisticsSectionUi {
    data class Focus(val section: LegacyFocusSectionUi) : LegacyStatisticsSectionUi()

    data class DayOverview(
        val title: String,
        val overallPercentText: String?,
        val subtitle: String,
        val habits: List<LegacyDayHabitRowUi>
    ) : LegacyStatisticsSectionUi()

    data class TierBars(
        val title: String,
        val rows: List<LegacyTierBarRowUi>
    ) : LegacyStatisticsSectionUi()

    data class PercentageBar(
        val title: String,
        val comparisonText: String?,
        val subtitle: String,
        val items: List<LegacyPercentageBarItem>
    ) : LegacyStatisticsSectionUi()

    data class MonthCalendar(
        val calendar: LegacyMonthCalendarSectionUi
    ) : LegacyStatisticsSectionUi()

    data class WeekdayRhythm(
        val title: String,
        val items: List<LegacyWeekRhythmItem>
    ) : LegacyStatisticsSectionUi()

    data class AllTimeHistory(
        val title: String,
        val subtitle: String,
        val items: List<LegacyBucketItem>
    ) : LegacyStatisticsSectionUi()

    data class Spheres(
        val title: String,
        val rows: List<LegacySphereRowUi>
    ) : LegacyStatisticsSectionUi()

    data class HabitChanges(
        val title: String,
        val subtitle: String,
        val rows: List<LegacyHabitChangeRowUi>
    ) : LegacyStatisticsSectionUi()

    data class HabitStability(
        val title: String,
        val rows: List<LegacyHabitStabilityRowUi>
    ) : LegacyStatisticsSectionUi()
}

internal sealed class LegacyStatisticsBodyState {
    data object Loading : LegacyStatisticsBodyState()

    data class Empty(
        val noHabits: Boolean,
        val canResetFilters: Boolean,
        val focusSection: LegacyFocusSectionUi?
    ) : LegacyStatisticsBodyState()

    data class Report(
        val sections: List<LegacyStatisticsSectionUi>
    ) : LegacyStatisticsBodyState()
}

internal data class LegacyStatisticsPageSnapshot(
    val pageId: Long = 0L,
    val body: LegacyStatisticsBodyState = LegacyStatisticsBodyState.Loading,
    val transitionDirection: Int = 0
)

internal data class LegacyStatisticsHeaderState(
    val selectedTab: StatisticsFragment.ReportTab = StatisticsFragment.ReportTab.DAY,
    val dateRangeText: String = "",
    val showDateArrows: Boolean = true,
    val nextEnabled: Boolean = false,
    val dayTiersEnabled: Boolean = true,
    val selectedTierScope: DayTierScope = DayTierScope.MINIMUM,
    val activeFiltersCount: Int = 0,
    val activeFilterScopeText: String? = null,
    val isRefreshing: Boolean = false
)

internal fun resolveCompareInitialPeriod(tab: StatisticsFragment.ReportTab): String = when (tab) {
    StatisticsFragment.ReportTab.DAY, StatisticsFragment.ReportTab.WEEK -> "WEEK"
    StatisticsFragment.ReportTab.MONTH -> "MONTH"
    StatisticsFragment.ReportTab.YEAR -> "YEAR"
    StatisticsFragment.ReportTab.ALL -> "ALL"
}

internal fun buildLegacyActiveFilterSummary(
    filters: StatisticsFilterState,
    blockNamesById: Map<Long, String>,
    archivedLabel: String,
    allHabitsLabel: String,
    booleanOnlyLabel: String,
    numericalOnlyLabel: String
): Pair<Int, String?> {
    val activeFiltersCount = listOfNotNull(
        filters.sphereId,
        filters.habitStatus.takeIf { it != StatisticsHabitStatusFilter.ACTIVE },
        filters.goalType.takeIf { it != StatisticsGoalTypeFilter.ALL }
    ).size

    if (activeFiltersCount == 0) return 0 to null

    val labels = mutableListOf<String>()
    filters.sphereId?.let { id ->
        blockNamesById[id]?.let(labels::add)
    }
    when (filters.habitStatus) {
        StatisticsHabitStatusFilter.ARCHIVED -> labels.add(archivedLabel)
        StatisticsHabitStatusFilter.ALL -> labels.add(allHabitsLabel)
        StatisticsHabitStatusFilter.ACTIVE -> Unit
    }
    when (filters.goalType) {
        StatisticsGoalTypeFilter.YES_NO -> labels.add(booleanOnlyLabel)
        StatisticsGoalTypeFilter.NUMERICAL -> labels.add(numericalOnlyLabel)
        StatisticsGoalTypeFilter.ALL -> Unit
    }
    return activeFiltersCount to labels.joinToString(" · ")
}

internal fun formatLegacyFocusDuration(millis: Long): String {
    val seconds = millis / 1000
    return when {
        seconds >= 3600 -> "${seconds / 3600} ч ${seconds / 60 % 60} мин"
        seconds >= 60 -> "${seconds / 60} мин ${seconds % 60} с"
        else -> "$seconds с"
    }
}

internal fun formatLegacyHabitResult(
    result: StatisticsHabitResult,
    statusLabel: String?
): String {
    val actual = result.actual.formatStatisticsValue()
    val target = result.target.formatStatisticsValue()
    val unit = result.unit.trim()
    val values = if (unit.isEmpty()) "$actual / $target" else "$actual / $target $unit"
    return if (statusLabel == null) values else "$values · $statusLabel"
}

internal fun computeNextLegacyAnchorDate(
    currentAnchorDate: LocalDate,
    tab: StatisticsFragment.ReportTab,
    direction: Int,
    firstWeekday: DayOfWeek
): LocalDate? {
    if (direction > 0 && isLatestAllowedPeriod(currentAnchorDate, tab, firstWeekday)) {
        return null
    }
    val stepped = when (tab) {
        StatisticsFragment.ReportTab.DAY -> currentAnchorDate.plus(direction)
        StatisticsFragment.ReportTab.WEEK -> currentAnchorDate.plus(direction * 7)
        StatisticsFragment.ReportTab.MONTH -> {
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
        StatisticsFragment.ReportTab.YEAR -> {
            LocalDate(currentAnchorDate.year + direction, currentAnchorDate.month, currentAnchorDate.day)
        }
        StatisticsFragment.ReportTab.ALL -> currentAnchorDate
    }
    return clampAnchorDateToLatestAllowed(stepped, tab, firstWeekday)
}

internal fun isLegacyReducedMotion(context: Context): Boolean {
    return try {
        val durationScale = Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1.0f
        )
        val transitionScale = Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.TRANSITION_ANIMATION_SCALE,
            1.0f
        )
        durationScale == 0f || transitionScale == 0f
    } catch (_: Exception) {
        false
    }
}

@Composable
internal fun LegacyStatisticsScreen(
    header: LegacyStatisticsHeaderState,
    pageSnapshot: LegacyStatisticsPageSnapshot,
    applyTopStatusBarInset: Boolean,
    shortWeekdayName: (DayOfWeek) -> String,
    shortMonthName: (LocalDate) -> String,
    onSelectTab: (StatisticsFragment.ReportTab) -> Unit,
    onTabSwipe: (Int) -> Unit,
    onDateNavigate: (Int) -> Unit,
    onSelectTierScope: (DayTierScope) -> Unit,
    onOpenCompare: () -> Unit,
    onOpenFilters: () -> Unit,
    onResetFilters: () -> Unit,
    onOpenHabit: (Habit) -> Unit,
    onOpenContainerStatistics: (ContainerId) -> Unit,
    modifier: Modifier = Modifier,
    colors: UHabitComposeColors = LocalUHabitComposeColors.current
) {
    val topInsetModifier = if (applyTopStatusBarInset) {
        Modifier.windowInsetsPadding(
            WindowInsets.statusBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Top)
        )
    } else {
        Modifier
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(colors.backgroundColor)
            .then(topInsetModifier)
            .semantics { testTagsAsResourceId = true }
    ) {
        if (!constraints.hasBoundedWidth || !constraints.hasBoundedHeight) {
            return@BoxWithConstraints
        }
        Column(modifier = Modifier.fillMaxSize()) {
            LegacyPeriodTabRow(
                selectedTab = header.selectedTab,
                onSelectTab = onSelectTab,
                onTabSwipe = onTabSwipe,
                colors = colors
            )

            LegacyDateNavigationBar(
                dateRangeText = header.dateRangeText,
                showArrows = header.showDateArrows,
                nextEnabled = header.nextEnabled,
                onPrev = { onDateNavigate(-1) },
                onNext = { onDateNavigate(1) },
                colors = colors
            )

            LegacyFiltersBar(
                dayTiersEnabled = header.dayTiersEnabled,
                selectedTierScope = header.selectedTierScope,
                activeFiltersCount = header.activeFiltersCount,
                activeFilterScopeText = header.activeFilterScopeText,
                onSelectTierScope = onSelectTierScope,
                onOpenCompare = onOpenCompare,
                onOpenFilters = onOpenFilters,
                colors = colors
            )

            if (header.isRefreshing) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp),
                    color = colors.accentColor,
                    trackColor = colors.accentColor.copy(alpha = 0.16f)
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(colors.resolveDividerColor())
            )

            LegacyStatisticsPageHost(
                pageSnapshot = pageSnapshot,
                shortWeekdayName = shortWeekdayName,
                shortMonthName = shortMonthName,
                onDateNavigate = onDateNavigate,
                onResetFilters = onResetFilters,
                onOpenHabit = onOpenHabit,
                onOpenContainerStatistics = onOpenContainerStatistics,
                colors = colors,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
        }
    }
}

@Composable
private fun LegacyPeriodTabRow(
    selectedTab: StatisticsFragment.ReportTab,
    onSelectTab: (StatisticsFragment.ReportTab) -> Unit,
    onTabSwipe: (Int) -> Unit,
    colors: UHabitComposeColors
) {
    val density = LocalDensity.current
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val scrollable = density.fontScale > 1.35f
    val tabs = StatisticsFragment.ReportTab.entries

    val swipeModifier = Modifier.pointerInput(isRtl, onTabSwipe) {
        val thresholdPx = 40.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val startX = down.position.x
            val startY = down.position.y
            var lastX = startX
            var lastY = startY
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull() ?: break
                lastX = change.position.x
                lastY = change.position.y
                if (!change.pressed) break
            }
            val dx = lastX - startX
            val dy = lastY - startY
            if (abs(dx) >= thresholdPx && abs(dx) > abs(dy) * 1.4f) {
                val rawDir = if (dx < 0f) 1 else -1
                onTabSwipe(if (isRtl) -rawDir else rawDir)
            }
        }
    }

    val rowModifier = Modifier
        .fillMaxWidth()
        .background(colors.backgroundColor)
        .let { if (scrollable) it.horizontalScroll(rememberScrollState()) else it.then(swipeModifier) }
        .selectableGroup()

    Row(
        modifier = rowModifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        tabs.forEach { tab ->
            val selected = tab == selectedTab
            val label = stringResource(tabLabelRes(tab))
            val tabModifier = if (scrollable) {
                Modifier.widthIn(min = 72.dp)
            } else {
                Modifier.weight(1f)
            }
            Box(
                modifier = tabModifier
                    .heightIn(min = 48.dp)
                    .drawBehind {
                        if (selected) {
                            val indicatorHeight = 2.dp.toPx()
                            drawRect(
                                color = colors.accentColor,
                                topLeft = Offset(0f, size.height - indicatorHeight),
                                size = Size(size.width, indicatorHeight)
                            )
                        }
                    }
                    .selectable(
                        selected = selected,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Tab,
                        onClick = { onSelectTab(tab) }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    color = if (selected) colors.foregroundColor else colors.secondaryColor,
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 12.dp)
                )
            }
        }
    }
}

@Composable
private fun LegacyDateNavigationBar(
    dateRangeText: String,
    showArrows: Boolean,
    nextEnabled: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    colors: UHabitComposeColors
) {
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val headerSurface = if (colors.isPureBlack) colors.backgroundColor else colors.surfaceColor

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(headerSurface)
            .padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showArrows) {
            val prevInteraction = remember { MutableInteractionSource() }
            val prevPressed by prevInteraction.collectIsPressedAsState()
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .testTag("btnPrev")
                    .graphicsLayer { alpha = if (prevPressed) 0.62f else 1f }
                    .clickable(
                        interactionSource = prevInteraction,
                        indication = null,
                        role = Role.Button,
                        onClick = onPrev
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_up_dark),
                    contentDescription = stringResource(R.string.unified_statistics_prev_period),
                    tint = colors.foregroundColor,
                    modifier = Modifier
                        .size(24.dp)
                        .graphicsLayer { rotationZ = if (isRtl) 90f else -90f }
                )
            }
        } else {
            Spacer(modifier = Modifier.size(48.dp))
        }

        Text(
            text = dateRangeText,
            color = colors.foregroundColor,
            fontSize = 15.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .testTag("tvDateRange")
                .padding(horizontal = 4.dp)
        )

        if (showArrows) {
            val nextInteraction = remember { MutableInteractionSource() }
            val nextPressed by nextInteraction.collectIsPressedAsState()
            val nextAlpha = when {
                !nextEnabled -> 0.35f
                nextPressed -> 0.62f
                else -> 1f
            }
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .testTag("btnNext")
                    .graphicsLayer { alpha = nextAlpha }
                    .clickable(
                        enabled = nextEnabled,
                        interactionSource = nextInteraction,
                        indication = null,
                        role = Role.Button,
                        onClick = onNext
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_down_dark),
                    contentDescription = stringResource(R.string.unified_statistics_next_period),
                    tint = colors.foregroundColor,
                    modifier = Modifier
                        .size(24.dp)
                        .graphicsLayer { rotationZ = if (isRtl) 90f else -90f }
                )
            }
        } else {
            Spacer(modifier = Modifier.size(48.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LegacyFiltersBar(
    dayTiersEnabled: Boolean,
    selectedTierScope: DayTierScope,
    activeFiltersCount: Int,
    activeFilterScopeText: String?,
    onSelectTierScope: (DayTierScope) -> Unit,
    onOpenCompare: () -> Unit,
    onOpenFilters: () -> Unit,
    colors: UHabitComposeColors
) {
    val headerSurface = if (colors.isPureBlack) colors.backgroundColor else colors.surfaceColor
    var scopeMenuExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(headerSurface)
            .padding(horizontal = 16.dp)
            .padding(bottom = 4.dp)
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(2.dp),
            itemVerticalAlignment = Alignment.CenterVertically
        ) {
            if (dayTiersEnabled) {
                Box {
                    val scopeInteraction = remember { MutableInteractionSource() }
                    val scopePressed by scopeInteraction.collectIsPressedAsState()
                    val arrowRotation by animateFloatAsState(
                        targetValue = if (scopeMenuExpanded) 180f else 0f,
                        animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing),
                        label = "scopeArrowRotation"
                    )
                    Row(
                        modifier = Modifier
                            .heightIn(min = 44.dp)
                            .testTag("scopeSelectorContainer")
                            .graphicsLayer { alpha = if (scopePressed) 0.62f else 1f }
                            .clickable(
                                interactionSource = scopeInteraction,
                                indication = null,
                                role = Role.Button,
                                onClick = { scopeMenuExpanded = true }
                            )
                            .padding(end = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.statistics_scope_label),
                            color = colors.secondaryColor,
                            fontSize = 13.sp,
                            maxLines = 1
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(tierScopeLabelRes(selectedTierScope)),
                            color = colors.foregroundColor,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_drop_down_dark),
                            contentDescription = null,
                            tint = colors.secondaryColor,
                            modifier = Modifier
                                .size(18.dp)
                                .graphicsLayer { rotationZ = arrowRotation }
                        )
                    }

                    DropdownMenu(
                        expanded = scopeMenuExpanded,
                        onDismissRequest = { scopeMenuExpanded = false },
                        offset = DpOffset(0.dp, 2.dp),
                        shape = RoundedCornerShape(12.dp),
                        containerColor = colors.dialogSurfaceColor,
                        tonalElevation = 0.dp,
                        shadowElevation = 6.dp,
                        border = BorderStroke(1.dp, colors.dialogBorderColor),
                        modifier = Modifier.widthIn(min = 180.dp, max = 260.dp)
                    ) {
                        DayTierScope.entries.forEach { scope ->
                            val isSelected = scope == selectedTierScope
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = stringResource(tierScopeLabelRes(scope)),
                                            color = if (isSelected) colors.accentColor else colors.foregroundColor,
                                            fontSize = 14.sp,
                                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        if (isSelected) {
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Box(
                                                modifier = Modifier
                                                    .size(7.dp)
                                                    .clip(CircleShape)
                                                    .background(colors.accentColor)
                                            )
                                        }
                                    }
                                },
                                onClick = {
                                    scopeMenuExpanded = false
                                    onSelectTierScope(scope)
                                }
                            )
                        }
                    }
                }
            } else {
                Spacer(modifier = Modifier.width(1.dp))
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End
            ) {
                LegacyQuietTextActionButton(
                    label = stringResource(R.string.statistics_compare_action),
                    iconRes = R.drawable.ic_settings_report_analytics,
                    onClick = onOpenCompare,
                    colors = colors,
                    modifier = Modifier.testTag("btnCompare")
                )
                LegacyQuietTextActionButton(
                    label = if (activeFiltersCount > 0) {
                        stringResource(R.string.statistics_filter_count, activeFiltersCount)
                    } else {
                        stringResource(R.string.statistics_filters_button)
                    },
                    iconRes = R.drawable.ic_statistics_filter,
                    onClick = onOpenFilters,
                    colors = colors,
                    modifier = Modifier.testTag("btnFilters")
                )
            }
        }

        if (!activeFilterScopeText.isNullOrBlank()) {
            val scopeTextInteraction = remember { MutableInteractionSource() }
            val scopeTextPressed by scopeTextInteraction.collectIsPressedAsState()
            Text(
                text = activeFilterScopeText,
                color = colors.secondaryColor,
                fontSize = 11.5.sp,
                lineHeight = 16.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("filterScope")
                    .graphicsLayer { alpha = if (scopeTextPressed) 0.62f else 1f }
                    .clickable(
                        interactionSource = scopeTextInteraction,
                        indication = null,
                        role = Role.Button,
                        onClick = onOpenFilters
                    )
                    .padding(top = 2.dp, bottom = 4.dp)
                    .semantics { contentDescription = activeFilterScopeText }
            )
        }
    }
}

@Composable
private fun LegacyQuietTextActionButton(
    label: String,
    iconRes: Int,
    onClick: () -> Unit,
    colors: UHabitComposeColors,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    Row(
        modifier = modifier
            .heightIn(min = 44.dp)
            .graphicsLayer { alpha = if (pressed) 0.62f else 1f }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick
            )
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = colors.accentColor,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            color = colors.accentColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun LegacyStatisticsPageHost(
    pageSnapshot: LegacyStatisticsPageSnapshot,
    shortWeekdayName: (DayOfWeek) -> String,
    shortMonthName: (LocalDate) -> String,
    onDateNavigate: (Int) -> Unit,
    onResetFilters: () -> Unit,
    onOpenHabit: (Habit) -> Unit,
    onOpenContainerStatistics: (ContainerId) -> Unit,
    colors: UHabitComposeColors,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val reducedMotion = remember(context) { isLegacyReducedMotion(context) }

    val pageSwipeModifier = Modifier.pointerInput(isRtl, onDateNavigate) {
        val touchSlopPx = viewConfiguration.touchSlop
        val swipeThresholdPx = maxOf(48.dp.toPx(), touchSlopPx * 3.5f)
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (down.isConsumed) return@awaitEachGesture
            val startX = down.position.x
            val startY = down.position.y
            var lastX = startX
            var lastY = startY
            var consumedByChild = false
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull() ?: break
                if (change.isConsumed) {
                    consumedByChild = true
                }
                lastX = change.position.x
                lastY = change.position.y
                if (!change.pressed) break
            }
            if (consumedByChild) return@awaitEachGesture
            val dx = lastX - startX
            val dy = lastY - startY
            if (abs(dx) >= swipeThresholdPx && abs(dx) > abs(dy) * 1.6f) {
                val rawDir = if (dx < 0f) 1 else -1
                onDateNavigate(if (isRtl) -rawDir else rawDir)
            }
        }
    }

    Box(modifier = modifier.then(pageSwipeModifier)) {
        AnimatedContent(
            targetState = pageSnapshot,
            contentKey = { it.pageId },
            transitionSpec = {
                val dir = targetState.transitionDirection
                val isInitial = initialState.pageId == 0L && initialState.body is LegacyStatisticsBodyState.Loading
                if (reducedMotion || isInitial) {
                    EnterTransition.None togetherWith ExitTransition.None
                } else if (dir == 0) {
                    fadeIn(tween(180, easing = FastOutSlowInEasing)) togetherWith
                        fadeOut(tween(180, easing = FastOutSlowInEasing))
                } else {
                    val effectiveDir = if (isRtl) -dir else dir
                    val spec = tween<androidx.compose.ui.unit.IntOffset>(210, easing = FastOutSlowInEasing)
                    if (effectiveDir > 0) {
                        slideInHorizontally(animationSpec = spec) { fullWidth -> fullWidth } togetherWith
                            slideOutHorizontally(animationSpec = spec) { fullWidth -> -fullWidth }
                    } else {
                        slideInHorizontally(animationSpec = spec) { fullWidth -> -fullWidth } togetherWith
                            slideOutHorizontally(animationSpec = spec) { fullWidth -> fullWidth }
                    }
                }
            },
            label = "legacyStatisticsPageTransition"
        ) { snapshot ->
            LegacyStatisticsPageContent(
                snapshot = snapshot,
                shortWeekdayName = shortWeekdayName,
                shortMonthName = shortMonthName,
                onResetFilters = onResetFilters,
                onOpenHabit = onOpenHabit,
                onOpenContainerStatistics = onOpenContainerStatistics,
                colors = colors
            )
        }
    }
}

@Composable
private fun LegacyStatisticsPageContent(
    snapshot: LegacyStatisticsPageSnapshot,
    shortWeekdayName: (DayOfWeek) -> String,
    shortMonthName: (LocalDate) -> String,
    onResetFilters: () -> Unit,
    onOpenHabit: (Habit) -> Unit,
    onOpenContainerStatistics: (ContainerId) -> Unit,
    colors: UHabitComposeColors
) {
    val horizontalPad = if (colors.isClassicSectionLayout) 0.dp else 16.dp
    val topPad = if (colors.isClassicSectionLayout) 0.dp else 8.dp
    val itemSpacing = if (colors.isClassicSectionLayout) 0.dp else 8.dp

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = horizontalPad, top = topPad, end = horizontalPad, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(itemSpacing)
    ) {
        when (val body = snapshot.body) {
            LegacyStatisticsBodyState.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 48.dp, start = 16.dp, end = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.reports_loading),
                        color = colors.secondaryColor,
                        fontSize = 15.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }

            is LegacyStatisticsBodyState.Empty -> {
                body.focusSection?.let { focus ->
                    LegacyFocusSectionCard(
                        section = focus,
                        onOpenContainerStatistics = onOpenContainerStatistics,
                        colors = colors
                    )
                }
                LegacyNoDataBlock(
                    noHabits = body.noHabits,
                    canResetFilters = body.canResetFilters,
                    onResetFilters = onResetFilters,
                    colors = colors
                )
            }

            is LegacyStatisticsBodyState.Report -> {
                body.sections.forEach { section ->
                    when (section) {
                        is LegacyStatisticsSectionUi.Focus -> LegacyFocusSectionCard(
                            section = section.section,
                            onOpenContainerStatistics = onOpenContainerStatistics,
                            colors = colors
                        )

                        is LegacyStatisticsSectionUi.DayOverview -> LegacyDayOverviewSectionCard(
                            section = section,
                            onOpenHabit = onOpenHabit,
                            colors = colors
                        )

                        is LegacyStatisticsSectionUi.TierBars -> LegacyTierBarsSectionCard(
                            section = section,
                            colors = colors
                        )

                        is LegacyStatisticsSectionUi.PercentageBar -> LegacyPercentageBarSectionCard(
                            section = section,
                            colors = colors
                        )

                        is LegacyStatisticsSectionUi.MonthCalendar -> LegacyMonthCalendarSectionCard(
                            section = section.calendar,
                            shortWeekdayName = shortWeekdayName,
                            shortMonthName = shortMonthName,
                            colors = colors
                        )

                        is LegacyStatisticsSectionUi.WeekdayRhythm -> LegacyWeekdayRhythmSectionCard(
                            section = section,
                            colors = colors
                        )

                        is LegacyStatisticsSectionUi.AllTimeHistory -> LegacyAllTimeHistorySectionCard(
                            section = section,
                            colors = colors
                        )

                        is LegacyStatisticsSectionUi.Spheres -> LegacySpheresSectionCard(
                            pageId = snapshot.pageId,
                            section = section,
                            colors = colors
                        )

                        is LegacyStatisticsSectionUi.HabitChanges -> LegacyHabitChangesSectionCard(
                            section = section,
                            onOpenHabit = onOpenHabit,
                            colors = colors
                        )

                        is LegacyStatisticsSectionUi.HabitStability -> LegacyHabitStabilitySectionCard(
                            pageId = snapshot.pageId,
                            section = section,
                            onOpenHabit = onOpenHabit,
                            colors = colors
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LegacyReportSectionCard(
    title: String?,
    secondaryText: String? = null,
    colors: UHabitComposeColors = LocalUHabitComposeColors.current,
    content: @Composable ColumnScope.() -> Unit
) {
    UHabitSectionCard(
        colors = colors,
        cornerRadiusDp = colors.statisticsCardCornerRadiusDp,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
    ) {
        if (title != null || secondaryText != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (title != null) {
                    Text(
                        text = title,
                        color = colors.foregroundColor,
                        fontSize = 14.sp,
                        lineHeight = 19.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .weight(1f)
                            .semantics { heading() }
                    )
                }
                if (secondaryText != null) {
                    Text(
                        text = secondaryText,
                        style = TextStyle(
                            color = colors.secondaryColor,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            textAlign = TextAlign.End,
                            fontFeatureSettings = "tnum"
                        ),
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
        }
        content()
    }
}

@Composable
private fun LegacyNoDataBlock(
    noHabits: Boolean,
    canResetFilters: Boolean,
    onResetFilters: () -> Unit,
    colors: UHabitComposeColors
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(
                if (noHabits) R.string.statistics_no_habits_for_filters
                else R.string.statistics_no_history_for_period
            ),
            color = colors.secondaryColor,
            fontSize = 15.sp,
            textAlign = TextAlign.Center
        )
        if (noHabits && canResetFilters) {
            Spacer(modifier = Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clickable(role = Role.Button, onClick = onResetFilters)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.statistics_reset_filters),
                    color = colors.accentColor,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun LegacyFocusSectionCard(
    section: LegacyFocusSectionUi,
    onOpenContainerStatistics: (ContainerId) -> Unit,
    colors: UHabitComposeColors
) {
    LegacyReportSectionCard(
        title = "Фокус по разделам",
        colors = colors
    ) {
        Text(
            text = "Сессии, завершённые за период · все задачи и привычки",
            color = colors.secondaryColor,
            fontSize = 12.sp,
            lineHeight = 16.sp
        )
        Text(
            text = "Всего: ${section.totalDurationLabel}",
            style = TextStyle(
                color = colors.foregroundColor,
                fontSize = 18.sp,
                lineHeight = 24.sp,
                fontFeatureSettings = "tnum"
            ),
            modifier = Modifier.padding(vertical = 8.dp)
        )
        if (section.rows.isEmpty()) {
            Text(
                text = "За этот период ещё нет завершённых сессий",
                color = colors.secondaryColor,
                fontSize = 14.sp
            )
        } else {
            section.rows.forEach { row ->
                val clickableModifier = if (row.containerId != null) {
                    Modifier.clickable(role = Role.Button) {
                        onOpenContainerStatistics(row.containerId)
                    }
                } else {
                    Modifier
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .then(clickableModifier),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(
                        text = "${row.name} · ${row.durationLabel}",
                        style = TextStyle(
                            color = colors.foregroundColor,
                            fontSize = 16.sp,
                            lineHeight = 22.sp,
                            fontFeatureSettings = "tnum"
                        )
                    )
                }
            }
        }
        Text(
            text = "Раздел на момент сессии. Время дочерних разделов показано отдельно.",
            color = colors.secondaryColor,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

@Composable
private fun LegacyDayOverviewSectionCard(
    section: LegacyStatisticsSectionUi.DayOverview,
    onOpenHabit: (Habit) -> Unit,
    colors: UHabitComposeColors
) {
    LegacyReportSectionCard(
        title = section.title,
        secondaryText = section.overallPercentText,
        colors = colors
    ) {
        Text(
            text = section.subtitle,
            color = colors.secondaryColor,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            modifier = Modifier.padding(bottom = 2.dp)
        )
        section.habits.forEachIndexed { index, row ->
            if (index > 0) {
                UHabitCardDivider(colors = colors, verticalPadding = 0.dp)
            }
            val interactionSource = remember { MutableInteractionSource() }
            val pressed by interactionSource.collectIsPressedAsState()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .graphicsLayer { alpha = if (pressed) 0.62f else 1f }
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        role = Role.Button,
                        onClick = { onOpenHabit(row.habit) }
                    )
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Color(row.colorArgb))
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = row.name,
                        color = colors.foregroundColor,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = row.detailText,
                        style = TextStyle(
                            color = colors.secondaryColor,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            fontFeatureSettings = "tnum"
                        ),
                        modifier = Modifier.padding(top = 1.dp)
                    )
                }
                Text(
                    text = row.percentText,
                    style = TextStyle(
                        color = colors.foregroundColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.End,
                        fontFeatureSettings = "tnum"
                    ),
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun LegacyTierBarsSectionCard(
    section: LegacyStatisticsSectionUi.TierBars,
    colors: UHabitComposeColors
) {
    LegacyReportSectionCard(
        title = section.title,
        colors = colors
    ) {
        section.rows.forEachIndexed { index, row ->
            LegacyProgressRow(
                dotColor = Color(row.colorArgb),
                label = row.label,
                valueText = row.valueText,
                valueEmphasized = row.hasProgress,
                progressFraction = row.progressFraction,
                topPadding = if (index == 0) 0.dp else 6.dp,
                bottomPadding = if (index == section.rows.lastIndex) 0.dp else 4.dp,
                colors = colors
            )
        }
    }
}

@Composable
private fun LegacyPercentageBarSectionCard(
    section: LegacyStatisticsSectionUi.PercentageBar,
    colors: UHabitComposeColors
) {
    LegacyReportSectionCard(
        title = section.title,
        secondaryText = section.comparisonText,
        colors = colors
    ) {
        Text(
            text = section.subtitle,
            color = colors.secondaryColor,
            fontSize = 11.sp,
            lineHeight = 15.sp
        )
        LegacyPercentageBarChart(
            items = section.items,
            colors = colors
        )
    }
}

@Composable
private fun LegacyMonthCalendarSectionCard(
    section: LegacyMonthCalendarSectionUi,
    shortWeekdayName: (DayOfWeek) -> String,
    shortMonthName: (LocalDate) -> String,
    colors: UHabitComposeColors
) {
    LegacyReportSectionCard(
        title = section.title,
        secondaryText = section.comparisonText,
        colors = colors
    ) {
        LegacyMonthCalendarChart(
            monthStart = section.monthStart,
            monthEnd = section.monthEnd,
            firstWeekday = section.firstWeekday,
            days = section.days,
            averageProgress = section.averageProgress,
            shortWeekdayName = shortWeekdayName,
            shortMonthName = shortMonthName,
            colors = colors
        )
    }
}

@Composable
private fun LegacyWeekdayRhythmSectionCard(
    section: LegacyStatisticsSectionUi.WeekdayRhythm,
    colors: UHabitComposeColors
) {
    LegacyReportSectionCard(
        title = section.title,
        colors = colors
    ) {
        LegacyWeekRhythmChart(
            items = section.items,
            colors = colors
        )
    }
}

@Composable
private fun LegacyAllTimeHistorySectionCard(
    section: LegacyStatisticsSectionUi.AllTimeHistory,
    colors: UHabitComposeColors
) {
    LegacyReportSectionCard(
        title = section.title,
        colors = colors
    ) {
        Text(
            text = section.subtitle,
            color = colors.secondaryColor,
            fontSize = 11.sp,
            lineHeight = 15.sp
        )
        LegacyBucketHistoryChart(
            items = section.items,
            colors = colors
        )
    }
}

@Composable
private fun LegacySpheresSectionCard(
    pageId: Long,
    section: LegacyStatisticsSectionUi.Spheres,
    colors: UHabitComposeColors
) {
    val maxInitial = 5
    var expanded by remember(pageId) { mutableStateOf(false) }
    val remainingCount = (section.rows.size - maxInitial).coerceAtLeast(0)
    val visibleRows = if (expanded || remainingCount == 0) section.rows else section.rows.take(maxInitial)

    LegacyReportSectionCard(
        title = section.title,
        colors = colors
    ) {
        visibleRows.forEachIndexed { index, row ->
            LegacyProgressRow(
                dotColor = Color(row.colorArgb),
                label = row.name,
                valueText = row.valueText,
                valueEmphasized = row.hasProgress,
                progressFraction = row.progressFraction,
                topPadding = if (index == 0) 0.dp else 6.dp,
                bottomPadding = if (index == visibleRows.lastIndex) 0.dp else 4.dp,
                colors = colors
            )
        }
        if (remainingCount > 0) {
            LegacyExpandToggleRow(
                expanded = expanded,
                remainingCount = remainingCount,
                onToggle = { expanded = !expanded },
                colors = colors
            )
        }
    }
}

@Composable
private fun LegacyHabitChangesSectionCard(
    section: LegacyStatisticsSectionUi.HabitChanges,
    onOpenHabit: (Habit) -> Unit,
    colors: UHabitComposeColors
) {
    LegacyReportSectionCard(
        title = section.title,
        colors = colors
    ) {
        Text(
            text = section.subtitle,
            color = colors.secondaryColor,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        section.rows.forEach { item ->
            val interactionSource = remember { MutableInteractionSource() }
            val pressed by interactionSource.collectIsPressedAsState()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .graphicsLayer { alpha = if (pressed) 0.62f else 1f }
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        role = Role.Button,
                        onClick = { onOpenHabit(item.habit) }
                    )
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "●",
                        color = Color(item.colorArgb),
                        fontSize = 12.sp
                    )
                    Text(
                        text = item.name,
                        color = colors.foregroundColor,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = item.transitionText,
                        style = TextStyle(
                            color = colors.secondaryColor,
                            fontSize = 12.sp,
                            fontFeatureSettings = "tnum"
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = item.deltaText,
                        style = TextStyle(
                            color = colors.secondaryColor,
                            fontSize = 12.sp,
                            fontFeatureSettings = "tnum"
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun LegacyHabitStabilitySectionCard(
    pageId: Long,
    section: LegacyStatisticsSectionUi.HabitStability,
    onOpenHabit: (Habit) -> Unit,
    colors: UHabitComposeColors
) {
    val maxInitial = 5
    var expanded by remember(pageId) { mutableStateOf(false) }
    val remainingCount = (section.rows.size - maxInitial).coerceAtLeast(0)
    val visibleRows = if (expanded || remainingCount == 0) section.rows else section.rows.take(maxInitial)

    LegacyReportSectionCard(
        title = section.title,
        colors = colors
    ) {
        visibleRows.forEachIndexed { index, item ->
            if (index > 0) {
                UHabitCardDivider(colors = colors, verticalPadding = 0.dp)
            }
            val interactionSource = remember { MutableInteractionSource() }
            val pressed by interactionSource.collectIsPressedAsState()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .graphicsLayer { alpha = if (pressed) 0.62f else 1f }
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        role = Role.Button,
                        onClick = { onOpenHabit(item.habit) }
                    )
                    .padding(vertical = 6.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Color(item.colorArgb))
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = item.name,
                        color = colors.foregroundColor,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = item.scoreText,
                        style = TextStyle(
                            color = colors.foregroundColor,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            fontFeatureSettings = "tnum"
                        ),
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                LegacyLinearProgressBar(
                    fraction = item.progressFraction,
                    fillColor = Color(item.colorArgb),
                    colors = colors
                )
            }
        }
        if (remainingCount > 0) {
            LegacyExpandToggleRow(
                expanded = expanded,
                remainingCount = remainingCount,
                onToggle = { expanded = !expanded },
                colors = colors
            )
        }
    }
}

@Composable
private fun LegacyProgressRow(
    dotColor: Color,
    label: String,
    valueText: String,
    valueEmphasized: Boolean,
    progressFraction: Float,
    topPadding: androidx.compose.ui.unit.Dp,
    bottomPadding: androidx.compose.ui.unit.Dp,
    colors: UHabitComposeColors
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = topPadding, bottom = bottomPadding)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                color = colors.secondaryColor,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = valueText,
                style = TextStyle(
                    color = if (valueEmphasized) colors.foregroundColor else colors.secondaryColor,
                    fontSize = 13.sp,
                    fontWeight = if (valueEmphasized) FontWeight.Bold else FontWeight.Normal,
                    fontFeatureSettings = "tnum"
                ),
                modifier = Modifier.padding(start = 8.dp)
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        LegacyLinearProgressBar(
            fraction = progressFraction,
            fillColor = dotColor,
            colors = colors
        )
    }
}

@Composable
private fun LegacyLinearProgressBar(
    fraction: Float,
    fillColor: Color,
    colors: UHabitComposeColors
) {
    val clamped = fraction.coerceIn(0f, 1f)
    val trackColor = lerp(colors.resolveSectionSurfaceColor(), colors.secondaryColor, 0.14f)
    val barShape = RoundedCornerShape(2.dp)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(barShape)
            .background(trackColor)
    ) {
        if (clamped > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(clamped)
                    .fillMaxHeight()
                    .clip(barShape)
                    .background(fillColor)
            )
        }
    }
}

@Composable
private fun LegacyExpandToggleRow(
    expanded: Boolean,
    remainingCount: Int,
    onToggle: () -> Unit,
    colors: UHabitComposeColors
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .graphicsLayer { alpha = if (pressed) 0.62f else 1f }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onToggle
            ),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = if (expanded) {
                stringResource(R.string.statistics_show_less_habits)
            } else {
                stringResource(R.string.statistics_show_more_habits, remainingCount)
            },
            color = colors.accentColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

private fun tabLabelRes(tab: StatisticsFragment.ReportTab): Int = when (tab) {
    StatisticsFragment.ReportTab.DAY -> R.string.reports_tab_day
    StatisticsFragment.ReportTab.WEEK -> R.string.reports_tab_week
    StatisticsFragment.ReportTab.MONTH -> R.string.reports_tab_month
    StatisticsFragment.ReportTab.YEAR -> R.string.reports_tab_year
    StatisticsFragment.ReportTab.ALL -> R.string.reports_tab_all
}

private fun tierScopeLabelRes(scope: DayTierScope): Int = when (scope) {
    DayTierScope.MINIMUM -> R.string.tier_scope_minimum
    DayTierScope.UP_TO_NORMAL -> R.string.tier_scope_up_to_normal
    DayTierScope.UP_TO_IDEAL -> R.string.tier_scope_up_to_ideal
    DayTierScope.ALL -> R.string.tier_scope_all
}
