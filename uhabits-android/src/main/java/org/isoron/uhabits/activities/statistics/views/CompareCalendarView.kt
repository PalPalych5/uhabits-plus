package org.isoron.uhabits.activities.statistics.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.ColorUtils
import org.isoron.platform.time.DayOfWeek
import org.isoron.platform.time.JavaLocalDateFormatter
import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Habit
import java.util.Locale
import kotlin.math.roundToInt

/** A shared time grid for one comparison series. Every instance receives the same range. */
class CompareCalendarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val formatter = JavaLocalDateFormatter(Locale.getDefault())
    private var habit: Habit? = null
    private var from = LocalDate(2000, 1, 1)
    private var to = from
    private var today = from
    private var firstWeekday = DayOfWeek.MONDAY
    private var monthLayout = false
    private var color = 0
    private var emptyColor = 0
    private var textColor = 0
    private val cell get() = dp(if (monthLayout) 19f else 12f)
    private val gap get() = dp(3f)
    private val leftInset get() = if (monthLayout) 0f else dp(24f)
    private val topInset get() = dp(20f)

    fun setData(
        habit: Habit,
        from: LocalDate,
        to: LocalDate,
        today: LocalDate,
        firstWeekday: DayOfWeek,
        monthLayout: Boolean,
        color: Int,
        emptyColor: Int,
        textColor: Int
    ) {
        this.habit = habit
        this.from = from
        this.to = to
        this.today = today
        this.firstWeekday = firstWeekday
        this.monthLayout = monthLayout
        this.color = color
        this.emptyColor = emptyColor
        this.textColor = textColor
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val offset = (from.dayOfWeek.ordinal - firstWeekday.ordinal + 7) % 7
        val days = (from.daysUntil(to) + 1).coerceAtLeast(0)
        val columns = if (monthLayout) 7 else (offset + days + 6) / 7
        val rows = if (monthLayout) (offset + days + 6) / 7 else 7
        val width = leftInset + columns * (cell + gap) + dp(8f)
        val height = topInset + rows * (cell + gap) + dp(6f)
        setMeasuredDimension(width.roundToInt(), height.roundToInt())
    }

    override fun onDraw(canvas: Canvas) {
        val currentHabit = habit ?: return
        val statsStart = currentHabit.effectiveStatisticsStartDate()
        val offset = (from.dayOfWeek.ordinal - firstWeekday.ordinal + 7) % 7
        labelPaint.color = textColor
        labelPaint.textSize = dp(9f)
        for (weekday in 0..6) {
            val day = DayOfWeek.entries[(firstWeekday.ordinal + weekday) % 7]
            if (monthLayout) {
                canvas.drawText(formatter.shortWeekdayName(day).take(2), weekday * (cell + gap) + cell / 2f,
                    dp(12f), labelPaint)
            } else {
                canvas.drawText(formatter.shortWeekdayName(day).take(2), dp(10f),
                    topInset + weekday * (cell + gap) + cell * 0.8f, labelPaint)
            }
        }
        var date = from
        var index = 0
        while (date <= to) {
            val slot = offset + index
            val column = if (monthLayout) slot % 7 else slot / 7
            val row = if (monthLayout) slot / 7 else slot % 7
            if (date <= today && (statsStart == null || date >= statsStart)) {
                val value = currentHabit.computedEntries.get(date).value
                paint.color = when {
                    value == Entry.UNKNOWN -> emptyColor
                    value == Entry.SKIP -> ColorUtils.setAlphaComponent(emptyColor, 160)
                    value == Entry.YES_MANUAL || value == Entry.YES_AUTO ||
                        (currentHabit.isNumerical && value > 0) -> color
                    else -> ColorUtils.setAlphaComponent(color, 60)
                }
                val x = leftInset + column * (cell + gap)
                val y = topInset + row * (cell + gap)
                canvas.drawRoundRect(x, y, x + cell, y + cell, dp(2f), dp(2f), paint)
            }
            date = date.plus(1)
            index++
        }
    }

    private fun dp(value: Float) = value * resources.displayMetrics.density
}
