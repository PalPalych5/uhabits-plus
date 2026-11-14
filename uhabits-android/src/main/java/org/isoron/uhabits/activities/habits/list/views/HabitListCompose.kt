/*
 * Copyright (C) 2016-2026 Álinson Santos Xavier <git@axavier.org> and contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.isoron.uhabits.activities.habits.list.views

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.common.icons.IconPack
import org.isoron.uhabits.compose.LocalUHabitComposeColors
import org.isoron.uhabits.compose.UHabitComposeColors
import org.isoron.uhabits.compose.components.UHabitInteractiveRowCard
import org.isoron.uhabits.compose.components.UHabitOverlineGroupHeader
import org.isoron.uhabits.core.containers.IconRef

/**
 * High-performance, Minimal & Refined Jetpack Compose components for the primary Habit List screen.
 */

// ============================================================================
// 1. Habit Progress Indicator (Score Ring)
// ============================================================================

@Composable
fun HabitProgressIndicator(
    score: Float,
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 15.dp,
    thickness: Dp = 3.dp,
    trackAlpha: Float = 0.15f
) {
    val clampedScore = score.coerceIn(0f, 1f)

    Canvas(modifier = modifier.size(size)) {
        val strokeWidth = thickness.toPx()
        val diameter = this.size.minDimension - strokeWidth
        val radius = diameter / 2f
        val arcTopLeft = Offset((this.size.width - diameter) / 2f, (this.size.height - diameter) / 2f)
        val arcSize = Size(diameter, diameter)

        // 1. Subtle background ring
        drawCircle(
            color = color.copy(alpha = trackAlpha),
            radius = radius,
            center = center,
            style = Stroke(width = strokeWidth)
        )

        // 2. Foreground progress
        if (clampedScore >= 1f) {
            // Seamless full circle without cap overlap artifacts
            drawCircle(
                color = color,
                radius = radius,
                center = center,
                style = Stroke(width = strokeWidth)
            )
        } else if (clampedScore > 0f) {
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = clampedScore * 360f,
                useCenter = false,
                topLeft = arcTopLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
        }
    }
}

// ============================================================================
// 2. Habit Icon Badge
// ============================================================================

@Composable
fun HabitIcon(
    iconRef: IconRef?,
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp
) {
    if (iconRef == null || iconRef is IconRef.None) return

    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center
    ) {
        when (iconRef) {
            is IconRef.Vector -> {
                val res = IconPack.getDrawableRes(iconRef.key, IconPack.DEFAULT_HABIT_ICON_RES)
                androidx.compose.foundation.Image(
                    painter = painterResource(id = res),
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(color)
                )
            }
            is IconRef.Emoji -> {
                BasicText(
                    text = iconRef.value,
                    style = TextStyle(fontSize = 13.sp, textAlign = TextAlign.Center)
                )
            }
            IconRef.None -> {}
        }
    }
}

// ============================================================================
// 3. Habit Section Header
// ============================================================================

@Composable
fun HabitSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    count: Int? = null,
    colors: UHabitComposeColors = LocalUHabitComposeColors.current
) {
    UHabitOverlineGroupHeader(
        title = title,
        modifier = modifier,
        count = count,
        colors = colors
    )
}

// ============================================================================
// 4. Habit List Empty State
// ============================================================================

enum class HabitListEmptyMode {
    EMPTY,
    ALL_DONE,
    SEARCH_NOT_FOUND
}

@Composable
fun HabitListEmptyState(
    mode: HabitListEmptyMode,
    modifier: Modifier = Modifier,
    colors: UHabitComposeColors = LocalUHabitComposeColors.current
) {
    val iconRes = when (mode) {
        HabitListEmptyMode.EMPTY -> R.drawable.ic_cat_star
        HabitListEmptyMode.ALL_DONE -> R.drawable.ic_cat_badge_check
        HabitListEmptyMode.SEARCH_NOT_FOUND -> R.drawable.ic_cat_activity
    }

    val titleRes = when (mode) {
        HabitListEmptyMode.EMPTY -> R.string.no_habits_found
        HabitListEmptyMode.ALL_DONE -> R.string.no_habits_left_to_do
        HabitListEmptyMode.SEARCH_NOT_FOUND -> R.string.no_search_results
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(Color(colors.surfaceVariant)),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.foundation.Image(
                    painter = painterResource(id = iconRes),
                    contentDescription = null,
                    modifier = Modifier.size(26.dp),
                    colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Color(colors.secondary))
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            BasicText(
                text = stringResource(id = titleRes),
                style = TextStyle(
                    color = Color(colors.foreground),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )
            )
        }
    }
}

// ============================================================================
// 5. Minimalist Habit List Row Shell
// ============================================================================

@Composable
fun HabitListRowShell(
    name: String,
    color: Color,
    score: Double,
    showScoreRing: Boolean,
    showHabitIcon: Boolean,
    iconRef: IconRef?,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    colors: UHabitComposeColors = LocalUHabitComposeColors.current,
    cornerRadius: Dp = colors.cardCornerRadiusDp.dp,
    actionsContent: @Composable () -> Unit = {}
) {
    UHabitInteractiveRowCard(
        isSelected = isSelected,
        onClick = onClick,
        modifier = modifier,
        colors = colors,
        cornerRadius = cornerRadius
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Optional Score Ring
            if (showScoreRing) {
                Spacer(modifier = Modifier.width(10.dp))
                HabitProgressIndicator(
                    score = score.toFloat(),
                    color = color,
                    size = 18.dp,
                    thickness = 2.5.dp
                )
                Spacer(modifier = Modifier.width(6.dp))
            } else {
                Spacer(modifier = Modifier.width(14.dp))
            }

            // Optional Habit Icon
            if (showHabitIcon && iconRef != null && iconRef !is IconRef.None) {
                HabitIcon(iconRef = iconRef, color = color, size = 24.dp)
                Spacer(modifier = Modifier.width(8.dp))
            }

            // Habit Name
            BasicText(
                text = name,
                style = TextStyle(
                    color = color,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.1.sp
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp)
            )

            // Day Action Columns (Checkmarks / Numbers)
            actionsContent()
        }
    }
}
