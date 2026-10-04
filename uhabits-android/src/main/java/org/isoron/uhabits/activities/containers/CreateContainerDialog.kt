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

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import org.isoron.platform.gui.toInt
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.common.icons.IconPack
import org.isoron.uhabits.activities.common.icons.IconPickerBottomSheet
import org.isoron.uhabits.activities.common.theme.MainTabsThemeBridge
import org.isoron.uhabits.core.containers.Container
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.containers.ContainerQueries
import org.isoron.uhabits.core.containers.IconRef
import org.isoron.uhabits.core.models.PaletteColor
import org.isoron.uhabits.utils.currentTheme

class CreateContainerDialog : BottomSheetDialogFragment() {

    var containerToEdit: Container? = null
    var initialParentId: ContainerId? = null
    var initialParentName: String? = null
    var containerQueries: ContainerQueries? = null
    var onSaveWithIcon: ((name: String, parentId: ContainerId?, color: PaletteColor?, icon: IconRef) -> Unit)? = null
    var onSave: ((name: String, parentId: ContainerId?, color: PaletteColor?) -> Unit)? = null

    private var selectedParentId: ContainerId? = null
    private var selectedColor: PaletteColor? = null
    private var selectedIcon: IconRef = IconRef.None

    private lateinit var nameInput: EditText
    private lateinit var previewTile: FrameLayout
    private lateinit var previewIconView: ImageView
    private lateinit var previewEmojiView: TextView
    private lateinit var previewTitleView: TextView
    private lateinit var previewLocationView: TextView

    private lateinit var iconRowValue: TextView
    private lateinit var iconRowTile: FrameLayout
    private lateinit var iconRowImageView: ImageView
    private lateinit var iconRowEmojiView: TextView

