package org.isoron.uhabits.sync

import me.tatarka.inject.annotations.Inject
import org.isoron.uhabits.core.userTasks.normalizeTaskPlannedDateForSync
import org.isoron.uhabits.core.userTasks.parseTaskPlannedDate
import org.isoron.uhabits.core.database.EntryData
import org.isoron.uhabits.core.database.HabitBlockData
import org.isoron.uhabits.core.database.HabitData
import org.isoron.uhabits.core.database.HabitExtensionData
import org.isoron.uhabits.core.database.HabitGoalData
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.sqlite.SQLModelFactory
import org.isoron.uhabits.core.models.sqlite.SQLiteHabitList
import org.isoron.uhabits.core.preferences.Preferences
import org.isoron.uhabits.widgets.WidgetUpdater
import org.isoron.uhabits.core.sync.SyncInboxRecord
import org.isoron.uhabits.core.sync.SyncInboxSchema
import org.isoron.uhabits.core.sync.SyncQueueRecord
import org.isoron.platform.io.begin
import org.isoron.platform.io.commit
import org.isoron.platform.io.PreparedStatement
import org.isoron.platform.io.query
import org.isoron.platform.io.queryLong
import org.isoron.platform.io.querySingle
import org.isoron.platform.io.run
import org.isoron.platform.io.runInTransaction
import org.isoron.platform.time.getToday
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import android.util.Log
import android.content.Context
import org.isoron.uhabits.inject.AppContext
import org.isoron.uhabits.R

import org.isoron.uhabits.core.AppScope
import org.isoron.uhabits.core.commands.Command
import org.isoron.uhabits.core.commands.CommandRunner
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel

private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)



@AppScope
@Inject


