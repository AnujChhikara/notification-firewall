package com.anuj.notificationfirewall.ai

import com.anuj.notificationfirewall.domain.model.IncomingNotification
import com.anuj.notificationfirewall.domain.model.Verdict

interface ImportanceService {
    suspend fun classify(n: IncomingNotification, profileName: String): Verdict
}
