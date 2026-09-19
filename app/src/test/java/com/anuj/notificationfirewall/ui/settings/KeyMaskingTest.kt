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

    @Test
    fun `key exactly 12 chars is fully masked, not reconstituted by prefix plus suffix`() {
        // 8 + 4 = 12: take(8) + takeLast(4) would reproduce the entire key.
        // The preview must never disclose the full key, so this length must
        // fall through to the fully-masked branch.
        val key = "sk-projABCDX"
        assertEquals(12, key.length)
        val preview = KeyMasking.maskedPreview(key)
        assertEquals("•".repeat(12), preview)
        key.forEach { c -> assertEquals(false, preview!!.contains(c)) }
    }

    @Test
    fun `key of 13 chars reveals exactly 8 plus 4 and hides exactly one character`() {
        val key = "sk-projABCDXY" // 13 chars
        assertEquals(13, key.length)
        val preview = KeyMasking.maskedPreview(key)
        requireNotNull(preview)
        assertEquals("${key.take(8)}…${key.takeLast(4)}", preview)
        // Preview carries 8 + 4 = 12 real characters plus the ellipsis; the
        // raw key has 13, so exactly one character (index 8) stays hidden.
        assertEquals(key.length - 1, preview.count { it != '…' })
        assertEquals(false, preview.contains(key))
    }

    @Test
    fun `preview never discloses the raw key, across a range of lengths`() {
        val lengths = listOf(1, 11, 12, 13, 20)
        for (len in lengths) {
            val key = (1..len).joinToString("") { ('a' + (it % 26)).toString() }
            val preview = KeyMasking.maskedPreview(key)
            requireNotNull(preview) { "preview should not be null for length $len" }
            assertEquals(false, preview == key)
            assertEquals(false, preview.contains(key))
        }
    }

    @Test
    fun `preview never discloses a realistic long api key`() {
        val key = "sk-proj-" + "a".repeat(148) + "b2c9" // ~160 chars, sk-proj- style
        val preview = KeyMasking.maskedPreview(key)
        requireNotNull(preview)
        assertEquals(false, preview == key)
        assertEquals(false, preview.contains(key))
    }
}
