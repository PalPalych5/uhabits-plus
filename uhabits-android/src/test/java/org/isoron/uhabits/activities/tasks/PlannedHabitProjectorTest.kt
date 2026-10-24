package org.isoron.uhabits.activities.tasks

import org.isoron.platform.time.DayOfWeek
import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.core.models.Frequency
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.PaletteColor
import org.isoron.uhabits.core.models.Reminder
import org.isoron.uhabits.core.models.WeekdayList
import org.isoron.uhabits.core.models.memory.MemoryModelFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlannedHabitProjectorTest {

    private val modelFactory = MemoryModelFactory()

    private fun createHabit(
        id: Long = 1L,
        name: String = "Exercise",
        frequency: Frequency = Frequency.DAILY,
        reminder: Reminder? = null,
        isArchived: Boolean = false,
        statisticsStartDate: LocalDate? = null
    ): Habit {
        val habit = modelFactory.buildHabit()
        habit.id = id
        habit.name = name
        habit.color = PaletteColor(1)
        habit.frequency = frequency
        habit.reminder = reminder
        habit.isArchived = isArchived
        habit.statisticsStartDate = statisticsStartDate
        return habit
    }

    @Test
    fun dailyHabitWithoutReminderIsAllDayEveryDay() {
        val habit = createHabit(id = 10, name = "Water")
        val monday = LocalDate(2026, 10, 19) // Monday
        val sunday = LocalDate(2026, 10, 25) // Sunday

        val mondayItems = PlannedHabitProjector.projectForDate(listOf(habit), monday)
        assertEquals(1, mondayItems.size)
        val m = mondayItems.single()
        assertEquals("Water", m.name)
        assertTrue(m.isAllDay)
        assertEquals(null, m.reminderHour)

        val sundayItems = PlannedHabitProjector.projectForDate(listOf(habit), sunday)
        assertEquals(1, sundayItems.size)
        assertTrue(sundayItems.single().isAllDay)
    }

    @Test
    fun dailyHabitWithReminderEveryDayHasExactTime() {
        // Every day (127 = 0x7F) at 08:30
        val reminder = Reminder(8, 30, WeekdayList(127))
        val habit = createHabit(id = 11, name = "Morning Jog", reminder = reminder)
        val date = LocalDate(2026, 10, 24) // Saturday

        val items = PlannedHabitProjector.projectForDate(listOf(habit), date)
        assertEquals(1, items.size)
        val item = items.single()
        assertFalse(item.isAllDay)
        assertEquals(8, item.reminderHour)
        assertEquals(30, item.reminderMinute)
    }

    @Test
    fun habitWithReminderOnSpecificWeekdaysOnlyProjectsOnThoseDays() {
        // Mon, Wed, Fri only
        // Monday = bit 2 (4), Wednesday = bit 4 (16), Friday = bit 6 (64) -> 4 + 16 + 64 = 84
        val packed = (1 shl 2) or (1 shl 4) or (1 shl 6)
        val reminder = Reminder(18, 0, WeekdayList(packed))
        val habit = createHabit(id = 12, name = "Gym", frequency = Frequency(3, 7), reminder = reminder)

        val monday = LocalDate(2026, 10, 19)
        val tuesday = LocalDate(2026, 10, 20)
        val wednesday = LocalDate(2026, 10, 21)
        val thursday = LocalDate(2026, 10, 22)
        val friday = LocalDate(2026, 10, 23)
        val saturday = LocalDate(2026, 10, 24)
        val sunday = LocalDate(2026, 10, 25)

        assertEquals(DayOfWeek.MONDAY, monday.dayOfWeek)
        assertEquals(DayOfWeek.TUESDAY, tuesday.dayOfWeek)
        assertEquals(DayOfWeek.WEDNESDAY, wednesday.dayOfWeek)
        assertEquals(DayOfWeek.THURSDAY, thursday.dayOfWeek)
        assertEquals(DayOfWeek.FRIDAY, friday.dayOfWeek)
        assertEquals(DayOfWeek.SATURDAY, saturday.dayOfWeek)
        assertEquals(DayOfWeek.SUNDAY, sunday.dayOfWeek)

        assertEquals(1, PlannedHabitProjector.projectForDate(listOf(habit), monday).size)
        assertEquals(0, PlannedHabitProjector.projectForDate(listOf(habit), tuesday).size)
        assertEquals(1, PlannedHabitProjector.projectForDate(listOf(habit), wednesday).size)
        assertEquals(0, PlannedHabitProjector.projectForDate(listOf(habit), thursday).size)
        assertEquals(1, PlannedHabitProjector.projectForDate(listOf(habit), friday).size)
        assertEquals(0, PlannedHabitProjector.projectForDate(listOf(habit), saturday).size)
        assertEquals(0, PlannedHabitProjector.projectForDate(listOf(habit), sunday).size)

        val monItem = PlannedHabitProjector.projectForDate(listOf(habit), monday).single()
        assertEquals(18, monItem.reminderHour)
        assertEquals(0, monItem.reminderMinute)
    }

    @Test
    fun weeklyHabitWithoutReminderDaysIsNotProjected() {
        // Documented limitation: weekly habit without explicit reminder days cannot be placed on specific dates
        val habit = createHabit(id = 13, name = "Call Parents", frequency = Frequency(1, 7), reminder = null)
        val date = LocalDate(2026, 10, 24)

        val items = PlannedHabitProjector.projectForDate(listOf(habit), date)
        assertTrue(items.isEmpty())
    }

    @Test
    fun archivedHabitIsNeverProjected() {
        val habit = createHabit(id = 14, isArchived = true)
        val date = LocalDate(2026, 10, 24)

        val items = PlannedHabitProjector.projectForDate(listOf(habit), date)
        assertTrue(items.isEmpty())
    }

    @Test
    fun dateBeforeStatisticsStartDateIsExcluded() {
        val habit = createHabit(id = 15, statisticsStartDate = LocalDate(2026, 10, 25))
        val yesterday = LocalDate(2026, 10, 24)
        val today = LocalDate(2026, 10, 25)

        assertTrue(PlannedHabitProjector.projectForDate(listOf(habit), yesterday).isEmpty())
        assertEquals(1, PlannedHabitProjector.projectForDate(listOf(habit), today).size)
    }

    @Test
    fun projectForRangeCollectsAllDaysInRange() {
        val habit = createHabit(id = 16, name = "Read")
        val start = LocalDate(2026, 10, 19)
        val end = LocalDate(2026, 10, 25)

        val items = PlannedHabitProjector.projectForRange(listOf(habit), start, end)
        assertEquals(7, items.size)
        assertEquals(start, items.first().date)
        assertEquals(end, items.last().date)
    }
}
