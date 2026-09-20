// work/MaintenanceWorker.kt
package com.anuj.notificationfirewall.work

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.anuj.notificationfirewall.data.db.dao.NotificationDao
import com.anuj.notificationfirewall.data.prefs.WallSettings
import com.anuj.notificationfirewall.domain.wall.VerdictCache
import com.anuj.notificationfirewall.service.ArmingController
import com.anuj.notificationfirewall.service.BreakGlassController
import com.anuj.notificationfirewall.service.HealthMonitor
import com.anuj.notificationfirewall.service.KeepAliveService
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

private const val TAG = "MaintenanceWorker"

/**
 * Periodic safety net, scheduled every 15 minutes by [WallWorkScheduler] —
 * WorkManager's minimum periodic interval. That cadence is set by
 * [healthMonitor]'s heartbeat, which detects a killed or unbound
 * `NotificationListenerService`: stretching this interval would silently
 * stretch listener-death detection right along with it, up to 24 hours if
 * this ran only daily. Do not lengthen this interval for the retention
 * purge's sake — that work only rides along here because it is cheap and
 * idempotent, not because it needs its own cadence.
 *
 * Each run: syncs the keep-alive service to the current armed state, runs the
 * health check, reconciles any pending break-glass window (the backstop for
 * BreakGlassController.reconcile() when the listener never reconnects on its
 * own), purges notification text past the retention window, and evicts stale
 * cache entries.
 *
 * The keep-alive sync is symmetric — start when armed, stop when not — rather
 * than stop-only, because [ArmingController.arm]/[ArmingController.disarm]
 * only react to explicit arm/disarm transitions. If the wall is already armed
 * and the OS kills the foreground service behind its back (Funtouch OS on the
 * target hardware does exactly this), nothing else ever restarts it: this
 * worker is the only thing that runs on a schedule regardless of whether the
 * app is open. [KeepAliveService.start] self-verifies armed state on every
 * start and no-ops if not armed, so calling it here on every run — including
 * when it's already running — is safe and idempotent.
 */
@HiltWorker
class MaintenanceWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted params: WorkerParameters,
    private val armingController: ArmingController,
    private val healthMonitor: HealthMonitor,
    private val notificationDao: NotificationDao,
    private val verdictCache: VerdictCache,
    private val settings: WallSettings,
    private val breakGlassController: BreakGlassController,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (armingController.isArmed()) KeepAliveService.start(appContext) else KeepAliveService.stop(appContext)
        healthMonitor.refresh()
        // Backstop for BreakGlassController.reconcile(): the fast path is
        // NfListenerService.onListenerConnected(), but if the listener never
        // reconnects on its own (permanently revoked access) or an alarm was
        // silently cancelled by the platform while the listener was already
        // connected, this periodic 15-minute run is the only thing left that
        // can still close (or keep alive the alarm for) an open window.
        breakGlassController.reconcile()

        // 0 means never purge.
        val retentionDays = settings.textRetentionDays
        if (retentionDays > 0) {
            val now = System.currentTimeMillis()
            val cutoff = now - retentionDays * 24L * 60 * 60 * 1000
            val purged = notificationDao.purgeTextBefore(cutoff, now)
            Log.i(TAG, "Purged text from $purged notification records")
        }
        val evicted = verdictCache.evictStale(maxAgeDays = 90)
        Log.i(TAG, "Evicted $evicted stale cache entries")

        return Result.success()
    }
}
