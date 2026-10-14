/* Copyright (C) 2026 Álinson Santos Xavier and contributors. GPL-3.0-or-later. */
package org.isoron.uhabits.core.models.projection

import kotlinx.coroutines.test.runTest
import org.isoron.platform.io.*
import org.isoron.platform.time.*
import org.isoron.uhabits.core.BaseUnitTest
import org.isoron.uhabits.core.LEGACY_DATABASE_VERSION
import org.isoron.uhabits.core.commands.*
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.contribution.*
import org.isoron.uhabits.core.contribution.sqlite.ContributionSchema
import org.isoron.uhabits.core.contribution.sqlite.SQLiteContributionRepository
import org.isoron.uhabits.core.models.*
import org.isoron.uhabits.core.models.sqlite.SQLModelFactory
import org.isoron.uhabits.core.models.sqlite.SQLiteHabitList
import java.nio.file.Files
import kotlin.test.*

class IncrementalHistoricalProjectionDifferentialOracleTest : BaseUnitTest() {

    private class IncrementalCacheOracle(
        private val provider: EffectiveHabitReadSnapshotProvider,
        private val revisionTracker: EffectiveReadRevision,
    ) {
        data class CacheContext(
            val from: LocalDate,
            val to: LocalDate,
            val datasetId: String,
            val revision: Long,
            val calendar: EffectiveReadCalendarContext,
            val rollbackGeneration: Long,
            val compatibilityFirstWeekday: Int,
        )

        var cachedContext: CacheContext? = null
        val cachedSnapshots = mutableMapOf<HabitRef, EffectiveHabitReadSnapshot>()
        var rollbackGeneration: Long = 0L
        var totalPreparedCount = 0
        var totalReusedCount = 0

        fun getSnapshots(
            habits: List<Habit>,
            from: LocalDate,
            to: LocalDate,
            calendar: EffectiveReadCalendarContext,
        ): Map<HabitRef, EffectiveHabitReadSnapshot> {
            val capturedRevision = revisionTracker.observedValue
            val currentContext = CacheContext(
                from = from,
                to = to,
                datasetId = revisionTracker.datasetId,
                revision = capturedRevision,
                calendar = calendar,
                rollbackGeneration = rollbackGeneration,
                compatibilityFirstWeekday = getFirstWeekdayNumberAccordingToLocale()
            )

            if (cachedContext != currentContext) {
                val prevContext = cachedContext
                val canIncrementallyUpdate = prevContext != null &&
                    prevContext.from == currentContext.from &&
                    prevContext.to == currentContext.to &&
                    prevContext.datasetId == currentContext.datasetId &&
                    prevContext.calendar == currentContext.calendar &&
                    prevContext.rollbackGeneration == currentContext.rollbackGeneration &&
                    prevContext.compatibilityFirstWeekday == currentContext.compatibilityFirstWeekday &&
                    prevContext.revision < currentContext.revision

                val affectedHabits = if (canIncrementallyUpdate) {
                    revisionTracker.affectedHabitsBetween(prevContext!!.revision, currentContext.revision)
                } else null

                if (affectedHabits != null) {
                    for (ref in affectedHabits) {
                        cachedSnapshots.remove(ref)
                    }
                    val survivingKeys = cachedSnapshots.keys.toList()
                    for (ref in survivingKeys) {
                        val s = cachedSnapshots[ref]
                        if (s != null && s.revision != currentContext.revision) {
                            cachedSnapshots[ref] = s.withRevision(currentContext.revision)
                        }
                    }
                } else {
                    cachedSnapshots.clear()
                }
                cachedContext = currentContext
            }

            val missingHabits = habits.filter { habit ->
                val ref = habit.uuid?.let { HabitRef(it) } ?: return@filter false
                !cachedSnapshots.containsKey(ref)
            }

            if (missingHabits.isNotEmpty()) {
                val prepared = provider.prepare(
                    missingHabits, from, to, currentContext.datasetId, currentContext.revision, currentContext.calendar
                )
                cachedSnapshots.putAll(prepared)
                totalPreparedCount += prepared.size
            }
            totalReusedCount += (habits.size - missingHabits.size)

            val result = mutableMapOf<HabitRef, EffectiveHabitReadSnapshot>()
            for (habit in habits) {
                val ref = habit.uuid?.let { HabitRef(it) } ?: continue
                cachedSnapshots[ref]?.let { result[ref] = it }
            }
            return result
        }

        fun rollback() {
            rollbackGeneration++
            cachedContext = null
            cachedSnapshots.clear()
        }
    }

