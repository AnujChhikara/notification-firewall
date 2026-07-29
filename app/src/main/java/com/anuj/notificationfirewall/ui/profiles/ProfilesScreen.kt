// ui/profiles/ProfilesScreen.kt
package com.anuj.notificationfirewall.ui.profiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.rememberCoroutineScope
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
import com.anuj.notificationfirewall.service.ProfileStateReconciler
import com.anuj.notificationfirewall.work.ProfileScheduler
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
import javax.inject.Inject

@HiltViewModel
class ProfilesViewModel @Inject constructor(
    private val profileDao: ProfileDao,
    private val digestScheduler: com.anuj.notificationfirewall.work.DigestScheduler,
    private val profileScheduler: ProfileScheduler,
    private val reconciler: ProfileStateReconciler,
) : ViewModel() {

    val profiles = profileDao.observeProfiles()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    suspend fun load(id: Long): ProfileEntity? = profileDao.profileById(id)

    /** Creates a disabled draft profile and returns its id for editing. */
    suspend fun createProfile(): Long = profileDao.upsert(
        ProfileEntity(
            name = "New profile",
            enabled = false,
            startMinuteOfDay = 22 * 60,
            endMinuteOfDay = 7 * 60,
            daysOfWeek = (1..7).toSet(),
            order = profileDao.nextOrder(),
            aiEnabled = false,
            defaultAction = BucketAction.CAPTURE,
            autoDnd = false,
        ),
    )

    fun save(entity: ProfileEntity) {
        viewModelScope.launch {
            val id = profileDao.upsert(entity)
            val stored = profileDao.profileById(id) ?: entity
            digestScheduler.scheduleForProfile(stored.toActiveProfile())
            // Window times may have changed: re-arm the boundary alarm and reflect
            // the new state on the phone right away (foreground → may start FGS).
            profileScheduler.rescheduleAll()
            reconciler.reconcileFromDb(canStartForeground = true)
        }
    }

    fun deleteProfile(entity: ProfileEntity) {
        viewModelScope.launch {
            profileDao.delete(entity)
            profileScheduler.rescheduleAll()
            reconciler.reconcileFromDb(canStartForeground = true)
        }
    }
}

