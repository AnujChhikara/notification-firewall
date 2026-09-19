package com.anuj.notificationfirewall.domain.wall

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.anuj.notificationfirewall.data.db.NfDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private const val DAY_MS = 24L * 60 * 60 * 1000

@RunWith(RobolectricTestRunner::class)
class VerdictCacheTest {

    private lateinit var db: NfDatabase
    private lateinit var cache: VerdictCache
    private var now = 1_700_000_000_000L

    private fun verdict(
        importance: Float = 1.4f,
        fromHuman: Float = 0.03f,
        confidence: Float = 0.9f,
    ) = JevVerdict(
        importance = importance,
        category = NotificationCategory.PROMOTION,
        isTimeSensitive = 0.1f,
        isFromHuman = fromHuman,
        needsAction = 0.05f,
        confidence = confidence,
    )

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            NfDatabase::class.java,
        ).allowMainThreadQueries().build()
        cache = VerdictCache(db.verdictCacheDao()) { now }
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun machineSenderHighConfidence_isCachedAndReadBack() = runTest {
        assertTrue(cache.put("shape1", "com.myntra", "Myntra", verdict()))

        val found = cache.get("com.myntra", "Myntra", "shape1")
        assertNotNull(found)
        assertEquals(1.4f, found!!.importance, 0.001f)
        assertEquals(NotificationCategory.PROMOTION, found.category)
    }

    @Test
    fun humanSenderIsNeverCached() = runTest {
        val stored = cache.put("shape2", "com.whatsapp", "Mom", verdict(fromHuman = 0.7f))

        assertFalse(stored)
        assertNull(cache.get("com.whatsapp", "Mom", "shape2"))
    }

    @Test
    fun humanSenderWellAboveThresholdIsNeverCached() = runTest {
        assertFalse(cache.put("shape3", "com.whatsapp", "Mom", verdict(fromHuman = 0.98f)))
        assertNull(cache.get("com.whatsapp", "Mom", "shape3"))
    }

    @Test
    fun justBelowHumanThresholdIsStillCached() = runTest {
        assertTrue(cache.put("shape4", "com.linkedin", "LinkedIn", verdict(fromHuman = 0.69f)))
        assertNotNull(cache.get("com.linkedin", "LinkedIn", "shape4"))
    }

    @Test
    fun lowConfidenceIsNeverCached() = runTest {
        assertFalse(cache.put("shape5", "com.myntra", "Myntra", verdict(confidence = 0.59f)))
        assertNull(cache.get("com.myntra", "Myntra", "shape5"))
    }

    @Test
    fun confidenceAtThresholdIsCached() = runTest {
        assertTrue(cache.put("shape6", "com.myntra", "Myntra", verdict(confidence = 0.6f)))
        assertNotNull(cache.get("com.myntra", "Myntra", "shape6"))
    }

    @Test
    fun readingAVerdictRecordsAHit() = runTest {
        cache.put("shape7", "com.myntra", "Myntra", verdict())
        cache.get("com.myntra", "Myntra", "shape7")
        cache.get("com.myntra", "Myntra", "shape7")

        val entry = db.verdictCacheDao().find("com.myntra", "Myntra", "shape7")!!
        assertEquals(2, entry.hitCount)
    }

    @Test
    fun evictRemovesTheEntry() = runTest {
        cache.put("shape8", "com.myntra", "Myntra", verdict())
        cache.evict("com.myntra", "Myntra", "shape8")
        assertNull(cache.get("com.myntra", "Myntra", "shape8"))
    }

    @Test
    fun evictStaleRemovesOnlyEntriesUnusedBeyondTheWindow() = runTest {
        cache.put("old", "com.a", "A", verdict())
        now += 100 * DAY_MS
        cache.put("fresh", "com.b", "B", verdict())

        val removed = cache.evictStale(maxAgeDays = 90)

        assertEquals(1, removed)
        assertNull(cache.get("com.a", "A", "old"))
        assertNotNull(cache.get("com.b", "B", "fresh"))
    }

    @Test
    fun missReturnsNull() = runTest {
        assertNull(cache.get("com.nothing", "Nobody", "never-stored"))
    }

    // ── Composite key (Finding 2) ───────────────────────────────────────────

    @Test
    fun twoDifferentPackagesWithTheSameContentShapeGetIndependentVerdicts() = runTest {
        // Two different apps posting identically-worded machine text ("You have
        // a new message") must never share a cache entry, or one app's verdict
        // silently answers for the other's notifications.
        cache.put("shared-shape", "com.appA", "SenderA", verdict(importance = 1.0f))
        cache.put("shared-shape", "com.appB", "SenderB", verdict(importance = 4.5f))

        val a = cache.get("com.appA", "SenderA", "shared-shape")
        val b = cache.get("com.appB", "SenderB", "shared-shape")

        assertNotNull(a)
        assertNotNull(b)
        assertEquals(1.0f, a!!.importance, 0.001f)
        assertEquals(4.5f, b!!.importance, 0.001f)
    }

    @Test
    fun writingTheSecondPackageDoesNotOverwriteTheFirstsAttribution() = runTest {
        cache.put("shared-shape", "com.appA", "SenderA", verdict(importance = 1.0f))
        cache.put("shared-shape", "com.appB", "SenderB", verdict(importance = 4.5f))

        assertEquals(2, db.verdictCacheDao().count())
    }

    @Test
    fun aNullSenderAndAnEmptyStringSenderAreTheSameCacheEntry() = runTest {
        // senderKey is normalized to an empty-string sentinel at the cache
        // boundary (Room composite primary keys cannot contain a NULL
        // column), so null and "" must collide, not create two rows.
        cache.put("shape9", "com.system", null, verdict())

        assertNotNull(cache.get("com.system", "", "shape9"))
        assertNotNull(cache.get("com.system", null, "shape9"))
        assertEquals(1, db.verdictCacheDao().count())
    }
}
