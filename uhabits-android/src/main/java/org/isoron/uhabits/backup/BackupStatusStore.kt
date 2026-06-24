package org.isoron.uhabits.backup

import android.content.Context
import androidx.preference.PreferenceManager
import me.tatarka.inject.annotations.Inject
import org.isoron.uhabits.inject.AppContext

@Inject
class BackupStatusStore(
    @AppContext private val context: Context
) {
    private val prefs
        get() = PreferenceManager.getDefaultSharedPreferences(context)

    fun load(): BackupStatus = BackupStatus(
        lastSuccessAt = getLong(KEY_LAST_SUCCESS_AT),
        lastFailureAt = getLong(KEY_LAST_FAILURE_AT),
        lastBackupLocation = prefs.getString(KEY_LAST_LOCATION, null),
        lastBackupSizeBytes = getLong(KEY_LAST_SIZE_BYTES),
        lastBackupDatabaseVersion = if (prefs.contains(KEY_LAST_DB_VERSION)) {
            prefs.getInt(KEY_LAST_DB_VERSION, 0)
        } else null,
        lastFailureReason = prefs.getString(KEY_LAST_FAILURE_REASON, null)
    )

    fun recordSuccess(location: String, sizeBytes: Long, databaseVersion: Int, timestamp: Long) {
        prefs.edit()
            .putLong(KEY_LAST_SUCCESS_AT, timestamp)
            .putString(KEY_LAST_LOCATION, location)
            .putLong(KEY_LAST_SIZE_BYTES, sizeBytes)
            .putInt(KEY_LAST_DB_VERSION, databaseVersion)
            .remove(KEY_LAST_FAILURE_REASON)
            .apply()
    }

    fun recordFailure(reason: String, timestamp: Long) {
        prefs.edit()
            .putLong(KEY_LAST_FAILURE_AT, timestamp)
            .putString(KEY_LAST_FAILURE_REASON, reason)
            .apply()
    }

    private fun getLong(key: String): Long? =
        if (prefs.contains(key)) prefs.getLong(key, 0L) else null

    companion object {
        private const val KEY_LAST_SUCCESS_AT = "backup_status_last_success_at"
        private const val KEY_LAST_FAILURE_AT = "backup_status_last_failure_at"
        private const val KEY_LAST_LOCATION = "backup_status_last_location"
        private const val KEY_LAST_SIZE_BYTES = "backup_status_last_size_bytes"
        private const val KEY_LAST_DB_VERSION = "backup_status_last_db_version"
        private const val KEY_LAST_FAILURE_REASON = "backup_status_last_failure_reason"
    }
}
