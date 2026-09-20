package com.anuj.notificationfirewall.work

import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.anuj.notificationfirewall.data.db.NfDatabase
import com.anuj.notificationfirewall.data.prefs.SecurePrefs
import com.anuj.notificationfirewall.data.prefs.WallSettings
import com.anuj.notificationfirewall.domain.wall.VerdictCache
import com.anuj.notificationfirewall.service.ArmingController
import com.anuj.notificationfirewall.service.BreakGlassController
import com.anuj.notificationfirewall.service.DndController
import com.anuj.notificationfirewall.service.HealthMonitor
import com.anuj.notificationfirewall.service.KeepAliveService
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Covers the keep-alive self-healing symmetry: [MaintenanceWorker] must
 * request a start when the wall is armed (the OS may have killed the
 * foreground service behind the app's back — Funtouch OS on the target
 * hardware does exactly this) and a stop when it is not, on every 15-minute
 * run regardless of whether an explicit arm/disarm ever happened.
 */
@RunWith(RobolectricTestRunner::class)
class MaintenanceWorkerTest {

    private lateinit var context: Context
    private lateinit var db: NfDatabase
    private lateinit var prefs: SecurePrefs
    private lateinit var arming: ArmingController

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val nm = context.getSystemService(NotificationManager::class.java)
        shadowOf(nm).setNotificationPolicyAccessGranted(true)
        prefs = SecurePrefs(context.getSharedPreferences("test-maintenance", Context.MODE_PRIVATE))
        prefs.listenerConnected = true
        arming = ArmingController(context, DndController(context, prefs), prefs)
        db = Room.inMemoryDatabaseBuilder(context, NfDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    private fun buildWorker(): MaintenanceWorker {
        val factory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ): ListenableWorker = MaintenanceWorker(
                appContext,
                workerParameters,
                arming,
                HealthMonitor(appContext, prefs),
                db.notificationDao(),
                VerdictCache(db.verdictCacheDao()),
                WallSettings(context.getSharedPreferences("test-maintenance-settings", Context.MODE_PRIVATE)),
                BreakGlassController(appContext, arming, prefs),
            )
        }
        return TestListenableWorkerBuilder<MaintenanceWorker>(context)
            .setWorkerFactory(factory)
            .build()
    }

    @Test
    fun armedRequestsKeepAliveStart() = runTest {
        arming.arm()
        shadowOf(context as ContextWrapper).clearStartedServices()

        buildWorker().doWork()

        val started = shadowOf(context as ContextWrapper).nextStartedService
        assertEquals(KeepAliveService::class.java.name, started?.component?.className)
    }

    @Test
    fun disarmedRequestsKeepAliveStop() = runTest {
        // Never armed -- starts disarmed.
        shadowOf(context as ContextWrapper).clearStartedServices()

        buildWorker().doWork()

        val stopped = shadowOf(context as ContextWrapper).nextStoppedService
        assertEquals(KeepAliveService::class.java.name, stopped?.component?.className)
        assertNull(
            "a disarmed wall must not also request a start",
            shadowOf(context as ContextWrapper).nextStartedService,
        )
    }

    @Test
    fun reconcilesAStillLiveBreakGlassWindowWhoseAlarmWasCancelled() = runTest {
        // This is the backstop path for BreakGlassController.reconcile():
        // the fast path is NfListenerService.onListenerConnected(), but if
        // the listener never reconnects (or the alarm is cancelled while it
        // was already connected -- force-stop, package replacement, an
        // exact-alarm grant revocation), this periodic run is the only thing
        // left that can put the alarm back before the deadline passes.
        val breakGlass = BreakGlassController(context, arming, prefs)
        arming.arm()
        breakGlass.start()

        val alarms = shadowOf(context.getSystemService(android.app.AlarmManager::class.java))
        val cancelledOperation = alarms.scheduledAlarms.single().operation
        context.getSystemService(android.app.AlarmManager::class.java).cancel(cancelledOperation)
        assertEquals(0, alarms.scheduledAlarms.size)

        buildWorker().doWork()

        assertEquals(
            "the worker must reschedule the alarm a force-stop/update/revocation silently cancelled",
            1,
            alarms.scheduledAlarms.size,
        )
    }
}
