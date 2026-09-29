package org.isoron.uhabits.activities.statistics.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.ColorUtils
import kotlin.math.roundToInt

/**
 * Shared Percentage Chart Scaffold following Loop Habit Tracker's visual language:
 * - 100%, 75%, 50%, 25%, 0% quiet horizontal grid lines
 * - Bars start at baseline 0%
 * - Value labels cleanly above bars
 * - Loop bar geometry: narrow bars, corner radius 15% of width
 * - Valid 0% draws at baseline with label; null/future draws no bar
 * - Subdued axis labels for future items, subtle accent for today
 */
class StatisticsPercentageBarChart @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class BarItem(
        val progress: Double?, // null = no data / future; 0.0 = real 0%
        val label: String,     // X-axis footer label
        val isToday: Boolean = false,
        val isFuture: Boolean = false
    )

    private var items: List<BarItem> = emptyList()
    private var barColor: Int = 0
    private var onSurfaceVariantColor: Int = 0
    private var dividerColor: Int = 0

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(0.75f)
    }
    private val gridLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(9f)
        textAlign = Paint.Align.RIGHT
        fontFeatureSettings = "tnum"
    }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val axisLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(10f)
        textAlign = Paint.Align.CENTER
    }
    private val valueLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(9.5f)
        textAlign = Paint.Align.CENTER
        fontFeatureSettings = "tnum"
    }

    private val rect = RectF()

    fun setData(
        items: List<BarItem>,
        barColor: Int,
        onSurfaceVariant: Int,
        dividerColor: Int
    ) {
        this.items = items
        this.barColor = barColor
        this.onSurfaceVariantColor = onSurfaceVariant
        this.dividerColor = dividerColor

        barPaint.color = barColor
        gridPaint.strokeWidth = dp(0.5f)
        gridPaint.color = ColorUtils.setAlphaComponent(onSurfaceVariant, 38)
        gridLabelPaint.color = ColorUtils.setAlphaComponent(onSurfaceVariant, 140)
        axisLabelPaint.color = onSurfaceVariant
        valueLabelPaint.color = barColor

        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = dp(148f).roundToInt()
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (items.isEmpty()) return

        val w = width.toFloat()
        val h = height.toFloat()

        val plotLeft = dp(34f)
        val plotRight = w - dp(12f)
        val plotTop = dp(28f)
        val plotBottom = h - dp(24f)
        val plotHeight = plotBottom - plotTop

        // 1. Draw 100%, 75%, 50%, 25%, 0% horizontal grid lines and labels
        // Like Loop BarChart, gridlines are drawn inside (75%, 50%, 25%, 0%) to keep 100% label free
        val gridPcts = intArrayOf(100, 75, 50, 25, 0)
        val labelOffset = (gridLabelPaint.descent() + gridLabelPaint.ascent()) / 2f
        for (pct in gridPcts) {
            val y = plotTop + plotHeight * (1f - pct / 100f)
            if (pct < 100) {
                canvas.drawLine(plotLeft, y, plotRight, y, gridPaint)
            }
            canvas.drawText("$pct%", plotLeft - dp(4f), y - labelOffset, gridLabelPaint)
        }

        // 2. Draw fixed-column bars and axis labels
        val nColumns = items.size
        val colWidth = (plotRight - plotLeft) / nColumns
        val maxBarW = dp(12f)
        val barWidth = minOf(maxBarW, colWidth * 0.44f)
        val cornerRadius = barWidth * 0.15f

        items.forEachIndexed { i, item ->
            val cx = plotLeft + (i + 0.5f) * colWidth

            // Axis label
            if (item.isToday) {
                axisLabelPaint.color = barColor
                axisLabelPaint.typeface = Typeface.DEFAULT_BOLD
            } else if (item.isFuture) {
                axisLabelPaint.color = ColorUtils.setAlphaComponent(onSurfaceVariantColor, 90)
                axisLabelPaint.typeface = Typeface.DEFAULT
            } else {
                axisLabelPaint.color = onSurfaceVariantColor
                axisLabelPaint.typeface = Typeface.DEFAULT
            }
            canvas.drawText(item.label, cx, h - dp(6f), axisLabelPaint)

            // Bar drawing & value label
            if (!item.isFuture && item.progress != null) {
                val progress = item.progress.coerceIn(0.0, 1.0).toFloat()
                val barH = progress * plotHeight
                val barTop = plotBottom - barH

                if (barH > 0f) {
                    val actualH = barH.coerceAtLeast(dp(2f))
                    rect.set(cx - barWidth / 2f, plotBottom - actualH, cx + barWidth / 2f, plotBottom)
                    canvas.drawRoundRect(rect, cornerRadius, cornerRadius, barPaint)
                } else {
                    // Valid 0% sits at baseline
                    rect.set(cx - barWidth / 2f, plotBottom - dp(1.5f), cx + barWidth / 2f, plotBottom)
                    canvas.drawRoundRect(rect, dp(0.75f), dp(0.75f), barPaint)
                }

                // Label cleanly above bar
                val valueY = if (barH > 0f) barTop - dp(5f) else plotBottom - dp(4f)
                val labelText = "${(progress * 100).roundToInt()}%"
                canvas.drawText(labelText, cx, valueY, valueLabelPaint)
            }
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
    private fun sp(value: Float): Float = value * resources.displayMetrics.scaledDensity
}
