// service/BucketExecutor.kt
package com.anuj.notificationfirewall.service

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.content.ContextCompat
import com.anuj.notificationfirewall.domain.model.BucketAction
import com.anuj.notificationfirewall.domain.model.SoundConfig

private const val TAG = "BucketExecutor"

// Fixed id used for every re-post; sbn.key is passed as the notify() tag,
// so (tag, id) together stay unique per original notification while
// updates to the same original notification update the same repost
// instead of stacking duplicates.
private const val REPOST_NOTIFICATION_ID = 1

/**
 * Cancels the *original* notification by key. Only a bound
 * NotificationListenerService can do this -- plain NotificationManager.cancel
 * only affects notifications posted by the calling app -- so BucketExecutor
 * cannot cancel the original notification on its own. The active listener
 * service (built in the follow-up listener task) registers itself here via
 * [BucketExecutor.canceller] from onListenerConnected and clears it from
 * onListenerDisconnected/onDestroy.
 */
fun interface NotificationCanceller {
    fun cancelNotification(key: String)
}

/**
 * Executes a bucket decision produced by NotificationPipeline: cancels
 * and/or re-posts the intercepted notification per [BucketAction].
 *
 * Documented trade-off: any re-posted notification is posted by THIS app,
 * not the original one, so it cannot carry the original app's action
 * PendingIntents (reply, mark-as-read, etc.) -- those are scoped to the
 * original app's process/components and cannot be re-issued by us. Only
 * title, text, and icons are carried over. LET_THROUGH_AS_IS is therefore
 * the only bucket that preserves native actions, because it is a pure
 * no-op that never touches the original notification.
 */
class BucketExecutor(
    private val context: Context,
    private val channelManager: ChannelManager,
) {
    private val notificationManager: NotificationManager =
        requireNotNull(context.getSystemService(NotificationManager::class.java)) {
            "NotificationManager unavailable"
        }

    /** See [NotificationCanceller] kdoc. Null when no listener is bound. */
    var canceller: NotificationCanceller? = null

    fun execute(result: PipelineResult, sbn: StatusBarNotification, soundConfig: SoundConfig?) {
        when (result.bucket) {
            BucketAction.LET_THROUGH_AS_IS -> {
                // onNotificationPosted also fires for UPDATES to the same
                // sbn.key. If an earlier update was bucketed SILENCE /
                // CUSTOM_SOUND we created our own re-post for this key; clear
                // it so the now-let-through original is not shadowed by a
                // stale silenced copy. Idempotent no-op when none exists.
                cancelOurRepost(sbn)
                // Otherwise a no-op: leave the original notification exactly
                // as posted so its native actions (e.g. quick-reply) work.
            }

            BucketAction.SILENCE -> {
                cancelOriginal(sbn)
                repost(sbn, channelManager.channelFor(sbn.packageName, null))
            }

            BucketAction.CAPTURE -> {
                // The record is already persisted by the listener before it
                // calls execute(); we only need to remove the notification
                // from the tray, nothing is re-posted.
                cancelOriginal(sbn)
                // Also clear any re-post from an earlier update of this key
                // (see LET_THROUGH_AS_IS) so CAPTURE truly leaves the tray
                // empty for this notification. Idempotent.
                cancelOurRepost(sbn)
            }

            BucketAction.LET_THROUGH_CUSTOM_SOUND -> {
                if (soundConfig == null) {
                    Log.w(
                        TAG,
                        "LET_THROUGH_CUSTOM_SOUND for ${sbn.key} arrived with no SoundConfig; " +
                            "downgrading to the silent channel",
                    )
                }
                cancelOriginal(sbn)
                repost(sbn, channelManager.channelFor(sbn.packageName, soundConfig))
            }

            BucketAction.ASK_AI -> {
                // Defensive only. NotificationPipeline always resolves
                // ASK_AI to a concrete bucket before a PipelineResult is
                // produced (see NotificationPipeline.decide), so this
                // should be unreachable. If it ever leaks through anyway,
                // fail safe to SILENCE rather than crash or leave an
                // unfiltered notification visible.
                Log.w(TAG, "ASK_AI reached BucketExecutor for ${sbn.key}; treating as SILENCE")
                cancelOriginal(sbn)
                repost(sbn, channelManager.channelFor(sbn.packageName, null))
            }
        }
    }

    /**
     * Cancels any notification THIS app previously re-posted for [sbn]'s key.
     * Used on bucket transitions (e.g. an update to a key previously bucketed
     * SILENCE/CUSTOM_SOUND now resolves to CAPTURE or LET_THROUGH_AS_IS) so a
     * stale re-post does not linger in the tray. Idempotent: a no-op when no
     * prior re-post exists.
     */
    private fun cancelOurRepost(sbn: StatusBarNotification) {
        notificationManager.cancel(sbn.key, REPOST_NOTIFICATION_ID)
    }

    private fun cancelOriginal(sbn: StatusBarNotification) {
        val active = canceller
        if (active == null) {
            Log.w(TAG, "No NotificationListenerService registered; cannot cancel ${sbn.key}")
            return
        }
        active.cancelNotification(sbn.key)
    }

    private fun repost(sbn: StatusBarNotification, channelId: String) {
        // Android 13+ requires the runtime POST_NOTIFICATIONS permission to
        // post any notification, including this re-post. The onboarding/
        // settings UI task is responsible for requesting it from the user;
        // here we just guard defensively so a not-yet-granted permission
        // degrades to "original stays cancelled, nothing re-posted"
        // instead of crashing the listener process with a SecurityException.
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "POST_NOTIFICATIONS not granted; cannot repost ${sbn.key}")
            return
        }

        val original = sbn.notification
        val extras = original.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)
        val text = extras?.getCharSequence(Notification.EXTRA_TEXT)

        // original.smallIcon is required on every posted notification in
        // practice, but fall back to a framework icon defensively since
        // this app does not yet ship its own notification icon asset.
        val smallIcon = original.smallIcon
            ?: Icon.createWithResource(context, android.R.drawable.ic_dialog_info)

        val builder = Notification.Builder(context, channelId)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(smallIcon)
            .setAutoCancel(true)

        // Sound/vibration/DND-bypass are channel-level settings (already
        // applied by ChannelManager when it created channelId); nothing
        // further to set on the builder for those.
        original.getLargeIcon()?.let { largeIcon -> builder.setLargeIcon(largeIcon) }

        notificationManager.notify(sbn.key, REPOST_NOTIFICATION_ID, builder.build())
    }
}
