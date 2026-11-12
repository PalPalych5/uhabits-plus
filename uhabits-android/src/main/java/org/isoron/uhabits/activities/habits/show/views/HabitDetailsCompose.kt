/*
 * Copyright (C) 2016-2026 Álinson Santos Xavier <git@axavier.org> and contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.isoron.uhabits.activities.habits.show.views

import android.content.Context
import android.view.View
import java.text.NumberFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.isoron.platform.gui.AndroidDataView
import org.isoron.platform.gui.toInt
import org.isoron.platform.time.JavaLocalDateFormatter
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.common.views.FrequencyChart
import org.isoron.uhabits.activities.common.views.ScoreChart
import org.isoron.uhabits.activities.common.views.StreakChart
import org.isoron.uhabits.activities.common.views.TargetChart
import org.isoron.uhabits.activities.habits.edit.formatFrequency
import org.isoron.uhabits.activities.habits.list.views.toShortString
import org.isoron.uhabits.activities.habits.show.FocusProgressPresentation
import org.isoron.uhabits.compose.HarmonizedChartTheme
import org.isoron.uhabits.compose.LocalUHabitComposeColors
import org.isoron.uhabits.compose.UHabitComposeColors
import org.isoron.uhabits.compose.components.UHabitActionChip
import org.isoron.uhabits.compose.components.UHabitCardDivider
import org.isoron.uhabits.compose.components.UHabitSectionCard
import org.isoron.uhabits.compose.components.UHabitSectionHeader
import org.isoron.uhabits.compose.rememberHarmonizedChartTheme
import org.isoron.uhabits.core.models.NumericalHabitType
import org.isoron.uhabits.core.models.projection.HabitGoalStatus
import org.isoron.uhabits.core.ui.screens.habits.show.ShowHabitState
import org.isoron.uhabits.core.ui.screens.habits.show.views.BarCardState
import org.isoron.uhabits.core.ui.screens.habits.show.views.FrequencyCardState
import org.isoron.uhabits.core.ui.screens.habits.show.views.HistoryCardState
import org.isoron.uhabits.core.ui.screens.habits.show.views.NotesCardState
import org.isoron.uhabits.core.ui.screens.habits.show.views.OverviewCardState
import org.isoron.uhabits.core.ui.screens.habits.show.views.ScoreCardState
import org.isoron.uhabits.core.ui.screens.habits.show.views.StreakCardState
import org.isoron.uhabits.core.ui.screens.habits.show.views.SubtitleCardState
import org.isoron.uhabits.core.ui.screens.habits.show.views.TargetCardState
import org.isoron.uhabits.core.ui.views.BarChart
import org.isoron.uhabits.core.ui.views.HistoryChart
import org.isoron.uhabits.core.ui.views.OnDateClickedListener
import org.isoron.uhabits.core.ui.views.Theme
import org.isoron.uhabits.utils.HabitQuantityFormatter
import org.isoron.uhabits.utils.formatTime
import java.util.Locale
import kotlin.math.abs

/** Resolves the surface background color for Habit Details card containers. */
fun resolveSectionSurfaceColor(colors: UHabitComposeColors): Color =
    colors.resolveSectionSurfaceColor()

/** Delegating theme that harmonizes cardBackgroundColor with the surrounding container surface. */
typealias HarmonizedTheme = HarmonizedChartTheme

/**
 * Cohesive card container matching UHabit Next - Minimal & Refined design standards.
 * Delegates to the shared [UHabitSectionCard] primitive.
 */
@Composable
fun HabitSectionCard(
    modifier: Modifier = Modifier,
    colors: UHabitComposeColors = LocalUHabitComposeColors.current,
    contentPadding: PaddingValues? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    if (contentPadding != null) {
        UHabitSectionCard(
            modifier = modifier,
            colors = colors,
            contentPadding = contentPadding,
            content = content
        )
    } else {
        UHabitSectionCard(
            modifier = modifier,
            colors = colors,
            content = content
        )
    }
}

