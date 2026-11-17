/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.activities.common.views

import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.color.MaterialColors
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.common.dialogs.CustomDialogs
import org.isoron.uhabits.utils.StyledResources

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
        val sres = StyledResources(context)
        val palette = CustomDialogs.resolvePalette(context)
        val accent = CustomDialogs.resolveAccentColor(context)
        val destructive = MaterialColors.getColor(
            anchor,
            com.google.android.material.R.attr.colorError,
            ContextCompat.getColor(context, R.color.red_600),
        )
        fun px(dp: Int) = (dp * context.resources.displayMetrics.density).toInt()
        val popup = PopupWindow(context)
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(6), px(6), px(6), px(6))
            background = GradientDrawable().apply {
                setColor(palette.dialogSurface)
                setStroke(px(1).coerceAtLeast(1), palette.dialogBorder)
                cornerRadius = px(12).toFloat()
            }
        }
        val visibleEntries = entries.filter {
            it !is Entry.Item || it.visible
        }
        visibleEntries.forEach { entry ->
            when (entry) {
                Entry.Divider -> content.addView(View(context).apply {
                    setBackgroundColor(palette.divider)
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    px(1),
                ).apply {
                    topMargin = px(4)
                    bottomMargin = px(4)
                })

                is Entry.Item -> content.addView(TextView(context).apply {
                    text = entry.title
                    textSize = 14f
                    gravity = Gravity.CENTER_VERTICAL
                    includeFontPadding = false
                    minHeight = px(42)
                    setPadding(px(12), 0, px(12), 0)
                    val itemColor = when {
                        entry.destructive -> destructive
                        entry.selected -> accent
                        else -> sres.getColor(R.attr.contrast100)
                    }
                    setTextColor(itemColor)
                    if (entry.iconRes != null) {
                        val d = ContextCompat.getDrawable(context, entry.iconRes)?.mutate()
                        d?.setTint(itemColor)
                        val iconPx = px(20)
                        d?.setBounds(0, 0, iconPx, iconPx)
                        setCompoundDrawablesRelative(d, null, null, null)
                        compoundDrawablePadding = px(12)
                    }
                    val ripple = android.util.TypedValue()
                    context.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
                    setBackgroundResource(ripple.resourceId)
                    if (entry.selected) {
                        background = GradientDrawable().apply {
                            setColor(palette.divider)
                            cornerRadius = px(8).toFloat()
                        }
                    }
                    setOnClickListener {
                        popup.dismiss()
                        onItemClick(entry.id)
                    }
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = px(2)
                    bottomMargin = px(2)
                })
            }
        }
        popup.contentView = content
        popup.width = ViewGroup.LayoutParams.WRAP_CONTENT
        popup.height = ViewGroup.LayoutParams.WRAP_CONTENT
        popup.isFocusable = true
        popup.isOutsideTouchable = true
        popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popup.elevation = px(8).toFloat()
        popup.animationStyle = R.style.CompactPopupAnimation
        popup.setOnDismissListener { onDismiss?.invoke() }

        content.measure(
            View.MeasureSpec.makeMeasureSpec(px(maxWidthDp), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val measuredWidth = content.measuredWidth
            .coerceAtLeast(px(minWidthDp))
            .coerceAtMost(px(maxWidthDp))
        popup.width = measuredWidth

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
            popupWidth = measuredWidth,
            popupHeight = content.measuredHeight,
            horizontalMarginPx = px(horizontalMarginDp),
            verticalMarginPx = px(8),
            verticalOffsetPx = px(verticalOffsetDp),
        )
        popup.showAtLocation(anchor.rootView, Gravity.TOP or Gravity.START, x, y)
    }
}
