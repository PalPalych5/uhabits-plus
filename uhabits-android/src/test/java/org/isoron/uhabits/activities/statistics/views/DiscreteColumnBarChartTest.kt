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

import android.view.KeyEvent
import android.view.View.MeasureSpec
import org.isoron.uhabits.activities.statistics.ChartColumnItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for DiscreteColumnBarChart exact 1:1 individual bucket touch target geometry,
 * non-overlapping >= 48dp hit bounds, dense vs sparse layout width computation, and accessibility invariants.
 */
class DiscreteColumnBarChartTest {

    private fun createDummyItems(count: Int, todayIndex: Int = -1): List<ChartColumnItem> {
        return (0 until count).map { i ->
            val day = i + 1
            ChartColumnItem(
                key = "day_$day",
                value = (day * 2).toLong(),
                formattedValue = "${day * 2}",
                label = "$day",
                isToday = (i == todayIndex),
                contentDescription = "Day $day: ${day * 2} tasks"
            )
        }
    }

    @Test
    fun exactBucketHitTargets_sparseMode_eachBucketHasSeparateNonOverlappingTargetWithAtLeast48dp() {
        val items = createDummyItems(7)
        val availableW = 420f
        val chartLeft = 30f
        val chartRight = 450f
        val height = 120f

        val bounds = DiscreteColumnBarChart.computePureColumnBounds(
            itemCount = items.size,
            width = 480f,
            height = height,
            chartLeft = chartLeft,
            chartRight = chartRight
        )

        assertEquals("7 columns must produce exactly 7 1:1 hit target bounds", 7, bounds.size)
        bounds.forEachIndexed { i, rect ->
            assertTrue(
                "Column $i width (${rect.width}) must be >= 48px",
                rect.width >= 48f - 0.001f
            )
            assertEquals("Column $i height must match chart height", height, rect.height, 0.001f)
        }

        // Verify strictly non-overlapping contiguous bounds
        assertEquals("First column left must equal chartLeft", chartLeft, bounds.first().left, 0.001f)
        assertEquals("Last column right must equal chartRight", chartRight, bounds.last().right, 0.001f)

        for (i in 0 until bounds.size - 1) {
            assertEquals(
                "Adjacent columns must meet seamlessly with zero gap",
                bounds[i].right,
                bounds[i + 1].left,
                0.001f
            )
            assertTrue(
                "Adjacent columns must not overlap",
                bounds[i + 1].left >= bounds[i].right - 0.001f
            )
        }
    }

    @Test
    fun exactBucketHitTargets_denseModeMonth30Days_retainsExact1To1BucketsWithAtLeast48dpTarget() {
        val items = createDummyItems(30, todayIndex = 14)
        val minPitch = 48f
        val availableW = items.size * minPitch // 1440px
        val chartLeft = 40f
        val chartRight = chartLeft + availableW
        val height = 140f

        val bounds = DiscreteColumnBarChart.computePureColumnBounds(
            itemCount = items.size,
            width = chartRight + 20f,
            height = height,
            chartLeft = chartLeft,
            chartRight = chartRight
        )

        assertEquals("All 30 buckets must retain their own exact 1:1 hit bounds without artificial grouping", 30, bounds.size)

        bounds.forEachIndexed { i, rect ->
            assertTrue(
                "Bucket $i touch target width (${rect.width}) must be >= 48px",
                rect.width >= 48f - 0.001f
            )
            assertEquals("Bucket $i height must match chart height", height, rect.height, 0.001f)
        }

        // Verify strictly non-overlapping contiguous bounds covering full chart area
        assertEquals("First column left must equal chartLeft", chartLeft, bounds.first().left, 0.001f)
        assertEquals("Last column right must equal chartRight", chartRight, bounds.last().right, 0.001f)

        for (i in 0 until bounds.size - 1) {
            assertEquals(
                "Column $i and ${i + 1} must meet seamlessly",
                bounds[i].right,
                bounds[i + 1].left,
                0.001f
            )
            assertTrue(
                "Column $i and ${i + 1} must have strictly non-overlapping bounds",
                bounds[i + 1].left >= bounds[i].right - 0.001f
            )
        }
    }

