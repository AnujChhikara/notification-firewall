package com.anuj.notificationfirewall.ai.jev

import com.anuj.notificationfirewall.domain.wall.JevException
import com.anuj.notificationfirewall.domain.wall.JevState
import com.anuj.notificationfirewall.domain.wall.NotificationCategory
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class JevClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: JevClient

    private val state = JevState(
        app = "Myntra",
        channel = "offers",
        title = "FLAT 70% OFF",
        text = "Shop now",
        arrivedAtLocal = "23:41",
        isReplyCapable = false,
        isFromContact = false,
    )

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        client = JevClient(server.url("/"), "test-key", OkHttpClient())
    }

    @After
    fun tearDown() = server.shutdown()

    private fun okBody(score: Double = 0.4, confidence: Double = 0.87) = """
        {
          "model": "jev-latest",
          "answers": {
            "importance": {
              "type": "score",
              "score": $score,
              "legend": {"0":"noise","1":"low","2":"routine","3":"matters","4":"critical"},
              "probabilities": {"0":0.6,"1":0.3,"2":0.1,"3":0.0,"4":0.0},
              "confidence": $confidence
            },
            "category": {
              "type": "choice",
              "choice": "promotion",
              "probabilities": {"promotion":0.95,"other":0.05},
              "confidence": 0.9
            },
            "is_time_sensitive": {"type":"noul","noul":0.12},
            "is_from_human": {"type":"noul","noul":0.03},
            "needs_action": {"type":"noul","noul":0.05}
          },
          "usage": {"input_tokens": 62, "output_tokens": 40}
        }
    """.trimIndent()

    @Test
    fun parsesVerdict_andShiftsScoreToOneBasedScale() = runTest {
        server.enqueue(MockResponse().setBody(okBody(score = 0.4)))

        val verdict = client.classify(state)

        assertEquals(1.4f, verdict.importance, 0.001f)
        assertEquals(NotificationCategory.PROMOTION, verdict.category)
        assertEquals(0.12f, verdict.isTimeSensitive, 0.001f)
        assertEquals(0.03f, verdict.isFromHuman, 0.001f)
        assertEquals(0.05f, verdict.needsAction, 0.001f)
        assertEquals(0.87f, verdict.confidence, 0.001f)
    }

    @Test
    fun topOfScaleMapsToFive() = runTest {
        server.enqueue(MockResponse().setBody(okBody(score = 4.0)))
        assertEquals(5.0f, client.classify(state).importance, 0.001f)
    }

    @Test
    fun sendsBearerTokenAndModelAndAllFiveQuestions() = runTest {
        server.enqueue(MockResponse().setBody(okBody()))
        client.classify(state)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/systemone", request.path)
        assertEquals("Bearer test-key", request.getHeader("Authorization"))

        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("jev-latest", body["model"]!!.jsonPrimitive.content)
        val questions = body["questions"]!!.jsonObject
        assertEquals(
            setOf("importance", "category", "is_time_sensitive", "is_from_human", "needs_action"),
            questions.keys,
        )
        assertEquals("score", questions["importance"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("choice", questions["category"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("noul", questions["needs_action"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun sendsStateFieldsIncludingLocalSignals() = runTest {
        server.enqueue(MockResponse().setBody(okBody()))
        client.classify(state)

        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val sent = body["state"]!!.jsonObject
        assertEquals("Myntra", sent["app"]!!.jsonPrimitive.content)
        assertEquals("FLAT 70% OFF", sent["title"]!!.jsonPrimitive.content)
        assertEquals("23:41", sent["arrived_at_local"]!!.jsonPrimitive.content)
        assertEquals("false", sent["is_reply_capable"]!!.jsonPrimitive.content)
        assertEquals("false", sent["is_from_contact"]!!.jsonPrimitive.content)
    }

    @Test
    fun unknownCategoryFallsBackToOther() = runTest {
        server.enqueue(
            MockResponse().setBody(okBody().replace("\"choice\": \"promotion\"", "\"choice\": \"weather\"")),
        )
        assertEquals(NotificationCategory.OTHER, client.classify(state).category)
    }

    @Test
    fun unauthorizedThrowsJevException() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"bad key"}"""))
        val e = assertThrows(JevException::class.java) { kotlinx.coroutines.runBlocking { client.classify(state) } }
        assertTrue(e.message!!.contains("401"))
    }

    @Test
    fun serverErrorThrowsJevException() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))
        assertThrows(JevException::class.java) { kotlinx.coroutines.runBlocking { client.classify(state) } }
    }

    @Test
    fun malformedBodyThrowsJevException() = runTest {
        server.enqueue(MockResponse().setBody("""{"answers":{}}"""))
        assertThrows(JevException::class.java) { kotlinx.coroutines.runBlocking { client.classify(state) } }
    }
}
