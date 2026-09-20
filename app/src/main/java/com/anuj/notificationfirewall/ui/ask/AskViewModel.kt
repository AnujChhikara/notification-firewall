package com.anuj.notificationfirewall.ui.ask

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anuj.notificationfirewall.ai.ask.AskOutcome
import com.anuj.notificationfirewall.ai.ask.AskService
import com.anuj.notificationfirewall.data.db.dao.AppCount
import com.anuj.notificationfirewall.data.db.dao.NotificationDao
import com.anuj.notificationfirewall.data.db.dao.SenderBiasDao
import com.anuj.notificationfirewall.data.prefs.SecurePrefs
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import kotlin.math.roundToInt

private const val DAY_MS = 24L * 60 * 60 * 1000
private const val STATS_WINDOW_DAYS = 7
private const val TOP_OFFENDERS = 5

/**
 * The five numbers above the chat box.
 *
 * Every percentage is nullable and is null when its denominator is zero: a
 * fresh install shows "—", not "0%". A stat card that invents a number is
 * worse than one that admits it has nothing to say, because this screen's
 * whole job is to be checkable.
 */
data class AskStats(
    val total: Int = 0,
    val keptQuiet: Int = 0,
    val topOffenders: List<AppCount> = emptyList(),
    val judged: Int = 0,
    val fromHuman: Int = 0,
    val byHour: List<Int> = List(24) { 0 },
    val corrections: Int = 0,
    val judgedByThisApp: Int = 0,
) {
    /** Share of arrivals the wall kept off the screen (silenced or dropped). */
    val noiseRatioPercent: Int? get() = share(keptQuiet, total)

    /** Share of *judged* arrivals a person actually wrote. */
    val humanSharePercent: Int? get() = share(fromHuman, judged)

    /** Corrections per notification this app judged — its own honesty metric. */
    val correctionRatePercent: Int? get() = share(corrections, judgedByThisApp)

    private fun share(part: Int, whole: Int): Int? =
        if (whole <= 0) null else (part * 100f / whole).roundToInt()
}

/** One turn of the conversation. [sql] is the query that was run, or refused. */
data class AskMessage(
    val fromUser: Boolean,
    val text: String,
    val sql: String? = null,
    val refused: Boolean = false,
)

data class AskUiState(
    val stats: AskStats = AskStats(),
    val messages: List<AskMessage> = emptyList(),
    val sending: Boolean = false,
    val allowContent: Boolean = false,
    val hasKey: Boolean = false,
)

@HiltViewModel
class AskViewModel @Inject constructor(
    private val notificationDao: NotificationDao,
    private val senderBiasDao: SenderBiasDao,
    private val askService: AskService,
    private val securePrefs: SecurePrefs,
) : ViewModel() {

    private val _ui = MutableStateFlow(AskUiState(hasKey = securePrefs.hasKey))
    val ui: StateFlow<AskUiState> = _ui.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { loadStats() }
    }

    fun send(question: String) {
        viewModelScope.launch { sendNow(question) }
    }

    /** The content opt-in is per question, never a mode. */
    fun setAllowContent(allow: Boolean) {
        _ui.value = _ui.value.copy(allowContent = allow)
    }

    /**
     * The stat cards, computed from fixed typed queries rather than through
     * [AskService]: these numbers are the app's own claims about itself, so
     * they must not depend on what a language model emitted today. They also
     * work with no API key at all.
     */
    internal suspend fun loadStats() {
        val zone = ZoneId.systemDefault()
        val since = System.currentTimeMillis() - STATS_WINDOW_DAYS * DAY_MS

        val hours = IntArray(24)
        notificationDao.timestampsSince(since).forEach { ms ->
            hours[Instant.ofEpochMilli(ms).atZone(zone).hour]++
        }

        _ui.value = _ui.value.copy(
            // Re-read on every load, not once in the initialiser: this ViewModel
            // survives navigating to Settings and back, so a key set while it was
            // off-screen must un-hide the composer on return.
            hasKey = securePrefs.hasKey,
            stats = AskStats(
                total = notificationDao.countSince(since),
                keptQuiet = notificationDao.countKeptQuietSince(since),
                topOffenders = notificationDao.topQuietedApps(since, TOP_OFFENDERS),
                judged = notificationDao.countJudgedSince(since),
                fromHuman = notificationDao.countFromHumanSince(since),
                byHour = hours.toList(),
                corrections = senderBiasDao.totalCorrections(),
                judgedByThisApp = notificationDao.countJudgedByThisApp(),
            ),
        )
    }

    internal suspend fun sendNow(question: String) {
        val asked = question.trim()
        if (asked.isEmpty() || _ui.value.sending) return

        // Read the opt-in once, then clear it in the same update that posts the
        // question. It applies to this question and nothing after it, and
        // clearing it up front means an error path cannot leave it stuck on.
        val allowContent = _ui.value.allowContent
        _ui.value = _ui.value.copy(
            messages = _ui.value.messages + AskMessage(fromUser = true, text = asked),
            sending = true,
            allowContent = false,
        )

        val reply = when (val outcome = askService.ask(asked, allowContent)) {
            is AskOutcome.Answered -> AskMessage(
                fromUser = false,
                text = outcome.text,
                sql = outcome.sql,
            )
            // A refusal is shown, with what was refused and why. Silently
            // swallowing it would teach the user the feature is flaky rather
            // than that it is careful.
            is AskOutcome.Refused -> AskMessage(
                fromUser = false,
                text = "I didn't run that query: ${outcome.reason}",
                sql = outcome.sql,
                refused = true,
            )
            is AskOutcome.Failed -> AskMessage(fromUser = false, text = outcome.reason)
        }

        _ui.value = _ui.value.copy(messages = _ui.value.messages + reply, sending = false)
    }
}
