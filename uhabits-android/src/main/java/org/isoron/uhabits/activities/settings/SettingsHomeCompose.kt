package org.isoron.uhabits.activities.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.isoron.uhabits.R
import org.isoron.uhabits.compose.UHabitComposeColors

@Composable
internal fun SettingsHomeCard(item: SettingItem.Section, colors: UHabitComposeColors) {
    val density = LocalDensity.current
    val titleSize = with(density) { dimensionResource(R.dimen.settings_home_title_size).toSp() }
    val summarySize = with(density) { dimensionResource(R.dimen.settings_home_summary_size).toSp() }
    Column(
        Modifier.fillMaxWidth()
            .clearAndSetSemantics {},
    ) {
        Row(
            Modifier.fillMaxWidth()
                .heightIn(min = dimensionResource(R.dimen.settings_home_min_height))
                .padding(
                    start = dimensionResource(R.dimen.settings_home_padding_start),
                    end = dimensionResource(R.dimen.settings_home_padding_end),
                    top = dimensionResource(R.dimen.settings_home_padding_vertical),
                    bottom = dimensionResource(R.dimen.settings_home_padding_vertical),
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(painterResource(item.iconRes), null, Modifier.size(dimensionResource(R.dimen.settings_home_icon_size)),
                colorFilter = ColorFilter.tint(Color(colors.accent)))
            Spacer(Modifier.width(dimensionResource(R.dimen.settings_home_icon_gap)))
            Column(Modifier.weight(1f)) {
                BasicText(item.title, style = TextStyle(color = Color(colors.foreground), fontSize = titleSize, fontWeight = FontWeight.Medium,
                    platformStyle = PlatformTextStyle(includeFontPadding = true)))
                Spacer(Modifier.height(2.dp))
                BasicText(item.summary, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = TextStyle(color = Color(colors.secondary), fontSize = summarySize,
                        platformStyle = PlatformTextStyle(includeFontPadding = true)))
            }
            Spacer(Modifier.width(dimensionResource(R.dimen.settings_home_end_icon_gap)))
            Image(painterResource(item.endIconRes ?: R.drawable.ic_chevron_right), null,
                Modifier.size(dimensionResource(R.dimen.settings_home_end_icon_size)),
                colorFilter = ColorFilter.tint(Color(colors.secondary)))
        }
        if (colors.isClassicSectionLayout && !item.presentation.isLast) {
            val startInset = dimensionResource(R.dimen.settings_home_padding_start) +
                dimensionResource(R.dimen.settings_home_icon_size) +
                dimensionResource(R.dimen.settings_home_icon_gap)
            val endInset = dimensionResource(R.dimen.settings_home_padding_end)
            Spacer(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = startInset, end = endInset)
                    .height(1.dp)
                    .background(Color(colors.divider)),
            )
        }
    }
}
