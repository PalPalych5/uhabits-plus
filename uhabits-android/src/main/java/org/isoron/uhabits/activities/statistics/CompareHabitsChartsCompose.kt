/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.activities.statistics

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.isoron.platform.time.DayOfWeek
import org.isoron.platform.time.JavaLocalDateFormatter
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.countWeekdayOccurrencesInMonth
import org.isoron.platform.time.getToday
import org.isoron.platform.time.getWeekdaySequence
import org.isoron.uhabits.compose.LocalUHabitComposeColors
import org.isoron.uhabits.compose.UHabitComposeColors
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.Streak
import org.isoron.uhabits.core.models.projection.EffectiveHabitReadSnapshot
import org.isoron.uhabits.core.models.projection.HabitGoalStatus
import org.isoron.uhabits.core.models.projection.HabitProjectionIntegrityException
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Immutable series payload for [CompareMultiLineChartCompose].
 */
data class CompareLineSeriesUiModel(
    val habitId: Long,
    val label: String,
    val colorArgb: Int,
    val points: List<Pair<LocalDate, Double>>,
)

enum class CompareCalendarCellState {
    PRE_START,
    SKIPPED,
    EXCLUDED,
    SATISFIED,
    NOT_SATISFIED,
    EMPTY,
    FUTURE,
}

data class CompareCalendarCellUiModel(
    val date: LocalDate,
    val column: Int,
    val row: Int,
    val state: CompareCalendarCellState,
    val goalStatus: HabitGoalStatus?,
)

data class CompareCalendarGridUiModel(
    val habitId: Long,
    val habitName: String,
    val seriesColorArgb: Int,
    val firstWeekday: DayOfWeek,
    val monthLayout: Boolean,
    val columns: Int,
    val rows: Int,
    val weekdayLabels: List<String>,
    val cells: List<CompareCalendarCellUiModel>,
) {
    fun goalStatusOn(date: LocalDate): HabitGoalStatus? =
        cells.firstOrNull { it.date == date }?.goalStatus
}

/**
 * Deterministic chart geometry and data helpers isolated under `activities/statistics`.
 * Reusable across Compare Habits and future statistics charts.
 */
object CompareChartLogic {

    fun buildDateAxis(rangeStart: LocalDate, rangeEnd: LocalDate): List<LocalDate> {
        if (rangeStart > rangeEnd) return emptyList()
        val dates = mutableListOf<LocalDate>()
        var date = rangeStart
        while (date <= rangeEnd) {
            dates.add(date)
            date = date.plus(1)
        }
        return dates
    }

    fun scrubIndexForX(x: Float, plotLeft: Float, plotRight: Float, dateCount: Int): Int? {
        if (dateCount < 2 || plotRight <= plotLeft) return null
        val clampedX = x.coerceIn(plotLeft, plotRight)
        val fraction = (clampedX - plotLeft) / (plotRight - plotLeft)
        return (fraction * (dateCount - 1)).roundToInt().coerceIn(0, dateCount - 1)
    }

    fun resolveCalendarGoalStatus(
        snapshot: EffectiveHabitReadSnapshot?,
        date: LocalDate,
    ): HabitGoalStatus? {
        val effective = snapshot?.takeIf { it.hasContributions } ?: return null
        val day = effective.dayOrNull(date) ?: return HabitGoalStatus.UNKNOWN
        if (day.isSkipped) return HabitGoalStatus.SKIPPED
        if (!day.includedInStatistics) return HabitGoalStatus.EXCLUDED
        return try {
            effective.goalAchievementOn(date).status
        } catch (_: HabitProjectionIntegrityException) {
            HabitGoalStatus.UNKNOWN
        }
    }

