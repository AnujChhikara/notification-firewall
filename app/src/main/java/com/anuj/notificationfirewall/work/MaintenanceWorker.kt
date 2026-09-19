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
import com.anuj.notificationfirewall.service.HealthMonitor
import com.anuj.notificationfirewall.service.KeepAliveService
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

private const val TAG = "MaintenanceWorker"

/**
 * Periodic safety net (every day, scheduled by [WallWorkScheduler]): stops the
 * keep-alive service if the wall is no longer armed, runs the health check,
 * purges notification text past the retention window, and evicts stale cache
 * entries. Runs in the background so it can't START the keep-alive service —
 * Android forbids starting a foreground service from most background
 * contexts — but stopping one is allowed from anywhere, so this is a safety
 * net against a stale keep-alive outliving a disarm that happened while the
 * app wasn't around to react to it.
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
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!armingController.isArmed()) KeepAliveService.stop(appContext)
        healthMonitor.refresh()

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
