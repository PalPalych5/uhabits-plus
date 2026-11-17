/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.activities.common.icons

import android.app.Dialog
import android.content.Context
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.emoji2.emojipicker.EmojiPickerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.common.theme.MainTabsThemeBridge
import org.isoron.uhabits.compose.LocalUHabitComposeColors
import org.isoron.uhabits.compose.UHabitComposeColors
import org.isoron.uhabits.compose.UHabitComposeTheme
import org.isoron.uhabits.compose.UHabitElevationAndBorders
import org.isoron.uhabits.core.containers.IconRef

enum class CuratedIconCategory(val title: String) {
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

@Deprecated("Use CuratedIconCategory instead", ReplaceWith("CuratedIconCategory"))
typealias CuratedComposeCategory = CuratedIconCategory

/**
 * Reusable BottomSheetDialogFragment hosting the canonical Compose icon picker [UHabitIconPickerContent].
 *
 * Fully lifecycle-safe: restores selection, preview color, and request key across configuration
 * changes and process restoration.
 */
open class UHabitIconPickerBottomSheet : BottomSheetDialogFragment() {

    var onIconSelected: ((IconRef) -> Unit)? = null
    var previewColor: Int = AndroidColor.parseColor("#4A90E2")
    var initialIconRef: IconRef = IconRef.None
    var requestKey: String? = null
    var initialSearchQuery: String = ""
    var initialTab: Int = 0

