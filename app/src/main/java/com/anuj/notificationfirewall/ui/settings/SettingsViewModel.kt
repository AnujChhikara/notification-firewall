package com.anuj.notificationfirewall.ui.settings

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anuj.notificationfirewall.ai.DigestStore
import com.anuj.notificationfirewall.data.db.OverrideEntity
import com.anuj.notificationfirewall.data.db.SenderBiasEntity
import com.anuj.notificationfirewall.data.db.dao.NotificationDao
import com.anuj.notificationfirewall.data.export.HistoryExporter
import com.anuj.notificationfirewall.data.prefs.WallSettings
import com.anuj.notificationfirewall.domain.wall.BiasStore
import com.anuj.notificationfirewall.domain.wall.OverrideKind
import com.anuj.notificationfirewall.domain.wall.OverrideSource
import com.anuj.notificationfirewall.domain.wall.OverrideStore
import com.anuj.notificationfirewall.domain.wall.VerdictCache
import com.anuj.notificationfirewall.domain.wall.WallBucket
import com.anuj.notificationfirewall.ui.permissions.PermissionStatus
import com.anuj.notificationfirewall.ui.permissions.Permissions
import com.anuj.notificationfirewall.work.WallWorkScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

data class ThresholdPreview(val wouldRingMore: Int = 0, val wouldSilenceMore: Int = 0)

/**
 * What moving the slider would have done to the notifications already seen.
 *
 * A bare number from 1 to 5 means nothing to anyone. "Two more would have rung
 * you today" is a sentence someone can actually make a decision about.
 */
object ThresholdMath {
    fun preview(
        scored: List<Pair<Float, WallBucket>>,
        oldThreshold: Float,
        newThreshold: Float,
    ): ThresholdPreview {
        var ringMore = 0
        var silenceMore = 0
        // DROP comes from the block list, which no threshold can reach.
        scored.filter { it.second != WallBucket.DROP }.forEach { (score, _) ->
            val wasRinging = score >= oldThreshold
            val wouldRing = score >= newThreshold
            if (!wasRinging && wouldRing) ringMore++
            if (wasRinging && !wouldRing) silenceMore++
        }
        return ThresholdPreview(ringMore, silenceMore)
    }
}

/**
 * What a Settings list shows in place of a sender name whose source text is
 * past the user's retention window. See [sourceTextExpired].
 */
const val EXPIRED_SENDER_LABEL = "(sender expired)"

private const val MS_PER_DAY = 24L * 60 * 60 * 1000

/**
 * True when an entry last touched at [entryEpochMs] is old enough that
 * retention has already purged the notification text it was derived from.
 *
 * `overrides` and `sender_bias` are never purged by anything -- deliberately,
 * because `senderKey` is the scoping key for corrections and nulling it would
 * silently re-scope or orphan a user's own rules. But a swipe-created entry's
 * *display* string is a verbatim copy of a notification title
 * (`NotificationMapper.kt`: `val senderKey = title`), so leaving it on screen
 * means titles outlive the retention setting indefinitely, and even survive
 * "Delete all history". Rendering is where that is fixed: the key stays in
 * the database doing its job, and the screen stops showing it once the same
 * cutoff that purged `notifications` has passed.
 *
 * Retention of 0 means "never purge", so nothing ever expires.
 */
fun sourceTextExpired(entryEpochMs: Long, retentionDays: Int, nowMs: Long): Boolean =
    retentionDays > 0 && entryEpochMs < nowMs - retentionDays * MS_PER_DAY

/** One row of the VIP or block list. [fromInbox] marks an entry that accumulated
 *  from a swipe rather than one the user typed in here deliberately. */
data class OverrideRow(
    val id: Long,
    val packageName: String,
    val senderKey: String?,
    val label: String,
    val fromInbox: Boolean,
    val createdAtEpochMs: Long = 0,
) {
    /**
     * The label to render. An entry the user typed in here has an app label
     * for its [label] and is left alone; only a swipe-created, sender-scoped
     * entry carries title text, and only that one expires.
     */
    fun displayLabel(retentionDays: Int, nowMs: Long): String =
        if (fromInbox && senderKey != null && sourceTextExpired(createdAtEpochMs, retentionDays, nowMs)) {
            EXPIRED_SENDER_LABEL
        } else {
            label
        }
}

