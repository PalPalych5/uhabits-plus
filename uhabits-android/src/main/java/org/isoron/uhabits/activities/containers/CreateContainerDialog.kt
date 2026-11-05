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
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import org.isoron.platform.gui.toInt
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.common.dialogs.ColorPickerDialog
import org.isoron.uhabits.activities.common.icons.IconPack
import org.isoron.uhabits.activities.common.icons.IconPickerBottomSheet
import org.isoron.uhabits.activities.common.theme.MainTabsThemeBridge
import org.isoron.uhabits.core.containers.Container
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.containers.ContainerQueries
import org.isoron.uhabits.core.containers.IconRef
import org.isoron.uhabits.core.models.PaletteColor
import org.isoron.uhabits.core.ui.views.DarkTheme
import org.isoron.uhabits.core.ui.views.PureBlackTheme
import org.isoron.uhabits.utils.currentTheme

class CreateContainerDialog : BottomSheetDialogFragment() {

    private fun sectionString(id: Int, vararg args: Any): String {
        val context = requireContext()
        val config = android.content.res.Configuration(context.resources.configuration).apply {
            setLocale(org.isoron.uhabits.activities.tasks.TaskDueDisplayFormatter.selectedLocale(context))
        }
        return context.createConfigurationContext(config).resources.getString(id, *args)
    }

    var containerToEdit: Container? = null
    var initialParentId: ContainerId? = null
    var initialParentName: String? = null
    var containerQueries: ContainerQueries? = null
    var onSaveWithIcon: ((name: String, parentId: ContainerId?, color: PaletteColor?, icon: IconRef) -> Unit)? = null
    var onSave: ((name: String, parentId: ContainerId?, color: PaletteColor?) -> Unit)? = null

    private var selectedParentId: ContainerId? = null
    private var selectedColor: PaletteColor? = null
    private var selectedIcon: IconRef = IconRef.None

    private var rootView: View? = null
    private lateinit var nameInput: EditText
    private lateinit var nameErrorText: TextView

    private lateinit var iconBtn: FrameLayout
    private lateinit var iconImageView: ImageView
    private lateinit var iconEmojiView: TextView

    private lateinit var parentLocationValue: TextView
    private val swatchViews = mutableListOf<View>()
    private lateinit var customSwatchView: FrameLayout
    private lateinit var customSwatchInner: View
    private lateinit var customSwatchIcon: ImageView

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            if (bottomSheet != null) {
                bottomSheet.setBackgroundColor(Color.TRANSPARENT)
                bottomSheet.elevation = 0f
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
            background = GradientDrawable().apply {
                setColor(palette.surface)
                cornerRadii = floatArrayOf(dp(20f), dp(20f), dp(20f), dp(20f), 0f, 0f, 0f, 0f)
            }
            setPadding(dp(20f).toInt(), dp(12f).toInt(), dp(20f).toInt(), dp(24f).toInt())
        }
        rootView = root

        // 1. Top Drag Handle
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

