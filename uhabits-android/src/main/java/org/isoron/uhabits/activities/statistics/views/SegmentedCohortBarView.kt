/*
 * Copyright (C) 2016-2026 Álinson Santos Xavier <git@axavier.org> and contributors
 *
 * This file is part of Loop Habit Tracker.
 *
 * Loop Habit Tracker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 */

package org.isoron.uhabits.activities.statistics.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import org.isoron.uhabits.activities.settings.SettingsThemePalette
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Quality interactive proportion bar for cohort breakdowns (T3 Due, T4 NoDue).
 * Features:
 * - Proportional visual segment blocks at the top.
 * - Independent labeled >= 48dp interactive rows below the bar for all segments (including zero-count states).
 * - Thin segments (< 48dp) have unambiguous full-row tap targets so touch accuracy is never compromised.
 * - Full accessible TalkBack integration using ExploreByTouchHelper virtual nodes (>= 48dp target for each bucket).
 * - Safe gesture handling compatible with vertical scroll containers and ACTION_CANCEL.
 * - Semantic theme palette compliance (Light, Dark, AMOLED).
 */
class SegmentedCohortBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    data class Segment(
        val key: String,
        val count: Int,
        val label: String,
        val color: Int,
        val textColor: Int,
        val descriptor: Any? = null,
    )

    private var segments: List<Segment> = emptyList()
    private var totalCount: Int = 0
    private var onSegmentTapped: ((segment: Segment) -> Unit)? = null
    private var palette: SettingsThemePalette? = null

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val barTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val rowTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private var rowWidth = 1
    private fun labelLayout(label: String): StaticLayout = StaticLayout.Builder.obtain(label, 0, label.length,
        rowTextPaint, (rowWidth - dp(48f)).toInt().coerceAtLeast(1)).setIncludePad(false).build()
    private val rowCountPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.RIGHT
        fontFeatureSettings = "tnum"
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    private val rect = RectF()
    private val segmentBounds = mutableListOf<Pair<Segment, RectF>>()
    private val visualBarBounds = mutableListOf<Pair<Segment, RectF>>()

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX: Float = 0f
    private var downY: Float = 0f
    private var isTouchCancelled: Boolean = false

    private val accessibilityHelper = CohortAccessibilityHelper(this)

    private fun dp(value: Float) = value * resources.displayMetrics.density
    private fun sp(value: Float) = value * resources.displayMetrics.scaledDensity

    init {
        isClickable = true
        isFocusable = true
        ViewCompat.setAccessibilityDelegate(this, accessibilityHelper)
    }

    fun setSegments(
        data: List<Segment>,
        total: Int,
        palette: SettingsThemePalette,
        onTapped: ((segment: Segment) -> Unit)? = null,
    ) {
        segments = data
        totalCount = total
        this.palette = palette
        onSegmentTapped = onTapped

        barTextPaint.textSize = sp(11f)
        rowTextPaint.textSize = sp(14f)
        rowCountPaint.textSize = sp(13f)

        outlinePaint.color = palette.border
        outlinePaint.strokeWidth = dp(1f)
        dividerPaint.color = palette.divider
        dividerPaint.strokeWidth = dp(0.75f)

        accessibilityHelper.invalidateRoot()
        requestLayout()
        invalidate()
    }

    // -------------------------------------------------------------------------
    // Layout & Measurement
    // -------------------------------------------------------------------------

    private fun calculateRowHeight(): Float {
        val labelHeight = segments.maxOfOrNull { labelLayout(it.label).height } ?: 0
        return maxOf(dp(48f), labelHeight + rowCountPaint.fontSpacing + dp(20f))
    }

    private fun calculateDesiredHeight(): Int {
        val barHeight = dp(24f)
        val barMarginTop = dp(4f)
        val barMarginBottom = dp(12f)
        val rowHeight = calculateRowHeight()
        val rowsTotal = segments.size * rowHeight
        val bottomMargin = dp(4f)
        return (barMarginTop + barHeight + barMarginBottom + rowsTotal + bottomMargin).roundToInt()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        rowWidth = width.coerceAtLeast(1)
        val desiredHeight = calculateDesiredHeight()

        val hMode = MeasureSpec.getMode(heightMeasureSpec)
        val hSize = MeasureSpec.getSize(heightMeasureSpec)
        val measuredHeight = resolveSize(desiredHeight, heightMeasureSpec)
        setMeasuredDimension(width, measuredHeight)
    }

    // -------------------------------------------------------------------------
    // Drawing
    // -------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        segmentBounds.clear()
        visualBarBounds.clear()

        val currentPalette = palette
        val surfaceVariantColor = currentPalette?.surfaceVariant ?: 0x14888888
        val onSurfaceColor = currentPalette?.onSurface ?: 0xFF000000.toInt()
        val onSurfaceVariantColor = currentPalette?.onSurfaceVariant ?: 0xFF757575.toInt()
        val borderColor = currentPalette?.border ?: 0x1F888888
        val dividerColor = currentPalette?.divider ?: 0x14888888

        outlinePaint.color = borderColor
        dividerPaint.color = dividerColor

        val barHeight = dp(24f)
        val barTop = dp(4f)
        val barBottom = barTop + barHeight
        val cornerRadius = dp(6f)
        val availableWidth = (width - paddingLeft - paddingRight).toFloat()
        val barLeft = paddingLeft.toFloat()
        val barRight = barLeft + availableWidth

        // 1. Proportional Visual Bar at Top
        if (totalCount <= 0 || segments.none { it.count > 0 }) {
            // Draw empty / neutral track
            rect.set(barLeft, barTop, barRight, barBottom)
            barPaint.color = surfaceVariantColor
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, barPaint)
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, outlinePaint)
        } else {
            var currentX = barLeft
            val nonZeroSegments = segments.filter { it.count > 0 }

            nonZeroSegments.forEachIndexed { index, seg ->
                val fraction = seg.count.toFloat() / totalCount.toFloat()
                val segWidth = if (index == nonZeroSegments.lastIndex) {
                    barRight - currentX
                } else {
                    availableWidth * fraction
                }

                val segRect = RectF(currentX, barTop, currentX + segWidth, barBottom)
                visualBarBounds.add(Pair(seg, RectF(segRect)))

                barPaint.color = seg.color

                if (nonZeroSegments.size == 1) {
                    canvas.drawRoundRect(segRect, cornerRadius, cornerRadius, barPaint)
                } else {
                    canvas.save()
                    canvas.clipRect(segRect)
                    rect.set(barLeft, barTop, barRight, barBottom)
                    canvas.drawRoundRect(rect, cornerRadius, cornerRadius, barPaint)
                    canvas.restore()
                }

                // Label & count inside segment if wide enough
                if (segWidth >= barTextPaint.measureText("${seg.count}") + dp(12f)) {
                    barTextPaint.color = seg.textColor
                    barTextPaint.isFakeBoldText = true
                    val labelText = "${seg.count}"
                    val textY = barTop + barHeight / 2f - (barTextPaint.descent() + barTextPaint.ascent()) / 2f
                    val centerX = currentX + segWidth / 2f
                    canvas.drawText(labelText, centerX, textY, barTextPaint)
                }

                currentX += segWidth
            }

            // Outer border
            rect.set(barLeft, barTop, barRight, barBottom)
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, outlinePaint)
        }

        // 2. Independent Labeled >= 48dp Clickable Rows Below Bar (Including Zero States)
        val rowHeight = calculateRowHeight()
        val rowsTop = barBottom + dp(12f)
        val dotRadius = dp(6f)
        val dotMarginStart = dp(12f)
        val labelMarginStart = dp(32f)
        val endMargin = dp(12f)

        segments.forEachIndexed { index, seg ->
            val rowTop = rowsTop + index * rowHeight
            val rowBottom = rowTop + rowHeight
            val rowCenterY = rowTop + rowHeight / 2f

            val rowRect = RectF(0f, rowTop, width.toFloat(), rowBottom)
            segmentBounds.add(Pair(seg, RectF(rowRect)))

            // Color dot
            dotPaint.color = seg.color
            canvas.drawCircle(paddingLeft + dotMarginStart, rowCenterY, dotRadius, dotPaint)

            // Label text
            rowTextPaint.color = onSurfaceColor
            canvas.save()
            canvas.translate(paddingLeft + labelMarginStart, rowTop + dp(6f))
            labelLayout(seg.label).draw(canvas)
            canvas.restore()

            // Count and percentage text
            rowCountPaint.color = onSurfaceVariantColor
            rowCountPaint.isFakeBoldText = true
            val countTextY = rowBottom - dp(6f) - rowCountPaint.descent()
            rowCountPaint.textAlign = Paint.Align.LEFT
            canvas.drawText("${seg.count}", paddingLeft + labelMarginStart, countTextY, rowCountPaint)

            // Divider line below row (except for last row)
            if (index < segments.lastIndex) {
                canvas.drawLine(
                    paddingLeft + labelMarginStart,
                    rowBottom,
                    width - paddingRight - endMargin,
                    rowBottom,
                    dividerPaint
                )
            }
        }
    }

    // -------------------------------------------------------------------------
    // Touch & Gestures
    // -------------------------------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || segments.isEmpty()) return super.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                isTouchCancelled = false
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = abs(event.x - downX)
                val dy = abs(event.y - downY)
                if (dx > touchSlop || dy > touchSlop) {
                    isTouchCancelled = true
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                isTouchCancelled = true
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!isTouchCancelled) {
                    val dx = abs(event.x - downX)
                    val dy = abs(event.y - downY)
                    if (dx <= touchSlop && dy <= touchSlop) {
                        // First check row bounds (covers full row >= 48dp)
                        val touchY = event.y
                        val touchX = event.x

                        val matchedRow = segmentBounds.firstOrNull { (_, bounds) ->
                            touchY >= bounds.top && touchY <= bounds.bottom
                        }

                        if (matchedRow != null) {
                            onSegmentTapped?.invoke(matchedRow.first)
                            performClick()
                            return true
                        }

                        // Check visual bar touch at the top
                        val matchedBar = visualBarBounds.firstOrNull { (_, bounds) ->
                            touchX >= bounds.left && touchX <= bounds.right && touchY <= bounds.bottom + dp(8f)
                        }
                        if (matchedBar != null) {
                            onSegmentTapped?.invoke(matchedBar.first)
                            performClick()
                            return true
                        }
                    }
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    // -------------------------------------------------------------------------
    // Keyboard Focus
    // -------------------------------------------------------------------------

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_SPACE -> {
                if (segments.isNotEmpty()) {
                    onSegmentTapped?.invoke(segments[0])
                    performClick()
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    // -------------------------------------------------------------------------
    // Accessibility (ExploreByTouchHelper)
    // -------------------------------------------------------------------------

    override fun dispatchHoverEvent(event: MotionEvent): Boolean {
        return accessibilityHelper.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        return accessibilityHelper.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        accessibilityHelper.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
    }

    private inner class CohortAccessibilityHelper(host: View) : ExploreByTouchHelper(host) {
        override fun getVirtualViewAt(x: Float, y: Float): Int {
            if (segments.isEmpty() || width <= 0) return INVALID_ID
            val index = segmentBounds.indexOfFirst { (_, bounds) ->
                y >= bounds.top && y <= bounds.bottom
            }
            return if (index != -1) index else INVALID_ID
        }

        override fun getVisibleVirtualViews(virtualViewIds: MutableList<Int>) {
            for (i in segments.indices) {
                virtualViewIds.add(i)
            }
        }

        override fun onPopulateNodeForVirtualView(virtualViewId: Int, node: AccessibilityNodeInfoCompat) {
            if (virtualViewId !in segments.indices) return
            val seg = segments[virtualViewId]
            val bounds = segmentBounds.getOrNull(virtualViewId)?.second ?: RectF(0f, 0f, width.toFloat(), dp(48f))
            val rectInt = Rect(
                bounds.left.roundToInt(),
                bounds.top.roundToInt(),
                bounds.right.roundToInt(),
                bounds.bottom.roundToInt()
            )
            node.setBoundsInParent(rectInt)

            val pctDesc = if (totalCount > 0) {
                val pct = (seg.count * 100f / totalCount).roundToInt()
                ", $pct%"
            } else ""
            node.contentDescription = "${seg.label}: ${seg.count}$pctDesc"
            node.className = "android.widget.Button"
            node.isClickable = true
            node.isFocusable = true
            node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
        }

        override fun onPerformActionForVirtualView(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
            if (action == AccessibilityNodeInfoCompat.ACTION_CLICK) {
                if (virtualViewId in segments.indices) {
                    onSegmentTapped?.invoke(segments[virtualViewId])
                    performClick()
                    return true
                }
            }
            return false
        }
    }
}
