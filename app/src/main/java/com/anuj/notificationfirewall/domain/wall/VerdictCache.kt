package com.anuj.notificationfirewall.domain.wall

import com.anuj.notificationfirewall.data.db.VerdictCacheEntity
import com.anuj.notificationfirewall.data.db.dao.VerdictCacheDao

/** Above this, the sender is a person and the verdict is single-use. */
private const val HUMAN_THRESHOLD = 0.7f

/** Below this, Jev was unsure and the verdict must not be made permanent. */
private const val CONFIDENCE_THRESHOLD = 0.6f

/**
 * Reusable Jev verdicts, keyed by content shape.
 *
 * Two admission rules, both load-bearing:
 *
 * 1. **A verdict about a human's message is never cached.** One WhatsApp thread
 *    is one sender, but "reached home safely" and a forwarded sale are not the
 *    same notification. Messages from people are judged fresh, every time.
 * 2. **A low-confidence verdict is never cached.** Jev is calibrated, so a low
 *    confidence genuinely means "unsure" — writing that down would let one
 *    uncertain call mislabel a sender permanently.
 */
class VerdictCache(
    private val dao: VerdictCacheDao,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    suspend fun get(shape: String): JevVerdict? {
        val entry = dao.find(shape) ?: return null
        dao.recordHit(shape, clock())
        return JevVerdict(
            importance = entry.importance,
            category = entry.category,
            isTimeSensitive = entry.isTimeSensitive,
            isFromHuman = entry.isFromHuman,
            needsAction = entry.needsAction,
            confidence = entry.confidence,
        )
    }

    /** Returns true when the verdict was admitted, false when a rule rejected it. */
    suspend fun put(shape: String, pkg: String, sender: String?, verdict: JevVerdict): Boolean {
        if (verdict.isFromHuman >= HUMAN_THRESHOLD) return false
        if (verdict.confidence < CONFIDENCE_THRESHOLD) return false

        val now = clock()
        dao.upsert(
            VerdictCacheEntity(
                contentShape = shape,
                packageName = pkg,
                senderKey = sender,
                importance = verdict.importance,
                category = verdict.category,
                isTimeSensitive = verdict.isTimeSensitive,
                isFromHuman = verdict.isFromHuman,
                needsAction = verdict.needsAction,
                confidence = verdict.confidence,
                hitCount = 0,
                createdAtEpochMs = now,
                lastUsedEpochMs = now,
            ),
        )
        return true
    }

    suspend fun evict(shape: String) = dao.evict(shape)

    suspend fun evictStale(maxAgeDays: Long): Int =
        dao.evictUnusedSince(clock() - maxAgeDays * 24 * 60 * 60 * 1000)
}
