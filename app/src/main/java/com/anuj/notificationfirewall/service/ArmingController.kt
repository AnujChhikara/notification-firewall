package com.anuj.notificationfirewall.service

import android.app.NotificationManager
import android.content.Context
import com.anuj.notificationfirewall.data.prefs.SecurePrefs
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import javax.inject.Inject
import javax.inject.Singleton

enum class WallState {
    ARMED,
    DISARMED,
    BLOCKED_NO_POLICY_ACCESS,
    BLOCKED_NO_LISTENER,
}

/**
 * The one place that answers "is the wall armed".
 *
 * The answer is always computed from the live system interruption filter, never
 * read from storage. The previous build stored a boolean saying whether the app
 * had enabled DND, and when the user turned DND off from the system shade that
 * boolean went stale — the UI cheerfully reported an armed wall that was doing
 * nothing at all. Deriving the state removes the possibility rather than
 * patching the symptom.
 *
 * [SecurePrefs.dndSetByApp] survives for one narrow purpose: recording that
 * *this app* was the one that enabled DND, so a disarm only ever restores a
 * policy the app actually replaced, and DND the user turned on themselves is
 * left strictly alone.
 */
@Singleton
class ArmingController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dndController: DndController,
    private val securePrefs: SecurePrefs,
) {

    private val changes = MutableSharedFlow<Unit>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private val notificationManager: NotificationManager?
        get() = context.getSystemService(NotificationManager::class.java)

    fun state(): WallState {
        val nm = notificationManager ?: return WallState.BLOCKED_NO_POLICY_ACCESS
        if (!nm.isNotificationPolicyAccessGranted) return WallState.BLOCKED_NO_POLICY_ACCESS
        if (!securePrefs.listenerConnected) return WallState.BLOCKED_NO_LISTENER

        val dndOn = nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
        // DND the user turned on themselves is their business, not an armed wall.
        return if (dndOn && securePrefs.dndSetByApp) WallState.ARMED else WallState.DISARMED
    }

    fun isArmed(): Boolean = state() == WallState.ARMED

    fun arm(): WallState {
        val nm = notificationManager ?: return WallState.BLOCKED_NO_POLICY_ACCESS
        if (!nm.isNotificationPolicyAccessGranted) return WallState.BLOCKED_NO_POLICY_ACCESS
        // Refuse before touching DND: arming with the listener down would put
        // the phone into system DND while state() reports BLOCKED_NO_LISTENER
        // -- the OS would then suppress the user's notifications and nothing
        // re-posts them. Gating this here, in the controller, rather than in
        // one UI caller, means every caller (including a future Quick
        // Settings tile) gets the same safe contract.
        if (!securePrefs.listenerConnected) return WallState.BLOCKED_NO_LISTENER

        dndController.apply(wantDnd = true)
        // KeepAliveService self-verifies armed state on every start and stops
        // itself if not armed, so calling this unconditionally on a successful
        // arm is safe and idempotent.
        KeepAliveService.start(context)
        changes.tryEmit(Unit)
        return state()
    }

    fun disarm(): WallState {
        dndController.apply(wantDnd = false)
        KeepAliveService.stop(context)
        changes.tryEmit(Unit)
        return state()
    }

    /**
     * Called whenever system DND changes, from any source.
     *
     * If DND is now off while the app still believed it owned it, the user
     * turned the wall off. Release ownership so a later disarm cannot clobber
     * DND they subsequently enable themselves, and let the state fall through
     * to DISARMED. The wall is never silently re-armed: overriding an explicit
     * user action later is exactly the behaviour this design rejects.
     */
    fun onSystemDndChanged() {
        val nm = notificationManager ?: return
        val dndOff = nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL
        if (dndOff && securePrefs.dndSetByApp) {
            securePrefs.dndSetByApp = false
            securePrefs.hasSavedDndPolicy = false
        }
        changes.tryEmit(Unit)
    }

    /** Emits the current state immediately, then on every DND change. */
    fun observeState(): Flow<WallState> = changes.onStart { emit(Unit) }.map { state() }
}
