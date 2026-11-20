/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.isoron.uhabits.activities.statistics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils
import org.isoron.platform.time.DayOfWeek
import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.R
import org.isoron.uhabits.compose.LocalUHabitComposeColors
import org.isoron.uhabits.compose.UHabitComposeColors
import org.isoron.uhabits.core.ui.screens.statistics.StatisticsDailyProgress
import kotlin.math.roundToInt

internal data class LegacyPercentageBarItem(
    val progress: Double?,
    val label: String,
    val isToday: Boolean = false,
    val isFuture: Boolean = false
)

internal data class LegacyWeekRhythmItem(
    val dayOfWeek: DayOfWeek,
    val progress: Double?,
    val label: String,
    val isToday: Boolean = false,
    val isFuture: Boolean = false
)

internal data class LegacyBucketItem(
    val label: String,
    val progress: Double?
)

internal data class LegacyMonthCalendarMetrics(
    val averageProgress: Double?,
    val averageText: String,
    val bestDayProgress: Double?,
    val bestDayPct: String,
    val bestDayDate: String,
    val firstDowOffset: Int,
    val totalDays: Int,
    val numWeeks: Int
)

internal fun formatLegacyPercent(progress: Double): String =
    "${(progress.coerceIn(0.0, 1.0) * 100.0).roundToInt()}%"

internal fun resolveCalendarIntensityAlpha(progress: Double): Float {
    val clamped = progress.coerceIn(0.0, 1.0)
    return when {
        clamped >= 1.0 -> 245f / 255f
        clamped >= 0.67 -> 185f / 255f
        clamped >= 0.34 -> 125f / 255f
        clamped > 0.0 -> 70f / 255f
        else -> 0f
    }
}

internal fun computeLegacyMonthCalendarMetrics(
    monthStart: LocalDate,
    monthEnd: LocalDate,
    firstWeekday: DayOfWeek,
    days: List<StatisticsDailyProgress>,
    averageProgress: Double?,
    shortMonthName: (LocalDate) -> String
): LegacyMonthCalendarMetrics {
    val averageText = averageProgress?.let(::formatLegacyPercent) ?: "—"
    val bestDay = days
        .filter { !it.isFuture && it.progress != null }
        .maxByOrNull { it.progress!! }
    val bestProgress = bestDay?.progress?.takeIf { it > 0.0 }
    val bestPct = bestProgress?.let(::formatLegacyPercent) ?: "—"
    val bestDate = if (bestDay != null && bestProgress != null) {
        "${bestDay.date.day} ${shortMonthName(bestDay.date)}"
    } else {
        ""
    }
    val firstDowOffset = (monthStart.dayOfWeek.ordinal - firstWeekday.ordinal + 7) % 7
    val totalDays = (monthStart.daysUntil(monthEnd) + 1).coerceAtLeast(1)
    val numWeeks = (firstDowOffset + totalDays + 6) / 7

    return LegacyMonthCalendarMetrics(
        averageProgress = averageProgress,
        averageText = averageText,
        bestDayProgress = bestProgress,
        bestDayPct = bestPct,
        bestDayDate = bestDate,
        firstDowOffset = firstDowOffset,
        totalDays = totalDays,
        numWeeks = numWeeks
    )
}

internal fun resolveCalendarDayAtPoint(
    touchX: Float,
    touchY: Float,
    startX: Float,
    startY: Float,
    cellSizePx: Float,
    spacingPx: Float,
    firstDowOffset: Int,
    totalDays: Int,
    monthStart: LocalDate,
    isRtl: Boolean
): Pair<LocalDate, Rect>? {
    val stride = cellSizePx + spacingPx
    if (stride <= 0f) return null
    val relX = touchX - startX
    val relY = touchY - startY
    if (relX < 0f || relY < 0f) return null
    val visualCol = (relX / stride).toInt()
    val row = (relY / stride).toInt()
    if (visualCol !in 0..6 || row < 0) return null
    val logicalCol = if (isRtl) 6 - visualCol else visualCol
    val dayIndex = row * 7 + logicalCol - firstDowOffset
    if (dayIndex !in 0 until totalDays) return null
    val left = startX + visualCol * stride
    val top = startY + row * stride
    return monthStart.plus(dayIndex) to Rect(left, top, left + cellSizePx, top + cellSizePx)
}

