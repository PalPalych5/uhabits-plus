package org.isoron.uhabits.core.ui

import org.isoron.platform.time.*
import org.isoron.uhabits.core.BaseUnitTest
import org.isoron.uhabits.core.commands.*
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.contribution.*
import org.isoron.uhabits.core.contribution.memory.MemoryContributionRepository
import org.isoron.uhabits.core.models.*
import org.isoron.uhabits.core.models.projection.*
import org.isoron.uhabits.core.preferences.MemoryStorage
import org.isoron.uhabits.core.preferences.Preferences
import org.isoron.uhabits.core.tasks.CoroutineTaskRunner
import kotlin.test.*

class NotificationTrayTest : BaseUnitTest() {
    private val monday = LocalDate(2026, 10, 5)
    private val repository = MemoryContributionRepository()
    private val preferences = Preferences(MemoryStorage())
    private lateinit var systemTray: TestSystemTray
    private lateinit var tray: NotificationTray
    private var sequence = 0

    @BeforeTest
    fun setup() {
        setToday(monday)
        systemTray = TestSystemTray()
        tray = NotificationTray(taskRunner, commandRunner, preferences, systemTray)
        tray.startListening()
        tray.effectiveSnapshotProvider = { habit -> read(habit) }
    }

    private fun habit(targetType: NumericalHabitType = NumericalHabitType.AT_LEAST): Habit {
        val h = modelFactory.buildHabit().apply {
            id = (++sequence).toLong()
            uuid = "habit-$sequence"
            name = "Study"
            type = HabitType.NUMERICAL
            unit = "min"
            frequency = Frequency(1, 7)
            this.targetType = targetType
            targetValue = 300.0
            reminder = Reminder(8, 0, WeekdayList(0x7F)) // Every day
        }
        habitList.add(h)
        return h
    }

    private fun credit(h: Habit, date: LocalDate = monday, value: Long): Contribution {
        val id = "credit-${++sequence}"
        val timestamp = startOfLocalDay(date) + 1000
        return Contribution(
            ContributionId(id),
            HabitRef(h.uuid!!),
            ContributionSourceType.FOCUS_SESSION,
            id,
            value,
            timestamp,
            timestamp,
            timestamp
        ).also(repository::record)
    }

    private fun read(
        h: Habit,
        calendar: EffectiveReadCalendarContext = EffectiveReadCalendarContext("local", DayOfWeek.MONDAY, 0, 0)
    ): EffectiveHabitReadSnapshot {
        val provider = EffectiveHabitReadSnapshotProvider(repository)
        return provider.prepare(listOf(h), monday.minus(30), getToday().plus(7), "notifications", 1, calendar)
            .getValue(HabitRef(h.uuid!!))
    }

    @Test
    fun canonicalFixture_weekly330SuppressesThen240Unsuppresses() {
        val h = habit()
        h.originalEntries.add(Entry(monday, 60000)) // 60 min manual
        h.recompute()
        credit(h, monday, 180000) // 180 min
        val c90 = credit(h, monday, 90000) // 90 min -> Total = 330 min >= 300 min

        // Alarm fires for today (monday)
        tray.show(h, monday, 1000L)

        // Effective total is 330 min >= 300 min -> completed -> reminder suppressed!
        assertFalse(systemTray.isShowing(h), "Notification should be suppressed when 330m >= 300m")
        assertFalse(tray.isShowing(h))

        // Tombstone the 90 min contribution using repository API
        repository.removeById(c90.id, startOfLocalDay(monday) + 2000)

        // Effective total is now 240 min < 300 min -> not completed -> reminder unsuppressed!
        tray.show(h, monday, 1000L)
        assertTrue(systemTray.isShowing(h), "Notification should be shown when 240m < 300m")
        assertTrue(tray.isShowing(h))
    }

