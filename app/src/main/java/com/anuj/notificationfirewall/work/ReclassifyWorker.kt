package com.anuj.notificationfirewall.work

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.anuj.notificationfirewall.data.db.dao.NotificationDao
import com.anuj.notificationfirewall.data.prefs.WallSettings
import com.anuj.notificationfirewall.domain.wall.JevApi
import com.anuj.notificationfirewall.domain.wall.JevState
import com.anuj.notificationfirewall.domain.wall.NotificationCategory
import com.anuj.notificationfirewall.domain.wall.VerdictCache
import com.anuj.notificationfirewall.domain.wall.WallBucket
import com.anuj.notificationfirewall.domain.wall.WallDecisionSource
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val TAG = "ReclassifyWorker"
private const val BATCH = 50
private val HOUR_MINUTE: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/**
 * Fills in verdicts for notifications that arrived while Jev was unreachable.
 *
 * Runs only when the network is up. It updates the stored record so stats and
 * Ask contain no holes, but never re-posts or rings: a notification that missed
 * its moment stays missed. Buzzing about an hour-old message would be worse
 * than the silence it replaces.
 */
@HiltWorker
class ReclassifyWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val notificationDao: NotificationDao,
    private val jev: JevApi,
    private val cache: VerdictCache,
    private val settings: WallSettings,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val pending = notificationDao.pending(BATCH)
        if (pending.isEmpty()) return Result.success()

        var failures = 0
        for (record in pending) {
            // Text may already have been purged by the retention job; without
            // content there is nothing to classify, so clear the flag and move
            // on rather than retrying forever.
            val title = record.title
            val text = record.text
            if (title == null && text == null) {
                notificationDao.applyVerdict(
                    id = record.id,
                    importance = 1f,
                    category = NotificationCategory.OTHER,
                    timeSensitive = 0f,
                    fromHuman = 0f,
                    needsAction = 0f,
                    confidence = 0f,
                    bucket = record.bucket,
                    source = WallDecisionSource.LEGACY,
                )
                continue
            }

            try {
                val verdict = jev.classify(
                    JevState(
                        app = record.appLabel,
                        channel = null,
                        title = title.orEmpty(),
                        text = text.orEmpty(),
                        arrivedAtLocal = Instant.ofEpochMilli(record.timestampEpochMs)
                            .atZone(ZoneId.systemDefault()).format(HOUR_MINUTE),
                        isReplyCapable = false,
                        isFromContact = false,
                    ),
                )
                cache.put(record.contentShape, record.packageName, record.senderKey, verdict)

                val biased = verdict.importance + record.biasApplied
                notificationDao.applyVerdict(
                    id = record.id,
                    importance = verdict.importance,
                    category = verdict.category,
                    timeSensitive = verdict.isTimeSensitive,
                    fromHuman = verdict.isFromHuman,
                    needsAction = verdict.needsAction,
                    confidence = verdict.confidence,
                    // Recorded for the history and stats only — nothing is
                    // re-posted, so a retroactive RING never makes a sound.
                    bucket = if (biased >= settings.threshold) WallBucket.RING else WallBucket.SILENCE,
                    source = WallDecisionSource.JEV,
                )
            } catch (e: CancellationException) {
                // A cancelled coroutine must keep propagating cancellation, not
                // be counted as a classification failure. See WallPipeline's
                // identical guard for the same reasoning.
                throw e
            } catch (e: Exception) {
                failures++
                Log.w(TAG, "Re-classification failed for ${record.id}", e)
            }
        }

        return if (failures == pending.size) Result.retry() else Result.success()
    }
}
