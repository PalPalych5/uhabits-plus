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
import android.graphics.Paint
import android.graphics.RectF
import org.isoron.platform.time.JavaLocalDateFormatter
import org.isoron.uhabits.R
import org.isoron.uhabits.core.models.Streak
import org.isoron.uhabits.utils.InterfaceUtils.dpToPixels
import org.isoron.uhabits.utils.InterfaceUtils.getDimension
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Shared native Canvas renderer for [StreakChart] and its Compose Canvas host.
 * Preserves exact bar width scaling, label suppression on narrow widths, and color thresholds.
 */
class StreakChartRenderer {
    private val paint = Paint().apply {
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }
    private val rect = RectF()
    private val colors = IntArray(4)
    private val textColors = IntArray(3)
    private var dateFormatter = JavaLocalDateFormatter(Locale.getDefault())
    private var currentLocale: Locale = Locale.getDefault()

    private var baseSize = 0
    private var em = 0f
    private var textMargin = 0f
    private var maxLength = 0L
    private var maxLabelWidth = 0f
    private var shouldShowLabels = false

    fun resolveBaseSize(context: Context): Int {
        if (baseSize == 0) {
            baseSize = context.resources.getDimensionPixelSize(R.dimen.baseSize)
        }
        return baseSize
    }

    fun draw(
        context: Context,
        canvas: Canvas,
        width: Int,
        streaks: List<Streak>,
        maxLengthOverride: Long?,
        primaryColor: Int,
        trackColor: Int,
        labelColor: Int,
        filledTextColor: Int
    ) {
        if (width <= 0 || streaks.isEmpty()) return

        val locale = Locale.getDefault()
        if (locale != currentLocale) {
            currentLocale = locale
            dateFormatter = JavaLocalDateFormatter(locale)
        }

        val rowHeight = resolveBaseSize(context)
        val minTextSize = getDimension(context, R.dimen.tinyTextSize)
        val maxTextSize = getDimension(context, R.dimen.regularTextSize)
        val textSize = rowHeight * 0.5f
        paint.textSize = max(min(textSize, maxTextSize), minTextSize)
        em = paint.fontSpacing
        textMargin = 0.5f * em

        updateMetrics(width, streaks, maxLengthOverride)
        initColors(primaryColor, trackColor, labelColor, filledTextColor)

        rect.set(0f, 0f, width.toFloat(), rowHeight.toFloat())
        val round = dpToPixels(context, 2f)
        for (s in streaks) {
            drawRow(canvas, width, rowHeight, s, rect, round)
            rect.offset(0f, rowHeight.toFloat())
        }
    }

    private fun updateMetrics(width: Int, streaks: List<Streak>, maxLengthOverride: Long?) {
        maxLength = 0L
        maxLabelWidth = 0f
        shouldShowLabels = true
        for (s in streaks) {
            if (s.length <= 0) continue
            maxLength = max(maxLength, s.length.toLong())
            val lw1 = paint.measureText(dateFormatter.longFormat(s.start))
            val lw2 = paint.measureText(dateFormatter.longFormat(s.end))
            maxLabelWidth = max(maxLabelWidth, max(lw1, lw2))
        }
        maxLength = max(maxLength, maxLengthOverride ?: 0L)
        if (width - 2 * maxLabelWidth < width * 0.25f) {
            maxLabelWidth = 0f
            shouldShowLabels = false
        }
    }

    private fun initColors(
        primaryColor: Int,
        trackColor: Int,
        labelColor: Int,
        filledTextColor: Int
    ) {
        val red = Color.red(primaryColor)
        val green = Color.green(primaryColor)
        val blue = Color.blue(primaryColor)
        colors[3] = primaryColor
        colors[2] = Color.argb(192, red, green, blue)
        colors[1] = Color.argb(96, red, green, blue)
        colors[0] = trackColor
        textColors[2] = filledTextColor
        textColors[1] = labelColor
        textColors[0] = labelColor
    }

    private fun drawRow(
        canvas: Canvas,
        width: Int,
        rowHeight: Int,
        streak: Streak,
        rect: RectF,
        round: Float
    ) {
        if (maxLength == 0L || streak.length <= 0) return
        val percentage = streak.length.toFloat() / maxLength
        var availableWidth = width - 2 * maxLabelWidth
        if (shouldShowLabels) availableWidth -= 2 * textMargin
        var barWidth = percentage * availableWidth
        val minBarWidth = paint.measureText(streak.length.toLong().toString()) + em
        barWidth = max(barWidth, minBarWidth)
        val gap = (width - barWidth) / 2
        val paddingTopBottom = rowHeight * 0.05f
        paint.color = percentageToColor(percentage)
        canvas.drawRoundRect(
            rect.left + gap,
            rect.top + paddingTopBottom,
            rect.right - gap,
            rect.bottom - paddingTopBottom,
            round,
            round,
            paint
        )
        val yOffset = rect.centerY() + 0.3f * em
        paint.color = percentageToTextColor(percentage)
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(
            streak.length.toLong().toString(),
            rect.centerX(),
            yOffset,
            paint
        )
        if (shouldShowLabels) {
            val startLabel = dateFormatter.longFormat(streak.start)
            val endLabel = dateFormatter.longFormat(streak.end)
            paint.color = textColors[1]
            paint.textAlign = Paint.Align.RIGHT
            canvas.drawText(startLabel, gap - textMargin, yOffset, paint)
            paint.textAlign = Paint.Align.LEFT
            canvas.drawText(endLabel, width - gap + textMargin, yOffset, paint)
        }
    }

    private fun percentageToColor(percentage: Float): Int {
        if (percentage >= 1.0f) return colors[3]
        if (percentage >= 0.8f) return colors[2]
        return if (percentage >= 0.5f) colors[1] else colors[0]
    }

    private fun percentageToTextColor(percentage: Float): Int {
        return if (percentage >= 0.5f) textColors[2] else textColors[1]
    }
}
