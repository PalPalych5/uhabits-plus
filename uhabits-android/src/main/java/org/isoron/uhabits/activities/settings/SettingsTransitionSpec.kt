package org.isoron.uhabits.activities.settings

import android.view.animation.PathInterpolator

object SettingsTransitionSpec {
    const val PRESS_DURATION = 70L
    const val FORWARD_DURATION = 540L
    const val RETURN_DURATION = 480L
    const val REDUCED_DURATION = 140L
    const val PRESSED_SCALE = 0.988f

    val PRINCIPAL_INTERPOLATOR = PathInterpolator(0.2f, 0f, 0f, 1f)

    fun smoothstep(start: Float, end: Float, value: Float): Float {
        if (end <= start) return if (value >= end) 1f else 0f
        val t = ((value - start) / (end - start)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    fun forwardSourceAlpha(progress: Float): Float =
        1f - smoothstep(0.18f, 0.48f, progress)

    fun forwardToolbarAlpha(progress: Float): Float = smoothstep(0.55f, 0.84f, progress)

    fun forwardBodyAlpha(progress: Float): Float = smoothstep(0.58f, 0.92f, progress)

    fun forwardNavigationAlpha(progress: Float): Float =
        1f - smoothstep(0.72f, 0.94f, progress)

    fun returnToolbarAlpha(returnProgress: Float): Float =
        1f - smoothstep(0.10f, 0.54f, returnProgress)

    fun returnBodyAlpha(returnProgress: Float): Float =
        1f - smoothstep(0.12f, 0.58f, returnProgress)

    fun returnSourceAlpha(returnProgress: Float): Float =
        smoothstep(0.22f, 0.62f, returnProgress)

    fun returnNavigationAlpha(returnProgress: Float): Float =
        smoothstep(0.12f, 0.42f, returnProgress)
}
