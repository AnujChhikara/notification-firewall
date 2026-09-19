package com.anuj.notificationfirewall.work

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.anuj.notificationfirewall.data.db.NfDatabase
import com.anuj.notificationfirewall.data.db.NotificationRecordEntity
import com.anuj.notificationfirewall.domain.wall.WallBucket
import com.anuj.notificationfirewall.domain.wall.WallDecisionSource
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private const val DAY_MS = 24L * 60 * 60 * 1000
private const val NOW = 1_700_000_000_000L

@RunWith(RobolectricTestRunner::class)
class RetentionTest {

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

    private suspend fun insert(ageDays: Long, title: String) = db.notificationDao().insert(
        NotificationRecordEntity(
            packageName = "com.myntra",
            appLabel = "Myntra",
            title = title,
            text = "body",
            timestampEpochMs = NOW - ageDays * DAY_MS,
            senderKey = "Myntra",
            contentShape = "shape",
            importanceScore = 1.4f,
            biasApplied = 0f,
            category = null,
            isTimeSensitive = null,
            isFromHuman = null,
            needsAction = null,
            jevConfidence = 0.9f,
            decisionSource = WallDecisionSource.JEV,
            bucket = WallBucket.SILENCE,
            pendingClassification = false,
            textPurgedAt = null,
            isRead = false,
        ),
    )

    @Test
    fun purgeNullsTextOnRecordsOlderThanTheWindowAndKeepsMetadata() = runTest {
        insert(ageDays = 45, title = "old")
        insert(ageDays = 5, title = "recent")

        val purged = db.notificationDao().purgeTextBefore(cutoffMs = NOW - 30 * DAY_MS, nowMs = NOW)

        assertEquals(1, purged)
        val all = db.notificationDao().recordsBetween(0, Long.MAX_VALUE)
        val old = all.first { it.timestampEpochMs < NOW - 30 * DAY_MS }
        val recent = all.first { it.timestampEpochMs > NOW - 30 * DAY_MS }

        assertNull(old.title)
        assertNull(old.text)
        assertNotNull("metadata must survive the purge", old.importanceScore)
        assertEquals(NOW, old.textPurgedAt)
        assertEquals("recent", recent.title)
    }

    @Test
    fun purgeIsIdempotent() = runTest {
        insert(ageDays = 45, title = "old")

        db.notificationDao().purgeTextBefore(NOW - 30 * DAY_MS, NOW)
        val second = db.notificationDao().purgeTextBefore(NOW - 30 * DAY_MS, NOW + 1000)

        assertEquals("already-purged rows must not be touched again", 0, second)
    }

    @Test
    fun pendingRecordsAreQueryable() = runTest {
        val id = db.notificationDao().insert(
            NotificationRecordEntity(
                packageName = "com.x", appLabel = "X", title = "t", text = "b",
                timestampEpochMs = NOW, senderKey = "S", contentShape = "shape",
                importanceScore = null, biasApplied = 0f, category = null,
                isTimeSensitive = null, isFromHuman = null, needsAction = null,
                jevConfidence = null, decisionSource = WallDecisionSource.PENDING,
                bucket = WallBucket.SILENCE, pendingClassification = true,
                textPurgedAt = null, isRead = false,
            ),
        )
        insert(ageDays = 1, title = "classified")

        val pending = db.notificationDao().pending(limit = 50)

        assertEquals(1, pending.size)
        assertEquals(id, pending.single().id)
    }

    @Test
    fun applyVerdictClearsThePendingFlag() = runTest {
        val id = db.notificationDao().insert(
            NotificationRecordEntity(
                packageName = "com.x", appLabel = "X", title = "t", text = "b",
                timestampEpochMs = NOW, senderKey = "S", contentShape = "shape",
                importanceScore = null, biasApplied = 0f, category = null,
                isTimeSensitive = null, isFromHuman = null, needsAction = null,
                jevConfidence = null, decisionSource = WallDecisionSource.PENDING,
                bucket = WallBucket.SILENCE, pendingClassification = true,
                textPurgedAt = null, isRead = false,
            ),
        )

        db.notificationDao().applyVerdict(
            id = id,
            importance = 2.2f,
            category = com.anuj.notificationfirewall.domain.wall.NotificationCategory.PROMOTION,
            timeSensitive = 0.1f,
            fromHuman = 0.02f,
            needsAction = 0.05f,
            confidence = 0.88f,
            bucket = WallBucket.SILENCE,
            source = WallDecisionSource.JEV,
        )

        assertEquals(0, db.notificationDao().pending(limit = 50).size)
    }

    @Test
    fun clearPendingLeavesVerdictColumnsNullForRecordsWithPurgedText() = runTest {
        val id = db.notificationDao().insert(
            NotificationRecordEntity(
                packageName = "com.x", appLabel = "X", title = null, text = null,
                timestampEpochMs = NOW, senderKey = "S", contentShape = "shape",
                importanceScore = null, biasApplied = 0f, category = null,
                isTimeSensitive = null, isFromHuman = null, needsAction = null,
                jevConfidence = null, decisionSource = WallDecisionSource.PENDING,
                bucket = WallBucket.SILENCE, pendingClassification = true,
                textPurgedAt = NOW - DAY_MS, isRead = false,
            ),
        )

        db.notificationDao().clearPending(id)

        val record = db.notificationDao().recordsBetween(0, Long.MAX_VALUE).single { it.id == id }
        assertEquals("pending flag must be cleared", false, record.pendingClassification)
        assertNull("no verdict was ever computed; importance must stay NULL", record.importanceScore)
        assertNull("no verdict was ever computed; category must stay NULL", record.category)
        assertNull("no verdict was ever computed; confidence must stay NULL", record.jevConfidence)
        assertEquals(
            "text was purged before a verdict could be obtained, not judged by an old app version",
            WallDecisionSource.EXPIRED,
            record.decisionSource,
        )
    }
}
