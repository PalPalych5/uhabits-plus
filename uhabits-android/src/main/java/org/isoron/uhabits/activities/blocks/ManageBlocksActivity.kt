/*
 * Copyright (C) 2016-2025 Álinson Santos Xavier <git@axavier.org>
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
package org.isoron.uhabits.activities.blocks

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.LinearLayout.LayoutParams
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.AndroidThemeSwitcher
import org.isoron.uhabits.activities.common.dialogs.ColorPickerDialogFactory
import org.isoron.uhabits.core.database.HabitBlockData
import org.isoron.uhabits.core.models.HabitBlock
import org.isoron.uhabits.core.models.PaletteColor
import org.isoron.uhabits.core.models.sqlite.SQLModelFactory
import org.isoron.uhabits.core.models.sqlite.SQLiteHabitList
import org.isoron.uhabits.databinding.ActivityManageBlocksBinding
import org.isoron.platform.gui.toInt
import org.isoron.uhabits.utils.dismissCurrentAndShow
import org.isoron.uhabits.utils.applyRootViewInsets
import org.isoron.uhabits.utils.applyToolbarInsets
import org.isoron.uhabits.utils.currentTheme
import org.isoron.uhabits.utils.dp
import org.isoron.uhabits.utils.sres

class ManageBlocksActivity : AppCompatActivity() {
    private lateinit var themeSwitcher: AndroidThemeSwitcher
    private lateinit var binding: ActivityManageBlocksBinding
    private val component
        get() = (applicationContext as HabitsApplication).component

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        themeSwitcher = AndroidThemeSwitcher(this, component.preferences)
        themeSwitcher.apply()

        binding = ActivityManageBlocksBinding.inflate(layoutInflater)
        binding.root.applyRootViewInsets()
        binding.toolbar.applyToolbarInsets()
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)

        refreshBlocks()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, R.string.add_block).apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            setIcon(R.drawable.ic_action_add_dark)
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            1 -> {
                showAddBlockDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun refreshBlocks() {
        val blocks = component.habitList.getBlocks()
        binding.blocksContainer.removeAllViews()

        for (block in blocks) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(16f).toInt(), dp(16f).toInt(), dp(16f).toInt(), dp(16f).toInt())
                isClickable = true
                val attrs = intArrayOf(android.R.attr.selectableItemBackground)
                val typedArray = obtainStyledAttributes(attrs)
                val backgroundResource = typedArray.getResourceId(0, 0)
                typedArray.recycle()
                setBackgroundResource(backgroundResource)
                setOnClickListener { showEditBlockDialog(block) }
            }

            val colorIndicator = View(this).apply {
                val size = dp(24f).toInt()
                layoutParams = LayoutParams(size, size).apply {
                    rightMargin = dp(16f).toInt()
                }
                val resolvedColor = themeSwitcher.currentTheme.color(block.color).toInt()
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(resolvedColor)
                }
            }

            val nameView = TextView(this).apply {
                text = getBlockDisplayName(block)
                textSize = 16f
                setTextColor(sres.getColor(R.attr.contrast100))
                layoutParams = LayoutParams(0, WRAP_CONTENT, 1f)
            }

            row.addView(colorIndicator)
            row.addView(nameView)
            binding.blocksContainer.addView(row)
        }
    }

    private fun showEditBlockDialog(block: HabitBlock) {
        val repository = (component.modelFactory as SQLModelFactory).habitBlockRepository

        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24f).toInt(), dp(16f).toInt(), dp(24f).toInt(), dp(16f).toInt())
        }

        val nameInput = EditText(this).apply {
            setText(getBlockDisplayName(block))
            hint = getString(R.string.block_name)
            maxLines = 1
        }
        dialogView.addView(nameInput)

        var selectedColor = block.color
        val colorButton = androidx.appcompat.widget.AppCompatButton(this).apply {
            text = getString(R.string.color)
            layoutParams = LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = dp(16f).toInt()
            }
            backgroundTintList = ColorStateList.valueOf(themeSwitcher.currentTheme.color(selectedColor).toInt())
            setOnClickListener {
                val colorPickerDialogFactory = ColorPickerDialogFactory(this@ManageBlocksActivity)
                val picker = colorPickerDialogFactory.create(selectedColor, themeSwitcher.currentTheme)
                picker.setListener { paletteColor ->
                    selectedColor = paletteColor
                    backgroundTintList = ColorStateList.valueOf(themeSwitcher.currentTheme.color(selectedColor).toInt())
                }
                picker.dismissCurrentAndShow(supportFragmentManager, "colorPicker")
            }
        }
        dialogView.addView(colorButton)

        val builder = AlertDialog.Builder(this)
            .setTitle(R.string.edit_block)
            .setView(dialogView)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(android.R.string.cancel, null)

        if (block.id ?: 0L > 7L) {
            builder.setNeutralButton(R.string.delete) { dialog, _ ->
                repository.delete(block.id!!)
                (component.habitList as SQLiteHabitList).reload()
                refreshBlocks()
                dialog.dismiss()
            }
        }

        val dialog = builder.create()
        dialog.show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name = nameInput.text.toString().trim()
            if (name.isEmpty()) {
                nameInput.error = getString(R.string.block_name_validation)
                return@setOnClickListener
            }
            val updated = HabitBlockData(
                id = block.id,
                name = name,
                color = selectedColor.paletteIndex,
                icon = block.icon,
                position = block.position,
                isArchived = block.isArchived
            )
            repository.update(updated)
            (component.habitList as SQLiteHabitList).reload()
            refreshBlocks()
            dialog.dismiss()
        }
    }

    private fun showAddBlockDialog() {
        val repository = (component.modelFactory as SQLModelFactory).habitBlockRepository
        val blocks = component.habitList.getBlocks()
        val nextPosition = (blocks.maxOfOrNull { it.position } ?: -1) + 1

        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24f).toInt(), dp(16f).toInt(), dp(24f).toInt(), dp(16f).toInt())
        }

        val nameInput = EditText(this).apply {
            hint = getString(R.string.block_name)
            maxLines = 1
        }
        dialogView.addView(nameInput)

        var selectedColor = PaletteColor(8)
        val colorButton = androidx.appcompat.widget.AppCompatButton(this).apply {
            text = getString(R.string.color)
            layoutParams = LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = dp(16f).toInt()
            }
            backgroundTintList = ColorStateList.valueOf(themeSwitcher.currentTheme.color(selectedColor).toInt())
            setOnClickListener {
                val colorPickerDialogFactory = ColorPickerDialogFactory(this@ManageBlocksActivity)
                val picker = colorPickerDialogFactory.create(selectedColor, themeSwitcher.currentTheme)
                picker.setListener { paletteColor ->
                    selectedColor = paletteColor
                    backgroundTintList = ColorStateList.valueOf(themeSwitcher.currentTheme.color(selectedColor).toInt())
                }
                picker.dismissCurrentAndShow(supportFragmentManager, "colorPicker")
            }
        }
        dialogView.addView(colorButton)

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.add_block)
            .setView(dialogView)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name = nameInput.text.toString().trim()
            if (name.isEmpty()) {
                nameInput.error = getString(R.string.block_name_validation)
                return@setOnClickListener
            }
            val data = HabitBlockData(
                name = name,
                color = selectedColor.paletteIndex,
                icon = "label",
                position = nextPosition,
                isArchived = false
            )
            repository.insert(data)
            (component.habitList as SQLiteHabitList).reload()
            refreshBlocks()
            dialog.dismiss()
        }
    }

    private fun getBlockDisplayName(block: HabitBlock): String {
        return if (block.id in 1L..7L) {
            when (block.id) {
                1L -> getString(R.string.today_section_intellect)
                2L -> getString(R.string.today_section_speech)
                3L -> getString(R.string.today_section_body)
                4L -> getString(R.string.today_section_care)
                5L -> getString(R.string.today_section_routine)
                6L -> getString(R.string.today_section_limits)
                7L -> getString(R.string.today_section_other)
                else -> block.name
            }
        } else {
            block.name
        }
    }
}
