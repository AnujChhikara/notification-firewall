// ui/settings/KeysScreen.kt
package com.anuj.notificationfirewall.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.navigation.NavHostController
import java.util.Locale
import com.anuj.notificationfirewall.ai.jev.JevClient
import com.anuj.notificationfirewall.data.prefs.SecurePrefs
import com.anuj.notificationfirewall.data.prefs.WallSettings
import com.anuj.notificationfirewall.domain.wall.JevException
import com.anuj.notificationfirewall.domain.wall.JevState
import com.anuj.notificationfirewall.ui.NfButton
import com.anuj.notificationfirewall.ui.NfCard
import com.anuj.notificationfirewall.ui.SectionLabel
import com.anuj.notificationfirewall.ui.NfScreen
import com.anuj.notificationfirewall.ui.theme.LocalWallColors
import com.anuj.notificationfirewall.ui.theme.WallColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject

private const val JEV_BASE_URL = "https://api.typesafe.ai/"
private val TEST_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

@HiltViewModel
class KeysViewModel @Inject constructor(
    private val wallSettings: WallSettings,
    private val securePrefs: SecurePrefs,
) : ViewModel() {

    val storedJevKey: String? get() = wallSettings.jevKey
    val storedOpenAiKey: String? get() = securePrefs.openAiKey

    fun saveJevKey(value: String) {
        wallSettings.jevKey = value.trim().ifBlank { null }
    }

    fun clearJevKey() {
        wallSettings.jevKey = null
    }

    fun saveOpenAiKey(value: String) {
        securePrefs.openAiKey = value.trim().ifBlank { null }
    }

    fun clearOpenAiKey() {
        securePrefs.openAiKey = null
    }

    /**
     * Tests the key currently in the text field, not whatever the Hilt graph
     * was built with — the graph's [com.anuj.notificationfirewall.domain.wall.JevApi]
     * is fixed at process start (see AppModule.provideJevApi), so it cannot
     * see a key typed after launch.
     */
    suspend fun testJevKey(key: String): Result<Float> {
        val trimmed = key.trim()
        if (trimmed.isBlank()) return Result.failure(IllegalArgumentException("Enter a key first"))
        val client = JevClient(JEV_BASE_URL.toHttpUrl(), trimmed, OkHttpClient())
        val state = JevState(
            app = "Notification Wall",
            channel = "connectivity-test",
            title = "Test notification",
            text = "This is a connectivity test from the notification wall app.",
            arrivedAtLocal = LocalTime.now().format(TEST_TIME_FORMAT),
            isReplyCapable = false,
            isFromContact = false,
        )
        return try {
            Result.success(client.classify(state).importance)
        } catch (e: JevException) {
            Result.failure(e)
        }
    }
}

private sealed interface TestOutcome {
    data class Success(val importance: Float) : TestOutcome
    data class Failure(val message: String) : TestOutcome
}

