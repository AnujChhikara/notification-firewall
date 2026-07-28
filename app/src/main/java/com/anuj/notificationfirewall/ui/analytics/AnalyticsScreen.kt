// ui/analytics/AnalyticsScreen.kt
package com.anuj.notificationfirewall.ui.analytics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.anuj.notificationfirewall.data.db.NotificationRecordEntity
import com.anuj.notificationfirewall.data.db.dao.NotificationDao
import com.anuj.notificationfirewall.ui.NfScreen
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
        topApps = countBy { it.appLabel }.take(10),
        topSenders = countBy { it.senderKey ?: "(unknown)" }.take(10),
    )
}

@Composable
fun AnalyticsScreen(nav: NavHostController, vm: AnalyticsViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()

    NfScreen(title = "Analytics", onBack = { nav.popBackStack() }) { modifier ->
        LazyColumn(
            modifier = modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("${state.total}", style = MaterialTheme.typography.headlineMedium)
                        Text("notifications seen so far", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (state.total == 0) {
                item {
                    Text(
                        "No data yet. Once the listener is running and a profile is " +
                            "active, every notification is logged here.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            item { Section("By outcome (bucket)", state.byBucket) }
            item { Section("By decision source", state.bySource) }
            item { Section("Top apps", state.topApps) }
            item { Section("Top senders", state.topSenders) }
        }
    }
}

@Composable
private fun Section(title: String, rows: List<Pair<String, Int>>) {
    if (rows.isEmpty()) return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            rows.forEach { (label, count) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                    Text("$count", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
