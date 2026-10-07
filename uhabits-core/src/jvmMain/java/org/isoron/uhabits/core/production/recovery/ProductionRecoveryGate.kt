package org.isoron.uhabits.core.production.recovery

import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/** Result of an independently opened, READ ONLY database inspection. No opener is owned here. */
data class RecoveryInspection(
    val userVersion: Int,
    val semanticHash: String,
    val eligible: Boolean,
    val schemaHash: String = "",
    val rowCounts: Map<String, Long> = emptyMap(),
    val identityMarkers: Map<String, String> = emptyMap(),
)

/** Deliberate allowlist, rather than copying arbitrary SharedPreferences (including credentials). */
enum class RecoveryPreferenceKey(val storageKey: String) {
    SYNC_DEVICE_ID("sync_device_id"),
    SYNC_ENABLED("pref_sync_enabled"),
    SYNC_LAST_LOG_ID("pref_sync_last_log_id"),
    SYNC_REVIEW_REQUIRED("pref_sync_review_required"),
    SYNC_BOOTSTRAP_QUEUED("pref_sync_bootstrap_queued"),
    SYNC_BOOTSTRAP_DONE("pref_sync_bootstrap_done"),
    MIDNIGHT_DELAY("pref_midnight_delay"),
    FIRST_WEEKDAY("pref_first_weekday"),
    POMODORO_AUTO_SWITCH("pref_pomodoro_auto_switch"),
    POMODORO_DEFAULT_FOCUS("pref_pomodoro_default_focus_minutes"),
    POMODORO_DEFAULT_BREAK("pref_pomodoro_default_break_minutes"),
    TIMER_HABIT_ID("pref_timer_habit_id"),
    TIMER_MODE("pref_timer_mode"),
    TIMER_PHASE("pref_timer_phase"),
    TIMER_IS_RUNNING("pref_timer_is_running"),
    TIMER_ACCUMULATED_ELAPSED_MILLIS("pref_timer_accumulated_elapsed_millis"),
    TIMER_STARTED_AT_WALL_CLOCK_MILLIS("pref_timer_started_at_wall_clock_millis"),
    TIMER_FOCUS_DURATION_MILLIS("pref_timer_focus_duration_millis"),
    TIMER_BREAK_DURATION_MILLIS("pref_timer_break_duration_millis"),
    TIMER_COMPLETION_TRIGGERED("pref_timer_completion_triggered"),
    TIMER_COMPLETION_DISPLAY_ACTIVE("pref_timer_completion_display_active"),
    TIMER_ALARM_REVISION("pref_timer_alarm_revision"),
    TIMER_COMPLETION_EVENT_ID("pref_timer_completion_event_id"),
}

