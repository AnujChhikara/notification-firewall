package com.anuj.notificationfirewall.domain.wall

/**
 * Detects live synchronous-communication invites locally, with no network call.
 *
 * A huddle/call invite that scores 4.02 instead of 4.05 is not a meaningfully
 * different notification, but a pure score gate turns it into "never buzzed".
 * Real-time invites expire: unlike a newsletter, seeing one an hour later is
 * useless. So this detector sits in front of Jev and rings, the same shape
 * as [OtpDetector].
 *
 * Two deliberate restrictions keep a loud DND-bypass ring safe:
 *
 * - package gate: only known communication apps. A promo that says "join our
 *   live call" from a shopping app must never ring.
 * - live-only phrasing: "missed call" is excluded on purpose. The call is
 *   already over; buzzing for it has no upside, only annoyance.
 *
 * Sits BELOW the user's block/VIP lists (an explicit instruction outranks a
 * heuristic) and ABOVE cache/Jev (a live invite must never be answered from
 * a stale verdict). See [WallPipeline].
 */
object CallDetector {

    private val PHRASE = Regex(
        """\b(invited you to a huddle|started a huddle|huddle is starting|""" +
            """is calling you|incoming (voice|video )?call|is inviting you to join)\b""",
        RegexOption.IGNORE_CASE,
    )

    private val CALL_PACKAGES = setOf(
        "com.Slack",
        "com.microsoft.teams",
        "com.google.android.apps.tachyon",
        "us.zoom.videomeetings",
        "com.whatsapp",
        "org.telegram.messenger",
        "com.facebook.orca",
        "com.skype.raider",
    )

    fun isLiveCallInvite(packageName: String, title: String, text: String): Boolean =
        packageName in CALL_PACKAGES && PHRASE.containsMatchIn("$title $text")
}
