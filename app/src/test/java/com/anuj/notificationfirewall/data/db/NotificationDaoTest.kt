package com.anuj.notificationfirewall.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.anuj.notificationfirewall.domain.model.BucketAction
import com.anuj.notificationfirewall.domain.model.DecisionSource
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

    private fun rec(ts: Long, bucket: BucketAction) = NotificationRecordEntity(
        packageName = "com.whatsapp", appLabel = "WhatsApp", title = "t", text = "x",
        timestampEpochMs = ts, senderKey = "mom", activeProfileId = 1, matchedRuleId = null,
        decisionSource = DecisionSource.DEFAULT, bucket = bucket, aiUrgent = null,
        aiReason = null, isRead = false
    )

    @Test fun recordsBetween_filters_by_window() = runBlocking {
        db.notificationDao().insert(rec(100, BucketAction.CAPTURE))
        db.notificationDao().insert(rec(500, BucketAction.CAPTURE))
        db.notificationDao().insert(rec(900, BucketAction.CAPTURE))
        val inWindow = db.notificationDao().recordsBetween(200, 800)
        assertEquals(1, inWindow.size)
        assertEquals(500, inWindow.first().timestampEpochMs)
    }
}
