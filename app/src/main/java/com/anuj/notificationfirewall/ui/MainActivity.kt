// ui/MainActivity.kt
package com.anuj.notificationfirewall.ui

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
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
        // Draw behind the status/navigation bars with transparent, light-content
        // (SystemBarStyle.dark) bars so the app's black canvas is continuous with
        // the system bars.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent {
            NfTheme {
                Surface(Modifier.fillMaxSize()) {
                    NfApp()
                }
            }
        }
    }
}

@Composable
private fun NfApp(
    @Suppress("UNUSED_PARAMETER") mainViewModel: MainViewModel = hiltViewModel(),
) {
    val nav = rememberNavController()
    val currentRoute by nav.currentBackStackEntryAsState()
    val route = currentRoute?.destination?.route
    val primaryRoutes = setOf(Routes.HOME, Routes.INBOX, Routes.ANALYTICS, Routes.SETTINGS)

    Box(Modifier.fillMaxSize()) {
        NfNavGraph(nav)
        if (route in primaryRoutes) {
            NfBottomBar(
                currentRoute = route,
                onSelect = { dest ->
                    if (dest != route) {
                        nav.navigate(dest) {
                            popUpTo(Routes.HOME) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
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
