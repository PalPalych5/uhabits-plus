/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.activities.statistics

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.isoron.platform.time.DayOfWeek
import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.R
import org.isoron.uhabits.compose.UHabitComposeColors
import org.isoron.uhabits.compose.UHabitComposeTheme
import org.isoron.uhabits.compose.components.UHabitCardDivider
import org.isoron.uhabits.compose.components.UHabitDialogShell
import org.isoron.uhabits.compose.components.UHabitSectionCard
import org.isoron.uhabits.compose.components.UHabitTextField
import org.isoron.uhabits.core.models.Streak
import org.isoron.uhabits.core.ui.screens.statistics.ComparePeriod

internal data class CompareSelectedChipUiModel(
    val habitId: Long,
    val name: String,
    val seriesColorArgb: Int,
)

internal data class CompareOverviewRowUiModel(
    val habitId: Long,
    val name: String,
    val seriesColorArgb: Int,
    val stabilityText: String,
    val changeText: String,
    val completionText: String,
    val streakText: String,
    val goalText: String?,
)

internal data class CompareStreakRowUiModel(
    val habitId: Long,
    val name: String,
    val seriesColorArgb: Int,
    val isExact: Boolean,
    val latestStreak: Streak?,
    val recordStreak: Streak?,
)

internal data class CompareFrequencyRowUiModel(
    val habitId: Long,
    val name: String,
    val seriesColorArgb: Int,
    val isNumerical: Boolean,
    val firstWeekday: DayOfWeek,
    val frequency: Map<LocalDate, Array<Int>>,
    val isUnavailable: Boolean,
)

internal data class ComparePickerHabitUiModel(
    val habitId: Long,
    val name: String,
    val habitColorArgb: Int,
    val blockId: Long?,
    val blockName: String?,
    val blockColorArgb: Int?,
)

internal sealed class CompareContentState {
    data object MinHabitsWarning : CompareContentState()
    data object Unavailable : CompareContentState()
    data class Ready(
        val rangeStart: LocalDate,
        val rangeEnd: LocalDate,
        val overviewRows: List<CompareOverviewRowUiModel>,
        val stabilitySeries: List<CompareLineSeriesUiModel>,
        val executionSeries: List<CompareLineSeriesUiModel>,
        val calendars: List<CompareCalendarGridUiModel>,
        val sharedMaxStreak: Long,
        val streakRows: List<CompareStreakRowUiModel>,
        val frequencyRows: List<CompareFrequencyRowUiModel>,
    ) : CompareContentState()
}

internal data class CompareHabitsUiState(
    val selectedChips: List<CompareSelectedChipUiModel>,
    val currentPeriod: ComparePeriod,
    val chipsScrollToEndToken: Int,
    val content: CompareContentState,
    val pickerHabits: List<ComparePickerHabitUiModel>,
    val spheresEnabled: Boolean,
    val showPicker: Boolean,
)

