package com.anuj.notificationfirewall.data.export

import com.anuj.notificationfirewall.data.db.dao.NotificationDao
import com.anuj.notificationfirewall.domain.wall.WallBucket
import com.anuj.notificationfirewall.domain.wall.WallDecisionSource
import org.json.JSONArray
import org.json.JSONObject

/**
 * Dumps the notification history as JSON.
 *
 * Content is opt-in here exactly as it is everywhere else: an export is the
 * easiest way for this data to end up somewhere the user did not intend, so
 * the default carries metadata only.
 *
 * "Metadata" is decided by provenance, not by column name. `senderKey` is a
 * verbatim copy of the title (`NotificationMapper.kt`: `val senderKey = title`),
 * so it is content and lives inside the `includeContent` branch with `title`
 * and `text` -- emitting it alongside `packageName` would have written every
 * title in the history into a file the user explicitly asked to carry no
 * titles, and `CreateDocument` will happily put that file in cloud storage.
 * It is additionally suppressed once `textPurgedAt`
 * is set: retention nulls `title`/`text` but deliberately leaves `senderKey`
 * populated (see `NotificationDao.purgeTextBefore`), so a purged row still
 * carries its original title there and no export -- content-bearing or not --
 * may resurrect text retention already promised to remove.
 *
 * `contentShape` is content under the same rule and is deliberately NOT
 * exported at all: it is a cache key, useless outside the device, and worth
 * nothing to a reader of the file.
 */
class HistoryExporter(private val notificationDao: NotificationDao) {

    /**
     * @param threshold the wall's live show/mute threshold (1-5 scale). A
     *   second model can re-check every JEV row with
     *   `biasedScore >= threshold  =>  shown`, which is exactly the comparison
     *   the wall itself applies before bucketing.
     */
    suspend fun toJson(includeContent: Boolean, threshold: Float): String {
        val records = notificationDao.recordsBetween(0, Long.MAX_VALUE)

        val array = JSONArray()
        records.forEach { r ->
            val biasedScore = r.importanceScore?.plus(r.biasApplied)
            array.put(
                JSONObject().apply {
                    put("timestampEpochMs", r.timestampEpochMs)
                    put("packageName", r.packageName)
                    put("appLabel", r.appLabel)
                    put("category", r.category?.name ?: JSONObject.NULL)
                    put("importanceScore", r.importanceScore ?: JSONObject.NULL)
                    put("biasApplied", r.biasApplied)
                    put("biasedScore", biasedScore ?: JSONObject.NULL)
                    put("jevConfidence", r.jevConfidence ?: JSONObject.NULL)
                    put("decisionSource", r.decisionSource.name)
                    put("bucket", r.bucket.name)
                    put("outcome", r.bucket.outcomeLabel())
                    put("why", r.decisionSource.explain(biasedScore, threshold))
                    if (includeContent) {
                        val purged = r.textPurgedAt != null
                        put("senderKey", if (purged) JSONObject.NULL else r.senderKey ?: JSONObject.NULL)
                        put("title", r.title ?: JSONObject.NULL)
                        put("text", r.text ?: JSONObject.NULL)
                    }
                },
            )
        }

        return JSONObject().apply {
            put("exportedAtEpochMs", System.currentTimeMillis())
            put("includesContent", includeContent)
            put("threshold", threshold.toDouble())
            put(
                "thresholdMeaning",
                "A notification with a verdict is shown when biasedScore >= threshold, muted otherwise. " +
                    "biasedScore = importanceScore + biasApplied. Rows decided by OTP/VIP/BLOCK " +
                    "bypass scoring entirely; PENDING/EXPIRED rows never received a verdict.",
            )
            put("count", records.size)
            put("notifications", array)
        }.toString(2)
    }

    private fun WallBucket.outcomeLabel(): String = when (this) {
        WallBucket.RING -> "shown"
        WallBucket.SILENCE -> "muted"
        WallBucket.DROP -> "blocked"
    }

    private fun WallDecisionSource.explain(biasedScore: Float?, threshold: Float): String = when (this) {
        WallDecisionSource.OTP -> "one-time-code fast path: always shown"
        WallDecisionSource.VIP -> "sender on VIP list: always shown"
        WallDecisionSource.BLOCK -> "sender on block list: always blocked"
        WallDecisionSource.CACHE -> "reused a cached verdict for identical content"
        WallDecisionSource.JEV ->
            if (biasedScore == null) "AI verdict missing despite JEV source"
            else "AI score $biasedScore vs threshold $threshold"
        WallDecisionSource.PENDING -> "muted while waiting for a verdict"
        WallDecisionSource.LEGACY -> "judged by an earlier scoring model"
        WallDecisionSource.EXPIRED -> "text expired before a verdict arrived"
    }
}
