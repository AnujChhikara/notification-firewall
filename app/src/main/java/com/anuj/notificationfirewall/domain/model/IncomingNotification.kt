package com.anuj.notificationfirewall.domain.model

import java.time.Instant

data class IncomingNotification(
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val senderKey: String,
    val isFavoriteContact: Boolean,
    val emailFromDomain: String?,
    val postedAt: Instant
)
