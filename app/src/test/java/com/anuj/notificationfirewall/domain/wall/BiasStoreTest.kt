package com.anuj.notificationfirewall.domain.wall

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.anuj.notificationfirewall.data.db.NfDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BiasStoreTest {

    private lateinit var db: NfDatabase
    private lateinit var store: BiasStore

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            NfDatabase::class.java,
        ).allowMainThreadQueries().build()
        store = BiasStore(db.senderBiasDao()) { 1_700_000_000_000L }
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun unknownSenderHasZeroBias() = runTest {
        assertEquals(0f, store.biasFor("com.myntra", "Myntra"), 0.001f)
    }

    @Test
    fun nullSenderHasZeroBiasAndIsNotPersisted() = runTest {
        assertEquals(0f, store.biasFor("com.myntra", null), 0.001f)
        assertEquals(0f, store.record("com.myntra", null, Correction.SHOULD_HAVE_RUNG), 0.001f)
    }

    @Test
    fun oneSilentCorrectionMovesBiasDownOneStep() = runTest {
        val bias = store.record("com.myntra", "Myntra", Correction.SHOULD_HAVE_BEEN_SILENT)
        assertEquals(-0.25f, bias, 0.001f)
        assertEquals(-0.25f, store.biasFor("com.myntra", "Myntra"), 0.001f)
    }

    @Test
    fun oneRingCorrectionMovesBiasUpOneStep() = runTest {
        assertEquals(0.25f, store.record("com.slack", "Boss", Correction.SHOULD_HAVE_RUNG), 0.001f)
    }

    @Test
    fun biasIsClampedAtNegativeThreeQuarters() = runTest {
        repeat(10) { store.record("com.myntra", "Myntra", Correction.SHOULD_HAVE_BEEN_SILENT) }
        assertEquals(-0.75f, store.biasFor("com.myntra", "Myntra"), 0.001f)
    }

    @Test
    fun biasIsClampedAtPositiveThreeQuarters() = runTest {
        repeat(10) { store.record("com.slack", "Boss", Correction.SHOULD_HAVE_RUNG) }
        assertEquals(0.75f, store.biasFor("com.slack", "Boss"), 0.001f)
    }

    @Test
    fun correctionsInOppositeDirectionsCancel() = runTest {
        store.record("com.x", "S", Correction.SHOULD_HAVE_BEEN_SILENT)
        store.record("com.x", "S", Correction.SHOULD_HAVE_BEEN_SILENT)
        store.record("com.x", "S", Correction.SHOULD_HAVE_RUNG)
        assertEquals(-0.25f, store.biasFor("com.x", "S"), 0.001f)
    }

    @Test
    fun correctionCountIsTracked() = runTest {
        repeat(3) { store.record("com.x", "S", Correction.SHOULD_HAVE_BEEN_SILENT) }
        assertEquals(3, db.senderBiasDao().find("com.x", "S")!!.correctionCount)
    }

    @Test
    fun biasIsScopedToTheSenderNotTheApp() = runTest {
        store.record("com.whatsapp", "Myntra Offers", Correction.SHOULD_HAVE_BEEN_SILENT)
        assertEquals(0f, store.biasFor("com.whatsapp", "Mom"), 0.001f)
    }

    @Test
    fun clearResetsASender() = runTest {
        store.record("com.x", "S", Correction.SHOULD_HAVE_BEEN_SILENT)
        store.clear("com.x", "S")
        assertEquals(0f, store.biasFor("com.x", "S"), 0.001f)
    }
}