@Composable
internal fun LegacyPercentageBarChart(
    items: List<LegacyPercentageBarItem>,
    modifier: Modifier = Modifier,
    colors: UHabitComposeColors = LocalUHabitComposeColors.current
) {
    if (items.isEmpty()) return
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl

    val gridLabelStyle = remember(colors.secondary) {
        TextStyle(
            fontSize = 9.sp,
            color = colors.secondaryColor.copy(alpha = 140f / 255f),
            fontFeatureSettings = "tnum"
        )
    }
    val valueLabelStyle = remember(colors.accent) {
        TextStyle(
            fontSize = 9.5.sp,
            color = colors.accentColor,
            fontFeatureSettings = "tnum"
        )
    }
    val axisRegularStyle = remember(colors.secondary) {
        TextStyle(fontSize = 10.sp, color = colors.secondaryColor)
    }
    val axisTodayStyle = remember(colors.accent) {
        TextStyle(fontSize = 10.sp, color = colors.accentColor, fontWeight = FontWeight.Bold)
    }
    val axisFutureStyle = remember(colors.secondary) {
        TextStyle(fontSize = 10.sp, color = colors.secondaryColor.copy(alpha = 90f / 255f))
    }

    val sampleGridSize = remember(textMeasurer, gridLabelStyle, density.fontScale) {
        textMeasurer.measure("100%", gridLabelStyle).size
    }
    val sampleValueSize = remember(textMeasurer, valueLabelStyle, density.fontScale) {
        textMeasurer.measure("100%", valueLabelStyle).size
    }
    val sampleAxisSize = remember(textMeasurer, axisRegularStyle, density.fontScale) {
        textMeasurer.measure("Wed", axisRegularStyle).size
    }

    val yAxisDp = with(density) { maxOf(34.dp, sampleGridSize.width.toDp() + 8.dp) }
    val plotTopDp = with(density) { maxOf(28.dp, sampleValueSize.height.toDp() + 10.dp) }
    val plotBottomPadDp = with(density) { maxOf(24.dp, sampleAxisSize.height.toDp() + 10.dp) }
    val chartHeightDp = maxOf(148.dp, plotTopDp + 96.dp + plotBottomPadDp)
    val minColDp = with(density) { maxOf(22.dp, sampleValueSize.width.toDp() + 4.dp) }

    val summaryDesc = remember(items) {
        items.joinToString(", ") { item ->
            val pct = if (!item.isFuture && item.progress != null) {
                formatLegacyPercent(item.progress)
            } else {
                "—"
            }
            "${item.label} $pct"
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        if (!constraints.hasBoundedWidth) return@BoxWithConstraints
        val requiredWidth = yAxisDp + 12.dp + minColDp * items.size
        val needsHorizontalScroll = requiredWidth > maxWidth
        val canvasWidthModifier = if (needsHorizontalScroll) {
            Modifier.width(requiredWidth)
        } else {
            Modifier.fillMaxWidth()
        }

        val gridColor = colors.secondaryColor.copy(alpha = 38f / 255f)
        val barColor = colors.accentColor

        Box(
            modifier = if (needsHorizontalScroll) {
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
            } else {
                Modifier.fillMaxWidth()
            }
        ) {
            Canvas(
                modifier = canvasWidthModifier
                    .height(chartHeightDp)
                    .semantics { contentDescription = summaryDesc }
            ) {
                val w = size.width
                val h = size.height
                val yAxisPx = yAxisDp.toPx()
                val sidePadPx = 12.dp.toPx()
                val plotLeft = if (isRtl) sidePadPx else yAxisPx
                val plotRight = if (isRtl) w - yAxisPx else w - sidePadPx
                val plotTop = plotTopDp.toPx()
                val plotBottom = h - plotBottomPadDp.toPx()
                val plotHeight = (plotBottom - plotTop).coerceAtLeast(1f)
                val gridStrokePx = 0.5.dp.toPx()

                // 1. Horizontal grid lines (75%, 50%, 25%, 0%) and Y-axis labels (100%..0%)
                val gridPcts = intArrayOf(100, 75, 50, 25, 0)
                for (pct in gridPcts) {
                    val y = plotTop + plotHeight * (1f - pct / 100f)
                    if (pct < 100) {
                        drawLine(
                            color = gridColor,
                            start = Offset(plotLeft, y),
                            end = Offset(plotRight, y),
                            strokeWidth = gridStrokePx
                        )
                    }
                    val layout = textMeasurer.measure("$pct%", gridLabelStyle)
                    val labelX = if (isRtl) {
                        plotRight + 4.dp.toPx()
                    } else {
                        (plotLeft - 4.dp.toPx() - layout.size.width).coerceAtLeast(0f)
                    }
                    val labelY = y - layout.size.height / 2f
                    drawText(layout, topLeft = Offset(labelX, labelY))
                }

                // 2. Columns, bars, value labels, and X-axis footer labels
                val colWidth = (plotRight - plotLeft) / items.size
                val maxBarW = 12.dp.toPx()
                val barWidth = minOf(maxBarW, colWidth * 0.44f)
                val cornerRadius = CornerRadius(barWidth * 0.15f, barWidth * 0.15f)
                val zeroTickHeight = 1.5.dp.toPx()
                val zeroCorner = CornerRadius(0.75.dp.toPx(), 0.75.dp.toPx())

                items.forEachIndexed { i, item ->
                    val visualIndex = if (isRtl) items.lastIndex - i else i
                    val cx = plotLeft + (visualIndex + 0.5f) * colWidth

                    val axisStyle = when {
                        item.isToday -> axisTodayStyle
                        item.isFuture -> axisFutureStyle
                        else -> axisRegularStyle
                    }
                    val axisLayout = textMeasurer.measure(item.label, axisStyle)
                    drawText(
                        textLayoutResult = axisLayout,
                        topLeft = Offset(
                            x = (cx - axisLayout.size.width / 2f).coerceIn(0f, (w - axisLayout.size.width).coerceAtLeast(0f)),
                            y = (h - 4.dp.toPx() - axisLayout.size.height).coerceAtLeast(plotBottom + 2.dp.toPx())
                        )
                    )

                    if (!item.isFuture && item.progress != null) {
                        val progress = item.progress.coerceIn(0.0, 1.0).toFloat()
                        val barH = progress * plotHeight
                        if (barH > 0f) {
                            val actualH = barH.coerceAtLeast(2.dp.toPx())
                            drawRoundRect(
                                color = barColor,
                                topLeft = Offset(cx - barWidth / 2f, plotBottom - actualH),
                                size = Size(barWidth, actualH),
                                cornerRadius = cornerRadius
                            )
                        } else {
                            drawRoundRect(
                                color = barColor,
                                topLeft = Offset(cx - barWidth / 2f, plotBottom - zeroTickHeight),
                                size = Size(barWidth, zeroTickHeight),
                                cornerRadius = zeroCorner
                            )
                        }

                        val valueLayout = textMeasurer.measure(formatLegacyPercent(item.progress), valueLabelStyle)
                        val barTopY = if (barH > 0f) {
                            plotBottom - barH.coerceAtLeast(2.dp.toPx())
                        } else {
                            plotBottom - zeroTickHeight
                        }
                        val valueTopY = (barTopY - 3.dp.toPx() - valueLayout.size.height).coerceAtLeast(0f)
                        drawText(
                            textLayoutResult = valueLayout,
                            topLeft = Offset(
                                x = (cx - valueLayout.size.width / 2f).coerceIn(0f, (w - valueLayout.size.width).coerceAtLeast(0f)),
                                y = valueTopY
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun LegacyMonthCalendarChart(
    monthStart: LocalDate,
    monthEnd: LocalDate,
    firstWeekday: DayOfWeek,
    days: List<StatisticsDailyProgress>,
    averageProgress: Double?,
    shortWeekdayName: (DayOfWeek) -> String,
    shortMonthName: (LocalDate) -> String,
    modifier: Modifier = Modifier,
    colors: UHabitComposeColors = LocalUHabitComposeColors.current
) {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl

    val daysLookup = remember(days) { days.associateBy { it.date } }
    val metrics = remember(monthStart, monthEnd, firstWeekday, days, averageProgress) {
        computeLegacyMonthCalendarMetrics(
            monthStart = monthStart,
            monthEnd = monthEnd,
            firstWeekday = firstWeekday,
            days = days,
            averageProgress = averageProgress,
            shortMonthName = shortMonthName
        )
    }

    val surfaceColor = colors.resolveSectionSurfaceColor()
    val onSurfaceColor = colors.foregroundColor
    val onSurfaceVariantColor = colors.secondaryColor
    val accentColor = colors.accentColor
    val dividerColor = colors.resolveDividerColor()

    val avgLabel = stringResource(R.string.statistics_month_average)
    val bestLabel = stringResource(R.string.statistics_month_best_short)
    val noDataLabel = stringResource(R.string.statistics_no_data)
    val skippedLabel = stringResource(R.string.statistics_skipped_label)

    var inspectedDate by remember(monthStart, monthEnd) { mutableStateOf<LocalDate?>(null) }
    var inspectedCellRect by remember(monthStart, monthEnd) { mutableStateOf<Rect?>(null) }

    val headerStyle = remember(onSurfaceVariantColor) {
        TextStyle(fontSize = 9.5.sp, color = onSurfaceVariantColor)
    }
    val ringTextStyle = remember(onSurfaceColor) {
        TextStyle(
            fontSize = 12.sp,
            color = onSurfaceColor,
            fontWeight = FontWeight.Bold,
            fontFeatureSettings = "tnum"
        )
    }
    val sideTitleStyle = remember(onSurfaceVariantColor) {
        TextStyle(fontSize = 10.sp, color = onSurfaceVariantColor.copy(alpha = 180f / 255f))
    }
    val sideDateStyle = remember(onSurfaceVariantColor) {
        TextStyle(fontSize = 8.5.sp, color = onSurfaceVariantColor.copy(alpha = 140f / 255f))
    }
    val legendStyle = remember(onSurfaceVariantColor) {
        TextStyle(
            fontSize = 9.sp,
            color = onSurfaceVariantColor.copy(alpha = 180f / 255f),
            fontFeatureSettings = "tnum"
        )
    }
    val tooltipTextStyle = remember(onSurfaceColor) {
        TextStyle(
            fontSize = 11.sp,
            color = onSurfaceColor,
            fontWeight = FontWeight.Bold,
            fontFeatureSettings = "tnum"
        )
    }

    val weekdayHeaders = remember(firstWeekday, shortWeekdayName) {
        List(7) { col ->
            val dow = DayOfWeek.entries[(firstWeekday.ordinal + col) % 7]
            shortWeekdayName(dow).take(2)
        }
    }

    val cellSizeDp = 19.dp
    val spacingDp = 2.5.dp
    val totalGridWidthDp = cellSizeDp * 7 + spacingDp * 6
    val headerHeightDp = with(density) {
        maxOf(18.dp, textMeasurer.measure("Mo", headerStyle).size.height.toDp() + 4.dp)
    }
    val legendHeightDp = with(density) {
        maxOf(32.dp, textMeasurer.measure("100%", legendStyle).size.height.toDp() + 18.dp)
    }
    val canvasHeightDp = headerHeightDp + (cellSizeDp + spacingDp) * metrics.numWeeks + legendHeightDp

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        if (!constraints.hasBoundedWidth) return@BoxWithConstraints
        val sideWidthDp = (maxWidth - totalGridWidthDp) / 2f
        val showSideRings = sideWidthDp > 50.dp && density.fontScale <= 1.45f

        Column(modifier = Modifier.fillMaxWidth()) {
            if (!showSideRings) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = metrics.averageText,
                            color = onSurfaceColor,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = avgLabel,
                            color = onSurfaceVariantColor,
                            fontSize = 11.sp
                        )
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = metrics.bestDayPct,
                            color = onSurfaceColor,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (metrics.bestDayDate.isNotEmpty()) {
                                "$bestLabel · ${metrics.bestDayDate}"
                            } else {
                                bestLabel
                            },
                            color = onSurfaceVariantColor,
                            fontSize = 11.sp
                        )
                    }
                }
            }

            val calendarSummary = "$avgLabel ${metrics.averageText}, $bestLabel ${metrics.bestDayPct}"

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(canvasHeightDp)
                    .semantics { contentDescription = calendarSummary }
                    .pointerInput(monthStart, monthEnd, firstWeekday, isRtl, metrics) {
                        val cellSizePx = cellSizeDp.toPx()
                        val spacingPx = spacingDp.toPx()
                        val headerHeightPx = headerHeightDp.toPx()
                        val totalGridWidthPx = 7f * cellSizePx + 6f * spacingPx

                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val startX = (size.width - totalGridWidthPx) / 2f
                            val startY = headerHeightPx
                            val initialHit = resolveCalendarDayAtPoint(
                                touchX = down.position.x,
                                touchY = down.position.y,
                                startX = startX,
                                startY = startY,
                                cellSizePx = cellSizePx,
                                spacingPx = spacingPx,
                                firstDowOffset = metrics.firstDowOffset,
                                totalDays = metrics.totalDays,
                                monthStart = monthStart,
                                isRtl = isRtl
                            )
                            if (initialHit == null) return@awaitEachGesture

                            down.consume()
                            inspectedDate = initialHit.first
                            inspectedCellRect = initialHit.second
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)

                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: break
                                if (!change.pressed) {
                                    change.consume()
                                    break
                                }
                                change.consume()
                                val hit = resolveCalendarDayAtPoint(
                                    touchX = change.position.x,
                                    touchY = change.position.y,
                                    startX = startX,
                                    startY = startY,
                                    cellSizePx = cellSizePx,
                                    spacingPx = spacingPx,
                                    firstDowOffset = metrics.firstDowOffset,
                                    totalDays = metrics.totalDays,
                                    monthStart = monthStart,
                                    isRtl = isRtl
                                )
                                if (hit != null) {
                                    if (inspectedDate != hit.first) {
                                        inspectedDate = hit.first
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
                                    inspectedCellRect = hit.second
                                } else if (inspectedDate != null) {
                                    inspectedDate = null
                                    inspectedCellRect = null
                                }
                            }
                            inspectedDate = null
                            inspectedCellRect = null
                        }
                    }
            ) {
                val cellSize = cellSizeDp.toPx()
                val spacing = spacingDp.toPx()
                val cornerRadius = CornerRadius(2.5.dp.toPx(), 2.5.dp.toPx())
                val headerHeight = headerHeightDp.toPx()
                val totalGridWidth = 7f * cellSize + 6f * spacing
                val startX = (size.width - totalGridWidth) / 2f
                val gridTop = headerHeight

                // 1 & 2. Symmetric side progress rings when space permits
                if (showSideRings) {
                    val ringRadius = 20.dp.toPx()
                    val ringCenterY = headerHeight + 28.dp.toPx()
                    val ringStroke = Stroke(width = 3.dp.toPx())
                    val ringSize = Size(ringRadius * 2f, ringRadius * 2f)

                    val startCx = if (isRtl) {
                        startX + totalGridWidth + (size.width - (startX + totalGridWidth)) / 2f
                    } else {
                        startX / 2f
                    }
                    val endCx = if (isRtl) {
                        startX / 2f
                    } else {
                        startX + totalGridWidth + (size.width - (startX + totalGridWidth)) / 2f
                    }

                    // Average ring
                    val avgTopLeft = Offset(startCx - ringRadius, ringCenterY - ringRadius)
                    drawArc(
                        color = dividerColor,
                        startAngle = -90f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = avgTopLeft,
                        size = ringSize,
                        style = ringStroke
                    )
                    metrics.averageProgress?.let { p ->
                        drawArc(
                            color = accentColor,
                            startAngle = -90f,
                            sweepAngle = (p.coerceIn(0.0, 1.0) * 360.0).toFloat(),
                            useCenter = false,
                            topLeft = avgTopLeft,
                            size = ringSize,
                            style = ringStroke
                        )
                    }
                    val avgValueLayout = textMeasurer.measure(metrics.averageText, ringTextStyle)
                    drawText(
                        avgValueLayout,
                        topLeft = Offset(
                            startCx - avgValueLayout.size.width / 2f,
                            ringCenterY - avgValueLayout.size.height / 2f
                        )
                    )
                    val avgTitleLayout = textMeasurer.measure(avgLabel, sideTitleStyle)
                    drawText(
                        avgTitleLayout,
                        topLeft = Offset(
                            startCx - avgTitleLayout.size.width / 2f,
                            ringCenterY + ringRadius + 4.dp.toPx()
                        )
                    )

                    // Best day ring
                    val bestTopLeft = Offset(endCx - ringRadius, ringCenterY - ringRadius)
                    drawArc(
                        color = dividerColor,
                        startAngle = -90f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = bestTopLeft,
                        size = ringSize,
                        style = ringStroke
                    )
                    metrics.bestDayProgress?.let { p ->
                        drawArc(
                            color = accentColor,
                            startAngle = -90f,
                            sweepAngle = (p.coerceIn(0.0, 1.0) * 360.0).toFloat(),
                            useCenter = false,
                            topLeft = bestTopLeft,
                            size = ringSize,
                            style = ringStroke
                        )
                    }
                    val bestValueLayout = textMeasurer.measure(metrics.bestDayPct, ringTextStyle)
                    drawText(
                        bestValueLayout,
                        topLeft = Offset(
                            endCx - bestValueLayout.size.width / 2f,
                            ringCenterY - bestValueLayout.size.height / 2f
                        )
                    )
                    val bestTitleLayout = textMeasurer.measure(bestLabel, sideTitleStyle)
                    val bestTitleY = ringCenterY + ringRadius + 4.dp.toPx()
                    drawText(
                        bestTitleLayout,
                        topLeft = Offset(endCx - bestTitleLayout.size.width / 2f, bestTitleY)
                    )
                    if (metrics.bestDayDate.isNotEmpty()) {
                        val bestDateLayout = textMeasurer.measure(metrics.bestDayDate, sideDateStyle)
                        drawText(
                            bestDateLayout,
                            topLeft = Offset(
                                endCx - bestDateLayout.size.width / 2f,
                                bestTitleY + bestTitleLayout.size.height + 1.dp.toPx()
                            )
                        )
                    }
                }

                // 3. Weekday headers
                for (col in 0..6) {
                    val visualCol = if (isRtl) 6 - col else col
                    val cx = startX + visualCol * (cellSize + spacing) + cellSize / 2f
                    val headerLayout = textMeasurer.measure(weekdayHeaders[col], headerStyle)
                    drawText(
                        headerLayout,
                        topLeft = Offset(
                            cx - headerLayout.size.width / 2f,
                            (headerHeight - headerLayout.size.height) / 2f
                        )
                    )
                }

                // 4. Calendar cells
                val emptyPastCellColor = lerp(surfaceColor, onSurfaceVariantColor, 0.12f)
                val skippedCellColor = lerp(surfaceColor, onSurfaceVariantColor, 0.22f)
                val opaqueSurfaceArgb = ColorUtils.setAlphaComponent(surfaceColor.toArgb(), 255)
                val opaqueOnSurfaceArgb = ColorUtils.setAlphaComponent(onSurfaceColor.toArgb(), 255)
                val dayNumberFontSize = (8.5f / density.fontScale.coerceAtLeast(1f)).coerceAtLeast(6.5f).sp

                var current = monthStart
                var dayIndex = 0
                while (current <= monthEnd) {
                    val slot = metrics.firstDowOffset + dayIndex
                    val logicalCol = slot % 7
                    val visualCol = if (isRtl) 6 - logicalCol else logicalCol
                    val row = slot / 7

                    val left = startX + visualCol * (cellSize + spacing)
                    val top = gridTop + row * (cellSize + spacing)
                    val cellTopLeft = Offset(left, top)
                    val cellDims = Size(cellSize, cellSize)

                    val daily = daysLookup[current]
                    val isFuture = daily?.isFuture == true
                    val isSkipped = daily?.isSkipped == true
                    val isToday = daily?.isToday == true
                    val progress = daily?.progress

                    var hasFill = false
                    var fillColor = surfaceColor

                    when {
                        isFuture -> Unit
                        isSkipped -> {
                            fillColor = skippedCellColor
                            hasFill = true
                        }
                        progress != null && progress > 0.0 -> {
                            fillColor = accentColor.copy(alpha = resolveCalendarIntensityAlpha(progress))
                            hasFill = true
                        }
                        progress != null && progress <= 0.0 -> {
                            fillColor = emptyPastCellColor
                            hasFill = true
                        }
                    }

                    if (hasFill) {
                        drawRoundRect(
                            color = fillColor,
                            topLeft = cellTopLeft,
                            size = cellDims,
                            cornerRadius = cornerRadius
                        )
                    }

                    if (isToday) {
                        drawRoundRect(
                            color = accentColor,
                            topLeft = cellTopLeft,
                            size = cellDims,
                            cornerRadius = cornerRadius,
                            style = Stroke(width = 1.5.dp.toPx())
                        )
                    }

                    if (current == inspectedDate) {
                        drawRoundRect(
                            color = onSurfaceColor,
                            topLeft = cellTopLeft,
                            size = cellDims,
                            cornerRadius = cornerRadius,
                            style = Stroke(width = 2.dp.toPx())
                        )
                    }

                    val dayTextColor = when {
                        isFuture -> onSurfaceVariantColor.copy(alpha = 80f / 255f)
                        hasFill && (progress ?: 0.0) >= 0.67 -> {
                            val opaqueFill = ColorUtils.compositeColors(fillColor.toArgb(), opaqueSurfaceArgb)
                            val contrastSurface = ColorUtils.calculateContrast(opaqueSurfaceArgb, opaqueFill)
                            val contrastOnSurface = ColorUtils.calculateContrast(opaqueOnSurfaceArgb, opaqueFill)
                            if (contrastSurface >= contrastOnSurface) surfaceColor else onSurfaceColor
                        }
                        else -> onSurfaceVariantColor
                    }

                    val dayLayout = textMeasurer.measure(
                        text = current.day.toString(),
                        style = TextStyle(
                            fontSize = dayNumberFontSize,
                            color = dayTextColor,
                            fontFeatureSettings = "tnum"
                        )
                    )
                    drawText(
                        dayLayout,
                        topLeft = Offset(
                            left + (cellSize - dayLayout.size.width) / 2f,
                            top + (cellSize - dayLayout.size.height) / 2f
                        )
                    )

                    current = current.plus(1)
                    dayIndex++
                }

                // 5. Bottom centered intensity legend (100% [][][][][] 0%)
                val gridBottom = gridTop + metrics.numWeeks * (cellSize + spacing)
                val legendBoxY = gridBottom + 10.dp.toPx()
                val legendCell = 9.dp.toPx()
                val legendGap = 4.dp.toPx()
                val legendWidth = 5f * legendCell + 4f * legendGap
                val legendStartX = (size.width - legendWidth) / 2f
                val discreteAlphas = floatArrayOf(245f / 255f, 185f / 255f, 125f / 255f, 70f / 255f, 0f)
                val legendCorner = CornerRadius(1.5.dp.toPx(), 1.5.dp.toPx())

                for (level in 0..4) {
                    val swatchColor = if (level == 4) {
                        emptyPastCellColor
                    } else {
                        accentColor.copy(alpha = discreteAlphas[level])
                    }
                    val boxX = legendStartX + level * (legendCell + legendGap)
                    drawRoundRect(
                        color = swatchColor,
                        topLeft = Offset(boxX, legendBoxY),
                        size = Size(legendCell, legendCell),
                        cornerRadius = legendCorner
                    )
                }

                val fullLayout = textMeasurer.measure("100%", legendStyle)
                val zeroLayout = textMeasurer.measure("0%", legendStyle)
                val legendCenterY = legendBoxY + legendCell / 2f
                drawText(
                    fullLayout,
                    topLeft = Offset(
                        (legendStartX - 6.dp.toPx() - fullLayout.size.width).coerceAtLeast(0f),
                        legendCenterY - fullLayout.size.height / 2f
                    )
                )
                drawText(
                    zeroLayout,
                    topLeft = Offset(
                        (legendStartX + legendWidth + 6.dp.toPx()).coerceAtMost(size.width - zeroLayout.size.width),
                        legendCenterY - zeroLayout.size.height / 2f
                    )
                )

                // 6. Touch inspection tooltip overlay
                val activeDate = inspectedDate
                val activeRect = inspectedCellRect
                if (activeDate != null && activeRect != null) {
                    val daily = daysLookup[activeDate]
                    val dateLabel = "${activeDate.day} ${shortMonthName(activeDate)}"
                    val p = daily?.progress
                    val valText = when {
                        daily == null || daily.isFuture || p == null -> noDataLabel
                        daily.isSkipped -> skippedLabel
                        else -> formatLegacyPercent(p)
                    }
                    val tooltipLayout = textMeasurer.measure("$dateLabel · $valText", tooltipTextStyle)
                    val padH = 8.dp.toPx()
                    val tipW = tooltipLayout.size.width + padH * 2f
                    val tipH = maxOf(24.dp.toPx(), tooltipLayout.size.height + 8.dp.toPx())
                    val tipX = (activeRect.center.x - tipW / 2f)
                        .coerceIn(8.dp.toPx(), (size.width - tipW - 8.dp.toPx()).coerceAtLeast(8.dp.toPx()))
                    var tipY = activeRect.top - 56.dp.toPx()
                    if (tipY < 4.dp.toPx()) {
                        tipY = activeRect.bottom + 20.dp.toPx()
                    }
                    tipY = tipY.coerceIn(4.dp.toPx(), (size.height - tipH - 4.dp.toPx()).coerceAtLeast(4.dp.toPx()))

                    val tipCorner = CornerRadius(6.dp.toPx(), 6.dp.toPx())
                    drawRoundRect(
                        color = lerp(surfaceColor, onSurfaceColor, 0.18f),
                        topLeft = Offset(tipX, tipY),
                        size = Size(tipW, tipH),
                        cornerRadius = tipCorner
                    )
                    drawRoundRect(
                        color = dividerColor.copy(alpha = 120f / 255f),
                        topLeft = Offset(tipX, tipY),
                        size = Size(tipW, tipH),
                        cornerRadius = tipCorner,
                        style = Stroke(width = 1.dp.toPx())
                    )
                    drawText(
                        tooltipLayout,
                        topLeft = Offset(
                            tipX + (tipW - tooltipLayout.size.width) / 2f,
                            tipY + (tipH - tooltipLayout.size.height) / 2f
                        )
                    )
                }
            }
        }
    }
}