    @Test
    fun exactBucketHitTargets_month31Days_preservesIndividualSelectionForEveryDay() {
        val items = createDummyItems(31)
        val minPitch = 48f
        val chartLeft = 24f
        val chartRight = chartLeft + items.size * minPitch // 31 * 48 = 1488px
        val height = 150f

        val bounds = DiscreteColumnBarChart.computePureColumnBounds(
            itemCount = items.size,
            width = chartRight + 24f,
            height = height,
            chartLeft = chartLeft,
            chartRight = chartRight
        )

        assertEquals("Every single day of 31-day month must have its own hit target", 31, bounds.size)
        bounds.forEachIndexed { i, rect ->
            assertTrue("Day ${i + 1} width must be >= 48px", rect.width >= 48f - 0.001f)
        }

        for (i in 0 until bounds.size - 1) {
            assertEquals(
                "Day ${i + 1} and ${i + 2} must not gap",
                bounds[i].right,
                bounds[i + 1].left,
                0.001f
            )
        }
    }

    @Test
    fun computeDesiredWidth_denseMode_enforcesMinimum48dpPitchToSupportHorizontalScrolling() {
        val itemCount = 30
        val minPitch = 48f
        val yAxisWidth = 40f
        val paddingLeft = 16
        val paddingRight = 16

        val desiredWidth = DiscreteColumnBarChart.computeDesiredWidth(
            itemCount = itemCount,
            minPitch = minPitch,
            yAxisWidth = yAxisWidth,
            paddingLeft = paddingLeft,
            paddingRight = paddingRight
        )

        val expectedMinWidth = (30 * 48) + 40 + 16 + 16 // 1512
        assertEquals(expectedMinWidth, desiredWidth)
        assertTrue(
            "Desired width must be large enough to trigger horizontal scrolling in viewport",
            desiredWidth >= 1440
        )
    }

    @Test
    fun computeDesiredWidth_sparseMode_allowsViewportExpansion() {
        val itemCount = 7
        val minPitch = 48f
        val yAxisWidth = 32f
        val paddingLeft = 12
        val paddingRight = 12

        val desiredWidth = DiscreteColumnBarChart.computeDesiredWidth(
            itemCount = itemCount,
            minPitch = minPitch,
            yAxisWidth = yAxisWidth,
            paddingLeft = paddingLeft,
            paddingRight = paddingRight
        )

        val expectedMinWidth = (7 * 48) + 32 + 12 + 12 // 336 + 56 = 392
        assertEquals(expectedMinWidth, desiredWidth)
    }

    @Test
    fun emptyItems_returnsEmptyBounds() {
        val bounds = DiscreteColumnBarChart.computePureColumnBounds(
            itemCount = 0,
            width = 300f,
            height = 100f,
            chartLeft = 20f,
            chartRight = 280f
        )
        assertTrue("Empty items must produce empty bounds", bounds.isEmpty())
    }

    @Test
    fun singleItem_spansAvailableWidthWithAtLeast48dp() {
        val bounds = DiscreteColumnBarChart.computePureColumnBounds(
            itemCount = 1,
            width = 200f,
            height = 100f,
            chartLeft = 20f,
            chartRight = 180f
        )
        assertEquals(1, bounds.size)
        val single = bounds.single()
        assertEquals(20f, single.left, 0.001f)
        assertEquals(180f, single.right, 0.001f)
        assertEquals(160f, single.width, 0.001f)
        assertTrue("Single item width must be >= 48px", single.width >= 48f)
    }

    // -------------------------------------------------------------------------
    // MeasureSpec EXACTLY / AT_MOST / UNSPECIFIED tests
    // -------------------------------------------------------------------------

    @Test
    fun computeMeasuredDimension_exactlyMode_honorsSpecSizeEvenWhenDesiredDiffers() {
        // EXACTLY: parent dictates exact size (e.g. fixed dp, match_parent, or HorizontalScrollView.isFillViewport stretch)
        val specSize = 600
        val desiredLarge = 1400
        val desiredSmall = 300

        assertEquals(
            "EXACTLY mode must return spec size when desired is larger",
            specSize,
            DiscreteColumnBarChart.computeMeasuredDimension(MeasureSpec.EXACTLY, specSize, desiredLarge)
        )
        assertEquals(
            "EXACTLY mode must return spec size when desired is smaller",
            specSize,
            DiscreteColumnBarChart.computeMeasuredDimension(MeasureSpec.EXACTLY, specSize, desiredSmall)
        )
    }

