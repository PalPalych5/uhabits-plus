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
import android.view.View
import android.view.ViewGroup
import org.isoron.platform.time.getToday
import org.isoron.uhabits.R
import org.isoron.uhabits.core.models.Streak
import org.isoron.uhabits.utils.StyledResources
import java.util.LinkedList
import java.util.Random
import kotlin.math.floor

class StreakChart : View {
    private val renderer = StreakChartRenderer()
    private var maxLengthOverride: Long? = null
    private var primaryColor = 0
    private var trackColor = 0
    private var labelColor = 0
    private var filledTextColor = 0
    private var trackColorOverride: Int? = null
    private var labelColorOverride: Int? = null
    private var filledTextColorOverride: Int? = null
    private var streaks: List<Streak> = emptyList()
    private var isBackgroundTransparent = false

    constructor(context: Context?) : super(context) {
        initColors()
    }

    constructor(context: Context?, attrs: AttributeSet?) : super(context, attrs) {
        initColors()
    }

    /**
     * Returns the maximum number of streaks this view is able to show, given
     * its current size.
     *
     * @return max number of visible streaks
     */
    val maxStreakCount: Int
        get() = floor((measuredHeight / renderer.resolveBaseSize(context)).toDouble()).toInt()

    fun populateWithRandomData() {
        var start = getToday()
        val generated: MutableList<Streak> = LinkedList()
        for (i in 0..9) {
            val length = Random().nextInt(100)
            val end = start.plus(length)
            generated.add(Streak(start, end))
            start = end.plus(1)
        }
        setStreaks(generated)
    }

    fun setColor(color: Int) {
        primaryColor = color
        initColors()
        postInvalidate()
    }

    fun setSurfaceColors(trackColor: Int, labelColor: Int, filledBarTextColor: Int? = null) {
        trackColorOverride = trackColor
        labelColorOverride = labelColor
        filledTextColorOverride = filledBarTextColor
        initColors()
        postInvalidate()
    }

    fun setIsBackgroundTransparent(isBackgroundTransparent: Boolean) {
        this.isBackgroundTransparent = isBackgroundTransparent
        initColors()
    }

    fun setStreaks(streaks: List<Streak>?) {
        this.streaks = streaks ?: emptyList()
        initColors()
        requestLayout()
    }

    fun setMaxLengthOverride(maxLength: Long?) {
        maxLengthOverride = maxLength
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        renderer.draw(
            context = context,
            canvas = canvas,
            width = width,
            streaks = streaks,
            maxLengthOverride = maxLengthOverride,
            primaryColor = primaryColor,
            trackColor = trackColor,
            labelColor = labelColor,
            filledTextColor = filledTextColor
        )
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        var resolvedWidthSpec = widthSpec
        var resolvedHeightSpec = heightSpec
        val params = layoutParams
        if (params != null && params.height == ViewGroup.LayoutParams.WRAP_CONTENT) {
            val width = MeasureSpec.getSize(widthSpec)
            val height = streaks.size * renderer.resolveBaseSize(context)
            resolvedHeightSpec = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
            resolvedWidthSpec = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY)
        }
        setMeasuredDimension(resolvedWidthSpec, resolvedHeightSpec)
    }

    private fun initColors() {
        val res = StyledResources(context)
        trackColor = trackColorOverride ?: res.getColor(R.attr.contrast20)
        labelColor = labelColorOverride ?: res.getColor(R.attr.contrast60)
        filledTextColor = filledTextColorOverride ?: TargetChart.resolveFilledBarTextColor(primaryColor)
    }
}