    fun buildCalendarGrid(
        habit: Habit,
        seriesColorArgb: Int,
        from: LocalDate,
        to: LocalDate,
        today: LocalDate,
        firstWeekday: DayOfWeek,
        monthLayout: Boolean,
        snapshot: EffectiveHabitReadSnapshot? = null,
        locale: Locale = Locale.getDefault(),
    ): CompareCalendarGridUiModel {
        val formatter = JavaLocalDateFormatter(locale)
        val statsStart = habit.effectiveStatisticsStartDate()
        val offset = (from.dayOfWeek.ordinal - firstWeekday.ordinal + 7) % 7
        val days = (from.daysUntil(to) + 1).coerceAtLeast(0)
        val columns = if (monthLayout) 7 else (offset + days + 6) / 7
        val rows = if (monthLayout) (offset + days + 6) / 7 else 7
        val weekdayLabels = (0..6).map { weekday ->
            val day = DayOfWeek.entries[(firstWeekday.ordinal + weekday) % 7]
            formatter.shortWeekdayName(day).take(2)
        }

        val cells = mutableListOf<CompareCalendarCellUiModel>()
        var date = from
        var index = 0
        val effective = snapshot?.takeIf { it.hasContributions }
        while (date <= to) {
            val slot = offset + index
            val column = if (monthLayout) slot % 7 else slot / 7
            val row = if (monthLayout) slot / 7 else slot % 7
            val goalStatus = resolveCalendarGoalStatus(snapshot, date)
            val state = if (date > today) {
                CompareCalendarCellState.FUTURE
            } else if (statsStart != null && date < statsStart) {
                CompareCalendarCellState.PRE_START
            } else if (effective != null) {
                val day = effective.dayOrNull(date)
                when {
                    day?.isSkipped == true -> CompareCalendarCellState.SKIPPED
                    day?.includedInStatistics != true -> CompareCalendarCellState.EXCLUDED
                    goalStatus == HabitGoalStatus.SATISFIED -> CompareCalendarCellState.SATISFIED
                    goalStatus == HabitGoalStatus.NOT_SATISFIED -> CompareCalendarCellState.NOT_SATISFIED
                    else -> CompareCalendarCellState.EMPTY
                }
            } else {
                val value = habit.computedEntries.get(date).value
                when {
                    value == Entry.UNKNOWN -> CompareCalendarCellState.EMPTY
                    value == Entry.SKIP -> CompareCalendarCellState.SKIPPED
                    value == Entry.YES_MANUAL || value == Entry.YES_AUTO ||
                        (habit.isNumerical && value > 0) -> CompareCalendarCellState.SATISFIED
                    else -> CompareCalendarCellState.NOT_SATISFIED
                }
            }
            cells.add(
                CompareCalendarCellUiModel(
                    date = date,
                    column = column,
                    row = row,
                    state = state,
                    goalStatus = goalStatus,
                )
            )
            date = date.plus(1)
            index++
        }

        return CompareCalendarGridUiModel(
            habitId = habit.id ?: 0L,
            habitName = habit.name,
            seriesColorArgb = seriesColorArgb,
            firstWeekday = firstWeekday,
            monthLayout = monthLayout,
            columns = columns,
            rows = rows,
            weekdayLabels = weekdayLabels,
            cells = cells,
        )
    }

    fun stepMonth(date: LocalDate, months: Int): LocalDate {
        var y = date.year
        var m = date.month + months
        while (m < 1) {
            m += 12
            y -= 1
        }
        while (m > 12) {
            m -= 12
            y += 1
        }
        return LocalDate(y, m, 1)
    }

    fun maxFrequencyValue(frequency: Map<LocalDate, Array<Int>>): Int {
        var maxValue = 1
        for (values in frequency.values) {
            for (value in values) {
                maxValue = max(value, maxValue)
            }
        }
        return maxValue
    }
}

/**
 * Pure Compose Canvas multi-series line chart with 0..100% grid, point markers,
 * interactive scrubbing hairline + floating tooltip, and accessibility summary.
 */