/** Section header with consistent typography and optional action chip. */
@Composable
fun HabitSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null
) {
    UHabitSectionHeader(
        title = title,
        modifier = modifier,
        trailing = action
    )
}

/** Action chip for period selection or dialog triggers. */
@Composable
fun HabitActionChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    hasDropdown: Boolean = false,
    isSelected: Boolean = false,
    accentColor: Color? = null
) {
    UHabitActionChip(
        text = text,
        onClick = onClick,
        modifier = modifier,
        hasDropdown = hasDropdown,
        isSelected = isSelected,
        accentColor = accentColor
    )
}

/** Concise empty state placeholder without excessive height. */
@Composable
fun HabitEmptyState(
    message: String,
    modifier: Modifier = Modifier
) {
    val colors = LocalUHabitComposeColors.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(84.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        BasicText(
            text = message,
            style = TextStyle(
                color = Color(colors.secondary).copy(alpha = 0.7f),
                fontSize = 13.sp,
                textAlign = TextAlign.Center
            )
        )
    }
}

/** Formats a numeric target quantity using localized number formatting without premature truncation. */
fun formatTargetQuantity(value: Double, unit: String): String {
    val formatter = NumberFormat.getNumberInstance().apply {
        maximumFractionDigits = 2
        minimumFractionDigits = 0
    }
    val formatted = formatter.format(value)
    return if (unit.isNotEmpty()) "$formatted $unit" else formatted
}

