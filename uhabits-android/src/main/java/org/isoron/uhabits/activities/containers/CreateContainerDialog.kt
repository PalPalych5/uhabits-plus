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

import android.graphics.Color
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
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.common.theme.MainTabsThemeBridge
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.containers.ContainerQueries
import org.isoron.uhabits.core.models.PaletteColor
import org.isoron.platform.gui.toInt
import org.isoron.uhabits.utils.currentTheme

class CreateContainerDialog : BottomSheetDialogFragment() {

    var initialParentId: ContainerId? = null
    var initialParentName: String? = null
    var containerQueries: ContainerQueries? = null
    var onSave: ((name: String, parentId: ContainerId?, color: PaletteColor?) -> Unit)? = null

    private var selectedParentId: ContainerId? = null
    private var selectedColor: PaletteColor? = null

    private lateinit var parentBtn: Button
    private lateinit var nameInput: EditText

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val context = requireContext()
        val palette = MainTabsThemeBridge.resolve(context)
        selectedParentId = initialParentId

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.background)
            setPadding(dp(20f).toInt(), dp(16f).toInt(), dp(20f).toInt(), dp(24f).toInt())
        }

        val titleView = TextView(context).apply {
            text = if (initialParentId != null) {
                getString(R.string.containers_create_subsection)
            } else {
                getString(R.string.containers_create_section)
            }
            setTextColor(palette.onSurface)
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(16f).toInt())
        }
        root.addView(titleView)

        // 1. Name
        val nameLabel = TextView(context).apply {
            text = getString(R.string.containers_name)
            setTextColor(palette.onSurfaceVariant)
            textSize = 12f
            setPadding(0, 0, 0, dp(4f).toInt())
        }
        root.addView(nameLabel)

        nameInput = EditText(context).apply {
            hint = getString(R.string.containers_name)
            setHintTextColor(MainTabsThemeBridge.withAlpha(palette.onSurfaceVariant, 0.6f))
            setTextColor(palette.onSurface)
            textSize = 15f
            val padY = dp(10f).toInt()
            val padX = dp(12f).toInt()
            setPadding(padX, padY, padX, padY)
            background = GradientDrawable().apply {
                setColor(palette.surface)
                cornerRadius = dp(8f)
                setStroke(dp(1f).toInt(), palette.border)
            }
        }
        root.addView(nameInput)

        // 2. Parent
        val parentLabel = TextView(context).apply {
            text = getString(R.string.containers_parent)
            setTextColor(palette.onSurfaceVariant)
            textSize = 12f
            setPadding(0, dp(14f).toInt(), 0, dp(4f).toInt())
        }
        root.addView(parentLabel)

        parentBtn = Button(context).apply {
            val pName = initialParentName ?: getString(R.string.containers_root_parent)
            text = "$pName ▾"
            setTextColor(palette.onSurface)
            textSize = 14f
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            val padY = dp(10f).toInt()
            val padX = dp(12f).toInt()
            setPadding(padX, padY, padX, padY)
            background = GradientDrawable().apply {
                setColor(palette.surface)
                cornerRadius = dp(8f)
                setStroke(dp(1f).toInt(), palette.border)
            }
            setOnClickListener {
                val picker = DrillDownMoveDialog().apply {
                    itemName = getString(R.string.containers_parent)
                    isParentPickerOnly = true
                    this.containerQueries = this@CreateContainerDialog.containerQueries
                    onTargetSelected = { targetId ->
                        selectedParentId = targetId
                        val newParentName = targetId?.let { containerQueries?.find(it)?.name }
                            ?: getString(R.string.containers_root_parent)
                        parentBtn.text = "$newParentName ▾"
                    }
                }
                picker.show(parentFragmentManager, "parent_picker")
            }
        }
        root.addView(parentBtn)

        // 3. Color
        val colorLabel = TextView(context).apply {
            text = getString(R.string.containers_color)
            setTextColor(palette.onSurfaceVariant)
            textSize = 12f
            setPadding(0, dp(14f).toInt(), 0, dp(8f).toInt())
        }
        root.addView(colorLabel)

        val colorScrollView = HorizontalScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
        }
        val colorRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(16f).toInt())
        }

        val swatchViews = mutableListOf<View>()
        for (i in 0 until 20) {
            val paletteColor = PaletteColor(i)
            val colorInt = root.currentTheme().color(paletteColor).toInt()

            val swatch = View(context).apply {
                val size = dp(32f).toInt()
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    setMargins(0, 0, dp(8f).toInt(), 0)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(colorInt)
                }
                setOnClickListener {
                    selectedColor = paletteColor
                    for ((idx, view) in swatchViews.withIndex()) {
                        val cInt = root.currentTheme().color(PaletteColor(idx)).toInt()
                        view.background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(cInt)
                            if (idx == i) {
                                setStroke(dp(3f).toInt(), Color.WHITE)
                            }
                        }
                    }
                }
            }
            swatchViews.add(swatch)
            colorRow.addView(swatch)
        }
        colorScrollView.addView(colorRow)
        root.addView(colorScrollView)

        // Actions
        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(8f).toInt(), 0, 0)
        }

        val cancelBtn = Button(context).apply {
            text = getString(R.string.containers_cancel)
            setTextColor(palette.onSurfaceVariant)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { dismiss() }
        }
        actions.addView(cancelBtn)

        val createBtn = Button(context).apply {
            text = getString(R.string.containers_create)
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(palette.accent)
                cornerRadius = dp(8f)
            }
            setOnClickListener {
                val name = nameInput.text.toString().trim()
                if (name.isBlank()) {
                    nameInput.error = getString(R.string.containers_name)
                    return@setOnClickListener
                }
                dismiss()
                onSave?.invoke(name, selectedParentId, selectedColor)
            }
        }
        val createLp = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            setMargins(dp(8f).toInt(), 0, 0, 0)
        }
        actions.addView(createBtn, createLp)

        root.addView(actions)

        return root
    }

    private fun dp(v: Float) = (v * resources.displayMetrics.density)
}
