package com.anuj.notificationfirewall.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeyMaskingTest {

    @Test
    fun `null key has no preview`() {
        assertNull(KeyMasking.maskedPreview(null))
    }

    @Test
    fun `empty key has no preview`() {
        assertNull(KeyMasking.maskedPreview(""))
    }

    @Test
    fun `blank key has no preview`() {
        assertNull(KeyMasking.maskedPreview("   "))
    }

    @Test
    fun `key shorter than 12 chars is fully masked with no real characters revealed`() {
        val key = "sk-short1" // 9 chars
        val preview = KeyMasking.maskedPreview(key)
        assertEquals("•".repeat(key.length), preview)
        assertEquals(key.length, preview?.length)
        key.forEach { c -> assertEquals(false, preview!!.contains(c)) }
    }

    @Test
    fun `single character key is fully masked`() {
        assertEquals("•", KeyMasking.maskedPreview("a"))
    }

    @Test
    fun `key exactly 12 chars reveals first 8 and last 4 with no overlap`() {
        val key = "sk-projABCD" + "X" // craft a 12-char key
        assertEquals(12, key.length)
        val preview = KeyMasking.maskedPreview(key)
        assertEquals("${key.take(8)}…${key.takeLast(4)}", preview)
    }

    @Test
    fun `key of 11 chars is still fully masked, not partially revealed`() {
        val key = "12345678901" // 11 chars
        val preview = KeyMasking.maskedPreview(key)
        assertEquals("•".repeat(11), preview)
    }

    @Test
    fun `long key shows first 8 and last 4 separated by ellipsis`() {
        val key = "sk-proj-abcdefghijklmnopqrstuvwxyza4oA"
        val preview = KeyMasking.maskedPreview(key)
        assertEquals("sk-proj-…a4oA", preview)
    }
}
