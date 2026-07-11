/*
 * Copyright (C) 2016-2025 Alinson Santos Xavier <git@axavier.org>
 *
 * This file is part of Loop Habit Tracker.
 *
 * Loop Habit Tracker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * Loop Habit Tracker is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <http://www.gnu.org/licenses/>.
 */

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
            val selected: Boolean = false,
            val destructive: Boolean = false,
            val visible: Boolean = true,
        ) : Entry()

        object Divider : Entry()
    }

    fun show(
        anchor: View,
        entries: List<Entry>,
        minWidthDp: Int = 132,
        maxWidthDp: Int = 156,
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
            setPadding(px(4), px(4), px(4), px(4))
            background = GradientDrawable().apply {
                setColor(palette.surface)
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
                    textSize = 15f
                    gravity = Gravity.CENTER_VERTICAL
                    includeFontPadding = false
                    minHeight = px(40)
                    setPadding(px(14), 0, px(14), 0)
                    setTextColor(
                        when {
                            entry.destructive -> destructive
                            entry.selected -> accent
                            else -> sres.getColor(R.attr.contrast100)
                        },
                    )
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
                    px(40),
                ))
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
        val location = IntArray(2)
        anchor.getLocationOnScreen(location)
        val margin = px(8)
        val x = (location[0] + anchor.width - measuredWidth)
            .coerceIn(frame.left + margin, frame.right - measuredWidth - margin)
        val belowY = location[1] + anchor.height + px(4)
        val aboveY = location[1] - content.measuredHeight - px(4)
        val y = if (belowY + content.measuredHeight <= frame.bottom - margin) {
            belowY
        } else {
            aboveY.coerceAtLeast(frame.top + margin)
        }
        popup.showAtLocation(anchor.rootView, Gravity.TOP or Gravity.START, x, y)
    }
}
