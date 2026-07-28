// ui/home/HomeScreen.kt
package com.anuj.notificationfirewall.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.anuj.notificationfirewall.data.db.NotificationRecordEntity
import com.anuj.notificationfirewall.data.db.ProfileEntity
import com.anuj.notificationfirewall.data.db.dao.NotificationDao
import com.anuj.notificationfirewall.data.db.dao.ProfileDao
import com.anuj.notificationfirewall.data.mapper.toActiveProfile
import com.anuj.notificationfirewall.data.prefs.SecurePrefs
import com.anuj.notificationfirewall.domain.profile.ProfileManager
import com.anuj.notificationfirewall.ui.NfScreen
import com.anuj.notificationfirewall.ui.Routes
import com.anuj.notificationfirewall.ui.permissions.Permissions
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.ZonedDateTime
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    profileDao: ProfileDao,
    notificationDao: NotificationDao,
    private val profileManager: ProfileManager,
    private val securePrefs: SecurePrefs,
) : ViewModel() {

    val profiles = profileDao.observeProfiles()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recent = notificationDao.observeAll()
        .map { it.take(10) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val hasApiKey: Boolean get() = securePrefs.hasKey

    fun activeProfileName(all: List<ProfileEntity>): String? {
        val active = profileManager.activeProfile(
            all.filter { it.enabled }.map { it.toActiveProfile() },
            ZonedDateTime.now(),
        )
        return active?.name
    }
}

@Composable
fun HomeScreen(nav: NavHostController, vm: HomeViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val status = remember(profiles) { Permissions.status(context, vm.hasApiKey) }
    val activeName = remember(profiles) { vm.activeProfileName(profiles) }

    NfScreen(title = "Notification Firewall") { modifier ->
        LazyColumn(
            modifier = modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Status", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (status.coreReady) "Firewall is armed." else "Setup incomplete.",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            activeName?.let { "Active profile: $it" }
                                ?: "No profile active right now (pass-through).",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (!status.coreReady) {
                            Button(onClick = { nav.navigate(Routes.ONBOARDING) }) {
                                Text("Finish setup")
                            }
                        }
                    }
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { nav.navigate(Routes.INBOX) }, Modifier.fillMaxWidth()) { Text("Inbox") }
                    Button(onClick = { nav.navigate(Routes.ANALYTICS) }, Modifier.fillMaxWidth()) { Text("Analytics") }
                    Button(onClick = { nav.navigate(Routes.PROFILES) }, Modifier.fillMaxWidth()) { Text("Profiles & rules") }
                    Button(onClick = { nav.navigate(Routes.DIGEST) }, Modifier.fillMaxWidth()) { Text("Wake-up digest") }
                    Button(onClick = { nav.navigate(Routes.SETTINGS) }, Modifier.fillMaxWidth()) { Text("Settings") }
                    Button(onClick = { nav.navigate(Routes.ONBOARDING) }, Modifier.fillMaxWidth()) { Text("Permissions") }
                }
            }

            item { Text("Recent decisions", style = MaterialTheme.typography.titleMedium) }
            if (recent.isEmpty()) {
                item { Text("No captured/silenced notifications yet.", style = MaterialTheme.typography.bodyMedium) }
            }
            items(recent) { rec -> RecentRow(rec) }
        }
    }
}

@Composable
private fun RecentRow(rec: NotificationRecordEntity) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("${rec.appLabel} · ${rec.bucket}", style = MaterialTheme.typography.labelLarge)
            Text(rec.title, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
