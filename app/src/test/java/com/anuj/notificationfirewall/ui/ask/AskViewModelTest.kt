package com.anuj.notificationfirewall.ui.ask

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.anuj.notificationfirewall.ai.OpenAiClient
import com.anuj.notificationfirewall.ai.ask.AskService
import com.anuj.notificationfirewall.data.db.NfDatabase
import com.anuj.notificationfirewall.data.db.NotificationRecordEntity
import com.anuj.notificationfirewall.data.db.SenderBiasEntity
import com.anuj.notificationfirewall.data.db.dao.RawQueryDao
import com.anuj.notificationfirewall.data.prefs.SecurePrefs
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
class AskViewModelTest {

    private lateinit var context: Context
    private lateinit var server: MockWebServer
    private lateinit var db: NfDatabase
    private lateinit var prefs: SecurePrefs

    private val dayMs = 24L * 60 * 60 * 1000
    private val now = System.currentTimeMillis()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        server = MockWebServer().apply { start() }
        db = Room.inMemoryDatabaseBuilder(context, NfDatabase::class.java)
            .allowMainThreadQueries().build()
        prefs = SecurePrefs(context.getSharedPreferences("test-ask-vm", Context.MODE_PRIVATE))
        prefs.openAiKey = "sk-test"
    }

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
    }

    private fun viewModel() = AskViewModel(
        notificationDao = db.notificationDao(),
        senderBiasDao = db.senderBiasDao(),
        askService = AskService(
            OpenAiClient(server.url("/"), "test-key", OkHttpClient()),
            RawQueryDao(db),
            model = "gpt-4o-mini",
        ),
        securePrefs = prefs,
    )

    private fun chatResponse(content: String) = MockResponse().setBody(
        """{"choices":[{"message":{"content":${JSONObject.quote(content)}}}]}""",
    )

    private suspend fun seed(
        label: String = "Myntra",
        bucket: WallBucket = WallBucket.SILENCE,
        source: WallDecisionSource = WallDecisionSource.JEV,
        fromHuman: Float? = 0.02f,
        atMs: Long = now - dayMs,
        n: Int = 1,
    ) = repeat(n) {
        db.notificationDao().insert(
            NotificationRecordEntity(
                packageName = "com.$label", appLabel = label, title = "t", text = "b",
                timestampEpochMs = atMs, senderKey = label, contentShape = "shape$it",
                importanceScore = if (fromHuman == null) null else 1.2f, biasApplied = 0f,
                category = if (fromHuman == null) null else NotificationCategory.PROMOTION,
                isTimeSensitive = if (fromHuman == null) null else 0.1f,
                isFromHuman = fromHuman,
                needsAction = if (fromHuman == null) null else 0.05f,
                jevConfidence = if (fromHuman == null) null else 0.9f,
                decisionSource = source, bucket = bucket,
                pendingClassification = false, textPurgedAt = null, isRead = false,
            ),
        )
    }

    @Test
    fun noiseRatioIsTheShareOfNotificationsThatNeverRang() = runTest {
        seed(bucket = WallBucket.SILENCE, n = 3)
        seed(bucket = WallBucket.DROP, n = 1)
        seed(bucket = WallBucket.RING, n = 1)

        val vm = viewModel()
        vm.loadStats()

        assertEquals(5, vm.ui.value.stats.total)
        assertEquals(4, vm.ui.value.stats.keptQuiet)
        assertEquals(80, vm.ui.value.stats.noiseRatioPercent)
    }

    @Test
    fun topOffendersAreTheAppsSilencedMost() = runTest {
        seed(label = "Myntra", n = 3)
        seed(label = "Swiggy", n = 2)
        seed(label = "Bank", bucket = WallBucket.RING, n = 5)

        val vm = viewModel()
        vm.loadStats()

        assertEquals(
            listOf("Myntra" to 3, "Swiggy" to 2),
            vm.ui.value.stats.topOffenders.map { it.appLabel to it.count },
        )
    }

    @Test
    fun theHumanShareCountsOnlyRowsThatActuallyCarryAVerdict() = runTest {
        seed(label = "Mum", fromHuman = 0.9f, n = 2)
        seed(label = "Myntra", fromHuman = 0.02f, n = 1)
        // EXPIRED, PENDING and LEGACY have no verdict: their verdict columns
        // are NULL by design and must not be counted as "not a human".
        seed(label = "Old", source = WallDecisionSource.EXPIRED, fromHuman = null, n = 4)
        seed(label = "Wait", source = WallDecisionSource.PENDING, fromHuman = null, n = 4)
        seed(label = "Ancient", source = WallDecisionSource.LEGACY, fromHuman = null, n = 4)

        val vm = viewModel()
        vm.loadStats()

        assertEquals(3, vm.ui.value.stats.judged)
        assertEquals(2, vm.ui.value.stats.fromHuman)
        assertEquals(67, vm.ui.value.stats.humanSharePercent)
    }

    @Test
    fun unjudgedRowsStillCountAsArrivals() = runTest {
        // They were still silenced; the user still did not see them.
        seed(source = WallDecisionSource.EXPIRED, fromHuman = null, n = 2)
        seed(source = WallDecisionSource.LEGACY, fromHuman = null, n = 2)

        val vm = viewModel()
        vm.loadStats()

        assertEquals(4, vm.ui.value.stats.total)
        assertEquals(100, vm.ui.value.stats.noiseRatioPercent)
        assertNull("no verdicts means no honest human share", vm.ui.value.stats.humanSharePercent)
    }

    @Test
    fun byHourHasABarPerHourOfTheLocalDay() = runTest {
        val at = now - dayMs
        seed(atMs = at, n = 3)

        val vm = viewModel()
        vm.loadStats()

        val hour = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).hour
        assertEquals(24, vm.ui.value.stats.byHour.size)
        assertEquals(3, vm.ui.value.stats.byHour[hour])
        assertEquals(3, vm.ui.value.stats.byHour.sum())
    }

    @Test
    fun correctionRateDividesByWhatThisAppItselfJudged() = runTest {
        seed(source = WallDecisionSource.JEV, n = 6)
        seed(source = WallDecisionSource.CACHE, n = 4)
        // Neither of these was judged by the current model, so neither belongs
        // in the denominator of the app's own honesty metric.
        seed(source = WallDecisionSource.LEGACY, fromHuman = null, n = 20)
        seed(source = WallDecisionSource.EXPIRED, fromHuman = null, n = 20)
        db.senderBiasDao().upsert(
            SenderBiasEntity("com.Myntra", "Myntra", 0.25f, 3, now),
        )

        val vm = viewModel()
        vm.loadStats()

        assertEquals(10, vm.ui.value.stats.judgedByThisApp)
        assertEquals(3, vm.ui.value.stats.corrections)
        assertEquals(30, vm.ui.value.stats.correctionRatePercent)
    }

    @Test
    fun emptyHistoryProducesNoFabricatedPercentages() = runTest {
        val vm = viewModel()
        vm.loadStats()

        assertNull(vm.ui.value.stats.noiseRatioPercent)
        assertNull(vm.ui.value.stats.humanSharePercent)
        assertNull(vm.ui.value.stats.correctionRatePercent)
        assertTrue(vm.ui.value.stats.topOffenders.isEmpty())
    }

    @Test
    fun anAnsweredQuestionShowsTheProseAndTheQueryItRan() = runTest {
        seed(n = 2)
        server.enqueue(chatResponse("""{"sql": "SELECT COUNT(*) AS c FROM notifications LIMIT 1"}"""))
        server.enqueue(chatResponse("""{"answer": "Two notifications."}"""))

        val vm = viewModel()
        vm.sendNow("how many?")

        val messages = vm.ui.value.messages
        assertEquals(2, messages.size)
        assertEquals("how many?", messages[0].text)
        assertTrue(messages[0].fromUser)
        assertEquals("Two notifications.", messages[1].text)
        assertEquals("SELECT COUNT(*) AS c FROM notifications LIMIT 1", messages[1].sql)
        assertEquals(listOf("SELECT COUNT(*) AS c FROM notifications LIMIT 1"), messages[1].queries)
        assertFalse(vm.ui.value.sending)
    }

    @Test
    fun allowContentResetsToOffAfterEverySend() = runTest {
        seed(n = 1)
        server.enqueue(chatResponse("""{"sql": "SELECT COUNT(*) AS c FROM notifications LIMIT 1"}"""))
        server.enqueue(chatResponse("""{"answer": "One."}"""))

        val vm = viewModel()
        vm.setAllowContent(true)
        assertTrue(vm.ui.value.allowContent)

        vm.sendNow("how many?")

        assertFalse("content opt-in must not persist to the next question", vm.ui.value.allowContent)
    }

    @Test
    fun aRefusalIsShownWithItsReasonAndTheRejectedSql() = runTest {
        server.enqueue(chatResponse("""{"sql": "SELECT title FROM notifications LIMIT 10"}"""))

        val vm = viewModel()
        vm.sendNow("what did they say?")

        val answer = vm.ui.value.messages.last()
        assertTrue(answer.refused)
        assertTrue(answer.text.isNotBlank())
        assertEquals("SELECT title FROM notifications LIMIT 10", answer.sql)
        assertEquals("a refusal must not trigger a phrasing call", 1, server.requestCount)
    }

    @Test
    fun aModelFailureIsShownRatherThanASilentNothing() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))

        val vm = viewModel()
        vm.sendNow("anything")

        assertTrue(vm.ui.value.messages.last().text.isNotBlank())
        assertFalse(vm.ui.value.sending)
    }

    @Test
    fun aMissingApiKeyIsReportedSoTheChatCanStepAside() = runTest {
        prefs.openAiKey = null

        val vm = viewModel()
        vm.loadStats()

        assertFalse(vm.ui.value.hasKey)
    }

    @Test
    fun aKeyAddedWhileTheScreenWasOffStageUnHidesTheChat() = runTest {
        // The Ask back-stack entry and its ViewModel survive navigating to the
        // Keys screen and back, so reading hasKey once in the initialiser would
        // leave the "add a key" card up forever after the user added one.
        prefs.openAiKey = null
        val vm = viewModel()
        vm.loadStats()
        assertFalse(vm.ui.value.hasKey)

        prefs.openAiKey = "sk-set-in-settings"
        vm.loadStats()

        assertTrue("returning from Settings with a key must reveal the composer", vm.ui.value.hasKey)
    }

    @Test
    fun blankQuestionsAreIgnored() = runTest {
        val vm = viewModel()
        vm.sendNow("   ")

        assertTrue(vm.ui.value.messages.isEmpty())
        assertEquals(0, server.requestCount)
    }
}
