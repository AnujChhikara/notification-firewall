package com.anuj.notificationfirewall.ui.inbox

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.anuj.notificationfirewall.data.db.NfDatabase
import com.anuj.notificationfirewall.data.db.NotificationRecordEntity
import com.anuj.notificationfirewall.domain.wall.BiasStore
import com.anuj.notificationfirewall.domain.wall.Correction
import com.anuj.notificationfirewall.domain.wall.JevVerdict
import com.anuj.notificationfirewall.domain.wall.NotificationCategory
import com.anuj.notificationfirewall.domain.wall.OverrideKind
import com.anuj.notificationfirewall.domain.wall.OverrideSource
import com.anuj.notificationfirewall.domain.wall.OverrideStore
import com.anuj.notificationfirewall.domain.wall.VerdictCache
import com.anuj.notificationfirewall.domain.wall.WallBucket
import com.anuj.notificationfirewall.domain.wall.WallDecisionSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.Executor

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class InboxViewModelTest {

    private lateinit var db: NfDatabase
    private lateinit var bias: BiasStore
    private lateinit var overrides: OverrideStore
    private lateinit var cache: VerdictCache
    private lateinit var vm: InboxViewModel

    // InboxViewModel.correct/addOverride fire-and-forget on viewModelScope
    // (Dispatchers.Main.immediate). Robolectric's real main looper won't pump
    // those coroutines to completion inside a runTest block on its own, so
    // Main is swapped for an unconfined test dispatcher: launched work then
    // runs eagerly, synchronously, on the calling thread.
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        // Room's suspend DAO methods hop to a background executor and resume
        // via callback, which is genuinely asynchronous relative to this test
        // thread no matter what Main dispatcher is installed. A same-thread
        // executor makes that hop resolve synchronously, so an assertion
        // right after vm.correct()/addOverride() observes the finished write
        // instead of racing it.
        val sameThread = Executor { it.run() }
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            NfDatabase::class.java,
        ).allowMainThreadQueries()
            .setQueryExecutor(sameThread)
            .setTransactionExecutor(sameThread)
            .build()
        bias = BiasStore(db.senderBiasDao()) { 1_700_000_000_000L }
        overrides = OverrideStore(db.overrideDao())
        cache = VerdictCache(db.verdictCacheDao()) { 1_700_000_000_000L }
        vm = InboxViewModel(db.notificationDao(), bias, overrides, cache)
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private suspend fun insert(
        pkg: String = "com.myntra",
        sender: String = "Myntra",
        shape: String = "shape1",
        bucket: WallBucket = WallBucket.SILENCE,
        source: WallDecisionSource = WallDecisionSource.JEV,
    ) = db.notificationDao().insert(
        NotificationRecordEntity(
            packageName = pkg, appLabel = "Myntra", title = "FLAT 70% OFF", text = "Shop now",
            timestampEpochMs = 1_700_000_000_000L, senderKey = sender, contentShape = shape,
            importanceScore = 1.4f, biasApplied = 0f, category = NotificationCategory.PROMOTION,
            isTimeSensitive = 0.1f, isFromHuman = 0.03f, needsAction = 0.05f,
            jevConfidence = 0.91f, decisionSource = source, bucket = bucket,
            pendingClassification = false, textPurgedAt = null, isRead = false,
        ),
    )

    private fun row(id: Long) = InboxRow(
        id = id, appLabel = "Myntra", sender = "Myntra", title = "FLAT 70% OFF", text = "Shop now",
        timestampMs = 1_700_000_000_000L, bucket = WallBucket.SILENCE,
        source = WallDecisionSource.JEV, importance = 1.4f, biasApplied = 0f,
        category = NotificationCategory.PROMOTION, confidence = 0.91f, explanation = "",
        packageName = "com.myntra", contentShape = "shape1",
    )

    @Test
    fun correctionWritesABiasForThatSender() = runTest {
        val id = insert()
        vm.correct(row(id), Correction.SHOULD_HAVE_BEEN_SILENT)

        assertEquals(-0.25f, bias.biasFor("com.myntra", "Myntra"), 0.001f)
    }

    @Test
    fun correctionIsScopedToTheSenderNotTheApp() = runTest {
        val id = insert(pkg = "com.whatsapp", sender = "Promo Group")
        vm.correct(
            row(id).copy(packageName = "com.whatsapp", sender = "Promo Group"),
            Correction.SHOULD_HAVE_BEEN_SILENT,
        )

        assertEquals(
            "a correction on one WhatsApp thread must not touch another",
            0f,
            bias.biasFor("com.whatsapp", "Mom"),
            0.001f,
        )
    }

    @Test
    fun correctionEvictsTheMatchingCacheEntry() = runTest {
        cache.put(
            "shape1", "com.myntra", "Myntra",
            JevVerdict(1.4f, NotificationCategory.PROMOTION, 0.1f, 0.03f, 0.05f, 0.9f),
        )
        val id = insert()

        vm.correct(row(id), Correction.SHOULD_HAVE_RUNG)

        assertNull(
            "a corrected verdict must not keep being served from cache",
            cache.get("com.myntra", "Myntra", "shape1"),
        )
    }

    @Test
    fun addingABlockOverrideIsSenderScoped() = runTest {
        val id = insert()
        vm.addOverride(row(id), OverrideKind.BLOCK)

        assertEquals(OverrideKind.BLOCK, overrides.kindFor("com.myntra", "Myntra"))
    }

    @Test
    fun overridesAddedFromTheInboxAreMarkedAsSwipeSourced() = runTest {
        val id = insert()
        vm.addOverride(row(id), OverrideKind.VIP)

        val entry = db.overrideDao().matching("com.myntra", "Myntra").single()
        assertEquals(OverrideSource.SWIPE, entry.source)
    }

    // Ruling P6: `rows` is a WhileSubscribed(5_000) StateFlow with no
    // collector started elsewhere in this test, so `rows.value` alone would
    // stay at its initial emptyList() forever and never observe the insert.
    // A real collector is started here (on backgroundScope, which `runTest`
    // cancels for us) and the test waits for the row it actually expects,
    // so this genuinely exercises the DB -> toRow() -> explain() path.
    @Test
    fun explanationNamesTheDecisionSourceAndScore() = runTest {
        backgroundScope.launch { vm.rows.collect {} }
        insert()

        val explained = vm.rows.first { it.isNotEmpty() }.first()

        assertTrue(explained.explanation.contains("1.4"))
        assertTrue(explained.explanation.lowercase().contains("promotion"))
    }

    @Test
    fun explanationMentionsBiasWhenOneWasApplied() = runTest {
        val text = InboxViewModel.explain(
            importance = 4.5f, bias = -0.75f,
            category = NotificationCategory.PROMOTION,
            source = WallDecisionSource.CACHE, confidence = 0.9f,
        )
        assertTrue(text.contains("-0.75") || text.contains("0.75"))
        assertTrue(text.lowercase().contains("your correction") || text.lowercase().contains("bias"))
    }

    @Test
    fun explanationForOtpDoesNotInventAScore() = runTest {
        val text = InboxViewModel.explain(null, 0f, null, WallDecisionSource.OTP, null)
        assertTrue(text.lowercase().contains("one-time code") || text.lowercase().contains("otp"))
    }
}
