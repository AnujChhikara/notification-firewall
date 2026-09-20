package com.anuj.notificationfirewall.work

import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Exercises the WorkManager side of [DigestScheduler]: what actually gets enqueued. */
@RunWith(RobolectricTestRunner::class)
class DigestSchedulerRobolectricTest {

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val config = Configuration.Builder()
            .setExecutor(SynchronousExecutor())
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
    }

    private fun workInfos(): List<WorkInfo> {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        return WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(DIGEST_UNIQUE_WORK_NAME)
            .get()
    }

    @Test
    fun scheduleDaily_enqueues_exactly_one_periodic_work() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        DigestScheduler(context).scheduleDaily(9 * 60)

        val infos = workInfos()
        assertEquals(1, infos.size)
        assertTrue(infos.first().state == WorkInfo.State.ENQUEUED)
    }

    @Test
    fun changing_the_time_reschedules_instead_of_stacking_duplicates() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val scheduler = DigestScheduler(context)

        scheduler.scheduleDaily(9 * 60)
        scheduler.scheduleDaily(21 * 60)

        // UPDATE policy: still exactly one work item under the unique name,
        // not two competing schedules.
        assertEquals(1, workInfos().size)
    }

    /**
     * De-duplication alone (the test above) would also pass for a bug where
     * the second call is silently ignored -- this proves UPDATE actually
     * moved the fire time, not just that the count stayed at one.
     */
    @Test
    fun changing_the_time_actually_moves_the_next_fire_time() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val scheduler = DigestScheduler(context)

        scheduler.scheduleDaily(9 * 60)
        val firstScheduleTime = workInfos().single().nextScheduleTimeMillis

        scheduler.scheduleDaily(21 * 60)
        val secondScheduleTime = workInfos().single().nextScheduleTimeMillis

        assertTrue(
            "rescheduling to a different minute-of-day must change the next fire time",
            firstScheduleTime != secondScheduleTime,
        )
    }

    @Test
    fun cancel_removes_the_scheduled_work() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val scheduler = DigestScheduler(context)
        scheduler.scheduleDaily(9 * 60)
        scheduler.cancel()

        val infos = workInfos()
        assertTrue(infos.isEmpty() || infos.all { it.state == WorkInfo.State.CANCELLED })
    }
}