@Composable
internal fun CompareHabitsScreen(
    state: CompareHabitsUiState,
    colors: UHabitComposeColors,
    onBack: () -> Unit,
    onRemoveHabit: (Long) -> Unit,
    onOpenPicker: () -> Unit,
    onDismissPicker: () -> Unit,
    onConfirmPickerSelection: (List<Long>) -> Unit,
    onSelectPeriod: (ComparePeriod) -> Unit,
) {
    UHabitComposeTheme(colors = colors) {
        val isClassic = colors.isClassicSectionLayout
        val isBorderedCards = !isClassic && colors.showCardBorders
        val canvasColor = if (isClassic) {
            if (colors.isPureBlack) colors.backgroundColor else colors.surfaceColor
        } else {
            colors.backgroundColor
        }
        val headerBg = if (isClassic) canvasColor else colors.surfaceColor
        val horizontalPadding = if (isBorderedCards) 12.dp else 0.dp
        val cardSpacing = if (isBorderedCards) 8.dp else 0.dp

        var hiddenStabilitySeries by remember(state.selectedChips) { mutableStateOf(emptySet<Long>()) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(canvasColor)
                .semantics { testTagsAsResourceId = true }
                .testTag("compare_habits_root"),
        ) {
            // Top bar + selected habit chips + period tabs
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(headerBg)
                    .statusBarsPadding(),
            ) {
                CompareTopBar(colors = colors, onBack = onBack)

                CompareSelectedChipsRow(
                    chips = state.selectedChips,
                    scrollToEndToken = state.chipsScrollToEndToken,
                    colors = colors,
                    onRemoveHabit = onRemoveHabit,
                    onOpenPicker = onOpenPicker,
                )

                ComparePeriodTabs(
                    currentPeriod = state.currentPeriod,
                    colors = colors,
                    onSelectPeriod = onSelectPeriod,
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(colors.resolveDividerColor()),
                )
            }

            // Main scrollable analysis content
            Crossfade(
                targetState = state.currentPeriod to state.content,
                animationSpec = tween(durationMillis = 160),
                label = "comparePeriodFade",
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) { (_, content) ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .navigationBarsPadding(),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Column(
                        modifier = Modifier
                            .widthIn(max = 680.dp)
                            .fillMaxWidth()
                            .padding(
                                start = horizontalPadding,
                                end = horizontalPadding,
                                top = if (isBorderedCards) 12.dp else 0.dp,
                                bottom = 28.dp,
                            ),
                        verticalArrangement = Arrangement.spacedBy(cardSpacing),
                    ) {
                        when (content) {
                            CompareContentState.MinHabitsWarning -> {
                                BasicText(
                                    text = stringResource(R.string.compare_habits_min_warning),
                                    style = TextStyle(
                                        color = colors.secondaryColor,
                                        fontSize = 15.sp,
                                        textAlign = TextAlign.Center,
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 24.dp, vertical = 48.dp)
                                        .testTag("tvMinHabitsWarning"),
                                )
                            }

                            CompareContentState.Unavailable -> {
                                UnavailableObservationText(colors = colors)
                            }

                            is CompareContentState.Ready -> {
                                CompareOverviewCard(
                                    rows = content.overviewRows,
                                    colors = colors,
                                )

                                CompareStabilityCard(
                                    series = content.stabilitySeries,
                                    hiddenIds = hiddenStabilitySeries,
                                    rangeStart = content.rangeStart,
                                    rangeEnd = content.rangeEnd,
                                    colors = colors,
                                    onToggleSeries = { habitId ->
                                        hiddenStabilitySeries = if (habitId in hiddenStabilitySeries) {
                                            hiddenStabilitySeries - habitId
                                        } else {
                                            hiddenStabilitySeries + habitId
                                        }
                                    },
                                )

                                CompareExecutionCard(
                                    series = content.executionSeries,
                                    rangeStart = content.rangeStart,
                                    rangeEnd = content.rangeEnd,
                                    colors = colors,
                                )

                                CompareCalendarSmallMultiplesCard(
                                    calendars = content.calendars,
                                    colors = colors,
                                )

                                CompareStreaksCard(
                                    rows = content.streakRows,
                                    sharedMax = content.sharedMaxStreak,
                                    colors = colors,
                                )

                                if (content.frequencyRows.isNotEmpty()) {
                                    CompareFrequencyCard(
                                        rows = content.frequencyRows,
                                        colors = colors,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (state.showPicker) {
            CompareHabitPickerDialog(
                availableHabits = state.pickerHabits,
                initialSelectedIds = state.selectedChips.map { it.habitId },
                spheresEnabled = state.spheresEnabled,
                colors = colors,
                onDismiss = onDismissPicker,
                onConfirm = onConfirmPickerSelection,
            )
        }
    }
}

@Composable
private fun CompareTopBar(
    colors: UHabitComposeColors,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .padding(horizontal = 4.dp),
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
                .testTag("compare_back"),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_settings_back),
                contentDescription = stringResource(R.string.section_back),
                colorFilter = ColorFilter.tint(colors.foregroundColor),
                modifier = Modifier.size(24.dp),
            )
        }

        BasicText(
            text = stringResource(R.string.statistics_compare_habits),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(
                color = colors.foregroundColor,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            ),
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp)
                .semantics { heading() },
        )
    }
}

@Composable
private fun CompareSelectedChipsRow(
    chips: List<CompareSelectedChipUiModel>,
    scrollToEndToken: Int,
    colors: UHabitComposeColors,
    onRemoveHabit: (Long) -> Unit,
    onOpenPicker: () -> Unit,
) {
    val scrollState = rememberScrollState()
    LaunchedEffect(scrollToEndToken) {
        if (scrollToEndToken > 0) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(scrollState)
                .padding(start = 16.dp, end = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            chips.forEach { chip ->
                val seriesColor = Color(chip.seriesColorArgb)
                val shape = RoundedCornerShape(14.dp)
                Row(
                    modifier = Modifier
                        .defaultMinSize(minHeight = 32.dp)
                        .clip(shape)
                        .background(seriesColor.copy(alpha = 0.14f))
                        .border(1.2.dp, seriesColor, shape)
                        .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(seriesColor),
                    )
                    Spacer(Modifier.width(6.dp))
                    BasicText(
                        text = chip.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(
                            color = colors.foregroundColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        modifier = Modifier.widthIn(max = 160.dp),
                    )
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .clickable(
                                role = Role.Button,
                                onClickLabel = chip.name,
                                onClick = { onRemoveHabit(chip.habitId) },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        BasicText(
                            text = "✕",
                            style = TextStyle(
                                color = colors.secondaryColor,
                                fontSize = 11.sp,
                            ),
                        )
                    }
                }
            }
        }

        if (chips.size < 4) {
            val addDesc = stringResource(R.string.compare_habits_add)
            Box(
                modifier = Modifier
                    .padding(end = 4.dp)
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable(
                        role = Role.Button,
                        onClickLabel = addDesc,
                        onClick = onOpenPicker,
                    )
                    .semantics { contentDescription = addDesc }
                    .testTag("addHabitButton"),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    text = "+",
                    style = TextStyle(
                        color = colors.accentColor,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                )
            }
        }
    }
}

@Composable
private fun ComparePeriodTabs(
    currentPeriod: ComparePeriod,
    colors: UHabitComposeColors,
    onSelectPeriod: (ComparePeriod) -> Unit,
) {
    val periods = listOf(
        ComparePeriod.WEEK to R.string.compare_period_week,
        ComparePeriod.MONTH to R.string.compare_period_month,
        ComparePeriod.THREE_MONTHS to R.string.compare_period_3months,
        ComparePeriod.YEAR to R.string.compare_period_year,
        ComparePeriod.ALL to R.string.compare_period_all,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        periods.forEach { (period, titleRes) ->
            val isSelected = period == currentPeriod
            val title = stringResource(titleRes)
            Column(
                modifier = Modifier
                    .defaultMinSize(minHeight = 44.dp)
                    .semantics {
                        role = Role.Tab
                        selected = isSelected
                    }
                    .clickable { onSelectPeriod(period) }
                    .padding(horizontal = 10.dp)
                    .testTag("compare_period_${period.name}"),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
            ) {
                Box(
                    modifier = Modifier.defaultMinSize(minHeight = 40.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(
                        text = title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(
                            color = if (isSelected) colors.accentColor else colors.secondaryColor,
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        ),
                    )
                }
                Box(
                    modifier = Modifier
                        .widthIn(min = 24.dp)
                        .height(2.dp)
                        .background(if (isSelected) colors.accentColor else Color.Transparent),
                )
            }
        }
    }
}

@Composable
private fun CompareSectionCard(
    title: String,
    subtitle: String? = null,
    colors: UHabitComposeColors,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    UHabitSectionCard(
        modifier = modifier,
        colors = colors,
        cornerRadiusDp = colors.statisticsCardCornerRadiusDp,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
    ) {
        BasicText(
            text = title,
            style = TextStyle(
                color = colors.foregroundColor,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            ),
            modifier = Modifier.semantics { heading() },
        )
        if (subtitle != null) {
            Spacer(Modifier.height(2.dp))
            BasicText(
                text = subtitle,
                style = TextStyle(
                    color = colors.secondaryColor,
                    fontSize = 11.sp,
                ),
            )
            Spacer(Modifier.height(8.dp))
        } else {
            Spacer(Modifier.height(8.dp))
        }
        content()
    }
}

@Composable
private fun SeriesHabitHeader(
    name: String,
    seriesColorArgb: Int,
    colors: UHabitComposeColors,
    dotSizeDp: Int = 7,
    fontSizeSp: Float = 12f,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(dotSizeDp.dp)
                .clip(CircleShape)
                .background(Color(seriesColorArgb)),
        )
        Spacer(Modifier.width(6.dp))
        BasicText(
            text = name,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(
                color = colors.foregroundColor,
                fontSize = fontSizeSp.sp,
                fontWeight = FontWeight.Bold,
            ),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun CompareOverviewCard(
    rows: List<CompareOverviewRowUiModel>,
    colors: UHabitComposeColors,
) {
    val fontScale = LocalDensity.current.fontScale
    CompareSectionCard(
        title = stringResource(R.string.compare_overview),
        colors = colors,
        modifier = Modifier.testTag("compare_overview_card"),
    ) {
        rows.forEachIndexed { index, row ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
            ) {
                SeriesHabitHeader(
                    name = row.name,
                    seriesColorArgb = row.seriesColorArgb,
                    colors = colors,
                    dotSizeDp = 8,
                    fontSizeSp = 14f,
                )

                Spacer(Modifier.height(6.dp))

                val stabilityLabel = stringResource(R.string.compare_stat_stability)
                val changeLabel = stringResource(R.string.compare_stat_change)
                val completionLabel = stringResource(R.string.compare_stat_completion)
                val streakLabel = stringResource(R.string.compare_stat_streak)

                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp),
                ) {
                    val useTwoRows = maxWidth < 310.dp || fontScale >= 1.4f
                    if (useTwoRows) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                OverviewMetricBlock(
                                    label = stabilityLabel,
                                    value = row.stabilityText,
                                    isAccent = true,
                                    colors = colors,
                                    modifier = Modifier.weight(1f),
                                )
                                OverviewMetricBlock(
                                    label = changeLabel,
                                    value = row.changeText,
                                    isAccent = false,
                                    colors = colors,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                OverviewMetricBlock(
                                    label = completionLabel,
                                    value = row.completionText,
                                    isAccent = false,
                                    colors = colors,
                                    modifier = Modifier.weight(1f),
                                )
                                OverviewMetricBlock(
                                    label = streakLabel,
                                    value = row.streakText,
                                    isAccent = false,
                                    colors = colors,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            OverviewMetricBlock(
                                label = stabilityLabel,
                                value = row.stabilityText,
                                isAccent = true,
                                colors = colors,
                                modifier = Modifier.weight(1f),
                            )
                            OverviewMetricBlock(
                                label = changeLabel,
                                value = row.changeText,
                                isAccent = false,
                                colors = colors,
                                modifier = Modifier.weight(1f),
                            )
                            OverviewMetricBlock(
                                label = completionLabel,
                                value = row.completionText,
                                isAccent = false,
                                colors = colors,
                                modifier = Modifier.weight(1f),
                            )
                            OverviewMetricBlock(
                                label = streakLabel,
                                value = row.streakText,
                                isAccent = false,
                                colors = colors,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                row.goalText?.let { goalStr ->
                    BasicText(
                        text = goalStr,
                        style = TextStyle(
                            color = colors.secondaryColor,
                            fontSize = 12.sp,
                        ),
                        modifier = Modifier
                            .padding(start = 16.dp, top = 4.dp)
                            .semantics { contentDescription = goalStr }
                            .testTag("compare_goal_${row.habitId}"),
                    )
                }
            }

            if (index < rows.lastIndex) {
                UHabitCardDivider(colors = colors, verticalPadding = 4.dp)
            }
        }
    }
}

@Composable
private fun OverviewMetricBlock(
    label: String,
    value: String,
    isAccent: Boolean,
    colors: UHabitComposeColors,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        BasicText(
            text = label,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(
                color = colors.secondaryColor,
                fontSize = 10.sp,
            ),
        )
        BasicText(
            text = value,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(
                color = if (isAccent) colors.accentColor else colors.foregroundColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            ),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CompareStabilityCard(
    series: List<CompareLineSeriesUiModel>,
    hiddenIds: Set<Long>,
    rangeStart: LocalDate,
    rangeEnd: LocalDate,
    colors: UHabitComposeColors,
    onToggleSeries: (Long) -> Unit,
) {
    CompareSectionCard(
        title = stringResource(R.string.compare_stability),
        subtitle = stringResource(R.string.compare_stability_loop_score),
        colors = colors,
        modifier = Modifier.testTag("compare_stability_card"),
    ) {
        CompareMultiLineChartCompose(
            series = series,
            hiddenHabitIds = hiddenIds,
            rangeStart = rangeStart,
            rangeEnd = rangeEnd,
            colors = colors,
            modifier = Modifier.testTag("compare_stability_chart"),
        )

        Spacer(Modifier.height(8.dp))

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            series.forEach { s ->
                val isVisible = s.habitId !in hiddenIds
                Row(
                    modifier = Modifier
                        .defaultMinSize(minHeight = 36.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .toggleable(
                            value = isVisible,
                            role = Role.Checkbox,
                            onValueChange = { onToggleSeries(s.habitId) },
                        )
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                        .alpha(if (isVisible) 1f else 0.4f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(Color(s.colorArgb)),
                    )
                    Spacer(Modifier.width(5.dp))
                    BasicText(
                        text = s.label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(
                            color = colors.foregroundColor.copy(alpha = 0.85f),
                            fontSize = 11.sp,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun CompareExecutionCard(
    series: List<CompareLineSeriesUiModel>,
    rangeStart: LocalDate,
    rangeEnd: LocalDate,
    colors: UHabitComposeColors,
) {
    CompareSectionCard(
        title = stringResource(R.string.compare_execution),
        colors = colors,
        modifier = Modifier.testTag("compare_execution_card"),
    ) {
        CompareMultiLineChartCompose(
            series = series,
            hiddenHabitIds = emptySet(),
            rangeStart = rangeStart,
            rangeEnd = rangeEnd,
            colors = colors,
            modifier = Modifier.testTag("compare_execution_chart"),
        )
    }
}

@Composable
private fun CompareCalendarSmallMultiplesCard(
    calendars: List<CompareCalendarGridUiModel>,
    colors: UHabitComposeColors,
) {
    CompareSectionCard(
        title = stringResource(R.string.compare_calendar),
        colors = colors,
        modifier = Modifier.testTag("compare_calendar_card"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        ) {
            calendars.forEach { cal ->
                Column(modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(Color(cal.seriesColorArgb)),
                        )
                        Spacer(Modifier.width(6.dp))
                        BasicText(
                            text = cal.habitName,
                            style = TextStyle(
                                color = colors.foregroundColor,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                            ),
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    CompareCalendarCompose(
                        model = cal,
                        colors = colors,
                        modifier = Modifier.testTag("compare_calendar_${cal.habitId}"),
                    )
                }
            }
        }
    }
}

@Composable
private fun CompareStreaksCard(
    rows: List<CompareStreakRowUiModel>,
    sharedMax: Long,
    colors: UHabitComposeColors,
) {
    CompareSectionCard(
        title = stringResource(R.string.compare_streaks),
        colors = colors,
        modifier = Modifier.testTag("compare_streaks_card"),
    ) {
        rows.forEach { row ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = 8.dp),
            ) {
                SeriesHabitHeader(
                    name = row.name,
                    seriesColorArgb = row.seriesColorArgb,
                    colors = colors,
                )

                if (!row.isExact) {
                    UnavailableObservationText(colors = colors)
                }

                row.latestStreak?.let { latest ->
                    BasicText(
                        text = stringResource(R.string.streak_current),
                        style = TextStyle(
                            color = colors.foregroundColor.copy(alpha = 0.8f),
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp, bottom = 2.dp),
                    )
                    val mutedColor = Color(row.seriesColorArgb).copy(alpha = 120f / 255f)
                    CompareStreakBarCompose(
                        streak = latest,
                        maxLength = sharedMax,
                        barColorArgb = android.graphics.Color.argb(
                            (mutedColor.alpha * 255f).toInt(),
                            (mutedColor.red * 255f).toInt(),
                            (mutedColor.green * 255f).toInt(),
                            (mutedColor.blue * 255f).toInt(),
                        ),
                        colors = colors,
                    )
                }

                row.recordStreak?.let { record ->
                    BasicText(
                        text = stringResource(R.string.streak_record),
                        style = TextStyle(
                            color = colors.foregroundColor.copy(alpha = 0.8f),
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp, bottom = 2.dp),
                    )
                    CompareStreakBarCompose(
                        streak = record,
                        maxLength = sharedMax,
                        barColorArgb = row.seriesColorArgb,
                        colors = colors,
                    )
                }
            }
        }
    }
}

@Composable
private fun CompareFrequencyCard(
    rows: List<CompareFrequencyRowUiModel>,
    colors: UHabitComposeColors,
) {
    CompareSectionCard(
        title = stringResource(R.string.compare_frequency),
        colors = colors,
        modifier = Modifier.testTag("compare_frequency_card"),
    ) {
        rows.forEach { row ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = 10.dp),
            ) {
                SeriesHabitHeader(
                    name = row.name,
                    seriesColorArgb = row.seriesColorArgb,
                    colors = colors,
                )
                Spacer(Modifier.height(6.dp))
                if (row.isUnavailable) {
                    UnavailableObservationText(colors = colors)
                } else {
                    CompareFrequencyChartCompose(
                        frequency = row.frequency,
                        seriesColorArgb = row.seriesColorArgb,
                        firstWeekday = row.firstWeekday,
                        isNumerical = row.isNumerical,
                        habitName = row.name,
                        colors = colors,
                    )
                }
            }
        }
    }
}

@Composable
private fun UnavailableObservationText(colors: UHabitComposeColors) {
    BasicText(
        text = stringResource(R.string.effective_read_unavailable),
        style = TextStyle(
            color = colors.secondaryColor,
            fontSize = 12.sp,
        ),
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
    )
}

@Composable
private fun CompareHabitPickerDialog(
    availableHabits: List<ComparePickerHabitUiModel>,
    initialSelectedIds: List<Long>,
    spheresEnabled: Boolean,
    colors: UHabitComposeColors,
    onDismiss: () -> Unit,
    onConfirm: (List<Long>) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var pendingIds by remember { mutableStateOf(initialSelectedIds) }

    val filtered = remember(availableHabits, query) {
        if (query.isBlank()) availableHabits
        else availableHabits.filter { it.name.contains(query, ignoreCase = true) }
    }
    val otherSphereLabel = stringResource(R.string.compare_picker_other_sphere)
    val confirmEnabled = pendingIds.size in 2..4

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        UHabitDialogShell(
            colors = colors,
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .heightIn(max = 560.dp)
                .semantics { testTagsAsResourceId = true }
                .testTag("compare_habit_picker_dialog"),
        ) {
            BasicText(
                text = stringResource(R.string.statistics_compare_habits),
                style = TextStyle(
                    color = colors.foregroundColor,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                ),
                modifier = Modifier.semantics { heading() },
            )

            Spacer(Modifier.height(12.dp))

            UHabitTextField(
                label = "",
                value = query,
                placeholder = stringResource(R.string.compare_picker_search),
                onValueChange = { query = it },
                colors = colors,
                modifier = Modifier.testTag("compare_picker_search"),
            )

            Spacer(Modifier.height(8.dp))

            LazyColumn(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .fillMaxWidth(),
            ) {
                if (spheresEnabled) {
                    val grouped = filtered.groupBy { it.blockId }
                    grouped.forEach { (blockId, members) ->
                        val headerName = members.firstOrNull()?.blockName ?: otherSphereLabel
                        val headerColor = members.firstOrNull()?.blockColorArgb?.let { Color(it) }
                            ?: colors.secondaryColor
                        item(key = "sphere_${blockId ?: -1L}") {
                            BasicText(
                                text = headerName,
                                style = TextStyle(
                                    color = headerColor,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                ),
                                modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
                            )
                        }
                        items(members, key = { it.habitId }) { habit ->
                            ComparePickerHabitRow(
                                habit = habit,
                                isSelected = habit.habitId in pendingIds,
                                colors = colors,
                                onToggle = {
                                    pendingIds = if (habit.habitId in pendingIds) {
                                        pendingIds - habit.habitId
                                    } else if (pendingIds.size < 4) {
                                        pendingIds + habit.habitId
                                    } else {
                                        pendingIds
                                    }
                                },
                            )
                        }
                    }
                } else {
                    val sorted = filtered.sortedBy { it.name.lowercase() }
                    items(sorted, key = { it.habitId }) { habit ->
                        ComparePickerHabitRow(
                            habit = habit,
                            isSelected = habit.habitId in pendingIds,
                            colors = colors,
                            onToggle = {
                                pendingIds = if (habit.habitId in pendingIds) {
                                    pendingIds - habit.habitId
                                } else if (pendingIds.size < 4) {
                                    pendingIds + habit.habitId
                                } else {
                                    pendingIds
                                }
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .defaultMinSize(minHeight = 48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(role = Role.Button, onClick = onDismiss)
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(
                        text = stringResource(android.R.string.cancel),
                        style = TextStyle(
                            color = colors.foregroundColor.copy(alpha = 0.8f),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                    )
                }

                Spacer(Modifier.width(4.dp))

                Box(
                    modifier = Modifier
                        .defaultMinSize(minHeight = 48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(
                            enabled = confirmEnabled,
                            role = Role.Button,
                            onClick = { onConfirm(pendingIds) },
                        )
                        .padding(horizontal = 12.dp)
                        .alpha(if (confirmEnabled) 1f else 0.4f)
                        .testTag("compare_picker_confirm"),
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(
                        text = stringResource(R.string.compare_picker_action, pendingIds.size),
                        style = TextStyle(
                            color = colors.accentColor,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun ComparePickerHabitRow(
    habit: ComparePickerHabitUiModel,
    isSelected: Boolean,
    colors: UHabitComposeColors,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .toggleable(
                value = isSelected,
                role = Role.Checkbox,
                onValueChange = { onToggle() },
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(width = 36.dp, height = 40.dp),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                text = if (isSelected) "●" else "○",
                style = TextStyle(
                    color = Color(habit.habitColorArgb),
                    fontSize = 20.sp,
                ),
            )
        }
        BasicText(
            text = habit.name,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(
                color = colors.foregroundColor,
                fontSize = 15.sp,
            ),
            modifier = Modifier.weight(1f),
        )
    }
}
