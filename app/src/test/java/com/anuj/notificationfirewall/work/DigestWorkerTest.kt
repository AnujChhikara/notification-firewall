package com.anuj.notificationfirewall.work

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.anuj.notificationfirewall.ai.DigestData
import com.anuj.notificationfirewall.ai.DigestService
import com.anuj.notificationfirewall.ai.DigestStore
import com.anuj.notificationfirewall.data.db.NfDatabase
import com.anuj.notificationfirewall.data.db.NotificationRecordEntity
import com.anuj.notificationfirewall.data.prefs.WallSettings
import com.anuj.notificationfirewall.domain.wall.WallBucket
import com.anuj.notificationfirewall.domain.wall.WallDecisionSource
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.LocalDate
import java.time.ZoneId

/**
 * Covers the one-per-day guarantee: [DigestWorker] must post at most one
 * digest notification for a given calendar day, even if it is asked to run
 * twice (the exact shape a settings-screen time change can produce --
 * [DigestScheduler.scheduleDaily] recomputing a same-day initial delay while
 * an old schedule's fire is still pending).
 */
@RunWith(RobolectricTestRunner::class)
class DigestWorkerTest {

    private lateinit var context: Context
    private lateinit var db: NfDatabase
    private lateinit var settings: WallSettings
    private var callCount = 0

    private val fakeDigestService = object : DigestService {
        override suspend fun summarise(data: DigestData): String {
            callCount++
            return "headline #$callCount"
        }
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val nm = context.getSystemService(NotificationManager::class.java)
        shadowOf(nm).setNotificationPolicyAccessGranted(true)
        settings = WallSettings(context.getSharedPreferences("test-digest-settings", Context.MODE_PRIVATE))
        db = Room.inMemoryDatabaseBuilder(context, NfDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    private fun record(bucket: WallBucket, tsMs: Long) = NotificationRecordEntity(
        packageName = "com.gmail", appLabel = "Gmail", title = "t", text = "x",
        timestampEpochMs = tsMs, senderKey = "promo", contentShape = "", importanceScore = 1f,
        biasApplied = 0f, category = null, isTimeSensitive = null, isFromHuman = null,
        needsAction = null, jevConfidence = null, decisionSource = WallDecisionSource.LEGACY,
        bucket = bucket, pendingClassification = false, textPurgedAt = null, isRead = false,
    )

    private fun buildWorker(): DigestWorker {
        val factory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ): ListenableWorker = DigestWorker(
                appContext,
                workerParameters,
                db.notificationDao(),
                fakeDigestService,
                DigestStore(settings),
            )
        }
        return TestListenableWorkerBuilder<DigestWorker>(context)
            .setWorkerFactory(factory)
            .build()
    }

    @Test
    fun postsExactlyOneNotificationPerCalendarDay() = runTest {
        val yesterdayMs = LocalDate.now(ZoneId.systemDefault())
            .minusDays(1)
            .atTime(12, 0)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        db.notificationDao().insert(record(WallBucket.SILENCE, yesterdayMs))

        buildWorker().doWork()
        // Simulates a same-day reschedule (settings-screen time change)
        // firing again before the calendar day rolls over.
        buildWorker().doWork()

        assertEquals("digestService must not be asked twice in one calendar day", 1, callCount)
        val nm = shadowOf(context.getSystemService(NotificationManager::class.java))
        assertEquals(
            "exactly one digest notification should have been posted, not two",
            1,
            nm.allNotifications.size,
        )
    }

    @Test
    fun persistsTheDigestForTheWallScreenCard() = runTest {
        db.notificationDao().insert(
            record(
                WallBucket.SILENCE,
                LocalDate.now(ZoneId.systemDefault()).minusDays(1).atTime(12, 0)
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            ),
        )

        buildWorker().doWork()

        val persisted = DigestStore(settings).load()
        assertNotNull(persisted)
        assertEquals("headline #1", persisted!!.headline)
        assertEquals(LocalDate.now(ZoneId.systemDefault()).toEpochDay(), persisted.dateEpochDay)
    }

    @Test
    fun stillPersistsWhenPostNotificationsIsDenied() = runTest {
        val nm = context.getSystemService(NotificationManager::class.java)
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        buildWorker().doWork()

        assertEquals(
            "a denied permission must not post, but must not crash either",
            0,
            shadowOf(nm).allNotifications.size,
        )
        assertNotNull(
            "the Wall card should still show today's digest even if the OS notification couldn't post",
            DigestStore(settings).load(),
        )
    }
}
