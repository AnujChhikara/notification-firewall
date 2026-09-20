package com.anuj.notificationfirewall.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Fires when a break-glass window's scheduled (or retry) exact alarm goes off.
 *
 * This is an explicit alarm [PendingIntent] delivery, not an implicit
 * broadcast, so the manifest-registered receiver below fires normally at
 * minSdk 26 -- unlike [DndChangeReceiver], which listens for an implicit
 * system broadcast and must be registered at runtime instead.
 *
 * All the logic -- including "only clear the deadline once arm() actually
 * reports ARMED, otherwise reschedule a retry" -- lives in
 * [BreakGlassController.handleExpiryAlarm], not here, for the same reason
 * [WallTileService.tileStateFor] is a free function: this codebase keeps
 * Android-framework entry points thin and puts the decision logic somewhere
 * a Robolectric test can reach directly, since there is no Hilt test harness
 * set up for exercising `@AndroidEntryPoint` components in this project's
 * unit tests.
 */
@AndroidEntryPoint
class BreakGlassReceiver : BroadcastReceiver() {

    @Inject lateinit var breakGlassController: BreakGlassController

    override fun onReceive(context: Context, intent: Intent) {
        breakGlassController.handleExpiryAlarm()
    }
}
