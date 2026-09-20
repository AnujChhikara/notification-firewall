package com.anuj.notificationfirewall.ai

/**
 * Turns a day's [DigestData] into the one-line prose the daily digest
 * notification leads with.
 *
 * Deliberately does NOT take the raw notification records, nor
 * [DigestData.worthALook]: by the time anything reaches an implementation of
 * this interface, the day has already been reduced to counts and an
 * offender's app label. That is the privacy boundary for this feature -- see
 * the KDoc on [OpenAiDigestService] for why [worthALook] never travels with
 * it.
 */
interface DigestService {
    suspend fun summarise(data: DigestData): String
}
