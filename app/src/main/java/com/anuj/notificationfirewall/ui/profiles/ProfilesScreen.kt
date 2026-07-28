// ui/profiles/ProfilesScreen.kt
package com.anuj.notificationfirewall.ui.profiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.anuj.notificationfirewall.data.db.ProfileEntity
import com.anuj.notificationfirewall.data.db.dao.ProfileDao
import com.anuj.notificationfirewall.data.mapper.toActiveProfile
import com.anuj.notificationfirewall.domain.model.BucketAction
import com.anuj.notificationfirewall.domain.profile.ProfileManager
import com.anuj.notificationfirewall.service.DndController
import com.anuj.notificationfirewall.ui.NfButton
import com.anuj.notificationfirewall.ui.NfCard
import com.anuj.notificationfirewall.ui.NfChip
import com.anuj.notificationfirewall.ui.NfScreen
import com.anuj.notificationfirewall.ui.Routes
import com.anuj.notificationfirewall.ui.SectionLabel
import com.anuj.notificationfirewall.ui.StatusDot
import com.anuj.notificationfirewall.ui.theme.NfAccent
import com.anuj.notificationfirewall.ui.theme.NfBorder
import com.anuj.notificationfirewall.ui.theme.NfRang
import com.anuj.notificationfirewall.ui.theme.NfSurfaceElevated
import com.anuj.notificationfirewall.ui.theme.NfText
import com.anuj.notificationfirewall.ui.theme.NfTextFaint
import com.anuj.notificationfirewall.ui.theme.NfTextMuted
import com.anuj.notificationfirewall.ui.theme.NfTitle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.ZonedDateTime
import javax.inject.Inject

@HiltViewModel
class ProfilesViewModel @Inject constructor(
    private val profileDao: ProfileDao,
    private val digestScheduler: com.anuj.notificationfirewall.work.DigestScheduler,
    private val profileManager: ProfileManager,
    private val dndController: DndController,
) : ViewModel() {

    val profiles = profileDao.observeProfiles()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    suspend fun load(id: Long): ProfileEntity? = profileDao.profileById(id)

    fun save(entity: ProfileEntity) {
        viewModelScope.launch {
            val id = profileDao.upsert(entity)
            val stored = profileDao.profileById(id) ?: entity
            digestScheduler.scheduleForProfile(stored.toActiveProfile())
            val active = profileManager.activeProfile(
                profileDao.enabledProfiles().map { it.toActiveProfile() },
                ZonedDateTime.now(),
            )
            dndController.reconcile(active)
        }
    }
}

