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
     */
    fun ringChannelId(): String {
        val id = RING_CHANNEL_ID
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
