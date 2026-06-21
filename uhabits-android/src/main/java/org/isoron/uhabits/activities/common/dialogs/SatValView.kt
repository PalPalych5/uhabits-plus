package org.isoron.uhabits.activities.common.dialogs

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class SatValView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var hue: Float = 0f
    private var saturation: Float = 1f
    private var value: Float = 1f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val indicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * resources.displayMetrics.density
    }

    private var valShader: Shader? = null
    private var satShader: Shader? = null

    var onColorChangedListener: ((s: Float, v: Float) -> Unit)? = null

    fun setHsv(h: Float, s: Float, v: Float) {
        this.hue = h
        this.saturation = s
        this.value = v
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        valShader = LinearGradient(0f, 0f, 0f, h.toFloat(), Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP)
        satShader = LinearGradient(0f, 0f, w.toFloat(), 0f, Color.WHITE, Color.TRANSPARENT, Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        if (w <= 0 || h <= 0) return

        // 1. Draw solid Hue color
        val color = Color.HSVToColor(floatArrayOf(hue, 1f, 1f))
        paint.shader = null
        paint.color = color
        
        val radius = 8f * resources.displayMetrics.density
        val rect = RectF(0f, 0f, w, h)
        canvas.drawRoundRect(rect, radius, radius, paint)

        // 2. Draw Saturation gradient (white -> transparent)
        paint.shader = satShader
        canvas.drawRoundRect(rect, radius, radius, paint)

        // 3. Draw Value gradient (transparent -> black)
        paint.shader = valShader
        canvas.drawRoundRect(rect, radius, radius, paint)

        // 4. Draw cursor/indicator
        val cx = saturation * w
        val cy = (1f - value) * h

        indicatorPaint.color = if (value > 0.6f && saturation < 0.4f) Color.BLACK else Color.WHITE
        canvas.drawCircle(cx, cy, 8f * resources.displayMetrics.density, indicatorPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent.requestDisallowInterceptTouchEvent(true)
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val x = event.x.coerceIn(0f, width.toFloat())
                val y = event.y.coerceIn(0f, height.toFloat())
                saturation = x / width
                value = 1f - (y / height)
                onColorChangedListener?.invoke(saturation, value)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
