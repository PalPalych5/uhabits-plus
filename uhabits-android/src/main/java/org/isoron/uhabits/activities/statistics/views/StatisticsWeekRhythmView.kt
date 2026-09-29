package org.isoron.uhabits.activities.statistics.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.ColorUtils
import org.isoron.platform.time.DayOfWeek
import kotlin.math.roundToInt

/**
 * Compact 7-column bar chart adhering strictly to Loop Habit Tracker's BarChart design language.
 * Used for Week "By day" and Month/Year "By weekday".
 */
class StatisticsWeekRhythmView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class RhythmItem(
        val dayOfWeek: DayOfWeek,
        val progress: Double?,
        val label: String,
        val isToday: Boolean = false,
        val isFuture: Boolean = false
    )

    private var items: List<RhythmItem> = emptyList()
    private var accentColor: Int = 0
    private var onSurfaceVariantColor: Int = 0
    private var dividerColor: Int = 0

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(10f)
        textAlign = Paint.Align.CENTER
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(10f)
        textAlign = Paint.Align.CENTER
        fontFeatureSettings = "tnum"
    }

    private val rect = RectF()

    fun setData(
        data: List<RhythmItem>,
        accentColor: Int,
        onSurfaceVariant: Int,
        divider: Int
    ) {
        this.items = data
        this.accentColor = accentColor
        this.onSurfaceVariantColor = onSurfaceVariant
        this.dividerColor = divider

        barPaint.color = accentColor
        labelPaint.color = onSurfaceVariant
        valuePaint.color = onSurfaceVariant

        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val desiredHeight = dp(100f).roundToInt()
        setMeasuredDimension(width, desiredHeight)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (items.isEmpty()) return

        val w = width.toFloat()
        val h = height.toFloat()

        val columnWidth = w / 7f
        val barWidth = dp(10f)
        val baselineY = h - dp(24f)
        val paddingTop = dp(18f)
        val maxBarHeight = baselineY - paddingTop

        // Seven fixed columns use Loop BarChart's narrow bars and quiet labels.
        items.forEachIndexed { i, item ->
            val cx = i * columnWidth + columnWidth / 2f

            // Weekday label below baseline
            if (item.isToday) {
                labelPaint.color = accentColor
                labelPaint.typeface = Typeface.DEFAULT_BOLD
                canvas.drawText(item.label, cx, baselineY + dp(14f), labelPaint)
            } else if (item.isFuture) {
                labelPaint.color = ColorUtils.setAlphaComponent(onSurfaceVariantColor, 100)
                labelPaint.typeface = Typeface.DEFAULT
                canvas.drawText(item.label, cx, baselineY + dp(14f), labelPaint)
            } else {
                labelPaint.color = onSurfaceVariantColor
                labelPaint.typeface = Typeface.DEFAULT
                canvas.drawText(item.label, cx, baselineY + dp(14f), labelPaint)
            }

            // Data bar & percentage — no background track, matching Loop BarChart
            if (!item.isFuture && item.progress != null) {
                val progress = item.progress.coerceIn(0.0, 1.0).toFloat()
                val barHeight = progress * maxBarHeight
                val cornerR = barWidth * 0.15f

                if (barHeight > 0f) {
                    val actualHeight = barHeight.coerceAtLeast(dp(2f))
                    rect.set(cx - barWidth / 2f, baselineY - actualHeight, cx + barWidth / 2f, baselineY)
                    canvas.drawRoundRect(rect, cornerR, cornerR, barPaint)
                }
                // A measured 0% has a label; null/no-data and future days do not.
                val valueText = "${(progress * 100).roundToInt()}%"
                valuePaint.color = onSurfaceVariantColor
                canvas.drawText(valueText, cx, baselineY - barHeight.coerceAtLeast(dp(2f)) - dp(3f), valuePaint)
            }
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
    private fun sp(value: Float): Float = value * resources.displayMetrics.scaledDensity
}
