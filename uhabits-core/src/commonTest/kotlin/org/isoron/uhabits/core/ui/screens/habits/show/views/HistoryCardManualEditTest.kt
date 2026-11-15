package org.isoron.uhabits.core.ui.screens.habits.show.views

import dev.mokkery.answering.calls
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verify
import org.isoron.platform.time.getToday
import org.isoron.uhabits.core.BaseUnitTest
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.preferences.Preferences
import org.isoron.uhabits.core.ui.callbacks.CheckMarkDialogCallback
import org.isoron.uhabits.core.ui.callbacks.NumberPickerCallback
import kotlin.test.Test
import kotlin.test.assertEquals

class HistoryCardManualEditTest : BaseUnitTest() {
    @Test
    fun editorSeedsManualValueAndDoesNotCopyDerivedQuantity() {
        val habit = fixtures.createNumericalHabit()
        habitList.add(habit)
        val today = getToday()
        habit.originalEntries.add(Entry(today, 30000, "manual"))
        habit.recompute()
        habit.computedEntries.add(Entry(today, 90000, "derived"))
        val screen = mock<HistoryCardPresenter.Screen>()
        var picker: NumberPickerCallback? = null
        every { screen.showNumberPopup(any(), any(), any(), any(), any()) } calls { args ->
            picker = args.arg<NumberPickerCallback>(4)
            Unit
        }
        val presenter = HistoryCardPresenter(commandRunner, habit, habitList, mock<Preferences>(), screen)
        presenter.onDateShortPress(today)
        verify { screen.showNumberPopup(30.0, "manual", habit, today, any()) }
        picker!!.onNumberPicked(40.0, "edited")
        assertEquals(40000, habit.originalEntries.get(today).value)
        assertEquals("edited", habit.originalEntries.get(today).notes)
    }

    @Test
    fun explicitZeroOnUnknownPersistsZero() {
        val habit = fixtures.createNumericalHabit()
        habitList.add(habit)
        val emptyDay = getToday().minus(2)
        val screen = mock<HistoryCardPresenter.Screen>()
        var picker: NumberPickerCallback? = null
        every { screen.showNumberPopup(any(), any(), any(), any(), any()) } calls { args ->
            picker = args.arg<NumberPickerCallback>(4)
            Unit
        }
        val presenter = HistoryCardPresenter(commandRunner, habit, habitList, mock<Preferences>(), screen)
        presenter.onDateShortPress(emptyDay)
        verify { screen.showNumberPopup(0.0, "", habit, emptyDay, any()) }
        picker!!.onNumberPicked(0.0, "")
        assertEquals(Entry.NO, habit.originalEntries.get(emptyDay).value)
    }

    @Test
    fun binaryHabitShortPressOpensCheckmarkDialogAndDoesNotSilentlyToggle() {
        val habit = fixtures.createEmptyHabit()
        habitList.add(habit)
        val today = getToday()
        val screen = mock<HistoryCardPresenter.Screen>()
        var callback: CheckMarkDialogCallback? = null
        every { screen.showCheckmarkPopup(any(), any(), any(), any()) } calls { args ->
            callback = args.arg<CheckMarkDialogCallback>(3)
            Unit
        }
        val prefs = mock<Preferences>()
        every { prefs.isShortToggleEnabled } returns true
        val presenter = HistoryCardPresenter(commandRunner, habit, habitList, prefs, screen)

        // Short press must open checkmark popup, not silently toggle
        presenter.onDateShortPress(today)
        verify { screen.showCheckmarkPopup(Entry.UNKNOWN, "", habit.color, any()) }

        // Executing the callback saves entry
        callback!!.onNotesSaved(Entry.YES_MANUAL, "done with note")
        assertEquals(Entry.YES_MANUAL, habit.originalEntries.get(today).value)
        assertEquals("done with note", habit.originalEntries.get(today).notes)
    }

    @Test
    fun binaryHabitLongPressAlsoOpensCheckmarkDialog() {
        val habit = fixtures.createEmptyHabit()
        habitList.add(habit)
        val today = getToday()
        val screen = mock<HistoryCardPresenter.Screen>()
        every { screen.showCheckmarkPopup(any(), any(), any(), any()) } returns Unit
        val prefs = mock<Preferences>()
        every { prefs.isShortToggleEnabled } returns false
        val presenter = HistoryCardPresenter(commandRunner, habit, habitList, prefs, screen)

        presenter.onDateLongPress(today)
        verify { screen.showCheckmarkPopup(Entry.UNKNOWN, "", habit.color, any()) }
    }
}
