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
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.habits.list.views.toShortString
import org.isoron.uhabits.utils.InterfaceUtils.dpToPixels
import org.isoron.uhabits.utils.InterfaceUtils.getDimension
import org.isoron.uhabits.utils.StyledResources
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class TargetChart : View {
    private val renderer = TargetChartRenderer(context)
    private val styled = StyledResources(context)
    private var baseSize = 0
    private var primaryColor = 0
    private var mediumContrastTextColor = styled.getColor(R.attr.contrast60)
    private var lowContrastTextColor = styled.getColor(R.attr.contrast20)
    private var barTextColorOverride: Int? = null
    private var values = emptyList<Double>()
    private var labels = emptyList<String>()
    private var targets = emptyList<Double>()
    private var isAtMost = false

    constructor(context: Context?) : super(context)

    constructor(context: Context?, attrs: AttributeSet?) : super(context, attrs)

    fun setColor(color: Int) {
        primaryColor = color
        postInvalidate()
    }

    fun setSurfaceColors(trackColor: Int, labelColor: Int, barTextColor: Int? = null) {
        lowContrastTextColor = trackColor
        mediumContrastTextColor = labelColor
        barTextColorOverride = barTextColor
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        renderer.draw(
            canvas, width, height, baseSize, labels, values, targets, isAtMost,
            primaryColor, lowContrastTextColor, mediumContrastTextColor, barTextColorOverride
        )
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        var widthSpec = widthSpec
        var heightSpec = heightSpec
        baseSize = renderer.rowHeight
        val width = MeasureSpec.getSize(widthSpec)
        var height = labels.size * baseSize
        val params = layoutParams
        if (params != null && params.height == ViewGroup.LayoutParams.MATCH_PARENT) {
            height = MeasureSpec.getSize(heightSpec)
            if (labels.isNotEmpty()) baseSize = height / labels.size
        }
        heightSpec = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        widthSpec = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY)
        setMeasuredDimension(widthSpec, heightSpec)
    }

    fun setValues(values: List<Double>) {
        this.values = values
        requestLayout()
    }

    fun setLabels(labels: List<String>) {
        this.labels = labels
        requestLayout()
    }

    fun setTargets(targets: List<Double>) {
        this.targets = targets
        requestLayout()
    }

    fun setIsAtMost(atMost: Boolean) {
        if (this.isAtMost != atMost) {
            this.isAtMost = atMost
            invalidate()
        }
    }

    companion object {
        private fun linearizeChannel(channel: Int): Double {
            val c = channel / 255.0
            return if (c <= 0.04045) {
                c / 12.92
            } else {
                Math.pow((c + 0.055) / 1.055, 2.4)
            }
        }

        fun resolveFilledBarTextColor(fillColor: Int): Int {
            val r = (fillColor ushr 16) and 0xFF
            val g = (fillColor ushr 8) and 0xFF
            val b = fillColor and 0xFF
            val luminance =
                0.2126 * linearizeChannel(r) +
                    0.7152 * linearizeChannel(g) +
                    0.0722 * linearizeChannel(b)
            return if (luminance > 0.42) {
                0xFF111318.toInt()
            } else {
                Color.WHITE
            }
        }
    }
}

private class TargetChartRenderer(context: Context) {
    val rowHeight = context.resources.getDimensionPixelSize(R.dimen.baseSize)
    private val padding = dpToPixels(context, 4f)
    private val radius = dpToPixels(context, 2f)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = getDimension(context, R.dimen.tinyTextSize)
    }
    private val bar = RectF()

    fun draw(
        canvas: Canvas,
        width: Int,
        height: Int,
        baseSize: Int,
        labels: List<String>,
        values: List<Double>,
        targets: List<Double>,
        isAtMost: Boolean,
        fillColor: Int,
        trackColor: Int,
        labelColor: Int,
        barTextColor: Int? = null
    ) {
        val labelWidth = labels.maxOfOrNull(paint::measureText) ?: return
        val stop = labelWidth + 2 * padding
        val marginTop = (height - baseSize * labels.size) / 2f
        val textOffset = (paint.descent() + paint.ascent()) / 2f
        val completedTextColor = barTextColor ?: TargetChart.resolveFilledBarTextColor(fillColor)
        labels.forEachIndexed { index, label ->
            val top = marginTop + index * baseSize
            val centerY = top + baseSize / 2f
            val value = values[index]
            val target = targets[index]
            paint.color = labelColor
            paint.textAlign = Paint.Align.RIGHT
            canvas.drawText(label, stop - padding, centerY - textOffset, paint)

            bar.set(stop + padding, top + baseSize * 0.05f, width - padding, top + baseSize * 0.95f)
            paint.color = trackColor
            canvas.drawRoundRect(bar, radius, radius, paint)

            val percentage = if (target > 0) min(1f, (value / target).toFloat()) else 1f
            var completedWidth = percentage * bar.width()
            if (completedWidth > 0 && completedWidth < 2 * radius) completedWidth = 2 * radius
            val remainingWidth = bar.width() - completedWidth
            bar.right = bar.left + completedWidth
            paint.color = fillColor
            canvas.drawRoundRect(bar, radius, radius, paint)

            val valueText = value.toShortString()
            val combined = "$valueText / ${target.toShortString()}"
            val completedText = if (isAtMost && value > target &&
                completedWidth > paint.measureText(combined) + 2 * padding
            ) combined else valueText
            paint.textAlign = Paint.Align.CENTER
            if (completedWidth > paint.measureText(completedText) + 2 * padding) {
                paint.color = completedTextColor
                canvas.drawText(completedText, bar.centerX(), centerY - textOffset, paint)
            }

            val remaining = max(0.0, target - value)
            val remainingText = remaining.toShortString()
            if (remaining > 0 && remainingWidth > paint.measureText(remainingText) + 2 * padding) {
                paint.color = labelColor
                bar.left = bar.right
                bar.right = width - padding
                canvas.drawText(remainingText, bar.centerX(), centerY - textOffset, paint)
            }
        }
    }
}

@Composable
@OptIn(ExperimentalComposeUiApi::class)
internal fun TargetProgressChart(
    labels: List<String>,
    values: List<Double>,
    targets: List<Double>,
    unit: String,
    isAtMost: Boolean,
    fillColor: Int,
    trackColor: Int,
    labelColor: Int
) {
    val context = LocalContext.current
    val renderer = remember(context) { TargetChartRenderer(context) }
    val height = with(LocalDensity.current) { (renderer.rowHeight * labels.size).toDp() }
    val summary = labels.indices.joinToString(". ") { index ->
        "${labels[index]} ${values[index].toShortString()} / ${targets[index].toShortString()} $unit".trim()
    }
    Canvas(
        Modifier.fillMaxWidth().height(height).semantics {
            contentDescription = summary
            testTagsAsResourceId = true
        }.testTag("habitTargetProgress"),
    ) {
        drawIntoCanvas { canvas ->
            renderer.draw(
                canvas.nativeCanvas, size.width.roundToInt(), size.height.roundToInt(), renderer.rowHeight,
                labels, values, targets, isAtMost, fillColor, trackColor, labelColor
            )
        }
    }
}
