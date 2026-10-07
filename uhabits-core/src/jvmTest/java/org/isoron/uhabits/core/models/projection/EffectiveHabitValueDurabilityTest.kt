package org.isoron.uhabits.core.models.projection

import kotlinx.coroutines.test.runTest
import org.isoron.platform.io.JavaDatabaseOpener
import org.isoron.platform.io.TestDatabaseHelper
import org.isoron.platform.io.getVersion
import org.isoron.platform.io.migrateTo
import org.isoron.platform.io.queryLong
import org.isoron.platform.io.setVersion
import org.isoron.platform.time.getToday
import org.isoron.platform.time.startOfLocalDay
import org.isoron.uhabits.core.BaseUnitTest
import org.isoron.uhabits.core.LEGACY_DATABASE_VERSION
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.contribution.Contribution
import org.isoron.uhabits.core.contribution.ContributionId
import org.isoron.uhabits.core.contribution.ContributionSourceType
import org.isoron.uhabits.core.contribution.sqlite.ContributionSchema
import org.isoron.uhabits.core.contribution.sqlite.SQLiteContributionRepository
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.HabitType
import org.isoron.uhabits.core.models.sqlite.SQLModelFactory
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class EffectiveHabitValueDurabilityTest : BaseUnitTest() {
    @Test
    fun reopenedSqliteProjectionDoesNotMaterializeOrQueueCredit() = runTest {
        val path = Files.createTempFile("habit-projection-", ".db")
        val date = getToday()
        val timestamp = startOfLocalDay(date) + 1000
        val id = ContributionId("durable-projection")
        var habitUuid = ""
        try {
            val first = JavaDatabaseOpener().open(path.toString())
            try {
                first.setVersion(8)
                first.migrateTo(LEGACY_DATABASE_VERSION) { TestDatabaseHelper.loadMigrationSQL(it) }
                ContributionSchema.createSchema(first)
                val factory = SQLModelFactory(first)
                val habit = factory.buildHabit().apply { type = HabitType.NUMERICAL; unit = "min"; targetValue = 45.0 }
                factory.buildHabitList().add(habit)
                habitUuid = assertNotNull(habit.uuid)
                habit.originalEntries.add(Entry(date, 12000, "manual"))
                val facts = SQLiteContributionRepository(first)
                facts.record(Contribution(id, HabitRef(habitUuid), ContributionSourceType.FOCUS_SESSION,
                    "source", 33000L, timestamp, timestamp, timestamp))
                val before = factory.entryRepository.findAllByHabitId(assertNotNull(habit.id))
                val queue = first.queryLong("SELECT COUNT(*) FROM SyncQueue")
                val ops = first.queryLong("SELECT COUNT(*) FROM EntryOps")
                val projected = EffectiveHabitValueService(facts).valueForDay(habit, date)
                assertEquals(45000L, projected.totalValue)
                assertEquals(before, factory.entryRepository.findAllByHabitId(habit.id!!))
                assertEquals(queue, first.queryLong("SELECT COUNT(*) FROM SyncQueue"))
                assertEquals(ops, first.queryLong("SELECT COUNT(*) FROM EntryOps"))
                assertEquals(29, first.getVersion())
            } finally { first.close() }

            val reopened = JavaDatabaseOpener().open(path.toString())
            try {
                val factory = SQLModelFactory(reopened)
                val habit = assertNotNull(factory.buildHabitList().getByUUID(habitUuid))
                val facts = SQLiteContributionRepository(reopened)
                val service = EffectiveHabitValueService(facts)
                assertEquals(45000L, service.valueForDay(habit, date).totalValue)
                habit.originalEntries.add(Entry(date, 20000, "edited manual"))
                assertEquals(53000L, service.valueForDay(habit, date).totalValue)
                facts.removeById(id, timestamp + 1000)
                assertEquals(20000L, service.valueForDay(habit, date).totalValue)
                assertEquals("edited manual", habit.originalEntries.get(date).notes)
                assertEquals(1L, reopened.queryLong("SELECT COUNT(*) FROM Repetitions WHERE deleted_at IS NULL"))
                habit.originalEntries.add(Entry(date, Entry.UNKNOWN))
                assertEquals(0L, reopened.queryLong("SELECT COUNT(*) FROM Repetitions WHERE deleted_at IS NULL"))
                assertEquals(timestamp + 1000, assertNotNull(facts.getById(id)).removedAt)
            } finally { reopened.close() }
        } finally { Files.deleteIfExists(path) }
    }
}
