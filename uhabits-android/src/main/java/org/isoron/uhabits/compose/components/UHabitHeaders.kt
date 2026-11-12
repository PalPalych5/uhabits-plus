/*
 * Copyright (C) 2016-2026 Álinson Santos Xavier <git@axavier.org> and contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.isoron.uhabits.compose.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.isoron.uhabits.compose.LocalUHabitComposeColors
import org.isoron.uhabits.compose.UHabitComposeColors

/**
 * Minimalist typographic section heading.
 *
 * Design rule: Does NOT force any decorative icon, pill, badge, border, or enclosing card.
 * Optional [trailing] slot allows callers (such as statistics cards) to attach a period selector
 * or edit action when needed.
 */
@Composable
fun UHabitSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    colors: UHabitComposeColors = LocalUHabitComposeColors.current,
    fontSize: TextUnit = 15.sp,
    bottomPadding: Dp = 12.dp,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = bottomPadding),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        BasicText(
            text = title,
            style = TextStyle(
                color = Color(colors.foreground),
                fontSize = fontSize,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.15.sp
            )
        )
        if (trailing != null) {
            trailing()
        }
    }
}

/**
 * Explicit variant for uppercase group/section headers in lists (e.g., Life Spheres / Containers
 * in the Habit List), with an optional leading dot indicator and optional item count badge.
 */
@Composable
fun UHabitOverlineGroupHeader(
    title: String,
    modifier: Modifier = Modifier,
    count: Int? = null,
    showLeadingDot: Boolean = false,
    colors: UHabitComposeColors = LocalUHabitComposeColors.current
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f, fill = false)
        ) {
            if (showLeadingDot) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(Color(colors.secondary))
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            BasicText(
                text = title,
                style = TextStyle(
                    color = Color(colors.secondary).copy(alpha = 0.85f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.2.sp
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (count != null && count > 0) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(colors.surfaceVariant))
                    .padding(horizontal = 7.dp, vertical = 2.dp)
            ) {
                BasicText(
                    text = count.toString(),
                    style = TextStyle(
                        color = Color(colors.secondary),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                )
            }
        }
    }
}
