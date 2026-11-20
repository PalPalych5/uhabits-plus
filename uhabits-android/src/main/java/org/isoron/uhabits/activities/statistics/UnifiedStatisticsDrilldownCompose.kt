/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.activities.statistics

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.rememberNestedScrollInteropConnection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.isoron.uhabits.R
import org.isoron.uhabits.compose.LocalUHabitComposeColors

internal data class StatisticsDrilldownContent(
    val subtitle: String = "",
    val queryContext: String = "",
    val rows: List<StatisticsDrilldownRow> = emptyList(),
    val message: String? = null,
    val showRetry: Boolean = false,
    val hasPreviousPage: Boolean = false,
    val hasNextPage: Boolean = false,
)

internal data class StatisticsDrilldownRow(
    val id: String,
    val title: String,
    val metadata: List<String> = emptyList(),
    val status: String? = null,
    val statusColor: Int? = null,
    val navigation: Boolean = false,
    val inlineStatus: Boolean = false,
    val chevron: Boolean = false,
    val onOpen: () -> Unit,
)

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun UnifiedStatisticsDrilldownCompose(
    title: String,
    tag: String,
    content: StatisticsDrilldownContent,
    searchQuery: String?,
    onSearch: (String) -> Unit,
    previousLabel: String,
    nextLabel: String,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onRetry: () -> Unit,
) {
    val colors = LocalUHabitComposeColors.current
    // The Fragment's existing dialog frame owns the surface, outline and elevation.
    BoxWithConstraints(
        Modifier.fillMaxWidth().testTag(tag).semantics { testTagsAsResourceId = true }.navigationBarsPadding(),
    ) {
        if (!constraints.hasBoundedHeight || !constraints.hasBoundedWidth) return@BoxWithConstraints
        LazyColumn(
            Modifier.fillMaxWidth().testTag("statistics_drilldown_list")
                .nestedScroll(rememberNestedScrollInteropConnection()),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp),
        ) {
            item("heading") {
                Column {
                    Text(
                        title, Modifier.semantics { heading() },
                        color = colors.foregroundColor, fontSize = 19.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold,
                    )
                    Text(
                        content.subtitle, Modifier.padding(top = 4.dp, bottom = 12.dp).testTag("statistics_drilldown_subtitle"),
                        color = colors.secondaryColor, fontSize = 14.sp, lineHeight = 18.sp,
                    )
                    if (content.queryContext.isNotEmpty()) {
                        Text(
                            content.queryContext, Modifier.padding(bottom = 12.dp).testTag("statistics_drilldown_scope"),
                            color = colors.secondaryColor, fontSize = 13.sp, lineHeight = 18.sp,
                        )
                    }
                }
            }
            if (searchQuery != null) {
                item("search") { DrilldownHabitSearch(searchQuery, onSearch) }
            }
            content.message?.let { message ->
                item("message") {
                    Text(
                        message, Modifier.padding(vertical = 12.dp).testTag("statistics_drilldown_message"),
                        color = colors.secondaryColor, fontSize = 14.sp, lineHeight = 18.sp,
                    )
                }
            }
            items(content.rows, key = { "row:${it.id}" }) { row -> DrilldownRow(row) }
            if (content.showRetry) {
                item("retry") {
                    UnifiedStatisticsReportAction(
                        stringResource(R.string.unified_statistics_state_retry), true, onRetry,
                        Modifier.testTag("statistics_drilldown_retry"),
                    )
                }
            }
            if (content.hasPreviousPage) {
                item("previous") {
                    UnifiedStatisticsReportAction(previousLabel, true, onPrevious, Modifier.testTag("statistics_drilldown_previous"))
                }
            }
            if (content.hasNextPage) {
                item("next") {
                    UnifiedStatisticsReportAction(nextLabel, true, onNext, Modifier.testTag("statistics_drilldown_next"))
                }
            }
        }
    }
}

@Composable
private fun DrilldownHabitSearch(query: String, onSearch: (String) -> Unit) {
    val colors = LocalUHabitComposeColors.current
    val hint = stringResource(R.string.unified_statistics_habits_search_hint)
    BasicTextField(
        value = query,
        onValueChange = onSearch,
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).testTag("habitSearchInput"),
        textStyle = TextStyle(color = colors.foregroundColor, fontSize = 16.sp, lineHeight = 20.sp),
        cursorBrush = SolidColor(colors.accentColor),
        decorationBox = { field ->
            Box(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .background(colors.surfaceVariantColor, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (query.isEmpty()) Text(hint, fontSize = 16.sp, lineHeight = 20.sp, color = colors.secondaryColor)
                field()
            }
        },
    )
}

@Composable
private fun DrilldownRow(row: StatisticsDrilldownRow) {
    val colors = LocalUHabitComposeColors.current
    val shape = RoundedCornerShape(8.dp)
    val decoration = if (row.navigation) Modifier else Modifier.padding(bottom = 8.dp).clip(shape).background(colors.surfaceVariantColor)
    val statusColor = row.statusColor?.let { Color(it) } ?: colors.secondaryColor
    val status: @Composable (Modifier) -> Unit = { modifier ->
        row.status?.let { Text(it, modifier, color = statusColor, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold) }
    }
    val title: @Composable (Modifier) -> Unit = { modifier ->
        Text(
            row.title, modifier, color = colors.foregroundColor, fontSize = 15.sp, lineHeight = 20.sp,
            fontWeight = if (row.navigation) FontWeight.Medium else FontWeight.Bold,
        )
    }
    Column(
        Modifier.fillMaxWidth().then(decoration).testTag(row.id).heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = row.onOpen)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        if (row.chevron || (row.inlineStatus && !statisticsStacked)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                title(Modifier.weight(1f))
                if (row.inlineStatus) status(Modifier.weight(1f, fill = false))
                if (row.chevron) {
                    Text(
                        if (LocalLayoutDirection.current == LayoutDirection.Ltr) "›" else "‹",
                        Modifier.clearAndSetSemantics {}, color = colors.secondaryColor, fontSize = 18.sp,
                    )
                }
            }
        } else {
            title(Modifier)
            status(Modifier.padding(top = 4.dp))
        }
        row.metadata.forEach { text ->
            Text(text, Modifier.padding(top = 4.dp), color = colors.secondaryColor, fontSize = 13.sp, lineHeight = 17.sp)
        }
    }
}
