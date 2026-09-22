package com.anuj.notificationfirewall.ui.settings

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.anuj.notificationfirewall.ai.DigestStore
import com.anuj.notificationfirewall.ai.PersistedDigest
import com.anuj.notificationfirewall.data.db.NfDatabase
import com.anuj.notificationfirewall.data.db.NotificationRecordEntity
import com.anuj.notificationfirewall.data.db.SenderBiasEntity
import com.anuj.notificationfirewall.data.prefs.WallSettings
import com.anuj.notificationfirewall.domain.wall.BiasStore
import com.anuj.notificationfirewall.domain.wall.NotificationCategory
import com.anuj.notificationfirewall.domain.wall.OverrideKind
import com.anuj.notificationfirewall.domain.wall.VerdictCache
import com.anuj.notificationfirewall.domain.wall.WallBucket
import com.anuj.notificationfirewall.domain.wall.WallDecisionSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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
import java.util.concurrent.Executor

/**
 * Covers the Settings screen's state model, with particular attention to the
 * destructive controls (purge history, empty cache, reset all learning) —
 * getting one of those wrong loses the user's data irreversibly.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SettingsViewModelTest {

    private lateinit var context: Context
    private lateinit var db: NfDatabase
    private lateinit var settings: WallSettings
    private lateinit var digestStore: DigestStore
    private lateinit var vm: SettingsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = ApplicationProvider.getApplicationContext()
        val sameThread = Executor { it.run() }
        db = Room.inMemoryDatabaseBuilder(context, NfDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(sameThread)
            .setTransactionExecutor(sameThread)
            .build()
        settings = WallSettings(context.getSharedPreferences("test-settings-vm", Context.MODE_PRIVATE))
        digestStore = DigestStore(settings)
        vm = SettingsViewModel(
            context = context,
            settings = settings,
            overrides = com.anuj.notificationfirewall.domain.wall.OverrideStore(db.overrideDao()),
            bias = BiasStore(db.senderBiasDao()) { 1_700_000_000_000L },
            verdictCache = VerdictCache(db.verdictCacheDao()) { 1_700_000_000_000L },
            notificationDao = db.notificationDao(),
            digestStore = digestStore,
        )
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private suspend fun insertRecord(
        importance: Float = 4.5f,
        bucket: WallBucket = WallBucket.RING,
        timestampEpochMs: Long = System.currentTimeMillis(),
    ) = db.notificationDao().insert(
        NotificationRecordEntity(
            packageName = "com.myntra", appLabel = "Myntra", title = "T", text = "B",
            timestampEpochMs = timestampEpochMs, senderKey = "Myntra", contentShape = "shape",
            importanceScore = importance, biasApplied = 0f, category = NotificationCategory.PROMOTION,
            isTimeSensitive = 0.1f, isFromHuman = 0.03f, needsAction = 0.05f, jevConfidence = 0.9f,
            decisionSource = WallDecisionSource.JEV, bucket = bucket,
            pendingClassification = false, textPurgedAt = null, isRead = false,
        ),
    )

    @Test
    fun thresholdChangePersistsImmediatelyToWallSettings() = runTest {
        vm.onThresholdChange(3.5f)

        assertEquals(3.5f, settings.threshold, 0.001f)
        assertEquals(3.5f, vm.ui.value.threshold, 0.001f)
    }

    @Test
    fun textRetentionChangePersistsAndUpdatesUiState() = runTest {
        vm.setTextRetentionDays(14)

        assertEquals(14, settings.textRetentionDays)
        assertEquals(14, vm.ui.value.textRetentionDays)
    }

    @Test
    fun addingAVipOverrideShowsUpInTheVipList() = runTest {
        vm.addOverride(OverrideKind.VIP, "com.whatsapp", "WhatsApp")

        assertEquals(1, vm.ui.value.vipList.size)
        assertEquals("com.whatsapp", vm.ui.value.vipList.first().packageName)
        assertTrue(vm.ui.value.blockList.isEmpty())
    }

    @Test
    fun removingAnOverrideTakesItOffTheList() = runTest {
        vm.addOverride(OverrideKind.BLOCK, "com.spam", "Spam Co")
        val row = vm.ui.value.blockList.first()

        vm.removeOverride(row, OverrideKind.BLOCK)

        assertTrue(vm.ui.value.blockList.isEmpty())
    }

    @Test
    fun clearingOneSendersBiasDoesNotTouchAnother() = runTest {
        db.senderBiasDao().upsert(SenderBiasEntity("com.myntra", "Myntra", 0.5f, 2, 0L))
        db.senderBiasDao().upsert(SenderBiasEntity("com.whatsapp", "Mom", -0.25f, 1, 0L))

        vm.clearBias(LearnedSenderRow("com.myntra", "Myntra", 0.5f))

        val remaining = vm.ui.value.learnedSenders
        assertEquals(1, remaining.size)
        assertEquals("Mom", remaining.first().senderKey)
    }

    @Test
    fun resetAllLearningWipesEverySender() = runTest {
        db.senderBiasDao().upsert(SenderBiasEntity("com.myntra", "Myntra", 0.5f, 2, 0L))
        db.senderBiasDao().upsert(SenderBiasEntity("com.whatsapp", "Mom", -0.25f, 1, 0L))

        vm.resetAllLearning()

        assertTrue("reset must clear every sender, not just one", vm.ui.value.learnedSenders.isEmpty())
    }

    @Test
    fun deleteAllHistoryRemovesEveryRecord() = runTest {
        insertRecord()
        insertRecord()

        vm.deleteAllHistory()

        assertEquals(0, vm.ui.value.historyCount)
        assertEquals(0, db.notificationDao().totalCount())
    }

    /**
     * DigestStore is a content-bearing store (sender names/titles can be in
     * worthALook) that summarises the very rows this wipes. "Delete all
     * history" that left a summary of that history sitting on the Wall
     * screen would not actually be deleting it.
     */
    @Test
    fun deleteAllHistoryAlsoClearsThePersistedDigest() = runTest {
        digestStore.save(
            PersistedDigest(
                dateEpochDay = 19_000,
                headline = "Yesterday: 5 silenced, 1 let through.",
                rang = 1, silenced = 5, dropped = 0,
                topOffenderLabel = "Myntra", topOffenderCount = 5,
                worthALook = listOf("Landlord: rent due"),
            ),
        )

        vm.deleteAllHistory()

        assertNull(
            "deleting all history must not leave a digest summarising it behind",
            digestStore.load(),
        )
    }

    @Test
    fun emptyCacheWipesTheVerdictCache() = runTest {
        val cache = VerdictCache(db.verdictCacheDao()) { 1_700_000_000_000L }
        cache.put(
            "shape1", "com.myntra", "Myntra",
            com.anuj.notificationfirewall.domain.wall.JevVerdict(
                1.4f, NotificationCategory.PROMOTION, 0.1f, 0.03f, 0.05f, 0.9f,
            ),
        )

        vm.emptyCache()

        assertEquals(0, vm.ui.value.cacheEntryCount)
        assertEquals(0, db.verdictCacheDao().count())
    }

    @Test
    fun exportJsonDelegatesToTheHistoryExporter() = runTest {
        insertRecord()

        val json = JSONObject(vm.exportJson(includeContent = false))

        assertEquals(1, json.getInt("count"))
        assertFalse(json.toString().contains("\"title\""))
    }

    @Test
    fun exportJsonIncludesContentWhenAsked() = runTest {
        insertRecord()

        val json = vm.exportJson(includeContent = true)

        assertTrue(json.contains("\"title\""))
    }
}