@Composable
fun ProfilesScreen(nav: NavHostController, vm: ProfilesViewModel = hiltViewModel()) {
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    NfScreen(eyebrow = "When the firewall is active", title = "Profiles", onBack = { nav.popBackStack() }) { modifier ->
        LazyColumn(
            modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 32.dp),
        ) {
            item {
                NfButton(
                    "New profile",
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { scope.launch { nav.navigate(Routes.profileEdit(vm.createProfile())) } },
                )
            }

            if (profiles.isEmpty()) {
                item {
                    Text(
                        "No profiles yet. Create one to set quiet hours or a focus window.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = NfTextMuted,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }

            items(profiles) { p ->
                NfCard {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            StatusDot(if (p.enabled) NfRang else NfTextFaint, size = 8.dp)
                            Text(p.name, style = MaterialTheme.typography.titleLarge, color = NfTitle)
                        }
                        Text(
                            "${fmt(p.startMinuteOfDay)}–${fmt(p.endMinuteOfDay)} · default ${p.defaultAction}" +
                                (if (p.autoDnd) " · Silenced" else "") + (if (p.aiEnabled) " · AI" else ""),
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

    var name by remember(current) { mutableStateOf(current.name) }
    var enabled by remember(current) { mutableStateOf(current.enabled) }
    var aiEnabled by remember(current) { mutableStateOf(current.aiEnabled) }
    var autoDnd by remember(current) { mutableStateOf(current.autoDnd) }
    var defaultAction by remember(current) { mutableStateOf(current.defaultAction) }
    var startH by remember(current) { mutableStateOf((current.startMinuteOfDay / 60).toString()) }
    var startM by remember(current) { mutableStateOf((current.startMinuteOfDay % 60).toString()) }
    var endH by remember(current) { mutableStateOf((current.endMinuteOfDay / 60).toString()) }
    var endM by remember(current) { mutableStateOf((current.endMinuteOfDay % 60).toString()) }
    var days by remember(current) { mutableStateOf(current.daysOfWeek) }

    NfScreen(eyebrow = "Profile", title = name.ifBlank { "Untitled" }, onBack = { nav.popBackStack() }) { modifier ->
        Column(
            modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SectionLabel("Name")
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                placeholder = { Text("e.g. Sleep, Work, Focus") },
                colors = fieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )

            SectionLabel("Behaviour")
            NfCard {
                Column(Modifier.padding(vertical = 4.dp, horizontal = 16.dp)) {
                    ToggleRow("Enabled", enabled) { enabled = it }
                    ToggleRow("Silence notifications while active", autoDnd) { autoDnd = it }
                    ToggleRow("AI triage (Ask-AI default)", aiEnabled) { aiEnabled = it }
                }
            }
            Text(
                "Silencing mutes app notifications while this profile is on — phone calls, " +
                    "repeat callers and alarms always ring, and so do your ring-through rules. " +
                    "Needs the \"Silence access\" grant.",
                style = MaterialTheme.typography.labelSmall, color = NfTextFaint,
                modifier = Modifier.padding(horizontal = 4.dp),
            )

            SectionLabel("Default action (no rule matches)")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(BucketAction.SILENCE, BucketAction.CAPTURE, BucketAction.ASK_AI).forEach { a ->
                    NfChip(a.name, defaultAction == a, { defaultAction = a })
                }
            }

            SectionLabel("Active window")
            NfCard {
                Row(
                    Modifier.padding(16.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    TimePicker("Start", startH, startM, { startH = it }, { startM = it })
                    TimePicker("End", endH, endM, { endH = it }, { endM = it })
                }
            }
            Text(
                "To test right now, set the window to 00:00 – 23:59.",
                style = MaterialTheme.typography.labelSmall, color = NfTextFaint,
                modifier = Modifier.padding(horizontal = 4.dp),
            )

            SectionLabel("Days")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DayOfWeek.values().forEach { dow ->
                    val v = dow.value
                    NfChip(dow.name.take(3), v in days, { days = if (v in days) days - v else days + v })
                }
            }

            Spacer(Modifier.height(4.dp))
            NfButton(
                "Save",
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    vm.save(
                        current.copy(
                            name = name.trim().ifBlank { "Untitled" },
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
            NfButton(
                "Delete profile",
                primary = false,
                modifier = Modifier.fillMaxWidth(),
                onClick = { vm.deleteProfile(current); nav.popBackStack() },
            )
        }
    }
}

@Composable
private fun TimePicker(
    label: String,
    hour: String,
    minute: String,
    onHour: (String) -> Unit,
    onMinute: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = NfTextMuted)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TimeField("HH", hour, onHour)
            Text(":", color = NfTextMuted, style = MaterialTheme.typography.titleLarge)
            TimeField("MM", minute, onMinute)
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
        colors = fieldColors(),
        modifier = Modifier.width(76.dp),
    )
}

@Composable
private fun fieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    focusedIndicatorColor = NfAccent,
    unfocusedIndicatorColor = NfBorder,
    focusedTextColor = NfTitle,
    unfocusedTextColor = NfText,
    cursorColor = NfAccent,
    focusedLabelColor = NfTextMuted,
    unfocusedLabelColor = NfTextFaint,
    focusedPlaceholderColor = NfTextFaint,
    unfocusedPlaceholderColor = NfTextFaint,
)

private fun toMinute(h: String, m: String): Int {
    val hh = h.toIntOrNull()?.coerceIn(0, 23) ?: 0
    val mm = m.toIntOrNull()?.coerceIn(0, 59) ?: 0
    return hh * 60 + mm
}

private fun fmt(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)
