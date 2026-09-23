package com.anuj.notificationfirewall.domain.wall

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.anuj.notificationfirewall.data.db.NfDatabase
import com.anuj.notificationfirewall.data.prefs.WallSettings
import com.anuj.notificationfirewall.domain.model.IncomingNotification
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.ZoneId

/** Records every call so tests can assert Jev was NOT consulted. */
private class FakeJev(
    var verdict: JevVerdict? = null,
    var error: Exception? = null,
) : JevApi {
    val calls = mutableListOf<JevState>()
    override suspend fun classify(state: JevState): JevVerdict {
        calls += state
        error?.let { throw it }
        return verdict ?: error("FakeJev has no verdict configured")
    }
}

@RunWith(RobolectricTestRunner::class)
class WallPipelineTest {

    private lateinit var db: NfDatabase
    private lateinit var jev: FakeJev
    private lateinit var settings: WallSettings
    private lateinit var overrides: OverrideStore
    private lateinit var bias: BiasStore
    private lateinit var cache: VerdictCache
    private lateinit var pipeline: WallPipeline

    private fun notification(
        pkg: String = "com.myntra",
        app: String = "Myntra",
        title: String = "FLAT 70% OFF",
        text: String = "Shop now",
        sender: String = "Myntra",
    ) = IncomingNotification(
        packageName = pkg,
        appLabel = app,
        title = title,
        text = text,
        senderKey = sender,
        isFavoriteContact = false,
        emailFromDomain = null,
        postedAt = Instant.ofEpochMilli(1_700_000_000_000L),
    )

