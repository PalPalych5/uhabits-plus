/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.activities.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.isoron.uhabits.R
import org.isoron.uhabits.compose.LocalUHabitComposeColors
import org.isoron.uhabits.compose.components.UHabitSectionCard

@Composable
fun StatisticsHelpComposeScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalUHabitComposeColors.current
    val sections = listOf(
        R.string.statistics_help_completion_title to R.string.statistics_help_completion_body,
        R.string.statistics_help_fully_closed_title to R.string.statistics_help_fully_closed_body,
        R.string.statistics_help_stability_title to R.string.statistics_help_stability_body
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(colors.background))
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Top Toolbar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .background(Color(colors.background))
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable(
                        role = Role.Button,
                        onClick = onBack,
                        onClickLabel = stringResource(R.string.statistics_help_back)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_settings_back),
                    contentDescription = stringResource(R.string.statistics_help_back),
                    tint = Color(colors.foreground),
                    modifier = Modifier.size(24.dp)
                )
            }
            Text(
                text = stringResource(R.string.statistics_local_help_title),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color(colors.foreground),
                modifier = Modifier
                    .padding(start = 8.dp)
                    .semantics { heading() }
            )
        }

        // Scrollable Body
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            sections.forEach { (titleRes, bodyRes) ->
                UHabitSectionCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                ) {
                    Text(
                        text = stringResource(titleRes),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(colors.foreground),
                        modifier = Modifier.semantics { heading() }
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(bodyRes),
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                        color = Color(colors.secondary)
                    )
                }
            }

            // Note Card
            UHabitSectionCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                Text(
                    text = stringResource(R.string.statistics_help_period_goals_note),
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = Color(colors.secondary)
                )
            }
        }
    }
}
