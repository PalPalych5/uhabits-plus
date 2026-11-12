/*
 * Copyright (C) 2016-2026 Álinson Santos Xavier <git@axavier.org> and contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.isoron.uhabits.compose.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.isoron.uhabits.compose.LocalUHabitComposeColors
import org.isoron.uhabits.compose.LocalUHabitDesignTokens
import org.isoron.uhabits.compose.UHabitComposeColors
import org.isoron.uhabits.compose.UHabitElevationAndBorders

/**
 * Shared section/statistics/form card primitive.
 *
 * Respects user preferences for:
 * - [UHabitComposeColors.cardCornerRadiusDp] (0dp -> crisp rectangle, >0dp -> rounded corners)
 * - [UHabitComposeColors.showCardBorders] (flat/unbordered when false, hairline border + surface when true)
 * - Light, Dark, and pure-black AMOLED surface semantics (never forcing an elevated gray surface in
 *   AMOLED when card borders are disabled).
 */
@Composable
fun UHabitSectionCard(
    modifier: Modifier = Modifier,
    colors: UHabitComposeColors = LocalUHabitComposeColors.current,
    cornerRadiusDp: Int = colors.cardCornerRadiusDp,
    contentPadding: PaddingValues = PaddingValues(
        horizontal = LocalUHabitDesignTokens.current.spacing.cardHorizontalPadding,
        vertical = LocalUHabitDesignTokens.current.spacing.cardVerticalPadding
    ),
    content: @Composable ColumnScope.() -> Unit
) {
    if (colors.isClassicSectionLayout) {
        val dividerColor = when {
            colors.isPureBlack -> Color(0xFF181818)
            colors.isDark -> Color.White.copy(alpha = 0.08f)
            else -> Color.Black.copy(alpha = 0.08f)
        }
        Column(
            modifier = modifier
                .fillMaxWidth()
                .background(colors.surfaceColor)
                .drawBehind {
                    val stroke = 1.dp.toPx()
                    drawLine(
                        color = dividerColor,
                        start = Offset(0f, size.height - stroke / 2f),
                        end = Offset(size.width, size.height - stroke / 2f),
                        strokeWidth = stroke
                    )
                }
                .padding(contentPadding),
            content = content
        )
        return
    }

    val shape = colors.resolveCardShape(cornerRadiusDp)
    val surfaceColor = colors.resolveSectionSurfaceColor()
    val borderModifier = if (colors.showCardBorders) {
        Modifier.border(
            width = UHabitElevationAndBorders.hairlineBorderWidth,
            color = colors.resolveSectionBorderColor(),
            shape = shape
        )
    } else {
        Modifier
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(surfaceColor)
            .then(borderModifier)
            .padding(contentPadding),
        content = content
    )
}

/**
 * Shared interactive row card primitive (used by Habit List rows).
 *
 * Separates persistent card decoration ([UHabitComposeColors.showCardBorders]) from interactive
 * selection feedback ([isSelected]):
 * - When `isSelected == true`, displays a subtle accent fill and 1.5dp accent outline even if
 *   persistent card borders are disabled.
 * - When `showCardBorders == false` and not selected, renders a transparent background so rows
 *   sit flush on the screen canvas (including pure `#000000` in AMOLED).
 */
@Composable
fun UHabitInteractiveRowCard(
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    colors: UHabitComposeColors = LocalUHabitComposeColors.current,
    cornerRadius: Dp = colors.cardCornerRadiusDp.dp,
    contentPadding: PaddingValues = PaddingValues(vertical = 8.dp),
    content: @Composable BoxScope.() -> Unit
) {
    val showBorders = colors.showCardBorders
    val shape = LocalUHabitDesignTokens.current.shapes.cardShape(cornerRadius)
    val cardBg = colors.resolveRowSurfaceColor(isSelected = isSelected)

    val borderModifier = when {
        isSelected -> Modifier.border(
            width = UHabitElevationAndBorders.selectedBorderWidth,
            color = colors.resolveRowBorderColor(isSelected = true),
            shape = shape
        )
        showBorders -> Modifier.border(
            width = UHabitElevationAndBorders.hairlineBorderWidth,
            color = colors.resolveRowBorderColor(isSelected = false),
            shape = shape
        )
        else -> Modifier
    }

    val outerPaddingH = if (showBorders) 4.dp else 1.dp
    val outerPaddingV = if (showBorders) 2.dp else 1.dp

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = outerPaddingH, vertical = outerPaddingV)
            .clip(shape)
            .background(cardBg)
            .then(borderModifier)
            .clickable(onClick = onClick)
            .padding(contentPadding),
        content = content
    )
}

/**
 * Subtle intra-card hairline divider that adapts across Light, Dark, and AMOLED modes.
 */
@Composable
fun UHabitCardDivider(
    modifier: Modifier = Modifier,
    colors: UHabitComposeColors = LocalUHabitComposeColors.current,
    verticalPadding: Dp = 4.dp
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = verticalPadding)
            .height(1.dp)
            .background(colors.resolveDividerColor())
    )
}
