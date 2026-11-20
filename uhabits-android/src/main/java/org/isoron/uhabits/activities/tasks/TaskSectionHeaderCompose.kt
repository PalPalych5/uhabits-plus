/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.activities.tasks

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
 * Minimal & Refined section disclosure header for task groups and sections (Overdue, Today,
 * Matrix quadrants, Completed, Container paths).
 */
@Composable
fun TaskSectionHeaderCompose(
    title: String,
    expanded: Boolean = true,
    colors: UHabitComposeColors = LocalUHabitComposeColors.current,
    modifier: Modifier = Modifier,
    count: Int? = null,
    onToggle: (() -> Unit)? = null,
) {
    UHabitComposeTheme(colors = colors) {
        val themeColors = LocalUHabitComposeColors.current
        val context = LocalContext.current
        val isRu = context.resources.configuration.locales[0].language == "ru"
        val expandedState = if (isRu) "Развёрнуто" else "Expanded"
        val collapsedState = if (isRu) "Свёрнуто" else "Collapsed"
        val sectionA11yLabel = if (isRu) "Раздел: $title" else "Section: $title"

        val chevronRotation by animateFloatAsState(
            targetValue = if (expanded) 0f else 180f,
            animationSpec = tween(durationMillis = 200),
            label = "chevronRotation"
        )

        val clickableModifier = if (onToggle != null) {
            Modifier.clickable(
                role = Role.Button,
                onClick = onToggle
            )
        } else {
            Modifier
        }

        Row(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .background(Color.Transparent)
                .then(clickableModifier)
                .semantics(mergeDescendants = true) {
                    heading()
                    this.contentDescription = sectionA11yLabel
                    if (onToggle != null) {
                        this.role = Role.Button
                        this.stateDescription = if (expanded) expandedState else collapsedState
                    }
                }
                .padding(start = 24.dp, top = 8.dp, end = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f, fill = false),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicText(
                    text = title,
                    style = TextStyle(
                        color = Color(themeColors.secondary),
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Default,
                        fontWeight = FontWeight.Medium
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (count != null && count > 0) {
                    BasicText(
                        text = " · $count",
                        style = TextStyle(
                            color = Color(themeColors.secondary).copy(alpha = 0.7f),
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Default,
                            fontWeight = FontWeight.Normal
                        )
                    )
                }
            }

            if (onToggle != null) {
                Box(
                    modifier = Modifier.size(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_chevron_up),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(Color(themeColors.secondary)),
                        modifier = Modifier
                            .size(16.dp)
                            .rotate(chevronRotation)
                    )
                }
            }
        }
    }
}
