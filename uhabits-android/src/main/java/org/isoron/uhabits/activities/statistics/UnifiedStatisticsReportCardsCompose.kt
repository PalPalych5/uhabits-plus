/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.activities.statistics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.settings.SettingsThemePalette
import org.isoron.uhabits.compose.LocalUHabitComposeColors
import org.isoron.uhabits.compose.components.UHabitSectionCard

internal val statisticsStacked: Boolean
    @Composable get() = LocalConfiguration.current.let { it.screenWidthDp < 360 || it.fontScale >= 1.3f }

@Composable
internal fun UnifiedStatisticsCards(content: @Composable ColumnScope.() -> Unit) {
    val colors = LocalUHabitComposeColors.current
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(
            if (!colors.isClassicSectionLayout && colors.showCardBorders) 10.dp else 0.dp
        ),
        content = content,
    )
}

@Composable
internal fun UnifiedStatisticsCard(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalUHabitComposeColors.current
    val divider = colors.resolveDividerColor()
    val borderlessDivider = if (!colors.isClassicSectionLayout && !colors.showCardBorders) {
        Modifier.drawWithContent {
            drawContent()
            val y = size.height - 0.5.dp.toPx()
            drawLine(divider, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
        }
    } else Modifier
    UHabitSectionCard(
        modifier = modifier.then(borderlessDivider).then(
            if (onClick == null) Modifier else Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        ),
        cornerRadiusDp = colors.statisticsCardCornerRadiusDp,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        content = content,
    )
}

@Composable
internal fun UnifiedStatisticsTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, color = LocalUHabitComposeColors.current.foregroundColor, fontSize = 16.sp, fontWeight = FontWeight.Bold)
}

@Composable
internal fun UnifiedStatisticsSecondary(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, color = LocalUHabitComposeColors.current.secondaryColor, fontSize = 13.sp)
}

@Composable
internal fun UnifiedStatisticsBadge(text: String, modifier: Modifier = Modifier, primary: Boolean = false) {
    val colors = LocalUHabitComposeColors.current
    val shape = RoundedCornerShape(12.dp)
    Text(
        text,
        modifier.background(colors.surfaceVariantColor, shape).border(1.dp, colors.resolveDividerColor(), shape)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        color = if (primary) colors.foregroundColor else colors.secondaryColor,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
internal fun UnifiedStatisticsValueRow(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    emphasizeTitle: Boolean = false,
    valueColor: Color = LocalUHabitComposeColors.current.secondaryColor,
) {
    val colors = LocalUHabitComposeColors.current
    val titleWeight = if (emphasizeTitle) FontWeight.Bold else FontWeight.Normal
    if (statisticsStacked) {
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = colors.foregroundColor, fontSize = 15.sp, fontWeight = titleWeight)
            Text(value, color = valueColor, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    } else {
        Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), color = colors.foregroundColor, fontSize = 15.sp, fontWeight = titleWeight)
            Text(value, Modifier.weight(1f, fill = false), color = valueColor, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
internal fun UnifiedStatisticsReportAction(label: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        label,
        modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 12.dp),
        color = LocalUHabitComposeColors.current.accentColor,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
internal fun UnifiedStatisticsProgress(
    ratio: Float,
    fill: Color,
    palette: SettingsThemePalette,
    modifier: Modifier = Modifier,
    showLimitMarker: Boolean = false,
) {
    val track = when {
        palette.isPureBlack -> colorResource(R.color.statistics_progress_track_amoled)
        palette.isDark -> Color(ColorUtils.blendARGB(palette.surface, palette.onSurface, 0.14f))
        else -> Color(ColorUtils.blendARGB(palette.surface, palette.onSurface, 0.10f))
    }
    Canvas(modifier.fillMaxWidth().height(10.dp)) {
        val height = 6.dp.toPx()
        val top = (size.height - height) / 2
        val radius = CornerRadius(height / 2)
        val rtl = layoutDirection == LayoutDirection.Rtl
        drawRoundRect(track, Offset(0f, top), Size(size.width, height), radius)
        if (ratio > 0f) {
            val width = (size.width * ratio.coerceAtMost(1f)).coerceAtLeast(height).coerceAtMost(size.width)
            drawRoundRect(fill, Offset(if (rtl) size.width - width else 0f, top), Size(width, height), radius)
        }
        if (showLimitMarker && size.width > 8.dp.toPx()) {
            val x = if (ratio > 1f) (size.width / ratio).coerceIn(4.dp.toPx(), size.width - 4.dp.toPx())
                else size.width - 2.dp.toPx()
            drawRoundRect(
                Color(ColorUtils.setAlphaComponent(palette.onSurface, 140)),
                Offset((if (rtl) size.width - x else x) - 1.dp.toPx(), 0f),
                Size(2.dp.toPx(), size.height),
                CornerRadius(1.dp.toPx()),
            )
        }
    }
}