data class RecoveryPreferences(
    val datasetMode: String = "LEGACY_PRODUCTION",
    val databaseName: String = "uhabits.db",
    val timerState: String = "READY",
    val values: Map<RecoveryPreferenceKey, String>,
) {
    internal fun fields(): Map<String, String> = mapOf(
        "datasetMode" to datasetMode, "databaseName" to databaseName, "timerState" to timerState,
    ) + values.mapKeys { "pref.${it.key.name}" }

    internal fun isReady(): Boolean = datasetMode == "LEGACY_PRODUCTION" &&
        databaseName == "uhabits.db" && timerState == "READY" &&
        values.keys.containsAll(requiredKeys) && validValueTypes() &&
        values[RecoveryPreferenceKey.TIMER_IS_RUNNING] == "false" &&
        values[RecoveryPreferenceKey.TIMER_ACCUMULATED_ELAPSED_MILLIS] == "0" &&
        values[RecoveryPreferenceKey.TIMER_COMPLETION_DISPLAY_ACTIVE] == "false" &&
        values[RecoveryPreferenceKey.TIMER_COMPLETION_TRIGGERED] == "false" &&
        values[RecoveryPreferenceKey.TIMER_PHASE] == "FOCUS"

    private fun validValueTypes(): Boolean {
        val booleans = setOf(RecoveryPreferenceKey.SYNC_ENABLED, RecoveryPreferenceKey.SYNC_REVIEW_REQUIRED,
            RecoveryPreferenceKey.SYNC_BOOTSTRAP_QUEUED, RecoveryPreferenceKey.SYNC_BOOTSTRAP_DONE,
            RecoveryPreferenceKey.MIDNIGHT_DELAY, RecoveryPreferenceKey.POMODORO_AUTO_SWITCH,
            RecoveryPreferenceKey.TIMER_IS_RUNNING, RecoveryPreferenceKey.TIMER_COMPLETION_TRIGGERED,
            RecoveryPreferenceKey.TIMER_COMPLETION_DISPLAY_ACTIVE)
        if (booleans.any { values[it] !in setOf("true", "false") }) return false
        if (values[RecoveryPreferenceKey.TIMER_MODE] !in setOf("STOPWATCH", "POMODORO")) return false
        val deviceId = values[RecoveryPreferenceKey.SYNC_DEVICE_ID] ?: return false
        // Absence on a never-initialized legacy install is preserved without generating identity.
        if (deviceId.isNotEmpty() && !deviceId.matches(Regex("[0-9a-fA-F]{32}|[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))) return false
        val weekday = values[RecoveryPreferenceKey.FIRST_WEEKDAY]?.toIntOrNull() ?: return false
        if (weekday != -1 && weekday !in 1..7) return false
        val positive = setOf(RecoveryPreferenceKey.POMODORO_DEFAULT_FOCUS, RecoveryPreferenceKey.POMODORO_DEFAULT_BREAK,
            RecoveryPreferenceKey.TIMER_FOCUS_DURATION_MILLIS, RecoveryPreferenceKey.TIMER_BREAK_DURATION_MILLIS)
        if (positive.any { (values[it]?.toLongOrNull() ?: -1) <= 0 }) return false
        val otherNumbers = requiredKeys - booleans - positive - setOf(RecoveryPreferenceKey.TIMER_MODE,
            RecoveryPreferenceKey.TIMER_PHASE, RecoveryPreferenceKey.FIRST_WEEKDAY, RecoveryPreferenceKey.SYNC_DEVICE_ID)
        return otherNumbers.all { key ->
            val minimum = if (key == RecoveryPreferenceKey.TIMER_HABIT_ID || key == RecoveryPreferenceKey.SYNC_LAST_LOG_ID) -1 else 0
            (values[key]?.toLongOrNull() ?: Long.MIN_VALUE) >= minimum
        }
    }

    companion object {
        val requiredTimerKeys: Set<RecoveryPreferenceKey> = RecoveryPreferenceKey.values()
            .filter { it.name.startsWith("TIMER_") }.toSet()
        val requiredKeys: Set<RecoveryPreferenceKey> = RecoveryPreferenceKey.values().toSet()
    }
}

/**
 * Acquired only before ordinary DI in a fresh process. The caller owns exclusion of every DB,
 * timer and sync writer, closes all DB handles, and keeps that exclusion until close(). A live
 * UI lock alone cannot satisfy this contract. capturePreferences must read that same authority.
 */
interface LegacySnapshotLease : AutoCloseable {
    val source: File
    fun capturePreferences(): RecoveryPreferences
}

enum class RecoveryPreparationState {
    LEGACY_STABLE, PREFLIGHT_PASSED, RECOVERY_BUNDLE_CREATING,
    RECOVERY_BUNDLE_VERIFIED, MIGRATION_READY, RECOVERY_REQUIRED,
}

enum class RecoveryFailurePoint {
    BEFORE_SNAPSHOT, DURING_SNAPSHOT, AFTER_COPY_BEFORE_MANIFEST,
    DURING_PREFERENCE_CAPTURE, BEFORE_VERIFICATION,
    AFTER_VERIFICATION_BEFORE_READY, DURING_INCOMPLETE_CLEANUP,
}

enum class RecoveryBlocker {
    SOURCE_MISSING, SOURCE_INELIGIBLE, ACTIVE_SIDECAR, PREFERENCES_NOT_READY,
    SOURCE_CHANGED, BUNDLE_MISSING, BUNDLE_CORRUPT, PREFERENCES_CHANGED,
    STATE_CORRUPT, PREPARATION_INTERRUPTED, IO_FAILURE,
}

data class RecoverySourceFingerprint(
    val databaseSha256: String,
    val semanticHash: String,
    val schemaHash: String,
    val userVersion: Int,
    val rowCounts: Map<String, Long>,
    val identityMarkers: Map<String, String>,
)

data class RecoveryPreparationResult(
    val state: RecoveryPreparationState,
    val blocker: RecoveryBlocker? = null,
    val bundleId: String? = null,
    val fingerprint: RecoverySourceFingerprint? = null,
) {
    val migrationEligible: Boolean get() = state == RecoveryPreparationState.MIGRATION_READY && blocker == null
}

/**
 * PR21 preparation only: never writes, creates, restores or migrates a production database.
 * Atomic durable metadata refers solely to files below bundleRoot. Failed/corrupt/stale bundles
 * can never grant readiness. Legacy authority remains canonical in every state.
 */
class ProductionRecoveryGate(
    private val bundleRoot: File,
    private val inspect: (File) -> RecoveryInspection,
    private val failureInjector: (RecoveryFailurePoint) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val stateFile get() = File(bundleRoot, "preparation.state")

    @Synchronized
    fun prepare(acquireLease: () -> LegacySnapshotLease): RecoveryPreparationResult {
        var id: String? = null
        var fingerprint: RecoverySourceFingerprint? = null
        try {
            initializeRoot()
            acquireLease().use { lease ->
                val source = lease.source.canonicalFile
                if (!source.isFile || source.length() == 0L) return blocked(RecoveryBlocker.SOURCE_MISSING)
                if (hasActiveSidecar(source)) return blocked(RecoveryBlocker.ACTIVE_SIDECAR)
                val original = inspect(source)
                if (!original.eligible || original.userVersion != 29) return blocked(RecoveryBlocker.SOURCE_INELIGIBLE)
                val preferences = lease.capturePreferences()
                if (!preferences.isReady()) return blocked(RecoveryBlocker.PREFERENCES_NOT_READY)
                fingerprint = fingerprint(source, original)
                id = UUID.randomUUID().toString()
                val base = fingerprintFields(fingerprint!!) + mapOf("bundleId" to id!!, "sourcePath" to source.path)
                persistState(RecoveryPreparationState.PREFLIGHT_PASSED, base)
                val folder = bundleFolder(id!!)
                check(folder.mkdir()) { "Cannot create new recovery bundle" }
                persistState(RecoveryPreparationState.RECOVERY_BUNDLE_CREATING, base)
                failureInjector(RecoveryFailurePoint.BEFORE_SNAPSHOT)
                val snapshot = File(folder, "legacy-v29.db")
                FileOutputStream(snapshot).use { output ->
                    source.inputStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var first = true
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            if (first) {
                                first = false
                                failureInjector(RecoveryFailurePoint.DURING_SNAPSHOT)
                            }
                        }
                    }
                    output.fd.sync()
                }
                failureInjector(RecoveryFailurePoint.AFTER_COPY_BEFORE_MANIFEST)
                val captured = lease.capturePreferences()
                failureInjector(RecoveryFailurePoint.DURING_PREFERENCE_CAPTURE)
                if (captured != preferences) return blocked(RecoveryBlocker.PREFERENCES_CHANGED, id, fingerprint)
                val manifest = base + preferences.fields() + mapOf(
                    "format" to "production-recovery-v1", "createdAtMillis" to now().toString(),
                    "preferencesHash" to hash(encode(preferences.fields())),
                )
                writeChecked(File(folder, "manifest"), manifest)
                failureInjector(RecoveryFailurePoint.BEFORE_VERIFICATION)
                val problem = verifyBundle(id!!, fingerprint!!)
                if (problem != null) return blocked(problem, id, fingerprint)
                if (hasActiveSidecar(source) || fingerprint(source, inspect(source)) != fingerprint ||
                    lease.capturePreferences() != preferences) {
                    return blocked(RecoveryBlocker.SOURCE_CHANGED, id, fingerprint)
                }
                val readyFields = base + mapOf("preferencesHash" to manifest.getValue("preferencesHash"))
                persistState(RecoveryPreparationState.RECOVERY_BUNDLE_VERIFIED, readyFields)
                failureInjector(RecoveryFailurePoint.AFTER_VERIFICATION_BEFORE_READY)
                persistState(RecoveryPreparationState.MIGRATION_READY, readyFields)
                return RecoveryPreparationResult(RecoveryPreparationState.MIGRATION_READY, bundleId = id, fingerprint = fingerprint)
            }
        } catch (_: Exception) {
            // Preserve the last durable boundary. recover() classifies it after restart, including
            // a verified bundle whose readiness commit was interrupted. Never touch the source.
            return RecoveryPreparationResult(RecoveryPreparationState.RECOVERY_REQUIRED, RecoveryBlocker.IO_FAILURE, id, fingerprint)
        }
    }

    /** Must also run before normal DI under the same closed-source exclusion as preparation. */
    @Synchronized
    fun recover(source: File, currentPreferences: RecoveryPreferences): RecoveryPreparationResult {
        try {
            initializeRoot()
            if (!stateFile.exists()) return RecoveryPreparationResult(RecoveryPreparationState.LEGACY_STABLE)
            val fields = try { readChecked(stateFile) } catch (_: Exception) {
                return RecoveryPreparationResult(RecoveryPreparationState.RECOVERY_REQUIRED, RecoveryBlocker.STATE_CORRUPT)
            }
            val state = try { RecoveryPreparationState.valueOf(fields.getValue("state")) } catch (_: Exception) {
                return RecoveryPreparationResult(RecoveryPreparationState.RECOVERY_REQUIRED, RecoveryBlocker.STATE_CORRUPT)
            }
            if (state == RecoveryPreparationState.LEGACY_STABLE) return RecoveryPreparationResult(state)
            if (state == RecoveryPreparationState.RECOVERY_REQUIRED) {
                return try {
                    RecoveryPreparationResult(state, RecoveryBlocker.valueOf(fields.getValue("blocker")),
                        fields["bundleId"], if ("databaseSha256" in fields) parseFingerprint(fields) else null)
                } catch (_: Exception) {
                    RecoveryPreparationResult(RecoveryPreparationState.RECOVERY_REQUIRED, RecoveryBlocker.STATE_CORRUPT)
                }
            }
            val id = fields["bundleId"] ?: return blocked(RecoveryBlocker.STATE_CORRUPT)
            val approved = try {
                require(UUID.fromString(id).toString() == id)
                parseFingerprint(fields)
            } catch (_: Exception) {
                return RecoveryPreparationResult(RecoveryPreparationState.RECOVERY_REQUIRED, RecoveryBlocker.STATE_CORRUPT)
            }
            if (state == RecoveryPreparationState.PREFLIGHT_PASSED || state == RecoveryPreparationState.RECOVERY_BUNDLE_CREATING) {
                cleanupIncomplete(id)
                persistState(RecoveryPreparationState.LEGACY_STABLE, emptyMap())
                return RecoveryPreparationResult(RecoveryPreparationState.LEGACY_STABLE, RecoveryBlocker.PREPARATION_INTERRUPTED)
            }
            verifyBundle(id, approved)?.let { return blocked(it, id, approved) }
            if (!source.isFile || source.length() == 0L) return blocked(RecoveryBlocker.SOURCE_MISSING, id, approved)
            if (hasActiveSidecar(source)) return blocked(RecoveryBlocker.ACTIVE_SIDECAR, id, approved)
            val current = inspect(source)
            if (!current.eligible || source.canonicalPath != fields["sourcePath"] || fingerprint(source, current) != approved) {
                return blocked(RecoveryBlocker.SOURCE_CHANGED, id, approved)
            }
            if (!currentPreferences.isReady() || hash(encode(currentPreferences.fields())) != fields["preferencesHash"]) {
                return blocked(RecoveryBlocker.PREFERENCES_CHANGED, id, approved)
            }
            // A fully verified previous bundle may be reapproved after interruption, only after
            // checking current source and preferences again. No authority change follows it.
            persistState(RecoveryPreparationState.MIGRATION_READY, fields - "state" - "blocker")
            return RecoveryPreparationResult(RecoveryPreparationState.MIGRATION_READY, bundleId = id, fingerprint = approved)
        } catch (_: Exception) {
            return RecoveryPreparationResult(RecoveryPreparationState.RECOVERY_REQUIRED, RecoveryBlocker.IO_FAILURE)
        }
    }

    private fun verifyBundle(id: String, approved: RecoverySourceFingerprint): RecoveryBlocker? {
        val folder = bundleFolder(id)
        val snapshot = File(folder, "legacy-v29.db")
        val manifestFile = File(folder, "manifest")
        if (!snapshot.isFile || !manifestFile.isFile) return RecoveryBlocker.BUNDLE_MISSING
        return try {
            require(!Files.isSymbolicLink(snapshot.toPath()))
            val manifest = readChecked(manifestFile)
            if (manifest.keys.any { it !in manifestKeys(approved) } ||
                manifest["format"] != "production-recovery-v1" || manifest["bundleId"] != id ||
                manifest["createdAtMillis"]?.toLongOrNull()?.let { it >= 0 } != true ||
                manifest["sourcePath"].isNullOrBlank() ||
                parseFingerprint(manifest) != approved || hasActiveSidecar(snapshot)) return RecoveryBlocker.BUNDLE_CORRUPT
            val prefs = parsePreferences(manifest)
            if (!prefs.isReady() || hash(encode(prefs.fields())) != manifest["preferencesHash"]) return RecoveryBlocker.BUNDLE_CORRUPT
            val copied = inspect(snapshot) // independent read-only reopen, integrity + semantic inspection
            if (!copied.eligible || copied.userVersion != 29 || fingerprint(snapshot, copied) != approved) {
                RecoveryBlocker.BUNDLE_CORRUPT
            } else null
        } catch (_: Exception) { RecoveryBlocker.BUNDLE_CORRUPT }
    }

    private fun manifestKeys(fingerprint: RecoverySourceFingerprint): Set<String> =
        fingerprintFields(fingerprint).keys + RecoveryPreferenceKey.values().map { "pref.${it.name}" } +
            setOf("bundleId", "sourcePath", "format", "createdAtMillis", "preferencesHash", "datasetMode", "databaseName", "timerState")

    private fun parsePreferences(fields: Map<String, String>) = RecoveryPreferences(
        fields.getValue("datasetMode"), fields.getValue("databaseName"), fields.getValue("timerState"),
        RecoveryPreferenceKey.values().mapNotNull { key -> fields["pref.${key.name}"]?.let { key to it } }.toMap(),
    )

    private fun fingerprint(source: File, inspection: RecoveryInspection) = RecoverySourceFingerprint(
        fileSha256(source), inspection.semanticHash, inspection.schemaHash, inspection.userVersion,
        inspection.rowCounts.toMap(), inspection.identityMarkers.toMap(),
    )

    private fun fingerprintFields(value: RecoverySourceFingerprint): Map<String, String> = mapOf(
        "databaseSha256" to value.databaseSha256, "semanticHash" to value.semanticHash,
        "schemaHash" to value.schemaHash, "userVersion" to value.userVersion.toString(),
    ) + value.rowCounts.mapKeys { "count.${it.key}" }.mapValues { it.value.toString() } +
        value.identityMarkers.mapKeys { "identity.${it.key}" }

    private fun parseFingerprint(fields: Map<String, String>) = RecoverySourceFingerprint(
        fields.getValue("databaseSha256"), fields.getValue("semanticHash"), fields.getValue("schemaHash"),
        fields.getValue("userVersion").toInt(),
        fields.filterKeys { it.startsWith("count.") }.mapKeys { it.key.removePrefix("count.") }.mapValues { it.value.toLong() },
        fields.filterKeys { it.startsWith("identity.") }.mapKeys { it.key.removePrefix("identity.") },
    )

    private fun blocked(
        issue: RecoveryBlocker,
        id: String? = null,
        fingerprint: RecoverySourceFingerprint? = null,
    ): RecoveryPreparationResult {
        val fields = mapOf("blocker" to issue.name) + (id?.let { mapOf("bundleId" to it) } ?: emptyMap()) +
            (fingerprint?.let { fingerprintFields(it) } ?: emptyMap())
        persistState(RecoveryPreparationState.RECOVERY_REQUIRED, fields)
        return RecoveryPreparationResult(RecoveryPreparationState.RECOVERY_REQUIRED, issue, id, fingerprint)
    }

    private fun persistState(state: RecoveryPreparationState, fields: Map<String, String>) =
        writeChecked(stateFile, fields + ("state" to state.name))

    private fun initializeRoot() {
        require(!Files.isSymbolicLink(bundleRoot.toPath()))
        if (!bundleRoot.exists()) check(bundleRoot.mkdirs())
        check(bundleRoot.isDirectory)
    }

    private fun bundleFolder(id: String): File {
        require(UUID.fromString(id).toString() == id)
        val folder = File(bundleRoot, id)
        require(!Files.isSymbolicLink(folder.toPath()))
        require(folder.canonicalFile.parentFile == bundleRoot.canonicalFile)
        return folder
    }

    private fun cleanupIncomplete(id: String) {
        val folder = bundleFolder(id)
        if (!folder.exists()) return
        // Delete only this service's fixed filenames; unknown files are preserved and block retry.
        listOf("legacy-v29.db", "manifest", "manifest.tmp").forEachIndexed { index, name ->
            val file = File(folder, name)
            require(!Files.isSymbolicLink(file.toPath()))
            if (file.exists()) check(file.delete())
            if (index == 0) failureInjector(RecoveryFailurePoint.DURING_INCOMPLETE_CLEANUP)
        }
        check(folder.delete())
    }

    private fun hasActiveSidecar(source: File): Boolean = listOf("-wal", "-journal").any {
        val file = File(source.path + it)
        file.exists() && file.length() > 0L
    }

    private fun writeChecked(file: File, fields: Map<String, String>) {
        val payload = encode(fields)
        val bytes = (hash(payload) + "\n").toByteArray(Charsets.UTF_8) + payload
        val temporary = File(file.parentFile, file.name + ".tmp")
        require(!Files.isSymbolicLink(file.toPath()) && !Files.isSymbolicLink(temporary.toPath()))
        FileOutputStream(temporary).use { output -> output.write(bytes); output.fd.sync() }
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        // Directory fsync is unavailable on some Android/Windows filesystems. File fsync + atomic
        // rename guarantees process-death boundaries; unsupported directory fsync is not a claim
        // of power-loss durability. Any missing/truncated metadata blocks readiness on restart.
        try { FileChannel.open(file.parentFile.toPath(), StandardOpenOption.READ).use { it.force(true) } } catch (_: Exception) { }
    }

    private fun readChecked(file: File): Map<String, String> {
        require(!Files.isSymbolicLink(file.toPath()))
        val bytes = file.readBytes()
        val newline = bytes.indexOf('\n'.code.toByte())
        require(newline == 64)
        val payload = bytes.copyOfRange(newline + 1, bytes.size)
        require(String(bytes.copyOfRange(0, newline), Charsets.UTF_8) == hash(payload))
        val decoder = Base64.getDecoder()
        val fields = linkedMapOf<String, String>()
        String(payload, Charsets.UTF_8).split('\n').filter { it.isNotEmpty() }.forEach { line ->
            val pair = line.split(':', limit = 2)
            require(pair.size == 2)
            val key = String(decoder.decode(pair[0]), Charsets.UTF_8)
            val value = String(decoder.decode(pair[1]), Charsets.UTF_8)
            require(fields.put(key, value) == null)
        }
        require(encode(fields).contentEquals(payload))
        return fields
    }

    private fun encode(fields: Map<String, String>): ByteArray {
        val encoder = Base64.getEncoder()
        fun b64(value: String) = encoder.encodeToString(value.toByteArray(Charsets.UTF_8))
        return fields.toSortedMap().entries.joinToString("\n", postfix = "\n") { b64(it.key) + ":" + b64(it.value) }
            .toByteArray(Charsets.UTF_8)
    }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        fun fileSha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
    }
}
