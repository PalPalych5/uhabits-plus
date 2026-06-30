/*
 * Copyright (C) 2016-2026 Álinson Santos Xavier <git@axavier.org>
 *
 * This file is part of Loop Habit Tracker.
 *
 * Loop Habit Tracker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 */
package org.isoron.uhabits.activities.common.dialogs

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.TextView
import androidx.annotation.AttrRes
import androidx.appcompat.app.AppCompatDialogFragment
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import org.isoron.uhabits.R
import org.isoron.uhabits.core.ui.callbacks.OnColorPickedCallback
import org.isoron.uhabits.utils.toPaletteColor
import kotlin.math.max

class ColorPickerDialog : AppCompatDialogFragment() {

    companion object {
        const val SIZE_LARGE = 1
        const val SIZE_SMALL = 2

        const val THEME_LIGHT = 0
        const val THEME_DARK = 1
        const val THEME_AMOLED = 2

        private const val ARG_INITIAL_COLOR = "initial_color"
        private const val ARG_DEFAULT_COLOR = "default_color"
        private const val ARG_PREVIEW_NAME = "preview_name"
        private const val ARG_THEME = "theme"
        private const val STATE_INITIAL_COLOR = "state_initial_color"
        private const val STATE_DEFAULT_COLOR = "state_default_color"
        private const val STATE_DRAFT_COLOR = "state_draft_color"
        private const val STATE_SOURCE = "state_source"
        internal const val CUSTOM_COLOR_RESULT = "custom_color_result"
        internal const val CUSTOM_COLOR_VALUE = "custom_color_value"

        fun newInstance(
            initialColor: Int,
            defaultColor: Int,
            previewName: String?,
            theme: Int
        ) = ColorPickerDialog().apply {
            arguments = Bundle().apply {
                putInt(ARG_INITIAL_COLOR, initialColor)
                putInt(ARG_DEFAULT_COLOR, defaultColor)
                putString(ARG_PREVIEW_NAME, previewName)
                putInt(ARG_THEME, theme)
            }
        }
    }

    private enum class ColorSource { PRESET, CUSTOM, DEFAULT }

    private var listener: OnColorPickedCallback? = null
    private var initialColor = ColorPickerUtils.DEFAULT_COLOR
    private var defaultColor = ColorPickerUtils.DEFAULT_COLOR
    private var draftColor = ColorPickerUtils.DEFAULT_COLOR
    private var themeMode = THEME_LIGHT
    private var source = ColorSource.DEFAULT
    private var pendingDraftColor: Int? = null
    private val isDirty: Boolean get() = draftColor != initialColor
    private lateinit var themedContext: Context

    private lateinit var previewSwatch: ColorSwatchView
    private lateinit var customSwatch: ColorSwatchView
    private lateinit var cancelButton: TextView
    private lateinit var applyButton: TextView
    private lateinit var swatches: List<ColorSwatchView>

    fun setListener(callback: OnColorPickedCallback) {
        listener = callback
    }

    fun snapshotInitialColor(): Int = initialColor

    fun snapshotDraftColor(): Int = draftColor

