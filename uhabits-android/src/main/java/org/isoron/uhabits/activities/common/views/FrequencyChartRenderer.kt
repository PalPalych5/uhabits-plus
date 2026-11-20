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

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import org.isoron.platform.time.DayOfWeek
import org.isoron.platform.time.JavaLocalDateFormatter
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.countWeekdayOccurrencesInMonth
import org.isoron.platform.time.getToday
import org.isoron.platform.time.getWeekdaySequence
import org.isoron.uhabits.utils.ColorUtils.mixColors
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Shared native Canvas renderer for [FrequencyChart] and its Compose Canvas host.
 * Preserves exact grid spacing, circle scaling, color interpolation, and month stepping math.
 */
class FrequencyChartRenderer {
    private val pGrid = Paint().apply { isAntiAlias = true }
    private val pText = Paint().apply { isAntiAlias = true }
    private val pGraph = Paint().apply {
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }
    private val rect = RectF()
    private val prevRect = RectF()
    private val colors = IntArray(4)

    private var dateFormatter = JavaLocalDateFormatter(Locale.getDefault())
    private var currentLocale: Locale = Locale.getDefault()
    private var em = 0f
    private var baseSize = 0
    private var internalPaddingTop = 0
    private var columnWidth = 0f
    private var columnHeight = 0
    private var nColumns = 0
    private var measuredWidth = -1
    private var measuredHeight = -1

    fun updateSize(width: Int, height: Int): Int {
        val locale = Locale.getDefault()
        if (locale != currentLocale) {
            currentLocale = locale
            dateFormatter = JavaLocalDateFormatter(locale)
        }
        val effectiveHeight = if (height < 9) 200 else height
        baseSize = effectiveHeight / 8
        pText.textSize = baseSize * 0.4f
        pGraph.textSize = baseSize * 0.4f
        pGraph.strokeWidth = baseSize * 0.1f
        pGrid.strokeWidth = baseSize * 0.05f
        em = pText.fontSpacing
        columnWidth = max(baseSize.toFloat(), maxMonthWidth * 1.2f)
        columnHeight = 8 * baseSize
        nColumns = if (columnWidth > 0f) (width / columnWidth).toInt() else 0
        internalPaddingTop = 0
        measuredWidth = width
        measuredHeight = height
        return baseSize
    }

    fun draw(
        canvas: Canvas,
        width: Int,
        height: Int,
        frequency: Map<LocalDate, Array<Int>>,
        maxFreq: Int,
        dataOffset: Int,
        firstWeekday: DayOfWeek,
        isNumerical: Boolean,
        primaryColor: Int,
        textColor: Int,
        gridColor: Int
    ) {
        if (width <= 0 || height <= 0) return
        if (measuredWidth != width || measuredHeight != height || currentLocale != Locale.getDefault()) {
            updateSize(width, height)
        }
        if (nColumns <= 0) return

        colors[0] = gridColor
        colors[3] = primaryColor
        colors[1] = mixColors(colors[0], colors[3], 0.66f)
        colors[2] = mixColors(colors[0], colors[3], 0.33f)

        rect.set(0f, 0f, nColumns * columnWidth, columnHeight.toFloat())
        rect.offset(0f, internalPaddingTop.toFloat())
        drawGrid(canvas, rect, firstWeekday, textColor, gridColor)

        pText.textAlign = Paint.Align.CENTER
        pText.color = textColor
        pGraph.color = primaryColor
        prevRect.setEmpty()

        val today = getToday()
        var currentDate = LocalDate(today.year, today.month, 1)
        currentDate = stepMonth(currentDate, -nColumns + 2 - dataOffset)
        for (i in 0 until nColumns - 1) {
            rect.set(0f, 0f, columnWidth, columnHeight.toFloat())
            rect.offset(i * columnWidth, 0f)
            drawColumn(canvas, rect, currentDate, frequency, maxFreq, firstWeekday, isNumerical)
            currentDate = stepMonth(currentDate, 1)
        }
    }

