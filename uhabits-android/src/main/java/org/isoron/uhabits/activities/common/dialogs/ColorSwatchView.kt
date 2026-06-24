package org.isoron.uhabits.activities.common.dialogs

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.min

class ColorSwatchView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var swatchColor: Int = Color.GRAY
        set(value) {
            field = value
            invalidate()
        }

    var showSelectionRing: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    var showCheckmark: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    var showSpectrum: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
        color = 0x26000000
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val checkPath = Path()

    init {
        isClickable = true
        isFocusable = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val reservedSpace = when {
            showSelectionRing -> 4f
            showSpectrum -> 4f
            else -> 0.5f
        }
        val swatchRadius = min(width, height) / 2f - reservedSpace * density

        fillPaint.shader = if (showSpectrum) {
            SweepGradient(
                cx,
                cy,
                intArrayOf(
                    0xFFE46C73.toInt(),
                    0xFFE2B950.toInt(),
                    0xFF63B67A.toInt(),
                    0xFF4CA9BD.toInt(),
                    0xFF617FC2.toInt(),
                    0xFF9A6DB1.toInt(),
                    0xFFE46C73.toInt()
                ),
                null
            )
        } else {
            null
        }
        fillPaint.color = swatchColor
        canvas.drawCircle(cx, cy, swatchRadius, fillPaint)
        fillPaint.shader = null
        canvas.drawCircle(cx, cy, swatchRadius, outlinePaint)

        if (isSelected && showSelectionRing) {
            ringPaint.color = swatchColor
            canvas.drawCircle(cx, cy, swatchRadius + 3f * density, ringPaint)
        }

        if (isSelected && showCheckmark) {
            checkPaint.color = ColorPickerUtils.onColor(swatchColor)
            checkPath.reset()
            checkPath.moveTo(cx - 5.5f * density, cy - 0.3f * density)
            checkPath.lineTo(cx - 1.3f * density, cy + 3.7f * density)
            checkPath.lineTo(cx + 6.2f * density, cy - 4.7f * density)
            canvas.drawPath(checkPath, checkPaint)
        }
    }

    override fun setSelected(selected: Boolean) {
        if (selected == isSelected) return
        super.setSelected(selected)
        refreshDrawableState()
        invalidate()
        sendAccessibilityEvent(AccessibilityNodeInfo.ACTION_SELECT)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.RadioButton"
        info.isCheckable = true
        info.isChecked = isSelected
    }
}
