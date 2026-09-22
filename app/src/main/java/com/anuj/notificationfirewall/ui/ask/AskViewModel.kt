package com.anuj.notificationfirewall.ui.ask

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anuj.notificationfirewall.ai.ask.AskOutcome
import com.anuj.notificationfirewall.ai.ask.AskService
import com.anuj.notificationfirewall.data.db.dao.AppCount
import com.anuj.notificationfirewall.data.db.dao.NotificationDao
import com.anuj.notificationfirewall.data.db.dao.SenderBiasDao
import com.anuj.notificationfirewall.data.prefs.SecurePrefs
import com.anuj.notificationfirewall.domain.wall.WallBucket
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlin.math.roundToInt

private const val DAY_MS = 24L * 60 * 60 * 1000
private const val STATS_WINDOW_DAYS = 7
private const val TOP_OFFENDERS = 5

/** Monday-first single-letter day labels for the weekly chart. */
private val DAY_LETTERS = listOf("M", "T", "W", "T", "F", "S", "S")

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
    /** Allowed vs quieted per day, oldest first, for the weekly chart. */
    val weekly: List<DaySplit> = emptyList(),
    /** Arrivals per daily phase, derived from [byHour]. */
    val phases: List<PhaseSplit> = emptyList(),
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
    /** When the turn was posted, for the "Today, 09:38" header. */
    val atMs: Long = System.currentTimeMillis(),
    /** Every query the agent ran for this answer, oldest first. */
    val queries: List<String> = emptyList(),
)

/** One day of the 7-day attention chart: allowed rings vs quieted pings. */
data class DaySplit(
    /** Single-letter day label (M/T/W/T/F/S/S) for the actual date. */
    val dayLabel: String,
    val rang: Int = 0,
    val quiet: Int = 0,
) {
    val total: Int get() = rang + quiet
}

/** Arrivals in one daily phase, summed from the hourly histogram. */
data class PhaseSplit(
    val name: String,
    val range: String,
    val count: Int = 0,
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
                weekly = loadWeekly(zone),
                phases = phasesFrom(hours),
            ),
        )
    }

    /**
     * Per-day allowed/quieted splits for the weekly chart, oldest first. Seven
     * small [countsForDay] queries -- the same call the Wall screen already
     * makes once for today.
     */
    private suspend fun loadWeekly(zone: ZoneId): List<DaySplit> {
        val today = LocalDate.now(zone)
        return (STATS_WINDOW_DAYS - 1 downTo 0).map { back ->
            val date = today.minusDays(back.toLong())
            val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
            val rows = notificationDao.countsForDay(start, start + DAY_MS)
                .associate { it.bucket to it.count }
            DaySplit(
                dayLabel = DAY_LETTERS[date.dayOfWeek.value - 1],
                rang = rows[WallBucket.RING] ?: 0,
                quiet = (rows[WallBucket.SILENCE] ?: 0) + (rows[WallBucket.DROP] ?: 0),
            )
        }
    }

    /**
     * Day-phase arrival counts from the hourly histogram. These are arrivals,
     * not interception rates -- per-phase quieted splits would need new DAO
     * queries, so the card reports what the histogram actually knows.
     */
    private fun phasesFrom(hours: IntArray): List<PhaseSplit> {
        fun sum(range: IntRange): Int = range.sumOf { hours.getOrElse(it) { 0 } }
        return listOf(
            PhaseSplit("Work Hours", "09:00 - 17:00", sum(9..16)),
            PhaseSplit("Evening Calm", "17:00 - 22:00", sum(17..21)),
            PhaseSplit("Nocturnal Sleep", "22:00 - 08:00", sum(22..23) + sum(0..7)),
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

        val reply = when (val outcome = askService.askDeep(asked, allowContent)) {
            is AskOutcome.Answered -> AskMessage(
                fromUser = false,
                text = outcome.text,
                sql = outcome.sql,
                queries = outcome.steps.map { it.sql },
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
