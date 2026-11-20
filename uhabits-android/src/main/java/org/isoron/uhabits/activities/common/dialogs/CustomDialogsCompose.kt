/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.isoron.uhabits.activities.common.dialogs

import android.graphics.Typeface
import android.text.Spanned
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.isoron.uhabits.compose.LocalUHabitComposeColors
import org.isoron.uhabits.compose.LocalUHabitDesignTokens
import org.isoron.uhabits.compose.UHabitElevationAndBorders
import org.isoron.uhabits.compose.components.UHabitDialogShell

internal data class CustomDialogListRow(val title: CharSequence, val summary: CharSequence?)

internal fun normalizeSelectedChoiceIndex(selectedIndex: Int, optionCount: Int): Int =
    selectedIndex.takeIf { it in 0 until optionCount } ?: -1

internal fun resolveSimpleListRows(
    items: List<CharSequence>,
    summaries: List<CharSequence>?
): List<CustomDialogListRow> = items.mapIndexed { index, item ->
    CustomDialogListRow(item, summaries?.getOrNull(index)?.takeIf { it.isNotEmpty() })
}

private val listPadding = PaddingValues(start = 18.dp, top = 14.dp, end = 18.dp, bottom = 8.dp)
private val choiceShape = RoundedCornerShape(10.dp)

@Composable
private fun CustomDialogFrame(
    title: CharSequence,
    padding: PaddingValues,
    maxBodyHeight: Dp = Dp.Infinity,
    actions: @Composable () -> Unit,
    body: @Composable ColumnScope.() -> Unit
) {
    val colors = LocalUHabitComposeColors.current
    val lightBorder = if (colors.isDark) Modifier else Modifier.border(
        width = UHabitElevationAndBorders.hairlineBorderWidth,
        color = colors.dialogBorderColor,
        shape = LocalUHabitDesignTokens.current.shapes.lg
    )
    UHabitDialogShell(
        modifier = Modifier
            .heightIn(max = LocalConfiguration.current.screenHeightDp.dp)
            .semantics {
                testTagsAsResourceId = true
                paneTitle = title.toString()
            }
            .testTag("dialog_root")
            .then(lightBorder),
        contentPadding = padding
    ) {
        // The window can shrink more than the screen configuration reports, especially with the IME.
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .heightIn(max = maxBodyHeight)
                .verticalScroll(rememberScrollState()),
            content = body
        )
        actions()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CustomDialogActions(
    negativeText: CharSequence? = null,
    onNegative: (() -> Unit)? = null,
    positiveText: CharSequence? = null,
    onPositive: (() -> Unit)? = null,
    neutralText: CharSequence? = null,
    onNeutral: (() -> Unit)? = null,
    isDestructive: Boolean = false
) {
    val colors = LocalUHabitComposeColors.current
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
    ) {
        if (!neutralText.isNullOrBlank() && onNeutral != null) {
            CustomDialogAction(neutralText, colors.secondaryColor, "button_neutral", onNeutral)
        }
        if (negativeText != null && onNegative != null) {
            CustomDialogAction(negativeText, colors.secondaryColor, "button_negative", onNegative)
        }
        if (positiveText != null && onPositive != null) {
            val color = if (isDestructive) colors.dangerColor else colors.accentColor
            CustomDialogAction(positiveText, color, "button_positive", onPositive)
        }
    }
}

