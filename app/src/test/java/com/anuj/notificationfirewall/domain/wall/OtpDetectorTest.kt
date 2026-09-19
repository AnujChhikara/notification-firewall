package com.anuj.notificationfirewall.domain.wall

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OtpDetectorTest {

    @Test
    fun classicOtpMessage_isDetected() {
        assertTrue(OtpDetector.isOtp("HDFC Bank", "123456 is your OTP. Do not share it with anyone."))
    }

    @Test
    fun verificationCodePhrasing_isDetected() {
        assertTrue(OtpDetector.isOtp("WhatsApp", "Your verification code is 482-193"))
    }

    @Test
    fun codeIsPhrasing_isDetected() {
        assertTrue(OtpDetector.isOtp("Google", "Your code is 8821"))
    }

    @Test
    fun twoFactorPhrasing_isDetected() {
        assertTrue(OtpDetector.isOtp("GitHub", "2FA code: 553201"))
    }

    @Test
    fun otpKeywordInTitle_isDetected() {
        assertTrue(OtpDetector.isOtp("OTP from Axis Bank", "Use 990211 to complete your login"))
    }

    @Test
    fun marketingWithNumbers_isNotDetected() {
        assertFalse(OtpDetector.isOtp("Myntra", "FLAT 70% OFF on 12000 styles. Shop now!"))
    }

    @Test
    fun deliveryUpdateWithOrderNumber_isNotDetected() {
        assertFalse(OtpDetector.isOtp("Swiggy", "Order 483920 is on the way, arriving in 12 mins"))
    }

    @Test
    fun keywordWithoutAnyCode_isNotDetected() {
        assertFalse(OtpDetector.isOtp("Bank", "Never share your OTP with anyone, we will never ask."))
    }

    @Test
    fun codeTooLong_isNotDetected() {
        assertFalse(OtpDetector.isOtp("Bank", "Your code is 1234567890123"))
    }

    @Test
    fun ordinaryConversation_isNotDetected() {
        assertFalse(OtpDetector.isOtp("Mom", "Call me when you're free, it's about Sunday"))
    }
}
