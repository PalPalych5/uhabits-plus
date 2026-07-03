package org.isoron.uhabits.activities.statistics

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.min

class StatisticsOverviewRingView(context: Context) : View(context) {
    data class RingData(
        val progress: Float,
        val color: Int,
        val trackColor: Int
    )

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val ringBounds = RectF()
    private var rings: List<RingData> = emptyList()

    fun setRings(data: List<RingData>) {
        rings = data.take(4)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (rings.isEmpty()) return

        val size = min(width, height).toFloat()
        val baseStroke = dp(4f)
        val gap = dp(5f)

        rings.forEachIndexed { index, ring ->
            val inset = index * (baseStroke + gap) + baseStroke / 2f
            ringBounds.set(inset, inset, size - inset, size - inset)
            trackPaint.color = ring.trackColor
            trackPaint.strokeWidth = baseStroke
            progressPaint.color = ring.color
            progressPaint.strokeWidth = baseStroke
            canvas.drawArc(ringBounds, -90f, 360f, false, trackPaint)
            canvas.drawArc(
                ringBounds,
                -90f,
                ring.progress.coerceIn(0f, 1f) * 360f,
                false,
                progressPaint
            )
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
