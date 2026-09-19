package com.anuj.notificationfirewall.domain.wall

/**
 * What the wall did with a notification.
 *
 * DROP is reachable only from the user's explicit block list. Jev is never
 * allowed to produce it: a judgement the user cannot find and review is a
 * judgement they cannot trust, and an untrusted wall gets disarmed.
 */
enum class WallBucket { RING, SILENCE, DROP }

/** Which stage of the pipeline produced the decision. */
enum class WallDecisionSource { OTP, VIP, BLOCK, CACHE, JEV, PENDING, LEGACY }

enum class OverrideKind { VIP, BLOCK }

/** Whether an override was added deliberately in Settings or accumulated from a swipe. */
enum class OverrideSource { MANUAL, SWIPE }
