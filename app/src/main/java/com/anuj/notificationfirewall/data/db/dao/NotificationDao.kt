package com.anuj.notificationfirewall.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.anuj.notificationfirewall.data.db.NotificationRecordEntity
import com.anuj.notificationfirewall.domain.wall.NotificationCategory
import com.anuj.notificationfirewall.domain.wall.WallBucket
import com.anuj.notificationfirewall.domain.wall.WallDecisionSource
import kotlinx.coroutines.flow.Flow

data class BucketCount(val bucket: WallBucket, val count: Int)

/** One app and how many of its notifications matched — for the Ask tab's stat cards. */
data class AppCount(val appLabel: String, val count: Int)

@Dao
interface NotificationDao {
    @Insert
    suspend fun insert(rec: NotificationRecordEntity): Long

    @Query("SELECT * FROM notifications ORDER BY timestampEpochMs DESC LIMIT :limit")
    fun observeRecent(limit: Int = 500): Flow<List<NotificationRecordEntity>>

    @Query("SELECT * FROM notifications WHERE timestampEpochMs BETWEEN :startMs AND :endMs ORDER BY timestampEpochMs")
    suspend fun recordsBetween(startMs: Long, endMs: Long): List<NotificationRecordEntity>

    @Query("SELECT * FROM notifications WHERE pendingClassification = 1 ORDER BY timestampEpochMs LIMIT :limit")
    suspend fun pending(limit: Int): List<NotificationRecordEntity>

    @Query(
        """
        UPDATE notifications
        SET importanceScore = :importance, category = :category,
            isTimeSensitive = :timeSensitive, isFromHuman = :fromHuman,
            needsAction = :needsAction, jevConfidence = :confidence,
            bucket = :bucket, decisionSource = :source, pendingClassification = 0
        WHERE id = :id
        """,
    )
    suspend fun applyVerdict(
        id: Long,
        importance: Float,
        category: NotificationCategory,
        timeSensitive: Float,
        fromHuman: Float,
        needsAction: Float,
        confidence: Float,
        bucket: WallBucket,
        source: WallDecisionSource,
    )

    @Query("UPDATE notifications SET isRead = 1 WHERE id = :id")
    suspend fun markRead(id: Long)

    /**
     * Clears the pending flag without writing a verdict — for records whose
     * text was already purged by retention. There is nothing left to send to
     * Jev, so no classification is possible; recording that honestly (verdict
     * columns left NULL) is correct, not writing fabricated numbers just to
     * satisfy [applyVerdict]'s non-nullable signature. SQL aggregates skip
     * NULLs, so stats and Ask stay accurate instead of averaging in a fake
     * verdict.
     *
     * decisionSource becomes EXPIRED, not left at PENDING: "pending = false"
     * with "source = PENDING" would describe a state no consumer expects
     * (pending implies a verdict is still coming), and LEGACY would be wrong
     * too -- LEGACY means a verdict WAS produced, by an earlier app version.
     * EXPIRED says plainly that the text was purged before a verdict could
     * ever be obtained.
     */
    @Query("UPDATE notifications SET pendingClassification = 0, decisionSource = 'EXPIRED' WHERE id = :id")
    suspend fun clearPending(id: Long)

    @Query(
        "UPDATE notifications SET title = NULL, text = NULL, textPurgedAt = :nowMs " +
            "WHERE timestampEpochMs < :cutoffMs AND textPurgedAt IS NULL",
    )
    suspend fun purgeTextBefore(cutoffMs: Long, nowMs: Long): Int

    @Query(
        "SELECT bucket, COUNT(*) AS count FROM notifications " +
            "WHERE timestampEpochMs >= :startMs AND timestampEpochMs < :endMs GROUP BY bucket",
    )
    suspend fun countsForDay(startMs: Long, endMs: Long): List<BucketCount>

    // --- Ask tab stat cards -------------------------------------------------
    //
    // These are fixed, hand-written queries, not model-authored ones: the
    // numbers on the cards are the app's own claims about itself and must not
    // depend on what a language model felt like emitting today.
    //
    // How the odd decisionSource values are treated, deliberately:
    //
    //  * Arrival-shaped stats (noise ratio, top offenders, by hour) count
    //    EVERY row regardless of decisionSource. `bucket` is NOT NULL for all
    //    of them, and the bucket is what the user actually experienced --
    //    a LEGACY or PENDING notification was still silenced or still rang.
    //  * Verdict-shaped stats (the human share) count only rows that carry a
    //    verdict, expressed as `isFromHuman IS NOT NULL`. That excludes
    //    EXPIRED (text purged before any verdict could be obtained, so the
    //    verdict columns are NULL by design), PENDING (not judged yet) and
    //    LEGACY (judged by a scoring model that no longer exists) without
    //    naming them, because the NULL *is* the fact. Dividing by a total
    //    that included them would silently understate the human share.
    //  * The correction rate divides by rows THIS app judged
    //    (decisionSource IN ('JEV','CACHE')). It is the honesty metric — "how
    //    often was I wrong" — so its denominator must be judgements the
    //    current model actually made. LEGACY's judgements were another
    //    model's; PENDING and EXPIRED never produced one.

    @Query("SELECT COUNT(*) FROM notifications WHERE timestampEpochMs >= :startMs")
    suspend fun countSince(startMs: Long): Int

    @Query(
        "SELECT COUNT(*) FROM notifications " +
            "WHERE timestampEpochMs >= :startMs AND bucket <> 'RING'",
    )
    suspend fun countKeptQuietSince(startMs: Long): Int

    @Query(
        "SELECT appLabel, COUNT(*) AS count FROM notifications " +
            "WHERE timestampEpochMs >= :startMs AND bucket <> 'RING' " +
            "GROUP BY appLabel ORDER BY count DESC, appLabel ASC LIMIT :limit",
    )
    suspend fun topQuietedApps(startMs: Long, limit: Int): List<AppCount>

    /** Rows carrying a real verdict — the only honest denominator for a verdict-derived share. */
    @Query(
        "SELECT COUNT(*) FROM notifications " +
            "WHERE timestampEpochMs >= :startMs AND isFromHuman IS NOT NULL",
    )
    suspend fun countJudgedSince(startMs: Long): Int

    @Query(
        "SELECT COUNT(*) FROM notifications " +
            "WHERE timestampEpochMs >= :startMs AND isFromHuman >= 0.5",
    )
    suspend fun countFromHumanSince(startMs: Long): Int

    /** Timestamps only: the hour-of-day histogram is bucketed in the app's own
     *  time zone, which SQLite's strftime cannot do correctly across DST. */
    @Query("SELECT timestampEpochMs FROM notifications WHERE timestampEpochMs >= :startMs")
    suspend fun timestampsSince(startMs: Long): List<Long>

    /** Notifications this app's current classifier judged — the correction-rate denominator. */
    @Query("SELECT COUNT(*) FROM notifications WHERE decisionSource IN ('JEV', 'CACHE')")
    suspend fun countJudgedByThisApp(): Int
}
