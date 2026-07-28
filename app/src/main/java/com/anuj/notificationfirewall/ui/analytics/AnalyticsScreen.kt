// ui/analytics/AnalyticsScreen.kt
package com.anuj.notificationfirewall.ui.analytics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.anuj.notificationfirewall.data.db.NotificationRecordEntity
import com.anuj.notificationfirewall.data.db.dao.NotificationDao
import com.anuj.notificationfirewall.ui.NfCard
import com.anuj.notificationfirewall.ui.NfScreen
import com.anuj.notificationfirewall.ui.SectionLabel
import com.anuj.notificationfirewall.ui.theme.NfText
import com.anuj.notificationfirewall.ui.theme.NfTextMuted
import com.anuj.notificationfirewall.ui.theme.NfTitle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class AnalyticsState(
    val total: Int = 0,
    val byBucket: List<Pair<String, Int>> = emptyList(),
    val bySource: List<Pair<String, Int>> = emptyList(),
    val topApps: List<Pair<String, Int>> = emptyList(),
    val topSenders: List<Pair<String, Int>> = emptyList(),
)

@HiltViewModel
class AnalyticsViewModel @Inject constructor(
    notificationDao: NotificationDao,
) : ViewModel() {

    val state = notificationDao.observeAll()
        .map { records -> records.toAnalytics() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AnalyticsState())
}

private fun List<NotificationRecordEntity>.toAnalytics(): AnalyticsState {
    fun <T> countBy(selector: (NotificationRecordEntity) -> T): List<Pair<String, Int>> =
        groupingBy { selector(it).toString() }.eachCount()
            .entries.sortedByDescending { it.value }
            .map { it.key to it.value }

    return AnalyticsState(
        total = size,
        byBucket = countBy { it.bucket },
        bySource = countBy { it.decisionSource },
        topApps = countBy { it.appLabel }.take(8),
        topSenders = countBy { it.senderKey ?: "Unknown" }.take(8),
    )
}

@Composable
fun AnalyticsScreen(nav: NavHostController, vm: AnalyticsViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()

    NfScreen(eyebrow = "What's coming in", title = "Analytics", onBack = { nav.popBackStack() }) { modifier ->
        LazyColumn(
            modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(top = 4.dp, bottom = 100.dp),
        ) {
            item {
                NfCard {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("${state.total}", style = MaterialTheme.typography.displaySmall, color = NfTitle)
                        Text("notifications seen", style = MaterialTheme.typography.bodyMedium, color = NfTextMuted)
                    }
                }
            }
            if (state.total == 0) {
                item {
                    Text(
                        "No data yet. Once the listener is running, every notification is logged here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = NfTextMuted,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
            breakdown("By outcome", state.byBucket)
            breakdown("By decision", state.bySource)
            breakdown("Top apps", state.topApps)
            breakdown("Top senders", state.topSenders)
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.breakdown(
    title: String,
    rows: List<Pair<String, Int>>,
) {
    if (rows.isEmpty()) return
    item { SectionLabel(title) }
    item {
        NfCard {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                rows.forEach { (label, count) ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(label, style = MaterialTheme.typography.bodyMedium, color = NfText)
                        Text("$count", style = MaterialTheme.typography.titleMedium, color = NfTitle)
                    }
                }
            }
        }
    }
}
