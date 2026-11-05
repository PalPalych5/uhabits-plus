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
import org.isoron.uhabits.R
import org.isoron.uhabits.compose.LocalUHabitComposeColors
import org.isoron.uhabits.core.containers.IconRef

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
    var selectedCategory by remember { mutableStateOf<IconCategory?>(IconCategory.ALL) }
    var selectedNamespace by remember { mutableStateOf<IconNamespace?>(null) }
    var recentSelected by remember { mutableStateOf(false) }
    var recentKeys by remember { mutableStateOf(prefs.getString("recent_icons", "").orEmpty().split(",").filter { it.isNotBlank() }) }

    val categories = remember { IconCategory.entries }

    val filteredIcons = remember(searchQuery, selectedCategory, selectedNamespace, recentSelected, recentKeys) {
        val query = searchQuery.trim().lowercase()
        if (recentSelected && query.isEmpty()) {
            val recent = recentKeys.mapNotNull { IconPack.getIcon(it) }.filter { selectedNamespace == null || it.namespace == selectedNamespace }
            recent.ifEmpty { IconPack.search("", IconCategory.ALL, selectedNamespace) }
        } else IconPack.search(query, selectedCategory ?: IconCategory.ALL, selectedNamespace)
    }

    val filteredEmojis = remember(searchQuery) {
        val query = searchQuery.trim()
        if (query.isEmpty()) {
            IconPack.CURATED_EMOJIS
        } else {
            IconPack.CURATED_EMOJIS.filter { it.contains(query) }
        }
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
                                text = if (currentTab == 0) "Поиск значков..." else "Поиск эмодзи...",
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

        // Category filter chips (only for Icons tab)
        if (currentTab == 0) {
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(null to "Все", IconNamespace.LUCIDE to "Lucide", IconNamespace.TABLER to "Tabler").forEach { (namespace, label) ->
                    Box(Modifier.clip(RoundedCornerShape(8.dp))
                        .background(if (selectedNamespace == namespace) Color(colors.accent).copy(alpha = 0.2f) else Color(colors.surfaceVariant))
                        .clickable { selectedNamespace = namespace }.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        BasicText(label, style = TextStyle(color = Color(if (selectedNamespace == namespace) colors.accent else colors.secondary), fontSize = 12.sp))
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(Modifier.clip(RoundedCornerShape(12.dp))
                    .background(if (recentSelected) Color(colors.accent).copy(alpha = 0.2f) else Color(colors.foreground).copy(alpha = 0.04f))
                    .clickable { recentSelected = true }.padding(horizontal = 10.dp, vertical = 4.dp)) {
                    BasicText("Недавние", style = TextStyle(color = Color(if (recentSelected) colors.accent else colors.secondary), fontSize = 11.sp))
                }
                for (cat in categories) {
                    val isSelected = !recentSelected && selectedCategory == cat
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
                            .clickable { selectedCategory = cat; recentSelected = false }
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        BasicText(
                            text = cat.titleRu,
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