    private lateinit var parentLocationValue: TextView

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            if (bottomSheet != null) {
                val behavior = BottomSheetBehavior.from(bottomSheet)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
            }
        }
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val context = requireContext()
        val palette = MainTabsThemeBridge.resolve(context)

        // Initialize state
        val edit = containerToEdit
        if (edit != null) {
            selectedParentId = edit.parentId
            selectedColor = edit.color
            selectedIcon = IconRef.fromString(edit.icon)
        } else {
            selectedParentId = initialParentId
            selectedColor = PaletteColor(10) // default neutral/blue palette color
            selectedIcon = IconRef.None
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.surface)
            setPadding(dp(20f).toInt(), dp(12f).toInt(), dp(20f).toInt(), dp(24f).toInt())
        }

        // Top drag handle
        val handle = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(36f).toInt(), dp(4f).toInt()).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setMargins(0, 0, 0, dp(14f).toInt())
            }
            background = GradientDrawable().apply {
                setColor(MainTabsThemeBridge.withAlpha(palette.onSurfaceVariant, 0.35f))
                cornerRadius = dp(2f)
            }
        }
        root.addView(handle)

        // Dialog Title
        val isEdit = containerToEdit != null
        val titleText = when {
            isEdit -> getString(R.string.containers_edit_section)
            initialParentId != null -> getString(R.string.containers_create_subsection)
            else -> getString(R.string.containers_create_section)
        }
        val titleView = TextView(context).apply {
            text = titleText
            setTextColor(palette.onSurface)
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(12f).toInt())
        }
        root.addView(titleView)

        // Live Preview Card
        val previewCard = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f).toInt(), dp(10f).toInt(), dp(12f).toInt(), dp(10f).toInt())
            background = GradientDrawable().apply {
                setColor(palette.background)
                cornerRadius = dp(12f)
                setStroke(dp(1f).toInt(), palette.border)
            }
        }

        val previewTileSize = dp(42f).toInt()
        previewTile = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(previewTileSize, previewTileSize).apply {
                setMargins(0, 0, dp(12f).toInt(), 0)
            }
        }

        previewIconView = ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(dp(24f).toInt(), dp(24f).toInt(), Gravity.CENTER)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        previewEmojiView = TextView(context).apply {
            layoutParams = FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER)
            textSize = 20f
        }
        previewTile.addView(previewIconView)
        previewTile.addView(previewEmojiView)
        previewCard.addView(previewTile)

        val previewTextCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        previewTitleView = TextView(context).apply {
            text = edit?.name?.ifBlank { null } ?: getString(R.string.containers_name)
            setTextColor(palette.onSurface)
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
        }
        previewLocationView = TextView(context).apply {
            text = resolveParentName()
            setTextColor(palette.onSurfaceVariant)
            textSize = 12f
            maxLines = 1
        }
        previewTextCol.addView(previewTitleView)
        previewTextCol.addView(previewLocationView)
        previewCard.addView(previewTextCol)

        root.addView(previewCard)

        // Spacer
        root.addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(14f).toInt()) })

        // 1. Name Input
        val nameLabel = TextView(context).apply {
            text = getString(R.string.containers_name)
            setTextColor(palette.onSurfaceVariant)
            textSize = 12f
            setPadding(0, 0, 0, dp(4f).toInt())
        }
        root.addView(nameLabel)

        nameInput = EditText(context).apply {
            hint = getString(R.string.containers_name)
            setText(edit?.name ?: "")
            setSelection(text.length)
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
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    val currentText = s?.toString()?.trim()
                    previewTitleView.text = if (currentText.isNullOrBlank()) {
                        getString(R.string.containers_name)
                    } else {
                        currentText
                    }
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        root.addView(nameInput)

        // Spacer
        root.addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(12f).toInt()) })

        // 2. Icon Row Selector
        val iconRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f).toInt(), dp(10f).toInt(), dp(12f).toInt(), dp(10f).toInt())
            background = GradientDrawable().apply {
                setColor(palette.background)
                cornerRadius = dp(8f)
                setStroke(dp(1f).toInt(), palette.border)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                openIconPicker()
            }
        }

        val iconTileSmallSize = dp(32f).toInt()
        iconRowTile = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(iconTileSmallSize, iconTileSmallSize).apply {
                setMargins(0, 0, dp(10f).toInt(), 0)
            }
        }
        iconRowImageView = ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(dp(18f).toInt(), dp(18f).toInt(), Gravity.CENTER)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        iconRowEmojiView = TextView(context).apply {
            layoutParams = FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER)
            textSize = 15f
        }
        iconRowTile.addView(iconRowImageView)
        iconRowTile.addView(iconRowEmojiView)
        iconRow.addView(iconRowTile)

        val iconRowLabel = TextView(context).apply {
            text = getString(R.string.containers_icon)
            setTextColor(palette.onSurface)
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        iconRow.addView(iconRowLabel)

        iconRowValue = TextView(context).apply {
            text = resolveIconLabel()
            setTextColor(palette.onSurfaceVariant)
            textSize = 13f
        }
        iconRow.addView(iconRowValue)

        val iconRowChevron = TextView(context).apply {
            text = " ›"
            setTextColor(palette.onSurfaceVariant)
            textSize = 16f
        }
        iconRow.addView(iconRowChevron)

        root.addView(iconRow)

        // Spacer
        root.addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(12f).toInt()) })

        // 3. Location / Parent Row Selector
        val locationRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f).toInt(), dp(10f).toInt(), dp(12f).toInt(), dp(10f).toInt())
            background = GradientDrawable().apply {
                setColor(palette.background)
                cornerRadius = dp(8f)
                setStroke(dp(1f).toInt(), palette.border)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                openLocationPicker()
            }
        }

        val locationLabel = TextView(context).apply {
            text = getString(R.string.containers_location)
            setTextColor(palette.onSurface)
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        locationRow.addView(locationLabel)

        parentLocationValue = TextView(context).apply {
            text = resolveParentName()
            setTextColor(palette.onSurfaceVariant)
            textSize = 13f
            maxLines = 1
        }
        locationRow.addView(parentLocationValue)

        val locationChevron = TextView(context).apply {
            text = " ›"
            setTextColor(palette.onSurfaceVariant)
            textSize = 16f
        }
        locationRow.addView(locationChevron)

        root.addView(locationRow)

        // Spacer
        root.addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(14f).toInt()) })

        // 4. Color Palette Selector
        val colorLabel = TextView(context).apply {
            text = getString(R.string.containers_color)
            setTextColor(palette.onSurfaceVariant)
            textSize = 12f
            setPadding(0, 0, 0, dp(8f).toInt())
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
                    if (selectedColor == paletteColor) {
                        setStroke(dp(3f).toInt(), palette.onSurface)
                    }
                }
                setOnClickListener {
                    selectedColor = paletteColor
                    for ((idx, view) in swatchViews.withIndex()) {
                        val cInt = root.currentTheme().color(PaletteColor(idx)).toInt()
                        view.background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(cInt)
                            if (idx == i) {
                                setStroke(dp(3f).toInt(), palette.onSurface)
                            }
                        }
                    }
                    updateVisualTiles()
                }
            }
            swatchViews.add(swatch)
            colorRow.addView(swatch)
        }
        colorScrollView.addView(colorRow)
        root.addView(colorScrollView)

        // 5. Actions: Cancel and Save/Create
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

        val saveBtn = Button(context).apply {
            text = if (isEdit) getString(R.string.containers_save) else getString(R.string.containers_create)
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
                if (onSaveWithIcon != null) {
                    onSaveWithIcon?.invoke(name, selectedParentId, selectedColor, selectedIcon)
                } else {
                    onSave?.invoke(name, selectedParentId, selectedColor)
                }
            }
        }
        val saveLp = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            setMargins(dp(8f).toInt(), 0, 0, 0)
        }
        actions.addView(saveBtn, saveLp)

        root.addView(actions)

        // Initial visual updates
        updateVisualTiles()

        // Autofocus name input and show keyboard
        nameInput.post {
            nameInput.requestFocus()
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(nameInput, InputMethodManager.SHOW_IMPLICIT)
        }

        return root
    }

    private fun openIconPicker() {
        val ctx = context ?: return
        val colorInt = selectedColor?.let {
            view?.currentTheme()?.color(it)?.toInt()
        } ?: MainTabsThemeBridge.resolve(ctx).accent

        val picker = IconPickerBottomSheet().apply {
            previewColor = colorInt
            initialIconRef = selectedIcon
            onIconSelected = { ref ->
                selectedIcon = ref
                updateVisualTiles()
                iconRowValue.text = resolveIconLabel()
            }
        }
        picker.show(parentFragmentManager, "icon_picker_dialog")
    }

    private fun openLocationPicker() {
        val picker = DrillDownMoveDialog().apply {
            itemName = getString(R.string.containers_parent)
            isParentPickerOnly = true
            movingContainerId = containerToEdit?.id
            this.containerQueries = this@CreateContainerDialog.containerQueries
            onTargetSelected = { targetId ->
                selectedParentId = targetId
                val parentName = resolveParentName()
                parentLocationValue.text = parentName
                previewLocationView.text = parentName
            }
        }
        picker.show(parentFragmentManager, "parent_picker")
    }

    private fun resolveParentName(): String {
        return selectedParentId?.let { id ->
            containerQueries?.find(id)?.name
        } ?: getString(R.string.containers_root_parent)
    }

    private fun resolveIconLabel(): String {
        return when (val ref = selectedIcon) {
            is IconRef.Vector -> IconPack.getIcon(ref.key)?.labelRu ?: ref.key
            is IconRef.Emoji -> ref.value
            IconRef.None -> getString(R.string.containers_no_icon)
        }
    }

    private fun updateVisualTiles() {
        val view = view ?: return
        val context = view.context
        val palette = MainTabsThemeBridge.resolve(context)
        val activeColor = selectedColor?.let {
            view.currentTheme().color(it).toInt()
        } ?: palette.accent

        val tileBg = GradientDrawable().apply {
            setColor(MainTabsThemeBridge.withAlpha(activeColor, 0.14f))
            cornerRadius = dp(10f)
        }
        val tileBgSmall = GradientDrawable().apply {
            setColor(MainTabsThemeBridge.withAlpha(activeColor, 0.14f))
            cornerRadius = dp(8f)
        }

        previewTile.background = tileBg
        iconRowTile.background = tileBgSmall

        when (val ref = selectedIcon) {
            is IconRef.Vector -> {
                val res = IconPack.getDrawableRes(ref.key, IconPack.DEFAULT_CONTAINER_ICON_RES)
                previewIconView.visibility = View.VISIBLE
                previewEmojiView.visibility = View.GONE
                previewIconView.setImageDrawable(AppCompatResources.getDrawable(context, res))
                previewIconView.setColorFilter(activeColor)

                iconRowImageView.visibility = View.VISIBLE
                iconRowEmojiView.visibility = View.GONE
                iconRowImageView.setImageDrawable(AppCompatResources.getDrawable(context, res))
                iconRowImageView.setColorFilter(activeColor)
            }
            is IconRef.Emoji -> {
                previewIconView.visibility = View.GONE
                previewEmojiView.visibility = View.VISIBLE
                previewEmojiView.text = ref.value

                iconRowImageView.visibility = View.GONE
                iconRowEmojiView.visibility = View.VISIBLE
                iconRowEmojiView.text = ref.value
            }
            IconRef.None -> {
                val res = IconPack.DEFAULT_CONTAINER_ICON_RES
                previewIconView.visibility = View.VISIBLE
                previewEmojiView.visibility = View.GONE
                previewIconView.setImageDrawable(AppCompatResources.getDrawable(context, res))
                previewIconView.setColorFilter(activeColor)

                iconRowImageView.visibility = View.VISIBLE
                iconRowEmojiView.visibility = View.GONE
                iconRowImageView.setImageDrawable(AppCompatResources.getDrawable(context, res))
                iconRowImageView.setColorFilter(activeColor)
            }
        }
    }

    private fun dp(v: Float) = (v * resources.displayMetrics.density)
}
