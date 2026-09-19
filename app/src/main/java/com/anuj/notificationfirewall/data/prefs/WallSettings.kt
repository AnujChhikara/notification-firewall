package com.anuj.notificationfirewall.data.prefs

import android.content.SharedPreferences
import androidx.core.content.edit
import com.anuj.notificationfirewall.ui.theme.ThemeMode

/** User-tunable wall policy. Backed by the same encrypted prefs as SecurePrefs. */
class WallSettings(private val prefs: SharedPreferences) {

    /** Ring when biased importance is at or above this. 1.0–5.0, default 4.0. */
    var threshold: Float
        get() = prefs.getFloat(KEY_THRESHOLD, DEFAULT_THRESHOLD)
        set(value) = prefs.edit { putFloat(KEY_THRESHOLD, value.coerceIn(1f, 5f)) }

    var otpFastPathEnabled: Boolean
        get() = prefs.getBoolean(KEY_OTP_FAST_PATH, true)
        set(value) = prefs.edit { putBoolean(KEY_OTP_FAST_PATH, value) }

    /** Days before notification text is purged. 0 means never purge. */
    var textRetentionDays: Int
        get() = prefs.getInt(KEY_TEXT_RETENTION_DAYS, DEFAULT_TEXT_RETENTION_DAYS)
        set(value) = prefs.edit { putInt(KEY_TEXT_RETENTION_DAYS, value.coerceAtLeast(0)) }

    var jevKey: String?
        get() = prefs.getString(KEY_JEV_API_KEY, null)
        set(value) = prefs.edit {
            if (value == null) remove(KEY_JEV_API_KEY) else putString(KEY_JEV_API_KEY, value)
        }

    var themeMode: ThemeMode
        get() = runCatching {
            ThemeMode.valueOf(prefs.getString(KEY_THEME_MODE, null) ?: ThemeMode.SYSTEM.name)
        }.getOrDefault(ThemeMode.SYSTEM)
        set(value) = prefs.edit { putString(KEY_THEME_MODE, value.name) }

    private companion object {
        const val KEY_THRESHOLD = "wall_threshold"
        const val KEY_OTP_FAST_PATH = "wall_otp_fast_path"
        const val KEY_TEXT_RETENTION_DAYS = "wall_text_retention_days"
        const val KEY_JEV_API_KEY = "jev_api_key"
        const val KEY_THEME_MODE = "wall_theme_mode"
        const val DEFAULT_THRESHOLD = 4.0f
        const val DEFAULT_TEXT_RETENTION_DAYS = 30
    }
}
