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

/** Fresh committed snapshots must not turn an unrelated revision into a visual row change. */
class HabitCardListCacheBindingInvalidationTest : BaseUnitTest() {
    private var revision = 1L
    private val repository = MemoryContributionRepository()
    private fun habit(name: String): Habit = modelFactory.buildHabit().apply {
        uuid = name; this.name = name; type = HabitType.NUMERICAL; unit = "min"
        frequency = Frequency.DAILY; targetValue = 100.0
        recompute(); habitList.add(this)
    }
    private fun credit(habit: Habit, suffix: String, quantity: Long): Contribution {
        val time = startOfLocalDay(getToday()) + 1000L
        return Contribution(ContributionId("credit-$suffix"), HabitRef(habit.uuid!!),
            ContributionSourceType.FOCUS_SESSION, "focus-$suffix", quantity, time, time, time)
            .also(repository::record)
    }
    private class Changes : HabitCardListCache.Listener {
        val full = mutableListOf<Int>()
        val entries = mutableListOf<Int>()
        override fun onItemChanged(position: Int) { full.add(position) }
        override fun onEntryChanged(position: Int) { entries.add(position) }
        fun clear() { full.clear(); entries.clear() }
        fun all() = full + entries
    }
    private fun ready(changes: Changes): HabitCardListCache =
        HabitCardListCache(habitList, commandRunner, taskRunner, mock()).apply {
            setCheckmarkCount(10)
            effectiveSnapshots = { habits ->
                EffectiveHabitReadSnapshotProvider(repository).prepare(habits, getToday().minus(365), getToday(),
                    "binding-test", revision, EffectiveReadCalendarContext("UTC", DayOfWeek.MONDAY, 0, 0))
            }
            refreshAllHabits()
            setListener(changes)
        }

    @Test fun oneChangedEntryRepaintsOnlyItsRowAndPublishesEveryFreshSnapshot() {
        val changed = habit("changed")
        val unrelated = habit("unrelated")
        val changes = Changes(); val cache = ready(changes)
        val previousUnrelated = cache.getEffectiveSnapshot(unrelated.id!!)!!
        changed.originalEntries.add(Entry(getToday(), 50000)); changed.recompute()
        revision++
        cache.refreshAllHabits()
        assertEquals(listOf(0), changes.all())
        assertEquals(50000, cache.getCheckmarks(changed.id!!).first())
        assertEquals(revision, cache.getEffectiveSnapshot(changed.id!!)!!.revision)
        val freshUnrelated = cache.getEffectiveSnapshot(unrelated.id!!)!!
        assertEquals(revision, freshUnrelated.revision)
        assertNotSame(previousUnrelated, freshUnrelated)
        changes.clear(); revision++; cache.refreshAllHabits()
        assertTrue(changes.all().isEmpty(), "Unchanged rows must not repaint for revision freshness")
        assertEquals(revision, cache.getEffectiveSnapshot(unrelated.id!!)!!.revision)
    }

    @Test fun unrelatedIncompleteScoreDoesNotRepaintForNaNOrGlobalRevision() {
        val changed = habit("changed")
        val incomplete = habit("incomplete")
        credit(incomplete, "incomplete", 30000)
        val changes = Changes(); val cache = ready(changes)
        assertTrue(cache.getScore(incomplete.id!!).isNaN())
        changed.originalEntries.add(Entry(getToday(), 1000)); changed.recompute()
        revision++; cache.refreshAllHabits()
        assertEquals(listOf(0), changes.all())
        assertTrue(cache.getScore(incomplete.id!!).isNaN())
        assertEquals(revision, cache.getEffectiveSnapshot(incomplete.id!!)!!.revision)
        changes.clear(); cache.refreshAllHabits()
        assertTrue(changes.all().isEmpty(), "NaN/NaN is unchanged rendering state")
    }

    @Test fun mutableHabitTitleColorAndHistoricalGoalChangesStillCauseFullBinding() {
        val edited = habit("edited"); habit("unrelated")
        val changes = Changes(); val cache = ready(changes)
        fun mutateAndRequireFull(change: () -> Unit) {
            changes.clear(); change(); revision++; cache.refreshAllHabits()
            assertEquals(listOf(0), changes.full)
            assertTrue(changes.entries.isEmpty())
        }
        mutateAndRequireFull { edited.name = "Updated title" }
        mutateAndRequireFull { edited.color = PaletteColor(7) }
        mutateAndRequireFull {
            edited.goalHistory = mutableListOf(HabitGoal(getToday().minus(2), Frequency(1, 7),
                NumericalHabitType.AT_MOST, 70.0, "min"))
        }
    }

    @Test fun focusQuantityCoverageAndRemovalStillRepaintTheirTarget() {
        val target = habit("focus"); habit("unrelated")
        val initial = credit(target, "initial", 30000)
        val changes = Changes(); val cache = ready(changes)
        fun refreshAndCheck() {
            revision++; cache.refreshAllHabits()
            assertEquals(listOf(0), changes.all())
            assertEquals(revision, cache.getEffectiveSnapshot(target.id!!)!!.revision)
            changes.clear()
        }
        credit(target, "additional", 10000)
        refreshAndCheck()
        assertEquals(40000L, cache.getEffectiveSnapshot(target.id!!)!!.day(getToday()).totalValue)
        target.originalEntries.add(Entry(getToday(), 0)); target.recompute()
        refreshAndCheck()
        assertTrue(cache.getEffectiveSnapshot(target.id!!)!!.day(getToday()).hasCompleteCoverage)
        repository.removeById(initial.id, startOfLocalDay(getToday()) + 2000L)
        refreshAndCheck()
        val day = cache.getEffectiveSnapshot(target.id!!)!!.day(getToday())
        assertEquals(10000L, day.totalValue)
        assertEquals(1, day.tombstonedContributions.size)
    }
}
