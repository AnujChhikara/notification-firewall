package com.anuj.notificationfirewall.ai

import com.anuj.notificationfirewall.data.prefs.WallSettings
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.ZoneId
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

/**
 * Reads/writes [WallSettings.lastDigestJson] as a typed [PersistedDigest].
 *
 * This is a content-bearing store -- [PersistedDigest.worthALook] can carry
 * real sender names and title text for any record that had not yet been
 * purged when the digest was built (see [DigestBuilder.renderWorthALookLine]
 * for the purge guard applied at build time; it protects records already
 * purged BEFORE the digest was built, not ones purged after). Nothing about
 * writing a new digest ever removes an old one on its own except being
 * overwritten by the next successful [save] -- so two things call [clear]
 * explicitly instead of relying on that: retention's periodic sweep (see
 * [purgeIfOlderThan], called from `MaintenanceWorker`) and "Delete all
 * history" (`SettingsViewModel.deleteAllHistory`). Without those, a digest's
 * content could outlive both the notification rows it was built from and
 * the user's own retention setting, bounded only by "the worker happens to
 * run again" -- not a real guarantee.
 */
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

    /** Removes the stored digest outright. */
    fun clear() {
        settings.lastDigestJson = null
    }

    /**
     * Clears the stored digest if it predates [cutoffMs] -- the same cutoff
     * `MaintenanceWorker` passes to `NotificationDao.purgeTextBefore` -- so a
     * persisted digest ages out on the same terms as the notification rows
     * it summarises, rather than surviving indefinitely between successful
     * [DigestWorker][com.anuj.notificationfirewall.work.DigestWorker] runs.
     * [PersistedDigest.dateEpochDay] is treated as that day's start-of-day
     * instant in the device's zone, which is at least as eager as the real
     * purge (the digest's content actually describes the PRIOR day, so this
     * errs on the side of clearing sooner, never later, than the rows it was
     * built from).
     */
    fun purgeIfOlderThan(cutoffMs: Long) {
        val digest = load() ?: return
        val postedAtMs = LocalDate.ofEpochDay(digest.dateEpochDay)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        if (postedAtMs < cutoffMs) clear()
    }
}
