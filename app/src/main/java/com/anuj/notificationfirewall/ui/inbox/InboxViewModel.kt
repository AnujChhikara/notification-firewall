package com.anuj.notificationfirewall.ui.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anuj.notificationfirewall.data.db.NotificationRecordEntity
import com.anuj.notificationfirewall.data.db.dao.NotificationDao
import com.anuj.notificationfirewall.domain.wall.BiasStore
import com.anuj.notificationfirewall.domain.wall.Correction
import com.anuj.notificationfirewall.domain.wall.NotificationCategory
import com.anuj.notificationfirewall.domain.wall.OverrideKind
import com.anuj.notificationfirewall.domain.wall.OverrideSource
import com.anuj.notificationfirewall.domain.wall.OverrideStore
import com.anuj.notificationfirewall.domain.wall.VerdictCache
import com.anuj.notificationfirewall.domain.wall.WallBucket
import com.anuj.notificationfirewall.domain.wall.WallDecisionSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * One row of the Inbox: a notification, the wall's judgement of it, and the
 * fields a correction needs. [packageName] and [contentShape] travel with the
 * row (not just derived state) because [correct] and [addOverride] need the
 * exact `(package, sender)` scope for the bias/override write and the exact
 * `(package, sender, contentShape)` triple to evict the right cache entry --
 * both keys the row's source record already carries.
 */
data class InboxRow(
    val id: Long,
    val appLabel: String,
    val sender: String?,
    val title: String?,
    val text: String?,
    val timestampMs: Long,
    val bucket: WallBucket,
    val source: WallDecisionSource,
    val importance: Float?,
    val biasApplied: Float,
    val category: NotificationCategory?,
    val confidence: Float?,
    val explanation: String,
    val packageName: String,
    val contentShape: String,
)

@HiltViewModel
class InboxViewModel @Inject constructor(
    private val notificationDao: NotificationDao,
    private val bias: BiasStore,
    private val overrides: OverrideStore,
    private val cache: VerdictCache,
) : ViewModel() {

    val rows: StateFlow<List<InboxRow>> = notificationDao.observeRecent(500)
        .map { records -> records.map { it.toRow() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Records a correction.
     *
     * Two things happen, in this order and for this reason: the bias moves
     * (bounded, per sender), then the cached verdict for this exact content is
     * evicted (so the wall stops serving the answer the user just rejected).
     * Nothing else happens -- in particular no app-wide rule is created, since
     * one messaging app carries both a family thread and forwarded junk.
     */
    fun correct(row: InboxRow, correction: Correction) {
        viewModelScope.launch {
            bias.record(row.packageName, row.sender, correction)
            cache.evict(row.packageName, row.sender, row.contentShape)
        }
    }

    fun addOverride(row: InboxRow, kind: OverrideKind) {
        viewModelScope.launch {
            overrides.add(
                kind = kind,
                pkg = row.packageName,
                sender = row.sender,
                label = row.sender ?: row.appLabel,
                source = OverrideSource.SWIPE,
            )
        }
    }

    /** Resets this sender's learned bias to zero -- the long-press sheet's "Clear learned bias". */
    fun clearBias(row: InboxRow) {
        val sender = row.sender ?: return
        viewModelScope.launch { bias.clear(row.packageName, sender) }
    }

    private fun NotificationRecordEntity.toRow() = InboxRow(
        id = id,
        packageName = packageName,
        appLabel = appLabel,
        sender = senderKey,
        title = title,
        text = text,
        timestampMs = timestampEpochMs,
        contentShape = contentShape,
        bucket = bucket,
        source = decisionSource,
        importance = importanceScore,
        biasApplied = biasApplied,
        category = category,
        confidence = jevConfidence,
        explanation = explain(importanceScore, biasApplied, category, decisionSource, jevConfidence),
    )

    companion object {
        /**
         * One human-readable line saying why this notification was treated the
         * way it was. A filter nobody can interrogate is a filter nobody
         * trusts, and an untrusted wall gets switched off.
         *
         * [WallDecisionSource.EXPIRED] deliberately never touches [importance],
         * [category] or [confidence] -- those columns are NULL by design for an
         * expired record (its text was purged before a verdict could be
         * obtained), so the message says that plainly instead of formatting a
         * fabricated score.
         */
        fun explain(
            importance: Float?,
            bias: Float,
            category: NotificationCategory?,
            source: WallDecisionSource,
            confidence: Float?,
        ): String = when (source) {
            WallDecisionSource.OTP ->
                "Rang: looks like a one-time code, which always gets through."
            WallDecisionSource.VIP ->
                "Rang: this sender is on your always-ring list."
            WallDecisionSource.BLOCK ->
                "Dropped: this sender is on your block list."
            WallDecisionSource.PENDING ->
                "Silenced: could not reach the classifier, will be re-judged shortly."
            WallDecisionSource.EXPIRED ->
                "Content expired before it could be judged."
            WallDecisionSource.LEGACY ->
                "Judged by an earlier version of the app."
            WallDecisionSource.JEV, WallDecisionSource.CACHE -> buildString {
                append(category?.name?.lowercase()?.replace('_', ' ') ?: "uncategorised")
                append(" · importance ")
                append(importance?.let { "%.1f".format(it) } ?: "unknown")
                if (bias != 0f) {
                    append(" · %+.2f from your corrections".format(bias))
                }
                if (source == WallDecisionSource.CACHE) append(" · cached")
                confidence?.let { append(" · %.0f%% confident".format(it * 100)) }
            }
        }
    }
}
