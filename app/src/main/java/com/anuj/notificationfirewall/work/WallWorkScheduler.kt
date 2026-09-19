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

    /** Pre-wall unique work name. See the cancelUniqueWork call below. */
    private const val LEGACY_MAINTENANCE = "nf-maintenance"

    fun scheduleAll(context: Context) {
        val wm = WorkManager.getInstance(context)

        // One-time migration: some installs predate WallWorkScheduler and
        // still have MaintenanceWorker scheduled under its old unique name.
        // MaintenanceWorker itself still exists, so leaving that entry alone
        // would run it twice — once under each name. Safe to call on every
        // install; a no-op once no work is registered under the old name.
        // Delete this line once no install predates the "wall-maintenance"
        // name.
        wm.cancelUniqueWork(LEGACY_MAINTENANCE)

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

        // 15 minutes because MaintenanceWorker's primary duty is the health
        // heartbeat that detects a killed/unbound NotificationListenerService
        // (see HealthMonitor.refresh() in MaintenanceWorker.doWork()) — that
        // detection latency is what sets this interval, and it is
        // WorkManager's minimum periodic interval besides. The retention
        // purge and cache eviction that also run on this worker are just
        // along for the ride: both are idempotent (purgeTextBefore filters
        // on textPurgedAt IS NULL, so after the first pass each day it scans
        // an index and returns 0) and cheap, so running them every 15
        // minutes instead of once a day costs effectively nothing. Do not
        // "optimize" this back down to a daily interval for retention's sake
        // — that would silently stretch listener-death detection to 24h.
        // UPDATE, not KEEP: this worker's cadence has already been revised
        // once (see the interval comment above), and UPDATE means a future
        // change to its schedule reaches existing installs on their next app
        // start instead of requiring another unique-name migration like the
        // one above for LEGACY_MAINTENANCE.
        wm.enqueueUniquePeriodicWork(
            MAINTENANCE,
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<MaintenanceWorker>(15, TimeUnit.MINUTES).build(),
        )
    }
}
