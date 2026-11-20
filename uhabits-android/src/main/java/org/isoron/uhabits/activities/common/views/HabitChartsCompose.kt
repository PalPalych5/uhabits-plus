/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.activities.common.views

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import org.isoron.platform.time.DayOfWeek
import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.core.models.Score
import org.isoron.uhabits.core.models.Streak

/**
 * Horizontal bucket-aligned scroll & fling state matching [ScrollableChart] gesture semantics:
 * dragging right increases [dataOffset] into older history; dragging left returns toward 0.
 */
@Stable
class ChartHorizontalScrollState(
    private val maxDataOffset: Int = DEFAULT_MAX_DATA_OFFSET
) {
    internal val animatable = Animatable(0f)
    internal var bucketSizePx: Int = 1
        private set

    val dataOffset: Int
        get() {
            val bucket = bucketSizePx
            if (bucket <= 0) return 0
            return (animatable.value / bucket).toInt().coerceIn(0, maxDataOffset)
        }

    private val maxScrollPx: Float
        get() = (maxDataOffset * bucketSizePx).toFloat()

    internal fun updateBucketSize(newBucketSize: Int) {
        val resolved = newBucketSize.coerceAtLeast(1)
        if (bucketSizePx != resolved) {
            bucketSizePx = resolved
            animatable.updateBounds(lowerBound = 0f, upperBound = maxScrollPx)
        }
    }

    internal suspend fun reset() {
        animatable.snapTo(0f)
    }

    internal suspend fun stop() = animatable.stop()

    internal suspend fun snapBy(deltaPx: Float) {
        val target = (animatable.value + deltaPx).coerceIn(0f, maxScrollPx)
        animatable.snapTo(target)
    }

    internal suspend fun fling(
        velocityPx: Float,
        decay: DecayAnimationSpec<Float>,
    ) {
        animatable.animateDecay(initialVelocity = velocityPx / 2f, animationSpec = decay)
    }

    companion object {
        const val DEFAULT_MAX_DATA_OFFSET = 12 * 200
    }
}

@Composable
fun rememberChartHorizontalScrollState(
    resetKey: Any? = Unit,
    maxDataOffset: Int = ChartHorizontalScrollState.DEFAULT_MAX_DATA_OFFSET
): ChartHorizontalScrollState {
    val state = remember { ChartHorizontalScrollState(maxDataOffset) }
    LaunchedEffect(resetKey) {
        state.reset()
    }
    return state
}

@Composable
fun ScoreChartCanvas(
    scores: List<Score>,
    bucketSize: Int,
    primaryColor: Int,
    textColor: Int,
    gridColor: Int,
    backgroundColor: Int,
    modifier: Modifier = Modifier,
    isTransparencyEnabled: Boolean = true
) {
    val context = LocalContext.current
    val renderer = remember { ScoreChartRenderer() }
    val scrollState = rememberChartHorizontalScrollState(resetKey = bucketSize)
    val scope = rememberCoroutineScope()
    val decay = rememberSplineBasedDecay<Float>()
    val draggableState = rememberDraggableState { delta ->
        scope.launch(start = CoroutineStart.UNDISPATCHED) { scrollState.snapBy(delta) }
    }

    Canvas(
        modifier = modifier
            .clipToBounds()
            .onSizeChanged { size ->
                if (size.width > 0 && size.height > 0) {
                    val bucketPx = renderer.updateSize(context, size.width, size.height)
                    scrollState.updateBucketSize(bucketPx)
                }
            }
            .draggable(
                state = draggableState,
                orientation = Orientation.Horizontal,
                reverseDirection = false,
                onDragStarted = { scrollState.stop() },
                onDragStopped = { velocity -> scrollState.fling(velocity, decay) }
            )
    ) {
        drawIntoCanvas { canvas ->
            renderer.draw(
                context = context,
                canvas = canvas.nativeCanvas,
                width = size.width.toInt(),
                height = size.height.toInt(),
                scores = scores,
                dataOffset = scrollState.dataOffset,
                bucketSize = bucketSize,
                primaryColor = primaryColor,
                textColor = textColor,
                gridColor = gridColor,
                backgroundColor = backgroundColor,
                isTransparencyEnabled = isTransparencyEnabled
            )
        }
    }
}

@Composable
fun FrequencyChartCanvas(
    frequency: Map<LocalDate, Array<Int>>,
    isNumerical: Boolean,
    firstWeekday: DayOfWeek,
    primaryColor: Int,
    textColor: Int,
    gridColor: Int,
    modifier: Modifier = Modifier
) {
    val renderer = remember { FrequencyChartRenderer() }
    val maxFreq = remember(frequency) { FrequencyChartRenderer.computeMaxFreq(frequency) }
    val scrollState = rememberChartHorizontalScrollState()
    val scope = rememberCoroutineScope()
    val decay = rememberSplineBasedDecay<Float>()
    val draggableState = rememberDraggableState { delta ->
        scope.launch(start = CoroutineStart.UNDISPATCHED) { scrollState.snapBy(delta) }
    }

    Canvas(
        modifier = modifier
            .clipToBounds()
            .onSizeChanged { size ->
                if (size.width > 0 && size.height > 0) {
                    val bucketPx = renderer.updateSize(size.width, size.height)
                    scrollState.updateBucketSize(bucketPx)
                }
            }
            .draggable(
                state = draggableState,
                orientation = Orientation.Horizontal,
                reverseDirection = false,
                onDragStarted = { scrollState.stop() },
                onDragStopped = { velocity -> scrollState.fling(velocity, decay) }
            )
    ) {
        drawIntoCanvas { canvas ->
            renderer.draw(
                canvas = canvas.nativeCanvas,
                width = size.width.toInt(),
                height = size.height.toInt(),
                frequency = frequency,
                maxFreq = maxFreq,
                dataOffset = scrollState.dataOffset,
                firstWeekday = firstWeekday,
                isNumerical = isNumerical,
                primaryColor = primaryColor,
                textColor = textColor,
                gridColor = gridColor
            )
        }
    }
}

@Composable
fun StreakChartCanvas(
    streaks: List<Streak>,
    maxLengthOverride: Long?,
    primaryColor: Int,
    trackColor: Int,
    labelColor: Int,
    filledTextColor: Int,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val renderer = remember { StreakChartRenderer() }

    Canvas(modifier = modifier.clipToBounds()) {
        drawIntoCanvas { canvas ->
            renderer.draw(
                context = context,
                canvas = canvas.nativeCanvas,
                width = size.width.toInt(),
                streaks = streaks,
                maxLengthOverride = maxLengthOverride,
                primaryColor = primaryColor,
                trackColor = trackColor,
                labelColor = labelColor,
                filledTextColor = filledTextColor
            )
        }
    }
}
