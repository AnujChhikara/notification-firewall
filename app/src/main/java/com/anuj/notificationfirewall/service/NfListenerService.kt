// service/NfListenerService.kt
package com.anuj.notificationfirewall.service

import android.app.Notification
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import com.anuj.notificationfirewall.data.db.NotificationRecordEntity
import com.anuj.notificationfirewall.data.db.dao.NotificationDao
import com.anuj.notificationfirewall.domain.model.IncomingNotification
import com.anuj.notificationfirewall.domain.wall.ContentShape
import com.anuj.notificationfirewall.domain.wall.WallBucket
import com.anuj.notificationfirewall.domain.wall.WallDecisionSource
import com.anuj.notificationfirewall.domain.wall.WallPipeline
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "NfListenerService"

/**
 * The heart of the firewall: the bound NotificationListenerService that sees
 * every posted notification, runs it through the [WallPipeline], records the
 * ones it manages, and hands the decision to the [BucketExecutor].
 *
 * Loop guard: our own RING re-posts are posted by THIS app, so they come back
 * through [onNotificationPosted]; we skip our own package to avoid an infinite
 * re-processing loop.
 */
@AndroidEntryPoint
class NfListenerService : NotificationListenerService() {

    @Inject lateinit var wallPipeline: WallPipeline
    @Inject lateinit var bucketExecutor: BucketExecutor
    @Inject lateinit var notificationMapper: NotificationMapper
    @Inject lateinit var notificationDao: NotificationDao
    @Inject lateinit var armingController: ArmingController
    @Inject lateinit var healthMonitor: HealthMonitor
    @Inject lateinit var securePrefs: com.anuj.notificationfirewall.data.prefs.SecurePrefs

