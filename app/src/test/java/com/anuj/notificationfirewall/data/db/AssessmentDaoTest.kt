package com.anuj.notificationfirewall.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.anuj.notificationfirewall.data.mapper.AssessmentMappers
import com.anuj.notificationfirewall.domain.assessment.AssessmentAnswers
import com.anuj.notificationfirewall.domain.assessment.AssessmentScorer
import com.anuj.notificationfirewall.domain.assessment.Goal
import com.anuj.notificationfirewall.domain.assessment.ProtectedApp
import com.anuj.notificationfirewall.domain.assessment.ScrollPattern
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AssessmentDaoTest {

    private lateinit var db: NfDatabase

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            NfDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun teardown() = db.close()

    @Test
    fun empty_table_observes_null() = runBlocking {
        assertNull(db.assessmentDao().observeLatest().first())
    }

    @Test
    fun latest_result_round_trips_through_mapper() = runBlocking {
        val profile = AssessmentScorer.score(
            AssessmentAnswers(
                pattern = ScrollPattern.REELS,
                goal = Goal.CALM_MORNINGS,
                protectedApps = listOf(
                    ProtectedApp("com.instagram.android", "Instagram"),
                    ProtectedApp("com.zhiliaoapp.musically", "TikTok"),
                ),
            ),
        )
        db.assessmentDao().insert(AssessmentMappers.toEntity(profile, nowEpochMs = 100))
        db.assessmentDao().insert(
            AssessmentMappers.toEntity(profile, nowEpochMs = 200).copy(goal = Goal.REAL_SLEEP.name),
        )

        val latest = db.assessmentDao().observeLatest().first()!!
        assertEquals(Goal.REAL_SLEEP.name, latest.goal)
        assertEquals(200, latest.createdAtEpochMs)

        val domain = AssessmentMappers.toDomain(latest)
        assertEquals(2, domain.protectedApps.size)
        assertEquals("Instagram", domain.protectedApps.first().label)
        assertEquals(ScrollPattern.REELS, domain.pattern)
    }
}
