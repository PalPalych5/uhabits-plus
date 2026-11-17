/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.activities.statistics.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import androidx.core.graphics.ColorUtils
import org.isoron.uhabits.activities.settings.SettingsThemePalette

/**
 * Compact Concept 2 horizontal progress / limit bar.
 * For AT_MOST limit habits, draws a subtle threshold tick at 100% of the allowed cap
 * (even when exceeded, placing the cap marker proportionally along the filled bar).
 */
class WeeklyLimitProgressView(context: Context) : View(context) {

    private var ratio: Float = 0f
    private var fillColor: Int = 0xFF4CAF50.toInt()
    private var trackColor: Int = 0xFFE5E7EB.toInt()
    private var thresholdColor: Int = 0xFF6B7280.toInt()
    private var showLimitMarker: Boolean = false

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val thresholdPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rect = RectF()

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    fun setState(
        progressRatio: Float,
        fillColor: Int,
        palette: SettingsThemePalette,
        showLimitMarker: Boolean,
    ) {
        this.ratio = progressRatio.coerceAtLeast(0f)
        this.fillColor = fillColor
        this.trackColor = if (palette.isPureBlack) {
            0xFF1A1F28.toInt()
        } else if (palette.isDark) {
            ColorUtils.blendARGB(palette.surface, palette.onSurface, 0.14f)
        } else {
            ColorUtils.blendARGB(palette.surface, palette.onSurface, 0.10f)
        }
        this.thresholdColor = ColorUtils.setAlphaComponent(palette.onSurface, 140)
        this.showLimitMarker = showLimitMarker
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredH = dp(10f).toInt().coerceAtLeast(10)
        val width = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(width, resolveSize(desiredH, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val barH = dp(6f)
        val top = (h - barH) * 0.5f
        val bottom = top + barH
        val radius = barH * 0.5f

        trackPaint.color = trackColor
        rect.set(0f, top, w, bottom)
        canvas.drawRoundRect(rect, radius, radius, trackPaint)

        val clampedFillRatio = ratio.coerceIn(0f, 1f)
        if (clampedFillRatio > 0f) {
            val fillWidth = (w * clampedFillRatio).coerceAtLeast(barH)
            fillPaint.color = fillColor
            rect.set(0f, top, fillWidth.coerceAtMost(w), bottom)
            canvas.drawRoundRect(rect, radius, radius, fillPaint)
        }

        if (showLimitMarker) {
            // At or below limit: threshold sits at right edge of bar.
            // Over limit (ratio > 1f): threshold marker sits proportionally at 1 / ratio.
            val thresholdX = if (ratio > 1f) {
                (w / ratio).coerceIn(dp(4f), w - dp(4f))
            } else {
                w - dp(2f)
            }
            val markerW = dp(2f)
            thresholdPaint.color = thresholdColor
            rect.set(
                (thresholdX - markerW * 0.5f).coerceAtLeast(0f),
                0f,
                (thresholdX + markerW * 0.5f).coerceAtMost(w),
                h
            )
            canvas.drawRoundRect(rect, dp(1f), dp(1f), thresholdPaint)
        }
    }
}
