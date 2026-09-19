package com.anuj.notificationfirewall.service

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.anuj.notificationfirewall.domain.wall.WallBucket
import com.anuj.notificationfirewall.domain.wall.WallDecision
import com.anuj.notificationfirewall.domain.wall.WallDecisionSource
import org.junit.Assert.assertEquals
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
}
