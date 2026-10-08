/*
 * Copyright (C) 2016-2026 Álinson Santos Xavier and contributors.
 *
 * This file is part of Loop Habit Tracker.
 *
 * Loop Habit Tracker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 */
package org.isoron.uhabits.activities.statistics

import org.isoron.platform.time.DayOfWeek
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.setToday
import org.isoron.platform.time.startOfLocalDay
import org.isoron.uhabits.BaseAndroidJVMTest
import org.isoron.uhabits.activities.statistics.views.HabitInsight
import org.isoron.uhabits.activities.statistics.views.StatisticsHabitInsightsView
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.contribution.Contribution
import org.isoron.uhabits.core.contribution.ContributionId
import org.isoron.uhabits.core.contribution.ContributionSourceType
import org.isoron.uhabits.core.contribution.memory.MemoryContributionRepository
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitType
import org.isoron.uhabits.core.models.NumericalHabitType
import org.isoron.uhabits.core.models.memory.MemoryModelFactory
import org.isoron.uhabits.core.models.projection.EffectiveHabitReadSnapshotProvider
import org.isoron.uhabits.core.models.projection.EffectiveReadCalendarContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class StatisticsHabitInsightsViewTest : BaseAndroidJVMTest() {

    private val modelFactory = MemoryModelFactory()
    private val today = LocalDate(2026, 10, 8)

    @Before
    override fun setUp() {
        super.setUp()
        setToday(today)
    }

    private fun createNumericalHabit(name: String, target: Double): Habit = modelFactory.buildHabit().apply {
        this.uuid = name
        this.name = name
        this.type = HabitType.NUMERICAL
        this.unit = "min"
        this.targetValue = target
        this.targetType = NumericalHabitType.AT_LEAST
    }

    @Test
    fun legacyParityWithoutContributions() {
        val habit = createNumericalHabit("Reading", 20.0).apply {
            unit = "pages"
            originalEntries.add(Entry(today, 20_000))
            originalEntries.add(Entry(today.minus(1), 10_000))
            originalEntries.add(Entry(today.minus(2), Entry.NO))
            originalEntries.add(Entry(today.minus(3), Entry.SKIP))
            originalEntries.add(Entry(today.minus(4), Entry.UNKNOWN))
            recompute()
        }

        val history = StatisticsHabitInsightsView.getHabitHistoryData(habit, null, today)
        assertEquals(10, history.size)
        assertEquals(1.0f, history[9], 0.001f) // today: 20/20 = 1.0
        assertEquals(0.5f, history[8], 0.001f) // today - 1: 10/20 = 0.5
        assertEquals(0.0f, history[7], 0.001f) // today - 2: 0/20 = 0.0
        assertEquals(0.0f, history[6], 0.001f) // today - 3: SKIP -> 0
        assertEquals(0.0f, history[5], 0.001f) // today - 4: UNKNOWN -> 0
    }

    @Test
    fun numericalHabitWithContributionsCalculatesEffectiveSparkline() {
        val habit = createNumericalHabit("Study", 60.0).apply {
            originalEntries.add(Entry(today, 20_000)) // 20 min manual
            recompute()
        }

        val repo = MemoryContributionRepository()
        val todayTs = startOfLocalDay(today) + 3600_000L
        val yesterdayTs = startOfLocalDay(today.minus(1)) + 3600_000L

        // Contribution 40 min today -> total 60 min (100% of 60m target)
        repo.record(Contribution(
            id = ContributionId("c1"),
            targetHabit = HabitRef(habit.uuid!!),
            sourceType = ContributionSourceType.FOCUS_SESSION,
            sourceId = "f1",
            contributedValue = 40_000L,
            effectiveTimestamp = todayTs,
            createdAt = todayTs,
            updatedAt = todayTs
        ))

        // Contribution 30 min yesterday (with 0 manual) -> total 30 min (50% of 60m target)
        repo.record(Contribution(
            id = ContributionId("c2"),
            targetHabit = HabitRef(habit.uuid!!),
            sourceType = ContributionSourceType.FOCUS_SESSION,
            sourceId = "f2",
            contributedValue = 30_000L,
            effectiveTimestamp = yesterdayTs,
            createdAt = yesterdayTs,
            updatedAt = yesterdayTs
        ))

        val provider = EffectiveHabitReadSnapshotProvider(repo)
        val snapshot = provider.prepare(
            habits = listOf(habit),
            from = today.minus(10),
            to = today,
            datasetId = "test",
            revision = 1L,
            calendar = EffectiveReadCalendarContext("UTC", DayOfWeek.MONDAY, 0, 0)
        )[HabitRef(habit.uuid!!)]

        val effectiveHistory = StatisticsHabitInsightsView.getHabitHistoryData(habit, snapshot, today)
        assertEquals(10, effectiveHistory.size)
        // today: (20 + 40) / 60 = 1.0f
        assertEquals(1.0f, effectiveHistory[9], 0.001f)
        // yesterday: (0 + 30) / 60 = 0.5f
        assertEquals(0.5f, effectiveHistory[8], 0.001f)
    }

    @Test
    fun consecutiveSkipsBreaksWhenContributionsPresent() {
        val habit = createNumericalHabit("Run", 30.0).apply {
            // Manual entries: NO for today, yesterday, 2 days ago
            originalEntries.add(Entry(today, Entry.NO))
            originalEntries.add(Entry(today.minus(1), Entry.NO))
            originalEntries.add(Entry(today.minus(2), Entry.NO))
            recompute()
        }

        // Without contributions: 3 consecutive skips
        val legacyInsight = StatisticsHabitInsightsView.computeInsight(habit, null, today)
        assertEquals(HabitInsight.ConsecutiveSkips(3), legacyInsight)

        // With contribution today (30 min FocusSession) -> breaks consecutive skips
        val repo = MemoryContributionRepository()
        val todayTs = startOfLocalDay(today) + 3600_000L
        repo.record(Contribution(
            id = ContributionId("c-run"),
            targetHabit = HabitRef(habit.uuid!!),
            sourceType = ContributionSourceType.FOCUS_SESSION,
            sourceId = "f-run",
            contributedValue = 30_000L,
            effectiveTimestamp = todayTs,
            createdAt = todayTs,
            updatedAt = todayTs
        ))

        val provider = EffectiveHabitReadSnapshotProvider(repo)
        val snapshot = provider.prepare(
            habits = listOf(habit),
            from = today.minus(10),
            to = today,
            datasetId = "test",
            revision = 1L,
            calendar = EffectiveReadCalendarContext("UTC", DayOfWeek.MONDAY, 0, 0)
        )[HabitRef(habit.uuid!!)]

        val effectiveInsight = StatisticsHabitInsightsView.computeInsight(habit, snapshot, today)
        // Today has 30m totalValue > 0 -> consecutive skips broken
        assertTrue(effectiveInsight !is HabitInsight.ConsecutiveSkips)
    }

    @Test
    fun incompleteEffectiveStreakDoesNotFallBackToManualStreak() {
        val habit = createNumericalHabit("Incomplete", 30.0).apply {
            for (offset in 1..10) originalEntries.add(Entry(today.minus(offset), 30_000))
            recompute()
        }
        val repo = MemoryContributionRepository()
        val timestamp = startOfLocalDay(today) + 3600_000L
        repo.record(Contribution(
            id = ContributionId("incomplete"), targetHabit = HabitRef(habit.uuid!!),
            sourceType = ContributionSourceType.FOCUS_SESSION, sourceId = "focus-incomplete",
            contributedValue = 10_000L, effectiveTimestamp = timestamp,
            createdAt = timestamp, updatedAt = timestamp
        ))
        val snapshot = EffectiveHabitReadSnapshotProvider(repo).prepare(
            listOf(habit), today.minus(10), today, "test", 1L,
            EffectiveReadCalendarContext("UTC", DayOfWeek.MONDAY, 0, 0)
        ).getValue(HabitRef(habit.uuid!!))
        assertEquals(null, snapshot.streakResult.streaks)
        assertEquals(HabitInsight.Stable, StatisticsHabitInsightsView.computeInsight(habit, snapshot, today))
    }
}
