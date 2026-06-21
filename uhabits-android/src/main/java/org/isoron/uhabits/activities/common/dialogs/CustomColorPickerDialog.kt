package org.isoron.uhabits.activities.common.dialogs

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.SeekBar
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDialogFragment
import org.isoron.uhabits.R
import java.util.Locale

class CustomColorPickerDialog : AppCompatDialogFragment() {

    interface OnColorPickedListener {
        fun onColorPicked(color: Int)
    }

    var listener: OnColorPickedListener? = null

    private lateinit var colorPreview: View
    private lateinit var hexInput: EditText
    private lateinit var satValView: SatValView
    private lateinit var hueSeekBar: SeekBar

    private var initialColor: Int = Color.BLACK
    private var currentColor: Int = Color.BLACK
    private var currentHsv = FloatArray(3)
    private var isUpdating = false

    companion object {
        fun newInstance(initialColor: Int): CustomColorPickerDialog {
            val dialog = CustomColorPickerDialog()
            dialog.arguments = Bundle().apply {
                putInt("initialColor", initialColor)
            }
            return dialog
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initialColor = arguments?.getInt("initialColor") ?: Color.BLACK
        currentColor = initialColor
        Color.colorToHSV(currentColor, currentHsv)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = LayoutInflater.from(requireContext()).inflate(R.layout.custom_color_picker_dialog, null)

        colorPreview = view.findViewById(R.id.colorPreview)
        hexInput = view.findViewById(R.id.hexInput)
        satValView = view.findViewById(R.id.satValView)
        hueSeekBar = view.findViewById(R.id.hueSeekBar)

        // Make colorPreview a rounded circle
        val density = resources.displayMetrics.density
        colorPreview.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(currentColor)
            setStroke((1.5f * density).toInt(), Color.parseColor("#40000000"))
        }

        // Initialize Hue seek bar rainbow gradient background
        val colors = IntArray(361) { i -> Color.HSVToColor(floatArrayOf(i.toFloat(), 1f, 1f)) }
        val hueBackground = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, colors).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 6f * density
        }
        hueSeekBar.progressDrawable = hueBackground
        
        // Setup initial progress / views
        isUpdating = true
        hueSeekBar.progress = currentHsv[0].toInt().coerceIn(0, 360)
        satValView.setHsv(currentHsv[0], currentHsv[1], currentHsv[2])
        updateHexInput()
        isUpdating = false

        setupListeners()

        return AlertDialog.Builder(requireContext())
            .setTitle(R.string.custom_color_picker_title)
            .setView(view)
            .setPositiveButton(R.string.done_label) { _, _ ->
                listener?.onColorPicked(currentColor)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }

    private fun setupListeners() {
        hueSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    currentHsv[0] = progress.toFloat()
                    currentColor = Color.HSVToColor(currentHsv)
                    satValView.setHsv(currentHsv[0], currentHsv[1], currentHsv[2])
                    updatePreviewColor()
                    updateHexInput()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        satValView.onColorChangedListener = { s, v ->
            if (!isUpdating) {
                currentHsv[1] = s
                currentHsv[2] = v
                currentColor = Color.HSVToColor(currentHsv)
                updatePreviewColor()
                updateHexInput()
            }
        }

        hexInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (isUpdating) return
                val text = s?.toString()?.trim() ?: ""
                if (text.length >= 6) {
                    val formattedText = if (text.startsWith("#")) text else "#$text"
                    if (formattedText.length == 7) {
                        try {
                            val parsed = Color.parseColor(formattedText)
                            currentColor = parsed
                            Color.colorToHSV(currentColor, currentHsv)
                            
                            isUpdating = true
                            hueSeekBar.progress = currentHsv[0].toInt().coerceIn(0, 360)
                            satValView.setHsv(currentHsv[0], currentHsv[1], currentHsv[2])
                            updatePreviewColor()
                            isUpdating = false
                            
                            hexInput.error = null
                        } catch (e: IllegalArgumentException) {
                            hexInput.error = getString(R.string.custom_color_invalid_hex)
                        }
                    }
                }
            }
        })
    }

    private fun updatePreviewColor() {
        (colorPreview.background as? GradientDrawable)?.setColor(currentColor)
    }

    private fun updateHexInput() {
        isUpdating = true
        // Mask out alpha to support #RRGGBB format
        val rgb = currentColor and 0x00FFFFFF
        val hex = String.format(Locale.US, "#%06X", rgb)
        hexInput.setText(hex)
        isUpdating = false
    }
}
