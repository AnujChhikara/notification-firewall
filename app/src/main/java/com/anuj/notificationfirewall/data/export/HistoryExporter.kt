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
                    put("senderKey", r.senderKey ?: JSONObject.NULL)
                    put("category", r.category?.name ?: JSONObject.NULL)
                    put("importanceScore", r.importanceScore ?: JSONObject.NULL)
                    put("biasApplied", r.biasApplied)
                    put("jevConfidence", r.jevConfidence ?: JSONObject.NULL)
                    put("decisionSource", r.decisionSource.name)
                    put("bucket", r.bucket.name)
                    if (includeContent) {
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
