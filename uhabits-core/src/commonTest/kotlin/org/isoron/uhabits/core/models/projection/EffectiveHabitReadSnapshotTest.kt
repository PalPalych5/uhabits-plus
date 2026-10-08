package org.isoron.uhabits.core.models.projection

import org.isoron.platform.time.*
import org.isoron.uhabits.core.BaseUnitTest
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.contribution.*
import org.isoron.uhabits.core.contribution.memory.MemoryContributionRepository
import org.isoron.uhabits.core.models.*
import kotlin.test.*

class EffectiveHabitReadSnapshotTest : BaseUnitTest() {
    private val monday = LocalDate(2026, 10, 5)
    private val calendar = EffectiveReadCalendarContext("local", DayOfWeek.MONDAY, 0, 0)
    private var sequence = 0
    private val repository = MemoryContributionRepository()
    @BeforeTest fun setSnapshotToday() { setToday(monday.plus(6)) }
    private fun habit(frequency: Frequency = Frequency.DAILY, targetType: NumericalHabitType = NumericalHabitType.AT_LEAST): Habit =
        modelFactory.buildHabit().apply {
            uuid = "read-${++sequence}"; type = HabitType.NUMERICAL; unit = "min"
            this.frequency = frequency; this.targetType = targetType; targetValue = 300.0
        }
    private fun credit(h: Habit, date: LocalDate, value: Long): Contribution {
        val id = "credit-${++sequence}"
        val timestamp = startOfLocalDay(date) + 1000
        return Contribution(ContributionId(id), HabitRef(h.uuid!!), ContributionSourceType.FOCUS_SESSION,
            id, value, timestamp, timestamp, timestamp).also(repository::record)
    }
    private fun read(h: Habit, to: LocalDate = monday.plus(6), revision: Long = 1): EffectiveHabitReadSnapshot =
        EffectiveHabitReadSnapshotProvider(repository).prepare(listOf(h), monday, to, "test", revision, calendar).getValue(HabitRef(h.uuid!!))

    @Test fun manualOnlyScoreStreakAndCompletionParityAcrossTypesAndGoals() {
        for (boolean in listOf(false, true)) for (denominator in listOf(1, 7, 30))
            for (target in NumericalHabitType.entries) {
                val h = habit(Frequency(1, denominator), target)
                if (boolean) h.type = HabitType.YES_NO
                for ((index, number) in listOf(Entry.UNKNOWN, Entry.SKIP, 0, 60000, 180000, 90000).withIndex()) {
                    h.originalEntries.add(Entry(monday.plus(index), if (boolean && number > 3) Entry.YES_MANUAL else number))
                }
                h.recompute()
                val snapshot = read(h)
                assertEquals(EffectiveReadExactness.LEGACY_COMPATIBILITY, snapshot.scoreResult.exactness)
                for (offset in 0..6) {
                    val date = monday.plus(offset)
                    assertEquals(h.scores[date].value, snapshot.scoreResult.on(date), "Score parity $boolean/$denominator/$target/$date")
                    assertEquals(h.isCompletedOn(date), snapshot.compatibilityCompletionOn(date).isCompleted)
                }
                assertEquals(h.streaks.getBest(100).sortedBy { it.start }, snapshot.streakResult.streaks!!.sortedBy { it.start })
            }
    }

