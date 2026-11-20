/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.activities.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.isoron.uhabits.R
import org.isoron.uhabits.compose.LocalUHabitComposeColors
import org.isoron.uhabits.compose.UHabitComposeColors
import org.isoron.uhabits.compose.UHabitComposeTheme

/**
 * Minimal & Refined info header row displaying task counter, tag filter chip, and date.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TasksListInfoCompose(
    subtitle: String,
    dateText: String? = null,
    tagFilterLabel: String? = null,
    colors: UHabitComposeColors = LocalUHabitComposeColors.current,
    modifier: Modifier = Modifier,
    onTagFilterClick: (() -> Unit)? = null
) {
    if (subtitle.isBlank() && tagFilterLabel.isNullOrBlank() && dateText.isNullOrBlank()) {
        return
    }

    UHabitComposeTheme(colors = colors) {
        val themeColors = LocalUHabitComposeColors.current

        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(start = 56.dp, end = 16.dp, top = 2.dp, bottom = 12.dp)
                .semantics { testTagsAsResourceId = true },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            BasicText(
                text = subtitle,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .testTag("tvTasksSubtitle"),
                style = TextStyle(
                    color = Color(themeColors.secondary),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Default,
                    fontWeight = FontWeight.Normal
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!tagFilterLabel.isNullOrBlank()) {
                    val tagFilterDesc = stringResource(R.string.task_tags_filter_desc)
                    val shape = RoundedCornerShape(6.dp)
                    val chipAccent = Color(themeColors.accent)

                    Box(
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .clip(shape)
                            .background(chipAccent.copy(alpha = if (themeColors.isDark) 0.18f else 0.10f))
                            .border(1.dp, chipAccent.copy(alpha = 0.40f), shape)
                            .then(
                                if (onTagFilterClick != null) {
                                    Modifier.clickable(role = Role.Button, onClick = onTagFilterClick)
                                } else Modifier
                            )
                            .testTag("tvTasksTagFilter")
                            .semantics {
                                this.role = Role.Button
                                this.contentDescription = tagFilterDesc
                            }
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        BasicText(
                            text = tagFilterLabel,
                            style = TextStyle(
                                color = chipAccent,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Default,
                                fontWeight = FontWeight.Medium
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (!dateText.isNullOrBlank()) {
                    Spacer(modifier = Modifier.width(8.dp))
                    BasicText(
                        text = dateText,
                        modifier = Modifier.testTag("tvTasksDate"),
                        style = TextStyle(
                            color = Color(themeColors.secondary),
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Default,
                            fontWeight = FontWeight.Normal
                        ),
                        maxLines = 1
                    )
                }
            }
        }
    }
}
