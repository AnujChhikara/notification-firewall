package com.anuj.notificationfirewall.service

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import com.anuj.notificationfirewall.domain.wall.WallBucket
import com.anuj.notificationfirewall.domain.wall.WallDecision
import com.anuj.notificationfirewall.domain.wall.WallDecisionSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class BucketExecutorTest {

    private lateinit var context: Context
    private lateinit var executor: BucketExecutor
    private val cancelled = mutableListOf<String>()

    private fun decision(bucket: WallBucket) = WallDecision(
        bucket = bucket,
        source = WallDecisionSource.JEV,
        verdict = null,
        biasApplied = 0f,
        contentShape = "shape",
        pendingClassification = false,
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        executor = BucketExecutor(context, ChannelManager(context))
        executor.canceller = NotificationCanceller { key -> cancelled += key }
    }

    @Test
    fun ringChannelBypassesDndAndIsHighImportance() {
        val id = ChannelManager(context).ringChannelId()
        val nm = context.getSystemService(NotificationManager::class.java)
        val channel = nm.getNotificationChannel(id)

        assertTrue("the ring channel must bypass DND or it cannot make a sound", channel.canBypassDnd())
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
    }

    @Test
    fun ringChannelIdIsStableAcrossCalls() {
        val manager = ChannelManager(context)
        assertEquals(manager.ringChannelId(), manager.ringChannelId())
    }

    private fun sbn(key: String): StatusBarNotification {
        val notification = Notification.Builder(context, ChannelManager(context).ringChannelId())
            .setContentTitle("Alice")
            .setContentText("On my way")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        return StatusBarNotification(
            context.packageName,
            context.packageName,
            1,
            key,
            Process.myUid(),
            0,
            0,
            notification,
            Process.myUserHandle(),
            System.currentTimeMillis(),
        )
    }

    @Test
    fun ringWithoutPostPermissionDegradesToSilenceInsteadOfDestroyingTheNotification() {
        // Deny POST_NOTIFICATIONS: the fresh-install state before onboarding
        // requests it.
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        val notification = sbn("k1")
        executor.execute(decision(WallBucket.RING), notification)

        // The original must NOT be cancelled -- destroying it is exactly the
        // outcome this fix prevents.
        assertTrue("original must be left in place when repost is impossible", cancelled.isEmpty())

        // Nothing should have been re-posted either.
        val nm = context.getSystemService(NotificationManager::class.java)
        assertFalse(
            "no repost should exist without POST_NOTIFICATIONS",
            nm.activeNotifications.any { it.tag == "k1" },
        )
    }
}
