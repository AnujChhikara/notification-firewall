// service/NfListenerService.kt
package com.anuj.notificationfirewall.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.anuj.notificationfirewall.data.db.NotificationRecordEntity
import com.anuj.notificationfirewall.data.db.dao.NotificationDao
import com.anuj.notificationfirewall.data.db.dao.ProfileDao
import com.anuj.notificationfirewall.data.db.dao.RuleDao
import com.anuj.notificationfirewall.data.mapper.toActiveProfile
import com.anuj.notificationfirewall.data.mapper.toRule
import com.anuj.notificationfirewall.domain.model.BucketAction
import com.anuj.notificationfirewall.domain.profile.ActiveProfile
import com.anuj.notificationfirewall.domain.rules.Rule
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.ZonedDateTime
import javax.inject.Inject

private const val TAG = "NfListenerService"

/**
 * The heart of the firewall: the bound NotificationListenerService that sees
 * every posted notification, runs it through the [NotificationPipeline], records
 * the ones it manages, and hands the decision to the [BucketExecutor].
 *
 * Loop guard: our own SILENCE / CUSTOM_SOUND re-posts are posted by THIS app, so
 * they come back through [onNotificationPosted]; we skip our own package to avoid
 * an infinite re-processing loop.
 */
@AndroidEntryPoint
class NfListenerService : NotificationListenerService() {

    @Inject lateinit var pipeline: NotificationPipeline
    @Inject lateinit var bucketExecutor: BucketExecutor
    @Inject lateinit var notificationMapper: NotificationMapper
    @Inject lateinit var notificationDao: NotificationDao
    @Inject lateinit var profileDao: ProfileDao
    @Inject lateinit var ruleDao: RuleDao

    // Off-main scope for the DB + network (AI) + PackageManager work triggered by
    // each posted notification. SupervisorJob so one failed decision never tears
    // down the scope for subsequent notifications. Cancelled in onDestroy.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "Listener connected")
        // Only a bound listener can cancel notifications posted by *other* apps;
        // register ourselves so BucketExecutor can cancel originals. See
        // NotificationCanceller kdoc.
        bucketExecutor.canceller = NotificationCanceller { key -> cancelNotification(key) }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.i(TAG, "Listener disconnected")
        bucketExecutor.canceller = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn ?: return
        // Loop guard: never re-process our own re-posts.
        if (notification.packageName == packageName) return

        scope.launch {
            try {
                handle(notification)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to process ${notification.key}", e)
            }
        }
    }

    private suspend fun handle(sbn: StatusBarNotification) {
        val incoming = notificationMapper.map(sbn)

        val profiles: List<ActiveProfile> = profileDao.enabledProfiles().map { it.toActiveProfile() }
        // The pipeline selects the active profile internally and then asks for
        // that profile's rules via a *non-suspend* lambda, so we pre-load the
        // enabled profiles' rules here (small N) into a map it can index.
        val rulesByProfile: Map<Long, List<Rule>> = profiles.associate { profile ->
            profile.id to ruleDao.rulesForProfile(profile.id).map { it.toRule() }
        }

        val result = pipeline.decide(
            n = incoming,
            profiles = profiles,
            rulesByProfile = { id -> rulesByProfile[id].orEmpty() },
            at = ZonedDateTime.now(),
        )

        // Persist only the buckets the inbox / wake-up digest care about
        // (CAPTURE + SILENCE); NotificationDao.observeCaptured() is unfiltered,
        // so recording pass-through / let-through notifications would pollute it.
        // Must happen before execute(): the CAPTURE branch only removes the
        // notification from the tray and relies on the record already existing.
        if (result.bucket == BucketAction.CAPTURE || result.bucket == BucketAction.SILENCE) {
            notificationDao.insert(
                NotificationRecordEntity(
                    packageName = incoming.packageName,
                    appLabel = incoming.appLabel,
                    title = incoming.title,
                    text = incoming.text,
                    timestampEpochMs = sbn.postTime,
                    senderKey = incoming.senderKey.ifBlank { null },
                    activeProfileId = result.activeProfileId,
                    matchedRuleId = result.matchedRuleId,
                    decisionSource = result.source,
                    bucket = result.bucket,
                    aiUrgent = result.verdict?.urgent,
                    aiReason = result.verdict?.reason,
                    isRead = false,
                ),
            )
        }

        // soundConfig is null for M1: no CUSTOM_SOUND rules can be authored until
        // the rule-builder UI (and its SoundConfig persistence) lands, and there
        // is no SoundConfig JSON decoder yet. BucketExecutor already downgrades a
        // null-config CUSTOM_SOUND to the silent channel with a warning.
        bucketExecutor.execute(result, sbn, soundConfig = null)
    }

    override fun onDestroy() {
        scope.cancel()
        bucketExecutor.canceller = null
        super.onDestroy()
    }
}
