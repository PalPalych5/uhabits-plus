/*
 * Copyright (C) 2016-2026 Álinson Santos Xavier <git@axavier.org> and contributors
 *
 * This file is part of Loop Habit Tracker.
 *
 * Loop Habit Tracker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 */

package org.isoron.uhabits.activities.common.icons

import android.content.Context
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.emoji2.emojipicker.EmojiPickerView
import org.isoron.uhabits.R
import org.isoron.uhabits.compose.LocalUHabitComposeColors
import org.isoron.uhabits.core.containers.IconRef

enum class CuratedComposeCategory(val title: String) {
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

@Composable
fun UHabitIconPickerContent(
    selectedIcon: IconRef,
    tintColor: Int,
    onIconSelected: (IconRef) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalUHabitComposeColors.current
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("icon_picker_prefs", Context.MODE_PRIVATE) }

    var currentTab by remember { mutableStateOf(0) } // 0 = Icons, 1 = Emoji
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf(CuratedComposeCategory.CURATED) }
    var selectedNamespace by remember { mutableStateOf<IconNamespace?>(null) }
    var recentKeys by remember { mutableStateOf(prefs.getString("recent_icons", "").orEmpty().split(",").filter { it.isNotBlank() }) }

    val filteredIcons = remember(searchQuery, selectedCategory, selectedNamespace, recentKeys) {
        val query = searchQuery.trim().lowercase()
        when {
            query.isNotEmpty() -> {
                IconPack.search(query, IconCategory.ALL, selectedNamespace)
            }
            selectedCategory == CuratedComposeCategory.CURATED -> {
                val curated = IconPack.getCuratedIcons()
                if (selectedNamespace != null) curated.filter { it.namespace == selectedNamespace } else curated
            }
            selectedCategory == CuratedComposeCategory.RECENTS -> {
                val recents = recentKeys.mapNotNull { IconPack.getIcon(it) }
                if (selectedNamespace != null) recents.filter { it.namespace == selectedNamespace } else recents
            }
            selectedCategory == CuratedComposeCategory.ALL -> {
                IconPack.search("", IconCategory.ALL, selectedNamespace)
            }
            else -> {
                val catIcons = IconPack.getCuratedIconsForCategory(selectedCategory.name)
                if (selectedNamespace != null) catIcons.filter { it.namespace == selectedNamespace } else catIcons
            }
        }
    }

    val filteredEmojis = remember(searchQuery) {
        EmojiCatalogData.search(searchQuery)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        // Tab row: Icons vs Emoji
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(colors.foreground).copy(alpha = 0.08f))
                    .padding(2.dp)
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (currentTab == 0) Color(colors.accent).copy(alpha = 0.2f) else Color.Transparent)
                        .clickable { currentTab = 0 }
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    BasicText(
                        text = "Значки",
                        style = TextStyle(
                            color = if (currentTab == 0) Color(colors.accent) else Color(colors.secondary),
                            fontSize = 13.sp,
                            fontWeight = if (currentTab == 0) FontWeight.Bold else FontWeight.Normal
                        )
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (currentTab == 1) Color(colors.accent).copy(alpha = 0.2f) else Color.Transparent)
                        .clickable { currentTab = 1 }
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    BasicText(
                        text = "Эмодзи",
                        style = TextStyle(
                            color = if (currentTab == 1) Color(colors.accent) else Color(colors.secondary),
                            fontSize = 13.sp,
                            fontWeight = if (currentTab == 1) FontWeight.Bold else FontWeight.Normal
                        )
                    )
                }
            }

            // Button to clear icon ("Без значка")
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onIconSelected(IconRef.None) }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                BasicText(
                    text = "Без значка",
                    style = TextStyle(color = Color(colors.secondary), fontSize = 12.sp)
                )
            }
        }

        // Search input
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Color(colors.surfaceVariant))
                .border(1.dp, Color(colors.divider), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
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
                modifier = Modifier.weight(1f),
                textStyle = TextStyle(color = Color(colors.foreground), fontSize = 14.sp),
                singleLine = true,
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (searchQuery.isEmpty()) {
                            BasicText(
                                text = if (currentTab == 0) "Поиск значка..." else "Поиск эмодзи (огонь, бег, книга...)",
                                style = TextStyle(color = Color(colors.secondary), fontSize = 14.sp)
                            )
                        }
                        inner()
                    }
                }
            )
            if (searchQuery.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .clickable { searchQuery = "" }
                        .padding(4.dp)
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

        // Library filter pills (only for Icons tab when ALL is selected)
        if (currentTab == 0 && selectedCategory == CuratedComposeCategory.ALL) {
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(null to "Все библиотеки", IconNamespace.LUCIDE to "Lucide", IconNamespace.TABLER to "Tabler").forEach { (namespace, label) ->
                    val isSelected = selectedNamespace == namespace
                    Box(Modifier.clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) Color(colors.accent).copy(alpha = 0.2f) else Color(colors.surfaceVariant))
                        .clickable { selectedNamespace = namespace }.padding(horizontal = 12.dp, vertical = 6.dp)) {
                        BasicText(label, style = TextStyle(color = Color(if (isSelected) colors.accent else colors.secondary), fontSize = 11.sp))
                    }
                }
            }
        }

        // Category filter chips (only for Icons tab)
        if (currentTab == 0) {
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                for (cat in CuratedComposeCategory.values()) {
                    val isSelected = selectedCategory == cat
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (isSelected) Color(colors.accent).copy(alpha = 0.2f)
                                else Color(colors.foreground).copy(alpha = 0.04f)
                            )
                            .border(
                                width = 1.dp,
                                color = if (isSelected) Color(colors.accent) else Color(colors.divider),
                                shape = RoundedCornerShape(12.dp)
                            )
                            .clickable { selectedCategory = cat }
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        BasicText(
                            text = cat.title,
                            style = TextStyle(
                                color = if (isSelected) Color(colors.accent) else Color(colors.secondary),
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // Grid of icons or emojis
        if (currentTab == 0) {
            if (selectedCategory == CuratedComposeCategory.RECENTS && filteredIcons.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp),
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
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredIcons, key = { it.key }) { entry ->
                    val isSelected = selectedIcon is IconRef.Vector &&
                        IconPack.getIcon(selectedIcon.key)?.key == entry.key
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) Color(colors.accent).copy(alpha = 0.2f) else Color.Transparent)
                            .border(
                                width = if (isSelected) 2.dp else 1.dp,
                                color = if (isSelected) Color(colors.accent) else Color(colors.divider),
                                shape = RoundedCornerShape(8.dp)
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
                            colorFilter = ColorFilter.tint(Color(tintColor)),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
            }
        } else {
            // EMOJI Tab: Show AndroidX EmojiPickerView if no search query, else search grid
            if (searchQuery.trim().isEmpty()) {
                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp),
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
                    columns = GridCells.Adaptive(48.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(filteredEmojis) { emoji ->
                        val isSelected = selectedIcon is IconRef.Emoji && selectedIcon.value == emoji
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) Color(colors.accent).copy(alpha = 0.2f) else Color.Transparent)
                                .border(
                                    width = if (isSelected) 2.dp else 1.dp,
                                    color = if (isSelected) Color(colors.accent) else Color(colors.divider),
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .clickable {
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
