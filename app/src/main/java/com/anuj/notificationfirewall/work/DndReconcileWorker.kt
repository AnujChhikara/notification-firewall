// work/DndReconcileWorker.kt
package com.anuj.notificationfirewall.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.anuj.notificationfirewall.data.db.dao.ProfileDao
import com.anuj.notificationfirewall.data.mapper.toActiveProfile
import com.anuj.notificationfirewall.domain.profile.ProfileManager
import com.anuj.notificationfirewall.service.DndController
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Periodic safety net that reconciles system DND with the active profile even
 * when no notifications are arriving — so DND turns OFF within ~15 min of a
 * profile window ending (and ON shortly after it begins) on an idle phone. The
 * listener also reconciles immediately on every notification and on connect.
 */
@HiltWorker
class DndReconcileWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val profileDao: ProfileDao,
    private val profileManager: ProfileManager,
    private val dndController: DndController,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val profiles = profileDao.enabledProfiles().map { it.toActiveProfile() }
        val active = profileManager.activeProfile(profiles, ZonedDateTime.now())
        dndController.reconcile(active)
        return Result.success()
    }

    companion object {
        private const val UNIQUE_NAME = "dnd-reconcile"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<DndReconcileWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
