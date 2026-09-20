package com.anuj.notificationfirewall.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Settings lists render strings that came from notification titles:
 * a swipe-created override's label is `sender ?: appLabel`, and every
 * `sender_bias` key is a verbatim copy of a title. Neither table is ever
 * purged, so without a rendering guard those titles outlive the user's
 * retention setting indefinitely — and survive "Delete all history".
 */
class ExpiredSenderLabelTest {

    private val now = 1_700_000_000_000L
    private val day = 24L * 60 * 60 * 1000
    private val title = "Mum: dinner at 7"

    @Test
    fun retentionOfZeroMeansNothingEverExpires() {
        assertFalse(sourceTextExpired(now - 10_000 * day, retentionDays = 0, nowMs = now))
    }

    @Test
    fun entryInsideTheWindowHasNotExpired() {
        assertFalse(sourceTextExpired(now - 29 * day, retentionDays = 30, nowMs = now))
    }

    @Test
    fun entryOlderThanTheWindowHasExpired() {
        assertTrue(sourceTextExpired(now - 31 * day, retentionDays = 30, nowMs = now))
    }

    @Test
    fun learnedCorrectionOlderThanRetentionHidesItsTitleDerivedKey() {
        val row = LearnedSenderRow("com.whatsapp", title, 0.25f, now - 31 * day)
        assertEquals(EXPIRED_SENDER_LABEL, row.displayLabel(retentionDays = 30, nowMs = now))
    }

    @Test
    fun recentLearnedCorrectionStillShowsItsSender() {
        val row = LearnedSenderRow("com.whatsapp", title, 0.25f, now - 2 * day)
        assertEquals(title, row.displayLabel(retentionDays = 30, nowMs = now))
    }

    @Test
    fun swipeCreatedOverrideOlderThanRetentionHidesItsTitleDerivedLabel() {
        val row = OverrideRow(1, "com.whatsapp", senderKey = title, label = title, fromInbox = true, createdAtEpochMs = now - 31 * day)
        assertEquals(EXPIRED_SENDER_LABEL, row.displayLabel(retentionDays = 30, nowMs = now))
    }

    @Test
    fun manuallyAddedAppOverrideNeverExpires() {
        // A manual entry's label is an app label the user picked, not content,
        // so age is irrelevant to it.
        val row = OverrideRow(1, "com.whatsapp", senderKey = null, label = "WhatsApp", fromInbox = false, createdAtEpochMs = now - 9_999 * day)
        assertEquals("WhatsApp", row.displayLabel(retentionDays = 30, nowMs = now))
    }

    @Test
    fun recentSwipeCreatedOverrideStillShowsItsSender() {
        val row = OverrideRow(1, "com.whatsapp", senderKey = title, label = title, fromInbox = true, createdAtEpochMs = now - 1 * day)
        assertEquals(title, row.displayLabel(retentionDays = 30, nowMs = now))
    }
}
