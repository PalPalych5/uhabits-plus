package org.isoron.uhabits.activities.statistics.views

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.max
import kotlin.math.min

class StatisticsOverviewRectView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class TierData(
        val progress: Float,
        val label: String,
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

    private var tiers: List<TierData> = emptyList()
    private var displayProgressFraction = 1f
    private var progressAnimator: ValueAnimator? = null

    private val rectPath = Path()
    private val drawPath = Path()
    private val pathMeasure = PathMeasure()
    private val rect = RectF()

    var cornerRadius: Float = dp(16f)
        set(value) {
            field = value
            invalidate()
        }

    var strokeWidth: Float = dp(4f)
        set(value) {
            field = value
            trackPaint.strokeWidth = value
            progressPaint.strokeWidth = value
            invalidate()
        }

    var gap: Float = dp(6f)
        set(value) {
            field = value
            invalidate()
        }

    init {
        trackPaint.strokeWidth = strokeWidth
        progressPaint.strokeWidth = strokeWidth
    }

    fun setData(data: List<TierData>, displayAtZero: Boolean = false) {
        cancelProgressAnimation(jumpToEnd = false)
        tiers = data.take(4)
        displayProgressFraction = if (displayAtZero) 0f else 1f
        updateAccessibilityDescription()
        invalidate()
    }

    fun animateProgress(duration: Long) {
        cancelProgressAnimation(jumpToEnd = false)
        if (tiers.isEmpty()) {
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
        if (tiers.isEmpty()) return

        val w = width.toFloat()
        val h = height.toFloat()
        val padding = strokeWidth / 2f + dp(2f) // safety offset

        tiers.forEachIndexed { index, tier ->
            val offset = padding + index * (strokeWidth + gap)
            rect.set(offset, offset, w - offset, h - offset)
            val rx = max(dp(2f), cornerRadius - index * (strokeWidth + gap))

            rectPath.reset()
            // Draw starting from top center sweeping clockwise
            val cx = rect.centerX()
            rectPath.moveTo(cx, rect.top)
            rectPath.lineTo(rect.right - rx, rect.top)
            rectPath.arcTo(rect.right - rx * 2, rect.top, rect.right, rect.top + rx * 2, -90f, 90f, false)
            rectPath.lineTo(rect.right, rect.bottom - rx)
            rectPath.arcTo(rect.right - rx * 2, rect.bottom - rx * 2, rect.right, rect.bottom, 0f, 90f, false)
            rectPath.lineTo(rect.left + rx, rect.bottom)
            rectPath.arcTo(rect.left, rect.bottom - rx * 2, rect.left + rx * 2, rect.bottom, 90f, 90f, false)
            rectPath.lineTo(rect.left, rect.top + rx)
            rectPath.arcTo(rect.left, rect.top, rect.left + rx * 2, rect.top + rx * 2, 180f, 90f, false)
            rectPath.close()

            trackPaint.color = tier.trackColor
            canvas.drawPath(rectPath, trackPaint)

            val targetProgress = tier.progress.coerceIn(0f, 1f)
            if (targetProgress > 0f) {
                progressPaint.color = tier.color
                pathMeasure.setPath(rectPath, false)
                val length = pathMeasure.length
                drawPath.reset()
                pathMeasure.getSegment(0f, targetProgress * displayProgressFraction * length, drawPath, true)
                canvas.drawPath(drawPath, progressPaint)
            }
        }
    }

    private fun updateAccessibilityDescription() {
        if (tiers.isEmpty()) {
            contentDescription = null
            return
        }
        val builder = StringBuilder()
        tiers.forEach { tier ->
            val percentage = (tier.progress * 100).toInt().coerceIn(0, 100)
            builder.append("${tier.label}: $percentage%. ")
        }
        contentDescription = builder.toString().trim()
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
