// service/KeepAliveService.kt
package com.anuj.notificationfirewall.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.app.Service
import android.os.IBinder
import com.anuj.notificationfirewall.R
import com.anuj.notificationfirewall.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint

/**
 * A minimal foreground service whose only job is to pin the process while a
 * profile window is active, so aggressive OEMs don't kill the notification
 * listener. Shows one quiet ongoing notification. Started/stopped by
 * [ProfileStateReconciler]; never runs when no profile is active.
 */
@AndroidEntryPoint
class KeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val profileName = intent?.getStringExtra(EXTRA_PROFILE_NAME)
        NfChannels.ensureStatus(this)

        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification: Notification = Notification.Builder(this, NfChannels.STATUS)
            .setContentTitle(profileName?.let { "$it active" } ?: "Firewall active")
            .setContentText("Filtering notifications")
            .setSmallIcon(R.drawable.ic_status)
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()

        // The foreground-service type comes from the manifest declaration
        // (specialUse), so the 2-arg call is valid on all supported versions.
        startForeground(NOTIFICATION_ID, notification)
        return START_STICKY
    }

    companion object {
        const val EXTRA_PROFILE_NAME = "profile_name"
        private const val NOTIFICATION_ID = 7
    }
}