/**
 * Habit Details Header with Narrative Region (Question & Description) and
 * an Adaptive Centered Metadata Row (Numerical Target, Frequency, and Reminder).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HabitDetailsEditorialHeader(
    state: ShowHabitState,
    focusProgress: FocusProgressPresentation? = null,
    onShowBreakdown: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val colors = LocalUHabitComposeColors.current
    val hasQuestion = state.subtitle.question.isNotEmpty()
    val hasDescription = state.notes.description.isNotEmpty()
    val hasNarrative = hasQuestion || hasDescription

    val padVertical = if (hasNarrative) 12.dp else 8.dp
    val padHorizontal = 16.dp

    HabitSectionCard(
        modifier = modifier.fillMaxWidth(),
        colors = colors,
        contentPadding = PaddingValues(horizontal = padHorizontal, vertical = padVertical)
    ) {
        // 1. Dedicated Centered Metadata Row (Directly below Toolbar, content-driven compact height)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)
        ) {
            // Target (when numerical)
            if (state.subtitle.isNumerical) {
                val isAtLeast = state.subtitle.targetType == NumericalHabitType.AT_LEAST
                val targetIconRes = if (isAtLeast) R.drawable.ic_l_circle_arrow_up else R.drawable.ic_l_circle_arrow_down
                val targetDesc = stringResource(if (isAtLeast) R.string.target_type_at_least else R.string.target_type_at_most)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Image(
                        painter = painterResource(targetIconRes),
                        contentDescription = targetDesc,
                        colorFilter = ColorFilter.tint(Color(colors.secondary).copy(alpha = 0.85f)),
                        modifier = Modifier.size(15.dp)
                    )
                    BasicText(
                        text = formatTargetQuantity(state.subtitle.targetValue, state.subtitle.unit),
                        style = TextStyle(
                            color = Color(colors.secondary),
                            fontSize = 13.5.sp
                        )
                    )
                }
            }

            // Frequency
            val freqText = formatFrequency(
                state.subtitle.frequency.numerator,
                state.subtitle.frequency.denominator,
                context.resources
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_l_calendar),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(Color(colors.secondary).copy(alpha = 0.85f)),
                    modifier = Modifier.size(14.dp)
                )
                BasicText(
                    text = freqText,
                    style = TextStyle(
                        color = Color(colors.secondary),
                        fontSize = 13.5.sp
                    )
                )
            }

            // Reminder
            val reminder = state.subtitle.reminder
            val reminderText = if (reminder != null) {
                formatTime(context, reminder.hour, reminder.minute).orEmpty()
            } else {
                stringResource(R.string.reminder_off)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Image(
                    painter = painterResource(if (reminder != null) R.drawable.ic_l_bell else R.drawable.ic_l_bell_off),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(Color(colors.secondary).copy(alpha = 0.85f)),
                    modifier = Modifier.size(14.dp)
                )
                BasicText(
                    text = reminderText,
                    style = TextStyle(
                        color = Color(colors.secondary),
                        fontSize = 13.5.sp
                    )
                )
            }
        }

        // 2. Narrative Text Region: Question & Description (Below metadata, with clear 10dp separation)
        if (hasNarrative) {
            Spacer(modifier = Modifier.height(10.dp))
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                if (hasQuestion) {
                    BasicText(
                        text = state.subtitle.question,
                        style = TextStyle(
                            color = Color(colors.foreground).copy(alpha = 0.88f),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Normal,
                            lineHeight = 21.sp
                        ),
                        modifier = Modifier.padding(bottom = if (hasDescription) 3.dp else 0.dp)
                    )
                }

                if (hasDescription) {
                    BasicText(
                        text = state.notes.description,
                        style = TextStyle(
                            color = Color(colors.secondary),
                            fontSize = 13.5.sp,
                            lineHeight = 19.sp
                        ),
                        modifier = Modifier.padding(bottom = 0.dp)
                    )
                }
            }
        }
    }
}

/** Fallback Habit identity header matching the adaptive centered metadata row layout. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HabitDetailsHeader(
    state: SubtitleCardState,
    notes: NotesCardState?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val colors = LocalUHabitComposeColors.current
    val hasQuestion = state.question.isNotEmpty()
    val hasDescription = notes?.description?.isNotEmpty() == true
    val hasNarrative = hasQuestion || hasDescription

    val padVertical = if (hasNarrative) 12.dp else 8.dp
    val padHorizontal = 16.dp

    HabitSectionCard(
        modifier = modifier.fillMaxWidth(),
        colors = colors,
        contentPadding = PaddingValues(horizontal = padHorizontal, vertical = padVertical)
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)
        ) {
            if (state.isNumerical) {
                val isAtLeast = state.targetType == NumericalHabitType.AT_LEAST
                val targetIconRes = if (isAtLeast) R.drawable.ic_l_circle_arrow_up else R.drawable.ic_l_circle_arrow_down
                val targetDesc = stringResource(if (isAtLeast) R.string.target_type_at_least else R.string.target_type_at_most)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Image(
                        painter = painterResource(targetIconRes),
                        contentDescription = targetDesc,
                        colorFilter = ColorFilter.tint(Color(colors.secondary).copy(alpha = 0.85f)),
                        modifier = Modifier.size(15.dp)
                    )
                    BasicText(
                        text = formatTargetQuantity(state.targetValue, state.unit),
                        style = TextStyle(color = Color(colors.secondary), fontSize = 13.5.sp)
                    )
                }
            }

            val freqText = formatFrequency(
                state.frequency.numerator,
                state.frequency.denominator,
                context.resources
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_l_calendar),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(Color(colors.secondary).copy(alpha = 0.85f)),
                    modifier = Modifier.size(14.dp)
                )
                BasicText(
                    text = freqText,
                    style = TextStyle(color = Color(colors.secondary), fontSize = 13.5.sp)
                )
            }

            val reminder = state.reminder
            val reminderText = if (reminder != null) {
                formatTime(context, reminder.hour, reminder.minute).orEmpty()
            } else {
                stringResource(R.string.reminder_off)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Image(
                    painter = painterResource(if (reminder != null) R.drawable.ic_l_bell else R.drawable.ic_l_bell_off),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(Color(colors.secondary).copy(alpha = 0.85f)),
                    modifier = Modifier.size(14.dp)
                )
                BasicText(
                    text = reminderText,
                    style = TextStyle(color = Color(colors.secondary), fontSize = 13.5.sp)
                )
            }
        }

        if (hasNarrative) {
            Spacer(modifier = Modifier.height(10.dp))
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                if (hasQuestion) {
                    BasicText(
                        text = state.question,
                        style = TextStyle(
                            color = Color(colors.foreground).copy(alpha = 0.88f),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Normal,
                            lineHeight = 21.sp
                        ),
                        modifier = Modifier.padding(bottom = if (hasDescription) 3.dp else 0.dp)
                    )
                }

                if (hasDescription) {
                    BasicText(
                        text = notes.description.orEmpty(),
                        style = TextStyle(
                            color = Color(colors.secondary),
                            fontSize = 13.5.sp,
                            lineHeight = 19.sp
                        ),
                        modifier = Modifier.padding(bottom = 0.dp)
                    )
                }
            }
        }
    }
}

/**
 * Statistics overview for binary habits using the approved grouped primary result layout
 * (Ring indicator and Score percentage grouped into a single primary unit with 3 balanced secondary metrics).
 */
