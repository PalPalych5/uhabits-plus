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

package org.isoron.uhabits.activities.statistics

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.ColorUtils
import org.isoron.platform.time.DayOfWeek
import org.isoron.platform.time.JavaLocalDateFormatter
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.getFirstWeekdayNumberAccordingToLocale
import org.isoron.platform.time.getToday
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.activities.common.theme.MainTabsThemeBridge
import org.isoron.uhabits.activities.settings.SettingsThemePalette
import org.isoron.uhabits.core.ui.screens.statistics.StatisticsCalendarDay
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Read-only, aggregate activity calendar for the Statistics screen.
 *
 * The view intentionally measures to the full width of its date range. Place
 * it inside a [android.widget.HorizontalScrollView] instead of shrinking the
 * cells when the range does not fit on screen.
 */
class StatisticsCalendarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var days: Map<LocalDate, StatisticsCalendarDay> = emptyMap()
        set(value) {
            field = value.toMap()
            invalidate()
        }

    var startDate: LocalDate = getToday()
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    var endDate: LocalDate = getToday()
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    private val dateFormatter = JavaLocalDateFormatter(Locale.getDefault())
    private val cellBounds = RectF()
    private val cellClipPath = Path()

    private val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val hatchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(HATCH_STROKE_DP)
    }
    private val dayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = sp(DAY_TEXT_SP)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        fontFeatureSettings = "tnum"
    }
    private val weekdayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.RIGHT
        textSize = sp(LABEL_TEXT_SP)
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val monthPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        textSize = sp(MONTH_TEXT_SP)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    init {
        isClickable = false
        isFocusable = false
    }

    fun setData(
        days: Map<LocalDate, StatisticsCalendarDay>,
        start: LocalDate,
        end: LocalDate
    ) {
        this.days = days.toMap()
        startDate = start
        endDate = end
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val geometry = geometry(firstWeekday())
        val desiredWidth = max(suggestedMinimumWidth, geometry.contentWidth.roundToInt())
        val desiredHeight = max(suggestedMinimumHeight, geometry.contentHeight.roundToInt())
        setMeasuredDimension(
            resolveSize(desiredWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val palette = MainTabsThemeBridge.resolve(context)
        canvas.drawColor(palette.surface)

        val firstWeekday = firstWeekday()
        val geometry = geometry(firstWeekday)
        if (geometry.weekCount <= 0) return

        drawWeekdayLabels(canvas, geometry, firstWeekday, palette)
        drawMonthHeaders(canvas, geometry, palette)
        drawCalendarDays(canvas, geometry, palette)
    }

    private fun drawWeekdayLabels(
        canvas: Canvas,
        geometry: Geometry,
        firstWeekday: DayOfWeek,
        palette: SettingsThemePalette
    ) {
        weekdayPaint.color = palette.onSurfaceVariant
        repeat(DAYS_PER_WEEK) { row ->
            val weekday = DayOfWeek.entries[
                (firstWeekday.daysSinceSunday + row) % DAYS_PER_WEEK
            ]
            val centerY = geometry.gridTop + row * geometry.stride + geometry.cellSize / 2f
            canvas.drawText(
                dateFormatter.shortWeekdayName(weekday),
                geometry.gridLeft - dp(LABEL_GAP_DP),
                centeredTextBaseline(centerY, weekdayPaint),
                weekdayPaint
            )
        }
    }

    private fun drawMonthHeaders(
        canvas: Canvas,
        geometry: Geometry,
        palette: SettingsThemePalette
    ) {
        monthPaint.color = palette.onSurfaceVariant
        var lastMonthKey: Int? = null

        repeat(geometry.weekCount) { column ->
            val weekStart = geometry.gridStart.plus(column * DAYS_PER_WEEK)
            val firstVisibleDate = (0 until DAYS_PER_WEEK)
                .asSequence()
                .map(weekStart::plus)
                .firstOrNull(::isInsideRange)
                ?: return@repeat
            val monthBoundary = (0 until DAYS_PER_WEEK)
                .asSequence()
                .map(weekStart::plus)
                .firstOrNull { it.day == 1 && isInsideRange(it) }
            val labelDate = monthBoundary ?: if (column == 0) firstVisibleDate else null
            if (labelDate != null) {
                val monthKey = labelDate.year * MONTHS_PER_YEAR + labelDate.month
                if (monthKey != lastMonthKey) {
                    val includeYear = column == 0 || labelDate.month == 1
                    val month = dateFormatter.shortMonthName(labelDate)
                    val label = if (includeYear) "$month ${labelDate.year}" else month
                    val x = geometry.gridLeft + column * geometry.stride
                    canvas.drawText(
                        label,
                        x,
                        centeredTextBaseline(
                            paddingTop + geometry.headerHeight / 2f,
                            monthPaint
                        ),
                        monthPaint
                    )
                    lastMonthKey = monthKey
                }
            }
        }
    }

    private fun drawCalendarDays(
        canvas: Canvas,
        geometry: Geometry,
        palette: SettingsThemePalette
    ) {
        val today = getToday()
        repeat(geometry.weekCount) { column ->
            for (row in 0 until DAYS_PER_WEEK) {
                val date = geometry.gridStart.plus(column * DAYS_PER_WEEK + row)
                if (!isInsideRange(date) || date.isNewerThan(today)) continue

                val state = days[date] ?: continue
                val progress = state.progress?.takeIf(Double::isFinite)
                if (progress == null && !state.skipped) continue

                val left = geometry.gridLeft + column * geometry.stride
                val top = geometry.gridTop + row * geometry.stride
                cellBounds.set(left, top, left + geometry.cellSize, top + geometry.cellSize)

                if (state.skipped) {
                    drawSkippedCell(canvas, geometry.cellRadius, palette)
                } else {
                    drawProgressCell(canvas, progress ?: 0.0, geometry.cellRadius, palette)
                }
                drawDayNumber(canvas, date, palette)
            }
        }
    }

    private fun drawProgressCell(
        canvas: Canvas,
        progress: Double,
        radius: Float,
        palette: SettingsThemePalette
    ) {
        val intensity = progressIntensity(progress, palette.isPureBlack)
        cellPaint.color = ColorUtils.blendARGB(palette.surface, palette.accent, intensity)
        canvas.drawRoundRect(cellBounds, radius, radius, cellPaint)
    }

    private fun drawSkippedCell(
        canvas: Canvas,
        radius: Float,
        palette: SettingsThemePalette
    ) {
        val fillIntensity = if (palette.isPureBlack) 0.28f else 0.20f
        cellPaint.color = ColorUtils.blendARGB(palette.surface, palette.accent, fillIntensity)
        canvas.drawRoundRect(cellBounds, radius, radius, cellPaint)

        cellClipPath.reset()
        cellClipPath.addRoundRect(cellBounds, radius, radius, Path.Direction.CW)
        val saveCount = canvas.save()
        canvas.clipPath(cellClipPath)
        hatchPaint.color = palette.surface
        hatchPaint.strokeWidth = dp(HATCH_STROKE_DP)

        val hatchStep = dp(HATCH_STEP_DP)
        var x = cellBounds.left - cellBounds.height()
        while (x < cellBounds.right) {
            canvas.drawLine(
                x,
                cellBounds.bottom,
                x + cellBounds.height(),
                cellBounds.top,
                hatchPaint
            )
            x += hatchStep
        }
        canvas.restoreToCount(saveCount)
    }

    private fun drawDayNumber(
        canvas: Canvas,
        date: LocalDate,
        palette: SettingsThemePalette
    ) {
        val fillColor = cellPaint.color
        val opaqueFillColor = ColorUtils.setAlphaComponent(fillColor, 255)
        val onSurfaceContrast = ColorUtils.calculateContrast(palette.onSurface, opaqueFillColor)
        val surfaceContrast = ColorUtils.calculateContrast(palette.surface, opaqueFillColor)
        dayPaint.color = if (onSurfaceContrast >= surfaceContrast) {
            palette.onSurface
        } else {
            palette.surface
        }
        canvas.drawText(
            date.day.toString(),
            cellBounds.centerX(),
            centeredTextBaseline(cellBounds.centerY(), dayPaint),
            dayPaint
        )
    }

    private fun geometry(firstWeekday: DayOfWeek): Geometry {
        val cellSize = dp(CELL_SIZE_DP)
        val stride = cellSize + dp(CELL_GAP_DP)
        val headerHeight = dp(HEADER_HEIGHT_DP)
        val labelWidth = DayOfWeek.entries.maxOf { weekday ->
            weekdayPaint.measureText(dateFormatter.shortWeekdayName(weekday)).toDouble()
        }.toFloat()
        val gridLeft = paddingLeft + labelWidth + dp(LABEL_GAP_DP)
        val gridTop = paddingTop + headerHeight
        val gridStart = startDate.startOfWeek(firstWeekday)
        val dayCount = if (endDate.isOlderThan(startDate)) {
            0
        } else {
            gridStart.daysUntil(endDate) + 1
        }
        val weekCount = if (dayCount <= 0) {
            0
        } else {
            ceil(dayCount / DAYS_PER_WEEK.toDouble()).toInt()
        }
        val gridWidth = if (weekCount == 0) 0f else weekCount * stride - dp(CELL_GAP_DP)
        val gridHeight = DAYS_PER_WEEK * stride - dp(CELL_GAP_DP)

        return Geometry(
            gridStart = gridStart,
            weekCount = weekCount,
            cellSize = cellSize,
            cellRadius = dp(CELL_RADIUS_DP),
            stride = stride,
            headerHeight = headerHeight,
            gridLeft = gridLeft,
            gridTop = gridTop,
            contentWidth = gridLeft + gridWidth + paddingRight,
            contentHeight = gridTop + gridHeight + paddingBottom
        )
    }

    private fun firstWeekday(): DayOfWeek {
        val app = context.applicationContext as? HabitsApplication
        return app?.component?.preferences?.firstWeekday
            ?: DayOfWeek.entries[getFirstWeekdayNumberAccordingToLocale() - 1]
    }

    private fun isInsideRange(date: LocalDate): Boolean =
        !date.isOlderThan(startDate) && !date.isNewerThan(endDate)

    private fun progressIntensity(progress: Double, isPureBlack: Boolean): Float {
        val value = progress.coerceIn(0.0, 1.0)
        val intensity = when {
            value <= 0.0 -> 0.12f
            value <= 0.25 -> 0.28f
            value <= 0.50 -> 0.48f
            value <= 0.75 -> 0.68f
            value < 1.0 -> 0.84f
            else -> 1.0f
        }
        return if (isPureBlack) max(AMOLED_MIN_INTENSITY, intensity) else intensity
    }

    private fun centeredTextBaseline(centerY: Float, paint: Paint): Float =
        centerY - (paint.ascent() + paint.descent()) / 2f

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun sp(value: Float): Float = value * resources.displayMetrics.scaledDensity

    private data class Geometry(
        val gridStart: LocalDate,
        val weekCount: Int,
        val cellSize: Float,
        val cellRadius: Float,
        val stride: Float,
        val headerHeight: Float,
        val gridLeft: Float,
        val gridTop: Float,
        val contentWidth: Float,
        val contentHeight: Float
    )

    private companion object {
        const val DAYS_PER_WEEK = 7
        const val MONTHS_PER_YEAR = 12
        const val CELL_SIZE_DP = 30f
        const val CELL_GAP_DP = 4f
        const val CELL_RADIUS_DP = 6f
        const val HEADER_HEIGHT_DP = 28f
        const val LABEL_GAP_DP = 8f
        const val DAY_TEXT_SP = 11f
        const val LABEL_TEXT_SP = 11f
        const val MONTH_TEXT_SP = 12f
        const val HATCH_STEP_DP = 6f
        const val HATCH_STROKE_DP = 1.1f
        const val AMOLED_MIN_INTENSITY = 0.20f
    }
}