    companion object {
        const val TAG = "UHabitIconPickerBottomSheet"
        const val ARG_PREVIEW_COLOR = "arg_preview_color"
        const val ARG_INITIAL_ICON = "arg_initial_icon"
        const val ARG_REQUEST_KEY = "arg_request_key"
        const val ARG_INITIAL_SEARCH_QUERY = "arg_initial_search_query"
        const val ARG_INITIAL_TAB = "arg_initial_tab"
        const val RESULT_ICON = "result_icon"

        fun newInstance(
            initialIconRef: IconRef = IconRef.None,
            previewColor: Int = AndroidColor.parseColor("#4A90E2"),
            requestKey: String? = null,
            initialSearchQuery: String = "",
            initialTab: Int = 0
        ): UHabitIconPickerBottomSheet {
            return UHabitIconPickerBottomSheet().apply {
                arguments = Bundle().apply {
                    putInt(ARG_PREVIEW_COLOR, previewColor)
                    putString(ARG_INITIAL_ICON, IconRef.toStorageString(initialIconRef))
                    if (requestKey != null) putString(ARG_REQUEST_KEY, requestKey)
                    if (initialSearchQuery.isNotEmpty()) putString(ARG_INITIAL_SEARCH_QUERY, initialSearchQuery)
                    if (initialTab != 0) putInt(ARG_INITIAL_TAB, initialTab)
                }
                this.initialIconRef = initialIconRef
                this.previewColor = previewColor
                this.requestKey = requestKey
                this.initialSearchQuery = initialSearchQuery
                this.initialTab = initialTab
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bundle = savedInstanceState ?: arguments
        if (bundle != null) {
            previewColor = bundle.getInt(ARG_PREVIEW_COLOR, previewColor)
            bundle.getString(ARG_INITIAL_ICON)?.let {
                initialIconRef = IconRef.fromString(it)
            }
            requestKey = bundle.getString(ARG_REQUEST_KEY, requestKey)
            initialSearchQuery = bundle.getString(ARG_INITIAL_SEARCH_QUERY, initialSearchQuery)
            initialTab = bundle.getInt(ARG_INITIAL_TAB, initialTab)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(ARG_PREVIEW_COLOR, previewColor)
        outState.putString(ARG_INITIAL_ICON, IconRef.toStorageString(initialIconRef))
        requestKey?.let { outState.putString(ARG_REQUEST_KEY, it) }
        if (initialSearchQuery.isNotEmpty()) outState.putString(ARG_INITIAL_SEARCH_QUERY, initialSearchQuery)
        if (initialTab != 0) outState.putInt(ARG_INITIAL_TAB, initialTab)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            if (bottomSheet != null) {
                bottomSheet.setBackgroundColor(AndroidColor.TRANSPARENT)
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
        val colors = UHabitComposeColors.fromPalette(palette)

        return ComposeView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                UHabitComposeTheme(colors = colors) {
                    UHabitIconPickerContent(
                        selectedIcon = initialIconRef,
                        tintColor = previewColor,
                        showHeader = true,
                        isBottomSheetPresentation = true,
                        showDefaultFolderBar = true,
                        initialSearchQuery = initialSearchQuery,
                        initialTab = initialTab,
                        onIconSelected = { picked ->
                            initialIconRef = picked
                            onIconSelected?.invoke(picked)
                            requestKey?.let { key ->
                                parentFragmentManager.setFragmentResult(key, Bundle().apply {
                                    putString(RESULT_ICON, IconRef.toStorageString(picked))
                                })
                            }
                            dismiss()
                        },
                        onDismiss = { dismiss() }
                    )
                }
            }
        }
    }
}

/**
 * Canonical Jetpack Compose Icon & Emoji Picker Content.
 *
 * Supports both modal bottom-sheet presentation and embedded dialog presentation without
 * creating duplicate backgrounds, nested scroll boundaries, or conflicting window insets.
 */
@Composable
fun UHabitIconPickerContent(
    selectedIcon: IconRef,
    tintColor: Int,
    onIconSelected: (IconRef) -> Unit,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
    showHeader: Boolean = true,
    isBottomSheetPresentation: Boolean = false,
    showDefaultFolderBar: Boolean = true,
    initialSearchQuery: String = "",
    initialTab: Int = 0
) {
    val colors = LocalUHabitComposeColors.current
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("icon_picker_prefs", Context.MODE_PRIVATE) }

    val defaultTab = when {
        selectedIcon is IconRef.Emoji -> 1
        initialTab != 0 -> initialTab
        else -> 0
    }
    var currentTab by rememberSaveable { mutableIntStateOf(defaultTab) } // 0 = Icons, 1 = Emoji
    var searchQuery by rememberSaveable { mutableStateOf(initialSearchQuery) }
    var selectedCategoryName by rememberSaveable { mutableStateOf(CuratedIconCategory.CURATED.name) }
    val selectedCategory = remember(selectedCategoryName) {
        try { CuratedIconCategory.valueOf(selectedCategoryName) } catch (_: Throwable) { CuratedIconCategory.CURATED }
    }
    var selectedNamespaceName by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedNamespace = remember(selectedNamespaceName) {
        selectedNamespaceName?.let { name ->
            try { IconNamespace.valueOf(name) } catch (_: Throwable) { null }
        }
    }

    var recentKeys by remember {
        mutableStateOf(
            prefs.getString("recent_icons", "").orEmpty()
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        )
    }

    val filteredIcons = remember(searchQuery, selectedCategory, selectedNamespace, recentKeys) {
        val query = searchQuery.trim().lowercase()
        when {
            query.isNotEmpty() -> {
                IconPack.search(query, IconCategory.ALL, selectedNamespace)
            }
            selectedCategory == CuratedIconCategory.CURATED -> {
                val curated = IconPack.getCuratedIcons()
                if (selectedNamespace != null) curated.filter { it.namespace == selectedNamespace } else curated
            }
            selectedCategory == CuratedIconCategory.RECENTS -> {
                val recents = recentKeys.mapNotNull { IconPack.getIcon(it) }
                if (selectedNamespace != null) recents.filter { it.namespace == selectedNamespace } else recents
            }
            selectedCategory == CuratedIconCategory.ALL -> {
                IconPack.search("", IconCategory.ALL, selectedNamespace)
            }
            else -> {
                val catIcons = IconPack.getCuratedIconsForCategory(selectedCategory.name)
                if (selectedNamespace != null) catIcons.filter { it.namespace == selectedNamespace } else catIcons
            }
        }
    }

    val filteredEmojis = remember(searchQuery) {
        EmojiCatalogData.search(searchQuery.trim())
    }

    val rootModifier = if (isBottomSheetPresentation) {
        val shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.dialogSurfaceColor)
            .let { mod ->
                if (colors.isPureBlack) {
                    mod.border(
                        width = UHabitElevationAndBorders.hairlineBorderWidth,
                        color = colors.dialogBorderColor,
                        shape = shape
                    )
                } else {
                    mod
                }
            }
            .navigationBarsPadding()
            .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 16.dp)
    } else {
        modifier
            .fillMaxWidth()
            .padding(16.dp)
    }

