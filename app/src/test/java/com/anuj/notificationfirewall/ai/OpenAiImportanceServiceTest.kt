package com.anuj.notificationfirewall.ai

import com.anuj.notificationfirewall.domain.model.IncomingNotification
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.Instant

class OpenAiImportanceServiceTest {
    private lateinit var server: MockWebServer
    private lateinit var service: OpenAiImportanceService
    @Before fun setup() {
        server = MockWebServer(); server.start()
        val client = OpenAiClient(server.url("/v1/"), "sk-test", OkHttpClient())
        service = OpenAiImportanceService(client)
    }
    @After fun teardown() = server.shutdown()

    private fun notif() = IncomingNotification(
        "com.whatsapp", "WhatsApp", "Mom", "call me now", "mom", true, null, Instant.EPOCH)

    private fun body(content: String) =
        """{"choices":[{"message":{"content":${JsonPrimitive(content)}}}]}"""

    @Test fun parses_urgent_verdict() = runBlocking {
        server.enqueue(MockResponse().setBody(body(
            """{"urgent":true,"reason":"Mom asked to call now","confidence":0.9}""")))
        val v = service.classify(notif(), "Sleep")
        assertTrue(v.urgent); assertEquals(0.9, v.confidence, 0.001)
    }
    @Test fun fails_safe_to_urgent_on_http_error() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        val v = service.classify(notif(), "Sleep")
        assertTrue("must wake user when AI unavailable", v.urgent)
        assertEquals(0.0, v.confidence, 0.001)
    }
    @Test fun sends_bearer_auth_and_model() = runBlocking {
        server.enqueue(MockResponse().setBody(body("""{"urgent":false,"reason":"promo","confidence":0.8}""")))
        service.classify(notif(), "Sleep")
        val req = server.takeRequest()
        assertEquals("Bearer sk-test", req.getHeader("Authorization"))
        assertTrue(req.body.readUtf8().contains("gpt-4o-mini"))
    }
}
