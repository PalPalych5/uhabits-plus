package org.isoron.uhabits.core.contribution

import kotlinx.coroutines.test.runTest
import org.isoron.platform.io.Database
import org.isoron.platform.io.PreparedStatement
import org.isoron.platform.io.TestDatabaseHelper
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.contribution.memory.MemoryContributionRepository
import org.isoron.uhabits.core.contribution.sqlite.ContributionSchema
import org.isoron.uhabits.core.contribution.sqlite.SQLiteContributionRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContributionBatchQueryTest {
    private fun fact(id: String, habit: HabitRef, timestamp: Long, value: Long = 1000) = Contribution(
        id = ContributionId(id), targetHabit = habit,
        sourceType = ContributionSourceType.FOCUS_SESSION, sourceId = "source-$id",
        contributedValue = value, effectiveTimestamp = timestamp, createdAt = 0, updatedAt = 0,
    )

    private fun verifyBoundaries(repo: ContributionRepository) {
        val a = HabitRef("a")
        val b = HabitRef("b")
        val other = HabitRef("other")
        val facts = listOf(
            fact("z-start", a, 10, 0), fact("a-start", b, 10),
            fact("end", b, 20), fact("removed", a, 15),
            fact("before", a, 9), fact("after", b, 21), fact("other", other, 15),
        )
        facts.reversed().forEach(repo::record)
        repo.removeById(ContributionId("removed"), 25)
        val requested = setOf(a, b)
        assertEquals(listOf("a-start", "z-start", "end"),
            repo.getByHabitsInRange(requested, 10, 20).map { it.id.value })
        assertEquals(0L, repo.getByHabitsInRange(requested, 10, 20)[1].contributedValue)
        assertEquals(listOf("a-start", "z-start", "removed", "end"),
            repo.getByHabitsInRange(requested, 10, 20, true).map { it.id.value })
        assertEquals(2, repo.getByHabitsInRange(requested, 10, 10).size)
        assertTrue(repo.getByHabitsInRange(emptySet(), 10, 20).isEmpty())
        assertTrue(repo.getByHabitsInRange(requested, 20, 10).isEmpty())
        assertTrue(repo.getByHabitsInRange(setOf(HabitRef("missing")), 10, 20).isEmpty())
        assertEquals(7, repo.getAll(true).size)
    }

    @Test fun memoryBoundaries() = verifyBoundaries(MemoryContributionRepository())

    @Test fun sqliteBoundaries() = runTest {
        val db = TestDatabaseHelper.createEmptyDatabase()
        try {
            ContributionSchema.createSchema(db)
            verifyBoundaries(SQLiteContributionRepository(db))
        } finally { db.close() }
    }

    @Test fun sqliteBatchesLargeHabitSetsAndMatchesMemory() = runTest {
        val db = TestDatabaseHelper.createEmptyDatabase()
        try {
            ContributionSchema.createSchema(db)
            val countingDb = CountingDatabase(db)
            val sqlite = SQLiteContributionRepository(countingDb)
            val memory = MemoryContributionRepository()
            val habits = (0 until 805).map { HabitRef("habit-$it") }.toSet()
            habits.forEachIndexed { index, habit ->
                val contribution = fact("id-$index", habit, (index % 5).toLong(), index.toLong())
                sqlite.record(contribution)
                memory.record(contribution)
            }
            countingDb.queries.clear()
            assertEquals(memory.getByHabitsInRange(habits, 0, 4), sqlite.getByHabitsInRange(habits, 0, 4))
            assertEquals(3, countingDb.queries.size)
            assertEquals(listOf(402, 402, 7), countingDb.queries.map { sql -> sql.count { it == '?' } })
            assertTrue(countingDb.queries.all { "target_habit_uuid IN" in it && "BETWEEN" in it })
            countingDb.queries.clear()
            assertTrue(sqlite.getByHabitsInRange(emptySet(), 0, 4).isEmpty())
            assertTrue(sqlite.getByHabitsInRange(habits, 4, 0).isEmpty())
            assertTrue(countingDb.queries.isEmpty())
        } finally { db.close() }
    }

    private class CountingDatabase(private val delegate: Database) : Database {
        val queries = mutableListOf<String>()
        override fun prepareStatement(sql: String): PreparedStatement {
            queries.add(sql)
            return delegate.prepareStatement(sql)
        }
        override fun close() = delegate.close()
    }
}
