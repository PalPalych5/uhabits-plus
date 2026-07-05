package org.isoron.uhabits.activities.common.dialogs

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.annotation.AttrRes
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.card.MaterialCardView
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.isoron.uhabits.R
import org.isoron.uhabits.utils.ColorUtils
import kotlin.math.max
import kotlin.math.roundToInt

class CustomColorBottomSheet : BottomSheetDialogFragment() {

    companion object {
        private const val ARG_COLOR = "initial_color"
        private const val ARG_THEME = "theme"
        private const val STATE_COLOR = "current_color"
        private const val STATE_TAB = "selected_tab"
        private const val STATE_HEX = "hex_value"
        private const val STATE_ERROR = "validation_error"
        private const val STATE_HUE = "current_hue"
        private const val STATE_SATURATION = "current_saturation"
        private const val STATE_VALUE = "current_value"

        fun newInstance(color: Int, theme: Int) = CustomColorBottomSheet().apply {
            arguments = Bundle().apply {
                putInt(ARG_COLOR, color)
                putInt(ARG_THEME, theme)
            }
        }
    }

    private var currentColor = ColorPickerUtils.DEFAULT_COLOR
    private var themeMode = ColorPickerDialog.THEME_LIGHT
    private var selectedTab = 0
    private var validationError: String? = null
    private var savedHex: String? = null
    private var updatingControls = false
    private var currentHue = 0f
    private var currentSaturation = 1f
    private var currentValue = 1f
    private lateinit var themedContext: Context

