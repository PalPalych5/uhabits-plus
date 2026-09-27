package org.isoron.uhabits.activities.settings

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.recyclerview.widget.RecyclerView
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import kotlin.math.min

class SettingsCardMorphController(
    private val overlayHost: ViewGroup,
) {
    enum class State {
        IDLE_HOME,
        PREPARING_FORWARD,
        ANIMATING_FORWARD,
        IDLE_DETAIL,
        PREPARING_RETURN,
        ANIMATING_RETURN,
        CANCELLING,
    }

    enum class SettleTarget { HOME, DETAIL }

    var state: State = State.IDLE_HOME
        private set

    private var animator: ValueAnimator? = null
    private var layer: SettingsMorphLayer? = null
    private var sourceView: View? = null
    private var detailRoot: View? = null
    private var bottomNavigation: View? = null
    private var detailRecycler: RecyclerView? = null
    private var savedItemAnimator: RecyclerView.ItemAnimator? = null
    private var backQueued = false
    private var terminalActionRunning = false

    fun reserveForward(): Boolean {
        if (state != State.IDLE_HOME) return false
        state = State.PREPARING_FORWARD
        backQueued = false
        return true
    }

    fun reserveReturn(): Boolean {
        if (state != State.IDLE_DETAIL) return false
        state = State.PREPARING_RETURN
        return true
    }

    fun requestBackDuringForward(): Boolean = when (state) {
        State.PREPARING_FORWARD, State.ANIMATING_FORWARD -> {
            backQueued = true
            true
        }
        else -> false
    }

    fun markHome() {
        if (state == State.IDLE_HOME) return
        cleanVisualState(SettleTarget.HOME)
        state = State.IDLE_HOME
    }

    fun markDetail() {
        if (state == State.IDLE_DETAIL) return
        cleanVisualState(SettleTarget.DETAIL)
        state = State.IDLE_DETAIL
    }

    fun animateForward(
        source: SettingsTransitionSource,
        detailRoot: View,
        detailToolbar: View,
        detailBody: RecyclerView,
        bottomNavigation: View,
        onDetailCommitted: () -> Unit,
        onComplete: (backQueued: Boolean) -> Unit,
    ) {
        if (state != State.PREPARING_FORWARD) return
        terminalActionRunning = false
        this.sourceView = source.container
        this.detailRoot = detailRoot
        this.bottomNavigation = bottomNavigation
        this.detailRecycler = detailBody
        savedItemAnimator = detailBody.itemAnimator
        detailBody.itemAnimator = null

        overlayHost.visibility = View.INVISIBLE
        overlayHost.post {
            if (state != State.PREPARING_FORWARD) return@post
            val prepared = prepareLayer(source, detailRoot, detailToolbar, detailBody, bottomNavigation)
            if (!prepared) {
                fallbackForward("forward_prepare_failed", source.container, detailRoot, bottomNavigation, onDetailCommitted, onComplete)
                return@post
            }
            val morphLayer = checkNotNull(layer)
            overlayHost.visibility = View.VISIBLE
            morphLayer.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    morphLayer.viewTreeObserver.removeOnPreDrawListener(this)
                    if (state != State.PREPARING_FORWARD) return true
                    source.container.alpha = 0f
                    state = State.ANIMATING_FORWARD
                    startForwardAnimator(
                        morphLayer,
                        detailRoot,
                        detailToolbar,
                        detailBody,
                        bottomNavigation,
                        onDetailCommitted,
                        onComplete,
                    )
                    return true
                }
            })
        }
    }

    fun animateReturn(
        source: SettingsTransitionSource?,
        detailRoot: View,
        detailToolbar: View,
        detailBody: RecyclerView,
        bottomNavigation: View,
        onHomeCommitted: () -> Unit,
        onComplete: () -> Unit,
    ) {
        if (state != State.PREPARING_RETURN) return
        terminalActionRunning = false
        this.sourceView = source?.container
        this.detailRoot = detailRoot
        this.bottomNavigation = bottomNavigation
        this.detailRecycler = detailBody
        savedItemAnimator = detailBody.itemAnimator
        detailBody.itemAnimator = null

        if (source == null) {
            fallbackReturn("return_source_unavailable", detailRoot, bottomNavigation, onHomeCommitted, onComplete)
            return
        }

        overlayHost.visibility = View.INVISIBLE
        overlayHost.post {
            if (state != State.PREPARING_RETURN) return@post
            if (!prepareLayer(source, detailRoot, detailToolbar, detailBody, bottomNavigation)) {
                fallbackReturn("return_prepare_failed", detailRoot, bottomNavigation, onHomeCommitted, onComplete)
                return@post
            }
            val morphLayer = checkNotNull(layer)
            morphLayer.setFrame(0f, SettingsMorphLayer.Direction.RETURN)
            overlayHost.visibility = View.VISIBLE
            morphLayer.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    morphLayer.viewTreeObserver.removeOnPreDrawListener(this)
                    if (state != State.PREPARING_RETURN) return true
                    detailRoot.alpha = 0f
                    source.container.alpha = 0f
                    bottomNavigation.visibility = View.VISIBLE
                    bottomNavigation.alpha = 0f
                    bottomNavigation.translationY = 12f * bottomNavigation.resources.displayMetrics.density
                    state = State.ANIMATING_RETURN
                    startReturnAnimator(morphLayer, detailRoot, bottomNavigation, onHomeCommitted, onComplete)
                    return true
                }
            })
        }
    }

    private fun prepareLayer(
        source: SettingsTransitionSource,
        detailRoot: View,
        detailToolbar: View,
        detailBody: View,
        bottomNavigation: View,
    ): Boolean {
        val sourceBounds = source.container.boundsInAncestor(overlayHost) ?: return false
        val detailBounds = detailRoot.boundsInAncestor(overlayHost) ?: return false
        val toolbarBounds = detailToolbar.boundsInAncestor(overlayHost) ?: return false
        val bodyBounds = detailBody.boundsInAncestor(overlayHost) ?: return false
        val bottomBounds = bottomNavigation.boundsInAncestor(overlayHost)
        if (!sourceBounds.isUsable() || !detailBounds.isUsable() || !toolbarBounds.isUsable() || !bodyBounds.isUsable()) {
            return false
        }

        val destinationBounds = RectF(detailBounds)
        if (bottomBounds != null && bottomBounds.isUsable()) destinationBounds.bottom = bottomBounds.bottom
        val maxWidth = detailRoot.resources.getDimensionPixelSize(R.dimen.settings_content_max_width).toFloat()
        if (destinationBounds.width() > maxWidth) {
            val center = destinationBounds.centerX()
            destinationBounds.left = center - maxWidth / 2f
            destinationBounds.right = center + maxWidth / 2f
        }

        source.container.animate().setListener(null).cancel()
        source.container.scaleX = 1f
        source.container.scaleY = 1f
        source.container.isPressed = false
        source.container.background?.jumpToCurrentState()
        val sourceAlpha = source.container.alpha
        source.container.alpha = 1f
        val snapshot = capture(source.container)
        source.container.alpha = sourceAlpha
        snapshot ?: return false
        val toolbarSnapshot = capture(detailToolbar)
        val bodySnapshot = capture(detailBody)
        if (toolbarSnapshot == null || bodySnapshot == null) {
            snapshot.recycle()
            toolbarSnapshot?.recycle()
            bodySnapshot?.recycle()
            return false
        }
        val app = detailRoot.context.applicationContext as HabitsApplication
        val palette = SettingsThemePaletteResolver.resolve(detailRoot.context, app.component.preferences)
        val morphLayer = SettingsMorphLayer(overlayHost.context)
        morphLayer.prepare(
            sourceBounds = sourceBounds,
            destinationBounds = destinationBounds,
            sourceBitmap = snapshot,
            toolbarBitmap = toolbarSnapshot,
            bodyBitmap = bodySnapshot,
            toolbarInHost = toolbarBounds,
            bodyInHost = bodyBounds,
            sourceColor = palette.surface,
            destinationColor = palette.background,
            boundaryColor = palette.divider,
            sourceCornerRadius = source.container.resources.getDimension(R.dimen.settings_card_radius),
        )
        overlayHost.removeAllViews()
        overlayHost.addView(
            morphLayer,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        layer = morphLayer
        return true
    }

    private fun startForwardAnimator(
        morphLayer: SettingsMorphLayer,
        detailRoot: View,
        detailToolbar: View,
        detailBody: RecyclerView,
        bottomNavigation: View,
        onDetailCommitted: () -> Unit,
        onComplete: (Boolean) -> Unit,
    ) {
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SettingsTransitionSpec.FORWARD_DURATION
            interpolator = SettingsTransitionSpec.PRINCIPAL_INTERPOLATOR
            addUpdateListener { animation ->
                val progress = animation.animatedValue as Float
                morphLayer.setFrame(progress, SettingsMorphLayer.Direction.FORWARD)
                val navAlpha = SettingsTransitionSpec.forwardNavigationAlpha(progress)
                bottomNavigation.alpha = navAlpha
                bottomNavigation.translationY = (1f - navAlpha) * 12f * bottomNavigation.resources.displayMetrics.density
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (terminalActionRunning || state != State.ANIMATING_FORWARD) return
                    terminalActionRunning = true
                    bottomNavigation.visibility = View.GONE
                    bottomNavigation.alpha = 1f
                    bottomNavigation.translationY = 0f
                    detailRoot.awaitNextPreDraw {
                        if (state != State.ANIMATING_FORWARD) return@awaitNextPreDraw
                        val finalToolbar = capture(detailToolbar)
                        val finalBody = capture(detailBody)
                        val toolbarBounds = detailToolbar.boundsInAncestor(overlayHost)
                        val bodyBounds = detailBody.boundsInAncestor(overlayHost)
                        if (finalToolbar != null && finalBody != null && toolbarBounds != null && bodyBounds != null) {
                            morphLayer.updateDetailContent(finalToolbar, finalBody, toolbarBounds, bodyBounds)
                        } else {
                            finalToolbar?.recycle()
                            finalBody?.recycle()
                        }
                        detailRoot.alpha = 1f
                        onDetailCommitted()
                        overlayHost.postOnAnimation {
                            val queued = backQueued
                            backQueued = false
                            restoreItemAnimator()
                            releaseOverlay()
                            state = State.IDLE_DETAIL
                            terminalActionRunning = false
                            onComplete(queued)
                        }
                    }
                }
            })
            start()
        }
    }

    private fun startReturnAnimator(
        morphLayer: SettingsMorphLayer,
        detailRoot: View,
        bottomNavigation: View,
        onHomeCommitted: () -> Unit,
        onComplete: () -> Unit,
    ) {
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SettingsTransitionSpec.RETURN_DURATION
            interpolator = SettingsTransitionSpec.PRINCIPAL_INTERPOLATOR
            addUpdateListener { animation ->
                val progress = animation.animatedValue as Float
                morphLayer.setFrame(progress, SettingsMorphLayer.Direction.RETURN)
                val navAlpha = SettingsTransitionSpec.returnNavigationAlpha(progress)
                bottomNavigation.alpha = navAlpha
                bottomNavigation.translationY = (1f - navAlpha) * 12f * bottomNavigation.resources.displayMetrics.density
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (terminalActionRunning || state != State.ANIMATING_RETURN) return
                    terminalActionRunning = true
                    onHomeCommitted()
                    sourceView?.alpha = 1f
                    detailRoot.alpha = 0f
                    bottomNavigation.visibility = View.VISIBLE
                    bottomNavigation.alpha = 1f
                    bottomNavigation.translationY = 0f
                    restoreItemAnimator()
                    releaseOverlay()
                    state = State.IDLE_HOME
                    terminalActionRunning = false
                    onComplete()
                }
            })
            start()
        }
    }

    private fun fallbackForward(
        reason: String,
        source: View,
        detailRoot: View,
        bottomNavigation: View,
        onDetailCommitted: () -> Unit,
        onComplete: (Boolean) -> Unit,
    ) {
        logFallback(reason)
        releaseOverlay()
        state = State.ANIMATING_FORWARD
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SettingsTransitionSpec.REDUCED_DURATION
            addUpdateListener { detailRoot.alpha = it.animatedValue as Float }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (state != State.ANIMATING_FORWARD) return
                    source.alpha = 1f
                    detailRoot.alpha = 1f
                    bottomNavigation.visibility = View.GONE
                    onDetailCommitted()
                    restoreItemAnimator()
                    state = State.IDLE_DETAIL
                    onComplete(backQueued.also { backQueued = false })
                }
            })
            start()
        }
    }

    private fun fallbackReturn(
        reason: String,
        detailRoot: View,
        bottomNavigation: View,
        onHomeCommitted: () -> Unit,
        onComplete: () -> Unit,
    ) {
        logFallback(reason)
        releaseOverlay()
        bottomNavigation.visibility = View.VISIBLE
        bottomNavigation.alpha = 0f
        state = State.ANIMATING_RETURN
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SettingsTransitionSpec.REDUCED_DURATION
            addUpdateListener {
                val progress = it.animatedValue as Float
                detailRoot.alpha = 1f - progress
                bottomNavigation.alpha = progress
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (state != State.ANIMATING_RETURN) return
                    onHomeCommitted()
                    sourceView?.alpha = 1f
                    bottomNavigation.alpha = 1f
                    restoreItemAnimator()
                    state = State.IDLE_HOME
                    onComplete()
                }
            })
            start()
        }
    }

    fun cancelAndSettle(target: SettleTarget) {
        if (state == State.IDLE_HOME && target == SettleTarget.HOME) return
        if (state == State.IDLE_DETAIL && target == SettleTarget.DETAIL) return
        state = State.CANCELLING
        animator?.removeAllListeners()
        animator?.cancel()
        animator = null
        cleanVisualState(target)
        state = if (target == SettleTarget.HOME) State.IDLE_HOME else State.IDLE_DETAIL
    }

    private fun cleanVisualState(target: SettleTarget) {
        sourceView?.apply {
            alpha = 1f
            scaleX = 1f
            scaleY = 1f
            isPressed = false
        }
        detailRoot?.alpha = if (target == SettleTarget.DETAIL) 1f else 0f
        bottomNavigation?.apply {
            visibility = if (target == SettleTarget.HOME) View.VISIBLE else View.GONE
            alpha = 1f
            translationY = 0f
        }
        restoreItemAnimator()
        releaseOverlay()
        backQueued = false
        terminalActionRunning = false
    }

    private fun restoreItemAnimator() {
        detailRecycler?.itemAnimator = savedItemAnimator
        savedItemAnimator = null
        detailRecycler = null
    }

    private fun releaseOverlay() {
        animator = null
        layer?.release()
        layer = null
        overlayHost.removeAllViews()
        overlayHost.visibility = View.INVISIBLE
    }

    private fun capture(view: View): Bitmap? {
        if (!view.isAttachedToWindow || view.width <= 0 || view.height <= 0) return null
        return runCatching {
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { bitmap ->
                view.draw(Canvas(bitmap))
            }
        }.getOrNull()
    }

    private fun logFallback(reason: String) {
        Log.w(TAG, "fallback=$reason state=$state")
    }

    companion object {
        private const val TAG = "SettingsMorph"
    }
}

internal fun View.boundsInAncestor(ancestor: ViewGroup): RectF? {
    if (!isAttachedToWindow || !ancestor.isAttachedToWindow || width <= 0 || height <= 0) return null
    if (ancestor.width <= 0 || ancestor.height <= 0) return null
    val viewLocation = IntArray(2)
    val ancestorLocation = IntArray(2)
    getLocationOnScreen(viewLocation)
    ancestor.getLocationOnScreen(ancestorLocation)
    val left = (viewLocation[0] - ancestorLocation[0]).toFloat()
    val top = (viewLocation[1] - ancestorLocation[1]).toFloat()
    return RectF(left, top, left + width, top + height)
}

private fun RectF.isUsable(): Boolean =
    left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite() && width() > 1f && height() > 1f

private inline fun View.awaitNextPreDraw(crossinline action: () -> Unit) {
    viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
        override fun onPreDraw(): Boolean {
            viewTreeObserver.removeOnPreDrawListener(this)
            action()
            return true
        }
    })
    requestLayout()
}