    Column(modifier = rootModifier) {
        // Drag Handle (only when presented as modal bottom sheet)
        if (isBottomSheetPresentation) {
            Box(
                modifier = Modifier
                    .padding(top = 2.dp, bottom = 10.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(colors.secondary).copy(alpha = 0.35f))
                    .align(Alignment.CenterHorizontally)
            )
        }

        // Header Row (Title & Close Button)
        if (showHeader) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicText(
                    text = "Выбор значка",
                    style = TextStyle(
                        color = Color(colors.foreground),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                )

                if (onDismiss != null) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .clickable { onDismiss() }
                            .padding(6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(R.drawable.ic_tabler_x),
                            contentDescription = "Закрыть",
                            colorFilter = ColorFilter.tint(Color(colors.secondary)),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        // Default Folder / Reset Row
        if (showDefaultFolderBar) {
            val isDefault = selectedIcon is IconRef.None
            val previewAccent = Color(tintColor)
            val folderBorderColor = if (isDefault) previewAccent else Color(colors.divider)
            val folderBgColor = if (isDefault) previewAccent.copy(alpha = 0.12f) else Color(colors.foreground).copy(alpha = 0.04f)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(folderBgColor)
                    .border(if (isDefault) 1.5.dp else 1.dp, folderBorderColor, RoundedCornerShape(10.dp))
                    .clickable { onIconSelected(IconRef.None) }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .semantics { contentDescription = "Стандартная папка (по умолчанию)" },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_tabler_folder),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(if (isDefault) previewAccent else Color(colors.secondary)),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                BasicText(
                    text = "Стандартная папка (по умолчанию)",
                    style = TextStyle(
                        color = if (isDefault) previewAccent else Color(colors.foreground),
                        fontSize = 13.sp,
                        fontWeight = if (isDefault) FontWeight.Bold else FontWeight.Normal
                    ),
                    modifier = Modifier.weight(1f)
                )

                if (!isDefault) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(colors.accent).copy(alpha = 0.10f))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        BasicText(
                            text = "Сбросить",
                            style = TextStyle(
                                color = Color(colors.accent),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }
                } else {
                    Image(
                        painter = painterResource(R.drawable.ic_tabler_check),
                        contentDescription = "Выбрано",
                        colorFilter = ColorFilter.tint(previewAccent),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        // Tabs: [ Иконки | Emoji ]
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp)
                .padding(bottom = 10.dp)
                .clip(RoundedCornerShape(19.dp))
                .background(Color(colors.foreground).copy(alpha = 0.06f)),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val activeTabShape = RoundedCornerShape(19.dp)
            val activeTabBorder = if (colors.isPureBlack) {
                Modifier.border(1.dp, colors.dialogBorderColor, activeTabShape)
            } else {
                Modifier.border(1.dp, Color(colors.foreground).copy(alpha = 0.10f), activeTabShape)
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(activeTabShape)
                    .then(if (currentTab == 0) Modifier.background(Color(colors.surface)).then(activeTabBorder) else Modifier)
                    .clickable { currentTab = 0 }
                    .semantics { testTag = "icon_picker_tab_icons" },
                contentAlignment = Alignment.Center
            ) {
                BasicText(
                    text = "Иконки",
                    style = TextStyle(
                        color = if (currentTab == 0) Color(colors.foreground) else Color(colors.secondary),
                        fontSize = 13.sp,
                        fontWeight = if (currentTab == 0) FontWeight.Bold else FontWeight.Normal
                    )
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(activeTabShape)
                    .then(if (currentTab == 1) Modifier.background(Color(colors.surface)).then(activeTabBorder) else Modifier)
                    .clickable { currentTab = 1 }
                    .semantics { testTag = "icon_picker_tab_emoji" },
                contentAlignment = Alignment.Center
            ) {
                BasicText(
                    text = "Emoji",
                    style = TextStyle(
                        color = if (currentTab == 1) Color(colors.foreground) else Color(colors.secondary),
                        fontSize = 13.sp,
                        fontWeight = if (currentTab == 1) FontWeight.Bold else FontWeight.Normal
                    )
                )
            }
        }

        // Search Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(colors.foreground).copy(alpha = 0.05f))
                .border(1.dp, Color(colors.divider), RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = painterResource(R.drawable.ic_tabler_search),
                contentDescription = null,
                colorFilter = ColorFilter.tint(Color(colors.secondary)),
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(8.dp))
            BasicTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .weight(1f)
                    .semantics {
                        testTag = "icon_picker_search"
                        contentDescription = "Поле поиска значков"
                    },
                textStyle = TextStyle(color = Color(colors.foreground), fontSize = 14.sp),
                singleLine = true,
                cursorBrush = SolidColor(Color(colors.accent)),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (searchQuery.isEmpty()) {
                            BasicText(
                                text = if (currentTab == 0) "Поиск значка..." else "Поиск эмодзи (огонь, бег, книга...)",
                                style = TextStyle(color = Color(colors.secondary).copy(alpha = 0.7f), fontSize = 14.sp)
                            )
                        }
                        inner()
                    }
                }
            )
            if (searchQuery.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .clickable { searchQuery = "" }
                        .padding(6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_tabler_x),
                        contentDescription = "Очистить",
                        colorFilter = ColorFilter.tint(Color(colors.secondary)),
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }

        // Library filter pills (when Icons tab and ALL category)
        if (currentTab == 0 && selectedCategory == CuratedIconCategory.ALL) {
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(
                    null to "Все библиотеки",
                    IconNamespace.LUCIDE to "Lucide",
                    IconNamespace.TABLER to "Tabler"
                ).forEach { (namespace, label) ->
                    val isSelected = selectedNamespace == namespace
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (isSelected) Color(tintColor).copy(alpha = 0.16f)
                                else Color(colors.foreground).copy(alpha = 0.04f)
                            )
                            .border(
                                width = 1.dp,
                                color = if (isSelected) Color(tintColor) else Color(colors.divider),
                                shape = RoundedCornerShape(12.dp)
                            )
                            .clickable { selectedNamespaceName = namespace?.name }
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        BasicText(
                            text = label,
                            style = TextStyle(
                                color = if (isSelected) Color(tintColor) else Color(colors.secondary),
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        )
                    }
                }
            }
        }

        // Category Filter Chips (only for Icons tab)
        if (currentTab == 0) {
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                for (cat in CuratedIconCategory.values()) {
                    val isSelected = selectedCategory == cat
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                if (isSelected) Color(tintColor).copy(alpha = 0.16f)
                                else Color(colors.foreground).copy(alpha = 0.05f)
                            )
                            .border(
                                width = 1.dp,
                                color = if (isSelected) Color(tintColor) else Color.Transparent,
                                shape = RoundedCornerShape(14.dp)
                            )
                            .clickable { selectedCategoryName = cat.name }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            .semantics { testTag = "icon_cat_${cat.name}" }
                    ) {
                        BasicText(
                            text = cat.title,
                            style = TextStyle(
                                color = if (isSelected) Color(tintColor) else Color(colors.foreground).copy(alpha = 0.8f),
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // Content Area: Grid of Icons or Emojis
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp)
        ) {
            if (currentTab == 0) {
                if (selectedCategory == CuratedIconCategory.RECENTS && filteredIcons.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        BasicText(
                            text = "Вы ещё не использовали значки",
                            style = TextStyle(color = Color(colors.secondary), fontSize = 14.sp)
                        )
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(5),
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filteredIcons, key = { it.key }) { entry ->
                            val isSelected = selectedIcon is IconRef.Vector &&
                                (selectedIcon.key == entry.key || (entry.legacyKey != null && selectedIcon.key == entry.legacyKey))
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (isSelected) Color(tintColor).copy(alpha = 0.18f)
                                        else Color(colors.foreground).copy(alpha = 0.04f)
                                    )
                                    .border(
                                        width = if (isSelected) 2.dp else 1.dp,
                                        color = if (isSelected) Color(tintColor) else Color(colors.divider),
                                        shape = RoundedCornerShape(10.dp)
                                    )
                                    .clickable {
                                        recentKeys = (listOf(entry.key) + recentKeys.filter { it != entry.key }).take(15)
                                        prefs.edit().putString("recent_icons", recentKeys.joinToString(",")).apply()
                                        onIconSelected(IconRef.Vector(entry.key))
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Image(
                                    painter = painterResource(entry.drawableRes),
                                    contentDescription = entry.labelRu,
                                    colorFilter = ColorFilter.tint(
                                        if (isSelected) Color(tintColor) else Color(colors.foreground)
                                    ),
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }
                }
            } else {
                // EMOJI Tab: AndroidX EmojiPickerView if no query, else Search Grid
                if (searchQuery.trim().isEmpty()) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            EmojiPickerView(ctx).apply {
                                emojiGridColumns = 9
                                setOnEmojiPickedListener { item ->
                                    val emojiPrefs = ctx.getSharedPreferences("androidx.emoji2.emojipicker.preferences", Context.MODE_PRIVATE)
                                    val current = emojiPrefs.getString("pref_key_recent_emoji", "")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
                                    val updated = (listOf(item.emoji) + current.filter { it != item.emoji }).take(30)
                                    emojiPrefs.edit().putString("pref_key_recent_emoji", updated.joinToString(",")).apply()
                                    onIconSelected(IconRef.Emoji(item.emoji))
                                }
                                post {
                                    val headerRv = findViewById<androidx.recyclerview.widget.RecyclerView>(androidx.emoji2.emojipicker.R.id.emoji_picker_header)
                                    val bodyRv = findViewById<androidx.recyclerview.widget.RecyclerView>(androidx.emoji2.emojipicker.R.id.emoji_picker_body)
                                    headerRv?.isNestedScrollingEnabled = false
                                    bodyRv?.isNestedScrollingEnabled = true
                                    bodyRv?.addOnItemTouchListener(object : androidx.recyclerview.widget.RecyclerView.SimpleOnItemTouchListener() {
                                        override fun onInterceptTouchEvent(rv: androidx.recyclerview.widget.RecyclerView, e: android.view.MotionEvent): Boolean {
                                            if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                                              rv.parent?.requestDisallowInterceptTouchEvent(true)
                                            }
                                            return false
                                        }
                                    })
                                    val emojiPrefs = ctx.getSharedPreferences("androidx.emoji2.emojipicker.preferences", Context.MODE_PRIVATE)
                                    val hasRecentEmoji = !emojiPrefs.getString("pref_key_recent_emoji", null).isNullOrEmpty()
                                    if (!hasRecentEmoji) {
                                        headerRv?.findViewHolderForAdapterPosition(1)?.itemView?.performClick()
                                            ?: bodyRv?.scrollToPosition(1)
                                    }
                                }
                            }
                        }
                    )
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(44.dp),
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(filteredEmojis) { emoji ->
                            val isSelected = selectedIcon is IconRef.Emoji && selectedIcon.value == emoji
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (isSelected) Color(tintColor).copy(alpha = 0.18f)
                                        else Color(colors.foreground).copy(alpha = 0.04f)
                                    )
                                    .border(
                                        width = if (isSelected) 2.dp else 1.dp,
                                        color = if (isSelected) Color(tintColor) else Color(colors.divider),
                                        shape = RoundedCornerShape(10.dp)
                                    )
                                    .clickable {
                                        val emojiPrefs = context.getSharedPreferences("androidx.emoji2.emojipicker.preferences", Context.MODE_PRIVATE)
                                        val current = emojiPrefs.getString("pref_key_recent_emoji", "")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
                                        val updated = (listOf(emoji) + current.filter { it != emoji }).take(30)
                                        emojiPrefs.edit().putString("pref_key_recent_emoji", updated.joinToString(",")).apply()
                                        onIconSelected(IconRef.Emoji(emoji))
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                BasicText(
                                    text = emoji,
                                    style = TextStyle(fontSize = 20.sp, textAlign = TextAlign.Center)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