@Composable
fun CompareMultiLineChartCompose(
    series: List<CompareLineSeriesUiModel>,
    hiddenHabitIds: Set<Long>,
    rangeStart: LocalDate,
    rangeEnd: LocalDate,
    modifier: Modifier = Modifier,
    colors: UHabitComposeColors = LocalUHabitComposeColors.current,
) {
    val allDates = remember(rangeStart, rangeEnd) {
        CompareChartLogic.buildDateAxis(rangeStart, rangeEnd)
    }
    val visibleSeries = remember(series, hiddenHabitIds) {
        series.filter { it.habitId !in hiddenHabitIds }
    }
    val seriesPointMaps = remember(visibleSeries) {
        visibleSeries.map { s -> s to s.points.toMap() }
    }
    val dateFormatter = remember { JavaLocalDateFormatter(Locale.getDefault()) }
    val density = LocalDensity.current
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val chartHeight = maxOf(200.dp, (168f + 32f * density.fontScale.coerceAtMost(2.2f)).dp)

    var scrubIndex by remember(rangeStart, rangeEnd) { mutableStateOf<Int?>(null) }
    var lastPlotLeft by remember { mutableFloatStateOf(0f) }
    var lastPlotRight by remember { mutableFloatStateOf(0f) }

    val surfaceColor = colors.resolveSectionSurfaceColor()
    val onSurfaceColor = colors.foregroundColor
    val onSurfaceVariantColor = colors.secondaryColor
    val dividerColor = colors.resolveDividerColor()
    val tooltipBgColor = lerp(surfaceColor, onSurfaceColor, 0.16f)
    val tooltipBorderColor = dividerColor.copy(alpha = 0.75f)

    val gridLabelPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.RIGHT
            fontFeatureSettings = "tnum"
        }
    }
    val axisLabelPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG)
    }
    val tooltipTextPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG)
    }

    val chartAccessibility = remember(visibleSeries, allDates) {
        if (allDates.isEmpty()) {
            ""
        } else {
            val dateSpan = "${allDates.first().day} ${dateFormatter.shortMonthName(allDates.first())} – " +
                "${allDates.last().day} ${dateFormatter.shortMonthName(allDates.last())}"
            val summaries = visibleSeries.mapNotNull { s ->
                val latest = s.points.lastOrNull()?.second ?: return@mapNotNull null
                "${s.label}: ${(latest * 100).roundToInt()}%"
            }
            if (summaries.isEmpty()) dateSpan else "$dateSpan. ${summaries.joinToString(", ")}"
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(chartHeight)
            .semantics { contentDescription = chartAccessibility }
            .pointerInput(allDates.size) {
                if (allDates.size < 2) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val downX = down.position.x
                    val downY = down.position.y
                    scrubIndex = CompareChartLogic.scrubIndexForX(downX, lastPlotLeft, lastPlotRight, allDates.size)
                    var horizontalLocked = false

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null || !change.pressed) {
                            scrubIndex = null
                            break
                        }
                        val dx = abs(change.position.x - downX)
                        val dy = abs(change.position.y - downY)
                        if (!horizontalLocked && dy > touchSlop && dy > dx * 1.4f) {
                            scrubIndex = null
                            break
                        }
                        if (dx > touchSlop) {
                            horizontalLocked = true
                        }
                        if (horizontalLocked) {
                            change.consume()
                        }
                        scrubIndex = CompareChartLogic.scrubIndexForX(
                            change.position.x,
                            lastPlotLeft,
                            lastPlotRight,
                            allDates.size,
                        )
                    }
                }
            },
    ) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        gridLabelPaint.textSize = 9.sp.toPx()
        gridLabelPaint.color = onSurfaceVariantColor.copy(alpha = 0.65f).toArgb()
        axisLabelPaint.textSize = 9.5.sp.toPx()
        axisLabelPaint.color = onSurfaceVariantColor.toArgb()
        tooltipTextPaint.textSize = 11.sp.toPx()

        val yLabelWidth = gridLabelPaint.measureText("100%")
        val plotLeft = max(34.dp.toPx(), yLabelWidth + 8.dp.toPx())
        val plotRight = (w - 12.dp.toPx()).coerceAtLeast(plotLeft + 1f)
        val plotTop = 16.dp.toPx()
        val footerSpace = max(22.dp.toPx(), axisLabelPaint.fontSpacing + 8.dp.toPx())
        val plotBottom = (h - footerSpace).coerceAtLeast(plotTop + 1f)
        val plotHeight = plotBottom - plotTop
        lastPlotLeft = plotLeft
        lastPlotRight = plotRight

        // 1. Grid lines: 100, 75, 50, 25, 0
        val gridStroke = 0.75.dp.toPx()
        val gridLineColor = dividerColor.copy(alpha = 0.45f)
        val labelOffset = (gridLabelPaint.descent() + gridLabelPaint.ascent()) / 2f
        val gridPcts = intArrayOf(100, 75, 50, 25, 0)
        for (pct in gridPcts) {
            val y = plotTop + plotHeight * (1f - pct / 100f)
            drawLine(
                color = gridLineColor,
                start = Offset(plotLeft, y),
                end = Offset(plotRight, y),
                strokeWidth = gridStroke,
            )
            drawContext.canvas.nativeCanvas.drawText(
                "$pct%",
                plotLeft - 4.dp.toPx(),
                y - labelOffset,
                gridLabelPaint,
            )
        }

        if (allDates.isEmpty()) return@Canvas

        // 2. Footer date labels: first & last
        val firstDate = allDates.first()
        val lastDate = allDates.last()
        val footerY = h - 4.dp.toPx()
        axisLabelPaint.textAlign = Paint.Align.LEFT
        drawContext.canvas.nativeCanvas.drawText(
            "${firstDate.day} ${dateFormatter.shortMonthName(firstDate)}",
            plotLeft,
            footerY,
            axisLabelPaint,
        )
        axisLabelPaint.textAlign = Paint.Align.RIGHT
        drawContext.canvas.nativeCanvas.drawText(
            "${lastDate.day} ${dateFormatter.shortMonthName(lastDate)}",
            plotRight,
            footerY,
            axisLabelPaint,
        )

        val totalPoints = allDates.size
        fun xForIndex(i: Int): Float =
            if (totalPoints <= 1) (plotLeft + plotRight) / 2f
            else plotLeft + (plotRight - plotLeft) * (i.toFloat() / (totalPoints - 1))

        // 3. Draw visible series lines & sparse point markers
        val lineStroke = Stroke(
            width = 2.2.dp.toPx(),
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )
        val outerDotR = 3.5.dp.toPx()
        val innerDotR = 2.8.dp.toPx()

        seriesPointMaps.forEach { (seriesItem, pointsByDate) ->
            if (pointsByDate.isEmpty()) return@forEach
            val lineColor = Color(seriesItem.colorArgb)
            val path = Path()
            var started = false
            val markerPoints = mutableListOf<Offset>()

            allDates.forEachIndexed { idx, date ->
                val value = pointsByDate[date]
                if (value == null) {
                    if (started) {
                        drawPath(path = path, color = lineColor, style = lineStroke)
                        path.reset()
                        started = false
                    }
                    return@forEachIndexed
                }
                val x = xForIndex(idx)
                val frac = value.coerceIn(0.0, 1.0).toFloat()
                val y = plotBottom - frac * plotHeight
                if (!started) {
                    path.moveTo(x, y)
                    started = true
                } else {
                    path.lineTo(x, y)
                }
                if (idx == 0 || idx == totalPoints - 1 || totalPoints <= 14) {
                    markerPoints.add(Offset(x, y))
                }
            }
            if (started) {
                drawPath(path = path, color = lineColor, style = lineStroke)
            }
            markerPoints.forEach { pt ->
                drawCircle(color = surfaceColor, radius = outerDotR, center = pt)
                drawCircle(color = lineColor, radius = innerDotR, center = pt)
            }
        }

        // 4. Scrub hairline & floating tooltip
        scrubIndex?.let { sIdx ->
            val scrubDate = allDates.getOrNull(sIdx) ?: return@let
            val sx = xForIndex(sIdx)
            drawLine(
                color = onSurfaceColor.copy(alpha = 0.47f),
                start = Offset(sx, plotTop),
                end = Offset(sx, plotBottom),
                strokeWidth = 1.dp.toPx(),
            )

            val dateLabel = "${scrubDate.day} ${dateFormatter.shortMonthName(scrubDate)}"
            val rows = buildList {
                seriesPointMaps.forEach { (s, map) ->
                    val v = map[scrubDate]
                    if (v != null) {
                        add(Triple(s.label, Color(s.colorArgb), "${(v * 100).roundToInt()}%"))
                    }
                }
            }

            if (rows.isNotEmpty()) {
                val padH = 10.dp.toPx()
                val padV = 6.dp.toPx()
                val lineH = max(16.dp.toPx(), tooltipTextPaint.fontSpacing + 2.dp.toPx())
                tooltipTextPaint.typeface = Typeface.DEFAULT_BOLD
                val maxValW = rows.maxOf { tooltipTextPaint.measureText(it.third) }
                tooltipTextPaint.typeface = Typeface.DEFAULT
                val maxNameW = rows.maxOf {
                    val truncated = if (it.first.length > 14) it.first.take(13) + "…" else it.first
                    tooltipTextPaint.measureText(truncated)
                }
                val dateW = tooltipTextPaint.measureText(dateLabel)
                val computedW = max(dateW + padH * 2f, padH * 2f + 16.dp.toPx() + maxNameW + 10.dp.toPx() + maxValW)
                val tipW = computedW.coerceIn(140.dp.toPx(), (w - 16.dp.toPx()).coerceAtLeast(140.dp.toPx()))
                val tipH = padV * 2f + lineH * (rows.size + 1)
                val tipX = (sx - tipW / 2f).coerceIn(8.dp.toPx(), (w - tipW - 8.dp.toPx()).coerceAtLeast(8.dp.toPx()))
                val tipY = plotTop + 6.dp.toPx()
                val corner = CornerRadius(6.dp.toPx(), 6.dp.toPx())

                drawRoundRect(
                    color = tooltipBgColor,
                    topLeft = Offset(tipX, tipY),
                    size = Size(tipW, tipH),
                    cornerRadius = corner,
                )
                drawRoundRect(
                    color = tooltipBorderColor,
                    topLeft = Offset(tipX, tipY),
                    size = Size(tipW, tipH),
                    cornerRadius = corner,
                    style = Stroke(width = 1.dp.toPx()),
                )

                val baselineOffset = -tooltipTextPaint.ascent()
                tooltipTextPaint.color = onSurfaceVariantColor.toArgb()
                tooltipTextPaint.typeface = Typeface.DEFAULT_BOLD
                tooltipTextPaint.textAlign = Paint.Align.LEFT
                drawContext.canvas.nativeCanvas.drawText(
                    dateLabel,
                    tipX + padH,
                    tipY + padV + baselineOffset,
                    tooltipTextPaint,
                )

                rows.forEachIndexed { rIdx, (name, dotColor, valStr) ->
                    val rowBaselineY = tipY + padV + baselineOffset + (rIdx + 1) * lineH
                    drawCircle(
                        color = dotColor,
                        radius = 3.dp.toPx(),
                        center = Offset(tipX + padH + 4.dp.toPx(), rowBaselineY - 3.5.dp.toPx()),
                    )
                    tooltipTextPaint.color = onSurfaceColor.toArgb()
                    tooltipTextPaint.typeface = Typeface.DEFAULT
                    tooltipTextPaint.textAlign = Paint.Align.LEFT
                    val truncatedName = if (name.length > 14) name.take(13) + "…" else name
                    drawContext.canvas.nativeCanvas.drawText(
                        truncatedName,
                        tipX + padH + 12.dp.toPx(),
                        rowBaselineY,
                        tooltipTextPaint,
                    )
                    tooltipTextPaint.textAlign = Paint.Align.RIGHT
                    tooltipTextPaint.typeface = Typeface.DEFAULT_BOLD
                    drawContext.canvas.nativeCanvas.drawText(
                        valStr,
                        tipX + tipW - padH,
                        rowBaselineY,
                        tooltipTextPaint,
                    )
                }
            }
        }
    }
}

