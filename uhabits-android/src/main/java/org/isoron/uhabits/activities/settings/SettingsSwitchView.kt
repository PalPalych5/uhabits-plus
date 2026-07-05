package org.isoron.uhabits.activities.settings

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.PathInterpolator
import android.widget.Checkable
import kotlin.math.abs
import kotlin.math.max

class SettingsSwitchView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr), Checkable {

    interface OnCheckedChangeListener {
        fun onCheckedChanged(view: SettingsSwitchView, checked: Boolean)
    }

    private val density = resources.displayMetrics.density
    private val motionInterpolator: TimeInterpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
    private val desiredTrackWidthDp = 42f
    private val desiredTrackHeightDp = 24f
    private val innerPadding = 3f * density

    private val trackRect = RectF()
    private val thumbRect = RectF()

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private var checkedState = false
    private var animationTarget = false
    private var animator: ValueAnimator? = null
    private var listener: OnCheckedChangeListener? = null

    private var thumbProgress = 0f
    private var baseTrackProgress = 0f
    private var thumbScale = 1f
    private var transientWaveVisible = false

    private var startTouchX = 0f
    private var startTouchY = 0f
    private var movedOutsideTap = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var trackRadius = 0f
    private var thumbRadius = 0f

    private var accentColor = Color.rgb(76, 175, 80)
    private var isDarkTheme = false
    private var isPureBlackTheme = false

    private var inactiveTrackColor = Color.rgb(228, 231, 236)
    private var activeTrackColor = Color.rgb(198, 167, 173)
    private var inactiveThumbColor = Color.WHITE
    private var activeThumbColor = Color.rgb(216, 74, 86)

    init {
        background = null
        foreground = null
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        minimumWidth = dp(desiredTrackWidthDp)
        minimumHeight = dp(desiredTrackHeightDp)
        refreshPalette()
    }

    fun configure(
        accentColor: Int,
        isDark: Boolean,
        isPureBlack: Boolean
    ) {
        if (
            this.accentColor == accentColor &&
            isDarkTheme == isDark &&
            isPureBlackTheme == isPureBlack
        ) {
            return
        }
        this.accentColor = accentColor
        isDarkTheme = isDark
        isPureBlackTheme = isPureBlack
        refreshPalette()
        invalidate()
    }

    fun bindChecked(checked: Boolean) {
        if (animator?.isRunning == true && animationTarget == checked) return

        animator?.cancel()
        animator = null
        checkedState = checked
        animationTarget = checked
        thumbProgress = if (checked) 1f else 0f
        baseTrackProgress = if (checked) 1f else 0f
        thumbScale = 1f
        transientWaveVisible = false
        refreshDrawableState()
        invalidate()
    }

    fun setCheckedAnimated(checked: Boolean) {
        setCheckedInternal(checked, animate = true, notify = true)
    }

    fun setOnCheckedChangeListener(listener: OnCheckedChangeListener?) {
        this.listener = listener
    }

    override fun isChecked(): Boolean = checkedState

    override fun setChecked(checked: Boolean) {
        setCheckedInternal(checked, animate = false, notify = false)
    }