    private lateinit var preview: MaterialCardView
    private lateinit var previewValue: TextView
    private lateinit var tabHex: TextView
    private lateinit var tabHsv: TextView
    private lateinit var tabRgb: TextView
    private lateinit var hexPanel: View
    private lateinit var hsvPanel: View
    private lateinit var rgbPanel: View
    private lateinit var hexLayout: TextInputLayout
    private lateinit var hexInput: TextInputEditText
    private lateinit var cancelButton: TextView
    private lateinit var doneButton: Button
    private lateinit var hsvSliders: List<Slider>
    private lateinit var rgbSliders: List<Slider>
    private lateinit var hsvValues: List<TextView>
    private lateinit var rgbValues: List<TextView>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        themeMode = arguments?.getInt(ARG_THEME, ColorPickerDialog.THEME_LIGHT) ?: ColorPickerDialog.THEME_LIGHT
        setStyle(
            STYLE_NORMAL,
            when (themeMode) {
                ColorPickerDialog.THEME_AMOLED -> R.style.ColorPickerBottomSheetTheme_Amoled
                ColorPickerDialog.THEME_DARK -> R.style.ColorPickerBottomSheetTheme_Dark
                else -> R.style.ColorPickerBottomSheetTheme
            }
        )
        currentColor = savedInstanceState?.getInt(STATE_COLOR)
            ?: arguments?.getInt(ARG_COLOR, ColorPickerUtils.DEFAULT_COLOR)
            ?: ColorPickerUtils.DEFAULT_COLOR
        selectedTab = savedInstanceState?.getInt(STATE_TAB) ?: 0
        savedHex = savedInstanceState?.getString(STATE_HEX)
        validationError = savedInstanceState?.getString(STATE_ERROR)
        if (savedInstanceState != null) {
            currentHue = savedInstanceState.getFloat(STATE_HUE)
            currentSaturation = savedInstanceState.getFloat(STATE_SATURATION)
            currentValue = savedInstanceState.getFloat(STATE_VALUE)
        } else {
            syncCanonicalHsvFromColor(currentColor, preserveHue = false)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.custom_color_editor_bottom_sheet, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        themedContext = view.context
        bindViews(view)
        setupInsets(view)
        setupTabs()
        setupHexInput()
        setupSliders()

        view.findViewById<View>(R.id.custom_color_close).setOnClickListener { dismiss() }
        view.findViewById<View>(R.id.custom_color_cancel).setOnClickListener { dismiss() }
        doneButton.setOnClickListener { finishEditing() }

        val restoredError = validationError
        updateControls(currentColor, updateHex = savedHex == null)
        if (savedHex != null) {
            updatingControls = true
            hexInput.setText(savedHex)
            updatingControls = false
        }
        validationError = restoredError
        selectTab(selectedTab)
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        (dialog as? BottomSheetDialog)?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let { sheet ->
            sheet.setBackgroundColor(Color.TRANSPARENT)
            // The Material container keeps its rectangular elevation shadow even when
            // transparent, which shows up as a grey bar behind our rounded sheet.
            sheet.elevation = 0f
            BottomSheetBehavior.from(sheet).apply {
                state = BottomSheetBehavior.STATE_EXPANDED
                skipCollapsed = true
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_TAB, selectedTab)
        outState.putString(STATE_HEX, hexInput.text?.toString())
        outState.putString(STATE_ERROR, validationError)
        outState.putFloat(STATE_HUE, currentHue)
        outState.putFloat(STATE_SATURATION, currentSaturation)
        outState.putFloat(STATE_VALUE, currentValue)
    }

    private fun bindViews(view: View) {
        preview = view.findViewById(R.id.custom_color_preview)
        previewValue = view.findViewById(R.id.custom_color_preview_value)
        tabHex = view.findViewById(R.id.custom_color_tab_hex)
        tabHsv = view.findViewById(R.id.custom_color_tab_hsv)
        tabRgb = view.findViewById(R.id.custom_color_tab_rgb)
        hexPanel = view.findViewById(R.id.custom_color_hex_panel)
        hsvPanel = view.findViewById(R.id.custom_color_hsv_panel)
        rgbPanel = view.findViewById(R.id.custom_color_rgb_panel)
        hexLayout = view.findViewById(R.id.custom_color_hex_layout)
        hexInput = view.findViewById(R.id.custom_color_hex_input)
        cancelButton = view.findViewById(R.id.custom_color_cancel)
        doneButton = view.findViewById(R.id.custom_color_done)
        hsvSliders = listOf(
            view.findViewById(R.id.custom_color_hue),
            view.findViewById(R.id.custom_color_saturation),
            view.findViewById(R.id.custom_color_value)
        )
        rgbSliders = listOf(
            view.findViewById(R.id.custom_color_red),
            view.findViewById(R.id.custom_color_green),
            view.findViewById(R.id.custom_color_blue)
        )
        hsvValues = listOf(
            view.findViewById(R.id.custom_color_hue_value),
            view.findViewById(R.id.custom_color_saturation_value),
            view.findViewById(R.id.custom_color_value_value)
        )
        rgbValues = listOf(
            view.findViewById(R.id.custom_color_red_value),
            view.findViewById(R.id.custom_color_green_value),
            view.findViewById(R.id.custom_color_blue_value)
        )
    }

    private fun setupTabs() {
        tabHex.setOnClickListener { selectTab(0) }
        tabHsv.setOnClickListener { selectTab(1) }
        tabRgb.setOnClickListener { selectTab(2) }
    }

    private fun selectTab(position: Int) {
        selectedTab = position

        val activeBg = ContextCompat.getDrawable(themedContext, R.drawable.color_picker_segment_selected)
        val activeTextColor = resolveColor(R.attr.colorPickerOnSurface)
        val inactiveTextColor = resolveColor(R.attr.colorPickerOnSurfaceVariant)

        tabHex.background = if (position == 0) activeBg else null
        tabHsv.background = if (position == 1) activeBg else null
        tabRgb.background = if (position == 2) activeBg else null

        tabHex.setTextColor(if (position == 0) activeTextColor else inactiveTextColor)
        tabHsv.setTextColor(if (position == 1) activeTextColor else inactiveTextColor)
        tabRgb.setTextColor(if (position == 2) activeTextColor else inactiveTextColor)

        showPanel(position)
    }

    private fun showPanel(position: Int) {
        selectedTab = position
        hexPanel.visibility = if (position == 0) View.VISIBLE else View.GONE
        hsvPanel.visibility = if (position == 1) View.VISIBLE else View.GONE
        rgbPanel.visibility = if (position == 2) View.VISIBLE else View.GONE
    }

    private fun setupHexInput() {
        hexInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (updatingControls) return
                val parsed = ColorPickerUtils.parseHex(s?.toString().orEmpty())
                if (parsed == null) {
                    validationError = getString(R.string.custom_color_invalid_hex_detail)
                    hexLayout.error = validationError
                } else {
                    validationError = null
                    hexLayout.error = null
                    updateControls(parsed, updateHex = false)
                }
            }
        })
    }

    private fun setupSliders() {
        val normalThumb = resources.getDimensionPixelSize(R.dimen.color_picker_slider_thumb_radius)
        val pressedThumb = resources.getDimensionPixelSize(R.dimen.color_picker_slider_thumb_radius_pressed)

        hsvSliders.forEachIndexed { index, slider ->
            slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) {
                    slider.thumbRadius = pressedThumb
                }

                override fun onStopTrackingTouch(slider: Slider) {
                    slider.thumbRadius = normalThumb
                }
            })
            slider.addOnChangeListener { _, _, fromUser ->
                if (fromUser && !updatingControls) {
                    when (index) {
                        0 -> currentHue = slider.value.coerceIn(0f, 360f)
                        1 -> currentSaturation = (slider.value / 100f).coerceIn(0f, 1f)
                        2 -> currentValue = (slider.value / 100f).coerceIn(0f, 1f)
                    }
                    updateControls(
                        Color.HSVToColor(floatArrayOf(currentHue, currentSaturation, currentValue)),
                        updateHex = true,
                        updateHsvSliders = false,
                        preserveHue = true
                    )
                }
            }
        }
        rgbSliders.forEach { slider ->
            slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) {
                    slider.thumbRadius = pressedThumb
                }

                override fun onStopTrackingTouch(slider: Slider) {
                    slider.thumbRadius = normalThumb
                }
            })
            slider.addOnChangeListener { _, _, fromUser ->
                if (fromUser && !updatingControls) {
                    updateControls(
                        Color.rgb(
                            rgbSliders[0].value.roundToInt(),
                            rgbSliders[1].value.roundToInt(),
                            rgbSliders[2].value.roundToInt()
                        ),
                        updateHex = true,
                        updateHsvSliders = true,
                        preserveHue = true
                    )
                }
            }
        }
    }

    private fun updateControls(
        color: Int,
        updateHex: Boolean,
        updateHsvSliders: Boolean = true,
        preserveHue: Boolean = true
    ) {
        currentColor = color or Color.BLACK
        updatingControls = true

        if (updateHsvSliders) {
            syncCanonicalHsvFromColor(currentColor, preserveHue)
            hsvSliders[0].value = currentHue.coerceIn(0f, 360f)
            hsvSliders[1].value = (currentSaturation * 100f).coerceIn(0f, 100f)
            hsvSliders[2].value = (currentValue * 100f).coerceIn(0f, 100f)
        }
        rgbSliders[0].value = Color.red(currentColor).toFloat()
        rgbSliders[1].value = Color.green(currentColor).toFloat()
        rgbSliders[2].value = Color.blue(currentColor).toFloat()

        hsvValues[0].text = getString(R.string.custom_color_degrees, hsvSliders[0].value.roundToInt())
        hsvValues[1].text = getString(R.string.custom_color_percent, hsvSliders[1].value.roundToInt())
        hsvValues[2].text = getString(R.string.custom_color_percent, hsvSliders[2].value.roundToInt())
        rgbValues.forEachIndexed { index, textView -> textView.text = rgbSliders[index].value.roundToInt().toString() }

        val hex = ColorPickerUtils.toHex(currentColor)
        if (updateHex) hexInput.setText(hex)
        previewValue.text = hex
        preview.setCardBackgroundColor(currentColor)
        val activeColor = toneSliderColor(currentColor)
        val inactiveColor = resolveColor(R.attr.colorPickerSliderInactive)
        val thumbStrokeColor = resolveColor(R.attr.colorPickerSliderThumbStroke)
        val focusColor = resolveColor(R.attr.colorPickerSliderFocus)
        (hsvSliders + rgbSliders).forEach { slider ->
            slider.trackActiveTintList = ColorStateList.valueOf(activeColor)
            slider.trackInactiveTintList = ColorStateList.valueOf(inactiveColor)
            slider.thumbTintList = ColorStateList.valueOf(activeColor)
            slider.thumbStrokeColor = ColorStateList.valueOf(thumbStrokeColor)
            slider.haloTintList = ColorStateList.valueOf(focusColor)
        }
        validationError = null
        hexLayout.error = null
        updateDoneButton()
        updatingControls = false
    }

    private fun syncCanonicalHsvFromColor(color: Int, preserveHue: Boolean) {
        val hsv = FloatArray(3)
        Color.colorToHSV(color or Color.BLACK, hsv)
        if (!preserveHue || (hsv[1] > 0f && hsv[2] > 0f)) {
            currentHue = hsv[0].coerceIn(0f, 360f)
        }
        currentSaturation = hsv[1].coerceIn(0f, 1f)
        currentValue = hsv[2].coerceIn(0f, 1f)
    }

    private fun updateDoneButton() {
        styleCancelButton()
        val borderColor = resolveColor(R.attr.colorPickerBorder)
        val luminance = androidx.core.graphics.ColorUtils.calculateLuminance(currentColor)
        val textColor = if (luminance > 0.5) 0xFF1C1B1F.toInt() else Color.WHITE
        doneButton.setTextColor(textColor)
        doneButton.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        doneButton.background = createActionButtonBackground(
            fillColor = currentColor,
            strokeColor = borderColor,
            rippleColor = rippleColorFor(textColor)
        )
        doneButton.stateListAnimator = null
        doneButton.elevation = 0f
        doneButton.translationZ = 0f
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
        return if (themeMode == ColorPickerDialog.THEME_AMOLED) 0xFF171A20.toInt()
        else resolveColor(R.attr.colorPickerSurfaceVariant)
    }

    private fun actionButtonBorderColor(): Int {
        return if (themeMode == ColorPickerDialog.THEME_AMOLED) 0x33FFFFFF
        else resolveColor(R.attr.colorPickerBorder)
    }

    private fun toneSliderColor(color: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[1] = (hsv[1] * 0.9f).coerceIn(0f, 1f)
        hsv[2] = when (themeMode) {
            ColorPickerDialog.THEME_LIGHT -> (hsv[2] * 0.94f).coerceIn(0f, 1f)
            ColorPickerDialog.THEME_AMOLED -> max(hsv[2], 0.42f).let { (it + 0.05f).coerceIn(0f, 1f) }
            else -> max(hsv[2], 0.38f).let { (it + 0.04f).coerceIn(0f, 1f) }
        }

        val toned = Color.HSVToColor(hsv)
        val surface = resolveColor(R.attr.colorPickerSurface)
        return ColorUtils.mixColors(toned, surface, 0.92f)
    }

    private fun finishEditing() {
        val parsed = ColorPickerUtils.parseHex(hexInput.text?.toString().orEmpty())
        if (parsed == null || validationError != null) {
            validationError = getString(R.string.custom_color_invalid_hex_detail)
            selectTab(0)
            hexInput.requestFocus()
            return
        }
        parentFragmentManager.setFragmentResult(
            ColorPickerDialog.CUSTOM_COLOR_RESULT,
            Bundle().apply { putInt(ColorPickerDialog.CUSTOM_COLOR_VALUE, currentColor) }
        )
        dismiss()
    }

    private fun setupInsets(root: View) {
        val initialBottom = root.paddingBottom
        val initialStart = root.paddingLeft
        val initialEnd = root.paddingRight
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(
                left = initialStart + systemBars.left,
                right = initialEnd + systemBars.right,
                bottom = initialBottom + systemBars.bottom
            )
            insets
        }
    }

    private fun resolveColor(@AttrRes attribute: Int): Int {
        val value = TypedValue()
        val context = if (this::themedContext.isInitialized) themedContext else requireContext()
        context.theme.resolveAttribute(attribute, value, true)
        return if (value.resourceId != 0) ContextCompat.getColor(context, value.resourceId) else value.data
    }

    private fun createActionButtonBackground(fillColor: Int, strokeColor: Int, rippleColor: Int): RippleDrawable {
        val cornerRadius = resources.displayMetrics.density * 16f
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
