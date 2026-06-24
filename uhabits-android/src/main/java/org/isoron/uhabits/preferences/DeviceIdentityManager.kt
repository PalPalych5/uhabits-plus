package org.isoron.uhabits.preferences

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import me.tatarka.inject.annotations.Inject
import org.isoron.uhabits.core.AppScope
import org.isoron.uhabits.inject.AppContext
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
@Inject
@AppScope
class DeviceIdentityManager(
    @AppContext context: Context
) {
    private val sharedPreferences: SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(context)

    val deviceId: String
        get() {
            val existing = sharedPreferences.getString(KEY_DEVICE_ID, null)
            if (!existing.isNullOrBlank()) return existing

            // Keep device_id outside the synced/restored SQLite DB. A DB restore is intentionally
            // destructive local replacement, and storing device identity inside that DB would clone
            // the same writer identity onto another device after backup/restore.
            val generated = Uuid.random().toHexString()
            sharedPreferences.edit().putString(KEY_DEVICE_ID, generated).apply()
            return generated
        }

    companion object {
        private const val KEY_DEVICE_ID = "sync_device_id"
    }
}
