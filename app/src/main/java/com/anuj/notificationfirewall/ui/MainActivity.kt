// ui/MainActivity.kt
package com.anuj.notificationfirewall.ui

import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.anuj.notificationfirewall.data.prefs.SecurePrefs
import com.anuj.notificationfirewall.data.prefs.WallSettings
import com.anuj.notificationfirewall.service.HealthMonitor
import com.anuj.notificationfirewall.ui.ask.AskScreen
import com.anuj.notificationfirewall.ui.inbox.InboxScreen
import com.anuj.notificationfirewall.ui.onboarding.OnboardingScreen
import com.anuj.notificationfirewall.ui.settings.KeysScreen
import com.anuj.notificationfirewall.ui.settings.SettingsScreen
import com.anuj.notificationfirewall.ui.theme.LocalWallColors
import com.anuj.notificationfirewall.ui.theme.NfTheme
import com.anuj.notificationfirewall.ui.theme.ThemeMode
import com.anuj.notificationfirewall.ui.wall.WallScreen
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Nav routes. */
object Routes {
    const val WALL = "wall"
    const val INBOX = "inbox"
    const val ASK = "ask"
    const val SETTINGS = "settings"
    const val ONBOARDING = "onboarding"
    const val KEYS = "keys"

    val primary: Set<String> = setOf(WALL, INBOX, ASK, SETTINGS)
}

@HiltViewModel
class MainViewModel @Inject constructor(
    private val healthMonitor: HealthMonitor,
    securePrefs: SecurePrefs,
) : ViewModel() {
    val startDestination: String =
        if (securePrefs.hasSeenWelcome) Routes.WALL else Routes.ONBOARDING

    init {
        viewModelScope.launch { healthMonitor.refresh() }
    }
}

@HiltViewModel
class ThemeViewModel @Inject constructor(
    private val settings: WallSettings,
) : ViewModel() {
    private val _mode = MutableStateFlow(settings.themeMode)
    val mode: StateFlow<ThemeMode> = _mode.asStateFlow()

    fun setMode(m: ThemeMode) {
        settings.themeMode = m
        _mode.value = m
    }
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val themeViewModel: ThemeViewModel = hiltViewModel()
            val mode by themeViewModel.mode.collectAsStateWithLifecycle()

            NfTheme(mode) {
                val colors = LocalWallColors.current
                // Re-declare the bar style whenever the resolved theme changes,
                // so status-bar icons stay legible against the canvas behind them.
                LaunchedEffect(colors.isLight) {
                    val style = if (colors.isLight) {
                        SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
                    } else {
                        SystemBarStyle.dark(AndroidColor.TRANSPARENT)
                    }
                    enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                }
                Surface(Modifier.fillMaxSize(), color = colors.background) {
                    NfApp(themeViewModel = themeViewModel)
                }
            }
        }
    }
}

@Composable
private fun NfApp(
    themeViewModel: ThemeViewModel,
    mainViewModel: MainViewModel = hiltViewModel(),
) {
    val nav = rememberNavController()
    val currentRoute by nav.currentBackStackEntryAsState()
    val route = currentRoute?.destination?.route

    Box(Modifier.fillMaxSize()) {
        NfNavGraph(nav, mainViewModel.startDestination, themeViewModel)
        if (route in Routes.primary) {
            NfBottomBar(
                currentRoute = route,
                onSelect = { dest ->
                    if (dest != route) {
                        nav.navigate(dest) {
                            popUpTo(Routes.WALL) { saveState = true }
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
private fun NfNavGraph(nav: NavHostController, startDestination: String, themeViewModel: ThemeViewModel) {
    NavHost(navController = nav, startDestination = startDestination) {
        composable(Routes.ONBOARDING) { OnboardingScreen(nav) }
        composable(Routes.WALL) { WallScreen(nav) }
        composable(Routes.INBOX) { InboxScreen(nav) }
        composable(Routes.ASK) { AskScreen(nav) }
        composable(Routes.SETTINGS) { SettingsScreen(nav, themeViewModel) }
        composable(Routes.KEYS) { KeysScreen(nav) }
    }
}
