/* Copyright (C) 2026 UHabit contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.isoron.uhabits.activities.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.isoron.uhabits.compose.LocalUHabitComposeColors
import org.isoron.uhabits.compose.UHabitComposeColors
import org.isoron.uhabits.compose.UHabitComposeTheme

@Composable
internal fun TaskCalendarControlsCompose(
    colors: UHabitComposeColors, title: String, today: Boolean, ru: Boolean,
    compactPhone: Boolean, mode: CalendarMode, scale: CalendarScale,
    onPrevious: () -> Unit, onNext: () -> Unit, onToday: () -> Unit,
    onMode: (CalendarMode) -> Unit, onScale: (CalendarScale) -> Unit
) {
    UHabitComposeTheme(colors) {
        val c = LocalUHabitComposeColors.current
        val fontScale = LocalConfiguration.current.fontScale

        Column(
            Modifier
                .fillMaxWidth()
                .background(Color(c.surface))
                .padding(horizontal = 12.dp, vertical = 4.dp)
        ) {
            // Row 1: Period Navigator & Today shortcut
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Period Navigator: ‹  Title  ›
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    // Previous period button
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .semantics {
                                contentDescription = if (ru) "Предыдущий период" else "Previous period"
                            }
                            .clickable(role = Role.Button, onClickLabel = if (ru) "Предыдущий период" else "Previous period", onClick = onPrevious),
                        contentAlignment = Alignment.Center
                    ) {
                        BasicText("‹", style = TextStyle(color = Color(c.foreground), fontSize = 22.sp, fontWeight = FontWeight.Bold))
                    }

                    // Clickable Title
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 6.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(role = Role.Button, onClickLabel = if (ru) "Перейти к сегодня" else "Go to today", onClick = onToday)
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        BasicText(
                            title,
                            style = TextStyle(
                                color = Color(c.foreground),
                                fontSize = if (fontScale > 1.15f) 14.sp else 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }

                    // Next period button
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .semantics {
                                contentDescription = if (ru) "Следующий период" else "Next period"
                            }
                            .clickable(role = Role.Button, onClickLabel = if (ru) "Следующий период" else "Next period", onClick = onNext),
                        contentAlignment = Alignment.Center
                    ) {
                        BasicText("›", style = TextStyle(color = Color(c.foreground), fontSize = 22.sp, fontWeight = FontWeight.Bold))
                    }
                }

                // Today button: sleek chip
                val todayLabel = if (ru) "Сегодня" else "Today"
                val todayBg = if (today) Color(c.accent).copy(alpha = 0.12f) else Color(c.surfaceVariant).copy(alpha = 0.5f)
                val todayTextColor = if (today) Color(c.accent) else Color(c.foreground)
                Box(
                    modifier = Modifier
                        .height(30.dp)
                        .clip(RoundedCornerShape(15.dp))
                        .background(todayBg)
                        .clickable(role = Role.Button, onClickLabel = todayLabel, onClick = onToday)
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    BasicText(
                        todayLabel,
                        style = TextStyle(
                            color = todayTextColor,
                            fontSize = 12.sp,
                            fontWeight = if (today) FontWeight.Bold else FontWeight.Medium
                        )
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            // Row 2: Mode toggle & Scale switcher
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Mode Switcher
                if (compactPhone) {
                    val target = if (mode == CalendarMode.PLANNING) CalendarMode.HISTORY else CalendarMode.PLANNING
                    val label = if (mode == CalendarMode.PLANNING) { if (ru) "План ↔" else "Plan ↔" } else { if (ru) "История ↔" else "History ↔" }
                    val desc = if (mode == CalendarMode.PLANNING) {
                        if (ru) "План, переключить на историю" else "Plan, switch to history"
                    } else {
                        if (ru) "История, переключить на план" else "History, switch to plan"
                    }
                    Box(
                        modifier = Modifier
                            .height(30.dp)
                            .clip(RoundedCornerShape(15.dp))
                            .background(Color(c.surfaceVariant).copy(alpha = 0.6f))
                            .semantics { contentDescription = desc }
                            .clickable(role = Role.Button, onClickLabel = desc) { onMode(target) }
                            .padding(horizontal = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        BasicText(
                            label,
                            style = TextStyle(color = Color(c.foreground), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        )
                    }
                } else {
                    // Wide / normal mode: segmented toggle
                    Row(
                        modifier = Modifier
                            .height(30.dp)
                            .clip(RoundedCornerShape(15.dp))
                            .background(Color(c.surfaceVariant).copy(alpha = 0.6f))
                            .padding(2.dp)
                    ) {
                        val isPlanning = mode == CalendarMode.PLANNING
                        val planLabel = if (ru) "Планирование" else "Planning"
                        val histLabel = if (ru) "История" else "History"

                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(13.dp))
                                .background(if (isPlanning) Color(c.accent) else Color.Transparent)
                                .semantics { selected = isPlanning }
                                .clickable(role = Role.Tab) { onMode(CalendarMode.PLANNING) }
                                .padding(horizontal = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            BasicText(
                                planLabel,
                                style = TextStyle(
                                    color = if (isPlanning) Color.White else Color(c.secondary),
                                    fontSize = 12.sp,
                                    fontWeight = if (isPlanning) FontWeight.SemiBold else FontWeight.Normal
                                )
                            )
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(13.dp))
                                .background(if (!isPlanning) Color(c.accent) else Color.Transparent)
                                .semantics { selected = !isPlanning }
                                .clickable(role = Role.Tab) { onMode(CalendarMode.HISTORY) }
                                .padding(horizontal = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            BasicText(
                                histLabel,
                                style = TextStyle(
                                    color = if (!isPlanning) Color.White else Color(c.secondary),
                                    fontSize = 12.sp,
                                    fontWeight = if (!isPlanning) FontWeight.SemiBold else FontWeight.Normal
                                )
                            )
                        }
                    }
                }

                Spacer(Modifier.width(8.dp))

                // Scale Switcher: Segmented pill container for Month | Week | Day
                Row(
                    modifier = Modifier
                        .height(30.dp)
                        .clip(RoundedCornerShape(15.dp))
                        .background(Color(c.surfaceVariant).copy(alpha = 0.6f))
                        .padding(2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val scaleItems = listOf(
                        CalendarScale.MONTH to (if (ru) "Месяц" else "Month"),
                        CalendarScale.WEEK to (if (ru) "Неделя" else "Week"),
                        CalendarScale.DAY to (if (ru) "День" else "Day")
                    )

                    scaleItems.forEach { (value, label) ->
                        val isSelected = scale == value
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(13.dp))
                                .background(if (isSelected) Color(c.accent) else Color.Transparent)
                                .semantics { selected = isSelected }
                                .clickable(role = Role.Tab) { onScale(value) }
                                .padding(horizontal = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            BasicText(
                                label,
                                style = TextStyle(
                                    color = if (isSelected) Color.White else Color(c.secondary),
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                    textAlign = TextAlign.Center
                                )
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            Spacer(
                Modifier
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(Color(c.divider).copy(alpha = 0.6f))
            )
        }
    }
}
