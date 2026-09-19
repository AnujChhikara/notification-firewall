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

        val found = cache.get("shape1")
        assertNotNull(found)
        assertEquals(1.4f, found!!.importance, 0.001f)
        assertEquals(NotificationCategory.PROMOTION, found.category)
    }

    @Test
    fun humanSenderIsNeverCached() = runTest {
        val stored = cache.put("shape2", "com.whatsapp", "Mom", verdict(fromHuman = 0.7f))

        assertFalse(stored)
        assertNull(cache.get("shape2"))
    }

    @Test
    fun humanSenderWellAboveThresholdIsNeverCached() = runTest {
        assertFalse(cache.put("shape3", "com.whatsapp", "Mom", verdict(fromHuman = 0.98f)))
        assertNull(cache.get("shape3"))
    }

    @Test
    fun justBelowHumanThresholdIsStillCached() = runTest {
        assertTrue(cache.put("shape4", "com.linkedin", "LinkedIn", verdict(fromHuman = 0.69f)))
        assertNotNull(cache.get("shape4"))
    }

    @Test
    fun lowConfidenceIsNeverCached() = runTest {
        assertFalse(cache.put("shape5", "com.myntra", "Myntra", verdict(confidence = 0.59f)))
        assertNull(cache.get("shape5"))
    }

    @Test
    fun confidenceAtThresholdIsCached() = runTest {
        assertTrue(cache.put("shape6", "com.myntra", "Myntra", verdict(confidence = 0.6f)))
        assertNotNull(cache.get("shape6"))
    }

    @Test
    fun readingAVerdictRecordsAHit() = runTest {
        cache.put("shape7", "com.myntra", "Myntra", verdict())
        cache.get("shape7")
        cache.get("shape7")

        val entry = db.verdictCacheDao().find("shape7")!!
        assertEquals(2, entry.hitCount)
    }

    @Test
    fun evictRemovesTheEntry() = runTest {
        cache.put("shape8", "com.myntra", "Myntra", verdict())
        cache.evict("shape8")
        assertNull(cache.get("shape8"))
    }

    @Test
    fun evictStaleRemovesOnlyEntriesUnusedBeyondTheWindow() = runTest {
        cache.put("old", "com.a", "A", verdict())
        now += 100 * DAY_MS
        cache.put("fresh", "com.b", "B", verdict())

        val removed = cache.evictStale(maxAgeDays = 90)

        assertEquals(1, removed)
        assertNull(cache.get("old"))
        assertNotNull(cache.get("fresh"))
    }

    @Test
    fun missReturnsNull() = runTest {
        assertNull(cache.get("never-stored"))
    }
}
