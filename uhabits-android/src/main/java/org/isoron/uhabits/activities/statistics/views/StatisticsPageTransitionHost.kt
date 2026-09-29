package org.isoron.uhabits.activities.statistics.views

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.provider.Settings
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import kotlin.math.abs

/**
 * Two-layer host container for atomic page-level sliding transitions:
 * - Incoming page built completely off-screen
 * - Both pages translated simultaneously using translationX
 * - Only after transition completes is the old page discarded
 * - Intercepts horizontal swipes cleanly without vertical scroll fighting
 * - Respects reduced motion settings
 */
class StatisticsPageTransitionHost @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    var onHorizontalSwipe: ((Int) -> Unit)? = null

    private var currentPage: View? = null
    private var isTransitioning = false

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val swipeThreshold = maxOf(48f * resources.displayMetrics.density, touchSlop * 3.5f)

    private var downX = 0f
    private var downY = 0f
    private var isHorizontalDrag = false

    fun getCurrentPage(): View? = currentPage

    fun showInitialPage(page: View) {
        removeAllViews()
        page.translationX = 0f
        addView(page, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        currentPage = page
    }

    fun transitionToPage(newPage: View, direction: Int, onComplete: (() -> Unit)? = null) {
        val oldPage = currentPage
        if (oldPage == null || direction == 0 || isReducedMotion(context)) {
            removeAllViews()
            newPage.translationX = 0f
            addView(newPage, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            currentPage = newPage
            onComplete?.invoke()
            return
        }

        if (isTransitioning) {
            oldPage.animate().cancel()
            removeAllViews()
        }

        isTransitioning = true
        val hostWidth = width.toFloat().takeIf { it > 0 }
            ?: resources.displayMetrics.widthPixels.toFloat()

        // Incoming page enters from right (direction > 0) or left (direction < 0)
        newPage.translationX = if (direction > 0) hostWidth else -hostWidth
        addView(newPage, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        val duration = 210L
        val interpolator = FastOutSlowInInterpolator()

        oldPage.animate()
            .translationX(if (direction > 0) -hostWidth else hostWidth)
            .setDuration(duration)
            .setInterpolator(interpolator)
            .start()

        newPage.animate()
            .translationX(0f)
            .setDuration(duration)
            .setInterpolator(interpolator)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    newPage.animate().setListener(null)
                    removeView(oldPage)
                    currentPage = newPage
                    isTransitioning = false
                    onComplete?.invoke()
                }
            })
            .start()
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                isHorizontalDrag = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (!isHorizontalDrag && abs(dx) > touchSlop && abs(dx) > abs(dy) * 1.6f) {
                    isHorizontalDrag = true
                    parent.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isHorizontalDrag = false
            }
        }
        return isHorizontalDrag
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (!isHorizontalDrag && abs(dx) > touchSlop && abs(dx) > abs(dy) * 1.6f) {
                    isHorizontalDrag = true
                    parent.requestDisallowInterceptTouchEvent(true)
                }
            }
            MotionEvent.ACTION_UP -> {
                val dx = ev.x - downX
                if (abs(dx) >= swipeThreshold) {
                    // Finger moved left (dx < 0) -> user wants next period (+1)
                    // Finger moved right (dx > 0) -> user wants previous period (-1)
                    val dir = if (dx < 0) 1 else -1
                    onHorizontalSwipe?.invoke(dir)
                }
                isHorizontalDrag = false
            }
            MotionEvent.ACTION_CANCEL -> {
                isHorizontalDrag = false
            }
        }
        return true
    }

    private fun isReducedMotion(context: Context): Boolean {
        return try {
            val durationScale = Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1.0f
            )
            val transitionScale = Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.TRANSITION_ANIMATION_SCALE,
                1.0f
            )
            durationScale == 0f || transitionScale == 0f
        } catch (_: Exception) {
            false
        }
    }
}
