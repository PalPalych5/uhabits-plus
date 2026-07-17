package org.isoron.uhabits.activities.settings

internal enum class SettingsAdaptiveProfile {
    COMPACT,
    REGULAR,
    WIDE;

    companion object {
        fun forWidthDp(widthDp: Int): SettingsAdaptiveProfile = when {
            widthDp < 360 -> COMPACT
            widthDp < 412 -> REGULAR
            else -> WIDE
        }
    }
}
