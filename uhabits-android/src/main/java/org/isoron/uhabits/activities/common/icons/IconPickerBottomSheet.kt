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
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.emoji2.emojipicker.EmojiPickerView
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
    private var selectedCategory = CuratedCategory.CURATED
    private var selectedNamespace: IconNamespace? = null
    private var searchQuery = ""

    private lateinit var iconsAdapter: IconsGridAdapter
    private lateinit var emojiAdapter: EmojiGridAdapter
    private lateinit var recyclerView: RecyclerView
    private lateinit var emojiContainer: FrameLayout
    private var emojiPickerView: EmojiPickerView? = null
    private lateinit var categoriesScroll: HorizontalScrollView
    private lateinit var categoriesRow: LinearLayout
    private lateinit var libraryFilterRow: LinearLayout
    private lateinit var tabIconsButton: TextView
    private lateinit var tabEmojiButton: TextView
    private lateinit var searchInput: EditText
    private lateinit var clearSearchButton: ImageView
    private lateinit var emptyRecentsView: TextView

    enum class Tab { ICONS, EMOJI }

    enum class CuratedCategory(val title: String) {
        CURATED("⭐ Подборка"),
        RECENTS("🕒 Недавние"),
        STUDY("📚 Учёба"),
        WORK("💼 Работа"),
        HEALTH("❤️ Здоровье"),
        SPORT("🏃 Спорт"),
        HOME("🏠 Дом"),
        FINANCE("💰 Финансы"),
        ART("🎨 Творчество"),
        PEOPLE("👥 Отношения"),
        LEISURE("☕ Отдых"),
        TRAVEL("✈️ Путешествия"),
        ALL("🌐 Все значки")
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            if (bottomSheet != null) {
                bottomSheet.setBackgroundColor(Color.TRANSPARENT)
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
            background = GradientDrawable().apply {
                setColor(palette.surface)
                cornerRadii = floatArrayOf(dp(20f), dp(20f), dp(20f), dp(20f), 0f, 0f, 0f, 0f)
            }
            setPadding(dp(16f).toInt(), dp(12f).toInt(), dp(16f).toInt(), dp(20f).toInt())
        }

        // 1. Top Drag Handle
        val handle = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(36f).toInt(), dp(4f).toInt()).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(12f).toInt()
            }
            background = GradientDrawable().apply {
                setColor(MainTabsThemeBridge.withAlpha(palette.onSurfaceVariant, 0.35f))
                cornerRadius = dp(2f)
            }
        }
        root.addView(handle)

        // 2. Header Row: Title & Close Button
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                bottomMargin = dp(12f).toInt()
            }
        }

        val titleView = TextView(context).apply {
            text = "Выбор значка"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(palette.onSurface)
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        headerRow.addView(titleView)

        val closeBtn = ImageView(context).apply {
            val size = dp(32f).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size)
            setImageResource(R.drawable.ic_tabler_x)
            setColorFilter(palette.onSurfaceVariant)
            setPadding(dp(6f).toInt(), dp(6f).toInt(), dp(6f).toInt(), dp(6f).toInt())
            val ripple = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true)
            setBackgroundResource(ripple.resourceId)
            contentDescription = "Закрыть"
            setOnClickListener { dismiss() }
        }
        headerRow.addView(closeBtn)
        root.addView(headerRow)

        // 3. Compact Default Folder / Reset Bar
        val isDefault = (initialIconRef is IconRef.None)
        val defaultFolderBar = LinearLayout(context).apply {
            tag = "icon_picker_default_folder_bar"
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(38f).toInt()
            background = GradientDrawable().apply {
                if (isDefault) {
                    setColor(MainTabsThemeBridge.withAlpha(previewColor, 0.12f))
                    cornerRadius = dp(10f)
                    setStroke(dp(1.5f).toInt(), previewColor)
                } else {
                    setColor(MainTabsThemeBridge.withAlpha(palette.onSurface, 0.04f))
                    cornerRadius = dp(10f)
                    setStroke(dp(1f).toInt(), MainTabsThemeBridge.withAlpha(palette.onSurface, 0.08f))
                }
            }
            setPadding(dp(12f).toInt(), dp(6f).toInt(), dp(12f).toInt(), dp(6f).toInt())
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                bottomMargin = dp(10f).toInt()
            }
            val ripple = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                onIconSelected?.invoke(IconRef.None)
                dismiss()
            }
        }

        val folderIcon = ImageView(context).apply {
            val iconSize = dp(18f).toInt()
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                marginEnd = dp(8f).toInt()
            }
            setImageResource(R.drawable.ic_tabler_folder)
            setColorFilter(if (isDefault) previewColor else palette.onSurfaceVariant)
        }
        defaultFolderBar.addView(folderIcon)

        val folderText = TextView(context).apply {
            text = "Стандартная папка (по умолчанию)"
            textSize = 13f
            setTextColor(if (isDefault) previewColor else palette.onSurface)
            if (isDefault) setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        defaultFolderBar.addView(folderText)

        if (!isDefault) {
            val resetPill = TextView(context).apply {
                text = "Сбросить"
                textSize = 12f
                setTextColor(palette.accent)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(8f).toInt(), dp(4f).toInt(), dp(8f).toInt(), dp(4f).toInt())
                background = GradientDrawable().apply {
                    cornerRadius = dp(6f)
                    setColor(MainTabsThemeBridge.withAlpha(palette.accent, 0.08f))
                }
            }
            defaultFolderBar.addView(resetPill)
        } else {
            val activeBadge = ImageView(context).apply {
                val iconSize = dp(16f).toInt()
                layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
                setImageResource(R.drawable.ic_tabler_check)
                setColorFilter(previewColor)
            }
            defaultFolderBar.addView(activeBadge)
        }
        root.addView(defaultFolderBar)

        // 4. Tabs: [ Иконки | Emoji ]
        val tabsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(38f).toInt()).apply {
                bottomMargin = dp(10f).toInt()
            }
            background = GradientDrawable().apply {
                cornerRadius = dp(19f)
                setColor(MainTabsThemeBridge.withAlpha(palette.onSurface, 0.06f))
            }
        }

        tabIconsButton = TextView(context).apply {
            tag = "icon_picker_tab_icons"
            text = "Иконки"
            textSize = 13f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, MATCH_PARENT, 1f)
            setOnClickListener { switchTab(Tab.ICONS) }
        }

        tabEmojiButton = TextView(context).apply {
            tag = "icon_picker_tab_emoji"
            text = "Emoji"
            textSize = 13f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, MATCH_PARENT, 1f)
            setOnClickListener { switchTab(Tab.EMOJI) }
        }

        tabsRow.addView(tabIconsButton)
        tabsRow.addView(tabEmojiButton)
        root.addView(tabsRow)

        // 5. Search Bar
        val searchBox = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(42f).toInt()).apply {
                bottomMargin = dp(8f).toInt()
            }
            background = GradientDrawable().apply {
                cornerRadius = dp(10f)
                setColor(MainTabsThemeBridge.withAlpha(palette.onSurface, 0.05f))
                setStroke(dp(1f).toInt(), MainTabsThemeBridge.withAlpha(palette.onSurface, 0.1f))
            }
            setPadding(dp(10f).toInt(), 0, dp(10f).toInt(), 0)
        }

        val searchIcon = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(18f).toInt(), dp(18f).toInt()).apply {
                marginEnd = dp(8f).toInt()
            }
            setImageResource(R.drawable.ic_tabler_search)
            setColorFilter(palette.onSurfaceVariant)
        }
        searchBox.addView(searchIcon)

        searchInput = EditText(context).apply {
            tag = "icon_picker_search"
            hint = "Поиск значка..."
            setHintTextColor(MainTabsThemeBridge.withAlpha(palette.onSurfaceVariant, 0.6f))
            setTextColor(palette.onSurface)
            textSize = 14f
            background = null
            isSingleLine = true
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    searchQuery = s?.toString().orEmpty()
                    clearSearchButton.visibility = if (searchQuery.isNotEmpty()) View.VISIBLE else View.GONE
                    updateFilteredList()
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        searchBox.addView(searchInput)

        clearSearchButton = ImageView(context).apply {
            val btnSize = dp(20f).toInt()
            layoutParams = LinearLayout.LayoutParams(btnSize, btnSize)
            setImageResource(R.drawable.ic_tabler_x)
            setColorFilter(palette.onSurfaceVariant)
            visibility = View.GONE
            setOnClickListener {
                searchInput.setText("")
            }
        }
        searchBox.addView(clearSearchButton)
        root.addView(searchBox)

        // 6. Library Filter Pills: [ Все библиотеки | Lucide | Tabler ] (visible only when ALL icons selected)
        libraryFilterRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                bottomMargin = dp(8f).toInt()
            }
            visibility = View.GONE
        }
        setupLibraryPills(libraryFilterRow)
        root.addView(libraryFilterRow)

        // 7. Categories horizontal chips (with "⭐ Подборка" at start)
        categoriesScroll = HorizontalScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                bottomMargin = dp(8f).toInt()
            }
        }

        categoriesRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        setupCategoryChips(categoriesRow)
        categoriesScroll.addView(categoriesRow)
        root.addView(categoriesScroll)

        // 8. Content View: RecyclerView (for icons & emoji search) + EmojiPickerView
        val contentContainer = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(320f).toInt())
        }

        emptyRecentsView = TextView(context).apply {
            text = "Вы ещё не использовали значки"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(MainTabsThemeBridge.withAlpha(palette.onSurfaceVariant, 0.7f))
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            visibility = View.GONE
        }
        contentContainer.addView(emptyRecentsView)

        recyclerView = RecyclerView(context).apply {
            layoutManager = GridLayoutManager(context, 5)
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            clipToPadding = false
            setPadding(0, dp(4f).toInt(), 0, dp(8f).toInt())
        }

        iconsAdapter = IconsGridAdapter(
            context = context,
            initialIconRef = initialIconRef,
            previewColor = previewColor,
            onItemClicked = { entry ->
                saveRecentIcon(entry.key)
                onIconSelected?.invoke(IconRef.Vector(entry.key))
                dismiss()
            }
        )

        emojiAdapter = EmojiGridAdapter(
            context = context,
            initialIconRef = initialIconRef,
            previewColor = previewColor,
            onItemClicked = { emoji ->
                onIconSelected?.invoke(IconRef.Emoji(emoji))
                dismiss()
            }
        )

        recyclerView.adapter = iconsAdapter
        contentContainer.addView(recyclerView)

        // Native AndroidX EmojiPickerView container
        emojiContainer = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            visibility = View.GONE
        }
        try {
            val picker = EmojiPickerView(context).apply {
                layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                emojiGridColumns = 9
                setOnEmojiPickedListener { item ->
                    saveRecentEmoji(item.emoji)
                    onIconSelected?.invoke(IconRef.Emoji(item.emoji))
                    dismiss()
                }
            }
            emojiPickerView = picker
            emojiContainer.addView(picker)

            picker.post {
                val headerRv = picker.findViewById<RecyclerView>(androidx.emoji2.emojipicker.R.id.emoji_picker_header)
                val bodyRv = picker.findViewById<RecyclerView>(androidx.emoji2.emojipicker.R.id.emoji_picker_body)
                headerRv?.isNestedScrollingEnabled = false
                bodyRv?.isNestedScrollingEnabled = true
                bodyRv?.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
                    override fun onInterceptTouchEvent(rv: RecyclerView, e: android.view.MotionEvent): Boolean {
                        if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                            rv.parent?.requestDisallowInterceptTouchEvent(true)
                        }
                        return false
                    }
                })

                val emojiPrefs = context.getSharedPreferences("androidx.emoji2.emojipicker.preferences", Context.MODE_PRIVATE)
                val hasRecentEmoji = !emojiPrefs.getString("pref_key_recent_emoji", null).isNullOrEmpty()
                if (!hasRecentEmoji) {
                    headerRv?.findViewHolderForAdapterPosition(1)?.itemView?.performClick()
                        ?: bodyRv?.scrollToPosition(1)
                }
            }
        } catch (_: Throwable) {
            // Fallback gracefully
        }
        contentContainer.addView(emojiContainer)

        root.addView(contentContainer)

        // Initial setup
        switchTab(Tab.ICONS)
        updateLibraryPills()
        updateCategoryChips()
        updateFilteredList()

        return root
    }

    private fun setupLibraryPills(container: LinearLayout) {
        val context = requireContext()
        val options = listOf(
            null to "Все библиотеки",
            IconNamespace.LUCIDE to "Lucide",
            IconNamespace.TABLER to "Tabler"
        )

        for ((ns, title) in options) {
            val pill = TextView(context).apply {
                text = title
                textSize = 11f
                tag = ns
                setPadding(dp(10f).toInt(), dp(4f).toInt(), dp(10f).toInt(), dp(4f).toInt())
                layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                    marginEnd = dp(6f).toInt()
                }
                setOnClickListener {
                    selectedNamespace = ns
                    updateLibraryPills()
                    updateFilteredList()
                }
            }
            container.addView(pill)
        }
    }

    private fun updateLibraryPills() {
        val palette = MainTabsThemeBridge.resolve(requireContext())
        for (i in 0 until libraryFilterRow.childCount) {
            val pill = libraryFilterRow.getChildAt(i) as TextView
            val ns = pill.tag as? IconNamespace
            val isSelected = (ns == selectedNamespace)

            pill.background = GradientDrawable().apply {
                cornerRadius = dp(12f)
                if (isSelected) {
                    setColor(MainTabsThemeBridge.withAlpha(previewColor, 0.16f))
                    setStroke(dp(1f).toInt(), previewColor)
                } else {
                    setColor(MainTabsThemeBridge.withAlpha(palette.onSurface, 0.04f))
                    setStroke(dp(1f).toInt(), MainTabsThemeBridge.withAlpha(palette.onSurface, 0.08f))
                }
            }
            pill.setTextColor(if (isSelected) previewColor else MainTabsThemeBridge.withAlpha(palette.onSurface, 0.65f))
            pill.setTypeface(null, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    private fun setupCategoryChips(container: LinearLayout) {
        val context = requireContext()
        container.removeAllViews()

        for (category in CuratedCategory.values()) {
            val chip = TextView(context).apply {
                text = category.title
                textSize = 12f
                tag = category
                setPadding(dp(12f).toInt(), dp(6f).toInt(), dp(12f).toInt(), dp(6f).toInt())
                layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                    marginEnd = dp(6f).toInt()
                }
                setOnClickListener {
                    selectedCategory = category
                    updateCategoryChips()
                    updateLibraryPillsVisibility()
                    updateFilteredList()
                }
            }
            container.addView(chip)
        }
    }

    private fun updateCategoryChips() {
        val palette = MainTabsThemeBridge.resolve(requireContext())

        for (i in 0 until categoriesRow.childCount) {
            val chip = categoriesRow.getChildAt(i) as TextView
            val cat = chip.tag as? CuratedCategory
            val isSelected = (cat == selectedCategory)

            chip.background = GradientDrawable().apply {
                cornerRadius = dp(14f)
                if (isSelected) {
                    setColor(MainTabsThemeBridge.withAlpha(previewColor, 0.16f))
                    setStroke(dp(1f).toInt(), previewColor)
                } else {
                    setColor(MainTabsThemeBridge.withAlpha(palette.onSurface, 0.05f))
                }
            }
            chip.setTextColor(if (isSelected) previewColor else MainTabsThemeBridge.withAlpha(palette.onSurface, 0.7f))
            chip.setTypeface(null, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    private fun updateLibraryPillsVisibility() {
        libraryFilterRow.visibility = if (currentTab == Tab.ICONS && selectedCategory == CuratedCategory.ALL) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    private fun switchTab(tab: Tab) {
        currentTab = tab
        val context = requireContext()
        val palette = MainTabsThemeBridge.resolve(context)

        val activeBg = GradientDrawable().apply {
            cornerRadius = dp(19f)
            setColor(palette.surface)
            setStroke(dp(1f).toInt(), MainTabsThemeBridge.withAlpha(palette.onSurface, 0.12f))
        }

        if (tab == Tab.ICONS) {
            tabIconsButton.background = activeBg
            tabIconsButton.setTextColor(palette.onSurface)
            tabIconsButton.setTypeface(null, Typeface.BOLD)

            tabEmojiButton.background = null
            tabEmojiButton.setTextColor(MainTabsThemeBridge.withAlpha(palette.onSurface, 0.55f))
            tabEmojiButton.setTypeface(null, Typeface.NORMAL)

            categoriesScroll.visibility = View.VISIBLE
            updateLibraryPillsVisibility()
            searchInput.hint = "Поиск значка..."
            recyclerView.isNestedScrollingEnabled = true
            emojiPickerView?.findViewById<RecyclerView>(androidx.emoji2.emojipicker.R.id.emoji_picker_body)?.isNestedScrollingEnabled = false
        } else {
            tabEmojiButton.background = activeBg
            tabEmojiButton.setTextColor(palette.onSurface)
            tabEmojiButton.setTypeface(null, Typeface.BOLD)

            tabIconsButton.background = null
            tabIconsButton.setTextColor(MainTabsThemeBridge.withAlpha(palette.onSurface, 0.55f))
            tabIconsButton.setTypeface(null, Typeface.NORMAL)

            categoriesScroll.visibility = View.GONE
            libraryFilterRow.visibility = View.GONE
            searchInput.hint = "Поиск эмодзи (огонь, бег, книга...)"
            recyclerView.isNestedScrollingEnabled = false
            emojiPickerView?.findViewById<RecyclerView>(androidx.emoji2.emojipicker.R.id.emoji_picker_body)?.isNestedScrollingEnabled = true
            emojiPickerView?.findViewById<RecyclerView>(androidx.emoji2.emojipicker.R.id.emoji_picker_header)?.isNestedScrollingEnabled = false
        }
        updateFilteredList()
    }

    private fun getRecentIcons(): List<IconEntry> {
        val prefs = requireContext().getSharedPreferences("icon_picker_prefs", Context.MODE_PRIVATE)
        val csv = prefs.getString("recent_icons", "") ?: ""
        return csv.split(",").mapNotNull { IconPack.getIcon(it.trim()) }
    }

    private fun saveRecentIcon(key: String) {
        val prefs = requireContext().getSharedPreferences("icon_picker_prefs", Context.MODE_PRIVATE)
        val current = prefs.getString("recent_icons", "")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        val updated = (listOf(key) + current.filter { it != key }).take(15)
        prefs.edit().putString("recent_icons", updated.joinToString(",")).apply()
    }

    private fun saveRecentEmoji(emoji: String) {
        val prefs = requireContext().getSharedPreferences("androidx.emoji2.emojipicker.preferences", Context.MODE_PRIVATE)
        val current = prefs.getString("pref_key_recent_emoji", "")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        val updated = (listOf(emoji) + current.filter { it != emoji }).take(30)
        prefs.edit().putString("pref_key_recent_emoji", updated.joinToString(",")).apply()
    }

    private fun updateFilteredList() {
        if (currentTab == Tab.ICONS) {
            recyclerView.visibility = View.VISIBLE
            emojiContainer.visibility = View.GONE
            recyclerView.adapter = iconsAdapter

            val results = when {
                searchQuery.isNotEmpty() -> {
                    // Full-catalog search across all icons
                    IconPack.search(searchQuery, IconCategory.ALL, selectedNamespace)
                }
                selectedCategory == CuratedCategory.CURATED -> {
                    val curated = IconPack.getCuratedIcons()
                    if (selectedNamespace != null) curated.filter { it.namespace == selectedNamespace } else curated
                }
                selectedCategory == CuratedCategory.RECENTS -> {
                    // Strictly user-selected icons only, no fake/curated fallback
                    val recents = getRecentIcons()
                    if (selectedNamespace != null) recents.filter { it.namespace == selectedNamespace } else recents
                }
                selectedCategory == CuratedCategory.ALL -> {
                    IconPack.search("", IconCategory.ALL, selectedNamespace)
                }
                else -> {
                    val catIcons = IconPack.getCuratedIconsForCategory(selectedCategory.name)
                    if (selectedNamespace != null) catIcons.filter { it.namespace == selectedNamespace } else catIcons
                }
            }

            val isRecentsEmpty = (selectedCategory == CuratedCategory.RECENTS && results.isEmpty())
            if (isRecentsEmpty) {
                recyclerView.visibility = View.GONE
                emptyRecentsView.visibility = View.VISIBLE
            } else {
                recyclerView.visibility = View.VISIBLE
                emptyRecentsView.visibility = View.GONE
            }
            iconsAdapter.setItems(results)
        } else {
            val query = searchQuery.trim()
            if (query.isEmpty() && emojiPickerView != null) {
                recyclerView.visibility = View.GONE
                emojiContainer.visibility = View.VISIBLE
            } else {
                recyclerView.visibility = View.VISIBLE
                emojiContainer.visibility = View.GONE
                recyclerView.adapter = emojiAdapter
                val emojis = EmojiCatalogData.search(query)
                emojiAdapter.setItems(emojis)
            }
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
                val size = context.dp(52f).toInt()
                layoutParams = RecyclerView.LayoutParams(MATCH_PARENT, size)
            }
            val iconView = ImageView(context).apply {
                val size = context.dp(44f).toInt()
                layoutParams = FrameLayout.LayoutParams(size, size, Gravity.CENTER)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(context.dp(10f).toInt(), context.dp(10f).toInt(), context.dp(10f).toInt(), context.dp(10f).toInt())
            }
            frame.addView(iconView)
            return ViewHolder(frame, iconView)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            val isSelected = when (initialIconRef) {
                is IconRef.Vector -> {
                    initialIconRef.key == item.key || (item.legacyKey != null && initialIconRef.key == item.legacyKey)
                }
                else -> false
            }
            val palette = MainTabsThemeBridge.resolve(context)

            holder.iconView.setImageResource(item.drawableRes)
            holder.iconView.setColorFilter(if (isSelected) previewColor else palette.onSurface)
            holder.iconView.contentDescription = item.labelRu

            holder.iconView.background = GradientDrawable().apply {
                cornerRadius = context.dp(10f)
                if (isSelected) {
                    setColor(MainTabsThemeBridge.withAlpha(previewColor, 0.18f))
                    setStroke(context.dp(2f).toInt(), previewColor)
                } else {
                    setColor(MainTabsThemeBridge.withAlpha(palette.onSurface, 0.04f))
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
        private val previewColor: Int,
        private val onItemClicked: (String) -> Unit
    ) : RecyclerView.Adapter<EmojiGridAdapter.ViewHolder>() {

        private var items = listOf<String>()

        fun setItems(newItems: List<String>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val frame = FrameLayout(context).apply {
                val size = context.dp(52f).toInt()
                layoutParams = RecyclerView.LayoutParams(MATCH_PARENT, size)
            }
            val textView = TextView(context).apply {
                val size = context.dp(44f).toInt()
                layoutParams = FrameLayout.LayoutParams(size, size, Gravity.CENTER)
                gravity = Gravity.CENTER
                textSize = 22f
            }
            frame.addView(textView)
            return ViewHolder(frame, textView)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val emoji = items[position]
            val isSelected = initialIconRef is IconRef.Emoji && initialIconRef.value == emoji
            val palette = MainTabsThemeBridge.resolve(context)

            holder.textView.text = emoji
            holder.textView.contentDescription = emoji

            holder.textView.background = GradientDrawable().apply {
                cornerRadius = context.dp(10f)
                if (isSelected) {
                    setColor(MainTabsThemeBridge.withAlpha(previewColor, 0.18f))
                    setStroke(context.dp(2f).toInt(), previewColor)
                } else {
                    setColor(MainTabsThemeBridge.withAlpha(palette.onSurface, 0.04f))
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
