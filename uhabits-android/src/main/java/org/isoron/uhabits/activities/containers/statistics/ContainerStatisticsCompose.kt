/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.activities.containers.statistics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.isoron.platform.time.DayOfWeek
import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.R
import org.isoron.uhabits.compose.LocalUHabitComposeColors
import org.isoron.uhabits.compose.UHabitComposeColors
import org.isoron.uhabits.compose.UHabitComposeTheme
import org.isoron.uhabits.compose.components.UHabitCardDivider
import org.isoron.uhabits.compose.components.UHabitSectionCard
import org.isoron.uhabits.core.containers.statistics.ContainerScopeMode
import org.isoron.uhabits.core.containers.statistics.ContainerStatsRange
import org.isoron.uhabits.core.models.projection.HabitGoalStatus
import java.util.Locale

internal enum class ContainerRangeType { DAY, WEEK, MONTH }

internal data class ContainerDailyBarUiModel(
    val date: LocalDate,
    val label: String,
    val durationMs: Long,
    val heightRatio: Float,
    val formattedDuration: String,
)

internal data class ContainerChildBreakdownUiModel(
    val id: String,
    val name: String,
    val durationText: String,
)

internal data class ContainerHabitRowUiModel(
    val uuid: String,
    val name: String,
    val colorArgb: Int,
    val goalStatus: HabitGoalStatus,
    val progressText: String,
    val focusContributionText: String?,
)

internal data class ContainerStatisticsUiState(
    val title: String,
    val subtitle: String,
    val rangeType: ContainerRangeType,
    val scopeMode: ContainerScopeMode,
    val dateRangeLabel: String,
    val serviceUnavailable: Boolean,
    val canOpenUnified: Boolean,
    val focusIsEmpty: Boolean,
    val focusTotalDuration: String,
    val focusSessionCountText: String,
    val focusSubtreeBreakdownText: String?,
    val focusDailyBars: List<ContainerDailyBarUiModel>,
    val focusChildRows: List<ContainerChildBreakdownUiModel>,
    val tasksCompletedCount: Int,
    val tasksPendingCount: Int,
    val tasksOverdueCount: Int,
    val tasksIsEmpty: Boolean,
    val habitsActiveCountText: String,
    val habitsReachedChipText: String,
    val habitsIncompleteChipText: String,
    val habitsUnknownChipText: String,
    val habitRows: List<ContainerHabitRowUiModel>,
    val habitsIsEmpty: Boolean,
)

/**
 * Pure deterministic formatting and range-stepping helpers for [ContainerStatisticsActivity].
 */
internal object ContainerStatisticsLogic {

    fun computeRange(rangeType: ContainerRangeType, anchorDate: LocalDate): ContainerStatsRange =
        when (rangeType) {
            ContainerRangeType.DAY -> ContainerStatsRange.day(anchorDate)
            ContainerRangeType.WEEK -> ContainerStatsRange.week(anchorDate, DayOfWeek.MONDAY)
            ContainerRangeType.MONTH -> ContainerStatsRange.month(anchorDate)
        }

    fun stepAnchor(rangeType: ContainerRangeType, anchorDate: LocalDate, delta: Int): LocalDate =
        when (rangeType) {
            ContainerRangeType.DAY -> anchorDate.plus(delta)
            ContainerRangeType.WEEK -> anchorDate.plus(delta * 7)
            ContainerRangeType.MONTH -> {
                val totalMonths = anchorDate.year * 12 + (anchorDate.month - 1) + delta
                val year = Math.floorDiv(totalMonths, 12)
                val month = Math.floorMod(totalMonths, 12) + 1
                LocalDate(year, month, 1)
            }
        }

    fun computeBarHeightRatio(durationMs: Long, maxDurationMs: Long): Float {
        if (durationMs <= 0L) return 0f
        val safeMax = maxDurationMs.coerceAtLeast(1L)
        return (durationMs.toFloat() / safeMax.toFloat()).coerceIn(0.05f, 1f)
    }

    fun formatNumber(value: Double, locale: Locale = Locale.getDefault()): String =
        if (value % 1.0 == 0.0) {
            value.toLong().toString()
        } else {
            String.format(locale, "%.1f", value)
        }

