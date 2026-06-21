package org.isoron.uhabits.activities.common.dialogs

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
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
        val swatchRadius = min(width, height) / 2f - 5f * density

        fillPaint.color = swatchColor
        canvas.drawCircle(cx, cy, swatchRadius, fillPaint)
        canvas.drawCircle(cx, cy, swatchRadius, outlinePaint)

        if (isSelected && showSelectionRing) {
            ringPaint.color = swatchColor
            canvas.drawCircle(cx, cy, swatchRadius + 3f * density, ringPaint)
        }

        if (isSelected && showCheckmark) {
            checkPaint.color = ColorPickerUtils.onColor(swatchColor)
            checkPath.reset()
            checkPath.moveTo(cx - 5.5f * density, cy)
            checkPath.lineTo(cx - 1.3f * density, cy + 4f * density)
            checkPath.lineTo(cx + 6.2f * density, cy - 5f * density)
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
