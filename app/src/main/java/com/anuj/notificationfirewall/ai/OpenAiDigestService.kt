package com.anuj.notificationfirewall.ai

import com.anuj.notificationfirewall.data.db.NotificationRecordEntity
import com.anuj.notificationfirewall.domain.wall.WallBucket

private const val MODEL = "gpt-4o-mini"

private const val SYSTEM_PROMPT = """
You are writing the one or two sentence lead-in for a daily "notification
wall" digest. The user gets a short recap of how many notifications rang,
were silenced, or were dropped over the last day, and which app silenced the
most. Given only those counts and the top offender's app name, write a
short, friendly plain-text line. Do not invent details you were not given.
Respond with plain text only, not JSON.
"""

data class DigestData(
    val rang: Int,
    val silenced: Int,
    val dropped: Int,
    val topOffender: Pair<String, Int>?,
    val worthALook: List<String>,
)

/**
 * Turns a day of records into the shape a digest needs.
 *
 * Pure and separate from the network call so the numbers are testable and so
 * a missing API key degrades to a perfectly good digest without the prose.
 */
object DigestBuilder {

    /** Below this, an item is noise and does not belong in "worth a look". */
    private const val WORTH_A_LOOK_FLOOR = 2.5f
    private const val WORTH_A_LOOK_MAX = 3

    /**
     * Verdicts below this confidence sort first: a mid-scoring notification
     * Jev was unsure about (e.g. a 3.24-confidence-0.33 KYC mail) is exactly
     * the thing a morning glance should catch, ahead of high-confidence
     * items the wall was sure it was right to silence.
     */
    private const val LOW_CONFIDENCE = 0.40f

    fun summarise(records: List<NotificationRecordEntity>): DigestData {
        val silencedRecords = records.filter { it.bucket == WallBucket.SILENCE }

        val topOffender = silencedRecords
            .groupingBy { it.appLabel }
            .eachCount()
            .maxByOrNull { it.value }
            ?.toPair()

        val worthALook = silencedRecords
            .filter { (it.importanceScore ?: 0f) >= WORTH_A_LOOK_FLOOR }
            .sortedWith(
                compareByDescending<NotificationRecordEntity> { (it.jevConfidence ?: 0f) < LOW_CONFIDENCE }
                    .thenByDescending { it.importanceScore ?: 0f },
            )
            .take(WORTH_A_LOOK_MAX)
            .map(::renderWorthALookLine)

        return DigestData(
            rang = records.count { it.bucket == WallBucket.RING },
            silenced = silencedRecords.size,
            dropped = records.count { it.bucket == WallBucket.DROP },
            topOffender = topOffender,
            worthALook = worthALook,
        )
    }

    /**
     * Retention purges [NotificationRecordEntity.title] and `.text` but
     * deliberately leaves [NotificationRecordEntity.senderKey] populated
     * (see `NotificationDao.purgeTextBefore`) -- but senderKey is a verbatim
     * copy of the title (`NotificationMapper.kt`: `val senderKey = title`),
     * so a record purged by retention still has its title sitting in
     * senderKey. Checking `title == null` here is NOT enough: it would
     * render "<the purged title>: (content expired)", resurfacing on-device
     * exactly the content retention promised to remove. [textPurgedAt] is
     * the actual source of truth for "has this record's content been
     * purged" -- when it is set, fall back to [NotificationRecordEntity.appLabel]
     * for both halves of the line instead of trusting senderKey at all.
     */
    private fun renderWorthALookLine(record: NotificationRecordEntity): String =
        if (record.textPurgedAt != null) {
            "${record.appLabel}: (content expired)"
        } else {
            "${record.senderKey ?: record.appLabel}: ${record.title ?: "(content expired)"}"
        }
}

/**
 * Composes the daily digest's headline sentence from a day's [DigestData].
 *
 * ## What leaves the device, and why
 *
 * Only [DigestData.rang], [DigestData.silenced], [DigestData.dropped], and
 * the *app label* half of [DigestData.topOffender] are ever sent to OpenAI
 * (see [promptFor]). Those are app-produced metadata under Task 6's
 * provenance rule: counts the wall itself computed, and an app label the
 * platform hands the app at post time -- never text a notifying app copied
 * from a human.
 *
 * [DigestData.worthALook] is NEVER passed to the network call. Its entries
 * are built (in [DigestBuilder]) from `senderKey` and `title`, both content
 * under Task 6's rule -- `senderKey` is a verbatim copy of the title (see
 * `NotificationMapper.kt`) -- and the user never opted in, per item, to
 * having one silenced notification's content read by a third party just so
 * the digest sentence sounds nicer. `DigestWorker` renders `worthALook`
 * straight into the notification body itself, entirely on-device; this class
 * never sees it.
 *
 * When no OpenAI key is configured, or the request fails, this returns a
 * deterministic, locally-composed string instead of failing or leaving the
 * digest silent: the counts are the useful part, and they don't depend on a
 * network call to be true.
 */
class OpenAiDigestService(
    private val client: OpenAiClient,
) : DigestService {

    override suspend fun summarise(data: DigestData): String {
        if (!client.hasApiKey) {
            return localSummary(data)
        }

        return try {
            client.chat(
                model = MODEL,
                systemPrompt = SYSTEM_PROMPT,
                userContent = promptFor(data),
                jsonMode = false,
            )
        } catch (e: Exception) {
            localSummary(data)
        }
    }

    /** Aggregates only -- see the class KDoc for why [DigestData.worthALook] never appears here. */
    private fun promptFor(data: DigestData): String {
        val offenderLine = data.topOffender
            ?.let { (label, count) -> "topOffenderApp=$label topOffenderCount=$count" }
            ?: "topOffenderApp=none"
        return "rang=${data.rang} silenced=${data.silenced} dropped=${data.dropped} $offenderLine"
    }

    private fun localSummary(data: DigestData): String {
        val offenderSentence = data.topOffender
            ?.let { (label, count) -> " $label led with $count." }
            .orEmpty()
        // Only mentioned when non-zero: DROP is reachable solely from the
        // user's own block list (never from Jev -- see WallBucket's KDoc),
        // so most days it is zero and the brief's example sentence
        // ("Yesterday: 312 silenced, 9 let through. Myntra led with 47.")
        // stays exactly as specified.
        val droppedSentence = if (data.dropped > 0) " ${data.dropped} dropped." else ""
        return "Yesterday: ${data.silenced} silenced, ${data.rang} let through.$offenderSentence$droppedSentence"
    }
}
