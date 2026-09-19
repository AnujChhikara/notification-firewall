package com.anuj.notificationfirewall.domain.wall

enum class NotificationCategory {
    PROMOTION,
    PERSONAL_MESSAGE,
    TRANSACTIONAL,
    WORK,
    SOCIAL,
    NEWS,
    SYSTEM,
    DELIVERY,
    OTHER;

    companion object {
        /** Maps Jev's wire option name back to the enum, defaulting to OTHER. */
        fun fromWire(s: String): NotificationCategory =
            entries.firstOrNull { it.name.equals(s, ignoreCase = true) } ?: OTHER
    }
}

/**
 * One Jev judgement of one notification.
 *
 * [importance] is on a 1.0–5.0 scale (1 = pure noise, 5 = interrupt now).
 * Jev's Score primitive returns a 0-based value; [com.anuj.notificationfirewall.ai.jev.JevClient] shifts it.
 *
 * [isTimeSensitive], [isFromHuman] and [needsAction] are Nouls — probabilities
 * in 0.0–1.0. [confidence] is Jev's calibrated confidence in the importance
 * judgement, and gates whether this verdict may be cached.
 */
data class JevVerdict(
    val importance: Float,
    val category: NotificationCategory,
    val isTimeSensitive: Float,
    val isFromHuman: Float,
    val needsAction: Float,
    val confidence: Float,
)

/** The content and local signals sent to Jev as the evaluation state. */
data class JevState(
    val app: String,
    val channel: String?,
    val title: String,
    val text: String,
    val arrivedAtLocal: String,
    val isReplyCapable: Boolean,
    val isFromContact: Boolean,
)

class JevException(message: String, cause: Throwable? = null) : Exception(message, cause)

interface JevApi {
    suspend fun classify(state: JevState): JevVerdict
}
