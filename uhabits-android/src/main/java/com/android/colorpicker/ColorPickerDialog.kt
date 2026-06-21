package com.android.colorpicker

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDialogFragment
import org.isoron.uhabits.R
import org.isoron.uhabits.core.models.PaletteColor
import org.isoron.uhabits.utils.StyledResources
import org.isoron.uhabits.utils.toPaletteColor
import org.isoron.uhabits.activities.common.dialogs.CustomColorPickerDialog

open class ColorPickerDialog : AppCompatDialogFragment() {

    fun interface OnColorSelectedListener {
        fun onColorSelected(color: Int)
    }

    companion object {
        const val SIZE_LARGE = 1
        const val SIZE_SMALL = 2

        protected const val KEY_TITLE_ID = "title_id"
        protected const val KEY_COLORS = "palette"
        protected const val KEY_SELECTED_COLOR = "selected_color"
        protected const val KEY_COLUMNS = "columns"
        protected const val KEY_SIZE = "size"

        fun newInstance(titleResId: Int, colors: IntArray, selectedColor: Int,
                columns: Int, size: Int): ColorPickerDialog {
            val dialog = ColorPickerDialog()
            dialog.initialize(titleResId, colors, selectedColor, columns, size)
            return dialog
        }
    }

    protected var mTitleResId = R.string.color_picker_default_title
    protected var mColors: IntArray? = null
    protected var mSelectedColor: Int = 0
    protected var mColumns: Int = 4
    protected var mSize: Int = SIZE_SMALL
    protected var mListener: OnColorSelectedListener? = null

    // 48 colors grid (6 rows of 8 columns) organized visually by hue + grayscale row
    private val paletteGrid = arrayOf(
        // Row 0: Very Light / Pastel
        PaletteColor(0xFFFFEBEE.toInt()), PaletteColor(0xFFFFF3E0.toInt()), PaletteColor(0xFFFFFDE7.toInt()), PaletteColor(0xFFF1F8E9.toInt()),
        PaletteColor(0xFFE8F5E9.toInt()), PaletteColor(0xFFE0F7FA.toInt()), PaletteColor(0xFFE8EAF6.toInt()), PaletteColor(0xFFFCE4EC.toInt()),

        // Row 1: Light
        PaletteColor(0xFFFFCDD2.toInt()), PaletteColor(1), PaletteColor(3), PaletteColor(5),
        PaletteColor(7), PaletteColor(9), PaletteColor(12), PaletteColor(15),

        // Row 2: Medium Light
        PaletteColor(0xFFE57373.toInt()), PaletteColor(2), PaletteColor(4), PaletteColor(6),
        PaletteColor(8), PaletteColor(10), PaletteColor(13), PaletteColor(0xFFF06292.toInt()),

        // Row 3: Standard
        PaletteColor(0), PaletteColor(0xFFFFA726.toInt()), PaletteColor(0xFFFDD835.toInt()), PaletteColor(0xFF558B2F.toInt()),
        PaletteColor(0xFF1B5E20.toInt()), PaletteColor(11), PaletteColor(14), PaletteColor(0xFFEC407A.toInt()),

        // Row 4: Dark
        PaletteColor(0xFFC62828.toInt()), PaletteColor(16), PaletteColor(0xFFF57F17.toInt()), PaletteColor(0xFF33691E.toInt()),
        PaletteColor(0xFF004D40.toInt()), PaletteColor(0xFF0D47A1.toInt()), PaletteColor(0xFF311B92.toInt()), PaletteColor(0xFF880E4F.toInt()),

        // Row 5: Grayscale Row
        PaletteColor(0xFFFFFFFF.toInt()), PaletteColor(19), PaletteColor(0xFFE0E0E0.toInt()), PaletteColor(18),
        PaletteColor(0xFF9E9E9E.toInt()), PaletteColor(0xFF757575.toInt()), PaletteColor(17), PaletteColor(0xFF000000.toInt())
    )