    @Test
    fun computeMeasuredDimension_atMostMode_constrainsToSpecSizeWithoutExceeding() {
        // AT_MOST: parent gives maximum bound (e.g. wrap_content inside bounded container)
        val specSize = 800
        val desiredLarge = 1200
        val desiredSmall = 400

        assertEquals(
            "AT_MOST mode must clamp to specSize when desired exceeds it",
            specSize,
            DiscreteColumnBarChart.computeMeasuredDimension(MeasureSpec.AT_MOST, specSize, desiredLarge)
        )
        assertEquals(
            "AT_MOST mode must return desiredSize when within specSize",
            desiredSmall,
            DiscreteColumnBarChart.computeMeasuredDimension(MeasureSpec.AT_MOST, specSize, desiredSmall)
        )
    }

    @Test
    fun computeMeasuredDimension_unspecifiedMode_returnsDesiredSizeForHorizontalScrolling() {
        // UNSPECIFIED: HorizontalScrollView measures child with UNSPECIFIED so it can expand and scroll
        val desiredScrollWidth = 1512

        assertEquals(
            "UNSPECIFIED mode must return full desiredSize to enable horizontal scrolling",
            desiredScrollWidth,
            DiscreteColumnBarChart.computeMeasuredDimension(MeasureSpec.UNSPECIFIED, 0, desiredScrollWidth)
        )
    }

    // -------------------------------------------------------------------------
    // Hover Y Bounds Checking tests
    // -------------------------------------------------------------------------

    @Test
    fun findPureColumnIndexAt_boundsChecksHoverYCorrectly() {
        val chartHeight = 120f
        val bounds = listOf(
            ColumnBounds(left = 0f, top = 0f, right = 50f, bottom = chartHeight),
            ColumnBounds(left = 50f, top = 0f, right = 100f, bottom = chartHeight),
            ColumnBounds(left = 100f, top = 0f, right = 150f, bottom = chartHeight),
        )

        // Valid inside bounds
        assertEquals(0, DiscreteColumnBarChart.findPureColumnIndexAt(bounds, 25f, 60f, chartHeight))
        assertEquals(1, DiscreteColumnBarChart.findPureColumnIndexAt(bounds, 75f, 60f, chartHeight))
        assertEquals(2, DiscreteColumnBarChart.findPureColumnIndexAt(bounds, 125f, 60f, chartHeight))

        // Hover Y above chart bounds (negative y)
        assertEquals(
            "Hover Y above chart (negative) must return -1",
            -1,
            DiscreteColumnBarChart.findPureColumnIndexAt(bounds, 25f, -1f, chartHeight)
        )
        assertEquals(
            "Hover Y far above chart must return -1",
            -1,
            DiscreteColumnBarChart.findPureColumnIndexAt(bounds, 75f, -50f, chartHeight)
        )

        // Hover Y below chart bounds (y > height)
        assertEquals(
            "Hover Y below chart height must return -1",
            -1,
            DiscreteColumnBarChart.findPureColumnIndexAt(bounds, 25f, 120.5f, chartHeight)
        )
        assertEquals(
            "Hover Y far below chart must return -1",
            -1,
            DiscreteColumnBarChart.findPureColumnIndexAt(bounds, 75f, 200f, chartHeight)
        )

        // Boundary edge checks
        assertEquals(
            "Hover Y at top edge (0f) must be accepted",
            0,
            DiscreteColumnBarChart.findPureColumnIndexAt(bounds, 25f, 0f, chartHeight)
        )
        assertEquals(
            "Hover Y at bottom edge (chartHeight) must be accepted",
            1,
            DiscreteColumnBarChart.findPureColumnIndexAt(bounds, 75f, chartHeight, chartHeight)
        )

        // Hover X outside columns
        assertEquals(
            "Hover X to the left must return -1",
            -1,
            DiscreteColumnBarChart.findPureColumnIndexAt(bounds, -5f, 60f, chartHeight)
        )
        assertEquals(
            "Hover X to the right must return -1",
            -1,
            DiscreteColumnBarChart.findPureColumnIndexAt(bounds, 160f, 60f, chartHeight)
        )
    }

    // -------------------------------------------------------------------------
    // Keyboard navigation tests (Arrow keys move selection, Enter activates)
    // -------------------------------------------------------------------------

