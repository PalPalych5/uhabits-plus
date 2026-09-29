package org.isoron.uhabits.activities.statistics.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.ColorUtils
import org.isoron.platform.time.JavaLocalDateFormatter
import org.isoron.platform.time.LocalDate
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Multi-series Loop Score / Stability line chart:
 * - 0%, 25%, 50%, 75%, 100% subtle grid
 * - Guaranteed distinct series colors
 * - Point markers on lines
 * - Touch inspection showing all visible habit values at touched date
 */
class CompareHabitsMultiLineChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class SeriesItem(
        val habitId: Long,
        val label: String,
        val color: Int,
        val points: List<Pair<LocalDate, Double>>,
        var isVisible: Boolean = true
    )

    private var seriesList: List<SeriesItem> = emptyList()
    private var allDates: List<LocalDate> = emptyList()

    private var onSurfaceColor: Int = 0
    private var onSurfaceVariantColor: Int = 0
    private var surfaceColor: Int = 0
    private var dividerColor: Int = 0

    private val dateFormatter = JavaLocalDateFormatter(Locale.getDefault())

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(0.75f)
    }
    private val gridLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(9f)
        textAlign = Paint.Align.RIGHT
        fontFeatureSettings = "tnum"
    }
    private val axisLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(9.5f)
        textAlign = Paint.Align.CENTER
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.2f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val pointStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
    }
    private val scrubLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val tooltipBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val tooltipStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val tooltipTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(11f)
    }

    private val path = Path()
    private val tooltipRect = RectF()

    private var scrubIndex: Int? = null

    fun setData(
        series: List<SeriesItem>,
        rangeStart: LocalDate,
        rangeEnd: LocalDate,
        surfaceColor: Int,
        onSurfaceColor: Int,
        onSurfaceVariantColor: Int,
        dividerColor: Int
    ) {
        this.seriesList = series
        this.surfaceColor = surfaceColor
        this.onSurfaceColor = onSurfaceColor
        this.onSurfaceVariantColor = onSurfaceVariantColor
        this.dividerColor = dividerColor

        gridPaint.color = ColorUtils.setAlphaComponent(dividerColor, 75)
        gridLabelPaint.color = ColorUtils.setAlphaComponent(onSurfaceVariantColor, 150)
        axisLabelPaint.color = onSurfaceVariantColor
        scrubLinePaint.color = ColorUtils.setAlphaComponent(onSurfaceColor, 120)
        tooltipBgPaint.color = ColorUtils.blendARGB(surfaceColor, onSurfaceColor, 0.16f)
        tooltipStrokePaint.color = ColorUtils.setAlphaComponent(dividerColor, 140)

        val dates = mutableListOf<LocalDate>()
        var date = rangeStart
        while (date <= rangeEnd) {
            dates.add(date)
            date = date.plus(1)
        }
        this.allDates = dates

        invalidate()
    }

    fun toggleSeriesVisibility(habitId: Long) {
        seriesList.firstOrNull { it.habitId == habitId }?.let {
            it.isVisible = !it.isVisible
            invalidate()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = dp(200f).roundToInt()
        setMeasuredDimension(width, height)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (allDates.size < 2) return super.onTouchEvent(event)
        val plotLeft = dp(34f)
        val plotRight = width - dp(12f)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val x = event.x.coerceIn(plotLeft, plotRight)
                val fraction = (x - plotLeft) / (plotRight - plotLeft)
                val idx = (fraction * (allDates.size - 1)).roundToInt().coerceIn(0, allDates.lastIndex)
                scrubIndex = idx
                parent.requestDisallowInterceptTouchEvent(true)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                scrubIndex = null
                parent.requestDisallowInterceptTouchEvent(false)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        val plotLeft = dp(34f)
        val plotRight = w - dp(12f)
        val plotTop = dp(16f)
        val plotBottom = h - dp(22f)
        val plotHeight = plotBottom - plotTop

        // 1. Grid lines: 100, 75, 50, 25, 0
        val gridPcts = intArrayOf(100, 75, 50, 25, 0)
        val labelOffset = (gridLabelPaint.descent() + gridLabelPaint.ascent()) / 2f
        for (pct in gridPcts) {
            val y = plotTop + plotHeight * (1f - pct / 100f)
            canvas.drawLine(plotLeft, y, plotRight, y, gridPaint)
            canvas.drawText("$pct%", plotLeft - dp(4f), y - labelOffset, gridLabelPaint)
        }

        if (allDates.isEmpty()) return

        // 2. Footer date labels: first and last date
        val firstDate = allDates.first()
        val lastDate = allDates.last()
        axisLabelPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("${firstDate.day} ${dateFormatter.shortMonthName(firstDate)}", plotLeft, h - dp(4f), axisLabelPaint)
        axisLabelPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("${lastDate.day} ${dateFormatter.shortMonthName(lastDate)}", plotRight, h - dp(4f), axisLabelPaint)

        val totalPoints = allDates.size
        fun xForIndex(i: Int): Float {
            return if (totalPoints <= 1) (plotLeft + plotRight) / 2f
            else plotLeft + (plotRight - plotLeft) * (i.toFloat() / (totalPoints - 1))
        }

        // 3. Draw visible series lines
        seriesList.filter { it.isVisible }.forEach { series ->
            if (series.points.isEmpty()) return@forEach
            linePaint.color = series.color
            pointPaint.color = series.color
            pointStrokePaint.color = surfaceColor

            path.reset()
            var started = false

            val pointsByDate = series.points.associate { it.first to it.second }
            allDates.forEachIndexed { idx, date ->
                val value = pointsByDate[date]
                if (value == null) {
                    if (started) canvas.drawPath(path, linePaint)
                    path.reset()
                    started = false
                    return@forEachIndexed
                }
                val x = xForIndex(idx)
                val frac = value.coerceIn(0.0, 1.0).toFloat()
                val y = plotBottom - frac * plotHeight

                if (!started) {
                    path.moveTo(x, y)
                    started = true
                } else {
                    path.lineTo(x, y)
                }

                // Draw point markers on start, end, or sparse points
                if (idx == 0 || idx == totalPoints - 1 || totalPoints <= 14) {
                    canvas.drawCircle(x, y, dp(3.5f), pointStrokePaint)
                    canvas.drawCircle(x, y, dp(2.8f), pointPaint)
                }
            }
            if (started) canvas.drawPath(path, linePaint)
        }

        // 4. Scrubbing inspection hairline & tooltip popup
        scrubIndex?.let { sIdx ->
            val scrubDate = allDates.getOrNull(sIdx) ?: return@let
            val sx = xForIndex(sIdx)
            canvas.drawLine(sx, plotTop, sx, plotBottom, scrubLinePaint)

            // Collect values of visible series on this date
            val dateLabel = "${scrubDate.day} ${dateFormatter.shortMonthName(scrubDate)}"
            val rows = mutableListOf<Triple<String, Int, String>>() // (name, color, valText)
            seriesList.filter { it.isVisible }.forEach { s ->
                val pt = s.points.firstOrNull { it.first == scrubDate }
                if (pt != null) {
                    rows.add(Triple(s.label, s.color, "${(pt.second * 100).roundToInt()}%"))
                }
            }

            if (rows.isNotEmpty()) {
                val padH = dp(10f)
                val padV = dp(6f)
                val lineH = dp(16f)
                val tipW = dp(150f)
                val tipH = padV * 2 + lineH * (rows.size + 1)

                var tipX = sx - tipW / 2f
                tipX = tipX.coerceIn(dp(8f), w - tipW - dp(8f))
                var tipY = plotTop + dp(8f)

                tooltipRect.set(tipX, tipY, tipX + tipW, tipY + tipH)
                canvas.drawRoundRect(tooltipRect, dp(6f), dp(6f), tooltipBgPaint)
                canvas.drawRoundRect(tooltipRect, dp(6f), dp(6f), tooltipStrokePaint)

                // Date header in tooltip
                tooltipTextPaint.color = onSurfaceVariantColor
                tooltipTextPaint.typeface = Typeface.DEFAULT_BOLD
                tooltipTextPaint.textAlign = Paint.Align.LEFT
                canvas.drawText(dateLabel, tipX + padH, tipY + padV + dp(11f), tooltipTextPaint)

                // Series rows
                rows.forEachIndexed { rIdx, (name, color, valStr) ->
                    val rowY = tipY + padV + dp(11f) + (rIdx + 1) * lineH
                    pointPaint.color = color
                    canvas.drawCircle(tipX + padH + dp(4f), rowY - dp(3.5f), dp(3f), pointPaint)

                    tooltipTextPaint.color = onSurfaceColor
                    tooltipTextPaint.typeface = Typeface.DEFAULT
                    tooltipTextPaint.textAlign = Paint.Align.LEFT
                    val truncatedName = if (name.length > 12) name.take(11) + "…" else name
                    canvas.drawText(truncatedName, tipX + padH + dp(12f), rowY, tooltipTextPaint)

                    tooltipTextPaint.textAlign = Paint.Align.RIGHT
                    tooltipTextPaint.typeface = Typeface.DEFAULT_BOLD
                    canvas.drawText(valStr, tipX + tipW - padH, rowY, tooltipTextPaint)
                }
            }
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
    private fun sp(value: Float): Float = value * resources.displayMetrics.scaledDensity
}
