package org.isoron.uhabits.activities.settings

import android.animation.ArgbEvaluator
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.animation.PathInterpolator
import androidx.appcompat.widget.SwitchCompat
import androidx.core.graphics.ColorUtils
import org.isoron.uhabits.core.preferences.Preferences
import kotlin.math.PI
import kotlin.math.sin

class SettingsSwitchView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SwitchCompat(context, attrs) {
    companion object {
        private const val ANIMATION_DURATION_MS = 420L
        private const val THUMB_PHASE_END = 0.65f
    }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val trackRect = RectF()
    private val evaluator = ArgbEvaluator()
    private var thumbProgress = if (isChecked) 1f else 0f
    private var colorProgress = if (isChecked) 1f else 0f
    private var thumbScale = 1f
    private var animator: ValueAnimator? = null
    private var animatingTarget: Boolean? = null
    private var palette: SettingsThemePalette? = null
    private var accentColor = 0

    init {
        minWidth = dp(52f).toInt()
        minimumHeight = dp(40f).toInt()
        showText = false
        splitTrack = false
        thumbDrawable = null
        trackDrawable = null
        background = null
        foreground = null
        stateListAnimator = null
    }

    fun applySettingsPalette(prefs: Preferences) {
        palette = SettingsThemePaletteResolver.resolve(context, prefs)
        accentColor = AccentColorManager.getAccentColor(context, prefs)
        invalidate()
    }

    fun setCheckedSilently(checked: Boolean) {
        if (animatingTarget == checked) {
            super.setChecked(checked)
            return
        }
        animator?.cancel()
        animator = null
        animatingTarget = null
        super.setChecked(checked)
        thumbProgress = if (checked) 1f else 0f
        colorProgress = thumbProgress
        thumbScale = 1f
        invalidate()
    }

    override fun setChecked(checked: Boolean) {
        if (checked == isChecked && animatingTarget == null) {
            super.setChecked(checked)
            return
        }
        if (animatingTarget == checked) {
            super.setChecked(checked)
            return
        }
        val startThumbProgress = thumbProgress
        val startColorProgress = colorProgress
        val startThumbScale = thumbScale
        animatingTarget = checked
        super.setChecked(checked)
        thumbProgress = startThumbProgress
        colorProgress = startColorProgress
        thumbScale = startThumbScale
        animateProgress(checked, startThumbProgress, startColorProgress, startThumbScale)
    }

    override fun jumpDrawablesToCurrentState() {
        super.jumpDrawablesToCurrentState()
        if (animatingTarget != null) return

        animator?.cancel()
        animator = null
        thumbProgress = if (isChecked) 1f else 0f
        colorProgress = thumbProgress
        thumbScale = 1f
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(dp(52f).toInt(), widthMeasureSpec),
            resolveSize(dp(40f).toInt(), heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        val palette = palette ?: SettingsThemePaletteResolver.resolve(
            context,
            (context.applicationContext as org.isoron.uhabits.HabitsApplication).component.preferences
        )
        if (accentColor == 0) {
            accentColor = AccentColorManager.getAccentColor(
                context,
                (context.applicationContext as org.isoron.uhabits.HabitsApplication).component.preferences
            )
        }

        val trackWidth = dp(44f)
        val trackHeight = dp(26f)
        val left = (width - trackWidth) / 2f
        val top = (height - trackHeight) / 2f
        trackRect.set(left, top, left + trackWidth, top + trackHeight)
        val radius = trackHeight / 2f

        val inactiveTrack = when {
            palette.isPureBlack -> palette.surfaceVariant
            palette.isDark -> ColorUtils.blendARGB(palette.surfaceVariant, palette.onSurface, 0.10f)
            else -> ColorUtils.blendARGB(palette.surfaceVariant, palette.onSurface, 0.08f)
        }
        val activeTrack = ColorUtils.setAlphaComponent(
            accentColor,
            when {
                palette.isPureBlack -> 92
                palette.isDark -> 82
                else -> 64
            }
        )
        val inactiveThumb = when {
            palette.isDark -> ColorUtils.blendARGB(palette.surfaceVariant, palette.onSurface, 0.18f)
            else -> 0xFFFFFFFF.toInt()
        }
        val activeThumb = accentColor
        val strokeColor = when {
            palette.isPureBlack -> 0x33FFFFFF
            palette.isDark -> 0x24FFFFFF
            else -> 0x24000000
        }

        trackPaint.color = evaluator.evaluate(colorProgress, inactiveTrack, activeTrack) as Int
        canvas.drawRoundRect(trackRect, radius, radius, trackPaint)

        strokePaint.color = strokeColor
        canvas.drawRoundRect(trackRect, radius, radius, strokePaint)

        val thumbRadius = minOf(dp(10f), trackHeight / 2f - dp(2f))
        val thumbStart = trackRect.left + radius
        val thumbEnd = trackRect.right - radius
        val thumbCx = (thumbStart + (thumbEnd - thumbStart) * thumbProgress)
            .coerceIn(thumbStart, thumbEnd)
        val thumbColor = evaluator.evaluate(colorProgress, inactiveThumb, activeThumb) as Int
        thumbPaint.color = thumbColor
        canvas.drawCircle(thumbCx, trackRect.centerY(), thumbRadius * thumbScale, thumbPaint)
    }

    private fun animateProgress(
        targetChecked: Boolean,
        startThumbProgress: Float,
        startColorProgress: Float,
        startThumbScale: Float
    ) {
        val target = if (targetChecked) 1f else 0f
        animator?.cancel()
        if (!isAttachedToWindow || !ValueAnimator.areAnimatorsEnabled()) {
            thumbProgress = target
            colorProgress = target
            thumbScale = 1f
            animatingTarget = null
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ANIMATION_DURATION_MS
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener {
                val phase = it.animatedValue as Float
                val thumbAmount = phaseProgress(phase, 0f, THUMB_PHASE_END)
                thumbProgress = lerp(startThumbProgress, target, thumbAmount)
                colorProgress = lerp(startColorProgress, target, phase)
                thumbScale = lerp(startThumbScale, computeThumbScale(phase), thumbAmount)
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (animator === animation) {
                        thumbProgress = target
                        colorProgress = target
                        thumbScale = 1f
                        animator = null
                        animatingTarget = null
                        invalidate()
                    }
                }

                override fun onAnimationCancel(animation: Animator) {
                    if (animator === animation) {
                        animator = null
                    }
                }
            })
            start()
        }
    }

    private fun phaseProgress(
        phase: Float,
        start: Float,
        end: Float
    ): Float {
        if (end <= start) return if (phase >= end) 1f else 0f
        return ((phase - start) / (end - start)).coerceIn(0f, 1f)
    }

    private fun computeThumbScale(phase: Float): Float {
        val compression = phaseProgress(phase, 0.10f, 0.75f)
        return 1f - (0.06f * sin(compression * PI).toFloat())
    }

    private fun lerp(start: Float, end: Float, amount: Float): Float {
        return start + (end - start) * amount
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
