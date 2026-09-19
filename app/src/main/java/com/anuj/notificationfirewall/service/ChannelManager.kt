// service/ChannelManager.kt
package com.anuj.notificationfirewall.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import com.anuj.notificationfirewall.domain.model.SoundConfig

private const val RING_CHANNEL_ID = "wall_ring"

/**
 * Lazily creates (and returns the id of) the Android NotificationChannel
 * BucketExecutor should re-post through.
 *
 * A NotificationChannel's sound/vibration/importance are immutable once
 * created (Android disallows apps from changing that behavior after the
 * fact -- only the user can, from system settings), so each distinct
 * SoundConfig for a given source package gets its own channel id rather
 * than mutating a shared one. soundConfig == null means the universal
 * silent channel (used for SILENCE and the ASK_AI defensive fallback).
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

    fun channelFor(packageName: String, soundConfig: SoundConfig?): String {
        val channelId = if (soundConfig == null) {
            SILENT_CHANNEL_ID
        } else {
            // hashCode is content-based (see SoundConfig) so the same
            // config always maps to the same channel id, and distinct
            // configs for the same source package get distinct channels.
            "nf_custom_${sanitize(packageName)}_${Integer.toHexString(soundConfig.hashCode())}"
        }
        ensureChannel(channelId, soundConfig)
        return channelId
    }

    private fun ensureChannel(channelId: String, soundConfig: SoundConfig?) {
        // getNotificationChannel is an in-memory lookup in the system
        // service, so no local cache is needed here; this also stays
        // correct across process restarts since channels persist in
        // system state, not in this class.
        if (notificationManager.getNotificationChannel(channelId) != null) return

        val channel = if (soundConfig == null) {
            NotificationChannel(
                channelId,
                "Silent notifications",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                setSound(null, null)
                enableVibration(false)
            }
        } else {
            NotificationChannel(
                channelId,
                "Custom sound notifications",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                setSound(soundConfig.soundUri, audioAttributes)
                if (soundConfig.vibrationPattern.isNotEmpty()) {
                    enableVibration(true)
                    vibrationPattern = soundConfig.vibrationPattern
                } else {
                    enableVibration(false)
                }
                // DND bypass requires the app to hold notification-policy
                // access at runtime (NotificationManager.isNotificationPolicyAccessGranted()),
                // a permission requested by the settings UI task. Setting
                // this flag is harmless without that access -- Android
                // simply ignores it until the user grants policy access,
                // at which point it takes effect without recreating the
                // channel.
                setBypassDnd(soundConfig.overrideDnd)
            }
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun sanitize(packageName: String): String =
        packageName.replace(Regex("[^a-zA-Z0-9_]"), "_")

    companion object {
        const val SILENT_CHANNEL_ID = "nf_silent"
    }
}
