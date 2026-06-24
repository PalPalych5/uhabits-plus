package org.isoron.uhabits.activities.common.dialogs

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import androidx.annotation.AttrRes
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.slider.Slider
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.isoron.uhabits.R
import kotlin.math.roundToInt

class CustomColorBottomSheet : BottomSheetDialogFragment() {

    companion object {
        private const val ARG_COLOR = "initial_color"
        private const val ARG_THEME = "theme"
        private const val STATE_COLOR = "current_color"
        private const val STATE_TAB = "selected_tab"
        private const val STATE_HEX = "hex_value"
        private const val STATE_ERROR = "validation_error"

        fun newInstance(color: Int, theme: Int) = CustomColorBottomSheet().apply {
            arguments = Bundle().apply {
                putInt(ARG_COLOR, color)
                putInt(ARG_THEME, theme)
            }
        }
    }

    private var currentColor = ColorPickerUtils.DEFAULT_COLOR
    private var selectedTab = 0
    private var validationError: String? = null
    private var savedHex: String? = null
    private var updatingControls = false

    private lateinit var preview: MaterialCardView
    private lateinit var previewValue: TextView
    private lateinit var tabs: TabLayout
    private lateinit var hexPanel: View
    private lateinit var hsvPanel: View
    private lateinit var rgbPanel: View
    private lateinit var hexLayout: TextInputLayout
    private lateinit var hexInput: TextInputEditText
    private lateinit var doneButton: MaterialButton
    private lateinit var hsvSliders: List<Slider>
    private lateinit var rgbSliders: List<Slider>
    private lateinit var hsvValues: List<TextView>
    private lateinit var rgbValues: List<TextView>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(
            STYLE_NORMAL,
            when (arguments?.getInt(ARG_THEME, ColorPickerDialog.THEME_LIGHT)) {
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
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.custom_color_editor_bottom_sheet, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
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
        hexLayout.error = validationError
        tabs.getTabAt(selectedTab)?.select()
        showPanel(selectedTab)
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
        outState.putInt(STATE_COLOR, currentColor)
        outState.putInt(STATE_TAB, tabs.selectedTabPosition.coerceAtLeast(0))
        outState.putString(STATE_HEX, hexInput.text?.toString())
        outState.putString(STATE_ERROR, validationError)
    }

    private fun bindViews(view: View) {
        preview = view.findViewById(R.id.custom_color_preview)
        previewValue = view.findViewById(R.id.custom_color_preview_value)
        tabs = view.findViewById(R.id.custom_color_tabs)
        hexPanel = view.findViewById(R.id.custom_color_hex_panel)
        hsvPanel = view.findViewById(R.id.custom_color_hsv_panel)
        rgbPanel = view.findViewById(R.id.custom_color_rgb_panel)
        hexLayout = view.findViewById(R.id.custom_color_hex_layout)
        hexInput = view.findViewById(R.id.custom_color_hex_input)
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
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = showPanel(tab.position)
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
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
        hsvSliders.forEach { slider ->
            slider.addOnChangeListener { _, _, fromUser ->
                if (fromUser && !updatingControls) {
                    val color = Color.HSVToColor(
                        floatArrayOf(hsvSliders[0].value, hsvSliders[1].value / 100f, hsvSliders[2].value / 100f)
                    )
                    updateControls(color, updateHex = true)
                }
            }
        }
        rgbSliders.forEach { slider ->
            slider.addOnChangeListener { _, _, fromUser ->
                if (fromUser && !updatingControls) {
                    updateControls(
                        Color.rgb(
                            rgbSliders[0].value.roundToInt(),
                            rgbSliders[1].value.roundToInt(),
                            rgbSliders[2].value.roundToInt()
                        ),
                        updateHex = true
                    )
                }
            }
        }
    }

    private fun updateControls(color: Int, updateHex: Boolean) {
        currentColor = color or Color.BLACK
        updatingControls = true

        val hsv = FloatArray(3)
        Color.colorToHSV(currentColor, hsv)
        hsvSliders[0].value = hsv[0].coerceIn(0f, 360f)
        hsvSliders[1].value = (hsv[1] * 100f).coerceIn(0f, 100f)
        hsvSliders[2].value = (hsv[2] * 100f).coerceIn(0f, 100f)
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
        (hsvSliders + rgbSliders).forEach { slider ->
            slider.trackActiveTintList = ColorStateList.valueOf(currentColor)
            slider.thumbTintList = ColorStateList.valueOf(currentColor)
        }
        validationError = null
        hexLayout.error = null
        updateDoneButton()
        updatingControls = false
    }

    private fun updateDoneButton() {
        val surface = resolveColor(R.attr.colorPickerSurfaceVariant)
        val onSurface = resolveColor(R.attr.colorPickerOnSurface)
        doneButton.backgroundTintList = ColorStateList.valueOf(surface)
        doneButton.strokeColor = ColorStateList.valueOf(currentColor)
        doneButton.strokeWidth = resources.getDimensionPixelSize(R.dimen.color_picker_action_stroke)
        doneButton.setTextColor(ColorPickerUtils.accentTextColor(currentColor, surface, onSurface))
        doneButton.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun finishEditing() {
        val parsed = ColorPickerUtils.parseHex(hexInput.text?.toString().orEmpty())
        if (parsed == null || validationError != null) {
            validationError = getString(R.string.custom_color_invalid_hex_detail)
            hexLayout.error = validationError
            tabs.getTabAt(0)?.select()
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
        requireContext().theme.resolveAttribute(attribute, value, true)
        return if (value.resourceId != 0) ContextCompat.getColor(requireContext(), value.resourceId) else value.data
    }
}
