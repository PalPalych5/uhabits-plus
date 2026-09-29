package org.isoron.uhabits.activities.statistics.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import org.isoron.uhabits.core.ui.screens.statistics.StatisticsBucket
import kotlin.math.roundToInt

/** Fixed Loop BarChart geometry for aggregate percentage buckets. Null is empty, not zero. */
class StatisticsBucketChartView(context: Context) : View(context) {
    private var buckets: List<StatisticsBucket> = emptyList()
    private var labels: List<String> = emptyList()
    private var accent = 0
    private var muted = 0
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = resources.displayMetrics.scaledDensity * 10f
    }
    private val rect = RectF()
    private fun dp(value: Float) = value * resources.displayMetrics.density

    fun setData(data: List<StatisticsBucket>, axisLabels: List<String>, color: Int, labelColor: Int) {
        buckets = data
        labels = axisLabels
        accent = color
        muted = labelColor
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(104f).roundToInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (buckets.isEmpty()) return
        val count = buckets.size
        val column = width.toFloat() / count
        val barWidth = minOf(dp(12f), column * 0.42f)
        val bottom = height - dp(22f)
        val maxBarHeight = dp(64f)
        buckets.forEachIndexed { index, bucket ->
            val x = column * (index + 0.5f)
            text.color = muted
            canvas.drawText(labels.getOrElse(index) { "" }, x, height - dp(6f), text)
            val progress = bucket.progress ?: return@forEachIndexed
            val fraction = progress.coerceIn(0.0, 1.0).toFloat()
            val barHeight = maxBarHeight * fraction
            if (barHeight > 0f) {
                val top = bottom - barHeight.coerceAtLeast(dp(2f))
                // Loop BarChart: width 12, corner radius 15% of width.
                rect.set(x - barWidth / 2f, top, x + barWidth / 2f, bottom)
                bar.color = accent
                canvas.drawRoundRect(rect, barWidth * 0.15f, barWidth * 0.15f, bar)
            }
            text.color = accent
            canvas.drawText("${(fraction * 100).roundToInt()}%", x,
                bottom - barHeight.coerceAtLeast(dp(2f)) - dp(5f), text)
        }
    }
}
