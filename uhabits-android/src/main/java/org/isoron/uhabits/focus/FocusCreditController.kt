/* Copyright (C) 2026 Álinson Santos Xavier and contributors. GPL-3.0-or-later. */
package org.isoron.uhabits.focus

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.isoron.platform.time.getToday
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.containers.facade.HabitOrganizationFacade
import org.isoron.uhabits.core.contribution.sqlite.ContributionSchema
import org.isoron.uhabits.core.contribution.sqlite.SQLiteContributionRepository
import org.isoron.uhabits.core.focus.FocusSession
import org.isoron.uhabits.core.focus.FocusSessionCursor
import org.isoron.uhabits.core.focus.runtime.FocusCaptureJournal
import org.isoron.uhabits.core.focus.runtime.FocusCaptureStore
import org.isoron.uhabits.core.focus.sqlite.FocusSessionSchema
import org.isoron.uhabits.core.focus.sqlite.SQLiteFocusSessionRepository
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitList
import org.isoron.uhabits.core.models.projection.EffectiveHabitGoal
import org.isoron.uhabits.core.models.projection.EffectiveHabitValueService
import org.isoron.uhabits.core.preferences.Preferences
import org.isoron.uhabits.core.rules.*
import org.isoron.uhabits.core.rules.sqlite.SourceRuleSchema
import org.isoron.uhabits.core.rules.sqlite.SQLiteSourceRuleRepository
import org.isoron.uhabits.database.AndroidDatabase
import java.util.UUID

