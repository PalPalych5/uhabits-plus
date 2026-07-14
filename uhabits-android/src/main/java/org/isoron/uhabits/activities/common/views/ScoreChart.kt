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
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.AttributeSet
import org.isoron.platform.time.JavaLocalDateFormatter
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.getToday
import org.isoron.uhabits.R
import org.isoron.uhabits.core.models.Score
import org.isoron.uhabits.utils.InterfaceUtils.dpToPixels
import org.isoron.uhabits.utils.InterfaceUtils.getDimension
import org.isoron.uhabits.utils.StyledResources
import java.util.LinkedList
import java.util.Locale
import java.util.Random
import kotlin.math.max
import kotlin.math.min

class ScoreChart : ScrollableChart {
    private var pGrid: Paint? = null
    private var em = 0f
    private var dateFormatter: JavaLocalDateFormatter? = null
    private var pText: Paint? = null
    private var pGraph: Paint? = null
    private var rect: RectF? = null
    private var prevRect: RectF? = null
    private val plotBounds = RectF()
    private var baseSize = 0
    private var internalPaddingTop = 0
    private var columnWidth = 0f
    private var columnHeight = 0
    private var nColumns = 0
    private var textColor = 0
    private var gridColor = 0
    private var scores: List<Score>? = null
    private var primaryColor = 0
    private var textColorOverride: Int? = null
    private var gridColorOverride: Int? = null
    private var backgroundColorOverride: Int? = null

    @Deprecated("")
    private var bucketSize = 7
    private var internalBackgroundColor = 0
    private var internalDrawingCache: Bitmap? = null
    private var cacheCanvas: Canvas? = null
    private var isTransparencyEnabled = false
    private var skipYear = 0
    private var previousYearText: String? = null
    private var previousMonthText: String? = null

    constructor(context: Context?) : super(context) {
        init()
    }

    constructor(context: Context?, attrs: AttributeSet?) : super(context, attrs) {
        init()
    }

    fun populateWithRandomData() {
        val random = Random()
        val newScores = LinkedList<Score>()
        var previous = 0.5
        val today = getToday()
        for (i in 1..99) {
            val step = 0.1
            var current = previous + random.nextDouble() * step * 2 - step
            current = max(0.0, min(1.0, current))
            newScores.add(Score(today.minus(i), current))
            previous = current
        }
        scores = newScores
    }

    fun setBucketSize(bucketSize: Int) {
        this.bucketSize = bucketSize
        postInvalidate()
    }

    fun setIsTransparencyEnabled(enabled: Boolean) {
        isTransparencyEnabled = enabled
        postInvalidate()
    }

    fun setColor(primaryColor: Int) {
        this.primaryColor = primaryColor
        postInvalidate()
    }

    fun setScores(scores: List<Score>) {
        this.scores = scores
        postInvalidate()
    }

