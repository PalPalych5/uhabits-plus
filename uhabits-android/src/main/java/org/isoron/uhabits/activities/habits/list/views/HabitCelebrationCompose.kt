/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.activities.habits.list.views

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

internal class CelebrationParticle(
    val color: Color,
    val angleRad: Float,
    val speed: Float,
    val initialSize: Float,
    val rotationSpeed: Float,
    val isCircle: Boolean
)

internal class CelebrationBurstInstance(
    val id: Long,
    val originX: Float,
    val originY: Float,
    val particles: List<CelebrationParticle>
)

class HabitCelebrationState {
    internal var bursts by mutableStateOf<List<CelebrationBurstInstance>>(emptyList())
        private set

    fun burst(x: Float, y: Float, colors: List<Color>) {
        if (colors.isEmpty()) return
        val random = Random(System.nanoTime())
        val particles = List(28) {
            val angle = random.nextFloat() * 2f * Math.PI.toFloat()
            val speed = 120f + random.nextFloat() * 320f
            val size = 7f + random.nextFloat() * 7f
            val rotSpeed = (random.nextFloat() - 0.5f) * 720f
            val color = colors[random.nextInt(colors.size)]
            val isCircle = random.nextBoolean()
            CelebrationParticle(color, angle, speed, size, rotSpeed, isCircle)
        }
        val instance = CelebrationBurstInstance(System.nanoTime(), x, y, particles)
        bursts = bursts + instance
    }

    internal fun removeBurst(id: Long) {
        bursts = bursts.filter { it.id != id }
    }
}

@Composable
fun HabitCelebrationOverlay(
    state: HabitCelebrationState,
    modifier: Modifier = Modifier
) {
    if (state.bursts.isEmpty()) return

    state.bursts.forEach { burst ->
        key(burst.id) {
            val progress = remember { Animatable(0f) }
            LaunchedEffect(burst.id) {
                progress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = 850, easing = LinearEasing)
                )
                state.removeBurst(burst.id)
            }

            val t = progress.value
            Canvas(modifier = modifier.fillMaxSize()) {
                val dampingFactor = (1f - (1f - t) * (1f - t)) // smooth decelerate
                burst.particles.forEach { p ->
                    val distance = p.speed * dampingFactor * 0.7f
                    val gravity = 220f * (t * t)
                    val px = burst.originX + cos(p.angleRad) * distance
                    val py = burst.originY + sin(p.angleRad) * distance + gravity
                    val alpha = (1f - t).coerceIn(0f, 1f)
                    val particleColor = p.color.copy(alpha = alpha)

                    rotate(degrees = p.rotationSpeed * t, pivot = Offset(px, py)) {
                        if (p.isCircle) {
                            drawCircle(
                                color = particleColor,
                                radius = p.initialSize * 0.5f * (1f - 0.3f * t),
                                center = Offset(px, py)
                            )
                        } else {
                            val w = p.initialSize * (1f - 0.2f * t)
                            val h = p.initialSize * 0.6f * (1f - 0.2f * t)
                            drawRect(
                                color = particleColor,
                                topLeft = Offset(px - w / 2, py - h / 2),
                                size = Size(w, h)
                            )
                        }
                    }
                }
            }
        }
    }
}