private fun OverrideEntity.toRow() = OverrideRow(
    id = id,
    packageName = packageName,
    senderKey = senderKey,
    label = label,
    fromInbox = source == OverrideSource.SWIPE,
    createdAtEpochMs = createdAtEpochMs,
)

/** One sender the wall has learned a nudge for. */
data class LearnedSenderRow(
    val packageName: String,
    val senderKey: String,
    val bias: Float,
    val lastCorrectedEpochMs: Long = 0,
) {
    /** Every sender_bias key is title-derived, so every row here can expire. */
    fun displayLabel(retentionDays: Int, nowMs: Long): String =
        if (sourceTextExpired(lastCorrectedEpochMs, retentionDays, nowMs)) EXPIRED_SENDER_LABEL else senderKey
}

private fun SenderBiasEntity.toRow() = LearnedSenderRow(packageName, senderKey, bias, lastCorrectedEpochMs)

/** One installed, launchable app — the app-picker's option list. */
data class InstalledApp(val packageName: String, val label: String)

data class SettingsUiState(
    val loading: Boolean = true,
    val threshold: Float = 4f,
    val thresholdPreview: ThresholdPreview = ThresholdPreview(),
    val vipList: List<OverrideRow> = emptyList(),
    val blockList: List<OverrideRow> = emptyList(),
    val learnedSenders: List<LearnedSenderRow> = emptyList(),
    val otpFastPathEnabled: Boolean = true,
    val digestTimeMinuteOfDay: Int = 21 * 60,
    val breakGlassDurationMinutes: Int = 15,
    val textRetentionDays: Int = 30,
    val cacheEntryCount: Int = 0,
    val historyCount: Int = 0,
    val permissionStatus: PermissionStatus? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: WallSettings,
    private val overrides: OverrideStore,
    private val bias: BiasStore,
    private val verdictCache: VerdictCache,
    private val notificationDao: NotificationDao,
    private val digestStore: DigestStore,
) : ViewModel() {

    private val historyExporter = HistoryExporter(notificationDao)

    /** The threshold as it stood when the screen was opened — the preview's
     *  fixed comparison point, so it keeps meaning "vs. what you're used to"
     *  no matter how many times the slider moves before it settles. */
    private val originalThreshold = settings.threshold

    /** Today's judged notifications as (biased score, bucket) pairs, loaded once. */
    private var todayScores: List<Pair<Float, WallBucket>> = emptyList()

    private val _ui = MutableStateFlow(
        SettingsUiState(
            threshold = originalThreshold,
            otpFastPathEnabled = settings.otpFastPathEnabled,
            digestTimeMinuteOfDay = settings.digestTimeMinuteOfDay,
            breakGlassDurationMinutes = settings.breakGlassDurationMinutes,
            textRetentionDays = settings.textRetentionDays,
        ),
    )
    val ui: StateFlow<SettingsUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            overrides.observeAll().collect { entries ->
                _ui.value = _ui.value.copy(
                    vipList = entries.filter { it.kind == OverrideKind.VIP }.map { it.toRow() },
                    blockList = entries.filter { it.kind == OverrideKind.BLOCK }.map { it.toRow() },
                )
            }
        }
        viewModelScope.launch {
            bias.observeAll().collect { entries ->
                _ui.value = _ui.value.copy(learnedSenders = entries.map { it.toRow() })
            }
        }
        viewModelScope.launch {
            loadTodayScores()
            refreshCounts()
            _ui.value = _ui.value.copy(loading = false)
        }
        refreshHealth()
    }

    private suspend fun loadTodayScores() {
        val zone = ZoneId.systemDefault()
        val startOfDay = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        val endOfDay = startOfDay + 24L * 60 * 60 * 1000 - 1
        todayScores = notificationDao.recordsBetween(startOfDay, endOfDay)
            .mapNotNull { r -> r.importanceScore?.let { (it + r.biasApplied) to r.bucket } }
        recomputePreview(_ui.value.threshold)
    }

    private fun recomputePreview(newThreshold: Float) {
        _ui.value = _ui.value.copy(
            threshold = newThreshold,
            thresholdPreview = ThresholdMath.preview(todayScores, originalThreshold, newThreshold),
        )
    }

    /** Called on every slider tick — the new value takes effect immediately,
     *  same as every other setting on this screen. */
    fun onThresholdChange(value: Float) {
        settings.threshold = value
        recomputePreview(settings.threshold)
    }

    fun setOtpFastPathEnabled(enabled: Boolean) {
        settings.otpFastPathEnabled = enabled
        _ui.value = _ui.value.copy(otpFastPathEnabled = enabled)
    }

    fun setDigestTimeMinuteOfDay(minuteOfDay: Int) {
        settings.digestTimeMinuteOfDay = minuteOfDay
        _ui.value = _ui.value.copy(digestTimeMinuteOfDay = settings.digestTimeMinuteOfDay)
        // UPDATE-policy reschedule (see DigestScheduler) so moving the slider
        // actually moves when the digest fires, not just the stored setting.
        WallWorkScheduler.scheduleDigest(context, settings.digestTimeMinuteOfDay)
    }

    fun setBreakGlassDurationMinutes(minutes: Int) {
        settings.breakGlassDurationMinutes = minutes
        _ui.value = _ui.value.copy(breakGlassDurationMinutes = settings.breakGlassDurationMinutes)
    }

    fun setTextRetentionDays(days: Int) {
        settings.textRetentionDays = days
        _ui.value = _ui.value.copy(textRetentionDays = settings.textRetentionDays)
    }

    fun addOverride(kind: OverrideKind, packageName: String, label: String) {
        viewModelScope.launch {
            overrides.add(kind, packageName, sender = null, label = label, source = OverrideSource.MANUAL)
        }
    }

    fun removeOverride(row: OverrideRow, kind: OverrideKind) {
        viewModelScope.launch {
            overrides.remove(
                OverrideEntity(
                    id = row.id,
                    kind = kind,
                    packageName = row.packageName,
                    senderKey = row.senderKey,
                    label = row.label,
                    source = if (row.fromInbox) OverrideSource.SWIPE else OverrideSource.MANUAL,
                    createdAtEpochMs = 0,
                ),
            )
        }
    }

    /** Clears one sender's learned nudge back to zero. It does not set a bias
     *  in the other direction — bias only ever tunes, never overrides. */
    fun clearBias(row: LearnedSenderRow) {
        viewModelScope.launch { bias.clear(row.packageName, row.senderKey) }
    }

    /** Wipes every learned bias. Confirmed by the caller before this is invoked. */
    fun resetAllLearning() {
        viewModelScope.launch { bias.clearAll() }
    }

    suspend fun exportJson(includeContent: Boolean): String = historyExporter.toJson(includeContent)

    /**
     * Deletes the entire notification history. Irreversible; confirmed by
     * the caller (a typed confirmation) before this is invoked.
     *
     * Also clears the persisted digest ([DigestStore]): it can carry
     * sender names/titles from rows this just deleted (see [DigestStore]'s
     * KDoc), and "delete all history" that leaves a summary of that history
     * visible on the Wall screen would not actually be deleting it.
     */
    fun deleteAllHistory() {
        viewModelScope.launch {
            notificationDao.deleteAll()
            digestStore.clear()
            refreshCounts()
        }
    }

    /** Drops every cached verdict. Safe — the next matching notification simply
     *  asks Jev again and repopulates it. */
    fun emptyCache() {
        viewModelScope.launch {
            verdictCache.clearAll()
            refreshCounts()
        }
    }

    private suspend fun refreshCounts() {
        _ui.value = _ui.value.copy(
            cacheEntryCount = verdictCache.count(),
            historyCount = notificationDao.totalCount(),
        )
    }

    /** Re-reads live permission/health state. Never cached across calls — this
     *  must be invoked again on resume so a grant made in system Settings and
     *  a return to this screen is reflected, not the snapshot from launch. */
    fun refreshHealth() {
        viewModelScope.launch {
            val status = Permissions.status(context, hasApiKey = !settings.jevKey.isNullOrBlank())
            _ui.value = _ui.value.copy(permissionStatus = status)
        }
    }

    /** Every launchable installed app, for the VIP/block "add" picker. Queried
     *  off the main thread — PackageManager enumeration is not free. */
    suspend fun installedApps(): List<InstalledApp> = withContext(Dispatchers.Default) {
        val pm = context.packageManager
        // Queried via the exact MAIN/LAUNCHER intent declared in the manifest's
        // <queries> element -- package visibility (API 30+) filters both
        // queryIntentActivities and getInstalledApplications down to almost
        // nothing unless the query matches a declared intent, so this must
        // stay in lockstep with that manifest entry.
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(launcherIntent, 0)
            .distinctBy { it.activityInfo.packageName }
            .map { InstalledApp(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .sortedBy { it.label.lowercase() }
    }
}
