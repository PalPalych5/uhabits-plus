/*
 * Copyright (C) 2016-2026 Álinson Santos Xavier <git@axavier.org> and contributors
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

package org.isoron.uhabits.activities.containers

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.common.theme.MainTabsThemeBridge
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.utils.dp

data class AncestorItem(
    val id: ContainerId?,
    val name: String,
    val isCurrent: Boolean = false
)

class AncestorsBottomSheetDialog : BottomSheetDialogFragment() {

    var items: List<AncestorItem> = emptyList()
    var onSelectAncestor: ((ContainerId?) -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val context = requireContext()
        val palette = MainTabsThemeBridge.resolve(context)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.background)
            setPadding(dp(20f).toInt(), dp(16f).toInt(), dp(20f).toInt(), dp(24f).toInt())
        }

        val title = TextView(context).apply {
            text = getString(R.string.containers_ancestors_title)
            setTextColor(palette.onSurface)
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(16f).toInt())
        }
        root.addView(title)

        for ((index, item) in items.withIndex()) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                val padY = dp(10f).toInt()
                val padX = dp(12f).toInt()
                setPadding(padX, padY, padX, padY)

                val bg = GradientDrawable().apply {
                    cornerRadius = dp(8f)
                    if (item.isCurrent) {
                        setColor(palette.surfaceVariant)
                        setStroke(dp(1.2f).toInt(), MainTabsThemeBridge.withAlpha(palette.accent, 0.6f))
                    } else {
                        setColor(palette.surface)
                    }
                }
                background = bg

                if (!item.isCurrent) {
                    setOnClickListener {
                        dismiss()
                        onSelectAncestor?.invoke(item.id)
                    }
                }
            }

            val indentPrefix = if (index == 0) {
                ""
            } else {
                "   ".repeat(index - 1) + "└── "
            }

            val label = TextView(context).apply {
                text = if (item.isCurrent) {
                    "$indentPrefix[ ${item.name} ]"
                } else {
                    "$indentPrefix${item.name}"
                }
                setTextColor(if (item.isCurrent) palette.accent else palette.onSurface)
                textSize = 15f
                if (item.isCurrent) {
                    setTypeface(typeface, Typeface.BOLD)
                }
            }
            row.addView(label)

            val lp = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                setMargins(0, 0, 0, dp(6f).toInt())
            }
            root.addView(row, lp)
        }

        val closeBtn = Button(context).apply {
            text = getString(R.string.containers_cancel)
            setTextColor(palette.onSurfaceVariant)
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setOnClickListener { dismiss() }
        }
        val closeLp = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            setMargins(0, dp(8f).toInt(), 0, 0)
        }
        root.addView(closeBtn, closeLp)

        return root
    }

    private fun dp(v: Float) = (v * resources.displayMetrics.density)
}
