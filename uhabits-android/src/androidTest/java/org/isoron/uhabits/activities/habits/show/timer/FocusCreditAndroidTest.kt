package org.isoron.uhabits.activities.habits.show.timer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ActivityScenario
import android.content.Intent
import android.view.View
import android.widget.EditText
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.habits.edit.EditHabitActivity
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.containers.session.DatasetSessionFactory
import org.isoron.uhabits.core.commands.CommandRunner
import org.isoron.uhabits.core.commands.Command
import org.isoron.uhabits.core.commands.AddNumericalEntryOpCommand
import org.isoron.uhabits.core.commands.CreateHabitCommand
import org.isoron.uhabits.core.commands.EditHabitCommand
import org.isoron.uhabits.core.timer.elapsedMillisToTenthsMinutes
import kotlin.math.roundToInt
import org.isoron.platform.time.getToday
import org.isoron.platform.time.startOfLocalDay
import org.isoron.platform.time.setToday
import org.isoron.uhabits.core.containers.migration.RawLegacyInventoryReader
import org.isoron.uhabits.core.contribution.sqlite.SQLiteContributionRepository
import org.isoron.uhabits.core.contribution.Contribution
import org.isoron.uhabits.core.contribution.ContributionId
import org.isoron.uhabits.core.contribution.ContributionSourceType
import org.isoron.uhabits.core.focus.FocusSession
import org.isoron.uhabits.core.focus.FocusSessionId
import org.isoron.uhabits.core.focus.FocusSessionSource
import org.isoron.uhabits.core.focus.sqlite.SQLiteFocusSessionRepository
import org.isoron.uhabits.core.models.HabitType
import org.isoron.uhabits.focus.FocusCreditController
import org.isoron.uhabits.inject.HabitsApplicationComponent
import org.isoron.uhabits.inject.create
import org.isoron.uhabits.utils.DatabaseUtils
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Uses a disposable external fixture; never launch instrumentation on an unpreserved user dataset. */
@RunWith(AndroidJUnit4::class)
class FocusCreditAndroidTest {
    @Test fun actualDatasetSwitchCancelsOldRecoveryAndReopenRecoversWithLiveDayOffset() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as HabitsApplication
        val originalSession = HabitsApplication.component.session
        assertTrue(originalSession.isExperimental)
        val suffix = UUID.randomUUID().toString()
        val expFile = context.getDatabasePath("pr16-switch-exp-$suffix.db")
        val prodFile = context.getDatabasePath("pr16-switch-prod-$suffix.db")
        File(requireNotNull(InstrumentationRegistry.getArguments().getString("focusFixture"))).copyTo(expFile)
        File(requireNotNull(InstrumentationRegistry.getArguments().getString("productionFixture"))).copyTo(prodFile)
        val expSession = originalSession.copy(databaseFilename = expFile.name, preferencesNamespace = "pr16-switch-$suffix")
        val prodSession = DatasetSessionFactory.production().copy(databaseFilename = prodFile.name)
        fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
        try {
            main { app.rebuildComponent(expSession) }
            instrumentation.waitForIdleSync()
            val exp = HabitsApplication.component
            val controller = requireNotNull(exp.focusCreditController)
            val habit = exp.modelFactory.buildHabit().apply {
                name = "PR16 actual switch"
                type = HabitType.NUMERICAL
                unit = "min"
            }
            val source = exp.organizationFacade.containerQueries!!.roots().first()
            val target = HabitRef(habit.uuid!!)
            main {
                exp.habitList.add(habit)
                controller.configure(habit, source.id, true)
                val now = System.currentTimeMillis()
                SQLiteFocusSessionRepository(exp.db).record(FocusSession(
                    FocusSessionId("switch-pending"), now - 1, now, 1,
                    containerId = source.id, source = FocusSessionSource.TIMER, createdAt = now, updatedAt = now))
                assertTrue(SQLiteContributionRepository(exp.db).getByHabit(target).isEmpty())
                controller.startRecovery()
                // Same main turn: shutdown must cancel the queued old-dataset reconciliation.
                app.rebuildComponent(prodSession)
            }
            instrumentation.waitForIdleSync()
            assertNull(HabitsApplication.component.focusCreditController)
            assertTrue(runCatching { controller.goalProgress(habit) }.isFailure)
            android.database.sqlite.SQLiteDatabase.openDatabase(prodFile.path, null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { db ->
                assertEquals(29, db.version)
                db.rawQuery("SELECT count(*) FROM sqlite_master WHERE name IN ('SourceRules','Contributions','FocusSessions')", null)
                    .use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
            }
            main { app.rebuildComponent(expSession) }
            instrumentation.waitForIdleSync()
            val reopened = HabitsApplication.component
            assertEquals(1, SQLiteContributionRepository(reopened.db).getByHabit(target).size)

            // A dedicated daily Habit proves a preference change rebuckets existing credit live.
            val offsetHabit = reopened.modelFactory.buildHabit().apply {
                name = "PR16 live offset"
                type = HabitType.NUMERICAL
                unit = "min"
            }
            val day = getToday()
            val timestamp = startOfLocalDay(day) + 60 * 60 * 1000L
            val previousDelay = reopened.preferences.isMidnightDelayEnabled
            main {
                reopened.habitList.add(offsetHabit)
                SQLiteFocusSessionRepository(reopened.db).record(FocusSession(
                    FocusSessionId("offset-source"), timestamp - 60000, timestamp, 60000,
                    habit = HabitRef(offsetHabit.uuid!!), source = FocusSessionSource.MANUAL,
                    createdAt = timestamp, updatedAt = timestamp))
                SQLiteContributionRepository(reopened.db).record(Contribution(
                    ContributionId("offset-credit"), HabitRef(offsetHabit.uuid!!), ContributionSourceType.FOCUS_SESSION,
                    "offset-source", 1000, timestamp, timestamp, timestamp))
                reopened.preferences.isMidnightDelayEnabled = false
                setToday(day)
                assertEquals(1000L, reopened.focusCreditController!!.goalProgress(offsetHabit).value.contributionValue)
                reopened.preferences.isMidnightDelayEnabled = true
                setToday(day)
                assertEquals(0L, reopened.focusCreditController!!.goalProgress(offsetHabit).value.contributionValue)
                reopened.preferences.isMidnightDelayEnabled = previousDelay
            }
        } finally {
            main { app.rebuildComponent(originalSession) }
            context.getSharedPreferences(expSession.preferencesNamespace, 0).edit().clear().commit()
            context.getSharedPreferences(expSession.preferencesNamespace + "_timer", 0).edit().clear().commit()
            listOf(expFile, prodFile).forEach { file ->
                listOf(file, File(file.path + "-wal"), File(file.path + "-shm"), File(file.path + "-journal")).forEach { it.delete() }
            }
        }
    }