@Composable
internal fun LegacyWeekRhythmChart(
    items: List<LegacyWeekRhythmItem>,
    modifier: Modifier = Modifier,
    colors: UHabitComposeColors = LocalUHabitComposeColors.current
) {
    if (items.isEmpty()) return
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl

    val valueStyle = remember(colors.secondary) {
        TextStyle(
            fontSize = 10.sp,
            color = colors.secondaryColor,
            fontFeatureSettings = "tnum"
        )
    }
    val labelRegularStyle = remember(colors.secondary) {
        TextStyle(fontSize = 10.sp, color = colors.secondaryColor)
    }
    val labelTodayStyle = remember(colors.accent) {
        TextStyle(fontSize = 10.sp, color = colors.accentColor, fontWeight = FontWeight.Bold)
    }
    val labelFutureStyle = remember(colors.secondary) {
        TextStyle(fontSize = 10.sp, color = colors.secondaryColor.copy(alpha = 100f / 255f))
    }

    val sampleValueSize = remember(textMeasurer, valueStyle, density.fontScale) {
        textMeasurer.measure("100%", valueStyle).size
    }
    val sampleLabelSize = remember(textMeasurer, labelRegularStyle, density.fontScale) {
        textMeasurer.measure("Wed", labelRegularStyle).size
    }

    val topPadDp = with(density) { maxOf(18.dp, sampleValueSize.height.toDp() + 6.dp) }
    val bottomPadDp = with(density) { maxOf(24.dp, sampleLabelSize.height.toDp() + 10.dp) }
    val chartHeightDp = maxOf(100.dp, topPadDp + 58.dp + bottomPadDp)
    val minColDp = with(density) { maxOf(28.dp, sampleValueSize.width.toDp() + 4.dp) }

    val summaryDesc = remember(items) {
        items.joinToString(", ") { item ->
            val pct = if (!item.isFuture && item.progress != null) {
                formatLegacyPercent(item.progress)
            } else {
                "—"
            }
            "${item.label} $pct"
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        if (!constraints.hasBoundedWidth) return@BoxWithConstraints
        val columnCount = maxOf(items.size, 7)
        val requiredWidth = minColDp * columnCount
        val needsHorizontalScroll = requiredWidth > maxWidth
        val canvasWidthModifier = if (needsHorizontalScroll) {
            Modifier.width(requiredWidth)
        } else {
            Modifier.fillMaxWidth()
        }

        Box(
            modifier = if (needsHorizontalScroll) {
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
            } else {
                Modifier.fillMaxWidth()
            }
        ) {
            Canvas(
                modifier = canvasWidthModifier
                    .height(chartHeightDp)
                    .semantics { contentDescription = summaryDesc }
            ) {
                val w = size.width
                val h = size.height
                val colWidth = w / columnCount
                val barWidth = 10.dp.toPx()
                val baselineY = h - bottomPadDp.toPx()
                val topPadPx = topPadDp.toPx()
                val maxBarHeight = (baselineY - topPadPx).coerceAtLeast(1f)
                val corner = CornerRadius(barWidth * 0.15f, barWidth * 0.15f)

                items.forEachIndexed { i, item ->
                    val visualIndex = if (isRtl) items.lastIndex - i else i
                    val cx = visualIndex * colWidth + colWidth / 2f

                    val labelStyle = when {
                        item.isToday -> labelTodayStyle
                        item.isFuture -> labelFutureStyle
                        else -> labelRegularStyle
                    }
                    val labelLayout = textMeasurer.measure(item.label, labelStyle)
                    drawText(
                        labelLayout,
                        topLeft = Offset(
                            (cx - labelLayout.size.width / 2f).coerceIn(0f, (w - labelLayout.size.width).coerceAtLeast(0f)),
                            baselineY + 4.dp.toPx()
                        )
                    )

                    if (!item.isFuture && item.progress != null) {
                        val progress = item.progress.coerceIn(0.0, 1.0).toFloat()
                        val barH = progress * maxBarHeight
                        val clampedBarH = barH.coerceAtLeast(2.dp.toPx())
                        if (barH > 0f) {
                            drawRoundRect(
                                color = colors.accentColor,
                                topLeft = Offset(cx - barWidth / 2f, baselineY - clampedBarH),
                                size = Size(barWidth, clampedBarH),
                                cornerRadius = corner
                            )
                        }
                        val valueLayout = textMeasurer.measure(formatLegacyPercent(item.progress), valueStyle)
                        drawText(
                            valueLayout,
                            topLeft = Offset(
                                (cx - valueLayout.size.width / 2f).coerceIn(0f, (w - valueLayout.size.width).coerceAtLeast(0f)),
                                (baselineY - clampedBarH - 3.dp.toPx() - valueLayout.size.height).coerceAtLeast(0f)
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun LegacyBucketHistoryChart(
    items: List<LegacyBucketItem>,
    modifier: Modifier = Modifier,
    colors: UHabitComposeColors = LocalUHabitComposeColors.current
) {
    if (items.isEmpty()) return
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl

    val axisStyle = remember(colors.secondary) {
        TextStyle(fontSize = 10.sp, color = colors.secondaryColor)
    }
    val valueStyle = remember(colors.accent) {
        TextStyle(
            fontSize = 10.sp,
            color = colors.accentColor,
            fontFeatureSettings = "tnum"
        )
    }

    val sampleValueSize = remember(textMeasurer, valueStyle, density.fontScale) {
        textMeasurer.measure("100%", valueStyle).size
    }
    val sampleAxisSize = remember(textMeasurer, axisStyle, density.fontScale) {
        textMeasurer.measure("2026", axisStyle).size
    }

    val topPadDp = with(density) { maxOf(18.dp, sampleValueSize.height.toDp() + 6.dp) }
    val bottomPadDp = with(density) { maxOf(22.dp, sampleAxisSize.height.toDp() + 8.dp) }
    val chartHeightDp = maxOf(104.dp, topPadDp + 64.dp + bottomPadDp)
    val minColDp = with(density) { maxOf(30.dp, maxOf(sampleValueSize.width, sampleAxisSize.width).toDp() + 4.dp) }

    val summaryDesc = remember(items) {
        items.joinToString(", ") { item ->
            val pct = item.progress?.let(::formatLegacyPercent) ?: "—"
            "${item.label} $pct"
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        if (!constraints.hasBoundedWidth) return@BoxWithConstraints
        val scrollNeeded = items.size > 12 || (minColDp * items.size > maxWidth)
        val widthModifier = if (scrollNeeded) {
            Modifier.width(minColDp * items.size)
        } else {
            Modifier.fillMaxWidth()
        }

        Box(
            modifier = if (scrollNeeded) {
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
            } else {
                Modifier.fillMaxWidth()
            }
        ) {
            Canvas(
                modifier = widthModifier
                    .height(chartHeightDp)
                    .semantics { contentDescription = summaryDesc }
            ) {
                val w = size.width
                val h = size.height
                val column = w / items.size
                val barWidth = minOf(12.dp.toPx(), column * 0.42f)
                val bottom = h - bottomPadDp.toPx()
                val maxBarHeight = (bottom - topPadDp.toPx()).coerceAtMost(64.dp.toPx()).coerceAtLeast(1f)
                val corner = CornerRadius(barWidth * 0.15f, barWidth * 0.15f)

                items.forEachIndexed { index, item ->
                    val visualIndex = if (isRtl) items.lastIndex - index else index
                    val x = column * (visualIndex + 0.5f)
                    val axisLayout = textMeasurer.measure(item.label, axisStyle)
                    drawText(
                        axisLayout,
                        topLeft = Offset(
                            (x - axisLayout.size.width / 2f).coerceIn(0f, (w - axisLayout.size.width).coerceAtLeast(0f)),
                            (h - 4.dp.toPx() - axisLayout.size.height).coerceAtLeast(bottom + 2.dp.toPx())
                        )
                    )

                    val progress = item.progress ?: return@forEachIndexed
                    val fraction = progress.coerceIn(0.0, 1.0).toFloat()
                    val barHeight = maxBarHeight * fraction
                    val clampedBarH = barHeight.coerceAtLeast(2.dp.toPx())
                    if (barHeight > 0f) {
                        drawRoundRect(
                            color = colors.accentColor,
                            topLeft = Offset(x - barWidth / 2f, bottom - clampedBarH),
                            size = Size(barWidth, clampedBarH),
                            cornerRadius = corner
                        )
                    }
                    val valueLayout = textMeasurer.measure(formatLegacyPercent(progress), valueStyle)
                    drawText(
                        valueLayout,
                        topLeft = Offset(
                            (x - valueLayout.size.width / 2f).coerceIn(0f, (w - valueLayout.size.width).coerceAtLeast(0f)),
                            (bottom - clampedBarH - 4.dp.toPx() - valueLayout.size.height).coerceAtLeast(0f)
                        )
                    )
                }
            }
        }
    }
}