    private fun assertSnapshotParity(
        candidate: EffectiveHabitReadSnapshot,
        authoritative: EffectiveHabitReadSnapshot,
        from: LocalDate,
        to: LocalDate,
    ) {
        assertEquals(authoritative.habitRef, candidate.habitRef, "HabitRef mismatch")
        assertEquals(authoritative.revision, candidate.revision, "Revision mismatch")
        assertEquals(authoritative.from, candidate.from, "From bounds mismatch")
        assertEquals(authoritative.to, candidate.to, "To bounds mismatch")
        assertEquals(authoritative.scoreResult, candidate.scoreResult, "Scores mismatch")
        assertEquals(authoritative.streakResult, candidate.streakResult, "Streaks mismatch")

        var date = from
        while (date <= to) {
            val cDay = candidate.day(date)
            val aDay = authoritative.day(date)
            assertEquals(aDay.totalValue, cDay.totalValue, "Total value mismatch on $date")
            assertEquals(aDay.manualValue, cDay.manualValue, "Manual value mismatch on $date")
            assertEquals(aDay.contributionValue, cDay.contributionValue, "Contribution value mismatch on $date")
            assertEquals(aDay.isSkipped, cDay.isSkipped, "Skip mismatch on $date")
            assertEquals(aDay.manualEntry.value, cDay.manualEntry.value, "Manual entry mismatch on $date")
            assertEquals(aDay.contributions, cDay.contributions, "Contributions mismatch on $date")
            assertEquals(aDay.tombstonedContributions, cDay.tombstonedContributions, "Tombstones mismatch on $date")

            assertEquals(
                authoritative.compatibilityCompletionOn(date),
                candidate.compatibilityCompletionOn(date),
                "Compatibility completion mismatch on $date"
            )
            assertEquals(
                authoritative.goalAchievementOn(date),
                candidate.goalAchievementOn(date),
                "Goal achievement mismatch on $date"
            )
            date = date.plus(1)
        }
    }

    private data class TestRig(
        val db: Database,
        val rawDb: Database,
        val revision: EffectiveReadRevision,
        val habitList: SQLiteHabitList,
        val facts: SQLiteContributionRepository,
        val provider: EffectiveHabitReadSnapshotProvider,
        val oracle: IncrementalCacheOracle,
        val calendar: EffectiveReadCalendarContext,
        val today: LocalDate,
        val from: LocalDate,
        val habits: List<Habit>,
    )

    private suspend fun setupTestRig(): TestRig {
        val path = Files.createTempFile("oracle-differential-", ".db")
        val raw = JavaDatabaseOpener().open(path.toString())
        raw.setVersion(8)
        raw.migrateTo(LEGACY_DATABASE_VERSION) { TestDatabaseHelper.loadMigrationSQL(it) }
        ContributionSchema.createSchema(raw)

        val revision = EffectiveReadRevision("test-oracle-dataset")
        val db = EffectiveReadDatabase(raw, revision)
        val factory = SQLModelFactory(db)
        val habitList = factory.buildHabitList() as SQLiteHabitList
        val facts = SQLiteContributionRepository(db)
        val provider = EffectiveHabitReadSnapshotProvider(facts)
        val oracle = IncrementalCacheOracle(provider, revision)

        val today = LocalDate(2026, 10, 14)
        setToday(today)
        val from = today.minus(90)
        val calendar = EffectiveReadCalendarContext("local", DayOfWeek.MONDAY, 0, 0)

        // Create 5 habits of different kinds
        val h0 = factory.buildHabit().apply {
            uuid = "h-bool-daily"; name = "Daily Exercise"; type = HabitType.YES_NO
            frequency = Frequency(1, 1)
        }
        val h1 = factory.buildHabit().apply {
            uuid = "h-bool-weekly"; name = "Weekly Review"; type = HabitType.YES_NO
            frequency = Frequency(1, 7)
        }
        val h2 = factory.buildHabit().apply {
            uuid = "h-num-daily"; name = "Reading Minutes"; type = HabitType.NUMERICAL
            unit = "min"; targetValue = 30.0; frequency = Frequency(1, 1)
        }
        val h3 = factory.buildHabit().apply {
            uuid = "h-num-weekly"; name = "Study Hours"; type = HabitType.NUMERICAL
            unit = "hr"; targetValue = 10.0; frequency = Frequency(1, 7)
        }
        val h4 = factory.buildHabit().apply {
            uuid = "h-contrib-target"; name = "Deep Work"; type = HabitType.NUMERICAL
            unit = "min"; targetValue = 60.0; frequency = Frequency(1, 1)
        }

        val habits = listOf(h0, h1, h2, h3, h4)
        habits.forEach { habitList.add(it) }

        // Populate initial history for each habit across last 30 days
        for (i in 1..30) {
            val d = today.minus(i)
            if (i % 2 == 0) h0.originalEntries.add(Entry(d, Entry.YES_MANUAL))
            if (i % 7 == 0) h1.originalEntries.add(Entry(d, Entry.YES_MANUAL))
            if (i % 3 == 0) h2.originalEntries.add(Entry(d, 20000))
            if (i % 5 == 0) h3.originalEntries.add(Entry(d, 2000))
        }

        return TestRig(
            db = db,
            rawDb = raw,
            revision = revision,
            habitList = habitList,
            facts = facts,
            provider = provider,
            oracle = oracle,
            calendar = calendar,
            today = today,
            from = from,
            habits = habits,
        )
    }

