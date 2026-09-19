package com.anuj.notificationfirewall.domain.wall

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.anuj.notificationfirewall.data.db.NfDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OverrideStoreTest {

    private lateinit var db: NfDatabase
    private lateinit var store: OverrideStore

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            NfDatabase::class.java,
        ).allowMainThreadQueries().build()
        store = OverrideStore(db.overrideDao())
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun noOverrideReturnsNull() = runTest {
        assertNull(store.kindFor("com.myntra", "Myntra"))
    }

    @Test
    fun appWideBlockMatchesAnySender() = runTest {
        store.add(OverrideKind.BLOCK, "com.myntra", null, "Myntra", OverrideSource.MANUAL)
        assertEquals(OverrideKind.BLOCK, store.kindFor("com.myntra", "anyone at all"))
        assertEquals(OverrideKind.BLOCK, store.kindFor("com.myntra", null))
    }

    @Test
    fun senderScopedVipMatchesOnlyThatSender() = runTest {
        store.add(OverrideKind.VIP, "com.whatsapp", "Mom", "Mom", OverrideSource.MANUAL)
        assertEquals(OverrideKind.VIP, store.kindFor("com.whatsapp", "Mom"))
        assertNull(store.kindFor("com.whatsapp", "Random Group"))
    }

    @Test
    fun vipWinsOverAppWideBlock() = runTest {
        store.add(OverrideKind.BLOCK, "com.linkedin", null, "LinkedIn", OverrideSource.MANUAL)
        store.add(OverrideKind.VIP, "com.linkedin", "Recruiter I like", "R", OverrideSource.MANUAL)
        assertEquals(OverrideKind.VIP, store.kindFor("com.linkedin", "Recruiter I like"))
        assertEquals(OverrideKind.BLOCK, store.kindFor("com.linkedin", "Someone else"))
    }

    @Test
    fun overrideIsScopedToItsApp() = runTest {
        store.add(OverrideKind.BLOCK, "com.myntra", null, "Myntra", OverrideSource.MANUAL)
        assertNull(store.kindFor("com.ajio", "Ajio"))
    }

    @Test
    fun removeDeletesTheOverride() = runTest {
        val id = store.add(OverrideKind.BLOCK, "com.myntra", null, "Myntra", OverrideSource.SWIPE)
        val entry = db.overrideDao().matching("com.myntra", null).first { it.id == id }
        store.remove(entry)
        assertNull(store.kindFor("com.myntra", "Myntra"))
    }
}
