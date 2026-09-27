package org.isoron.uhabits.activities.settings

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import kotlin.math.min

class SettingsMorphLayer(context: Context) : View(context) {
    enum class Direction { FORWARD, RETURN }

    private val startBounds = RectF()
    private val endBounds = RectF()
    private val currentBounds = RectF()
    private val shadowBounds = RectF()
    private val toolbarBounds = RectF()
    private val bodyBounds = RectF()
    private val toolbarDrawBounds = RectF()
    private val bodyDrawBounds = RectF()
    private val roundedPath = Path()
    private val surfacePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private var sourceSnapshot: Bitmap? = null
    private var toolbarSnapshot: Bitmap? = null
    private var bodySnapshot: Bitmap? = null
    private var startColor = Color.TRANSPARENT
    private var endColor = Color.TRANSPARENT
    private var outlineColor = Color.TRANSPARENT
    private var startRadius = 0f
    private var currentRadius = 0f
    private var toolbarAlpha = 0f
    private var toolbarTranslationY = 0f
    private var bodyAlpha = 0f
    private var bodyTranslationY = 0f
    private var snapshotAlpha = 1f
    private var liftPx = 0f
    private var boundaryAlpha = 1f

    fun prepare(
        sourceBounds: RectF,
        destinationBounds: RectF,
        sourceBitmap: Bitmap,
        toolbarBitmap: Bitmap,
        bodyBitmap: Bitmap,
        toolbarInHost: RectF,
        bodyInHost: RectF,
        sourceColor: Int,
        destinationColor: Int,
        boundaryColor: Int,
        sourceCornerRadius: Float,
    ) {
        startBounds.set(sourceBounds)
        endBounds.set(destinationBounds)
        sourceSnapshot = sourceBitmap
        toolbarSnapshot = toolbarBitmap
        bodySnapshot = bodyBitmap
        toolbarBounds.set(toolbarInHost)
        bodyBounds.set(bodyInHost)
        startColor = sourceColor
        endColor = destinationColor
        outlineColor = boundaryColor
        startRadius = sourceCornerRadius
        outlinePaint.strokeWidth = 0.7f * resources.displayMetrics.density
        setFrame(0f, Direction.FORWARD)
    }

    fun updateDetailContent(
        toolbarBitmap: Bitmap,
        bodyBitmap: Bitmap,
        toolbarInHost: RectF,
        bodyInHost: RectF,
    ) {
        toolbarSnapshot?.recycle()
        bodySnapshot?.recycle()
        toolbarSnapshot = toolbarBitmap
        bodySnapshot = bodyBitmap
        toolbarBounds.set(toolbarInHost)
        bodyBounds.set(bodyInHost)
    }