    @Test
    fun scenario1_booleanHabitEdits_exactDifferentialParity() = runTest {
        val rig = setupTestRig()
        try {
            // Initial read warms cache
            val initial = rig.oracle.getSnapshots(rig.habits, rig.from, rig.today, rig.calendar)
            assertEquals(5, initial.size)
            assertEquals(5, rig.oracle.totalPreparedCount)

            // Edit boolean habit 0
            val cmd = CreateRepetitionCommand(rig.habitList, rig.habits[0], rig.today, Entry.YES_MANUAL, "done")
            cmd.run()

            // Verify affected scope is tracked
            val afterEdit = rig.oracle.getSnapshots(rig.habits, rig.from, rig.today, rig.calendar)
            // Exactly 1 prepared (habit 0), 4 reused (habits 1..4)
            assertEquals(6, rig.oracle.totalPreparedCount)
            assertEquals(4, rig.oracle.totalReusedCount)

            // Authoritative full recomputation
            val authoritative = rig.provider.prepare(
                rig.habits, rig.from, rig.today, rig.revision.datasetId, rig.revision.value, rig.calendar
            )

            for (h in rig.habits) {
                val ref = HabitRef(h.uuid!!)
                assertSnapshotParity(afterEdit.getValue(ref), authoritative.getValue(ref), rig.from, rig.today)
            }
        } finally {
            rig.db.close()
        }
    }

    @Test
    fun scenario2_numericalHabitEdits_exactDifferentialParity() = runTest {
        val rig = setupTestRig()
        try {
            rig.oracle.getSnapshots(rig.habits, rig.from, rig.today, rig.calendar)

            // Edit numerical habit 2 with AddNumericalEntryOpCommand
            val cmd = AddNumericalEntryOpCommand(rig.habitList, rig.habits[2], rig.today, 15000, "step 1")
            cmd.run()

            val afterEdit = rig.oracle.getSnapshots(rig.habits, rig.from, rig.today, rig.calendar)
            assertEquals(6, rig.oracle.totalPreparedCount) // only 1 new prep
            assertEquals(4, rig.oracle.totalReusedCount)

            val authoritative = rig.provider.prepare(
                rig.habits, rig.from, rig.today, rig.revision.datasetId, rig.revision.value, rig.calendar
            )
            for (h in rig.habits) {
                val ref = HabitRef(h.uuid!!)
                assertSnapshotParity(afterEdit.getValue(ref), authoritative.getValue(ref), rig.from, rig.today)
            }
        } finally {
            rig.db.close()
        }
    }

