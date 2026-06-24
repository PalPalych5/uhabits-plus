package org.isoron.uhabits.sync

import me.tatarka.inject.annotations.Inject
import org.isoron.uhabits.core.database.EntryData
import org.isoron.uhabits.core.database.HabitBlockData
import org.isoron.uhabits.core.database.HabitData
import org.isoron.uhabits.core.database.HabitExtensionData
import org.isoron.uhabits.core.database.HabitGoalData
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.sqlite.SQLModelFactory
import org.isoron.uhabits.core.models.sqlite.SQLiteHabitList
import org.isoron.uhabits.core.preferences.Preferences
import org.json.JSONObject

@Inject
class SyncCoordinator(
    private val modelFactory: SQLModelFactory,
    private val habitList: SQLiteHabitList,
    private val preferences: Preferences,
    private val authStore: SyncAuthStore,
    private val backend: SyncBackend,
    private val deviceIdProvider: DeviceIdProvider
) {
    fun isSignedIn(): Boolean = authStore.load() != null

    fun currentAccountEmail(): String? =
        runCatching { authStore.load()?.email ?: preferences.syncAccountEmail }.getOrNull()

    suspend fun signIn(email: String, password: String): SyncRunResult {
        val config = requireConfig()
            ?: return configurationMissing("Укажите Supabase URL и anon key в режиме разработчика.")
        return runCatching {
            val session = backend.signIn(config, email.trim(), password)
            authStore.save(session)
            preferences.syncAccountEmail = session.email ?: email.trim()
            preferences.syncStatus = "idle"
            preferences.syncStatusDetail = ""
            SyncRunResult.Success(0, 0)
        }.getOrElse {
            syncUnavailable("Не удалось войти в аккаунт синхронизации.", it)
        }
    }

    suspend fun signOut(): SyncRunResult {
        return runCatching {
            val session = authStore.load()
            val config = requireConfig()
            runCatching {
                if (session != null && config != null) backend.signOut(session, config)
            }
            authStore.clear()
            preferences.syncAccountEmail = null
            preferences.syncStatus = "signed_out"
            preferences.syncStatusDetail = ""
            preferences.isSyncEnabled = false
            SyncRunResult.Success(0, 0)
        }.getOrElse {
            syncUnavailable("Не удалось отключить синхронизацию на этом устройстве.", it)
        }
    }

    suspend fun runSync(manual: Boolean, allowAfterReview: Boolean = false): SyncRunResult {
        if (!preferences.isSyncEnabled && !manual) {
            return SyncRunResult.Skipped("disabled")
        }
        if (preferences.isSyncReviewRequired && !allowAfterReview) {
            return SyncRunResult.Skipped("review_required")
        }

        val config = requireConfig()
            ?: return configurationMissing("Supabase URL / anon key не настроены.")
        var session = authStore.load()
            ?: return SyncRunResult.Failure("Сначала войдите в аккаунт синхронизации.", "No session")

        return runCatching {
            preferences.syncStatus = "syncing"
            preferences.syncStatusDetail = ""

            if (session.expiresAtEpochSeconds in 1 until (System.currentTimeMillis() / 1000L + 60L)) {
                session = backend.refreshSession(config, session)
                authStore.save(session)
            }

            val pending = modelFactory.syncQueueRepository.findPending()
            val pushed = backend.pushChanges(config, session, deviceIdProvider.value(), pending)
            pushed.pushedQueueIds.forEach { queueId ->
                modelFactory.syncQueueRepository.markPushed(queueId, System.currentTimeMillis())
            }

            val pull = backend.pullChanges(config, session, preferences.syncLastLogId)
            applyRemoteEvents(pull.events.filter { it.deviceId != deviceIdProvider.value() })
            preferences.syncLastLogId = pull.latestLogId
            preferences.syncLastSuccessAt = System.currentTimeMillis()
            preferences.syncStatus = "success"
            preferences.syncStatusDetail = ""
            SyncRunResult.Success(pushed = pushed.pushedQueueIds.size, pulled = pull.events.count { it.deviceId != deviceIdProvider.value() })
        }.getOrElse {
            syncUnavailable("Синхронизация сейчас недоступна.", it)
        }
    }

    fun markRestoreNeedsReview(reason: String) {
        preferences.isSyncReviewRequired = true
        preferences.syncReviewReason = reason
        preferences.syncStatus = "review_required"
        preferences.syncStatusDetail = reason
        preferences.syncLastLogId = 0L
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

    private fun applyRemoteEvents(events: List<RemoteSyncEvent>) {
        if (events.isEmpty()) return
        modelFactory.syncManager.withCapturePaused {
            events.forEach { event ->
                when (event.entityType) {
                    "habit_block" -> applyBlock(event)
                    "habit" -> applyHabit(event)
                    "habit_goal" -> applyGoal(event)
                    "entry" -> applyEntry(event)
                    "entry_op" -> applyEntryOp(event)
                    "app_setting" -> applyAppSetting(event)
                }
            }
        }

        habitList.reloadAndNotify()
    }

    private fun applyBlock(event: RemoteSyncEvent) {
        val payload = JSONObject(event.payloadJson)
        val existing = modelFactory.habitBlockRepository.findByUuid(event.entityUuid)
        if (!shouldApply(existing?.updatedAt ?: 0L, existing?.deletedAt, event)) return
        val record = HabitBlockData(
            id = existing?.id,
            name = payload.optString("name"),
            color = payload.optInt("color"),
            icon = existing?.icon,
            position = payload.optInt("position", existing?.position ?: 0),
            isArchived = payload.optString("is_archived", "false").toBoolean(),
            uuid = event.entityUuid,
            updatedAt = event.createdAt,
            deletedAt = if (event.operationType == "delete") event.createdAt else null
        )
        if (existing == null) modelFactory.habitBlockRepository.insert(record)
        else modelFactory.habitBlockRepository.update(record)
    }

    private fun applyHabit(event: RemoteSyncEvent) {
        val payload = JSONObject(event.payloadJson)
        val existing = modelFactory.habitRepository.findByUuid(event.entityUuid)
        if (!shouldApply(existing?.updatedAt ?: 0L, existing?.deletedAt, event)) return

        val blockUuid = payload.optString("block_uuid").ifBlank { null }
        val blockId = blockUuid?.let { modelFactory.habitBlockRepository.findByUuid(it)?.id }
        val position = existing?.position ?: modelFactory.habitRepository.findAll().size
        val updated = HabitData(
            id = existing?.id,
            name = payload.optString("name"),
            description = payload.optString("description"),
            question = payload.optString("question"),
            freqNum = payload.optString("freq_num", "1").toInt(),
            freqDen = payload.optString("freq_den", "1").toInt(),
            color = payload.optString("color", "0").toInt(),
            position = position,
            reminderHour = payload.optString("reminder_hour").ifBlank { null }?.toInt(),
            reminderMin = payload.optString("reminder_min").ifBlank { null }?.toInt(),
            reminderDays = payload.optString("reminder_days", "0").toInt(),
            highlight = existing?.highlight ?: 0,
            archived = if (payload.optString("archived", "false").toBoolean()) 1 else 0,
            type = payload.optString("type", "0").toInt(),
            targetValue = payload.optString("target_value", "0").toDouble(),
            targetType = payload.optString("target_type", "0").toInt(),
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

        modelFactory.habitExtensionRepository.upsert(
            HabitExtensionData(
                habitId = habitId,
                dayTier = payload.optString("day_tier", "NORMAL"),
                timerEnabled = payload.optString("timer_enabled", "false").toBoolean(),
                blockId = blockId,
                statsStartTimestamp = payload.optString("statistics_start").ifBlank { null }?.toLong()
            )
        )
    }

    private fun applyGoal(event: RemoteSyncEvent) {
        val payload = JSONObject(event.payloadJson)
        val habit = modelFactory.habitRepository.findByUuid(payload.getString("habit_uuid")) ?: return
        val existing = modelFactory.habitGoalRepository.findByUuid(event.entityUuid)
        if (!shouldApply(existing?.updatedAt ?: 0L, existing?.deletedAt, event)) return
        modelFactory.habitGoalRepository.upsert(
            HabitGoalData(
                id = existing?.id,
                habitId = habit.id!!,
                uuid = event.entityUuid,
                effectiveTimestamp = payload.getString("effective_timestamp").toLong(),
                freqNum = payload.getString("freq_num").toInt(),
                freqDen = payload.getString("freq_den").toInt(),
                targetType = payload.getString("target_type").toInt(),
                targetValue = payload.getString("target_value").toDouble(),
                unit = payload.optString("unit"),
                updatedAt = event.createdAt,
                deletedAt = if (event.operationType == "delete") event.createdAt else null
            )
        )
    }

    private fun applyEntry(event: RemoteSyncEvent) {
        val payload = JSONObject(event.payloadJson)
        val habit = modelFactory.habitRepository.findByUuid(payload.getString("habit_uuid")) ?: return
        val existing = modelFactory.entryRepository.findByUuid(event.entityUuid)
        if (!shouldApply(existing?.updatedAt ?: 0L, existing?.deletedAt, event)) return
        modelFactory.entryRepository.upsert(
            EntryData(
                id = existing?.id,
                habitId = habit.id!!,
                uuid = event.entityUuid,
                timestamp = payload.getString("entry_date").toLong(),
                value = if (event.operationType == "delete") Entry.UNKNOWN else payload.optString("value", Entry.UNKNOWN.toString()).toInt(),
                notes = payload.optString("notes"),
                updatedAt = event.createdAt,
                deletedAt = if (event.operationType == "delete") event.createdAt else null
            )
        )
    }

    private fun applyEntryOp(event: RemoteSyncEvent) {
        if (modelFactory.entryOpRepository.findByOpUuid(event.entityUuid) != null) return
        val payload = JSONObject(event.payloadJson)
        val habitUuid = payload.getString("habit_uuid")
        val habit = modelFactory.habitRepository.findByUuid(habitUuid) ?: return
        val timestamp = payload.getString("entry_date").toLong()
        modelFactory.entryOpRepository.insert(
            org.isoron.uhabits.core.sync.EntryOpRecord(
                opUuid = event.entityUuid,
                habitUuid = habitUuid,
                entryTimestamp = timestamp,
                deltaValue = payload.getString("delta_value").toInt(),
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
    }

    private fun applyAppSetting(event: RemoteSyncEvent) {
        val payload = JSONObject(event.payloadJson)
        if (payload.optString("key") != GLOBAL_STATS_START_KEY) return
        val value = payload.optString("long_value").ifBlank { null }?.toLong()
        modelFactory.appSettingRepository.putLong(GLOBAL_STATS_START_KEY, value)
    }

    private fun shouldApply(localUpdatedAt: Long, localDeletedAt: Long?, event: RemoteSyncEvent): Boolean {
        if (event.operationType != "delete" && localDeletedAt != null && localDeletedAt > event.createdAt) {
            return false
        }
        return event.createdAt >= localUpdatedAt
    }

    companion object {
        private const val GLOBAL_STATS_START_KEY = "global_stats_start_timestamp"
    }
}

fun interface DeviceIdProvider {
    fun value(): String
}
