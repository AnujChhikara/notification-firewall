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
 * gap: without it, [SecurePrefs.dndSetByApp] ownership is never cleared when the
 * user turns DND off externally while unbound, and a later user-initiated DND
 * would be misreported as ARMED.
 *
 * DO NOT register this in AndroidManifest.xml. `ACTION_INTERRUPTION_FILTER_CHANGED`
 * is an implicit broadcast and is NOT on the platform's exemption list for
 * Android 8+ manifest-declared-receiver restrictions
 * (https://developer.android.com/develop/background-work/background-tasks/broadcasts/broadcast-exceptions).
 * A manifest `<receiver>` for it never fires on any device at minSdk 26+ — it
 * would look like a working safety net while silently doing nothing. Instead
 * this class is instantiated and registered at runtime, from
 * `NfApplication.onCreate()`, via `ContextCompat.registerReceiver(...,
 * RECEIVER_NOT_EXPORTED)`. Runtime registration is exempt from the implicit
 * broadcast restriction. `@AndroidEntryPoint` field injection still works: Hilt
 * injects [armingController] inside the generated `onReceive`, using the
 * `Context` the system passes at dispatch time, not the context the receiver
 * was constructed or registered with — so it is unaffected by manual
 * instantiation.
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
