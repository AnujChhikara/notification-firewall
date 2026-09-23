package com.anuj.notificationfirewall.ai

import com.anuj.notificationfirewall.data.db.NotificationRecordEntity
import com.anuj.notificationfirewall.domain.wall.NotificationCategory
import com.anuj.notificationfirewall.domain.wall.WallBucket
import com.anuj.notificationfirewall.domain.wall.WallDecisionSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DigestBuilderTest {

    private fun record(
        app: String,
        bucket: WallBucket,
        importance: Float = 1.2f,
        sender: String = app,
        confidence: Float = 0.9f,
    ) = NotificationRecordEntity(
        packageName = "com.$app", appLabel = app, title = "$sender says hi", text = "body",
        timestampEpochMs = 1_700_000_000_000L, senderKey = sender, contentShape = "shape",
        importanceScore = importance, biasApplied = 0f, category = NotificationCategory.PROMOTION,
        isTimeSensitive = 0.1f, isFromHuman = 0.1f, needsAction = 0.1f, jevConfidence = confidence,
        decisionSource = WallDecisionSource.JEV, bucket = bucket,
        pendingClassification = false, textPurgedAt = null, isRead = false,
    )

    @Test
    fun countsEachBucket() {
        val data = DigestBuilder.summarise(
            listOf(
                record("Myntra", WallBucket.SILENCE),
                record("Myntra", WallBucket.SILENCE),
                record("Slack", WallBucket.RING),
                record("Spam", WallBucket.DROP),
            ),
        )

        assertEquals(1, data.rang)
        assertEquals(2, data.silenced)
        assertEquals(1, data.dropped)
    }

    @Test
    fun namesTheWorstOffenderBySilencedCount() {
        val data = DigestBuilder.summarise(
            List(5) { record("Myntra", WallBucket.SILENCE) } +
                List(2) { record("Ajio", WallBucket.SILENCE) },
        )

        assertEquals("Myntra", data.topOffender?.first)
        assertEquals(5, data.topOffender?.second)
    }

    @Test
    fun theWorstOffenderIgnoresNotificationsThatRang() {
        val data = DigestBuilder.summarise(
            List(9) { record("Slack", WallBucket.RING) } +
                List(2) { record("Myntra", WallBucket.SILENCE) },
        )

        assertEquals("a top offender is one that wasted your attention", "Myntra", data.topOffender?.first)
    }

    @Test
    fun worthALookHoldsTheHighestScoringSilencedItems() {
        val data = DigestBuilder.summarise(
            listOf(
                record("Gmail", WallBucket.SILENCE, importance = 3.8f, sender = "Landlord"),
                record("Myntra", WallBucket.SILENCE, importance = 1.1f),
                record("Gmail", WallBucket.SILENCE, importance = 3.5f, sender = "Dentist"),
            ),
        )

        assertEquals(2, data.worthALook.size)
        assertTrue(data.worthALook.first().contains("Landlord"))
    }

    @Test
    fun worthALookIsCappedAtThree() {
        val data = DigestBuilder.summarise(List(10) { record("Gmail", WallBucket.SILENCE, importance = 3.9f) })
        assertTrue(data.worthALook.size <= 3)
    }

    @Test
    fun worthALookExcludesObviousNoise() {
        val data = DigestBuilder.summarise(List(5) { record("Myntra", WallBucket.SILENCE, importance = 1.1f) })
        assertTrue(data.worthALook.isEmpty())
    }

    @Test
    fun worthALookSurfacesLowConfidenceItemsFirst() {
        // A 3.3 the wall was sure about is less worth your glance than a 3.2
        // it was not: when unsure, show.
        val data = DigestBuilder.summarise(
            listOf(
                record("Sure", WallBucket.SILENCE, importance = 3.3f, confidence = 0.9f),
                record("Unsure", WallBucket.SILENCE, importance = 3.2f, confidence = 0.33f),
            ),
        )

        assertEquals(2, data.worthALook.size)
        assertTrue(
            "low-confidence items sort ahead of high-confidence ones: ${data.worthALook}",
            data.worthALook.first().contains("Unsure"),
        )
    }

    @Test
    fun anEmptyDayHasNoOffender() {
        val data = DigestBuilder.summarise(emptyList())
        assertEquals(0, data.silenced)
        assertNull(data.topOffender)
    }

    /**
     * Retention purges title/text but deliberately leaves senderKey set
     * (see NotificationDao.purgeTextBefore), and senderKey is a verbatim
     * copy of the title (NotificationMapper.kt). A record whose content was
     * purged must not resurface that title via senderKey just because it
     * still scores high enough for "worth a look".
     */
    @Test
    fun worthALookNeverResurfacesAPurgedTitleThroughSenderKey() {
        val purgedButScored = record("Gmail", WallBucket.SILENCE, importance = 4.0f, sender = "Landlord")
            .copy(title = null, text = null, textPurgedAt = 1_700_000_500_000L)

        val data = DigestBuilder.summarise(listOf(purgedButScored))

        assertEquals(1, data.worthALook.size)
        val line = data.worthALook.first()
        assertTrue("purged content must not surface: $line", !line.contains("Landlord"))
        assertTrue("falls back to the app label instead: $line", line.contains("Gmail"))
        assertTrue("must say the content expired, not fabricate a title: $line", line.contains("content expired"))
    }
}