@Composable
fun HabitOverview(
    state: OverviewCardState,
    modifier: Modifier = Modifier
) {
    val colors = LocalUHabitComposeColors.current
    val accentColor = Color(state.theme.color(state.color).toInt())

    fun formatDiff(diff: Float): String {
        return String.format("%s%.0f%%", if (diff >= 0) "+" else "\u2212", abs(diff) * 100)
    }

    HabitSectionCard(modifier = modifier) {
        HabitSectionHeader(title = stringResource(R.string.overview))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Primary Unit: Ring + Score Percentage + Label
            Row(
                modifier = Modifier.weight(1.35f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                val percentage = state.scoreToday.toFloat().coerceIn(0f, 1f)
                Canvas(modifier = Modifier.size(30.dp)) {
                    val strokeWidth = 5.dp.toPx()
                    val halfStroke = strokeWidth / 2f
                    val arcSize = size.minDimension - strokeWidth
                    drawCircle(
                        color = Color(colors.foreground).copy(alpha = 0.15f),
                        radius = arcSize / 2f,
                        style = Stroke(width = strokeWidth)
                    )
                    if (percentage > 0f) {
                        drawArc(
                            color = accentColor,
                            startAngle = -90f,
                            sweepAngle = (percentage * 360f).coerceIn(0f, 360f),
                            useCenter = false,
                            topLeft = androidx.compose.ui.geometry.Offset(halfStroke, halfStroke),
                            size = androidx.compose.ui.geometry.Size(arcSize, arcSize),
                            style = Stroke(width = strokeWidth, cap = StrokeCap.Butt)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.Start) {
                    BasicText(
                        text = String.format("%.0f%%", state.scoreToday * 100),
                        style = TextStyle(
                            color = accentColor,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Normal
                        )
                    )
                    BasicText(
                        text = stringResource(R.string.score),
                        style = TextStyle(
                            color = Color(colors.secondary),
                            fontSize = 13.sp
                        )
                    )
                }
            }

            // Column 2: Month Diff
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val isPositive = state.scoreMonthDiff >= 0
                val color = if (isPositive) accentColor else Color(colors.secondary)
                BasicText(
                    text = formatDiff(state.scoreMonthDiff),
                    style = TextStyle(
                        color = color,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Normal
                    )
                )
                BasicText(
                    text = stringResource(R.string.month),
                    style = TextStyle(
                        color = Color(colors.secondary),
                        fontSize = 13.sp
                    )
                )
            }

            // Column 3: Year Diff
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val isPositive = state.scoreYearDiff >= 0
                val color = if (isPositive) accentColor else Color(colors.secondary)
                BasicText(
                    text = formatDiff(state.scoreYearDiff),
                    style = TextStyle(
                        color = color,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Normal
                    )
                )
                BasicText(
                    text = stringResource(R.string.year),
                    style = TextStyle(
                        color = Color(colors.secondary),
                        fontSize = 13.sp
                    )
                )
            }

            // Column 4: Total Count (numeric value colored with habit accent)
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                BasicText(
                    text = "${state.totalCount}",
                    style = TextStyle(
                        color = accentColor,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Normal
                    )
                )
                BasicText(
                    text = stringResource(R.string.total),
                    style = TextStyle(
                        color = Color(colors.secondary),
                        fontSize = 13.sp
                    )
                )
            }
        }
    }
}