    @Test fun futureStreaksUseSameLegacyThirtyDayHorizon() {
        val today = getToday()
        for (boolean in listOf(false, true)) {
            val h = habit()
            h.targetValue = 45.0
            if (boolean) h.type = HabitType.YES_NO
            val amount = if (boolean) Entry.YES_MANUAL else 45000
            h.originalEntries.add(Entry(today.plus(10), amount))
            h.originalEntries.add(Entry(today.plus(40), amount)); h.recompute()
            val snapshot = read(h, today)
            assertEquals(h.streaks.getBest(100).sortedBy { it.start }, snapshot.streakResult.streaks!!.sortedBy { it.start })
            assertEquals(h.streaks.getBest(100).sortedBy { it.start }, snapshot.presentationHabit().streaks.getBest(100).sortedBy { it.start })
            assertTrue(snapshot.streakResult.streaks!!.any { it.end == today.plus(10) })
            assertFalse(snapshot.streakResult.streaks!!.any { it.end == today.plus(40) })
        }
    }
    @Test fun derivedFutureStreaksKeepTheSameThirtyDayHorizon() {
        val today = getToday()
        val h = habit(); h.targetValue = 45.0; h.statisticsStartDate = monday
        for (offset in 0..70) h.originalEntries.add(Entry(monday.plus(offset), 0))
        h.recompute(); credit(h, today.plus(10), 45000); credit(h, today.plus(40), 45000)
        val snapshot = read(h, today)
        assertEquals(EffectiveReadExactness.EXACT, snapshot.streakResult.exactness)
        assertTrue(snapshot.streakResult.streaks!!.any { it.end == today.plus(10) })
        assertFalse(snapshot.streakResult.streaks!!.any { it.end == today.plus(40) })
        assertEquals(snapshot.streakResult.streaks!!.sortedBy { it.start }, snapshot.presentationHabit().streaks.getBest(100).sortedBy { it.start })
        assertEquals(today.plus(40), snapshot.days.lastOrNull { it.contributions.isNotEmpty() }!!.manualEntry.date)
    }
    @Test fun exactDerivedObservationsKeepUnchangedScoreFormulaAndCompatibilityStreaks() {
        for (target in NumericalHabitType.entries) for (denominator in listOf(1, 7, 30)) {
            val h = habit(Frequency(1, denominator), target)
            if (target == NumericalHabitType.AT_LEAST) h.targetValue = 45.0
            h.statisticsStartDate = monday
            for (offset in 0..70) h.originalEntries.add(Entry(monday.plus(offset), 0))
            h.originalEntries.add(Entry(monday, 30000)); h.recompute()
            credit(h, monday, 60000)
            val reference = modelFactory.buildHabit().apply {
                copyFrom(h)
                h.originalEntries.getKnown().forEach(originalEntries::add)
                originalEntries.add(Entry(monday, 90000)); recompute()
            }
            val snapshot = read(h)
            assertEquals(EffectiveReadExactness.EXACT, snapshot.scoreResult.exactness)
            for (offset in 0..6) {
                val date = monday.plus(offset)
                assertEquals(reference.scores[date].value, snapshot.scoreResult.on(date))
                assertEquals(reference.isCompletedOn(date), snapshot.compatibilityCompletionOn(date).isCompleted)
            }
            val expected = StreakList().apply { recompute(reference, reference.computedEntries, monday, getToday().plus(30)) }
            assertEquals(expected.getBest(100).sortedBy { it.start }, snapshot.streakResult.streaks!!.sortedBy { it.start })
        }
    }

    @Test fun firstRollingWindowMissingCoverageCannotProduceExactDerivedScore() {
        val h = habit(Frequency(1, 7)); h.originalEntries.add(Entry(monday, 30000)); h.recompute()
        credit(h, monday, 60000)
        val snapshot = read(h, monday)
        assertTrue(snapshot.day(monday).hasCompleteCoverage)
        assertEquals(EffectiveReadExactness.INCOMPLETE, snapshot.scoreResult.exactness)
        assertNull(snapshot.scoreResult.scores); assertNull(snapshot.streakResult.streaks)
    }

