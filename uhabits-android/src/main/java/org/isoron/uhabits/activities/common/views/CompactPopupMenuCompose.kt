/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.activities.common.views

import android.content.Context
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.util.TypedValue
import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.core.content.ContextCompat
import androidx.core.os.ConfigurationCompat
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.settings.SettingsThemePalette
import org.isoron.uhabits.utils.StyledResources
import kotlin.math.ceil

internal class CompactPopupSpacing(private val density: Float) {
    fun px(dp: Int) = (dp * density).toInt()

    val menuPadding = px(6)
    val itemPadding = px(12)
    val itemMargin = px(2)
    val itemMinHeight = px(42)
    val iconSize = px(20)
    val iconGap = px(12)
    val dividerHeight = px(1)
    val dividerMargin = px(4)
    val outline = px(1).coerceAtLeast(1)
    val menuRadius = px(12)
    val selectedRadius = px(8)

    fun iconWidth(hasIcon: Boolean) = if (hasIcon) iconSize + iconGap else 0

    fun rowHeight(labelHeight: Int, hasIcon: Boolean) =
        maxOf(itemMinHeight, labelHeight, if (hasIcon) iconSize else 0)

    fun menuWidth(widestItem: Int, minWidthDp: Int, maxWidthDp: Int) =
        (widestItem + 2 * menuPadding).coerceAtLeast(px(minWidthDp)).coerceAtMost(px(maxWidthDp))

    fun itemWidth(labelWidth: Int, hasIcon: Boolean) = labelWidth + iconWidth(hasIcon) + 2 * itemPadding

    fun rowWidth(menuWidth: Int, hasIcon: Boolean) =
        (menuWidth - 2 * (menuPadding + itemPadding) - iconWidth(hasIcon)).coerceAtLeast(1)
}

internal data class CompactPopupLayout(
    val width: Int,
    val height: Int,
    val rows: List<CompactPopupRow>,
)

internal sealed interface CompactPopupRow {
    data object Divider : CompactPopupRow
    data class Item(
        val id: Int,
        val title: String,
        val label: StaticLayout,
        val icon: Drawable?,
        val height: Int,
        val selected: Boolean,
    ) : CompactPopupRow
}

private data class PopupLabel(
    val entry: CompactPopupMenu.Entry.Item,
    val paint: TextPaint,
    val icon: Drawable?,
)

internal fun measureCompactPopupMenu(
    context: Context,
    entries: List<CompactPopupMenu.Entry>,
    spacing: CompactPopupSpacing,
    minWidthDp: Int,
    maxWidthDp: Int,
    rtl: Boolean,
    accent: Int,
    destructive: Int,
): CompactPopupLayout {
    val defaultColor by lazy { StyledResources(context).getColor(R.attr.contrast100) }
    val displayMetrics = context.resources.displayMetrics
    val textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14f, displayMetrics)
    val locale = ConfigurationCompat.getLocales(context.resources.configuration)[0]
    val labels = entries
        .filterNot { it is CompactPopupMenu.Entry.Item && !it.visible }
        .map { entry ->
            if (entry is CompactPopupMenu.Entry.Item) {
                val color = when {
                    entry.destructive -> destructive
                    entry.selected -> accent
                    else -> defaultColor
                }
                val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.color = color
                    this.textSize = textSize
                    density = displayMetrics.density
                    if (locale != null) textLocale = locale
                }
                val icon = entry.iconRes?.let { ContextCompat.getDrawable(context, it) }?.mutate()?.apply {
                    layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                    setTint(color)
                    setBounds(0, 0, spacing.iconSize, spacing.iconSize)
                }
                PopupLabel(entry, paint, icon)
            } else null
        }
    val widestItem = labels.filterNotNull().maxOfOrNull { label ->
        spacing.itemWidth(ceil(Layout.getDesiredWidth(label.entry.title, label.paint)).toInt(), label.icon != null)
    } ?: 0
    val width = spacing.menuWidth(widestItem, minWidthDp, maxWidthDp)
    val direction = if (rtl) TextDirectionHeuristics.FIRSTSTRONG_RTL else TextDirectionHeuristics.FIRSTSTRONG_LTR
    val rows = labels.map { item ->
        if (item == null) {
            CompactPopupRow.Divider
        } else {
            val title = item.entry.title
            val hasIcon = item.icon != null
            // Keep CharSequence spans and reuse the measured paragraph for drawing.
            val label = StaticLayout.Builder.obtain(title, 0, title.length, item.paint, spacing.rowWidth(width, hasIcon))
                .setIncludePad(false)
                .setTextDirection(direction)
                .setUseLineSpacingFromFallbacks(true)
                .build()
            CompactPopupRow.Item(item.entry.id, title.toString(), label, item.icon, spacing.rowHeight(label.height, hasIcon), item.entry.selected)
        }
    }
    val height = 2 * spacing.menuPadding + rows.sumOf { row ->
        when (row) {
            CompactPopupRow.Divider -> spacing.dividerHeight + 2 * spacing.dividerMargin
            is CompactPopupRow.Item -> row.height + 2 * spacing.itemMargin
        }
    }
    return CompactPopupLayout(width, height, rows)
}

@Composable
internal fun CompactPopupMenuContent(
    layout: CompactPopupLayout,
    spacing: CompactPopupSpacing,
    palette: SettingsThemePalette,
    onItemClick: (Int) -> Unit,
) {
    val density = LocalDensity.current
    with(density) {
        val shape = RoundedCornerShape(spacing.menuRadius.toDp())
        Column(
            Modifier.fillMaxWidth()
                .background(Color(palette.dialogSurface), shape)
                .border(spacing.outline.toDp(), Color(palette.dialogBorder), shape)
                .padding(spacing.menuPadding.toDp()),
        ) {
            layout.rows.forEach { row ->
                when (row) {
                    CompactPopupRow.Divider -> Spacer(
                        Modifier.fillMaxWidth().padding(vertical = spacing.dividerMargin.toDp())
                            .height(spacing.dividerHeight.toDp()).background(Color(palette.divider)),
                    )
                    is CompactPopupRow.Item -> {
                        val selection = if (row.selected) {
                            Modifier.background(Color(palette.divider), RoundedCornerShape(spacing.selectedRadius.toDp()))
                        } else Modifier
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = spacing.itemMargin.toDp()).height(row.height.toDp())
                                .then(selection)
                                .clickable(
                                    interactionSource = null,
                                    indication = if (row.selected) null else ripple(color = Color(palette.onSurface)),
                                    role = Role.Button,
                                    onClick = { onItemClick(row.id) },
                                )
                                .semantics {
                                    text = AnnotatedString(row.title)
                                    if (row.selected) selected = true
                                }
                                .padding(horizontal = spacing.itemPadding.toDp()),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            row.icon?.let { icon ->
                                Canvas(Modifier.size(spacing.iconSize.toDp())) {
                                    drawIntoCanvas { icon.draw(it.nativeCanvas) }
                                }
                                Spacer(Modifier.width(spacing.iconGap.toDp()))
                            }
                            Canvas(Modifier.weight(1f).height(row.label.height.toDp()).clipToBounds()) {
                                drawIntoCanvas { row.label.draw(it.nativeCanvas) }
                            }
                        }
                    }
                }
            }
        }
    }
}
