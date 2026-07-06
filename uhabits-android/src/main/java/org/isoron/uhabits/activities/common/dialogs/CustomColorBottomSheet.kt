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
import android.view.inputmethod.EditorInfo
import android.view.KeyEvent
import android.widget.Button
import android.widget.TextView
import android.widget.EditText
import android.view.animation.PathInterpolator
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
    private var isUpdatingFromModel = false
    private var isCommittingInput = false
    private var currentHue = 0f
    private var currentSaturation = 1f
    private var currentValue = 1f
    private var heightAnimator: android.animation.ValueAnimator? = null
    private var transitionToken = 0
    private val textAnimators = mutableMapOf<TextView, android.animation.ValueAnimator>()
    private var lastDisplayedHue = 0f
    private var lastDisplayedSaturation = 0f
    private var lastDisplayedValue = 0f
    private var lastDisplayedRed = 0f
    private var lastDisplayedGreen = 0f
    private var lastDisplayedBlue = 0f
    private val sliderAnimators = mutableMapOf<Slider, android.animation.ValueAnimator>()
    private var pendingOldPanelRunnable: Runnable? = null
    private var pendingNewPanelRunnable: Runnable? = null
    private lateinit var themedContext: Context

    private lateinit var preview: MaterialCardView
    private lateinit var previewValue: TextView
    private lateinit var tabHex: TextView
    private lateinit var tabHsv: TextView
    private lateinit var tabRgb: TextView
    private lateinit var tabIndicator: View
    private lateinit var hexPanel: View
    private lateinit var hsvPanel: View
    private lateinit var rgbPanel: View
    private lateinit var hexLayout: TextInputLayout
    private lateinit var hexInput: TextInputEditText
    private lateinit var cancelButton: TextView
    private lateinit var doneButton: Button
    private lateinit var hsvSliders: List<Slider>
    private lateinit var rgbSliders: List<Slider>
    private lateinit var hsvValues: List<EditText>
    private lateinit var rgbValues: List<EditText>

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
        ViewCompat.setAccessibilityPaneTitle(view, getString(R.string.custom_color_picker_title))
        bindViews(view)
        hexInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        hexInput.includeFontPadding = false
        hexInput.gravity = android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.START
        setupInsets(view)
        setupTabs()
        setupHexInput()
        setupSliders()
        setupEditableValues()

        view.findViewById<View>(R.id.custom_color_cancel).setOnClickListener {
            val focused = activity?.currentFocus ?: dialog?.window?.currentFocus
            if (focused != null) {
                isCommittingInput = true
                try {
                    focused.clearFocus()
                } finally {
                    isCommittingInput = false
                }
                hideKeyboard(focused)
            }
            dismiss()
        }
        doneButton.setOnClickListener {
            clearFocusAndHideKeyboard()
            finishEditing()
        }

        // Make root layouts focusable to accept focus when clearing it from inputs
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        val innerLayout = (view as? ViewGroup)?.getChildAt(0)
        innerLayout?.isFocusable = true
        innerLayout?.isFocusableInTouchMode = true

        // Setup click listeners on root layouts and panels to clear focus when tapping empty area
        val clearFocusListener = View.OnClickListener {
            clearFocusAndHideKeyboard()
        }
        view.setOnClickListener(clearFocusListener)
        innerLayout?.setOnClickListener(clearFocusListener)
        hexPanel.setOnClickListener(clearFocusListener)
        hsvPanel.setOnClickListener(clearFocusListener)
        rgbPanel.setOnClickListener(clearFocusListener)

        syncCanonicalHsvFromColor(currentColor, preserveHue = false)
        lastDisplayedHue = currentHue.coerceIn(0f, 360f)
        lastDisplayedSaturation = (currentSaturation * 100f).coerceIn(0f, 100f)
        lastDisplayedValue = (currentValue * 100f).coerceIn(0f, 100f)
        lastDisplayedRed = Color.red(currentColor).toFloat()
        lastDisplayedGreen = Color.green(currentColor).toFloat()
        lastDisplayedBlue = Color.blue(currentColor).toFloat()

        val restoredError = validationError
        updateControls(currentColor, updateHex = savedHex == null)
        if (savedHex != null) {
            isUpdatingFromModel = true
            hexInput.setText(savedHex)
            isUpdatingFromModel = false
        }
        validationError = restoredError
        selectTab(selectedTab, animate = false)
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
        tabIndicator = view.findViewById(R.id.custom_color_tab_indicator)
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

    private fun selectTab(position: Int, animate: Boolean = true) {
        val oldPosition = selectedTab
        clearFocusAndHideKeyboard()
        ++transitionToken
        cancelAllAnimations()

        val activeTextColor = resolveColor(R.attr.colorPickerOnSurface)
        val inactiveTextColor = resolveColor(R.attr.colorPickerOnSurfaceVariant)

        animateTextColor(tabHex, if (position == 0) activeTextColor else inactiveTextColor, animate)
        animateTextColor(tabHsv, if (position == 1) activeTextColor else inactiveTextColor, animate)
        animateTextColor(tabRgb, if (position == 2) activeTextColor else inactiveTextColor, animate)

        val moveIndicator = Runnable {
            val width = tabIndicator.width.toFloat()
            val targetX = position * width
            if (animate && width > 0f) {
                tabIndicator.animate().cancel()
                tabIndicator.animate()
                    .translationX(targetX)
                    .setDuration(300L)
                    .setInterpolator(PathInterpolator(0.2f, 0f, 0f, 1f))
                    .start()
            } else {
                tabIndicator.animate().cancel()
                tabIndicator.translationX = targetX
            }
        }

        if (tabIndicator.width > 0) {
            moveIndicator.run()
        } else {
            tabIndicator.post(moveIndicator)
        }

        // HSV ↔ RGB is a morph (rows stay in place), other transitions use panel slide
        val isHsvRgbMorph = (oldPosition == 1 && position == 2) || (oldPosition == 2 && position == 1)
        if (isHsvRgbMorph) {
            morphHsvRgb(toHsv = (position == 1), animate = animate)
        } else {
            showPanel(position, animate)
        }
    }

    /**
     * Morph transition between HSV and RGB: rows stay in place, labels/values cross-fade,
     * sliders animate to new values. No panel slide is used.
     */
    private fun morphHsvRgb(toHsv: Boolean, animate: Boolean) {
        val oldSliders = if (toHsv) rgbSliders else hsvSliders
        val newSliders = if (toHsv) hsvSliders else rgbSliders
        val oldPanel   = if (toHsv) rgbPanel    else hsvPanel
        val newPanel   = if (toHsv) hsvPanel    else rgbPanel

        val currentToken = transitionToken
        selectedTab = if (toHsv) 1 else 2

        // Compute the target model values for the incoming panel
        val newTargetValues: List<Float> = if (toHsv) {
            listOf(
                currentHue.coerceIn(0f, 360f),
                (currentSaturation * 100f).coerceIn(0f, 100f),
                (currentValue * 100f).coerceIn(0f, 100f)
            )
        } else {
            listOf(
                Color.red(currentColor).toFloat(),
                Color.green(currentColor).toFloat(),
                Color.blue(currentColor).toFloat()
            )
        }

        // Mirror old slider positions onto new sliders (normalized 0–1) so thumbs start at
        // the same visual position as the outgoing ones. Then animate to actual model values.
        val oldRanges = oldSliders.map { it.valueTo - it.valueFrom }
        isUpdatingFromModel = true
        try {
            for (i in 0..2) {
                val oldFraction = if (oldRanges[i] > 0f)
                    (oldSliders[i].value - oldSliders[i].valueFrom) / oldRanges[i]
                else 0f
                val newRange = newSliders[i].valueTo - newSliders[i].valueFrom
                val mirroredStart = (newSliders[i].valueFrom + oldFraction * newRange)
                    .coerceIn(newSliders[i].valueFrom, newSliders[i].valueTo)
                newSliders[i].value = mirroredStart
                updateSliderTextSync(newSliders[i], mirroredStart)
            }
        } finally { isUpdatingFromModel = false }

        // Update lastDisplayed to the mirrored start so updateSliderTextSync is coherent
        if (toHsv) {
            lastDisplayedHue = newSliders[0].value
            lastDisplayedSaturation = newSliders[1].value
            lastDisplayedValue = newSliders[2].value
        } else {
            lastDisplayedRed = newSliders[0].value
            lastDisplayedGreen = newSliders[1].value
            lastDisplayedBlue = newSliders[2].value
        }

        if (!animate) {
            // Snap directly to correct values
            isUpdatingFromModel = true
            try {
                for (i in 0..2) { newSliders[i].value = newTargetValues[i]; updateSliderTextSync(newSliders[i], newTargetValues[i]) }
            } finally { isUpdatingFromModel = false }
            if (toHsv) {
                lastDisplayedHue = newSliders[0].value; lastDisplayedSaturation = newSliders[1].value; lastDisplayedValue = newSliders[2].value
            } else {
                lastDisplayedRed = newSliders[0].value; lastDisplayedGreen = newSliders[1].value; lastDisplayedBlue = newSliders[2].value
            }
            
            oldSliders.forEach { it.visibility = View.INVISIBLE }
            newSliders.forEach { it.visibility = View.VISIBLE }
            
            oldPanel.visibility = View.GONE; oldPanel.alpha = 1f
            newPanel.visibility = View.VISIBLE; newPanel.alpha = 1f
            getPanelChildren(oldPanel).forEach { row ->
                if (row is ViewGroup) for (i in 0 until row.childCount) { val c = row.getChildAt(i); c.alpha=1f; c.translationY=0f }
            }
            getPanelChildren(newPanel).forEach { row ->
                if (row is ViewGroup) for (i in 0 until row.childCount) { val c = row.getChildAt(i); c.alpha=1f; c.translationY=0f }
            }
            return
        }

        val morphDuration = 200L
        val sliderDelay   = 50L
        val interpolator  = PathInterpolator(0.2f, 0f, 0f, 1f)
        val slideY        = dp(4f)

        // Make old sliders invisible immediately so they do not overlap
        oldSliders.forEach { it.visibility = View.INVISIBLE }
        newSliders.forEach { it.visibility = View.VISIBLE }

        // Show newPanel at full alpha from the start; it physically overlaps oldPanel in FrameLayout.
        // We only cross-fade the label (child 0) and value (child 2) of each row.
        // Sliders in newPanel are already positioned to match oldPanel's visual position (mirrored).
        newPanel.alpha = 1f
        newPanel.translationX = 0f
        newPanel.translationY = 0f
        newPanel.visibility = View.VISIBLE
        oldPanel.alpha = 1f
        oldPanel.visibility = View.VISIBLE

        // Hide newPanel label/value children initially; keep its sliders visible
        for (rowIdx in 0..2) {
            val newRow = getPanelChildren(newPanel).getOrNull(rowIdx) as? ViewGroup ?: continue
            listOf(0, 2).forEach { childIdx ->
                val newChild = newRow.getChildAt(childIdx) ?: return@forEach
                newChild.alpha = 0f
                newChild.translationY = slideY
            }
        }

        // Cross-fade label and value children for each row
        for (rowIdx in 0..2) {
            val oldRow = getPanelChildren(oldPanel).getOrNull(rowIdx) as? ViewGroup ?: continue
            val newRow = getPanelChildren(newPanel).getOrNull(rowIdx) as? ViewGroup ?: continue
            listOf(0, 2).forEach { childIdx ->
                val oldChild = oldRow.getChildAt(childIdx) ?: return@forEach
                val newChild = newRow.getChildAt(childIdx) ?: return@forEach
                oldChild.animate()?.alpha(0f)?.translationY(-slideY)
                    ?.setDuration(morphDuration)?.setInterpolator(interpolator)?.start()
                newChild.animate()?.alpha(1f)?.translationY(0f)
                    ?.setDuration(morphDuration)?.setInterpolator(interpolator)?.start()
            }
        }

        // After cross-fade, hide old panel and reset its children for future use
        val oldPanelRunnable = Runnable {
            if (currentToken == transitionToken) {
                oldPanel.visibility = View.GONE
                getPanelChildren(oldPanel).forEach { row ->
                    if (row is ViewGroup) for (i in 0 until row.childCount) {
                        val c = row.getChildAt(i)
                        c.alpha = 1f
                        c.translationY = 0f
                    }
                }
                pendingOldPanelRunnable = null
            }
        }
        pendingOldPanelRunnable = oldPanelRunnable
        oldPanel.postDelayed(oldPanelRunnable, morphDuration)
        val oldValues = if (toHsv) rgbValues else hsvValues
        // Animate new slider thumbs from mirrored start → actual model values
        val newPanelRunnable = Runnable {
            if (isAdded && currentToken == transitionToken) {
                for (i in 0..2) {
                    val oldValText = oldValues[i].text.toString()
                    val cleanText = oldValText.removeSuffix("°").removeSuffix("%").trim()
                    val oldNum = cleanText.toFloatOrNull()
                    animateSliderTo(newSliders[i], newTargetValues[i], oldNum)
                }
                pendingNewPanelRunnable = null
            }
        }
        pendingNewPanelRunnable = newPanelRunnable
        newPanel.postDelayed(newPanelRunnable, sliderDelay)
    }


    private fun showPanel(position: Int, animate: Boolean = true) {
        val oldTab = selectedTab
        selectedTab = position

        val currentToken = transitionToken

        val hsvSlidersVisible = (position == 1)
        val rgbSlidersVisible = (position == 2)
        hsvSliders.forEach { it.visibility = if (hsvSlidersVisible) View.VISIBLE else View.INVISIBLE }
        rgbSliders.forEach { it.visibility = if (rgbSlidersVisible) View.VISIBLE else View.INVISIBLE }

        // Prepare slider values silently (no animation — panel slide handles the visual)
        if (position == 1) { // HSV
            isUpdatingFromModel = true
            try {
                hsvSliders[0].value = currentHue.coerceIn(0f, 360f)
                hsvSliders[1].value = (currentSaturation * 100f).coerceIn(0f, 100f)
                hsvSliders[2].value = (currentValue * 100f).coerceIn(0f, 100f)
            } finally { isUpdatingFromModel = false }
            lastDisplayedHue = hsvSliders[0].value
            lastDisplayedSaturation = hsvSliders[1].value
            lastDisplayedValue = hsvSliders[2].value
            updateSliderTextSync(hsvSliders[0], lastDisplayedHue)
            updateSliderTextSync(hsvSliders[1], lastDisplayedSaturation)
            updateSliderTextSync(hsvSliders[2], lastDisplayedValue)
        } else if (position == 2) { // RGB
            val targetR = Color.red(currentColor).toFloat()
            val targetG = Color.green(currentColor).toFloat()
            val targetB = Color.blue(currentColor).toFloat()
            isUpdatingFromModel = true
            try {
                rgbSliders[0].value = targetR
                rgbSliders[1].value = targetG
                rgbSliders[2].value = targetB
            } finally { isUpdatingFromModel = false }
            lastDisplayedRed = rgbSliders[0].value
            lastDisplayedGreen = rgbSliders[1].value
            lastDisplayedBlue = rgbSliders[2].value
            updateSliderTextSync(rgbSliders[0], lastDisplayedRed)
            updateSliderTextSync(rgbSliders[1], lastDisplayedGreen)
            updateSliderTextSync(rgbSliders[2], lastDisplayedBlue)
        }

        val oldPanel = when (oldTab) {
            0 -> hexPanel
            1 -> hsvPanel
            2 -> rgbPanel
            else -> null
        }
        val newPanel = when (position) {
            0 -> hexPanel
            1 -> hsvPanel
            2 -> rgbPanel
            else -> null
        }

        if (newPanel == null || oldPanel == newPanel) {
            val panels = listOf(hexPanel, hsvPanel, rgbPanel)
            panels.forEachIndexed { index, panel ->
                panel.animate()?.cancel()
                getPanelChildren(panel).forEach { child ->
                    child.animate()?.cancel()
                    child.alpha = 1f
                    child.translationX = 0f
                }
                if (index == position) {
                    panel.visibility = View.VISIBLE
                    panel.alpha = 1f
                    panel.translationX = 0f
                    panel.translationY = 0f
                } else {
                    panel.visibility = View.GONE
                }
            }
            return
        }

        val panelsContainer = hexPanel.parent as? ViewGroup
        if (panelsContainer == null) {
            val panels = listOf(hexPanel, hsvPanel, rgbPanel)
            panels.forEachIndexed { index, panel ->
                panel.animate()?.cancel()
                getPanelChildren(panel).forEach { child ->
                    child.animate()?.cancel()
                    child.alpha = 1f
                    child.translationX = 0f
                }
                if (index == position) {
                    panel.visibility = View.VISIBLE
                    panel.alpha = 1f
                    panel.translationX = 0f
                    panel.translationY = 0f
                } else {
                    panel.visibility = View.GONE
                }
            }
            return
        }

        oldPanel?.animate()?.setListener(null)?.cancel()
        newPanel.animate()?.setListener(null)?.cancel()
        getPanelChildren(oldPanel).forEach { it.animate()?.cancel() }
        getPanelChildren(newPanel).forEach { it.animate()?.cancel() }
        heightAnimator?.cancel()

        if (!animate) {
            val panels = listOf(hexPanel, hsvPanel, rgbPanel)
            panels.forEachIndexed { index, panel ->
                if (index == position) {
                    panel.visibility = View.VISIBLE
                    panel.alpha = 1f
                    panel.translationX = 0f
                    panel.translationY = 0f
                    getPanelChildren(panel).forEach { child ->
                        child.alpha = 1f
                        child.translationX = 0f
                    }
                } else {
                    panel.visibility = View.GONE
                }
            }
            val params = panelsContainer.layoutParams
            params.height = ViewGroup.LayoutParams.WRAP_CONTENT
            panelsContainer.layoutParams = params
            return
        }

        val fromHeight = panelsContainer.height
        val panels = listOf(hexPanel, hsvPanel, rgbPanel)

        // Temporarily set layout visibility to measure target height of panelsContainer
        panels.forEachIndexed { index, panel ->
            panel.visibility = if (index == position) View.VISIBLE else View.GONE
        }

        // Measure panelsContainer with the target panel to determine toHeight
        val widthSpec = View.MeasureSpec.makeMeasureSpec(panelsContainer.width, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        panelsContainer.measure(widthSpec, heightSpec)
        val toHeight = panelsContainer.measuredHeight

        // Restore layout visibility for transition: both old and new panels visible, others GONE
        panels.forEach { panel ->
            if (panel == oldPanel || panel == newPanel) {
                panel.visibility = View.VISIBLE
            } else {
                panel.visibility = View.GONE
            }
        }

        val animDuration = 280L
        val animInterpolator = PathInterpolator(0.2f, 0f, 0f, 1f)

        if (fromHeight > 0 && toHeight > 0 && fromHeight != toHeight) {
            heightAnimator = android.animation.ValueAnimator.ofInt(fromHeight, toHeight).apply {
                duration = animDuration
                this.interpolator = animInterpolator
                addUpdateListener { animator ->
                    if (currentToken != transitionToken) return@addUpdateListener
                    val valHeight = animator.animatedValue as Int
                    val params = panelsContainer.layoutParams
                    params.height = valHeight
                    panelsContainer.layoutParams = params
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        if (currentToken != transitionToken) return
                        val params = panelsContainer.layoutParams
                        params.height = ViewGroup.LayoutParams.WRAP_CONTENT
                        panelsContainer.layoutParams = params
                        heightAnimator = null
                    }
                })
                start()
            }
        } else {
            val params = panelsContainer.layoutParams
            params.height = ViewGroup.LayoutParams.WRAP_CONTENT
            panelsContainer.layoutParams = params
        }

        val direction = if (position > oldTab) 1f else -1f

        // Animate old panel out
        oldPanel?.animate()
            ?.alpha(0f)
            ?.translationX(-direction * dp(10f))
            ?.setDuration(animDuration)
            ?.setInterpolator(animInterpolator)
            ?.setListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (currentToken != transitionToken) return
                    oldPanel?.visibility = View.GONE
                    oldPanel?.translationX = 0f
                    getPanelChildren(oldPanel).forEach { child ->
                        child.alpha = 1f
                        child.translationX = 0f
                    }
                }
            })
            ?.start()

        // Prepare new panel and its children starting states
        newPanel?.alpha = 0f
        newPanel?.translationX = direction * dp(14f)
        newPanel?.translationY = 0f

        val newChildren = getPanelChildren(newPanel)
        newChildren.forEachIndexed { idx, child ->
            child.alpha = 0f
            child.translationX = direction * dp(6f)
            child.animate()
                ?.alpha(1f)
                ?.translationX(0f)
                ?.setDuration(animDuration)
                ?.setInterpolator(animInterpolator)
                ?.setStartDelay(12L + idx * 15L)
                ?.start()
        }

        newPanel?.animate()
            ?.alpha(1f)
            ?.translationX(0f)
            ?.setDuration(animDuration)
            ?.setInterpolator(animInterpolator)
            ?.setListener(null)
            ?.start()
    }

    private fun animateSliderTo(slider: Slider, targetValue: Float, oldTextValue: Float? = null) {
        sliderAnimators[slider]?.cancel()
        val startValue = slider.value
        val startTextValue = oldTextValue ?: startValue

        if (startValue == targetValue && startTextValue.roundToInt() == targetValue.roundToInt()) {
            updateSliderTextSync(slider, targetValue)
            return
        }

        var lastRounded = startTextValue.roundToInt()
        val animator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 410L
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener { anim ->
                isUpdatingFromModel = true
                try {
                    val fraction = anim.animatedValue as Float
                    
                    // Interpolate slider value safely within its own bounds
                    val currentSliderVal = startValue + fraction * (targetValue - startValue)
                    slider.value = currentSliderVal
                    
                    // Interpolate text value between old text and target
                    val currentTextVal = startTextValue + fraction * (targetValue - startTextValue)
                    val rounded = currentTextVal.roundToInt()
                    if (rounded != lastRounded) {
                        lastRounded = rounded
                        updateSliderTextSync(slider, currentTextVal)
                    }
                } finally {
                    isUpdatingFromModel = false
                }
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    sliderAnimators.remove(slider)
                    // Ensure final exact value is displayed
                    isUpdatingFromModel = true
                    try { updateSliderTextSync(slider, targetValue) }
                    finally { isUpdatingFromModel = false }
                }
            })
        }
        sliderAnimators[slider] = animator
        animator.start()
    }

    private fun setupHexInput() {
        hexInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (isUpdatingFromModel) return
                val text = s?.toString().orEmpty().trim()
                val cleanHex = text.removePrefix("#")
                val parsed = ColorPickerUtils.parseHex(text)
                if (parsed != null && cleanHex.length == 6) {
                    validationError = null
                    hexLayout.error = null
                    updateControls(
                        parsed,
                        updateHex = false,
                        updateHsvSliders = true,
                        updateRgbSliders = true,
                        updatePreview = true,
                        preserveHue = false
                    )
                }
            }
        })
    }

    private fun setupSliders() {
        val normalThumb = resources.getDimensionPixelSize(R.dimen.color_picker_slider_thumb_radius)
        val pressedThumb = resources.getDimensionPixelSize(R.dimen.color_picker_slider_thumb_radius_pressed)

        val handleSliderTouch = {
            clearFocusAndHideKeyboard()
        }

        hsvSliders.forEachIndexed { index, slider ->
            slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) {
                    sliderAnimators[slider]?.cancel()
                    slider.thumbRadius = pressedThumb
                    handleSliderTouch()
                }

                override fun onStopTrackingTouch(slider: Slider) {
                    slider.thumbRadius = normalThumb
                }
            })
            slider.addOnChangeListener { _, _, fromUser ->
                if (fromUser && !isUpdatingFromModel) {
                    when (index) {
                        0 -> currentHue = slider.value.coerceIn(0f, 360f)
                        1 -> currentSaturation = (slider.value / 100f).coerceIn(0f, 1f)
                        2 -> currentValue = (slider.value / 100f).coerceIn(0f, 1f)
                    }
                    lastDisplayedHue = hsvSliders[0].value
                    lastDisplayedSaturation = hsvSliders[1].value
                    lastDisplayedValue = hsvSliders[2].value
                    updateControls(
                        Color.HSVToColor(floatArrayOf(currentHue, currentSaturation, currentValue)),
                        updateHex = true,
                        updateHsvSliders = false,
                        updateRgbSliders = true,
                        updatePreview = true,
                        preserveHue = true
                    )
                }
            }
        }
        rgbSliders.forEachIndexed { index, slider ->
            slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) {
                    sliderAnimators[slider]?.cancel()
                    slider.thumbRadius = pressedThumb
                    handleSliderTouch()
                }

                override fun onStopTrackingTouch(slider: Slider) {
                    slider.thumbRadius = normalThumb
                }
            })
            slider.addOnChangeListener { _, _, fromUser ->
                if (fromUser && !isUpdatingFromModel) {
                    lastDisplayedRed = rgbSliders[0].value
                    lastDisplayedGreen = rgbSliders[1].value
                    lastDisplayedBlue = rgbSliders[2].value
                    updateControls(
                        Color.rgb(
                            rgbSliders[0].value.roundToInt(),
                            rgbSliders[1].value.roundToInt(),
                            rgbSliders[2].value.roundToInt()
                        ),
                        updateHex = true,
                        updateHsvSliders = true,
                        updateRgbSliders = false,
                        updatePreview = true,
                        preserveHue = true
                    )
                }
            }
        }
    }



    private fun updateSliderTextSync(slider: Slider, value: Float) {
        val rounded = value.roundToInt()
        when (slider.id) {
            R.id.custom_color_hue -> {
                lastDisplayedHue = value
                setEditTextValue(hsvValues[0], formatHsvValue(0, rounded, hsvValues[0].hasFocus()))
            }
            R.id.custom_color_saturation -> {
                lastDisplayedSaturation = value
                setEditTextValue(hsvValues[1], formatHsvValue(1, rounded, hsvValues[1].hasFocus()))
            }
            R.id.custom_color_value -> {
                lastDisplayedValue = value
                setEditTextValue(hsvValues[2], formatHsvValue(2, rounded, hsvValues[2].hasFocus()))
            }
            R.id.custom_color_red -> {
                lastDisplayedRed = value
                setEditTextValue(rgbValues[0], rounded.toString())
            }
            R.id.custom_color_green -> {
                lastDisplayedGreen = value
                setEditTextValue(rgbValues[1], rounded.toString())
            }
            R.id.custom_color_blue -> {
                lastDisplayedBlue = value
                setEditTextValue(rgbValues[2], rounded.toString())
            }
        }
    }

    private fun updateControls(
        color: Int,
        updateHex: Boolean = true,
        updateHsvSliders: Boolean = true,
        updateRgbSliders: Boolean = true,
        updatePreview: Boolean = true,
        preserveHue: Boolean = true
    ) {
        currentColor = color or Color.BLACK
        isUpdatingFromModel = true
        try {
            if (updateHsvSliders) {
                syncCanonicalHsvFromColor(currentColor, preserveHue)
                hsvSliders[0].value = currentHue.coerceIn(0f, 360f)
                hsvSliders[1].value = (currentSaturation * 100f).coerceIn(0f, 100f)
                hsvSliders[2].value = (currentValue * 100f).coerceIn(0f, 100f)
                if (selectedTab == 1) {
                    lastDisplayedHue = hsvSliders[0].value
                    lastDisplayedSaturation = hsvSliders[1].value
                    lastDisplayedValue = hsvSliders[2].value
                }
            }
            if (updateRgbSliders) {
                rgbSliders[0].value = Color.red(currentColor).toFloat()
                rgbSliders[1].value = Color.green(currentColor).toFloat()
                rgbSliders[2].value = Color.blue(currentColor).toFloat()
                if (selectedTab == 2) {
                    lastDisplayedRed = rgbSliders[0].value
                    lastDisplayedGreen = rgbSliders[1].value
                    lastDisplayedBlue = rgbSliders[2].value
                }
            }

            val hueVal = hsvSliders[0].value.roundToInt()
            val satVal = hsvSliders[1].value.roundToInt()
            val valVal = hsvSliders[2].value.roundToInt()

            setEditTextValue(hsvValues[0], formatHsvValue(0, hueVal, hsvValues[0].hasFocus()))
            setEditTextValue(hsvValues[1], formatHsvValue(1, satVal, hsvValues[1].hasFocus()))
            setEditTextValue(hsvValues[2], formatHsvValue(2, valVal, hsvValues[2].hasFocus()))

            rgbValues.forEachIndexed { index, editText ->
                val rgbVal = rgbSliders[index].value.roundToInt()
                setEditTextValue(editText, rgbVal.toString())
            }

            val hex = ColorPickerUtils.toHex(currentColor)
            if (updateHex) {
                setEditTextValue(hexInput, hex)
            }
            if (updatePreview) {
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
            }
            validationError = null
            hexLayout.error = null
            updateDoneButton()
        } finally {
            isUpdatingFromModel = false
        }
    }

    private fun syncCanonicalHsvFromColor(color: Int, preserveHue: Boolean) {
        val hsv = FloatArray(3)
        Color.colorToHSV(color or Color.BLACK, hsv)
        if (!preserveHue || (hsv[1] > 0f && hsv[2] > 0f)) {
            val newHue = hsv[0].coerceIn(0f, 360f)
            if (newHue == 0f && currentHue > 359f) {
                // keep currentHue at 360f to prevent the slider thumb from jumping
            } else {
                currentHue = newHue
            }
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
        val focused = activity?.currentFocus ?: dialog?.window?.currentFocus
        if (focused != null) {
            commitFocusedInput(focused)
        }

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

    private fun dp(value: Float): Float {
        return value * resources.displayMetrics.density
    }

    private fun animateTextColor(textView: TextView, targetColor: Int, animate: Boolean) {
        textAnimators[textView]?.cancel()
        textAnimators.remove(textView)

        val startColor = textView.currentTextColor
        if (startColor == targetColor) return

        if (animate) {
            val animator = android.animation.ValueAnimator.ofObject(
                android.animation.ArgbEvaluator(),
                startColor,
                targetColor
            ).apply {
                duration = 180L
                addUpdateListener { anim ->
                    textView.setTextColor(anim.animatedValue as Int)
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        textAnimators.remove(textView)
                    }
                })
            }
            textAnimators[textView] = animator
            animator.start()
        } else {
            textView.setTextColor(targetColor)
        }
    }

    private fun getPanelChildren(panel: View?): List<View> {
        if (panel is ViewGroup) {
            return (0 until panel.childCount).map { panel.getChildAt(it) }
        }
        return emptyList()
    }

    private fun cancelAllAnimations() {
        val runningSliders = sliderAnimators.values.toList()
        sliderAnimators.clear()
        runningSliders.forEach { it.cancel() }

        val runningTexts = textAnimators.values.toList()
        textAnimators.clear()
        runningTexts.forEach { it.cancel() }

        heightAnimator?.cancel()
        heightAnimator = null

        if (this::hsvPanel.isInitialized && this::rgbPanel.isInitialized && this::hexPanel.isInitialized) {
            tabIndicator.animate().cancel()

            pendingOldPanelRunnable?.let {
                hsvPanel.removeCallbacks(it)
                rgbPanel.removeCallbacks(it)
                pendingOldPanelRunnable = null
            }
            pendingNewPanelRunnable?.let {
                hsvPanel.removeCallbacks(it)
                rgbPanel.removeCallbacks(it)
                pendingNewPanelRunnable = null
            }

            listOf(hexPanel, hsvPanel, rgbPanel).forEach { panel ->
                panel.animate().cancel()
                getPanelChildren(panel).forEach { row ->
                    row.animate().cancel()
                    if (row is ViewGroup) {
                        for (i in 0 until row.childCount) {
                            row.getChildAt(i).animate().cancel()
                        }
                    }
                }
            }
        }
    }

    private fun formatHsvValue(index: Int, value: Int, hasFocus: Boolean): String {
        return if (hasFocus) {
            value.toString()
        } else {
            when (index) {
                0 -> "$value°"
                1, 2 -> "$value%"
                else -> value.toString()
            }
        }
    }

    private fun setEditTextValue(editText: EditText, text: String) {
        val oldText = editText.text?.toString().orEmpty()
        if (oldText != text) {
            editText.setText(text)
            if (editText.hasFocus()) {
                editText.setSelection(editText.text?.length ?: 0)
            }
        }
    }

    private fun commitFocusedInput(viewToCommit: View? = null) {
        if (isCommittingInput || isUpdatingFromModel) return
        if (!this::hsvValues.isInitialized || !this::rgbValues.isInitialized || !this::hexInput.isInitialized) return

        val targetView = viewToCommit ?: activity?.currentFocus ?: dialog?.window?.currentFocus ?: return
        if (!ViewCompat.isAttachedToWindow(targetView)) return

        val hsvIndex = hsvValues.indexOf(targetView)
        val rgbIndex = rgbValues.indexOf(targetView)
        val isHex = targetView == hexInput

        if (hsvIndex == -1 && rgbIndex == -1 && !isHex) return

        isCommittingInput = true
        try {
            if (isHex) {
                val text = hexInput.text?.toString().orEmpty().trim()
                val parsed = ColorPickerUtils.parseHex(text)
                if (parsed != null) {
                    currentColor = parsed or Color.BLACK
                    syncCanonicalHsvFromColor(currentColor, preserveHue = false)
                } else {
                    val lastValidHex = ColorPickerUtils.toHex(currentColor)
                    hexInput.setText(lastValidHex)
                }
            } else if (hsvIndex != -1) {
                val editText = hsvValues[hsvIndex]
                val rawText = editText.text?.toString().orEmpty().trim()
                val cleanText = rawText.removeSuffix("°").removeSuffix("%").trim()
                val num = cleanText.toIntOrNull() ?: 0
                val maxVal = if (hsvIndex == 0) 360 else 100
                val clamped = num.coerceIn(0, maxVal)

                val hasFocus = editText.hasFocus()
                editText.setText(formatHsvValue(hsvIndex, clamped, hasFocus))
                if (hasFocus) {
                    editText.setSelection(editText.text?.length ?: 0)
                }

                when (hsvIndex) {
                    0 -> currentHue = clamped.toFloat()
                    1 -> currentSaturation = clamped / 100f
                    2 -> currentValue = clamped / 100f
                }
                currentColor = Color.HSVToColor(floatArrayOf(currentHue, currentSaturation, currentValue)) or Color.BLACK
            } else if (rgbIndex != -1) {
                val editText = rgbValues[rgbIndex]
                val rawText = editText.text?.toString().orEmpty().trim()
                val cleanText = rawText.removeSuffix("°").removeSuffix("%").trim()
                val num = cleanText.toIntOrNull() ?: 0
                val clamped = num.coerceIn(0, 255)

                editText.setText(clamped.toString())
                if (editText.hasFocus()) {
                    editText.setSelection(editText.text?.length ?: 0)
                }

                val r = if (rgbIndex == 0) clamped else Color.red(currentColor)
                val g = if (rgbIndex == 1) clamped else Color.green(currentColor)
                val b = if (rgbIndex == 2) clamped else Color.blue(currentColor)
                currentColor = Color.rgb(r, g, b) or Color.BLACK
                syncCanonicalHsvFromColor(currentColor, preserveHue = true)
            }
        } finally {
            isCommittingInput = false
        }

        updateControls(
            currentColor,
            updateHex = true,
            updateHsvSliders = true,
            updateRgbSliders = true,
            updatePreview = true,
            preserveHue = true
        )
    }

    private fun clearFocusAndHideKeyboard() {
        if (!isAdded) return
        val focused = activity?.currentFocus ?: dialog?.window?.currentFocus ?: return
        if (!ViewCompat.isAttachedToWindow(focused)) return

        commitFocusedInput(focused)
        isCommittingInput = true
        try {
            focused.clearFocus()
        } finally {
            isCommittingInput = false
        }
        hideKeyboard(focused)
    }

    private fun hideKeyboard(view: View) {
        val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
        imm?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun setupImeActions(editText: EditText) {
        editText.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_DONE ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                clearFocusAndHideKeyboard()
                true
            } else {
                false
            }
        }
    }

    private fun setupEditableValues() {
        (hsvValues + rgbValues + listOf(hexInput)).forEach { editText ->
            setupImeActions(editText)
        }

        hexInput.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                hexInput.selectAll()
            } else {
                commitFocusedInput(hexInput)
            }
        }

        hsvValues.forEachIndexed { index, editText ->
            editText.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    val rawText = editText.text?.toString().orEmpty().trim()
                    val cleanText = rawText.removeSuffix("°").removeSuffix("%").trim()
                    val num = cleanText.toIntOrNull() ?: 0
                    editText.setText(num.toString())
                    editText.selectAll()
                } else {
                    commitFocusedInput(editText)
                }
            }

            editText.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    if (isUpdatingFromModel || !editText.hasFocus()) return
                    val rawText = s?.toString().orEmpty().trim()
                    val cleanText = rawText.removeSuffix("°").removeSuffix("%").trim()
                    if (cleanText.isEmpty()) return
                    val num = cleanText.toIntOrNull() ?: 0
                    val maxVal = if (index == 0) 360 else 100
                    val clamped = num.coerceIn(0, maxVal)

                    when (index) {
                        0 -> currentHue = clamped.toFloat()
                        1 -> currentSaturation = clamped / 100f
                        2 -> currentValue = clamped / 100f
                    }
                    updateControls(
                        Color.HSVToColor(floatArrayOf(currentHue, currentSaturation, currentValue)),
                        updateHex = true,
                        updateHsvSliders = true,
                        updateRgbSliders = true,
                        updatePreview = true,
                        preserveHue = true
                    )
                }
            })
        }

        rgbValues.forEachIndexed { index, editText ->
            editText.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    editText.selectAll()
                } else {
                    commitFocusedInput(editText)
                }
            }

            editText.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    if (isUpdatingFromModel || !editText.hasFocus()) return
                    val rawText = s?.toString().orEmpty().trim()
                    if (rawText.isEmpty()) return
                    val num = rawText.toIntOrNull() ?: 0
                    val clamped = num.coerceIn(0, 255)

                    val r = if (index == 0) clamped else Color.red(currentColor)
                    val g = if (index == 1) clamped else Color.green(currentColor)
                    val b = if (index == 2) clamped else Color.blue(currentColor)

                    updateControls(
                        Color.rgb(r, g, b),
                        updateHex = true,
                        updateHsvSliders = true,
                        updateRgbSliders = true,
                        updatePreview = true,
                        preserveHue = true
                    )
                }
            })
        }
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        val focused = activity?.currentFocus ?: this.dialog?.window?.currentFocus
        if (focused != null) {
            isCommittingInput = true
            try {
                focused.clearFocus()
            } finally {
                isCommittingInput = false
            }
            hideKeyboard(focused)
        }
        super.onDismiss(dialog)
    }
}
