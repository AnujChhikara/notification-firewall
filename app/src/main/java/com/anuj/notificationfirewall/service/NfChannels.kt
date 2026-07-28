// service/NfChannels.kt
package com.anuj.notificationfirewall.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/** Central place for the app's own notification channels (re-posts aside). */
object NfChannels {
    const val STATUS = "nf_status" // ongoing keep-alive notification
    const val ALERTS = "nf_alerts" // "firewall stopped" health alert

    fun ensureStatus(context: Context) {
        channel(context, STATUS, "Firewall status", NotificationManager.IMPORTANCE_LOW)
    }

    fun ensureAlerts(context: Context) {
        channel(context, ALERTS, "Firewall alerts", NotificationManager.IMPORTANCE_DEFAULT)
    }

    private fun channel(context: Context, id: String, name: String, importance: Int) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(id) == null) {
            nm.createNotificationChannel(NotificationChannel(id, name, importance))
        }
    }
}
