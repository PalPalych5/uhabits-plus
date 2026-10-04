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

package org.isoron.uhabits.activities.common.icons

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.setPadding
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.common.theme.MainTabsThemeBridge
import org.isoron.uhabits.core.containers.IconRef

private fun Context.dp(v: Float): Float = v * resources.displayMetrics.density

class IconPickerBottomSheet : BottomSheetDialogFragment() {

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    var onIconSelected: ((IconRef) -> Unit)? = null
    var previewColor: Int = Color.parseColor("#4A90E2")
    var initialIconRef: IconRef = IconRef.None

    private var currentTab = Tab.ICONS
    private var selectedCategory = IconCategory.ALL
    private var searchQuery = ""

    private lateinit var iconsAdapter: IconsGridAdapter
    private lateinit var emojiAdapter: EmojiGridAdapter
    private lateinit var recyclerView: RecyclerView
    private lateinit var categoriesScroll: HorizontalScrollView
    private lateinit var tabIconsButton: TextView
    private lateinit var tabEmojiButton: TextView
    private lateinit var searchInput: EditText

    enum class Tab { ICONS, EMOJI }

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

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.surface)
            setPadding(dp(16f).toInt(), dp(12f).toInt(), dp(16f).toInt(), dp(16f).toInt())
        }

        // 1. Header with Drag Handle, Title and "Remove icon" button
        val handle = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(36f).toInt(), dp(4f).toInt()).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(12f).toInt()
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(2f)
                setColor(MainTabsThemeBridge.withAlpha(palette.onSurface, 0.2f))
            }
        }
        root.addView(handle)

        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(12f).toInt()
            }
        }

        val titleView = TextView(context).apply {
            text = "Выбрать значок"
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(palette.onSurface)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerRow.addView(titleView)

        val clearButton = TextView(context).apply {
            text = "Без значка"
            textSize = 14f
            setTextColor(MainTabsThemeBridge.withAlpha(palette.onSurface, 0.6f))
            setPadding(dp(8f).toInt(), dp(4f).toInt(), dp(8f).toInt(), dp(4f).toInt())
            setOnClickListener {
                onIconSelected?.invoke(IconRef.None)
                dismiss()
            }
        }
        headerRow.addView(clearButton)
        root.addView(headerRow)

        // 2. Tabs: [ Иконки | Emoji ]
        val tabsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(36f).toInt()
            ).apply {
                bottomMargin = dp(12f).toInt()
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(18f)
                setColor(MainTabsThemeBridge.withAlpha(palette.onSurface, 0.08f))
            }
        }

        tabIconsButton = TextView(context).apply {
            text = "Иконки"
            textSize = 14f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            setOnClickListener { switchTab(Tab.ICONS) }
        }

        tabEmojiButton = TextView(context).apply {
            text = "Emoji"
            textSize = 14f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            setOnClickListener { switchTab(Tab.EMOJI) }
        }

        tabsRow.addView(tabIconsButton)
        tabsRow.addView(tabEmojiButton)
        root.addView(tabsRow)

        // 3. Search Bar
        val searchBox = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(42f).toInt()
            ).apply {
                bottomMargin = dp(10f).toInt()
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10f)
                setColor(MainTabsThemeBridge.withAlpha(palette.onSurface, 0.06f))
            }
            setPadding(dp(12f).toInt(), 0, dp(12f).toInt(), 0)
        }

        val searchIcon = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(18f).toInt(), dp(18f).toInt()).apply {
                marginEnd = dp(8f).toInt()
            }
            setImageDrawable(AppCompatResources.getDrawable(context, R.drawable.ic_toolbar_search))
            setColorFilter(MainTabsThemeBridge.withAlpha(palette.onSurface, 0.5f))
        }
        searchBox.addView(searchIcon)

        searchInput = EditText(context).apply {
            hint = "Поиск значков..."
            setHintTextColor(MainTabsThemeBridge.withAlpha(palette.onSurface, 0.4f))
            setTextColor(palette.onSurface)
            textSize = 14f
            background = null
            isSingleLine = true
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    searchQuery = s?.toString().orEmpty()
                    updateFilteredList()
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        searchBox.addView(searchInput)
        root.addView(searchBox)

        // 4. Categories horizontal chips
        categoriesScroll = HorizontalScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(10f).toInt()
            }
        }

        val categoriesRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        for (category in IconCategory.values()) {
            val chip = TextView(context).apply {
                text = category.titleRu
                textSize = 13f
                tag = category
                setPadding(dp(12f).toInt(), dp(6f).toInt(), dp(12f).toInt(), dp(6f).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = dp(6f).toInt()
                }
                setOnClickListener {
                    selectedCategory = category
                    updateCategoryChips(categoriesRow)
                    updateFilteredList()
                }
            }
            categoriesRow.addView(chip)
        }
        categoriesScroll.addView(categoriesRow)
        root.addView(categoriesScroll)

        // 5. Grid of icons
        recyclerView = RecyclerView(context).apply {
            layoutManager = GridLayoutManager(context, 5)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(280f).toInt()
            )
            clipToPadding = false
            setPadding(0, dp(4f).toInt(), 0, dp(8f).toInt())
        }

        iconsAdapter = IconsGridAdapter(
            context = context,
            initialIconRef = initialIconRef,
            previewColor = previewColor,
            onItemClicked = { entry ->
                onIconSelected?.invoke(IconRef.Vector(entry.key))
                dismiss()
            }
        )

        emojiAdapter = EmojiGridAdapter(
            context = context,
            initialIconRef = initialIconRef,
            onItemClicked = { emoji ->
                onIconSelected?.invoke(IconRef.Emoji(emoji))
                dismiss()
            }
        )

        recyclerView.adapter = iconsAdapter
        root.addView(recyclerView)

        // Initial setup
        switchTab(Tab.ICONS)
        updateCategoryChips(categoriesRow)
        updateFilteredList()

        return root
    }

    private fun switchTab(tab: Tab) {
        currentTab = tab
        val context = requireContext()
        val palette = MainTabsThemeBridge.resolve(context)

        val activeBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(18f)
            setColor(palette.surface)
            setStroke(dp(1f).toInt(), MainTabsThemeBridge.withAlpha(palette.onSurface, 0.12f))
        }

        if (tab == Tab.ICONS) {
            tabIconsButton.background = activeBg
            tabIconsButton.setTextColor(palette.onSurface)
            tabIconsButton.setTypeface(null, android.graphics.Typeface.BOLD)

            tabEmojiButton.background = null
            tabEmojiButton.setTextColor(MainTabsThemeBridge.withAlpha(palette.onSurface, 0.5f))
            tabEmojiButton.setTypeface(null, android.graphics.Typeface.NORMAL)

            categoriesScroll.visibility = View.VISIBLE
            recyclerView.adapter = iconsAdapter
        } else {
            tabEmojiButton.background = activeBg
            tabEmojiButton.setTextColor(palette.onSurface)
            tabEmojiButton.setTypeface(null, android.graphics.Typeface.BOLD)

            tabIconsButton.background = null
            tabIconsButton.setTextColor(MainTabsThemeBridge.withAlpha(palette.onSurface, 0.5f))
            tabIconsButton.setTypeface(null, android.graphics.Typeface.NORMAL)

            categoriesScroll.visibility = View.GONE
            recyclerView.adapter = emojiAdapter
        }
        updateFilteredList()
    }

    private fun updateCategoryChips(container: LinearLayout) {
        val context = requireContext()
        val palette = MainTabsThemeBridge.resolve(context)

        for (i in 0 until container.childCount) {
            val chip = container.getChildAt(i) as TextView
            val cat = chip.tag as IconCategory
            val isSelected = cat == selectedCategory

            chip.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(14f)
                if (isSelected) {
                    setColor(MainTabsThemeBridge.withAlpha(previewColor, 0.18f))
                    setStroke(dp(1f).toInt(), previewColor)
                } else {
                    setColor(MainTabsThemeBridge.withAlpha(palette.onSurface, 0.06f))
                }
            }
            chip.setTextColor(if (isSelected) previewColor else MainTabsThemeBridge.withAlpha(palette.onSurface, 0.7f))
            chip.setTypeface(null, if (isSelected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
    }

    private fun updateFilteredList() {
        if (currentTab == Tab.ICONS) {
            val results = IconPack.search(searchQuery, selectedCategory)
            iconsAdapter.setItems(results)
        } else {
            val query = searchQuery.trim().lowercase()
            val emojis = if (query.isEmpty()) {
                IconPack.CURATED_EMOJIS
            } else {
                IconPack.CURATED_EMOJIS.filter { it.contains(query) }
            }
            emojiAdapter.setItems(emojis)
        }
    }

    // ========================================================================
    // Adapters
    // ========================================================================

    private class IconsGridAdapter(
        private val context: Context,
        private val initialIconRef: IconRef,
        private val previewColor: Int,
        private val onItemClicked: (IconEntry) -> Unit
    ) : RecyclerView.Adapter<IconsGridAdapter.ViewHolder>() {

        private var items = listOf<IconEntry>()

        fun setItems(newItems: List<IconEntry>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val frame = FrameLayout(context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    context.dp(52f).toInt()
                )
            }
            val iconView = ImageView(context).apply {
                val size = context.dp(44f).toInt()
                layoutParams = FrameLayout.LayoutParams(size, size).apply {
                    gravity = Gravity.CENTER
                }
                setPadding(context.dp(10f).toInt())
            }
            frame.addView(iconView)
            return ViewHolder(frame, iconView)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            val isSelected = initialIconRef is IconRef.Vector && initialIconRef.key == item.key

            holder.iconView.setImageResource(item.drawableRes)
            holder.iconView.setColorFilter(previewColor)

            holder.iconView.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = context.dp(10f)
                val alpha = if (isSelected) 0.28f else 0.12f
                setColor(MainTabsThemeBridge.withAlpha(previewColor, alpha))
                if (isSelected) {
                    setStroke(context.dp(2f).toInt(), previewColor)
                }
            }

            holder.itemView.setOnClickListener {
                onItemClicked(item)
            }
        }

        override fun getItemCount(): Int = items.size

        class ViewHolder(itemView: View, val iconView: ImageView) : RecyclerView.ViewHolder(itemView)
    }

    private class EmojiGridAdapter(
        private val context: Context,
        private val initialIconRef: IconRef,
        private val onItemClicked: (String) -> Unit
    ) : RecyclerView.Adapter<EmojiGridAdapter.ViewHolder>() {

        private var items = listOf<String>()

        fun setItems(newItems: List<String>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val frame = FrameLayout(context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    context.dp(52f).toInt()
                )
            }
            val textView = TextView(context).apply {
                val size = context.dp(44f).toInt()
                layoutParams = FrameLayout.LayoutParams(size, size).apply {
                    gravity = Gravity.CENTER
                }
                gravity = Gravity.CENTER
                textSize = 22f
            }
            frame.addView(textView)
            return ViewHolder(frame, textView)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val emoji = items[position]
            val isSelected = initialIconRef is IconRef.Emoji && initialIconRef.value == emoji

            holder.textView.text = emoji

            val palette = MainTabsThemeBridge.resolve(context)
            holder.textView.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = context.dp(10f)
                if (isSelected) {
                    setColor(MainTabsThemeBridge.withAlpha(palette.accent, 0.2f))
                    setStroke(context.dp(2f).toInt(), palette.accent)
                } else {
                    setColor(MainTabsThemeBridge.withAlpha(palette.onSurface, 0.06f))
                }
            }

            holder.itemView.setOnClickListener {
                onItemClicked(emoji)
            }
        }

        override fun getItemCount(): Int = items.size

        class ViewHolder(itemView: View, val textView: TextView) : RecyclerView.ViewHolder(itemView)
    }
}
