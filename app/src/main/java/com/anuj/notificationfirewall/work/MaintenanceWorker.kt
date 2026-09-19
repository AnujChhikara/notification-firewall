// work/MaintenanceWorker.kt
package com.anuj.notificationfirewall.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.anuj.notificationfirewall.service.ArmingController
import com.anuj.notificationfirewall.service.HealthMonitor
import com.anuj.notificationfirewall.service.KeepAliveService
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Periodic safety net (every 15 min): stops the keep-alive service if the wall
 * is no longer armed, and runs the health check. Runs in the background so it
 * can't START the keep-alive service — Android forbids starting a foreground
 * service from most background contexts — but stopping one is allowed from
 * anywhere, so this is a safety net against a stale keep-alive outliving a
 * disarm that happened while the app wasn't around to react to it.
 */
@HiltWorker
class MaintenanceWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted params: WorkerParameters,
    private val armingController: ArmingController,
    private val healthMonitor: HealthMonitor,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!armingController.isArmed()) KeepAliveService.stop(appContext)
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
