// ui/inbox/InboxScreen.kt
package com.anuj.notificationfirewall.ui.inbox

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class InboxViewModel @Inject constructor(
    private val notificationDao: NotificationDao,
) : ViewModel() {

    val records = notificationDao.observeCaptured()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun markRead(id: Long) {
        viewModelScope.launch { notificationDao.markRead(id) }
    }
}

@Composable
fun InboxScreen(nav: NavHostController, vm: InboxViewModel = hiltViewModel()) {
    val records by vm.records.collectAsStateWithLifecycle()
    val grouped = records.groupBy { it.appLabel }

    NfScreen(title = "Inbox", onBack = { nav.popBackStack() }) { modifier ->
        if (records.isEmpty()) {
            Text(
                "Nothing captured or silenced yet.",
                modifier = modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
            return@NfScreen
        }
        LazyColumn(
            modifier = modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            grouped.forEach { (app, recs) ->
                item {
                    Text(
                        "$app (${recs.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                items(recs) { rec -> InboxRow(rec) { vm.markRead(rec.id) } }
            }
        }
    }
}

@Composable
private fun InboxRow(rec: NotificationRecordEntity, onMarkRead: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                rec.title.ifBlank { "(no title)" },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (rec.isRead) FontWeight.Normal else FontWeight.Bold,
            )
            if (rec.text.isNotBlank()) {
                Text(rec.text, style = MaterialTheme.typography.bodyMedium, maxLines = 3)
            }
            Text(
                "${rec.bucket} · ${rec.decisionSource}",
                style = MaterialTheme.typography.labelSmall,
            )
            rec.aiReason?.let { reason ->
                Text("AI: $reason", style = MaterialTheme.typography.labelSmall)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                if (!rec.isRead) {
                    TextButton(onClick = onMarkRead) { Text("Mark read") }
                }
            }
        }
    }
}