    fun restoreDraftColor(color: Int) {
        pendingDraftColor = color
        draftColor = color
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        themeMode = arguments?.getInt(ARG_THEME, THEME_LIGHT) ?: THEME_LIGHT
        setStyle(
            STYLE_NORMAL,
            when (themeMode) {
                THEME_AMOLED -> R.style.ColorPickerFullScreenTheme_Amoled
                THEME_DARK -> R.style.ColorPickerFullScreenTheme_Dark
                else -> R.style.ColorPickerFullScreenTheme
            }
        )

        initialColor = savedInstanceState?.getInt(STATE_INITIAL_COLOR)
            ?: arguments?.getInt(ARG_INITIAL_COLOR, ColorPickerUtils.DEFAULT_COLOR)
            ?: ColorPickerUtils.DEFAULT_COLOR
        defaultColor = savedInstanceState?.getInt(STATE_DEFAULT_COLOR)
            ?: arguments?.getInt(ARG_DEFAULT_COLOR, ColorPickerUtils.DEFAULT_COLOR)
            ?: ColorPickerUtils.DEFAULT_COLOR
        draftColor = savedInstanceState?.getInt(STATE_DRAFT_COLOR) ?: pendingDraftColor ?: initialColor
        source = savedInstanceState?.getString(STATE_SOURCE)?.let(ColorSource::valueOf)
            ?: when {
                draftColor == defaultColor -> ColorSource.DEFAULT
                draftColor in ColorPickerUtils.presetColors -> ColorSource.PRESET
                else -> ColorSource.CUSTOM
            }

        childFragmentManager.setFragmentResultListener(CUSTOM_COLOR_RESULT, this) { _, result ->
            updateDraft(result.getInt(CUSTOM_COLOR_VALUE), ColorSource.CUSTOM)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.color_picker_fullscreen, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        themedContext = view.context

        val toolbar = view.findViewById<Toolbar>(R.id.toolbar)
        val actions = view.findViewById<View>(R.id.color_picker_actions)
        previewSwatch = view.findViewById(R.id.color_picker_preview_swatch)
        customSwatch = view.findViewById(R.id.custom_color_preview)
        cancelButton = view.findViewById(R.id.color_picker_cancel)
        applyButton = view.findViewById(R.id.color_picker_apply)

        toolbar.setNavigationOnClickListener { dismiss() }
        toolbar.navigationContentDescription = getString(R.string.color_picker_back)
        view.findViewById<TextView>(R.id.color_picker_preview_name).text =
            arguments?.getString(ARG_PREVIEW_NAME)
                ?.takeIf { it.isNotBlank() }
                ?: getString(R.string.color_picker_habit_preview_fallback)

        setupInsets(view, toolbar, actions)
        setupPalette(view.findViewById(R.id.color_picker_palette))

        view.findViewById<View>(R.id.custom_color_row).setOnClickListener {
            CustomColorBottomSheet.newInstance(draftColor, arguments?.getInt(ARG_THEME, THEME_LIGHT) ?: THEME_LIGHT)
                .show(childFragmentManager, "customColorPicker")
        }
        view.findViewById<View>(R.id.color_picker_reset).setOnClickListener {
            updateDraft(defaultColor, ColorSource.DEFAULT)
        }
        cancelButton.setOnClickListener { dismiss() }
        applyButton.setOnClickListener {
            listener?.onColorPicked(draftColor.toPaletteColor(requireContext()))
            dismiss()
        }

        updateDraft(draftColor, source)
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.let { window ->
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val lightBars = arguments?.getInt(ARG_THEME, THEME_LIGHT) == THEME_LIGHT
            WindowInsetsControllerCompat(window, window.decorView).apply {
                isAppearanceLightStatusBars = lightBars
                isAppearanceLightNavigationBars = lightBars
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_INITIAL_COLOR, initialColor)
        outState.putInt(STATE_DEFAULT_COLOR, defaultColor)
        outState.putInt(STATE_DRAFT_COLOR, draftColor)
        outState.putString(STATE_SOURCE, source.name)
    }

    private fun setupPalette(grid: GridLayout) {
        val size = resources.getDimensionPixelSize(R.dimen.color_picker_swatch_touch_size)
        swatches = ColorPickerUtils.presetColors.mapIndexed { index, color ->
            ColorSwatchView(requireContext()).apply {
                swatchColor = color
                layoutParams = GridLayout.LayoutParams(
                    GridLayout.spec(index / 6),
                    GridLayout.spec(index % 6, 1f)
                ).apply {
                    width = 0
                    height = size
                }
                setOnClickListener { updateDraft(color, ColorSource.PRESET) }
            }.also(grid::addView)
        }
    }

    private fun updateDraft(color: Int, newSource: ColorSource) {
        if (!this::previewSwatch.isInitialized) {
            draftColor = color
            source = newSource
            return
        }
        draftColor = color or Color.BLACK
        source = newSource

        previewSwatch.apply {
            swatchColor = draftColor
            showSelectionRing = false
            showCheckmark = true
            isSelected = true
            contentDescription = getString(R.string.color_picker_preview_description, ColorPickerUtils.toHex(draftColor))
        }
        customSwatch.apply {
            swatchColor = draftColor
            val customIsSelected = source == ColorSource.CUSTOM
            showSpectrum = !customIsSelected
            showSelectionRing = customIsSelected
            showCheckmark = customIsSelected
            isSelected = customIsSelected
        }
        swatches.forEach { swatch ->
            swatch.isSelected = source != ColorSource.CUSTOM && swatch.swatchColor == draftColor
            swatch.contentDescription = getString(
                if (swatch.isSelected) R.string.color_picker_swatch_selected else R.string.color_picker_swatch,
                ColorPickerUtils.toHex(swatch.swatchColor)
            )
        }
        updateApplyButton()
        applyButton.contentDescription = getString(
            if (isDirty) R.string.color_picker_apply_dirty else R.string.color_picker_apply
        )
    }

    private fun updateApplyButton() {
        styleCancelButton()
        val borderColor = resolveColor(R.attr.colorPickerBorder)
        val luminance = androidx.core.graphics.ColorUtils.calculateLuminance(draftColor)
        val textColor = if (luminance > 0.5) 0xFF1C1B1F.toInt() else Color.WHITE
        applyButton.setTextColor(textColor)
        applyButton.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        applyButton.background = createActionButtonBackground(
            fillColor = draftColor,
            strokeColor = borderColor,
            rippleColor = rippleColorFor(textColor)
        )
        applyButton.stateListAnimator = null
        applyButton.elevation = 0f
        applyButton.translationZ = 0f
    }

    private fun styleCancelButton() {
        val surfaceColor = actionButtonSurfaceColor()
        val borderColor = actionButtonBorderColor()
        val textColor = resolveColor(R.attr.colorPickerOnSurface)
        cancelButton.alpha = 1f
        cancelButton.isEnabled = true
        cancelButton.isClickable = true
        cancelButton.isFocusable = true
        cancelButton.text = getString(R.string.color_picker_cancel)
        cancelButton.setTextColor(textColor)
        cancelButton.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        ViewCompat.setBackgroundTintList(cancelButton, null)
        cancelButton.background = createActionButtonBackground(
            fillColor = surfaceColor,
            strokeColor = borderColor,
            rippleColor = rippleColorFor(textColor)
        )
        cancelButton.elevation = 0f
        cancelButton.translationZ = 0f
        cancelButton.stateListAnimator = null
    }

    private fun actionButtonSurfaceColor(): Int {
        return if (themeMode == THEME_AMOLED) 0xFF171A20.toInt()
        else resolveColor(R.attr.colorPickerSurfaceVariant)
    }

    private fun actionButtonBorderColor(): Int {
        return if (themeMode == THEME_AMOLED) 0x33FFFFFF
        else resolveColor(R.attr.colorPickerBorder)
    }

    private fun setupInsets(root: View, toolbar: View, actions: View) {
        val toolbarTop = toolbar.paddingTop
        val actionsBottom = actions.paddingBottom
        val rootStart = root.paddingLeft
        val rootEnd = root.paddingRight
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, windowInsets ->
            val systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val cutout = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val safe = Insets.max(systemBars, cutout)
            root.updatePadding(left = rootStart + safe.left, right = rootEnd + safe.right)
            toolbar.updatePadding(top = toolbarTop + safe.top)
            actions.updatePadding(bottom = actionsBottom + safe.bottom)
            windowInsets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun resolveColor(@AttrRes attribute: Int): Int {
        val value = TypedValue()
        val context = if (this::themedContext.isInitialized) themedContext else requireContext()
        context.theme.resolveAttribute(attribute, value, true)
        return if (value.resourceId != 0) ContextCompat.getColor(context, value.resourceId) else value.data
    }

    private fun createActionButtonBackground(fillColor: Int, strokeColor: Int, rippleColor: Int): RippleDrawable {
        val cornerRadius = resources.getDimension(R.dimen.color_picker_action_radius)
        val strokeWidth = max(1, resources.getDimensionPixelSize(R.dimen.color_picker_action_stroke))
        val content = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            this.cornerRadius = cornerRadius
            setColor(fillColor)
            setStroke(strokeWidth, strokeColor)
        }
        val mask = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            this.cornerRadius = cornerRadius
            setColor(Color.WHITE)
        }
        return RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask)
    }

    private fun rippleColorFor(baseColor: Int): Int {
        return androidx.core.graphics.ColorUtils.setAlphaComponent(baseColor, 24)
    }
}
