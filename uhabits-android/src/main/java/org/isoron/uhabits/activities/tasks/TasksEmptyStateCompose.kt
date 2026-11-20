/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.activities.tasks

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.isoron.uhabits.R
import org.isoron.uhabits.compose.LocalUHabitComposeColors
import org.isoron.uhabits.compose.UHabitComposeColors
import org.isoron.uhabits.compose.UHabitComposeTheme

/**
 * Minimal & Refined empty state for the Tasks list when no tasks are present.
 */
@Composable
fun TasksEmptyStateCompose(
    title: String,
    message: String,
    colors: UHabitComposeColors = LocalUHabitComposeColors.current,
    modifier: Modifier = Modifier
) {
    UHabitComposeTheme(colors = colors) {
        val themeColors = LocalUHabitComposeColors.current

        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(
                painter = painterResource(R.drawable.ic_cat_list_todo),
                contentDescription = null,
                colorFilter = ColorFilter.tint(Color(themeColors.secondary).copy(alpha = 0.7f)),
                modifier = Modifier.size(48.dp)
            )

            BasicText(
                text = title,
                modifier = Modifier.padding(top = 16.dp),
                style = TextStyle(
                    color = Color(themeColors.foreground),
                    fontSize = 16.sp,
                    fontFamily = FontFamily.Default,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center
                )
            )

            if (message.isNotBlank()) {
                BasicText(
                    text = message,
                    modifier = Modifier.padding(top = 6.dp),
                    style = TextStyle(
                        color = Color(themeColors.secondary),
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Default,
                        fontWeight = FontWeight.Normal,
                        textAlign = TextAlign.Center
                    )
                )
            }
        }
    }
}
