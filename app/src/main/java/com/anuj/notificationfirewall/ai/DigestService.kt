package com.anuj.notificationfirewall.ai

import com.anuj.notificationfirewall.data.db.NotificationRecordEntity

/** Summarizes notifications missed during a Do Not Disturb profile window. */
interface DigestService {
    suspend fun summarize(records: List<NotificationRecordEntity>): String
}