@Composable
fun ProfilesScreen(nav: NavHostController, vm: ProfilesViewModel = hiltViewModel()) {
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    NfScreen(eyebrow = "When the firewall is active", title = "Profiles", onBack = { nav.popBackStack() }) { modifier ->
        LazyColumn(
            modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(profiles) { p ->
                NfCard {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            StatusDot(if (p.enabled) NfRang else NfTextFaint, size = 8.dp)
                            Text(p.name, style = MaterialTheme.typography.titleLarge, color = NfTitle)
                        }
                        Text(
                            "${fmt(p.startMinuteOfDay)}–${fmt(p.endMinuteOfDay)} · default ${p.defaultAction}" +
                                (if (p.autoDnd) " · Auto-DND" else "") + (if (p.aiEnabled) " · AI" else ""),
                            style = MaterialTheme.typography.bodyMedium,
                            color = NfTextMuted,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            NfButton("Edit", onClick = { nav.navigate(Routes.profileEdit(p.id)) })
                            NfButton("Rules", primary = false, onClick = { nav.navigate(Routes.rules(p.id)) })
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileEditScreen(nav: NavHostController, profileId: Long, vm: ProfilesViewModel = hiltViewModel()) {
    var entity by remember { mutableStateOf<ProfileEntity?>(null) }
    LaunchedEffect(profileId) { entity = vm.load(profileId) }

    val current = entity
    if (current == null) {
        NfScreen(title = "Edit profile", onBack = { nav.popBackStack() }) { m ->
            Text("Loading…", modifier = m.padding(20.dp), color = NfTextMuted)
        }
        return
    }

    var enabled by remember(current) { mutableStateOf(current.enabled) }
    var aiEnabled by remember(current) { mutableStateOf(current.aiEnabled) }
    var autoDnd by remember(current) { mutableStateOf(current.autoDnd) }
    var defaultAction by remember(current) { mutableStateOf(current.defaultAction) }
    var startH by remember(current) { mutableStateOf((current.startMinuteOfDay / 60).toString()) }
    var startM by remember(current) { mutableStateOf((current.startMinuteOfDay % 60).toString()) }
    var endH by remember(current) { mutableStateOf((current.endMinuteOfDay / 60).toString()) }
    var endM by remember(current) { mutableStateOf((current.endMinuteOfDay % 60).toString()) }
    var days by remember(current) { mutableStateOf(current.daysOfWeek) }

    NfScreen(eyebrow = "Profile", title = current.name, onBack = { nav.popBackStack() }) { modifier ->
        Column(
            modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NfCard {
                Column(Modifier.padding(vertical = 4.dp, horizontal = 16.dp)) {
                    ToggleRow("Enabled", enabled) { enabled = it }
                    ToggleRow("Auto Do-Not-Disturb while active", autoDnd) { autoDnd = it }
                    ToggleRow("AI triage (Ask-AI default)", aiEnabled) { aiEnabled = it }
                }
            }
            Text(
                "Auto-DND silences everything through the system while this profile is on; only " +
                    "your ring-through rules make a sound. Needs DND access.",
                style = MaterialTheme.typography.labelSmall, color = NfTextFaint,
                modifier = Modifier.padding(horizontal = 4.dp),
            )

            SectionLabel("Default action (no rule matches)")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(BucketAction.SILENCE, BucketAction.CAPTURE, BucketAction.ASK_AI).forEach { a ->
                    NfChip(a.name, defaultAction == a, { defaultAction = a })
                }
            }

            SectionLabel("Active window (24h)")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TimeField("H", startH) { startH = it }
                TimeField("M", startM) { startM = it }
                Text("to", color = NfTextMuted)
                TimeField("H", endH) { endH = it }
                TimeField("M", endM) { endM = it }
            }
            Text(
                "To test now, set 00 : 00 to 23 : 59.",
                style = MaterialTheme.typography.labelSmall, color = NfTextFaint,
                modifier = Modifier.padding(horizontal = 4.dp),
            )

            SectionLabel("Days")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DayOfWeek.values().forEach { dow ->
                    val v = dow.value
                    NfChip(dow.name.take(3), v in days, { days = if (v in days) days - v else days + v })
                }
            }

            NfButton(
                "Save",
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                onClick = {
                    vm.save(
                        current.copy(
                            enabled = enabled, aiEnabled = aiEnabled, autoDnd = autoDnd,
                            defaultAction = defaultAction,
                            startMinuteOfDay = toMinute(startH, startM),
                            endMinuteOfDay = toMinute(endH, endM),
                            daysOfWeek = days.ifEmpty { (1..7).toSet() },
                        ),
                    )
                    nav.popBackStack()
                },
            )
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = NfText, modifier = Modifier.width(230.dp))
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = NfTitle,
                checkedTrackColor = NfAccent,
                uncheckedThumbColor = NfTextMuted,
                uncheckedTrackColor = NfSurfaceElevated,
                uncheckedBorderColor = NfBorder,
            ),
        )
    }
}

@Composable
private fun TimeField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { if (it.length <= 2 && it.all(Char::isDigit)) onChange(it) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = NfAccent,
            unfocusedIndicatorColor = NfBorder,
            focusedTextColor = NfTitle,
            unfocusedTextColor = NfText,
            cursorColor = NfAccent,
            focusedLabelColor = NfTextMuted,
            unfocusedLabelColor = NfTextFaint,
        ),
        modifier = Modifier.width(72.dp),
    )
}

private fun toMinute(h: String, m: String): Int {
    val hh = h.toIntOrNull()?.coerceIn(0, 23) ?: 0
    val mm = m.toIntOrNull()?.coerceIn(0, 59) ?: 0
    return hh * 60 + mm
}

private fun fmt(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)
