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
        val json = exporter.toJson(includeContent = false, threshold = 3f, personalBar = 2.5f)

        assertFalse("content must be opt-in even in an export", json.contains("SECRET BODY"))
        assertTrue(json.contains("Myntra"))
        assertTrue(json.contains("PROMOTION"))
    }

    @Test
    fun metadataOnlyExportCarriesNoTitleEvenViaSenderKey() = runTest {
        seed()
        val json = exporter.toJson(includeContent = false, threshold = 3f, personalBar = 2.5f)

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
        val json = exporter.toJson(includeContent = true, threshold = 3f, personalBar = 2.5f)

        // Retention nulls title/text but leaves senderKey populated, so
        // without the purge guard an opt-in export would hand back text
        // retention had already removed.
        assertFalse(json.contains(SECRET_TITLE))
    }

    @Test
    fun includesContentWhenExplicitlyRequested() = runTest {
        seed()
        val json = exporter.toJson(includeContent = true, threshold = 3f, personalBar = 2.5f)
        assertTrue(json.contains("SECRET BODY"))
        assertTrue(json.contains(SECRET_TITLE))
    }

    @Test
    fun producesParseableJsonWithACountHeader() = runTest {
        seed()
        seed()
        val root = JSONObject(exporter.toJson(includeContent = false, threshold = 3f, personalBar = 2.5f))

        assertEquals(2, root.getInt("count"))
        assertEquals(2, root.getJSONArray("notifications").length())
    }

    @Test
    fun emptyHistoryExportsAnEmptyArrayNotAnError() = runTest {
        val root = JSONObject(exporter.toJson(includeContent = false, threshold = 3f, personalBar = 2.5f))
        assertEquals(0, root.getInt("count"))
    }

    @Test
    fun rowsCarryOutcomeBiasedScoreAndWhyForExternalVerification() = runTest {
        seed()
        val root = JSONObject(exporter.toJson(includeContent = false, threshold = 3f, personalBar = 2.5f))

        assertEquals(3.0, root.getDouble("threshold"), 0.0)
        assertTrue(root.getString("thresholdMeaning").contains("biasedScore >= its bar"))

        val row = root.getJSONArray("notifications").getJSONObject(0)
        assertEquals("SILENCE", row.getString("bucket"))
        assertEquals("muted", row.getString("outcome"))
        assertEquals(1.4, row.getDouble("biasedScore"), 0.001)
        assertTrue(row.getString("why").contains("vs global bar"))
    }

    @Test
    fun personalQuestionRowNamesThePersonalBar() = runTest {
        db.notificationDao().insert(
            NotificationRecordEntity(
                packageName = "com.whatsapp", appLabel = "WhatsApp",
                title = "Anmol Rishi", text = "Can you come to gurgaon?",
                timestampEpochMs = 1_700_000_000_000L, senderKey = "Anmol Rishi",
                contentShape = "shape", importanceScore = 3.44f, biasApplied = 0f,
                category = NotificationCategory.PERSONAL_MESSAGE, isTimeSensitive = null,
                isFromHuman = 0.9f, needsAction = null, jevConfidence = 0.39f,
                decisionSource = WallDecisionSource.JEV, bucket = WallBucket.RING,
                pendingClassification = false, textPurgedAt = null, isRead = false,
            ),
        )
        val root = JSONObject(
            exporter.toJson(includeContent = false, threshold = 4.05f, personalBar = 2.5f),
        )
        val row = root.getJSONArray("notifications").getJSONObject(0)
        assertTrue(
            "a re-checking model must compare against the personal bar, not the global one",
            row.getString("why").contains("vs personal-question bar 2.5"),
        )
    }

    @Test
    fun ringBucketExportsShownOutcome() = runTest {
        db.notificationDao().insert(
            NotificationRecordEntity(
                packageName = "com.whatsapp", appLabel = "WhatsApp",
                title = null, text = null,
                timestampEpochMs = 1_700_000_000_000L, senderKey = null,
                contentShape = "shape", importanceScore = 4.5f, biasApplied = 0.2f,
                category = null, isTimeSensitive = null,
                isFromHuman = 0.9f, needsAction = null, jevConfidence = 0.95f,
                decisionSource = WallDecisionSource.JEV, bucket = WallBucket.RING,
                pendingClassification = false, textPurgedAt = null, isRead = false,
            ),
        )
        val root = JSONObject(exporter.toJson(includeContent = false, threshold = 3f, personalBar = 2.5f))
        val row = root.getJSONArray("notifications").getJSONObject(0)
        assertEquals("shown", row.getString("outcome"))
        assertEquals(4.7, row.getDouble("biasedScore"), 0.001)
    }

    private companion object {
        const val SECRET_TITLE = "SECRET TITLE"
    }
}
