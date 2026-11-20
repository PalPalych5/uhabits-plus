/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.activities.common.views

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.PopupWindow
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.findViewTreeViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.google.android.material.color.MaterialColors
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.common.dialogs.CustomDialogs

object CompactPopupMenu {
    sealed class Entry {
        data class Item(
            val id: Int,
            val title: CharSequence,
            val iconRes: Int? = null,
            val selected: Boolean = false,
            val destructive: Boolean = false,
            val visible: Boolean = true,
        ) : Entry()

        object Divider : Entry()
    }

    internal fun calculatePopupCoordinates(
        anchorLeft: Int,
        anchorTop: Int,
        anchorRight: Int,
        anchorBottom: Int,
        frameLeft: Int,
        frameTop: Int,
        frameRight: Int,
        frameBottom: Int,
        popupWidth: Int,
        popupHeight: Int,
        horizontalMarginPx: Int,
        verticalMarginPx: Int,
        verticalOffsetPx: Int,
    ): Pair<Int, Int> {
        val minX = frameLeft + horizontalMarginPx
        val maxX = (frameRight - popupWidth - horizontalMarginPx).coerceAtLeast(minX)
        val x = (anchorRight - popupWidth).coerceIn(minX, maxX)

        val minY = frameTop + verticalMarginPx
        val maxY = (frameBottom - popupHeight - verticalMarginPx).coerceAtLeast(minY)
        val belowY = anchorBottom + verticalOffsetPx
        val aboveY = anchorTop - popupHeight - verticalOffsetPx
        val spaceBelow = (frameBottom - verticalMarginPx) - (anchorBottom + verticalOffsetPx)
        val spaceAbove = (anchorTop - verticalOffsetPx) - (frameTop + verticalMarginPx)

        val y = when {
            popupHeight <= spaceBelow -> belowY.coerceIn(minY, maxY)
            popupHeight <= spaceAbove -> aboveY.coerceIn(minY, maxY)
            spaceAbove > spaceBelow -> aboveY.coerceIn(minY, maxY)
            else -> belowY.coerceIn(minY, maxY)
        }
        return x to y
    }

    internal fun calculatePopupPosition(
        anchorRect: Rect,
        visibleFrame: Rect,
        popupWidth: Int,
        popupHeight: Int,
        horizontalMarginPx: Int,
        verticalMarginPx: Int,
        verticalOffsetPx: Int,
    ): Pair<Int, Int> = calculatePopupCoordinates(
        anchorLeft = anchorRect.left,
        anchorTop = anchorRect.top,
        anchorRight = anchorRect.right,
        anchorBottom = anchorRect.bottom,
        frameLeft = visibleFrame.left,
        frameTop = visibleFrame.top,
        frameRight = visibleFrame.right,
        frameBottom = visibleFrame.bottom,
        popupWidth = popupWidth,
        popupHeight = popupHeight,
        horizontalMarginPx = horizontalMarginPx,
        verticalMarginPx = verticalMarginPx,
        verticalOffsetPx = verticalOffsetPx,
    )

    fun show(
        anchor: View,
        entries: List<Entry>,
        minWidthDp: Int = 144,
        maxWidthDp: Int = 220,
        horizontalMarginDp: Int = 8,
        verticalOffsetDp: Int = 4,
        anchorRectOnScreen: Rect? = null,
        onDismiss: (() -> Unit)? = null,
        onItemClick: (Int) -> Unit,
    ) {
        val context = anchor.context
        val lifecycle = anchor.findViewTreeLifecycleOwner() ?: context.findOwner<LifecycleOwner>()
        val savedState = anchor.findViewTreeSavedStateRegistryOwner() ?: context.findOwner<SavedStateRegistryOwner>()
        if (anchor.windowToken == null || lifecycle == null || savedState == null ||
            lifecycle.lifecycle.currentState == Lifecycle.State.DESTROYED
        ) {
            onDismiss?.invoke()
            return
        }
        val viewModels = anchor.findViewTreeViewModelStoreOwner() ?: context.findOwner<ViewModelStoreOwner>()
        val palette = CustomDialogs.resolvePalette(context)
        val accent = CustomDialogs.resolveAccentColor(context)
        val destructive = MaterialColors.getColor(
            anchor,
            com.google.android.material.R.attr.colorError,
            ContextCompat.getColor(context, R.color.red_600),
        )
        val spacing = CompactPopupSpacing(context.resources.displayMetrics.density)
        val layout = measureCompactPopupMenu(
            context, entries, spacing, minWidthDp, maxWidthDp, anchor.layoutDirection == View.LAYOUT_DIRECTION_RTL,
            accent, destructive,
        )
        val popup = PopupWindow(context)
        val host = ComposeView(context).apply {
            id = View.generateViewId()
            layoutDirection = anchor.layoutDirection
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                CompactPopupMenuContent(layout, spacing, palette) { id ->
                    if (popup.isShowing) {
                        popup.dismiss()
                        onItemClick(id)
                    }
                }
            }
        }
        popup.contentView = FrameLayout(context).apply {
            // This boundary makes the child the content root for the popup window recomposer.
            id = android.R.id.content
            layoutDirection = anchor.layoutDirection
            setViewTreeLifecycleOwner(lifecycle)
            setViewTreeSavedStateRegistryOwner(savedState)
            setViewTreeViewModelStoreOwner(viewModels)
            addView(host, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        popup.width = layout.width
        popup.height = layout.height
        popup.isFocusable = true
        popup.isOutsideTouchable = true
        popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popup.elevation = spacing.px(8).toFloat()
        popup.animationStyle = R.style.CompactPopupAnimation
        val detachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) = popup.dismiss()
        }
        popup.setOnDismissListener {
            anchor.removeOnAttachStateChangeListener(detachListener)
            onDismiss?.invoke()
        }

        val frame = Rect()
        anchor.rootView.getWindowVisibleDisplayFrame(frame)
        val anchorRect = if (anchorRectOnScreen != null && !anchorRectOnScreen.isEmpty) {
            anchorRectOnScreen
        } else {
            val location = IntArray(2)
            anchor.getLocationOnScreen(location)
            Rect(
                location[0],
                location[1],
                location[0] + anchor.width,
                location[1] + anchor.height,
            )
        }
        val (x, y) = calculatePopupPosition(
            anchorRect = anchorRect,
            visibleFrame = frame,
            popupWidth = layout.width,
            popupHeight = layout.height,
            horizontalMarginPx = spacing.px(horizontalMarginDp),
            verticalMarginPx = spacing.px(8),
            verticalOffsetPx = spacing.px(verticalOffsetDp),
        )
        // x already uses physical screen coordinates; row layout uses the anchor direction.
        val screenGravity = Gravity.getAbsoluteGravity(Gravity.TOP or Gravity.START, View.LAYOUT_DIRECTION_LTR)
        popup.showAtLocation(anchor.rootView, screenGravity, x, y)
        if (popup.isShowing) anchor.addOnAttachStateChangeListener(detachListener)
    }

    private inline fun <reified T> Context.findOwner(): T? {
        var candidate: Context? = this
        while (candidate is ContextWrapper && candidate !is T) candidate = candidate.baseContext
        return candidate as? T
    }
}
