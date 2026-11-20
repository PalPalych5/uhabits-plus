/*
 * Copyright (C) 2016-2025 Álinson Santos Xavier <git@axavier.org>
 *
 * This file is part of Loop Habit Tracker.
 *
 * Loop Habit Tracker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * Loop Habit Tracker is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.isoron.uhabits.activities.common.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import org.isoron.platform.time.JavaLocalDateFormatter
import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.R
import org.isoron.uhabits.core.models.Score
import org.isoron.uhabits.utils.InterfaceUtils.dpToPixels
import org.isoron.uhabits.utils.InterfaceUtils.getDimension
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Stateless-input native Canvas renderer for [ScoreChart] and its Compose Canvas host.
 * Preserves exact geometry, typography, marker clipping, and offscreen transparency cutout math.
 */
class ScoreChartRenderer {
    private val pGrid = Paint().apply { isAntiAlias = true }
    private val pText = Paint().apply { isAntiAlias = true }
    private val pGraph = Paint().apply {
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }
    private val rect = RectF()
    private val prevRect = RectF()
    private val plotBounds = RectF()

    private var dateFormatter = JavaLocalDateFormatter(Locale.getDefault())
    private var currentLocale: Locale = Locale.getDefault()
    private var em = 0f
    private var baseSize = 0
    private var internalPaddingTop = 0
    private var columnWidth = 0f
    private var columnHeight = 0
    private var nColumns = 0
    private var skipYear = 0
    private var previousYearText = ""
    private var previousMonthText = ""
    private var measuredWidth = -1
    private var measuredHeight = -1

    fun updateSize(context: Context, width: Int, height: Int): Int {
        val locale = Locale.getDefault()
        if (locale != currentLocale) {
            currentLocale = locale
            dateFormatter = JavaLocalDateFormatter(locale)
        }
        val effectiveHeight = if (height < 9) 200 else height
        val maxTextSize = getDimension(context, R.dimen.tinyTextSize)
        val textSize = effectiveHeight * 0.06f
        pText.textSize = min(textSize, maxTextSize)
        em = pText.fontSpacing
        val footerHeight = (3 * em).toInt()
        internalPaddingTop = em.toInt()
        baseSize = (effectiveHeight - footerHeight - internalPaddingTop) / 8
        columnWidth = baseSize.toFloat()
        columnWidth = max(columnWidth, maxDayWidth * 1.5f)
        columnWidth = max(columnWidth, maxMonthWidth * 1.2f)
        pGraph.textSize = baseSize * 0.5f
        pGraph.strokeWidth = baseSize * 0.1f
        val markerRadius = baseSize * 0.275f
        val graphInset = markerRadius + pGraph.strokeWidth / 2f
        val yAxisLabelWidth = pText.measureText("100%") + em
        plotBounds.set(
            max(graphInset, yAxisLabelWidth),
            internalPaddingTop + graphInset,
            width - graphInset,
            internalPaddingTop + 8 * baseSize - graphInset
        )
        nColumns = max(1, (plotBounds.width() / columnWidth).toInt())
        columnWidth = plotBounds.width() / nColumns
        columnHeight = plotBounds.height().toInt()
        val minStrokeWidth = dpToPixels(context, 1f)
        pGrid.strokeWidth = min(minStrokeWidth, baseSize * 0.05f)
        measuredWidth = width
        measuredHeight = height
        return columnWidth.toInt()
    }

    fun draw(
        context: Context,
        canvas: Canvas,
        width: Int,
        height: Int,
        scores: List<Score>?,
        dataOffset: Int,
        bucketSize: Int,
        primaryColor: Int,
        textColor: Int,
        gridColor: Int,
        backgroundColor: Int,
        isTransparencyEnabled: Boolean
    ) {
        if (width <= 0 || height <= 0) return
        if (measuredWidth != width || measuredHeight != height || currentLocale != Locale.getDefault()) {
            updateSize(context, width, height)
        }
        if (scores == null) return

        val layerId = if (isTransparencyEnabled) {
            canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        } else {
            -1
        }

        drawGrid(canvas, plotBounds, textColor, gridColor)
        pText.color = textColor
        pGraph.color = primaryColor
        pGraph.xfermode = null
        prevRect.setEmpty()
        previousMonthText = ""
        previousYearText = ""
        skipYear = 0

        val graphSaveCount = canvas.save()
        val markerClipMargin = baseSize * 0.35f
        canvas.clipRect(
            plotBounds.left,
            plotBounds.top - markerClipMargin,
            plotBounds.right,
            plotBounds.bottom + markerClipMargin
        )
        for (k in 0 until nColumns) {
            val offset = nColumns - k - 1 + dataOffset
            if (offset < 0 || offset >= scores.size) continue
            val score = scores[offset].value
            val colHeight = (columnHeight * score).toInt()
            rect.set(0f, 0f, baseSize.toFloat(), baseSize.toFloat())
            rect.offset(
                plotBounds.left + k * columnWidth + (columnWidth - baseSize) / 2,
                (plotBounds.bottom - colHeight - baseSize / 2).toFloat()
            )
            if (!prevRect.isEmpty) {
                drawLine(canvas, prevRect, rect, primaryColor)
                drawMarker(canvas, prevRect, primaryColor, backgroundColor, isTransparencyEnabled)
            }
            if (k == nColumns - 1) {
                drawMarker(canvas, rect, primaryColor, backgroundColor, isTransparencyEnabled)
            }
            prevRect.set(rect)
        }
        canvas.restoreToCount(graphSaveCount)

        for (k in 0 until nColumns) {
            val offset = nColumns - k - 1 + dataOffset
            if (offset < 0 || offset >= scores.size) continue
            rect.set(0f, 0f, columnWidth, (8 * baseSize).toFloat())
            rect.offset(plotBounds.left + k * columnWidth, internalPaddingTop.toFloat())
            drawFooter(canvas, rect, scores[offset].date, bucketSize)
        }

        if (layerId >= 0) {
            canvas.restoreToCount(layerId)
        }
    }

