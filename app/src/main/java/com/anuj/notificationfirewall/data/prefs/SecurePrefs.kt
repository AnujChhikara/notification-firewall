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

    private companion object {
        const val KEY_OPENAI_API_KEY = "openai_api_key"
    }
}
