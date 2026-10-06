package org.isoron.uhabits.core.models.projection

import org.isoron.platform.time.DayOfWeek
import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.contribution.Contribution
import org.isoron.uhabits.core.contribution.ContributionId
import org.isoron.uhabits.core.contribution.ContributionSourceType
import org.isoron.uhabits.core.contribution.memory.MemoryContributionRepository
import org.isoron.uhabits.core.models.Frequency
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitType
import org.isoron.uhabits.core.models.memory.MemoryModelFactory
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.TimeZone
import kotlin.test.assertEquals

class EffectiveHabitTimezoneTest {
    private lateinit var originalTimeZone: TimeZone
    private val repository = MemoryContributionRepository()
    private val habit = MemoryModelFactory().buildHabit().apply {
        type = HabitType.NUMERICAL
        unit = "min"
        targetValue = 1.0
    }

    @Before
    fun saveTimeZone() {
        originalTimeZone = TimeZone.getDefault()
    }

    @After
    fun restoreTimeZone() {
        TimeZone.setDefault(originalTimeZone)
    }

    @Test
    fun repeatedHourIsBucketedByCanonicalDateEvenWhenLabelMovesBackwards() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        val service = EffectiveHabitValueService(repository, 1, 30, DayOfWeek.MONDAY)
        record("before", "2026-11-01T05:29:59Z", 1000)
        record("first-boundary", "2026-11-01T05:30:00Z", 2000)
        record("clock-back", "2026-11-01T06:00:00Z", 4000)
        record("second-boundary", "2026-11-01T06:30:00Z", 8000)
        assertEquals(5000L, service.valueForDay(habit, LocalDate(2026, 10, 31)).contributionValue)
        assertEquals(10000L, service.valueForDay(habit, LocalDate(2026, 11, 1)).contributionValue)
        assertEquals(15000L, service.valueForRange(habit, LocalDate(2026, 10, 31), LocalDate(2026, 11, 1)).contributionValue)
    }

    @Test
    fun shiftedMidnightUsesContributionEffectiveTimestamp() {
        TimeZone.setDefault(TimeZone.getTimeZone("GMT+07:00"))
        val service = EffectiveHabitValueService(repository, 3, 30, DayOfWeek.MONDAY)
        // A session can cross civil midnight. Only its ledger effective timestamp is projected.
        record("cross-midnight", "2026-10-01T18:00:00Z", 120000)
        record("next-day-boundary", "2026-10-01T20:30:00Z", 60000)
        assertEquals(120000L, service.valueForDay(habit, LocalDate(2026, 10, 1)).contributionValue)
        assertEquals(60000L, service.valueForDay(habit, LocalDate(2026, 10, 2)).contributionValue)
    }

    @Test
    fun weekAndMonthQueriesExcludeAdjacentEnvelopeFacts() {
        TimeZone.setDefault(TimeZone.getTimeZone("GMT+07:00"))
        val service = EffectiveHabitValueService(repository, 3, 30, DayOfWeek.MONDAY)
        record("before-month", "2026-09-30T20:29:59Z", 1000)
        record("month-start", "2026-09-30T20:30:00Z", 2000)
        record("before-week", "2026-10-04T20:29:59Z", 4000)
        record("week-start", "2026-10-04T20:30:00Z", 8000)
        record("after-week", "2026-10-11T20:30:00Z", 16000)
        record("after-month", "2026-10-31T20:30:00Z", 32000)
        habit.frequency = Frequency.WEEKLY
        val week = service.goalProgressOn(habit, LocalDate(2026, 10, 5))
        assertEquals(LocalDate(2026, 10, 5), week.from)
        assertEquals(LocalDate(2026, 10, 11), week.to)
        assertEquals(8000L, week.value.contributionValue)
        habit.frequency = Frequency(1, 30)
        val month = service.goalProgressOn(habit, LocalDate(2026, 10, 5))
        assertEquals(LocalDate(2026, 10, 1), month.from)
        assertEquals(LocalDate(2026, 10, 31), month.to)
        assertEquals(30000L, month.value.contributionValue)
    }

    private fun record(id: String, timestamp: String, value: Long) {
        val instant = Instant.parse(timestamp).toEpochMilli()
        repository.record(Contribution(
            id = ContributionId(id),
            targetHabit = HabitRef(requireNotNull(habit.uuid)),
            sourceType = ContributionSourceType.FOCUS_SESSION,
            sourceId = id,
            contributedValue = value,
            effectiveTimestamp = instant,
            createdAt = instant,
            updatedAt = instant,
        ))
    }
}
