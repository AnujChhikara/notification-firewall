// service/ProfileStateReconciler.kt
package com.anuj.notificationfirewall.service

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.anuj.notificationfirewall.data.db.dao.ProfileDao
import com.anuj.notificationfirewall.data.mapper.toActiveProfile
import com.anuj.notificationfirewall.domain.profile.ActiveProfile
import com.anuj.notificationfirewall.domain.profile.ProfileManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ProfileStateReconciler"

/**
 * Single source of truth for "make the phone match the active profile." Given the
 * profile in effect it (a) runs/stops the keep-alive foreground service and
 * (b) reconciles DND — so the two can never disagree.
 *
 * [canStartForeground] guards the FGS *start*: Android forbids starting a
 * foreground service from most background contexts, so only callers reached via
 * a blessed trigger (an exact alarm, boot, or the app being in the foreground)
 * pass true. Stopping the service and reconciling DND are allowed from anywhere.
 */
@Singleton
class ProfileStateReconciler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profileDao: ProfileDao,
    private val profileManager: ProfileManager,
    private val dndController: DndController,
) {
    fun reconcile(active: ActiveProfile?, canStartForeground: Boolean) {
        if (active != null) {
            if (canStartForeground) startKeepAlive(active.name)
        } else {
            stopKeepAlive()
        }
        dndController.reconcile(active)
    }

    suspend fun reconcileFromDb(canStartForeground: Boolean) {
        val profiles = profileDao.enabledProfiles().map { it.toActiveProfile() }
        val active = profileManager.activeProfile(profiles, ZonedDateTime.now())
        reconcile(active, canStartForeground)
    }

    private fun startKeepAlive(profileName: String) {
        val intent = Intent(context, KeepAliveService::class.java)
            .putExtra(KeepAliveService.EXTRA_PROFILE_NAME, profileName)
        runCatching { ContextCompat.startForegroundService(context, intent) }
            .onFailure { Log.w(TAG, "Could not start keep-alive service", it) }
    }

    private fun stopKeepAlive() {
        runCatching { context.stopService(Intent(context, KeepAliveService::class.java)) }
    }
}