    fun setSurfaceColors(textColor: Int, gridColor: Int, backgroundColor: Int) {
        textColorOverride = textColor
        gridColorOverride = gridColor
        backgroundColorOverride = backgroundColor
        this.textColor = textColor
        this.gridColor = gridColor
        this.internalBackgroundColor = backgroundColor
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val activeCanvas: Canvas
        if (isTransparencyEnabled) {
            if (internalDrawingCache == null) initCache(width, height)
            activeCanvas = cacheCanvas!!
            internalDrawingCache!!.eraseColor(Color.TRANSPARENT)
        } else {
            activeCanvas = canvas
        }
        if (scores == null) return
        drawGrid(activeCanvas, plotBounds)
        pText!!.color = textColor
        pGraph!!.color = primaryColor
        prevRect!!.setEmpty()
        previousMonthText = ""
        previousYearText = ""
        skipYear = 0
        val graphSaveCount = activeCanvas.save()
        activeCanvas.clipRect(plotBounds)
        for (k in 0 until nColumns) {
            val offset = nColumns - k - 1 + dataOffset
            if (offset >= scores!!.size) continue
            val score = scores!![offset].value
            val height = (columnHeight * score).toInt()
            rect!![0f, 0f, baseSize.toFloat()] = baseSize.toFloat()
            rect!!.offset(
                plotBounds.left + k * columnWidth + (columnWidth - baseSize) / 2,
                (
                    plotBounds.bottom - height - baseSize / 2
                    ).toFloat()
            )
            if (!prevRect!!.isEmpty) {
                drawLine(activeCanvas, prevRect, rect)
                drawMarker(activeCanvas, prevRect)
            }
            if (k == nColumns - 1) drawMarker(activeCanvas, rect)
            prevRect!!.set(rect!!)
        }
        activeCanvas.restoreToCount(graphSaveCount)
        for (k in 0 until nColumns) {
            val offset = nColumns - k - 1 + dataOffset
            if (offset >= scores!!.size) continue
            rect!![0f, 0f, columnWidth] = (8 * baseSize).toFloat()
            rect!!.offset(plotBounds.left + k * columnWidth, internalPaddingTop.toFloat())
            drawFooter(activeCanvas, rect, scores!![offset].date)
        }
        if (activeCanvas !== canvas) canvas.drawBitmap(internalDrawingCache!!, 0f, 0f, null)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)
    }

    override fun onSizeChanged(
        width: Int,
        height: Int,
        oldWidth: Int,
        oldHeight: Int
    ) {
        var height = height
        if (height < 9) height = 200
        val maxTextSize = getDimension(context, R.dimen.tinyTextSize)
        val textSize = height * 0.06f
        pText!!.textSize = min(textSize, maxTextSize)
        em = pText!!.fontSpacing
        val footerHeight = (3 * em).toInt()
        internalPaddingTop = em.toInt()
        baseSize = (height - footerHeight - internalPaddingTop) / 8
        columnWidth = baseSize.toFloat()
        columnWidth = max(columnWidth, maxDayWidth * 1.5f)
        columnWidth = max(columnWidth, maxMonthWidth * 1.2f)
        pGraph!!.textSize = baseSize * 0.5f
        pGraph!!.strokeWidth = baseSize * 0.1f
        val markerRadius = baseSize * 0.275f
        val graphInset = markerRadius + pGraph!!.strokeWidth / 2f
        val yAxisLabelWidth = pText!!.measureText("100%") + em
        plotBounds.set(
            max(graphInset, yAxisLabelWidth),
            internalPaddingTop + graphInset,
            width - graphInset,
            internalPaddingTop + 8 * baseSize - graphInset
        )
        nColumns = max(1, (plotBounds.width() / columnWidth).toInt())
        columnWidth = plotBounds.width() / nColumns
        setScrollerBucketSize(columnWidth.toInt())
        columnHeight = plotBounds.height().toInt()
        val minStrokeWidth = dpToPixels(context, 1f)
        pGrid!!.strokeWidth = min(minStrokeWidth, baseSize * 0.05f)
        if (isTransparencyEnabled) initCache(width, height)
    }

    private fun drawFooter(canvas: Canvas?, rect: RectF?, date: LocalDate) {
        val df = dateFormatter ?: return
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
            pText!!.textAlign = Paint.Align.CENTER
            canvas!!.drawText(
                yearText,
                rect!!.centerX(),
                rect.bottom + em * 2.2f,
                pText!!
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
            pText!!.textAlign = Paint.Align.CENTER
            canvas!!.drawText(
                text,
                rect!!.centerX(),
                rect.bottom + em * 1.2f,
                pText!!
            )
        }
    }

    private fun drawGrid(canvas: Canvas?, rGrid: RectF?) {
        val nRows = 5
        val rowHeight = rGrid!!.height() / nRows
        var y = rGrid.top
        pText!!.textAlign = Paint.Align.LEFT
        pText!!.color = textColor
        pGrid!!.color = gridColor
        for (i in 0 until nRows) {
            canvas!!.drawText(
                String.format("%d%%", 100 - i * 100 / nRows),
                max(0f, rGrid.left - pText!!.measureText("100%") - 0.5f * em),
                y + 1f * em,
                pText!!
            )
            canvas.drawLine(
                rGrid.left,
                y,
                rGrid.right,
                y,
                pGrid!!
            )
            y += rowHeight
        }
        canvas!!.drawLine(rGrid.left, y, rGrid.right, y, pGrid!!)
    }

    private fun drawLine(canvas: Canvas?, rectFrom: RectF?, rectTo: RectF?) {
        pGraph!!.color = primaryColor
        canvas!!.drawLine(
            rectFrom!!.centerX(),
            rectFrom.centerY(),
            rectTo!!.centerX(),
            rectTo.centerY(),
            pGraph!!
        )
    }

    private fun drawMarker(canvas: Canvas?, rect: RectF?) {
        rect!!.inset(baseSize * 0.225f, baseSize * 0.225f)
        setModeOrColor(pGraph, XFERMODE_CLEAR, internalBackgroundColor)
        canvas!!.drawOval(rect, pGraph!!)
        rect.inset(baseSize * 0.1f, baseSize * 0.1f)
        setModeOrColor(pGraph, XFERMODE_SRC, primaryColor)
        canvas.drawOval(rect, pGraph!!)

//        rect.inset(baseSize * 0.1f, baseSize * 0.1f);
//        setModeOrColor(pGraph, XFERMODE_CLEAR, backgroundColor);
//        canvas.drawOval(rect, pGraph);
        if (isTransparencyEnabled) pGraph!!.xfermode = XFERMODE_SRC
    }

    private val maxDayWidth: Float
        private get() {
            val df = dateFormatter ?: return 0f
            var maxDayWidth = 0f
            for (i in 1..12) {
                val date = LocalDate(2020, i, 1)
                val monthWidth = pText!!.measureText(df.shortMonthName(date))
                maxDayWidth = max(maxDayWidth, monthWidth)
            }
            return maxDayWidth
        }
    private val maxMonthWidth: Float
        private get() {
            val df = dateFormatter ?: return 0f
            var maxMonthWidth = 0f
            for (i in 1..12) {
                val date = LocalDate(2020, i, 1)
                val monthWidth = pText!!.measureText(df.shortMonthName(date))
                maxMonthWidth = max(maxMonthWidth, monthWidth)
            }
            return maxMonthWidth
        }

    private fun init() {
        initPaints()
        initColors()
        initDateFormats()
        initRects()
    }

    private fun initCache(width: Int, height: Int) {
        if (internalDrawingCache != null) internalDrawingCache!!.recycle()
        val newDrawingCache = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        internalDrawingCache = newDrawingCache
        cacheCanvas = Canvas(newDrawingCache)
    }

    private fun initColors() {
        val res = StyledResources(context)
        primaryColor = Color.BLACK
        textColor = textColorOverride ?: res.getColor(R.attr.contrast60)
        gridColor = gridColorOverride ?: res.getColor(R.attr.contrast20)
        internalBackgroundColor = backgroundColorOverride ?: res.getColor(R.attr.cardBgColor)
    }

    private fun initDateFormats() {
        dateFormatter = JavaLocalDateFormatter(Locale.getDefault())
    }

    private fun initPaints() {
        pText = Paint()
        pText!!.isAntiAlias = true
        pGraph = Paint()
        pGraph!!.textAlign = Paint.Align.CENTER
        pGraph!!.isAntiAlias = true
        pGrid = Paint()
        pGrid!!.isAntiAlias = true
    }

    private fun initRects() {
        rect = RectF()
        prevRect = RectF()
    }

    private fun setModeOrColor(p: Paint?, mode: PorterDuffXfermode, color: Int) {
        if (isTransparencyEnabled) p!!.xfermode = mode else p!!.color = color
    }

    companion object {
        private val XFERMODE_CLEAR = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        private val XFERMODE_SRC = PorterDuffXfermode(PorterDuff.Mode.SRC)
    }
}
