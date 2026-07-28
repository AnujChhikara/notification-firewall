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

    private companion object {
        const val KEY_OPENAI_API_KEY = "openai_api_key"
        const val KEY_DND_SET_BY_APP = "dnd_set_by_app"
    }
}
