package com.anuj.notificationfirewall.data.prefs

import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Thin wrapper around a [SharedPreferences] instance holding the user's
 * OpenAI API key.
 *
 * This class takes the [SharedPreferences] as a constructor argument rather
 * than building it internally so it can be unit-tested under Robolectric
 * with a plain (unencrypted) SharedPreferences — EncryptedSharedPreferences
 * requires the Android Keystore, which is unavailable on the JVM. The Hilt
 * `AppModule` provider wraps this class around a real
 * EncryptedSharedPreferences instance in production.
 */
class SecurePrefs(private val prefs: SharedPreferences) {

    var openAiKey: String?
        get() = prefs.getString(KEY_OPENAI_API_KEY, null)
        set(value) {
            prefs.edit {
                if (value == null) {
                    remove(KEY_OPENAI_API_KEY)
                } else {
                    putString(KEY_OPENAI_API_KEY, value)
                }
            }
        }

    val hasKey: Boolean
        get() = !openAiKey.isNullOrBlank()

    /**
     * True while system DND is currently on *because this app turned it on* for an
     * active auto-DND profile. Lets [DndController] restore DND only when it owns
     * the change, never clobbering DND the user enabled manually.
     */
    var dndSetByApp: Boolean
        get() = prefs.getBoolean(KEY_DND_SET_BY_APP, false)
        set(value) = prefs.edit { putBoolean(KEY_DND_SET_BY_APP, value) }

    // The user's own DND policy, captured before we overwrite it with our
    // call-safe policy, so we can restore it exactly when we turn DND back off.
    var hasSavedDndPolicy: Boolean
        get() = prefs.getBoolean(KEY_HAS_SAVED_DND_POLICY, false)
        set(value) = prefs.edit { putBoolean(KEY_HAS_SAVED_DND_POLICY, value) }

    var savedDndCategories: Int
        get() = prefs.getInt(KEY_DND_CATEGORIES, 0)
        set(value) = prefs.edit { putInt(KEY_DND_CATEGORIES, value) }

    var savedDndCallSenders: Int
        get() = prefs.getInt(KEY_DND_CALL_SENDERS, 0)
        set(value) = prefs.edit { putInt(KEY_DND_CALL_SENDERS, value) }

    var savedDndMessageSenders: Int
        get() = prefs.getInt(KEY_DND_MSG_SENDERS, 0)
        set(value) = prefs.edit { putInt(KEY_DND_MSG_SENDERS, value) }

    var savedDndSuppressedEffects: Int
        get() = prefs.getInt(KEY_DND_SUPPRESSED, 0)
        set(value) = prefs.edit { putInt(KEY_DND_SUPPRESSED, value) }

    private companion object {
        const val KEY_OPENAI_API_KEY = "openai_api_key"
        const val KEY_DND_SET_BY_APP = "dnd_set_by_app"
        const val KEY_HAS_SAVED_DND_POLICY = "dnd_has_saved_policy"
        const val KEY_DND_CATEGORIES = "dnd_saved_categories"
        const val KEY_DND_CALL_SENDERS = "dnd_saved_call_senders"
        const val KEY_DND_MSG_SENDERS = "dnd_saved_msg_senders"
        const val KEY_DND_SUPPRESSED = "dnd_saved_suppressed"
    }
}
