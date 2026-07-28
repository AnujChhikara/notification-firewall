package com.anuj.notificationfirewall.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Headless tests for the pure e-mail-domain extraction helper used by
 * [NotificationMapper]. The rest of the mapper (PackageManager label lookup,
 * ContactsContract starred lookup, StatusBarNotification parsing) is Android
 * I/O verified on-device per the Task 10 manual checklist.
 */
class NotificationMapperTest {

    @Test
    fun `plain address returns lowercased domain`() {
        assertEquals("work.com", extractEmailDomain("john@work.com"))
    }

    @Test
    fun `display name with angle-bracketed address extracts domain`() {
        assertEquals("work.com", extractEmailDomain("John Doe <John@Work.com>"))
    }

    @Test
    fun `uppercase domain is normalized to lowercase`() {
        assertEquals("company.co.uk", extractEmailDomain("alerts@Company.CO.UK"))
    }

    @Test
    fun `plus and dotted local parts are handled`() {
        assertEquals("gmail.com", extractEmailDomain("jane.doe+news@gmail.com"))
    }

    @Test
    fun `no address returns null`() {
        assertNull(extractEmailDomain("John Doe"))
    }

    @Test
    fun `null input returns null`() {
        assertNull(extractEmailDomain(null))
    }

    @Test
    fun `blank input returns null`() {
        assertNull(extractEmailDomain("   "))
    }
}
