// ui/home/HomeScreen.kt
package com.anuj.notificationfirewall.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
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
import com.anuj.notificationfirewall.ui.NfCard
import com.anuj.notificationfirewall.ui.NfRow
import com.anuj.notificationfirewall.ui.NfScreen
import com.anuj.notificationfirewall.ui.Routes
import com.anuj.notificationfirewall.ui.SectionLabel
import com.anuj.notificationfirewall.ui.StatusDot
import com.anuj.notificationfirewall.ui.bucketColor
import com.anuj.notificationfirewall.ui.bucketLabel
import com.anuj.notificationfirewall.ui.permissions.Permissions
import com.anuj.notificationfirewall.ui.theme.NfAccent
import com.anuj.notificationfirewall.ui.theme.NfRang
import com.anuj.notificationfirewall.ui.theme.NfText
import com.anuj.notificationfirewall.ui.theme.NfTextMuted
import com.anuj.notificationfirewall.ui.theme.NfTitle
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
        .map { it.take(8) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val hasApiKey: Boolean get() = securePrefs.hasKey

    fun activeProfileName(all: List<ProfileEntity>): String? =
        profileManager.activeProfile(
            all.filter { it.enabled }.map { it.toActiveProfile() },
            ZonedDateTime.now(),
        )?.name
}

@Composable
fun HomeScreen(nav: NavHostController, vm: HomeViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val status = remember(profiles) { Permissions.status(context, vm.hasApiKey) }
    val activeName = remember(profiles) { vm.activeProfileName(profiles) }

    NfScreen(eyebrow = "Notification Firewall", title = if (status.coreReady) "Armed" else "Setup needed") { modifier ->
        LazyColumn(
            modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 32.dp),
        ) {
            item {
                NfCard {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatusDot(if (status.coreReady) NfRang else NfTextMuted, size = 9.dp)
                            Text(
                                if (status.coreReady) "Watching your notifications" else "Not watching yet",
                                style = MaterialTheme.typography.titleMedium,
                                color = NfTitle,
                            )
                        }
                        Text(
                            activeName?.let { "Active profile · $it" } ?: "No profile active — everything passes through",
                            style = MaterialTheme.typography.bodyMedium,
                            color = NfTextMuted,
                        )
                    }
                }
            }

            if (!status.coreReady) {
                item {
                    NfCard(Modifier.padding(top = 8.dp)) {
                        NfRow(
                            title = "Finish setup",
                            subtitle = "Grant notification access so the firewall can start",
                            dotColor = NfAccent,
                            onClick = { nav.navigate(Routes.ONBOARDING) },
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                    }
                }
            }

            item { SectionLabel("Overview") }
            item { NfRow("Inbox", subtitle = "Captured & silenced", dotColor = NfAccent) { nav.navigate(Routes.INBOX) } }
            item { NfRow("Analytics", subtitle = "What's coming in", dotColor = NfRang) { nav.navigate(Routes.ANALYTICS) } }

            item { SectionLabel("Manage") }
            item { NfRow("Profiles & rules") { nav.navigate(Routes.PROFILES) } }
            item { NfRow("Wake-up digest") { nav.navigate(Routes.DIGEST) } }
            item { NfRow("Settings") { nav.navigate(Routes.SETTINGS) } }
            item { NfRow("Permissions") { nav.navigate(Routes.ONBOARDING) } }

            if (recent.isNotEmpty()) {
                item { SectionLabel("Recent") }
                items(recent) { rec -> RecentRow(rec) }
            }
        }
    }
}

@Composable
private fun RecentRow(rec: NotificationRecordEntity) {
    NfRow(
        title = rec.title.ifBlank { rec.appLabel },
        subtitle = "${rec.appLabel} · ${bucketLabel(rec.bucket)}",
        dotColor = bucketColor(rec.bucket),
    )
}
