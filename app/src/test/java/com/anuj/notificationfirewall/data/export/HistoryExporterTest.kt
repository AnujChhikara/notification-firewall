package com.anuj.notificationfirewall.data.export

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.anuj.notificationfirewall.data.db.NfDatabase
import com.anuj.notificationfirewall.data.db.NotificationRecordEntity
import com.anuj.notificationfirewall.domain.wall.NotificationCategory
import com.anuj.notificationfirewall.domain.wall.WallBucket
import com.anuj.notificationfirewall.domain.wall.WallDecisionSource
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HistoryExporterTest {

    private lateinit var db: NfDatabase
    private lateinit var exporter: HistoryExporter

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            NfDatabase::class.java,
        ).allowMainThreadQueries().build()
        exporter = HistoryExporter(db.notificationDao())
    }

    @After
    fun tearDown() = db.close()

    private suspend fun seed() = db.notificationDao().insert(
        NotificationRecordEntity(
            packageName = "com.myntra", appLabel = "Myntra",
            title = "SECRET TITLE", text = "SECRET BODY",
            timestampEpochMs = 1_700_000_000_000L, senderKey = "Myntra",
            contentShape = "shape", importanceScore = 1.4f, biasApplied = 0f,
            category = NotificationCategory.PROMOTION, isTimeSensitive = 0.1f,
            isFromHuman = 0.03f, needsAction = 0.05f, jevConfidence = 0.9f,
            decisionSource = WallDecisionSource.JEV, bucket = WallBucket.SILENCE,
            pendingClassification = false, textPurgedAt = null, isRead = false,
        ),
    )

    @Test
    fun exportsMetadataWithoutContentByDefault() = runTest {
        seed()
        val json = exporter.toJson(includeContent = false)

        assertFalse("content must be opt-in even in an export", json.contains("SECRET BODY"))
        assertTrue(json.contains("Myntra"))
        assertTrue(json.contains("PROMOTION"))
    }

    @Test
    fun includesContentWhenExplicitlyRequested() = runTest {
        seed()
        assertTrue(exporter.toJson(includeContent = true).contains("SECRET BODY"))
    }

    @Test
    fun producesParseableJsonWithACountHeader() = runTest {
        seed()
        seed()
        val root = JSONObject(exporter.toJson(includeContent = false))

        assertEquals(2, root.getInt("count"))
        assertEquals(2, root.getJSONArray("notifications").length())
    }

    @Test
    fun emptyHistoryExportsAnEmptyArrayNotAnError() = runTest {
        val root = JSONObject(exporter.toJson(includeContent = false))
        assertEquals(0, root.getInt("count"))
    }
}
