package org.isoron.uhabits.core.database

import kotlinx.coroutines.test.runTest
import org.isoron.platform.io.TestDatabaseHelper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

/** Repositories reuse statements: lock the complete reset/bind/step/read operation. */
class RepositoryConcurrencyTest {
    @Test
    fun concurrentHabitLookupsKeepEachUuidAndRowTogether() = runTest {
        val db = TestDatabaseHelper.createEmptyDatabase()
        try {
            val repo = HabitRepository(db)
            repeat(4) { repo.insert(HabitData(name = "Habit $it", uuid = "habit-$it", position = it)) }
            concurrently { worker ->
                repeat(100) {
                    val row = repo.findByUuid("habit-$worker")!!
                    assertEquals("habit-$worker", row.uuid)
                    assertEquals("Habit $worker", row.name)
                    assertEquals(listOf(0, 1, 2, 3), repo.findAll().map { it.position })
                }
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun concurrentEntryReadsAndWritesDoNotInterleaveBindings() = runTest {
        val db = TestDatabaseHelper.createEmptyDatabase()
        try {
            val habits = HabitRepository(db)
            val ids = (0..3).map { habits.insert(HabitData(name = "Habit $it", position = it)) }
            val repo = EntryRepository(db)
            concurrently { worker ->
                repeat(100) { iteration ->
                    val uuid = "entry-$worker-$iteration"
                    repo.upsert(EntryData(habitId = ids[worker], timestamp = iteration.toLong(), value = worker, uuid = uuid))
                    assertEquals(ids[worker], repo.findByUuid(uuid)!!.habitId)
                    assertEquals(worker, repo.findByUuid(uuid)!!.value)
                    assertEquals(iteration + 1, repo.findAllByHabitId(ids[worker]).size)
                }
            }
        } finally {
            db.close()
        }
    }

    private fun concurrently(action: (Int) -> Unit) {
        val executor = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        try {
            val futures = (0..3).map { worker ->
                executor.submit {
                    check(start.await(5, TimeUnit.SECONDS))
                    action(worker)
                }
            }
            start.countDown()
            futures.forEach { it.get(15, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
    }
}
