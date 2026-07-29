// ui/welcome/WelcomeScreen.kt
package com.anuj.notificationfirewall.ui.welcome

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.navigation.NavHostController
import com.anuj.notificationfirewall.data.prefs.SecurePrefs
import com.anuj.notificationfirewall.ui.NfButton
import com.anuj.notificationfirewall.ui.Routes
import com.anuj.notificationfirewall.ui.theme.NfAccent
import com.anuj.notificationfirewall.ui.theme.NfBackground
import com.anuj.notificationfirewall.ui.theme.NfBorder
import com.anuj.notificationfirewall.ui.theme.NfCaptured
import com.anuj.notificationfirewall.ui.theme.NfRang
import com.anuj.notificationfirewall.ui.theme.NfText
import com.anuj.notificationfirewall.ui.theme.NfTextMuted
import com.anuj.notificationfirewall.ui.theme.NfTitle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject
import androidx.compose.ui.graphics.Color

@HiltViewModel
class WelcomeViewModel @Inject constructor(
    private val securePrefs: SecurePrefs,
) : ViewModel() {
    fun markSeen() { securePrefs.hasSeenWelcome = true }
}

private data class Slide(val accent: Color, val eyebrow: String, val title: String, val body: String)

private val slides = listOf(
    Slide(
        NfAccent, "Notification Firewall",
        "Only what matters gets through",
        "A calm layer over your notifications. The noise gets held back; the people and messages you care about still reach you.",
    ),
    Slide(
        NfRang, "Profiles",
        "Set your quiet hours",
        "A Sleep profile mutes app notifications on a schedule — while phone calls, alarms, and your important contacts still ring through.",
    ),
    Slide(
        NfCaptured, "Rules & buckets",
        "You decide, per app or sender",
        "Let it through, silence it, or capture it for later. Marketing and group spam get tucked away; the rest is yours to shape.",
    ),
    Slide(
        NfAccent, "Digest & analytics",
        "Wake up in control",
        "Get a short digest of what you missed, and see exactly what's flooding in — so you can quiet it at the source.",
    ),
)

@Composable
fun WelcomeScreen(nav: NavHostController, vm: WelcomeViewModel = hiltViewModel()) {
    val pagerState = rememberPagerState(pageCount = { slides.size })
    val scope = rememberCoroutineScope()
    val isLast = pagerState.currentPage == slides.lastIndex

    fun finish() {
        vm.markSeen()
        nav.navigate(Routes.ONBOARDING) {
            popUpTo(Routes.WELCOME) { inclusive = true }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(NfBackground)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // Skip
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                "Skip",
                style = MaterialTheme.typography.labelLarge,
                color = NfTextMuted,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .clip(CircleShape)
                    .clickable { finish() }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }

        HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { page ->
            SlideView(slides[page])
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            slides.indices.forEach { i ->
                val selected = i == pagerState.currentPage
                val width by animateDpAsState(if (selected) 22.dp else 8.dp, label = "dot")
                Box(
                    Modifier
                        .size(width = width, height = 8.dp)
                        .clip(CircleShape)
                        .background(if (selected) NfAccent else NfBorder),
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        NfButton(
            if (isLast) "Get started" else "Next",
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 0.dp),
            onClick = {
                if (isLast) finish() else scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
            },
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SlideView(slide: Slide) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start,
    ) {
        Box(Modifier.size(14.dp).clip(CircleShape).background(slide.accent))
        Spacer(Modifier.height(24.dp))
        Text(slide.eyebrow, style = MaterialTheme.typography.labelSmall, color = NfTextMuted)
        Spacer(Modifier.height(6.dp))
        Text(slide.title, style = MaterialTheme.typography.headlineLarge, color = NfTitle)
        Spacer(Modifier.height(16.dp))
        Text(
            slide.body,
            style = MaterialTheme.typography.bodyLarge,
            color = NfText,
            textAlign = TextAlign.Start,
        )
    }
}
