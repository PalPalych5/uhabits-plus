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
import android.util.AttributeSet
import org.isoron.platform.time.DayOfWeek
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.getToday
import org.isoron.uhabits.R
import org.isoron.uhabits.utils.StyledResources
import java.util.Random

class FrequencyChart : ScrollableChart {
    private val renderer = FrequencyChartRenderer()
    private var textColor = 0
    private var gridColor = 0
    private var primaryColor = 0
    private var textColorOverride: Int? = null
    private var gridColorOverride: Int? = null
    private var isBackgroundTransparent = false
    private lateinit var frequency: HashMap<LocalDate, Array<Int>>
    private var maxFreq = 0
    private var firstWeekday: DayOfWeek = DayOfWeek.SUNDAY
    private var isNumerical: Boolean = false

    constructor(context: Context?) : super(context) {
        frequency = HashMap()
        initColors()
    }

    constructor(context: Context?, attrs: AttributeSet?) : super(context, attrs) {
        frequency = HashMap()
        initColors()
    }

    fun setColor(color: Int) {
        primaryColor = color
        initColors()
        postInvalidate()
    }

    fun setSurfaceColors(labelColor: Int, gridColor: Int) {
        textColorOverride = labelColor
        gridColorOverride = gridColor
        initColors()
        postInvalidate()
    }

    fun setIsNumerical(type: Boolean) {
        isNumerical = type
        postInvalidate()
    }

    fun setFrequency(frequency: java.util.HashMap<LocalDate, Array<Int>>) {
        this.frequency = frequency
        maxFreq = FrequencyChartRenderer.computeMaxFreq(frequency)
        postInvalidate()
    }

    fun setFirstWeekday(firstWeekday: DayOfWeek) {
        this.firstWeekday = firstWeekday
        postInvalidate()
    }

    fun setIsBackgroundTransparent(isBackgroundTransparent: Boolean) {
        this.isBackgroundTransparent = isBackgroundTransparent
        initColors()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        renderer.draw(
            canvas = canvas,
            width = width,
            height = height,
            frequency = frequency,
            maxFreq = maxFreq,
            dataOffset = dataOffset,
            firstWeekday = firstWeekday,
            isNumerical = isNumerical,
            primaryColor = primaryColor,
            textColor = textColor,
            gridColor = gridColor
        )
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        val bucketWidth = renderer.updateSize(width, height)
        setScrollerBucketSize(bucketWidth)
    }

    private fun initColors() {
        val res = StyledResources(context)
        textColor = textColorOverride ?: res.getColor(R.attr.contrast60)
        gridColor = gridColorOverride ?: res.getColor(R.attr.contrast20)
    }

    fun populateWithRandomData() {
        val today = getToday()
        var date = LocalDate(today.year, today.month, 1)
        val rand = Random()
        frequency.clear()
        for (i in 0..39) {
            val values = IntArray(7) { rand.nextInt(5) }.toTypedArray()
            frequency[date] = values
            date = FrequencyChartRenderer.stepMonth(date, -1)
        }
        maxFreq = FrequencyChartRenderer.computeMaxFreq(frequency)
    }
}