    private fun drawColumn(
        canvas: Canvas,
        rect: RectF,
        date: LocalDate,
        frequency: Map<LocalDate, Array<Int>>,
        maxFreq: Int,
        firstWeekday: DayOfWeek,
        isNumerical: Boolean
    ) {
        val values = frequency[date]
        val weekDaysInMonth = countWeekdayOccurrencesInMonth(date)
        val rowHeight = rect.height() / 8.0f
        prevRect.set(rect)
        val localeWeekdayList = getWeekdaySequence(firstWeekday)
        for (j in localeWeekdayList.indices) {
            rect.set(0f, 0f, baseSize.toFloat(), baseSize.toFloat())
            rect.offset(prevRect.left, prevRect.top + baseSize * j)
            val i = (localeWeekdayList[j].daysSinceSunday + 1) % 7
            if (values != null) {
                drawMarker(canvas, rect, values[i], weekDaysInMonth[i], maxFreq, isNumerical)
            }
            rect.offset(0f, rowHeight)
        }
        drawFooter(canvas, rect, date)
    }

    private fun drawFooter(canvas: Canvas, rect: RectF, date: LocalDate) {
        canvas.drawText(
            dateFormatter.shortMonthName(date),
            rect.centerX(),
            rect.centerY() - 0.1f * em,
            pText
        )
        if (date.month == 2) {
            canvas.drawText(
                date.year.toString(),
                rect.centerX(),
                rect.centerY() + 0.9f * em,
                pText
            )
        }
    }

    private fun drawGrid(
        canvas: Canvas,
        rGrid: RectF,
        firstWeekday: DayOfWeek,
        textColor: Int,
        gridColor: Int
    ) {
        val nRows = 7
        val rowHeight = rGrid.height() / (nRows + 1)
        pText.textAlign = Paint.Align.LEFT
        pText.color = textColor
        pGrid.color = gridColor
        for (day in dateFormatter.shortWeekdayNames(firstWeekday)) {
            canvas.drawText(
                day,
                rGrid.right - columnWidth,
                rGrid.top + rowHeight / 2 + 0.25f * em,
                pText
            )
            pGrid.strokeWidth = 1f
            canvas.drawLine(rGrid.left, rGrid.top, rGrid.right, rGrid.top, pGrid)
            rGrid.offset(0f, rowHeight)
        }
        canvas.drawLine(rGrid.left, rGrid.top, rGrid.right, rGrid.top, pGrid)
    }

    private fun drawMarker(
        canvas: Canvas,
        rect: RectF,
        value: Int,
        weekdayFrequency: Int,
        maxFreq: Int,
        isNumerical: Boolean
    ) {
        val valueCopy = max(0, value)
        val padding = rect.height() * 0.2f
        val maxRadius = (rect.height() - 2 * padding) / 2.0f
        val scalingFactor = if (isNumerical) maxFreq else weekdayFrequency
        val scale = 1.0f / scalingFactor * valueCopy
        val radius = maxRadius * scale
        val colorIndex = min(colors.size - 1, ((colors.size - 1) * scale).roundToInt())
        pGraph.color = colors[colorIndex]
        canvas.drawCircle(rect.centerX(), rect.centerY(), radius, pGraph)
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
        fun computeMaxFreq(frequency: Map<LocalDate, Array<Int>>): Int {
            var maxValue = 1
            for (values in frequency.values) {
                for (value in values) {
                    maxValue = max(value, maxValue)
                }
            }
            return maxValue
        }

        fun stepMonth(date: LocalDate, months: Int): LocalDate {
            var y = date.year
            var m = date.month + months
            while (m < 1) { m += 12; y -= 1 }
            while (m > 12) { m -= 12; y += 1 }
            return LocalDate(y, m, 1)
        }
    }
}
