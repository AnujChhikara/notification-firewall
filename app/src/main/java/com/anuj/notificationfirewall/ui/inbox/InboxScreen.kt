// ui/inbox/InboxScreen.kt
package com.anuj.notificationfirewall.ui.inbox

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.anuj.notificationfirewall.domain.wall.WallBucket
import com.anuj.notificationfirewall.ui.NfScreen
import com.anuj.notificationfirewall.ui.StatusDot
import com.anuj.notificationfirewall.ui.theme.NfCaptured
import com.anuj.notificationfirewall.ui.theme.NfRang
import com.anuj.notificationfirewall.ui.theme.NfSilenced
import com.anuj.notificationfirewall.ui.theme.NfTextFaint
import com.anuj.notificationfirewall.ui.theme.NfTextMuted
import com.anuj.notificationfirewall.ui.theme.NfTitle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class InboxViewModel @Inject constructor(
    private val notificationDao: NotificationDao,
) : ViewModel() {

    // The listener logs every notification; the Inbox shows only the ones we
    // actually held back (Silenced or Dropped). TODO(Task 10): this screen is
    // rewritten against the wall pipeline; this is a minimal compile shim.
    val records = notificationDao.observeRecent(500)
        .map { all -> all.filter { it.bucket == WallBucket.SILENCE || it.bucket == WallBucket.DROP } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun markRead(id: Long) {
        viewModelScope.launch { notificationDao.markRead(id) }
    }
}

@Composable
fun InboxScreen(nav: NavHostController, vm: InboxViewModel = hiltViewModel()) {
    val records by vm.records.collectAsStateWithLifecycle()
    val unread = records.count { !it.isRead }

    NfScreen(
        eyebrow = if (unread > 0) "$unread unread" else "All caught up",
        title = "Inbox",
        onBack = { nav.popBackStack() },
    ) { modifier ->
        if (records.isEmpty()) {
            Text(
                "Nothing held back yet. Captured and silenced notifications land here.",
                modifier = modifier.padding(20.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = NfTextMuted,
            )
            return@NfScreen
        }
        LazyColumn(
            modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
            contentPadding = PaddingValues(bottom = 100.dp),
        ) {
            items(records) { rec -> InboxRow(rec) { vm.markRead(rec.id) } }
        }
    }
}

@Composable
private fun InboxRow(rec: NotificationRecordEntity, onMarkRead: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { if (!rec.isRead) onMarkRead() }
            .padding(horizontal = 8.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatusDot(wallBucketColor(rec.bucket), size = 8.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                rec.title?.ifBlank { rec.appLabel } ?: rec.appLabel,
                style = MaterialTheme.typography.titleMedium,
                color = if (rec.isRead) NfTextMuted else NfTitle,
                fontWeight = if (rec.isRead) FontWeight.Normal else FontWeight.SemiBold,
            )
            if (!rec.text.isNullOrBlank()) {
                Text(
                    rec.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = NfTextMuted,
                    maxLines = 2,
                )
            }
            Text(
                "${rec.appLabel} · ${wallBucketLabel(rec.bucket)}",
                style = MaterialTheme.typography.labelSmall,
                color = NfTextFaint,
            )
        }
        Text(
            relativeTime(rec.timestampEpochMs),
            style = MaterialTheme.typography.labelSmall,
            color = NfTextFaint,
        )
    }
}

// TODO(Task 10): temporary WallBucket display mapping; this screen is
// rewritten against the wall pipeline.
private fun wallBucketColor(bucket: WallBucket) = when (bucket) {
    WallBucket.RING -> NfRang
    WallBucket.SILENCE -> NfSilenced
    WallBucket.DROP -> NfCaptured
}

private fun wallBucketLabel(bucket: WallBucket) = when (bucket) {
    WallBucket.RING -> "Rang through"
    WallBucket.SILENCE -> "Silenced"
    WallBucket.DROP -> "Dropped"
}

private fun relativeTime(epochMs: Long): String {
    val diff = System.currentTimeMillis() - epochMs
    val minutes = diff / 60_000
    return when {
        minutes < 1 -> "now"
        minutes < 60 -> "${minutes}m"
        minutes < 60 * 24 -> "${minutes / 60}h"
        else -> "${minutes / (60 * 24)}d"
    }
}
