package org.isoron.uhabits.activities.statistics

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.DecelerateInterpolator
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
    private var displayProgressFraction = 1f
    private var progressAnimator: ValueAnimator? = null

    fun setRings(data: List<RingData>, displayAtZero: Boolean = false) {
        cancelProgressAnimation(jumpToEnd = false)
        rings = data.take(4)
        displayProgressFraction = if (displayAtZero) 0f else 1f
        invalidate()
    }

    fun animateProgress(duration: Long) {
        cancelProgressAnimation(jumpToEnd = false)
        if (rings.isEmpty()) {
            displayProgressFraction = 1f
            invalidate()
            return
        }
        progressAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                displayProgressFraction = animator.animatedValue as Float
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    displayProgressFraction = 1f
                    progressAnimator = null
                    invalidate()
                }

                override fun onAnimationCancel(animation: android.animation.Animator) {
                    progressAnimator = null
                }
            })
            start()
        }
    }

    fun cancelProgressAnimation(jumpToEnd: Boolean) {
        progressAnimator?.cancel()
        progressAnimator = null
        if (jumpToEnd) {
            displayProgressFraction = 1f
            invalidate()
        }
    }

    override fun onDetachedFromWindow() {
        cancelProgressAnimation(jumpToEnd = true)
        super.onDetachedFromWindow()
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
                ring.progress.coerceIn(0f, 1f) * displayProgressFraction * 360f,
                false,
                progressPaint
            )
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
