package com.anuj.notificationfirewall.ai.ask

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.anuj.notificationfirewall.ai.OpenAiClient
import com.anuj.notificationfirewall.data.db.NfDatabase
import com.anuj.notificationfirewall.data.db.NotificationRecordEntity
import com.anuj.notificationfirewall.data.db.dao.RawQueryDao
import com.anuj.notificationfirewall.domain.wall.NotificationCategory
import com.anuj.notificationfirewall.domain.wall.WallBucket
import com.anuj.notificationfirewall.domain.wall.WallDecisionSource
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AskServiceTest {

    private lateinit var server: MockWebServer
    private lateinit var db: NfDatabase
    private lateinit var service: AskService

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            NfDatabase::class.java,
        ).allowMainThreadQueries().build()
        service = AskService(
            OpenAiClient(server.url("/"), "test-key", OkHttpClient()),
            RawQueryDao(db),
            model = "gpt-4o-mini",
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
    }

    private fun chatResponse(content: String) = MockResponse().setBody(
        """{"choices":[{"message":{"content":${JSONObject.quote(content)}}}]}""",
    )

    private suspend fun seed(
        pkg: String,
        label: String,
        n: Int,
        title: String = "t",
        text: String = "b",
    ) = repeat(n) {
        db.notificationDao().insert(
            NotificationRecordEntity(
                packageName = pkg, appLabel = label, title = title, text = text,
                timestampEpochMs = 1_700_000_000_000L + it, senderKey = label,
                contentShape = "shape$it", importanceScore = 1.2f, biasApplied = 0f,
                category = NotificationCategory.PROMOTION, isTimeSensitive = 0.1f,
                isFromHuman = 0.02f, needsAction = 0.05f, jevConfidence = 0.9f,
                decisionSource = WallDecisionSource.JEV, bucket = WallBucket.SILENCE,
                pendingClassification = false, textPurgedAt = null, isRead = false,
            ),
        )
    }

    /** Every `content` string in the request body's `messages` array. */
    private fun messageContents(body: String): List<String> {
        val messages = JSONObject(body).getJSONArray("messages")
        return (0 until messages.length()).map { messages.getJSONObject(it).getString("content") }
    }

    @Test
    fun runsTheGeneratedQueryAndPhrasesTheResult() = runTest {
        seed("com.myntra", "Myntra", 3)
        server.enqueue(
            chatResponse("SELECT appLabel, COUNT(*) AS c FROM notifications GROUP BY appLabel LIMIT 10"),
        )
        server.enqueue(chatResponse("Myntra sent you 3 notifications."))

        val outcome = service.ask("who spams me most?", allowContent = false)

        assertTrue(outcome.toString(), outcome is AskOutcome.Answered)
        assertTrue((outcome as AskOutcome.Answered).text.contains("Myntra"))
        assertTrue(outcome.result.rows.isNotEmpty())
    }

    @Test
    fun neverSendsNotificationContentInTheGenerationRequest() = runTest {
        seed("com.myntra", "Myntra", 2, title = "ZEBRAFISHTITLE", text = "ZEBRAFISHBODY")
        server.enqueue(chatResponse("SELECT COUNT(*) AS c FROM notifications LIMIT 1"))
        server.enqueue(chatResponse("Two."))

        service.ask("how many?", allowContent = false)

        val generation = messageContents(server.takeRequest().body.readUtf8())
        assertTrue("the schema is sent", generation.any { it.contains("CREATE TABLE notifications") })
        generation.forEach {
            assertFalse("a notification title reached the model: $it", it.contains("ZEBRAFISHTITLE"))
            assertFalse("a notification body reached the model: $it", it.contains("ZEBRAFISHBODY"))
        }
    }

    @Test
    fun neverSendsNotificationContentInThePhrasingRequest() = runTest {
        seed("com.myntra", "Myntra", 2, title = "ZEBRAFISHTITLE", text = "ZEBRAFISHBODY")
        server.enqueue(
            chatResponse("SELECT appLabel, COUNT(*) AS c FROM notifications GROUP BY appLabel LIMIT 10"),
        )
        server.enqueue(chatResponse("Myntra, twice."))

        service.ask("who spams me most?", allowContent = false)

        server.takeRequest() // generation
        val phrasing = messageContents(server.takeRequest().body.readUtf8())
        phrasing.forEach {
            assertFalse("a notification title reached the model: $it", it.contains("ZEBRAFISHTITLE"))
            assertFalse("a notification body reached the model: $it", it.contains("ZEBRAFISHBODY"))
        }
    }

    @Test
    fun rejectsAWriteStatementWithoutTouchingTheDatabase() = runTest {
        seed("com.myntra", "Myntra", 2)
        server.enqueue(chatResponse("DELETE FROM notifications"))

        val outcome = service.ask("clear my history", allowContent = false)

        assertTrue(outcome is AskOutcome.Refused)
        assertTrue(db.notificationDao().recordsBetween(0, Long.MAX_VALUE).size == 2)
    }

    @Test
    fun rejectsAQueryTouchingContentWhenContentIsNotAllowed() = runTest {
        server.enqueue(chatResponse("SELECT title FROM notifications LIMIT 10"))

        val outcome = service.ask("what did they say?", allowContent = false)

        assertTrue(outcome is AskOutcome.Refused)
    }

    @Test
    fun refusalDoesNotIssueASecondModelCall() = runTest {
        server.enqueue(chatResponse("DROP TABLE notifications"))

        service.ask("drop everything", allowContent = false)

        assertTrue("only the generation call should have been made", server.requestCount == 1)
    }

    @Test
    fun surfacesAModelFailureRatherThanGuessing() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))

        assertTrue(service.ask("anything", allowContent = false) is AskOutcome.Failed)
    }

    @Test
    fun emptyResultIsStillAnswered() = runTest {
        server.enqueue(chatResponse("SELECT COUNT(*) AS c FROM notifications WHERE packageName = 'nope' LIMIT 1"))
        server.enqueue(chatResponse("Nothing matched."))

        val outcome = service.ask("anything from nope?", allowContent = false)

        assertTrue(outcome is AskOutcome.Answered)
    }

    @Test
    fun aStarProjectionIsRefusedAndNeverPhrased() = runTest {
        seed("com.myntra", "Myntra", 2, title = "ZEBRAFISHTITLE", text = "ZEBRAFISHBODY")
        server.enqueue(chatResponse("SELECT * FROM notifications LIMIT 10"))

        val outcome = service.ask("show me everything", allowContent = false)

        assertTrue(outcome is AskOutcome.Refused)
        assertEquals("no phrasing call may follow a refusal", 1, server.requestCount)
    }

    @Test
    fun anAliasedContentColumnIsRefusedBeforeItCanBePhrased() = runTest {
        // The cursor's column name here is "whatever", so the execution gate's
        // column check cannot see this one -- SqlValidator can, which is why
        // both gates exist and why neither is optional.
        seed("com.myntra", "Myntra", 1, title = "ZEBRAFISHTITLE", text = "ZEBRAFISHBODY")
        server.enqueue(chatResponse("SELECT title AS whatever FROM notifications LIMIT 5"))

        val outcome = service.ask("what did they say?", allowContent = false)

        assertTrue(outcome is AskOutcome.Refused)
        assertEquals("nothing may be phrased after a refusal", 1, server.requestCount)
    }

    @Test
    fun groupingBySenderKeyIsRefusedBecauseThatColumnIsTheTitle() = runTest {
        // The exact shape "who messages me most" produces. senderKey IS the
        // title string, so phrasing this result would have sent raw titles.
        seed("com.myntra", "Myntra", 2, title = "ZEBRAFISHTITLE", text = "ZEBRAFISHBODY")
        server.enqueue(
            chatResponse(
                "SELECT senderKey, COUNT(*) AS c FROM notifications GROUP BY senderKey " +
                    "ORDER BY c DESC LIMIT 20",
            ),
        )

        val outcome = service.ask("who messages me most?", allowContent = false)

        assertTrue(outcome.toString(), outcome is AskOutcome.Refused)
        assertEquals("nothing may be phrased after a refusal", 1, server.requestCount)
    }

    @Test
    fun theSchemaSentToTheModelMarksEverySenderDerivedColumnAsContent() = runTest {
        seed("com.myntra", "Myntra", 1)
        server.enqueue(chatResponse("SELECT COUNT(*) AS c FROM notifications LIMIT 1"))
        server.enqueue(chatResponse("One."))

        service.ask("how many?", allowContent = false)

        val system = messageContents(server.takeRequest().body.readUtf8()).first()
        assertTrue("senderKey must be marked as content", system.contains("senderKey TEXT,                -- CONTENT"))
        assertTrue("contentShape must be marked as content", system.contains("contentShape TEXT NOT NULL,    -- CONTENT"))
        assertTrue(
            "the model must be steered to appLabel instead",
            system.contains("use appLabel or"),
        )
    }

    @Test
    fun contentIsSentOnlyWhenTheUserOptedInForThatQuestion() = runTest {
        seed("com.myntra", "Myntra", 1, title = "ZEBRAFISHTITLE", text = "ZEBRAFISHBODY")
        server.enqueue(chatResponse("SELECT title FROM notifications LIMIT 10"))
        server.enqueue(chatResponse("They said hello."))

        val outcome = service.ask("what did they say?", allowContent = true)

        assertTrue(outcome.toString(), outcome is AskOutcome.Answered)
        server.takeRequest() // generation
        val phrasing = messageContents(server.takeRequest().body.readUtf8())
        assertTrue(
            "opting in must actually send the rows",
            phrasing.any { it.contains("ZEBRAFISHTITLE") },
        )
    }

    @Test
    fun agentRunsTwoQueriesThenAnswersFromBoth() = runTest {
        seed("com.myntra", "Myntra", 3)
        server.enqueue(chatResponse("""{"sql": "SELECT COUNT(*) AS c FROM notifications LIMIT 1"}"""))
        server.enqueue(
            chatResponse(
                """{"sql": "SELECT appLabel, COUNT(*) AS c FROM notifications GROUP BY appLabel LIMIT 10"}""",
            ),
        )
        server.enqueue(chatResponse("""{"answer": "Myntra sent all 3 notifications."}"""))

        val outcome = service.askDeep("who spams me most?", allowContent = false)

        assertTrue(outcome.toString(), outcome is AskOutcome.Answered)
        outcome as AskOutcome.Answered
        assertTrue(outcome.text.contains("Myntra"))
        assertEquals(2, outcome.steps.size)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun agentStopsWhenAQueryIsRefused() = runTest {
        seed("com.myntra", "Myntra", 1)
        server.enqueue(chatResponse("""{"sql": "SELECT title FROM notifications LIMIT 10"}"""))

        val outcome = service.askDeep("what did they say?", allowContent = false)

        assertTrue(outcome is AskOutcome.Refused)
        assertEquals("a refusal must end the loop, not invite a retry", 1, server.requestCount)
    }

    @Test
    fun agentGreetsWithoutQuerying() = runTest {
        seed("com.myntra", "Myntra", 1)
        server.enqueue(chatResponse("""{"answer": "Hello! Ask me about your notifications."}"""))

        val outcome = service.askDeep("hi", allowContent = false)

        assertTrue(outcome.toString(), outcome is AskOutcome.Answered)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun agentIsNudgedToQueryForDataQuestions() = runTest {
        seed("com.myntra", "Myntra", 3)
        server.enqueue(chatResponse("""{"answer": "Probably Myntra."}"""))
        server.enqueue(chatResponse("""{"sql": "SELECT COUNT(*) AS c FROM notifications LIMIT 1"}"""))
        server.enqueue(chatResponse("""{"answer": "3 notifications, all Myntra."}"""))

        val outcome = service.askDeep("who spams me most?", allowContent = false)

        assertTrue(outcome.toString(), outcome is AskOutcome.Answered)
        outcome as AskOutcome.Answered
        assertEquals(1, outcome.steps.size)
        assertEquals(3, server.requestCount)
    }
}
