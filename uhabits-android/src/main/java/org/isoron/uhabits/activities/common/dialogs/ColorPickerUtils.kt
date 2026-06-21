package org.isoron.uhabits.activities.common.dialogs

import android.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

internal object ColorPickerUtils {
    val presetColors = intArrayOf(
        0xFFF8B4B7.toInt(), 0xFFF28E94.toInt(), 0xFFE66770.toInt(), 0xFFD84A56.toInt(), 0xFFB93643.toInt(), 0xFF8F2934.toInt(),
        0xFFF8C79F.toInt(), 0xFFF2AA73.toInt(), 0xFFE88B4B.toInt(), 0xFFD86C2D.toInt(), 0xFFB55220.toInt(), 0xFF873E1D.toInt(),
        0xFFF5E5A8.toInt(), 0xFFEDD470.toInt(), 0xFFDFBA3F.toInt(), 0xFFC99A24.toInt(), 0xFF9F761D.toInt(), 0xFF77591C.toInt(),
        0xFFB9E2BB.toInt(), 0xFF8FD498.toInt(), 0xFF68C37A.toInt(), 0xFF55B868.toInt(), 0xFF358E4B.toInt(), 0xFF276B3A.toInt(),
        0xFFB8E4E8.toInt(), 0xFF8AD2DA.toInt(), 0xFF5ABBC8.toInt(), 0xFF3C9EAE.toInt(), 0xFF2F7D8D.toInt(), 0xFF28616C.toInt(),
        0xFFBCD5F1.toInt(), 0xFF91B9E8.toInt(), 0xFF679DDA.toInt(), 0xFF487FC6.toInt(), 0xFF3563A4.toInt(), 0xFF2C4B7D.toInt(),
        0xFFD8C5EB.toInt(), 0xFFBFA1DF.toInt(), 0xFFA27BD0.toInt(), 0xFF865AB8.toInt(), 0xFF6B4395.toInt(), 0xFF523574.toInt(),
        0xFFE2E3E5.toInt(), 0xFFBFC2C6.toInt(), 0xFF969BA1.toInt(), 0xFF71767D.toInt(), 0xFF50555B.toInt(), 0xFF34383D.toInt()
    )

    const val DEFAULT_COLOR = 0xFF55B868.toInt()
    const val DARK_ON_COLOR = 0xFF17191C.toInt()

    fun toHex(color: Int): String = String.format(
        "#%02X%02X%02X",
        Color.red(color),
        Color.green(color),
        Color.blue(color)
    )

    fun parseHex(value: String): Int? {
        val normalized = value.trim().removePrefix("#")
        if (!normalized.matches(Regex("[0-9a-fA-F]{6}"))) return null
        return (0xFF000000L or normalized.toLong(16)).toInt()
    }

    fun relativeLuminance(color: Int): Double {
        fun channel(value: Int): Double {
            val normalized = value / 255.0
            return if (normalized <= 0.04045) normalized / 12.92
            else ((normalized + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(Color.red(color)) +
            0.7152 * channel(Color.green(color)) +
            0.0722 * channel(Color.blue(color))
    }

    fun contrastRatio(first: Int, second: Int): Double {
        val firstLuminance = relativeLuminance(first)
        val secondLuminance = relativeLuminance(second)
        return (max(firstLuminance, secondLuminance) + 0.05) /
            (min(firstLuminance, secondLuminance) + 0.05)
    }

    fun onColor(color: Int): Int = if (
        contrastRatio(color, DARK_ON_COLOR) >= contrastRatio(color, Color.WHITE)
    ) DARK_ON_COLOR else Color.WHITE

    fun accentTextColor(accent: Int, background: Int, fallback: Int): Int =
        if (contrastRatio(accent, background) >= 4.5) accent else fallback
}
