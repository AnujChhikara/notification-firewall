package com.anuj.notificationfirewall.ai

import com.anuj.notificationfirewall.data.prefs.WallSettings
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the Wall screen's digest card shows, and what [DigestWorker][com.anuj.notificationfirewall.work.DigestWorker]
 * uses to tell whether it already ran today.
 *
 * [headline] is exactly the string the notification showed -- whatever
 * [OpenAiDigestService] returned, model prose or local fallback -- never
 * recomputed. Recomputing on demand would either re-hit OpenAI (a second
 * charged call that could produce a *different* sentence than the one the
 * user was actually notified with) or silently fall back to the local
 * summary when the real headline came from the model; either way the card
 * would show something other than what the user was told.
 *
 * [worthALook] carries the same purge-guarded lines [DigestBuilder] produced
 * -- see [DigestBuilder.renderWorthALookLine]'s KDoc for why a purged
 * record's line is safe to keep here too. This is on-device storage only;
 * nothing here is ever sent anywhere.
 */
@Serializable
data class PersistedDigest(
    /** [java.time.LocalDate.toEpochDay] of the day this digest was posted (not the day it covers). */
    val dateEpochDay: Long,
    val headline: String,
    val rang: Int,
    val silenced: Int,
    val dropped: Int,
    val topOffenderLabel: String? = null,
    val topOffenderCount: Int? = null,
    val worthALook: List<String> = emptyList(),
)

/** Reads/writes [WallSettings.lastDigestJson] as a typed [PersistedDigest]. */
@Singleton
class DigestStore @Inject constructor(
    private val settings: WallSettings,
) {
    fun save(digest: PersistedDigest) {
        settings.lastDigestJson = Json.encodeToString(PersistedDigest.serializer(), digest)
    }

    /** Null on first run, or if the stored JSON is somehow unreadable (a schema change, corruption). */
    fun load(): PersistedDigest? =
        settings.lastDigestJson?.let {
            runCatching { Json.decodeFromString(PersistedDigest.serializer(), it) }.getOrNull()
        }
}
