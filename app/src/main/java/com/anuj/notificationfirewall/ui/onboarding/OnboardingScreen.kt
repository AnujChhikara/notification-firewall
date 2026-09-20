// ui/onboarding/OnboardingScreen.kt
package com.anuj.notificationfirewall.ui.onboarding

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.anuj.notificationfirewall.data.prefs.SecurePrefs
import com.anuj.notificationfirewall.data.prefs.WallSettings
import com.anuj.notificationfirewall.ui.NfButton
import com.anuj.notificationfirewall.ui.NfCard
import com.anuj.notificationfirewall.ui.Routes
import com.anuj.notificationfirewall.ui.permissions.PermissionStatus
import com.anuj.notificationfirewall.ui.permissions.Permissions
import com.anuj.notificationfirewall.ui.theme.LocalWallColors
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/** One ask at a time, in the order onboarding walks the user through them. */
enum class OnboardingStep { NOTIFICATION_ACCESS, DND_ACCESS, POST_NOTIFICATIONS, JEV_KEY, BATTERY, DONE }

/**
 * One ask at a time, in dependency order.
 *
 * Notification access first because nothing works without it, DND access second
 * because the wall cannot actually block anything without it, and the battery
 * exemption last because it is the one the user is most likely to refuse and
 * the app is still useful without it.
 */
object OnboardingSteps {
    fun next(status: PermissionStatus, hasJevKey: Boolean): OnboardingStep = when {
        !status.notificationAccess -> OnboardingStep.NOTIFICATION_ACCESS
        !status.dndAccess -> OnboardingStep.DND_ACCESS
        !status.postNotifications -> OnboardingStep.POST_NOTIFICATIONS
        !hasJevKey -> OnboardingStep.JEV_KEY
        !status.batteryExempt -> OnboardingStep.BATTERY
        else -> OnboardingStep.DONE
    }
}

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val wallSettings: WallSettings,
    private val securePrefs: SecurePrefs,
) : ViewModel() {

    private fun hasJevKey() = !wallSettings.jevKey.isNullOrBlank()

    private fun currentStep(): OnboardingStep {
        val status = Permissions.status(context, hasApiKey = hasJevKey())
        return OnboardingSteps.next(status, hasJevKey = hasJevKey())
    }

    private val _step = MutableStateFlow(currentStep())
    val step: StateFlow<OnboardingStep> = _step.asStateFlow()

    /** Re-checked whenever the screen resumes -- e.g. returning from system settings. */
    fun refresh() {
        _step.value = currentStep()
    }

    fun saveJevKey(value: String) {
        wallSettings.jevKey = value.trim().ifBlank { null }
        refresh()
    }

    /** Marks onboarding complete. Idempotent; safe to call more than once. */
    fun finish() {
        securePrefs.hasSeenWelcome = true
    }
}

@Composable
fun OnboardingScreen(nav: NavHostController, vm: OnboardingViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val step by vm.step.collectAsStateWithLifecycle()

    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose {}
    }

    if (step == OnboardingStep.DONE) {
        // Navigate exactly once, as soon as the machine says DONE -- whether
        // that's because the last grant just came back from settings, or
        // because a returning user with everything already granted lands
        // here at all (defensive; MainViewModel should have sent them
        // straight to Routes.WALL, but this is the honest fallback).
        LaunchedEffect(Unit) {
            vm.finish()
            nav.navigate(Routes.WALL) {
                popUpTo(0)
            }
        }
        return
    }

    val c = LocalWallColors.current
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
            .padding(top = 64.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Set up the wall", style = MaterialTheme.typography.headlineLarge, color = c.title)
        Text(
            "A few grants, one at a time. Each one explains why it's needed.",
            style = MaterialTheme.typography.bodyMedium,
            color = c.textMuted,
        )

        Spacer(Modifier.padding(top = 8.dp))

        when (step) {
            OnboardingStep.NOTIFICATION_ACCESS -> StepCard(
                title = "Notification access",
                body = "The wall reads every notification as it arrives so it can decide " +
                    "whether to let it ring. Without this, nothing works.",
                buttonLabel = "Grant notification access",
                onGrant = { context.startActivity(Permissions.notificationAccessIntent()) },
            )
            OnboardingStep.DND_ACCESS -> StepCard(
                title = "Do Not Disturb access",
                body = "The wall works by holding your phone in Do Not Disturb and letting " +
                    "only the notifications that matter ring. Calls always come through — " +
                    "from anyone, including repeat callers. Alarms still go off.",
                buttonLabel = "Grant Do Not Disturb access",
                onGrant = { context.startActivity(Permissions.dndAccessIntent()) },
            )
            OnboardingStep.POST_NOTIFICATIONS -> StepCard(
                title = "Post notifications",
                body = "Android requires this permission before the wall can re-post the " +
                    "notifications it lets through, or show you the daily digest.",
                buttonLabel = "Grant permission",
                onGrant = { context.startActivity(Permissions.appNotificationSettingsIntent(context)) },
            )
            OnboardingStep.JEV_KEY -> JevKeyStep(onSave = vm::saveJevKey)
            OnboardingStep.BATTERY -> StepCard(
                title = "Skip battery optimization",
                body = "Some phone makers kill background listeners to save battery. " +
                    "Exempting the wall keeps it filtering reliably. The app still works " +
                    "without this, but less dependably.",
                buttonLabel = "Grant exemption",
                onGrant = { context.startActivity(Permissions.batteryExemptionIntent(context)) },
            )
            OnboardingStep.DONE -> Unit // handled above
        }
    }
}

@Composable
private fun StepCard(
    title: String,
    body: String,
    buttonLabel: String,
    onGrant: () -> Unit,
) {
    val c = LocalWallColors.current
    NfCard {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = c.title)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = c.text)
            NfButton(text = buttonLabel, onClick = onGrant, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun JevKeyStep(onSave: (String) -> Unit) {
    val c = LocalWallColors.current
    var field by rememberSaveable { mutableStateOf("") }
    // Masked by default, matching KeysScreen's identical Jev-key field (see
    // its PasswordVisualTransformation() default and Show/Hide toggle) --
    // this is the same secret, so it gets the same guard, not a weaker one.
    var visible by rememberSaveable { mutableStateOf(false) }
    NfCard {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Jev API key", style = MaterialTheme.typography.titleLarge, color = c.title)
            Text(
                "The wall cannot classify notifications without this key. " +
                    "It's stored encrypted on-device and sent only to api.typesafe.ai.",
                style = MaterialTheme.typography.bodyMedium,
                color = c.text,
            )
            OutlinedTextField(
                value = field,
                onValueChange = { field = it },
                label = { Text("Jev API key") },
                singleLine = true,
                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NfButton(
                    text = if (visible) "Hide" else "Show",
                    primary = false,
                    onClick = { visible = !visible },
                )
                NfButton(
                    text = "Save and continue",
                    onClick = { onSave(field) },
                    enabled = field.isNotBlank(),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
