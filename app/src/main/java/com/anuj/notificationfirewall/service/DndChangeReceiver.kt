package com.anuj.notificationfirewall.service

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Catches DND changes while the listener service is unbound.
 *
 * NotificationListenerService.onInterruptionFilterChanged is the primary signal,
 * but it only fires while the listener is connected. This receiver covers the
 * gap, so the wall's reported state cannot drift during a rebind.
 */
@AndroidEntryPoint
class DndChangeReceiver : BroadcastReceiver() {

    @Inject lateinit var armingController: ArmingController

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED) {
            armingController.onSystemDndChanged()
        }
    }
}
