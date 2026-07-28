// service/DndController.kt
package com.anuj.notificationfirewall.service

import android.app.NotificationManager
import android.content.Context
import android.util.Log
import com.anuj.notificationfirewall.data.prefs.SecurePrefs
import com.anuj.notificationfirewall.domain.profile.ActiveProfile
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "DndController"

/**
 * Keeps system Do Not Disturb in sync with the active profile. When an auto-DND
 * profile is active we switch the phone to DND (priority) so the OS silences all
 * original notifications the instant they arrive — this is the only reliable way
 * to prevent the sound, because a NotificationListenerService is notified only
 * *after* the system has already alerted. The firewall's "important" rules
 * re-post on a DND-bypass channel, so only those ring through.
 *
 * We track [SecurePrefs.dndSetByApp] so we only ever turn DND back off when we
 * were the ones who turned it on — never overriding DND the user set by hand.
 */
@Singleton
class DndController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val securePrefs: SecurePrefs,
) {
    /** [active] is the profile currently in effect, or null when none is. */
    @Synchronized
    fun reconcile(active: ActiveProfile?) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (!nm.isNotificationPolicyAccessGranted) {
            // Can't touch DND without policy access (requested in onboarding).
            return
        }

        val wantDnd = active?.autoDnd == true
        val currentlyDnd = nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL

        if (wantDnd) {
            if (!currentlyDnd) {
                Log.i(TAG, "Enabling DND for active profile '${active?.name}'")
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                securePrefs.dndSetByApp = true
            }
        } else {
            // Only restore if WE enabled it; leave a user's manual DND untouched.
            if (currentlyDnd && securePrefs.dndSetByApp) {
                Log.i(TAG, "Restoring DND off (no auto-DND profile active)")
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
            }
            securePrefs.dndSetByApp = false
        }
    }
}
