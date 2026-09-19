// ui/MainActivity.kt
package com.anuj.notificationfirewall.ui

import android.content.Context
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
import com.anuj.notificationfirewall.data.prefs.SecurePrefs
import com.anuj.notificationfirewall.service.ArmingController
import com.anuj.notificationfirewall.service.HealthMonitor
import com.anuj.notificationfirewall.service.KeepAliveService
import com.anuj.notificationfirewall.ui.welcome.WelcomeScreen
import com.anuj.notificationfirewall.ui.digest.DigestScreen
import com.anuj.notificationfirewall.ui.home.HomeScreen
import com.anuj.notificationfirewall.ui.inbox.InboxScreen
import com.anuj.notificationfirewall.ui.onboarding.OnboardingScreen
import com.anuj.notificationfirewall.ui.settings.SettingsScreen
import com.anuj.notificationfirewall.ui.theme.NfTheme
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Nav routes. */
object Routes {
    const val WELCOME = "welcome"
    const val HOME = "home"
    const val ONBOARDING = "onboarding"
    const val INBOX = "inbox"
    const val SETTINGS = "settings"
    const val DIGEST = "digest"
}

@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val armingController: ArmingController,
    private val healthMonitor: HealthMonitor,
    securePrefs: SecurePrefs,
) : ViewModel() {
    val startDestination: String = if (securePrefs.hasSeenWelcome) Routes.HOME else Routes.WELCOME

    init {
        viewModelScope.launch {
            // The app is in the foreground here, so it's a blessed context to
            // start the keep-alive service if the wall is already armed.
            if (armingController.isArmed()) KeepAliveService.start(context)
            healthMonitor.refresh()
        }
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
    mainViewModel: MainViewModel = hiltViewModel(),
) {
    val nav = rememberNavController()
    val currentRoute by nav.currentBackStackEntryAsState()
    val route = currentRoute?.destination?.route
    val primaryRoutes = setOf(Routes.HOME, Routes.SETTINGS)

    Box(Modifier.fillMaxSize()) {
        NfNavGraph(nav, mainViewModel.startDestination)
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
private fun NfNavGraph(nav: NavHostController, startDestination: String) {
    NavHost(navController = nav, startDestination = startDestination) {
        composable(Routes.WELCOME) { WelcomeScreen(nav) }
        composable(Routes.HOME) { HomeScreen(nav) }
        composable(Routes.ONBOARDING) { OnboardingScreen(nav) }
        composable(Routes.INBOX) { InboxScreen(nav) }
        composable(Routes.SETTINGS) { SettingsScreen(nav) }
        composable(Routes.DIGEST) { DigestScreen(nav) }
    }
}
