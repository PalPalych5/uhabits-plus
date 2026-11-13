package org.isoron.uhabits.activities.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import org.isoron.uhabits.R
import org.isoron.uhabits.compose.UHabitComposeColors
import org.isoron.uhabits.compose.UHabitComposeTheme

@Composable
internal fun CardRoundingDialogContent(
    colors: UHabitComposeColors,
    allowedValues: List<Int>,
    initialHabitsRadius: Int,
    initialStatsRadius: Int,
    formatRadiusLabel: (Int) -> String,
    onHabitsRadiusChanged: (Int) -> Unit,
    onStatsRadiusChanged: (Int) -> Unit,
) {
    UHabitComposeTheme(colors) {
        var habitsIndex by remember(initialHabitsRadius, allowedValues) {
            mutableIntStateOf(allowedValues.indexOf(initialHabitsRadius).coerceAtLeast(0))
        }
        var statsIndex by remember(initialStatsRadius, allowedValues) {
            mutableIntStateOf(allowedValues.indexOf(initialStatsRadius).coerceAtLeast(0))
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
        ) {
            CornerRadiusSliderSection(
                label = stringResource(R.string.pref_habits_card_corners_title),
                colors = colors,
                allowedValues = allowedValues,
                selectedIndex = habitsIndex,
                formatRadiusLabel = formatRadiusLabel,
                onIndexSelected = { newIndex ->
                    habitsIndex = newIndex
                    onHabitsRadiusChanged(allowedValues[newIndex])
                },
            )
            Spacer(modifier = Modifier.height(12.dp))
            CornerRadiusSliderSection(
                label = stringResource(R.string.pref_statistics_card_corners_title),
                colors = colors,
                allowedValues = allowedValues,
                selectedIndex = statsIndex,
                formatRadiusLabel = formatRadiusLabel,
                onIndexSelected = { newIndex ->
                    statsIndex = newIndex
                    onStatsRadiusChanged(allowedValues[newIndex])
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CornerRadiusSliderSection(
    label: String,
    colors: UHabitComposeColors,
    allowedValues: List<Int>,
    selectedIndex: Int,
    formatRadiusLabel: (Int) -> String,
    onIndexSelected: (Int) -> Unit,
) {
    val clampedIndex = selectedIndex.coerceIn(0, allowedValues.lastIndex)
    val currentRadius = allowedValues[clampedIndex]
    val fraction = if (allowedValues.size > 1) {
        clampedIndex.toFloat() / allowedValues.lastIndex.toFloat()
    } else {
        0f
    }
    val sliderColors = SliderDefaults.colors(
        thumbColor = Color(colors.accent),
        activeTrackColor = Color(colors.accent),
        inactiveTrackColor = Color(colors.border),
        activeTickColor = Color(colors.surface),
        inactiveTickColor = Color(colors.secondary),
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                color = Color(colors.foreground),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp),
            )
            Text(
                text = formatRadiusLabel(currentRadius),
                color = Color(colors.secondary),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Slider(
            value = clampedIndex.toFloat(),
            onValueChange = { rawValue ->
                val snapped = rawValue.roundToInt().coerceIn(0, allowedValues.lastIndex)
                if (snapped != clampedIndex) {
                    onIndexSelected(snapped)
                }
            },
            valueRange = 0f..allowedValues.lastIndex.toFloat(),
            steps = (allowedValues.size - 2).coerceAtLeast(0),
            modifier = Modifier.semantics {
                contentDescription = label
                stateDescription = formatRadiusLabel(currentRadius)
            },
            colors = sliderColors,
            thumb = {
                Box(
                    modifier = Modifier.size(20.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(Color(colors.accent))
                            .border(2.dp, Color(colors.surface), CircleShape),
                    )
                }
            },
            track = {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp),
                ) {
                    val cornerRadius = CornerRadius(size.height / 2f, size.height / 2f)
                    drawRoundRect(
                        color = Color(colors.border),
                        topLeft = Offset.Zero,
                        size = size,
                        cornerRadius = cornerRadius,
                    )
                    if (fraction > 0f) {
                        drawRoundRect(
                            color = Color(colors.accent),
                            topLeft = Offset.Zero,
                            size = Size(size.width * fraction, size.height),
                            cornerRadius = cornerRadius,
                        )
                    }
                    val dotRadius = 1.5.dp.toPx()
                    val stepCount = allowedValues.lastIndex
                    if (stepCount > 0) {
                        for (i in 0..stepCount) {
                            val t = i.toFloat() / stepCount.toFloat()
                            val cx = dotRadius + (size.width - dotRadius * 2f) * t
                            val dotColor = if (i <= clampedIndex) {
                                Color(colors.surface).copy(alpha = 0.65f)
                            } else {
                                Color(colors.secondary).copy(alpha = 0.55f)
                            }
                            drawCircle(
                                color = dotColor,
                                radius = dotRadius,
                                center = Offset(cx, size.height / 2f),
                            )
                        }
                    }
                }
            },
        )
    }
}