    fun formatDuration(millis: Long, isRussian: Boolean = Locale.getDefault().language == "ru"): String {
        val totalMinutes = millis / 60000L
        val hours = totalMinutes / 60L
        val minutes = totalMinutes % 60L
        val hUnit = if (isRussian) "ч" else "h"
        val mUnit = if (isRussian) "мин" else "m"
        return if (hours > 0L) {
            if (minutes > 0L) "$hours $hUnit $minutes $mUnit" else "$hours $hUnit"
        } else {
            "$minutes $mUnit"
        }
    }

    fun formatDayLabel(
        date: LocalDate,
        bucketCount: Int = 7,
        isRussian: Boolean = Locale.getDefault().language == "ru",
    ): String {
        if (bucketCount > 7) return date.day.toString()
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

    fun formatDateRange(
        range: ContainerStatsRange,
        isRussian: Boolean = Locale.getDefault().language == "ru",
    ): String = when {
        range.from == range.to ->
            "${range.from.day} ${monthName(range.from.month, isRussian)} ${range.from.year}"
        range.from.year == range.to.year ->
            "${range.from.day} ${monthName(range.from.month, isRussian)} — ${range.to.day} ${monthName(range.to.month, isRussian)} ${range.to.year}"
        else ->
            "${range.from.day} ${monthName(range.from.month, isRussian)} ${range.from.year} — ${range.to.day} ${monthName(range.to.month, isRussian)} ${range.to.year}"
    }

    fun monthName(month: Int, isRussian: Boolean = Locale.getDefault().language == "ru"): String =
        when (month) {
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

@Composable
internal fun ContainerStatisticsScreen(
    state: ContainerStatisticsUiState,
    colors: UHabitComposeColors,
    onBack: () -> Unit,
    onSelectRange: (ContainerRangeType) -> Unit,
    onSelectScope: (ContainerScopeMode) -> Unit,
    onPrevRange: () -> Unit,
    onNextRange: () -> Unit,
    onOpenUnifiedStatistics: () -> Unit,
) {
    UHabitComposeTheme(colors = colors) {
        val canvasColor = if (colors.isClassicSectionLayout) {
            if (colors.isPureBlack) colors.backgroundColor else colors.surfaceColor
        } else {
            colors.backgroundColor
        }
        val isBorderedCards = !colors.isClassicSectionLayout && colors.showCardBorders
        val horizontalPadding = if (isBorderedCards) 16.dp else 0.dp
        val cardGap = if (isBorderedCards) 12.dp else 0.dp

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(canvasColor)
                .semantics { testTagsAsResourceId = true }
                .testTag("container_statistics_root"),
        ) {
            ContainerStatisticsTopBar(
                title = state.title,
                subtitle = state.subtitle,
                canOpenUnified = state.canOpenUnified,
                colors = colors,
                onBack = onBack,
                onOpenUnifiedStatistics = onOpenUnifiedStatistics,
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding(),
                contentAlignment = Alignment.TopCenter,
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 640.dp)
                        .fillMaxWidth()
                        .padding(
                            start = horizontalPadding,
                            end = horizontalPadding,
                            top = if (isBorderedCards) 8.dp else 0.dp,
                            bottom = 32.dp,
                        ),
                    verticalArrangement = Arrangement.spacedBy(cardGap),
                ) {
                    ContainerFiltersCard(
                        state = state,
                        colors = colors,
                        onSelectRange = onSelectRange,
                        onSelectScope = onSelectScope,
                        onPrevRange = onPrevRange,
                        onNextRange = onNextRange,
                    )

                    if (!state.serviceUnavailable) {
                        ContainerFocusCard(state = state, colors = colors)
                        ContainerTasksCard(state = state, colors = colors)
                        ContainerHabitsCard(state = state, colors = colors)
                    }
                }
            }
        }
    }
}

