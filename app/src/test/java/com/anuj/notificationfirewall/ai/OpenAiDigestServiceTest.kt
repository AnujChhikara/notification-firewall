package com.anuj.notificationfirewall.ai

import com.anuj.notificationfirewall.data.db.NotificationRecordEntity
import com.anuj.notificationfirewall.domain.model.BucketAction
import com.anuj.notificationfirewall.domain.model.DecisionSource
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class OpenAiDigestServiceTest {
    private lateinit var server: MockWebServer
    private lateinit var service: OpenAiDigestService
    @Before fun setup() {
        server = MockWebServer(); server.start()
        service = OpenAiDigestService(OpenAiClient(server.url("/v1/"), "sk-test", OkHttpClient()))
    }
    @After fun teardown() = server.shutdown()
    private fun rec() = NotificationRecordEntity(
        packageName = "com.gmail", appLabel = "Gmail", title = "t", text = "x",
        timestampEpochMs = 1, senderKey = "promo", activeProfileId = 1, matchedRuleId = null,
        decisionSource = DecisionSource.DEFAULT, bucket = BucketAction.CAPTURE,
        aiUrgent = null, aiReason = null, isRead = false)

    @Test fun empty_input_short_circuits_without_http() = runBlocking {
        val text = service.summarize(emptyList())
        assertEquals("Nothing came through while you were away.", text)
        assertEquals(0, server.requestCount)
    }
    @Test fun returns_model_summary() = runBlocking {
        server.enqueue(MockResponse().setBody(
            """{"choices":[{"message":{"content":"3 promos, nothing urgent."}}]}"""))
        assertEquals("3 promos, nothing urgent.", service.summarize(listOf(rec())))
    }
    @Test fun http_error_falls_back_to_count() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        assertTrue(service.summarize(listOf(rec())).contains("1"))
    }
}