/** Dataset-owned main-thread bridge. No repository escapes into a worker or another dataset. */
class FocusCreditController(
    db: AndroidDatabase,
    private val habits: HabitList,
    private val organization: HabitOrganizationFacade,
    private val preferences: Preferences,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val rules: SQLiteSourceRuleRepository
    private val reconciler: FocusCreditReconciler
    private val contributions: SQLiteContributionRepository
    val configuration: SourceRuleConfigurationService
    private val observers = linkedSetOf<() -> Unit>()
    private var disposed = false
    private var recoveryQueued = false
    private var cursor: FocusSessionCursor? = null
    private val pending = linkedSetOf<org.isoron.uhabits.core.focus.FocusSessionId>()

    init {
        FocusSessionSchema.createSchema(db)
        ContributionSchema.createSchema(db)
        SourceRuleSchema.createSchema(db)
        rules = SQLiteSourceRuleRepository(db)
        contributions = SQLiteContributionRepository(db)
        configuration = SourceRuleConfigurationService(rules) { SourceRuleId(UUID.randomUUID().toString()) }
        val attribution = SourceRuleAttributionService(rules, contributions, sourceEligibility = { rule, fact ->
            val queries = organization.containerQueries
            val source = queries?.find(rule.sourceContainerId, includeDeleted = true)
            source != null && (source.deletedAt?.let { fact.endedAt < it }
                ?: !queries.isEffectivelyArchived(rule.sourceContainerId))
        }) { ref ->
            habits.getByUUID(ref.uuid)?.takeUnless { it.isArchived }?.let {
                TargetHabitInfo(it.isNumerical, it.unit, it.name)
            }
        }
        reconciler = FocusCreditReconciler(SQLiteFocusSessionRepository(db), attribution)
    }

    fun configure(habit: Habit, sourceContainerId: ContainerId?, includeDescendants: Boolean) {
        check(!disposed)
        if (sourceContainerId != null) {
            require(!habit.isArchived)
            require(organization.containerQueries?.find(sourceContainerId) != null)
            require(organization.containerQueries?.isEffectivelyArchived(sourceContainerId) != true)
        }
        configuration.configure(HabitRef(requireNotNull(habit.uuid)),
            TargetHabitInfo(habit.isNumerical, habit.unit, habit.name), sourceContainerId,
            includeDescendants, System.currentTimeMillis())
        notifyChanged()
    }

    fun goalProgress(habit: Habit): EffectiveHabitGoal {
        check(!disposed)
        return EffectiveHabitValueService(contributions, preferences.midnightDelayHours, 0, preferences.firstWeekday)
            .goalProgressOn(habit, getToday())
    }

    fun addObserver(observer: () -> Unit) { if (!disposed) observers.add(observer) }
    fun removeObserver(observer: () -> Unit) { observers.remove(observer) }
    private fun notifyChanged() { observers.toList().forEach { observer ->
        runCatching { observer() }.onFailure { Log.w(TAG, "Focus view refresh failed", it) }
    } }

    /** Facts commit first. Credit errors never roll back or prevent timer journal advancement. */
    fun captureStore(delegate: FocusCaptureStore): FocusCaptureStore = object : FocusCaptureStore {
        override fun load() = delegate.load()
        override fun commit(journal: FocusCaptureJournal, facts: List<FocusSession>) {
            check(!disposed)
            delegate.commit(journal, facts)
            if (facts.isEmpty()) return
            runCatching {
                closeUnavailableRules()
                facts.forEach { fact ->
                    runCatching { reconciler.reconcileSession(fact.id, System.currentTimeMillis()) }
                        .onFailure { pending.add(fact.id); Log.w(TAG, "Focus credit deferred until recovery", it) }
                }
                notifyChanged()
            }.onFailure {
                pending.addAll(facts.map { fact -> fact.id })
                Log.w(TAG, "Committed Focus credit deferred", it)
            }
            if (pending.isNotEmpty()) { handler.removeCallbacks(retry); handler.postDelayed(retry, 1000) }
        }
    }

    /** One startup sweep, 50 finalized facts per main-loop turn; no per-frame/history UI queries. */
    fun startRecovery() {
        if (disposed || recoveryQueued) return
        cursor = null
        recoveryQueued = true
        handler.post(recovery)
    }

    private val recovery = object : Runnable {
        override fun run() {
            if (disposed) return
            try {
                closeUnavailableRules()
                val page = reconciler.processPage(cursor, 50, System.currentTimeMillis())
                cursor = page.nextCursor
                if (page.created > 0) notifyChanged()
                page.failures.forEach { pending.add(it.sessionId); Log.w(TAG, "Focus recovery remains pending", it.error) }
                if (pending.isNotEmpty()) { handler.removeCallbacks(retry); handler.postDelayed(retry, 1000) }
                if (page.hasMore) handler.postDelayed(this, 16) else recoveryQueued = false
            } catch (error: Exception) {
                recoveryQueued = false
                Log.w(TAG, "Focus recovery will retry on next reopen/finalization", error)
            }
        }
    }

    /** One bounded retry per failure; persistent failures remain durable for the next reopen. */
    private val retry = Runnable {
        if (!disposed) {
            pending.toList().take(50).forEach { id ->
                runCatching { reconciler.reconcileSession(id, System.currentTimeMillis()) }
                    .onSuccess { pending.remove(id) }
                    .onFailure { Log.w(TAG, "Focus credit awaits next reopen", it) }
            }
            notifyChanged()
        }
    }

    /** Archive/delete ends future policy; durable attribution and rule identity remain. */
    private fun closeUnavailableRules() {
        val now = System.currentTimeMillis()
        rules.getAll().filter { it.isActive }.forEach { rule ->
            val source = organization.containerQueries
            if (source?.find(rule.sourceContainerId) == null ||
                source.isEffectivelyArchived(rule.sourceContainerId) ||
                habits.getByUUID(rule.targetHabit.uuid)?.isArchived != false) {
                rules.remove(rule.id, now)
            }
        }
    }

    fun dispose() {
        disposed = true
        handler.removeCallbacks(recovery)
        handler.removeCallbacks(retry)
        pending.clear()
        observers.clear()
        recoveryQueued = false
    }

    companion object { private const val TAG = "FocusCredit" }
}