    @Test
    fun canonicalFixture_multiDayGoalAchievementDoesNotSuppressTodayReminder() {
        val h = habit()
        val wednesday = monday.plus(2)
        setToday(wednesday)

        // Mon = 180 min, Tue = 90 min, Wed (today) = 60 min. Total week = 330 min.
        credit(h, monday, 180000)
        credit(h, monday.plus(1), 90000)
        h.originalEntries.add(Entry(wednesday, 60000))
        h.recompute()

        val snapshot = read(h)
        // Week's goal achievement is satisfied
        assertEquals(HabitGoalStatus.SATISFIED, snapshot.goalAchievementOn(wednesday).status)
        // But today's compatibility completion is not satisfied (60 min < 300 min target)
        assertEquals(HabitGoalStatus.NOT_SATISFIED, snapshot.compatibilityCompletionOn(wednesday).status)

        tray.show(h, wednesday, 1000L)
        assertTrue(systemTray.isShowing(h), "Today's reminder should fire despite prior days meeting weekly goal")
    }

    @Test
    fun liveInvalidation_activeNotificationDismissedWhenTargetReached() {
        val h = habit()
        h.originalEntries.add(Entry(monday, 60000))
        h.recompute()
        credit(h, monday, 180000) // Total = 240 min < 300 min

        tray.show(h, monday, 1000L)
        assertTrue(systemTray.isShowing(h), "Initial reminder should be showing at 240 min")

        // Add 90 min contribution, bringing total to 330 min
        credit(h, monday, 90000)
        // Revision change triggers tray refresh
        tray.onEffectiveRevisionChanged()

        assertFalse(systemTray.isShowing(h), "Active notification should be dismissed once target is achieved")
        assertFalse(tray.isShowing(h))
    }

    @Test
    fun noContributionParity_booleanAndNumerical() {
        // Boolean habit
        val b = modelFactory.buildHabit().apply {
            id = (++sequence).toLong()
            uuid = "bool-$sequence"
            name = "Exercise"
            type = HabitType.YES_NO
            frequency = Frequency(1, 1)
            reminder = Reminder(8, 0, WeekdayList(0x7F))
        }
        habitList.add(b)

        tray.show(b, monday, 1000L)
        assertTrue(systemTray.isShowing(b), "Incomplete boolean habit shows reminder")

        // Mark completed via command
        commandRunner.run(CreateRepetitionCommand(habitList, b, monday, Entry.YES_MANUAL, ""))
        assertFalse(systemTray.isShowing(b), "Completed boolean habit suppresses/cancels reminder")

        // Numerical habit without contributions
        val n = habit()
        n.targetValue = 60.0
        n.originalEntries.add(Entry(monday, 50000)) // 50 min < 60 min
        n.recompute()

        tray.show(n, monday, 1000L)
        assertTrue(systemTray.isShowing(n), "Numerical habit below target shows reminder")

        // Mark numerical habit completed via command
        commandRunner.run(CreateRepetitionCommand(habitList, n, monday, 60000, ""))
        assertFalse(systemTray.isShowing(n), "Numerical habit reaching target suppresses/cancels reminder")
    }

    @Test
    fun contributionOnlyDate_suppressesWhenTargetMet() {
        val h = habit()
        // No manual entry logged (Entry.UNKNOWN). Contribution = 350 min >= 300 min.
        credit(h, monday, 350000)

        tray.show(h, monday, 1000L)
        assertFalse(systemTray.isShowing(h), "Contribution-only date meeting target suppresses reminder")
    }

    @Test
    fun unknownWithSufficientAtLeastLowerBound_suppresses() {
        val h = habit(NumericalHabitType.AT_LEAST)
        // Manual entry is missing/unknown. Contributions alone = 320 min >= 300 min target.
        credit(h, monday, 320000)

        tray.show(h, monday, 1000L)
        assertFalse(systemTray.isShowing(h), "Sufficient lower bound (320m >= 300m) suppresses reminder")
    }

    @Test
    fun unknownWithInsufficientLowerBound_doesNotSuppress() {
        val h = habit(NumericalHabitType.AT_LEAST)
        // Contributions alone = 100 min < 300 min target. Manual entry missing/unknown.
        credit(h, monday, 100000)

        tray.show(h, monday, 1000L)
        assertTrue(systemTray.isShowing(h), "Insufficient lower bound (100m < 300m) does not suppress reminder")
    }