    @Test fun liveCalendarAchievementAndLegacyAtMostCalendarStayDistinct() {
        val localeFirst = DayOfWeek.entries[getFirstWeekdayNumberAccordingToLocale() - 1]
        val liveFirst = DayOfWeek.entries[(localeFirst.ordinal + 1) % 7]
        val start = monday.startOfWeek(localeFirst)
        val date = start.plus(3)
        val h = habit(Frequency(1, 7), NumericalHabitType.AT_MOST)
        h.targetValue = 100.0; h.statisticsStartDate = start
        for (offset in 0..7) h.originalEntries.add(Entry(start.plus(offset), 0))
        h.recompute(); credit(h, start, 150000)
        val snapshot = EffectiveHabitReadSnapshotProvider(repository).prepare(listOf(h), start, date,
            "calendar", 1, calendar.copy(firstWeekday = liveFirst)).getValue(HabitRef(h.uuid!!))
        assertEquals(HabitGoalStatus.SATISFIED, snapshot.goalAchievementOn(date).status)
        assertEquals(HabitGoalStatus.NOT_SATISFIED, snapshot.compatibilityCompletionOn(date).status)
        assertEquals(EffectiveReadExactness.EXACT, snapshot.scoreResult.exactness)
    }

    @Test fun atMostUnloggedFuturePeriodMakesStreakUnavailableEvenWithKnownCurrentDay() {
        val h = habit(Frequency(1, 7), NumericalHabitType.AT_MOST)
        val first = DayOfWeek.entries[getFirstWeekdayNumberAccordingToLocale() - 1]
        val start = monday.startOfWeek(first)
        h.statisticsStartDate = start
        h.originalEntries.add(Entry(start, 0)); h.recompute(); credit(h, start, 60000)
        val snapshot = EffectiveHabitReadSnapshotProvider(repository).prepare(listOf(h), start, start,
            "at-most", 1, calendar).getValue(HabitRef(h.uuid!!))
        assertEquals(HabitGoalStatus.UNKNOWN, snapshot.compatibilityCompletionOn(start).status)
        assertEquals(EffectiveReadExactness.INCOMPLETE, snapshot.streakResult.exactness)
        assertNull(snapshot.streakResult.streaks)
    }

    @Test fun incompatibleAndBooleanContributionTargetsRemainTypedIntegrityFailures() {
        val h = habit(); h.unit = "steps"; credit(h, monday, 45000)
        assertFailsWith<HabitProjectionIntegrityException> { read(h) }
        val boolean = habit(); boolean.type = HabitType.YES_NO; credit(boolean, monday, 45000)
        assertFailsWith<HabitProjectionIntegrityException> { read(boolean) }
    }
    @Test fun manualHistoricalUnitChangesKeepLegacyPresentationAndScoreParity() {
        val h = habit()
        h.goalHistory = mutableListOf(
            HabitGoal(monday, Frequency.DAILY, NumericalHabitType.AT_LEAST, 1.0, "hours"),
            HabitGoal(monday.plus(1), Frequency.DAILY, NumericalHabitType.AT_LEAST, 45.0, "min"))
        h.originalEntries.add(Entry(monday, 1000)); h.originalEntries.add(Entry(monday.plus(1), 45000)); h.recompute()
        val snapshot = read(h)
        assertEquals(h.scores[monday].value, snapshot.scoreResult.on(monday))
        assertEquals(h.scores[monday.plus(1)].value, snapshot.scoreResult.on(monday.plus(1)))
        assertEquals(1000, snapshot.presentationHabit().computedEntries.get(monday).value)
        assertEquals(HabitGoalStatus.SATISFIED, snapshot.goalAchievementOn(monday).status)
        assertEquals(HabitGoalStatus.SATISFIED, snapshot.goalAchievementOn(monday.plus(1)).status)
        // The existing aggregate service still rejects summing two incompatible units.
        assertFailsWith<HabitProjectionIntegrityException> {
            EffectiveHabitValueService(repository).valueForRange(h, monday, monday.plus(1))
        }
    }
    @Test fun weeklyCalendarAchievementIsNotDailyCompatibilityOrRollingScore() {
        val h = habit(Frequency(1, 7))
        h.originalEntries.add(Entry(monday, 60000))
        h.originalEntries.add(Entry(monday.plus(1), 180000))
        h.originalEntries.add(Entry(monday.plus(2), 90000)); h.recompute()
        val snapshot = read(h)
        assertEquals(HabitGoalStatus.SATISFIED, snapshot.goalAchievementOn(monday.plus(2)).status)
        assertFalse(snapshot.compatibilityCompletionOn(monday.plus(2)).isCompleted)
        assertEquals(h.scores[monday.plus(2)].value, snapshot.scoreResult.on(monday.plus(2)))
    }

