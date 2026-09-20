package com.anuj.notificationfirewall.service

import android.service.quicksettings.Tile
import org.junit.Assert.assertEquals
import org.junit.Test

class WallTileServiceTest {

    @Test
    fun armedShowsActive() {
        assertEquals(Tile.STATE_ACTIVE, tileStateFor(WallState.ARMED))
    }

    @Test
    fun disarmedShowsInactive() {
        assertEquals(Tile.STATE_INACTIVE, tileStateFor(WallState.DISARMED))
    }

    @Test
    fun missingPolicyAccessShowsUnavailable() {
        assertEquals(Tile.STATE_UNAVAILABLE, tileStateFor(WallState.BLOCKED_NO_POLICY_ACCESS))
    }

    @Test
    fun missingListenerShowsUnavailable() {
        assertEquals(Tile.STATE_UNAVAILABLE, tileStateFor(WallState.BLOCKED_NO_LISTENER))
    }

    @Test
    fun blockedIsNeverReportedAsActive() {
        listOf(WallState.BLOCKED_NO_LISTENER, WallState.BLOCKED_NO_POLICY_ACCESS).forEach {
            assertEquals(
                "a tile that cannot act must not look armed",
                Tile.STATE_UNAVAILABLE,
                tileStateFor(it),
            )
        }
    }
}
