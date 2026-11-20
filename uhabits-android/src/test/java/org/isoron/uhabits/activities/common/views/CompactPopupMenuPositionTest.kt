package org.isoron.uhabits.activities.common.views

import org.junit.Assert.assertEquals
import org.junit.Test

class CompactPopupMenuPositionTest {

    @Test
    fun keepsPixelSpacingAndWidthBoundsAtFractionalDensity() {
        val spacing = CompactPopupSpacing(1.25f)

        assertEquals(7, spacing.menuPadding)
        assertEquals(2, spacing.itemMargin)
        assertEquals(1, spacing.dividerHeight)
        assertEquals(5, spacing.dividerMargin)
        assertEquals(40, spacing.iconWidth(hasIcon = true))
        assertEquals(180, spacing.menuWidth(spacing.itemWidth(22, hasIcon = false), 144, 220))
        assertEquals(275, spacing.menuWidth(spacing.itemWidth(420, hasIcon = true), 144, 220))
        assertEquals(191, spacing.rowWidth(275, hasIcon = true))
        assertEquals(231, spacing.rowWidth(275, hasIcon = false))
    }

    @Test
    fun usesMeasuredMultilineHeightOnlyWhenLargerThanTheCompactRow() {
        val spacing = CompactPopupSpacing(1.25f)

        assertEquals(52, spacing.rowHeight(labelHeight = 38, hasIcon = false))
        assertEquals(52, spacing.rowHeight(labelHeight = 38, hasIcon = true))
        assertEquals(76, spacing.rowHeight(labelHeight = 76, hasIcon = true))
    }

    @Test
    fun keepsAnchorCoordinatesPhysicalWithinAnInsetVisibleFrame() {
        val position = CompactPopupMenu.calculatePopupCoordinates(
            anchorLeft = 480,
            anchorTop = 220,
            anchorRight = 530,
            anchorBottom = 264,
            frameLeft = 320,
            frameTop = 60,
            frameRight = 720,
            frameBottom = 680,
            popupWidth = 155,
            popupHeight = 188,
            horizontalMarginPx = 8,
            verticalMarginPx = 8,
            verticalOffsetPx = 4,
        )

        assertEquals(375 to 268, position)
    }

    @Test
    fun placesMenuDirectlyBelowSelectorChipRightAligned() {
        val (x, y) = CompactPopupMenu.calculatePopupCoordinates(
            anchorLeft = 700,
            anchorTop = 420,
            anchorRight = 980,
            anchorBottom = 510,
            frameLeft = 0,
            frameTop = 80,
            frameRight = 1080,
            frameBottom = 2280,
            popupWidth = 360,
            popupHeight = 420,
            horizontalMarginPx = 16,
            verticalMarginPx = 16,
            verticalOffsetPx = 8,
        )

        assertEquals(620, x)
        assertEquals(518, y)
    }

    @Test
    fun flipsMenuAboveChipWhenNearBottomEdge() {
        val (x, y) = CompactPopupMenu.calculatePopupCoordinates(
            anchorLeft = 700,
            anchorTop = 1980,
            anchorRight = 980,
            anchorBottom = 2070,
            frameLeft = 0,
            frameTop = 80,
            frameRight = 1080,
            frameBottom = 2280,
            popupWidth = 360,
            popupHeight = 420,
            horizontalMarginPx = 16,
            verticalMarginPx = 16,
            verticalOffsetPx = 8,
        )

        assertEquals(620, x)
        assertEquals(1552, y)
    }

    @Test
    fun clampsMenuWithinTopAndBottomSystemInsets() {
        val (_, yNearTop) = CompactPopupMenu.calculatePopupCoordinates(
            anchorLeft = 400,
            anchorTop = 100,
            anchorRight = 640,
            anchorBottom = 180,
            frameLeft = 0,
            frameTop = 120,
            frameRight = 1080,
            frameBottom = 700,
            popupWidth = 300,
            popupHeight = 600,
            horizontalMarginPx = 16,
            verticalMarginPx = 16,
            verticalOffsetPx = 8,
        )

        assertEquals(136, yNearTop)
    }

    @Test
    fun clampsHorizontalPositionOnNarrow320dpScreen() {
        val (xRightEdge, _) = CompactPopupMenu.calculatePopupCoordinates(
            anchorLeft = 360,
            anchorTop = 300,
            anchorRight = 630,
            anchorBottom = 380,
            frameLeft = 0,
            frameTop = 60,
            frameRight = 640,
            frameBottom = 1200,
            popupWidth = 420,
            popupHeight = 300,
            horizontalMarginPx = 16,
            verticalMarginPx = 16,
            verticalOffsetPx = 8,
        )
        assertEquals(204, xRightEdge)

        val (xLeftOverflow, _) = CompactPopupMenu.calculatePopupCoordinates(
            anchorLeft = 20,
            anchorTop = 300,
            anchorRight = 220,
            anchorBottom = 380,
            frameLeft = 0,
            frameTop = 60,
            frameRight = 640,
            frameBottom = 1200,
            popupWidth = 420,
            popupHeight = 300,
            horizontalMarginPx = 16,
            verticalMarginPx = 16,
            verticalOffsetPx = 8,
        )
        assertEquals(16, xLeftOverflow)
    }
}