        // 2. Dialog Title
        val isEdit = containerToEdit != null
        val titleText = when {
            isEdit -> sectionString(R.string.containers_edit_section)
            initialParentId != null -> sectionString(R.string.containers_create_subsection)
            else -> sectionString(R.string.containers_create_section)
        }
        val titleView = TextView(context).apply {
            text = titleText
            setTextColor(palette.onSurface)
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(14f).toInt())
        }
        root.addView(titleView)

        // 3. Name Input Row with Clickable Icon on the Left
        val nameRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                bottomMargin = dp(6f).toInt()
            }
        }

        // Clickable Icon Button on the left
        val iconTileSize = dp(46f).toInt()
        iconBtn = FrameLayout(context).apply {
            tag = "container_icon_btn"
            layoutParams = LinearLayout.LayoutParams(iconTileSize, iconTileSize).apply {
                marginEnd = dp(12f).toInt()
            }
            clipToOutline = true
            val ripple = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
            foreground = AppCompatResources.getDrawable(context, ripple.resourceId)
            isClickable = true
            isFocusable = true
            contentDescription = sectionString(R.string.section_select_icon)
            setOnClickListener { openIconPicker() }
        }

        iconImageView = ImageView(context).apply {
            val s = dp(24f).toInt()
            layoutParams = FrameLayout.LayoutParams(s, s, Gravity.CENTER)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        iconEmojiView = TextView(context).apply {
            layoutParams = FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER)
            textSize = 20f
        }
        iconBtn.addView(iconImageView)
        iconBtn.addView(iconEmojiView)
        nameRow.addView(iconBtn)

        // Name text input container with styled rounded outline/fill
        val inputContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            background = GradientDrawable().apply {
                setColor(MainTabsThemeBridge.withAlpha(palette.onSurface, 0.05f))
                cornerRadius = dp(12f)
                setStroke(dp(1f).toInt(), MainTabsThemeBridge.withAlpha(palette.onSurface, 0.12f))
            }
            setPadding(dp(14f).toInt(), dp(4f).toInt(), dp(14f).toInt(), dp(4f).toInt())
        }

        nameInput = EditText(context).apply {
            tag = "container_name_input"
            hint = sectionString(R.string.containers_name)
            setText(edit?.name ?: "")
            setSelection(text.length)
            setHintTextColor(MainTabsThemeBridge.withAlpha(palette.onSurfaceVariant, 0.55f))
            setTextColor(palette.onSurface)
            textSize = 15f
            minHeight = dp(42f).toInt()
            setBackgroundColor(Color.TRANSPARENT)
            setSingleLine(true)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    val currentText = s?.toString()?.trim()
                    if (!currentText.isNullOrBlank()) {
                        nameErrorText.visibility = View.GONE
                    }
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        inputContainer.addView(nameInput)
        nameRow.addView(inputContainer)
        root.addView(nameRow)

        nameErrorText = TextView(context).apply {
            text = sectionString(R.string.containers_name)
            setTextColor(ContextCompat.getColor(context, R.color.red_600))
            textSize = 12f
            visibility = View.GONE
            setPadding(dp(58f).toInt(), 0, 0, dp(6f).toInt())
        }
        root.addView(nameErrorText)

        // 4. Compact Location / Parent Row
        val locationRow = LinearLayout(context).apply {
            tag = "container_location_row"
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(44f).toInt()).apply {
                topMargin = dp(6f).toInt()
                bottomMargin = dp(16f).toInt()
            }
            background = GradientDrawable().apply {
                setColor(MainTabsThemeBridge.withAlpha(palette.onSurface, 0.04f))
                cornerRadius = dp(10f)
            }
            setPadding(dp(12f).toInt(), 0, dp(12f).toInt(), 0)
            val ripple = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
            setBackgroundResource(ripple.resourceId)
            isClickable = true
            isFocusable = true
            setOnClickListener { openLocationPicker() }
        }

        val locationIcon = ImageView(context).apply {
            val iconSize = dp(18f).toInt()
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                marginEnd = dp(10f).toInt()
            }
            setImageResource(R.drawable.ic_tabler_hierarchy_2)
            setColorFilter(palette.onSurfaceVariant)
        }
        locationRow.addView(locationIcon)

        val locationLabel = TextView(context).apply {
            text = "${sectionString(R.string.section_location_label)}: "
            setTextColor(palette.onSurfaceVariant)
            textSize = 13f
        }
        locationRow.addView(locationLabel)

        parentLocationValue = TextView(context).apply {
            text = resolveParentName()
            setTextColor(palette.onSurface)
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        locationRow.addView(parentLocationValue)

        val locationChevron = ImageView(context).apply {
            val iconSize = dp(16f).toInt()
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
            setImageResource(R.drawable.ic_tabler_chevron_right)
            setColorFilter(palette.onSurfaceVariant)
        }
        locationRow.addView(locationChevron)
        root.addView(locationRow)

        // 5. Color Section (Header: "Цвет")
        val colorLabel = TextView(context).apply {
            text = sectionString(R.string.section_color)
            setTextColor(palette.onSurfaceVariant)
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(8f).toInt())
        }
        root.addView(colorLabel)

        val colorScrollView = HorizontalScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                bottomMargin = dp(16f).toInt()
            }
        }
        val colorRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // Custom Color Button on the start of palette row
        customSwatchView = FrameLayout(context).apply {
            tag = "container_custom_color_btn"
            contentDescription = sectionString(R.string.section_custom_color)
            val touchSize = dp(44f).toInt()
            layoutParams = LinearLayout.LayoutParams(touchSize, touchSize).apply {
                marginEnd = dp(6f).toInt()
            }
            val ripple = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true)
            setBackgroundResource(ripple.resourceId)
            isClickable = true
            isFocusable = true
            setOnClickListener { openColorPickerDialog() }
        }

        val customInnerSize = dp(32f).toInt()
        customSwatchInner = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(customInnerSize, customInnerSize, Gravity.CENTER)
        }
        customSwatchIcon = ImageView(context).apply {
            val iconSize = dp(18f).toInt()
            layoutParams = FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER)
            setImageResource(R.drawable.ic_tabler_palette)
        }
        customSwatchView.addView(customSwatchInner)
        customSwatchView.addView(customSwatchIcon)
        colorRow.addView(customSwatchView)

        // Palette presets (0..19)
        swatchViews.clear()
        for (i in 0 until 20) {
            val paletteColor = PaletteColor(i)
            val colorInt = root.currentTheme().color(paletteColor).toInt()

            val swatch = View(context).apply {
                val size = dp(32f).toInt()
                layoutParams = FrameLayout.LayoutParams(size, size, Gravity.CENTER)
                background = createSwatchDrawable(colorInt, selectedColor == paletteColor, palette.onSurface)
            }
            val target = FrameLayout(context).apply {
                tag = "container_color_swatch_$i"
                layoutParams = LinearLayout.LayoutParams(dp(44f).toInt(), dp(44f).toInt())
                contentDescription = "${sectionString(R.string.section_color)} ${i + 1}"
                addView(swatch)
                val ripple = android.util.TypedValue()
                context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true)
                setBackgroundResource(ripple.resourceId)
                setOnClickListener {
                    selectedColor = paletteColor
                    updateColorSwatches(palette.onSurface)
                    updateVisualTiles()
                }
            }
            swatchViews.add(swatch)
            colorRow.addView(target)
        }

        colorScrollView.addView(colorRow)
        root.addView(colorScrollView)

        // 6. Action Buttons: Cancel and Create / Save
        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, dp(4f).toInt(), 0, 0)
        }

        val cancelBtn = Button(context).apply {
            tag = "container_cancel_btn"
            text = sectionString(R.string.containers_cancel)
            setTextColor(palette.onSurfaceVariant)
            textSize = 14f
            minHeight = dp(44f).toInt()
            setBackgroundColor(Color.TRANSPARENT)
            val ripple = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
            setBackgroundResource(ripple.resourceId)
            setOnClickListener { dismiss() }
        }
        actions.addView(cancelBtn)

        val saveBtn = Button(context).apply {
            tag = "container_save_btn"
            text = if (isEdit) sectionString(R.string.containers_save) else sectionString(R.string.containers_create)
            setTextColor(Color.WHITE)
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            minHeight = dp(44f).toInt()
            background = GradientDrawable().apply {
                setColor(palette.accent)
                cornerRadius = dp(12f)
            }
            setPadding(dp(20f).toInt(), 0, dp(20f).toInt(), 0)
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                marginStart = dp(8f).toInt()
            }
            setOnClickListener {
                val name = nameInput.text.toString().trim()
                if (name.isBlank()) {
                    nameErrorText.visibility = View.VISIBLE
                    nameInput.requestFocus()
                    return@setOnClickListener
                }
                if (onSaveWithIcon != null) {
                    onSaveWithIcon?.invoke(name, selectedParentId, selectedColor, selectedIcon)
                } else {
                    onSave?.invoke(name, selectedParentId, selectedColor)
                }
                dismiss()
            }
        }
        actions.addView(saveBtn)
        root.addView(actions)

        updateVisualTiles()
        updateColorSwatches(palette.onSurface)

        // Auto request focus on name input
        nameInput.post {
            nameInput.requestFocus()
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(nameInput, InputMethodManager.SHOW_IMPLICIT)
        }

        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val palette = MainTabsThemeBridge.resolve(requireContext())
        updateVisualTiles()
        updateColorSwatches(palette.onSurface)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        rootView = null
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    private fun createSwatchDrawable(color: Int, isSelected: Boolean, onSurface: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            if (isSelected) {
                setStroke(dp(3f).toInt(), onSurface)
            }
        }
    }

    private fun updateColorSwatches(onSurface: Int) {
        val v = view ?: rootView ?: return
        for (i in 0 until 20) {
            val paletteColor = PaletteColor(i)
            val colorInt = v.currentTheme().color(paletteColor).toInt()
            if (i < swatchViews.size) {
                swatchViews[i].background = createSwatchDrawable(
                    color = colorInt,
                    isSelected = selectedColor == paletteColor,
                    onSurface = onSurface
                )
            }
        }

        val isCustom = selectedColor != null && selectedColor!!.paletteIndex !in 0..19
        val activeColor = selectedColor?.let { v.currentTheme().color(it).toInt() }
        val palette = MainTabsThemeBridge.resolve(requireContext())

        customSwatchInner.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            if (isCustom && activeColor != null) {
                setColor(activeColor)
                setStroke(dp(2f).toInt(), onSurface)
            } else {
                setColor(MainTabsThemeBridge.withAlpha(palette.onSurface, 0.08f))
                setStroke(dp(1f).toInt(), MainTabsThemeBridge.withAlpha(palette.onSurface, 0.2f))
            }
        }
        val iconTint = if (isCustom && activeColor != null) {
            getContrastColor(activeColor)
        } else {
            palette.onSurfaceVariant
        }
        customSwatchIcon.setColorFilter(iconTint)
    }

    private fun getContrastColor(color: Int): Int {
        val luminance = androidx.core.graphics.ColorUtils.calculateLuminance(color)
        return if (luminance > 0.5) 0xFF1C1B1F.toInt() else Color.WHITE
    }

    fun openColorPickerDialog() {
        val ctx = context ?: return
        val v = view ?: rootView
        val currentTheme = v?.currentTheme()
        val themeMode = when (currentTheme) {
            is PureBlackTheme -> ColorPickerDialog.THEME_AMOLED
            is DarkTheme -> ColorPickerDialog.THEME_DARK
            else -> ColorPickerDialog.THEME_LIGHT
        }

        val colorInt = selectedColor?.let {
            v?.currentTheme()?.color(it)?.toInt()
        } ?: MainTabsThemeBridge.resolve(ctx).accent

        val dialog = ColorPickerDialog.newInstance(
            initialColor = colorInt,
            defaultColor = colorInt,
            previewName = nameInput.text.toString().trim().ifBlank { sectionString(R.string.containers_name) },
            theme = themeMode
        )
        dialog.setListener { picked ->
            selectedColor = picked
            val palette = MainTabsThemeBridge.resolve(ctx)
            updateColorSwatches(palette.onSurface)
            updateVisualTiles()
        }
        dialog.show(parentFragmentManager, "container_color_picker")
    }

    private fun openIconPicker() {
        val ctx = context ?: return
        val v = view ?: rootView
        val colorInt = selectedColor?.let {
            v?.currentTheme()?.color(it)?.toInt()
        } ?: MainTabsThemeBridge.resolve(ctx).accent

        val picker = IconPickerBottomSheet().apply {
            previewColor = colorInt
            initialIconRef = this@CreateContainerDialog.selectedIcon
            onIconSelected = { iconRef ->
                this@CreateContainerDialog.selectedIcon = iconRef
                updateVisualTiles()
            }
        }
        picker.show(parentFragmentManager, "container_icon_picker")
    }

    private fun openLocationPicker() {
        val picker = DrillDownMoveDialog().apply {
            itemName = sectionString(R.string.containers_parent)
            isParentPickerOnly = true
            movingContainerId = containerToEdit?.id
            this.containerQueries = this@CreateContainerDialog.containerQueries
            onTargetSelected = { targetId ->
                selectedParentId = targetId
                parentLocationValue.text = resolveParentName()
            }
        }
        picker.show(parentFragmentManager, "parent_picker")
    }

    private fun resolveParentName(): String {
        return selectedParentId?.let { id ->
            containerQueries?.find(id)?.name
        } ?: sectionString(R.string.containers_root_parent)
    }

    private fun updateVisualTiles() {
        val context = context ?: return
        val v = view ?: rootView ?: return
        val palette = MainTabsThemeBridge.resolve(context)
        val activeColor = selectedColor?.let {
            v.currentTheme().color(it).toInt()
        } ?: palette.accent

        iconBtn.background = GradientDrawable().apply {
            setColor(MainTabsThemeBridge.withAlpha(activeColor, 0.16f))
            cornerRadius = dp(12f)
            setStroke(dp(1.5f).toInt(), MainTabsThemeBridge.withAlpha(activeColor, 0.35f))
        }

        when (val ref = selectedIcon) {
            is IconRef.Vector -> {
                val res = IconPack.getDrawableRes(ref.key, IconPack.DEFAULT_CONTAINER_ICON_RES)
                iconImageView.visibility = View.VISIBLE
                iconEmojiView.visibility = View.GONE
                iconImageView.setImageDrawable(AppCompatResources.getDrawable(context, res))
                iconImageView.setColorFilter(activeColor)
            }
            is IconRef.Emoji -> {
                iconImageView.visibility = View.GONE
                iconEmojiView.visibility = View.VISIBLE
                iconEmojiView.text = ref.value
            }
            IconRef.None -> {
                val res = IconPack.DEFAULT_CONTAINER_ICON_RES
                iconImageView.visibility = View.VISIBLE
                iconEmojiView.visibility = View.GONE
                iconImageView.setImageDrawable(AppCompatResources.getDrawable(context, res))
                iconImageView.setColorFilter(activeColor)
            }
        }
    }
}
