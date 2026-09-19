package com.anuj.notificationfirewall.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.anuj.notificationfirewall.domain.wall.WallBucket
import com.anuj.notificationfirewall.domain.wall.WallDecisionSource
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private const val NOW = 1_700_000_000_000L
private const val DAY_MS = 24L * 60 * 60 * 1000

@RunWith(RobolectricTestRunner::class)
class TodayCountsTest {

    private lateinit var db: NfDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            NfDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun insert(bucket: WallBucket, atMs: Long, pkg: String = "com.myntra") =
        db.notificationDao().insert(
            NotificationRecordEntity(
                packageName = pkg, appLabel = "App", title = "t", text = "b",
                timestampEpochMs = atMs, senderKey = "S", contentShape = "shape",
                importanceScore = 2f, biasApplied = 0f, category = null,
                isTimeSensitive = null, isFromHuman = null, needsAction = null,
                jevConfidence = 0.9f, decisionSource = WallDecisionSource.JEV,
                bucket = bucket, pendingClassification = false,
                textPurgedAt = null, isRead = false,
            ),
        )

    @Test
    fun countsAreGroupedByBucketWithinTheWindow() = runTest {
        insert(WallBucket.SILENCE, NOW)
        insert(WallBucket.SILENCE, NOW + 1000)
        insert(WallBucket.RING, NOW + 2000)
        insert(WallBucket.DROP, NOW + 3000)

        val counts = db.notificationDao().countsForDay(NOW - DAY_MS, NOW + DAY_MS)
            .associate { it.bucket to it.count }

        assertEquals(2, counts[WallBucket.SILENCE])
        assertEquals(1, counts[WallBucket.RING])
        assertEquals(1, counts[WallBucket.DROP])
    }

    @Test
    fun countsExcludeRecordsOutsideTheWindow() = runTest {
        insert(WallBucket.SILENCE, NOW - 3 * DAY_MS)
        insert(WallBucket.SILENCE, NOW)

        val counts = db.notificationDao().countsForDay(NOW - DAY_MS, NOW + DAY_MS)

        assertEquals(1, counts.single().count)
    }

    @Test
    fun emptyWindowReturnsNoRows() = runTest {
        assertEquals(0, db.notificationDao().countsForDay(NOW, NOW + 1000).size)
    }

    @Test
    fun endBoundaryBelongsToTheNextDayNotThisOne() = runTest {
        // A record landing at exactly midnight (the next day's startMs, which
        // is this window's endMs) must be counted into the next day only --
        // the range is half-open, [startMs, endMs), so it never double-counts
        // across the day boundary.
        insert(WallBucket.SILENCE, NOW) // inside this window
        insert(WallBucket.SILENCE, NOW + DAY_MS) // exactly at endMs, belongs to the next day

        val counts = db.notificationDao().countsForDay(NOW, NOW + DAY_MS)

        assertEquals(1, counts.single().count)
    }
}
