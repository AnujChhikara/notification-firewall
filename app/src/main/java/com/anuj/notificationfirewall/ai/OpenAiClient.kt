package com.anuj.notificationfirewall.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
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

/** Thin wrapper over OpenAI's chat/completions endpoint. */
class OpenAiClient(
    private val baseUrl: HttpUrl,
    private val apiKey: String,
    private val http: OkHttpClient
) {
    suspend fun chat(
        model: String,
        systemPrompt: String,
        userContent: String,
        jsonMode: Boolean
    ): String = withContext(Dispatchers.IO) {
        val requestBody = buildJsonObject {
            put("model", model)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", systemPrompt)
                })
                add(buildJsonObject {
                    put("role", "user")
                    put("content", userContent)
                })
            })
            if (jsonMode) {
                put("response_format", buildJsonObject {
                    put("type", "json_object")
                })
            }
        }

        val url = baseUrl.newBuilder().addPathSegments("chat/completions").build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .post(requestBody.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("OpenAI request failed: HTTP ${response.code}")
            }
            val bodyString = response.body?.string()
                ?: throw IOException("OpenAI response had no body")
            val root = Json.parseToJsonElement(bodyString)
            root.jsonObject["choices"]!!
                .jsonArray[0]
                .jsonObject["message"]!!
                .jsonObject["content"]!!
                .jsonPrimitive.content
        }
    }
}
