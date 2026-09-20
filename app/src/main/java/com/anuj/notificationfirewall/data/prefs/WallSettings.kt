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

    /**
     * When the daily digest fires, as minutes since midnight (0–1439).
     * Read by [com.anuj.notificationfirewall.work.WallWorkScheduler] at app
     * start and by Settings on every change to (re)schedule
     * [com.anuj.notificationfirewall.work.DigestWorker] via
     * [com.anuj.notificationfirewall.work.DigestScheduler].
     */
    var digestTimeMinuteOfDay: Int
        get() = prefs.getInt(KEY_DIGEST_TIME_MINUTE, DEFAULT_DIGEST_TIME_MINUTE)
        set(value) = prefs.edit { putInt(KEY_DIGEST_TIME_MINUTE, value.coerceIn(0, 1439)) }

    /**
     * How long a break-glass window stays open once triggered, in minutes.
     * Settings owns the knob;
     * [com.anuj.notificationfirewall.service.BreakGlassController] reads it
     * when opening a window and is what sets
     * [com.anuj.notificationfirewall.ui.wall.WallUiState.breakGlassUntilMs].
     */
    var breakGlassDurationMinutes: Int
        get() = prefs.getInt(KEY_BREAK_GLASS_MINUTES, DEFAULT_BREAK_GLASS_MINUTES)
        set(value) = prefs.edit { putInt(KEY_BREAK_GLASS_MINUTES, value.coerceIn(5, 120)) }

    /**
     * Opaque JSON blob for the last digest [com.anuj.notificationfirewall.work.DigestWorker]
     * built -- see [com.anuj.notificationfirewall.ai.DigestStore], the only
     * reader/writer. Kept as an untyped string here (rather than WallSettings
     * knowing about `PersistedDigest`) so this class doesn't need to depend
     * on the `ai` package; DigestStore owns the schema and (de)serialization.
     * Doubles as the "already ran today" stamp DigestWorker checks before
     * doing any work, so a stray second fire on the same calendar day (e.g.
     * from a mid-day settings change reschedule) is a no-op instead of a
     * second notification.
     */
    var lastDigestJson: String?
        get() = prefs.getString(KEY_LAST_DIGEST_JSON, null)
        set(value) = prefs.edit {
            if (value == null) remove(KEY_LAST_DIGEST_JSON) else putString(KEY_LAST_DIGEST_JSON, value)
        }

    private companion object {
        const val KEY_THRESHOLD = "wall_threshold"
        const val KEY_OTP_FAST_PATH = "wall_otp_fast_path"
        const val KEY_TEXT_RETENTION_DAYS = "wall_text_retention_days"
        const val KEY_JEV_API_KEY = "jev_api_key"
        const val KEY_THEME_MODE = "wall_theme_mode"
        const val KEY_DIGEST_TIME_MINUTE = "wall_digest_time_minute"
        const val KEY_BREAK_GLASS_MINUTES = "wall_break_glass_minutes"
        const val KEY_LAST_DIGEST_JSON = "wall_last_digest_json"
        const val DEFAULT_THRESHOLD = 4.0f
        const val DEFAULT_TEXT_RETENTION_DAYS = 30
        const val DEFAULT_DIGEST_TIME_MINUTE = 21 * 60 // 9:00 PM
        const val DEFAULT_BREAK_GLASS_MINUTES = 15
    }
}
