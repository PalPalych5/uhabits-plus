/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.activities.statistics

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.isoron.uhabits.activities.settings.SettingsThemePalette
import kotlin.math.roundToInt

internal data class StatisticsCohortSegment(
    val key: String,
    val count: Int,
    val label: String,
    val color: Int,
    val textColor: Int,
    val descriptor: TasksDrilldownDescriptor,
)

@Composable
internal fun UnifiedStatisticsCohortBar(
    tag: String,
    segments: List<StatisticsCohortSegment>,
    total: Int,
    palette: SettingsThemePalette,
    enabled: Boolean,
    onSegment: (StatisticsCohortSegment) -> Unit,
) {
    val positive = remember(segments) { segments.filter { it.count > 0 } }
    val latestAction = rememberUpdatedState(onSegment)
    val labelSize = with(LocalDensity.current) { 11.sp.toPx() }
    val paint = remember(labelSize) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
            textSize = labelSize
        }
    }
    Column(Modifier.fillMaxWidth().testTag(tag).padding(top = 4.dp, bottom = 4.dp)) {
        Canvas(
            Modifier.fillMaxWidth().height(24.dp)
                .then(if (enabled && total > 0 && positive.isNotEmpty()) {
                    Modifier.pointerInput(positive, total) {
                        detectTapGestures { position ->
                            var edge = 0f
                            val fraction = position.x / size.width.coerceAtLeast(1)
                            val segment = positive.firstOrNull {
                                edge += it.count.toFloat() / total
                                fraction < edge
                            } ?: positive.last()
                            latestAction.value(segment)
                        }
                    }
                } else Modifier),
        ) {
            if (size.width <= 0f || size.height <= 0f) return@Canvas
            val radius = CornerRadius(6.dp.toPx())
            if (total <= 0 || positive.isEmpty()) {
                drawRoundRect(Color(palette.surfaceVariant), cornerRadius = radius)
            } else {
                val track = Path().apply {
                    addRoundRect(RoundRect(0f, 0f, size.width, size.height, radius))
                }
                clipPath(track) {
                    var start = 0f
                    positive.forEachIndexed { index, segment ->
                        val width = if (index == positive.lastIndex) size.width - start
                            else size.width * segment.count / total
                        drawRect(Color(segment.color), Offset(start, 0f), Size(width, size.height))
                        val count = segment.count.toString()
                        if (width >= paint.measureText(count) + 12.dp.toPx()) {
                            paint.color = segment.textColor
                            val baseline = size.height / 2 - (paint.descent() + paint.ascent()) / 2
                            drawIntoCanvas { it.nativeCanvas.drawText(count, start + width / 2, baseline, paint) }
                        }
                        start += width
                    }
                }
            }
            val strokeWidth = 1.dp.toPx()
            val inset = strokeWidth / 2
            drawRoundRect(
                Color(palette.border), Offset(inset, inset), Size(size.width - strokeWidth, size.height - strokeWidth),
                radius, style = Stroke(strokeWidth),
            )
        }
        Column(Modifier.padding(top = 12.dp)) {
            segments.forEachIndexed { index, segment ->
                val percent = if (total > 0) ", ${(segment.count * 100f / total).roundToInt()}%" else ""
                Row(
                    Modifier.fillMaxWidth().testTag("${tag}_${segment.key}")
                        .heightIn(min = 48.dp)
                        .clickable(enabled = enabled, role = Role.Button) { onSegment(segment) }
                        .semantics { contentDescription = "${segment.label}: ${segment.count}$percent" }
                        .padding(start = 6.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Box(Modifier.size(12.dp).background(Color(segment.color), CircleShape))
                    Column(Modifier.weight(1f).clearAndSetSemantics {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(segment.label, fontSize = 14.sp, color = Color(palette.onSurface))
                        Text(segment.count.toString(), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(palette.onSurfaceVariant))
                    }
                }
                if (index < segments.lastIndex) {
                    HorizontalDivider(Modifier.padding(start = 32.dp, end = 12.dp), thickness = 0.75.dp, color = Color(palette.divider))
                }
            }
        }
    }
}