@Composable
private fun ContainerStatisticsTopBar(
    title: String,
    subtitle: String,
    canOpenUnified: Boolean,
    colors: UHabitComposeColors,
    onBack: () -> Unit,
    onOpenUnifiedStatistics: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val barBg = if (colors.isClassicSectionLayout) {
        if (colors.isPureBlack) colors.backgroundColor else colors.surfaceColor
    } else {
        colors.backgroundColor
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(barBg)
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 56.dp)
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable(
                        role = Role.Button,
                        onClickLabel = stringResource(R.string.section_back),
                        onClick = onBack,
                    )
                    .testTag("container_stats_back"),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_settings_back),
                    contentDescription = stringResource(R.string.section_back),
                    colorFilter = ColorFilter.tint(colors.foregroundColor),
                    modifier = Modifier.size(24.dp),
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
            ) {
                BasicText(
                    text = title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(
                        color = colors.foregroundColor,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    modifier = Modifier.semantics { heading() },
                )
                if (subtitle.isNotBlank()) {
                    BasicText(
                        text = subtitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(
                            color = colors.secondaryColor,
                            fontSize = 12.sp,
                        ),
                    )
                }
            }

            if (canOpenUnified) {
                Box {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .clickable(
                                role = Role.Button,
                                onClickLabel = stringResource(R.string.container_statistics_action_open_unified),
                                onClick = { menuExpanded = true },
                            )
                            .testTag("container_stats_overflow"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            painter = painterResource(R.drawable.ic_toolbar_more_vertical),
                            contentDescription = stringResource(R.string.container_statistics_action_open_unified),
                            colorFilter = ColorFilter.tint(colors.foregroundColor),
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                        containerColor = colors.dialogSurfaceColor,
                    ) {
                        DropdownMenuItem(
                            text = {
                                BasicText(
                                    text = stringResource(R.string.container_statistics_action_open_unified),
                                    style = TextStyle(
                                        color = colors.foregroundColor,
                                        fontSize = 14.sp,
                                    ),
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onOpenUnifiedStatistics()
                            },
                        )
                    }
                }
            }
        }
        if (colors.isClassicSectionLayout) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(colors.resolveDividerColor()),
            )
        }
    }
}

