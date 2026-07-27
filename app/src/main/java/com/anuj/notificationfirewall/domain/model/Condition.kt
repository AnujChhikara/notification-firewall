package com.anuj.notificationfirewall.domain.model

sealed interface Condition {
    fun matches(n: IncomingNotification): Boolean

    data class AppIs(val packages: Set<String>) : Condition {
        override fun matches(n: IncomingNotification) = n.packageName in packages
    }
    data class TitleContains(val text: String) : Condition {
        override fun matches(n: IncomingNotification) = n.title.contains(text, ignoreCase = true)
    }
    data class BodyContainsAny(val keywords: List<String>) : Condition {
        override fun matches(n: IncomingNotification) =
            keywords.any { n.text.contains(it, ignoreCase = true) }
    }
    data object IsFavoriteContact : Condition {
        override fun matches(n: IncomingNotification) = n.isFavoriteContact
    }
    data class EmailFromDomain(val domain: String, val shouldMatch: Boolean) : Condition {
        override fun matches(n: IncomingNotification): Boolean {
            val isDomain = n.emailFromDomain?.equals(domain, ignoreCase = true) == true
            return isDomain == shouldMatch
        }
    }
}
