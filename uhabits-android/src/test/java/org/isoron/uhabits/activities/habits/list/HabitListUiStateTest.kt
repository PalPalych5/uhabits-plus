/*
 * Copyright (C) 2016-2026 Álinson Santos Xavier <git@axavier.org> and contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.isoron.uhabits.activities.habits.list

import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.activities.habits.list.views.HabitCellTone
import org.isoron.uhabits.activities.habits.list.views.HabitCheckmarkGlyph
import org.isoron.uhabits.activities.habits.list.views.HabitGroupUiInfo
import org.isoron.uhabits.activities.habits.list.views.HabitRowIntValues
import org.isoron.uhabits.activities.habits.list.views.computeCheckmarkCount
import org.isoron.uhabits.activities.habits.list.views.computeHeaderDates
import org.isoron.uhabits.activities.habits.list.views.computeVisibleDayOffsets
import org.isoron.uhabits.activities.habits.list.views.resolveCheckmarkCell
import org.isoron.uhabits.activities.habits.list.views.resolveGroupUiInfo
import org.isoron.uhabits.activities.habits.list.views.resolveNumberCell
import org.isoron.uhabits.core.models.DayTier
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.HabitList
import org.isoron.uhabits.core.models.NumericalHabitType
import org.isoron.uhabits.core.ui.screens.habits.list.HabitCardListCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HabitListUiStateTest {

    private val today = LocalDate(2026, 11, 13)

    @Test
    fun computeCheckmarkCount_respectsMinLabelWidthAndMaxClamp() {
        // Narrow phone (320dp @ 1x): labelWidth = max(106.6, 160) = 160 -> (320 - 160) / 48 = 3
        assertEquals(3, computeCheckmarkCount(320f, 160f, 48f))
        // Standard phone (412dp @ 1x): labelWidth = 160 -> (412 - 160) / 48 = 5
        assertEquals(5, computeCheckmarkCount(412f, 160f, 48f))
        // Ultra-wide screen clamps at MAX_CHECKMARK_COUNT
        assertEquals(MAX_CHECKMARK_COUNT, computeCheckmarkCount(6000f, 160f, 48f))
        assertEquals(0, computeCheckmarkCount(0f, 160f, 48f))
    }

    @Test
    fun computeHeaderDates_normalAndReversedWithOffset() {
        val normalOffsets = computeVisibleDayOffsets(4, isReversed = false)
        assertEquals(listOf(0, 1, 2, 3), normalOffsets)

        val reversedOffsets = computeVisibleDayOffsets(4, isReversed = true)
        assertEquals(listOf(3, 2, 1, 0), reversedOffsets)

        val normalHeader = computeHeaderDates(
            today = today,
            dataOffset = 0,
            buttonCount = 3,
            isReversed = false,
            shortWeekdayProvider = { d -> "d${d.day}" }
        )
        assertEquals(listOf(today, today.minus(1), today.minus(2)), normalHeader.map { it.date })
        assertTrue(normalHeader[0].isToday)
        assertFalse(normalHeader[1].isToday)
        assertEquals("D13", normalHeader[0].dayOfWeekLabel)

        val shiftedReversedHeader = computeHeaderDates(
            today = today,
            dataOffset = 2,
            buttonCount = 3,
            isReversed = true,
            shortWeekdayProvider = { d -> "d${d.day}" }
        )
        assertEquals(listOf(today.minus(4), today.minus(3), today.minus(2)), shiftedReversedHeader.map { it.date })
        assertTrue(shiftedReversedHeader.none { it.isToday })
    }

    @Test
    fun resolveGroupUiInfo_handlesSpheresAndDayTiersCorrectly() {
        val rows = listOf(
            HabitCardListCache.RowMetadata(null, "k1", DayTier.MINIMUM, "Health"),
            HabitCardListCache.RowMetadata(null, "k1", DayTier.NORMAL, "Health"),
            HabitCardListCache.RowMetadata(null, null, DayTier.IDEAL, null)
        )

        val bySphereWithHeadings = resolveGroupUiInfo(
            metadataList = rows,
            primaryOrder = HabitList.Order.BY_SPHERE,
            spheresEnabled = true,
            showSphereNames = true,
            dayTiersEnabled = true,
            groupSeparatorsEnabled = false,
            unassignedHeading = "Other"
        )
        assertEquals(
            listOf(
                HabitGroupUiInfo(heading = "Health", showTopGap = false),
                HabitGroupUiInfo(heading = null, showTopGap = false),
                HabitGroupUiInfo(heading = "Other", showTopGap = false)
            ),
            bySphereWithHeadings
        )

        val bySphereWithoutHeadings = resolveGroupUiInfo(
            metadataList = rows,
            primaryOrder = HabitList.Order.BY_SPHERE,
            spheresEnabled = true,
            showSphereNames = false,
            dayTiersEnabled = true,
            groupSeparatorsEnabled = false,
            unassignedHeading = "Other"
        )
        assertEquals(
            listOf(
                HabitGroupUiInfo(heading = null, showTopGap = false),
                HabitGroupUiInfo(heading = null, showTopGap = false),
                HabitGroupUiInfo(heading = null, showTopGap = true)
            ),
            bySphereWithoutHeadings
        )

        val byDayTierWithSeparators = resolveGroupUiInfo(
            metadataList = rows,
            primaryOrder = HabitList.Order.BY_DAY_TIER,
            spheresEnabled = true,
            showSphereNames = true,
            dayTiersEnabled = true,
            groupSeparatorsEnabled = true,
            unassignedHeading = "Other"
        )
        assertEquals(
            listOf(
                HabitGroupUiInfo(heading = null, showTopGap = false),
                HabitGroupUiInfo(heading = null, showTopGap = true),
                HabitGroupUiInfo(heading = null, showTopGap = true)
            ),
            byDayTierWithSeparators
        )
    }

    @Test
    fun resolveCheckmarkCell_preservesYesAutoHollowAndQuestionMarks() {
        val yesAuto = resolveCheckmarkCell(Entry.YES_AUTO, notes = "auto note", areQuestionMarksEnabled = false)
        assertEquals(HabitCheckmarkGlyph.CHECK, yesAuto.glyph)
        assertTrue(yesAuto.isHollowYesAuto)
        assertEquals(HabitCellTone.ACTIVE, yesAuto.tone)
        assertTrue(yesAuto.hasNotes)

        val unknownQ = resolveCheckmarkCell(Entry.UNKNOWN, notes = "   ", areQuestionMarksEnabled = true)
        assertEquals(HabitCheckmarkGlyph.QUESTION, unknownQ.glyph)
        assertEquals(HabitCellTone.LOW, unknownQ.tone)
        assertFalse(unknownQ.hasNotes)

        val noWithQ = resolveCheckmarkCell(Entry.NO, notes = "", areQuestionMarksEnabled = true)
        assertEquals(HabitCheckmarkGlyph.TIMES, noWithQ.glyph)
        assertEquals(HabitCellTone.MEDIUM, noWithQ.tone)

        val skip = resolveCheckmarkCell(Entry.SKIP, notes = "sick", areQuestionMarksEnabled = false)
        assertEquals(HabitCheckmarkGlyph.SKIPPED, skip.glyph)
        assertEquals(HabitCellTone.ACTIVE, skip.tone)
        assertTrue(skip.hasNotes)
    }

    @Test
    fun resolveNumberCell_evaluatesThresholdsSkipAndUnknown() {
        val satisfiedAtLeast = resolveNumberCell(
            rawMilliValue = 2000,
            notes = "done",
            units = "km",
            targetType = NumericalHabitType.AT_LEAST,
            threshold = 2.0,
            areQuestionMarksEnabled = true,
            date = today,
            habit = null,
            effectiveSnapshot = null
        )
        assertEquals("2", satisfiedAtLeast.primaryText)
        assertEquals("km", satisfiedAtLeast.unitsText)
        assertEquals(HabitCellTone.ACTIVE, satisfiedAtLeast.tone)
        assertTrue(satisfiedAtLeast.hasNotes)
        assertNull(satisfiedAtLeast.glyph)

        val exceededAtMost = resolveNumberCell(
            rawMilliValue = 5000,
            notes = "",
            units = "min",
            targetType = NumericalHabitType.AT_MOST,
            threshold = 3.0,
            areQuestionMarksEnabled = false,
            date = today,
            habit = null,
            effectiveSnapshot = null
        )
        assertEquals(HabitCellTone.MEDIUM, exceededAtMost.tone)

        val skipped = resolveNumberCell(
            rawMilliValue = Entry.SKIP,
            notes = "",
            units = "min",
            targetType = NumericalHabitType.AT_LEAST,
            threshold = 10.0,
            areQuestionMarksEnabled = false,
            date = today,
            habit = null,
            effectiveSnapshot = null
        )
        assertTrue(skipped.isSkipped)
        assertEquals(HabitCheckmarkGlyph.SKIPPED, skipped.glyph)
        assertEquals(HabitCellTone.MEDIUM, skipped.tone)

        val unknownQ = resolveNumberCell(
            rawMilliValue = Entry.UNKNOWN,
            notes = "",
            units = "pages",
            targetType = NumericalHabitType.AT_LEAST,
            threshold = 10.0,
            areQuestionMarksEnabled = true,
            date = today,
            habit = null,
            effectiveSnapshot = null
        )
        assertTrue(unknownQ.isUnknown)
        assertEquals(HabitCheckmarkGlyph.QUESTION, unknownQ.glyph)
        assertEquals(HabitCellTone.LOW, unknownQ.tone)
    }

    @Test
    fun habitRowIntValues_usesContentEqualityForComposeSkipping() {
        val a = HabitRowIntValues(intArrayOf(Entry.YES_MANUAL, Entry.NO))
        val b = HabitRowIntValues(intArrayOf(Entry.YES_MANUAL, Entry.NO))
        val c = HabitRowIntValues(intArrayOf(Entry.YES_MANUAL, Entry.SKIP))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, c)
    }
}
