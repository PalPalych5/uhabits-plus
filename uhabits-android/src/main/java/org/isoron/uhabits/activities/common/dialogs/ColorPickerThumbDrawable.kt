package org.isoron.uhabits.activities.common.dialogs

import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import kotlin.math.roundToInt

/**
 * Custom thumb drawable providing a unified dual-contour marker style for color picker sliders.
 *
 * Visual hierarchy:
 * 1. Delicate soft drop shadow (offset 1 dp down, ~15% opacity).
 * 2. Active color fill (disk with [radiusDp]).
 * 3. Crisp white inner stroke (~2 dp).
 * 4. Semi-transparent dark outer contour (~1 dp, ~28% opacity).
 *
 * Guarantees high-contrast visibility against pure white, pure black, saturated yellow,
 * and all theme backgrounds.
 */
class ColorPickerThumbDrawable(
    private val density: Float,
    private val radiusDp: Float = 9f,
    private val state: ThumbState = ThumbState(density = density, radiusDp = radiusDp)
) : Drawable() {

    init {
        state.listeners.add(this)
    }

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x26000000 // delicate soft shadow (~15% black)
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = state.color
    }

    private val whiteStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = Color.WHITE
    }

    private val outerContourPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = 0x47000000 // semi-transparent dark contour (~28% black)
    }

    fun setColor(newColor: Int) {
        state.updateColor(newColor)
    }

    fun getColor(): Int = state.color

    override fun getIntrinsicWidth(): Int = (2f * radiusDp * density).roundToInt()
    override fun getIntrinsicHeight(): Int = (2f * radiusDp * density).roundToInt()

    override fun draw(canvas: Canvas) {
        val cx = bounds.exactCenterX()
        val cy = bounds.exactCenterY()
        val r = radiusDp * density

        // 1. Delicate soft drop shadow (offset 1dp down, slightly expanded)
        canvas.drawCircle(cx, cy + 1f * density, r + 1.25f * density, shadowPaint)

        // 2. Inner color fill (matching active color)
        fillPaint.color = state.color
        canvas.drawCircle(cx, cy, r, fillPaint)

        // 3. Crisp white inner stroke (~2 dp)
        canvas.drawCircle(cx, cy, r, whiteStrokePaint)

        // 4. Semi-transparent dark outer contour (~1 dp)
        canvas.drawCircle(cx, cy, r + 1.25f * density, outerContourPaint)
    }

    override fun setAlpha(alpha: Int) {
        fillPaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        // Strokes remain un-tinted to maintain high contrast borders
    }

    override fun setTint(tintColor: Int) {
        state.updateColor(tintColor)
    }

    override fun setTintList(tint: ColorStateList?) {
        tint?.defaultColor?.let { state.updateColor(it) }
    }

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun getConstantState(): ConstantState = state

    override fun mutate(): Drawable = this

    class ThumbState(
        var color: Int = Color.WHITE,
        val density: Float,
        val radiusDp: Float = 9f
    ) : ConstantState() {
        val listeners = mutableListOf<ColorPickerThumbDrawable>()

        override fun newDrawable(): Drawable {
            return ColorPickerThumbDrawable(density, radiusDp, this)
        }

        override fun getChangingConfigurations(): Int = 0

        fun updateColor(newColor: Int) {
            if (color != newColor) {
                color = newColor
                val iterator = listeners.iterator()
                while (iterator.hasNext()) {
                    val listener = iterator.next()
                    listener.fillPaint.color = newColor
                    listener.invalidateSelf()
                }
            }
        }
    }
}
