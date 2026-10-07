package org.isoron.uhabits.production

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.preference.PreferenceManager
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.core.DATABASE_FILENAME
import org.isoron.uhabits.core.production.preflight.LegacyTimerReadinessClassifier
import org.isoron.uhabits.core.production.preflight.PreflightIssue
import org.isoron.uhabits.core.production.preflight.PreflightIssueCategory
import org.isoron.uhabits.core.production.preflight.ProductionPreflightReport
import org.isoron.uhabits.core.production.preflight.ProductionPreflightService
import org.isoron.uhabits.core.production.recovery.LegacySnapshotLease
import org.isoron.uhabits.core.production.recovery.ProductionRecoveryGate
import org.isoron.uhabits.core.production.recovery.RecoveryInspection
import org.isoron.uhabits.core.production.recovery.RecoveryPreferenceKey
import org.isoron.uhabits.core.production.recovery.RecoveryPreferences
import org.isoron.uhabits.core.production.recovery.RecoveryPreparationResult
import org.isoron.uhabits.database.AndroidDatabase
import java.io.File
import java.io.IOException

/** Internal PR21 API. No migration action or authority selector is exposed. */
object ProductionPreparationStartup {
    private const val REQUEST_PREFS = "production_preparation_request"
    private const val REQUESTED = "requested"
    var lastReport: ProductionPreflightReport? = null
        private set

    /** The caller must restart the process. This never attempts an unsafe live snapshot. */
    fun requestAtNextColdStart(context: Context): Boolean {
        if (context.getSharedPreferences("dataset_session_prefs", 0)
                .getString("dataset_selected_mode", "LEGACY_PRODUCTION") != "LEGACY_PRODUCTION") return false
        // Await pending preference persistence before the explicit process restart.
        if (!PreferenceManager.getDefaultSharedPreferences(context).edit().commit()) return false
        return context.getSharedPreferences(REQUEST_PREFS, 0).edit().putBoolean(REQUESTED, true).commit()
    }

    /** Called first in Application.onCreate, before session resolution or SQLiteOpenHelper/DI. */
    fun runBeforeDependencyInjection(context: Context): RecoveryPreparationResult? {
        val requestPrefs = context.getSharedPreferences(REQUEST_PREFS, 0)
        val root = File(context.noBackupFilesDir, "production-recovery")
        val requested = requestPrefs.getBoolean(REQUESTED, false)
        if (!requested && !File(root, "preparation.state").exists()) return null
        check(!HabitsApplication.isComponentInitialized) { "Production preparation requires a cold process" }
        val database = context.getDatabasePath(DATABASE_FILENAME)
        val sessionPrefs = context.getSharedPreferences("dataset_session_prefs", 0)
        val selectedMode = sessionPrefs.getString("dataset_selected_mode", "LEGACY_PRODUCTION") ?: "INVALID"
        if (selectedMode != "LEGACY_PRODUCTION") {
            // Invalidate approval, but never switch the selected experimental authority.
            val gate = ProductionRecoveryGate(root, { RecoveryInspection(-1, "", false) })
            val result = gate.recover(database, capturePreferences(PreferenceManager.getDefaultSharedPreferences(context).all, selectedMode))
            if (requested) check(requestPrefs.edit().putBoolean(REQUESTED, false).commit())
            // Do not delegate a contradictory authority marker to the ordinary resolver's
            // fallback-to-production behavior while recovery metadata is being reconciled.
            throw IOException("Production preparation authority mismatch: $selectedMode (${result.blocker})")
        }
        // Missing canonical data must never fall through to SQLiteOpenHelper.onCreate.
        if (!database.isFile || database.length() == 0L) throw IOException("Production preparation: canonical legacy database is missing; automatic creation is blocked")
        val runner = ProductionPreparationRunner(database, root, {
            capturePreferences(PreferenceManager.getDefaultSharedPreferences(context).all, selectedMode)
        }, { check(!HabitsApplication.isComponentInitialized) })
        val result = if (requested) runner.prepare() else runner.recover()
        lastReport = runner.report
        // The ordinary opener may upgrade old versions or replace unsupported databases.
        // An explicitly inspected source must never fall through to either behavior.
        requireSafeLegacyStartup(runner.report, result)
        // A failed request remains resumable in the gate's durable state. Clear only the request;
        // it is never a safety approval, and legacy authority is never switched here.
        if (requested) check(requestPrefs.edit().putBoolean(REQUESTED, false).commit())
        return result
    }

    internal fun requireSafeLegacyStartup(inspected: ProductionPreflightReport?, result: RecoveryPreparationResult) {
        if (inspected == null || inspected.userVersion != 29 || inspected.blockingIssues.any {
                it.category == PreflightIssueCategory.IntegrityCheckFailed ||
                    it.category == PreflightIssueCategory.InspectionFailed
            }) throw IOException("Production preparation cannot safely open the inspected legacy source: ${result.blocker}")
    }

