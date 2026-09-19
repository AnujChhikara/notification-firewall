package com.anuj.notificationfirewall.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.anuj.notificationfirewall.domain.wall.WallBucket
import com.anuj.notificationfirewall.domain.wall.WallDecisionSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NotificationDaoTest {
    private lateinit var db: NfDatabase
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), NfDatabase::class.java
        ).allowMainThreadQueries().build()
    }
    @After fun teardown() = db.close()

    private fun rec(ts: Long, bucket: WallBucket) = NotificationRecordEntity(
        packageName = "com.whatsapp", appLabel = "WhatsApp", title = "t", text = "x",
        timestampEpochMs = ts, senderKey = "mom", contentShape = "", importanceScore = null,
        biasApplied = 0f, category = null, isTimeSensitive = null, isFromHuman = null,
        needsAction = null, jevConfidence = null, decisionSource = WallDecisionSource.LEGACY,
        bucket = bucket, pendingClassification = false, textPurgedAt = null, isRead = false
    )

    @Test fun recordsBetween_filters_by_window() = runBlocking {
        db.notificationDao().insert(rec(100, WallBucket.SILENCE))
        db.notificationDao().insert(rec(500, WallBucket.SILENCE))
        db.notificationDao().insert(rec(900, WallBucket.SILENCE))
        val inWindow = db.notificationDao().recordsBetween(200, 800)
        assertEquals(1, inWindow.size)
        assertEquals(500, inWindow.first().timestampEpochMs)
    }
}
