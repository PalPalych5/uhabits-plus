package org.isoron.uhabits.core.models

import kotlinx.coroutines.runBlocking
import org.isoron.platform.io.Database
import org.isoron.platform.io.PreparedStatement
import org.isoron.platform.io.StepResult
import org.isoron.platform.time.getToday
import org.isoron.uhabits.core.BaseUnitTest
import org.isoron.uhabits.core.database.EntryData
import org.isoron.uhabits.core.database.HabitData
import org.isoron.uhabits.core.database.HabitExtensionData
import org.isoron.uhabits.core.models.sqlite.SQLModelFactory
import org.isoron.uhabits.core.models.sqlite.SQLiteHabitList
import org.isoron.uhabits.core.models.memory.MemoryHabitList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SQLiteHabitHydrationTest : BaseUnitTest() {
    private fun seed(db: Database) {
        val factory = SQLModelFactory(db)
        repeat(6) { index ->
            val id = factory.habitRepository.insert(HabitData(
                name = "Habit ${5 - index}", uuid = "hydrate-$index", position = index,
                type = index % 2, targetValue = 10.0, color = index % 2, archived = if (index == 5) 1 else 0
            ))
            factory.habitExtensionRepository.upsert(HabitExtensionData(id, DayTier.entries[index % 4].name))
            for ((offset, value) in listOf(30 to if (index % 2 == 0) Entry.YES_MANUAL else 5000,
                0 to listOf(Entry.SKIP, 12000, Entry.UNKNOWN, 0, Entry.YES_MANUAL, 9000)[index])) {
                factory.entryRepository.insert(EntryData(habitId = id, uuid = "row-$index-$offset",
                    timestamp = getToday().minus(offset).unixTime, value = value, notes = "note-$index"))
            }
        }
    }

    private fun state(list: HabitList): List<String> = list.map { habit ->
        val today = getToday()
        listOf(habit.uuid, habit.position, habit.type, habit.dayTier, habit.isArchived,
            habit.originalEntries.get(today), habit.computedEntries.get(today),
            habit.scores[today].value, habit.streaks.getBest(10)).joinToString("|")
    }

    @Test fun coldHydrationMatchesIncrementalSortingForEveryPrimaryAndSecondaryOrder() = runBlocking {
        val db = buildMemoryDatabase()
        try {
            seed(db)
            for (primary in HabitList.Order.entries) for (secondary in HabitList.Order.entries) {
                val eager = SQLiteHabitList(SQLModelFactory(db))
                assertEquals(6, eager.size())
                // Baseline cold loading inserts records in position order under
                // the requested comparator. Sorting first under another order
                // changes stable ties and is not a valid reference sequence.
                val reference = MemoryHabitList().apply {
                    primaryOrder = primary
                    secondaryOrder = secondary
                }
                eager.forEach { reference.add(it) }
                val cold = SQLiteHabitList(SQLModelFactory(db))
                cold.primaryOrder = primary
                cold.secondaryOrder = secondary
                assertEquals(state(reference), state(cold), "$primary/$secondary")
                assertEquals(state(reference.getFiltered(HabitMatcher())), state(cold.getFiltered(HabitMatcher())))
            }
        } finally { db.close() }
    }

    @Test fun observedReloadRetainsSortedPrefixNotificationsAndLiveUpdates() = runBlocking {
        val db = buildMemoryDatabase()
        try {
            seed(db)
            val list = SQLiteHabitList(SQLModelFactory(db))
            val child = list.getFiltered(HabitMatcher(isArchivedAllowed = true))
            val sizes = mutableListOf<Int>()
            child.observable.addListener {
                sizes.add(child.size())
                assertEquals(child.map { it.position }.sorted(), child.map { it.position })
            }
            list.reload()
            assertEquals(6, list.size())
            assertEquals(listOf(5, 4, 3, 2, 1, 0, 0, 0, 1, 2, 3, 4, 5, 6), sizes)
            sizes.clear()
            list.add(SQLModelFactory(db).buildHabit().apply { name = "Live" })
            assertEquals(listOf(7), sizes)
            assertEquals("Live", child.getByPosition(6).name)
        } finally { db.close() }
    }

    @Test fun reentrantWrapperListenerCanSubscribeBeforeFirstHydratedHabit() = runBlocking {
        val db = buildMemoryDatabase()
        try {
            seed(db)
            val list = SQLiteHabitList(SQLModelFactory(db))
            var child: HabitList? = null
            val sizes = mutableListOf<Int>()
            list.observable.addListener {
                if (child == null) {
                    child = list.getFiltered(HabitMatcher(isArchivedAllowed = true))
                    child!!.observable.addListener { sizes.add(child!!.size()) }
                }
            }
            assertEquals(6, list.size())
            assertEquals(listOf(1, 2, 3, 4, 5, 6), sizes)
        } finally { db.close() }
    }

    @Test fun coldHydrationSerializesConcurrentReadsAndFilteredSubscriptions() = runBlocking {
        val db = buildMemoryDatabase()
        try {
            seed(db)
            val entered = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            val errors = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
            val blocking = object : Database by db {
                override fun prepareStatement(sql: String): PreparedStatement {
                    val statement = db.prepareStatement(sql)
                    if (!sql.contains("FROM HabitExtensions WHERE habit_id")) return statement
                    return object : PreparedStatement by statement {
                        override fun step(): StepResult {
                            entered.countDown()
                            check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                            return statement.step()
                        }
                    }
                }
            }
            val list = SQLiteHabitList(SQLModelFactory(blocking))
            val loader = kotlin.concurrent.thread {
                try { assertEquals(6, list.size()) } catch (error: Throwable) { errors.add(error) }
            }
            check(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val attempting = java.util.concurrent.CountDownLatch(1)
            val subscribed = java.util.concurrent.CountDownLatch(1)
            val reader = kotlin.concurrent.thread {
                try {
                    attempting.countDown()
                    val child = list.getFiltered(HabitMatcher(isArchivedAllowed = true))
                    assertEquals(6, child.size())
                    assertEquals(6, list.toList().size)
                } catch (error: Throwable) { errors.add(error) }
                finally { subscribed.countDown() }
            }
            try {
                check(attempting.await(5, java.util.concurrent.TimeUnit.SECONDS))
                kotlin.test.assertFalse(subscribed.await(100, java.util.concurrent.TimeUnit.MILLISECONDS))
            } finally { release.countDown() }
            loader.join(5000)
            reader.join(5000)
            kotlin.test.assertFalse(loader.isAlive)
            kotlin.test.assertFalse(reader.isAlive)
            assertEquals(emptyList(), errors)
        } finally { db.close() }
    }

    @Test fun failedHydrationRetainsSortedPartialPrefixAndExplicitReloadRecovers() = runBlocking {
        val db = buildMemoryDatabase()
        try {
            seed(db)
            var fail = true
            val broken = object : Database by db {
                override fun prepareStatement(sql: String): PreparedStatement {
                    val statement = db.prepareStatement(sql)
                    if (!sql.contains("FROM HabitExtensions WHERE habit_id")) return statement
                    return object : PreparedStatement by statement {
                        private var habitId = 0L
                        override fun bindLong(index: Int, value: Long) { habitId = value; statement.bindLong(index, value) }
                        override fun step(): StepResult {
                            if (fail && habitId == 4L) throw IllegalStateException("synthetic hydration failure")
                            return statement.step()
                        }
                    }
                }
            }
            val list = SQLiteHabitList(SQLModelFactory(broken))
            list.primaryOrder = HabitList.Order.BY_NAME_ASC
            assertFailsWith<IllegalStateException> { list.size() }
            assertEquals(listOf("Habit 3", "Habit 4", "Habit 5"), list.map { it.name })
            fail = false
            list.reload()
            assertEquals(6, list.size())
            assertEquals(list.map { it.name }.sorted(), list.map { it.name })
        } finally { db.close() }
    }
}
