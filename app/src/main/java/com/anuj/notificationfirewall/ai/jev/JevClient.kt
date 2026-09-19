package com.anuj.notificationfirewall.ai.jev

import com.anuj.notificationfirewall.domain.wall.JevApi
import com.anuj.notificationfirewall.domain.wall.JevException
import com.anuj.notificationfirewall.domain.wall.JevState
import com.anuj.notificationfirewall.domain.wall.JevVerdict
import com.anuj.notificationfirewall.domain.wall.NotificationCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
private const val MODEL = "jev-latest"

/**
 * Thin client over Jev's single System One endpoint.
 *
 * Every failure mode — transport, HTTP status, malformed body — surfaces as
 * [JevException], so the pipeline has exactly one thing to catch and one
 * degradation path (silence and store for later re-classification).
 */
class JevClient(
    private val baseUrl: HttpUrl,
    private val apiKey: String,
    private val http: OkHttpClient,
) : JevApi {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun classify(state: JevState): JevVerdict = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("model", MODEL)
            put("state", buildJsonObject {
                put("app", state.app)
                state.channel?.let { put("channel", it) }
                put("title", state.title)
                put("text", state.text)
                put("arrived_at_local", state.arrivedAtLocal)
                put("is_reply_capable", state.isReplyCapable)
                put("is_from_contact", state.isFromContact)
            })
            put("questions", JevQuestions.build())
        }

        val request = Request.Builder()
            .url(baseUrl.newBuilder().addPathSegments("v1/systemone").build())
            .header("Authorization", "Bearer $apiKey")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val raw = try {
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw JevException("Jev returned ${response.code}: ${text.take(200)}")
                }
                text
            }
        } catch (e: IOException) {
            throw JevException("Jev request failed", e)
        }

        parse(raw)
    }

    private fun parse(raw: String): JevVerdict = try {
        val answers = json.parseToJsonElement(raw).jsonObject
            .getValue("answers").jsonObject

        val importance = answers.getValue("importance").jsonObject
        val category = answers.getValue("category").jsonObject

        JevVerdict(
            // Jev's Score is 0-based across the legend; the app's scale is 1–5.
            importance = importance.getValue("score").jsonPrimitive.float + 1f,
            category = NotificationCategory.fromWire(
                category.getValue("choice").jsonPrimitive.content,
            ),
            isTimeSensitive = noul(answers, "is_time_sensitive"),
            isFromHuman = noul(answers, "is_from_human"),
            needsAction = noul(answers, "needs_action"),
            confidence = importance.getValue("confidence").jsonPrimitive.float,
        )
    } catch (e: Exception) {
        if (e is JevException) throw e
        throw JevException("Could not parse Jev response", e)
    }

    private fun noul(answers: JsonObject, key: String): Float =
        answers.getValue(key).jsonObject.getValue("noul").jsonPrimitive.float
}
