// service/DndController.kt
package com.anuj.notificationfirewall.service

import android.app.NotificationManager
import android.app.NotificationManager.Policy
import android.content.Context
import android.os.Build
import android.util.Log
import com.anuj.notificationfirewall.data.prefs.SecurePrefs
import com.anuj.notificationfirewall.domain.profile.ActiveProfile
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "DndController"

/**
 * Keeps system Do Not Disturb in sync with the active profile. When an auto-DND
 * profile is active we switch the phone to DND (priority) so the OS silences
 * app notifications the instant they arrive — the only reliable way to prevent
 * the sound, since a NotificationListenerService is notified only *after* the
 * system already alerted. The firewall's "important" rules re-post on a
 * DND-bypass channel, so only those ring.
 *
 * Crucially, our DND is **call-safe**: before enabling it we overwrite the DND
 * policy to always allow phone calls (from anyone), repeat callers, and alarms,
 * so ONLY app notifications are silenced — you never miss a call. The user's
 * original policy is saved and restored verbatim when we turn DND back off.
 *
 * [SecurePrefs.dndSetByApp] marks that DND is on because of us, so we only ever
 * undo (and restore the policy for) DND that we turned on — never the user's own.
 */
@Singleton
class DndController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val securePrefs: SecurePrefs,
) {
    @Synchronized
    fun reconcile(active: ActiveProfile?) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (!nm.isNotificationPolicyAccessGranted) return

        val wantDnd = active?.autoDnd == true

        if (wantDnd) {
            if (!securePrefs.dndSetByApp) {
                Log.i(TAG, "Enabling call-safe DND for '${active?.name}'")
                saveCurrentPolicy(nm)
                nm.notificationPolicy = callSafePolicy()
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                securePrefs.dndSetByApp = true
            }
        } else {
            if (securePrefs.dndSetByApp) {
                Log.i(TAG, "Restoring DND off (no auto-DND profile active)")
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
                restoreSavedPolicy(nm)
                securePrefs.dndSetByApp = false
            }
        }
    }

    private fun saveCurrentPolicy(nm: NotificationManager) {
        val p = nm.notificationPolicy ?: return
        securePrefs.savedDndCategories = p.priorityCategories
        securePrefs.savedDndCallSenders = p.priorityCallSenders
        securePrefs.savedDndMessageSenders = p.priorityMessageSenders
        securePrefs.savedDndSuppressedEffects = p.suppressedVisualEffects
        securePrefs.hasSavedDndPolicy = true
    }

    private fun restoreSavedPolicy(nm: NotificationManager) {
        if (!securePrefs.hasSavedDndPolicy) return
        runCatching {
            nm.notificationPolicy = Policy(
                securePrefs.savedDndCategories,
                securePrefs.savedDndCallSenders,
                securePrefs.savedDndMessageSenders,
                securePrefs.savedDndSuppressedEffects,
            )
        }.onFailure { Log.w(TAG, "Could not restore saved DND policy", it) }
        securePrefs.hasSavedDndPolicy = false
    }

    /**
     * Policy that lets calls / repeat callers / alarms (and, on newer Android,
     * media, system and event/reminder sounds) through, while app message and
     * conversation notifications stay silenced. Callers from ANY number so a call
     * is never missed.
     */
    private fun callSafePolicy(): Policy {
        var categories = Policy.PRIORITY_CATEGORY_CALLS or
            Policy.PRIORITY_CATEGORY_REPEAT_CALLERS or
            Policy.PRIORITY_CATEGORY_EVENTS or
            Policy.PRIORITY_CATEGORY_REMINDERS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            categories = categories or
                Policy.PRIORITY_CATEGORY_ALARMS or
                Policy.PRIORITY_CATEGORY_MEDIA or
                Policy.PRIORITY_CATEGORY_SYSTEM
        }
        return Policy(categories, Policy.PRIORITY_SENDERS_ANY, Policy.PRIORITY_SENDERS_ANY)
    }
}