class SyncCoordinator(
    @AppContext private val context: Context,
    private val modelFactory: SQLModelFactory,
    private val habitList: SQLiteHabitList,
    private val preferences: Preferences,
    private val authStore: SyncAuthStore,
    private val backend: SyncBackend,
    private val deviceIdProvider: DeviceIdProvider,
    private val widgetUpdater: WidgetUpdater,
    private val commandRunner: CommandRunner,
    val session: org.isoron.uhabits.core.containers.session.DatasetSession = org.isoron.uhabits.core.containers.session.DatasetSessionFactory.production()
) {
    interface Listener {
        fun onSyncStateChanged(isSyncing: Boolean, lastResult: SyncRunResult?)
    }

    private val syncListeners = mutableListOf<Listener>()

    fun addListener(l: Listener) {
        synchronized(syncListeners) {
            if (!syncListeners.contains(l)) {
                syncListeners.add(l)
            }
        }
    }

    fun removeListener(l: Listener) {
        synchronized(syncListeners) {
            syncListeners.remove(l)
        }
    }

    private fun notifySyncStateChanged(isSyncing: Boolean, lastResult: SyncRunResult? = null) {
        val targets = synchronized(syncListeners) { syncListeners.toList() }
        targets.forEach { it.onSyncStateChanged(isSyncing, lastResult) }
    }

    @Volatile
    var isSyncing: Boolean = false
        private set

    @Volatile
    var isAutoSyncPaused: Boolean = false

    private var lastLocalChangeSyncTime = 0L
    private var isPendingSync = false
    private var syncJob: kotlinx.coroutines.Job? = null
    private val syncScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
    // Account/dataset transitions and local application share this coordinator's monitor.
    private var syncContextGeneration = 0L
    private var disposed = false

    private class SyncContextChanged : IllegalStateException("Sync context changed")

    private fun <T> inSyncContext(generation: Long, userId: String, block: () -> T): T =
        synchronized(this) {
            if (disposed || generation != syncContextGeneration || authStore.load()?.userId != userId) {
                throw SyncContextChanged()
            }
            block()
        }

    init {
        SyncInboxSchema.createSchema(modelFactory.database)
        val existingDbCursor = modelFactory.syncStateRepository.getCursor()
        if (existingDbCursor == null && preferences.syncLastLogId > 0L) {
            modelFactory.syncStateRepository.setCursor(preferences.syncLastLogId, System.currentTimeMillis())
        }

        if (session.capabilities.syncEnabled && session.mode != org.isoron.uhabits.core.containers.session.DatasetMode.CONTAINER_LOCAL_EXPERIMENTAL) {
            modelFactory.syncManager.onQueueAppended = {
                if (isAutoSyncPaused) {
                    // paused
                } else if (isSyncing) {
                    synchronized(this@SyncCoordinator) { isPendingSync = true }
                } else {
                    scheduleBackgroundSync("queue_appended")
                }
            }
            commandRunner.addListener(object : CommandRunner.Listener {
            override fun onCommandFinished(command: Command) {
                if (isAutoSyncPaused) return
                if (command is org.isoron.uhabits.core.commands.CreateRepetitionCommand ||
                    command is org.isoron.uhabits.core.commands.AddNumericalEntryOpCommand ||
                    command is org.isoron.uhabits.core.commands.BatchCreateRepetitionCommand ||
                    command is org.isoron.uhabits.core.commands.CreateHabitCommand ||
                    command is org.isoron.uhabits.core.commands.EditHabitCommand ||
                    command is org.isoron.uhabits.core.commands.DeleteHabitsCommand ||
                    command is org.isoron.uhabits.core.commands.ArchiveHabitsCommand ||
                    command is org.isoron.uhabits.core.commands.UnarchiveHabitsCommand ||
                    command is org.isoron.uhabits.core.commands.ClearHabitEntriesCommand ||
                    command is org.isoron.uhabits.core.commands.ClearAllEntriesCommand ||
                    command is org.isoron.uhabits.core.commands.EditHabitGoalCommand
                ) {
                    if (isSyncing) {
                        synchronized(this@SyncCoordinator) { isPendingSync = true }
                    } else {
                        scheduleBackgroundSync("local_change")
                    }
                }
            }
        })
        preferences.addListener(object : Preferences.Listener {
            override fun onSyncPreferencesChanged() {
                if (isSyncReady()) {
                    val hasPending = try {
                        modelFactory.syncQueueRepository.findPending().isNotEmpty()
                    } catch (e: Exception) {
                        false
                    }
                    if (hasPending) {
                        scheduleBackgroundSync("preferences_changed")
                    }
                }
                notifySyncStateChanged(isSyncing, null)
            }
        })
        }
    }

    fun hasPendingLocalChanges(): Boolean {
        return runCatching {
            modelFactory.syncQueueRepository.findPending().isNotEmpty()
        }.getOrDefault(false)
    }

    fun scheduleBackgroundSyncIfHasChanges(reason: String) {
        if (!isSyncReady()) return
        syncScope.launch {
            if (hasPendingLocalChanges()) {
                scheduleBackgroundSync(reason)
            }
        }
    }

    fun scheduleBackgroundSync(reason: String = "command_runner") {
        if (!session.capabilities.syncEnabled || !session.capabilities.remoteCallsAllowed || session.isExperimental) {
            return
        }
        if (!isSyncReady()) {
            return
        }
        preferences.syncLastBackgroundSyncReason = reason
        preferences.syncLastBackgroundSyncScheduledAt = System.currentTimeMillis()
        synchronized(this) {
            isPendingSync = true
            if (syncJob?.isActive == true) {
                return
            }
            syncJob = syncScope.launch {
                while (true) {
                    var shouldRun = false
                    synchronized(this@SyncCoordinator) {
                        if (isPendingSync) {
                            shouldRun = true
                            isPendingSync = false
                        }
                    }
                    if (!shouldRun) break

                    kotlinx.coroutines.delay(10_000L) // 10s debounce

                    val now = System.currentTimeMillis()
                    val timeSinceLast = now - lastLocalChangeSyncTime
                    val throttleLimit = 30_000L // 30s throttle
                    if (timeSinceLast < throttleLimit) {
                        kotlinx.coroutines.delay(throttleLimit - timeSinceLast)
                    }

                    if (!isSyncReady()) {
                        break
                    }

                    if (isSyncing) {
                        synchronized(this@SyncCoordinator) {
                            isPendingSync = true
                        }
                        kotlinx.coroutines.delay(5_000L)
                        continue
                    }

                    lastLocalChangeSyncTime = System.currentTimeMillis()
                    runSync(manual = false, allowAfterReview = false, ignoreThrottle = true)
                }
            }
        }
    }
    private var lastSyncAttemptTime = 0L

    companion object {
        private const val GLOBAL_STATS_START_KEY = "global_stats_start_timestamp"
        private const val DEFAULT_BLOCK_MAX_ID = 7L
        private const val THROTTLE_INTERVAL_MS = 180_000L // 3 minutes

        /**
         * Deterministic stable UUIDs for the 7 built-in default spheres.
         * These are fixed constants — all devices must use the same UUID for the same built-in sphere.
         * This eliminates the per-device random UUID assigned by migration 29.
         * Format: "default:" + stable key (no hyphens, lowercase).
         */
        val DETERMINISTIC_DEFAULT_BLOCK_UUIDS: Map<Long, String> = mapOf(
            1L to "default:intellect-learning",
            2L to "default:speech-thinking",
            3L to "default:body",
            4L to "default:hygiene-care",
            5L to "default:mode-reflection",
            6L to "default:limits-self-control",
            7L to "default:other"
        )
    }
    fun isSignedIn(): Boolean = authStore.load() != null

    enum class SyncReadyReason {
        READY,
        SYNC_DISABLED,
        CONFIG_MISSING,
        NOT_SIGNED_IN,
        AUTH_SESSION_UNAVAILABLE,
        ALREADY_SYNCING
    }

    fun syncReadyReason(): SyncReadyReason {
        if (!session.capabilities.syncEnabled || !session.capabilities.remoteCallsAllowed || session.isExperimental) {
            return SyncReadyReason.SYNC_DISABLED
        }
        if (!session.isNextArchitecture && modelFactory.habitBlockRepository.authorityMode == org.isoron.uhabits.core.containers.facade.OrganizationAuthorityMode.CONTAINER_LOCAL) {
            return SyncReadyReason.SYNC_DISABLED
        }
        if (!preferences.isSyncEnabled) return SyncReadyReason.SYNC_DISABLED
        if (requireConfig() == null) return SyncReadyReason.CONFIG_MISSING
        if (!isSignedIn()) return SyncReadyReason.NOT_SIGNED_IN
        if (authStore.load() == null) return SyncReadyReason.AUTH_SESSION_UNAVAILABLE
        if (isSyncing) return SyncReadyReason.ALREADY_SYNCING
        return SyncReadyReason.READY
    }

    fun isSyncReady(): Boolean {
        val reason = syncReadyReason()
        return reason == SyncReadyReason.READY || reason == SyncReadyReason.ALREADY_SYNCING
    }

    fun currentAccountEmail(): String? =
        runCatching { authStore.load()?.email ?: preferences.syncAccountEmail }.getOrNull()

    fun buildDiagnosticsReport(appVersionName: String, appVersionCode: Int): String {
        val root = JSONObject()
        root.put("generated_at_epoch_ms", System.currentTimeMillis())
        root.put(
            "app",
            JSONObject()
                .put("version_name", appVersionName)
                .put("version_code", appVersionCode)
        )
        root.put("device_id", deviceIdProvider.value())
        root.put("account_email", currentAccountEmail() ?: JSONObject.NULL)
        root.put("sync_enabled", preferences.isSyncEnabled)
        val ready = isSyncReady()
        root.put("sync_ready", ready)
        root.put("sync_ready_reason", syncReadyReason().name.lowercase())
        root.put("has_supabase_url", preferences.syncSupabaseUrl.isNotBlank())
        root.put("has_supabase_key", preferences.syncSupabaseAnonKey.isNotBlank())
        root.put("has_access_token", authStore.load()?.accessToken?.isNotBlank() == true)
        root.put("pending_sync_queue_count", runCatching { modelFactory.syncQueueRepository.findPending().size }.getOrDefault(0))
        root.put("last_background_sync_reason", preferences.syncLastBackgroundSyncReason)
        root.put("last_background_sync_scheduled_at", preferences.syncLastBackgroundSyncScheduledAt)
        root.put("last_background_sync_result", preferences.syncLastBackgroundSyncResult.ifBlank { JSONObject.NULL })
        root.put("bootstrap_queued", preferences.isSyncBootstrapQueued)
        root.put("bootstrap_done", preferences.isSyncBootstrapDone)
        root.put("sync_last_log_id", preferences.syncLastLogId)
        root.put("sync_durable_cursor", modelFactory.syncStateRepository.getCursor() ?: JSONObject.NULL)
        root.put("sync_inbox_pending_count", modelFactory.syncInboxRepository.count())
        root.put("sync_account_user_id", modelFactory.syncStateRepository.getAccountUserId() ?: JSONObject.NULL)
        root.put("sync_status", preferences.syncStatus)
        root.put("sync_status_detail", preferences.syncStatusDetail)
        root.put(
            "last_ui_refresh_trigger",
            JSONObject()
                .put("reason", preferences.syncLastUiRefreshReason)
                .put("timestamp", preferences.syncLastUiRefreshAt)
                .put("destination", preferences.syncLastUiRefreshDestination.ifBlank { JSONObject.NULL })
                .put("habit_count_seen", preferences.syncLastUiRefreshHabitCount)
        )
        root.put("is_syncing", isSyncing)
        val allBlocks = modelFactory.habitBlockRepository.findAll()
        val duplicateBlocks = mutableListOf<String>()
        val defaultBlockNames = allBlocks.filter { (it.id ?: 0L) <= DEFAULT_BLOCK_MAX_ID }.map { it.name }.toSet()
        for (block in allBlocks) {
            val id = block.id ?: continue
            if (id > DEFAULT_BLOCK_MAX_ID && defaultBlockNames.contains(block.name)) {
                duplicateBlocks.add("id=$id, name=${block.name}, uuid=${block.uuid}, refs=${referencedHabitCount(id)}")
            }
        }
        root.put("duplicate_default_block_candidates", JSONArray(duplicateBlocks))
        root.put("last_sync_diagnostics", lastSyncDiagnostics())
        root.putSection("sync_queue_counts_by_type") { syncQueueCountsByType() }
        root.putSection("sync_queue_unpushed_or_failed") { unpushedOrFailedQueueRows() }
        root.putSection("entry_ops_by_habit_date") { entryOpsByHabitDate() }
        root.putSection("habits") { habitsReport() }
        root.putSection("entries") { entriesReport() }
        root.putSection("goals") { goalsReport() }
        root.putSection("blocks") { blocksReport() }
        root.putSection("habit_block_assignments") { habitBlockAssignmentsReport() }
        root.putSection("unresolved_habit_block_applies") {
            val arr = JSONArray()
            synchronized(lastUnresolvedHabitBlocks) {
                for (item in lastUnresolvedHabitBlocks) {
                    arr.put(
                        JSONObject()
                            .put("remote_habit_uuid", item.habitUuid)
                            .put("remote_block_uuid", item.remoteBlockUuid)
                            .put("event_log_id", item.eventLogId ?: JSONObject.NULL)
                            .put("reason", "block_uuid_not_found_locally")
                    )
                }
            }
            arr
        }
        root.putSection("raw_vs_computed_state") { rawVsComputedReport() }
        return root.toString(2)
    }

    fun refreshLocalViewsForDiagnostics(reason: String = "sync_coordinator_refresh") {
        val activeHabits = activeHabitCount()
        habitList.reloadAndNotify()
        widgetUpdater.updateWidgets()
        preferences.syncLastUiRefreshReason = reason
        preferences.syncLastUiRefreshAt = System.currentTimeMillis()
        preferences.syncLastUiRefreshDestination = "no_activity"
        preferences.syncLastUiRefreshHabitCount = activeHabits
        preferences.notifySyncFinished()
    }

    suspend fun signIn(email: String, password: String): SyncRunResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        if (!session.capabilities.remoteCallsAllowed || !session.capabilities.syncEnabled || session.isExperimental) {
            return@withContext configurationMissing("Синхронизация отключена в текущей сессии.")
        }
        val config = requireConfig()
            ?: return@withContext configurationMissing("Укажите Supabase URL и anon key в режиме разработчика.")
        return@withContext runCatching {
            val session = backend.signIn(config, email.trim(), password)
            synchronized(this@SyncCoordinator) {
                if (disposed) throw SyncContextChanged()
                syncContextGeneration++
                authStore.save(session)
                val storedUserId = modelFactory.syncStateRepository.getAccountUserId()
                if (storedUserId != null && storedUserId != session.userId) {
                    modelFactory.database.runInTransaction {
                        modelFactory.syncStateRepository.clear()
                        modelFactory.syncInboxRepository.clearAll()
                        modelFactory.syncTombstoneRepository.clearAll()
                        modelFactory.syncStateRepository.setAccountUserId(session.userId, System.currentTimeMillis())
                    }
                    preferences.syncLastLogId = 0L
                } else {
                    modelFactory.syncStateRepository.setAccountUserId(session.userId, System.currentTimeMillis())
                }
                preferences.syncAccountEmail = session.email ?: email.trim()
                preferences.syncStatus = "idle"
                preferences.syncStatusDetail = ""
                preferences.isSyncBootstrapQueued = false
                preferences.isSyncBootstrapDone = false
                preferences.syncLastLogId = modelFactory.syncStateRepository.getCursor() ?: 0L
                SyncRunResult.Success(0, 0)
            }
        }.getOrElse {
            syncUnavailable("Не удалось войти в аккаунт синхронизации.", it)
        }
    }

    suspend fun signOut(): SyncRunResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        if (!session.capabilities.remoteCallsAllowed || !session.capabilities.syncEnabled || session.isExperimental) {
            return@withContext syncUnavailable("Синхронизация отключена в текущей сессии.", IllegalStateException("Sync disabled in session"))
        }
        return@withContext runCatching {
            val config = requireConfig()
            val session = synchronized(this@SyncCoordinator) {
                syncContextGeneration++
                val oldSession = authStore.load()
                authStore.clear()
                preferences.syncAccountEmail = null
                preferences.syncStatus = "signed_out"
                preferences.syncStatusDetail = ""
                preferences.isSyncEnabled = false
                preferences.isSyncBootstrapQueued = false
                preferences.isSyncBootstrapDone = false
                preferences.syncLastLogId = 0L
                modelFactory.syncStateRepository.clear()
                modelFactory.syncInboxRepository.clearAll()
                oldSession
            }
            runCatching {
                if (session != null && config != null) backend.signOut(session, config)
            }
            SyncRunResult.Success(0, 0)
        }.getOrElse {
            syncUnavailable("Не удалось отключить синхронизацию на этом устройстве.", it)
        }
    }

    suspend fun runSync(manual: Boolean, allowAfterReview: Boolean = false, ignoreThrottle: Boolean = false): SyncRunResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        if (!session.capabilities.syncEnabled || !session.capabilities.remoteCallsAllowed || session.mode == org.isoron.uhabits.core.containers.session.DatasetMode.CONTAINER_LOCAL_EXPERIMENTAL) {
            return@withContext if (manual) {
                val rawMsg: String? = try {
                    context.getString(R.string.sync_status_disabled)
                } catch (t: Throwable) {
                    null
                }
                val userMsg = rawMsg ?: "Sync is disabled"
                SyncRunResult.Failure(userMsg, "sync_disabled_in_session")
            } else {
                SyncRunResult.Skipped("sync_disabled_in_session")
            }
        }
        val readyReason = syncReadyReason()
        if (readyReason != SyncReadyReason.READY) {
            return@withContext if (manual) {
                val userMsg = when (readyReason) {
                    SyncReadyReason.SYNC_DISABLED -> context.getString(R.string.sync_status_disabled)
                    SyncReadyReason.CONFIG_MISSING -> context.getString(R.string.sync_error_config_missing)
                    SyncReadyReason.NOT_SIGNED_IN -> context.getString(R.string.sync_signed_out)
                    SyncReadyReason.AUTH_SESSION_UNAVAILABLE -> context.getString(R.string.sync_error_session_unavailable)
                    SyncReadyReason.ALREADY_SYNCING -> context.getString(R.string.sync_error_already_syncing)
                    else -> context.getString(R.string.sync_status_error, "Unknown state")
                }
                val techMsg = readyReason.name.lowercase()
                SyncRunResult.Failure(userMsg, techMsg)
            } else {
                SyncRunResult.Skipped("not_ready:${readyReason.name.lowercase()}")
            }
        }

        if (preferences.isSyncReviewRequired && !allowAfterReview) {
            return@withContext SyncRunResult.Skipped("review_required")
        }

        if (!manual && !ignoreThrottle) {
            val now = System.currentTimeMillis()
            if (now - lastSyncAttemptTime < THROTTLE_INTERVAL_MS) {
                return@withContext SyncRunResult.Skipped("throttled")
            }
        }
        if (!manual) {
            lastSyncAttemptTime = System.currentTimeMillis()
        }

        val config = requireConfig() ?: return@withContext SyncRunResult.Skipped("not_configured")
        var session = authStore.load()
            ?: return@withContext SyncRunResult.Failure("Сначала войдите в аккаунт синхронизации.", "No session")

        val runGeneration = synchronized(this@SyncCoordinator) {
            if (disposed) return@withContext SyncRunResult.Skipped("sync_context_changed")
            if (isSyncing) {
                return@withContext if (manual) {
                    SyncRunResult.Failure(
                        context.getString(R.string.sync_error_already_syncing),
                        "already_syncing"
                    )
                } else {
                    SyncRunResult.Skipped("already_syncing")
                }
            }
            session = authStore.load()
                ?: return@withContext SyncRunResult.Skipped("sync_context_changed")
            isSyncing = true
            syncContextGeneration
        }
        notifySyncStateChanged(true)

        var result: SyncRunResult? = null
        try {
            val res = runCatching {
                inSyncContext(runGeneration, session.userId) {
                    preferences.syncStatus = "syncing"
                    preferences.syncStatusDetail = ""
                }

                if (session.expiresAtEpochSeconds in 1 until (System.currentTimeMillis() / 1000L + 60L)) {
                    session = backend.refreshSession(config, session)
                    inSyncContext(runGeneration, session.userId) { authStore.save(session) }
                }

                val (bootstrapResult, pending) = inSyncContext(runGeneration, session.userId) {
                    repairDefaultBlockUuids()

                    val bootstrapResult = ensureBootstrapSyncQueue()

                    val unresolvedTasks = mutableMapOf<String, Boolean>()
                    val pending = modelFactory.syncQueueRepository.findPending().mapNotNull { record ->
                        if (record.entityType != "user_task" || record.operationType == "delete") record
                        else if (unresolvedTasks.getOrPut(record.entityUuid) { hasUnsupportedTaskPlan(record.entityUuid) }) null
                        else try {
                            val payload = JSONObject(record.payloadJson)
                            if (!payload.has("due_kind")) {
                                val localTask = modelFactory.database.querySingle(
                                    "SELECT due_kind, due_date, due_time_millis, due_zone FROM UserTasks WHERE id = ?",
                                    record.entityUuid
                                ) { stmt ->
                                    Quad(stmt.getText(0), stmt.getTextOrNull(1), stmt.getLongOrNull(2), stmt.getTextOrNull(3))
                                }
                                if (localTask != null) {
                                    payload.put("due_kind", localTask.first)
                                    payload.put("due_date", localTask.second ?: JSONObject.NULL)
                                    payload.put("due_time_millis", localTask.third ?: JSONObject.NULL)
                                    payload.put("due_zone", localTask.fourth ?: JSONObject.NULL)
                                }
                            }
                            val raw = payload.optionalString("planned_date")
                            val canonical = normalizeTaskPlannedDateForSync(raw)
                            // Transport copy only: preserve queue identity/order and the stored retry payload.
                            if (canonical == raw) record else record.copy(
                                payloadJson = payload.put("planned_date", canonical ?: JSONObject.NULL).toString()
                            )
                        } catch (_: IllegalArgumentException) {
                            null // Retain an older unreadable queued payload without blocking other tasks.
                        } catch (_: IndexOutOfBoundsException) {
                            null
                        }
                    }
                    Pair(bootstrapResult, pending)
                }
                val pushed = backend.pushChanges(config, session, deviceIdProvider.value(), pending)
                val lastLogIdBefore = inSyncContext(runGeneration, session.userId) {
                    pushed.pushedQueueIds.forEach { queueId ->
                        modelFactory.syncQueueRepository.markPushed(queueId, System.currentTimeMillis())
                    }

                    val storedUserId = modelFactory.syncStateRepository.getAccountUserId()
                    if (storedUserId != null && storedUserId != session.userId) {
                        modelFactory.database.runInTransaction {
                            modelFactory.syncStateRepository.clear()
                            modelFactory.syncInboxRepository.clearAll()
                            modelFactory.syncTombstoneRepository.clearAll()
                            modelFactory.syncStateRepository.setAccountUserId(session.userId, System.currentTimeMillis())
                        }
                        preferences.syncLastLogId = 0L
                    } else if (storedUserId == null) {
                        modelFactory.syncStateRepository.setAccountUserId(session.userId, System.currentTimeMillis())
                    }

                    val lastLogIdBefore = modelFactory.syncStateRepository.getCursor() ?: preferences.syncLastLogId
                    if (preferences.syncLastLogId != lastLogIdBefore) {
                        preferences.syncLastLogId = lastLogIdBefore
                    }
                    lastLogIdBefore
                }

                val pull = backend.pullChanges(config, session, lastLogIdBefore)
                inSyncContext(runGeneration, session.userId) {
                    val pulledForeignEvents = pull.events.filter { it.deviceId != deviceIdProvider.value() }

                    val (applied, skipped) = modelFactory.database.runInTransaction {
                        val skippedCount = applyRemoteEvents(pulledForeignEvents)
                        modelFactory.syncStateRepository.setCursor(pull.latestLogId, System.currentTimeMillis())
                        Pair(pulledForeignEvents.size - skippedCount, skippedCount)
                    }

                    preferences.syncLastLogId = pull.latestLogId
                    preferences.syncLastSuccessAt = System.currentTimeMillis()
                    preferences.syncStatus = "success"

                    val replayed = replayInbox(ignoreBackoff = manual)

                    val hasPending = modelFactory.syncQueueRepository.findPending().isNotEmpty()
                    preferences.isSyncBootstrapDone = bootstrapResult.complete || (preferences.isSyncBootstrapQueued && !hasPending)

                    val details = StringBuilder(
                        "pushed=${pushed.pushedQueueIds.size}, pulled=${pulledForeignEvents.size}, applied=$applied, skipped=$skipped, replayed=$replayed, last_log_before=$lastLogIdBefore, last_log_after=${pull.latestLogId}"
                    )
                    details.append(
                        ", bootstrap_queued=${bootstrapResult.queuedRecords}, bootstrap_complete=${bootstrapResult.complete}, bootstrap_missing_goals_before=${bootstrapResult.missingGoalsBefore}"
                    )
                    if (skipped > 0) {
                        val blockedTask = pulledForeignEvents.any {
                            it.logId == lastSkippedRemoteLogId && it.entityType == "user_task"
                        }
                        details.append(if (blockedTask) ", first_error=unsupported_task_planned_date" else ", first_error=missing_parent_habit")
                    }
                    preferences.syncStatusDetail = details.toString()

                    habitList.reloadAndNotify()
                    widgetUpdater.updateWidgets()
                    preferences.syncLastUiRefreshReason = "sync_coordinator_success"
                    preferences.syncLastUiRefreshAt = System.currentTimeMillis()
                    preferences.syncLastUiRefreshDestination = "listener_pending"
                    preferences.syncLastUiRefreshHabitCount = activeHabitCount()
                    preferences.notifySyncFinished()

                    if (!manual) {
                        preferences.syncLastBackgroundSyncResult = "success"
                    }

                    SyncRunResult.Success(pushed = pushed.pushedQueueIds.size, pulled = pulledForeignEvents.size)
                }
            }.getOrElse {
                if (it is SyncContextChanged) return@getOrElse SyncRunResult.Skipped("sync_context_changed")
                synchronized(this@SyncCoordinator) {
                    if (disposed || runGeneration != syncContextGeneration) {
                        return@getOrElse SyncRunResult.Skipped("sync_context_changed")
                    }
                    if (!manual) {
                        preferences.syncLastBackgroundSyncResult = "failure: ${technicalMessageFor(it)}"
                    }
                    syncUnavailable("Синхронизация сейчас недоступна.", it)
                }
            }
            result = res
            return@withContext res
        } finally {
            synchronized(this@SyncCoordinator) {
                isSyncing = false
            }
            notifySyncStateChanged(false, result)
            if (synchronized(this@SyncCoordinator) { isPendingSync }) {
                scheduleBackgroundSync("local_change_during_sync")
            }
        }
    }

    fun markRestoreNeedsReview(reason: String) = synchronized(this) {
        syncContextGeneration++
        preferences.isSyncReviewRequired = true
        preferences.syncReviewReason = reason
        preferences.syncStatus = "review_required"
        preferences.syncStatusDetail = reason
        preferences.syncLastLogId = 0L
        modelFactory.syncStateRepository.clear()
        modelFactory.syncInboxRepository.clearAll()
        modelFactory.syncTombstoneRepository.clearAll()
    }

    fun confirmSyncReview() {
        preferences.isSyncReviewRequired = false
        preferences.syncReviewReason = ""
        if (preferences.syncStatus == "review_required") {
            preferences.syncStatus = "idle"
            preferences.syncStatusDetail = ""
        }
    }

    private fun pendingFailure(message: String) {
        preferences.syncStatus = "error"
        preferences.syncStatusDetail = message
        modelFactory.syncQueueRepository.findPending()
            .forEach { record ->
                record.queueId?.let {
                    modelFactory.syncQueueRepository.markFailed(it, System.currentTimeMillis(), message)
                }
            }
    }

    private fun configurationMissing(userMessage: String): SyncRunResult.Failure {
        preferences.syncStatus = "error"
        preferences.syncStatusDetail = "Sync configuration is missing"
        return SyncRunResult.Failure(userMessage, "Missing config")
    }

    private fun syncUnavailable(userMessage: String, throwable: Throwable): SyncRunResult.Failure {
        pendingFailure(technicalMessageFor(throwable))
        return SyncRunResult.Failure(userMessage, throwable.message)
    }

    private fun technicalMessageFor(throwable: Throwable): String {
        return when (throwable) {
            is NoClassDefFoundError,
            is ClassNotFoundException,
            is LinkageError -> "Sync unavailable in this build. Network runtime initialization failed."
            else -> throwable.message ?: throwable::class.simpleName ?: "Sync failed"
        }
    }

    private fun requireConfig(): SupabaseSyncConfig? {
        val url = preferences.syncSupabaseUrl.trim()
        val key = preferences.syncSupabaseAnonKey.trim()
        if (url.isBlank() || key.isBlank()) return null
        return SupabaseSyncConfig(url, key)
    }

    private data class BootstrapResult(
        val queuedRecords: Int,
        val complete: Boolean,
        val missingGoalsBefore: Int
    )

    private data class GoalBootstrapRow(
        val habitUuid: String,
        val goalUuid: String,
        val effectiveTimestamp: Long,
        val freqNum: Int,
        val freqDen: Int,
        val targetType: Int,
        val targetValue: Double,
        val unit: String
    )

    private fun ensureBootstrapSyncQueue(): BootstrapResult {
        var queuedRecords = 0
        if (!preferences.isSyncBootstrapQueued) {
            queuedRecords += bootstrapSyncQueue()
        }
        val missingGoalsBefore = currentGoalRows().count {
            !syncQueueContains("habit_goal", it.goalUuid)
        }
        queuedRecords += backfillMissingBootstrapRecords()
        return BootstrapResult(
            queuedRecords = queuedRecords,
            complete = isBootstrapQueueComplete(),
            missingGoalsBefore = missingGoalsBefore
        )
    }

    internal fun bootstrapSyncQueue(): Int {
        var insertedRecords = 0
        val now = modelFactory.syncManager.now()
        val deviceId = deviceIdProvider.value()
        modelFactory.database.begin()
        try {
            habitList.reload()
            val habits = habitList.toList()
            if (!session.isNextArchitecture) {
                val blocks = modelFactory.habitBlockRepository.findAll()
                for (block in blocksForBootstrap(blocks, habits)) {
                    val blockUuid = if (block.uuid.isNullOrBlank()) {
                        val newUuid = UUID.randomUUID().toString().replace("-", "")
                        modelFactory.habitBlockRepository.update(block.copy(uuid = newUuid))
                        newUuid
                    } else {
                        block.uuid!!
                    }
                    val payloadJson = json(
                        "name" to block.name,
                        "color" to block.color.toString(),
                        "position" to block.position.toString(),
                        "is_archived" to block.isArchived.toString()
                    )
                    insertedRecords += insertBootstrapRecordIfMissing(
                        opUuid = UUID.randomUUID().toString().replace("-", ""),
                        entityType = "habit_block",
                        entityUuid = blockUuid,
                        operationType = "create",
                        payloadJson = payloadJson,
                        createdAt = now,
                        deviceId = deviceId
                    )
                }
            } else {
                insertedRecords += bootstrapNextEntities(now, deviceId)
            }

            for (habit in habits) {
                val habitUuid = if (habit.uuid.isNullOrBlank()) {
                    val newUuid = UUID.randomUUID().toString().replace("-", "")
                    habit.uuid = newUuid
                    val data = SQLiteHabitList.copyFrom(habit)
                    data.updatedAt = now
                    modelFactory.habitRepository.update(data)
                    newUuid
                } else {
                    habit.uuid!!
                }

                val habitPayload = habitPayload(habit)
                insertedRecords += insertBootstrapRecordIfMissing(
                    opUuid = UUID.randomUUID().toString().replace("-", ""),
                    entityType = "habit",
                    entityUuid = habitUuid,
                    operationType = "create",
                    payloadJson = habitPayload,
                    createdAt = now,
                    deviceId = deviceId
                )

                val goals = habit.normalizedGoalHistory()
                for (goal in goals) {
                    val goalUuid = "${habitUuid}:goal:${goal.effectiveDate.unixTime}"
                    val goalPayload = json(
                        "habit_uuid" to habitUuid,
                        "effective_timestamp" to goal.effectiveDate.unixTime.toString(),
                        "freq_num" to goal.frequency.numerator.toString(),
                        "freq_den" to goal.frequency.denominator.toString(),
                        "target_type" to goal.targetType.value.toString(),
                        "target_value" to goal.targetValue.toString(),
                        "unit" to goal.unit
                    )
                    insertedRecords += insertBootstrapRecordIfMissing(
                        opUuid = UUID.randomUUID().toString().replace("-", ""),
                        entityType = "habit_goal",
                        entityUuid = goalUuid,
                        operationType = "goal_change",
                        payloadJson = goalPayload,
                        createdAt = now,
                        deviceId = deviceId
                    )
                }

                val entries = habit.originalEntries.getKnown()
                for (entry in entries) {
                    val entryUuid = "${habitUuid}:${entry.date.unixTime}"
                    val entryPayload = json(
                        "habit_uuid" to habitUuid,
                        "entry_date" to entry.date.unixTime.toString(),
                        "value" to entry.value.toString(),
                        "notes" to entry.notes
                    )
                    insertedRecords += insertBootstrapRecordIfMissing(
                        opUuid = UUID.randomUUID().toString().replace("-", ""),
                        entityType = "entry",
                        entityUuid = entryUuid,
                        operationType = "entry_set",
                        payloadJson = entryPayload,
                        createdAt = now,
                        deviceId = deviceId
                    )
                }
            }

            val statsStart = modelFactory.appSettingRepository.getLong(GLOBAL_STATS_START_KEY)
            if (statsStart != null) {
                val appSettingPayload = json(
                    "key" to GLOBAL_STATS_START_KEY,
                    "long_value" to statsStart.toString()
                )
                insertedRecords += insertBootstrapRecordIfMissing(
                    opUuid = UUID.randomUUID().toString().replace("-", ""),
                    entityType = "app_setting",
                    entityUuid = GLOBAL_STATS_START_KEY,
                    operationType = "update",
                    payloadJson = appSettingPayload,
                    createdAt = now,
                    deviceId = deviceId
                )
            }

            preferences.isSyncBootstrapQueued = true
            modelFactory.database.commit()
            return insertedRecords
        } catch (e: Exception) {
            try { modelFactory.database.run("ROLLBACK") } catch (_: Exception) {}
            throw e
        }
    }

    private fun insertBootstrapRecordIfMissing(
        opUuid: String,
        entityType: String,
        entityUuid: String,
        operationType: String,
        payloadJson: String,
        createdAt: Long,
        deviceId: String
    ): Int {
        if (syncQueueContains(entityType, entityUuid)) return 0
        modelFactory.syncQueueRepository.insert(
            SyncQueueRecord(
                opUuid = opUuid,
                entityType = entityType,
                entityUuid = entityUuid,
                operationType = operationType,
                payloadJson = payloadJson,
                createdAt = createdAt,
                deviceId = deviceId
            )
        )
        return 1
    }

    private fun backfillMissingBootstrapRecords(): Int {
        var insertedRecords = 0
        val now = modelFactory.syncManager.now()
        val deviceId = deviceIdProvider.value()
        modelFactory.database.begin()
        try {
            habitList.reload()
            val habits = habitList.toList()
            if (!session.isNextArchitecture) {
                val blocks = modelFactory.habitBlockRepository.findAll()
                for (block in blocksForBootstrap(blocks, habits)) {
                    val blockUuid = if (block.uuid.isNullOrBlank()) {
                        val newUuid = UUID.randomUUID().toString().replace("-", "")
                        modelFactory.habitBlockRepository.update(block.copy(uuid = newUuid))
                        newUuid
                    } else {
                        block.uuid!!
                    }
                    insertedRecords += insertBootstrapRecordIfMissing(
                        opUuid = UUID.randomUUID().toString().replace("-", ""),
                        entityType = "habit_block",
                        entityUuid = blockUuid,
                        operationType = "create",
                        payloadJson = json(
                            "name" to block.name,
                            "color" to block.color.toString(),
                            "position" to block.position.toString(),
                            "is_archived" to block.isArchived.toString()
                        ),
                        createdAt = now,
                        deviceId = deviceId
                    )
                }
            } else {
                insertedRecords += bootstrapNextEntities(now, deviceId)
            }

            for (goal in currentGoalRows()) {
                insertedRecords += insertBootstrapRecordIfMissing(
                    opUuid = UUID.randomUUID().toString().replace("-", ""),
                    entityType = "habit_goal",
                    entityUuid = goal.goalUuid,
                    operationType = "goal_change",
                    payloadJson = json(
                        "habit_uuid" to goal.habitUuid,
                        "effective_timestamp" to goal.effectiveTimestamp.toString(),
                        "freq_num" to goal.freqNum.toString(),
                        "freq_den" to goal.freqDen.toString(),
                        "target_type" to goal.targetType.toString(),
                        "target_value" to goal.targetValue.toString(),
                        "unit" to goal.unit
                    ),
                    createdAt = now,
                    deviceId = deviceId
                )
            }
            modelFactory.database.commit()
            return insertedRecords
        } catch (e: Exception) {
            try { modelFactory.database.run("ROLLBACK") } catch (_: Exception) {}
            throw e
        }
    }

    internal fun blocksForBootstrap(blocks: List<HabitBlockData>, habits: List<Habit>): List<HabitBlockData> {
        val referencedBlockIds = habits.mapNotNull { it.blockId }.toSet()
        return blocks.filter { block ->
            val id = block.id
            // Always include default blocks (id <= DEFAULT_BLOCK_MAX_ID) even if unreferenced,
            // so receiving devices learn their UUIDs and can resolve habit block_uuid fields.
            // For custom/user blocks (id > DEFAULT_BLOCK_MAX_ID), only bootstrap if referenced.
            id != null && (id <= DEFAULT_BLOCK_MAX_ID || referencedBlockIds.contains(id))
        }
    }

    private fun currentGoalRows(): List<GoalBootstrapRow> {
        val rows = mutableListOf<GoalBootstrapRow>()
        modelFactory.database.query(
            """SELECT h.uuid, g.uuid, g.effective_timestamp, g.freq_num, g.freq_den,
                      g.target_type, g.target_value, g.unit
               FROM HabitGoals g
               INNER JOIN Habits h ON h.id = g.habit_id
               WHERE h.deleted_at IS NULL
                 AND g.deleted_at IS NULL
                 AND h.uuid IS NOT NULL
               ORDER BY h.position, g.effective_timestamp"""
        ) { stmt ->
            val habitUuid = stmt.getText(0)
            val effectiveTimestamp = stmt.getLong(2)
            rows.add(
                GoalBootstrapRow(
                    habitUuid = habitUuid,
                    goalUuid = stmt.getTextOrNull(1)?.takeUnless { it.isBlank() }
                        ?: "$habitUuid:goal:$effectiveTimestamp",
                    effectiveTimestamp = effectiveTimestamp,
                    freqNum = stmt.getInt(3),
                    freqDen = stmt.getInt(4),
                    targetType = stmt.getInt(5),
                    targetValue = stmt.getReal(6),
                    unit = stmt.getTextOrNull(7) ?: ""
                )
            )
        }
        return rows
    }

    private fun syncQueueContains(entityType: String, entityUuid: String): Boolean =
        modelFactory.database.querySingle(
            "SELECT queue_id FROM SyncQueue WHERE entity_type = ? AND entity_uuid = ? LIMIT 1",
            entityType,
            entityUuid
        ) { true } ?: false

    private fun isBootstrapQueueComplete(): Boolean {
        val missingGoal = currentGoalRows().any { !syncQueueContains("habit_goal", it.goalUuid) }
        if (missingGoal) return false
        habitList.reload()
        val habits = habitList.toList()
        val missingHabit = habits.any { habit ->
            val uuid = habit.uuid
            uuid.isNullOrBlank() || !syncQueueContains("habit", uuid)
        }
        if (missingHabit) return false
        if (!session.isNextArchitecture) {
            val blocks = modelFactory.habitBlockRepository.findAll()
            return blocksForBootstrap(blocks, habits).all { block ->
                !block.uuid.isNullOrBlank() && syncQueueContains("habit_block", block.uuid!!)
            }
        } else {
            if (!isNextSchemaAvailable()) return true
            val missingContainer = modelFactory.database.querySingle(
                "SELECT count(*) FROM Containers WHERE uuid NOT IN (SELECT entity_uuid FROM SyncQueue WHERE entity_type = 'container')"
            ) { it.getLong(0) } ?: 0L
            if (missingContainer > 0) return false

            val missingPlacement = modelFactory.database.querySingle(
                """SELECT count(*) FROM HabitPlacements p
                   INNER JOIN Habits h ON h.id = p.habit_id
                   WHERE h.deleted_at IS NULL AND h.uuid IS NOT NULL
                     AND h.uuid NOT IN (SELECT entity_uuid FROM SyncQueue WHERE entity_type = 'habit_placement')"""
            ) { it.getLong(0) } ?: 0L
            if (missingPlacement > 0) return false

            val missingTask = modelFactory.database.querySingle(
                "SELECT count(*) FROM UserTasks WHERE id NOT IN (SELECT entity_uuid FROM SyncQueue WHERE entity_type = 'user_task')"
            ) { it.getLong(0) } ?: 0L
            if (missingTask > 0) return false

            val missingFocus = modelFactory.database.querySingle(
                "SELECT count(*) FROM FocusSessions WHERE id NOT IN (SELECT entity_uuid FROM SyncQueue WHERE entity_type = 'focus_session')"
            ) { it.getLong(0) } ?: 0L
            if (missingFocus > 0) return false

            val missingContribution = modelFactory.database.querySingle(
                "SELECT count(*) FROM Contributions WHERE id NOT IN (SELECT entity_uuid FROM SyncQueue WHERE entity_type = 'contribution')"
            ) { it.getLong(0) } ?: 0L
            if (missingContribution > 0) return false

            return true
        }
    }

    private fun habitPayload(habit: Habit): String {
        val goal = habit.currentGoal()
        val blockUuid = if (session.isNextArchitecture) {
            null
        } else {
            habit.blockId?.let { modelFactory.habitBlockRepository.findById(it)?.uuid }
        }
        return json(
            "name" to habit.name,
            "description" to habit.description,
            "question" to habit.question,
            "archived" to habit.isArchived.toString(),
            "type" to habit.type.value.toString(),
            "freq_num" to habit.frequency.numerator.toString(),
            "freq_den" to habit.frequency.denominator.toString(),
            "color" to habit.color.paletteIndex.toString(),
            "target_value" to goal.targetValue.toString(),
            "target_type" to goal.targetType.value.toString(),
            "unit" to goal.unit,
            "day_tier" to habit.dayTier.name,
            "block_uuid" to blockUuid,
            "timer_enabled" to habit.timerEnabled.toString(),
            "statistics_start" to habit.statisticsStartDate?.unixTime?.toString(),
            "reminder_hour" to habit.reminder?.hour?.toString(),
            "reminder_min" to habit.reminder?.minute?.toString(),
            "reminder_days" to habit.reminder?.days?.toInteger()?.toString()
        )
    }

    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

    private fun json(vararg pairs: Pair<String, String?>): String {
        return pairs.joinToString(prefix = "{", postfix = "}") { (key, value) ->
            "\"${escape(key)}\":${value?.let { "\"${escape(it)}\"" } ?: "null"}"
        }
    }

    private fun JSONObject.putSection(name: String, block: () -> Any) {
        put(
            name,
            runCatching { block() }.getOrElse {
                JSONObject()
                    .put("error", it.message ?: it::class.simpleName ?: "unknown")
            }
        )
    }

    private fun lastSyncDiagnostics(): JSONObject {
        val detail = preferences.syncStatusDetail
        return JSONObject()
            .put("pushed", detail.intField("pushed"))
            .put("pulled", detail.intField("pulled"))
            .put("applied", detail.intField("applied"))
            .put("skipped", detail.intField("skipped"))
            .put("last_log_before", detail.longField("last_log_before"))
            .put("last_log_after", detail.longField("last_log_after"))
            .put("error", if (preferences.syncStatus == "error") detail else JSONObject.NULL)
            .put("raw_detail", detail)
    }

    private fun String.intField(name: String): Any {
        return Regex("""$name\s*[=:]\s*(\d+)""", RegexOption.IGNORE_CASE)
            .find(this)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: JSONObject.NULL
    }

    private fun String.longField(name: String): Any {
        return Regex("""$name\s*[=:]\s*(\d+)""", RegexOption.IGNORE_CASE)
            .find(this)
            ?.groupValues
            ?.getOrNull(1)
            ?.toLongOrNull()
            ?: JSONObject.NULL
    }

    private fun queryArray(sql: String, mapper: (PreparedStatement) -> JSONObject): JSONArray {
        val array = JSONArray()
        modelFactory.database.query(sql) { stmt ->
            array.put(mapper(stmt))
        }
        return array
    }

    private fun activeHabitCount(): Long =
        modelFactory.database.queryLong("SELECT COUNT(*) FROM Habits WHERE deleted_at IS NULL")

    private fun syncQueueCountsByType(): JSONArray {
        val byType = mutableMapOf<String, JSONObject>()
        modelFactory.database.query(
            """SELECT entity_type, COUNT(*),
                      SUM(CASE WHEN pushed_at IS NULL THEN 1 ELSE 0 END),
                      SUM(CASE WHEN pushed_at IS NOT NULL THEN 1 ELSE 0 END)
               FROM SyncQueue
               GROUP BY entity_type
               ORDER BY entity_type"""
        ) { stmt ->
            val entityType = stmt.getText(0)
            byType[entityType] = JSONObject()
                .put("entity_type", entityType)
                .put("total", stmt.getLong(1))
                .put("unpushed", stmt.getLongOrNull(2) ?: 0L)
                .put("pushed", stmt.getLongOrNull(3) ?: 0L)
        }
        for (entityType in listOf("habit", "habit_goal", "entry", "entry_op", "habit_block", "app_setting")) {
            byType.putIfAbsent(
                entityType,
                JSONObject()
                    .put("entity_type", entityType)
                    .put("total", 0L)
                    .put("unpushed", 0L)
                    .put("pushed", 0L)
            )
        }
        val array = JSONArray()
        byType.keys.sorted().forEach { array.put(byType[it]) }
        return array
    }

    private fun unpushedOrFailedQueueRows(): JSONArray = queryArray(
        """SELECT queue_id, entity_type, entity_uuid, operation_type, created_at,
                  pushed_at, failed_at, failure_reason
           FROM SyncQueue
           WHERE pushed_at IS NULL OR failed_at IS NOT NULL
           ORDER BY queue_id ASC"""
    ) { stmt ->
        JSONObject()
            .put("queue_id", stmt.getLong(0))
            .put("entity_type", stmt.getText(1))
            .put("entity_uuid", stmt.getText(2))
            .put("operation_type", stmt.getText(3))
            .put("created_at", stmt.getLong(4))
            .put("pushed_at", stmt.getLongOrNull(5) ?: JSONObject.NULL)
            .put("failed_at", stmt.getLongOrNull(6) ?: JSONObject.NULL)
            .put("failure_reason", stmt.getTextOrNull(7) ?: JSONObject.NULL)
    }

    private fun entryOpsByHabitDate(): JSONArray = queryArray(
        """SELECT e.habit_uuid, h.name, e.entry_timestamp, COUNT(*), SUM(e.delta_value),
                  MIN(e.created_at), MAX(e.created_at)
           FROM EntryOps e
           LEFT JOIN Habits h ON h.uuid = e.habit_uuid
           WHERE e.deleted_at IS NULL
           GROUP BY e.habit_uuid, h.name, e.entry_timestamp
           ORDER BY h.name, e.entry_timestamp"""
    ) { stmt ->
        JSONObject()
            .put("habit_uuid", stmt.getTextOrNull(0) ?: JSONObject.NULL)
            .put("habit_name", stmt.getTextOrNull(1) ?: JSONObject.NULL)
            .put("timestamp", stmt.getLong(2))
            .put("ops_count", stmt.getLong(3))
            .put("delta_value_sum", stmt.getLongOrNull(4) ?: 0L)
            .put("first_created_at", stmt.getLongOrNull(5) ?: JSONObject.NULL)
            .put("last_created_at", stmt.getLongOrNull(6) ?: JSONObject.NULL)
    }

    private fun habitsReport(): JSONArray = queryArray(
        """SELECT uuid, name, type, archived, deleted_at, updated_at
           FROM Habits
           ORDER BY position, name"""
    ) { stmt ->
        JSONObject()
            .put("uuid", stmt.getTextOrNull(0) ?: JSONObject.NULL)
            .put("name", stmt.getTextOrNull(1) ?: "")
            .put("type", stmt.getInt(2))
            .put("archived", stmt.getInt(3) != 0)
            .put("deleted_at", stmt.getLongOrNull(4) ?: JSONObject.NULL)
            .put("updated_at", stmt.getLong(5))
    }

    private fun entriesReport(): JSONArray = queryArray(
        """SELECT h.uuid, h.name, r.uuid, r.timestamp, r.value, r.notes, r.updated_at, r.deleted_at
           FROM Repetitions r
           LEFT JOIN Habits h ON h.id = r.habit
           ORDER BY h.name, r.timestamp"""
    ) { stmt ->
        JSONObject()
            .put("habit_uuid", stmt.getTextOrNull(0) ?: JSONObject.NULL)
            .put("habit_name", stmt.getTextOrNull(1) ?: JSONObject.NULL)
            .put("entry_uuid", stmt.getTextOrNull(2) ?: JSONObject.NULL)
            .put("timestamp", stmt.getLong(3))
            .put("value", stmt.getInt(4))
            .put("notes", stmt.getTextOrNull(5) ?: "")
            .put("updated_at", stmt.getLong(6))
            .put("deleted_at", stmt.getLongOrNull(7) ?: JSONObject.NULL)
    }

    private fun goalsReport(): JSONArray = queryArray(
        """SELECT h.uuid, h.name, g.uuid, g.effective_timestamp, g.freq_num, g.freq_den,
                  g.target_type, g.target_value, g.unit, g.updated_at, g.deleted_at
           FROM HabitGoals g
           LEFT JOIN Habits h ON h.id = g.habit_id
           ORDER BY h.name, g.effective_timestamp"""
    ) { stmt ->
        JSONObject()
            .put("habit_uuid", stmt.getTextOrNull(0) ?: JSONObject.NULL)
            .put("habit_name", stmt.getTextOrNull(1) ?: JSONObject.NULL)
            .put("goal_uuid", stmt.getTextOrNull(2) ?: JSONObject.NULL)
            .put("effective_timestamp", stmt.getLong(3))
            .put("freq_num", stmt.getInt(4))
            .put("freq_den", stmt.getInt(5))
            .put("target_type", stmt.getInt(6))
            .put("target_value", stmt.getReal(7))
            .put("unit", stmt.getTextOrNull(8) ?: "")
            .put("updated_at", stmt.getLong(9))
            .put("deleted_at", stmt.getLongOrNull(10) ?: JSONObject.NULL)
    }

    private fun blocksReport(): JSONArray = queryArray(
        """SELECT b.id, b.uuid, b.name, b.color, b.position, b.is_archived, b.updated_at, b.deleted_at,
                  (SELECT COUNT(*)
                   FROM HabitExtensions e
                   INNER JOIN Habits h ON h.id = e.habit_id
                   WHERE e.block_id = b.id AND h.deleted_at IS NULL) AS referenced_by_habits
           FROM HabitBlocks b
           WHERE b.deleted_at IS NULL
           ORDER BY b.position, b.name"""
    ) { stmt ->
        val id = stmt.getLong(0)
        val uuid = stmt.getTextOrNull(1)
        val referencedByHabits = stmt.getLong(8)
        val expectedUuid = DETERMINISTIC_DEFAULT_BLOCK_UUIDS[id]
        JSONObject()
            .put("id", id)
            .put("uuid", uuid ?: JSONObject.NULL)
            .put("name", stmt.getTextOrNull(2) ?: "")
            .put("color", stmt.getInt(3))
            .put("position", stmt.getInt(4))
            .put("archived", stmt.getInt(5) != 0)
            .put("updated_at", stmt.getLong(6))
            .put("deleted_at", stmt.getLongOrNull(7) ?: JSONObject.NULL)
            .put("referenced_by_habits", referencedByHabits)
            .put("unreferenced", referencedByHabits == 0L)
            .put("default_candidate", id <= DEFAULT_BLOCK_MAX_ID)
            .put("bootstrap_candidate", referencedByHabits > 0L || id > DEFAULT_BLOCK_MAX_ID)
            .put("expected_uuid", expectedUuid ?: JSONObject.NULL)
            .put("uuid_matches_expected", expectedUuid != null && uuid == expectedUuid)
    }

    /**
     * Reports the current habit-to-block assignment for every non-deleted habit,
     * joining Habits + HabitExtensions + HabitBlocks via local IDs.
     * Useful for diagnosing sphere assignments across devices.
     */
    private fun habitBlockAssignmentsReport(): JSONArray = queryArray(
        """SELECT h.uuid, h.name, e.block_id, b.uuid, b.name
           FROM Habits h
           LEFT JOIN HabitExtensions e ON e.habit_id = h.id
           LEFT JOIN HabitBlocks b ON b.id = e.block_id AND b.deleted_at IS NULL
           WHERE h.deleted_at IS NULL
           ORDER BY h.position, h.name"""
    ) { stmt ->
        val localBlockId = stmt.getLongOrNull(2)
        val blockUuid = stmt.getTextOrNull(3)
        val blockName = stmt.getTextOrNull(4)
        JSONObject()
            .put("habit_uuid", stmt.getTextOrNull(0) ?: JSONObject.NULL)
            .put("habit_name", stmt.getTextOrNull(1) ?: "")
            .put("local_block_id", localBlockId ?: JSONObject.NULL)
            .put("block_uuid", blockUuid ?: JSONObject.NULL)
            .put("block_name", blockName ?: JSONObject.NULL)
            .put("is_other", localBlockId == null)
    }

    /**
     * Compares raw Repetitions table entries with what the in-memory Habit model exposes
     * as computedEntries for today. This lets you spot whether raw DB data exists but the
     * UI-facing computed state is 0 / UNKNOWN — the key signal for derived-cache staleness.
     */
    private fun rawVsComputedReport(): JSONArray {
        val today = getToday()
        val todayTs = today.unixTime
        val array = JSONArray()
        habitList.reload()
        val habits = habitList.toList().filter { !it.isArchived }
        for (habit in habits) {
            val habitId = habit.id ?: continue
            val habitUuid = habit.uuid ?: ""

            // Raw entry count from DB
            val rawEntryCount = modelFactory.database.querySingle(
                "SELECT COUNT(*) FROM Repetitions WHERE habit = ? AND deleted_at IS NULL",
                habitId.toString()
            ) { stmt -> stmt.getLong(0) } ?: 0L

            // Latest raw entry value and timestamp for today
            val rawTodayValue = modelFactory.database.querySingle(
                "SELECT value FROM Repetitions WHERE habit = ? AND timestamp = ? AND deleted_at IS NULL LIMIT 1",
                habitId.toString(), todayTs.toString()
            ) { stmt -> stmt.getInt(0) }

            // Latest raw entry value overall (most recent non-deleted)
            val rawLatestTs = modelFactory.database.querySingle(
                "SELECT timestamp, value FROM Repetitions WHERE habit = ? AND deleted_at IS NULL ORDER BY timestamp DESC LIMIT 1",
                habitId.toString()
            ) { stmt -> Pair(stmt.getLong(0), stmt.getInt(1)) }

            // Computed (in-memory after recompute) entry for today
            val computedTodayEntry = runCatching { habit.computedEntries.get(today) }.getOrNull()
            val computedTodayValue = computedTodayEntry?.value

            // Goal for today
            val goal = runCatching { habit.goalAt(today) }.getOrNull()

            val obj = JSONObject()
                .put("habit_uuid", habitUuid)
                .put("habit_name", habit.name)
                .put("habit_type", habit.type.value)
                .put("raw_entry_count", rawEntryCount)
                .put("raw_today_value", rawTodayValue ?: JSONObject.NULL)
                .put("raw_latest_ts", rawLatestTs?.first ?: JSONObject.NULL)
                .put("raw_latest_value", rawLatestTs?.second ?: JSONObject.NULL)
                .put("computed_today_value", computedTodayValue ?: JSONObject.NULL)
                .put("target_value", goal?.targetValue ?: JSONObject.NULL)
                .put("target_type", goal?.targetType?.value ?: JSONObject.NULL)
                .put("unit", goal?.unit ?: "")
                .put(
                    "computed_today_human",
                    when (computedTodayValue) {
                        null, Entry.UNKNOWN -> "UNKNOWN"
                        Entry.SKIP -> "SKIP"
                        Entry.YES_MANUAL -> "YES_MANUAL"
                        Entry.YES_AUTO -> "YES_AUTO"
                        Entry.NO -> "NO"
                        else -> if (habit.isNumerical) "${computedTodayValue / 1000.0} ${goal?.unit ?: ""}"
                              else computedTodayValue.toString()
                    }
                )
                .put(
                    "raw_today_human",
                    when (rawTodayValue) {
                        null -> "(no raw entry today)"
                        Entry.SKIP -> "SKIP"
                        Entry.YES_MANUAL -> "YES_MANUAL(2)"
                        Entry.YES_AUTO -> "YES_AUTO(1)"
                        Entry.NO -> "NO(0)"
                        Entry.UNKNOWN -> "UNKNOWN(-1)"
                        else -> if (habit.isNumerical) "${rawTodayValue / 1000.0} ${goal?.unit ?: ""}"
                              else rawTodayValue.toString()
                    }
                )
                .put(
                    "state_match",
                    rawTodayValue != null && rawTodayValue == computedTodayValue
                )
            array.put(obj)
        }
        return array
    }

    /**
     * Tracks a habit-to-block relation that could not be resolved during apply because
     * the remote block UUID was not yet present locally. Used for second-pass re-resolve
     * and diagnostics.
     */
    private data class UnresolvedHabitBlock(
        val habitId: Long,
        val habitUuid: String,
        val remoteBlockUuid: String,
        val eventLogId: Long?
    )

    /** Accumulated during applyRemoteEvents for diagnostics display. */
    private val lastUnresolvedHabitBlocks = mutableListOf<UnresolvedHabitBlock>()
    private var lastSkippedRemoteLogId: Long? = null

    internal fun applyRemoteEvents(events: List<RemoteSyncEvent>): Int {
        lastSkippedRemoteLogId = null
        if (events.isEmpty()) return 0
        // Do NOT run destructive cleanupDuplicateDefaultBlocks here.
        // Duplicate blocks are less harmful than losing habit-sphere assignments.

        // Apply in dependency order: habit_block/container metadata first, then habits, then placements/goals/entries, then tasks, focus, contributions.
        val priority = mapOf(
            "habit_block" to 0,
            "container" to 0,
            "habit" to 1,
            "habit_placement" to 2,
            "user_task" to 3,
            "focus_session" to 4,
            "contribution" to 5
        )
        val sortedEvents = events.sortedWith(compareBy { priority[it.entityType] ?: 2 })

        var skipped = 0
        val unresolvedHabitBlocks = mutableListOf<UnresolvedHabitBlock>()
        val unresolvedContainerParents = mutableListOf<Pair<String, String>>()

        modelFactory.syncManager.withCapturePaused {
            sortedEvents.forEach { event ->
                val applied = when (event.entityType) {
                    "habit_block" -> { applyBlock(event); true }
                    "container" -> { applyContainer(event, unresolvedContainerParents); true }
                    "habit" -> {
                        val unresolved = applyHabit(event)
                        if (unresolved != null) unresolvedHabitBlocks.add(unresolved)
                        true
                    }
                    "habit_placement" -> applyHabitPlacement(event)
                    "user_task" -> applyUserTask(event)
                    "focus_session" -> { applyFocusSession(event); true }
                    "contribution" -> applyContribution(event)
                    "habit_goal" -> applyGoal(event)
                    "entry" -> applyEntry(event)
                    "entry_op" -> applyEntryOp(event)
                    "app_setting" -> { applyAppSetting(event); true }
                    else -> true
                }
                if (!applied) {
                    skipped++
                    lastSkippedRemoteLogId = minOf(lastSkippedRemoteLogId ?: event.logId, event.logId)
                    Log.w("SyncCoordinator", "Skipped remote event ${event.entityType} uuid=${event.entityUuid}")
                }
            }

            // Second pass for unresolved container parents
            for ((childUuid, parentUuid) in unresolvedContainerParents) {
                val parentId = modelFactory.database.querySingle(
                    "SELECT id FROM Containers WHERE uuid = ?",
                    parentUuid
                ) { it.getLong(0) }
                if (parentId != null) {
                    modelFactory.database.run(
                        "UPDATE Containers SET parent_id = $parentId WHERE uuid = '$childUuid'"
                    )
                }
            }

            // Second-pass: try to resolve habit-block links that were unresolved in first pass.
            // This handles the case where a habit event appears before its habit_block event in the log,
            // even after sorting (e.g. block arrived in a previous batch that wasn't applied yet).
            val stillUnresolved = mutableListOf<UnresolvedHabitBlock>()
            for (item in unresolvedHabitBlocks) {
                val resolvedBlock = modelFactory.habitBlockRepository.findByUuid(item.remoteBlockUuid)
                if (resolvedBlock?.id != null) {
                    val existing = modelFactory.habitExtensionRepository.findByHabitId(item.habitId)
                    modelFactory.habitExtensionRepository.upsert(
                        HabitExtensionData(
                            habitId = item.habitId,
                            dayTier = existing?.dayTier ?: "NORMAL",
                            timerEnabled = existing?.timerEnabled ?: false,
                            blockId = resolvedBlock.id,
                            statsStartTimestamp = existing?.statsStartTimestamp
                        )
                    )
                    Log.d("SyncCoordinator", "Second-pass resolved block ${item.remoteBlockUuid} -> local id ${resolvedBlock.id} for habit ${item.habitUuid}")
                } else {
                    stillUnresolved.add(item)
                    Log.w("SyncCoordinator", "Unresolved habit-block after second pass: habit=${item.habitUuid} block_uuid=${item.remoteBlockUuid} logId=${item.eventLogId}; preserving existing assignment")
                }
            }

            synchronized(lastUnresolvedHabitBlocks) {
                lastUnresolvedHabitBlocks.clear()
                lastUnresolvedHabitBlocks.addAll(stillUnresolved)
            }
        }

        return skipped
    }

    // Replaying a still-blocked event must not reset its bounded retry history.
    private fun deferRemoteEvent(record: SyncInboxRecord) {
        val previous = modelFactory.syncInboxRepository.findByEntity(record.entityType, record.entityUuid)
            .firstOrNull { it.logId == record.logId }
        modelFactory.syncInboxRepository.insertOrUpdate(
            if (previous == null) record else record.copy(
                receivedAt = previous.receivedAt,
                attempts = previous.attempts,
                lastAttemptAt = previous.lastAttemptAt,
                status = if (record.status == "PENDING") previous.status else record.status
            )
        )
    }
    internal fun replayInbox(
        maxAttempts: Int = 5,
        now: Long = System.currentTimeMillis(),
        ignoreBackoff: Boolean = false
    ): Int {
        val pending = modelFactory.syncInboxRepository.findPending(
            maxAttempts = maxAttempts,
            now = now,
            ignoreBackoff = ignoreBackoff
        )
        if (pending.isEmpty()) return 0
        var replayedCount = 0

        modelFactory.database.runInTransaction {
            modelFactory.syncManager.withCapturePaused {
                for (record in pending) {
                    if (modelFactory.syncTombstoneRepository.isDeleted(record.entityType, record.entityUuid, record.createdAt)) {
                        modelFactory.syncInboxRepository.deleteByLogId(record.logId)
                        continue
                    }

                    val event = RemoteSyncEvent(
                        logId = record.logId,
                        opUuid = record.opUuid,
                        entityType = record.entityType,
                        entityUuid = record.entityUuid,
                        operationType = record.operationType,
                        payloadJson = record.payloadJson,
                        createdAt = record.createdAt,
                        deviceId = record.deviceId
                    )

                    val applied = when (record.entityType) {
                        "user_task" -> {
                            if (hasUnsupportedTaskPlan(record.entityUuid)) {
                                false
                            } else {
                                applyUserTask(event)
                            }
                        }
                        "habit_placement" -> applyHabitPlacement(event)
                        "habit_goal" -> applyGoal(event)
                        "entry" -> applyEntry(event)
                        "entry_op" -> applyEntryOp(event)
                        else -> true
                    }

                    if (applied) {
                        modelFactory.syncInboxRepository.deleteByLogId(record.logId)
                        replayedCount++
                    } else {
                        modelFactory.syncInboxRepository.recordAttempt(record.logId, now, null, maxAttempts)
                    }
                }
            }
        }
        return replayedCount
    }

    /**
     * Disabled: destructive block cleanup was causing habit sphere assignments to be lost.
     * Duplicate blocks are safer than silently clearing a habit's sphere.
     * Kept as a no-op; duplicate candidates are reported in diagnostics only.
     */
    @Suppress("unused")
    private fun cleanupDuplicateDefaultBlocks() {
        // Intentionally disabled. Do not remap HabitExtensions.block_id or delete blocks here.
        // Duplicates are visible in diagnostics under 'duplicate_default_block_candidates'.
        Log.d("SyncCoordinator", "cleanupDuplicateDefaultBlocks: disabled (diagnostics-only mode)")
    }

    /**
     * Repairs default block UUIDs (IDs 1–7) to use stable deterministic values.
     * Migration 29 assigned random UUIDs; this function replaces them with fixed constants
     * so all devices converge on the same UUIDs for the same built-in spheres.
     *
     * Safety guarantees:
     * - Only updates blocks with id 1..DEFAULT_BLOCK_MAX_ID whose current UUID differs.
     * - Does NOT touch HabitExtensions (block_id foreign key by numeric id is preserved).
     * - Does NOT delete any blocks.
     * - Idempotent: safe to call multiple times per sync cycle.
     */
    internal fun repairDefaultBlockUuids() {
        if (!session.capabilities.syncEnabled || session.isNextArchitecture || session.isExperimental) {
            return
        }
        if (modelFactory.habitBlockRepository.authorityMode == org.isoron.uhabits.core.containers.facade.OrganizationAuthorityMode.CONTAINER_LOCAL) {
            return
        }
        var repaired = 0
        for ((id, deterministicUuid) in DETERMINISTIC_DEFAULT_BLOCK_UUIDS) {
            val block = modelFactory.habitBlockRepository.findById(id) ?: continue
            if (block.uuid == deterministicUuid) continue  // already correct
            try {
                modelFactory.habitBlockRepository.update(block.copy(uuid = deterministicUuid))
                repaired++
                Log.i("SyncCoordinator", "Repaired default block id=$id uuid ${block.uuid} -> $deterministicUuid (name=${block.name})")
            } catch (e: Exception) {
                Log.e("SyncCoordinator", "Failed to repair default block id=$id uuid: ${e.message}")
            }
        }
        if (repaired > 0) {
            Log.i("SyncCoordinator", "repairDefaultBlockUuids: updated $repaired default block(s)")
        }
    }

    private fun applyBlock(event: RemoteSyncEvent) {
        val payload = JSONObject(event.payloadJson)
        val existing = modelFactory.habitBlockRepository.findByUuid(event.entityUuid)
            ?: findAdoptableDefaultBlock(payload, event)
        if (!shouldApply(existing?.updatedAt ?: 0L, existing?.deletedAt, event)) return
        val record = HabitBlockData(
            id = existing?.id,
            name = payload.optString("name"),
            color = payload.intOrDefault("color", 0, event),
            icon = existing?.icon,
            position = payload.intOrDefault("position", existing?.position ?: 0, event),
            isArchived = payload.optString("is_archived", "false").toBoolean(),
            uuid = event.entityUuid,
            updatedAt = event.createdAt,
            deletedAt = if (event.operationType == "delete") event.createdAt else null
        )
        if (existing == null) modelFactory.habitBlockRepository.insert(record)
        else modelFactory.habitBlockRepository.update(record)
    }

    /**
     * Tries to find a local default block that should adopt the incoming remote block event.
     * Adoption priority:
     *   1. Exact UUID match is handled by the caller (findByUuid) before this is called.
     *   2. Deterministic UUID: if the incoming UUID is a known deterministic default UUID,
     *      find the block at that ID (this handles cross-device adoption stably).
     *   3. Name match: legacy fallback for blocks without deterministic UUIDs yet.
     * Only matches blocks with id <= DEFAULT_BLOCK_MAX_ID.
     */
    private fun findAdoptableDefaultBlock(payload: JSONObject, event: RemoteSyncEvent): HabitBlockData? {
        if (event.operationType == "delete") return null
        val name = payload.optString("name")
        val incomingUuid = event.entityUuid
        // Check if incoming UUID is a known deterministic default block UUID
        val deterministicId = DETERMINISTIC_DEFAULT_BLOCK_UUIDS.entries
            .firstOrNull { it.value == incomingUuid }?.key
        if (deterministicId != null) {
            val byId = modelFactory.habitBlockRepository.findById(deterministicId)
            if (byId != null && byId.uuid != incomingUuid) return byId
            // If byId already has the correct UUID, no adoption needed (findByUuid would have found it)
            return byId?.takeIf { it.uuid != incomingUuid }
        }
        // Legacy name-based fallback
        return modelFactory.habitBlockRepository.findAll().firstOrNull { block ->
            val id = block.id ?: return@firstOrNull false
            id <= DEFAULT_BLOCK_MAX_ID &&
                block.uuid != incomingUuid &&
                block.name == name
        }
    }

    private fun referencedHabitCount(blockId: Long): Long =
        modelFactory.database.querySingle(
            """SELECT COUNT(*)
               FROM HabitExtensions e
               INNER JOIN Habits h ON h.id = e.habit_id
               WHERE e.block_id = ? AND h.deleted_at IS NULL""",
            blockId.toString()
        ) { stmt -> stmt.getLong(0) } ?: 0L

    /**
     * Applies a remote habit event. Returns an [UnresolvedHabitBlock] if the payload contained
     * a non-null block_uuid that could not be resolved to a local block — so the caller can
     * attempt a second-pass resolve after all habit_block events in the batch have been applied.
     * Returns null if the block was resolved (or was explicitly null/Other).
     */
    private fun applyHabit(event: RemoteSyncEvent): UnresolvedHabitBlock? {
        val payload = JSONObject(event.payloadJson)
        val existing = modelFactory.habitRepository.findByUuid(event.entityUuid)
        if (!shouldApply(existing?.updatedAt ?: 0L, existing?.deletedAt, event)) return null

        val hasBlockUuid = payload.has("block_uuid")
        val blockUuid = if (hasBlockUuid) payload.optionalString("block_uuid") else null
        // Resolve block UUID to local id:
        //   null blockUuid => explicit Other/unassigned => blockId = null
        //   non-null blockUuid, found locally => use that local id
        //   non-null blockUuid, NOT found locally => preserve existing assignment; return for second pass
        val resolvedBlock = blockUuid?.let { modelFactory.habitBlockRepository.findByUuid(it) }
        val blockUnresolved = !session.isNextArchitecture && blockUuid != null && resolvedBlock == null

        val position = existing?.position ?: modelFactory.habitRepository.findAll().size
        val updated = HabitData(
            id = existing?.id,
            name = payload.optString("name"),
            description = payload.optString("description"),
            question = payload.optString("question"),
            freqNum = payload.intOrDefault("freq_num", 1, event),
            freqDen = payload.intOrDefault("freq_den", 1, event),
            color = payload.intOrDefault("color", 0, event),
            position = position,
            reminderHour = payload.optionalInt("reminder_hour", event),
            reminderMin = payload.optionalInt("reminder_min", event),
            reminderDays = payload.intOrDefault("reminder_days", 0, event),
            highlight = existing?.highlight ?: 0,
            archived = if (payload.optString("archived", "false").toBoolean()) 1 else 0,
            type = payload.intOrDefault("type", 0, event),
            targetValue = payload.doubleOrDefault("target_value", 0.0, event),
            targetType = payload.intOrDefault("target_type", 0, event),
            unit = payload.optString("unit"),
            uuid = event.entityUuid,
            updatedAt = event.createdAt,
            deletedAt = if (event.operationType == "delete") event.createdAt else null
        )

        val habitId = if (existing == null) modelFactory.habitRepository.insert(updated)
        else {
            modelFactory.habitRepository.update(updated)
            existing.id!!
        }

        // Determine the blockId to write to HabitExtensions:
        //   - In Next architecture: blockId is always null (containers provide organization)
        //   - Legacy:
        //       - Missing field (legacy payload) => keep existing assignment
        //       - Explicit null (Other) => write null
        //       - Resolved block => write its local id
        //       - Unresolved (block not yet in DB) => keep existing assignment; do NOT write null
        val existingExtension = modelFactory.habitExtensionRepository.findByHabitId(habitId)
        val blockIdToWrite = if (session.isNextArchitecture) {
            null
        } else {
            when {
                !hasBlockUuid -> existingExtension?.blockId
                blockUuid == null -> null
                resolvedBlock != null -> resolvedBlock.id
                else -> existingExtension?.blockId
            }
        }

        modelFactory.habitExtensionRepository.upsert(
            HabitExtensionData(
                habitId = habitId,
                dayTier = payload.optString("day_tier", existingExtension?.dayTier ?: "NORMAL"),
                timerEnabled = payload.optString("timer_enabled", (existingExtension?.timerEnabled ?: false).toString()).toBoolean(),
                blockId = blockIdToWrite,
                statsStartTimestamp = payload.optionalLong("statistics_start", event) ?: existingExtension?.statsStartTimestamp
            )
        )

        if (blockUnresolved) {
            Log.w("SyncCoordinator", "applyHabit: block_uuid=$blockUuid not found locally for habit ${event.entityUuid}; preserving existing blockId=${existingExtension?.blockId} for second-pass resolve")
            return UnresolvedHabitBlock(
                habitId = habitId,
                habitUuid = event.entityUuid,
                remoteBlockUuid = blockUuid!!,
                eventLogId = event.logId
            )
        }
        return null
    }

    private fun applyGoal(event: RemoteSyncEvent): Boolean {
        val payload = JSONObject(event.payloadJson)
        val habit = modelFactory.habitRepository.findByUuid(payload.getString("habit_uuid"))
        if (habit == null) {
            deferRemoteEvent(
                SyncInboxRecord(
                    logId = event.logId,
                    deviceId = event.deviceId,
                    opUuid = event.opUuid,
                    entityType = event.entityType,
                    entityUuid = event.entityUuid,
                    operationType = event.operationType,
                    payloadJson = event.payloadJson,
                    createdAt = event.createdAt,
                    receivedAt = System.currentTimeMillis(),
                    deferredReason = "MISSING_PARENT_HABIT",
                    status = "PENDING"
                )
            )
            return false
        }
        val existing = modelFactory.habitGoalRepository.findByUuid(event.entityUuid)
        if (!shouldApply(existing?.updatedAt ?: 0L, existing?.deletedAt, event)) {
            modelFactory.syncInboxRepository.deleteByLogId(event.logId)
            return true
        }
        modelFactory.habitGoalRepository.upsert(
            HabitGoalData(
                id = existing?.id,
                habitId = habit.id!!,
                uuid = event.entityUuid,
                effectiveTimestamp = payload.requiredLong("effective_timestamp", event),
                freqNum = payload.requiredInt("freq_num", event),
                freqDen = payload.requiredInt("freq_den", event),
                targetType = payload.requiredInt("target_type", event),
                targetValue = payload.requiredDouble("target_value", event),
                unit = payload.optString("unit"),
                updatedAt = event.createdAt,
                deletedAt = if (event.operationType == "delete") event.createdAt else null
            )
        )
        modelFactory.syncInboxRepository.deleteByLogId(event.logId)
        return true
    }

    private fun applyEntry(event: RemoteSyncEvent): Boolean {
        val payload = JSONObject(event.payloadJson)
        val habit = modelFactory.habitRepository.findByUuid(payload.getString("habit_uuid"))
        if (habit == null) {
            deferRemoteEvent(
                SyncInboxRecord(
                    logId = event.logId,
                    deviceId = event.deviceId,
                    opUuid = event.opUuid,
                    entityType = event.entityType,
                    entityUuid = event.entityUuid,
                    operationType = event.operationType,
                    payloadJson = event.payloadJson,
                    createdAt = event.createdAt,
                    receivedAt = System.currentTimeMillis(),
                    deferredReason = "MISSING_PARENT_HABIT",
                    status = "PENDING"
                )
            )
            return false
        }
        val existing = modelFactory.entryRepository.findByUuid(event.entityUuid)
        if (!shouldApply(existing?.updatedAt ?: 0L, existing?.deletedAt, event)) {
            modelFactory.syncInboxRepository.deleteByLogId(event.logId)
            return true
        }
        modelFactory.entryRepository.upsert(
            EntryData(
                id = existing?.id,
                habitId = habit.id!!,
                uuid = event.entityUuid,
                timestamp = payload.requiredLong("entry_date", event),
                value = if (event.operationType == "delete") Entry.UNKNOWN
                else payload.intOrDefault("value", Entry.UNKNOWN, event),
                notes = payload.optString("notes"),
                updatedAt = event.createdAt,
                deletedAt = if (event.operationType == "delete") event.createdAt else null
            )
        )
        modelFactory.syncInboxRepository.deleteByLogId(event.logId)
        return true
    }

    private fun applyEntryOp(event: RemoteSyncEvent): Boolean {
        if (modelFactory.entryOpRepository.findByOpUuid(event.entityUuid) != null) return true
        val payload = JSONObject(event.payloadJson)
        val habitUuid = payload.getString("habit_uuid")
        val habit = modelFactory.habitRepository.findByUuid(habitUuid)
        if (habit == null) {
            deferRemoteEvent(
                SyncInboxRecord(
                    logId = event.logId, deviceId = event.deviceId, opUuid = event.opUuid,
                    entityType = event.entityType, entityUuid = event.entityUuid,
                    operationType = event.operationType, payloadJson = event.payloadJson,
                    createdAt = event.createdAt, receivedAt = System.currentTimeMillis(),
                    deferredReason = "MISSING_PARENT_HABIT"
                )
            )
            return false
        }
        val timestamp = payload.requiredLong("entry_date", event)
        modelFactory.entryOpRepository.insert(
            org.isoron.uhabits.core.sync.EntryOpRecord(
                opUuid = event.entityUuid,
                habitUuid = habitUuid,
                entryTimestamp = timestamp,
                deltaValue = payload.requiredInt("delta_value", event),
                opType = payload.getString("op_type"),
                notes = payload.optString("notes").ifBlank { null },
                deviceId = event.deviceId,
                createdAt = event.createdAt
            )
        )

        val total = modelFactory.entryOpRepository.findActive(habitUuid, timestamp).sumOf { it.deltaValue }
        val entryUuid = "$habitUuid:$timestamp"
        val existing = modelFactory.entryRepository.findByUuid(entryUuid)
        modelFactory.entryRepository.upsert(
            EntryData(
                id = existing?.id,
                habitId = habit.id!!,
                uuid = entryUuid,
                timestamp = timestamp,
                value = total,
                notes = existing?.notes ?: payload.optString("notes"),
                updatedAt = maxOf(existing?.updatedAt ?: 0L, event.createdAt),
                deletedAt = null
            )
        )
        return true
    }

    private fun applyAppSetting(event: RemoteSyncEvent) {
        val payload = JSONObject(event.payloadJson)
        if (payload.optString("key") != GLOBAL_STATS_START_KEY) return
        val value = payload.optionalLong("long_value", event)
        modelFactory.appSettingRepository.putLong(GLOBAL_STATS_START_KEY, value)
    }

    private fun JSONObject.optionalString(field: String): String? {
        if (!has(field) || isNull(field)) return null
        return optString(field).trim().takeUnless { it.isEmpty() || it == "null" }
    }

    private fun JSONObject.numericText(field: String, event: RemoteSyncEvent): String? {
        if (!has(field) || isNull(field)) return null
        val value = get(field)
        val text = when (value) {
            is Number -> value.toString()
            is String -> value.trim()
            else -> throw invalidNumericField(field, value, event)
        }
        return text.takeUnless { it.isEmpty() || it == "null" }
    }

    private fun JSONObject.optionalInt(field: String, event: RemoteSyncEvent): Int? {
        val text = numericText(field, event) ?: return null
        return text.toIntOrNull() ?: throw invalidNumericField(field, text, event)
    }

    private fun JSONObject.optionalLong(field: String, event: RemoteSyncEvent): Long? {
        val text = numericText(field, event) ?: return null
        return text.toLongOrNull() ?: throw invalidNumericField(field, text, event)
    }

    private fun JSONObject.optionalDouble(field: String, event: RemoteSyncEvent): Double? {
        val text = numericText(field, event) ?: return null
        return text.toDoubleOrNull() ?: throw invalidNumericField(field, text, event)
    }

    private fun JSONObject.requiredInt(field: String, event: RemoteSyncEvent): Int =
        optionalInt(field, event) ?: throw missingNumericField(field, event)

    private fun JSONObject.requiredLong(field: String, event: RemoteSyncEvent): Long =
        optionalLong(field, event) ?: throw missingNumericField(field, event)

    private fun JSONObject.requiredDouble(field: String, event: RemoteSyncEvent): Double =
        optionalDouble(field, event) ?: throw missingNumericField(field, event)

    private fun JSONObject.intOrDefault(field: String, default: Int, event: RemoteSyncEvent): Int =
        optionalInt(field, event) ?: default

    private fun JSONObject.doubleOrDefault(field: String, default: Double, event: RemoteSyncEvent): Double =
        optionalDouble(field, event) ?: default

    private fun invalidNumericField(field: String, value: Any, event: RemoteSyncEvent) =
        IllegalStateException(
            "Invalid numeric sync payload field '$field' for ${event.entityType}/${event.entityUuid} " +
                "(log_id=${event.logId}, value=$value); payload=${event.payloadJson.take(1000)}"
        )

    private fun missingNumericField(field: String, event: RemoteSyncEvent) =
        IllegalStateException(
            "Missing required numeric sync payload field '$field' for ${event.entityType}/${event.entityUuid} " +
                "(log_id=${event.logId}); payload=${event.payloadJson.take(1000)}"
        )

    private fun shouldApply(localUpdatedAt: Long, localDeletedAt: Long?, event: RemoteSyncEvent): Boolean {
        if (event.operationType != "delete" && localDeletedAt != null && localDeletedAt > event.createdAt) {
            return false
        }
        return event.createdAt >= localUpdatedAt
    }

    private fun isNextSchemaAvailable(): Boolean =
        modelFactory.database.querySingle(
            "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='Containers'"
        ) { stmt -> stmt.getLong(0) > 0 } ?: false

    private fun ensureRemoteOrganizationRevision(
        opUuid: String,
        recordedAt: Long,
        operationType: String,
        payload: String
    ): Long {
        val existingRevision = modelFactory.database.querySingle(
            "SELECT revision FROM OrganizationChanges WHERE op_uuid = ?",
            opUuid
        ) { it.getLong(0) }
        if (existingRevision != null) return existingRevision

        val maxRev = modelFactory.database.querySingle(
            "SELECT COALESCE(MAX(revision), 0) FROM OrganizationChanges"
        ) { it.getLong(0) } ?: 0L
        val newRev = maxRev + 1L

        val stmt = modelFactory.database.prepareStatement(
            "INSERT INTO OrganizationChanges (revision, op_uuid, recorded_at, operation_type, origin, command_payload) VALUES (?, ?, ?, ?, ?, ?)"
        )
        stmt.bindLong(1, newRev)
        stmt.bindText(2, opUuid)
        stmt.bindLong(3, recordedAt)
        stmt.bindText(4, operationType)
        stmt.bindText(5, "REMOTE_SYNC")
        stmt.bindText(6, payload)
        stmt.step()
        stmt.finalize()

        modelFactory.database.run(
            "UPDATE OrganizationState SET current_revision = MAX(current_revision, $newRev) WHERE id = 1"
        )

        return newRev
    }

    private fun applyContainer(event: RemoteSyncEvent, unresolvedParents: MutableList<Pair<String, String>>) {
        if (!isNextSchemaAvailable()) return
        val payload = JSONObject(event.payloadJson)
        val containerUuid = event.entityUuid
        val existing = modelFactory.database.querySingle(
            "SELECT id, updated_at, deleted_at FROM Containers WHERE uuid = ?",
            containerUuid
        ) { stmt ->
            Triple(stmt.getLong(0), stmt.getLong(1), stmt.getLongOrNull(2))
        }
        val localUpdatedAt = existing?.second ?: 0L
        val localDeletedAt = existing?.third
        if (!shouldApply(localUpdatedAt, localDeletedAt, event)) return

        val revision = ensureRemoteOrganizationRevision(
            event.opUuid,
            event.createdAt,
            event.operationType,
            event.payloadJson
        )

        if (event.operationType == "delete") {
            val deletedAt = payload.optionalLong("deleted_at", event) ?: event.createdAt
            modelFactory.database.run(
                "UPDATE Containers SET deleted_at = $deletedAt, updated_at = ${event.createdAt}, revision = $revision WHERE uuid = '$containerUuid'"
            )
            return
        }

        val name = payload.optString("name", "Untitled")
        val color = payload.optionalInt("color", event)
        val icon = payload.optionalString("icon")
        val siblingOrder = payload.intOrDefault("sibling_order", 0, event)
        val isArchived = if (payload.optString("is_archived", "false").toBoolean()) 1 else 0
        val createdAt = payload.optionalLong("created_at", event) ?: event.createdAt
        val parentUuid = payload.optionalString("parent_uuid")
        val parentLocalId = if (parentUuid != null) {
            modelFactory.database.querySingle(
                "SELECT id FROM Containers WHERE uuid = ?",
                parentUuid
            ) { it.getLong(0) }
        } else null

        if (parentUuid != null && parentLocalId == null) {
            unresolvedParents.add(containerUuid to parentUuid)
        }

        if (existing == null) {
            val stmt = modelFactory.database.prepareStatement(
                """INSERT INTO Containers (
                    uuid, parent_id, name, color, icon, sibling_order, is_archived,
                    created_at, updated_at, deleted_at, revision
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?)"""
            )
            stmt.bindText(1, containerUuid)
            if (parentLocalId != null) stmt.bindLong(2, parentLocalId) else stmt.bindNull(2)
            stmt.bindText(3, name)
            if (color != null) stmt.bindInt(4, color) else stmt.bindNull(4)
            if (icon != null) stmt.bindText(5, icon) else stmt.bindNull(5)
            stmt.bindInt(6, siblingOrder)
            stmt.bindInt(7, isArchived)
            stmt.bindLong(8, createdAt)
            stmt.bindLong(9, event.createdAt)
            stmt.bindLong(10, revision)
            stmt.step()
            stmt.finalize()
        } else {
            val stmt = modelFactory.database.prepareStatement(
                """UPDATE Containers SET
                    parent_id = ?, name = ?, color = ?, icon = ?, sibling_order = ?,
                    is_archived = ?, created_at = ?, updated_at = ?, deleted_at = NULL, revision = ?
                WHERE uuid = ?"""
            )
            if (parentLocalId != null) stmt.bindLong(1, parentLocalId) else stmt.bindNull(1)
            stmt.bindText(2, name)
            if (color != null) stmt.bindInt(3, color) else stmt.bindNull(3)
            if (icon != null) stmt.bindText(4, icon) else stmt.bindNull(4)
            stmt.bindInt(5, siblingOrder)
            stmt.bindInt(6, isArchived)
            stmt.bindLong(7, createdAt)
            stmt.bindLong(8, event.createdAt)
            stmt.bindLong(9, revision)
            stmt.bindText(10, containerUuid)
            stmt.step()
            stmt.finalize()
        }
    }

    private fun applyHabitPlacement(event: RemoteSyncEvent): Boolean {
        if (!isNextSchemaAvailable()) return true
        val payload = JSONObject(event.payloadJson)
        val habitUuid = payload.optString("habit_uuid", event.entityUuid)
        val habitLocalId = modelFactory.database.querySingle(
            "SELECT id FROM Habits WHERE uuid = ? AND deleted_at IS NULL",
            habitUuid
        ) { it.getLong(0) }
        if (habitLocalId == null) {
            deferRemoteEvent(
                SyncInboxRecord(
                    logId = event.logId,
                    deviceId = event.deviceId,
                    opUuid = event.opUuid,
                    entityType = event.entityType,
                    entityUuid = event.entityUuid,
                    operationType = event.operationType,
                    payloadJson = event.payloadJson,
                    createdAt = event.createdAt,
                    receivedAt = System.currentTimeMillis(),
                    deferredReason = "MISSING_PARENT_HABIT",
                    status = "PENDING"
                )
            )
            return false
        }

        val containerUuid = payload.optionalString("container_uuid")
        val containerLocalId = if (containerUuid != null) {
            modelFactory.database.querySingle(
                "SELECT id FROM Containers WHERE uuid = ? AND deleted_at IS NULL",
                containerUuid
            ) { it.getLong(0) }
        } else null

        val localOrder = payload.intOrDefault("local_order", 0, event)
        val revision = ensureRemoteOrganizationRevision(
            event.opUuid,
            event.createdAt,
            event.operationType,
            event.payloadJson
        )

        val stmt = modelFactory.database.prepareStatement(
            """INSERT OR REPLACE INTO HabitPlacements (
                habit_id, container_id, local_order, placement_origin, revision
            ) VALUES (?, ?, ?, ?, ?)"""
        )
        stmt.bindLong(1, habitLocalId)
        if (containerLocalId != null) stmt.bindLong(2, containerLocalId) else stmt.bindNull(2)
        stmt.bindInt(3, localOrder)
        stmt.bindText(4, "USER_CHANGE")
        stmt.bindLong(5, revision)
        stmt.step()
        stmt.finalize()

        modelFactory.syncInboxRepository.deleteByLogId(event.logId)
        return true
    }

    private fun hasUnsupportedTaskPlan(taskId: String): Boolean {
        if (!isNextSchemaAvailable()) return true
        val raw = modelFactory.database.querySingle("SELECT planned_date FROM UserTasks WHERE id = ?", taskId) {
            it.getTextOrNull(0)
        }
        return try {
            parseTaskPlannedDate(raw)
            false
        } catch (_: IllegalArgumentException) {
            true
        } catch (_: IndexOutOfBoundsException) {
            true
        }
    }

    private fun applyUserTask(event: RemoteSyncEvent): Boolean {
        if (!isNextSchemaAvailable()) return true
        val taskId = event.entityUuid

        if (modelFactory.syncTombstoneRepository.isDeleted("user_task", taskId, event.createdAt)) {
            return true
        }

        val existingUpdatedAt = modelFactory.database.querySingle(
            "SELECT updated_at FROM UserTasks WHERE id = ?",
            taskId
        ) { it.getLong(0) }

        if (existingUpdatedAt != null && event.createdAt < existingUpdatedAt) {
            return true
        }

        if (event.operationType == "delete") {
            modelFactory.syncTombstoneRepository.recordTombstone("user_task", taskId, event.createdAt)
            modelFactory.syncInboxRepository.purgeForEntity("user_task", taskId)
            modelFactory.database.run("DELETE FROM UserTasks WHERE id = '$taskId'")
            return true
        }

        val payload = JSONObject(event.payloadJson)
        val title = payload.optString("title", "Task")
        val description = payload.optString("description", "")
        val containerId = payload.optionalString("container_id")
        val status = payload.optString("status", "PENDING")
        val plannedDate = try {
            normalizeTaskPlannedDateForSync(
                payload.optionalString("planned_date")
            )
        } catch (e: Exception) {
            when (e) {
                is IllegalArgumentException, is IndexOutOfBoundsException -> {
                    deferRemoteEvent(
                        SyncInboxRecord(
                            logId = event.logId,
                            deviceId = event.deviceId,
                            opUuid = event.opUuid,
                            entityType = event.entityType,
                            entityUuid = event.entityUuid,
                            operationType = event.operationType,
                            payloadJson = event.payloadJson,
                            createdAt = event.createdAt,
                            receivedAt = System.currentTimeMillis(),
                            deferredReason = "MALFORMED_TASK_PAYLOAD",
                            status = "BLOCKED_MALFORMED"
                        )
                    )
                    return false
                }
                else -> throw e
            }
        }

        val dueKind: String
        val dueDate: String?
        val dueTimeMillis: Long?
        val dueZone: String?

        val rawDueKind = payload.optionalString("due_kind")
        if (rawDueKind != null) {
            dueKind = rawDueKind
            dueDate = payload.optionalString("due_date")
            dueTimeMillis = payload.optionalLong("due_time_millis", event)
            dueZone = payload.optionalString("due_zone")
            if (dueDate != null) {
                try {
                    if (org.isoron.uhabits.core.userTasks.parseIsoDateString(dueDate) == null) {
                        throw IllegalArgumentException("Malformed due_date: $dueDate")
                    }
                } catch (e: Exception) {
                    deferRemoteEvent(
                        SyncInboxRecord(
                            logId = event.logId,
                            deviceId = event.deviceId,
                            opUuid = event.opUuid,
                            entityType = event.entityType,
                            entityUuid = event.entityUuid,
                            operationType = event.operationType,
                            payloadJson = event.payloadJson,
                            createdAt = event.createdAt,
                            receivedAt = System.currentTimeMillis(),
                            deferredReason = "MALFORMED_TASK_PAYLOAD",
                            status = "BLOCKED_MALFORMED"
                        )
                    )
                    return false
                }
            }
        } else {
            val deadline = payload.optionalLong("deadline", event)
            if (deadline != null && deadline > 0) {
                dueKind = "TIMED"
                dueDate = null
                dueTimeMillis = deadline
                dueZone = null
            } else {
                val existingDue = modelFactory.database.querySingle(
                    "SELECT due_kind, due_date, due_time_millis, due_zone FROM UserTasks WHERE id = ?",
                    taskId
                ) { Quad(it.getText(0), it.getTextOrNull(1), it.getLongOrNull(2), it.getTextOrNull(3)) }

                if (existingDue != null && existingDue.first != "NONE") {
                    dueKind = existingDue.first
                    dueDate = existingDue.second
                    dueTimeMillis = existingDue.third
                    dueZone = existingDue.fourth
                } else {
                    dueKind = "NONE"
                    dueDate = null
                    dueTimeMillis = null
                    dueZone = null
                }
            }
        }

        val completedAt = payload.optionalLong("completed_at", event)
        val parentTaskId = payload.optionalString("parent_task_id")
        val sortOrder = payload.intOrDefault("sort_order", 0, event)
        val createdAt = payload.optionalLong("created_at", event) ?: event.createdAt
        val updatedAt = payload.optionalLong("updated_at", event) ?: event.createdAt

        // A remote title-only/null upsert must not erase unresolved local raw data.
        if (hasUnsupportedTaskPlan(taskId)) {
            deferRemoteEvent(
                SyncInboxRecord(
                    logId = event.logId,
                    deviceId = event.deviceId,
                    opUuid = event.opUuid,
                    entityType = event.entityType,
                    entityUuid = event.entityUuid,
                    operationType = event.operationType,
                    payloadJson = event.payloadJson,
                    createdAt = event.createdAt,
                    receivedAt = System.currentTimeMillis(),
                    deferredReason = "UNSUPPORTED_TASK_PLAN",
                    status = "PENDING"
                )
            )
            return false
        }

        org.isoron.uhabits.core.userTasks.sqlite.UserTaskSqlWriter.upsert(modelFactory.database, taskId) {
            bindText(1, taskId)
            bindText(2, title)
            bindText(3, description)
            if (containerId != null) bindText(4, containerId) else bindNull(4)
            bindText(5, status)
            bindText(6, dueKind)
            if (dueDate != null) bindText(7, dueDate) else bindNull(7)
            if (dueTimeMillis != null) bindLong(8, dueTimeMillis) else bindNull(8)
            if (dueZone != null) bindText(9, dueZone) else bindNull(9)
            if (completedAt != null) bindLong(10, completedAt) else bindNull(10)
            if (parentTaskId != null) bindText(11, parentTaskId) else bindNull(11)
            bindInt(12, sortOrder)
            bindLong(13, createdAt)
            bindLong(14, updatedAt)
            if (plannedDate != null) bindText(15, plannedDate) else bindNull(15)
        }

        modelFactory.syncInboxRepository.deleteByLogId(event.logId)
        return true
    }

    private fun applyFocusSession(event: RemoteSyncEvent) {
        if (!isNextSchemaAvailable()) return
        val sessionId = event.entityUuid

        if (modelFactory.syncTombstoneRepository.isDeleted("focus_session", sessionId, event.createdAt)) {
            return
        }

        val existingUpdatedAt = modelFactory.database.querySingle(
            "SELECT updated_at FROM FocusSessions WHERE id = ?",
            sessionId
        ) { it.getLong(0) }

        if (existingUpdatedAt != null && event.createdAt < existingUpdatedAt) {
            return
        }

        if (event.operationType == "delete") {
            modelFactory.syncTombstoneRepository.recordTombstone("focus_session", sessionId, event.createdAt)
            modelFactory.syncInboxRepository.purgeForEntity("focus_session", sessionId)
            modelFactory.database.run("DELETE FROM FocusSessions WHERE id = '$sessionId'")
            return
        }

        val payload = JSONObject(event.payloadJson)
        val startedAt = payload.requiredLong("started_at", event)
        val endedAt = payload.requiredLong("ended_at", event)
        val durationMs = payload.requiredLong("duration_ms", event)
        val taskId = payload.optionalString("task_id")
        val habitUuid = payload.optionalString("habit_uuid")
        val containerId = payload.optionalString("container_id")
        val source = payload.optString("source", "MANUAL")
        val deviceId = payload.optionalString("device_id")
        val createdAt = payload.optionalLong("created_at", event) ?: event.createdAt
        val updatedAt = payload.optionalLong("updated_at", event) ?: event.createdAt
        val primaryTargetType = payload.optionalString("primary_target_type")
        val organizationContext = payload.optionalString("organization_context")

        val stmt = modelFactory.database.prepareStatement(
            """INSERT OR REPLACE INTO FocusSessions (
                id, started_at, ended_at, duration_ms, task_id, habit_uuid,
                container_id, source, device_id, created_at, updated_at,
                revision, primary_target_type, organization_context
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)"""
        )
        stmt.bindText(1, sessionId)
        stmt.bindLong(2, startedAt)
        stmt.bindLong(3, endedAt)
        stmt.bindLong(4, durationMs)
        if (taskId != null) stmt.bindText(5, taskId) else stmt.bindNull(5)
        if (habitUuid != null) stmt.bindText(6, habitUuid) else stmt.bindNull(6)
        if (containerId != null) stmt.bindText(7, containerId) else stmt.bindNull(7)
        stmt.bindText(8, source)
        if (deviceId != null) stmt.bindText(9, deviceId) else stmt.bindNull(9)
        stmt.bindLong(10, createdAt)
        stmt.bindLong(11, updatedAt)
        if (primaryTargetType != null) stmt.bindText(12, primaryTargetType) else stmt.bindNull(12)
        if (organizationContext != null) stmt.bindText(13, organizationContext) else stmt.bindNull(13)
        stmt.step()
        stmt.finalize()

        modelFactory.syncInboxRepository.purgeForEntity("focus_session", sessionId)
    }

    private fun applyContribution(event: RemoteSyncEvent): Boolean {
        if (!isNextSchemaAvailable()) return true
        val payload = JSONObject(event.payloadJson)
        val contribId = event.entityUuid
        val existing = modelFactory.database.querySingle(
            "SELECT updated_at, removed_at FROM Contributions WHERE id = ?",
            contribId
        ) { stmt -> Pair(stmt.getLong(0), stmt.getLongOrNull(1)) }

        val localUpdatedAt = existing?.first ?: 0L
        val localRemovedAt = existing?.second
        if (!shouldApply(localUpdatedAt, localRemovedAt, event)) return true

        if (event.operationType == "remove") {
            val removedAt = payload.optionalLong("removed_at", event) ?: event.createdAt
            modelFactory.database.run(
                "UPDATE Contributions SET removed_at = $removedAt, updated_at = ${event.createdAt} WHERE id = '$contribId'"
            )
            return true
        }

        val targetHabitUuid = payload.getString("target_habit_uuid")
        val sourceType = payload.optString("source_type", "FOCUS_SESSION")
        val sourceId = payload.getString("source_id")
        val contributedValue = payload.requiredInt("contributed_value", event)
        val effectiveTimestamp = payload.requiredLong("effective_timestamp", event)
        val createdAt = payload.optionalLong("created_at", event) ?: event.createdAt
        val updatedAt = payload.optionalLong("updated_at", event) ?: event.createdAt
        val removedAt = payload.optionalLong("removed_at", event)
        val sourceRuleId = payload.optionalString("source_rule_id")

        val stmt = modelFactory.database.prepareStatement(
            """INSERT OR REPLACE INTO Contributions (
                id, target_habit_uuid, source_type, source_id, contributed_value,
                effective_timestamp, created_at, updated_at, removed_at, source_rule_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"""
        )
        stmt.bindText(1, contribId)
        stmt.bindText(2, targetHabitUuid)
        stmt.bindText(3, sourceType)
        stmt.bindText(4, sourceId)
        stmt.bindInt(5, contributedValue)
        stmt.bindLong(6, effectiveTimestamp)
        stmt.bindLong(7, createdAt)
        stmt.bindLong(8, updatedAt)
        if (removedAt != null) stmt.bindLong(9, removedAt) else stmt.bindNull(9)
        if (sourceRuleId != null) stmt.bindText(10, sourceRuleId) else stmt.bindNull(10)
        stmt.step()
        stmt.finalize()

        return true
    }

    private fun bootstrapNextEntities(now: Long, deviceId: String): Int {
        if (!isNextSchemaAvailable()) return 0
        var inserted = 0

        // 1. Containers
        modelFactory.database.query(
            """SELECT c.uuid, p.uuid, c.name, c.color, c.icon, c.sibling_order,
                      c.is_archived, c.created_at, c.updated_at, c.deleted_at
               FROM Containers c
               LEFT JOIN Containers p ON c.parent_id = p.id"""
        ) { stmt ->
            val uuid = stmt.getText(0)
            val parentUuid = stmt.getTextOrNull(1)
            val name = stmt.getText(2)
            val color = stmt.getLongOrNull(3)
            val icon = stmt.getTextOrNull(4)
            val siblingOrder = stmt.getInt(5)
            val isArchived = stmt.getInt(6) == 1
            val createdAt = stmt.getLongOrNull(7)
            val updatedAt = stmt.getLong(8)
            val deletedAt = stmt.getLongOrNull(9)

            val payload = json(
                "uuid" to uuid,
                "parent_uuid" to parentUuid,
                "name" to name,
                "color" to color?.toString(),
                "icon" to icon,
                "sibling_order" to siblingOrder.toString(),
                "is_archived" to isArchived.toString(),
                "created_at" to createdAt?.toString(),
                "updated_at" to updatedAt.toString(),
                "deleted_at" to deletedAt?.toString()
            )
            inserted += insertBootstrapRecordIfMissing(
                opUuid = UUID.randomUUID().toString().replace("-", ""),
                entityType = "container",
                entityUuid = uuid,
                operationType = if (deletedAt != null) "delete" else "upsert",
                payloadJson = payload,
                createdAt = updatedAt,
                deviceId = deviceId
            )
        }

        // 2. Habit Placements
        modelFactory.database.query(
            """SELECT h.uuid, c.uuid, p.local_order
               FROM HabitPlacements p
               INNER JOIN Habits h ON h.id = p.habit_id
               LEFT JOIN Containers c ON c.id = p.container_id
               WHERE h.deleted_at IS NULL AND h.uuid IS NOT NULL"""
        ) { stmt ->
            val habitUuid = stmt.getText(0)
            val containerUuid = stmt.getTextOrNull(1)
            val localOrder = stmt.getInt(2)

            val payload = json(
                "habit_uuid" to habitUuid,
                "container_uuid" to containerUuid,
                "local_order" to localOrder.toString(),
                "updated_at" to now.toString()
            )
            inserted += insertBootstrapRecordIfMissing(
                opUuid = UUID.randomUUID().toString().replace("-", ""),
                entityType = "habit_placement",
                entityUuid = habitUuid,
                operationType = "place",
                payloadJson = payload,
                createdAt = now,
                deviceId = deviceId
            )
        }

        // 3. User Tasks
        modelFactory.database.query(
            """SELECT id, title, description, container_id, status, due_kind,
                      due_date, due_time_millis, due_zone, completed_at, parent_task_id, sort_order, created_at, updated_at
               FROM UserTasks"""
        ) { stmt ->
            val taskId = stmt.getText(0)
            val title = stmt.getText(1)
            val description = stmt.getText(2)
            val containerId = stmt.getTextOrNull(3)
            val status = stmt.getText(4)
            val dueKind = stmt.getText(5)
            val dueDate = stmt.getTextOrNull(6)
            val dueTimeMillis = stmt.getLongOrNull(7)
            val dueZone = stmt.getTextOrNull(8)
            val completedAt = stmt.getLongOrNull(9)
            val parentTaskId = stmt.getTextOrNull(10)
            val sortOrder = stmt.getInt(11)
            val createdAt = stmt.getLong(12)
            val updatedAt = stmt.getLong(13)

            val payload = json(
                "id" to taskId,
                "title" to title,
                "description" to description,
                "container_id" to containerId,
                "status" to status,
                "due_kind" to dueKind,
                "due_date" to dueDate,
                "due_time_millis" to dueTimeMillis?.toString(),
                "due_zone" to dueZone,
                "completed_at" to completedAt?.toString(),
                "parent_task_id" to parentTaskId,
                "sort_order" to sortOrder.toString(),
                "created_at" to createdAt.toString(),
                "updated_at" to updatedAt.toString()
            )
            inserted += insertBootstrapRecordIfMissing(
                opUuid = UUID.randomUUID().toString().replace("-", ""),
                entityType = "user_task",
                entityUuid = taskId,
                operationType = "upsert",
                payloadJson = payload,
                createdAt = updatedAt,
                deviceId = deviceId
            )
        }

        // 4. Focus Sessions
        modelFactory.database.query(
            """SELECT id, started_at, ended_at, duration_ms, task_id, habit_uuid,
                      container_id, source, device_id, created_at, updated_at,
                      primary_target_type, organization_context
               FROM FocusSessions"""
        ) { stmt ->
            val id = stmt.getText(0)
            val startedAt = stmt.getLong(1)
            val endedAt = stmt.getLong(2)
            val durationMs = stmt.getLong(3)
            val taskId = stmt.getTextOrNull(4)
            val habitUuid = stmt.getTextOrNull(5)
            val containerId = stmt.getTextOrNull(6)
            val source = stmt.getText(7)
            val rowDeviceId = stmt.getTextOrNull(8) ?: deviceId
            val createdAt = stmt.getLong(9)
            val updatedAt = stmt.getLong(10)
            val primaryTargetType = stmt.getTextOrNull(11)
            val orgContext = stmt.getTextOrNull(12)

            val payload = json(
                "id" to id,
                "started_at" to startedAt.toString(),
                "ended_at" to endedAt.toString(),
                "duration_ms" to durationMs.toString(),
                "task_id" to taskId,
                "habit_uuid" to habitUuid,
                "container_id" to containerId,
                "source" to source,
                "device_id" to rowDeviceId,
                "created_at" to createdAt.toString(),
                "updated_at" to updatedAt.toString(),
                "primary_target_type" to primaryTargetType,
                "organization_context" to orgContext
            )
            inserted += insertBootstrapRecordIfMissing(
                opUuid = UUID.randomUUID().toString().replace("-", ""),
                entityType = "focus_session",
                entityUuid = id,
                operationType = "upsert",
                payloadJson = payload,
                createdAt = updatedAt,
                deviceId = deviceId
            )
        }

        // 5. Contributions
        modelFactory.database.query(
            """SELECT id, target_habit_uuid, source_type, source_id, contributed_value,
                      effective_timestamp, created_at, updated_at, removed_at, source_rule_id
               FROM Contributions"""
        ) { stmt ->
            val id = stmt.getText(0)
            val targetHabitUuid = stmt.getText(1)
            val sourceType = stmt.getText(2)
            val sourceId = stmt.getText(3)
            val contributedValue = stmt.getInt(4)
            val effectiveTimestamp = stmt.getLong(5)
            val createdAt = stmt.getLong(6)
            val updatedAt = stmt.getLong(7)
            val removedAt = stmt.getLongOrNull(8)
            val sourceRuleId = stmt.getTextOrNull(9)

            val payload = json(
                "id" to id,
                "target_habit_uuid" to targetHabitUuid,
                "source_type" to sourceType,
                "source_id" to sourceId,
                "contributed_value" to contributedValue.toString(),
                "effective_timestamp" to effectiveTimestamp.toString(),
                "created_at" to createdAt.toString(),
                "updated_at" to updatedAt.toString(),
                "removed_at" to removedAt?.toString(),
                "source_rule_id" to sourceRuleId
            )
            inserted += insertBootstrapRecordIfMissing(
                opUuid = UUID.randomUUID().toString().replace("-", ""),
                entityType = "contribution",
                entityUuid = id,
                operationType = if (removedAt != null) "remove" else "upsert",
                payloadJson = payload,
                createdAt = updatedAt,
                deviceId = deviceId
            )
        }

        return inserted
    }

    fun dispose() {
        synchronized(this) {
            disposed = true
            syncContextGeneration++
        }
        modelFactory.syncManager.onQueueAppended = null
        runCatching { syncScope.cancel() }
        synchronized(syncListeners) { syncListeners.clear() }
    }
}

fun interface DeviceIdProvider {
    fun value(): String
}