@Composable
private fun ContainerFiltersCard(
    state: ContainerStatisticsUiState,
    colors: UHabitComposeColors,
    onSelectRange: (ContainerRangeType) -> Unit,
    onSelectScope: (ContainerScopeMode) -> Unit,
    onPrevRange: () -> Unit,
    onNextRange: () -> Unit,
) {
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val onAccentColor = MaterialTheme.colorScheme.onPrimary

    UHabitSectionCard(
        modifier = Modifier.testTag("cardFilters"),
        colors = colors,
        cornerRadiusDp = colors.statisticsCardCornerRadiusDp,
        contentPadding = PaddingValues(12.dp),
    ) {
        // Range selector (Day, Week, Month)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val rangeTabs = listOf(
                Triple(ContainerRangeType.DAY, R.string.container_statistics_range_day, "btnRangeDay"),
                Triple(ContainerRangeType.WEEK, R.string.container_statistics_range_week, "btnRangeWeek"),
                Triple(ContainerRangeType.MONTH, R.string.container_statistics_range_month, "btnRangeMonth"),
            )
            rangeTabs.forEach { (type, labelRes, tag) ->
                val isSelected = state.rangeType == type
                val label = stringResource(labelRes)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) colors.accentColor else Color.Transparent)
                        .semantics {
                            role = Role.Tab
                            selected = isSelected
                        }
                        .clickable { onSelectRange(type) }
                        .padding(horizontal = 6.dp, vertical = 8.dp)
                        .testTag(tag),
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(
                        text = label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(
                            color = if (isSelected) onAccentColor else colors.secondaryColor,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                        ),
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Scope selector (Subtree vs This container)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val scopeTabs = listOf(
                Triple(ContainerScopeMode.SUBTREE, R.string.container_statistics_scope_subtree, "btnScopeSubtree"),
                Triple(ContainerScopeMode.HERE, R.string.container_statistics_scope_here, "btnScopeHere"),
            )
            scopeTabs.forEach { (mode, labelRes, tag) ->
                val isSelected = state.scopeMode == mode
                val shape = RoundedCornerShape(8.dp)
                val label = stringResource(labelRes)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 48.dp)
                        .clip(shape)
                        .background(if (isSelected) colors.surfaceVariantColor else Color.Transparent)
                        .let { mod ->
                            if (isSelected) {
                                mod.border(1.dp, colors.accentColor, shape)
                            } else {
                                mod
                            }
                        }
                        .semantics {
                            role = Role.Tab
                            selected = isSelected
                        }
                        .clickable { onSelectScope(mode) }
                        .padding(horizontal = 8.dp, vertical = 8.dp)
                        .testTag(tag),
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(
                        text = label,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(
                            color = if (isSelected) colors.accentColor else colors.secondaryColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                        ),
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Date range stepper (< range label >)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable(
                        role = Role.Button,
                        onClickLabel = stringResource(R.string.unified_statistics_prev_period),
                        onClick = onPrevRange,
                    )
                    .testTag("btnPrevRange"),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_chevron_left),
                    contentDescription = stringResource(R.string.unified_statistics_prev_period),
                    colorFilter = ColorFilter.tint(colors.foregroundColor),
                    modifier = Modifier
                        .size(22.dp)
                        .graphicsLayer { scaleX = if (isRtl) -1f else 1f },
                )
            }

            BasicText(
                text = state.dateRangeLabel,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(
                    color = colors.foregroundColor,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                ),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp)
                    .testTag("tvDateRangeLabel"),
            )

            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable(
                        role = Role.Button,
                        onClickLabel = stringResource(R.string.unified_statistics_next_period),
                        onClick = onNextRange,
                    )
                    .testTag("btnNextRange"),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_chevron_right),
                    contentDescription = stringResource(R.string.unified_statistics_next_period),
                    colorFilter = ColorFilter.tint(colors.foregroundColor),
                    modifier = Modifier
                        .size(22.dp)
                        .graphicsLayer { scaleX = if (isRtl) -1f else 1f },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ContainerFocusCard(
    state: ContainerStatisticsUiState,
    colors: UHabitComposeColors,
) {
    UHabitSectionCard(
        modifier = Modifier.testTag("cardFocus"),
        colors = colors,
        cornerRadiusDp = colors.statisticsCardCornerRadiusDp,
        contentPadding = PaddingValues(16.dp),
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            BasicText(
                text = stringResource(R.string.container_statistics_focus_title),
                style = TextStyle(
                    color = colors.foregroundColor,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                ),
                modifier = Modifier.semantics { heading() },
            )
            BasicText(
                text = state.focusSessionCountText,
                style = TextStyle(
                    color = colors.secondaryColor,
                    fontSize = 12.sp,
                ),
            )
        }

        Spacer(Modifier.height(10.dp))

        BasicText(
            text = state.focusTotalDuration,
            style = TextStyle(
                color = colors.accentColor,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
            ),
            modifier = Modifier.testTag("tvFocusTotalDuration"),
        )

        if (state.focusIsEmpty) {
            Spacer(Modifier.height(8.dp))
            BasicText(
                text = stringResource(R.string.container_statistics_focus_empty),
                style = TextStyle(
                    color = colors.secondaryColor,
                    fontSize = 13.sp,
                ),
            )
        } else {
            state.focusSubtreeBreakdownText?.let { breakdown ->
                Spacer(Modifier.height(4.dp))
                BasicText(
                    text = breakdown,
                    style = TextStyle(
                        color = colors.secondaryColor,
                        fontSize = 12.sp,
                    ),
                )
            }

            if (state.focusDailyBars.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                BasicText(
                    text = stringResource(R.string.container_statistics_focus_daily_trend),
                    style = TextStyle(
                        color = colors.secondaryColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                )
                Spacer(Modifier.height(6.dp))
                ContainerFocusDailyBarsChart(
                    bars = state.focusDailyBars,
                    colors = colors,
                )
            }

            if (state.focusChildRows.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                BasicText(
                    text = stringResource(R.string.container_statistics_focus_subcontainers_breakdown),
                    style = TextStyle(
                        color = colors.secondaryColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                )
                Spacer(Modifier.height(6.dp))
                state.focusChildRows.forEachIndexed { index, child ->
                    if (index > 0) {
                        UHabitCardDivider(colors = colors, verticalPadding = 2.dp)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BasicText(
                            text = child.name,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = TextStyle(
                                color = colors.foregroundColor,
                                fontSize = 13.sp,
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 8.dp),
                        )
                        BasicText(
                            text = child.durationText,
                            style = TextStyle(
                                color = colors.accentColor,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                            ),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        BasicText(
            text = stringResource(R.string.container_statistics_focus_historical_note),
            style = TextStyle(
                color = colors.secondaryColor,
                fontSize = 11.sp,
            ),
        )
    }
}

/**
 * Pure Compose Canvas daily focus bar chart preserving proportional height, rounded bars,
 * and readable horizontal scrolling for month ranges or narrow viewports.
 */
@Composable
internal fun ContainerFocusDailyBarsChart(
    bars: List<ContainerDailyBarUiModel>,
    colors: UHabitComposeColors = LocalUHabitComposeColors.current,
    modifier: Modifier = Modifier,
) {
    if (bars.isEmpty()) return
    val chartDescription = remember(bars) {
        bars.joinToString(", ") { "${it.label}: ${it.formattedDuration}" }
    }
    val accent = colors.accentColor
    val emptyBarColor = colors.surfaceVariantColor
    val baselineColor = colors.resolveDividerColor()
    val scrollState = rememberScrollState()

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val minSlotWidth = if (bars.size <= 7) maxWidth / bars.size else 34.dp
        val contentWidth = maxOf(maxWidth, minSlotWidth * bars.size)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scrollState)
                .semantics { contentDescription = chartDescription }
                .testTag("container_focus_daily_chart"),
        ) {
            Canvas(
                modifier = Modifier
                    .width(contentWidth)
                    .height(52.dp),
            ) {
                val count = bars.size
                val slotW = size.width / count
                val gapPx = 4.dp.toPx()
                val barW = (slotW - gapPx).coerceIn(6.dp.toPx(), 28.dp.toPx())
                val corner = CornerRadius(3.dp.toPx(), 3.dp.toPx())
                val maxBarH = 44.dp.toPx()
                val minEmptyH = 4.dp.toPx()
                val baselineY = size.height - 1.dp.toPx()

                drawLine(
                    color = baselineColor,
                    start = Offset(0f, baselineY),
                    end = Offset(size.width, baselineY),
                    strokeWidth = 1.dp.toPx(),
                )

                bars.forEachIndexed { index, bar ->
                    val centerX = slotW * (index + 0.5f)
                    val left = centerX - barW / 2f
                    val barH = if (bar.durationMs > 0L) {
                        (maxBarH * bar.heightRatio.coerceIn(0.05f, 1f)).coerceAtLeast(minEmptyH)
                    } else {
                        minEmptyH
                    }
                    val top = (baselineY - barH).coerceAtLeast(0f)
                    drawRoundRect(
                        color = if (bar.durationMs > 0L) accent else emptyBarColor,
                        topLeft = Offset(left, top),
                        size = Size(barW, barH),
                        cornerRadius = corner,
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            Row(modifier = Modifier.width(contentWidth)) {
                bars.forEach { bar ->
                    BasicText(
                        text = bar.label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(
                            color = colors.secondaryColor,
                            fontSize = 10.sp,
                            textAlign = TextAlign.Center,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun ContainerTasksCard(
    state: ContainerStatisticsUiState,
    colors: UHabitComposeColors,
) {
    val fontScale = LocalDensity.current.fontScale
    UHabitSectionCard(
        modifier = Modifier.testTag("cardTasks"),
        colors = colors,
        cornerRadiusDp = colors.statisticsCardCornerRadiusDp,
        contentPadding = PaddingValues(16.dp),
    ) {
        BasicText(
            text = stringResource(R.string.container_statistics_tasks_title),
            style = TextStyle(
                color = colors.foregroundColor,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            ),
            modifier = Modifier.semantics { heading() },
        )

        Spacer(Modifier.height(12.dp))

        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val stackBadges = maxWidth < 300.dp || fontScale >= 1.5f
            if (stackBadges) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TaskMetricBadge(
                        count = state.tasksCompletedCount,
                        label = stringResource(R.string.container_statistics_tasks_completed),
                        countColor = colors.foregroundColor,
                        colors = colors,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TaskMetricBadge(
                        count = state.tasksPendingCount,
                        label = stringResource(R.string.container_statistics_tasks_pending),
                        countColor = colors.foregroundColor,
                        colors = colors,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TaskMetricBadge(
                        count = state.tasksOverdueCount,
                        label = stringResource(R.string.container_statistics_tasks_overdue),
                        countColor = colors.accentColor,
                        colors = colors,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TaskMetricBadge(
                        count = state.tasksCompletedCount,
                        label = stringResource(R.string.container_statistics_tasks_completed),
                        countColor = colors.foregroundColor,
                        colors = colors,
                        modifier = Modifier.weight(1f),
                    )
                    TaskMetricBadge(
                        count = state.tasksPendingCount,
                        label = stringResource(R.string.container_statistics_tasks_pending),
                        countColor = colors.foregroundColor,
                        colors = colors,
                        modifier = Modifier.weight(1f),
                    )
                    TaskMetricBadge(
                        count = state.tasksOverdueCount,
                        label = stringResource(R.string.container_statistics_tasks_overdue),
                        countColor = colors.accentColor,
                        colors = colors,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        if (state.tasksIsEmpty) {
            Spacer(Modifier.height(10.dp))
            BasicText(
                text = stringResource(R.string.container_statistics_tasks_empty),
                style = TextStyle(
                    color = colors.secondaryColor,
                    fontSize = 13.sp,
                ),
            )
        }

        Spacer(Modifier.height(10.dp))
        BasicText(
            text = stringResource(R.string.container_statistics_tasks_placement_note),
            style = TextStyle(
                color = colors.secondaryColor,
                fontSize = 11.sp,
            ),
        )
    }
}

@Composable
private fun TaskMetricBadge(
    count: Int,
    label: String,
    countColor: Color,
    colors: UHabitComposeColors,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(colors.surfaceVariantColor)
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BasicText(
            text = count.toString(),
            style = TextStyle(
                color = countColor,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
            ),
        )
        Spacer(Modifier.height(2.dp))
        BasicText(
            text = label,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(
                color = colors.secondaryColor,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
            ),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ContainerHabitsCard(
    state: ContainerStatisticsUiState,
    colors: UHabitComposeColors,
) {
    val reachedBg = colorResource(R.color.green_700)
    val incompleteBg = colorResource(R.color.orange_700)

    UHabitSectionCard(
        modifier = Modifier.testTag("cardHabits"),
        colors = colors,
        cornerRadiusDp = colors.statisticsCardCornerRadiusDp,
        contentPadding = PaddingValues(16.dp),
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            BasicText(
                text = stringResource(R.string.container_statistics_habits_title),
                style = TextStyle(
                    color = colors.foregroundColor,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                ),
                modifier = Modifier.semantics { heading() },
            )
            BasicText(
                text = state.habitsActiveCountText,
                style = TextStyle(
                    color = colors.secondaryColor,
                    fontSize = 12.sp,
                ),
            )
        }

        if (state.habitsIsEmpty) {
            Spacer(Modifier.height(10.dp))
            BasicText(
                text = stringResource(R.string.container_statistics_habits_empty),
                style = TextStyle(
                    color = colors.secondaryColor,
                    fontSize = 13.sp,
                ),
            )
        } else {
            Spacer(Modifier.height(12.dp))

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SummaryChip(state.habitsReachedChipText, colors)
                SummaryChip(state.habitsIncompleteChipText, colors)
                SummaryChip(state.habitsUnknownChipText, colors)
            }

            Spacer(Modifier.height(8.dp))

            state.habitRows.forEachIndexed { index, row ->
                if (index > 0) {
                    UHabitCardDivider(colors = colors, verticalPadding = 2.dp)
                }
                ContainerHabitActivityRow(
                    row = row,
                    reachedBg = reachedBg,
                    incompleteBg = incompleteBg,
                    colors = colors,
                )
            }
        }
    }
}

@Composable
private fun SummaryChip(
    text: String,
    colors: UHabitComposeColors,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(colors.surfaceVariantColor)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        BasicText(
            text = text,
            style = TextStyle(
                color = colors.secondaryColor,
                fontSize = 12.sp,
            ),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ContainerHabitActivityRow(
    row: ContainerHabitRowUiModel,
    reachedBg: Color,
    incompleteBg: Color,
    colors: UHabitComposeColors,
) {
    val (badgeLabel, badgeBg, badgeTextColor) = when (row.goalStatus) {
        HabitGoalStatus.SATISFIED -> Triple(
            stringResource(R.string.container_statistics_habits_reached),
            reachedBg,
            Color.White,
        )
        HabitGoalStatus.NOT_SATISFIED -> Triple(
            stringResource(R.string.container_statistics_habits_incomplete),
            incompleteBg,
            Color.White,
        )
        else -> Triple(
            stringResource(R.string.container_statistics_habits_unknown),
            colors.surfaceVariantColor,
            colors.secondaryColor,
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(Color(row.colorArgb)),
            )
            Spacer(Modifier.width(8.dp))
            BasicText(
                text = row.name,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(
                    color = colors.foregroundColor,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                ),
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp),
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(badgeBg)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                BasicText(
                    text = badgeLabel,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(
                        color = badgeTextColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 18.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            BasicText(
                text = row.progressText,
                style = TextStyle(
                    color = colors.secondaryColor,
                    fontSize = 12.sp,
                ),
            )
            row.focusContributionText?.let { contrib ->
                BasicText(
                    text = contrib,
                    style = TextStyle(
                        color = colors.accentColor,
                        fontSize = 11.sp,
                    ),
                )
            }
        }
    }
}
