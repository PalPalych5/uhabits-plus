/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.activities.common.views

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import org.isoron.platform.gui.AndroidCanvas
import org.isoron.platform.gui.DataView
import kotlin.math.roundToInt

/** Reuses the shared chart drawing code with Compose layout, gestures and scrolling. */
@Composable
internal fun ScrollableDataChart(
    renderer: DataView,
    label: String,
    modifier: Modifier = Modifier,
    dateGestures: Boolean = false
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val canvasAdapter = remember(context) { AndroidCanvas().apply { this.context = context } }
    val currentRenderer by rememberUpdatedState(renderer)
    var columnOffset by rememberSaveable { mutableFloatStateOf(renderer.dataOffset.toFloat()) }
    val scrollState = rememberScrollableState { delta ->
        val columnWidthPx = currentRenderer.dataColumnWidth.toFloat() * density
        if (columnWidthPx <= 0f || !columnWidthPx.isFinite()) {
            0f
        } else {
            val previous = columnOffset
            columnOffset = (previous + delta / columnWidthPx).coerceAtLeast(0f)
            (columnOffset - previous) * columnWidthPx
        }
    }
    val platformFling = ScrollableDefaults.flingBehavior()
    val fling = remember(platformFling) {
        object : FlingBehavior {
            override suspend fun ScrollScope.performFling(initialVelocity: Float): Float =
                with(platformFling) { performFling(initialVelocity / 2f) }
        }
    }
    val tapModifier = if (dateGestures) {
        Modifier.pointerInput(renderer) {
            detectTapGestures(
                onTap = { renderer.onClick(it.x.toDouble() / density, it.y.toDouble() / density) },
                onLongPress = { renderer.onLongClick(it.x.toDouble() / density, it.y.toDouble() / density) }
            )
        }
    } else {
        Modifier
    }

    Canvas(
        modifier = modifier
            .clipToBounds()
            .semantics { contentDescription = label }
            .scrollable(scrollState, Orientation.Horizontal, flingBehavior = fling)
            .then(tapModifier)
    ) {
        renderer.dataOffset = columnOffset.toInt()
        drawIntoCanvas { composeCanvas ->
            canvasAdapter.innerCanvas = composeCanvas.nativeCanvas
            canvasAdapter.innerWidth = size.width.roundToInt()
            canvasAdapter.innerHeight = size.height.roundToInt()
            canvasAdapter.innerDensity = density.toDouble()
            renderer.draw(canvasAdapter)
        }
    }
}
