package com.anuj.notificationfirewall.domain.wall

/**
 * What the wall did with a notification.
 *
 * DROP is reachable only from the user's explicit block list. Jev is never
 * allowed to produce it: a judgement the user cannot find and review is a
 * judgement they cannot trust, and an untrusted wall gets disarmed.
 */
enum class WallBucket { RING, SILENCE, DROP }

/**
 * Which stage of the pipeline produced the decision.
 *
 * EXPIRED means the record's text was purged by retention before a verdict
 * could ever be obtained for it -- distinct from LEGACY, which means the
 * record WAS judged, just by an earlier version of the app's scoring model.
 */
enum class WallDecisionSource { OTP, VIP, BLOCK, CALL, CACHE, JEV, PENDING, LEGACY, EXPIRED }

enum class OverrideKind { VIP, BLOCK }

/** Whether an override was added deliberately in Settings or accumulated from a swipe. */
enum class OverrideSource { MANUAL, SWIPE }
