package com.anuj.notificationfirewall.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Registers the wall's two background jobs. Idempotent — safe to call on
 * every app start. This is the single owner of [MaintenanceWorker]'s
 * schedule; [MaintenanceWorker] itself no longer exposes a `schedule()`
 * entry point, so there is exactly one unique work name for it.
 */
object WallWorkScheduler {

    private const val RECLASSIFY = "wall-reclassify"
    private const val MAINTENANCE = "wall-maintenance"

    fun scheduleAll(context: Context) {
        val wm = WorkManager.getInstance(context)

        wm.enqueueUniquePeriodicWork(
            RECLASSIFY,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ReclassifyWorker>(30, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build(),
        )

        wm.enqueueUniquePeriodicWork(
            MAINTENANCE,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<MaintenanceWorker>(1, TimeUnit.DAYS).build(),
        )
    }
}
