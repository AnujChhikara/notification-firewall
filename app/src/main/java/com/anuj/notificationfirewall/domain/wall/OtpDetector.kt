package com.anuj.notificationfirewall.domain.wall

/**
 * Detects one-time passcodes locally, with no network call.
 *
 * Requires BOTH a code-shaped token and nearby OTP vocabulary. Either signal
 * alone is far too common: marketing copy is full of bare numbers, and security
 * advice ("never share your OTP") is full of the vocabulary. Demanding both
 * keeps the false-positive rate low enough that this can safely sit in front of
 * even the block list.
 */
object OtpDetector {

    private val KEYWORD = Regex(
        """\b(otp|one[\s-]?time[\s-]?(password|code|pin)|verification[\s-]?code|""" +
            """security[\s-]?code|auth(?:entication)?[\s-]?code|passcode|2fa|mfa|""" +
            """code\s+is|confirmation[\s-]?code)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** 4–8 digits, optionally split once by a space or hyphen (e.g. "482-193"). */
    private val CODE = Regex("""(?<![\w.])\d{3,4}[\s-]?\d{1,5}(?![\w.])""")

    fun isOtp(title: String, text: String): Boolean {
        val haystack = "$title $text"
        if (!KEYWORD.containsMatchIn(haystack)) return false
        val match = CODE.find(haystack) ?: return false
        val digits = match.value.count { it.isDigit() }
        return digits in 4..8
    }
}
