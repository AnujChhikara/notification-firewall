package com.anuj.notificationfirewall.domain.wall

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ContentShapeTest {

    @Test
    fun sameMarketingTemplateWithDifferentNumbers_collapsesToOneShape() {
        val a = ContentShape.of("Myntra", "FLAT 70% OFF ends in 3 hours. Shop now!")
        val b = ContentShape.of("Myntra", "FLAT 50% OFF ends in 6 hours. Shop now!")
        assertEquals(a, b)
    }

    @Test
    fun genuinelyDifferentMessagesFromSameSender_produceDifferentShapes() {
        val promo = ContentShape.of("Mom", "Check out this 50% off sale at Croma!")
        val real = ContentShape.of("Mom", "Reached home safely, call me when free")
        assertNotEquals(promo, real)
    }

    @Test
    fun currencyAmountsAreNormalised() {
        val a = ContentShape.of("HDFC Bank", "Rs. 1,299.00 debited from your account")
        val b = ContentShape.of("HDFC Bank", "Rs. 45,000.50 debited from your account")
        assertEquals(a, b)
    }

    @Test
    fun urlsAreNormalised() {
        val a = ContentShape.of("Swiggy", "Track your order at https://swiggy.com/o/abc123")
        val b = ContentShape.of("Swiggy", "Track your order at https://swiggy.com/o/zzz999")
        assertEquals(a, b)
    }

    @Test
    fun timesAndDatesAreNormalised() {
        val a = ContentShape.of("Calendar", "Standup at 09:30 on 12/03/2026")
        val b = ContentShape.of("Calendar", "Standup at 14:00 on 03/11/2026")
        assertEquals(a, b)
    }

    @Test
    fun caseAndWhitespaceDoNotMatter() {
        val a = ContentShape.of("Myntra", "Shop   Now!")
        val b = ContentShape.of("myntra", "shop now!")
        assertEquals(a, b)
    }

    @Test
    fun titleIsPartOfTheShape() {
        val a = ContentShape.of("Myntra", "Your order shipped")
        val b = ContentShape.of("Amazon", "Your order shipped")
        assertNotEquals(a, b)
    }

    @Test
    fun outputIs32HexChars() {
        val shape = ContentShape.of("Myntra", "anything at all")
        assertEquals(32, shape.length)
        assertEquals(true, shape.all { it in "0123456789abcdef" })
    }

    @Test
    fun emptyInputIsStable() {
        assertEquals(ContentShape.of("", ""), ContentShape.of("", ""))
    }
}