    private fun verdict(
        importance: Float,
        fromHuman: Float = 0.03f,
        confidence: Float = 0.9f,
        category: NotificationCategory = NotificationCategory.PROMOTION,
    ) = JevVerdict(importance, category, 0.1f, fromHuman, 0.05f, confidence)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, NfDatabase::class.java)
            .allowMainThreadQueries().build()
        jev = FakeJev()
        settings = WallSettings(context.getSharedPreferences("test-wall", Context.MODE_PRIVATE))
        settings.threshold = 4.0f
        settings.otpFastPathEnabled = true
        overrides = OverrideStore(db.overrideDao())
        bias = BiasStore(db.senderBiasDao()) { 1_700_000_000_000L }
        cache = VerdictCache(db.verdictCacheDao()) { 1_700_000_000_000L }
        pipeline = WallPipeline(overrides, cache, bias, jev, settings, ZoneId.of("Asia/Kolkata"))
    }

    @After
    fun tearDown() = db.close()

    private suspend fun decide(n: IncomingNotification = notification()) =
        pipeline.decide(n, channelId = null, isReplyCapable = false)

    // ── OTP fast-path ────────────────────────────────────────────────────────

    @Test
    fun otpRingsWithoutConsultingJev() = runTest {
        val d = decide(notification(title = "HDFC Bank", text = "123456 is your OTP. Do not share."))

        assertEquals(WallBucket.RING, d.bucket)
        assertEquals(WallDecisionSource.OTP, d.source)
        assertTrue("Jev must not be called for an OTP", jev.calls.isEmpty())
    }

    @Test
    fun otpRingsEvenWhenTheSenderIsBlocked() = runTest {
        overrides.add(OverrideKind.BLOCK, "com.myntra", null, "Myntra", OverrideSource.MANUAL)

        val d = decide(notification(text = "Your OTP is 445566 for your Myntra order"))

        assertEquals(WallBucket.RING, d.bucket)
        assertEquals(WallDecisionSource.OTP, d.source)
    }

    @Test
    fun otpFastPathCanBeDisabled() = runTest {
        settings.otpFastPathEnabled = false
        jev.verdict = verdict(importance = 4.8f)

        val d = decide(notification(text = "123456 is your OTP"))

        assertEquals(WallDecisionSource.JEV, d.source)
        assertEquals(1, jev.calls.size)
    }

    // ── Overrides ────────────────────────────────────────────────────────────

    @Test
    fun blockedSenderIsDroppedWithoutConsultingJev() = runTest {
        overrides.add(OverrideKind.BLOCK, "com.myntra", null, "Myntra", OverrideSource.SWIPE)

        val d = decide()

        assertEquals(WallBucket.DROP, d.bucket)
        assertEquals(WallDecisionSource.BLOCK, d.source)
        assertTrue(jev.calls.isEmpty())
    }

    @Test
    fun vipSenderRingsWithoutConsultingJev() = runTest {
        overrides.add(OverrideKind.VIP, "com.whatsapp", "Mom", "Mom", OverrideSource.MANUAL)

        val d = decide(notification("com.whatsapp", "WhatsApp", "Mom", "anything", "Mom"))

        assertEquals(WallBucket.RING, d.bucket)
        assertEquals(WallDecisionSource.VIP, d.source)
        assertTrue(jev.calls.isEmpty())
    }

    // ── Jev and the threshold ────────────────────────────────────────────────

    @Test
    fun lowImportanceIsSilenced() = runTest {
        jev.verdict = verdict(importance = 1.4f)
        val d = decide()

        assertEquals(WallBucket.SILENCE, d.bucket)
        assertEquals(WallDecisionSource.JEV, d.source)
        assertEquals(1.4f, d.verdict!!.importance, 0.001f)
    }

    @Test
    fun importanceAtThresholdRings() = runTest {
        jev.verdict = verdict(importance = 4.0f)
        assertEquals(WallBucket.RING, decide().bucket)
    }

    @Test
    fun importanceJustBelowThresholdIsSilenced() = runTest {
        jev.verdict = verdict(importance = 3.99f)
        assertEquals(WallBucket.SILENCE, decide().bucket)
    }

    @Test
    fun jevNeverProducesDrop() = runTest {
        jev.verdict = verdict(importance = 1.0f)
        assertEquals(WallBucket.SILENCE, decide().bucket)
    }

    @Test
    fun relaxedThresholdLetsMoreThrough() = runTest {
        settings.threshold = 2.0f
        jev.verdict = verdict(importance = 2.5f)
        assertEquals(WallBucket.RING, decide().bucket)
    }

    @Test
    fun jevReceivesTheLocalSignals() = runTest {
        jev.verdict = verdict(importance = 2f)
        pipeline.decide(notification(), channelId = "offers", isReplyCapable = true)

        val state = jev.calls.single()
        assertEquals("Myntra", state.app)
        assertEquals("offers", state.channel)
        assertEquals(true, state.isReplyCapable)
        assertEquals("03:43", state.arrivedAtLocal)
    }

    // ── Cache ────────────────────────────────────────────────────────────────

    @Test
    fun secondIdenticalMachineNotificationUsesTheCache() = runTest {
        jev.verdict = verdict(importance = 1.4f)
        decide()

        val d = decide(notification(title = "FLAT 50% OFF", text = "Shop now"))

        assertEquals(WallDecisionSource.CACHE, d.source)
        assertEquals(1, jev.calls.size)
        assertEquals(WallBucket.SILENCE, d.bucket)
    }

    @Test
    fun humanSenderIsAlwaysReclassified() = runTest {
        jev.verdict = verdict(importance = 4.5f, fromHuman = 0.95f, category = NotificationCategory.PERSONAL_MESSAGE)
        val mom = notification("com.whatsapp", "WhatsApp", "Mom", "Reached home safely", "Mom")

        decide(mom)
        decide(mom)

        assertEquals("a human's message must never be cached", 2, jev.calls.size)
    }

    @Test
    fun promoFromAHumanSenderDoesNotInheritTheRealMessagesVerdict() = runTest {
        val real = notification("com.whatsapp", "WhatsApp", "Mom", "Reached home safely", "Mom")
        val promo = notification("com.whatsapp", "WhatsApp", "Mom", "Check out 50% off at Croma!", "Mom")

        jev.verdict = verdict(importance = 4.5f, fromHuman = 0.95f)
        assertEquals(WallBucket.RING, decide(real).bucket)

        jev.verdict = verdict(importance = 1.2f, fromHuman = 0.9f)
        assertEquals(WallBucket.SILENCE, decide(promo).bucket)
    }

    @Test
    fun lowConfidenceVerdictIsNotCached() = runTest {
        jev.verdict = verdict(importance = 2f, confidence = 0.4f)
        decide()
        decide()

        assertEquals(2, jev.calls.size)
    }

    // ── Bias ─────────────────────────────────────────────────────────────────

    @Test
    fun negativeBiasSilencesABorderlineNotification() = runTest {
        repeat(3) { bias.record("com.myntra", "Myntra", Correction.SHOULD_HAVE_BEEN_SILENT) }
        jev.verdict = verdict(importance = 4.5f)

        val d = decide()

        assertEquals(WallBucket.SILENCE, d.bucket)
        assertEquals(-0.75f, d.biasApplied, 0.001f)
    }

    @Test
    fun maximumNegativeBiasCannotSuppressACriticalNotification() = runTest {
        repeat(10) { bias.record("com.myntra", "Myntra", Correction.SHOULD_HAVE_BEEN_SILENT) }
        jev.verdict = verdict(importance = 5.0f)

        assertEquals(
            "bias tunes, it never overrides",
            WallBucket.RING,
            decide().bucket,
        )
    }

    @Test
    fun positiveBiasRingsABorderlineNotification() = runTest {
        repeat(3) { bias.record("com.slack", "Boss", Correction.SHOULD_HAVE_RUNG) }
        jev.verdict = verdict(importance = 3.5f, category = NotificationCategory.WORK)

        assertEquals(WallBucket.RING, decide(notification("com.slack", "Slack", "Boss", "ping", "Boss")).bucket)
    }

    @Test
    fun biasAppliesToCachedVerdictsToo() = runTest {
        jev.verdict = verdict(importance = 4.5f)
        decide()
        repeat(3) { bias.record("com.myntra", "Myntra", Correction.SHOULD_HAVE_BEEN_SILENT) }

        val d = decide(notification(title = "FLAT 50% OFF"))

        assertEquals(WallDecisionSource.CACHE, d.source)
        assertEquals(WallBucket.SILENCE, d.bucket)
    }

    // ── Failure handling ─────────────────────────────────────────────────────

    @Test
    fun jevFailureSilencesAndMarksPending() = runTest {
        jev.error = JevException("offline")

        val d = decide()

        assertEquals(WallBucket.SILENCE, d.bucket)
        assertEquals(WallDecisionSource.PENDING, d.source)
        assertTrue(d.pendingClassification)
        assertNull(d.verdict)
    }

    @Test
    fun jevFailureStillProducesAContentShapeForLaterRetry() = runTest {
        jev.error = JevException("offline")
        assertEquals(32, decide().contentShape.length)
    }

    @Test
    fun aSuccessfulDecisionIsNotPending() = runTest {
        jev.verdict = verdict(importance = 2f)
        val d = decide()
        assertTrue(!d.pendingClassification)
        assertNotNull(d.verdict)
    }

    // ── Call fast-path ───────────────────────────────────────────────────

    @Test
    fun huddleInviteRingsWithoutConsultingJev() = runTest {
        val d = decide(
            notification(
                pkg = "com.Slack",
                app = "Slack",
                title = "Anmol Rishi",
                text = "Anmol Rishi invited you to a huddle",
                sender = "Anmol Rishi",
            ),
        )

        assertEquals(WallBucket.RING, d.bucket)
        assertEquals(WallDecisionSource.CALL, d.source)
        assertTrue("Jev must not be called for a live invite", jev.calls.isEmpty())
    }

    @Test
    fun blockedSenderStaysBlockedDespiteCallPhrasing() = runTest {
        // An explicit instruction outranks the heuristic: the call detector
        // sits below block/VIP on purpose.
        overrides.add(OverrideKind.BLOCK, "com.Slack", null, "Slack", OverrideSource.MANUAL)

        val d = decide(
            notification(
                pkg = "com.Slack",
                app = "Slack",
                title = "Anmol Rishi",
                text = "Anmol Rishi invited you to a huddle",
                sender = "Anmol Rishi",
            ),
        )

        assertEquals(WallBucket.DROP, d.bucket)
        assertEquals(WallDecisionSource.BLOCK, d.source)
    }

    // ── Personal-question bar ──────────────────────────────────────────

    @Test
    fun personalQuestionRingsBelowTheGlobalBar() = runTest {
        // Verbatim miss from the Sept 2026 sample: 3.44 vs a ~4.05 bar.
        jev.verdict = verdict(importance = 3.44f, category = NotificationCategory.PERSONAL_MESSAGE)

        val d = decide(
            notification(
                pkg = "com.whatsapp",
                app = "WhatsApp",
                title = "Anmol Rishi",
                text = "Can you come to gurgaon?",
                sender = "Anmol Rishi",
            ),
        )

        assertEquals(WallBucket.RING, d.bucket)
        assertEquals(WallDecisionSource.JEV, d.source)
    }

    @Test
    fun personalStatementWithoutQuestionStaysSilent() = runTest {
        jev.verdict = verdict(importance = 2.97f, category = NotificationCategory.PERSONAL_MESSAGE)

        val d = decide(
            notification(
                pkg = "com.whatsapp",
                app = "WhatsApp",
                title = "Ayush Neurodrift",
                text = "Damn, I will need that or build one myself",
                sender = "Ayush Neurodrift",
            ),
        )

        assertEquals(WallBucket.SILENCE, d.bucket)
    }

    @Test
    fun groupQuestionDoesNotGetThePersonalBar() = runTest {
        jev.verdict = verdict(importance = 3.51f, category = NotificationCategory.PERSONAL_MESSAGE)

        val d = decide(
            notification(
                pkg = "com.whatsapp",
                app = "WhatsApp",
                title = "V-AI x NeuroDrift: ~ Divyam Nigam",
                text = "done @Kanhaiya Mohan, is this fine?",
                sender = "~ Divyam Nigam",
            ),
        )

        assertEquals(WallBucket.SILENCE, d.bucket)
    }

    @Test
    fun personalBarAppliesToCachedVerdictsToo() = runTest {
        jev.verdict = verdict(importance = 3.05f, category = NotificationCategory.PERSONAL_MESSAGE)
        val first = notification(
            pkg = "com.whatsapp",
            app = "WhatsApp",
            title = "Ayush Neurodrift",
            text = "Busy ho aap abhi?",
            sender = "Ayush Neurodrift",
        )
        decide(first)
        assertEquals(1, jev.calls.size)

        val d = decide(first)

        assertEquals(WallDecisionSource.CACHE, d.source)
        assertEquals(WallBucket.RING, d.bucket)
    }
}
