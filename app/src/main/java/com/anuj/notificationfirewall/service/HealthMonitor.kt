// service/HealthMonitor.kt
package com.anuj.notificationfirewall.service

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import androidx.core.content.ContextCompat
import com.anuj.notificationfirewall.R
import com.anuj.notificationfirewall.data.db.dao.ProfileDao
import com.anuj.notificationfirewall.data.prefs.SecurePrefs
import com.anuj.notificationfirewall.ui.MainActivity
import com.anuj.notificationfirewall.ui.permissions.Permissions
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private const val ALERT_ID = 99

/**
 * Checks whether the firewall can actually do its job and, when it can't, posts a
 * single "Firewall stopped · tap to fix" notification that auto-clears once
 * healthy. Also nudges the listener to rebind if access is on but it dropped.
 */
@Singleton
class HealthMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val securePrefs: SecurePrefs,
    private val profileDao: ProfileDao,
) {
    suspend fun refresh(): HealthState {
        val accessOn = Permissions.notificationAccessGranted(context)
        val connected = securePrefs.listenerConnected

        // Access is on but the listener isn't bound → ask the platform to rebind.
        if (accessOn && !connected) {
            runCatching {
                NotificationListenerService.requestRebind(
                    ComponentName(context, NfListenerService::class.java),
                )
            }
        }

        val flags = HealthFlags(
            notificationAccess = accessOn,
            listenerConnected = connected,
            postNotifications = Permissions.postNotificationsGranted(context),
            needsDndAccess = profileDao.enabledProfiles().any { it.autoDnd },
            dndAccess = Permissions.dndAccessGranted(context),
            batteryExempt = Permissions.batteryExempt(context),
            exactAlarms = Permissions.exactAlarmsAllowed(context),
        )
        val state = HealthEvaluator.evaluate(flags)

        // Don't cry "stopped" over a listener that simply hasn't connected for the
        // first time yet (fresh install, access not granted / just granted).
        val brokenByFirstConnect = state.level == HealthLevel.BROKEN &&
            accessOn && !connected && !securePrefs.everConnected

        if (state.level == HealthLevel.BROKEN && !brokenByFirstConnect) {
            postBroken(state.reason ?: "The firewall isn't running")
        } else {
            cancelBroken()
        }
        return state
    }

    private fun postBroken(reason: String) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        NfChannels.ensureAlerts(context)
        val open = PendingIntent.getActivity(
            context, 1,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, NfChannels.ALERTS)
            .setContentTitle("Firewall stopped")
            .setContentText("$reason · tap to fix")
            .setStyle(Notification.BigTextStyle().bigText("$reason. Tap to fix."))
            .setSmallIcon(R.drawable.ic_status)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOngoing(false)
            .build()
        context.getSystemService(NotificationManager::class.java)?.notify(ALERT_ID, notification)
    }

    private fun cancelBroken() {
        context.getSystemService(NotificationManager::class.java)?.cancel(ALERT_ID)
    }
}
