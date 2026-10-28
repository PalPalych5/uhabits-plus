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
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityEvent
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import org.isoron.uhabits.activities.settings.SettingsThemePalette
import org.isoron.uhabits.activities.statistics.ChartColumnItem
import org.isoron.uhabits.activities.statistics.UnifiedStatisticsStateUtils
import kotlin.math.abs
import kotlin.math.roundToInt

/** Pure representation of column touch target bounds for unit test and layout geometry calculations. */
data class ColumnBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
    fun centerX(): Float = (left + right) / 2f
    fun toRectF(): RectF = RectF(left, top, right, bottom)
}

/**
 * Interactive discrete column bar chart for daily task completions (T1), daily focus durations (F1),
 * or habit cycle completions across periods.
 * Features:
 * - Numeric Y scale with subtle horizontal grid lines and baseline tick labels.
 * - Dynamic height adapting to 200% font scale without clipping value or date text.
 * - Enforces minimum readable column pitch (>= 48dp) and supports horizontal scrolling via minimumWidth.
 * - Exact raw Long values (including focus duration in ms) formatted via item.formattedValue / valueFormatter.
 * - Full accessible TalkBack integration using ExploreByTouchHelper virtual nodes (each bucket >= 48dp).
 * - Exact 1:1 individual bucket touch targets and selection without artificial aggregation or grouping.
 * - Segmented column rendering for multi-outcome cohorts (satisfied / not satisfied / skip).
 * - Safe gesture handling compatible with vertical and horizontal scroll containers and ACTION_CANCEL.
 * - Semantic theme palette compliance (Light, Dark, AMOLED).
 */
