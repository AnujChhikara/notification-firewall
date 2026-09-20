package com.anuj.notificationfirewall.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.anuj.notificationfirewall.data.prefs.SecurePrefs
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Fires when a break-glass window's exact alarm goes off: clears the window
 * and re-arms.
 *
 * This is an explicit alarm [PendingIntent] delivery, not an implicit
 * broadcast, so the manifest-registered receiver below fires normally at
 * minSdk 26 -- unlike [DndChangeReceiver], which listens for an implicit
 * system broadcast and must be registered at runtime instead.
 *
 * Re-arming goes through [ArmingController.arm] (never by poking the filter
 * directly) so it renders whatever [ArmingController] actually decides --
 * including BLOCKED_NO_LISTENER if the listener has since disconnected.
 * [BreakGlassController.start] already released `dndSetByApp` ownership when
 * the window opened, so this call is not a no-op.
 */
@AndroidEntryPoint
class BreakGlassReceiver : BroadcastReceiver() {

    @Inject lateinit var armingController: ArmingController
    @Inject lateinit var securePrefs: SecurePrefs

    override fun onReceive(context: Context, intent: Intent) {
        securePrefs.breakGlassUntilMs = 0L
        armingController.arm()
    }
}
