package org.isoron.uhabits.core.models

import kotlinx.coroutines.test.runTest
import org.isoron.platform.time.getToday
import org.isoron.uhabits.core.BaseUnitTest
import org.isoron.uhabits.core.commands.CreateRepetitionCommand
import org.isoron.uhabits.core.models.memory.MemoryHabitList
import org.isoron.uhabits.core.models.sqlite.SQLModelFactory
import org.isoron.uhabits.core.models.sqlite.SQLiteHabitList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class SQLiteHabitListEntryChangeTest : BaseUnitTest() {
    @Test
    fun commandDeliversWrapperAndFilteredNotificationsWithPersistedEntryParity() = runTest {
        val db = buildMemoryDatabase()
        try {
            val factory = SQLModelFactory(db)
            val list = SQLiteHabitList(factory)
            val habit = factory.buildHabit().apply { name = "Daily" }
            list.add(habit)
            val child = list.getFiltered(HabitMatcher(isCompletedAllowed = false))
            val deliveries = mutableListOf<String>()
            child.observable.addListener { deliveries.add("child:${child.size()}") }
            list.observable.addListener {
                deliveries.add("wrapper:${habit.originalEntries.get(getToday()).value}")
            }
            CreateRepetitionCommand(list, habit, getToday(), Entry.YES_MANUAL, "persisted note").run()
            assertEquals(listOf("child:0", "wrapper:${Entry.YES_MANUAL}"), deliveries)
            val fresh = SQLiteHabitList(factory).getById(habit.id!!)!!
            assertEquals(Entry(getToday(), Entry.YES_MANUAL, "persisted note"), fresh.originalEntries.get(getToday()))
            assertEquals(habit.isCompletedToday(), fresh.isCompletedToday())
            assertEquals(habit.scores[getToday()], fresh.scores[getToday()])
            assertEquals(list.map { it.uuid }, SQLiteHabitList(factory).map { it.uuid })
        } finally { db.close() }
    }

    @Test
    fun actualTransactionRollbackThenReloadRestoresEntryMembershipAndNewIdentity() = runTest {
        val db = buildMemoryDatabase()
        try {
            val factory = SQLModelFactory(db)
            val list = SQLiteHabitList(factory)
            val habit = factory.buildHabit().apply { name = "Rollback" }
            list.add(habit)
            val child = list.getFiltered(HabitMatcher(isCompletedAllowed = false))
            db.beginTransaction()
            try {
                CreateRepetitionCommand(list, habit, getToday(), Entry.YES_MANUAL, "uncommitted").run()
                assertEquals(emptyList(), child.toList())
            } finally {
                // No setTransactionSuccessful: execute the real SQLite rollback.
                db.endTransaction()
            }
            // Existing reload is the recovery boundary: onEntriesChanged alone is
            // not expected to repair entry caches after an external rollback.
            list.reload()
            val restored = list.getById(habit.id!!)!!
            list.onEntriesChanged()
            assertNotSame(habit, restored)
            assertEquals(Entry.UNKNOWN, restored.originalEntries.get(getToday()).value)
            assertEquals(false, restored.isCompletedToday())
            assertSame(restored, child.single())
            assertEquals(Entry.UNKNOWN, SQLiteHabitList(factory).getById(habit.id!!)!!.originalEntries.get(getToday()).value)
        } finally { db.close() }
    }

    @Test
    fun separateSQLiteDatasetsWithIdenticalHabitUuidDeliverOnlyTheirOwnUpdates() = runTest {
        val oldDb = buildMemoryDatabase()
        val activeDb = buildMemoryDatabase()
        try {
            val oldFactory = SQLModelFactory(oldDb)
            val activeFactory = SQLModelFactory(activeDb)
            val old = SQLiteHabitList(oldFactory)
            val active = SQLiteHabitList(activeFactory)
            val oldHabit = oldFactory.buildHabit().apply { uuid = "same-logical-id"; name = "Old" }
            val activeHabit = activeFactory.buildHabit().apply { uuid = "same-logical-id"; name = "Active" }
            old.add(oldHabit); active.add(activeHabit)
            val oldChild = old.getFiltered(HabitMatcher(isCompletedAllowed = false)) as MemoryHabitList
            oldChild.detachFromParent()
            val activeChild = active.getFiltered(HabitMatcher(isCompletedAllowed = false))
            var activeCalls = 0
            active.observable.addListener { activeCalls++ }
            CreateRepetitionCommand(old, oldHabit, getToday(), Entry.YES_MANUAL, "old dataset").run()
            assertEquals(0, activeCalls)
            assertSame(activeHabit, activeChild.single())
            assertEquals(Entry.UNKNOWN, activeHabit.originalEntries.get(getToday()).value)
            CreateRepetitionCommand(active, activeHabit, getToday(), Entry.YES_MANUAL, "active dataset").run()
            assertEquals(1, activeCalls)
            assertEquals(emptyList(), activeChild.toList())
            assertSame(oldHabit, oldChild.single())
            assertEquals("old dataset", SQLiteHabitList(oldFactory).getById(oldHabit.id!!)!!.originalEntries.get(getToday()).notes)
            assertEquals("active dataset", SQLiteHabitList(activeFactory).getById(activeHabit.id!!)!!.originalEntries.get(getToday()).notes)
        } finally {
            oldDb.close()
            activeDb.close()
        }
    }
}
