package org.isoron.uhabits.core.ui.screens.habits.show.views

import dev.mokkery.answering.calls
import dev.mokkery.every
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verify
import org.isoron.platform.time.getToday
import org.isoron.uhabits.core.BaseUnitTest
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.preferences.Preferences
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
        every { screen.showNumberPopup(any(), any(), any()) } calls { args ->
            picker = args.arg<NumberPickerCallback>(2)
            Unit
        }
        val presenter = HistoryCardPresenter(commandRunner, habit, habitList, mock<Preferences>(), screen)
        presenter.onDateShortPress(today)
        verify { screen.showNumberPopup(30.0, "manual", any()) }
        picker!!.onNumberPicked(40.0, "edited")
        assertEquals(40000, habit.originalEntries.get(today).value)
        assertEquals("edited", habit.originalEntries.get(today).notes)
    }
}
