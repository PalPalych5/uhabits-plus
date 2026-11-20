/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.activities.statistics

import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.first
import org.isoron.uhabits.activities.settings.SettingsThemePalette
import org.isoron.uhabits.activities.statistics.views.DiscreteColumnBarChart
import kotlin.math.roundToInt

@Composable
internal fun UnifiedStatisticsColumnChart(
    tag: String,
    columns: List<ChartColumnItem>,
    palette: SettingsThemePalette,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    unitLabel: String? = null,
    valueFormatter: ((Long) -> String)? = null,
    onColumn: (ChartColumnItem) -> Unit,
) {
    if (columns.isEmpty()) return
    val density = LocalDensity.current
    val ticks = DiscreteColumnBarChart.computeYTickValues(columns.maxOf { it.value }, valueFormatter ?: Long::toString)
    val painter = remember(columns, density, palette, ticks) { StatisticsColumnPainter(columns, density, palette, ticks) }

    // Dates and the numeric axis retain the report's chronological plot coordinates.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        BoxWithConstraints(modifier.fillMaxWidth().testTag(tag).semantics { testTagsAsResourceId = true }) {
            if (!constraints.hasBoundedWidth) return@BoxWithConstraints
            val plotWidth = maxOf(constraints.maxWidth, painter.desiredWidth)
            val bounds = remember(painter, plotWidth) {
                DiscreteColumnBarChart.computePureColumnBounds(
                    columns.size, plotWidth.toFloat(), painter.height.toFloat(), painter.axisWidth,
                    (plotWidth - painter.endPadding).toFloat(),
                )
            }
            key(columns) {
                val scroll = rememberScrollState()
                var selected by remember { mutableIntStateOf(columns.indexOfFirst { it.isSelected }) }
                var focused by remember { mutableIntStateOf(-1) }
                LaunchedEffect(plotWidth, constraints.maxWidth) {
                    val limit = snapshotFlow { scroll.maxValue }.first { it != Int.MAX_VALUE }
                    val today = columns.indexOfFirst { it.isToday }
                    val target = if (today == -1) limit
                        else (bounds[today].right.roundToInt() - constraints.maxWidth).coerceIn(0, limit)
                    scroll.scrollTo(target)
                }
                Box(Modifier.testTag("${tag}_scroll").horizontalScroll(scroll)) {
                    Box(Modifier.width(with(density) { plotWidth.toDp() }).height(with(density) { painter.height.toDp() })) {
                        Canvas(Modifier.matchParentSize().testTag("${tag}_axis").semantics {
                            contentDescription = listOfNotNull(unitLabel, ticks.joinToString(" · ") { it.second }).joinToString(" · ")
                        }) {
                            drawIntoCanvas {
                                painter.draw(it.nativeCanvas, size.width.roundToInt(), size.height.roundToInt(), if (focused >= 0) focused else selected)
                            }
                        }
                        Row(Modifier.matchParentSize().selectableGroup()) {
                            Box(Modifier.width(with(density) { painter.axisWidth.toDp() }).fillMaxHeight())
                            columns.forEachIndexed { index, item ->
                                val active = index == (if (focused >= 0) focused else selected) || item.isSelected
                                Box(
                                    Modifier.weight(1f).fillMaxHeight()
                                        .testTag("${tag}_column_$index")
                                        .onFocusChanged { state ->
                                            if (state.isFocused) focused = index else if (focused == index) focused = -1
                                        }
                                        .selectable(selected = active, enabled = enabled, role = Role.Button) {
                                            selected = index
                                            onColumn(item)
                                        }
                                        .semantics { contentDescription = item.contentDescription.orEmpty() },
                                )
                            }
                            Box(Modifier.width(with(density) { painter.endPadding.toDp() }).fillMaxHeight())
                        }
                    }
                }
            }
        }
    }
}

