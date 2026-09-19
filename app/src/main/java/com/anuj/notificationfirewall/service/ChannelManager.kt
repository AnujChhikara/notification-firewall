// service/ChannelManager.kt
package com.anuj.notificationfirewall.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.RingtoneManager

private const val RING_CHANNEL_ID = "wall_ring"

/**
 * Lazily creates (and returns the id of) the Android NotificationChannel
 * BucketExecutor should re-post through.
 */
class ChannelManager(private val context: Context) {

    private val notificationManager: NotificationManager =
        requireNotNull(context.getSystemService(NotificationManager::class.java)) {
            "NotificationManager unavailable"
        }

    /**
     * The one channel wall re-posts ring on. Created with IMPORTANCE_HIGH and
     * DND bypass so it alerts while the wall holds the phone in DND — that
     * bypass is the entire reason a re-post can make a sound at all.
     *
     * `setBypassDnd(true)` is only honoured by the platform while this app
     * holds notification-policy access (`ACCESS_NOTIFICATION_POLICY`), and
     * the platform recomputes a channel's actual bypass state when that
     * access is revoked -- e.g. the user pulls the DND-access grant in
     * system Settings, which silently strips bypass from every channel this
     * app owns. `createNotificationChannel` is a no-op once a channel with
     * this id already exists (recreating it with the same settings does NOT
     * re-assert the ones the platform can override), so a channel that lost
     * bypass would stay broken forever -- every "important" re-post going
     * silent with no error anywhere. Checking `canBypassDnd()` here and
     * deleting-then-recreating when it's false is what actually re-asserts
     * the configuration instead of silently trusting a stale channel.
     */
    fun ringChannelId(): String {
        val id = RING_CHANNEL_ID
        val existing = notificationManager.getNotificationChannel(id)
        if (existing != null && !existing.canBypassDnd()) {
            notificationManager.deleteNotificationChannel(id)
        }
        if (notificationManager.getNotificationChannel(id) == null) {
            val channel = NotificationChannel(id, "Let through", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                    Notification.AUDIO_ATTRIBUTES_DEFAULT,
                )
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 300, 200, 300)
                setBypassDnd(true)
            }
            notificationManager.createNotificationChannel(channel)
        }
        return id
    }
}