/** Numerical habit goal progress summary card. */
@Composable
fun HabitTargetProgress(
    progress: FocusProgressPresentation?,
    state: TargetCardState,
    unavailable: Boolean,
    onShowBreakdown: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val colors = LocalUHabitComposeColors.current
    val androidColor = state.theme.color(state.color).toInt()

    HabitSectionCard(modifier = modifier) {
        HabitSectionHeader(
            title = stringResource(R.string.target),
            action = null
        )

        if (unavailable || state.intervals.isEmpty()) {
            HabitEmptyState(stringResource(R.string.focus_credit_unavailable))
        } else {
            val indices = state.intervals.indices
            val labels = indices.map { TargetCardView.intervalToLabel(context.resources, state.intervals[it]) }
            val values = indices.map { state.values[it] }
            val targets = indices.map { state.targets[it] }

            AndroidView(
                factory = { ctx ->
                    TargetChart(ctx).apply {
                        setColor(androidColor)
                        setLabels(labels)
                        setTargets(targets)
                        setValues(values)
                    }
                },
                update = { chart ->
                    chart.setColor(androidColor)
                    chart.setLabels(labels)
                    chart.setTargets(targets)
                    chart.setValues(values)
                    chart.postInvalidate()
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/** Interactive calendar section embedding HistoryChart; pressing Edit opens the expanded calendar dialog. */
@Composable
fun HabitHistoryCalendar(
    state: HistoryCardState,
    onEditClicked: () -> Unit = {},
    onDateClickedListener: OnDateClickedListener = object : OnDateClickedListener {},
    modifier: Modifier = Modifier
) {
    val colors = LocalUHabitComposeColors.current
    val harmonizedTheme = rememberHarmonizedChartTheme(state.theme, colors)

    HabitSectionCard(modifier = modifier) {
        HabitSectionHeader(
            title = stringResource(R.string.calendar),
            action = {
                HabitActionChip(
                    text = stringResource(R.string.edit),
                    onClick = onEditClicked
                )
            }
        )

        AndroidView(
            factory = { ctx ->
                AndroidDataView(ctx).apply {
                    view = HistoryChart(
                        today = state.today,
                        paletteColor = state.color,
                        theme = harmonizedTheme,
                        dateFormatter = JavaLocalDateFormatter(Locale.getDefault()),
                        series = state.series,
                        defaultSquare = state.defaultSquare,
                        notesIndicators = state.notesIndicators,
                        firstWeekday = state.firstWeekday,
                        onDateClickedListener = onDateClickedListener
                    )
                }
            },
            update = { chart ->
                val existingOffset = (chart.view as? HistoryChart)?.dataOffset ?: 0
                chart.view = HistoryChart(
                    today = state.today,
                    paletteColor = state.color,
                    theme = harmonizedTheme,
                    dateFormatter = JavaLocalDateFormatter(Locale.getDefault()),
                    series = state.series,
                    defaultSquare = state.defaultSquare,
                    notesIndicators = state.notesIndicators,
                    firstWeekday = state.firstWeekday,
                    onDateClickedListener = onDateClickedListener
                ).apply {
                    dataOffset = existingOffset
                }
                chart.postInvalidate()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .clipToBounds()
        )
    }
}

/** Score history trend section for binary habits. */
@Composable
fun HabitStatisticsSection(
    state: ScoreCardState,
    periodLabel: String,
    onSelectPeriod: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalUHabitComposeColors.current
    val surfaceColor = resolveSectionSurfaceColor(colors)
    val androidColor = state.theme.color(state.color).toInt()

    HabitSectionCard(modifier = modifier) {
        HabitSectionHeader(
            title = stringResource(R.string.score),
            action = {
                HabitActionChip(
                    text = periodLabel,
                    onClick = onSelectPeriod,
                    hasDropdown = true
                )
            }
        )

        if (state.scores.isEmpty()) {
            HabitEmptyState(stringResource(R.string.reports_no_data))
        } else {
            val chartHeight = 200.dp
            val lastBucketSize = remember { androidx.compose.runtime.mutableIntStateOf(state.bucketSize) }
            AndroidView(
                factory = { ctx ->
                    ScoreChart(ctx).apply {
                        setScores(state.scores)
                        reset()
                        setBucketSize(state.bucketSize)
                        setColor(androidColor)
                        setIsTransparencyEnabled(true)
                        setSurfaceColors(
                            textColor = colors.secondary,
                            gridColor = colors.border,
                            backgroundColor = surfaceColor.toArgb()
                        )
                    }
                },
                update = { chart ->
                    chart.setScores(state.scores)
                    if (lastBucketSize.intValue != state.bucketSize) {
                        lastBucketSize.intValue = state.bucketSize
                        chart.reset()
                    }
                    chart.setBucketSize(state.bucketSize)
                    chart.setColor(androidColor)
                    chart.setIsTransparencyEnabled(true)
                    chart.setSurfaceColors(
                        textColor = colors.secondary,
                        gridColor = colors.border,
                        backgroundColor = surfaceColor.toArgb()
                    )
                    chart.postInvalidate()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(chartHeight)
                    .clipToBounds()
            )
        }
    }
}

/** History bar chart section for numerical intervals or binary frequency. */
@Composable
fun HabitBarHistorySection(
    state: BarCardState,
    periodLabel: String,
    onSelectPeriod: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalUHabitComposeColors.current
    val harmonizedTheme = rememberHarmonizedChartTheme(state.theme, colors)

    HabitSectionCard(modifier = modifier) {
        HabitSectionHeader(
            title = stringResource(R.string.history),
            action = {
                HabitActionChip(
                    text = periodLabel,
                    onClick = onSelectPeriod,
                    hasDropdown = true
                )
            }
        )

        if (state.entries.isEmpty()) {
            HabitEmptyState(stringResource(R.string.reports_no_data))
        } else {
            val currentPeriodIndex = if (state.isNumerical) state.numericalSpinnerPosition else state.boolSpinnerPosition
            val lastPeriodIndex = remember { androidx.compose.runtime.mutableIntStateOf(currentPeriodIndex) }
            AndroidView(
                factory = { ctx ->
                    AndroidDataView(ctx).apply {
                        view = BarChart(harmonizedTheme, JavaLocalDateFormatter(Locale.getDefault())).apply {
                            series = mutableListOf(state.entries.map { it.value / 1000.0 })
                            this.colors = mutableListOf(harmonizedTheme.color(state.color.paletteIndex))
                            axis = state.entries.map { it.date }
                        }
                        resetDataOffset()
                    }
                },
                update = { chart ->
                    val periodChanged = lastPeriodIndex.intValue != currentPeriodIndex
                    val existingOffset = if (periodChanged) 0 else ((chart.view as? BarChart)?.dataOffset ?: 0)
                    lastPeriodIndex.intValue = currentPeriodIndex
                    chart.view = BarChart(harmonizedTheme, JavaLocalDateFormatter(Locale.getDefault())).apply {
                        series = mutableListOf(state.entries.map { it.value / 1000.0 })
                        this.colors = mutableListOf(harmonizedTheme.color(state.color.paletteIndex))
                        axis = state.entries.map { it.date }
                        dataOffset = existingOffset
                    }
                    if (periodChanged) {
                        chart.resetDataOffset()
                    }
                    chart.postInvalidate()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .clipToBounds()
            )
        }
    }
}

/** Streak metrics summary section. */
@Composable
fun HabitStreakSummary(
    state: StreakCardState,
    modifier: Modifier = Modifier
) {
    val androidColor = state.theme.color(state.color).toInt()
    val colors = LocalUHabitComposeColors.current
    val validLatest = state.latestStreak?.takeIf { it.length > 0 }
    val validBest = state.bestStreaks.filter { it.length > 0 }
    val displayBest = validBest.take(5)
    val hasLatest = validLatest != null
    val hasBest = displayBest.isNotEmpty()
    val sharedMaxLength = (listOfNotNull(validLatest) + displayBest)
        .maxOfOrNull { it.length }
        ?.toLong()

    HabitSectionCard(modifier = modifier) {
        HabitSectionHeader(title = stringResource(R.string.streak_card_title))

        if (!hasLatest && !hasBest) {
            HabitEmptyState(stringResource(R.string.reports_no_data))
        } else {
            if (hasLatest) {
                BasicText(
                    text = stringResource(R.string.latest_streak),
                    style = TextStyle(color = Color(colors.secondary), fontSize = 12.sp, fontWeight = FontWeight.Medium),
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                AndroidView(
                    factory = { ctx ->
                        StreakChart(ctx).apply {
                            setColor(androidColor)
                            sharedMaxLength?.let { setMaxLengthOverride(it) }
                            setStreaks(listOfNotNull(validLatest))
                        }
                    },
                    update = { chart ->
                        chart.setColor(androidColor)
                        sharedMaxLength?.let { chart.setMaxLengthOverride(it) }
                        chart.setStreaks(listOfNotNull(validLatest))
                        chart.postInvalidate()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(20.dp)
                        .clipToBounds()
                )
                Spacer(Modifier.height(10.dp))
            }

            if (hasBest) {
                BasicText(
                    text = stringResource(R.string.best_streaks),
                    style = TextStyle(color = Color(colors.secondary), fontSize = 12.sp, fontWeight = FontWeight.Medium),
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                val chartHeight = (displayBest.size * 20).dp
                AndroidView(
                    factory = { ctx ->
                        StreakChart(ctx).apply {
                            setColor(androidColor)
                            sharedMaxLength?.let { setMaxLengthOverride(it) }
                            setStreaks(displayBest)
                        }
                    },
                    update = { chart ->
                        chart.setColor(androidColor)
                        sharedMaxLength?.let { chart.setMaxLengthOverride(it) }
                        chart.setStreaks(displayBest)
                        chart.postInvalidate()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(chartHeight)
                        .clipToBounds()
                )
            }
        }
    }
}

/** Day-of-week frequency distribution section. */
@Composable
fun HabitFrequencySection(
    state: FrequencyCardState,
    modifier: Modifier = Modifier
) {
    val androidColor = state.theme.color(state.color).toInt()

    HabitSectionCard(modifier = modifier) {
        HabitSectionHeader(title = stringResource(R.string.frequency))

        if (state.frequency.isEmpty()) {
            HabitEmptyState(stringResource(R.string.reports_no_data))
        } else {
            AndroidView(
                factory = { ctx ->
                    FrequencyChart(ctx).apply {
                        setFrequency(state.frequency)
                        setIsNumerical(state.isNumerical)
                        setFirstWeekday(state.firstWeekday)
                        setColor(androidColor)
                    }
                },
                update = { chart ->
                    chart.setFrequency(state.frequency)
                    chart.setIsNumerical(state.isNumerical)
                    chart.setFirstWeekday(state.firstWeekday)
                    chart.setColor(androidColor)
                    chart.postInvalidate()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clipToBounds()
            )
        }
    }
}
