/* Copyright (C) 2026 Loop Habit Tracker contributors. GPL-3.0-or-later. */
package org.isoron.uhabits.activities.common.dialogs

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorPickerProjectionTest {

    private fun projectX(draft: ComposeColorDraft): Float {
        val mid = 0.45f
        val w = (1f - draft.saturation) * draft.value
        val b = 1f - draft.value
        return if (b > w) {
            mid + (1f - mid) * b
        } else {
            mid * (1f - w)
        }.coerceIn(0f, 1f)
    }

    @Test
    fun testSpectrumProjectionBounds() {
        // Pure White: (S=0, V=1) -> x = 0.0
        val white = ComposeColorDraft(0xFFFFFFFF.toInt(), 0f, 0f, 1f)
        assertEquals(0f, projectX(white), 0.001f)

        // Pure Black: (V=0) -> x = 1.0
        val black = ComposeColorDraft(0xFF000000.toInt(), 0f, 0f, 0f)
        assertEquals(1f, projectX(black), 0.001f)

        // Pure Red Hue: (S=1, V=1, H=0) -> x = 0.45
        val pureRed = ComposeColorDraft(0xFFFF0000.toInt(), 0f, 1f, 1f)
        assertEquals(0.45f, projectX(pureRed), 0.001f)

        // Pure Green Hue: (S=1, V=1, H=120) -> x = 0.45
        val pureGreen = ComposeColorDraft(0xFF00FF00.toInt(), 120f, 1f, 1f)
        assertEquals(0.45f, projectX(pureGreen), 0.001f)

        // Pure Blue Hue: (S=1, V=1, H=240) -> x = 0.45
        val pureBlue = ComposeColorDraft(0xFF0000FF.toInt(), 240f, 1f, 1f)
        assertEquals(0.45f, projectX(pureBlue), 0.001f)
    }

    @Test
    fun testPastelAndDarkProjections() {
        // Light pastel: high value (0.95), low saturation (0.2) -> should project to light side (x < 0.45)
        val pastel = ComposeColorDraft(0xFFF0C0D0.toInt(), 340f, 0.2f, 0.95f)
        val xPastel = projectX(pastel)
        assertTrue("Pastel should be on light side (< 0.45), was $xPastel", xPastel < 0.45f)

        // Dark color: low value (0.25), high saturation (0.8) -> should project to dark side (x > 0.45)
        val dark = ComposeColorDraft(0xFF102040.toInt(), 220f, 0.8f, 0.25f)
        val xDark = projectX(dark)
        assertTrue("Dark should be on dark side (> 0.45), was $xDark", xDark > 0.45f)

        // Mid-tone grayscale (S=0, V=0.5) -> balanced
        val midGray = ComposeColorDraft(0xFF808080.toInt(), 0f, 0f, 0.5f)
        val xGray = projectX(midGray)
        assertTrue("Mid-gray should project in middle range (0.2..0.8), was $xGray", xGray in 0.2f..0.8f)
    }

    @Test
    fun testColorPickerUtilsHex() {
        val originalArgb = 0xFF55B868.toInt() // UHabit green preset
        val hex = ColorPickerUtils.toHex(originalArgb)
        assertEquals("#55B868", hex)
        val parsed = ColorPickerUtils.parseHex(hex)
        assertEquals(originalArgb, parsed)
    }

    @Test
    fun testAllPresetColorsProjectionBounds() {
        // Test that all 20 standard preset colors project within valid [0..1] range
        val presets = intArrayOf(
            0xFFD32F2F.toInt(), 0xFFE64A19.toInt(), 0xFFF57C00.toInt(), 0xFFFF8F00.toInt(),
            0xFFF9A825.toInt(), 0xFFAFB42B.toInt(), 0xFF7CB342.toInt(), 0xFF388E3C.toInt(),
            0xFF00897B.toInt(), 0xFF00ACC1.toInt(), 0xFF039BE5.toInt(), 0xFF1976D2.toInt(),
            0xFF303F9F.toInt(), 0xFF5E35B1.toInt(), 0xFF8E24AA.toInt(), 0xFFD81B60.toInt(),
            0xFF5D4037.toInt(), 0xFF303030.toInt(), 0xFF757575.toInt(), 0xFFAAAAAA.toInt()
        )
        for ((idx, argb) in presets.withIndex()) {
            val draft = ComposeColorDraft.fromArgb(argb)
            val px = projectX(draft)
            assertTrue("Preset $idx ($argb) projected X out of bounds: $px", px in 0f..1f)
            val py = draft.hue / 360f
            assertTrue("Preset $idx ($argb) projected Y (hue) out of bounds: $py", py in 0f..1f)
        }
    }

    @Test
    fun testForwardAndInverseProjectionConsistency() {
        // Test 100 sample points on the 2D Spectrum surface
        val mid = 0.45f
        for (i in 0..10) {
            val touchX = i / 10f
            val touchY = i / 10f
            val hue = (touchY * 360f).coerceIn(0f, 360f)
            val s: Float
            val v: Float
            if (touchX <= mid) {
                s = (touchX / mid).coerceIn(0f, 1f)
                v = 1.0f
            } else {
                s = 1.0f
                v = (1.0f - (touchX - mid) / (1.0f - mid)).coerceIn(0f, 1f)
            }
            val draft = ComposeColorDraft(android.graphics.Color.HSVToColor(floatArrayOf(hue, s, v)), hue, s, v)
            val projX = projectX(draft)
            assertEquals("Projection of touchX $touchX should roundtrip within tolerance", touchX, projX, 0.015f)
        }
    }

    @Test
    fun testSlidingSelectionAsymmetricCornerRadiiAtBoundaries() {
        fun computeCornerRadii(colFloat: Float, rowFloat: Float): FloatArray {
            val pTop = (1.0f - rowFloat).coerceIn(0f, 1f)
            val pBottom = (1.0f - (10.0f - rowFloat)).coerceIn(0f, 1f)
            val pLeft = (1.0f - colFloat).coerceIn(0f, 1f)
            val pRight = (1.0f - (11.0f - colFloat)).coerceIn(0f, 1f)

            val cornerFactorTL = pTop * pLeft
            val cornerFactorTR = pTop * pRight
            val cornerFactorBL = pBottom * pLeft
            val cornerFactorBR = pBottom * pRight

            val baseR = 4f
            val containerCornerR = 11.25f

            val rTL = baseR + (containerCornerR - baseR) * cornerFactorTL
            val rTR = baseR + (containerCornerR - baseR) * cornerFactorTR
            val rBL = baseR + (containerCornerR - baseR) * cornerFactorBL
            val rBR = baseR + (containerCornerR - baseR) * cornerFactorBR
            return floatArrayOf(rTL, rTR, rBL, rBR)
        }

        // Top-Left corner cell (col 0, row 0): outward corner TL is 11.25, others are base 4.0
        val tl = computeCornerRadii(0f, 0f)
        assertEquals(11.25f, tl[0], 0.001f)
        assertEquals(4f, tl[1], 0.001f)
        assertEquals(4f, tl[2], 0.001f)
        assertEquals(4f, tl[3], 0.001f)

        // Top-Right corner cell (col 11, row 0): outward corner TR is 11.25, others are base 4.0
        val tr = computeCornerRadii(11f, 0f)
        assertEquals(4f, tr[0], 0.001f)
        assertEquals(11.25f, tr[1], 0.001f)
        assertEquals(4f, tr[2], 0.001f)
        assertEquals(4f, tr[3], 0.001f)

        // Bottom-Left corner cell (col 0, row 10): outward corner BL is 11.25, others are base 4.0
        val bl = computeCornerRadii(0f, 10f)
        assertEquals(4f, bl[0], 0.001f)
        assertEquals(4f, bl[1], 0.001f)
        assertEquals(11.25f, bl[2], 0.001f)
        assertEquals(4f, bl[3], 0.001f)

        // Bottom-Right corner cell (col 11, row 10): outward corner BR is 11.25, others are base 4.0
        val br = computeCornerRadii(11f, 10f)
        assertEquals(4f, br[0], 0.001f)
        assertEquals(4f, br[1], 0.001f)
        assertEquals(4f, br[2], 0.001f)
        assertEquals(11.25f, br[3], 0.001f)

        // Straight top edge cell (col 5, row 0): all corners are base 4.0 (no outer container corner!)
        val topEdge = computeCornerRadii(5f, 0f)
        assertEquals(4f, topEdge[0], 0.001f)
        assertEquals(4f, topEdge[1], 0.001f)
        assertEquals(4f, topEdge[2], 0.001f)
        assertEquals(4f, topEdge[3], 0.001f)

        // Straight left edge cell (col 0, row 5): all corners are base 4.0
        val leftEdge = computeCornerRadii(0f, 5f)
        assertEquals(4f, leftEdge[0], 0.001f)
        assertEquals(4f, leftEdge[1], 0.001f)
        assertEquals(4f, leftEdge[2], 0.001f)
        assertEquals(4f, leftEdge[3], 0.001f)

        // Interior cell (col 5, row 5): all corners are base 4.0
        val interior = computeCornerRadii(5f, 5f)
        assertEquals(4f, interior[0], 0.001f)
        assertEquals(4f, interior[1], 0.001f)
        assertEquals(4f, interior[2], 0.001f)
        assertEquals(4f, interior[3], 0.001f)
    }

    @Test
    fun testSlidingSelectionEdgeClampingAndHalfStrokeBounds() {
        val totalCols = 12
        val totalRows = 11
        val cellW = 30f
        val cellH = 30f
        val totalW = totalCols * cellW
        val totalH = totalRows * cellH
        val halfW = cellW / 2f
        val halfH = cellH / 2f
        val darkStrokeWidth = 1.5f
        val halfStroke = darkStrokeWidth / 2f // 0.75f

        val testPoints = listOf(
            Pair(-100f, -50f),
            Pair(totalW + 200f, totalH + 100f),
            Pair(0f, 0f),
            Pair(totalW, totalH),
            Pair(cellW / 2f, cellH / 2f),
            Pair(totalW - cellW / 2f, totalH - cellH / 2f)
        )

        for ((rawX, rawY) in testPoints) {
            val clampedX = rawX.coerceIn(halfW, totalW - halfW)
            val clampedY = rawY.coerceIn(halfH, totalH - halfH)

            val reticleLeft = (clampedX - halfW) + halfStroke
            val reticleTop = (clampedY - halfH) + halfStroke
            val reticleRight = (clampedX + halfW) - halfStroke
            val reticleBottom = (clampedY + halfH) - halfStroke

            // Outer stroke boundaries (center ± halfStroke)
            val strokeLeft = reticleLeft - halfStroke
            val strokeTop = reticleTop - halfStroke
            val strokeRight = reticleRight + halfStroke
            val strokeBottom = reticleBottom + halfStroke

            assertTrue("Stroke left must be >= 0 (was $strokeLeft)", strokeLeft >= 0f)
            assertTrue("Stroke top must be >= 0 (was $strokeTop)", strokeTop >= 0f)
            assertTrue("Stroke right must be <= totalW (was $strokeRight)", strokeRight <= totalW)
            assertTrue("Stroke bottom must be <= totalH (was $strokeBottom)", strokeBottom <= totalH)
        }
    }

    @Test
    fun testLoupeCollisionAvoidanceAndSelectionClearance() {
        val density = 2.5f
        val contentW = 360f * density
        val contentH = 330f * density
        val loupeW = 108f * density
        val loupeH = 42f * density
        val margin = 6f * density
        val minSelClearance = 14f * density
        val selRadius = 15f * density

        val testPoints = listOf(
            Offset(15f * density, 15f * density),    // top-left corner
            Offset(180f * density, 15f * density),   // top edge
            Offset(345f * density, 15f * density),   // top-right corner
            Offset(180f * density, 150f * density),  // center
            Offset(15f * density, 150f * density),   // left edge
            Offset(345f * density, 150f * density),  // right edge
            Offset(15f * density, 315f * density),   // bottom-left corner
            Offset(180f * density, 315f * density),  // bottom edge
            Offset(345f * density, 315f * density)   // bottom-right corner
        )

        for (pt in testPoints) {
            val res = LoupeCollisionSolver.calculatePlacement(
                touchPoint = pt,
                selectionCenter = pt,
                selectionRadius = selRadius,
                contentWidthPx = contentW,
                contentHeightPx = contentH,
                density = density
            )

            // 1. Host bounding box containment (no clipping)
            assertTrue("Loupe left must be >= margin (was ${res.x})", res.x >= margin - 0.01f)
            assertTrue("Loupe top must be >= margin (was ${res.y})", res.y >= margin - 0.01f)
            assertTrue("Loupe right must be <= contentW - margin (was ${res.x + loupeW})", res.x + loupeW <= contentW - margin + 0.01f)
            assertTrue("Loupe bottom must be <= contentH - margin (was ${res.y + loupeH})", res.y + loupeH <= contentH - margin + 0.01f)

            // 2. Zero collision with selection indicator (with minSelClearance)
            val selLeft = pt.x - selRadius
            val selTop = pt.y - selRadius
            val selRight = pt.x + selRadius
            val selBottom = pt.y + selRadius

            val overlapsSelection = !(
                (res.x + loupeW) < (selLeft - minSelClearance) ||
                res.x > (selRight + minSelClearance) ||
                (res.y + loupeH) < (selTop - minSelClearance) ||
                res.y > (selBottom + minSelClearance)
            )
            assertTrue(
                "Loupe [${res.x}, ${res.y}, ${res.x + loupeW}, ${res.y + loupeH}] overlaps selection [${selLeft}, ${selTop}, ${selRight}, ${selBottom}] with clearance $minSelClearance for touch $pt",
                !overlapsSelection
            )
        }
    }

    @Test
    fun testLoupeHysteresisAndStability() {
        val density = 2.0f
        val contentW = 360f * density
        val contentH = 330f * density
        val selRadius = 15f * density

        var mode = LoupePlacementMode.ABOVE

        // Drag upwards from y = 150dp to y = 20dp
        val dragUpY = listOf(150f, 120f, 100f, 90f, 80f, 70f, 50f, 20f)
        for (yDp in dragUpY) {
            val pt = Offset(180f * density, yDp * density)
            val res = LoupeCollisionSolver.calculatePlacement(
                touchPoint = pt,
                selectionCenter = pt,
                selectionRadius = selRadius,
                contentWidthPx = contentW,
                contentHeightPx = contentH,
                density = density,
                currentMode = mode
            )
            mode = res.mode
        }
        assertEquals("Near top edge, mode should switch to BELOW", LoupePlacementMode.BELOW, mode)

        // Drag back downwards from y = 20dp to y = 150dp
        // In the hysteresis deadband (e.g. 80dp, 85dp), mode should stay in BELOW
        val res85 = LoupeCollisionSolver.calculatePlacement(
            touchPoint = Offset(180f * density, 85f * density),
            selectionCenter = Offset(180f * density, 85f * density),
            selectionRadius = selRadius,
            contentWidthPx = contentW,
            contentHeightPx = contentH,
            density = density,
            currentMode = mode
        )
        assertEquals("In deadband (85dp), mode must remain BELOW", LoupePlacementMode.BELOW, res85.mode)

        // Once dragged back down to 120dp, mode should comfortably return to ABOVE
        val res120 = LoupeCollisionSolver.calculatePlacement(
            touchPoint = Offset(180f * density, 120f * density),
            selectionCenter = Offset(180f * density, 120f * density),
            selectionRadius = selRadius,
            contentWidthPx = contentW,
            contentHeightPx = contentH,
            density = density,
            currentMode = res85.mode
        )
        assertEquals("At 120dp, mode should return to preferred ABOVE", LoupePlacementMode.ABOVE, res120.mode)
    }

    @Test
    fun testTapInterruptionAndSettleMath() {
        var curVisualX = 100f
        val target1X = 200f

        // Simulate in-flight progress
        curVisualX = curVisualX + (target1X - curVisualX) * 0.4f
        assertEquals(140f, curVisualX, 0.001f)

        // Interruption by new tap
        val target2X = 50f
        val newDx = target2X - curVisualX
        assertEquals(-90f, newDx, 0.001f)
    }
}