    @Test
    fun scenario3_historicalGoalEdits_exactDifferentialParity() = runTest {
        val rig = setupTestRig()
        try {
            rig.oracle.getSnapshots(rig.habits, rig.from, rig.today, rig.calendar)

            // Edit goal of habit 1 from 15 days ago
            val modified = rig.habits[1].copy().apply {
                frequency = Frequency(2, 7)
            }
            val cmd = EditHabitGoalCommand(
                rig.habitList,
                rig.habits[1].id!!,
                modified,
                GoalApplyScope.FROM_DATE,
                rig.today.minus(15)
            )
            cmd.run()

            val afterEdit = rig.oracle.getSnapshots(rig.habits, rig.from, rig.today, rig.calendar)
            assertEquals(6, rig.oracle.totalPreparedCount)
            assertEquals(4, rig.oracle.totalReusedCount)

            val authoritative = rig.provider.prepare(
                rig.habits, rig.from, rig.today, rig.revision.datasetId, rig.revision.value, rig.calendar
            )
            for (h in rig.habits) {
                val ref = HabitRef(h.uuid!!)
                assertSnapshotParity(afterEdit.getValue(ref), authoritative.getValue(ref), rig.from, rig.today)
            }
        } finally {
            rig.db.close()
        }
    }

    @Test
    fun scenario4_focusContributionChanges_exactDifferentialParity() = runTest {
        val rig = setupTestRig()
        try {
            rig.oracle.getSnapshots(rig.habits, rig.from, rig.today, rig.calendar)

            // Record a focus contribution for habit 4
            val contribTime = startOfLocalDay(rig.today) + 5000
            val contrib = Contribution(
                id = ContributionId("focus-contrib-1"),
                targetHabit = HabitRef(rig.habits[4].uuid!!),
                sourceType = ContributionSourceType.FOCUS_SESSION,
                sourceId = "session-1",
                contributedValue = 45000L,
                effectiveTimestamp = contribTime,
                createdAt = contribTime,
                updatedAt = contribTime
            )
            rig.facts.record(contrib)

            val afterRecord = rig.oracle.getSnapshots(rig.habits, rig.from, rig.today, rig.calendar)
            assertEquals(6, rig.oracle.totalPreparedCount)
            assertEquals(4, rig.oracle.totalReusedCount)

            val auth1 = rig.provider.prepare(
                rig.habits, rig.from, rig.today, rig.revision.datasetId, rig.revision.value, rig.calendar
            )
            for (h in rig.habits) {
                val ref = HabitRef(h.uuid!!)
                assertSnapshotParity(afterRecord.getValue(ref), auth1.getValue(ref), rig.from, rig.today)
            }

            // Remove contribution
            rig.facts.remove(ContributionSourceType.FOCUS_SESSION, "session-1", HabitRef(rig.habits[4].uuid!!), contribTime + 100)
            val afterRemove = rig.oracle.getSnapshots(rig.habits, rig.from, rig.today, rig.calendar)
            assertEquals(7, rig.oracle.totalPreparedCount)

            val auth2 = rig.provider.prepare(
                rig.habits, rig.from, rig.today, rig.revision.datasetId, rig.revision.value, rig.calendar
            )
            for (h in rig.habits) {
                val ref = HabitRef(h.uuid!!)
                assertSnapshotParity(afterRemove.getValue(ref), auth2.getValue(ref), rig.from, rig.today)
            }
        } finally {
            rig.db.close()
        }
    }

    @Test
    fun scenario5_containerOrganizationChange_triggersFullInvalidationFallback() = runTest {
        val rig = setupTestRig()
        try {
            rig.oracle.getSnapshots(rig.habits, rig.from, rig.today, rig.calendar)
            assertEquals(5, rig.oracle.totalPreparedCount)

            // Touching global invalidation table AppSettings or HabitPlacements
            rig.db.prepareStatement("INSERT OR REPLACE INTO AppSettings (key, long_value) VALUES ('first_weekday', 1)").step()

            // Revision advanced with null scope
            val afterGlobalWrite = rig.oracle.getSnapshots(rig.habits, rig.from, rig.today, rig.calendar)
            // All 5 must be recomputed due to fallback!
            assertEquals(10, rig.oracle.totalPreparedCount)
            assertEquals(0, rig.oracle.totalReusedCount)

            val authoritative = rig.provider.prepare(
                rig.habits, rig.from, rig.today, rig.revision.datasetId, rig.revision.value, rig.calendar
            )
            for (h in rig.habits) {
                val ref = HabitRef(h.uuid!!)
                assertSnapshotParity(afterGlobalWrite.getValue(ref), authoritative.getValue(ref), rig.from, rig.today)
            }
        } finally {
            rig.db.close()
        }
    }