    @Test
    fun computeKeyNavigationTarget_arrowKeysMoveSelectionWithinBounds() {
        val count = 5

        // Initial navigation when nothing is selected (-1)
        assertEquals(
            "DPAD_RIGHT on unselected chart selects index 0",
            0,
            DiscreteColumnBarChart.computeKeyNavigationTarget(KeyEvent.KEYCODE_DPAD_RIGHT, -1, count)
        )
        assertEquals(
            "DPAD_LEFT on unselected chart selects index 0",
            0,
            DiscreteColumnBarChart.computeKeyNavigationTarget(KeyEvent.KEYCODE_DPAD_LEFT, -1, count)
        )

        // Moving right
        assertEquals(1, DiscreteColumnBarChart.computeKeyNavigationTarget(KeyEvent.KEYCODE_DPAD_RIGHT, 0, count))
        assertEquals(2, DiscreteColumnBarChart.computeKeyNavigationTarget(KeyEvent.KEYCODE_DPAD_RIGHT, 1, count))
        assertEquals(4, DiscreteColumnBarChart.computeKeyNavigationTarget(KeyEvent.KEYCODE_DPAD_RIGHT, 3, count))
        assertNull(
            "DPAD_RIGHT at last item returns null to allow focus escape",
            DiscreteColumnBarChart.computeKeyNavigationTarget(KeyEvent.KEYCODE_DPAD_RIGHT, 4, count)
        )

        // Moving left
        assertEquals(3, DiscreteColumnBarChart.computeKeyNavigationTarget(KeyEvent.KEYCODE_DPAD_LEFT, 4, count))
        assertEquals(0, DiscreteColumnBarChart.computeKeyNavigationTarget(KeyEvent.KEYCODE_DPAD_LEFT, 1, count))
        assertNull(
            "DPAD_LEFT at index 0 returns null to allow focus escape",
            DiscreteColumnBarChart.computeKeyNavigationTarget(KeyEvent.KEYCODE_DPAD_LEFT, 0, count)
        )

        // Enter / Space are not navigation keys (they activate selection in onKeyDown)
        assertNull(
            "ENTER does not navigate, it triggers drilldown activation",
            DiscreteColumnBarChart.computeKeyNavigationTarget(KeyEvent.KEYCODE_ENTER, 2, count)
        )
        assertNull(
            "DPAD_CENTER does not navigate, it triggers drilldown activation",
            DiscreteColumnBarChart.computeKeyNavigationTarget(KeyEvent.KEYCODE_DPAD_CENTER, 2, count)
        )
    }

    @Test
    fun computePureColumnBounds_columnZeroAlwaysStartsAtChartLeft() {
        val bounds = DiscreteColumnBarChart.computePureColumnBounds(
            itemCount = 30,
            width = 1600f,
            height = 200f,
            chartLeft = 60f,
            chartRight = 1540f
        )
        assertEquals(30, bounds.size)
        assertEquals(60f, bounds[0].left, 0.001f)
        assertEquals(200f, bounds[0].bottom, 0.001f)
    }

    @Test
    fun computePureDesiredHeight_includesPaddingsAndReflectsFontScale() {
        // Standard font scale: valueFontSpacing=20, labelFontSpacing=16, minPlot=68
        val standardHeightNoPadding = DiscreteColumnBarChart.computePureDesiredHeight(
            valueFontSpacing = 20f,
            valueTextMarginDp = 6f,
            labelFontSpacing = 16f,
            bottomAxisMarginDp = 14f,
            minPlotHeightDp = 68f,
            paddingTop = 0,
            paddingBottom = 0,
        )
        // 26 + 68 + 30 = 124
        assertEquals(124, standardHeightNoPadding)

        // With top and bottom padding (e.g. 10dp bottom padding to prevent scrollbar occlusion)
        val standardHeightWithPadding = DiscreteColumnBarChart.computePureDesiredHeight(
            valueFontSpacing = 20f,
            valueTextMarginDp = 6f,
            labelFontSpacing = 16f,
            bottomAxisMarginDp = 14f,
            minPlotHeightDp = 68f,
            paddingTop = 4,
            paddingBottom = 10,
        )
        assertEquals(138, standardHeightWithPadding)

        // 200% font scale: font spacings doubled
        val largeFontHeight = DiscreteColumnBarChart.computePureDesiredHeight(
            valueFontSpacing = 40f,
            valueTextMarginDp = 6f,
            labelFontSpacing = 32f,
            bottomAxisMarginDp = 14f,
            minPlotHeightDp = 68f,
            paddingTop = 4,
            paddingBottom = 10,
        )
        // 46 + 68 + 46 + 14 = 174
        assertEquals(174, largeFontHeight)
        assertTrue("200% font scale expands desired height without clipping", largeFontHeight > standardHeightWithPadding)
    }
}
