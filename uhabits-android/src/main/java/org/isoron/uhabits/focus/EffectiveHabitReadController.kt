/* Copyright (C) 2026 Álinson Santos Xavier and contributors. GPL-3.0-or-later. */
package org.isoron.uhabits.focus

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import org.isoron.platform.time.computeToday
import org.isoron.platform.time.getToday
import org.isoron.platform.time.setToday
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.contribution.sqlite.SQLiteContributionRepository
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitList
import org.isoron.uhabits.core.models.projection.*
import org.isoron.uhabits.core.preferences.Preferences
import org.isoron.uhabits.core.ui.screens.habits.list.HabitCardListCache
import org.isoron.uhabits.database.AndroidDatabase
import java.util.TimeZone

/** Read capability only: never constructs Focus capture, rules, schema or automatic writers. */
class EffectiveHabitReadController(
    private val context: Context,
    db: AndroidDatabase,
    private val preferences: Preferences,
    val revision: EffectiveReadRevision,
    private val cache: HabitCardListCache,
    private val habits: HabitList,
    private val notificationTray: org.isoron.uhabits.core.ui.NotificationTray? = null,
) : Preferences.Listener {
    private val provider = EffectiveHabitReadSnapshotProvider(SQLiteContributionRepository(db))
    private val handler = Handler(Looper.getMainLooper())
    private var disposed = false
    private var calendar = captureCalendar()
    private var restoreModels = false
    private data class SnapshotRequest(
        val refs: List<HabitRef>, val from: org.isoron.platform.time.LocalDate,
        val to: org.isoron.platform.time.LocalDate, val revision: Long,
        val calendar: EffectiveReadCalendarContext, val rollbackGeneration: Long,
        val compatibilityFirstWeekday: Int,
    )
    @Volatile private var rollbackGeneration = 0L
    // One dataset-owned immutable batch. Repeated attach/resume refreshes must not
    // rebuild the entire historical projection when its durable inputs are unchanged.
    private var cachedRequest: SnapshotRequest? = null
    private var cachedSnapshots: Map<HabitRef, EffectiveHabitReadSnapshot> = emptyMap()
    private val refresh = Runnable {
        if (!disposed) {
            if (restoreModels) { habits.forEach { it.recompute() }; restoreModels = false }
            cache.refreshAllHabits()
        }
    }
    private val changed: () -> Unit = {
        if (!disposed) {
            handler.removeCallbacks(refresh)
            handler.postDelayed(refresh, 200)
            notificationTray?.onEffectiveRevisionChanged()
        }
    }
    private val rollback: () -> Unit = {
        if (!disposed) {
            rollbackGeneration++
            habits.forEach { it.originalEntries.invalidate() }
            restoreModels = true
            changed()
        }
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { calendarChanged(force = true) }
    }

    init {
        cache.effectiveSnapshots = ::snapshots
        notificationTray?.effectiveSnapshotProvider = { habit ->
            try {
                snapshot(habit)
            } catch (t: Throwable) {
                android.util.Log.e("EffectiveHabitReadController", "Failed to compute snapshot for habit ${habit.id}", t)
                null
            }
        }
        revision.addListener(changed)
        revision.addRollbackListener(rollback)
        preferences.addListener(this)
        ContextCompat.registerReceiver(context, receiver, IntentFilter().apply {
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun captureCalendar() = EffectiveReadCalendarContext(
        TimeZone.getDefault().id, preferences.firstWeekday, preferences.midnightDelayHours, 0,
    )

    val currentCalendar: EffectiveReadCalendarContext get() = captureCalendar()

    @Synchronized
    fun snapshots(
        habits: List<Habit>,
        from: org.isoron.platform.time.LocalDate,
        to: org.isoron.platform.time.LocalDate
    ): Map<HabitRef, EffectiveHabitReadSnapshot> {
        if (disposed) throw EffectiveReadInvalidatedException()
        repeat(3) {
            val captured = captureCalendar()
            val capturedRevision = revision.value
            val capturedRollback = rollbackGeneration
            val capturedLocaleWeekday = org.isoron.platform.time.getFirstWeekdayNumberAccordingToLocale()
            val request = SnapshotRequest(habits.map { HabitRef(requireNotNull(it.uuid)) }, from, to,
                capturedRevision, captured, capturedRollback, capturedLocaleWeekday)
            if (request == cachedRequest && !disposed && captured == captureCalendar() &&
                capturedRevision == revision.value && capturedRollback == rollbackGeneration &&
                capturedLocaleWeekday == org.isoron.platform.time.getFirstWeekdayNumberAccordingToLocale()) return cachedSnapshots
            val result = provider.prepare(habits, from, to, revision.datasetId, capturedRevision, captured)
            if (!disposed && captured == captureCalendar() && capturedRevision == revision.value &&
                capturedRollback == rollbackGeneration && capturedLocaleWeekday == org.isoron.platform.time.getFirstWeekdayNumberAccordingToLocale()) {
                cachedRequest = request
                cachedSnapshots = result
                return result
            }
        }
        throw EffectiveReadInvalidatedException()
    }

    fun snapshots(habits: List<Habit>): Map<HabitRef, EffectiveHabitReadSnapshot> {
        val today = getToday()
        return snapshots(habits, today.minus(365), today)
    }
    fun snapshot(habit: Habit): EffectiveHabitReadSnapshot = snapshots(listOf(habit)).getValue(HabitRef(requireNotNull(habit.uuid)))

    private fun calendarChanged(force: Boolean = false) {
        if (disposed) return
        val current = captureCalendar()
        if (force || current != calendar) {
            calendar = current
            setToday(computeToday(current.hourOffset, current.minuteOffset))
            revision.changed()
        }
    }
    override fun onCheckmarkSequenceChanged() = calendarChanged()
    override fun onHabitListAppearanceChanged() = calendarChanged()
    override fun onSyncFinished() = calendarChanged()
    @Synchronized
    fun dispose() {
        if (disposed) return
        disposed = true
        cachedRequest = null
        cachedSnapshots = emptyMap()
        notificationTray?.effectiveSnapshotProvider = null
        handler.removeCallbacksAndMessages(null)
        cache.effectiveSnapshots = null
        revision.removeListener(changed)
        revision.removeRollbackListener(rollback)
        revision.dispose()
        preferences.removeListener(this)
        context.unregisterReceiver(receiver)
    }
}
