package com.anuj.notificationfirewall.ui.wall

import android.app.NotificationManager
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.anuj.notificationfirewall.data.db.NfDatabase
import com.anuj.notificationfirewall.data.prefs.SecurePrefs
import com.anuj.notificationfirewall.data.prefs.WallSettings
import com.anuj.notificationfirewall.service.ArmingController
import com.anuj.notificationfirewall.service.BreakGlassController
import com.anuj.notificationfirewall.service.DndController
import com.anuj.notificationfirewall.service.WallState
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class WallViewModelTest {

    private lateinit var context: Context
    private lateinit var nm: NotificationManager
    private lateinit var db: NfDatabase
    private lateinit var prefs: SecurePrefs
    private lateinit var arming: ArmingController
    private lateinit var vm: WallViewModel

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        nm = context.getSystemService(NotificationManager::class.java)
        shadowOf(nm).setNotificationPolicyAccessGranted(true)
        db = Room.inMemoryDatabaseBuilder(context, NfDatabase::class.java)
            .allowMainThreadQueries().build()
        prefs = SecurePrefs(context.getSharedPreferences("test-wall-vm", Context.MODE_PRIVATE))
        prefs.listenerConnected = true
        arming = ArmingController(context, DndController(context, prefs), prefs)
        val breakGlass = BreakGlassController(context, arming, prefs)
        val wallSettings = WallSettings(context.getSharedPreferences("test-wall-settings-vm", Context.MODE_PRIVATE))
        vm = WallViewModel(arming, db.notificationDao(), breakGlass, wallSettings)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun reportsDisarmedInitially() = runTest {
        vm.refresh()
        assertEquals(WallState.DISARMED, vm.ui.value.state)
    }

    @Test
    fun toggleArmsTheWall() = runTest {
        vm.toggle()
        assertEquals(WallState.ARMED, vm.ui.value.state)
    }

    @Test
    fun toggleTwiceReturnsToDisarmed() = runTest {
        vm.toggle()
        vm.toggle()
        assertEquals(WallState.DISARMED, vm.ui.value.state)
    }

    @Test
    fun externalDndOffIsReflectedAfterRefresh() = runTest {
        vm.toggle()
        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        arming.onSystemDndChanged()
        vm.refresh()

        assertEquals(
            "the toggle must never claim armed when DND is off",
            WallState.DISARMED,
            vm.ui.value.state,
        )
    }

    @Test
    fun missingPolicyAccessIsSurfacedNotHidden() = runTest {
        shadowOf(nm).setNotificationPolicyAccessGranted(false)
        vm.refresh()
        assertEquals(WallState.BLOCKED_NO_POLICY_ACCESS, vm.ui.value.state)
    }

    @Test
    fun togglingWhileBlockedDoesNotClaimArmed() = runTest {
        shadowOf(nm).setNotificationPolicyAccessGranted(false)
        vm.toggle()
        assertEquals(WallState.BLOCKED_NO_POLICY_ACCESS, vm.ui.value.state)
    }

    @Test
    fun togglingWithoutAConnectedListenerDoesNotClaimArmed() = runTest {
        // Policy access is granted (from setUp), but the listener never
        // connected -- arm() must refuse before touching DND, and the screen
        // must render exactly what arm() returned rather than assume success.
        prefs.listenerConnected = false
        vm.toggle()
        assertEquals(
            "the toggle must never claim armed without a connected listener",
            WallState.BLOCKED_NO_LISTENER,
            vm.ui.value.state,
        )
    }

    @Test
    fun breakGlassOpensTheWallAndPopulatesTheCountdown() = runTest {
        vm.toggle()
        vm.breakGlass()

        assertEquals(WallState.DISARMED, vm.ui.value.state)
        assertEquals(true, vm.ui.value.breakGlassUntilMs != null)
    }

    @Test
    fun cancelBreakGlassReArmsAndClearsTheCountdown() = runTest {
        vm.toggle()
        vm.breakGlass()
        vm.cancelBreakGlass()

        assertEquals(WallState.ARMED, vm.ui.value.state)
        assertEquals(null, vm.ui.value.breakGlassUntilMs)
    }
}
