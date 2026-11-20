/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.activities.habits.show.views

import android.graphics.Rect
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.habits.show.ShowHabitSurfaceSpec
import kotlin.math.roundToInt

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun ShowHabitToolbar(
    title: String,
    surface: ShowHabitSurfaceSpec,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onMore: (Rect?) -> Unit,
) {
    val tint = Color(surface.iconColor)
    var moreAnchor by remember { mutableStateOf<Rect?>(null) }
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(surface.toolbarColor))
            .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Top))
            .semantics { testTagsAsResourceId = true },
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.ic_settings_back), stringResource(R.string.settings_back), tint = tint)
            }
            BasicText(
                text = title,
                style = TextStyle(color = tint, fontSize = 20.sp, fontWeight = FontWeight.Medium),
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp).semantics { heading() },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(onClick = onEdit, modifier = Modifier.testTag("action_edit_habit")) {
                Icon(painterResource(R.drawable.ic_toolbar_edit), stringResource(R.string.edit), tint = tint)
            }
            IconButton(
                onClick = { onMore(moreAnchor) },
                modifier = Modifier.testTag("action_more_habit").onGloballyPositioned { coordinates ->
                    val bounds = coordinates.boundsInWindow()
                    moreAnchor = Rect(
                        bounds.left.roundToInt(), bounds.top.roundToInt(),
                        bounds.right.roundToInt(), bounds.bottom.roundToInt(),
                    )
                },
            ) {
                Icon(painterResource(R.drawable.ic_toolbar_more_vertical), stringResource(R.string.today_show_more), tint = tint)
            }
        }
        if (surface.showToolbarDivider) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(surface.dividerColor)))
        }
    }
}
