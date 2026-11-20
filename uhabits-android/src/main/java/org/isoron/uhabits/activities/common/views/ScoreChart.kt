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
import android.graphics.Color
import android.util.AttributeSet
import org.isoron.platform.time.getToday
import org.isoron.uhabits.R
import org.isoron.uhabits.core.models.Score
import org.isoron.uhabits.utils.StyledResources
import java.util.LinkedList
import java.util.Random
import kotlin.math.max
import kotlin.math.min

class ScoreChart : ScrollableChart {
    private val renderer = ScoreChartRenderer()
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
    private var isTransparencyEnabled = false

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
        renderer.draw(
            context = context,
            canvas = canvas,
            width = width,
            height = height,
            scores = scores,
            dataOffset = dataOffset,
            bucketSize = bucketSize,
            primaryColor = primaryColor,
            textColor = textColor,
            gridColor = gridColor,
            backgroundColor = internalBackgroundColor,
            isTransparencyEnabled = isTransparencyEnabled
        )
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        val bucketWidth = renderer.updateSize(context, width, height)
        setScrollerBucketSize(bucketWidth)
    }

    private fun init() {
        val res = StyledResources(context)
        primaryColor = Color.BLACK
        textColor = textColorOverride ?: res.getColor(R.attr.contrast60)
        gridColor = gridColorOverride ?: res.getColor(R.attr.contrast20)
        internalBackgroundColor = backgroundColorOverride ?: res.getColor(R.attr.cardBgColor)
    }
}