    @Test fun productionTimerStillWritesExactlyOneManualValueAndDoesNotActivateFocusStorage() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val original = HabitsApplication.component
        val name = "pr16-production-${UUID.randomUUID()}.db"
        val file = context.getDatabasePath(name)
        File(requireNotNull(InstrumentationRegistry.getArguments().getString("productionFixture"))).copyTo(file)
        val session = DatasetSessionFactory.production().copy(databaseFilename = name, preferencesNamespace = name)
        val component = HabitsApplicationComponent::class.create(appContext = context, dbFile = file, session = session)
        fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
        var manager: TimerSessionManager? = null
        try {
            DatabaseUtils.closeDatabase()
            DatabaseUtils.initializeDatabase(context, name)
            main { HabitsApplication.component = component }
            assertNull(component.focusCreditController)
            val habit = component.modelFactory.buildHabit().apply {
                this.name = "PR16 production"
                type = HabitType.NUMERICAL
                unit = "min"
                timerEnabled = true
            }
            main { component.habitList.add(habit) }
            val completed = CountDownLatch(1)
            val writeCount = AtomicInteger()
            val listener = object : CommandRunner.Listener {
                override fun onCommandFinished(command: Command) {
                    if (command is AddNumericalEntryOpCommand) {
                        writeCount.incrementAndGet()
                        completed.countDown()
                    }
                }
            }
            main {
                component.commandRunner.addListener(listener)
                manager = component.timerSessionManager
                manager!!.resetCurrent()
                assertTrue(manager!!.startOrPause(habit))
            }
            instrumentation.waitForIdleSync()
            Thread.sleep(3300)
            var expectedValue = 0
            main {
                assertTrue(manager!!.startOrPause(habit)) // Pause freezes duration for exact expected credit.
                expectedValue = (elapsedMillisToTenthsMinutes(manager!!.snapshot().elapsedMillis) * 1000).roundToInt()
                manager!!.finish(habit)
                manager!!.finish(habit)
            }
            assertTrue(completed.await(10, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            main { component.commandRunner.removeListener(listener) }
            assertTrue(expectedValue > 0)
            assertEquals(expectedValue, habit.originalEntries.get(getToday()).value)
            assertEquals(1, writeCount.get())
            val database = android.database.sqlite.SQLiteDatabase.openDatabase(file.path, null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY)
            database.use { db ->
                assertEquals(29, db.version)
                db.rawQuery("SELECT count(*) FROM sqlite_master WHERE name IN ('SourceRules','Contributions','FocusSessions')", null)
                    .use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
            }
        } finally {
            main { manager?.dispose(); HabitsApplication.component = original }
            runCatching { component.db.close() }
            DatabaseUtils.closeDatabase()
            DatabaseUtils.initializeDatabase(context, original.session.databaseFilename)
            listOf(file, File(file.path + "-wal"), File(file.path + "-shm"), File(file.path + "-journal")).forEach { it.delete() }
        }
    }

    @Test fun finalizedTimerCreditsOnceAndRestartRecoversInterruptedCreditWithoutManualWrites() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val original = HabitsApplication.component
        assertTrue("Requires disposable experimental QA dataset", original.session.isExperimental)
        val name = "pr16-credit-${UUID.randomUUID()}.db"
        val file = context.getDatabasePath(name)
        File(requireNotNull(InstrumentationRegistry.getArguments().getString("focusFixture"))).copyTo(file)
        val session = original.session.copy(databaseFilename = name, preferencesNamespace = name)
        val component = HabitsApplicationComponent::class.create(appContext = context, dbFile = file, session = session)
        fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
        var reopened: FocusCreditController? = null
        try {
            DatabaseUtils.closeDatabase()
            DatabaseUtils.initializeDatabase(context, name)
            main { HabitsApplication.component = component }
            val habit = component.modelFactory.buildHabit().apply {
                this.name = "PR16 credit"
                type = HabitType.NUMERICAL
                unit = "min"
                timerEnabled = true
            }
            val source = component.organizationFacade.containerQueries!!.roots().first()
            main {
                component.habitList.add(habit)
                component.organizationFacade.placeNewHabit(habit.uuid!!, source.id)
            }
            ActivityScenario.launch<EditHabitActivity>(Intent(context, EditHabitActivity::class.java)
                .putExtra("habitId", habit.id!!)).use { scenario ->
                scenario.onActivity { editor ->
                    val section = editor.findViewById<View>(R.id.focusCreditOuterBox)
                    assertEquals(View.VISIBLE, section.visibility)
                    editor.findViewById<EditText>(R.id.unitInput).setText("m")
                    assertEquals(View.GONE, section.visibility)
                    editor.findViewById<EditText>(R.id.unitInput).setText("мин")
                    assertEquals(View.VISIBLE, section.visibility)
                }
            }
            ActivityScenario.launch<EditHabitActivity>(Intent(context, EditHabitActivity::class.java)
                .putExtra("habitType", HabitType.YES_NO.value)).use { scenario ->
                scenario.onActivity { editor ->
                    assertEquals(View.GONE, editor.findViewById<View>(R.id.focusCreditOuterBox).visibility)
                }
            }
            // Habit creation succeeds but a disappeared source rejects configuration.
            // The next Save must edit that persisted Habit rather than create another.
            val created = CountDownLatch(1)
            val edited = CountDownLatch(1)
            val saves = object : CommandRunner.Listener {
                override fun onCommandFinished(command: Command) {
                    if (command is CreateHabitCommand) created.countDown()
                    if (command is EditHabitCommand) edited.countDown()
                }
            }
            main { component.commandRunner.addListener(saves) }
            ActivityScenario.launch<EditHabitActivity>(Intent(context, EditHabitActivity::class.java)
                .putExtra("habitType", HabitType.NUMERICAL.value)).use { scenario ->
                scenario.onActivity { editor ->
                    editor.findViewById<EditText>(R.id.nameInput).setText("PR16 save retry")
                    editor.findViewById<EditText>(R.id.unitInput).setText("min")
                    editor.findViewById<EditText>(R.id.targetInput).setText("300")
                    editor.focusSourceContainerId = "source-removed-before-save"
                    editor.findViewById<View>(R.id.buttonSave).performClick()
                }
                assertTrue(created.await(10, TimeUnit.SECONDS))
                instrumentation.waitForIdleSync()
                scenario.onActivity { editor ->
                    assertTrue(editor.habitId >= 0)
                    assertTrue(editor.findViewById<View>(R.id.buttonSave).isEnabled)
                    editor.focusSourceContainerId = null
                    editor.findViewById<View>(R.id.buttonSave).performClick()
                }
                assertTrue(edited.await(10, TimeUnit.SECONDS))
                instrumentation.waitForIdleSync()
                assertEquals(1, component.habitList.count { it.name == "PR16 save retry" })
            }
            main { component.commandRunner.removeListener(saves) }
            val controller = requireNotNull(component.focusCreditController)
            main {
                val invalid = component.modelFactory.buildHabit().apply { type = HabitType.YES_NO }
                assertTrue(runCatching { controller.configure(invalid, source.id, true) }.isFailure)
                invalid.type = HabitType.NUMERICAL
                invalid.unit = "m"
                assertTrue(runCatching { controller.configure(invalid, source.id, true) }.isFailure)
            }
            val facts = SQLiteFocusSessionRepository(component.db)
            val credits = SQLiteContributionRepository(component.db)
            val target = HabitRef(habit.uuid!!)
            main { controller.configure(habit, source.id, true) }
            val firstRule = requireNotNull(controller.configuration.current(target))
            assertEquals(source.id, firstRule.sourceContainerId)
            assertTrue(firstRule.includeDescendants)
            val beforeManual = RawLegacyInventoryReader.read(component.db).snapshotSha256
            val beforeFacts = facts.getAll().size
            val timer = component.timerSessionManager
            main { assertTrue(timer.startOrPause(habit)) }
            // Greater than the legacy 0.1-minute rounding threshold: a missing guard would write manual credit.
            Thread.sleep(3300)
            main { timer.finish(habit); timer.finish(habit) }
            assertEquals(beforeFacts + 1, facts.getAll().size)
            assertEquals(1, credits.getByHabit(target).size)
            assertTrue(credits.getByHabit(target).single().contributedValue > 0)
            main {
                val progress = controller.goalProgress(habit).value
                assertEquals(credits.getByHabit(target).single().contributedValue, progress.contributionValue)
                assertFalse(progress.hasCompleteCoverage)
            }
            assertEquals(beforeManual, RawLegacyInventoryReader.read(component.db).snapshotSha256)
            main { controller.startRecovery() }
            instrumentation.waitForIdleSync()
            assertEquals(1, credits.getByHabit(target).size)

            // Commit a finalized fact without invoking the runtime bridge, then reopen its controller.
            val now = System.currentTimeMillis()
            facts.record(FocusSession(FocusSessionId("missing-callback"), now - 1000, now, 1000,
                containerId = source.id, source = FocusSessionSource.TIMER, createdAt = now, updatedAt = now))
            main {
                controller.dispose()
                reopened = FocusCreditController(component.db, component.habitList,
                    component.organizationFacade, component.preferences)
                reopened!!.startRecovery()
            }
            instrumentation.waitForIdleSync()
            assertEquals(2, credits.getByHabit(target).size)
            assertEquals(beforeManual, RawLegacyInventoryReader.read(component.db).snapshotSha256)
            main { reopened!!.configure(habit, null, true) }
            assertNull(reopened!!.configuration.current(target))
            assertEquals(2, credits.getByHabit(target).size)
            Thread.sleep(5)
            val later = System.currentTimeMillis()
            facts.record(FocusSession(FocusSessionId("after-disable"), later - 1, later, 1,
                containerId = source.id, source = FocusSessionSource.TIMER, createdAt = later, updatedAt = later))
            main { reopened!!.startRecovery() }
            instrumentation.waitForIdleSync()
            assertEquals(2, credits.getByHabit(target).size)
            main { reopened!!.configure(habit, source.id, false) }
            val replacement = requireNotNull(reopened!!.configuration.current(target))
            assertNotEquals(firstRule.id, replacement.id)
            assertFalse(replacement.includeDescendants)
            assertEquals(2, credits.getByHabit(target).size)

            // Dataset lifecycle disposal rejects stale projection/configuration and cancels queued work.
            main { reopened!!.startRecovery(); reopened!!.dispose() }
            instrumentation.waitForIdleSync()
            assertTrue(runCatching { reopened!!.goalProgress(habit) }.isFailure)
            assertTrue(runCatching { reopened!!.configure(habit, source.id, true) }.isFailure)
            assertFalse(timer.acceptsDatasetCallback("production"))
            assertEquals(29, org.isoron.uhabits.core.DATABASE_VERSION)
        } finally {
            main {
                reopened?.dispose()
                component.focusCreditController?.dispose()
                runCatching { component.timerSessionManager.dispose() }
                HabitsApplication.component = original
            }
            runCatching { component.db.close() }
            DatabaseUtils.closeDatabase()
            DatabaseUtils.initializeDatabase(context, original.session.databaseFilename)
            context.getSharedPreferences(session.preferencesNamespace + "_timer", 0).edit().clear().commit()
            listOf(file, File(file.path + "-wal"), File(file.path + "-shm"), File(file.path + "-journal")).forEach { it.delete() }
        }
    }
}
