package com.anuj.notificationfirewall.ui.settings

import com.anuj.notificationfirewall.domain.wall.WallBucket
import org.junit.Assert.assertEquals
import org.junit.Test

class ThresholdPreviewTest {

    private val today = listOf(
        1.2f to WallBucket.SILENCE,
        2.5f to WallBucket.SILENCE,
        3.6f to WallBucket.SILENCE,
        4.2f to WallBucket.RING,
        4.9f to WallBucket.RING,
    )

    @Test
    fun loweringTheThresholdWouldRingMore() {
        val preview = ThresholdMath.preview(today, oldThreshold = 4.0f, newThreshold = 3.0f)
        assertEquals(1, preview.wouldRingMore)
        assertEquals(0, preview.wouldSilenceMore)
    }

    @Test
    fun raisingTheThresholdWouldSilenceMore() {
        val preview = ThresholdMath.preview(today, oldThreshold = 4.0f, newThreshold = 4.5f)
        assertEquals(0, preview.wouldRingMore)
        assertEquals(1, preview.wouldSilenceMore)
    }

    @Test
    fun noChangeMeansNoDifference() {
        val preview = ThresholdMath.preview(today, oldThreshold = 4.0f, newThreshold = 4.0f)
        assertEquals(0, preview.wouldRingMore)
        assertEquals(0, preview.wouldSilenceMore)
    }

    @Test
    fun droppedNotificationsAreNotCountedEitherWay() {
        val withDropped = today + (1.0f to WallBucket.DROP)
        val preview = ThresholdMath.preview(withDropped, oldThreshold = 4.0f, newThreshold = 1.0f)
        assertEquals("a blocked sender stays blocked at any threshold", 3, preview.wouldRingMore)
    }

    @Test
    fun emptyHistoryPreviewsZero() {
        val preview = ThresholdMath.preview(emptyList(), 4.0f, 2.0f)
        assertEquals(0, preview.wouldRingMore)
        assertEquals(0, preview.wouldSilenceMore)
    }
}
