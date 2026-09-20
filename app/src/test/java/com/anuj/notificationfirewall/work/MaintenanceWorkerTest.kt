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
import com.anuj.notificationfirewall.ai.DigestStore
import com.anuj.notificationfirewall.ai.PersistedDigest
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.LocalDate
import java.time.ZoneId

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
    private lateinit var wallSettings: WallSettings
    private lateinit var digestStore: DigestStore

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
        wallSettings = WallSettings(context.getSharedPreferences("test-maintenance-settings", Context.MODE_PRIVATE))
        digestStore = DigestStore(wallSettings)
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
                wallSettings,
                BreakGlassController(appContext, arming, prefs),
                digestStore,
            )
        }
        return TestListenableWorkerBuilder<MaintenanceWorker>(context)
            .setWorkerFactory(factory)
            .build()
    }

    private fun digest(dateEpochDay: Long, worthALook: List<String> = emptyList()) = PersistedDigest(
        dateEpochDay = dateEpochDay,
        headline = "Yesterday: 5 silenced, 1 let through.",
        rang = 1, silenced = 5, dropped = 0,
        topOffenderLabel = "Myntra", topOffenderCount = 5,
        worthALook = worthALook,
    )

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

    /**
     * The persisted digest (Wall screen card) is a content-bearing store
     * (worthALook can carry sender names/titles -- see DigestStore's KDoc)
     * that nothing else was guaranteed to ever clear. It must age out on the
     * same retention terms as the notification rows it summarises.
     */
    @Test
    fun retentionSweepClearsAPersistedDigestOlderThanTheRetentionWindow() = runTest {
        wallSettings.textRetentionDays = 1
        digestStore.save(digest(dateEpochDay = LocalDate.now(ZoneId.systemDefault()).minusDays(5).toEpochDay()))

        buildWorker().doWork()

        assertNull(
            "a digest older than the retention window must be cleared, not merely stale",
            digestStore.load(),
        )
    }

    @Test
    fun retentionSweepKeepsAPersistedDigestWithinTheRetentionWindow() = runTest {
        wallSettings.textRetentionDays = 30
        digestStore.save(digest(dateEpochDay = LocalDate.now(ZoneId.systemDefault()).toEpochDay()))

        buildWorker().doWork()

        assertNotNull(
            "a digest still inside the retention window must not be wiped by an unrelated sweep",
            digestStore.load(),
        )
    }

    @Test
    fun retentionOfZeroNeverPurgesTheDigestEither() = runTest {
        // 0 means "never purge" for notification text; the digest must honour
        // the same "never" rather than silently applying its own cutoff.
        wallSettings.textRetentionDays = 0
        digestStore.save(digest(dateEpochDay = LocalDate.now(ZoneId.systemDefault()).minusDays(400).toEpochDay()))

        buildWorker().doWork()

        assertNotNull(digestStore.load())
    }

    /**
     * The end-to-end promise: a record's content that was baked into a
     * digest, then purged by retention, must not remain readable from the
     * digest store afterward -- the same guarantee retention already makes
     * for the `notifications` table itself.
     */
    @Test
    fun aDigestBuiltFromAContentBearingRecordDoesNotSurviveThatRecordsRetentionPurge() = runTest {
        wallSettings.textRetentionDays = 1
        digestStore.save(
            digest(
                dateEpochDay = LocalDate.now(ZoneId.systemDefault()).minusDays(5).toEpochDay(),
                worthALook = listOf("Landlord: rent due"),
            ),
        )

        buildWorker().doWork()

        val reloaded = digestStore.load()
        assertNull(reloaded)
        assertFalse(
            "the purged record's content must not be readable from the store by any path",
            (reloaded?.worthALook ?: emptyList()).any { it.contains("Landlord") },
        )
    }
}
