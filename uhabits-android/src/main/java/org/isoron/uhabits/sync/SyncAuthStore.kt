package org.isoron.uhabits.sync

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

open class SyncAuthStore(context: Context) {
    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    open fun save(session: SyncSession) {
        prefs.edit()
            .putString(KEY_ACCESS_TOKEN, session.accessToken)
            .putString(KEY_REFRESH_TOKEN, session.refreshToken)
            .putString(KEY_USER_ID, session.userId)
            .putString(KEY_EMAIL, session.email)
            .putLong(KEY_EXPIRES_AT, session.expiresAtEpochSeconds)
            .apply()
    }

    open fun load(): SyncSession? {
        val accessToken = prefs.getString(KEY_ACCESS_TOKEN, null) ?: return null
        val refreshToken = prefs.getString(KEY_REFRESH_TOKEN, null) ?: return null
        val userId = prefs.getString(KEY_USER_ID, null) ?: return null
        return SyncSession(
            accessToken = accessToken,
            refreshToken = refreshToken,
            userId = userId,
            email = prefs.getString(KEY_EMAIL, null),
            expiresAtEpochSeconds = prefs.getLong(KEY_EXPIRES_AT, 0L)
        )
    }

    open fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val FILE_NAME = "sync_auth"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_EMAIL = "email"
        private const val KEY_EXPIRES_AT = "expires_at"
    }
}
