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

    // senderKey is seeded equal to title on purpose: real ingest copies the
    // title verbatim into senderKey (NotificationMapper.kt: `val senderKey =
    // title`), so a seed with senderKey = the *app label* -- which this test
    // used to carry -- could never catch a senderKey leak, because the app
    // label is legitimately exported as metadata either way.
    private suspend fun seed(purgedAt: Long? = null) = db.notificationDao().insert(
        NotificationRecordEntity(
            packageName = "com.myntra", appLabel = "Myntra",
            title = if (purgedAt == null) SECRET_TITLE else null,
            text = if (purgedAt == null) "SECRET BODY" else null,
            timestampEpochMs = 1_700_000_000_000L, senderKey = SECRET_TITLE,
            contentShape = "shape", importanceScore = 1.4f, biasApplied = 0f,
            category = NotificationCategory.PROMOTION, isTimeSensitive = 0.1f,
            isFromHuman = 0.03f, needsAction = 0.05f, jevConfidence = 0.9f,
            decisionSource = WallDecisionSource.JEV, bucket = WallBucket.SILENCE,
            pendingClassification = false, textPurgedAt = purgedAt, isRead = false,
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
    fun metadataOnlyExportCarriesNoTitleEvenViaSenderKey() = runTest {
        seed()
        val json = exporter.toJson(includeContent = false)

        // senderKey is a verbatim copy of the title, so exporting it outside
        // the includeContent branch put every title in the history into a
        // file the checkbox promised would carry none.
        assertFalse(
            "senderKey is title-derived and must not appear in a metadata-only export",
            json.contains(SECRET_TITLE),
        )
        assertFalse(json.contains("senderKey"))
    }

    @Test
    fun purgedRowExportsNoSenderKeyEvenWithContentRequested() = runTest {
        seed(purgedAt = 1_700_000_500_000L)
        val json = exporter.toJson(includeContent = true)

        // Retention nulls title/text but leaves senderKey populated, so
        // without the purge guard an opt-in export would hand back text
        // retention had already removed.
        assertFalse(json.contains(SECRET_TITLE))
    }

    @Test
    fun includesContentWhenExplicitlyRequested() = runTest {
        seed()
        val json = exporter.toJson(includeContent = true)
        assertTrue(json.contains("SECRET BODY"))
        assertTrue(json.contains(SECRET_TITLE))
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

    private companion object {
        const val SECRET_TITLE = "SECRET TITLE"
    }
}
