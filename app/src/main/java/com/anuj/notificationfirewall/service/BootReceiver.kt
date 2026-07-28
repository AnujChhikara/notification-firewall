// service/BootReceiver.kt
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

private const val TAG = "BootReceiver"

/**
 * After a reboot: re-arm the profile-boundary alarm and, if a window is active
 * right now, start the keep-alive service + DND. WorkManager restores its own
 * periodic jobs. BOOT_COMPLETED is a blessed context for starting a foreground
 * service, so canStartForeground = true.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var reconciler: ProfileStateReconciler
    @Inject lateinit var scheduler: ProfileScheduler

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                scheduler.rescheduleAll()
                reconciler.reconcileFromDb(canStartForeground = true)
            } catch (e: Exception) {
                Log.e(TAG, "Boot reconcile failed", e)
            } finally {
                pending.finish()
            }
        }
    }
}
