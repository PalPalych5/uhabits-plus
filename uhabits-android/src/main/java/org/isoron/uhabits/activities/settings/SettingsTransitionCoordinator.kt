package org.isoron.uhabits.activities.settings

import kotlin.math.min

/**
 * Pure Kotlin transition ordering coordinator, state machine, and morph frame math
 * shared with SettingsSectionFragment / SettingsCardMorphController / SettingsMorphLayer
 * so return-scroll ordering and transition lifecycle invariants can be verified directly
 * under JVM unit tests.
 */
internal object SettingsReturnCoordinator {
    fun runPreparedReturn(
        revealBottomNavigationHidden: () -> Unit,
        showHomeFragment: () -> Unit,
        restoreHomeScrollAndStart: () -> Unit,
    ) {
        revealBottomNavigationHidden()
        showHomeFragment()
        restoreHomeScrollAndStart()
    }

    fun runImmediateReturn(
        settleOverlayHome: () -> Unit,
        revealBottomNavigation: () -> Unit,
        showHomeFragment: () -> Unit,
        popDetailBackStack: () -> Unit,
        restoreHomeScroll: () -> Unit,
        syncChrome: () -> Unit,
    ) {
        settleOverlayHome()
        revealBottomNavigation()
        showHomeFragment()
        popDetailBackStack()
        restoreHomeScroll()
        syncChrome()
    }
}

internal class SettingsMorphStateMachine(
    initialState: SettingsCardMorphController.State = SettingsCardMorphController.State.IDLE_HOME,
) {
    var state: SettingsCardMorphController.State = initialState
        private set

    var backQueued: Boolean = false
        private set

    fun reserveForward(): Boolean {
        if (state != SettingsCardMorphController.State.IDLE_HOME) return false
        state = SettingsCardMorphController.State.PREPARING_FORWARD
        backQueued = false
        return true
    }

    fun startAnimatingForward(): Boolean {
        if (state != SettingsCardMorphController.State.PREPARING_FORWARD) return false
        state = SettingsCardMorphController.State.ANIMATING_FORWARD
        return true
    }

    fun finishForward(): Boolean {
        val queued = backQueued
        backQueued = false
        state = SettingsCardMorphController.State.IDLE_DETAIL
        return queued
    }

    fun reserveReturn(): Boolean {
        if (state != SettingsCardMorphController.State.IDLE_DETAIL) return false
        state = SettingsCardMorphController.State.PREPARING_RETURN
        return true
    }

    fun startAnimatingReturn(): Boolean {
        if (state != SettingsCardMorphController.State.PREPARING_RETURN) return false
        state = SettingsCardMorphController.State.ANIMATING_RETURN
        return true
    }

    fun finishReturn() {
        backQueued = false
        state = SettingsCardMorphController.State.IDLE_HOME
    }

    fun requestBackDuringForward(): Boolean = when (state) {
        SettingsCardMorphController.State.PREPARING_FORWARD,
        SettingsCardMorphController.State.ANIMATING_FORWARD -> {
            backQueued = true
            true
        }
        else -> false
    }

    fun markHome(): Boolean {
        if (state == SettingsCardMorphController.State.IDLE_HOME) return false
        backQueued = false
        state = SettingsCardMorphController.State.IDLE_HOME
        return true
    }

    fun markDetail(): Boolean {
        if (state == SettingsCardMorphController.State.IDLE_DETAIL) return false
        backQueued = false
        state = SettingsCardMorphController.State.IDLE_DETAIL
        return true
    }

    fun needsCancel(target: SettingsCardMorphController.SettleTarget): Boolean =
        !((state == SettingsCardMorphController.State.IDLE_HOME && target == SettingsCardMorphController.SettleTarget.HOME) ||
            (state == SettingsCardMorphController.State.IDLE_DETAIL && target == SettingsCardMorphController.SettleTarget.DETAIL))

    fun beginCancel() {
        state = SettingsCardMorphController.State.CANCELLING
    }

    fun finishCancel(target: SettingsCardMorphController.SettleTarget) {
        backQueued = false
        state = if (target == SettingsCardMorphController.SettleTarget.HOME) {
            SettingsCardMorphController.State.IDLE_HOME
        } else {
            SettingsCardMorphController.State.IDLE_DETAIL
        }
    }
}

internal data class MorphRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

internal data class MorphFrameGeometry(
    val bounds: MorphRect,
    val cornerRadius: Float,
    val geometryProgress: Float,
)

internal object SettingsMorphFrameMath {
    fun computeFrame(
        startBounds: MorphRect,
        endBounds: MorphRect,
        startRadius: Float,
        progress: Float,
        isForward: Boolean,
    ): MorphFrameGeometry {
        val p = progress.coerceIn(0f, 1f)
        val geometryProgress = if (isForward) p else 1f - p
        val side = SettingsTransitionSpec.smoothstep(0f, 0.92f, geometryProgress)
        val top = SettingsTransitionSpec.smoothstep(0.03f, 0.95f, geometryProgress)
        val bottom = SettingsTransitionSpec.smoothstep(0.07f, 1f, geometryProgress)
        val bounds = MorphRect(
            left = lerp(startBounds.left, endBounds.left, side),
            top = lerp(startBounds.top, endBounds.top, top),
            right = lerp(startBounds.right, endBounds.right, side),
            bottom = lerp(startBounds.bottom, endBounds.bottom, bottom),
        )
        val radiusProgress = SettingsTransitionSpec.smoothstep(0.34f, 1f, geometryProgress)
        val cornerRadius = lerp(startRadius, 0f, radiusProgress)
            .coerceAtMost(min(bounds.width, bounds.height) / 2f)
        return MorphFrameGeometry(
            bounds = bounds,
            cornerRadius = cornerRadius,
            geometryProgress = geometryProgress,
        )
    }

    private fun lerp(start: Float, end: Float, fraction: Float): Float =
        start + (end - start) * fraction
}
