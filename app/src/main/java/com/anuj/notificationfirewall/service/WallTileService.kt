// service/WallTileService.kt
package com.anuj.notificationfirewall.service

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Maps wall state to the platform's three tile states. */
internal fun tileStateFor(state: WallState): Int = when (state) {
    WallState.ARMED -> Tile.STATE_ACTIVE
    WallState.DISARMED -> Tile.STATE_INACTIVE
    // A tile the user can tap but that cannot possibly work is worse than a
    // greyed-out one.
    WallState.BLOCKED_NO_LISTENER,
    WallState.BLOCKED_NO_POLICY_ACCESS -> Tile.STATE_UNAVAILABLE
}

/**
 * Arms and disarms the wall from the system shade.
 *
 * Reads the same live state as the Wall screen, so the two can never disagree
 * -- they are both views of the system interruption filter rather than of any
 * stored flag. [ArmingController.state] is a synchronous getter, but it is
 * computed fresh from the live interruption filter on every call, not cached,
 * so reading it here rather than collecting a Flow is correct: a
 * `TileService` renders on discrete lifecycle callbacks anyway, and there is
 * no "current" UI to keep continuously in sync the way a Composable is.
 *
 * That still leaves a gap: [onStartListening] and [onClick] only render at
 * the moment they fire, so a DND change made by another app or the user while
 * the shade is already open and this tile already listening would not appear
 * until the next listen/click cycle. [ArmingController.observeState] closes
 * that gap cheaply: it already emits on every system DND change (the same
 * signal [DndChangeReceiver] feeds it), so collecting it for the lifetime of
 * [onStartListening]..[onStopListening] repaints the tile the moment the
 * fact it mirrors changes, without turning [render] itself into a Flow
 * collector.
 *
 * Residual staleness window: while the shade is closed (tile not listening),
 * an external DND change is not reflected until the shade is next opened
 * (onStartListening) -- Quick Settings tiles are never rendered off-screen,
 * so there is nothing to keep in sync during that window regardless.
 */
@AndroidEntryPoint
class WallTileService : TileService() {

    @Inject lateinit var armingController: ArmingController

    private var scope: CoroutineScope? = null

    override fun onStartListening() {
        super.onStartListening()
        render()

        val listeningScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = listeningScope
        listeningScope.launch {
            armingController.observeState().collect { render() }
        }
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        // Render whatever arm()/disarm() actually returned rather than
        // assuming success -- arm() can refuse (BLOCKED_NO_LISTENER) without
        // touching DND, and the tile must not claim armed when it didn't.
        if (armingController.isArmed()) armingController.disarm() else armingController.arm()
        render()
    }

    private fun render() {
        val tile = qsTile ?: return
        val state = armingController.state()
        tile.state = tileStateFor(state)
        tile.label = "Notification Wall"
        tile.contentDescription = when (state) {
            WallState.ARMED -> "Wall armed"
            WallState.DISARMED -> "Wall disarmed"
            WallState.BLOCKED_NO_LISTENER -> "Notification access needed"
            WallState.BLOCKED_NO_POLICY_ACCESS -> "Do Not Disturb access needed"
        }
        tile.updateTile()
    }
}