    override fun toggle() {
        setCheckedInternal(!checkedState, animate = true, notify = true)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredWidth = dp(desiredTrackWidthDp) + paddingLeft + paddingRight
        val desiredHeight = dp(desiredTrackHeightDp) + paddingTop + paddingBottom
        setMeasuredDimension(
            resolveSize(desiredWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec)
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val availableWidth = (w - paddingLeft - paddingRight).toFloat()
        val availableHeight = (h - paddingTop - paddingBottom).toFloat()
        val trackWidth = minOf(availableWidth, dp(desiredTrackWidthDp).toFloat())
        val trackHeight = minOf(availableHeight, dp(desiredTrackHeightDp).toFloat())
        val left = paddingLeft + (availableWidth - trackWidth) / 2f
        val top = paddingTop + (availableHeight - trackHeight) / 2f

        trackRect.set(left, top, left + trackWidth, top + trackHeight)
        trackRadius = trackRect.height() / 2f
        thumbRadius = max(0f, trackRadius - innerPadding)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (trackRect.isEmpty) return

        val thumbCenterX = currentThumbCenterX()
        val centerY = trackRect.centerY()
        val resolvedThumbRadius = thumbRadius * thumbScale

        trackPaint.color = blendArgb(inactiveTrackColor, activeTrackColor, baseTrackProgress)
        canvas.drawRoundRect(trackRect, trackRadius, trackRadius, trackPaint)

        thumbRect.set(
            thumbCenterX - resolvedThumbRadius,
            centerY - resolvedThumbRadius,
            thumbCenterX + resolvedThumbRadius,
            centerY + resolvedThumbRadius
        )
        thumbPaint.color = blendArgb(inactiveThumbColor, activeThumbColor, baseTrackProgress)
        canvas.drawOval(thumbRect, thumbPaint)

    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startTouchX = event.x
                startTouchY = event.y
                movedOutsideTap = false
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (abs(event.x - startTouchX) > touchSlop || abs(event.y - startTouchY) > touchSlop) {
                    movedOutsideTap = true
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!movedOutsideTap) performClick()
                return true
            }

            MotionEvent.ACTION_CANCEL -> return true
        }

        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        toggle()
        return true
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        thumbScale = 1f
        transientWaveVisible = false
        super.onDetachedFromWindow()
    }

    override fun onCreateDrawableState(extraSpace: Int): IntArray {
        val drawableState = super.onCreateDrawableState(extraSpace + 1)
        if (checkedState) {
            mergeDrawableStates(drawableState, CHECKED_STATE_SET)
        }
        return drawableState
    }

    override fun getAccessibilityClassName(): CharSequence = "android.widget.Switch"

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = accessibilityClassName
        info.isCheckable = true
        info.isChecked = checkedState
    }

    override fun onInitializeAccessibilityEvent(event: AccessibilityEvent) {
        super.onInitializeAccessibilityEvent(event)
        event.className = accessibilityClassName
        event.isChecked = checkedState
    }

    private fun setCheckedInternal(
        checked: Boolean,
        animate: Boolean,
        notify: Boolean
    ) {
        if (checked == animationTarget && animator?.isRunning == true) return
        if (checkedState == checked && animationTarget == checked && !animate) return
        if (checkedState == checked && animationTarget == checked && animator == null) return

        checkedState = checked
        animationTarget = checked
        refreshDrawableState()

        if (notify) {
            sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED)
        }

        if (!animate || !canAnimate()) {
            animator?.cancel()
            animator = null
            thumbProgress = if (checked) 1f else 0f
            baseTrackProgress = if (checked) 1f else 0f
            thumbScale = 1f
            transientWaveVisible = false
            invalidate()
            if (notify) listener?.onCheckedChanged(this, checked)
            return
        }

        val startThumb = thumbProgress
        val startBaseTrack = baseTrackProgress
        val startScale = thumbScale

        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (checked) 1500L else 1200L
            interpolator = motionInterpolator
            addUpdateListener { valueAnimator ->
                val t = valueAnimator.animatedValue as Float
                transientWaveVisible = false
                if (checked) {
                    thumbProgress = lerp(startThumb, 1f, easedPhase(t, 0.12f, 0.68f))
                    baseTrackProgress = lerp(startBaseTrack, 1f, easedPhase(t, 0.42f, 0.88f))
                    thumbScale = when {
                        t < 0.18f -> lerp(startScale, 0.90f, easedPhase(t, 0f, 0.18f))
                        t < 0.74f -> 0.90f
                        else -> lerp(0.90f, 1f, easedPhase(t, 0.74f, 1f))
                    }
                } else {
                    thumbProgress = lerp(startThumb, 0f, easedPhase(t, 0.10f, 0.74f))
                    baseTrackProgress = when {
                        t < 0.52f -> lerp(startBaseTrack, 0.35f, easedPhase(t, 0f, 0.52f))
                        else -> lerp(0.35f, 0f, easedPhase(t, 0.52f, 1f))
                    }
                    thumbScale = when {
                        t < 0.16f -> lerp(startScale, 0.90f, easedPhase(t, 0f, 0.16f))
                        t < 0.76f -> 0.90f
                        else -> lerp(0.90f, 1f, easedPhase(t, 0.76f, 1f))
                    }
                }
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: Animator) {
                    if (animator === animation) {
                        animator = null
                    }
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (animator === animation) {
                        thumbProgress = if (checked) 1f else 0f
                        baseTrackProgress = if (checked) 1f else 0f
                        thumbScale = 1f
                        transientWaveVisible = false
                        animator = null
                        invalidate()
                    }
                }
            })
            start()
        }

        if (notify) listener?.onCheckedChanged(this, checked)
    }

    private fun currentThumbCenterX(): Float {
        return lerp(thumbStartX(), thumbEndX(), thumbProgress)
    }

    private fun thumbStartX(): Float {
        return trackRect.left + innerPadding + thumbRadius
    }

    private fun thumbEndX(): Float {
        return trackRect.right - innerPadding - thumbRadius
    }

    private fun refreshPalette() {
        val inactiveBase = when {
            isPureBlackTheme -> Color.rgb(32, 33, 38)
            isDarkTheme -> Color.rgb(48, 50, 56)
            else -> Color.rgb(228, 231, 236)
        }
        val activeMix = when {
            isPureBlackTheme -> 0.50f
            isDarkTheme -> 0.50f
            else -> 0.40f
        }

        inactiveTrackColor = inactiveBase
        activeTrackColor = blendArgb(inactiveBase, accentColor, activeMix)
        inactiveThumbColor = when {
            isPureBlackTheme -> Color.rgb(233, 235, 240)
            isDarkTheme -> Color.rgb(233, 235, 240)
            else -> Color.WHITE
        }
        activeThumbColor = accentColor
    }

    private fun canAnimate(): Boolean {
        return isAttachedToWindow &&
            width > 0 &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled())
    }

    private fun easedPhase(
        time: Float,
        start: Float,
        end: Float
    ): Float {
        return motionInterpolator.getInterpolation(phase(time, start, end))
    }

    private fun phase(
        time: Float,
        start: Float,
        end: Float
    ): Float {
        if (time <= start) return 0f
        if (time >= end) return 1f
        return ((time - start) / (end - start)).coerceIn(0f, 1f)
    }

    private fun lerp(
        start: Float,
        end: Float,
        amount: Float
    ): Float {
        return start + (end - start) * amount.coerceIn(0f, 1f)
    }

    private fun dp(value: Float): Int {
        return (value * density + 0.5f).toInt()
    }

    private fun blendArgb(from: Int, to: Int, amount: Float): Int {
        val t = amount.coerceIn(0f, 1f)
        val inverse = 1f - t
        return Color.argb(
            (Color.alpha(from) * inverse + Color.alpha(to) * t).toInt(),
            (Color.red(from) * inverse + Color.red(to) * t).toInt(),
            (Color.green(from) * inverse + Color.green(to) * t).toInt(),
            (Color.blue(from) * inverse + Color.blue(to) * t).toInt()
        )
    }

    companion object {
        private val CHECKED_STATE_SET = intArrayOf(android.R.attr.state_checked)
    }
}
