package org.isoron.uhabits.activities.habits.list

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Test

class HabitToolbarAlignmentTest {

    @Test
    fun `classic 3-button toolbar icons align exactly with trailing 3 calendar column centers`() {
        val actionCentersFromEnd = computeToolbarActionCentersFromEndDp(
            actionCount = 3,
            buttonWidthDp = 48f,
            endMarginDp = 0f
        )
        // Calendar columns from left to right (e.g. 5 columns): trailing 3 are 2, 1, 0 from end
        val trailingCalendarCentersFromEnd = listOf(120f, 72f, 24f)
        assertEquals(trailingCalendarCentersFromEnd, actionCentersFromEnd)
    }

    @Test
    fun `modern bordered 4-button toolbar with sync aligns with trailing 4 columns`() {
        val actionCentersFromEnd = computeToolbarActionCentersFromEndDp(
            actionCount = 4,
            buttonWidthDp = 48f,
            endMarginDp = 4f
        )
        val trailingCalendarCentersFromEnd = listOf(172f, 124f, 76f, 28f)
        assertEquals(trailingCalendarCentersFromEnd, actionCentersFromEnd)
    }

    @Test
    fun `narrow 3-day borderless toolbar aligns with all 3 visible columns without overlap`() {
        val actionCentersFromEnd = computeToolbarActionCentersFromEndDp(
            actionCount = 3,
            buttonWidthDp = 48f,
            endMarginDp = 2f
        )
        val trailingCalendarCentersFromEnd = listOf(122f, 74f, 26f)
        assertEquals(trailingCalendarCentersFromEnd, actionCentersFromEnd)
        for (i in 0 until actionCentersFromEnd.lastIndex) {
            assertEquals(48f, actionCentersFromEnd[i] - actionCentersFromEnd[i + 1], 0.001f)
        }
    }

    @Test
    fun `home overflow popup sits inset from right screen edge in classic mode`() {
        val offset = calculateToolbarPopupPositionPx(
            anchorBounds = IntRect(954, 100, 1080, 226),
            windowSize = IntSize(1080, 2400),
            popupSize = IntSize(420, 240),
            layoutDirection = LayoutDirection.Ltr,
            endScreenMarginPx = 32,
            edgeMarginPx = 21,
            verticalOffsetPx = 5
        )
        // Right edge is placed at 1080 - 32 = 1048, so left x = 1048 - 420 = 628
        assertEquals(IntOffset(628, 231), offset)
    }

    @Test
    fun `home overflow popup clamps safely on narrow 320dp screen and flips above near bottom`() {
        val offset = calculateToolbarPopupPositionPx(
            anchorBounds = IntRect(544, 1180, 640, 1276),
            windowSize = IntSize(640, 1280),
            popupSize = IntSize(360, 200),
            layoutDirection = LayoutDirection.Ltr,
            endScreenMarginPx = 24,
            edgeMarginPx = 16,
            verticalOffsetPx = 4
        )
        assertEquals(IntOffset(256, 976), offset)

        val rtlOffset = calculateToolbarPopupPositionPx(
            anchorBounds = IntRect(0, 100, 96, 196),
            windowSize = IntSize(640, 1280),
            popupSize = IntSize(360, 200),
            layoutDirection = LayoutDirection.Rtl,
            endScreenMarginPx = 24,
            edgeMarginPx = 16,
            verticalOffsetPx = 4
        )
        assertEquals(IntOffset(24, 200), rtlOffset)
    }
}
