package com.anuj.notificationfirewall.ui.wall

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anuj.notificationfirewall.data.db.dao.NotificationDao
import com.anuj.notificationfirewall.data.prefs.WallSettings
import com.anuj.notificationfirewall.domain.wall.WallBucket
import com.anuj.notificationfirewall.service.ArmingController
import com.anuj.notificationfirewall.service.BreakGlassController
import com.anuj.notificationfirewall.service.WallState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

data class TodayCounts(val rang: Int = 0, val silenced: Int = 0, val dropped: Int = 0) {
    val total: Int get() = rang + silenced + dropped
}

data class WallUiState(
    val state: WallState = WallState.DISARMED,
    val counts: TodayCounts = TodayCounts(),
    val breakGlassUntilMs: Long? = null,
    /** Clamped 5..120; drives both the "Let everything through for N" label
     *  and the actual duration [WallViewModel.breakGlass] starts. */
    val breakGlassDurationMinutes: Int = 15,
    val loading: Boolean = true,
)

@HiltViewModel
class WallViewModel @Inject constructor(
    private val arming: ArmingController,
    private val notificationDao: NotificationDao,
    private val breakGlassController: BreakGlassController,
    private val wallSettings: WallSettings,
) : ViewModel() {

    private val _ui = MutableStateFlow(WallUiState())
    val ui: StateFlow<WallUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            // ArmingController emits on every system DND change, so a change
            // made from the system shade -- or by the break-glass alarm
            // re-arming, or the user cancelling break-glass -- repaints this
            // screen without the user having to leave and come back.
            arming.observeState().collect { state ->
                _ui.value = _ui.value.copy(
                    state = state,
                    breakGlassUntilMs = breakGlassController.activeUntilMs(),
                    breakGlassDurationMinutes = clampedBreakGlassMinutes(),
                    loading = false,
                )
            }
        }
        refresh()
    }

    fun refresh() {
        _ui.value = _ui.value.copy(
            state = arming.state(),
            breakGlassUntilMs = breakGlassController.activeUntilMs(),
            breakGlassDurationMinutes = clampedBreakGlassMinutes(),
            loading = false,
        )
        viewModelScope.launch { loadCounts() }
    }

    fun toggle() {
        if (arming.isArmed()) arming.disarm() else arming.arm()
        refresh()
    }

    /**
     * Read from [WallSettings] and clamped here even though the setter
     * already clamps to 5..120: the getter does not, so a value of 1-4
     * persisted by an older build before that clamp existed would otherwise
     * still be honoured verbatim. Used for both the button's label (via
     * [WallUiState.breakGlassDurationMinutes]) and the actual duration
     * [breakGlass] starts, so the two can never disagree the way "Let
     * everything through for 1 hour" once did against a 15-minute default.
     */
    private fun clampedBreakGlassMinutes(): Int = wallSettings.breakGlassDurationMinutes.coerceIn(5, 120)

    fun breakGlass() {
        breakGlassController.start(durationMs = clampedBreakGlassMinutes() * 60_000L)
        refresh()
    }

    /** "Re-arm now": closes the window early and re-arms immediately. */
    fun cancelBreakGlass() {
        breakGlassController.cancel()
        refresh()
    }

    private suspend fun loadCounts() {
        val zone = ZoneId.systemDefault()
        val startOfDay = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        val rows = notificationDao.countsForDay(startOfDay, startOfDay + 24L * 60 * 60 * 1000)
            .associate { it.bucket to it.count }

        _ui.value = _ui.value.copy(
            counts = TodayCounts(
                rang = rows[WallBucket.RING] ?: 0,
                silenced = rows[WallBucket.SILENCE] ?: 0,
                dropped = rows[WallBucket.DROP] ?: 0,
            ),
        )
    }
}
