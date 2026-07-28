// ui/digest/DigestScreen.kt
package com.anuj.notificationfirewall.ui.digest

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.anuj.notificationfirewall.ai.DigestService
import com.anuj.notificationfirewall.data.db.dao.NotificationDao
import com.anuj.notificationfirewall.ui.NfScreen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DigestUiState(
    val loading: Boolean = false,
    val summary: String? = null,
    val count: Int = 0,
)

@HiltViewModel
class DigestViewModel @Inject constructor(
    private val notificationDao: NotificationDao,
    private val digestService: DigestService,
) : ViewModel() {

    private val _state = MutableStateFlow(DigestUiState())
    val state: StateFlow<DigestUiState> = _state

    /** On-demand digest over the last [hours] hours — handy for testing the AI path. */
    fun generate(hours: Long = 12) {
        viewModelScope.launch {
            _state.value = DigestUiState(loading = true)
            val end = System.currentTimeMillis()
            val start = end - hours * 60 * 60 * 1000
            val records = notificationDao.recordsBetween(start, end)
            val summary = digestService.summarize(records)
            _state.value = DigestUiState(loading = false, summary = summary, count = records.size)
        }
    }
}

@Composable
fun DigestScreen(nav: NavHostController, vm: DigestViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()

    NfScreen(title = "Wake-up digest", onBack = { nav.popBackStack() }) { modifier ->
        Column(
            modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "The digest normally posts automatically when a profile's window ends. " +
                    "Generate one now over the last 12 hours to preview it.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = { vm.generate() }, enabled = !state.loading) {
                Text("Generate now")
            }
            if (state.loading) CircularProgressIndicator()
            state.summary?.let { summary ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Summary (${state.count} records)", style = MaterialTheme.typography.titleSmall)
                        Text(summary, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