    private fun drawFooter(canvas: Canvas, rect: RectF, date: LocalDate, bucketSize: Int) {
        val df = dateFormatter
        val yearText = date.year.toString()
        val monthText = df.shortMonthName(date)
        val dayText = date.day.toString()
        val text: String
        var shouldPrintYear = true
        if (yearText == previousYearText) shouldPrintYear = false
        if (bucketSize >= 365 && date.year % 2 != 0) shouldPrintYear = false
        if (skipYear > 0) {
            skipYear--
            shouldPrintYear = false
        }
        if (shouldPrintYear) {
            previousYearText = yearText
            previousMonthText = ""
            pText.textAlign = Paint.Align.CENTER
            canvas.drawText(
                yearText,
                rect.centerX(),
                rect.bottom + em * 2.2f,
                pText
            )
            skipYear = 1
        }
        if (bucketSize < 365) {
            if (monthText != previousMonthText) {
                previousMonthText = monthText
                text = monthText
            } else {
                text = dayText
            }
            pText.textAlign = Paint.Align.CENTER
            canvas.drawText(
                text,
                rect.centerX(),
                rect.bottom + em * 1.2f,
                pText
            )
        }
    }

    private fun drawGrid(canvas: Canvas, rGrid: RectF, textColor: Int, gridColor: Int) {
        val nRows = 5
        val rowHeight = rGrid.height() / nRows
        var y = rGrid.top
        pText.textAlign = Paint.Align.LEFT
        pText.color = textColor
        pGrid.color = gridColor
        for (i in 0 until nRows) {
            canvas.drawText(
                String.format("%d%%", 100 - i * 100 / nRows),
                max(0f, rGrid.left - pText.measureText("100%") - 0.5f * em),
                y + 1f * em,
                pText
            )
            canvas.drawLine(rGrid.left, y, rGrid.right, y, pGrid)
            y += rowHeight
        }
        canvas.drawLine(rGrid.left, y, rGrid.right, y, pGrid)
    }

    private fun drawLine(canvas: Canvas, rectFrom: RectF, rectTo: RectF, primaryColor: Int) {
        pGraph.color = primaryColor
        canvas.drawLine(
            rectFrom.centerX(),
            rectFrom.centerY(),
            rectTo.centerX(),
            rectTo.centerY(),
            pGraph
        )
    }

    private fun drawMarker(
        canvas: Canvas,
        rect: RectF,
        primaryColor: Int,
        backgroundColor: Int,
        isTransparencyEnabled: Boolean
    ) {
        rect.inset(baseSize * 0.225f, baseSize * 0.225f)
        setModeOrColor(pGraph, XFERMODE_CLEAR, backgroundColor, isTransparencyEnabled)
        canvas.drawOval(rect, pGraph)
        rect.inset(baseSize * 0.1f, baseSize * 0.1f)
        setModeOrColor(pGraph, XFERMODE_SRC, primaryColor, isTransparencyEnabled)
        canvas.drawOval(rect, pGraph)
        if (isTransparencyEnabled) pGraph.xfermode = XFERMODE_SRC
    }

    private fun setModeOrColor(
        p: Paint,
        mode: PorterDuffXfermode,
        color: Int,
        isTransparencyEnabled: Boolean
    ) {
        if (isTransparencyEnabled) p.xfermode = mode else p.color = color
    }

    private val maxDayWidth: Float
        get() {
            var maxW = 0f
            for (i in 1..12) {
                val date = LocalDate(2020, i, 1)
                val monthWidth = pText.measureText(dateFormatter.shortMonthName(date))
                maxW = max(maxW, monthWidth)
            }
            return maxW
        }

    private val maxMonthWidth: Float
        get() {
            var maxW = 0f
            for (i in 1..12) {
                val date = LocalDate(2020, i, 1)
                val monthWidth = pText.measureText(dateFormatter.shortMonthName(date))
                maxW = max(maxW, monthWidth)
            }
            return maxW
        }

    companion object {
        private val XFERMODE_CLEAR = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        private val XFERMODE_SRC = PorterDuffXfermode(PorterDuff.Mode.SRC)
    }
}
