// service/BootReceiver.kt
package com.anuj.notificationfirewall.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "BootReceiver"

/**
 * After a reboot: recompute the wall's armed state from the live system DND
 * filter — never read from storage, per [ArmingController] — so the reported
 * state is correct immediately, then re-run the health check. WorkManager
 * restores its own periodic jobs on its own.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var armingController: ArmingController
    @Inject lateinit var healthMonitor: HealthMonitor

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                armingController.onSystemDndChanged()
                healthMonitor.refresh()
            } catch (e: Exception) {
                Log.e(TAG, "Boot reconcile failed", e)
            } finally {
                pending.finish()
            }
        }
    }
}
