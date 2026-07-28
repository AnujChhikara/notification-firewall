// ui/MainActivity.kt
package com.anuj.notificationfirewall.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.anuj.notificationfirewall.data.seed.DefaultSeeder
import com.anuj.notificationfirewall.ui.analytics.AnalyticsScreen
import com.anuj.notificationfirewall.ui.digest.DigestScreen
import com.anuj.notificationfirewall.ui.home.HomeScreen
import com.anuj.notificationfirewall.ui.inbox.InboxScreen
import com.anuj.notificationfirewall.ui.onboarding.OnboardingScreen
import com.anuj.notificationfirewall.ui.profiles.ProfileEditScreen
import com.anuj.notificationfirewall.ui.profiles.ProfilesScreen
import com.anuj.notificationfirewall.ui.rules.RuleBuilderScreen
import com.anuj.notificationfirewall.ui.settings.SettingsScreen
import com.anuj.notificationfirewall.ui.theme.NfTheme
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Nav routes. Screens that take an id append it as a path arg. */
object Routes {
    const val HOME = "home"
    const val ONBOARDING = "onboarding"
    const val INBOX = "inbox"
    const val ANALYTICS = "analytics"
    const val PROFILES = "profiles"
    const val SETTINGS = "settings"
    const val DIGEST = "digest"
    const val PROFILE_EDIT = "profile_edit/{profileId}"
    const val RULES = "rules/{profileId}"

    fun profileEdit(profileId: Long) = "profile_edit/$profileId"
    fun rules(profileId: Long) = "rules/$profileId"
}

@HiltViewModel
class MainViewModel @Inject constructor(
    private val seeder: DefaultSeeder,
) : ViewModel() {
    init {
        // First-run seed of the Sleep profile + rule table (design §7).
        viewModelScope.launch { seeder.seedIfEmpty() }
    }
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NfTheme {
                Surface {
                    NfApp()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NfApp(
    @Suppress("UNUSED_PARAMETER") mainViewModel: MainViewModel = hiltViewModel(),
) {
    val nav = rememberNavController()
    NfNavGraph(nav)
}

@Composable
private fun NfNavGraph(nav: NavHostController) {
    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) { HomeScreen(nav) }
        composable(Routes.ONBOARDING) { OnboardingScreen(nav) }
        composable(Routes.INBOX) { InboxScreen(nav) }
        composable(Routes.ANALYTICS) { AnalyticsScreen(nav) }
        composable(Routes.PROFILES) { ProfilesScreen(nav) }
        composable(Routes.SETTINGS) { SettingsScreen(nav) }
        composable(Routes.DIGEST) { DigestScreen(nav) }
        composable(Routes.PROFILE_EDIT) { backStack ->
            val id = backStack.arguments?.getString("profileId")?.toLongOrNull() ?: return@composable
            ProfileEditScreen(nav, id)
        }
        composable(Routes.RULES) { backStack ->
            val id = backStack.arguments?.getString("profileId")?.toLongOrNull() ?: return@composable
            RuleBuilderScreen(nav, id)
        }
    }
}

/** Shared scaffold used by the top-level screens. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NfScreen(
    title: String,
    onBack: (() -> Unit)? = null,
    content: @Composable (Modifier) -> Unit,
) {
    Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { androidx.compose.material3.Text(title) },
                navigationIcon = {
                    if (onBack != null) {
                        androidx.compose.material3.TextButton(onClick = onBack) {
                            androidx.compose.material3.Text("Back")
                        }
                    }
                },
            )
        },
    ) { padding ->
        content(Modifier.padding(padding))
    }
}