    @Test fun manualPlusCreditAndEditNeverCopiesDerivedQuantity() {
        val h = habit(); h.originalEntries.add(Entry(monday, 30000, "manual")); h.recompute()
        val fact = credit(h, monday, 60000)
        val snapshot = read(h, monday)
        assertEquals(90000L, snapshot.day(monday).totalValue)
        assertEquals(30000, snapshot.day(monday).manualEntry.value)
        val display = snapshot.presentationHabit()
        assertEquals(90000, display.computedEntries.get(monday).value)
        assertEquals(30000, display.originalEntries.get(monday).value)
        display.originalEntries.add(Entry(monday, 123000))
        assertEquals(30000, snapshot.day(monday).manualEntry.value)
        h.originalEntries.add(Entry(monday, 40000)); h.recompute()
        assertEquals(100000L, read(h, monday).day(monday).totalValue)
        assertEquals(fact, repository.getById(fact.id))
        assertEquals(40000, h.originalEntries.get(monday).value)
    }

    @Test fun contributionOnlyDomainAndUnknownScoreRemainTyped() {
        val h = habit(); credit(h, monday.minus(7), 45000); credit(h, monday.plus(10), 0)
        val snapshot = read(h)
        assertEquals(monday.minus(7), snapshot.observedFrom)
        assertEquals(monday.plus(10), snapshot.observedTo)
        assertEquals(45000L, snapshot.day(monday.minus(7)).totalValue)
        assertFalse(snapshot.day(monday.minus(7)).hasCompleteCoverage)
        assertEquals(0L, snapshot.day(monday.plus(10)).totalValue)
        assertEquals(EffectiveReadExactness.INCOMPLETE, snapshot.scoreResult.exactness)
        assertNull(snapshot.scoreResult.scores); assertNull(snapshot.streakResult.streaks)
        assertTrue(h.originalEntries.getKnown().isEmpty()); assertTrue(h.computedEntries.getKnown().isEmpty())
    }

    @Test fun lowerBoundProvesAtLeastButNotAtMostAndSkipExcludes() {
        val h = habit(); credit(h, monday, 330000)
        var snapshot = read(h)
        assertEquals(HabitGoalStatus.SATISFIED, snapshot.goalAchievementOn(monday).status)
        assertTrue(snapshot.compatibilityCompletionOn(monday).isCompleted)
        h.targetType = NumericalHabitType.AT_MOST
        snapshot = read(h)
        assertEquals(HabitGoalStatus.NOT_SATISFIED, snapshot.goalAchievementOn(monday).status)
        h.targetValue = 400.0
        assertEquals(HabitGoalStatus.UNKNOWN, read(h).goalAchievementOn(monday).status)
        h.originalEntries.add(Entry(monday, Entry.SKIP)); h.recompute()
        snapshot = read(h)
        assertEquals(HabitGoalStatus.SKIPPED, snapshot.goalAchievementOn(monday).status)
        assertNull(snapshot.day(monday).totalValue)
        assertEquals(330000L, snapshot.day(monday).contributionValue)
    }

    @Test fun tombstoneReturnsDeterministicallyToLegacySemantics() {
        val h = habit(Frequency(1, 7)); h.originalEntries.add(Entry(monday, 30000)); h.recompute()
        val baseline = read(h)
        val fact = credit(h, monday, 60000)
        assertEquals(90000L, read(h).day(monday).totalValue)
        repository.removeById(fact.id, fact.createdAt + 1)
        val corrected = read(h, revision = 2)
        assertEquals(30000L, corrected.day(monday).totalValue)
        assertEquals(baseline.scoreResult, corrected.scoreResult)
        assertEquals(baseline.streakResult, corrected.streakResult)
        assertEquals(baseline.compatibilityCompletionOn(monday), corrected.compatibilityCompletionOn(monday))
        assertEquals(30000, h.originalEntries.get(monday).value)
    }

