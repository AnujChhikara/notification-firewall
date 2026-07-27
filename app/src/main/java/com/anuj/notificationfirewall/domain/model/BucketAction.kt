package com.anuj.notificationfirewall.domain.model

enum class BucketAction {
    LET_THROUGH_AS_IS,
    LET_THROUGH_CUSTOM_SOUND,
    SILENCE,
    CAPTURE,
    // Only valid as a profile default action. Resolved by NotificationPipeline
    // and must never be returned as a final bucket.
    ASK_AI
}
