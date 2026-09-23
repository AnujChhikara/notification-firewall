package com.anuj.notificationfirewall.domain.wall

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallDetectorTest {

    @Test
    fun slackHuddleInvitesRing() {
        assertTrue(
            CallDetector.isLiveCallInvite(
                "com.Slack",
                "Kanhaiya Mohan",
                "Kanhaiya Mohan invited you to a huddle",
            ),
        )
        assertTrue(
            CallDetector.isLiveCallInvite(
                "com.Slack",
                "Anmol Rishi",
                "Anmol Rishi invited you to a huddle",
            ),
        )
    }

    @Test
    fun callPhrasingAcrossCommAppsRings() {
        assertTrue(CallDetector.isLiveCallInvite("com.whatsapp", "Mum", "Mum is calling you"))
        assertTrue(CallDetector.isLiveCallInvite("com.google.android.apps.tachyon", "Meet", "Incoming video call"))
        assertTrue(CallDetector.isLiveCallInvite("us.zoom.videomeetings", "Zoom", "Aarav is inviting you to join"))
    }

    @Test
    fun promoCallVocabularyFromOtherAppsDoesNotRing() {
        // The package gate is the safety case: marketing "live call" copy
        // must never earn a DND-bypass ring.
        assertFalse(
            CallDetector.isLiveCallInvite(
                "com.myntra",
                "Myntra Live",
                "Join our live call with celebrity stylists now",
            ),
        )
        assertFalse(
            CallDetector.isLiveCallInvite(
                "com.Slack",
                "Standup bot",
                "Join our live call with celebrity stylists now",
            ),
        )
    }

    @Test
    fun missedCallsDoNotRing() {
        // The call is over; buzzing for it has no upside, only annoyance.
        assertFalse(CallDetector.isLiveCallInvite("com.whatsapp", "Mum", "Missed voice call from Mum"))
        assertFalse(CallDetector.isLiveCallInvite("com.Slack", "Ayush", "You missed a huddle with Ayush"))
    }

    @Test
    fun ordinaryWorkMessagesDoNotRing() {
        assertFalse(
            CallDetector.isLiveCallInvite(
                "com.Slack",
                "Standup",
                "update status of items due for 21st Sep before our meeting",
            ),
        )
    }
}
