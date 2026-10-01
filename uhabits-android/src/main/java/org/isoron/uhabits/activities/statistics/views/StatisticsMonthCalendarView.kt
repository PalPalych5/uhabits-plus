package org.isoron.uhabits.activities.statistics.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.ColorUtils
import org.isoron.platform.time.DayOfWeek
import org.isoron.platform.time.JavaLocalDateFormatter
import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.core.ui.screens.statistics.StatisticsDailyProgress
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Compact Month Calendar following Loop Habit Tracker design language:
 * - Left side: compact statistics context (Среднее, Лучший день)
 * - Center: compact month calendar (Loop HistoryChart cell size ~19dp)
 * - Right side: vertical discrete intensity legend (100% to 0%)
 * - Touch inspection: hover cell highlight + tooltip popup on touch/drag
 */
class StatisticsMonthCalendarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var monthStart: LocalDate? = null
    private var monthEnd: LocalDate? = null
    private var firstWeekday: DayOfWeek = DayOfWeek.MONDAY
    private var daysLookup: Map<LocalDate, StatisticsDailyProgress> = emptyMap()

    private var averageText: String = "—"
    private var averageProgress: Double? = null
    private var bestDayPct: String = "—"
    private var bestDayDate: String = ""

    private var bestDayProgress: Double? = null

    private var surfaceColor: Int = 0
    private var onSurfaceColor: Int = 0
    private var onSurfaceVariantColor: Int = 0
    private var accentColor: Int = 0
    private var dividerColor: Int = 0

    private val dateFormatter = JavaLocalDateFormatter(Locale.getDefault())

    // Paints preallocated
    private val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        fontFeatureSettings = "tnum"
    }
    private val ringTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        fontFeatureSettings = "tnum"
    }
    private val sideTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val sideDatePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val legendLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        fontFeatureSettings = "tnum"
    }
    private val tooltipBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val tooltipStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val tooltipTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        fontFeatureSettings = "tnum"
    }

    private val cellRect = RectF()
    private val tooltipRect = RectF()
    private val ringRect = RectF()

    // Touch inspection state
    private var inspectedDate: LocalDate? = null
    private var inspectedCellRect: RectF? = null

    fun setData(
        monthStart: LocalDate,
        monthEnd: LocalDate,
        firstWeekday: DayOfWeek,
        days: List<StatisticsDailyProgress>,
        averageProgress: Double?,
        surfaceColor: Int,
        onSurfaceColor: Int,
        onSurfaceVariantColor: Int,
        accentColor: Int,
        dividerColor: Int
    ) {
        this.monthStart = monthStart
        this.monthEnd = monthEnd
        this.firstWeekday = firstWeekday
        this.daysLookup = days.associateBy { it.date }
        this.surfaceColor = surfaceColor
        this.onSurfaceColor = onSurfaceColor
        this.onSurfaceVariantColor = onSurfaceVariantColor
        this.accentColor = accentColor
        this.dividerColor = dividerColor

        this.averageText = averageProgress?.let { "${(it * 100).roundToInt()}%" } ?: "—"
        this.averageProgress = averageProgress
        val bestDay = days.filter { !it.isFuture && it.progress != null }.maxByOrNull { it.progress!! }
        if (bestDay != null && (bestDay.progress ?: 0.0) > 0.0) {
            this.bestDayProgress = bestDay.progress
            this.bestDayPct = "${((bestDay.progress ?: 0.0) * 100).roundToInt()}%"
            this.bestDayDate = "${bestDay.date.day} ${dateFormatter.shortMonthName(bestDay.date)}"
        } else {
            this.bestDayProgress = null
            this.bestDayPct = "—"
            this.bestDayDate = ""
        }

        headerPaint.color = onSurfaceVariantColor
        headerPaint.textSize = sp(9.5f)
        textPaint.textSize = sp(8.5f)
        ringTextPaint.color = onSurfaceColor
        ringTextPaint.textSize = sp(12f)

        sideTitlePaint.color = ColorUtils.setAlphaComponent(onSurfaceVariantColor, 180)
        sideTitlePaint.textSize = sp(10f)

        sideDatePaint.color = ColorUtils.setAlphaComponent(onSurfaceVariantColor, 140)
        sideDatePaint.textSize = sp(8.5f)

        legendLabelPaint.color = ColorUtils.setAlphaComponent(onSurfaceVariantColor, 180)
        legendLabelPaint.textSize = sp(9f)

        tooltipBgPaint.color = ColorUtils.blendARGB(surfaceColor, onSurfaceColor, 0.18f)
        tooltipStrokePaint.color = ColorUtils.setAlphaComponent(dividerColor, 120)
        tooltipTextPaint.color = onSurfaceColor
        tooltipTextPaint.textSize = sp(11f)
        tooltipTextPaint.typeface = Typeface.DEFAULT_BOLD

        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val mStart = monthStart
        val mEnd = monthEnd
        if (mStart == null || mEnd == null || width == 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }

        val cellSize = dp(19f)
        val spacing = dp(2.5f)
        val headerHeight = dp(18f)

        val firstDowOffset = (mStart.dayOfWeek.ordinal - firstWeekday.ordinal + 7) % 7
        val totalDays = mStart.daysUntil(mEnd) + 1
        val numWeeks = ((firstDowOffset + totalDays + 6) / 7)

        // Extra space for centered intensity legend below grid
        val totalH = paddingTop + paddingBottom + headerHeight + (numWeeks * (cellSize + spacing)) + dp(32f)
        setMeasuredDimension(width, totalH.roundToInt())
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val mStart = monthStart ?: return super.onTouchEvent(event)
        val mEnd = monthEnd ?: return super.onTouchEvent(event)

        val cellSize = dp(19f)
        val spacing = dp(2.5f)
        val headerHeight = dp(18f)
        val totalGridWidth = 7f * cellSize + 6f * spacing
        val startX = (width - totalGridWidth) / 2f
        val startY = paddingTop.toFloat() + headerHeight

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val touchX = event.x
                val touchY = event.y

                val col = ((touchX - startX) / (cellSize + spacing)).toInt()
                val row = ((touchY - startY) / (cellSize + spacing)).toInt()

                if (col in 0..6 && row >= 0) {
                    val firstDowOffset = (mStart.dayOfWeek.ordinal - firstWeekday.ordinal + 7) % 7
                    val dayIndex = row * 7 + col - firstDowOffset
                    if (dayIndex in 0 until (mStart.daysUntil(mEnd) + 1)) {
                        val date = mStart.plus(dayIndex)
                        if (inspectedDate != date) {
                            inspectedDate = date
                            performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                        }
                        val left = startX + col * (cellSize + spacing)
                        val top = startY + row * (cellSize + spacing)
                        inspectedCellRect = RectF(left, top, left + cellSize, top + cellSize)
                        parent.requestDisallowInterceptTouchEvent(true)
                        invalidate()
                        return true
                    }
                }
                if (event.actionMasked == MotionEvent.ACTION_MOVE && inspectedDate != null) {
                    // dragged outside
                    inspectedDate = null
                    inspectedCellRect = null
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (inspectedDate != null) {
                    inspectedDate = null
                    inspectedCellRect = null
                    parent.requestDisallowInterceptTouchEvent(false)
                    invalidate()
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val mStart = monthStart ?: return
        val mEnd = monthEnd ?: return

        val cellSize = dp(19f)
        val spacing = dp(2.5f)
        val cornerRadius = dp(2.5f)
        val headerHeight = dp(18f)

        val totalGridWidth = 7f * cellSize + 6f * spacing
        val startX = (width - totalGridWidth) / 2f
        var startY = paddingTop.toFloat()

        val ringRadius = dp(20f)
        val ringCenterY = startY + headerHeight + dp(28f)

        // 1. LEFT SIDE: Mini progress ring for Mean
        if (startX > dp(50f)) {
            val leftCx = startX / 2f
            ringRect.set(leftCx - ringRadius, ringCenterY - ringRadius, leftCx + ringRadius, ringCenterY + ringRadius)
            strokePaint.strokeWidth = dp(3f)
            strokePaint.color = dividerColor
            canvas.drawArc(ringRect, -90f, 360f, false, strokePaint)
            averageProgress?.let {
                strokePaint.color = accentColor
                canvas.drawArc(ringRect, -90f, (it.coerceIn(0.0, 1.0) * 360).toFloat(), false, strokePaint)
            }
            canvas.drawText(averageText, leftCx,
                ringCenterY - (ringTextPaint.ascent() + ringTextPaint.descent()) / 2f, ringTextPaint)
            canvas.drawText(context.getString(org.isoron.uhabits.R.string.statistics_month_average),
                leftCx, ringCenterY + ringRadius + dp(14f), sideTitlePaint)
        }

        // 2. RIGHT SIDE: Symmetric matching mini progress ring for Best Day
        val rightStartX = startX + totalGridWidth
        val rightAreaWidth = width - rightStartX
        if (rightAreaWidth > dp(50f)) {
            val rightCx = rightStartX + rightAreaWidth / 2f
            ringRect.set(rightCx - ringRadius, ringCenterY - ringRadius, rightCx + ringRadius, ringCenterY + ringRadius)
            strokePaint.strokeWidth = dp(3f)
            strokePaint.color = dividerColor
            canvas.drawArc(ringRect, -90f, 360f, false, strokePaint)
            bestDayProgress?.let {
                strokePaint.color = accentColor
                canvas.drawArc(ringRect, -90f, (it.coerceIn(0.0, 1.0) * 360).toFloat(), false, strokePaint)
            }
            canvas.drawText(bestDayPct, rightCx,
                ringCenterY - (ringTextPaint.ascent() + ringTextPaint.descent()) / 2f, ringTextPaint)
            canvas.drawText(context.getString(org.isoron.uhabits.R.string.statistics_month_best_short),
                rightCx, ringCenterY + ringRadius + dp(14f), sideTitlePaint)
            if (bestDayDate.isNotEmpty()) {
                canvas.drawText(bestDayDate, rightCx, ringCenterY + ringRadius + dp(25f), sideDatePaint)
            }
        }

        // 3. CENTER: Weekday Headers
        val headerY = startY + headerHeight * 0.65f
        for (col in 0..6) {
            val dow = DayOfWeek.entries[(firstWeekday.ordinal + col) % 7]
            val cx = startX + col * (cellSize + spacing) + cellSize / 2f
            canvas.drawText(dateFormatter.shortWeekdayName(dow).take(2), cx, headerY, headerPaint)
        }

        startY += headerHeight

        // 4. CENTER: Month Days
        val firstDowOffset = (mStart.dayOfWeek.ordinal - firstWeekday.ordinal + 7) % 7
        var current = mStart
        var dayIndex = 0

        val emptyPastCellColor = ColorUtils.blendARGB(surfaceColor, onSurfaceVariantColor, 0.12f)
        val skippedCellColor = ColorUtils.blendARGB(surfaceColor, onSurfaceVariantColor, 0.22f)

        while (current <= mEnd) {
            val slot = firstDowOffset + dayIndex
            val col = slot % 7
            val row = slot / 7

            val left = startX + col * (cellSize + spacing)
            val top = startY + row * (cellSize + spacing)
            cellRect.set(left, top, left + cellSize, top + cellSize)

            val daily = daysLookup[current]
            val isFuture = daily?.isFuture == true
            val isSkipped = daily?.isSkipped == true
            val isToday = daily?.isToday == true
            val progress = daily?.progress

            var hasFill = false
            var fill = surfaceColor

            when {
                isFuture -> {
                    // Future: empty
                }
                isSkipped -> {
                    fill = skippedCellColor
                    hasFill = true
                }
                progress != null && progress > 0.0 -> {
                    val p = progress.coerceIn(0.0, 1.0)
                    val alpha = when {
                        p >= 1.0 -> 245
                        p >= 0.67 -> 185
                        p >= 0.34 -> 125
                        else -> 70
                    }
                    fill = ColorUtils.setAlphaComponent(accentColor, alpha)
                    hasFill = true
                }
                progress != null && progress <= 0.0 -> {
                    fill = emptyPastCellColor
                    hasFill = true
                }
            }

            if (hasFill) {
                cellPaint.color = fill
                canvas.drawRoundRect(cellRect, cornerRadius, cornerRadius, cellPaint)
            }

            // Outline for today
            if (isToday) {
                strokePaint.color = accentColor
                strokePaint.strokeWidth = dp(1.5f)
                canvas.drawRoundRect(cellRect, cornerRadius, cornerRadius, strokePaint)
            }

            // Subtle highlight for hovered/inspected day
            if (current == inspectedDate) {
                strokePaint.color = onSurfaceColor
                strokePaint.strokeWidth = dp(2f)
                canvas.drawRoundRect(cellRect, cornerRadius, cornerRadius, strokePaint)
            }

            // Day number
            val textColor = when {
                isFuture -> ColorUtils.setAlphaComponent(onSurfaceVariantColor, 80)
                hasFill && (progress ?: 0.0) >= 0.67 -> {
                    val opaqueSurface = ColorUtils.setAlphaComponent(surfaceColor, 255)
                    val opaqueOnSurface = ColorUtils.setAlphaComponent(onSurfaceColor, 255)
                    val opaqueFill = ColorUtils.compositeColors(fill, opaqueSurface)
                    val contrastDark = ColorUtils.calculateContrast(opaqueSurface, opaqueFill)
                    val contrastLight = ColorUtils.calculateContrast(opaqueOnSurface, opaqueFill)
                    if (contrastDark >= contrastLight) surfaceColor else onSurfaceColor
                }
                else -> onSurfaceVariantColor
            }

            textPaint.color = textColor
            val textY = top + cellSize / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(current.day.toString(), left + cellSize / 2f, textY, textPaint)

            current = current.plus(1)
            dayIndex++
        }

        // 5. CENTERED BOTTOM INTENSITY LEGEND
        val totalDays = mStart.daysUntil(mEnd) + 1
        val numWeeks = (firstDowOffset + totalDays + 6) / 7
        val gridBottom = startY + numWeeks * (cellSize + spacing)
        val legendBoxY = gridBottom + dp(10f)

        val legendCell = dp(9f)
        val legendGap = dp(4f)
        val legendWidth = 5 * legendCell + 4 * legendGap
        val legendStartX = (width - legendWidth) / 2f
        val discreteAlphas = intArrayOf(245, 185, 125, 70, 0)

        for (level in 0..4) {
            cellPaint.color = if (level == 4) emptyPastCellColor else
                ColorUtils.setAlphaComponent(accentColor, discreteAlphas[level])
            val boxX = legendStartX + level * (legendCell + legendGap)
            cellRect.set(boxX, legendBoxY, boxX + legendCell, legendBoxY + legendCell)
            canvas.drawRoundRect(cellRect, dp(1.5f), dp(1.5f), cellPaint)
        }

        val textBaseline = legendBoxY + legendCell / 2f - (legendLabelPaint.descent() + legendLabelPaint.ascent()) / 2f
        legendLabelPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("100%", legendStartX - dp(6f), textBaseline, legendLabelPaint)
        legendLabelPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("0%", legendStartX + legendWidth + dp(6f), textBaseline, legendLabelPaint)

        // 6. TOOLTIP OVERLAY (positioned safely above touching finger)
        inspectedDate?.let { date ->
            val daily = daysLookup[date]
            val dateLabel = "${date.day} ${dateFormatter.shortMonthName(date)}"
            val progress = daily?.progress
            val valText = when {
                daily == null || daily.isFuture || progress == null -> context.getString(org.isoron.uhabits.R.string.statistics_no_data)
                daily.isSkipped -> context.getString(org.isoron.uhabits.R.string.statistics_skipped_label)
                else -> "${(progress * 100).roundToInt()}%"
            }
            val tooltipStr = "$dateLabel · $valText"

            val textW = tooltipTextPaint.measureText(tooltipStr)
            val padH = dp(8f)
            val tipW = textW + padH * 2
            val tipH = dp(24f)

            val cellR = inspectedCellRect ?: return@let
            var tipX = cellR.centerX() - tipW / 2f
            tipX = tipX.coerceIn(dp(8f), width - tipW - dp(8f))

            // Offset 56dp above cell so finger does not cover tooltip
            var tipY = cellR.top - dp(56f)
            if (tipY < dp(4f)) {
                tipY = cellR.bottom + dp(20f)
            }

            tooltipRect.set(tipX, tipY, tipX + tipW, tipY + tipH)
            canvas.drawRoundRect(tooltipRect, dp(6f), dp(6f), tooltipBgPaint)
            canvas.drawRoundRect(tooltipRect, dp(6f), dp(6f), tooltipStrokePaint)

            val textY = tooltipRect.centerY() - (tooltipTextPaint.descent() + tooltipTextPaint.ascent()) / 2f
            canvas.drawText(tooltipStr, tooltipRect.centerX(), textY, tooltipTextPaint)
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
    private fun sp(value: Float): Float = value * resources.displayMetrics.scaledDensity
}