/**
 * Pure Compose Canvas shared calendar small-multiple grid preserving exact cell layout
 * and effective read status coloring of [CompareCalendarView].
 */
@Composable
fun CompareCalendarCompose(
    model: CompareCalendarGridUiModel,
    modifier: Modifier = Modifier,
    colors: UHabitComposeColors = LocalUHabitComposeColors.current,
) {
    val density = LocalDensity.current
    val cellDp = if (model.monthLayout) 19.dp else 17.dp
    val gapDp = 3.dp
    val leftInsetDp = if (model.monthLayout) 0.dp else maxOf(24.dp, (20f * density.fontScale.coerceAtMost(1.8f)).dp)
    val topInsetDp = maxOf(20.dp, (16f * density.fontScale.coerceAtMost(1.8f)).dp)
    val totalWidthDp = leftInsetDp + (cellDp + gapDp) * model.columns + 8.dp
    val totalHeightDp = topInsetDp + (cellDp + gapDp) * model.rows + 6.dp

    val seriesColor = Color(model.seriesColorArgb)
    val emptyColor = colors.surfaceVariantColor
    val labelColor = colors.secondaryColor
    val labelPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
        }
    }

    val satisfiedCount = remember(model.cells) {
        model.cells.count { it.state == CompareCalendarCellState.SATISFIED }
    }
    val activeCount = remember(model.cells) {
        model.cells.count { it.state != CompareCalendarCellState.FUTURE }
    }
    val accessibilityDesc = "${model.habitName}: $satisfiedCount/$activeCount"

    Canvas(
        modifier = modifier
            .width(totalWidthDp)
            .height(totalHeightDp)
            .semantics { contentDescription = accessibilityDesc },
    ) {
        val cellPx = cellDp.toPx()
        val gapPx = gapDp.toPx()
        val leftInsetPx = leftInsetDp.toPx()
        val topInsetPx = topInsetDp.toPx()
        val corner = CornerRadius(2.dp.toPx(), 2.dp.toPx())

        labelPaint.color = labelColor.toArgb()
        labelPaint.textSize = 9.dp.toPx() * density.fontScale.coerceAtMost(1.4f)

        model.weekdayLabels.forEachIndexed { weekday, text ->
            if (model.monthLayout) {
                drawContext.canvas.nativeCanvas.drawText(
                    text,
                    weekday * (cellPx + gapPx) + cellPx / 2f,
                    topInsetPx - 6.dp.toPx(),
                    labelPaint,
                )
            } else {
                drawContext.canvas.nativeCanvas.drawText(
                    text,
                    leftInsetPx * 0.45f,
                    topInsetPx + weekday * (cellPx + gapPx) + cellPx * 0.8f,
                    labelPaint,
                )
            }
        }

        model.cells.forEach { cell ->
            if (cell.state == CompareCalendarCellState.FUTURE) return@forEach
            val fill = when (cell.state) {
                CompareCalendarCellState.PRE_START,
                CompareCalendarCellState.EXCLUDED -> emptyColor.copy(alpha = 0.18f)
                CompareCalendarCellState.SKIPPED -> emptyColor.copy(alpha = 0.63f)
                CompareCalendarCellState.SATISFIED -> seriesColor
                CompareCalendarCellState.NOT_SATISFIED -> seriesColor.copy(alpha = 0.24f)
                CompareCalendarCellState.EMPTY -> emptyColor
                CompareCalendarCellState.FUTURE -> Color.Transparent
            }
            val x = leftInsetPx + cell.column * (cellPx + gapPx)
            val y = topInsetPx + cell.row * (cellPx + gapPx)
            drawRoundRect(
                color = fill,
                topLeft = Offset(x, y),
                size = Size(cellPx, cellPx),
                cornerRadius = corner,
            )
        }
    }
}

