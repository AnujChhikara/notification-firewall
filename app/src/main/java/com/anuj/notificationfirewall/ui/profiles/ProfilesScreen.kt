// ui/profiles/ProfilesScreen.kt
package com.anuj.notificationfirewall.ui.profiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
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
import com.anuj.notificationfirewall.ui.NfScreen
import com.anuj.notificationfirewall.ui.Routes
import com.anuj.notificationfirewall.work.DigestScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import javax.inject.Inject

@HiltViewModel
class ProfilesViewModel @Inject constructor(
    private val profileDao: ProfileDao,
    private val digestScheduler: DigestScheduler,
) : ViewModel() {

    val profiles = profileDao.observeProfiles()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    suspend fun load(id: Long): ProfileEntity? = profileDao.profileById(id)

    fun save(entity: ProfileEntity) {
        viewModelScope.launch {
            val id = profileDao.upsert(entity)
            val stored = profileDao.profileById(id) ?: entity
            digestScheduler.scheduleForProfile(stored.toActiveProfile())
        }
    }
}

@Composable
fun ProfilesScreen(nav: NavHostController, vm: ProfilesViewModel = hiltViewModel()) {
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    NfScreen(title = "Profiles", onBack = { nav.popBackStack() }) { modifier ->
        LazyColumn(
            modifier = modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(profiles) { p ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(p.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${if (p.enabled) "Enabled" else "Disabled"} · " +
                                "${fmt(p.startMinuteOfDay)}–${fmt(p.endMinuteOfDay)} · " +
                                "default ${p.defaultAction}" + if (p.aiEnabled) " · AI on" else "",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { nav.navigate(Routes.profileEdit(p.id)) }) { Text("Edit") }
                            OutlinedButton(onClick = { nav.navigate(Routes.rules(p.id)) }) { Text("Rules") }
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
            Text("Loading…", modifier = m.padding(16.dp))
        }
        return
    }

    var enabled by remember(current) { mutableStateOf(current.enabled) }
    var aiEnabled by remember(current) { mutableStateOf(current.aiEnabled) }
    var defaultAction by remember(current) { mutableStateOf(current.defaultAction) }
    var startH by remember(current) { mutableStateOf((current.startMinuteOfDay / 60).toString()) }
    var startM by remember(current) { mutableStateOf((current.startMinuteOfDay % 60).toString()) }
    var endH by remember(current) { mutableStateOf((current.endMinuteOfDay / 60).toString()) }
    var endM by remember(current) { mutableStateOf((current.endMinuteOfDay % 60).toString()) }
    var days by remember(current) { mutableStateOf(current.daysOfWeek) }

    NfScreen(title = "Edit ${current.name}", onBack = { nav.popBackStack() }) { modifier ->
        Column(
            modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ToggleRow("Enabled", enabled) { enabled = it }
            ToggleRow("AI triage (Ask-AI default calls OpenAI)", aiEnabled) { aiEnabled = it }

            Text("Default action (no rule matches)", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(BucketAction.SILENCE, BucketAction.CAPTURE, BucketAction.ASK_AI).forEach { a ->
                    FilterChip(
                        selected = defaultAction == a,
                        onClick = { defaultAction = a },
                        label = { Text(a.name) },
                    )
                }
            }

            Text("Active window (24h)", style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TimeField("Start h", startH) { startH = it }
                TimeField("m", startM) { startM = it }
                Text("to")
                TimeField("End h", endH) { endH = it }
                TimeField("m", endM) { endM = it }
            }
            Text(
                "Tip: to test right now, set the window to cover the current time " +
                    "(e.g. 00:00 to 23:59).",
                style = MaterialTheme.typography.labelSmall,
            )

            Text("Days", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DayOfWeek.values().forEach { dow ->
                    val v = dow.value
                    FilterChip(
                        selected = v in days,
                        onClick = { days = if (v in days) days - v else days + v },
                        label = { Text(dow.name.take(3)) },
                    )
                }
            }

            Button(
                onClick = {
                    vm.save(
                        current.copy(
                            enabled = enabled,
                            aiEnabled = aiEnabled,
                            defaultAction = defaultAction,
                            startMinuteOfDay = toMinute(startH, startM),
                            endMinuteOfDay = toMinute(endH, endM),
                            daysOfWeek = days.ifEmpty { (1..7).toSet() },
                        ),
                    )
                    nav.popBackStack()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save") }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(240.dp))
        Switch(checked = checked, onCheckedChange = onChange)
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
        modifier = Modifier.width(80.dp),
    )
}

private fun toMinute(h: String, m: String): Int {
    val hh = h.toIntOrNull()?.coerceIn(0, 23) ?: 0
    val mm = m.toIntOrNull()?.coerceIn(0, 59) ?: 0
    return hh * 60 + mm
}

private fun fmt(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)
