package org.isoron.uhabits.core.production.preflight

enum class PreflightIssueCategory {
    UnsupportedDatabaseVersion, IntegrityCheckFailed, ForeignKeyCheckFailed,
    MissingRequiredLegacyTable, UnexpectedNextTable, NonEmptyUnexpectedNextTable,
    UnexpectedSchemaObject, DuplicatePortableUuid, BlankPortableUuid, DanglingBlockReference,
    ActiveHabitInDeletedBlock, OrphanHabitExtension, OrphanHabitGoal, OrphanRepetition,
    OrphanEntryOp, MalformedLegacyRow, LegacyTimerActive, RecoveryBundleUnavailable,
    RecoveryBundleMismatch, InspectionFailed,
}

data class PreflightIssue(val category: PreflightIssueCategory, val table: String? = null, val detail: String = "")
enum class TimerMigrationReadiness { READY, BLOCKED_ACTIVE_WORK, BLOCKED_PENDING_COMPLETION, INCONSISTENT_STATE }

data class PhysicalSchemaObject(val type: String, val name: String, val table: String, val createSql: String?)
data class ProductionSourceFingerprint(
    val userVersion: Int,
    val databaseSha256: String,
    val semanticSha256: String,
    val schemaSha256: String,
    val rowCounts: Map<String, Long>,
    val identityMarkers: Map<String, String>,
)

/** Full migration readiness additionally requires independently verified recovery evidence. */
data class ProductionPreflightReport(
    val databasePath: String,
    val fingerprint: ProductionSourceFingerprint,
    val schemaInventory: List<PhysicalSchemaObject>,
    val columns: Map<String, List<String>>,
    val integrityStatus: List<String>,
    val foreignKeyStatus: List<String>,
    val foreignKeysEnabled: Boolean,
    val journalMode: String,
    val timerReadiness: TimerMigrationReadiness,
    val blockingIssues: List<PreflightIssue>,
    val warnings: List<String>,
) {
    val migrationEligible: Boolean get() = blockingIssues.isEmpty()
    val sourceEligible: Boolean get() = blockingIssues.none { it.category != PreflightIssueCategory.RecoveryBundleUnavailable && it.category != PreflightIssueCategory.RecoveryBundleMismatch }
    val userVersion: Int get() = fingerprint.userVersion
}

/** Pure classification of the actual legacy preference keys; never resets or completes a timer. */
object LegacyTimerReadinessClassifier {
    fun classify(preferences: Map<String, Any?>): TimerMigrationReadiness {
        fun long(key: String, default: Long) = (preferences[key] as? Number)?.toLong() ?: default
        fun bool(key: String) = preferences[key] == true
        val keys = setOf("pref_timer_habit_id", "pref_timer_is_running", "pref_timer_accumulated_elapsed_millis",
            "pref_timer_started_at_wall_clock_millis", "pref_timer_mode", "pref_timer_phase",
            "pref_timer_completion_triggered", "pref_timer_completion_display_active",
            "pref_timer_focus_duration_millis", "pref_timer_break_duration_millis",
            "pref_timer_alarm_revision", "pref_timer_completion_event_id")
        if (keys.any { key -> preferences.containsKey(key) && when (key) {
                "pref_timer_mode", "pref_timer_phase" -> preferences[key] !is String
                "pref_timer_is_running", "pref_timer_completion_triggered", "pref_timer_completion_display_active" -> preferences[key] !is Boolean
                else -> preferences[key] !is Long && preferences[key] !is Int
            } }) return TimerMigrationReadiness.INCONSISTENT_STATE
        val id = long("pref_timer_habit_id", -1)
        val elapsed = long("pref_timer_accumulated_elapsed_millis", 0)
        val started = long("pref_timer_started_at_wall_clock_millis", 0)
        val running = bool("pref_timer_is_running")
        val mode = preferences["pref_timer_mode"] ?: "STOPWATCH"
        val phase = preferences["pref_timer_phase"] ?: "FOCUS"
        if (long("pref_timer_alarm_revision", 0) < 0 || long("pref_timer_completion_event_id", 0) < 0)
            return TimerMigrationReadiness.INCONSISTENT_STATE
        if (long("pref_timer_focus_duration_millis", 1500000) <= 0 || long("pref_timer_break_duration_millis", 300000) <= 0)
            return TimerMigrationReadiness.INCONSISTENT_STATE
        if (elapsed < 0 || started < 0 || mode !in listOf("STOPWATCH", "POMODORO") || phase !in listOf("FOCUS", "BREAK"))
            return TimerMigrationReadiness.INCONSISTENT_STATE
        if ((running && (id < 0 || started == 0L)) || (!running && started != 0L) || (id < 0 && (elapsed > 0 || phase == "BREAK")) || (mode == "STOPWATCH" && phase == "BREAK")) return TimerMigrationReadiness.INCONSISTENT_STATE
        if (bool("pref_timer_completion_triggered") || bool("pref_timer_completion_display_active")) return TimerMigrationReadiness.BLOCKED_PENDING_COMPLETION
        if (running || elapsed > 0 || phase == "BREAK") return TimerMigrationReadiness.BLOCKED_ACTIVE_WORK
        return TimerMigrationReadiness.READY
    }
}
