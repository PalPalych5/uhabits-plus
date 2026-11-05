package org.isoron.uhabits.activities.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.recyclerview.widget.RecyclerView
import androidx.compose.ui.platform.ComposeView
import org.isoron.uhabits.compose.UHabitComposeColors
import org.isoron.uhabits.compose.UHabitComposeTheme
import org.isoron.uhabits.R

internal class ComposeSettingsHolder(view: ComposeView) : RecyclerView.ViewHolder(view)

@Composable
internal fun PomodoroSettingsRow(item: SettingItem, colors: UHabitComposeColors) {
    UHabitComposeTheme(colors) {
        Column {
        when (item) {
            is SettingItem.Subsection -> BasicText(
                item.title,
                Modifier.fillMaxWidth().semantics { heading() }.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp),
                style = TextStyle(color = Color(colors.accent), fontSize = 14.sp)
            )
            is SettingItem.Navigation -> SettingsComposeRow(
                item.title, item.summary, item.iconRes, item.presentation, colors,
                Modifier.clickable(enabled = item.presentation.enabled && item.onClick != null, role = Role.Button) { item.onClick?.invoke() },
                chevron = item.onClick != null,
                danger = item.isDanger,
                busy = item.isBusy
            )
            is SettingItem.Switch -> {
                var checked by remember(item.stableKey, item.checked) { mutableStateOf(item.checked) }
                SettingsComposeRow(
                    item.title, item.summary, item.iconRes, item.presentation, colors,
                    Modifier.toggleable(value = checked, enabled = item.presentation.enabled, role = Role.Switch) {
                        checked = it
                        item.onCheckedChange(it)
                    },
                    checked = checked
                )
            }
            else -> Unit
        }
        if (!item.presentation.isLast) {
            Spacer(Modifier.fillMaxWidth().padding(start = if (item.presentation.showIcon) 52.dp else 18.dp, end = 16.dp).height(1.dp).background(Color(colors.divider)))
        }
        }
    }
}

@Composable
private fun SettingsComposeRow(title: String, summary: String?, icon: Int, presentation: SettingPresentation, colors: UHabitComposeColors, action: Modifier, checked: Boolean? = null, chevron: Boolean = false, danger: Boolean = false, busy: Boolean = false) {
    val contentAlpha = if (presentation.enabled || busy) 1f else 0.48f
    Row(action.fillMaxWidth().heightIn(min = 52.dp).padding(start = 18.dp, end = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (presentation.showIcon) {
            Image(painterResource(icon), contentDescription = null, colorFilter = ColorFilter.tint(Color(colors.secondary)), modifier = Modifier.size(22.dp).alpha(contentAlpha))
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            val titleColor = if (danger) androidx.compose.ui.res.colorResource(R.color.red_500) else Color(colors.foreground)
            BasicText(title, Modifier.alpha(contentAlpha), style = TextStyle(color = titleColor, fontSize = 16.sp))
            if (!summary.isNullOrBlank()) BasicText(summary, Modifier.padding(top = 2.dp).alpha(contentAlpha), maxLines = 2, overflow = TextOverflow.Ellipsis, style = TextStyle(color = Color(colors.secondary), fontSize = 13.sp))
        }
        if (checked != null) {
            Spacer(Modifier.width(12.dp))
            Canvas(Modifier.size(48.dp).alpha(contentAlpha)) {
                val inactive = when {
                    colors.isPureBlack -> Color(0xff202126)
                    colors.isDark -> Color(0xff303238)
                    else -> Color(0xffe4e7ec)
                }
                val accent = Color(colors.accent)
                val track = if (checked) lerp(inactive, accent, if (colors.isDark || colors.isPureBlack) 0.5f else 0.4f) else inactive
                val thumb = if (checked) accent else if (colors.isDark || colors.isPureBlack) Color(0xffe9ebf0) else Color.White
                val width = 42.dp.toPx()
                val height = 24.dp.toPx()
                val left = (size.width - width) / 2f
                val top = (size.height - height) / 2f
                drawRoundRect(track, Offset(left, top), Size(width, height), CornerRadius(height / 2f))
                val radius = 9.dp.toPx()
                drawCircle(thumb, radius, Offset(if (checked) left + width - 3.dp.toPx() - radius else left + 3.dp.toPx() + radius, size.height / 2f))
            }
        } else if (chevron) {
            Spacer(Modifier.width(12.dp))
            Image(painterResource(R.drawable.ic_chevron_right), contentDescription = null, colorFilter = ColorFilter.tint(Color(colors.secondary)), modifier = Modifier.size(16.dp).alpha(contentAlpha))
        }
    }
}
