/*
 * Copyright (C) 2016-2026 Álinson Santos Xavier <git@axavier.org> and contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.isoron.uhabits.compose.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.isoron.uhabits.compose.LocalUHabitComposeColors
import org.isoron.uhabits.compose.LocalUHabitDesignTokens
import org.isoron.uhabits.compose.UHabitComposeColors
import org.isoron.uhabits.compose.UHabitElevationAndBorders

/**
 * Compact action or period-selector chip used in card headers.
 */
@Composable
fun UHabitActionChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    hasDropdown: Boolean = false,
    isSelected: Boolean = false,
    accentColor: Color? = null,
    colors: UHabitComposeColors = LocalUHabitComposeColors.current
) {
    val shape = LocalUHabitDesignTokens.current.shapes.sm
    val effectiveAccent = accentColor ?: Color(colors.accent)
    val chipBorderColor = when {
        isSelected -> effectiveAccent.copy(alpha = 0.50f)
        colors.isPureBlack -> Color(UHabitElevationAndBorders.AMOLED_CARD_BORDER_ARGB)
        colors.isDark -> Color.White.copy(alpha = 0.10f)
        else -> Color(colors.border).copy(alpha = 0.25f)
    }
    val chipSurfaceColor = when {
        isSelected -> effectiveAccent.copy(alpha = if (colors.isPureBlack) 0.25f else 0.15f)
        colors.isPureBlack -> Color(0xFF141414)
        colors.isDark -> Color(colors.surfaceVariant).copy(alpha = 0.25f)
        else -> Color(colors.surfaceVariant).copy(alpha = 0.35f)
    }
    val textColor = when {
        isSelected -> effectiveAccent
        else -> Color(colors.foreground).copy(alpha = 0.85f)
    }

    Row(
        modifier = modifier
            .defaultMinSize(minHeight = 32.dp)
            .clip(shape)
            .background(chipSurfaceColor)
            .border(1.dp, chipBorderColor, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BasicText(
            text = text,
            style = TextStyle(
                color = textColor,
                fontSize = 13.sp,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium
            )
        )
        if (hasDropdown) {
            Spacer(Modifier.width(5.dp))
            val chevronColor = textColor.copy(alpha = 0.80f)
            Canvas(modifier = Modifier.size(12.dp)) {
                val path = Path().apply {
                    moveTo(2.5.dp.toPx(), 4.5.dp.toPx())
                    lineTo(6.0.dp.toPx(), 8.0.dp.toPx())
                    lineTo(9.5.dp.toPx(), 4.5.dp.toPx())
                }
                drawPath(
                    path = path,
                    color = chevronColor,
                    style = Stroke(
                        width = 1.7.dp.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )
            }
        }
    }
}

/**
 * Minimalist segmented pill tab item (e.g., Stopwatch / Pomodoro mode selector).
 * Does not force an enclosing box track around the row.
 */
@Composable
fun UHabitSegmentedPillTab(
    text: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    colors: UHabitComposeColors = LocalUHabitComposeColors.current
) {
    val accent = Color(colors.accent)
    val onSurfaceVariant = Color(colors.secondary)

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) accent.copy(alpha = 0.12f) else Color.Transparent)
            .semantics {
                role = Role.Tab
                this.selected = selected
            }
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        BasicText(
            text = text,
            style = TextStyle(
                color = if (selected) accent else onSurfaceVariant,
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
            )
        )
    }
}

/**
 * Compact 40x24dp animated toggle switch matching UHabit Next's Minimal & Refined form aesthetic.
 */
@Composable
fun UHabitCompactSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    colors: UHabitComposeColors = LocalUHabitComposeColors.current
) {
    val motion = LocalUHabitDesignTokens.current.motion
    val trackBg = if (checked) Color(colors.accent) else Color(colors.secondary).copy(alpha = 0.25f)
    val thumbOffset by animateFloatAsState(
        targetValue = if (checked) 16f else 0f,
        animationSpec = motion.fastTween(),
        label = "switchOffset"
    )

    Box(
        modifier = modifier
            .size(width = 40.dp, height = 24.dp)
            .clip(CircleShape)
            .background(trackBg)
            .clickable(role = Role.Switch) { onCheckedChange(!checked) }
            .padding(3.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .padding(start = thumbOffset.dp)
                .size(18.dp)
                .clip(CircleShape)
                .background(Color.White)
        )
    }
}
