/*
 * Copyright (C) 2016-2026 Álinson Santos Xavier <git@axavier.org> and contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.isoron.uhabits.activities.habits.show.views

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.snapping.SnapLayoutInfoProvider
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.isoron.uhabits.R
import kotlin.math.abs

/**
 * Native Jetpack Compose implementation of the historical [MinuteWheelView].
 *
 * Provides a minimal 3-row vertical wheel picker with smooth center snapping,
 * 1-minute resolution, natural scale and alpha falloff, and tactile feedback.
 *
 * Employs top and bottom spacers so that the target item and adjacent rows
 * are immediately laid out on frame 1 without layout shifts or first-frame jumps.
 */
@Composable
fun MinuteWheelPicker(
    value: Int,
    onValueChange: (Int) -> Unit,
    range: IntRange,
    modifier: Modifier = Modifier,
    textColor: Color = Color.Unspecified
) {
    val coroutineScope = rememberCoroutineScope()
    val view = LocalView.current
    val itemCount = range.count()
    // In our list:
    // index 0: top spacer
    // index 1..itemCount: minute values
    // index itemCount + 1: bottom spacer
    // To center minute at minuteIndex (0..itemCount - 1), top item is minuteIndex.
    val initialMinuteIndex = remember(range, value) {
        (value - range.first).coerceIn(0, (itemCount - 1).coerceAtLeast(0))
    }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialMinuteIndex)
    val snapLayoutInfoProvider = remember(listState) {
        SnapLayoutInfoProvider(listState, SnapPosition.Center)
    }
    val flingBehavior = rememberSnapFlingBehavior(snapLayoutInfoProvider)

    val currentCenteredIndex by remember(listState) {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val visibleItems = layoutInfo.visibleItemsInfo
            if (visibleItems.isEmpty()) return@derivedStateOf -1
            val viewportCenter = (layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset) / 2
            val closest = visibleItems.filter { it.index in 1..itemCount }.minByOrNull { item ->
                abs((item.offset + item.size / 2) - viewportCenter)
            }
            if (closest != null) closest.index - 1 else -1
        }
    }

    var userInteracted by remember { mutableStateOf(false) }

    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            userInteracted = true
        }
    }

    LaunchedEffect(currentCenteredIndex) {
        if (userInteracted && currentCenteredIndex in 0 until itemCount) {
            val selectedValue = range.first + currentCenteredIndex
            onValueChange(selectedValue)
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(132.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                    do {
                        val event = awaitPointerEvent()
                    } while (event.changes.any { it.pressed })
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        LazyColumn(
            state = listState,
            flingBehavior = flingBehavior,
            modifier = Modifier
                .fillMaxWidth()
                .height(132.dp)
        ) {
            // 1. Top Spacer
            item(key = "top_spacer") {
                Spacer(modifier = Modifier.fillMaxWidth().height(44.dp))
            }

            // 2. Minute items
            items(
                count = itemCount,
                key = { index -> range.first + index }
            ) { minuteIndex ->
                val minute = range.first + minuteIndex
                val listIndex = 1 + minuteIndex
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .graphicsLayer {
                            val itemInfo = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == listIndex }
                            val viewportHeight = listState.layoutInfo.viewportEndOffset - listState.layoutInfo.viewportStartOffset
                            val progress = if (itemInfo != null && viewportHeight > 0) {
                                val viewportCenter = viewportHeight / 2f
                                val itemCenter = itemInfo.offset + itemInfo.size / 2f
                                val distance = abs(itemCenter - viewportCenter)
                                val itemHeightPx = itemInfo.size.toFloat()
                                if (itemHeightPx > 0f) (distance / itemHeightPx).coerceIn(0f, 1f) else 0f
                            } else {
                                abs(minute - value).coerceIn(0, 1).toFloat()
                            }
                            val scale = 1f - 0.22f * progress
                            scaleX = scale
                            scaleY = scale
                            alpha = 1f - 0.62f * progress
                        }
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            userInteracted = true
                            coroutineScope.launch {
                                // To center listIndex (1 + minuteIndex), scroll to minuteIndex
                                listState.animateScrollToItem(minuteIndex)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    BasicText(
                        text = stringResource(R.string.pomodoro_minutes_short, minute),
                        style = TextStyle(
                            color = textColor,
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            textAlign = TextAlign.Center
                        )
                    )
                }
            }

            // 3. Bottom Spacer
            item(key = "bottom_spacer") {
                Spacer(modifier = Modifier.fillMaxWidth().height(44.dp))
            }
        }
    }
}
