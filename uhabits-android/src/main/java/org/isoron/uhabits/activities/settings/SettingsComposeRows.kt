package org.isoron.uhabits.activities.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.common.dialogs.ColorPickerUtils
import org.isoron.uhabits.activities.common.dialogs.CustomDialogs
import org.isoron.uhabits.compose.UHabitComposeColors
import org.isoron.uhabits.compose.UHabitComposeTheme
import org.isoron.uhabits.core.ui.ThemeSwitcher

private fun String?.isLongSettingsSummary(): Boolean =
    !isNullOrBlank() && (length > 72 || contains('\n'))

private val expandedSummaryKeys = setOf(
    "syncSignIn", "syncSignOut", "syncNow", "syncReview",
    "pref_sync_base_url", "pref_sync_key", "seedDemoData", "exportSyncDiagnostics",
    "bugReport", "pref_container_experiment", "pref_production_cutover_dev",
    "pref_container_ui_experiments",
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SettingsDetailRow(item: SettingItem, colors: UHabitComposeColors) {
    val view = LocalView.current
    UHabitComposeTheme(colors) {
        Column(modifier = Modifier.fillMaxWidth()) {
            when (item) {
                is SettingItem.Subsection -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 36.dp)
                        .padding(start = 18.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)
                        .semantics { heading() },
                    contentAlignment = Alignment.CenterStart,
                ) {
                    val titleColor = if (item.isDestructive) {
                        colors.dangerColor.copy(alpha = 0.8f)
                    } else {
                        Color(colors.accent)
                    }
                    BasicText(
                        text = item.title,
                        style = TextStyle(
                            color = titleColor,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                    )
                }

                is SettingItem.Status -> SettingsComposeRow(
                    title = item.title,
                    summary = item.summary,
                    icon = item.iconRes,
                    presentation = item.presentation,
                    colors = colors,
                    action = Modifier,
                    summaryMaxLines = 4,
                    trailing = when {
                        item.showProgress -> {
                            {
                                CircularProgressIndicator(
                                    color = Color(colors.accent),
                                    strokeWidth = 2.5.dp,
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                        }
                        item.reserveProgressSpace -> {
                            { Spacer(Modifier.size(22.dp)) }
                        }
                        else -> null
                    },
                )

                is SettingItem.SegmentedTheme -> SettingsSegmentedThemeRow(
                    item = item,
                    colors = colors,
                )

                is SettingItem.ColorPicker -> SettingsComposeRow(
                    title = item.title,
                    summary = item.summary,
                    icon = item.iconRes,
                    presentation = item.presentation,
                    colors = colors,
                    action = buildClickModifier(
                        enabled = item.presentation.enabled,
                        summary = item.summary,
                        onClick = item.onClick,
                        onLongClick = {
                            CustomDialogs.showInfoDialog(
                                context = view.context,
                                title = item.title,
                                message = item.summary,
                            )
                        },
                    ),
                    trailing = {
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(Color(colors.accent))
                                .border(1.dp, Color(colors.divider), CircleShape),
                        )
                    },
                )

                is SettingItem.Navigation -> SettingsComposeRow(
                    title = item.title,
                    summary = item.summary,
                    icon = item.iconRes,
                    presentation = item.presentation,
                    colors = colors,
                    action = buildClickModifier(
                        enabled = item.presentation.enabled && item.onClick != null && !item.isBusy,
                        summary = item.summary,
                        allowSummaryPreview = item.key in expandedSummaryKeys,
                        onClick = { item.onClick?.invoke() },
                        onLongClick = {
                            item.summary?.let { summaryText ->
                                CustomDialogs.showInfoDialog(
                                    context = view.context,
                                    title = item.title,
                                    message = summaryText,
                                )
                            }
                        },
                    ),
                    chevron = item.onClick != null,
                    danger = item.isDanger,
                    busy = item.isBusy,
                    summaryMaxLines = if (item.key in expandedSummaryKeys) Int.MAX_VALUE else 2,
                )

                is SettingItem.Switch -> {
                    var checked by remember(item.stableKey, item.checked) { mutableStateOf(item.checked) }
                    SettingsComposeRow(
                        title = item.title,
                        summary = item.summary,
                        icon = item.iconRes,
                        presentation = item.presentation,
                        colors = colors,
                        action = Modifier.toggleable(
                            value = checked,
                            enabled = item.presentation.enabled,
                            role = Role.Switch,
                        ) {
                            checked = it
                            item.onCheckedChange(it)
                        },
                        checked = checked,
                    )
                }

                is SettingItem.Value -> SettingsComposeRow(
                    title = item.title,
                    summary = null,
                    icon = item.iconRes,
                    presentation = item.presentation,
                    colors = colors,
                    action = Modifier.clickable(
                        enabled = item.presentation.enabled,
                        role = Role.Button,
                        onClick = item.onClick,
                    ),
                    trailing = {
                        BasicText(
                            text = item.valueText,
                            style = TextStyle(color = Color(colors.secondary), fontSize = 14.sp),
                        )
                    },
                )

                else -> Unit
            }

            val showDivider = !item.presentation.isLast || colors.isClassicSectionLayout
            if (showDivider) {
                val isSubsection = item is SettingItem.Subsection
                val startInset = if (isSubsection || !item.presentation.showIcon) {
                    dimensionResource(R.dimen.settings_detail_group_divider_start)
                } else {
                    dimensionResource(R.dimen.settings_detail_row_divider_start)
                }
                val endInset = dimensionResource(R.dimen.settings_detail_divider_end)
                val dividerColor = if (isSubsection) {
                    Color(if (colors.isDark) 0x1FFFFFFF else 0x1F000000)
                } else {
                    Color(colors.divider)
                }
                Spacer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = startInset, end = endInset)
                        .height(1.dp)
                        .background(dividerColor),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun buildClickModifier(
    enabled: Boolean,
    summary: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    allowSummaryPreview: Boolean = false,
): Modifier {
    return if (summary.isLongSettingsSummary() || (allowSummaryPreview && !summary.isNullOrBlank())) {
        Modifier.combinedClickable(
            enabled = enabled,
            role = Role.Button,
            onClick = onClick,
            onLongClick = onLongClick,
        )
    } else {
        Modifier.clickable(
            enabled = enabled,
            role = Role.Button,
            onClick = onClick,
        )
    }
}

@Composable
private fun SettingsSegmentedThemeRow(
    item: SettingItem.SegmentedTheme,
    colors: UHabitComposeColors,
) {
    val themeEntries = stringArrayResource(R.array.pref_theme_entries)
    val options = listOf(
        Triple(
            themeEntries.getOrElse(0) { "" },
            item.themeValue == ThemeSwitcher.THEME_AUTOMATIC,
        ) {
            item.onSegmentSelected(ThemeSwitcher.THEME_AUTOMATIC, item.pureBlackValue)
        },
        Triple(
            themeEntries.getOrElse(1) { "" },
            item.themeValue == ThemeSwitcher.THEME_LIGHT,
        ) {
            item.onSegmentSelected(ThemeSwitcher.THEME_LIGHT, item.pureBlackValue)
        },
        Triple(
            themeEntries.getOrElse(2) { "" },
            item.themeValue == ThemeSwitcher.THEME_DARK && !item.pureBlackValue,
        ) {
            item.onSegmentSelected(ThemeSwitcher.THEME_DARK, false)
        },
        Triple(
            "AMOLED",
            item.themeValue == ThemeSwitcher.THEME_DARK && item.pureBlackValue,
        ) {
            item.onSegmentSelected(ThemeSwitcher.THEME_DARK, true)
        },
    )

    val trackStartInset = if (item.presentation.showIcon) 52.dp else 18.dp
    val trackBorderColor = if (colors.isDark) Color(0x1FFFFFFF) else Color(colors.border)
    val selectedBg = Color(colors.accent)
    val selectedFg = Color(ColorPickerUtils.onColor(colors.accent))

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .padding(start = 18.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (item.presentation.showIcon) {
                Image(
                    painter = painterResource(item.iconRes),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(Color(colors.secondary)),
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(12.dp))
            }
            BasicText(
                text = item.title,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(
                    color = Color(colors.foreground),
                    fontSize = 15.sp,
                ),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = trackStartInset, end = 16.dp, bottom = 8.dp)
                .height(IntrinsicSize.Min)
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, trackBorderColor, RoundedCornerShape(8.dp))
                .padding(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            options.forEach { (label, isSelected, onSelect) ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .heightIn(min = 44.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isSelected) selectedBg else Color.Transparent)
                        .selectable(
                            selected = isSelected,
                            enabled = item.presentation.enabled,
                            role = Role.RadioButton,
                            onClick = onSelect,
                        )
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(
                        text = label,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(
                            color = if (isSelected) selectedFg else Color(colors.secondary),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsComposeRow(
    title: String,
    summary: String?,
    icon: Int,
    presentation: SettingPresentation,
    colors: UHabitComposeColors,
    action: Modifier,
    checked: Boolean? = null,
    chevron: Boolean = false,
    danger: Boolean = false,
    busy: Boolean = false,
    summaryMaxLines: Int = 2,
    trailing: (@Composable () -> Unit)? = null,
) {
    val contentAlpha = if (presentation.enabled || busy) 1f else 0.48f
    val secondaryTint = if (danger) {
        androidx.compose.ui.res.colorResource(R.color.red_500)
    } else {
        Color(colors.secondary)
    }
    Row(
        action
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .padding(start = 18.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (presentation.showIcon) {
            Image(
                painterResource(icon),
                contentDescription = null,
                colorFilter = ColorFilter.tint(secondaryTint),
                modifier = Modifier
                    .size(22.dp)
                    .alpha(contentAlpha),
            )
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            val titleColor = if (danger) {
                androidx.compose.ui.res.colorResource(R.color.red_500)
            } else {
                Color(colors.foreground)
            }
            BasicText(
                title,
                Modifier.alpha(contentAlpha),
                style = TextStyle(color = titleColor, fontSize = 16.sp),
            )
            if (!summary.isNullOrBlank()) {
                BasicText(
                    summary,
                    Modifier
                        .padding(top = 2.dp)
                        .alpha(contentAlpha),
                    maxLines = summaryMaxLines,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(color = Color(colors.secondary), fontSize = 13.sp),
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            Box(modifier = Modifier.alpha(contentAlpha)) {
                trailing()
            }
        } else if (checked != null) {
            Spacer(Modifier.width(12.dp))
            Canvas(
                Modifier
                    .size(48.dp)
                    .alpha(contentAlpha),
            ) {
                val inactive = when {
                    colors.isPureBlack -> Color(0xff202126)
                    colors.isDark -> Color(0xff303238)
                    else -> Color(0xffe4e7ec)
                }
                val accent = Color(colors.accent)
                val track = if (checked) {
                    lerp(inactive, accent, if (colors.isDark || colors.isPureBlack) 0.5f else 0.4f)
                } else {
                    inactive
                }
                val thumb = if (checked) {
                    accent
                } else if (colors.isDark || colors.isPureBlack) {
                    Color(0xffe9ebf0)
                } else {
                    Color.White
                }
                val width = 42.dp.toPx()
                val height = 24.dp.toPx()
                val left = (size.width - width) / 2f
                val top = (size.height - height) / 2f
                drawRoundRect(track, Offset(left, top), Size(width, height), CornerRadius(height / 2f))
                val radius = 9.dp.toPx()
                drawCircle(
                    thumb,
                    radius,
                    Offset(
                        if (checked) left + width - 3.dp.toPx() - radius else left + 3.dp.toPx() + radius,
                        size.height / 2f,
                    ),
                )
            }
        } else if (busy) {
            Spacer(Modifier.width(12.dp))
            CircularProgressIndicator(
                color = Color(colors.accent),
                strokeWidth = 2.5.dp,
                modifier = Modifier.size(20.dp),
            )
        } else if (chevron) {
            Spacer(Modifier.width(12.dp))
            Image(
                painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                colorFilter = ColorFilter.tint(Color(colors.secondary)),
                modifier = Modifier
                    .size(16.dp)
                    .alpha(contentAlpha),
            )
        }
    }
}