    // Off-main scope for the DB + network (Jev) + PackageManager work triggered by
    // each posted notification. SupervisorJob so one failed decision never tears
    // down the scope for subsequent notifications. Cancelled in onDestroy.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // sbn.key -> hash of the last (title|text) we LOGGED for it. Apps re-post the
    // same notification many times as they update it, which would otherwise create
    // 4-5 identical Inbox rows; we still re-run the wall each time (so an updated
    // notification is re-cancelled) but only log a row when the content changes.
    private val lastLoggedSignature = ConcurrentHashMap<String, Int>()

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "Listener connected")
        securePrefs.listenerConnected = true
        securePrefs.everConnected = true
        // Only a bound listener can cancel notifications posted by *other* apps;
        // register ourselves so BucketExecutor can cancel originals. See
        // NotificationCanceller kdoc.
        bucketExecutor.canceller = NotificationCanceller { key -> cancelNotification(key) }
        scope.launch { healthMonitor.refresh() }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.i(TAG, "Listener disconnected")
        securePrefs.listenerConnected = false
        bucketExecutor.canceller = null
        // Ask the platform to rebind, and surface the outage if it can't.
        runCatching { requestRebind(ComponentName(this, NfListenerService::class.java)) }
        scope.launch { healthMonitor.refresh() }
    }

    /**
     * Any change to system DND — ours or the user's — lands here while the
     * listener is bound. ArmingController recomputes from the live filter, so
     * the toggle can never report an armed wall that is not actually armed.
     */
    override fun onInterruptionFilterChanged(interruptionFilter: Int) {
        super.onInterruptionFilterChanged(interruptionFilter)
        armingController.onSystemDndChanged()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn ?: return
        // Loop guard: never re-process our own re-posts.
        if (notification.packageName == packageName) return
        if (shouldIgnore(notification)) return

        scope.launch {
            try {
                handle(notification)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to process ${notification.key}", e)
            }
        }
    }

    /**
     * Skip notifications that aren't real "incoming messages":
     * - group summaries (the "N new messages" umbrella, logged separately from
     *   the individual messages -> duplicates),
     * - ongoing / foreground-service notifications (media players, downloads, and
     *   the transient "Sending…" notification an app shows while YOU send a
     *   message/GIF -> was being captured by mistake).
     */
    private fun shouldIgnore(sbn: StatusBarNotification): Boolean {
        val flags = sbn.notification.flags
        val isGroupSummary = flags and Notification.FLAG_GROUP_SUMMARY != 0
        val isOngoing = flags and Notification.FLAG_ONGOING_EVENT != 0
        @Suppress("DEPRECATION")
        val isForegroundService = flags and Notification.FLAG_FOREGROUND_SERVICE != 0
        return isGroupSummary || isOngoing || isForegroundService
    }

    private suspend fun handle(sbn: StatusBarNotification) {
        val incoming = notificationMapper.map(sbn)

        // Disarmed: a down wall observes but does not judge. It never calls
        // Jev -- a verdict for a notification the wall won't act on would be
        // discarded, and worse, a Jev failure while disarmed used to get
        // stored as bucket=RING/pendingClassification=true, which
        // ReclassifyWorker would later rewrite from the threshold, turning a
        // notification that actually rang into a recorded SILENCE. A disarmed
        // wall also never blocks or re-posts. It still logs the record -- with
        // contentShape computed locally, at zero Jev cost -- so stats and Ask
        // stay continuous across arm/disarm.
        if (!armingController.isArmed()) {
            logIfNewContent(sbn, incoming) {
                NotificationRecordEntity(
                    packageName = incoming.packageName,
                    appLabel = incoming.appLabel,
                    title = incoming.title,
                    text = incoming.text,
                    timestampEpochMs = sbn.postTime,
                    senderKey = incoming.senderKey.ifBlank { null },
                    contentShape = ContentShape.of(incoming.title, incoming.text),
                    importanceScore = null,
                    biasApplied = 0f,
                    category = null,
                    isTimeSensitive = null,
                    isFromHuman = null,
                    needsAction = null,
                    jevConfidence = null,
                    decisionSource = WallDecisionSource.PENDING,
                    bucket = WallBucket.RING,
                    pendingClassification = false,
                    textPurgedAt = null,
                    isRead = false,
                )
            }
            return
        }

        val decision = wallPipeline.decide(
            n = incoming,
            channelId = sbn.notification.channelId,
            isReplyCapable = sbn.notification.actions?.any { it.remoteInputs?.isNotEmpty() == true } == true,
        )

        logIfNewContent(sbn, incoming) {
            NotificationRecordEntity(
                packageName = incoming.packageName,
                appLabel = incoming.appLabel,
                title = incoming.title,
                text = incoming.text,
                timestampEpochMs = sbn.postTime,
                senderKey = incoming.senderKey.ifBlank { null },
                contentShape = decision.contentShape,
                importanceScore = decision.verdict?.importance,
                biasApplied = decision.biasApplied,
                category = decision.verdict?.category,
                isTimeSensitive = decision.verdict?.isTimeSensitive,
                isFromHuman = decision.verdict?.isFromHuman,
                needsAction = decision.verdict?.needsAction,
                jevConfidence = decision.verdict?.confidence,
                decisionSource = decision.source,
                bucket = decision.bucket,
                pendingClassification = decision.pendingClassification,
                textPurgedAt = null,
                isRead = false,
            )
        }

        bucketExecutor.execute(decision, sbn)
    }

    /**
     * Apps re-post the same notification many times as they update it, which
     * would otherwise create 4-5 identical Inbox rows; only log a row when the
     * (title, text) content actually changed since the last log for this key.
     * [entity] is built lazily so a duplicate never pays for a record it will
     * throw away.
     */
    private suspend fun logIfNewContent(
        sbn: StatusBarNotification,
        incoming: IncomingNotification,
        entity: () -> NotificationRecordEntity,
    ) {
        // Kotlin escape for the NUL byte, not a literal one: same character at
        // runtime and the same hash input as before, but keeps this file text
        // (a literal NUL makes git classify the whole file as binary, which
        // makes every diff of it unreviewable).
        val signature = (incoming.title + "\u0000" + incoming.text).hashCode()
        val isNewContent = lastLoggedSignature.put(sbn.key, signature) != signature
        if (isNewContent) {
            notificationDao.insert(entity())
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        // Forget the dedupe signature once a notification is gone so the map does
        // not grow unbounded and a genuinely new notification reusing the key later
        // is logged again.
        sbn?.key?.let { lastLoggedSignature.remove(it) }
    }

    override fun onDestroy() {
        scope.cancel()
        bucketExecutor.canceller = null
        super.onDestroy()
    }
}
