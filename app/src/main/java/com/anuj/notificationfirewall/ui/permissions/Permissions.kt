// ui/permissions/Permissions.kt
package com.anuj.notificationfirewall.ui.permissions

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** Snapshot of the five M1 grants (design §10). */
data class PermissionStatus(
    val notificationAccess: Boolean,
    val dndAccess: Boolean,
    val contacts: Boolean,
    val postNotifications: Boolean,
    val batteryExempt: Boolean,
    val hasApiKey: Boolean,
) {
    /** The two that actually gate a working firewall. */
    val coreReady: Boolean get() = notificationAccess && postNotifications
}

/** Pure permission/setting lookups plus the intents that let the user grant them. */
object Permissions {

    fun notificationAccessGranted(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context)
            .contains(context.packageName)

    fun dndAccessGranted(context: Context): Boolean =
        context.getSystemService(NotificationManager::class.java)
            ?.isNotificationPolicyAccessGranted == true

    fun contactsGranted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    fun postNotificationsGranted(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    fun batteryExempt(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName) == true

    fun status(context: Context, hasApiKey: Boolean): PermissionStatus = PermissionStatus(
        notificationAccess = notificationAccessGranted(context),
        dndAccess = dndAccessGranted(context),
        contacts = contactsGranted(context),
        postNotifications = postNotificationsGranted(context),
        batteryExempt = batteryExempt(context),
        hasApiKey = hasApiKey,
    )

    fun notificationAccessIntent(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)

    fun dndAccessIntent(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)

    @Suppress("BatteryLife")
    fun batteryExemptionIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))
}