@Composable
private fun CustomDialogAction(
    text: CharSequence,
    color: Color,
    tag: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .testTag(tag)
            .clickable(
                interactionSource = null,
                indication = ripple(color = color),
                role = Role.Button,
                onClick = onClick
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        CustomDialogText(text, color, 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun CustomDialogText(
    text: CharSequence,
    color: Color,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight = FontWeight.Normal,
    messageSpacing: Boolean = false
) {
    // Keep Android spans supplied to the CharSequence API, including custom spans.
    if (text is Spanned) {
        AndroidView(
            factory = { TextView(it) },
            modifier = modifier,
            update = { view ->
                view.text = text
                view.setTextColor(color.toArgb())
                view.textSize = fontSize.value
                val family = if (fontWeight == FontWeight.Medium) "sans-serif-medium" else "sans-serif"
                val style = if (fontWeight == FontWeight.Bold) Typeface.BOLD else Typeface.NORMAL
                view.typeface = Typeface.create(family, style)
                val extra = if (messageSpacing) 2f * view.resources.displayMetrics.density else 0f
                view.setLineSpacing(extra, 1f)
            }
        )
    } else {
        Text(
            text = text.toString(),
            modifier = modifier,
            color = color,
            fontSize = fontSize,
            fontWeight = fontWeight,
            lineHeight = if (messageSpacing) 20.sp else TextUnit.Unspecified,
            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = true))
        )
    }
}

@Composable
private fun CustomDialogTitle(title: CharSequence, fontSize: TextUnit = 18.sp) {
    CustomDialogText(
        text = title,
        color = LocalUHabitComposeColors.current.foregroundColor,
        fontSize = fontSize,
        modifier = Modifier.fillMaxWidth().testTag("dialog_title").semantics { heading() },
        fontWeight = FontWeight.Bold
    )
}

@Composable
internal fun CustomConfirmDialogContent(
    title: CharSequence,
    message: CharSequence,
    isDestructive: Boolean,
    positiveText: CharSequence,
    negativeText: CharSequence?,
    @DrawableRes iconRes: Int? = null,
    @ColorInt iconTint: Int? = null,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    CustomDialogFrame(
        title = title,
        padding = PaddingValues(20.dp),
        actions = {
            CustomDialogActions(negativeText, onDismiss, positiveText, onConfirm, isDestructive = isDestructive)
        }
    ) {
        if (iconRes != null) {
            AndroidView(
                factory = { ImageView(it).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO } },
                modifier = Modifier.size(28.dp).testTag("dialog_icon"),
                update = {
                    it.setImageResource(iconRes)
                    if (iconTint != null) it.setColorFilter(iconTint) else it.clearColorFilter()
                }
            )
            Spacer(Modifier.height(12.dp))
        }
        CustomDialogTitle(title, 20.sp)
        Spacer(Modifier.height(10.dp))
        CustomDialogText(
            text = message,
            color = LocalUHabitComposeColors.current.secondaryColor,
            fontSize = 15.sp,
            modifier = Modifier.fillMaxWidth().testTag("dialog_message"),
            messageSpacing = true
        )
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
internal fun CustomCredentialsDialogContent(
    title: CharSequence,
    fields: View,
    positiveText: CharSequence,
    negativeText: CharSequence,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    CustomDialogFrame(
        title = title,
        padding = PaddingValues(24.dp),
        actions = { CustomDialogActions(negativeText, onDismiss, positiveText, onConfirm) }
    ) {
        CustomDialogTitle(title)
        Spacer(Modifier.height(12.dp))
        AndroidView(
            factory = {
                (fields.parent as? ViewGroup)?.removeView(fields)
                fields
            },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
internal fun CustomSingleChoiceDialogContent(
    title: CharSequence,
    options: List<String>,
    selectedIndex: Int,
    message: CharSequence?,
    neutralText: CharSequence?,
    negativeText: CharSequence,
    onSelect: (Int) -> Unit,
    onNeutral: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    val colors = LocalUHabitComposeColors.current
    var selection by remember { mutableIntStateOf(normalizeSelectedChoiceIndex(selectedIndex, options.size)) }
    CustomDialogFrame(
        title = title,
        padding = listPadding,
        maxBodyHeight = 400.dp,
        actions = {
            CustomDialogActions(negativeText, onDismiss, neutralText = neutralText, onNeutral = onNeutral)
        }
    ) {
        CustomDialogTitle(title)
        Spacer(Modifier.height(6.dp))
        if (!message.isNullOrBlank()) {
            CustomDialogText(message, colors.secondaryColor, 14.sp, Modifier.testTag("dialog_message"))
            Spacer(Modifier.height(8.dp))
        }
        Column(
            modifier = Modifier.fillMaxWidth().selectableGroup().testTag("options_container")
        ) {
            options.forEachIndexed { index, label ->
                val selected = index == selection
                val decoration = if (selected) {
                    Modifier
                        .background(colors.accentColor.copy(alpha = colors.accentColor.alpha * 0.12f), choiceShape)
                        .border(1.dp, colors.accentColor.copy(alpha = colors.accentColor.alpha * 0.35f), choiceShape)
                } else {
                    Modifier
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clip(choiceShape)
                        .then(decoration)
                        .selectable(
                            selected = selected,
                            interactionSource = null,
                            indication = ripple(color = colors.foregroundColor),
                            role = Role.RadioButton,
                            onClick = {
                                selection = index
                                onSelect(index)
                            }
                        )
                        .padding(top = 1.dp, end = 4.dp, bottom = 1.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.width(38.dp), contentAlignment = Alignment.Center) {
                        Box(
                            modifier = Modifier.size(16.dp).border(1.6.dp, colors.accentColor, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            if (selected) {
                                Box(Modifier.size(8.dp).background(colors.accentColor, CircleShape))
                            }
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                    CustomDialogText(label, colors.foregroundColor, 15.sp, Modifier.weight(1f))
                }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
internal fun CustomSimpleListDialogContent(
    title: CharSequence,
    rows: List<CustomDialogListRow>,
    emptyStateMessage: CharSequence,
    negativeText: CharSequence,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = LocalUHabitComposeColors.current
    CustomDialogFrame(
        title = title,
        padding = listPadding,
        maxBodyHeight = 400.dp,
        actions = { CustomDialogActions(negativeText, onDismiss) }
    ) {
        CustomDialogTitle(title)
        Spacer(Modifier.height(6.dp))
        if (rows.isEmpty()) {
            CustomDialogText(emptyStateMessage, colors.secondaryColor, 14.sp, Modifier.testTag("dialog_message"))
            Spacer(Modifier.height(8.dp))
        } else {
            Column(modifier = Modifier.fillMaxWidth().testTag("options_container")) {
                rows.forEachIndexed { index, row ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clip(choiceShape)
                            .clickable(
                                interactionSource = null,
                                indication = ripple(color = colors.foregroundColor),
                                role = Role.Button,
                                onClick = { onSelect(index) }
                            )
                            .padding(start = 6.dp, top = 1.dp, end = 4.dp, bottom = 1.dp),
                        verticalArrangement = Arrangement.Center
                    ) {
                        val weight = if (row.summary == null) FontWeight.Normal else FontWeight.Bold
                        CustomDialogText(row.title, colors.foregroundColor, 15.sp, fontWeight = weight)
                        row.summary?.let { CustomDialogText(it, colors.foregroundColor, 12.sp) }
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}