    @Test
    fun atMostException_neverSuppressedByCompletion() {
        val h = habit(NumericalHabitType.AT_MOST)
        h.targetValue = 60.0
        // Effective = 30 min <= 60 min (satisfied under AT_MOST)
        credit(h, monday, 30000)

        tray.show(h, monday, 1000L)
        assertTrue(systemTray.isShowing(h), "AT_MOST reminders are never suppressed by completion")
    }

    @Test
    fun explicitSkipAndExplicitZero_doNotSuppress() {
        val h = habit()
        h.originalEntries.add(Entry(monday, Entry.SKIP))
        h.recompute()

        tray.show(h, monday, 1000L)
        assertTrue(systemTray.isShowing(h), "SKIP entry does not suppress reminder")

        val hZero = habit()
        hZero.originalEntries.add(Entry(monday, 0))
        hZero.recompute()

        tray.show(hZero, monday, 1000L)
        assertTrue(systemTray.isShowing(hZero), "Explicit zero entry does not suppress reminder")
    }

    @Test
    fun historicalGoalChange_updatesReminderDecision() {
        val h = habit()
        h.targetValue = 100.0 // Initially 100 min
        h.originalEntries.add(Entry(monday, 150000)) // 150 min (meets 100 min)
        h.recompute()

        tray.show(h, monday, 1000L)
        assertFalse(systemTray.isShowing(h), "Initially completed at 150m >= 100m")

        // Edit goal: increase target to 400 min starting today
        val modified = modelFactory.buildHabit().apply {
            copyFrom(h)
            targetValue = 400.0
        }
        commandRunner.run(EditHabitGoalCommand(habitList, h.id!!, modified, GoalApplyScope.FROM_DATE, monday))

        // Trigger show again with updated goal
        tray.show(h, monday, 1000L)
        assertTrue(systemTray.isShowing(h), "Now incomplete under new 400m target, reminder shows")
    }

    @Test
    fun archiveAndDelete_cancelsActiveNotification() {
        val h = habit()
        tray.show(h, monday, 1000L)
        assertTrue(systemTray.isShowing(h))

        commandRunner.run(ArchiveHabitsCommand(habitList, listOf(h)))
        assertFalse(systemTray.isShowing(h), "Archived habit notification is cancelled")

        val h2 = habit()
        tray.show(h2, monday, 1000L)
        assertTrue(systemTray.isShowing(h2))

        commandRunner.run(DeleteHabitsCommand(habitList, listOf(h2)))
        assertFalse(systemTray.isShowing(h2), "Deleted habit notification is cancelled")
    }

    @Test
    fun cancelAll_removesAllActiveNotifications() {
        val h1 = habit()
        val h2 = habit()
        tray.show(h1, monday, 1000L)
        tray.show(h2, monday, 1000L)
        assertTrue(systemTray.isShowing(h1))
        assertTrue(systemTray.isShowing(h2))

        tray.cancelAll()
        assertFalse(systemTray.isShowing(h1))
        assertFalse(systemTray.isShowing(h2))
        assertFalse(tray.isShowing(h1))
        assertFalse(tray.isShowing(h2))
    }

    class TestSystemTray : NotificationTray.SystemTray {
        val shown = mutableListOf<NotificationRecord>()
        val removed = mutableListOf<Int>()
        val logs = mutableListOf<String>()

        data class NotificationRecord(
            val habit: Habit,
            val notificationId: Int,
            val date: LocalDate,
            val reminderTime: Long
        )

        override fun removeNotification(notificationId: Int) {
            removed.add(notificationId)
            shown.removeAll { it.notificationId == notificationId }
        }

        override fun showNotification(
            habit: Habit,
            notificationId: Int,
            date: LocalDate,
            reminderTime: Long
        ) {
            shown.add(NotificationRecord(habit, notificationId, date, reminderTime))
        }

        override fun log(msg: String) {
            logs.add(msg)
        }

        fun isShowing(habit: Habit): Boolean = shown.any { it.habit.id == habit.id }
    }
}
