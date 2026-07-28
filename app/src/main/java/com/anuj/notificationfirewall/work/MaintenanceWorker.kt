// work/MaintenanceWorker.kt
package com.anuj.notificationfirewall.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.anuj.notificationfirewall.service.HealthMonitor
import com.anuj.notificationfirewall.service.ProfileStateReconciler
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Periodic safety net (every 15 min): re-asserts profile state, re-arms the
 * boundary alarm in case one was dropped, and runs the health check. Runs in the
 * background so it can't START the keep-alive service — that's the boundary
 * alarm's job — but it reconciles DND, re-schedules, rebinds, and alerts.
 */
@HiltWorker
class MaintenanceWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val reconciler: ProfileStateReconciler,
    private val scheduler: ProfileScheduler,
    private val healthMonitor: HealthMonitor,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        reconciler.reconcileFromDb(canStartForeground = false)
        scheduler.rescheduleAll()
        healthMonitor.refresh()
        return Result.success()
    }

    companion object {
        private const val UNIQUE_NAME = "nf-maintenance"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<MaintenanceWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }
    }
}