/**
 * Pure Compose Canvas horizontal streak bar preserving Loop's centered proportional bar,
 * opacity tiers, contrast-safe length label, and start/end date labels.
 */
@Composable
fun CompareStreakBarCompose(
    streak: Streak,
    maxLength: Long,
    barColorArgb: Int,
    modifier: Modifier = Modifier,
    colors: UHabitComposeColors = LocalUHabitComposeColors.current,
) {
    val dateFormatter = remember { JavaLocalDateFormatter(Locale.getDefault()) }
    val density = LocalDensity.current
    val barHeightDp = maxOf(36.dp, (28f * density.fontScale.coerceAtMost(2f)).dp)
    val startLabel = remember(streak.start) { dateFormatter.longFormat(streak.start) }
    val endLabel = remember(streak.end) { dateFormatter.longFormat(streak.end) }
    val lengthText = streak.length.toString()

    val baseColor = Color(barColorArgb)
    val trackColor = colors.surfaceVariantColor
    val labelColor = colors.secondaryColor
    val filledTextColor = if ((1.05f / (baseColor.luminance() + 0.05f)) >= 4.5f) {
        Color.White
    } else {
        colors.foregroundColor
    }

    val textPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG)
    }

    val desc = "$lengthText ($startLabel – $endLabel)"

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(barHeightDp)
            .semantics { contentDescription = desc },
    ) {
        if (maxLength <= 0L || streak.length <= 0 || size.width <= 0f) return@Canvas
        val w = size.width
        val h = size.height

        textPaint.textSize = (12.sp.toPx()).coerceAtMost(h * 0.52f)
        val em = textPaint.fontSpacing
        val textMargin = 0.5f * em

        var maxLabelWidth = max(textPaint.measureText(startLabel), textPaint.measureText(endLabel))
        var shouldShowLabels = true
        if (w - 2f * maxLabelWidth < w * 0.25f) {
            maxLabelWidth = 0f
            shouldShowLabels = false
        }

        val percentage = (streak.length.toFloat() / maxLength.toFloat()).coerceIn(0f, 1f)
        var availableW = w - 2f * maxLabelWidth
        if (shouldShowLabels) availableW -= 2f * textMargin
        val minBarW = textPaint.measureText(lengthText) + em
        val barW = max(percentage * availableW, minBarW).coerceAtMost(w)
        val gap = (w - barW) / 2f
        val padV = h * 0.08f
        val barFill = when {
            percentage >= 1.0f -> baseColor
            percentage >= 0.8f -> baseColor.copy(alpha = baseColor.alpha * 0.75f)
            percentage >= 0.5f -> baseColor.copy(alpha = baseColor.alpha * 0.45f)
            else -> trackColor
        }
        val corner = CornerRadius(2.dp.toPx(), 2.dp.toPx())
        drawRoundRect(
            color = barFill,
            topLeft = Offset(gap, padV),
            size = Size(barW, h - padV * 2f),
            cornerRadius = corner,
        )

        val baselineY = h / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        textPaint.color = if (percentage >= 0.5f) filledTextColor.toArgb() else labelColor.toArgb()
        textPaint.textAlign = Paint.Align.CENTER
        drawContext.canvas.nativeCanvas.drawText(lengthText, w / 2f, baselineY, textPaint)

        if (shouldShowLabels) {
            textPaint.color = labelColor.toArgb()
            textPaint.textAlign = Paint.Align.RIGHT
            drawContext.canvas.nativeCanvas.drawText(startLabel, gap - textMargin, baselineY, textPaint)
            textPaint.textAlign = Paint.Align.LEFT
            drawContext.canvas.nativeCanvas.drawText(endLabel, w - gap + textMargin, baselineY, textPaint)
        }
    }
}

