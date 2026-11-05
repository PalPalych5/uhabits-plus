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
    private var valShader: Shader? = null
    private var satShader: Shader? = null
    private val density get() = resources.displayMetrics.density
    private val indicatorRadius get() = 9f * density
    private val indicatorStrokeWidth get() = 2f * density
    private val fieldBounds = RectF()

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x26000000 // delicate soft shadow (~15% black)
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
    }

    private val outerContourPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x47000000 // semi-transparent dark contour (~28% black)
    }

    var onColorChangedListener: ((s: Float, v: Float) -> Unit)? = null

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)

        val desiredRatio = 1.7f // width / height

        val w = if (widthMode != MeasureSpec.UNSPECIFIED && widthSize > 0) {
            widthSize
        } else {
            (300f * resources.displayMetrics.density).toInt()
        }
        val targetHeight = (w / desiredRatio).toInt()
        val h = when (heightMode) {
            MeasureSpec.EXACTLY -> heightSize
            MeasureSpec.AT_MOST -> minOf(targetHeight, heightSize)
            else -> targetHeight
        }
        setMeasuredDimension(w, h)
    }

    fun setHsv(h: Float, s: Float, v: Float) {
        this.hue = h.coerceIn(0f, 360f)
        this.saturation = s.coerceIn(0f, 1f)
        this.value = v.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val inset = 14f * density
        fieldBounds.set(inset, inset, w - inset, h - inset)
        if (fieldBounds.width() <= 0f || fieldBounds.height() <= 0f) return
        valShader = LinearGradient(0f, fieldBounds.top, 0f, fieldBounds.bottom, Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP)
        satShader = LinearGradient(fieldBounds.left, 0f, fieldBounds.right, 0f, Color.WHITE, Color.TRANSPARENT, Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        if (w <= 0 || h <= 0 || fieldBounds.width() <= 0f || fieldBounds.height() <= 0f) return

        // 1. Draw solid Hue color
        val color = Color.HSVToColor(floatArrayOf(hue, 1f, 1f))
        paint.shader = null
        paint.color = color
        
        val radius = 14f * resources.displayMetrics.density
        val rect = RectF(0f, 0f, w, h)
        canvas.drawRoundRect(rect, radius, radius, paint)

        // 2. Draw Saturation gradient (white -> transparent)
        paint.shader = satShader
        canvas.drawRoundRect(rect, radius, radius, paint)

        // 3. Draw Value gradient (transparent -> black)
        paint.shader = valShader
        canvas.drawRoundRect(rect, radius, radius, paint)

        // 4. Draw cursor/indicator with unified dual-contour marker style
        val cx = fieldBounds.left + saturation * fieldBounds.width()
        val cy = fieldBounds.top + (1f - value) * fieldBounds.height()

        val currentColor = Color.HSVToColor(floatArrayOf(hue, saturation, value))
        val r = indicatorRadius

        fillPaint.color = currentColor
        strokePaint.strokeWidth = indicatorStrokeWidth
        outerContourPaint.strokeWidth = 1f * density

        // A. Delicate soft drop shadow (offset 1dp down, slightly expanded)
        canvas.drawCircle(cx, cy + 1f * density, r + 1.25f * density, shadowPaint)

        // B. Inner color fill (matching active color)
        canvas.drawCircle(cx, cy, r, fillPaint)

        // C. Crisp white contrast stroke (~2 dp)
        canvas.drawCircle(cx, cy, r, strokePaint)

        // D. Semi-transparent dark outer contour (~1 dp)
        canvas.drawCircle(cx, cy, r + 1.25f * density, outerContourPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (fieldBounds.width() <= 0f || fieldBounds.height() <= 0f) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                saturation = ((event.x - fieldBounds.left) / fieldBounds.width()).coerceIn(0f, 1f)
                value = (1f - (event.y - fieldBounds.top) / fieldBounds.height()).coerceIn(0f, 1f)
                onColorChangedListener?.invoke(saturation, value)
                invalidate()
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    parent?.requestDisallowInterceptTouchEvent(false)
                    performClick()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> { parent?.requestDisallowInterceptTouchEvent(false); return true }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
