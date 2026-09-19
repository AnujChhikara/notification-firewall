// service/KeepAliveService.kt
package com.anuj.notificationfirewall.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.app.Service
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.anuj.notificationfirewall.R
import com.anuj.notificationfirewall.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * A minimal foreground service whose only job is to pin the process while the
 * wall is armed, so aggressive OEMs don't kill the notification listener.
 * Shows one quiet ongoing notification.
 *
 * Self-verifying rather than trusting its caller's intent: every start checks
 * [ArmingController] itself and immediately stops if the wall is not armed, so
 * a stale or racing start can never pin the process when it shouldn't.
 */
@AndroidEntryPoint
class KeepAliveService : Service() {

    @Inject lateinit var armingController: ArmingController

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!armingController.isArmed()) {
            stopSelf()
            return START_NOT_STICKY
        }

        NfChannels.ensureStatus(this)

        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification: Notification = Notification.Builder(this, NfChannels.STATUS)
            .setContentTitle("Firewall active")
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
        private const val NOTIFICATION_ID = 7

        /** Requests the service start; it self-checks arming and no-ops if disarmed. */
        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, KeepAliveService::class.java))
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, KeepAliveService::class.java)) }
        }
    }
}