@Composable
fun KeysScreen(nav: NavHostController, vm: KeysViewModel = hiltViewModel()) {
    val scope = rememberCoroutineScope()
    val c = LocalWallColors.current

    var jevKeyField by remember { mutableStateOf("") }
    var jevKeyEdited by remember { mutableStateOf(false) }
    var jevKeySaved by remember { mutableStateOf(false) }
    var jevKeyVisible by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testOutcome by remember { mutableStateOf<TestOutcome?>(null) }

    var openAiKeyField by remember { mutableStateOf("") }
    var openAiKeyEdited by remember { mutableStateOf(false) }
    var openAiKeySaved by remember { mutableStateOf(false) }
    var openAiKeyVisible by remember { mutableStateOf(false) }

    val jevPreview = remember(vm.storedJevKey, jevKeySaved) { KeyMasking.maskedPreview(vm.storedJevKey) }
    val openAiPreview = remember(vm.storedOpenAiKey, openAiKeySaved) { KeyMasking.maskedPreview(vm.storedOpenAiKey) }

    NfScreen(eyebrow = "Configuration", title = "API keys", onBack = { nav.popBackStack() }) { modifier ->
        Column(
            modifier = modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 100.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionLabel("Jev — required")
            NfCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "The wall cannot classify notifications without this key. " +
                            "It's stored encrypted on-device and sent only to api.typesafe.ai.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.textMuted,
                    )
                    if (jevPreview != null && !jevKeyEdited) {
                        Text(
                            "Stored: $jevPreview",
                            style = MaterialTheme.typography.labelMedium,
                            color = c.textMuted,
                        )
                    }
                    OutlinedTextField(
                        value = jevKeyField,
                        onValueChange = {
                            jevKeyField = it
                            jevKeyEdited = true
                            jevKeySaved = false
                            testOutcome = null
                        },
                        label = { Text("Jev API key") },
                        placeholder = { Text(jevPreview ?: "Paste key…") },
                        singleLine = true,
                        visualTransformation = if (jevKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        colors = nfFieldColors(c),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        NfButton(if (jevKeyVisible) "Hide" else "Show", primary = false, onClick = { jevKeyVisible = !jevKeyVisible })
                        NfButton(
                            "Save",
                            onClick = {
                                vm.saveJevKey(jevKeyField)
                                jevKeySaved = true
                                jevKeyEdited = false
                                jevKeyField = ""
                            },
                            enabled = jevKeyField.isNotBlank(),
                        )
                        NfButton(
                            "Clear",
                            primary = false,
                            onClick = {
                                vm.clearJevKey()
                                jevKeyField = ""
                                jevKeyEdited = false
                                jevKeySaved = false
                                testOutcome = null
                            },
                        )
                    }
                    if (jevKeySaved) {
                        Text(
                            "Saved. Restart the app for the new key to reach the live classifier — " +
                                "it's read once when the app process starts.",
                            style = MaterialTheme.typography.labelMedium,
                            color = c.bucketRang,
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        NfButton(
                            if (testing) "Testing…" else "Test Jev key",
                            primary = false,
                            enabled = !testing && (jevKeyField.isNotBlank() || jevPreview != null),
                            onClick = {
                                val keyToTest = jevKeyField.ifBlank { vm.storedJevKey.orEmpty() }
                                testing = true
                                testOutcome = null
                                scope.launch {
                                    val result = vm.testJevKey(keyToTest)
                                    testing = false
                                    testOutcome = result.fold(
                                        onSuccess = { importance -> TestOutcome.Success(importance) },
                                        onFailure = { e -> TestOutcome.Failure(e.message ?: "Test failed") },
                                    )
                                }
                            },
                        )
                    }
                    when (val outcome = testOutcome) {
                        is TestOutcome.Success -> Text(
                            "Round trip worked — importance score ${String.format(Locale.ROOT, "%.1f", outcome.importance)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = c.bucketRang,
                        )
                        is TestOutcome.Failure -> Text(
                            outcome.message,
                            style = MaterialTheme.typography.labelMedium,
                            color = c.danger,
                        )
                        null -> Unit
                    }
                }
            }

            SectionLabel("OpenAI — optional")
            NfCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Nothing uses this yet — it's stored for a future \"ask your data\" " +
                            "feature. You can skip this for now.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.textMuted,
                    )
                    if (openAiPreview != null && !openAiKeyEdited) {
                        Text(
                            "Stored: $openAiPreview",
                            style = MaterialTheme.typography.labelMedium,
                            color = c.textMuted,
                        )
                    }
                    OutlinedTextField(
                        value = openAiKeyField,
                        onValueChange = {
                            openAiKeyField = it
                            openAiKeyEdited = true
                            openAiKeySaved = false
                        },
                        label = { Text("OpenAI API key") },
                        placeholder = { Text(openAiPreview ?: "sk-… (optional)") },
                        singleLine = true,
                        visualTransformation = if (openAiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        colors = nfFieldColors(c),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        NfButton(if (openAiKeyVisible) "Hide" else "Show", primary = false, onClick = { openAiKeyVisible = !openAiKeyVisible })
                        NfButton(
                            "Save",
                            onClick = {
                                vm.saveOpenAiKey(openAiKeyField)
                                openAiKeySaved = true
                                openAiKeyEdited = false
                                openAiKeyField = ""
                            },
                            enabled = openAiKeyField.isNotBlank(),
                        )
                        NfButton(
                            "Clear",
                            primary = false,
                            onClick = {
                                vm.clearOpenAiKey()
                                openAiKeyField = ""
                                openAiKeyEdited = false
                                openAiKeySaved = false
                            },
                        )
                    }
                    if (openAiKeySaved) {
                        Text("Saved", style = MaterialTheme.typography.labelMedium, color = c.bucketRang)
                    }
                }
            }
        }
    }
}

@Composable
private fun nfFieldColors(c: WallColors) = TextFieldDefaults.colors(
    focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
    unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
    focusedIndicatorColor = c.accent,
    unfocusedIndicatorColor = c.border,
    focusedTextColor = c.title,
    unfocusedTextColor = c.text,
    cursorColor = c.accent,
    focusedLabelColor = c.textMuted,
    unfocusedLabelColor = c.textFaint,
)