/**
 * Pure Compose Canvas weekday frequency bubble chart preserving Loop's 7-weekday x month grid,
 * proportional circle markers, 4-step color mix, and horizontal pan through historical months.
 */
@Composable
fun CompareFrequencyChartCompose(
    frequency: Map<LocalDate, Array<Int>>,
    seriesColorArgb: Int,
    firstWeekday: DayOfWeek,
    isNumerical: Boolean,
    habitName: String,
    modifier: Modifier = Modifier,
    colors: UHabitComposeColors = LocalUHabitComposeColors.current,
) {
    val dateFormatter = remember { JavaLocalDateFormatter(Locale.getDefault()) }
    val weekdaySequence = remember(firstWeekday) { getWeekdaySequence(firstWeekday) }
    val weekdayNames = remember(firstWeekday) { dateFormatter.shortWeekdayNames(firstWeekday) }
    val maxFreq = remember(frequency) { CompareChartLogic.maxFrequencyValue(frequency) }
    val density = LocalDensity.current
    val chartHeightDp = maxOf(104.dp, (92f * density.fontScale.coerceAtMost(1.8f)).dp)

    var monthOffset by remember { mutableIntStateOf(0) }
    var dragAccumulator by remember { mutableFloatStateOf(0f) }

    val seriesColor = Color(seriesColorArgb)
    val gridColor = colors.resolveDividerColor()
    val textColor = colors.secondaryColor
    val stepColors = remember(gridColor, seriesColor) {
        listOf(
            gridColor,
            lerp(gridColor, seriesColor, 0.34f),
            lerp(gridColor, seriesColor, 0.67f),
            seriesColor,
        )
    }

    val textPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG)
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(chartHeightDp)
            .semantics { contentDescription = "$habitName: ${frequency.size}" }
            .pointerInput(frequency) {
                detectHorizontalDragGestures(
                    onDragEnd = { dragAccumulator = 0f },
                    onDragCancel = { dragAccumulator = 0f },
                ) { change, dragAmount ->
                    change.consume()
                    dragAccumulator += dragAmount
                    val stepPx = 28.dp.toPx()
                    while (dragAccumulator >= stepPx) {
                        monthOffset += 1
                        dragAccumulator -= stepPx
                    }
                    while (dragAccumulator <= -stepPx) {
                        monthOffset = (monthOffset - 1).coerceAtLeast(0)
                        dragAccumulator += stepPx
                    }
                }
            },
    ) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        val rowHeight = h / 8f
        textPaint.textSize = (rowHeight * 0.56f).coerceIn(7.sp.toPx(), 11.sp.toPx())
        textPaint.color = textColor.toArgb()
        val em = textPaint.fontSpacing

        val maxMonthW = (1..12).maxOf { m ->
            textPaint.measureText(dateFormatter.shortMonthName(LocalDate(2020, m, 1)))
        }
        val maxWeekdayW = weekdayNames.maxOfOrNull { textPaint.measureText(it) } ?: rowHeight
        val rightLabelColW = max(rowHeight * 1.4f, maxWeekdayW + 6.dp.toPx())
        val colW = max(rowHeight * 1.25f, maxMonthW * 1.25f)
        val nDataCols = ((w - rightLabelColW) / colW).toInt().coerceAtLeast(1)
        val gridRight = nDataCols * colW + rightLabelColW
        val gridStroke = 0.75.dp.toPx()

        // Horizontal grid & weekday labels on right
        textPaint.textAlign = Paint.Align.LEFT
        for (rowIdx in 0..6) {
            val rowTop = rowIdx * rowHeight
            drawLine(
                color = gridColor,
                start = Offset(0f, rowTop),
                end = Offset(gridRight.coerceAtMost(w), rowTop),
                strokeWidth = gridStroke,
            )
            drawContext.canvas.nativeCanvas.drawText(
                weekdayNames[rowIdx],
                nDataCols * colW + 4.dp.toPx(),
                rowTop + rowHeight / 2f + 0.25f * em,
                textPaint,
            )
        }
        drawLine(
            color = gridColor,
            start = Offset(0f, 7f * rowHeight),
            end = Offset(gridRight.coerceAtMost(w), 7f * rowHeight),
            strokeWidth = gridStroke,
        )

        // Columns: oldest left -> newest right
        val today = getToday()
        var currentMonth = CompareChartLogic.stepMonth(
            LocalDate(today.year, today.month, 1),
            -(nDataCols - 1) - monthOffset,
        )
        textPaint.textAlign = Paint.Align.CENTER
        val maxRadius = (min(colW, rowHeight) * 0.36f).coerceAtLeast(1.5f)

        for (colIdx in 0 until nDataCols) {
            val colCenterX = colIdx * colW + colW / 2f
            val values = frequency[currentMonth]
            val weekdayOccurrences = countWeekdayOccurrencesInMonth(currentMonth)
            if (values != null) {
                for (rowIdx in weekdaySequence.indices) {
                    val arrayIdx = (weekdaySequence[rowIdx].daysSinceSunday + 1) % 7
                    val rawVal = max(0, values[arrayIdx])
                    if (rawVal > 0) {
                        val scalingFactor = (if (isNumerical) maxFreq else weekdayOccurrences[arrayIdx]).coerceAtLeast(1)
                        val scale = (rawVal.toFloat() / scalingFactor.toFloat()).coerceIn(0f, 1f)
                        val radius = maxRadius * scale
                        val colorIdx = min(stepColors.lastIndex, (stepColors.lastIndex * scale).roundToInt())
                        drawCircle(
                            color = stepColors[colorIdx],
                            radius = radius,
                            center = Offset(colCenterX, rowIdx * rowHeight + rowHeight / 2f),
                        )
                    }
                }
            }
            val footerCenterY = 7f * rowHeight + rowHeight / 2f
            drawContext.canvas.nativeCanvas.drawText(
                dateFormatter.shortMonthName(currentMonth),
                colCenterX,
                footerCenterY + 0.2f * em,
                textPaint,
            )
            currentMonth = CompareChartLogic.stepMonth(currentMonth, 1)
        }
    }
}
