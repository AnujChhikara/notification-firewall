// service/NfChannels.kt
package com.anuj.notificationfirewall.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/** Central place for the app's own notification channels (re-posts aside). */
object NfChannels {
    const val STATUS = "nf_status" // ongoing keep-alive notification
    const val ALERTS = "nf_alerts" // "firewall stopped" health alert
    const val DIGEST = "nf_digest" // daily summary

    fun ensureStatus(context: Context) {
        channel(context, STATUS, "Firewall status", NotificationManager.IMPORTANCE_LOW)
    }

    fun ensureAlerts(context: Context) {
        channel(context, ALERTS, "Firewall alerts", NotificationManager.IMPORTANCE_DEFAULT)
    }

    /**
     * IMPORTANCE_LOW, no sound: the daily digest is the payoff for the wall's
     * silence, not another interruption. A notification app that breaks its
     * own promise to make your phone quiet -- to tell you it did -- would be
     * its own punchline. This channel never bypasses DND (contrast
     * [ChannelManager.ringChannelId], which deliberately does).
     */
    fun ensureDigest(context: Context) {
        channel(context, DIGEST, "Daily digest", NotificationManager.IMPORTANCE_LOW)
    }

    private fun channel(context: Context, id: String, name: String, importance: Int) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(id) == null) {
            nm.createNotificationChannel(NotificationChannel(id, name, importance))
        }
    }
}
