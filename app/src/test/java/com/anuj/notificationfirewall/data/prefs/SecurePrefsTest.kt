package com.anuj.notificationfirewall.data.prefs

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * SecurePrefs is tested against a plain (unencrypted) SharedPreferences under
 * Robolectric, since EncryptedSharedPreferences requires the Android Keystore
 * which is unavailable on the JVM. The production Hilt provider wraps the
 * same class around a real EncryptedSharedPreferences instance.
 */
@RunWith(RobolectricTestRunner::class)
class SecurePrefsTest {

    private lateinit var securePrefs: SecurePrefs

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val prefs = context.getSharedPreferences("secure_prefs_test", android.content.Context.MODE_PRIVATE)
        securePrefs = SecurePrefs(prefs)
    }

    @Test
    fun hasKey_isFalse_whenNoKeyStored() {
        assertFalse(securePrefs.hasKey)
        assertNull(securePrefs.openAiKey)
    }

    @Test
    fun roundTrips_theStoredKey() {
        securePrefs.openAiKey = "sk-test-12345"
        assertEquals("sk-test-12345", securePrefs.openAiKey)
    }

    @Test
    fun hasKey_flips_falseToTrue_whenKeySet() {
        assertFalse(securePrefs.hasKey)
        securePrefs.openAiKey = "sk-test-12345"
        assertTrue(securePrefs.hasKey)
    }

    @Test
    fun hasKey_flips_backToFalse_whenKeyCleared() {
        securePrefs.openAiKey = "sk-test-12345"
        assertTrue(securePrefs.hasKey)

        securePrefs.openAiKey = null
        assertFalse(securePrefs.hasKey)
        assertNull(securePrefs.openAiKey)
    }

    @Test
    fun hasKey_isFalse_whenKeyIsBlank() {
        securePrefs.openAiKey = "   "
        assertFalse(securePrefs.hasKey)
    }
}
