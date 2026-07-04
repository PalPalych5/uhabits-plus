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
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.LinearLayout.LayoutParams
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.AndroidThemeSwitcher
import org.isoron.uhabits.activities.common.dialogs.ColorPickerDialogFactory
import org.isoron.uhabits.activities.common.dialogs.CustomDialogs
import org.isoron.uhabits.activities.settings.SettingsThemePalette
import org.isoron.uhabits.activities.settings.SettingsThemePaletteResolver
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
import java.util.Locale
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
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

        val palette = SettingsThemePaletteResolver.resolve(this, component.preferences)
        binding.root.setBackgroundColor(palette.background)
        binding.toolbar.background = android.graphics.drawable.ColorDrawable(palette.background)
        binding.toolbar.setTitleTextColor(palette.onSurface)
        binding.blocksScrollView.setBackgroundColor(palette.background)
        binding.blocksContainer.setBackgroundColor(palette.background)

        window.statusBarColor = palette.background
        window.navigationBarColor = if (palette.isPureBlack) palette.background else palette.surface
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.isAppearanceLightStatusBars = !palette.isDark
        windowInsetsController.isAppearanceLightNavigationBars = !palette.isDark

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)

        val navIcon = binding.toolbar.navigationIcon
        if (navIcon != null) {
            androidx.core.graphics.drawable.DrawableCompat.setTint(navIcon, palette.onSurface)
        }

        refreshBlocks()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val palette = SettingsThemePaletteResolver.resolve(this, component.preferences)
        menu.add(0, 1, 0, R.string.add_block).apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            val icon = ContextCompat.getDrawable(this@ManageBlocksActivity, R.drawable.ic_action_add_dark)
            if (icon != null) {
                androidx.core.graphics.drawable.DrawableCompat.setTint(icon, palette.onSurface)
                setIcon(icon)
            }
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
        val allHabits = component.habitList.toList()
        binding.blocksContainer.removeAllViews()

        val palette = SettingsThemePaletteResolver.resolve(this, component.preferences)

        if (blocks.isEmpty()) {
            val emptyContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                layoutParams = LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                    topMargin = dp(48f).toInt()
                    bottomMargin = dp(48f).toInt()
                }
            }
            val emptyTextView = TextView(this).apply {
                text = getString(R.string.manage_blocks_empty_state)
                textSize = 15f
                gravity = android.view.Gravity.CENTER
                setTextColor(palette.onSurfaceVariant)
                layoutParams = LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                    leftMargin = dp(24f).toInt()
                    rightMargin = dp(24f).toInt()
                    bottomMargin = dp(16f).toInt()
                }
            }
            val createButton = Button(this).apply {
                text = getString(R.string.add_block)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(8f)
                    setStroke(dp(1f).toInt(), palette.border)
                    setColor(android.graphics.Color.TRANSPARENT)
                }
                setTextColor(palette.onSurface)
                isAllCaps = false
                setOnClickListener {
                    showAddBlockDialog()
                }
                val paddingHorizontal = dp(16f).toInt()
                val paddingVertical = dp(8f).toInt()
                setPadding(paddingHorizontal, paddingVertical, paddingHorizontal, paddingVertical)
            }
            emptyContainer.addView(emptyTextView)
            emptyContainer.addView(createButton)
            binding.blocksContainer.addView(emptyContainer)
            return
        }

        val cardContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val lp = LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                leftMargin = dp(16f).toInt()
                rightMargin = dp(16f).toInt()
                topMargin = dp(16f).toInt()
                bottomMargin = dp(16f).toInt()
            }
            layoutParams = lp
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12f)
                setColor(palette.surface)
            }
            clipToOutline = true
        }

        for ((index, block) in blocks.withIndex()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                gravity = android.view.Gravity.CENTER_VERTICAL
                minimumHeight = dp(56f).toInt()
                isClickable = true
                isFocusable = true
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
                    leftMargin = dp(16f).toInt()
                    rightMargin = dp(16f).toInt()
                }
                val resolvedColor = themeSwitcher.currentTheme.color(block.color).toInt()
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(6f)
                    setColor(resolvedColor)
                }
            }
            row.addView(colorIndicator)

            val textContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LayoutParams(0, WRAP_CONTENT, 1f).apply {
                    topMargin = dp(8f).toInt()
                    bottomMargin = dp(8f).toInt()
                }
            }
            val nameView = TextView(this).apply {
                text = getBlockDisplayName(block)
                textSize = 16f
                setTextColor(palette.onSurface)
            }
            textContainer.addView(nameView)

            val count = allHabits.count { it.blockId == block.id }
            if (count > 0) {
                val metadataView = TextView(this).apply {
                    text = getString(R.string.manage_blocks_habit_count, count)
                    textSize = 13f
                    setTextColor(palette.onSurfaceVariant)
                    layoutParams = LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                        topMargin = dp(2f).toInt()
                    }
                }
                textContainer.addView(metadataView)
            }
            row.addView(textContainer)

            val chevron = ImageView(this).apply {
                val size = dp(16f).toInt()
                layoutParams = LayoutParams(size, size).apply {
                    rightMargin = dp(16f).toInt()
                    leftMargin = dp(16f).toInt()
                }
                setImageResource(R.drawable.ic_chevron_right)
                imageTintList = ColorStateList.valueOf(palette.onSurfaceVariant)
            }
            row.addView(chevron)

            cardContainer.addView(row)

            if (index < blocks.size - 1) {
                val divider = View(this).apply {
                    layoutParams = LayoutParams(MATCH_PARENT, dp(1f).toInt()).apply {
                        leftMargin = dp(56f).toInt()
                        rightMargin = dp(16f).toInt()
                    }
                    setBackgroundColor(palette.divider)
                }
                cardContainer.addView(divider)
            }
        }
        binding.blocksContainer.addView(cardContainer)
    }

    private fun showEditBlockDialog(block: HabitBlock) {
        val repository = (component.modelFactory as SQLModelFactory).habitBlockRepository
        val syncManager = (component.modelFactory as SQLModelFactory).syncManager
        val palette = SettingsThemePaletteResolver.resolve(this, component.preferences)

        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24f).toInt(), dp(16f).toInt(), dp(24f).toInt(), dp(16f).toInt())
        }

        val nameInput = EditText(this).apply {
            setText(getBlockDisplayName(block))
            hint = getString(R.string.block_name)
            maxLines = 1
        }
        styleBlockNameInput(nameInput, palette)
        dialogView.addView(nameInput)

        var selectedColor = block.color

        // Custom Styled Color Selector Row
        val colorRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            minimumHeight = dp(48f).toInt()
            isClickable = true
            isFocusable = true
            val attrs = intArrayOf(android.R.attr.selectableItemBackground)
            val typedArray = obtainStyledAttributes(attrs)
            val backgroundResource = typedArray.getResourceId(0, 0)
            typedArray.recycle()

            val bgDrawable = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(8f)
                setColor(palette.background)
                setStroke(dp(1f).toInt(), palette.border)
            }
            background = RippleDrawable(
                ColorStateList.valueOf(ContextCompat.getColor(this@ManageBlocksActivity, if (palette.isDark) R.color.grey_800 else R.color.grey_300)),
                bgDrawable,
                null
            )

            layoutParams = LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = dp(16f).toInt()
            }
            setPadding(dp(16f).toInt(), dp(12f).toInt(), dp(16f).toInt(), dp(12f).toInt())
        }

        val colorLabel = TextView(this).apply {
            text = getString(R.string.color)
            textSize = 16f
            setTextColor(palette.onSurface)
            layoutParams = LayoutParams(0, WRAP_CONTENT, 1f)
        }
        colorRow.addView(colorLabel)

        val swatch = View(this).apply {
            val size = dp(20f).toInt()
            layoutParams = LayoutParams(size, size).apply {
                rightMargin = dp(8f).toInt()
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(4f)
                setColor(themeSwitcher.currentTheme.color(selectedColor).toInt())
            }
        }
        colorRow.addView(swatch)

        val colorChevron = ImageView(this).apply {
            val size = dp(14f).toInt()
            layoutParams = LayoutParams(size, size)
            setImageResource(R.drawable.ic_chevron_right)
            imageTintList = ColorStateList.valueOf(palette.onSurfaceVariant)
        }
        colorRow.addView(colorChevron)

        colorRow.setOnClickListener {
            val colorPickerDialogFactory = ColorPickerDialogFactory(this@ManageBlocksActivity)
            val picker = colorPickerDialogFactory.create(selectedColor, themeSwitcher.currentTheme)
            picker.setListener { paletteColor ->
                selectedColor = paletteColor
                swatch.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = binding.root.dp(4f)
                    setColor(themeSwitcher.currentTheme.color(selectedColor).toInt())
                }
            }
            picker.dismissCurrentAndShow(supportFragmentManager, "colorPicker")
        }

        dialogView.addView(colorRow)

        val dialog = CustomDialogs.showCustomViewDialog(
            context = this,
            title = getString(R.string.edit_block),
            contentView = dialogView,
            positiveText = getString(R.string.save),
            neutralText = if (block.id ?: 0L > 7L) getString(R.string.delete) else null,
            onNeutral = if (block.id ?: 0L > 7L) { {
                val deletedAt = syncManager.now()
                syncManager.enqueueBlockChange(
                    HabitBlockData(
                        id = block.id,
                        name = block.name,
                        color = block.color.paletteIndex,
                        icon = block.icon,
                        position = block.position,
                        isArchived = block.isArchived,
                        uuid = repository.findById(block.id!!)?.uuid,
                        updatedAt = deletedAt,
                        deletedAt = deletedAt
                    ),
                    "delete"
                )
                repository.softDelete(block.id!!, deletedAt)
                (component.habitList as SQLiteHabitList).reload()
                refreshBlocks()
            } } else null
        ) {}
        styleBlockNameInput(nameInput, palette)

        dialog.findViewById<Button>(R.id.button_positive)?.setOnClickListener {
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
                isArchived = block.isArchived,
                uuid = repository.findById(block.id!!)?.uuid,
                updatedAt = syncManager.now()
            )
            repository.update(updated)
            syncManager.enqueueBlockChange(updated, "block_change")
            (component.habitList as SQLiteHabitList).reload()
            refreshBlocks()
            dialog.dismiss()
        }
    }

    private fun showAddBlockDialog() {
        val repository = (component.modelFactory as SQLModelFactory).habitBlockRepository
        val syncManager = (component.modelFactory as SQLModelFactory).syncManager
        val blocks = component.habitList.getBlocks()
        val nextPosition = (blocks.maxOfOrNull { it.position } ?: -1) + 1
        val palette = SettingsThemePaletteResolver.resolve(this, component.preferences)

        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24f).toInt(), dp(16f).toInt(), dp(24f).toInt(), dp(16f).toInt())
        }

        val nameInput = EditText(this).apply {
            hint = getString(R.string.block_name)
            maxLines = 1
        }
        styleBlockNameInput(nameInput, palette)
        dialogView.addView(nameInput)

        var selectedColor = PaletteColor(8)

        // Custom Styled Color Selector Row
        val colorRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            minimumHeight = dp(48f).toInt()
            isClickable = true
            isFocusable = true
            val attrs = intArrayOf(android.R.attr.selectableItemBackground)
            val typedArray = obtainStyledAttributes(attrs)
            val backgroundResource = typedArray.getResourceId(0, 0)
            typedArray.recycle()

            val bgDrawable = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(8f)
                setColor(palette.background)
                setStroke(dp(1f).toInt(), palette.border)
            }
            background = RippleDrawable(
                ColorStateList.valueOf(ContextCompat.getColor(this@ManageBlocksActivity, if (palette.isDark) R.color.grey_800 else R.color.grey_300)),
                bgDrawable,
                null
            )

            layoutParams = LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = dp(16f).toInt()
            }
            setPadding(dp(16f).toInt(), dp(12f).toInt(), dp(16f).toInt(), dp(12f).toInt())
        }

        val colorLabel = TextView(this).apply {
            text = getString(R.string.color)
            textSize = 16f
            setTextColor(palette.onSurface)
            layoutParams = LayoutParams(0, WRAP_CONTENT, 1f)
        }
        colorRow.addView(colorLabel)

        val swatch = View(this).apply {
            val size = dp(20f).toInt()
            layoutParams = LayoutParams(size, size).apply {
                rightMargin = dp(8f).toInt()
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(4f)
                setColor(themeSwitcher.currentTheme.color(selectedColor).toInt())
            }
        }
        colorRow.addView(swatch)

        val colorChevron = ImageView(this).apply {
            val size = dp(14f).toInt()
            layoutParams = LayoutParams(size, size)
            setImageResource(R.drawable.ic_chevron_right)
            imageTintList = ColorStateList.valueOf(palette.onSurfaceVariant)
        }
        colorRow.addView(colorChevron)

        colorRow.setOnClickListener {
            val colorPickerDialogFactory = ColorPickerDialogFactory(this@ManageBlocksActivity)
            val picker = colorPickerDialogFactory.create(selectedColor, themeSwitcher.currentTheme)
            picker.setListener { paletteColor ->
                selectedColor = paletteColor
                swatch.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = binding.root.dp(4f)
                    setColor(themeSwitcher.currentTheme.color(selectedColor).toInt())
                }
            }
            picker.dismissCurrentAndShow(supportFragmentManager, "colorPicker")
        }

        dialogView.addView(colorRow)

        val dialog = CustomDialogs.showCustomViewDialog(
            context = this,
            title = getString(R.string.add_block),
            contentView = dialogView,
            positiveText = getString(R.string.save)
        ) {}
        styleBlockNameInput(nameInput, palette)

        dialog.findViewById<Button>(R.id.button_positive)?.setOnClickListener {
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
                isArchived = false,
                uuid = Uuid.random().toHexString(),
                updatedAt = syncManager.now()
            )
            val id = repository.insert(data)
            syncManager.enqueueBlockChange(data.copy(id = id), "create")
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

    private fun styleBlockNameInput(input: EditText, palette: SettingsThemePalette) {
        input.backgroundTintList = null
        input.setHintTextColor(palette.onSurfaceVariant)
        input.setTextColor(palette.onSurface)
        input.highlightColor = ColorStateList.valueOf(palette.onSurfaceVariant).defaultColor
        input.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = binding.root.dp(8f)
            setColor(palette.surfaceVariant)
            setStroke(binding.root.dp(1f).toInt().coerceAtLeast(1), palette.border)
        }
        val padding = binding.root.dp(12f).toInt()
        input.setPadding(padding, padding, padding, padding)
    }
}