private class StatisticsColumnPainter(
    private val items: List<ChartColumnItem>,
    private val density: Density,
    palette: SettingsThemePalette,
    private val ticks: List<Pair<Long, String>>,
) {
    private fun dp(value: Float) = with(density) { value.dp.toPx() }
    private fun sp(value: Int) = with(density) { value.sp.toPx() }
    private val primaryColor = palette.accent
    private val onSurfaceColor = palette.onSurface
    private val onSurfaceVariantColor = palette.onSurfaceVariant
    private val surfaceVariantColor = palette.surfaceVariant
    private val outlineColor = palette.border
    private val dividerColor = palette.divider
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val baselinePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gridLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val gridTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.RIGHT
        fontFeatureSettings = "tnum"
        textSize = sp(9)
    }
    private val valueTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = sp(11)
    }
    private val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = sp(10)
    }
    private val todayDotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val columnPath = Path()
    private val rawMaxLong = items.maxOf { it.value }
    val axisWidth = ticks.maxOf { (_, label) -> gridTextPaint.measureText(label) } + dp(10f)
    val endPadding = dp(10f).roundToInt()
    val height = DiscreteColumnBarChart.computePureDesiredHeight(
        valueTextPaint.fontSpacing, dp(6f), labelTextPaint.fontSpacing, dp(14f), dp(68f), 0, 0,
    )
    val desiredWidth: Int = run {
        val compact = items.size <= 7
        val textPadding = dp(if (compact) 8f else 16f)
        val minPitch = maxOf(
            dp(if (compact) 36f else 48f),
            items.maxOf { labelTextPaint.measureText(it.label) } + textPadding,
            items.maxOf { if (it.value > 0L) valueTextPaint.measureText(it.formattedValue) else 0f } + textPadding,
        )
        DiscreteColumnBarChart.computeDesiredWidth(items.size, minPitch, axisWidth, 0, endPadding)
    }

    fun draw(canvas: AndroidCanvas, width: Int, height: Int, selectedIndex: Int) {
        if (items.isEmpty()) return

        val count = items.size
        val chartLeft = axisWidth
        val chartRight = (width - endPadding).toFloat()
        val availableW = (chartRight - chartLeft).coerceAtLeast(0f)
        val columnWidth = if (count > 0) availableW / count else 0f
        val barWidth = (columnWidth * 0.45f).coerceIn(dp(6f), dp(22f))

        val topPadding = valueTextPaint.fontSpacing + dp(6f)
        val bottomAxisMargin = labelTextPaint.fontSpacing + dp(14f)
        val bottomBaseline = height - bottomAxisMargin
        val maxBarHeight = (bottomBaseline - topPadding).coerceAtLeast(dp(20f))

        val maxValue = (items.maxOfOrNull { it.value } ?: 0L).coerceAtLeast(1L).toFloat()

        // 1. Numeric Y scale grid lines & labels
        gridLinePaint.strokeWidth = dp(0.75f)
        gridLinePaint.color = dividerColor
        gridTextPaint.color = onSurfaceVariantColor

        // Baseline (0) and deduplicated Y scale ticks (mid & max)
        baselinePaint.color = outlineColor
        baselinePaint.strokeWidth = dp(1f)
        canvas.drawLine(chartLeft, bottomBaseline, chartRight, bottomBaseline, baselinePaint)
        if (ticks.isNotEmpty()) {
            val zeroY = bottomBaseline - (gridTextPaint.descent() + gridTextPaint.ascent()) / 2f
            canvas.drawText(ticks.first().second, chartLeft - dp(4f), zeroY, gridTextPaint)

            ticks.drop(1).forEach { (tickVal, tickText) ->
                val y = if (tickVal == rawMaxLong) {
                    bottomBaseline - maxBarHeight
                } else {
                    bottomBaseline - maxBarHeight * (tickVal.toDouble() / rawMaxLong).toFloat()
                }
                canvas.drawLine(chartLeft, y, chartRight, y, gridLinePaint)
                val textY = y - (gridTextPaint.descent() + gridTextPaint.ascent()) / 2f
                canvas.drawText(tickText, chartLeft - dp(4f), textY, gridTextPaint)
            }
        }

        // 2. Columns, values & date axis labels
        items.forEachIndexed { index, item ->
            val centerX = chartLeft + columnWidth * (index + 0.5f)
            val isSelected = index == selectedIndex || item.isSelected

            // Axis date label
            labelTextPaint.color = if (isSelected) primaryColor else onSurfaceVariantColor
            labelTextPaint.isFakeBoldText = isSelected || item.isToday
            val labelY = height - dp(6f)
            val labelHalfW = labelTextPaint.measureText(item.label) / 2f
            val clampedCenterX = if (width > 0) {
                centerX.coerceIn(minOf(labelHalfW + dp(2f), width / 2f), maxOf(width - labelHalfW - dp(2f), width / 2f))
            } else centerX
            canvas.drawText(item.label, clampedCenterX, labelY, labelTextPaint)

            // Today indicator dot
            if (item.isToday) {
                todayDotPaint.color = primaryColor
                canvas.drawCircle(clampedCenterX, labelY + dp(3.5f), dp(1.75f), todayDotPaint)
            }

            // Bar drawing
            if (item.value > 0L) {
                val fraction = (item.value.toFloat() / maxValue).coerceIn(0f, 1f)
                val barHeight = (maxBarHeight * fraction).coerceAtLeast(dp(3f))
                val barTop = bottomBaseline - barHeight
                val cornerRadius = barWidth * 0.25f

                if (item.segments.isNotEmpty()) {
                    var currentBase = bottomBaseline
                    val saveCount = canvas.save()
                    rect.set(centerX - barWidth / 2f, barTop, centerX + barWidth / 2f, bottomBaseline)
                    columnPath.reset()
                    columnPath.addRoundRect(rect, cornerRadius, cornerRadius, android.graphics.Path.Direction.CW)
                    canvas.clipPath(columnPath)
                    item.segments.forEach { seg ->
                        val segFraction = (seg.value.toFloat() / item.value)
                        val segH = barHeight * segFraction
                        val segTop = (currentBase - segH).coerceAtLeast(barTop)
                        rect.set(centerX - barWidth / 2f, segTop, centerX + barWidth / 2f, currentBase)
                        barPaint.color = seg.color
                        canvas.drawRect(rect, barPaint)
                        currentBase = segTop
                    }
                    canvas.restoreToCount(saveCount)
                    if (isSelected) {
                        gridLinePaint.color = onSurfaceColor
                        gridLinePaint.strokeWidth = dp(2f)
                        rect.set(centerX - barWidth / 2f, barTop, centerX + barWidth / 2f, bottomBaseline)
                        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, gridLinePaint)
                    }
                } else {
                    rect.set(centerX - barWidth / 2f, barTop, centerX + barWidth / 2f, bottomBaseline)
                    barPaint.color = if (isSelected) onSurfaceColor else primaryColor
                    canvas.drawRoundRect(rect, cornerRadius, cornerRadius, barPaint)
                }

                // Value text above bar
                valueTextPaint.color = if (isSelected) primaryColor else onSurfaceColor
                valueTextPaint.isFakeBoldText = isSelected
                canvas.drawText(item.formattedValue, centerX, barTop - dp(4f), valueTextPaint)
            } else {
                // 0 value: subtle baseline tick mark
                rect.set(centerX - barWidth / 3f, bottomBaseline - dp(2f), centerX + barWidth / 3f, bottomBaseline)
                barPaint.color = if (isSelected) primaryColor else surfaceVariantColor
                canvas.drawRect(rect, barPaint)
                if (isSelected) {
                    valueTextPaint.color = primaryColor
                    valueTextPaint.isFakeBoldText = true
                    canvas.drawText(item.formattedValue, centerX, bottomBaseline - dp(4f), valueTextPaint)
                }
            }
        }
    }
}
