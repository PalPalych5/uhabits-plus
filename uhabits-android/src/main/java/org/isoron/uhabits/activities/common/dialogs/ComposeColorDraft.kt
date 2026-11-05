/* Copyright (C) 2026 Loop Habit Tracker contributors. GPL-3.0-or-later. */
package org.isoron.uhabits.activities.common.dialogs

import android.graphics.Color
import androidx.compose.runtime.saveable.listSaver

/** One color draft, including the hue to use when saturation or value is zero. */
data class ComposeColorDraft(
    val argb: Int,
    val hue: Float,
    val saturation: Float,
    val value: Float,
) {
    companion object {
        fun fromArgb(color: Int): ComposeColorDraft {
            val hsv = FloatArray(3)
            Color.colorToHSV(color, hsv)
            return ComposeColorDraft(color, hsv[0], hsv[1], hsv[2])
        }

        val Saver = listSaver<ComposeColorDraft, Any>(
            save = { listOf(it.argb, it.hue, it.saturation, it.value) },
            restore = { ComposeColorDraft(it[0] as Int, it[1] as Float, it[2] as Float, it[3] as Float) }
        )
    }

    /** Keep the exact input and retain the previous hue for gray or black. */
    fun withArgb(color: Int): ComposeColorDraft {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        val nextHue = if (hsv[1] == 0f || hsv[2] == 0f) hue else hsv[0]
        return ComposeColorDraft(color, nextHue, hsv[1], hsv[2])
    }

    /** Update HSV without discarding the color's existing alpha channel. */
    fun withHsv(h: Float, s: Float, v: Float): ComposeColorDraft {
        val nextHue = h.coerceIn(0f, 360f)
        val nextSaturation = s.coerceIn(0f, 1f)
        val nextValue = v.coerceIn(0f, 1f)
        val color = Color.HSVToColor(Color.alpha(argb), floatArrayOf(nextHue, nextSaturation, nextValue))
        return ComposeColorDraft(color, nextHue, nextSaturation, nextValue)
    }
}
