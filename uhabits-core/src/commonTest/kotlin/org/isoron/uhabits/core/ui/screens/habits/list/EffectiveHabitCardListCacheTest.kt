package org.isoron.uhabits.core.ui.screens.habits.list

import dev.mokkery.mock
import org.isoron.platform.time.*
import org.isoron.uhabits.core.BaseUnitTest
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.contribution.*
import org.isoron.uhabits.core.contribution.memory.MemoryContributionRepository
import org.isoron.uhabits.core.models.*
import org.isoron.uhabits.core.models.projection.*
import kotlin.test.*

class EffectiveHabitCardListCacheTest : BaseUnitTest() {
    private fun numerical(name: String, value: Int? = null): Habit = modelFactory.buildHabit().apply {
        uuid = name; this.name = name; type = HabitType.NUMERICAL; unit = "min"
        frequency = Frequency.DAILY; targetValue = 100.0
        if (value != null) originalEntries.add(Entry(getToday(), value))
        recompute(); habitList.add(this)
    }
    private fun cache(repo: MemoryContributionRepository? = null): HabitCardListCache =
        HabitCardListCache(habitList, commandRunner, taskRunner, mock()).apply {
            setCheckmarkCount(10)
            if (repo != null) effectiveSnapshots = { habits ->
                EffectiveHabitReadSnapshotProvider(repo).prepare(habits, getToday().minus(365), getToday(),
                    "test", 1, EffectiveReadCalendarContext("UTC", DayOfWeek.MONDAY, 0, 0))
            }
        }
    private fun ids(cache: HabitCardListCache) = (0 until cache.habitCount).map { cache.getHabitByPosition(it)!!.uuid }
    private fun credit(repo: MemoryContributionRepository, h: Habit, value: Long): Contribution {
        val timestamp = startOfLocalDay(getToday()) + 1000
        return Contribution(ContributionId("credit-${h.uuid}"), HabitRef(h.uuid!!),
            ContributionSourceType.FOCUS_SESSION, "focus-${h.uuid}", value, timestamp, timestamp, timestamp)
            .also { repo.record(it) }
    }

    @Test fun noContributionOrderFiltersQuantitiesAndScoresHaveExactLegacyParity() {
        numerical("A", 10000); numerical("B", 100000); numerical("C", Entry.SKIP); numerical("D")
        val boolean = fixtures.createShortHabit(); boolean.uuid = "boolean"; habitList.add(boolean)
        val legacy = cache(); val effective = cache(MemoryContributionRepository())
        for (order in listOf(HabitList.Order.BY_SCORE_DESC, HabitList.Order.BY_SCORE_ASC,
            HabitList.Order.BY_STATUS_DESC, HabitList.Order.BY_STATUS_ASC, HabitList.Order.BY_NAME_ASC)) {
            legacy.primaryOrder = order; effective.primaryOrder = order
            for (filter in listOf(HabitMatcher(), HabitMatcher(isCompletedAllowed = false), HabitMatcher(isEnteredAllowed = false))) {
                legacy.setFilter(filter); effective.setFilter(filter)
                legacy.refreshAllHabits(); effective.refreshAllHabits()
                assertEquals(ids(legacy), ids(effective), "$order / $filter")
                for (i in 0 until legacy.habitCount) {
                    val id = legacy.getHabitByPosition(i)!!.id!!
                    assertEquals(legacy.getScore(id), effective.getScore(id))
                    assertContentEquals(legacy.getCheckmarks(id), effective.getCheckmarks(id))
                }
            }
        }
    }

    @Test fun secondaryScoreDoesNotOverridePrimaryName() {
        numerical("A", 100000); numerical("B", 10000)
        val legacy = cache(); val effective = cache(MemoryContributionRepository())
        legacy.primaryOrder = HabitList.Order.BY_NAME_ASC; effective.primaryOrder = HabitList.Order.BY_NAME_ASC
        legacy.secondaryOrder = HabitList.Order.BY_SCORE_DESC; effective.secondaryOrder = HabitList.Order.BY_SCORE_DESC
        legacy.refreshAllHabits(); effective.refreshAllHabits()
        assertEquals(ids(legacy), ids(effective))
    }

    @Test fun automaticLowerBoundUsesCompatibilityCompletionAndEnteredFiltering() {
        val reached = numerical("reached"); val incomplete = numerical("incomplete"); numerical("untouched")
        val repo = MemoryContributionRepository(); credit(repo, reached, 100000); credit(repo, incomplete, 30000)
        val effective = cache(repo)
        effective.setFilter(HabitMatcher(isCompletedAllowed = false)); effective.refreshAllHabits()
        assertEquals(setOf("incomplete", "untouched"), ids(effective).toSet())
        effective.setFilter(HabitMatcher(isEnteredAllowed = false)); effective.refreshAllHabits()
        assertEquals(listOf("untouched"), ids(effective))
        assertEquals(Entry.UNKNOWN, reached.originalEntries.get(getToday()).value)
        assertEquals(Entry.UNKNOWN, incomplete.originalEntries.get(getToday()).value)
    }

    @Test fun removingReadCapabilityClearsOldSnapshots() {
        val h = numerical("habit", 30000); val repo = MemoryContributionRepository(); credit(repo, h, 60000)
        val effective = cache(repo); effective.refreshAllHabits()
        assertNotNull(effective.getEffectiveSnapshot(h.id!!))
        effective.effectiveSnapshots = null; effective.refreshAllHabits()
        assertNull(effective.getEffectiveSnapshot(h.id!!))
        assertEquals(h.scores[getToday()].value, effective.getScore(h.id!!))
    }

    @Test fun noCreditBooleanAutoTodayRemainsEntered() {
        val h = modelFactory.buildHabit().apply {
            uuid = "auto-today"; name = "auto-today"; type = HabitType.YES_NO
            originalEntries.add(Entry(getToday().minus(1), Entry.YES_MANUAL))
            computedEntries.add(Entry(getToday(), Entry.YES_AUTO))
            computedEntries.add(Entry(getToday().minus(1), Entry.YES_MANUAL))
        }
        habitList.add(h)
        assertTrue(h.isEnteredToday())
        assertEquals(Entry.UNKNOWN, h.originalEntries.get(getToday()).value)
        val legacy = cache(); val effective = cache(MemoryContributionRepository())
        legacy.setFilter(HabitMatcher(isEnteredAllowed = false)); effective.setFilter(HabitMatcher(isEnteredAllowed = false))
        legacy.refreshAllHabits(); effective.refreshAllHabits()
        assertEquals(ids(legacy), ids(effective))
        assertEquals(0, effective.habitCount)
    }
}