    internal fun capturePreferences(raw: Map<String, *>, mode: String = "LEGACY_PRODUCTION"): RecoveryPreferences {
        val defaults = mapOf(
            "sync_device_id" to "",
            "pref_timer_habit_id" to "-1", "pref_timer_mode" to "STOPWATCH", "pref_timer_phase" to "FOCUS",
            "pref_timer_is_running" to "false", "pref_timer_accumulated_elapsed_millis" to "0",
            "pref_timer_started_at_wall_clock_millis" to "0", "pref_timer_focus_duration_millis" to "1500000",
            "pref_timer_break_duration_millis" to "300000", "pref_timer_completion_triggered" to "false",
            "pref_timer_completion_display_active" to "false", "pref_timer_alarm_revision" to "0",
            "pref_timer_completion_event_id" to "0",
            "pref_sync_enabled" to "false", "pref_sync_last_log_id" to "0", "pref_sync_review_required" to "false",
            "pref_sync_bootstrap_queued" to "false", "pref_sync_bootstrap_done" to "false",
            "pref_midnight_delay" to "true", "pref_first_weekday" to "-1",
            "pref_pomodoro_auto_switch" to "true", "pref_pomodoro_default_focus_minutes" to "25",
            "pref_pomodoro_default_break_minutes" to "5",
        )
        return RecoveryPreferences(datasetMode = mode, databaseName = DATABASE_FILENAME,
            timerState = LegacyTimerReadinessClassifier.classify(raw).name,
            values = RecoveryPreferenceKey.values().associateWith { key ->
                raw[key.storageKey]?.toString() ?: defaults.getValue(key.storageKey)
            })
    }
}

/** Caller-owned closed-source lease. Production entry is only the pre-DI cold-start seam above. */
internal class ProductionPreparationRunner(
    private val database: File,
    bundleRoot: File,
    private val readPreferences: () -> RecoveryPreferences,
    private val assertQuiescent: () -> Unit,
) {
    var report: ProductionPreflightReport? = null
        private set
    private val gate = ProductionRecoveryGate(bundleRoot, ::inspect)

    fun prepare(): RecoveryPreparationResult {
        assertQuiescent()
        if (database.isFile && database.length() > 0) runCatching { inspect(database) }
        val result = gate.prepare { object : LegacySnapshotLease {
            override val source: File = database
            override fun capturePreferences(): RecoveryPreferences { assertQuiescent(); return readPreferences() }
            override fun close() = Unit
        } }
        completeReport(result)
        return result
    }

    fun recover(): RecoveryPreparationResult {
        assertQuiescent()
        // State/bundle rejection may return without inspecting the source. Independently verify
        // that legacy startup is safe, even when migration readiness must stay blocked.
        if (database.isFile && database.length() > 0) runCatching { inspect(database) }
        val result = gate.recover(database, readPreferences())
        completeReport(result)
        return result
    }

    private fun completeReport(result: RecoveryPreparationResult) {
        val blocker = result.blocker
        if (result.migrationEligible) report = report?.copy(blockingIssues = report!!.blockingIssues.filterNot {
            it.category == PreflightIssueCategory.RecoveryBundleUnavailable
        }) else if (blocker != null) report = report?.copy(blockingIssues = report!!.blockingIssues +
            PreflightIssue(PreflightIssueCategory.RecoveryBundleMismatch, detail = blocker.name))
    }

    private fun inspect(file: File): RecoveryInspection {
        assertQuiescent()
        // Reject WAL before SQLite can create a shared-memory sidecar even in read-only mode.
        val header = ByteArray(20)
        val headerSize = file.inputStream().use { it.read(header) }
        if (headerSize >= 20 && (header[18].toInt() == 2 || header[19].toInt() == 2) ||
            listOf("-wal", "-journal").any { File(file.path + it).let { f -> f.exists() && f.length() > 0 } })
            throw IOException("WAL/hot journal source requires separate resolution; no SQLite open or checkpoint performed")
        val prefs = readPreferences()
        val before = ProductionRecoveryGate.fileSha256(file)
        val raw = prefs.values.entries.associate { (key, value) -> key.storageKey to when (key) {
            RecoveryPreferenceKey.TIMER_IS_RUNNING, RecoveryPreferenceKey.TIMER_COMPLETION_TRIGGERED,
            RecoveryPreferenceKey.TIMER_COMPLETION_DISPLAY_ACTIVE -> value.toBooleanStrictOrNull()
            RecoveryPreferenceKey.TIMER_MODE, RecoveryPreferenceKey.TIMER_PHASE -> value
            else -> value.toLongOrNull() ?: value
        } }
        var inspection = SQLiteDatabase.openDatabase(file.path, null,
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { sqlite ->
            ProductionPreflightService.inspect(AndroidDatabase(sqlite), file.path, before,
                LegacyTimerReadinessClassifier.classify(raw), mapOf("datasetMode" to prefs.datasetMode,
                    "syncDeviceId" to prefs.values.getValue(RecoveryPreferenceKey.SYNC_DEVICE_ID)))
        }
        val stable = before == ProductionRecoveryGate.fileSha256(file)
        val sidecar = listOf("-wal", "-journal").any { File(file.path + it).let { f -> f.exists() && f.length() > 0 } }
        // A WAL mode DB is not silently checkpointed or reduced to a main-file fingerprint.
        val safeJournal = inspection.journalMode.lowercase() in setOf("delete", "truncate", "persist") && !sidecar
        if (!safeJournal || !stable) inspection = inspection.copy(blockingIssues = inspection.blockingIssues +
            PreflightIssue(PreflightIssueCategory.InspectionFailed, detail = if (!stable) "source changed during inspection" else "journal/WAL requires separate resolution; no checkpoint performed"))
        if (file == database) report = inspection
        return RecoveryInspection(inspection.userVersion, inspection.fingerprint.semanticSha256,
            inspection.sourceEligible && stable && safeJournal,
            inspection.fingerprint.schemaSha256, inspection.fingerprint.rowCounts,
            inspection.fingerprint.identityMarkers)
    }
}