class DiscreteColumnBarChart @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var items: List<ChartColumnItem> = emptyList()
    private var onColumnTapped: ((index: Int, item: ChartColumnItem) -> Unit)? = null

    var selectedIndex: Int = -1
        private set

    var unitLabel: String? = null
        set(value) {
            field = value
            invalidate()
        }

    var valueFormatter: ((Long) -> String)? = null
        set(value) {
            field = value
            invalidate()
        }

    var showYAxis: Boolean = true
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    private var primaryColor: Int = 0xFF2196F3.toInt()
    private var onSurfaceColor: Int = 0xFF000000.toInt()
    private var onSurfaceVariantColor: Int = 0xFF757575.toInt()
    private var surfaceVariantColor: Int = 0xFFE0E0E0.toInt()
    private var outlineColor: Int = 0xFFBDBDBD.toInt()
    private var dividerColor: Int = 0x14000000

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val baselinePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gridLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val gridTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.RIGHT
        fontFeatureSettings = "tnum"
    }
    private val valueTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val todayDotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val columnPath = android.graphics.Path()

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX: Float = 0f
    private var downY: Float = 0f
    private var isTouchCancelled: Boolean = false

    private val accessibilityHelper = ChartAccessibilityHelper(this)

    private fun dp(value: Float) = value * resources.displayMetrics.density
    private fun sp(value: Float) = value * resources.displayMetrics.scaledDensity

    init {
        isClickable = true
        isFocusable = true
        ViewCompat.setAccessibilityDelegate(this, accessibilityHelper)
    }

    /**
     * Preserves existing (data, palette, onTapped) trailing lambda API while allowing
     * optional unitLabel and valueFormatter defaults.
     */
    @JvmOverloads
    fun setData(
        data: List<ChartColumnItem>,
        palette: SettingsThemePalette,
        unitLabel: String? = null,
        valueFormatter: ((Long) -> String)? = null,
        onTapped: ((index: Int, item: ChartColumnItem) -> Unit)? = null,
    ) {
        items = data
        onColumnTapped = onTapped
        this.unitLabel = unitLabel
        this.valueFormatter = valueFormatter

        primaryColor = palette.accent
        onSurfaceColor = palette.onSurface
        onSurfaceVariantColor = palette.onSurfaceVariant
        surfaceVariantColor = palette.surfaceVariant
        outlineColor = palette.border
        dividerColor = palette.divider

        selectedIndex = data.indexOfFirst { it.isSelected }

        valueTextPaint.textSize = sp(11f)
        labelTextPaint.textSize = sp(10f)
        gridTextPaint.textSize = sp(9f)
        gridLinePaint.strokeWidth = dp(0.75f)

        // Min readable width allowing horizontal scroll in coordinator wrapper
        val minPitch = calculateMinColumnPitch()
        val yAxisW = if (showYAxis) calculateYAxisWidth() else 0f
        val calculatedWidth = (items.size * minPitch + yAxisW + paddingLeft + paddingRight).roundToInt()
        minimumWidth = calculatedWidth

        accessibilityHelper.invalidateRoot()
        requestLayout()
        invalidate()
    }

    fun setSelectedIndex(index: Int) {
        if (selectedIndex != index) {
            val oldIndex = selectedIndex
            selectedIndex = index
            if (oldIndex in items.indices) accessibilityHelper.invalidateVirtualView(oldIndex)
            if (index in items.indices) {
                accessibilityHelper.invalidateVirtualView(index)
                accessibilityHelper.sendEventForVirtualView(index, AccessibilityEvent.TYPE_VIEW_SELECTED)
                accessibilityHelper.syncKeyboardFocus(index)
            }
            invalidate()
            if (index in items.indices) {
                ensureColumnVisible(index)
            }
        }
    }

    fun ensureColumnVisible(index: Int) {
        if (index !in items.indices || width <= 0) return
        val bounds = getColumnBounds(index)
        val left = if (index == 0) 0 else bounds.left.roundToInt()
        val right = bounds.right.roundToInt()
        val h = if (height > 0) height else 1
        requestRectangleOnScreen(Rect(left, 0, right, h), false)
    }

    // -------------------------------------------------------------------------
    // Safe Test & Inspection Hooks
    // -------------------------------------------------------------------------

    val columnBounds: List<RectF>
        get() {
            if (items.isEmpty() || width <= 0 || height <= 0) return emptyList()
            val yAxisW = if (showYAxis) calculateYAxisWidth() else 0f
            val chartLeft = paddingLeft + yAxisW
            val chartRight = (width - paddingRight).toFloat()
            return computePureColumnBounds(items.size, width.toFloat(), height.toFloat(), chartLeft, chartRight)
                .map { it.toRectF() }
        }

    fun getColumnBounds(index: Int): RectF {
        val bounds = columnBounds
        return if (index in bounds.indices) bounds[index] else RectF()
    }

    fun getColumnCenterX(index: Int): Float {
        val bounds = columnBounds
        return if (index in bounds.indices) bounds[index].centerX() else 0f
    }

    // -------------------------------------------------------------------------
    // Layout & Measurement
    // -------------------------------------------------------------------------

    private fun calculateMinColumnPitch(): Float {
        val maxLabelW = items.maxOfOrNull { labelTextPaint.measureText(it.label) } ?: 0f
        val maxValueW = items.maxOfOrNull { valueTextPaint.measureText(it.formattedValue) } ?: 0f
        return maxOf(dp(MIN_TOUCH_TARGET_DP), maxLabelW + dp(16f), maxValueW + dp(16f))
    }

    private fun calculateYAxisWidth(): Float {
        if (items.isEmpty()) return dp(28f)
        val maxValue = items.maxOfOrNull { it.value } ?: 0L
        val maxLabel = formatYTick(maxValue)
        val midLabel = formatYTick(maxValue / 2L)
        val zeroLabel = formatYTick(0L)
        val widest = maxOf(
            gridTextPaint.measureText(maxLabel),
            gridTextPaint.measureText(midLabel),
            gridTextPaint.measureText(zeroLabel)
        )
        return widest + dp(10f)
    }

    private fun calculateDesiredHeight(): Int {
        return computePureDesiredHeight(
            valueFontSpacing = valueTextPaint.fontSpacing,
            valueTextMarginDp = dp(6f),
            labelFontSpacing = labelTextPaint.fontSpacing,
            bottomAxisMarginDp = dp(14f),
            minPlotHeightDp = dp(68f),
            paddingTop = paddingTop,
            paddingBottom = paddingBottom,
        )
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredH = calculateDesiredHeight()
        val minPitch = calculateMinColumnPitch()
        val yAxisW = if (showYAxis) calculateYAxisWidth() else 0f
        val desiredW = computeDesiredWidth(
            itemCount = items.size,
            minPitch = minPitch,
            yAxisWidth = yAxisW,
            paddingLeft = paddingLeft,
            paddingRight = paddingRight,
            suggestedMinWidth = suggestedMinimumWidth
        )

        val wMode = MeasureSpec.getMode(widthMeasureSpec)
        val wSize = MeasureSpec.getSize(widthMeasureSpec)
        val measuredWidth = computeMeasuredDimension(wMode, wSize, desiredW)

        val hMode = MeasureSpec.getMode(heightMeasureSpec)
        val hSize = MeasureSpec.getSize(heightMeasureSpec)
        val measuredHeight = computeMeasuredDimension(hMode, hSize, desiredH)

        setMeasuredDimension(measuredWidth, measuredHeight)
    }

    // -------------------------------------------------------------------------
    // Drawing
    // -------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (items.isEmpty()) return

        val count = items.size
        val yAxisW = if (showYAxis) calculateYAxisWidth() else 0f
        val chartLeft = paddingLeft + yAxisW
        val chartRight = width - paddingRight.toFloat()
        val availableW = (chartRight - chartLeft).coerceAtLeast(0f)
        val columnWidth = if (count > 0) availableW / count else 0f
        val barWidth = (columnWidth * 0.45f).coerceIn(dp(6f), dp(22f))

        val topPadding = paddingTop + valueTextPaint.fontSpacing + dp(6f)
        val bottomAxisMargin = paddingBottom + labelTextPaint.fontSpacing + dp(14f)
        val bottomBaseline = height - bottomAxisMargin
        val maxBarHeight = (bottomBaseline - topPadding).coerceAtLeast(dp(20f))

        val maxValue = (items.maxOfOrNull { it.value } ?: 0L).coerceAtLeast(1L).toFloat()

        // 1. Numeric Y scale grid lines & labels
        gridLinePaint.color = dividerColor
        gridTextPaint.color = onSurfaceVariantColor

        // Baseline (0)
        baselinePaint.color = outlineColor
        baselinePaint.strokeWidth = dp(1f)
        canvas.drawLine(chartLeft, bottomBaseline, chartRight, bottomBaseline, baselinePaint)
        if (showYAxis) {
            val zeroY = bottomBaseline - (gridTextPaint.descent() + gridTextPaint.ascent()) / 2f
            canvas.drawText(formatYTick(0L), chartLeft - dp(4f), zeroY, gridTextPaint)
        }

        // Ticks for max and mid
        val rawMaxLong = items.maxOfOrNull { it.value } ?: 0L
        if (showYAxis && rawMaxLong > 0L) {
            // Top grid line
            val topY = bottomBaseline - maxBarHeight
            canvas.drawLine(chartLeft, topY, chartRight, topY, gridLinePaint)
            val topTextY = topY - (gridTextPaint.descent() + gridTextPaint.ascent()) / 2f
            canvas.drawText(formatYTick(rawMaxLong), chartLeft - dp(4f), topTextY, gridTextPaint)

            // Mid grid line if >= 2
            if (rawMaxLong >= 2L) {
                val midVal = rawMaxLong / 2L
                val midY = bottomBaseline - maxBarHeight * (midVal.toDouble() / rawMaxLong).toFloat()
                canvas.drawLine(chartLeft, midY, chartRight, midY, gridLinePaint)
                val midTextY = midY - (gridTextPaint.descent() + gridTextPaint.ascent()) / 2f
                canvas.drawText(formatYTick(midVal), chartLeft - dp(4f), midTextY, gridTextPaint)
            }
        }

        // 2. Columns, values & date axis labels
        items.forEachIndexed { index, item ->
            val centerX = chartLeft + columnWidth * (index + 0.5f)
            val isSelected = index == selectedIndex || item.isSelected

            // Axis date label
            labelTextPaint.color = if (isSelected) primaryColor else onSurfaceVariantColor
            labelTextPaint.isFakeBoldText = isSelected || item.isToday
            val labelY = height - paddingBottom - dp(6f)
            val labelHalfW = labelTextPaint.measureText(item.label) / 2f
            val clampedCenterX = if (width > 0) {
                centerX.coerceIn(labelHalfW + dp(2f), width - labelHalfW - dp(2f))
            } else centerX
            canvas.drawText(item.label, clampedCenterX, labelY, labelTextPaint)

            // Today indicator dot
            if (item.isToday) {
                todayDotPaint.color = primaryColor
                canvas.drawCircle(clampedCenterX, labelY + dp(3.5f), dp(1.75f), todayDotPaint)
            }

            // Bar drawing
            if (item.value > 0L) {
                val fraction = (item.value.toFloat() / maxValue).coerceIn(0f, 1f)
                val barHeight = (maxBarHeight * fraction).coerceAtLeast(dp(3f))
                val barTop = bottomBaseline - barHeight
                val cornerRadius = barWidth * 0.25f

                if (item.segments.isNotEmpty()) {
                    var currentBase = bottomBaseline
                    val saveCount = canvas.save()
                    rect.set(centerX - barWidth / 2f, barTop, centerX + barWidth / 2f, bottomBaseline)
                    columnPath.reset()
                    columnPath.addRoundRect(rect, cornerRadius, cornerRadius, android.graphics.Path.Direction.CW)
                    canvas.clipPath(columnPath)
                    item.segments.forEachIndexed { _, seg ->
                        val segFraction = (seg.value.toFloat() / item.value)
                        val segH = barHeight * segFraction
                        val segTop = (currentBase - segH).coerceAtLeast(barTop)
                        rect.set(centerX - barWidth / 2f, segTop, centerX + barWidth / 2f, currentBase)
                        barPaint.color = seg.color
                        canvas.drawRect(rect, barPaint)
                        currentBase = segTop
                    }
                    canvas.restoreToCount(saveCount)
                    if (isSelected) {
                        gridLinePaint.color = onSurfaceColor
                        gridLinePaint.strokeWidth = dp(2f)
                        rect.set(centerX - barWidth / 2f, barTop, centerX + barWidth / 2f, bottomBaseline)
                        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, gridLinePaint)
                    }
                } else {
                    rect.set(centerX - barWidth / 2f, barTop, centerX + barWidth / 2f, bottomBaseline)
                    barPaint.color = if (isSelected) onSurfaceColor else primaryColor
                    canvas.drawRoundRect(rect, cornerRadius, cornerRadius, barPaint)
                }

                // Value text above bar
                valueTextPaint.color = if (isSelected) primaryColor else onSurfaceColor
                valueTextPaint.isFakeBoldText = isSelected
                canvas.drawText(item.formattedValue, centerX, barTop - dp(4f), valueTextPaint)
            } else {
                // 0 value: subtle baseline tick mark
                rect.set(centerX - barWidth / 3f, bottomBaseline - dp(2f), centerX + barWidth / 3f, bottomBaseline)
                barPaint.color = if (isSelected) primaryColor else surfaceVariantColor
                canvas.drawRect(rect, barPaint)
                if (isSelected) {
                    valueTextPaint.color = primaryColor
                    valueTextPaint.isFakeBoldText = true
                    canvas.drawText(item.formattedValue, centerX, bottomBaseline - dp(4f), valueTextPaint)
                }
            }
        }
    }

    private fun formatYTick(valLong: Long): String {
        if (valueFormatter != null) {
            return valueFormatter!!.invoke(valLong)
        }
        if (unitLabel != null) {
            return "$valLong $unitLabel"
        }
        // Duration auto-formatting if raw ms duration is detected
        if (valLong >= 60_000L) {
            val isRussian = context.resources.configuration.locales[0].language == "ru"
            return UnifiedStatisticsStateUtils.formatDurationMs(valLong, isRussian)
        }
        return valLong.toString()
    }

    // -------------------------------------------------------------------------
    // Touch & Gestures
    // -------------------------------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || items.isEmpty()) return super.onTouchEvent(event)

        val yAxisW = if (showYAxis) calculateYAxisWidth() else 0f
        val chartLeft = paddingLeft + yAxisW
        val chartRight = (width - paddingRight).toFloat()

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (event.x < chartLeft || event.x > chartRight || event.y < 0f || event.y > height.toFloat()) {
                    return false
                }
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
                    if (dx <= touchSlop && dy <= touchSlop && event.x in chartLeft..chartRight && event.y in 0f..height.toFloat()) {
                        val availableW = (chartRight - chartLeft).coerceAtLeast(0f)
                        val columnWidth = if (items.isNotEmpty()) availableW / items.size else 0f
                        val tappedIndex = if (columnWidth > 0f) {
                            ((event.x - chartLeft) / columnWidth).toInt().coerceIn(0, items.size - 1)
                        } else 0
                        selectItem(tappedIndex)
                        return true
                    }
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    fun selectItem(index: Int) {
        if (index in items.indices) {
            setSelectedIndex(index)
            ensureColumnVisible(index)
            val item = items[index]
            onColumnTapped?.invoke(index, item)
            performClick()
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    // -------------------------------------------------------------------------
    // Keyboard Focus
    // -------------------------------------------------------------------------

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val nextIndex = computeKeyNavigationTarget(keyCode, selectedIndex, items.size)
        if (nextIndex != null) {
            setSelectedIndex(nextIndex)
            ensureColumnVisible(nextIndex)
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_SPACE
        ) {
            if (selectedIndex in items.indices) {
                selectItem(selectedIndex)
                return true
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

    private inner class ChartAccessibilityHelper(host: View) : ExploreByTouchHelper(host) {
        fun syncKeyboardFocus(index: Int) {
            if (keyboardFocusedVirtualViewId != index) {
                requestKeyboardFocusForVirtualView(index)
            }
        }

        override fun getVirtualViewAt(x: Float, y: Float): Int {
            if (items.isEmpty() || width <= 0 || height <= 0) return INVALID_ID
            return findColumnIndexAt(columnBounds, x, y, height.toFloat())
        }

        override fun getVisibleVirtualViews(virtualViewIds: MutableList<Int>) {
            for (i in items.indices) {
                virtualViewIds.add(i)
            }
        }

        override fun onPopulateNodeForVirtualView(virtualViewId: Int, node: AccessibilityNodeInfoCompat) {
            if (virtualViewId !in items.indices) return
            val item = items[virtualViewId]
            val bounds = getColumnBounds(virtualViewId)
            val rectInt = Rect(
                bounds.left.roundToInt(),
                0,
                bounds.right.roundToInt(),
                height
            )
            node.setBoundsInParent(rectInt)
            node.contentDescription = item.contentDescription
            node.className = "android.widget.Button"
            node.isClickable = true
            node.isEnabled = isEnabled
            node.isFocusable = true
            val isSelected = (virtualViewId == selectedIndex || item.isSelected)
            node.isSelected = isSelected
            node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
            if (!isSelected) {
                node.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SELECT)
            } else {
                node.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLEAR_SELECTION)
            }
            node.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SHOW_ON_SCREEN)
        }

        override fun onPerformActionForVirtualView(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
            if (!isEnabled || virtualViewId !in items.indices) return false
            when (action) {
                AccessibilityNodeInfoCompat.ACTION_CLICK -> {
                    selectItem(virtualViewId)
                    return true
                }
                AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SELECT.id,
                AccessibilityNodeInfoCompat.ACTION_SELECT -> {
                    if (selectedIndex != virtualViewId) {
                        setSelectedIndex(virtualViewId)
                        ensureColumnVisible(virtualViewId)
                    }
                    return true
                }
                AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLEAR_SELECTION.id,
                AccessibilityNodeInfoCompat.ACTION_CLEAR_SELECTION -> {
                    if (selectedIndex == virtualViewId) {
                        setSelectedIndex(-1)
                    }
                    return true
                }
                AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SHOW_ON_SCREEN.id,
                android.R.id.accessibilityActionShowOnScreen -> {
                    ensureColumnVisible(virtualViewId)
                    return true
                }
            }
            return false
        }

        override fun onPopulateEventForVirtualView(virtualViewId: Int, event: AccessibilityEvent) {
            super.onPopulateEventForVirtualView(virtualViewId, event)
            if (event.eventType == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED) {
                ensureColumnVisible(virtualViewId)
            }
        }

        override fun onVirtualViewKeyboardFocusChanged(virtualViewId: Int, hasFocus: Boolean) {
            super.onVirtualViewKeyboardFocusChanged(virtualViewId, hasFocus)
            if (hasFocus && virtualViewId in items.indices) {
                setSelectedIndex(virtualViewId)
                ensureColumnVisible(virtualViewId)
            }
        }
    }

    companion object {
        const val MIN_TOUCH_TARGET_DP = 48f

        fun computePureDesiredHeight(
            valueFontSpacing: Float,
            valueTextMarginDp: Float,
            labelFontSpacing: Float,
            bottomAxisMarginDp: Float,
            minPlotHeightDp: Float,
            paddingTop: Int,
            paddingBottom: Int,
        ): Int {
            val valueTextMargin = valueFontSpacing + valueTextMarginDp
            val bottomAxisMargin = labelFontSpacing + bottomAxisMarginDp
            return (valueTextMargin + minPlotHeightDp + bottomAxisMargin + paddingTop + paddingBottom).roundToInt()
        }

        fun computeMeasuredDimension(mode: Int, specSize: Int, desiredSize: Int): Int {
            return when (mode) {
                MeasureSpec.EXACTLY -> specSize
                MeasureSpec.AT_MOST -> minOf(specSize, desiredSize)
                else -> desiredSize
            }
        }

        fun computeKeyNavigationTarget(keyCode: Int, currentIndex: Int, itemCount: Int): Int? {
            if (itemCount <= 0) return null
            return when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (currentIndex > 0) {
                        currentIndex - 1
                    } else if (currentIndex == -1) {
                        0
                    } else {
                        null
                    }
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (currentIndex in 0 until itemCount - 1) {
                        currentIndex + 1
                    } else if (currentIndex == -1) {
                        0
                    } else {
                        null
                    }
                }
                else -> null
            }
        }

        fun findPureColumnIndexAt(
            bounds: List<ColumnBounds>,
            x: Float,
            y: Float,
            chartHeight: Float
        ): Int {
            if (y < 0f || y > chartHeight || chartHeight <= 0f) return -1
            return bounds.indexOfFirst { x >= it.left && x <= it.right && y >= it.top && y <= it.bottom }
        }

        fun findColumnIndexAt(
            bounds: List<RectF>,
            x: Float,
            y: Float,
            chartHeight: Float
        ): Int {
            if (y < 0f || y > chartHeight || chartHeight <= 0f) return -1
            return bounds.indexOfFirst { x >= it.left && x <= it.right && y >= it.top && y <= it.bottom }
        }

        fun computeDesiredWidth(
            itemCount: Int,
            minPitch: Float,
            yAxisWidth: Float,
            paddingLeft: Int,
            paddingRight: Int,
            suggestedMinWidth: Int = 0
        ): Int {
            val totalColsW = (itemCount * minPitch).roundToInt()
            val total = totalColsW + yAxisWidth.roundToInt() + paddingLeft + paddingRight
            return maxOf(suggestedMinWidth, total)
        }

        fun computePureColumnBounds(
            itemCount: Int,
            width: Float,
            height: Float,
            chartLeft: Float,
            chartRight: Float,
        ): List<ColumnBounds> {
            if (itemCount <= 0 || width <= 0f || height <= 0f) return emptyList()
            val availableW = (chartRight - chartLeft).coerceAtLeast(0f)
            val colW = availableW / itemCount
            return (0 until itemCount).map { i ->
                ColumnBounds(
                    left = chartLeft + i * colW,
                    top = 0f,
                    right = chartLeft + (i + 1) * colW,
                    bottom = height
                )
            }
        }

        fun computeColumnBounds(
            itemCount: Int,
            width: Float,
            height: Float,
            chartLeft: Float,
            chartRight: Float,
        ): List<RectF> {
            return computePureColumnBounds(itemCount, width, height, chartLeft, chartRight).map { it.toRectF() }
        }
    }
}
