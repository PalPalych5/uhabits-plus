package org.isoron.uhabits.qa

import androidx.test.platform.app.InstrumentationRegistry
import org.isoron.platform.time.getToday
import org.isoron.platform.time.startOfLocalDay
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.contribution.sqlite.SQLiteContributionRepository
import org.isoron.uhabits.core.focus.*
import org.isoron.uhabits.core.focus.sqlite.SQLiteFocusSessionRepository
import org.isoron.uhabits.core.models.*
import org.isoron.uhabits.core.models.projection.EffectiveHabitValueService
import org.isoron.uhabits.core.rules.*
import org.isoron.uhabits.core.rules.sqlite.SQLiteSourceRuleRepository
import org.isoron.uhabits.inject.HabitsApplicationComponent
import org.isoron.uhabits.utils.DemoDataGenerator
import org.junit.Assert.*

/** Call before changing the guarded fixture to production UI mode. Generator enforces all write guards. */
object ProductionUiHotfixFixture {
    fun prepare(component: HabitsApplicationComponent) {
        val ins = InstrumentationRegistry.getInstrumentation()
        check(component === HabitsApplication.component && component.session.isExperimental)
        val today = getToday()
        // Existing marker, isolated filename, offline capabilities and no active timer are
        // validated by the established generator before any destructive QA reset.
        DemoDataGenerator.generate(ins.targetContext, component.sqlModelFactory, component.habitList,
            null, component.habitCardListCache, true, today)
        ins.runOnMainSync {
            fun configure(name: String, unit: String, goal: Double, title: String = name): Habit {
                val habit = component.habitList.toList().first { it.name == name }
                habit.name = title
                habit.type = HabitType.NUMERICAL
                habit.unit = unit
                habit.targetValue = goal
                habit.targetType = NumericalHabitType.AT_LEAST
                habit.frequency = Frequency.DAILY
                habit.timerEnabled = unit == "мин"
                habit.goalHistory = mutableListOf(HabitGoal(today.minus(181), Frequency.DAILY,
                    NumericalHabitType.AT_LEAST, goal, unit))
                component.habitList.update(habit)
                return habit
            }
            val meditation = configure("Медитация", "мин", 10.0)
            val ordinary = configure("Велосипед", "мин", 20.0)
            val water = configure("Пить воду", "мл", 2000.0)
            val longTitle = configure("Ревью кода", "мин", 15.0, "Развитие голоса и речи")
            for (offset in 0..4) {
                meditation.originalEntries.add(Entry(today.minus(offset), when (offset) {
                    2 -> 5300; 3 -> Entry.SKIP; 4 -> 0; else -> Entry.UNKNOWN
                }))
                ordinary.originalEntries.add(Entry(today.minus(offset), 0))
                water.originalEntries.add(Entry(today.minus(offset), if (offset == 0) 0 else 2000000))
                longTitle.originalEntries.add(Entry(today.minus(offset), if (offset == 1) 34500 else Entry.UNKNOWN))
            }
            val end = startOfLocalDay(today) + 8 * 3600000L
            val fact = FocusSession(FocusSessionId("qa-hotfix-meditation-short"), end - 3720L, end, 3720L,
                habit = HabitRef(meditation.uuid!!), source = FocusSessionSource.TIMER,
                deviceId = "disposable-qa", createdAt = end - 3720L, updatedAt = end)
            val facts = SQLiteFocusSessionRepository(component.db)
            val ledger = SQLiteContributionRepository(component.db)
            facts.record(fact)
            val service = SourceRuleAttributionService(SQLiteSourceRuleRepository(component.db), ledger,
                habitResolver = { ref -> component.habitList.getByUUID(ref.uuid)?.let {
                    TargetHabitInfo(it.isNumerical, it.unit, it.name)
                } })
            assertTrue((service.attributeSession(fact).single() as AttributionOutcome.Attributed).wasInserted)
            assertFalse((service.attributeSession(fact).single() as AttributionOutcome.Attributed).wasInserted)
            assertEquals(1, facts.getAll().count { it.id == fact.id })
            assertEquals(1, ledger.getAll().count { it.sourceId == fact.id.value })
            val longTitleFact = fact.copy(id = FocusSessionId("qa-hotfix-long-title"),
                startedAt = end - 60000L, durationMs = 60000L,
                habit = HabitRef(longTitle.uuid!!), createdAt = end - 60000L)
            facts.record(longTitleFact)
            assertTrue((service.attributeSession(longTitleFact).single() as AttributionOutcome.Attributed).wasInserted)
            val value = EffectiveHabitValueService(ledger).valueForDay(meditation, today)
            assertEquals(62L, value.totalValue)
            assertFalse(value.hasCompleteCoverage)
            assertEquals(Entry.UNKNOWN, meditation.originalEntries.get(today).value)
            assertEquals(Entry.SKIP, meditation.originalEntries.get(today.minus(3)).value)
            listOf(meditation, ordinary, water, longTitle).forEach { it.recompute() }
        }
    }
}
