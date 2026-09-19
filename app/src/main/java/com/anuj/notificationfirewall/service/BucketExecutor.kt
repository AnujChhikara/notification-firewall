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
import com.anuj.notificationfirewall.domain.wall.WallBucket
import com.anuj.notificationfirewall.domain.wall.WallDecision

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
 * Executes a bucket decision produced by [com.anuj.notificationfirewall.domain.wall.WallPipeline]:
 * cancels and/or re-posts the intercepted notification per [WallDecision.bucket].
 *
 * Documented trade-off: any re-posted notification is posted by THIS app,
 * not the original one, so it cannot carry the original app's action
 * PendingIntents (reply, mark-as-read, etc.) -- those are scoped to the
 * original app's process/components and cannot be re-issued by us. Only
 * title, text, and icons are carried over. SILENCE is therefore the only
 * bucket that preserves native actions, because it leaves the original
 * notification untouched.
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

    fun execute(decision: WallDecision, sbn: StatusBarNotification) {
        when (decision.bucket) {
            WallBucket.RING -> {
                // Under DND the OS has already silenced the original, so a
                // re-post on the bypass channel is the only thing that can
                // actually alert. Cancel the silent original so the tray does
                // not show the same notification twice.
                //
                // The permission check MUST run here, before cancelOriginal(),
                // not be left solely to repost()'s own guard: repost() no-ops
                // silently when POST_NOTIFICATIONS is missing, and if we had
                // already cancelled the original by then the notification would
                // be destroyed outright -- worse than DROP, and inflicted on
                // exactly the notifications judged most important. Checking
                // first lets us degrade to the SILENCE behaviour instead, so the
                // original stays visible (just silent) rather than vanishing.
                // Do not re-inline this ahead of cancelOriginal.
                if (!hasPostPermission()) {
                    Log.w(
                        TAG,
                        "POST_NOTIFICATIONS not granted; degrading RING to SILENCE for " +
                            "${sbn.key} so the original is not lost",
                    )
                    cancelOurRepost(sbn)
                    return
                }
                cancelOriginal(sbn)
                repost(sbn, channelManager.ringChannelId())
            }

            WallBucket.SILENCE -> {
                // Leave the original in place. DND already stripped its sound
                // and heads-up, so it sits quietly in the shade exactly as the
                // user would expect, keeping the origin app's own actions.
                cancelOurRepost(sbn)
            }

            WallBucket.DROP -> {
                cancelOriginal(sbn)
                cancelOurRepost(sbn)
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

    private fun hasPostPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun repost(sbn: StatusBarNotification, channelId: String) {
        // Android 13+ requires the runtime POST_NOTIFICATIONS permission to
        // post any notification, including this re-post. The RING branch in
        // execute() already checks this before it ever calls repost(), so this
        // is defence in depth (e.g. a future caller of repost() added without
        // going through execute()) rather than the sole guard.
        if (!hasPostPermission()) {
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
