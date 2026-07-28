// service/ProfileBoundaryReceiver.kt
package com.anuj.notificationfirewall.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.anuj.notificationfirewall.work.ProfileScheduler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "ProfileBoundaryReceiver"

/**
 * Fires at each profile-window boundary (exact alarm). Because it runs from an
 * alarm trigger it may legally start the keep-alive foreground service, so it
 * passes canStartForeground = true. Then it re-arms the next boundary alarm.
 */
@AndroidEntryPoint
class ProfileBoundaryReceiver : BroadcastReceiver() {

    @Inject lateinit var reconciler: ProfileStateReconciler
    @Inject lateinit var scheduler: ProfileScheduler

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_BOUNDARY) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                reconciler.reconcileFromDb(canStartForeground = true)
                scheduler.rescheduleAll()
            } catch (e: Exception) {
                Log.e(TAG, "Boundary reconcile failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_BOUNDARY = "com.anuj.notificationfirewall.PROFILE_BOUNDARY"
    }
}
