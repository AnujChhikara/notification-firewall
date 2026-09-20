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
 * state is correct immediately; if that leaves the wall armed, start the
 * keep-alive service, since BOOT_COMPLETED is a documented exemption from
 * Android's background foreground-service-start restrictions. Then re-run the
 * health check. WorkManager restores its own periodic jobs on its own.
 *
 * [BreakGlassController.restoreAfterBoot] runs first because an
 * AlarmManager alarm does not survive a reboot: without this call, a phone
 * that restarts mid-break-glass-window would stay wide open forever with
 * nothing left to close it. It either re-arms immediately (window already
 * expired while powered off) or re-schedules the alarm the reboot discarded
 * (window still live) -- either way the ordinary reconcile below is safe to
 * run afterwards since it only ever acts on the live system filter.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var armingController: ArmingController
    @Inject lateinit var healthMonitor: HealthMonitor
    @Inject lateinit var breakGlassController: BreakGlassController

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                breakGlassController.restoreAfterBoot()
                armingController.onSystemDndChanged()
                if (armingController.isArmed()) KeepAliveService.start(context)
                healthMonitor.refresh()
            } catch (e: Exception) {
                Log.e(TAG, "Boot reconcile failed", e)
            } finally {
                pending.finish()
            }
        }
    }
}
