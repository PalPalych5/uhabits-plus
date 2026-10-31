/*
 * Copyright (C) 2016-2026 Álinson Santos Xavier <git@axavier.org> and contributors
 *
 * This file is part of Loop Habit Tracker.
 *
 * Loop Habit Tracker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * Loop Habit Tracker is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.isoron.uhabits.activities.habits.show

import kotlinx.coroutines.Dispatchers
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.getToday
import org.isoron.platform.time.setToday
import org.isoron.uhabits.BaseAndroidJVMTest
import org.isoron.uhabits.activities.common.dialogs.HistoryEditorHabitBinding
import org.isoron.uhabits.activities.common.dialogs.HistoryEditorResolutionResult
import org.isoron.uhabits.core.commands.CommandRunner
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Frequency
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitGoal
import org.isoron.uhabits.core.models.HabitList
import org.isoron.uhabits.core.models.HabitType
import org.isoron.uhabits.core.models.ModelObservable
import org.isoron.uhabits.core.models.NumericalHabitType
import org.isoron.uhabits.core.models.PaletteColor
import org.isoron.uhabits.core.models.memory.MemoryModelFactory
import org.isoron.uhabits.core.preferences.MemoryStorage
import org.isoron.uhabits.core.preferences.Preferences
import org.isoron.uhabits.core.tasks.CoroutineTaskRunner
import org.isoron.uhabits.core.ui.callbacks.CheckMarkDialogCallback
import org.isoron.uhabits.core.ui.callbacks.NumberPickerCallback
import org.isoron.uhabits.core.ui.callbacks.OnConfirmedCallback
import org.isoron.uhabits.core.ui.screens.habits.show.ShowHabitMenuPresenter
import org.isoron.uhabits.core.ui.screens.habits.show.ShowHabitPresenter
import org.isoron.uhabits.core.ui.views.LightTheme
import org.isoron.uhabits.core.ui.views.OnDateClickedListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ShowHabitSyncReloadTest : BaseAndroidJVMTest() {

    private lateinit var factory: MemoryModelFactory
    private lateinit var testHabitList: HabitList
    private lateinit var preferences: Preferences
    private lateinit var runner: CommandRunner

    private val stubScreen = object : ShowHabitPresenter.Screen, ShowHabitMenuPresenter.Screen {
        override fun updateWidgets() {}
        override fun refresh() {}
        override fun showHistoryEditorDialog(listener: OnDateClickedListener) {}
        override fun showFeedback() {}
        override fun showNumberPopup(value: Double, notes: String, callback: NumberPickerCallback) {}
        override fun showNumberPopup(value: Double, notes: String, habit: Habit?, date: LocalDate?, callback: NumberPickerCallback) {}
        override fun showCheckmarkPopup(selectedValue: Int, notes: String, color: PaletteColor, callback: CheckMarkDialogCallback) {}
        override fun showEditHabitScreen(habit: Habit) {}
        override fun showMessage(m: ShowHabitMenuPresenter.Message?) {}
        override fun showSendFileScreen(filename: String) {}
        override fun showDeleteConfirmationScreen(callback: OnConfirmedCallback) {}
        override fun showStatisticsStartDateDialog(current: LocalDate?, callback: (LocalDate?) -> Unit) {}
        override fun showHardResetStatisticsConfirmation(callback: OnConfirmedCallback) {}
        override fun close() {}
    }

    @Before
    override fun setUp() {
        super.setUp()
        setToday(LocalDate(2026, 10, 31))
        factory = MemoryModelFactory()
        testHabitList = factory.buildHabitList()
        preferences = Preferences(MemoryStorage())
        val taskRunner = CoroutineTaskRunner(Dispatchers.Unconfined, Dispatchers.Unconfined)
        runner = CommandRunner(taskRunner)
    }

    private fun createHabit(
        name: String,
        color: PaletteColor,
        targetValue: Double,
        goals: List<HabitGoal> = emptyList()
    ): Habit {
        val h = factory.buildHabit()
        h.name = name
        h.color = color
        h.type = HabitType.NUMERICAL
        h.unit = "min"
        h.frequency = Frequency.DAILY
        h.targetType = NumericalHabitType.AT_LEAST
        h.targetValue = targetValue
        h.goalHistory = goals.toMutableList()
        testHabitList.add(h)
        return h
    }

    /**
     * Confirms the core defect: if the open detail screen does not re-resolve the Habit instance
     * after sync updates the HabitList, buildState continues reading stale pre-sync fields.
     */
    @Test
    fun staleHabitReferenceReproduction_withoutRebind_retainsOldState() {
        val initialGoal = HabitGoal(
            effectiveDate = LocalDate(2026, 1, 1),
            frequency = Frequency.DAILY,
            targetType = NumericalHabitType.AT_LEAST,
            targetValue = 10.0,
            unit = "min"
        )
        val habit1 = createHabit("Morning Study", PaletteColor(1), 10.0, listOf(initialGoal))
        val habitId = habit1.id!!

        // Client builds state initially with habit1
        val initialPresenter = ShowHabitPresenter(habit1, testHabitList, preferences, stubScreen, runner)
        val initialState = ShowHabitPresenter.buildState(habit1, preferences, LightTheme())
        assertEquals("Morning Study", initialState.title)
        assertEquals(PaletteColor(1), initialState.color)

        // Inbound sync occurs: habit1 is replaced in habitList by habit2 with new name, color, and goal
        val syncedGoal = HabitGoal(
            effectiveDate = LocalDate(2026, 10, 1),
            frequency = Frequency.DAILY,
            targetType = NumericalHabitType.AT_LEAST,
            targetValue = 60.0,
            unit = "min"
        )
        val habit2 = factory.buildHabit().apply {
            id = habitId
            uuid = habit1.uuid
            name = "Advanced Study"
            color = PaletteColor(4)
            type = HabitType.NUMERICAL
            unit = "min"
            frequency = Frequency.DAILY
            targetType = NumericalHabitType.AT_LEAST
            targetValue = 60.0
            goalHistory = mutableListOf(syncedGoal)
        }
        // Replace in list (simulating SQLiteHabitList.loadRecords rehydration)
        testHabitList.remove(habit1)
        testHabitList.add(habit2)

        // Stale reproduction: if caller still references habit1, the UI state is wrong
        val staleState = ShowHabitPresenter.buildState(habit1, preferences, LightTheme())
        assertEquals("Morning Study", staleState.title)
        assertEquals(PaletteColor(1), staleState.color)
        assertNotEquals("Advanced Study", staleState.title)

        // Correct behavior: re-resolving from habitList yields habit2 and updated state
        val reResolvedHabit = testHabitList.getById(habitId)
        assertNotNull(reResolvedHabit)
        assertSame(habit2, reResolvedHabit)
        val freshState = ShowHabitPresenter.buildState(reResolvedHabit!!, preferences, LightTheme())
        assertEquals("Advanced Study", freshState.title)
        assertEquals(PaletteColor(4), freshState.color)
    }

    @Test
    fun showHabitHabitBinding_rebindsFreshHabitAndReflectsUpdatedState() {
        val habit1 = createHabit("Old Name", PaletteColor(1), 15.0)
        val habitId = habit1.id!!

        val binding = ShowHabitHabitBinding(runner, testHabitList, preferences, stubScreen)
        binding.initialize(habit1)
        assertSame(habit1, binding.habit)

        // Sync arrives: new Habit entity instance
        val habit2 = factory.buildHabit().apply {
            id = habitId
            uuid = habit1.uuid
            name = "Synced Name"
            color = PaletteColor(2)
            type = HabitType.NUMERICAL
            unit = "min"
            frequency = Frequency.DAILY
            targetType = NumericalHabitType.AT_LEAST
            targetValue = 30.0
        }
        testHabitList.remove(habit1)
        testHabitList.add(habit2)

        val result = binding.resolve(
            isDatasetRebuilding = false,
            currentSessionDatasetId = "prod",
            initialDatasetId = "prod",
            listener = null,
            isResumed = false
        )

        assertTrue(result is HabitResolutionResult.Rebound)
        val rebound = result as HabitResolutionResult.Rebound
        assertTrue(rebound.habitChanged)
        assertSame(habit2, rebound.habit)
        assertSame(habit2, binding.habit)

        val state = ShowHabitPresenter.buildState(binding.habit!!, preferences, LightTheme())
        assertEquals("Synced Name", state.title)
        assertEquals(PaletteColor(2), state.color)
    }

    @Test
    fun showHabitHabitBinding_rewiresListenersOnRebind() {
        val habit1 = createHabit("Habit 1", PaletteColor(1), 10.0)
        val habitId = habit1.id!!

        var listenerFiredCount = 0
        val listener = object : ModelObservable.Listener {
            override fun onModelChange() {
                listenerFiredCount++
            }
        }

        val binding = ShowHabitHabitBinding(runner, testHabitList, preferences, stubScreen)
        binding.initialize(habit1, listener, isResumed = true)

        // Firing habit1 notifies listener
        habit1.observable.notifyListeners()
        assertEquals(1, listenerFiredCount)

        // Inbound sync reload produces habit2
        val habit2 = factory.buildHabit().apply {
            id = habitId
            uuid = habit1.uuid
            name = "Habit 2"
            color = PaletteColor(3)
        }
        testHabitList.remove(habit1)
        testHabitList.add(habit2)

        // Rebind with listener rewiring
        val result = binding.resolve(
            isDatasetRebuilding = false,
            currentSessionDatasetId = "prod",
            initialDatasetId = "prod",
            listener = listener,
            isResumed = true
        )
        assertTrue(result is HabitResolutionResult.Rebound)

        // habit1 is detached, so modifying it must NOT trigger listener
        habit1.observable.notifyListeners()
        assertEquals(1, listenerFiredCount)

        // habit2 is attached, so modifying it DOES trigger listener
        habit2.observable.notifyListeners()
        assertEquals(2, listenerFiredCount)

        // Detach on pause/destroy
        binding.detach(listener)
        habit2.observable.notifyListeners()
        assertEquals(2, listenerFiredCount)
    }

    @Test
    fun showHabitHabitBinding_actionsOperateOnFreshHabit() {
        val habit1 = createHabit("Target Habit", PaletteColor(1), 10.0)
        val habitId = habit1.id!!

        val binding = ShowHabitHabitBinding(runner, testHabitList, preferences, stubScreen)
        binding.initialize(habit1)

        val habit2 = factory.buildHabit().apply {
            id = habitId
            uuid = habit1.uuid
            name = "Target Habit"
            color = PaletteColor(1)
            type = HabitType.NUMERICAL
            unit = "min"
            frequency = Frequency.DAILY
            targetType = NumericalHabitType.AT_LEAST
            targetValue = 10.0
        }
        testHabitList.remove(habit1)
        testHabitList.add(habit2)

        binding.resolve(
            isDatasetRebuilding = false,
            currentSessionDatasetId = "prod",
            initialDatasetId = "prod",
            listener = null,
            isResumed = false
        )

        val activePresenter = binding.presenter
        assertNotNull(activePresenter)
        assertSame(habit2, activePresenter!!.habit)
        assertSame(habit2, activePresenter.historyCardPresenter.habit)

        val today = getToday()
        // Non-numerical toggle test directly
        val nonNum1 = factory.buildHabit().apply {
            name = "Checkmark Habit"
            color = PaletteColor(2)
            frequency = Frequency.DAILY
        }
        testHabitList.add(nonNum1)
        val nonNumId = nonNum1.id!!

        val nonNumBinding = ShowHabitHabitBinding(runner, testHabitList, preferences, stubScreen)
        nonNumBinding.initialize(nonNum1)

        val nonNum2 = factory.buildHabit().apply {
            id = nonNumId
            uuid = nonNum1.uuid
            name = "Checkmark Habit"
            color = PaletteColor(2)
            frequency = Frequency.DAILY
        }
        testHabitList.remove(nonNum1)
        testHabitList.add(nonNum2)

        nonNumBinding.resolve(false, "prod", "prod")
        preferences.isShortToggleEnabled = true
        nonNumBinding.presenter!!.historyCardPresenter.onDateShortPress(today)

        // Verify habit2 has the entry recorded, while habit1 remains untouched
        assertEquals(Entry.YES_MANUAL, nonNum2.originalEntries.get(today).value)
        assertEquals(Entry.UNKNOWN, nonNum1.originalEntries.get(today).value)
    }

    @Test
    fun showHabitHabitBinding_deletionReturnsHabitDeleted() {
        val habit = createHabit("To Delete", PaletteColor(1), 5.0)
        val binding = ShowHabitHabitBinding(runner, testHabitList, preferences, stubScreen)
        binding.initialize(habit)

        // Remote deletion: habit removed from list
        testHabitList.remove(habit)

        val result = binding.resolve(
            isDatasetRebuilding = false,
            currentSessionDatasetId = "prod",
            initialDatasetId = "prod"
        )
        assertSame(HabitResolutionResult.HabitDeleted, result)
    }

    @Test
    fun showHabitHabitBinding_sessionMismatchReturnsSessionMismatched() {
        val habit = createHabit("Session Habit", PaletteColor(1), 5.0)
        val binding = ShowHabitHabitBinding(runner, testHabitList, preferences, stubScreen)
        binding.initialize(habit)

        // Dataset rebuilding
        val rebuildingResult = binding.resolve(
            isDatasetRebuilding = true,
            currentSessionDatasetId = "prod",
            initialDatasetId = "prod"
        )
        assertSame(HabitResolutionResult.SessionMismatched, rebuildingResult)

        // Dataset ID changed (e.g. account switch)
        val mismatchedResult = binding.resolve(
            isDatasetRebuilding = false,
            currentSessionDatasetId = "foreign-dataset",
            initialDatasetId = "prod"
        )
        assertSame(HabitResolutionResult.SessionMismatched, mismatchedResult)
    }

    @Test
    fun historyEditorHabitBinding_rebindsFreshHabitAndUpdatesPaletteColor() {
        val habit1 = createHabit("History Habit", PaletteColor(1), 10.0)
        val habitId = habit1.id!!

        val binding = HistoryEditorHabitBinding(testHabitList)
        binding.initialize(habit1)
        assertSame(habit1, binding.habit)

        // Inbound sync changes color from 1 to 5
        val habit2 = factory.buildHabit().apply {
            id = habitId
            uuid = habit1.uuid
            name = "History Habit"
            color = PaletteColor(5)
        }
        testHabitList.remove(habit1)
        testHabitList.add(habit2)

        val result = binding.resolve(
            habitId = habitId,
            habitUuid = habit1.uuid,
            isDatasetRebuilding = false,
            currentSessionDatasetId = "prod",
            initialDatasetId = "prod"
        )

        assertTrue(result is HistoryEditorResolutionResult.Rebound)
        val rebound = result as HistoryEditorResolutionResult.Rebound
        assertTrue(rebound.habitChanged)
        assertSame(habit2, rebound.habit)
        assertEquals(PaletteColor(5), rebound.habit.color)
    }

    @Test
    fun historyEditorHabitBinding_deletionAndSessionMismatchDismissSafely() {
        val habit = createHabit("History Delete", PaletteColor(1), 10.0)
        val binding = HistoryEditorHabitBinding(testHabitList)
        binding.initialize(habit)

        // Deletion
        testHabitList.remove(habit)
        val deleteResult = binding.resolve(
            habitId = habit.id,
            habitUuid = habit.uuid,
            isDatasetRebuilding = false,
            currentSessionDatasetId = "prod",
            initialDatasetId = "prod"
        )
        assertSame(HistoryEditorResolutionResult.HabitDeleted, deleteResult)

        // Session mismatch
        val sessionResult = binding.resolve(
            habitId = habit.id,
            habitUuid = habit.uuid,
            isDatasetRebuilding = true,
            currentSessionDatasetId = "prod",
            initialDatasetId = "prod"
        )
        assertSame(HistoryEditorResolutionResult.SessionMismatched, sessionResult)
    }
}
