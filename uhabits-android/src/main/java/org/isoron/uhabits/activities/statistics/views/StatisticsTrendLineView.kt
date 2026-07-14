package org.isoron.uhabits.activities.statistics.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.activities.common.theme.MainTabsThemeBridge
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class StatisticsTrendLineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class TrendPoint(
        val date: LocalDate,
        val progress: Double,
        val label: String
    )

    private var points: List<TrendPoint> = emptyList()
    private var prevPoints: List<Double> = emptyList()

    private var accentColor: Int = 0
    private var onSurfaceVariantColor: Int = 0
    private var dividerColor: Int = 0
    private var surfaceColor: Int = 0

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        strokeCap = Paint.Cap.ROUND
    }

    private val prevLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        strokeCap = Paint.Cap.ROUND
        pathEffect = DashPathEffect(floatArrayOf(dp(4f), dp(4f)), 0f)
    }

    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(10f)
        textAlign = Paint.Align.CENTER
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(0.75f)
    }

    private var selectedIndex = -1
    private val path = Path()

    fun setData(
        current: List<TrendPoint>,
        previous: List<Double>,
        accentColor: Int,
        onSurfaceVariant: Int,
        divider: Int,
        surface: Int
    ) {
        this.points = current
        this.prevPoints = previous
        this.accentColor = accentColor
        this.onSurfaceVariantColor = onSurfaceVariant
        this.dividerColor = divider
        this.surfaceColor = surface

        linePaint.color = accentColor
        prevLinePaint.color = MainTabsThemeBridge.withAlpha(onSurfaceVariant, 0.4f)
        pointPaint.color = accentColor
        labelPaint.color = onSurfaceVariant
        gridPaint.color = divider

        selectedIndex = -1
        updateAccessibilityDescription()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (points.isEmpty()) return

        val paddingLeft = dp(16f)
        val paddingRight = dp(16f)
        val paddingTop = dp(24f)
        val paddingBottom = dp(24f)

        val w = width.toFloat() - paddingLeft - paddingRight
        val h = height.toFloat() - paddingTop - paddingBottom

        // Draw horizontal grid lines (0%, 50%, 100%)
        repeat(3) { i ->
            val y = paddingTop + i * (h / 2)
            canvas.drawLine(paddingLeft, y, width.toFloat() - paddingRight, y, gridPaint)
        }

        // Draw previous period dashed line
        if (prevPoints.isNotEmpty() && prevPoints.size == points.size) {
            path.reset()
            val step = w / (prevPoints.size - 1).coerceAtLeast(1)
            prevPoints.forEachIndexed { i, value ->
                val x = paddingLeft + i * step
                val y = paddingTop + h - (value.coerceIn(0.0, 1.0).toFloat() * h)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, prevLinePaint)
        }

        // Draw current period line
        path.reset()
        val step = w / (points.size - 1).coerceAtLeast(1)
        points.forEachIndexed { i, pt ->
            val x = paddingLeft + i * step
            val y = paddingTop + h - (pt.progress.coerceIn(0.0, 1.0).toFloat() * h)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, linePaint)

        // Draw points and labels
        points.forEachIndexed { i, pt ->
            val x = paddingLeft + i * step
            val y = paddingTop + h - (pt.progress.coerceIn(0.0, 1.0).toFloat() * h)

            // Draw point dot
            canvas.drawCircle(x, y, dp(3f), pointPaint)

            // Draw X-axis label (for first, middle, last to avoid overlap)
            if (i == 0 || i == points.lastIndex || (points.size > 4 && i == points.size / 2)) {
                canvas.drawText(pt.label, x, height.toFloat() - dp(6f), labelPaint)
            }
        }

        // Draw selection tooltip
        if (selectedIndex in points.indices) {
            val pt = points[selectedIndex]
            val x = paddingLeft + selectedIndex * step
            val y = paddingTop + h - (pt.progress.coerceIn(0.0, 1.0).toFloat() * h)

            // Draw selection guide line
            canvas.drawLine(x, paddingTop, x, height.toFloat() - paddingBottom, gridPaint)

            // Draw tooltip bubble
            val tooltipText = String.format(Locale.getDefault(), "%d%%", (pt.progress * 100).roundToInt())
            val tooltipWidth = labelPaint.measureText(tooltipText) + dp(12f)
            val tooltipHeight = dp(20f)
            val tx = min(max(x - tooltipWidth / 2, paddingLeft), width.toFloat() - paddingRight - tooltipWidth)
            val ty = max(y - dp(12f) - tooltipHeight, dp(4f))

            val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = surfaceColor
                style = Paint.Style.FILL
            }
            val bubbleBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = onSurfaceVariantColor
                style = Paint.Style.STROKE
                strokeWidth = dp(1f)
            }

            canvas.drawRoundRect(tx, ty, tx + tooltipWidth, ty + tooltipHeight, dp(6f), dp(6f), bubblePaint)
            canvas.drawRoundRect(tx, ty, tx + tooltipWidth, ty + tooltipHeight, dp(6f), dp(6f), bubbleBorderPaint)

            val textPaint = Paint(labelPaint).apply {
                color = onSurfaceVariantColor
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText(
                tooltipText,
                tx + tooltipWidth / 2,
                ty + tooltipHeight / 2 - (textPaint.descent() + textPaint.ascent()) / 2f,
                textPaint
            )
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (points.isEmpty()) return false
        val x = event.x
        val paddingLeft = dp(16f)
        val w = width.toFloat() - paddingLeft - dp(16f)
        val step = w / (points.size - 1).coerceAtLeast(1)

        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val index = ((x - paddingLeft) / step).roundToInt().coerceIn(points.indices)
                if (index != selectedIndex) {
                    selectedIndex = index
                    updateAccessibilityDescription()
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                selectedIndex = -1
                updateAccessibilityDescription()
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun updateAccessibilityDescription() {
        if (points.isEmpty()) {
            contentDescription = null
            return
        }
        if (selectedIndex in points.indices) {
            val pt = points[selectedIndex]
            contentDescription = "${pt.label}: ${(pt.progress * 100).roundToInt()}%"
        } else {
            val avg = (points.map { it.progress }.average() * 100).roundToInt()
            contentDescription = "Trend line chart. Average: $avg%."
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
    private fun sp(value: Float): Float = value * resources.displayMetrics.scaledDensity
}