    fun setFrame(progress: Float, direction: Direction) {
        val p = progress.coerceIn(0f, 1f)
        val geometryProgress = if (direction == Direction.FORWARD) p else 1f - p
        val side = SettingsTransitionSpec.smoothstep(0f, 0.92f, geometryProgress)
        val top = SettingsTransitionSpec.smoothstep(0.03f, 0.95f, geometryProgress)
        val bottom = SettingsTransitionSpec.smoothstep(0.07f, 1f, geometryProgress)
        currentBounds.set(
            lerp(startBounds.left, endBounds.left, side),
            lerp(startBounds.top, endBounds.top, top),
            lerp(startBounds.right, endBounds.right, side),
            lerp(startBounds.bottom, endBounds.bottom, bottom),
        )
        val radiusProgress = SettingsTransitionSpec.smoothstep(0.34f, 1f, geometryProgress)
        currentRadius = lerp(startRadius, 0f, radiusProgress)
            .coerceAtMost(min(currentBounds.width(), currentBounds.height()) / 2f)
        val density = resources.displayMetrics.density
        val liftIn = SettingsTransitionSpec.smoothstep(0f, 0.14f, geometryProgress)
        val liftOut = 1f - SettingsTransitionSpec.smoothstep(0.14f, 0.70f, geometryProgress)
        liftPx = 2f * density * min(liftIn, liftOut)
        boundaryAlpha = 1f - SettingsTransitionSpec.smoothstep(0.28f, 0.76f, geometryProgress)

        if (direction == Direction.FORWARD) {
            snapshotAlpha = SettingsTransitionSpec.forwardSourceAlpha(p)
            toolbarAlpha = SettingsTransitionSpec.forwardToolbarAlpha(p)
            bodyAlpha = SettingsTransitionSpec.forwardBodyAlpha(p)
            toolbarTranslationY = (1f - toolbarAlpha) * 5f * density
            bodyTranslationY = (1f - bodyAlpha) * 8f * density
        } else {
            snapshotAlpha = SettingsTransitionSpec.returnSourceAlpha(p)
            toolbarAlpha = SettingsTransitionSpec.returnToolbarAlpha(p)
            bodyAlpha = SettingsTransitionSpec.returnBodyAlpha(p)
            toolbarTranslationY = (1f - toolbarAlpha) * 3f * density
            bodyTranslationY = (1f - bodyAlpha) * 5f * density
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (currentBounds.isEmpty) return
        roundedPath.reset()
        roundedPath.addRoundRect(currentBounds, currentRadius, currentRadius, Path.Direction.CW)

        if (liftPx > 0f) {
            shadowBounds.set(currentBounds)
            shadowBounds.offset(0f, liftPx)
            shadowPaint.color = Color.argb((20f * boundaryAlpha).toInt(), 0, 0, 0)
            canvas.drawRoundRect(shadowBounds, currentRadius, currentRadius, shadowPaint)
        }

        surfacePaint.color = blendColor(startColor, endColor, geometryFraction())
        canvas.drawPath(roundedPath, surfacePaint)
        if (boundaryAlpha > 0f && outlineColor != Color.TRANSPARENT) {
            outlinePaint.color = withAlpha(outlineColor, boundaryAlpha)
            canvas.drawPath(roundedPath, outlinePaint)
        }

        drawPreparedBitmap(canvas, toolbarSnapshot, toolbarBounds, toolbarDrawBounds, toolbarAlpha, toolbarTranslationY)
        drawPreparedBitmap(canvas, bodySnapshot, bodyBounds, bodyDrawBounds, bodyAlpha, bodyTranslationY)

        sourceSnapshot?.let { bitmap ->
            if (snapshotAlpha > 0f) {
                bitmapPaint.alpha = (snapshotAlpha * 255f).toInt().coerceIn(0, 255)
                canvas.drawBitmap(bitmap, null, startBounds, bitmapPaint)
            }
        }
    }

    private fun drawPreparedBitmap(
        canvas: Canvas,
        bitmap: Bitmap?,
        viewBounds: RectF,
        drawBounds: RectF,
        alpha: Float,
        translationY: Float,
    ) {
        if (bitmap == null || alpha <= 0f) return
        drawBounds.set(viewBounds)
        drawBounds.offset(0f, translationY)
        bitmapPaint.alpha = (alpha * 255f).toInt().coerceIn(0, 255)
        val save = canvas.save()
        canvas.clipPath(roundedPath)
        canvas.drawBitmap(bitmap, null, drawBounds, bitmapPaint)
        canvas.restoreToCount(save)
    }

    private fun geometryFraction(): Float {
        val total = endBounds.width() - startBounds.width()
        return if (total == 0f) 1f else ((currentBounds.width() - startBounds.width()) / total).coerceIn(0f, 1f)
    }

    fun release() {
        sourceSnapshot?.recycle()
        toolbarSnapshot?.recycle()
        bodySnapshot?.recycle()
        sourceSnapshot = null
        toolbarSnapshot = null
        bodySnapshot = null
    }

    private fun lerp(start: Float, end: Float, fraction: Float): Float = start + (end - start) * fraction

    private fun withAlpha(color: Int, alpha: Float): Int = Color.argb(
        (Color.alpha(color) * alpha).toInt().coerceIn(0, 255),
        Color.red(color),
        Color.green(color),
        Color.blue(color),
    )

    private fun blendColor(from: Int, to: Int, fraction: Float): Int {
        val f = fraction.coerceIn(0f, 1f)
        return Color.argb(
            lerp(Color.alpha(from).toFloat(), Color.alpha(to).toFloat(), f).toInt(),
            lerp(Color.red(from).toFloat(), Color.red(to).toFloat(), f).toInt(),
            lerp(Color.green(from).toFloat(), Color.green(to).toFloat(), f).toInt(),
            lerp(Color.blue(from).toFloat(), Color.blue(to).toFloat(), f).toInt(),
        )
    }
}
