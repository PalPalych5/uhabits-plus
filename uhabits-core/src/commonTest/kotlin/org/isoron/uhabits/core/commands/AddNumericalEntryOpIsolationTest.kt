package org.isoron.uhabits.core.commands

import kotlinx.coroutines.test.runTest
import org.isoron.platform.io.TestDatabaseHelper
import org.isoron.platform.io.queryLong
import org.isoron.platform.io.run
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.setToday
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.HabitType
import org.isoron.uhabits.core.models.sqlite.SQLModelFactory
import org.isoron.uhabits.core.models.projection.EffectiveReadDatabase
import org.isoron.uhabits.core.models.projection.EffectiveReadRevision
import kotlin.test.Test
import kotlin.test.assertEquals

class AddNumericalEntryOpIsolationTest {
    @Test
    fun legacyTimerDeltaUsesPersistedManualBaselineAndRollbackRestoresIt() = runTest {
        setToday(LocalDate(2026, 10, 6))
        val revision = EffectiveReadRevision("manual-isolation")
        val db = EffectiveReadDatabase(TestDatabaseHelper.createEmptyDatabase(), revision)
        try {
            val factory = SQLModelFactory(db)
            val habits = factory.buildHabitList()
            val habit = factory.buildHabit().apply { type = HabitType.NUMERICAL }
            habits.add(habit)
            revision.addRollbackListener {
                habit.originalEntries.invalidate()
                habit.recompute()
            }
            val date = LocalDate(2026, 10, 6)
            habit.originalEntries.add(Entry(date, 30000, "manual"))
            habit.recompute()
            habit.computedEntries.add(Entry(date, 90000, "derived"))
            AddNumericalEntryOpCommand(habits, habit, date, 10000, "manual").run()
            assertEquals(40000, habit.originalEntries.get(date).value)
            assertEquals(40000L, db.queryLong("SELECT value FROM Repetitions WHERE habit = ${habit.id} AND deleted_at IS NULL"))
            val operations = db.queryLong("SELECT count(*) FROM EntryOps")
            AddNumericalEntryOpCommand(habits, habit, date, 0, "ignored").run()
            assertEquals(operations, db.queryLong("SELECT count(*) FROM EntryOps"))
            val beforeRollback = revision.value
            db.run("BEGIN")
            habit.computedEntries.add(Entry(date, 100000, "derived"))
            AddNumericalEntryOpCommand(habits, habit, date, 10000, "manual").run()
            db.run("ROLLBACK")
            assertEquals(40000L, db.queryLong("SELECT value FROM Repetitions WHERE habit = ${habit.id} AND deleted_at IS NULL"))
            assertEquals(operations, db.queryLong("SELECT count(*) FROM EntryOps"))
            assertEquals(beforeRollback, revision.value)
            assertEquals(40000, habit.originalEntries.get(date).value)
            AddNumericalEntryOpCommand(habits, habit, date, 10000, "manual").run()
            assertEquals(50000, habit.originalEntries.get(date).value)
            assertEquals(50000L, db.queryLong("SELECT value FROM Repetitions WHERE habit = ${habit.id} AND deleted_at IS NULL"))
        } finally { db.close() }
    }
}