    fun initialize(titleResId: Int, colors: IntArray, selectedColor: Int, columns: Int, size: Int) {
        setArguments(titleResId, columns, size)
        setColors(colors, selectedColor)
    }

    fun setArguments(titleResId: Int, columns: Int, size: Int) {
        arguments = Bundle().apply {
            putInt(KEY_TITLE_ID, titleResId)
            putInt(KEY_COLUMNS, columns)
            putInt(KEY_SIZE, size)
        }
    }

    fun setOnColorSelectedListener(listener: OnColorSelectedListener?) {
        mListener = listener
    }

    fun setColors(colors: IntArray, selectedColor: Int) {
        mColors = colors
        mSelectedColor = selectedColor
    }

    fun setColors(colors: IntArray) {
        mColors = colors
    }

    fun setSelectedColor(color: Int) {
        mSelectedColor = color
    }

    fun getColors(): IntArray? = mColors

    fun getSelectedColor(): Int = mSelectedColor

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            mTitleResId = it.getInt(KEY_TITLE_ID, R.string.color_picker_default_title)
            mColumns = it.getInt(KEY_COLUMNS, 4)
            mSize = it.getInt(KEY_SIZE, SIZE_SMALL)
        }
        if (savedInstanceState != null) {
            mColors = savedInstanceState.getIntArray(KEY_COLORS)
            mSelectedColor = savedInstanceState.getInt(KEY_SELECTED_COLOR)
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val activity = requireActivity()
        val view = LayoutInflater.from(activity).inflate(R.layout.color_picker_dialog, null)

        val gridView = view.findViewById<GridView>(R.id.color_grid)
        val customColorButton = view.findViewById<View>(R.id.custom_color_button)
        val resetColorButton = view.findViewById<View>(R.id.reset_color_button)

        val styledRes = StyledResources(activity)
        val standardColors = styledRes.getPalette()

        fun getAndroidColor(pc: PaletteColor): Int {
            return if (pc.paletteIndex in 0..19) {
                standardColors[pc.paletteIndex]
            } else {
                pc.paletteIndex
            }
        }

        gridView.adapter = object : BaseAdapter() {
            override fun getCount(): Int = paletteGrid.size
            override fun getItem(position: Int): PaletteColor = paletteGrid[position]
            override fun getItemId(position: Int): Long = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val swatch = convertView ?: LayoutInflater.from(activity).inflate(R.layout.item_color_swatch, parent, false)
                val colorView = swatch.findViewById<ImageView>(R.id.color_picker_swatch)
                val checkmarkView = swatch.findViewById<ImageView>(R.id.color_picker_checkmark)

                val item = getItem(position)
                val colorInt = getAndroidColor(item)

                val drawables = arrayOf(activity.resources.getDrawable(R.drawable.color_picker_swatch))
                colorView.setImageDrawable(ColorStateDrawable(drawables, colorInt))

                val isSelected = (colorInt == mSelectedColor)
                checkmarkView.visibility = if (isSelected) View.VISIBLE else View.GONE

                swatch.setOnClickListener {
                    onColorSelected(colorInt)
                }

                return swatch
            }
        }

        customColorButton.setOnClickListener {
            val picker = CustomColorPickerDialog.newInstance(mSelectedColor)
            picker.listener = object : CustomColorPickerDialog.OnColorPickedListener {
                override fun onColorPicked(color: Int) {
                    onColorSelected(color)
                }
            }
            picker.show(parentFragmentManager, "customColorPicker")
        }

        resetColorButton.setOnClickListener {
            // Reset to default standard color (Red, index 0)
            val defaultColor = standardColors[0]
            onColorSelected(defaultColor)
        }

        return AlertDialog.Builder(activity)
            .setTitle(mTitleResId)
            .setView(view)
            .create()
    }

    protected open fun onColorSelected(color: Int) {
        mListener?.onColorSelected(color)
        (targetFragment as? OnColorSelectedListener)?.onColorSelected(color)
        dismiss()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putIntArray(KEY_COLORS, mColors)
        outState.putInt(KEY_SELECTED_COLOR, mSelectedColor)
    }
}