    @Test fun largeQuantityIsUnavailableRatherThanClamped() {
        val h = habit(); h.originalEntries.add(Entry(monday, 0)); h.recompute(); credit(h, monday, Int.MAX_VALUE.toLong() + 1)
        val snapshot = read(h, monday)
        assertEquals(Int.MAX_VALUE.toLong() + 1, snapshot.day(monday).totalValue)
        assertEquals(EffectiveReadExactness.UNREPRESENTABLE, snapshot.scoreResult.exactness)
        assertFailsWith<IllegalStateException> { snapshot.presentationHabit() }
    }

    @Test fun sentinelCollisionIsGatedRatherThanPresentedAsSkip() {
        val h = habit(); h.originalEntries.add(Entry(monday, 0)); h.recompute(); credit(h, monday, Entry.SKIP.toLong())
        val snapshot = read(h, monday)
        assertEquals(3L, snapshot.day(monday).totalValue)
        assertFalse(snapshot.day(monday).isSkipped)
        assertFalse(snapshot.canUseLegacyEntryAdapter)
        assertEquals(EffectiveReadExactness.UNREPRESENTABLE, snapshot.scoreResult.exactness)
    }
    @Test fun rollingOverflowIsUnavailableWithoutChangingLegacyFormula() {
        val h = habit(Frequency(1, 7))
        for (offset in 0..6) h.originalEntries.add(Entry(monday.plus(offset), 0))
        h.recompute(); credit(h, monday, 1500000000); credit(h, monday.plus(1), 1500000000)
        val snapshot = read(h)
        assertEquals(EffectiveReadExactness.UNREPRESENTABLE, snapshot.scoreResult.exactness)
        assertNull(snapshot.scoreResult.scores)
    }

    @Test fun batchAndPreloadedGoalEvaluationNeverQueryPerHabitOrPeriod() {
        var queries = 0
        val counted = object : ContributionRepository by repository {
            override fun getByHabitsInRange(targetHabits: Set<HabitRef>, fromTimestamp: Long, toTimestamp: Long, includeRemoved: Boolean): List<Contribution> {
                queries++; return repository.getByHabitsInRange(targetHabits, fromTimestamp, toTimestamp, includeRemoved)
            }
        }
        val habits = (0 until 500).map { habit(Frequency(1, 7)).apply {
            originalEntries.add(Entry(monday.minus(90), 0)); originalEntries.add(Entry(monday, 0)); recompute()
        } }
        habits.forEach { credit(it, monday, 45000) }
        val snapshots = EffectiveHabitReadSnapshotProvider(counted).prepare(habits, monday.minus(90), monday, "batch", 3, calendar)
        assertEquals(2, queries)
        snapshots.values.forEach { it.goalAchievementOn(monday); it.goalAchievementOn(monday.minus(30)); it.compatibilityCompletionOn(monday) }
        assertEquals(2, queries)
        assertEquals(500, snapshots.size)
    }

    @Test fun cutoffAndCapturedMetadataDoNotDriftAfterSnapshot() {
        val h = habit(); h.statisticsStartDate = monday
        credit(h, monday.minus(2), 45000); credit(h, monday, 330000)
        val snapshot = read(h)
        assertEquals(monday, snapshot.observedFrom)
        h.targetValue = 1000.0; h.statisticsStartDate = monday.plus(1)
        assertEquals(HabitGoalStatus.SATISFIED, snapshot.goalAchievementOn(monday).status)
        assertEquals(HabitGoalStatus.EXCLUDED, read(h).goalAchievementOn(monday).status)
    }
}

