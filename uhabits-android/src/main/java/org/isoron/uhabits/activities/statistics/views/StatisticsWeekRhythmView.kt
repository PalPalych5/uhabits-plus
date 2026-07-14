package org.isoron.uhabits.activities.statistics.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import org.isoron.platform.time.DayOfWeek
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.common.theme.MainTabsThemeBridge
import kotlin.math.roundToInt

class StatisticsWeekRhythmView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    data class RhythmItem(
        val dayOfWeek: DayOfWeek,
        val progress: Double,
        val label: String
    )

    private val chartView: View
    private val insightView: TextView

    private var items: List<RhythmItem> = emptyList()
    private var accentColor: Int = 0
    private var onSurfaceVariantColor: Int = 0
    private var dividerColor: Int = 0

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
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

    init {
        orientation = VERTICAL
        setPadding(dp(12f).toInt(), dp(12f).toInt(), dp(12f).toInt(), dp(12f).toInt())

        chartView = object : View(context) {
            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                drawChart(canvas)
            }
        }.apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(120f).toInt())
        }

        insightView = TextView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(16f).toInt()
            }
            textSize = 13f
            textAlignment = TEXT_ALIGNMENT_CENTER
        }

        addView(chartView)
        addView(insightView)
    }

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
        trackPaint.color = divider
        labelPaint.color = onSurfaceVariant
        valuePaint.color = onSurfaceVariant

        chartView.invalidate()
        generateInsight()
    }

    private fun drawChart(canvas: Canvas) {
        if (items.isEmpty()) return

        val w = chartView.width.toFloat()
        val h = chartView.height.toFloat()

        val columnWidth = w / 7
        val barWidth = dp(16f)
        val maxBarHeight = h - dp(32f) // space for label and value

        items.forEachIndexed { i, item ->
            val cx = i * columnWidth + columnWidth / 2
            val progress = item.progress.coerceIn(0.0, 1.0).toFloat()
            val barHeight = progress * maxBarHeight

            // Draw track
            rect.set(cx - barWidth / 2, dp(16f), cx + barWidth / 2, dp(16f) + maxBarHeight)
            canvas.drawRoundRect(rect, barWidth / 2, barWidth / 2, trackPaint)

            // Draw filled bar
            if (barHeight > 0f) {
                rect.set(cx - barWidth / 2, dp(16f) + maxBarHeight - barHeight, cx + barWidth / 2, dp(16f) + maxBarHeight)
                canvas.drawRoundRect(rect, barWidth / 2, barWidth / 2, barPaint)
            }

            // Draw value text
            val valueText = "${(progress * 100).roundToInt()}%"
            canvas.drawText(valueText, cx, dp(12f), valuePaint)

            // Draw label
            canvas.drawText(item.label, cx, h - dp(4f), labelPaint)
        }
    }

    private fun generateInsight() {
        if (items.isEmpty()) {
            insightView.visibility = GONE
            return
        }

        val validItems = items.filter { it.progress >= 0.0 }
        if (validItems.isEmpty()) {
            insightView.visibility = GONE
            return
        }

        val average = validItems.map { it.progress }.average()
        val weakest = validItems.minByOrNull { it.progress }

        if (weakest != null && weakest.progress < average - 0.05) {
            val diffPercent = ((average - weakest.progress) * 100).roundToInt()
            insightView.visibility = VISIBLE
            insightView.setTextColor(onSurfaceVariantColor)
            val resourceString = context.getString(R.string.statistics_rhythm_insight)
            insightView.text = String.format(resourceString, weakest.label, diffPercent)
        } else {
            insightView.visibility = GONE
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
    private fun sp(value: Float): Float = value * resources.displayMetrics.scaledDensity
}
