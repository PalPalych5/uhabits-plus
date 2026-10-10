/*
 * Copyright (C) 2016-2026 Álinson Santos Xavier <git@axavier.org> and contributors
 *
 * This file is part of Loop Habit Tracker.
 *
 * Loop Habit Tracker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * Loop Habit Tracker is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.isoron.uhabits.backup

import android.content.SharedPreferences
import org.isoron.uhabits.core.models.DayTier
import org.isoron.uhabits.core.models.HabitList
import org.json.JSONObject

enum class PreferenceValueType {
    BOOLEAN,
    INT,
    STRING
}

data class PreferenceDefinition(
    val key: String,
    val type: PreferenceValueType,
    val validator: (Any) -> Boolean
)

data class PreferencesValidationResult(
    val isValid: Boolean,
    val errorMessage: String? = null,
    val preferenceCount: Int = 0
)

object BackupPreferencesAdapter {

    const val PREFERENCES_FORMAT = "uhabit-backup-preferences"
    const val CURRENT_PREFERENCES_FORMAT_VERSION = 1

    val ALLOWLIST: Map<String, PreferenceDefinition> = listOf(
        // Appearance & UI
        PreferenceDefinition("pref_theme", PreferenceValueType.INT) {
            it is Int && it in 0..3
        },
        PreferenceDefinition("pref_app_language", PreferenceValueType.STRING) {
            it is String && it.length <= 16
        },
        PreferenceDefinition("pref_pure_black", PreferenceValueType.BOOLEAN) {
            it is Boolean
        },
        PreferenceDefinition("pref_accent_color", PreferenceValueType.STRING) {
            it is String && it.length <= 32
        },
        PreferenceDefinition("pref_disable_animation", PreferenceValueType.BOOLEAN) {
            it is Boolean
        },
        PreferenceDefinition("pref_widget_opacity", PreferenceValueType.STRING) {
            it is String && it.toIntOrNull()?.let { num -> num in 0..255 } == true
        },
        PreferenceDefinition("pref_habit_card_corner_radius", PreferenceValueType.INT) {
            it is Int && it in 0..32
        },
        PreferenceDefinition("pref_habits_card_corner_radius", PreferenceValueType.INT) {
            it is Int && it in -1..32
        },
        PreferenceDefinition("pref_statistics_card_corner_radius", PreferenceValueType.INT) {
            it is Int && it in -1..32
        },
        PreferenceDefinition("pref_show_habit_card_borders", PreferenceValueType.BOOLEAN) {
            it is Boolean
        },

        // Habit behaviors & display
        PreferenceDefinition("pref_first_weekday", PreferenceValueType.STRING) {
            it is String && it in listOf("-1", "1", "2", "3", "4", "5", "6", "7")
        },
        PreferenceDefinition("pref_midnight_delay", PreferenceValueType.BOOLEAN) {
            it is Boolean
        },
        PreferenceDefinition("pref_enable_habit_spheres", PreferenceValueType.BOOLEAN) {
            it is Boolean
        },
        PreferenceDefinition("pref_enable_day_tiers", PreferenceValueType.BOOLEAN) {
            it is Boolean
        },
        PreferenceDefinition("pref_habit_group_separators", PreferenceValueType.BOOLEAN) {
            it is Boolean
        },
        PreferenceDefinition("pref_day_tier_sort_order", PreferenceValueType.STRING) {
            it is String && it.split(",").all { item ->
                item.isBlank() || runCatching { DayTier.valueOf(item) }.isSuccess
            }
        },
        PreferenceDefinition("pref_short_toggle", PreferenceValueType.BOOLEAN) {
            it is Boolean
        },
        PreferenceDefinition("pref_checkmark_reverse_order", PreferenceValueType.BOOLEAN) {
            it is Boolean
        },
        PreferenceDefinition("pref_skip_enabled", PreferenceValueType.BOOLEAN) {
            it is Boolean
        },
        PreferenceDefinition("pref_unknown_enabled", PreferenceValueType.BOOLEAN) {
            it is Boolean
        },
        PreferenceDefinition("pref_default_order", PreferenceValueType.STRING) {
            it is String && runCatching { HabitList.Order.valueOf(it) }.isSuccess
        },
        PreferenceDefinition("pref_default_secondary_order", PreferenceValueType.STRING) {
            it is String && runCatching { HabitList.Order.valueOf(it) }.isSuccess
        },
        PreferenceDefinition("pref_default_habit_palette_color", PreferenceValueType.INT) {
            it is Int
        },
        PreferenceDefinition("pref_show_archived", PreferenceValueType.BOOLEAN) {
            it is Boolean
        },
        PreferenceDefinition("pref_show_completed", PreferenceValueType.BOOLEAN) {
            it is Boolean
        },

        // Statistics
        PreferenceDefinition("pref_score_view_interval", PreferenceValueType.INT) {
            it is Int && it in 0..4
        },
        PreferenceDefinition("pref_bar_card_bool_spinner", PreferenceValueType.INT) {
            it is Int && it in 0..3
        },
        PreferenceDefinition("pref_bar_card_numerical_spinner", PreferenceValueType.INT) {
            it is Int && it in 0..4
        },

        // Pomodoro defaults
        PreferenceDefinition("pref_pomodoro_default_focus_minutes", PreferenceValueType.INT) {
            it is Int && it in 1..180
        },
        PreferenceDefinition("pref_pomodoro_default_break_minutes", PreferenceValueType.INT) {
            it is Int && it in 1..60
        },
        PreferenceDefinition("pref_pomodoro_auto_switch", PreferenceValueType.BOOLEAN) {
            it is Boolean
        },
        PreferenceDefinition("pref_pomodoro_focus_alert", PreferenceValueType.BOOLEAN) {
            it is Boolean
        },
        PreferenceDefinition("pref_pomodoro_break_alert", PreferenceValueType.BOOLEAN) {
            it is Boolean
        },

        // Notifications
        PreferenceDefinition("pref_sticky_notifications", PreferenceValueType.BOOLEAN) {
            it is Boolean
        }
    ).associateBy { it.key }

    val EXCLUDED_KEYS = setOf(
        "sync_device_id",
        "publicBackupFolder",
        "pref_ringtone_uri",
        "pref_sync_base_url",
        "pref_sync_key",
        "pref_encryption_key",
        "pref_sync_enabled",
        "pref_sync_status",
        "pref_sync_status_detail",
        "pref_sync_last_ui_refresh_reason",
        "pref_sync_last_ui_refresh_at",
        "pref_sync_last_ui_refresh_destination",
        "pref_sync_last_ui_refresh_habit_count",
        "pref_sync_last_success_at",
        "pref_sync_last_log_id",
        "pref_sync_review_required",
        "pref_sync_review_reason",
        "pref_sync_account_email",
        "pref_sync_bootstrap_queued",
        "pref_sync_bootstrap_done",
        "pref_sync_last_bg_reason",
        "pref_sync_last_bg_scheduled_at",
        "pref_sync_last_bg_result",
        "pref_developer",
        "production_preparation_request",
        "pref_first_run",
        "last_version",
        "launch_count",
        "last_hint_number",
        "last_hint_timestamp",
        "backup_status_last_success_at",
        "backup_status_last_failure_at",
        "backup_status_last_location",
        "backup_status_last_size_bytes",
        "backup_status_last_db_version",
        "backup_status_last_failure_reason",
        "pref_timer_habit_id",
        "pref_timer_habit_name",
        "pref_timer_mode",
        "pref_timer_phase",
        "pref_timer_is_running",
        "pref_timer_accumulated_elapsed_millis",
        "pref_timer_started_at_wall_clock_millis",
        "pref_timer_focus_duration_millis"
    )

    fun exportPreferences(
        sharedPrefs: SharedPreferences,
        appId: String = "org.isoron.uhabits",
        createdAt: Long = System.currentTimeMillis()
    ): String {
        val root = JSONObject()
        root.put("format", PREFERENCES_FORMAT)
        root.put("format_version", CURRENT_PREFERENCES_FORMAT_VERSION)
        root.put("app_id", appId)
        root.put("created_at", createdAt)

        val prefsObj = JSONObject()
        val allStored = sharedPrefs.all

        // Sort keys alphabetically for deterministic output
        val sortedKeys = ALLOWLIST.keys.sorted()
        for (key in sortedKeys) {
            val def = ALLOWLIST[key] ?: continue
            if (!allStored.containsKey(key)) continue
            val rawValue = allStored[key] ?: continue

            // Type check and validate
            val isValid = when (def.type) {
                PreferenceValueType.BOOLEAN -> rawValue is Boolean && def.validator(rawValue)
                PreferenceValueType.INT -> (rawValue as? Number)?.toInt()?.let { def.validator(it) } == true
                PreferenceValueType.STRING -> rawValue is String && def.validator(rawValue)
            }

            if (isValid) {
                val entryObj = JSONObject()
                entryObj.put("type", def.type.name)
                when (def.type) {
                    PreferenceValueType.BOOLEAN -> entryObj.put("value", rawValue as Boolean)
                    PreferenceValueType.INT -> entryObj.put("value", (rawValue as Number).toInt())
                    PreferenceValueType.STRING -> entryObj.put("value", rawValue as String)
                }
                prefsObj.put(key, entryObj)
            }
        }

        root.put("preferences", prefsObj)
        return root.toString(2)
    }

    fun validatePreferencesJson(jsonStr: String): PreferencesValidationResult {
        if (jsonStr.isBlank()) {
            return PreferencesValidationResult(false, "Preferences JSON is empty")
        }

        val root = try {
            JSONObject(jsonStr)
        } catch (e: Exception) {
            return PreferencesValidationResult(false, "Malformed preferences JSON: ${e.message}")
        }

        val format = root.optString("format", "")
        if (format != PREFERENCES_FORMAT) {
            return PreferencesValidationResult(false, "Unsupported preferences format: $format")
        }

        val formatVersion = root.optInt("format_version", 0)
        if (formatVersion != CURRENT_PREFERENCES_FORMAT_VERSION) {
            return PreferencesValidationResult(false, "Unsupported preferences format version: $formatVersion")
        }

        if (!root.has("preferences")) {
            return PreferencesValidationResult(false, "Missing preferences object")
        }

        val prefsObj = root.optJSONObject("preferences")
            ?: return PreferencesValidationResult(false, "Invalid preferences structure")

        var count = 0
        val keys = prefsObj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val def = ALLOWLIST[key]
            if (def == null) {
                // Reject unknown or unallowlisted keys fail-closed
                return PreferencesValidationResult(false, "Unallowlisted or unauthorized preference key in backup: $key")
            }

            val entryObj = prefsObj.optJSONObject(key)
                ?: return PreferencesValidationResult(false, "Invalid preference entry for key: $key")

            val typeStr = entryObj.optString("type", "")
            val expectedType = try {
                PreferenceValueType.valueOf(typeStr)
            } catch (_: Exception) {
                return PreferencesValidationResult(false, "Unknown type '$typeStr' for key: $key")
            }

            if (expectedType != def.type) {
                return PreferencesValidationResult(false, "Type mismatch for key $key: expected ${def.type}, got $expectedType")
            }

            val isValid = when (expectedType) {
                PreferenceValueType.BOOLEAN -> {
                    if (!entryObj.has("value") || entryObj.get("value") !is Boolean) false
                    else def.validator(entryObj.getBoolean("value"))
                }
                PreferenceValueType.INT -> {
                    if (!entryObj.has("value") || entryObj.get("value") !is Number) false
                    else def.validator(entryObj.getInt("value"))
                }
                PreferenceValueType.STRING -> {
                    if (!entryObj.has("value") || entryObj.get("value") !is String) false
                    else def.validator(entryObj.getString("value"))
                }
            }

            if (!isValid) {
                return PreferencesValidationResult(false, "Invalid or out-of-range value for key: $key")
            }
            count++
        }

        return PreferencesValidationResult(true, null, count)
    }

    fun importPreferences(sharedPrefs: SharedPreferences, jsonStr: String) {
        val validation = validatePreferencesJson(jsonStr)
        if (!validation.isValid) {
            throw IllegalArgumentException("Cannot import invalid preferences: ${validation.errorMessage}")
        }

        val root = JSONObject(jsonStr)
        val prefsObj = root.getJSONObject("preferences")
        val editor = sharedPrefs.edit()

        val keys = prefsObj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val def = ALLOWLIST[key] ?: continue
            val entryObj = prefsObj.getJSONObject(key)

            when (def.type) {
                PreferenceValueType.BOOLEAN -> editor.putBoolean(key, entryObj.getBoolean("value"))
                PreferenceValueType.INT -> editor.putInt(key, entryObj.getInt("value"))
                PreferenceValueType.STRING -> editor.putString(key, entryObj.getString("value"))
            }
        }

        val committed = editor.commit()
        if (!committed) {
            throw IllegalStateException("Failed to commit imported preferences")
        }
    }

    fun capturePortablePreferencesSnapshot(sharedPrefs: SharedPreferences): Map<String, Any> {
        val snapshot = mutableMapOf<String, Any>()
        val allStored = sharedPrefs.all
        for ((key, def) in ALLOWLIST) {
            if (allStored.containsKey(key)) {
                val value = allStored[key]
                if (value != null) {
                    snapshot[key] = value
                }
            }
        }
        return snapshot
    }

    fun restorePortablePreferencesSnapshot(sharedPrefs: SharedPreferences, snapshot: Map<String, Any>) {
        val editor = sharedPrefs.edit()
        for ((key, def) in ALLOWLIST) {
            if (snapshot.containsKey(key)) {
                val value = snapshot[key]
                when (def.type) {
                    PreferenceValueType.BOOLEAN -> if (value is Boolean) editor.putBoolean(key, value)
                    PreferenceValueType.INT -> if (value is Number) editor.putInt(key, value.toInt())
                    PreferenceValueType.STRING -> if (value is String) editor.putString(key, value)
                }
            } else {
                editor.remove(key)
            }
        }
        editor.commit()
    }
}
