package com.anuj.notificationfirewall.data.export

import com.anuj.notificationfirewall.data.db.dao.NotificationDao
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

    suspend fun toJson(includeContent: Boolean): String {
        val records = notificationDao.recordsBetween(0, Long.MAX_VALUE)

        val array = JSONArray()
        records.forEach { r ->
            array.put(
                JSONObject().apply {
                    put("timestampEpochMs", r.timestampEpochMs)
                    put("packageName", r.packageName)
                    put("appLabel", r.appLabel)
                    put("category", r.category?.name ?: JSONObject.NULL)
                    put("importanceScore", r.importanceScore ?: JSONObject.NULL)
                    put("biasApplied", r.biasApplied)
                    put("jevConfidence", r.jevConfidence ?: JSONObject.NULL)
                    put("decisionSource", r.decisionSource.name)
                    put("bucket", r.bucket.name)
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
            put("count", records.size)
            put("notifications", array)
        }.toString(2)
    }
}
