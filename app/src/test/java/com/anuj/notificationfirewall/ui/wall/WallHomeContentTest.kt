package com.anuj.notificationfirewall.ui.wall

import com.anuj.notificationfirewall.service.WallState
import org.junit.Assert.assertEquals
import org.junit.Test

class WallHomeContentTest {
    @Test
    fun armedStateExplainsActiveProtection() {
        val content = wallStatusContent(WallState.ARMED)

        assertEquals("ARMED", content.label)
        assertEquals("Protection is active", content.summary)
        assertEquals("Tap to let notifications through", content.actionHint)
    }

    @Test
    fun disarmedStateExplainsPassThrough() {
        val content = wallStatusContent(WallState.DISARMED)

        assertEquals("DISARMED", content.label)
        assertEquals("Notifications pass through", content.summary)
        assertEquals("Tap to arm the wall", content.actionHint)
    }
}
