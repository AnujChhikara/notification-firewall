package com.anuj.notificationfirewall.domain.model

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ConditionTest {
    private fun notif(
        pkg: String = "com.whatsapp", title: String = "Hi", text: String = "hello",
        fav: Boolean = false, domain: String? = null
    ) = IncomingNotification(pkg, "App", title, text, "sender", fav, domain, Instant.EPOCH)

    @Test fun appIs_matches_by_package() {
        assertTrue(Condition.AppIs(setOf("com.whatsapp")).matches(notif(pkg = "com.whatsapp")))
        assertFalse(Condition.AppIs(setOf("com.whatsapp")).matches(notif(pkg = "com.gmail")))
    }
    @Test fun bodyContainsAny_is_case_insensitive() {
        assertTrue(Condition.BodyContainsAny(listOf("sale", "% off")).matches(notif(text = "Big SALE today")))
        assertFalse(Condition.BodyContainsAny(listOf("sale")).matches(notif(text = "meeting at 5")))
    }
    @Test fun emailFromDomain_shouldMatch_false_means_NOT_this_domain() {
        val notMyCompany = Condition.EmailFromDomain("mycompany.com", shouldMatch = false)
        assertTrue(notMyCompany.matches(notif(domain = "promo.io")))
        assertFalse(notMyCompany.matches(notif(domain = "mycompany.com")))
    }
    @Test fun isFavoriteContact_matches_flag() {
        assertTrue(Condition.IsFavoriteContact.matches(notif(fav = true)))
    }
}
