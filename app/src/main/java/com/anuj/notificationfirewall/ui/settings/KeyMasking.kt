// ui/settings/KeyMasking.kt
package com.anuj.notificationfirewall.ui.settings

/**
 * Pure key-preview logic for the key-entry screen.
 *
 * A stored key is never rendered in full: the owner needs to tell *which*
 * key is stored, not shoulder-surf its value. For a key long enough that a
 * first-8/last-4 preview doesn't overlap, that preview is shown (e.g.
 * `sk-proj-…a4oA`). For anything shorter, revealing 8+4=12 characters would
 * either overlap or expose the whole key, so it is masked completely instead.
 */
object KeyMasking {
    private const val PREFIX_LEN = 8
    private const val SUFFIX_LEN = 4
    private const val MIN_LEN_FOR_PARTIAL_REVEAL = PREFIX_LEN + SUFFIX_LEN

    /** Returns null when there is nothing stored (null or blank key). */
    fun maskedPreview(key: String?): String? {
        if (key.isNullOrBlank()) return null
        return if (key.length >= MIN_LEN_FOR_PARTIAL_REVEAL) {
            "${key.take(PREFIX_LEN)}…${key.takeLast(SUFFIX_LEN)}"
        } else {
            "•".repeat(key.length)
        }
    }
}
