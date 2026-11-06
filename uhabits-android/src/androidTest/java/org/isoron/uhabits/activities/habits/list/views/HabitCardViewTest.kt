/*
 * Copyright (C) 2016-2025 Álinson Santos Xavier <git@axavier.org>
 *
 * This file is part of Loop Habit Tracker.
 *
 * Loop Habit Tracker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * Loop Habit Tracker is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.isoron.uhabits.activities.habits.list.views

import android.widget.LinearLayout
import android.widget.FrameLayout
import android.widget.TextView
import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import androidx.compose.ui.platform.ComposeView
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.getToday
import org.isoron.uhabits.BaseViewTest
import org.isoron.uhabits.R
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.PaletteColor
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Frequency
import org.isoron.uhabits.core.models.HabitType
import org.isoron.uhabits.core.models.NumericalHabitType
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.contribution.memory.MemoryContributionRepository
import org.isoron.uhabits.core.models.projection.EffectiveHabitReadSnapshotProvider
import org.isoron.uhabits.core.models.projection.EffectiveReadCalendarContext
import org.isoron.uhabits.core.models.projection.HabitGoalStatus
import org.isoron.platform.time.DayOfWeek
import org.isoron.platform.time.getFirstWeekdayNumberAccordingToLocale
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@MediumTest
class HabitCardViewTest : BaseViewTest() {

    @Test
    fun recycledSphereHeadingUpdatesAndClearsWithoutChangingCardContent() {
        val content = view.getChildAt(0) as LinearLayout
        val original = content.layoutParams as FrameLayout.LayoutParams
        assertEquals(0, original.topMargin)

        view.groupHeading = "First sphere"
        view.composeGroupHeading = "First sphere"
        val heading = (0 until view.childCount).map(view::getChildAt).filterIsInstance<ComposeView>().single()
        org.junit.Assert.assertTrue((content.layoutParams as FrameLayout.LayoutParams).topMargin > 0)
        assertEquals(View.VISIBLE, heading.visibility)

        view.groupHeading = "Second sphere"
        view.composeGroupHeading = "Second sphere"
        org.junit.Assert.assertSame(heading, (0 until view.childCount).map(view::getChildAt).filterIsInstance<ComposeView>().single())
        view.groupHeading = null
        view.composeGroupHeading = null
        assertEquals(View.GONE, heading.visibility)
        assertEquals(0, (content.layoutParams as FrameLayout.LayoutParams).topMargin)
        org.junit.Assert.assertSame(content, view.getChildAt(0))
        org.junit.Assert.assertSame(habit1, view.habit)
    }

    @Test
    fun manualWeeklyLimitUsesWholeWeekInsteadOfDailyFraction() {
        val date = getToday()
        val first = DayOfWeek.entries[getFirstWeekdayNumberAccordingToLocale() - 1]
        val weekStart = date.startOfWeek(first)
        val h = fixtures.createEmptyHabit().apply {
            uuid = "manual-weekly-limit-qa"
            type = HabitType.NUMERICAL
            unit = "min"
            frequency = Frequency.WEEKLY
            targetType = NumericalHabitType.AT_MOST
            targetValue = 180.0
            for (offset in 0..6) originalEntries.add(Entry(weekStart.plus(offset), 0))
            originalEntries.add(Entry(date, 30000))
            recompute()
        }
        val provider = EffectiveHabitReadSnapshotProvider(MemoryContributionRepository())
        val calendar = EffectiveReadCalendarContext("UTC", first, 0, 0)
        fun snapshot() = provider.prepare(listOf(h), date, date, "test", 1, calendar).getValue(HabitRef(h.uuid!!))
        fun findPanel(v: View): NumberPanelView? {
            if (v is NumberPanelView) return v
            if (v is ViewGroup) for (i in 0 until v.childCount) findPanel(v.getChildAt(i))?.let { return it }
            return null
        }
        view.habit = h
        view.buttonCount = 1
        view.effectiveSnapshot = snapshot()
        val panel = requireNotNull(findPanel(view))
        assertEquals(HabitGoalStatus.SATISFIED, panel.buttons.single().effectiveCompletion)
        h.originalEntries.add(Entry(date, 200000)); h.recompute()
        view.effectiveSnapshot = snapshot()
        assertEquals(HabitGoalStatus.NOT_SATISFIED, panel.buttons.single().effectiveCompletion)
    }

    val PATH = "habits/list/HabitCardView"
    private lateinit var view: HabitCardView
    private lateinit var habit1: Habit
    private lateinit var habit2: Habit
    private lateinit var today: LocalDate

    override fun setUp() {
        super.setUp()
        setTheme(R.style.AppBaseTheme)

        habit1 = fixtures.createLongHabit()
        habit2 = fixtures.createLongNumericalHabit()
        today = getToday()

        val entries = habit1
            .computedEntries
            .getByInterval(today.minus(300), today)
            .map { it.value }.toIntArray()

        view = component.getHabitCardViewFactory().create().apply {
            habit = habit1
            values = entries
            score = habit1.scores[today].value
            isSelected = false
            buttonCount = 5
        }
        latch.countDown()

        latch.await()
        measureView(view, dpToPixels(400), dpToPixels(50))
    }

    @Test
    fun testRender() {
        assertRenders(view, "$PATH/render.png")
    }

    @Test
    fun testRender_selected() {
        view.isSelected = true
        measureView(view, dpToPixels(400), dpToPixels(50))
        assertRenders(view, "$PATH/render_selected.png")
    }

    @Test
    fun testRender_numerical() {
        val entries = habit2
            .computedEntries
            .getByInterval(today.minus(300), today)
            .map { it.value }.toIntArray()

        view.apply {
            habit = habit2
            values = entries
        }
        assertRenders(view, "$PATH/render_numerical.png")
    }

    @Test
    fun testChangeModel() {
        habit1.name = "Wake up early"
        habit1.color = PaletteColor(2)
        habit1.observable.notifyListeners()
        Thread.sleep(500)
        assertRenders(view, "$PATH/render_changed.png")
    }

    @Test
    fun entryBindingPreservesCardIdentityAndUpdatesVisibleValues() {
        for (habit in listOf(habit1, habit2)) {
            view.habit = habit
            view.isSelected = true
            view.dataOffset = 1
            val content = view.getChildAt(0) as LinearLayout
            val background = content.background
            val numberPanel = (0 until content.childCount).map { content.getChildAt(it) }
                .filterIsInstance<NumberPanelView>().single()
            val unit = view.unit
            val threshold = view.threshold
            view.bindEntryData(0.75, intArrayOf(1000, 2500, 3500), arrayOf("first", "second", "third"), null)
            org.junit.Assert.assertSame(habit, view.habit)
            org.junit.Assert.assertSame(background, content.background)
            org.junit.Assert.assertTrue(view.isSelected)
            assertEquals(5, view.buttonCount)
            assertEquals(1, view.dataOffset)
            assertEquals(unit, view.unit)
            assertEquals(threshold, view.threshold, 0.0)
            assertEquals(0.75, view.score, 0.0001)
            assertEquals(2500, view.checkmarkPanel.buttons[0].value)
            assertEquals("second", view.checkmarkPanel.buttons[0].notes)
            if (habit.isNumerical) {
                assertEquals(2.5, numberPanel.buttons[0].value, 0.0)
                assertEquals("second", numberPanel.buttons[0].notes)
                org.junit.Assert.assertNull(view.effectiveSnapshot)
            }
        }
    }

    @Test
    fun unknownScorePreservesTitleAndNumericColumns() {
        for (width in listOf(360, 400)) {
            for (habit in listOf(habit1, habit2)) {
                view.habit = habit.apply { name = "Развитие голоса и речи" }
                view.score = 0.5
                measureView(view, dpToPixels(width), dpToPixels(50))
                val content = view.getChildAt(0) as LinearLayout
                val title = (0 until content.childCount).map { content.getChildAt(it) }
                    .filterIsInstance<TextView>().first()
                val originalLeft = title.left
                val originalWidth = title.width
                val panel = content.getChildAt(if (habit.isNumerical) content.childCount - 1 else content.childCount - 2)
                val numericLeft = panel.left
                view.score = Double.NaN
                measureView(view, dpToPixels(width), dpToPixels(50))
                assertEquals(originalLeft, title.left)
                assertEquals(originalWidth, title.width)
                assertEquals(numericLeft, panel.left)
                view.score = 0.5
                measureView(view, dpToPixels(width), dpToPixels(50))
                assertEquals(originalLeft, title.left)
                }
        }
    }

    @Test
    fun entryBindingPublishesFocusSnapshotAndClearsFinalTombstoneOverlay() {
        val repository = org.isoron.uhabits.core.contribution.memory.MemoryContributionRepository()
        val provider = org.isoron.uhabits.core.models.projection.EffectiveHabitReadSnapshotProvider(repository)
        habit2.unit = "min"
        habit2.originalEntries.add(org.isoron.uhabits.core.models.Entry(today, 30000))
        habit2.recompute()
        val ref = org.isoron.uhabits.core.containers.HabitRef(requireNotNull(habit2.uuid))
        val timestamp = org.isoron.platform.time.startOfLocalDay(today) + 1000
        val credit = org.isoron.uhabits.core.contribution.Contribution(
            org.isoron.uhabits.core.contribution.ContributionId("card-focus-credit"), ref,
            org.isoron.uhabits.core.contribution.ContributionSourceType.FOCUS_SESSION,
            "card-focus-source", 60000L, timestamp, timestamp, timestamp
        )
        repository.record(credit)
        val calendar = org.isoron.uhabits.core.models.projection.EffectiveReadCalendarContext(
            java.util.TimeZone.getDefault().id, org.isoron.platform.time.DayOfWeek.MONDAY, 0, 0
        )
        fun snapshot(revision: Long) = provider.prepare(
            listOf(habit2), today.minus(4), today, "disposable-card-test", revision, calendar
        ).getValue(ref)
        view.habit = habit2
        view.dataOffset = 0
        view.isSelected = true
        measureView(view, dpToPixels(400), dpToPixels(50))
        val content = view.getChildAt(0) as LinearLayout
        val panel = (0 until content.childCount).map { content.getChildAt(it) }
            .filterIsInstance<NumberPanelView>().single()
        val background = content.background
        val left = panel.left
        val width = panel.width
        val units = view.unit
        val values = intArrayOf(30000, 0, 0, 0, 0)
        val notes = arrayOf("manual", "", "", "", "")
        val credited = snapshot(1)
        view.bindEntryData(0.5, values, notes, credited)
        org.junit.Assert.assertSame(credited, panel.effectiveSnapshot)
        assertEquals(90000L, panel.buttons[0].effectiveDay!!.totalValue)
        org.junit.Assert.assertTrue(panel.buttons[0].effectiveDay!!.hasCompleteCoverage)
        org.junit.Assert.assertSame(habit2, view.habit)
        org.junit.Assert.assertSame(background, content.background)
        assertEquals(units, view.unit)
        measureView(view, dpToPixels(400), dpToPixels(50))
        assertEquals(left, panel.left)
        assertEquals(width, panel.width)

        repository.removeById(credit.id, timestamp + 1)
        val tombstoned = snapshot(2)
        org.junit.Assert.assertFalse(tombstoned.hasContributions)
        org.junit.Assert.assertTrue(tombstoned.hasHistoricalContributions)
        view.bindEntryData(0.5, values, notes, tombstoned)
        org.junit.Assert.assertNull(view.effectiveSnapshot)
        org.junit.Assert.assertNull(panel.buttons[0].effectiveDay)
        org.junit.Assert.assertNull(panel.buttons[0].effectiveCompletion)
        assertEquals(30.0, panel.buttons[0].value, 0.0)
        assertEquals("manual", panel.buttons[0].notes)
        assertEquals(30000, habit2.originalEntries.get(today).value)
        org.junit.Assert.assertSame(habit2, view.habit)
        org.junit.Assert.assertSame(background, content.background)
        assertEquals(units, view.unit)
    }
}
