package com.anuj.notificationfirewall.ai

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OpenAiDigestServiceTest {
    private lateinit var server: MockWebServer

    private fun serviceWithKey(key: String) =
        OpenAiDigestService(OpenAiClient(server.url("/v1/"), key, OkHttpClient()))

    @Before fun setup() {
        server = MockWebServer(); server.start()
    }
    @After fun teardown() = server.shutdown()

    private fun data(
        rang: Int = 9,
        silenced: Int = 312,
        dropped: Int = 4,
        topOffender: Pair<String, Int>? = "Myntra" to 47,
        worthALook: List<String> = listOf("Landlord: rent due", "Dentist: reschedule?"),
    ) = DigestData(rang = rang, silenced = silenced, dropped = dropped, topOffender = topOffender, worthALook = worthALook)

    @Test
    fun no_api_key_never_makes_a_network_call() = runBlocking {
        val text = serviceWithKey("").summarise(data())
        assertEquals(0, server.requestCount)
        assertEquals("Yesterday: 312 silenced, 9 let through. Myntra led with 47. 4 dropped.", text)
    }

    @Test
    fun no_api_key_with_no_offender_still_reports_the_counts() = runBlocking {
        val text = serviceWithKey("").summarise(data(topOffender = null, dropped = 0))
        assertEquals("Yesterday: 312 silenced, 9 let through.", text)
    }

    @Test
    fun zero_dropped_matches_the_brief_verbatim_sentence() = runBlocking {
        val text = serviceWithKey("").summarise(data(dropped = 0))
        assertEquals("Yesterday: 312 silenced, 9 let through. Myntra led with 47.", text)
    }

    @Test
    fun returns_model_prose_when_a_key_is_configured() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"choices":[{"message":{"content":"Quiet day: 312 silenced, Myntra led."}}]}""",
            ),
        )
        val text = serviceWithKey("sk-test").summarise(data())
        assertEquals("Quiet day: 312 silenced, Myntra led.", text)
    }

    @Test
    fun http_error_falls_back_to_the_local_summary() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        val text = serviceWithKey("sk-test").summarise(data())
        assertEquals("Yesterday: 312 silenced, 9 let through. Myntra led with 47. 4 dropped.", text)
    }

    /**
     * The privacy-critical assertion: worthALook holds sender names and
     * titles (content, per Task 6's provenance rule), and must never appear
     * in the request this class sends to OpenAI, even though the same
     * DigestData reaches this class carrying it (DigestWorker needs it for
     * the on-device notification body).
     */
    @Test
    fun never_sends_worthALook_content_to_the_network() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"choices":[{"message":{"content":"ok"}}]}"""),
        )
        serviceWithKey("sk-test").summarise(data())
        val sentBody = server.takeRequest().body.readUtf8()
        assertFalse(sentBody.contains("Landlord"))
        assertFalse(sentBody.contains("Dentist"))
        assertFalse(sentBody.contains("rent due"))
        assertTrue(sentBody.contains("312"))
        assertTrue(sentBody.contains("Myntra"))
    }
}
