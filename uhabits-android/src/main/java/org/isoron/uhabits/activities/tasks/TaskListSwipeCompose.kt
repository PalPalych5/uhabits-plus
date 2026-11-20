/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.activities.tasks

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.isoron.uhabits.R
import org.isoron.uhabits.compose.LocalUHabitComposeColors
import kotlin.math.abs

internal enum class TaskSwipeAction { CHANGE_DUE, MOVE_CONTAINER }

private const val DISTANCE_FRACTION = 0.25f
private const val ESCAPE_VELOCITY_DP = 120f * 1.4f
private const val MAX_VELOCITY_DP = 800f
private const val SETTLE_MILLIS = 90
private val MaxShift = 116.dp

private fun swipeAction(dx: Float, rtl: Boolean): TaskSwipeAction =
    if ((dx > 0) != rtl) TaskSwipeAction.CHANGE_DUE else TaskSwipeAction.MOVE_CONTAINER

internal fun committedTaskSwipe(
    dx: Float,
    widthPx: Int,
    velocityX: Float,
    velocityY: Float,
    density: Float,
    rtl: Boolean,
): TaskSwipeAction? {
    val limit = MAX_VELOCITY_DP * density
    val vx = velocityX.coerceIn(-limit, limit)
    val vy = velocityY.coerceIn(-limit, limit)
    val directionalFling = dx != 0f && (vx > 0) == (dx > 0) &&
        abs(vx) >= ESCAPE_VELOCITY_DP * density && abs(vx) > abs(vy)
    return if (abs(dx) > widthPx * DISTANCE_FRACTION || directionalFling) swipeAction(dx, rtl) else null
}

@Composable
internal fun SwipeableTask(
    enabled: Boolean,
    onSwipe: (TaskSwipeAction) -> Unit,
    content: @Composable () -> Unit,
) {
    val colors = LocalUHabitComposeColors.current
    val density = LocalDensity.current
    val maxShiftPx = with(density) { MaxShift.toPx() }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val dispatchSwipe by rememberUpdatedState(onSwipe)
    var displacement by remember { mutableFloatStateOf(0f) }
    var rowWidth by remember { mutableIntStateOf(0) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clipToBounds()
            .onSizeChanged { rowWidth = it.width }
            .pointerInput(enabled, rtl, density.density) {
                if (!enabled) {
                    coroutineScope {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ ->
                                change.consume()
                            }?.let { drag ->
                                horizontalDrag(drag.id) { change ->
                                    change.consume()
                                }
                            }
                        }
                    }
                    return@pointerInput
                }
                coroutineScope {
                    var settling: Job? = null
                    var committing = false
                    try {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            if (committing) return@awaitEachGesture
                            val tracker = VelocityTracker()
                            tracker.addPosition(down.uptimeMillis, down.position)
                            val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, overSlop ->
                                settling?.cancel()
                                displacement = if (change.id == down.id) change.position.x - down.position.x else overSlop
                                change.consume()
                            } ?: return@awaitEachGesture
                            tracker.addPosition(drag.uptimeMillis, drag.position)
                            val ended = horizontalDrag(drag.id) { change ->
                                displacement += change.positionChange().x
                                tracker.addPosition(change.uptimeMillis, change.position)
                                change.consume()
                            }
                            val action = if (ended) {
                                currentEvent.changes.firstOrNull { it.id == drag.id }?.let { up ->
                                    tracker.addPosition(up.uptimeMillis, up.position)
                                }
                                val velocity = tracker.calculateVelocity()
                                committedTaskSwipe(displacement, size.width, velocity.x, velocity.y, density.density, rtl)
                            } else null

                            committing = action != null
                            val target = when {
                                action == null -> 0f
                                displacement > 0f -> size.width.toFloat()
                                else -> -size.width.toFloat()
                            }
                            settling = launch {
                                animate(
                                    initialValue = displacement,
                                    targetValue = target,
                                    animationSpec = tween(SETTLE_MILLIS),
                                ) { value, _ -> displacement = value }
                                displacement = 0f
                                committing = false
                                action?.let(dispatchSwipe)
                            }
                        }
                    } finally {
                        settling?.cancel()
                        displacement = 0f
                    }
                }
            },
    ) {
        if (abs(displacement) > 1f) {
            val action = swipeAction(displacement, rtl)
            val towardEnd = action == TaskSwipeAction.CHANGE_DUE
            val armed = abs(displacement) >= minOf(rowWidth * DISTANCE_FRACTION, maxShiftPx * 0.85f)
            val background = if (armed) {
                colors.accentColor.copy(alpha = if (colors.isDark) 0.22f else 0.14f)
            } else {
                colors.foregroundColor.copy(alpha = if (colors.isDark) 0.08f else 0.05f)
            }
            val foreground = if (armed) colors.accentColor else colors.secondaryColor
            Box(Modifier.matchParentSize().background(background).clearAndSetSemantics { }) {
                Row(
                    modifier = Modifier
                        .align(if (towardEnd) Alignment.CenterStart else Alignment.CenterEnd)
                        .width(MaxShift)
                        .padding(start = if (towardEnd) 16.dp else 0.dp, end = if (towardEnd) 0.dp else 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, if (towardEnd) Alignment.Start else Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val icon: @Composable () -> Unit = {
                        Image(
                            painterResource(if (towardEnd) R.drawable.ic_cat_calendar else R.drawable.ic_cat_folder),
                            contentDescription = null,
                            colorFilter = ColorFilter.tint(foreground),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    if (towardEnd) icon()
                    BasicText(
                        text = stringResource(if (towardEnd) R.string.task_detail_due else R.string.task_container_picker_title),
                        style = TextStyle(color = foreground, fontSize = 13.sp, fontWeight = FontWeight.Medium),
                        modifier = Modifier.weight(1f, fill = false),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!towardEnd) icon()
                }
            }
        }
        Box(
            Modifier
                .graphicsLayer { translationX = displacement.coerceIn(-maxShiftPx, maxShiftPx) }
                .background(colors.backgroundColor),
        ) { content() }
    }
}