    @Test
    fun scenario6_rollback_triggersFullInvalidationFallback() = runTest {
        val rig = setupTestRig()
        try {
            rig.oracle.getSnapshots(rig.habits, rig.from, rig.today, rig.calendar)
            assertEquals(5, rig.oracle.totalPreparedCount)

            // Trigger rollback
            rig.oracle.rollback()
            rig.revision.rolledBack()

            val afterRollback = rig.oracle.getSnapshots(rig.habits, rig.from, rig.today, rig.calendar)
            assertEquals(10, rig.oracle.totalPreparedCount)

            val authoritative = rig.provider.prepare(
                rig.habits, rig.from, rig.today, rig.revision.datasetId, rig.revision.value, rig.calendar
            )
            for (h in rig.habits) {
                val ref = HabitRef(h.uuid!!)
                assertSnapshotParity(afterRollback.getValue(ref), authoritative.getValue(ref), rig.from, rig.today)
            }
        } finally {
            rig.db.close()
        }
    }

    @Test
    fun scenario7_unknownExternalWrite_triggersFullInvalidationFallback() = runTest {
        val rig = setupTestRig()
        try {
            rig.oracle.getSnapshots(rig.habits, rig.from, rig.today, rig.calendar)
            assertEquals(5, rig.oracle.totalPreparedCount)

            // Raw SQL write on Repetitions outside withMutationScope
            val stmt = rig.db.prepareStatement(
                "INSERT INTO Repetitions (habit, timestamp, value) VALUES (?, ?, ?)"
            )
            stmt.bindLong(1, rig.habits[0].id!!)
            stmt.bindLong(2, rig.today.minus(50).unixTime)
            stmt.bindLong(3, Entry.YES_MANUAL.toLong())
            stmt.step()

            // Unknown writer scope is null -> conservative fallback clears cache
            val afterUnknownWrite = rig.oracle.getSnapshots(rig.habits, rig.from, rig.today, rig.calendar)
            assertEquals(10, rig.oracle.totalPreparedCount)

            // Reload habit records so in-memory model matches SQLite
            rig.habitList.reload()
            val authoritative = rig.provider.prepare(
                rig.habits, rig.from, rig.today, rig.revision.datasetId, rig.revision.value, rig.calendar
            )
            for (h in rig.habits) {
                val ref = HabitRef(h.uuid!!)
                assertSnapshotParity(afterUnknownWrite.getValue(ref), authoritative.getValue(ref), rig.from, rig.today)
            }
        } finally {
            rig.db.close()
        }
    }

    @Test
    fun scenario8_rapidEdits_exactDifferentialParity() = runTest {
        val rig = setupTestRig()
        try {
            rig.oracle.getSnapshots(rig.habits, rig.from, rig.today, rig.calendar)
            assertEquals(5, rig.oracle.totalPreparedCount)

            // Rapid edits to habit 0, then habit 2, then habit 0 again, without reading in between
            CreateRepetitionCommand(rig.habitList, rig.habits[0], rig.today, Entry.YES_MANUAL, "").run()
            AddNumericalEntryOpCommand(rig.habitList, rig.habits[2], rig.today, 10000, "").run()
            CreateRepetitionCommand(rig.habitList, rig.habits[0], rig.today, Entry.NO, "").run()

            // Read now: affected habits between initial and current revision are {habit0, habit2}
            val afterRapidEdits = rig.oracle.getSnapshots(rig.habits, rig.from, rig.today, rig.calendar)
            // Exactly 2 recomputed (habit 0 and habit 2), 3 reused (habits 1, 3, 4)
            assertEquals(7, rig.oracle.totalPreparedCount)
            assertEquals(3, rig.oracle.totalReusedCount)

            val authoritative = rig.provider.prepare(
                rig.habits, rig.from, rig.today, rig.revision.datasetId, rig.revision.value, rig.calendar
            )
            for (h in rig.habits) {
                val ref = HabitRef(h.uuid!!)
                assertSnapshotParity(afterRapidEdits.getValue(ref), authoritative.getValue(ref), rig.from, rig.today)
            }
        } finally {
            rig.db.close()
        }
    }
}
