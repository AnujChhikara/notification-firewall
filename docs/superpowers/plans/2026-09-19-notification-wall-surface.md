# Notification Wall — Surface Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the app the user actually touches — four screens on a light/dark token system, a master toggle that cannot lie about its state, an Inbox that explains every judgement, and an Ask tab that answers questions about the user's own notification history without their messages ever leaving the device.

**Architecture:** A semantic colour-token layer resolved from a light or dark palette through a `CompositionLocal`, so no composable ever references a raw colour. Four tabs over the engine from Plan 1, each backed by a Hilt `ViewModel` exposing a single immutable UI state. Ask uses text-to-SQL: OpenAI sees the schema and the question, returns a `SELECT`, a hard validator vets it, the device runs it, and only aggregate rows go back for phrasing.

**Tech Stack:** Jetpack Compose (BOM 2024.09.03), Material 3, Navigation Compose 2.8.2, Hilt 2.52 + hilt-navigation-compose 1.2.0, Room 2.6.1, OkHttp 4.12.0, WorkManager 2.9.1. Tests: JUnit 4.13.2, Robolectric 4.13, MockWebServer 4.12.0, kotlinx-coroutines-test 1.8.1.

**Spec:** `docs/superpowers/specs/2026-09-19-notification-wall-design.md`

**Depends on:** `docs/superpowers/plans/2026-09-19-notification-wall-engine.md` — every task below assumes all twelve engine tasks are complete and green.

## Global Constraints

- `minSdk = 26`, `compileSdk = 34`, `targetSdk = 34`. Anything above API 26 needs a `Build.VERSION.SDK_INT` guard.
- **No composable may reference a raw colour constant.** Colours come from `LocalWallColors.current` only. This is the rule that makes light mode possible at all; the current build is structurally dark-only precisely because it breaks it.
- Armed state is read from `ArmingController.observeState()`. No screen, tile, or view model may cache a boolean and render from it.
- The Ask tab may send OpenAI the schema, the question, and aggregate result rows. It may **never** send `title` or `text` column values unless the user has enabled content-in-answers for that specific query.
- Every generated SQL statement passes `SqlValidator` before execution. No exceptions, no debug bypass.
- Existing carry-overs, unchanged: `ui/theme/Type.kt`, `ui/theme/Fonts.kt`, `ui/permissions/Permissions.kt`.
- Every task ends green: `./gradlew :app:testDebugUnitTest` passes and `./gradlew :app:assembleDebug` compiles.
- Commit at the end of every task. Never commit a red build.

---

### Task 1: Semantic colour tokens, light and dark

The foundation everything else draws on. Done first because retrofitting tokens after four screens exist means touching all four again.

**Files:**
- Create: `app/src/main/java/com/anuj/notificationfirewall/ui/theme/WallColors.kt`
- Modify: `app/src/main/java/com/anuj/notificationfirewall/ui/theme/Theme.kt`
- Delete: `app/src/main/java/com/anuj/notificationfirewall/ui/theme/Color.kt`
- Modify: `app/src/main/java/com/anuj/notificationfirewall/ui/Components.kt`
- Test: `app/src/test/java/com/anuj/notificationfirewall/ui/theme/WallColorsTest.kt`

**Interfaces:**
- Consumes: `WallSettings` (engine Task 8), `WallBucket` (engine Task 5).
- Produces:
```kotlin
enum class ThemeMode { SYSTEM, LIGHT, DARK }
data class WallColors(background, surface, surfaceElevated, border, borderSubtle,
                      title, text, textMuted, textFaint, accent, accentSoft,
                      bucketRang, bucketSilenced, bucketDropped, danger, dangerSurface, isLight)
val LocalWallColors: ProvidableCompositionLocal<WallColors>
val DarkWallColors: WallColors
val LightWallColors: WallColors
@Composable fun NfTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit)
@Composable fun bucketColor(bucket: WallBucket): Color
fun bucketLabel(bucket: WallBucket): String
```
Used by every task in this plan.

- [ ] **Step 1: Write the failing contrast tests**

Create `app/src/test/java/com/anuj/notificationfirewall/ui/theme/WallColorsTest.kt`:

```kotlin
package com.anuj.notificationfirewall.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class WallColorsTest {

    /** WCAG relative-contrast ratio between two opaque colours. */
    private fun contrast(a: Color, b: Color): Float {
        val la = a.luminance() + 0.05f
        val lb = b.luminance() + 0.05f
        return max(la, lb) / min(la, lb)
    }

    @Test
    fun darkBodyTextMeetsAaOnItsBackground() {
        assertTrue(
            "body text on dark background must reach 4.5:1",
            contrast(DarkWallColors.text, DarkWallColors.background) >= 4.5f,
        )
    }

    @Test
    fun lightBodyTextMeetsAaOnItsBackground() {
        assertTrue(
            "body text on light background must reach 4.5:1",
            contrast(LightWallColors.text, LightWallColors.background) >= 4.5f,
        )
    }

    @Test
    fun mutedTextMeetsLargeTextContrastInBothThemes() {
        assertTrue(contrast(DarkWallColors.textMuted, DarkWallColors.background) >= 3.0f)
        assertTrue(contrast(LightWallColors.textMuted, LightWallColors.background) >= 3.0f)
    }

    @Test
    fun statusColoursAreDistinguishableFromSurfaceInBothThemes() {
        listOf(DarkWallColors, LightWallColors).forEach { palette ->
            listOf(palette.bucketRang, palette.bucketSilenced, palette.bucketDropped).forEach { status ->
                assertTrue(
                    "status dot must be visible against its surface",
                    contrast(status, palette.surface) >= 2.0f,
                )
            }
        }
    }

    @Test
    fun statusColoursAreDistinguishableFromEachOther() {
        listOf(DarkWallColors, LightWallColors).forEach { palette ->
            val rang = palette.bucketRang.luminance()
            val silenced = palette.bucketSilenced.luminance()
            val dropped = palette.bucketDropped.luminance()
            assertTrue(abs(rang - silenced) > 0.05f)
            assertTrue(abs(silenced - dropped) > 0.05f)
        }
    }

    @Test
    fun lightAndDarkAreGenuinelyDifferentPalettes() {
        assertNotEquals(LightWallColors.background, DarkWallColors.background)
        assertTrue(LightWallColors.isLight)
        assertTrue(!DarkWallColors.isLight)
    }

    @Test
    fun lightBackgroundIsLighterThanItsSurfaceContrastPartner() {
        assertTrue(LightWallColors.background.luminance() > 0.7f)
        assertTrue(DarkWallColors.background.luminance() < 0.1f)
    }

    @Test
    fun dangerIsLegibleOnItsOwnSurface() {
        listOf(DarkWallColors, LightWallColors).forEach { palette ->
            assertTrue(contrast(palette.danger, palette.dangerSurface) >= 3.0f)
        }
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
./gradlew :app:testDebugUnitTest --tests "*WallColorsTest*"
```

Expected: FAIL — `WallColors` unresolved.

- [ ] **Step 3: Write the token layer**

Create `app/src/main/java/com/anuj/notificationfirewall/ui/theme/WallColors.kt`:

```kotlin
package com.anuj.notificationfirewall.ui.theme

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * Every colour the app is allowed to use, named by role rather than by hue.
 *
 * Components read these and nothing else. The previous build imported raw
 * `Color` constants directly into composables, which is what made it
 * structurally dark-only — a light palette had nowhere to attach. Naming by
 * role means a component says what it means ("this is a border") and the
 * palette decides what that looks like.
 */
data class WallColors(
    val background: Color,
    val surface: Color,
    val surfaceElevated: Color,
    val border: Color,
    val borderSubtle: Color,
    val title: Color,
    val text: Color,
    val textMuted: Color,
    val textFaint: Color,
    val accent: Color,
    val accentSoft: Color,
    val bucketRang: Color,
    val bucketSilenced: Color,
    val bucketDropped: Color,
    val danger: Color,
    val dangerSurface: Color,
    val isLight: Boolean,
)

/** The original near-black palette, kept as-is — it was the good part. */
val DarkWallColors = WallColors(
    background = Color(0xFF08090A),
    surface = Color(0xFF101113),
    surfaceElevated = Color(0xFF17181B),
    border = Color(0xFF212227),
    borderSubtle = Color(0xFF17181B),
    title = Color(0xFFF7F8F8),
    text = Color(0xFFE6E7EA),
    textMuted = Color(0xFF8A8F98),
    textFaint = Color(0xFF585C64),
    accent = Color(0xFF5E6AD2),
    accentSoft = Color(0x335E6AD2),
    bucketRang = Color(0xFF48C78E),
    bucketSilenced = Color(0xFF7C8698),
    bucketDropped = Color(0xFFE0A03A),
    danger = Color(0xFFE5484D),
    dangerSurface = Color(0xFF2A1416),
    isLight = false,
)

/**
 * The light palette, built to the same restraint: one near-white canvas, barely
 * separated surfaces, one accent.
 *
 * The status colours are NOT the dark ones reused. Green at #48C78E and amber at
 * #E0A03A read clearly on near-black and almost vanish on near-white, so both
 * are darkened until they carry their meaning on a light surface too.
 */
val LightWallColors = WallColors(
    background = Color(0xFFFCFCFD),
    surface = Color(0xFFF4F5F7),
    surfaceElevated = Color(0xFFFFFFFF),
    border = Color(0xFFDCDEE3),
    borderSubtle = Color(0xFFEBECEF),
    title = Color(0xFF0D0E10),
    text = Color(0xFF26282D),
    textMuted = Color(0xFF61656E),
    textFaint = Color(0xFF8E939C),
    accent = Color(0xFF4F5BC4),
    accentSoft = Color(0x1F4F5BC4),
    bucketRang = Color(0xFF127A50),
    bucketSilenced = Color(0xFF6B7280),
    bucketDropped = Color(0xFF9A6511),
    danger = Color(0xFFC01C21),
    dangerSurface = Color(0xFFFDEBEC),
    isLight = true,
)

val LocalWallColors: ProvidableCompositionLocal<WallColors> =
    staticCompositionLocalOf { DarkWallColors }
```

- [ ] **Step 4: Rewrite `Theme.kt`**

Replace `app/src/main/java/com/anuj/notificationfirewall/ui/theme/Theme.kt` with:

```kotlin
package com.anuj.notificationfirewall.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.dp

private val NfShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private fun materialScheme(c: WallColors) = if (c.isLight) {
    lightColorScheme(
        primary = c.accent, onPrimary = c.surfaceElevated,
        secondary = c.accent,
        background = c.background, onBackground = c.text,
        surface = c.background, onSurface = c.text,
        surfaceVariant = c.surface, onSurfaceVariant = c.textMuted,
        outline = c.border, outlineVariant = c.borderSubtle,
        error = c.danger, onError = c.surfaceElevated,
        errorContainer = c.dangerSurface, onErrorContainer = c.danger,
    )
} else {
    darkColorScheme(
        primary = c.accent, onPrimary = c.title,
        secondary = c.accent,
        background = c.background, onBackground = c.text,
        surface = c.background, onSurface = c.text,
        surfaceVariant = c.surface, onSurfaceVariant = c.textMuted,
        outline = c.border, outlineVariant = c.borderSubtle,
        error = c.danger, onError = c.title,
        errorContainer = c.dangerSurface, onErrorContainer = c.text,
    )
}

@Composable
fun NfTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val light = when (mode) {
        ThemeMode.LIGHT -> true
        ThemeMode.DARK -> false
        ThemeMode.SYSTEM -> !isSystemInDarkTheme()
    }
    val colors = if (light) LightWallColors else DarkWallColors

    CompositionLocalProvider(LocalWallColors provides colors) {
        MaterialTheme(
            colorScheme = materialScheme(colors),
            typography = NfTypography,
            shapes = NfShapes,
            content = content,
        )
    }
}
```

- [ ] **Step 5: Delete the raw palette and migrate `Components.kt`**

```bash
rm "/Volumes/External SSD/dev/projects/notification-firewall/app/src/main/java/com/anuj/notificationfirewall/ui/theme/Color.kt"
```

In `Components.kt`, delete every `import ...ui.theme.Nf*` colour import and add `import com.anuj.notificationfirewall.ui.theme.LocalWallColors`. In each composable, read the palette once at the top:

```kotlin
    val c = LocalWallColors.current
```

then replace each reference mechanically: `NfBackground` → `c.background`, `NfSurface` → `c.surface`, `NfSurfaceElevated` → `c.surfaceElevated`, `NfBorder` → `c.border`, `NfBorderSubtle` → `c.borderSubtle`, `NfTitle` → `c.title`, `NfText` → `c.text`, `NfTextMuted` → `c.textMuted`, `NfTextFaint` → `c.textFaint`, `NfAccent` → `c.accent`, `NfAccentSoft` → `c.accentSoft`.

Replace the two bucket helpers at the bottom of the file — they still reference the deleted `BucketAction`:

```kotlin
@Composable
fun bucketColor(bucket: WallBucket): Color {
    val c = LocalWallColors.current
    return when (bucket) {
        WallBucket.RING -> c.bucketRang
        WallBucket.SILENCE -> c.bucketSilenced
        WallBucket.DROP -> c.bucketDropped
    }
}

fun bucketLabel(bucket: WallBucket): String = when (bucket) {
    WallBucket.RING -> "Rang through"
    WallBucket.SILENCE -> "Silenced"
    WallBucket.DROP -> "Dropped"
}
```

Delete the `NfNavItems` list and `NfBottomBar` for now — Task 2 rebuilds them against the new four-tab structure.

- [ ] **Step 6: Verify no raw colour references survive**

```bash
cd "/Volumes/External SSD/dev/projects/notification-firewall"
grep -rn "NfBackground\|NfSurface\|NfBorder\|NfTitle\|NfText\|NfAccent\|NfRang\|NfSilenced\|NfCaptured\|NfDanger" \
  app/src/main --include="*.kt"
```

Expected: no output. Any hit is a composable that will render wrong in light mode.

- [ ] **Step 7: Run the tests**

```bash
./gradlew :app:testDebugUnitTest --tests "*WallColorsTest*"
```

Expected: PASS, 8 tests. If a contrast assertion fails, darken or lighten the offending token until it passes — do not relax the assertion. The thresholds are WCAG AA, and the whole point of testing colour is that eyeballing it in one theme is how the other theme ends up unreadable.

- [ ] **Step 8: Build**

```bash
./gradlew :app:assembleDebug
```

Screens still referencing deleted colours will fail here; they are all rewritten in Tasks 3–5. If the build cannot pass yet, comment out the affected screen bodies with a `TODO(Task N)` marker rather than leaving them broken, and restore them in their own task.

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "feat(ui): semantic colour tokens with a real light palette

Colours are now resolved by role through LocalWallColors, so no
composable references a raw constant — the property that made the old
theme structurally dark-only. Status colours are re-tuned for light
surfaces rather than reused, and contrast ratios are asserted in tests
because eyeballing one theme is how the other becomes unreadable."
```

---

### Task 2: App shell — four tabs and theme-aware system bars

**Files:**
- Modify: `app/src/main/java/com/anuj/notificationfirewall/ui/MainActivity.kt`
- Modify: `app/src/main/java/com/anuj/notificationfirewall/ui/Components.kt`
- Create: `app/src/main/res/drawable/ic_nav_wall.xml`, `ic_nav_inbox.xml`, `ic_nav_ask.xml`, `ic_nav_settings.xml` (reuse the existing settings icon if present)
- Delete: `app/src/main/java/com/anuj/notificationfirewall/ui/welcome/WelcomeScreen.kt`, `ui/onboarding/OnboardingScreen.kt`, `ui/home/HomeScreen.kt`, `ui/digest/DigestScreen.kt`
- Test: `app/src/test/java/com/anuj/notificationfirewall/ui/RoutesTest.kt`

**Interfaces:**
- Consumes: `NfTheme`, `ThemeMode`, `LocalWallColors` (Task 1); `WallSettings` (engine Task 8).
- Produces:
```kotlin
object Routes { const val WALL = "wall"; const val INBOX = "inbox"; const val ASK = "ask"; const val SETTINGS = "settings"; const val ONBOARDING = "onboarding"; val primary: Set<String> }
@Composable fun NfBottomBar(currentRoute: String?, onSelect: (String) -> Unit, modifier: Modifier)
class ThemeViewModel : ViewModel { val mode: StateFlow<ThemeMode>; fun setMode(m: ThemeMode) }
```

- [ ] **Step 1: Write the failing routes test**

Create `app/src/test/java/com/anuj/notificationfirewall/ui/RoutesTest.kt`:

```kotlin
package com.anuj.notificationfirewall.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutesTest {

    @Test
    fun exactlyFourPrimaryDestinations() {
        assertEquals(4, Routes.primary.size)
    }

    @Test
    fun primaryDestinationsAreTheFourTabs() {
        assertEquals(
            setOf(Routes.WALL, Routes.INBOX, Routes.ASK, Routes.SETTINGS),
            Routes.primary,
        )
    }

    @Test
    fun onboardingIsNotAPrimaryDestination() {
        assertTrue(Routes.ONBOARDING !in Routes.primary)
    }

    @Test
    fun noStillEraRoutesRemain() {
        val fields = Routes::class.java.declaredFields.map { it.name.lowercase() }
        listOf("assessment", "program", "pods", "results", "profiles", "rules").forEach {
            assertTrue("Still-era route '$it' must be gone", it !in fields)
        }
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
./gradlew :app:testDebugUnitTest --tests "*RoutesTest*"
```

Expected: FAIL — `Routes.primary` unresolved and Still-era routes still present.

- [ ] **Step 3: Rewrite `Routes` and the nav graph**

Replace the top of `MainActivity.kt`:

```kotlin
object Routes {
    const val WALL = "wall"
    const val INBOX = "inbox"
    const val ASK = "ask"
    const val SETTINGS = "settings"
    const val ONBOARDING = "onboarding"

    val primary: Set<String> = setOf(WALL, INBOX, ASK, SETTINGS)
}
```

and the nav graph:

```kotlin
@Composable
private fun NfNavGraph(nav: NavHostController, startDestination: String) {
    NavHost(navController = nav, startDestination = startDestination) {
        composable(Routes.ONBOARDING) { OnboardingScreen(nav) }
        composable(Routes.WALL) { WallScreen(nav) }
        composable(Routes.INBOX) { InboxScreen(nav) }
        composable(Routes.ASK) { AskScreen(nav) }
        composable(Routes.SETTINGS) { SettingsScreen(nav) }
    }
}
```

Delete `MainViewModel`'s seeder/scheduler/reconciler dependencies (all removed in engine Task 11) and reduce it to:

```kotlin
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
```

- [ ] **Step 4: Make the system bars follow the theme**

The current `MainActivity` hardcodes `SystemBarStyle.dark(...)`, which paints light-coloured status-bar icons — invisible on a white canvas. Replace `onCreate` with:

```kotlin
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
                    NfApp()
                }
            }
        }
    }
```

with `import android.graphics.Color as AndroidColor`.

- [ ] **Step 5: Write `ThemeViewModel`**

Create it at the bottom of `MainActivity.kt`:

```kotlin
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
```

and add the backing property to `WallSettings` (engine Task 8):

```kotlin
    var themeMode: ThemeMode
        get() = runCatching {
            ThemeMode.valueOf(prefs.getString(KEY_THEME_MODE, null) ?: ThemeMode.SYSTEM.name)
        }.getOrDefault(ThemeMode.SYSTEM)
        set(value) = prefs.edit { putString(KEY_THEME_MODE, value.name) }
```

with `const val KEY_THEME_MODE = "wall_theme_mode"` in its companion.

- [ ] **Step 6: Rebuild the bottom bar for four tabs**

In `Components.kt`:

```kotlin
data class NfNavItem(val route: String, val iconRes: Int, val label: String)

val NfNavItems = listOf(
    NfNavItem(Routes.WALL, R.drawable.ic_nav_wall, "Wall"),
    NfNavItem(Routes.INBOX, R.drawable.ic_nav_inbox, "Inbox"),
    NfNavItem(Routes.ASK, R.drawable.ic_nav_ask, "Ask"),
    NfNavItem(Routes.SETTINGS, R.drawable.ic_nav_settings, "Settings"),
)

@Composable
fun NfBottomBar(currentRoute: String?, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = LocalWallColors.current
    Row(
        modifier
            .navigationBarsPadding()
            .padding(bottom = 14.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(c.surfaceElevated)
            .border(1.dp, c.border, RoundedCornerShape(26.dp))
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NfNavItems.forEach { item ->
            val selected = currentRoute == item.route
            Box(
                Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (selected) c.accentSoft else Color.Transparent)
                    .clickable { onSelect(item.route) }
                    .padding(horizontal = 18.dp, vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(item.iconRes),
                    contentDescription = item.label,
                    tint = if (selected) c.text else c.textMuted,
                    modifier = Modifier.size(21.dp),
                )
            }
        }
    }
}
```

Update `NfApp` to use `Routes.primary` instead of the old `primaryRoutes` set, and `popUpTo(Routes.WALL)`.

- [ ] **Step 7: Add the four nav icons**

Create simple 24dp vector drawables in `app/src/main/res/drawable/`: `ic_nav_wall.xml` (a shield outline), `ic_nav_inbox.xml` (a tray), `ic_nav_ask.xml` (a speech bubble), `ic_nav_settings.xml` (reuse the existing one if it is already there). Each uses `android:tint` so the `Icon` tint applies:

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24"
    android:tint="?attr/colorControlNormal">
    <path android:fillColor="@android:color/white"
        android:pathData="M12,2L4,5v6c0,5 3.4,9.4 8,11 4.6,-1.6 8,-6 8,-11V5L12,2z" />
</vector>
```

- [ ] **Step 8: Delete the superseded screens**

```bash
cd "/Volumes/External SSD/dev/projects/notification-firewall/app/src/main/java/com/anuj/notificationfirewall"
rm -rf ui/welcome ui/home ui/digest
```

`ui/onboarding/OnboardingScreen.kt` is rewritten in Task 11; leave it stubbed to a single `Text("Onboarding")` composable so the graph compiles until then.

- [ ] **Step 9: Run tests and build**

```bash
./gradlew :app:testDebugUnitTest --tests "*RoutesTest*" && ./gradlew :app:assembleDebug
```

Expected: PASS, 4 tests. `WallScreen`, `AskScreen`, and the rewritten `InboxScreen`/`SettingsScreen` do not exist yet — create each as a one-line placeholder composable so the graph compiles; Tasks 3–7 fill them in.

- [ ] **Step 10: Commit**

```bash
git add -A
git commit -m "feat(ui): four-tab shell with theme-aware system bars

Wall / Inbox / Ask / Settings replaces the five Still-era destinations.
System bar icon style now follows the resolved theme instead of being
hardcoded light-on-dark, which would have been invisible in light mode."
```

---

### Task 3: Wall screen — the master toggle

The screen the user opens most and trusts least if it ever lies. Its entire job is to render the truth about system DND.

**Files:**
- Create: `app/src/main/java/com/anuj/notificationfirewall/ui/wall/WallScreen.kt`
- Create: `app/src/main/java/com/anuj/notificationfirewall/ui/wall/WallViewModel.kt`
- Modify: `app/src/main/java/com/anuj/notificationfirewall/data/db/dao/NotificationDao.kt`
- Test: `app/src/test/java/com/anuj/notificationfirewall/ui/wall/WallViewModelTest.kt`
- Test: `app/src/test/java/com/anuj/notificationfirewall/data/db/TodayCountsTest.kt`

**Interfaces:**
- Consumes: `ArmingController`, `WallState` (engine Task 9); `NotificationDao` (engine Task 5); `Permissions` (existing).
- Produces:
```kotlin
data class TodayCounts(val rang: Int, val silenced: Int, val dropped: Int) { val total: Int }
data class WallUiState(val state: WallState, val counts: TodayCounts, val breakGlassUntilMs: Long?, val loading: Boolean)
class WallViewModel : ViewModel { val ui: StateFlow<WallUiState>; fun toggle(); fun refresh() }
@Composable fun WallScreen(nav: NavHostController)
```
`NotificationDao.countsForDay(startMs, endMs): List<BucketCount>` used by Task 7's stat cards too.

- [ ] **Step 1: Write the failing DAO test**

Create `app/src/test/java/com/anuj/notificationfirewall/data/db/TodayCountsTest.kt`:

```kotlin
package com.anuj.notificationfirewall.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.anuj.notificationfirewall.domain.wall.WallBucket
import com.anuj.notificationfirewall.domain.wall.WallDecisionSource
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private const val NOW = 1_700_000_000_000L
private const val DAY_MS = 24L * 60 * 60 * 1000

@RunWith(RobolectricTestRunner::class)
class TodayCountsTest {

    private lateinit var db: NfDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            NfDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun insert(bucket: WallBucket, atMs: Long, pkg: String = "com.myntra") =
        db.notificationDao().insert(
            NotificationRecordEntity(
                packageName = pkg, appLabel = "App", title = "t", text = "b",
                timestampEpochMs = atMs, senderKey = "S", contentShape = "shape",
                importanceScore = 2f, biasApplied = 0f, category = null,
                isTimeSensitive = null, isFromHuman = null, needsAction = null,
                jevConfidence = 0.9f, decisionSource = WallDecisionSource.JEV,
                bucket = bucket, pendingClassification = false,
                textPurgedAt = null, isRead = false,
            ),
        )

    @Test
    fun countsAreGroupedByBucketWithinTheWindow() = runTest {
        insert(WallBucket.SILENCE, NOW)
        insert(WallBucket.SILENCE, NOW + 1000)
        insert(WallBucket.RING, NOW + 2000)
        insert(WallBucket.DROP, NOW + 3000)

        val counts = db.notificationDao().countsForDay(NOW - DAY_MS, NOW + DAY_MS)
            .associate { it.bucket to it.count }

        assertEquals(2, counts[WallBucket.SILENCE])
        assertEquals(1, counts[WallBucket.RING])
        assertEquals(1, counts[WallBucket.DROP])
    }

    @Test
    fun countsExcludeRecordsOutsideTheWindow() = runTest {
        insert(WallBucket.SILENCE, NOW - 3 * DAY_MS)
        insert(WallBucket.SILENCE, NOW)

        val counts = db.notificationDao().countsForDay(NOW - DAY_MS, NOW + DAY_MS)

        assertEquals(1, counts.single().count)
    }

    @Test
    fun emptyWindowReturnsNoRows() = runTest {
        assertEquals(0, db.notificationDao().countsForDay(NOW, NOW + 1000).size)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
./gradlew :app:testDebugUnitTest --tests "*TodayCountsTest*"
```

Expected: FAIL — `countsForDay` unresolved.

- [ ] **Step 3: Add the aggregate query**

Add to `NotificationDao`:

```kotlin
data class BucketCount(val bucket: WallBucket, val count: Int)

    @Query(
        "SELECT bucket, COUNT(*) AS count FROM notifications " +
            "WHERE timestampEpochMs BETWEEN :startMs AND :endMs GROUP BY bucket",
    )
    suspend fun countsForDay(startMs: Long, endMs: Long): List<BucketCount>
```

(`BucketCount` goes at file scope in the same file, above the `@Dao` interface.)

- [ ] **Step 4: Write the failing view-model test**

Create `app/src/test/java/com/anuj/notificationfirewall/ui/wall/WallViewModelTest.kt`:

```kotlin
package com.anuj.notificationfirewall.ui.wall

import android.app.NotificationManager
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.anuj.notificationfirewall.data.db.NfDatabase
import com.anuj.notificationfirewall.data.prefs.SecurePrefs
import com.anuj.notificationfirewall.service.ArmingController
import com.anuj.notificationfirewall.service.DndController
import com.anuj.notificationfirewall.service.WallState
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class WallViewModelTest {

    private lateinit var context: Context
    private lateinit var nm: NotificationManager
    private lateinit var db: NfDatabase
    private lateinit var arming: ArmingController
    private lateinit var vm: WallViewModel

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        nm = context.getSystemService(NotificationManager::class.java)
        shadowOf(nm).setNotificationPolicyAccessGranted(true)
        db = Room.inMemoryDatabaseBuilder(context, NfDatabase::class.java)
            .allowMainThreadQueries().build()
        val prefs = SecurePrefs(context.getSharedPreferences("test-wall-vm", Context.MODE_PRIVATE))
        prefs.listenerConnected = true
        arming = ArmingController(context, DndController(context, prefs), prefs)
        vm = WallViewModel(arming, db.notificationDao())
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun reportsDisarmedInitially() = runTest {
        vm.refresh()
        assertEquals(WallState.DISARMED, vm.ui.value.state)
    }

    @Test
    fun toggleArmsTheWall() = runTest {
        vm.toggle()
        assertEquals(WallState.ARMED, vm.ui.value.state)
    }

    @Test
    fun toggleTwiceReturnsToDisarmed() = runTest {
        vm.toggle()
        vm.toggle()
        assertEquals(WallState.DISARMED, vm.ui.value.state)
    }

    @Test
    fun externalDndOffIsReflectedAfterRefresh() = runTest {
        vm.toggle()
        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        arming.onSystemDndChanged()
        vm.refresh()

        assertEquals(
            "the toggle must never claim armed when DND is off",
            WallState.DISARMED,
            vm.ui.value.state,
        )
    }

    @Test
    fun missingPolicyAccessIsSurfacedNotHidden() = runTest {
        shadowOf(nm).setNotificationPolicyAccessGranted(false)
        vm.refresh()
        assertEquals(WallState.BLOCKED_NO_POLICY_ACCESS, vm.ui.value.state)
    }

    @Test
    fun togglingWhileBlockedDoesNotClaimArmed() = runTest {
        shadowOf(nm).setNotificationPolicyAccessGranted(false)
        vm.toggle()
        assertEquals(WallState.BLOCKED_NO_POLICY_ACCESS, vm.ui.value.state)
    }
}
```

- [ ] **Step 5: Run to verify it fails**

```bash
./gradlew :app:testDebugUnitTest --tests "*WallViewModelTest*"
```

Expected: FAIL — `WallViewModel` unresolved.

- [ ] **Step 6: Implement the view model**

Create `app/src/main/java/com/anuj/notificationfirewall/ui/wall/WallViewModel.kt`:

```kotlin
package com.anuj.notificationfirewall.ui.wall

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anuj.notificationfirewall.data.db.dao.NotificationDao
import com.anuj.notificationfirewall.domain.wall.WallBucket
import com.anuj.notificationfirewall.service.ArmingController
import com.anuj.notificationfirewall.service.WallState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

data class TodayCounts(val rang: Int = 0, val silenced: Int = 0, val dropped: Int = 0) {
    val total: Int get() = rang + silenced + dropped
}

data class WallUiState(
    val state: WallState = WallState.DISARMED,
    val counts: TodayCounts = TodayCounts(),
    val breakGlassUntilMs: Long? = null,
    val loading: Boolean = true,
)

@HiltViewModel
class WallViewModel @Inject constructor(
    private val arming: ArmingController,
    private val notificationDao: NotificationDao,
) : ViewModel() {

    private val _ui = MutableStateFlow(WallUiState())
    val ui: StateFlow<WallUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            // ArmingController emits on every system DND change, so a change
            // made from the system shade repaints this screen without the user
            // having to leave and come back.
            arming.observeState().collect { state ->
                _ui.value = _ui.value.copy(state = state, loading = false)
            }
        }
        refresh()
    }

    fun refresh() {
        _ui.value = _ui.value.copy(state = arming.state(), loading = false)
        viewModelScope.launch { loadCounts() }
    }

    fun toggle() {
        if (arming.isArmed()) arming.disarm() else arming.arm()
        refresh()
    }

    private suspend fun loadCounts() {
        val zone = ZoneId.systemDefault()
        val startOfDay = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        val rows = notificationDao.countsForDay(startOfDay, startOfDay + 24L * 60 * 60 * 1000)
            .associate { it.bucket to it.count }

        _ui.value = _ui.value.copy(
            counts = TodayCounts(
                rang = rows[WallBucket.RING] ?: 0,
                silenced = rows[WallBucket.SILENCE] ?: 0,
                dropped = rows[WallBucket.DROP] ?: 0,
            ),
        )
    }
}
```

- [ ] **Step 7: Run to verify it passes**

```bash
./gradlew :app:testDebugUnitTest --tests "*WallViewModelTest*" --tests "*TodayCountsTest*"
```

Expected: PASS, 9 tests.

- [ ] **Step 8: Build the screen**

Create `app/src/main/java/com/anuj/notificationfirewall/ui/wall/WallScreen.kt`. Structure, all colours from `LocalWallColors.current`:

- `NfScreen(title = "Wall")` chrome.
- **Hero toggle**: a tall rounded card filling most of the upper screen. Armed → `bucketRang`-tinted border, a filled status dot, the word "ARMED", and the subtitle "Nothing reaches you unless it matters". Disarmed → `border`, hollow dot, "DISARMED", subtitle "Everything is getting through". Tapping calls `vm.toggle()`.
- **Blocked states replace the toggle entirely** — never render a toggle the app cannot honour:
  - `BLOCKED_NO_LISTENER` → "Notification access is off", button launching `Permissions.notificationAccessIntent()`.
  - `BLOCKED_NO_POLICY_ACCESS` → "Do Not Disturb access is off", button launching `Permissions.dndAccessIntent()`.
- **Counters row**: three `NfCard`s — rang / silenced / dropped — each a `displaySmall` number over a `labelSmall` caption, dot-coloured by `bucketColor`. When `counts.total == 0`, show a single muted line "Nothing yet today" instead of three zeros.
- **Break-glass button** below (wired in Task 9; render it disabled with the label "Let everything through for 1 hour" until then).
- **Digest card** at the bottom (wired in Task 10).
- Call `vm.refresh()` from a `LifecycleResumeEffect` so returning from system settings repaints immediately.

Add one line of honesty under the toggle when armed, in `textMuted`: *"Do Not Disturb is on. Calls still ring."* — the user needs to know the mechanism, and it pre-empts the "why is my phone in DND" confusion.

- [ ] **Step 9: Build and install**

```bash
./gradlew :app:assembleDebug
```

- [ ] **Step 10: Commit**

```bash
git add -A
git commit -m "feat(ui): wall screen with a toggle that cannot lie

The hero toggle renders ArmingController's live state and repaints on
any system DND change. Blocked states replace the toggle rather than
sitting next to it, so the app never offers a control it cannot honour."
```

---

### Task 4: Inbox — every judgement, explained and correctable

**Files:**
- Create: `app/src/main/java/com/anuj/notificationfirewall/ui/inbox/InboxViewModel.kt`
- Rewrite: `app/src/main/java/com/anuj/notificationfirewall/ui/inbox/InboxScreen.kt`
- Test: `app/src/test/java/com/anuj/notificationfirewall/ui/inbox/InboxViewModelTest.kt`

**Interfaces:**
- Consumes: `NotificationDao`, `BiasStore`, `Correction`, `OverrideStore`, `OverrideKind`, `OverrideSource`, `VerdictCache` (engine Tasks 5–7).
- Produces:
```kotlin
data class InboxRow(val id: Long, val appLabel: String, val sender: String?, val title: String?,
                    val text: String?, val timestampMs: Long, val bucket: WallBucket,
                    val source: WallDecisionSource, val importance: Float?, val biasApplied: Float,
                    val category: NotificationCategory?, val confidence: Float?, val explanation: String)
class InboxViewModel : ViewModel {
    val rows: StateFlow<List<InboxRow>>
    fun correct(row: InboxRow, correction: Correction)
    fun addOverride(row: InboxRow, kind: OverrideKind)
}
```

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/anuj/notificationfirewall/ui/inbox/InboxViewModelTest.kt`:

```kotlin
package com.anuj.notificationfirewall.ui.inbox

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.anuj.notificationfirewall.data.db.NfDatabase
import com.anuj.notificationfirewall.data.db.NotificationRecordEntity
import com.anuj.notificationfirewall.domain.wall.BiasStore
import com.anuj.notificationfirewall.domain.wall.Correction
import com.anuj.notificationfirewall.domain.wall.JevVerdict
import com.anuj.notificationfirewall.domain.wall.NotificationCategory
import com.anuj.notificationfirewall.domain.wall.OverrideKind
import com.anuj.notificationfirewall.domain.wall.OverrideStore
import com.anuj.notificationfirewall.domain.wall.VerdictCache
import com.anuj.notificationfirewall.domain.wall.WallBucket
import com.anuj.notificationfirewall.domain.wall.WallDecisionSource
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class InboxViewModelTest {

    private lateinit var db: NfDatabase
    private lateinit var bias: BiasStore
    private lateinit var overrides: OverrideStore
    private lateinit var cache: VerdictCache
    private lateinit var vm: InboxViewModel

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            NfDatabase::class.java,
        ).allowMainThreadQueries().build()
        bias = BiasStore(db.senderBiasDao()) { 1_700_000_000_000L }
        overrides = OverrideStore(db.overrideDao())
        cache = VerdictCache(db.verdictCacheDao()) { 1_700_000_000_000L }
        vm = InboxViewModel(db.notificationDao(), bias, overrides, cache)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun insert(
        pkg: String = "com.myntra",
        sender: String = "Myntra",
        shape: String = "shape1",
        bucket: WallBucket = WallBucket.SILENCE,
        source: WallDecisionSource = WallDecisionSource.JEV,
    ) = db.notificationDao().insert(
        NotificationRecordEntity(
            packageName = pkg, appLabel = "Myntra", title = "FLAT 70% OFF", text = "Shop now",
            timestampEpochMs = 1_700_000_000_000L, senderKey = sender, contentShape = shape,
            importanceScore = 1.4f, biasApplied = 0f, category = NotificationCategory.PROMOTION,
            isTimeSensitive = 0.1f, isFromHuman = 0.03f, needsAction = 0.05f,
            jevConfidence = 0.91f, decisionSource = source, bucket = bucket,
            pendingClassification = false, textPurgedAt = null, isRead = false,
        ),
    )

    private fun row(id: Long) = InboxRow(
        id = id, appLabel = "Myntra", sender = "Myntra", title = "FLAT 70% OFF", text = "Shop now",
        timestampMs = 1_700_000_000_000L, bucket = WallBucket.SILENCE,
        source = WallDecisionSource.JEV, importance = 1.4f, biasApplied = 0f,
        category = NotificationCategory.PROMOTION, confidence = 0.91f, explanation = "",
        packageName = "com.myntra", contentShape = "shape1",
    )

    @Test
    fun correctionWritesABiasForThatSender() = runTest {
        val id = insert()
        vm.correct(row(id), Correction.SHOULD_HAVE_BEEN_SILENT)

        assertEquals(-0.25f, bias.biasFor("com.myntra", "Myntra"), 0.001f)
    }

    @Test
    fun correctionIsScopedToTheSenderNotTheApp() = runTest {
        val id = insert(pkg = "com.whatsapp", sender = "Promo Group")
        vm.correct(
            row(id).copy(packageName = "com.whatsapp", sender = "Promo Group"),
            Correction.SHOULD_HAVE_BEEN_SILENT,
        )

        assertEquals(
            "a correction on one WhatsApp thread must not touch another",
            0f,
            bias.biasFor("com.whatsapp", "Mom"),
            0.001f,
        )
    }

    @Test
    fun correctionEvictsTheMatchingCacheEntry() = runTest {
        cache.put(
            "shape1", "com.myntra", "Myntra",
            JevVerdict(1.4f, NotificationCategory.PROMOTION, 0.1f, 0.03f, 0.05f, 0.9f),
        )
        val id = insert()

        vm.correct(row(id), Correction.SHOULD_HAVE_RUNG)

        assertNull(
            "a corrected verdict must not keep being served from cache",
            cache.get("shape1"),
        )
    }

    @Test
    fun addingABlockOverrideIsSenderScoped() = runTest {
        val id = insert()
        vm.addOverride(row(id), OverrideKind.BLOCK)

        assertEquals(OverrideKind.BLOCK, overrides.kindFor("com.myntra", "Myntra"))
    }

    @Test
    fun overridesAddedFromTheInboxAreMarkedAsSwipeSourced() = runTest {
        val id = insert()
        vm.addOverride(row(id), OverrideKind.VIP)

        val entry = db.overrideDao().matching("com.myntra", "Myntra").single()
        assertEquals(OverrideSource.SWIPE, entry.source)
    }

    @Test
    fun explanationNamesTheDecisionSourceAndScore() = runTest {
        insert()
        val explained = vm.rows.value.firstOrNull()
            ?: InboxViewModel.explain(1.4f, 0f, NotificationCategory.PROMOTION, WallDecisionSource.JEV, 0.91f)
                .let { row(1).copy(explanation = it) }

        assertTrue(explained.explanation.contains("1.4"))
        assertTrue(explained.explanation.lowercase().contains("promotion"))
    }

    @Test
    fun explanationMentionsBiasWhenOneWasApplied() = runTest {
        val text = InboxViewModel.explain(
            importance = 4.5f, bias = -0.75f,
            category = NotificationCategory.PROMOTION,
            source = WallDecisionSource.CACHE, confidence = 0.9f,
        )
        assertTrue(text.contains("-0.75") || text.contains("0.75"))
        assertTrue(text.lowercase().contains("your correction") || text.lowercase().contains("bias"))
    }

    @Test
    fun explanationForOtpDoesNotInventAScore() = runTest {
        val text = InboxViewModel.explain(null, 0f, null, WallDecisionSource.OTP, null)
        assertTrue(text.lowercase().contains("one-time code") || text.lowercase().contains("otp"))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
./gradlew :app:testDebugUnitTest --tests "*InboxViewModelTest*"
```

Expected: FAIL — `InboxViewModel` unresolved.

- [ ] **Step 3: Implement the view model**

Create `app/src/main/java/com/anuj/notificationfirewall/ui/inbox/InboxViewModel.kt`:

```kotlin
package com.anuj.notificationfirewall.ui.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anuj.notificationfirewall.data.db.NotificationRecordEntity
import com.anuj.notificationfirewall.data.db.dao.NotificationDao
import com.anuj.notificationfirewall.domain.wall.BiasStore
import com.anuj.notificationfirewall.domain.wall.Correction
import com.anuj.notificationfirewall.domain.wall.NotificationCategory
import com.anuj.notificationfirewall.domain.wall.OverrideKind
import com.anuj.notificationfirewall.domain.wall.OverrideSource
import com.anuj.notificationfirewall.domain.wall.OverrideStore
import com.anuj.notificationfirewall.domain.wall.VerdictCache
import com.anuj.notificationfirewall.domain.wall.WallBucket
import com.anuj.notificationfirewall.domain.wall.WallDecisionSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class InboxRow(
    val id: Long,
    val packageName: String,
    val appLabel: String,
    val sender: String?,
    val title: String?,
    val text: String?,
    val timestampMs: Long,
    val contentShape: String,
    val bucket: WallBucket,
    val source: WallDecisionSource,
    val importance: Float?,
    val biasApplied: Float,
    val category: NotificationCategory?,
    val confidence: Float?,
    val explanation: String,
)

@HiltViewModel
class InboxViewModel @Inject constructor(
    private val notificationDao: NotificationDao,
    private val bias: BiasStore,
    private val overrides: OverrideStore,
    private val cache: VerdictCache,
) : ViewModel() {

    val rows: StateFlow<List<InboxRow>> = notificationDao.observeRecent(500)
        .map { records -> records.map { it.toRow() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Records a correction.
     *
     * Three things happen, in this order and for this reason: the bias moves
     * (bounded, per sender), the cached verdict for this exact content is
     * evicted (so the wall stops serving the answer the user just rejected),
     * and nothing else. In particular no app-wide rule is created — one
     * messaging app carries both a family thread and forwarded junk.
     */
    fun correct(row: InboxRow, correction: Correction) {
        viewModelScope.launch {
            bias.record(row.packageName, row.sender, correction)
            cache.evict(row.contentShape)
        }
    }

    fun addOverride(row: InboxRow, kind: OverrideKind) {
        viewModelScope.launch {
            overrides.add(
                kind = kind,
                pkg = row.packageName,
                sender = row.sender,
                label = row.sender ?: row.appLabel,
                source = OverrideSource.SWIPE,
            )
        }
    }

    private fun NotificationRecordEntity.toRow() = InboxRow(
        id = id,
        packageName = packageName,
        appLabel = appLabel,
        sender = senderKey,
        title = title,
        text = text,
        timestampMs = timestampEpochMs,
        contentShape = contentShape,
        bucket = bucket,
        source = decisionSource,
        importance = importanceScore,
        biasApplied = biasApplied,
        category = category,
        confidence = jevConfidence,
        explanation = explain(importanceScore, biasApplied, category, decisionSource, jevConfidence),
    )

    companion object {
        /**
         * One human-readable line saying why this notification was treated the
         * way it was. A filter nobody can interrogate is a filter nobody
         * trusts, and an untrusted wall gets switched off.
         */
        fun explain(
            importance: Float?,
            bias: Float,
            category: NotificationCategory?,
            source: WallDecisionSource,
            confidence: Float?,
        ): String = when (source) {
            WallDecisionSource.OTP ->
                "Rang: looks like a one-time code, which always gets through."
            WallDecisionSource.VIP ->
                "Rang: this sender is on your always-ring list."
            WallDecisionSource.BLOCK ->
                "Dropped: this sender is on your block list."
            WallDecisionSource.PENDING ->
                "Silenced: could not reach the classifier, will be re-judged shortly."
            WallDecisionSource.LEGACY ->
                "Judged by an earlier version of the app."
            WallDecisionSource.JEV, WallDecisionSource.CACHE -> buildString {
                append(category?.name?.lowercase()?.replace('_', ' ') ?: "uncategorised")
                append(" · importance ")
                append(importance?.let { "%.1f".format(it) } ?: "unknown")
                if (bias != 0f) append(", %+.2f from your corrections".format(bias))
                if (source == WallDecisionSource.CACHE) append(" · reused for this sender")
                confidence?.let { append(" · %.0f%% confident".format(it * 100)) }
            }
        }
    }
}
```

- [ ] **Step 4: Run to verify it passes**

```bash
./gradlew :app:testDebugUnitTest --tests "*InboxViewModelTest*"
```

Expected: PASS, 8 tests.

- [ ] **Step 5: Rewrite the screen**

Replace `app/src/main/java/com/anuj/notificationfirewall/ui/inbox/InboxScreen.kt`. Structure:

- `NfScreen(title = "Inbox")`.
- Filter chips across the top: All · Silenced · Rang · Dropped, using the existing `NfChip`.
- A `LazyColumn` of rows. Each row: a `StatusDot(bucketColor(row.bucket))`, the sender in `titleMedium`, the title in `bodyMedium` `textMuted`, a relative timestamp trailing, and the explanation line in `labelSmall` `textFaint`.
- **Swipe** via `SwipeToDismissBox` with both directions enabled and `confirmValueChange` returning `false` so the row springs back rather than disappearing — the correction is a judgement about the row, not a removal of it. Left → `Correction.SHOULD_HAVE_BEEN_SILENT`, right → `Correction.SHOULD_HAVE_RUNG`. Background reveals the target colour and a short label ("Silence this" / "Ring this").
- A snackbar after each correction: "Noted — Myntra will be judged more harshly" with an Undo action that applies the opposite `Correction`.
- **Tap** expands the row to show full text, the raw verdict numbers, and the bias applied.
- **Long-press** opens a bottom sheet: Always ring this sender · Block this sender · Clear learned bias.
- When `text == null` because retention purged it, render "Content expired" in `textFaint` rather than an empty row.

- [ ] **Step 6: Build**

```bash
./gradlew :app:assembleDebug
```

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat(ui): inbox with per-row explanations and swipe corrections

Every row says why it was judged that way. Corrections write a clamped
per-sender bias and evict the matching cache entry; they never create an
app-wide rule, so one WhatsApp thread carrying both family messages and
forwarded promos stays correctly judged either way."
```

---

### Task 5: SQL validator

Built before the Ask UI, because it is the thing standing between a model-authored string and the user's database. Every test here is an attack the validator must survive.

**Files:**
- Create: `app/src/main/java/com/anuj/notificationfirewall/ai/ask/SqlValidator.kt`
- Test: `app/src/test/java/com/anuj/notificationfirewall/ai/ask/SqlValidatorTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
```kotlin
sealed interface SqlVerdict {
    data class Allowed(val sql: String) : SqlVerdict
    data class Rejected(val reason: String) : SqlVerdict
}
object SqlValidator {
    const val MAX_LIMIT = 500
    fun validate(raw: String, allowContent: Boolean): SqlVerdict
}
```

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/anuj/notificationfirewall/ai/ask/SqlValidatorTest.kt`:

```kotlin
package com.anuj.notificationfirewall.ai.ask

import org.junit.Assert.assertTrue
import org.junit.Test

class SqlValidatorTest {

    private fun allowed(sql: String, content: Boolean = false) =
        SqlValidator.validate(sql, allowContent = content) is SqlVerdict.Allowed

    private fun rejected(sql: String, content: Boolean = false) =
        SqlValidator.validate(sql, allowContent = content) is SqlVerdict.Rejected

    // ── The happy path ───────────────────────────────────────────────────────

    @Test
    fun plainAggregateSelectIsAllowed() {
        assertTrue(allowed("SELECT packageName, COUNT(*) FROM notifications GROUP BY packageName LIMIT 20"))
    }

    @Test
    fun joinAcrossKnownTablesIsAllowed() {
        assertTrue(
            allowed(
                "SELECT n.appLabel, b.bias FROM notifications n " +
                    "JOIN sender_bias b ON b.packageName = n.packageName LIMIT 50",
            ),
        )
    }

    @Test
    fun lowercaseSelectIsAllowed() {
        assertTrue(allowed("select count(*) from notifications limit 1"))
    }

    // ── Writes ───────────────────────────────────────────────────────────────

    @Test
    fun deleteIsRejected() = assertTrue(rejected("DELETE FROM notifications"))

    @Test
    fun updateIsRejected() = assertTrue(rejected("UPDATE notifications SET isRead = 1 LIMIT 1"))

    @Test
    fun insertIsRejected() =
        assertTrue(rejected("INSERT INTO overrides (kind) VALUES ('BLOCK')"))

    @Test
    fun dropIsRejected() = assertTrue(rejected("DROP TABLE notifications"))

    @Test
    fun alterIsRejected() = assertTrue(rejected("ALTER TABLE notifications ADD COLUMN x TEXT"))

    @Test
    fun createIsRejected() = assertTrue(rejected("CREATE TABLE evil (x TEXT)"))

    // ── Statement smuggling ──────────────────────────────────────────────────

    @Test
    fun trailingStatementIsRejected() {
        assertTrue(rejected("SELECT COUNT(*) FROM notifications LIMIT 1; DROP TABLE notifications"))
    }

    @Test
    fun trailingSemicolonAloneIsAllowed() {
        assertTrue(allowed("SELECT COUNT(*) FROM notifications LIMIT 1;"))
    }

    @Test
    fun commentHidingASecondStatementIsRejected() {
        assertTrue(rejected("SELECT 1 FROM notifications LIMIT 1 -- ; DROP TABLE notifications"))
    }

    @Test
    fun blockCommentIsRejected() {
        assertTrue(rejected("SELECT /* sneaky */ COUNT(*) FROM notifications LIMIT 1"))
    }

    // ── Dangerous verbs ──────────────────────────────────────────────────────

    @Test
    fun pragmaIsRejected() = assertTrue(rejected("PRAGMA table_info(notifications)"))

    @Test
    fun attachIsRejected() =
        assertTrue(rejected("SELECT 1 FROM notifications WHERE 1=1 LIMIT 1 ATTACH DATABASE 'x' AS y"))

    @Test
    fun sqliteMasterIsRejected() {
        assertTrue(rejected("SELECT name FROM sqlite_master LIMIT 10"))
    }

    // ── Table allow-list ─────────────────────────────────────────────────────

    @Test
    fun unknownTableIsRejected() {
        assertTrue(rejected("SELECT * FROM android_metadata LIMIT 5"))
    }

    @Test
    fun allFourWallTablesAreKnown() {
        listOf("notifications", "verdict_cache", "sender_bias", "overrides").forEach { table ->
            assertTrue("$table must be queryable", allowed("SELECT COUNT(*) FROM $table LIMIT 1"))
        }
    }

    // ── Limits ───────────────────────────────────────────────────────────────

    @Test
    fun missingLimitIsRejected() {
        assertTrue(rejected("SELECT packageName FROM notifications"))
    }

    @Test
    fun limitAboveTheCapIsRejected() {
        assertTrue(rejected("SELECT packageName FROM notifications LIMIT 100000"))
    }

    @Test
    fun limitAtTheCapIsAllowed() {
        assertTrue(allowed("SELECT packageName FROM notifications LIMIT ${SqlValidator.MAX_LIMIT}"))
    }

    // ── Content columns ──────────────────────────────────────────────────────

    @Test
    fun selectingTextIsRejectedWhenContentIsNotAllowed() {
        assertTrue(rejected("SELECT text FROM notifications LIMIT 10", content = false))
    }

    @Test
    fun selectingTitleIsRejectedWhenContentIsNotAllowed() {
        assertTrue(rejected("SELECT title FROM notifications LIMIT 10", content = false))
    }

    @Test
    fun selectingStarIsRejectedWhenContentIsNotAllowed() {
        assertTrue(
            "SELECT * would leak title and text",
            rejected("SELECT * FROM notifications LIMIT 10", content = false),
        )
    }

    @Test
    fun contentColumnsAreAllowedWhenTheUserOptsIn() {
        assertTrue(allowed("SELECT title, text FROM notifications LIMIT 10", content = true))
    }

    @Test
    fun filteringOnTextWithoutSelectingItIsStillRejected() {
        assertTrue(
            "a WHERE on text can leak content one row at a time",
            rejected("SELECT COUNT(*) FROM notifications WHERE text LIKE '%salary%' LIMIT 1", content = false),
        )
    }

    // ── Junk ─────────────────────────────────────────────────────────────────

    @Test
    fun emptyStringIsRejected() = assertTrue(rejected(""))

    @Test
    fun proseIsRejected() = assertTrue(rejected("Here is the query you asked for:"))

    @Test
    fun markdownFencedSqlIsUnwrappedAndAllowed() {
        assertTrue(allowed("```sql\nSELECT COUNT(*) FROM notifications LIMIT 1\n```"))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
./gradlew :app:testDebugUnitTest --tests "*SqlValidatorTest*"
```

Expected: FAIL — `SqlValidator` unresolved.

- [ ] **Step 3: Implement**

Create `app/src/main/java/com/anuj/notificationfirewall/ai/ask/SqlValidator.kt`:

```kotlin
package com.anuj.notificationfirewall.ai.ask

sealed interface SqlVerdict {
    data class Allowed(val sql: String) : SqlVerdict
    data class Rejected(val reason: String) : SqlVerdict
}

/**
 * Gate between a model-authored string and the user's database.
 *
 * The posture is allow-list, not deny-list: the statement must be a single
 * SELECT, over four named tables, with a bounded LIMIT, and — unless the user
 * opted in for this query — mentioning neither content column anywhere, not
 * even in a WHERE clause, since a filter on text leaks it one row at a time.
 * Anything not positively recognised is rejected.
 *
 * A deny-list would be the wrong shape here. The input is generated by a model
 * that can be steered by the contents of notifications it has read, so the
 * question is not "have I thought of every bad statement" but "have I permitted
 * only the good ones".
 */
object SqlValidator {

    const val MAX_LIMIT = 500

    private val KNOWN_TABLES = setOf("notifications", "verdict_cache", "sender_bias", "overrides")
    private val CONTENT_COLUMNS = setOf("title", "text")

    private val FORBIDDEN = listOf(
        "insert", "update", "delete", "drop", "alter", "create", "replace",
        "pragma", "attach", "detach", "vacuum", "reindex", "trigger",
        "sqlite_master", "sqlite_temp_master", "load_extension",
    )

    private val FROM_OR_JOIN = Regex("""\b(?:from|join)\s+([a-z_][a-z0-9_]*)""", RegexOption.IGNORE_CASE)
    private val LIMIT = Regex("""\blimit\s+(\d+)""", RegexOption.IGNORE_CASE)
    private val FENCE = Regex("""^```(?:sql)?\s*|\s*```$""", RegexOption.IGNORE_CASE)

    fun validate(raw: String, allowContent: Boolean): SqlVerdict {
        val sql = raw.trim().replace(FENCE, "").trim().removeSuffix(";").trim()

        if (sql.isEmpty()) return SqlVerdict.Rejected("Empty statement")

        // Comments can hide a second statement from a naive split, and nothing
        // legitimate here needs them.
        if (sql.contains("--") || sql.contains("/*")) {
            return SqlVerdict.Rejected("Comments are not permitted")
        }
        if (sql.contains(";")) {
            return SqlVerdict.Rejected("Only a single statement is permitted")
        }

        val lower = sql.lowercase()

        if (!lower.startsWith("select ")) {
            return SqlVerdict.Rejected("Only SELECT statements are permitted")
        }
        FORBIDDEN.firstOrNull { Regex("""\b${Regex.escape(it)}\b""").containsMatchIn(lower) }
            ?.let { return SqlVerdict.Rejected("Forbidden keyword: $it") }

        val tables = FROM_OR_JOIN.findAll(lower).map { it.groupValues[1] }.toSet()
        if (tables.isEmpty()) return SqlVerdict.Rejected("No table referenced")
        (tables - KNOWN_TABLES).firstOrNull()
            ?.let { return SqlVerdict.Rejected("Unknown table: $it") }

        if (!allowContent) {
            if (lower.contains("*")) {
                return SqlVerdict.Rejected("SELECT * would expose notification content")
            }
            CONTENT_COLUMNS.firstOrNull { Regex("""\b$it\b""").containsMatchIn(lower) }
                ?.let { return SqlVerdict.Rejected("Column '$it' holds notification content") }
        }

        val limit = LIMIT.find(lower)?.groupValues?.get(1)?.toIntOrNull()
            ?: return SqlVerdict.Rejected("A LIMIT is required")
        if (limit > MAX_LIMIT) return SqlVerdict.Rejected("LIMIT must be at most $MAX_LIMIT")

        return SqlVerdict.Allowed(sql)
    }
}
```

- [ ] **Step 4: Run to verify it passes**

```bash
./gradlew :app:testDebugUnitTest --tests "*SqlValidatorTest*"
```

Expected: PASS, 29 tests. If any attack case passes validation, fix the validator — never the test.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/anuj/notificationfirewall/ai/ask/SqlValidator.kt \
        app/src/test/java/com/anuj/notificationfirewall/ai/ask/SqlValidatorTest.kt
git commit -m "feat(ask): allow-list SQL validator

Single SELECT, four known tables, mandatory bounded LIMIT, and content
columns barred everywhere — including WHERE, since a LIKE on text leaks
it one row at a time. Allow-list rather than deny-list because the input
is authored by a model that reads the user's notifications."
```

---

### Task 6: Ask — stats and text-to-SQL chat

**Files:**
- Create: `app/src/main/java/com/anuj/notificationfirewall/ai/ask/AskService.kt`
- Create: `app/src/main/java/com/anuj/notificationfirewall/ai/ask/AskSchema.kt`
- Create: `app/src/main/java/com/anuj/notificationfirewall/data/db/dao/RawQueryDao.kt`
- Create: `app/src/main/java/com/anuj/notificationfirewall/ui/ask/AskViewModel.kt`
- Create: `app/src/main/java/com/anuj/notificationfirewall/ui/ask/AskScreen.kt`
- Modify: `app/src/main/java/com/anuj/notificationfirewall/data/db/NfDatabase.kt`, `di/AppModule.kt`
- Test: `app/src/test/java/com/anuj/notificationfirewall/ai/ask/AskServiceTest.kt`

**Interfaces:**
- Consumes: `SqlValidator`, `SqlVerdict` (Task 5); `OpenAiClient` (existing); `NfDatabase`.
- Produces:
```kotlin
data class QueryResult(val columns: List<String>, val rows: List<List<String>>) { val isEmpty: Boolean }
sealed interface AskOutcome {
    data class Answered(val text: String, val sql: String, val result: QueryResult) : AskOutcome
    data class Refused(val reason: String, val sql: String?) : AskOutcome
    data class Failed(val reason: String) : AskOutcome
}
class AskService(openAi, rawQueryDao, model: String) {
    suspend fun ask(question: String, allowContent: Boolean): AskOutcome
}
class RawQueryDao { suspend fun run(sql: String): QueryResult }
```

- [ ] **Step 1: Write the schema description**

Create `app/src/main/java/com/anuj/notificationfirewall/ai/ask/AskSchema.kt`:

```kotlin
package com.anuj.notificationfirewall.ai.ask

/**
 * The only thing about the user's data that is ever sent to OpenAI ahead of a
 * question: the shape of the tables, never their contents.
 */
internal object AskSchema {

    val DDL = """
        CREATE TABLE notifications (
          id INTEGER PRIMARY KEY,
          packageName TEXT NOT NULL,     -- e.g. 'com.myntra'
          appLabel TEXT NOT NULL,        -- e.g. 'Myntra'
          title TEXT,                    -- CONTENT: usually the sender's name
          text TEXT,                     -- CONTENT: the message body
          timestampEpochMs INTEGER NOT NULL,
          senderKey TEXT,                -- sender or conversation, may be NULL
          contentShape TEXT NOT NULL,
          importanceScore REAL,          -- 1.0 (noise) .. 5.0 (critical)
          biasApplied REAL NOT NULL,     -- learned nudge, -0.75 .. 0.75
          category TEXT,                 -- PROMOTION|PERSONAL_MESSAGE|TRANSACTIONAL|
                                         -- WORK|SOCIAL|NEWS|SYSTEM|DELIVERY|OTHER
          isTimeSensitive REAL,          -- 0..1
          isFromHuman REAL,              -- 0..1; high means a person wrote it
          needsAction REAL,              -- 0..1
          jevConfidence REAL,            -- 0..1
          decisionSource TEXT NOT NULL,  -- OTP|VIP|BLOCK|CACHE|JEV|PENDING|LEGACY
          bucket TEXT NOT NULL,          -- RING|SILENCE|DROP
          pendingClassification INTEGER NOT NULL,
          textPurgedAt INTEGER,          -- non-NULL once title/text were purged
          isRead INTEGER NOT NULL
        );
        CREATE TABLE verdict_cache (
          contentShape TEXT PRIMARY KEY, packageName TEXT NOT NULL, senderKey TEXT,
          importance REAL NOT NULL, category TEXT NOT NULL, confidence REAL NOT NULL,
          hitCount INTEGER NOT NULL, createdAtEpochMs INTEGER NOT NULL,
          lastUsedEpochMs INTEGER NOT NULL
        );
        CREATE TABLE sender_bias (
          packageName TEXT NOT NULL, senderKey TEXT NOT NULL, bias REAL NOT NULL,
          correctionCount INTEGER NOT NULL, lastCorrectedEpochMs INTEGER NOT NULL
        );
        CREATE TABLE overrides (
          id INTEGER PRIMARY KEY, kind TEXT NOT NULL, packageName TEXT NOT NULL,
          senderKey TEXT, label TEXT NOT NULL, source TEXT NOT NULL,
          createdAtEpochMs INTEGER NOT NULL
        );
    """.trimIndent()

    fun systemPrompt(allowContent: Boolean, nowEpochMs: Long): String = """
        You translate a question about a personal notification history into ONE SQLite SELECT.

        Schema:
        $DDL

        Rules:
        - Reply with the SQL statement and nothing else. No prose, no markdown fence.
        - Exactly one statement. SELECT only. No semicolons, no comments.
        - Always include a LIMIT of at most ${SqlValidator.MAX_LIMIT}.
        - Timestamps are epoch milliseconds. Now is $nowEpochMs.
        - Prefer aggregates (COUNT, AVG, GROUP BY) over returning raw rows.
        ${if (allowContent) {
            "- You MAY select or filter on the title and text columns for this question."
        } else {
            "- You must NOT reference the title or text columns anywhere, including WHERE clauses."
        }}
    """.trimIndent()

    const val PHRASING_PROMPT =
        "You are given a question and the result rows of a query over the user's own " +
            "notification history. Answer the question directly in one or two sentences, " +
            "citing the numbers. Do not mention SQL. If the rows are empty, say so plainly."
}
```

- [ ] **Step 2: Add the raw-query DAO**

Create `app/src/main/java/com/anuj/notificationfirewall/data/db/dao/RawQueryDao.kt`:

```kotlin
package com.anuj.notificationfirewall.data.db.dao

import androidx.sqlite.db.SimpleSQLiteQuery
import com.anuj.notificationfirewall.ai.ask.QueryResult
import com.anuj.notificationfirewall.data.db.NfDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs a validated SELECT against the wall database.
 *
 * Callers MUST pass SQL that SqlValidator has already allowed. Nothing here
 * re-checks it — the check belongs at the boundary where model output enters
 * the system, not scattered at each use.
 */
class RawQueryDao(private val db: NfDatabase) {

    suspend fun run(sql: String): QueryResult = withContext(Dispatchers.IO) {
        db.openHelper.readableDatabase.query(SimpleSQLiteQuery(sql)).use { cursor ->
            val columns = cursor.columnNames.toList()
            val rows = buildList {
                while (cursor.moveToNext()) {
                    add(columns.indices.map { i -> cursor.getString(i) ?: "—" })
                }
            }
            QueryResult(columns, rows)
        }
    }
}
```

- [ ] **Step 3: Write the failing service tests**

Create `app/src/test/java/com/anuj/notificationfirewall/ai/ask/AskServiceTest.kt`:

```kotlin
package com.anuj.notificationfirewall.ai.ask

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.anuj.notificationfirewall.ai.OpenAiClient
import com.anuj.notificationfirewall.data.db.NfDatabase
import com.anuj.notificationfirewall.data.db.NotificationRecordEntity
import com.anuj.notificationfirewall.data.db.dao.RawQueryDao
import com.anuj.notificationfirewall.domain.wall.NotificationCategory
import com.anuj.notificationfirewall.domain.wall.WallBucket
import com.anuj.notificationfirewall.domain.wall.WallDecisionSource
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AskServiceTest {

    private lateinit var server: MockWebServer
    private lateinit var db: NfDatabase
    private lateinit var service: AskService

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            NfDatabase::class.java,
        ).allowMainThreadQueries().build()
        service = AskService(
            OpenAiClient(server.url("/"), "test-key", OkHttpClient()),
            RawQueryDao(db),
            model = "gpt-4o-mini",
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
    }

    private fun chatResponse(content: String) = MockResponse().setBody(
        """{"choices":[{"message":{"content":${org.json.JSONObject.quote(content)}}}]}""",
    )

    private suspend fun seed(pkg: String, label: String, n: Int) = repeat(n) {
        db.notificationDao().insert(
            NotificationRecordEntity(
                packageName = pkg, appLabel = label, title = "t", text = "b",
                timestampEpochMs = 1_700_000_000_000L + it, senderKey = label,
                contentShape = "shape$it", importanceScore = 1.2f, biasApplied = 0f,
                category = NotificationCategory.PROMOTION, isTimeSensitive = 0.1f,
                isFromHuman = 0.02f, needsAction = 0.05f, jevConfidence = 0.9f,
                decisionSource = WallDecisionSource.JEV, bucket = WallBucket.SILENCE,
                pendingClassification = false, textPurgedAt = null, isRead = false,
            ),
        )
    }

    @Test
    fun runsTheGeneratedQueryAndPhrasesTheResult() = runTest {
        seed("com.myntra", "Myntra", 3)
        server.enqueue(
            chatResponse("SELECT appLabel, COUNT(*) AS c FROM notifications GROUP BY appLabel LIMIT 10"),
        )
        server.enqueue(chatResponse("Myntra sent you 3 notifications."))

        val outcome = service.ask("who spams me most?", allowContent = false)

        assertTrue(outcome is AskOutcome.Answered)
        assertTrue((outcome as AskOutcome.Answered).text.contains("Myntra"))
        assertTrue(outcome.result.rows.isNotEmpty())
    }

    @Test
    fun neverSendsNotificationContentInTheGenerationRequest() = runTest {
        seed("com.myntra", "Myntra", 2)
        server.enqueue(chatResponse("SELECT COUNT(*) AS c FROM notifications LIMIT 1"))
        server.enqueue(chatResponse("Two."))

        service.ask("how many?", allowContent = false)

        val generation = server.takeRequest().body.readUtf8()
        assertFalse("the schema is sent, never the rows", generation.contains("\"b\""))
        assertTrue(generation.contains("CREATE TABLE notifications"))
    }

    @Test
    fun rejectsAWriteStatementWithoutTouchingTheDatabase() = runTest {
        seed("com.myntra", "Myntra", 2)
        server.enqueue(chatResponse("DELETE FROM notifications"))

        val outcome = service.ask("clear my history", allowContent = false)

        assertTrue(outcome is AskOutcome.Refused)
        assertTrue(db.notificationDao().recordsBetween(0, Long.MAX_VALUE).size == 2)
    }

    @Test
    fun rejectsAQueryTouchingContentWhenContentIsNotAllowed() = runTest {
        server.enqueue(chatResponse("SELECT title FROM notifications LIMIT 10"))

        val outcome = service.ask("what did they say?", allowContent = false)

        assertTrue(outcome is AskOutcome.Refused)
    }

    @Test
    fun refusalDoesNotIssueASecondModelCall() = runTest {
        server.enqueue(chatResponse("DROP TABLE notifications"))

        service.ask("drop everything", allowContent = false)

        assertTrue("only the generation call should have been made", server.requestCount == 1)
    }

    @Test
    fun surfacesAModelFailureRatherThanGuessing() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))

        assertTrue(service.ask("anything", allowContent = false) is AskOutcome.Failed)
    }

    @Test
    fun emptyResultIsStillAnswered() = runTest {
        server.enqueue(chatResponse("SELECT COUNT(*) AS c FROM notifications WHERE packageName = 'nope' LIMIT 1"))
        server.enqueue(chatResponse("Nothing matched."))

        val outcome = service.ask("anything from nope?", allowContent = false)

        assertTrue(outcome is AskOutcome.Answered)
    }
}
```

- [ ] **Step 4: Run to verify it fails**

```bash
./gradlew :app:testDebugUnitTest --tests "*AskServiceTest*"
```

Expected: FAIL — `AskService` unresolved.

- [ ] **Step 5: Implement the service**

Create `app/src/main/java/com/anuj/notificationfirewall/ai/ask/AskService.kt`:

```kotlin
package com.anuj.notificationfirewall.ai.ask

import android.util.Log
import com.anuj.notificationfirewall.ai.OpenAiClient
import com.anuj.notificationfirewall.data.db.dao.RawQueryDao

private const val TAG = "AskService"

data class QueryResult(val columns: List<String>, val rows: List<List<String>>) {
    val isEmpty: Boolean get() = rows.isEmpty()

    /** Compact rendering for the phrasing call — the only data that leaves the device. */
    fun toTsv(): String = buildString {
        appendLine(columns.joinToString("\t"))
        rows.forEach { appendLine(it.joinToString("\t")) }
    }
}

sealed interface AskOutcome {
    data class Answered(val text: String, val sql: String, val result: QueryResult) : AskOutcome
    data class Refused(val reason: String, val sql: String?) : AskOutcome
    data class Failed(val reason: String) : AskOutcome
}

/**
 * Answers questions about the user's notification history without their
 * notifications leaving the device.
 *
 * Two model calls, and what each one sees matters:
 *   1. Generate — sees the table schema and the question. No data.
 *   2. Phrase   — sees the question and the aggregate rows the device computed.
 *
 * Between them sits [SqlValidator]. A refusal stops the flow entirely rather
 * than falling back to something looser: if the generated statement is not
 * something we are willing to run, there is nothing to phrase.
 */
class AskService(
    private val openAi: OpenAiClient,
    private val rawQueryDao: RawQueryDao,
    private val model: String,
) {

    suspend fun ask(question: String, allowContent: Boolean): AskOutcome {
        val raw = try {
            openAi.chat(
                model = model,
                systemPrompt = AskSchema.systemPrompt(allowContent, System.currentTimeMillis()),
                userContent = question,
                jsonMode = false,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Query generation failed", e)
            return AskOutcome.Failed("Could not reach the model: ${e.message}")
        }

        val sql = when (val verdict = SqlValidator.validate(raw, allowContent)) {
            is SqlVerdict.Rejected -> return AskOutcome.Refused(verdict.reason, raw)
            is SqlVerdict.Allowed -> verdict.sql
        }

        val result = try {
            rawQueryDao.run(sql)
        } catch (e: Exception) {
            Log.w(TAG, "Validated query failed to execute: $sql", e)
            return AskOutcome.Failed("That query could not run: ${e.message}")
        }

        val answer = try {
            openAi.chat(
                model = model,
                systemPrompt = AskSchema.PHRASING_PROMPT,
                userContent = "Question: $question\n\nRows:\n${result.toTsv()}",
                jsonMode = false,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Phrasing failed", e)
            return AskOutcome.Failed("Could not phrase the answer: ${e.message}")
        }

        return AskOutcome.Answered(answer.trim(), sql, result)
    }
}
```

- [ ] **Step 6: Wire the DI**

In `AppModule`, add:

```kotlin
    @Provides
    @Singleton
    fun provideRawQueryDao(db: NfDatabase): RawQueryDao = RawQueryDao(db)

    @Provides
    @Singleton
    fun provideAskService(openAi: OpenAiClient, rawQueryDao: RawQueryDao): AskService =
        AskService(openAi, rawQueryDao, model = "gpt-4o-mini")
```

- [ ] **Step 7: Run to verify it passes**

```bash
./gradlew :app:testDebugUnitTest --tests "*AskServiceTest*"
```

Expected: PASS, 7 tests.

- [ ] **Step 8: Build the Ask screen and view model**

`AskViewModel` exposes `StateFlow<AskUiState>` holding the stat cards, the message list, and a `sending` flag, plus `fun send(question: String)` and `fun setAllowContent(Boolean)`.

Stat cards, each computed with a normal typed DAO query (not through `AskService` — these are fixed, not model-authored):
- **Noise ratio**: silenced ÷ total over the last 7 days, as a percentage with a one-line caption.
- **Top offenders**: the five apps with the most silenced notifications this week.
- **Machines vs humans**: share of notifications with `isFromHuman >= 0.5`.
- **By hour**: a 24-bar sparkline of arrivals by hour of day.
- **Correction rate**: corrections ÷ total judged — the app's own honesty metric, and the one number that tells the user whether to trust the rest.

Chat below: a scrolling message list, a text field, and a send button. Each answer renders the prose, then a collapsed "Show query" disclosure containing the SQL — the user can always see exactly what was run against their data. A `Refused` outcome renders as a plain explanation plus the rejected SQL, never as a silent failure. An "Include message content in this answer" toggle sits next to the input, defaulting off and resetting to off after every send.

If no OpenAI key is set, the chat area is replaced by a prompt linking to Settings; the stat cards still work, since they need no model at all.

- [ ] **Step 9: Build**

```bash
./gradlew :app:assembleDebug
```

- [ ] **Step 10: Commit**

```bash
git add -A
git commit -m "feat(ask): stats and text-to-SQL chat over local history

OpenAI sees the schema and the question, returns a SELECT, the validator
vets it, the device runs it, and only aggregate rows go back for
phrasing — so notification content never leaves the phone unless the
user opts in per question. Every answer shows the SQL it ran."
```

---

### Task 7: Settings

**Files:**
- Create: `app/src/main/java/com/anuj/notificationfirewall/ui/settings/SettingsViewModel.kt`
- Rewrite: `app/src/main/java/com/anuj/notificationfirewall/ui/settings/SettingsScreen.kt`
- Create: `app/src/main/java/com/anuj/notificationfirewall/data/export/HistoryExporter.kt`
- Test: `app/src/test/java/com/anuj/notificationfirewall/ui/settings/ThresholdPreviewTest.kt`
- Test: `app/src/test/java/com/anuj/notificationfirewall/data/export/HistoryExporterTest.kt`

**Interfaces:**
- Consumes: `WallSettings`, `OverrideStore`, `BiasStore`, `VerdictCache`, `NotificationDao`, `Permissions`, `ThemeViewModel`.
- Produces:
```kotlin
data class ThresholdPreview(val wouldRingMore: Int, val wouldSilenceMore: Int)
object ThresholdMath { fun preview(scores: List<Pair<Float, WallBucket>>, oldThreshold: Float, newThreshold: Float): ThresholdPreview }
class HistoryExporter(notificationDao) { suspend fun toJson(includeContent: Boolean): String }
```

- [ ] **Step 1: Write the failing threshold-preview tests**

Create `app/src/test/java/com/anuj/notificationfirewall/ui/settings/ThresholdPreviewTest.kt`:

```kotlin
package com.anuj.notificationfirewall.ui.settings

import com.anuj.notificationfirewall.domain.wall.WallBucket
import org.junit.Assert.assertEquals
import org.junit.Test

class ThresholdPreviewTest {

    private val today = listOf(
        1.2f to WallBucket.SILENCE,
        2.5f to WallBucket.SILENCE,
        3.6f to WallBucket.SILENCE,
        4.2f to WallBucket.RING,
        4.9f to WallBucket.RING,
    )

    @Test
    fun loweringTheThresholdWouldRingMore() {
        val preview = ThresholdMath.preview(today, oldThreshold = 4.0f, newThreshold = 3.0f)
        assertEquals(1, preview.wouldRingMore)
        assertEquals(0, preview.wouldSilenceMore)
    }

    @Test
    fun raisingTheThresholdWouldSilenceMore() {
        val preview = ThresholdMath.preview(today, oldThreshold = 4.0f, newThreshold = 4.5f)
        assertEquals(0, preview.wouldRingMore)
        assertEquals(1, preview.wouldSilenceMore)
    }

    @Test
    fun noChangeMeansNoDifference() {
        val preview = ThresholdMath.preview(today, oldThreshold = 4.0f, newThreshold = 4.0f)
        assertEquals(0, preview.wouldRingMore)
        assertEquals(0, preview.wouldSilenceMore)
    }

    @Test
    fun droppedNotificationsAreNotCountedEitherWay() {
        val withDropped = today + (1.0f to WallBucket.DROP)
        val preview = ThresholdMath.preview(withDropped, oldThreshold = 4.0f, newThreshold = 1.0f)
        assertEquals("a blocked sender stays blocked at any threshold", 3, preview.wouldRingMore)
    }

    @Test
    fun emptyHistoryPreviewsZero() {
        val preview = ThresholdMath.preview(emptyList(), 4.0f, 2.0f)
        assertEquals(0, preview.wouldRingMore)
        assertEquals(0, preview.wouldSilenceMore)
    }
}
```

- [ ] **Step 2: Write the failing exporter tests**

Create `app/src/test/java/com/anuj/notificationfirewall/data/export/HistoryExporterTest.kt`:

```kotlin
package com.anuj.notificationfirewall.data.export

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.anuj.notificationfirewall.data.db.NfDatabase
import com.anuj.notificationfirewall.data.db.NotificationRecordEntity
import com.anuj.notificationfirewall.domain.wall.NotificationCategory
import com.anuj.notificationfirewall.domain.wall.WallBucket
import com.anuj.notificationfirewall.domain.wall.WallDecisionSource
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HistoryExporterTest {

    private lateinit var db: NfDatabase
    private lateinit var exporter: HistoryExporter

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            NfDatabase::class.java,
        ).allowMainThreadQueries().build()
        exporter = HistoryExporter(db.notificationDao())
    }

    @After
    fun tearDown() = db.close()

    private suspend fun seed() = db.notificationDao().insert(
        NotificationRecordEntity(
            packageName = "com.myntra", appLabel = "Myntra",
            title = "SECRET TITLE", text = "SECRET BODY",
            timestampEpochMs = 1_700_000_000_000L, senderKey = "Myntra",
            contentShape = "shape", importanceScore = 1.4f, biasApplied = 0f,
            category = NotificationCategory.PROMOTION, isTimeSensitive = 0.1f,
            isFromHuman = 0.03f, needsAction = 0.05f, jevConfidence = 0.9f,
            decisionSource = WallDecisionSource.JEV, bucket = WallBucket.SILENCE,
            pendingClassification = false, textPurgedAt = null, isRead = false,
        ),
    )

    @Test
    fun exportsMetadataWithoutContentByDefault() = runTest {
        seed()
        val json = exporter.toJson(includeContent = false)

        assertFalse("content must be opt-in even in an export", json.contains("SECRET BODY"))
        assertTrue(json.contains("Myntra"))
        assertTrue(json.contains("PROMOTION"))
    }

    @Test
    fun includesContentWhenExplicitlyRequested() = runTest {
        seed()
        assertTrue(exporter.toJson(includeContent = true).contains("SECRET BODY"))
    }

    @Test
    fun producesParseableJsonWithACountHeader() = runTest {
        seed()
        seed()
        val root = JSONObject(exporter.toJson(includeContent = false))

        assertEquals(2, root.getInt("count"))
        assertEquals(2, root.getJSONArray("notifications").length())
    }

    @Test
    fun emptyHistoryExportsAnEmptyArrayNotAnError() = runTest {
        val root = JSONObject(exporter.toJson(includeContent = false))
        assertEquals(0, root.getInt("count"))
    }
}
```

- [ ] **Step 3: Run to verify both fail**

```bash
./gradlew :app:testDebugUnitTest --tests "*ThresholdPreviewTest*" --tests "*HistoryExporterTest*"
```

Expected: FAIL — `ThresholdMath` and `HistoryExporter` unresolved.

- [ ] **Step 4: Implement `ThresholdMath`**

Put it at the top of `app/src/main/java/com/anuj/notificationfirewall/ui/settings/SettingsViewModel.kt`:

```kotlin
data class ThresholdPreview(val wouldRingMore: Int = 0, val wouldSilenceMore: Int = 0)

/**
 * What moving the slider would have done to the notifications already seen.
 *
 * A bare number from 1 to 5 means nothing to anyone. "Two more would have rung
 * you today" is a sentence someone can actually make a decision about.
 */
object ThresholdMath {
    fun preview(
        scored: List<Pair<Float, WallBucket>>,
        oldThreshold: Float,
        newThreshold: Float,
    ): ThresholdPreview {
        var ringMore = 0
        var silenceMore = 0
        // DROP comes from the block list, which no threshold can reach.
        scored.filter { it.second != WallBucket.DROP }.forEach { (score, _) ->
            val wasRinging = score >= oldThreshold
            val wouldRing = score >= newThreshold
            if (!wasRinging && wouldRing) ringMore++
            if (wasRinging && !wouldRing) silenceMore++
        }
        return ThresholdPreview(ringMore, silenceMore)
    }
}
```

- [ ] **Step 5: Implement `HistoryExporter`**

Create `app/src/main/java/com/anuj/notificationfirewall/data/export/HistoryExporter.kt`:

```kotlin
package com.anuj.notificationfirewall.data.export

import com.anuj.notificationfirewall.data.db.dao.NotificationDao
import org.json.JSONArray
import org.json.JSONObject

/**
 * Dumps the notification history as JSON.
 *
 * Content is opt-in here exactly as it is everywhere else: an export is the
 * easiest way for this data to end up somewhere the user did not intend, so
 * the default carries metadata only.
 */
class HistoryExporter(private val notificationDao: NotificationDao) {

    suspend fun toJson(includeContent: Boolean): String {
        val records = notificationDao.recordsBetween(0, Long.MAX_VALUE)

        val array = JSONArray()
        records.forEach { r ->
            array.put(
                JSONObject().apply {
                    put("timestampEpochMs", r.timestampEpochMs)
                    put("packageName", r.packageName)
                    put("appLabel", r.appLabel)
                    put("senderKey", r.senderKey ?: JSONObject.NULL)
                    put("category", r.category?.name ?: JSONObject.NULL)
                    put("importanceScore", r.importanceScore ?: JSONObject.NULL)
                    put("biasApplied", r.biasApplied)
                    put("jevConfidence", r.jevConfidence ?: JSONObject.NULL)
                    put("decisionSource", r.decisionSource.name)
                    put("bucket", r.bucket.name)
                    if (includeContent) {
                        put("title", r.title ?: JSONObject.NULL)
                        put("text", r.text ?: JSONObject.NULL)
                    }
                },
            )
        }

        return JSONObject().apply {
            put("exportedAtEpochMs", System.currentTimeMillis())
            put("includesContent", includeContent)
            put("count", records.size)
            put("notifications", array)
        }.toString(2)
    }
}
```

- [ ] **Step 6: Run to verify both pass**

```bash
./gradlew :app:testDebugUnitTest --tests "*ThresholdPreviewTest*" --tests "*HistoryExporterTest*"
```

Expected: PASS, 9 tests.

- [ ] **Step 7: Build the Settings screen**

Sections, each an `NfCard` under a `SectionLabel`:

1. **Sensitivity** — the Strict↔Relaxed slider bound to `WallSettings.threshold`, with the live preview line underneath: *"2 more would have rung you today · 0 fewer"*, recomputed from today's records as the slider moves.
2. **Always ring** — the VIP list, each row removable; an add button opening an app picker.
3. **Never show** — the block list, same shape. Rows added by swiping show a small "from inbox" tag, so the user can tell what they chose deliberately from what accumulated.
4. **Learned corrections** — count of biased senders, a row per sender with its bias value, and "Reset all learning" with a confirmation.
5. **Behaviour** — OTP fast-path toggle, digest time picker, break-glass duration.
6. **Appearance** — System / Light / Dark segmented control bound to `ThemeViewModel.setMode`.
7. **Keys** — Jev API key and OpenAI API key, masked, stored in `EncryptedSharedPreferences`. Note under them: changing a key requires restarting the app (see engine Plan Task 10).
8. **Data** — text retention days, cache size with an "Empty cache" action, "Export history" (with an include-content checkbox) writing through `ACTION_CREATE_DOCUMENT`, and "Delete all history" behind a typed confirmation.
9. **Health** — the `Permissions.status()` checklist, each unmet item a tappable row launching its settings intent.

- [ ] **Step 8: Build**

```bash
./gradlew :app:assembleDebug
```

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "feat(ui): settings with a slider that shows its consequences

The threshold slider previews what the new value would have done to
today's notifications, because a number from 1 to 5 is not something
anyone can reason about. Export defaults to metadata only."
```

---

### Task 8: Quick Settings tile

**Files:**
- Create: `app/src/main/java/com/anuj/notificationfirewall/service/WallTileService.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/res/drawable/ic_tile_wall.xml`
- Test: `app/src/test/java/com/anuj/notificationfirewall/service/WallTileServiceTest.kt`

**Interfaces:**
- Consumes: `ArmingController`, `WallState` (engine Task 9).
- Produces: `class WallTileService : TileService`, with `internal fun tileStateFor(state: WallState): Int` exposed for testing.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/anuj/notificationfirewall/service/WallTileServiceTest.kt`:

```kotlin
package com.anuj.notificationfirewall.service

import android.service.quicksettings.Tile
import org.junit.Assert.assertEquals
import org.junit.Test

class WallTileServiceTest {

    @Test
    fun armedShowsActive() {
        assertEquals(Tile.STATE_ACTIVE, tileStateFor(WallState.ARMED))
    }

    @Test
    fun disarmedShowsInactive() {
        assertEquals(Tile.STATE_INACTIVE, tileStateFor(WallState.DISARMED))
    }

    @Test
    fun missingPolicyAccessShowsUnavailable() {
        assertEquals(Tile.STATE_UNAVAILABLE, tileStateFor(WallState.BLOCKED_NO_POLICY_ACCESS))
    }

    @Test
    fun missingListenerShowsUnavailable() {
        assertEquals(Tile.STATE_UNAVAILABLE, tileStateFor(WallState.BLOCKED_NO_LISTENER))
    }

    @Test
    fun blockedIsNeverReportedAsActive() {
        listOf(WallState.BLOCKED_NO_LISTENER, WallState.BLOCKED_NO_POLICY_ACCESS).forEach {
            assertEquals(
                "a tile that cannot act must not look armed",
                Tile.STATE_UNAVAILABLE,
                tileStateFor(it),
            )
        }
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
./gradlew :app:testDebugUnitTest --tests "*WallTileServiceTest*"
```

Expected: FAIL — `tileStateFor` unresolved.

- [ ] **Step 3: Implement**

Create `app/src/main/java/com/anuj/notificationfirewall/service/WallTileService.kt`:

```kotlin
package com.anuj.notificationfirewall.service

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** Maps wall state to the platform's three tile states. */
internal fun tileStateFor(state: WallState): Int = when (state) {
    WallState.ARMED -> Tile.STATE_ACTIVE
    WallState.DISARMED -> Tile.STATE_INACTIVE
    // A tile the user can tap but that cannot possibly work is worse than a
    // greyed-out one.
    WallState.BLOCKED_NO_LISTENER,
    WallState.BLOCKED_NO_POLICY_ACCESS -> Tile.STATE_UNAVAILABLE
}

/**
 * Arms and disarms the wall from the system shade.
 *
 * Reads the same live state as the Wall screen, so the two can never disagree —
 * they are both views of the system interruption filter rather than of any
 * stored flag.
 */
@AndroidEntryPoint
class WallTileService : TileService() {

    @Inject lateinit var armingController: ArmingController

    override fun onStartListening() {
        super.onStartListening()
        render()
    }

    override fun onClick() {
        super.onClick()
        if (armingController.isArmed()) armingController.disarm() else armingController.arm()
        render()
    }

    private fun render() {
        val tile = qsTile ?: return
        val state = armingController.state()
        tile.state = tileStateFor(state)
        tile.label = "Notification Wall"
        tile.contentDescription = when (state) {
            WallState.ARMED -> "Wall armed"
            WallState.DISARMED -> "Wall disarmed"
            WallState.BLOCKED_NO_LISTENER -> "Notification access needed"
            WallState.BLOCKED_NO_POLICY_ACCESS -> "Do Not Disturb access needed"
        }
        tile.updateTile()
    }
}
```

Register in `AndroidManifest.xml` inside `<application>`:

```xml
        <service
            android:name=".service.WallTileService"
            android:exported="true"
            android:icon="@drawable/ic_tile_wall"
            android:label="Notification Wall"
            android:permission="android.permission.BIND_QUICK_SETTINGS_TILE">
            <intent-filter>
                <action android:name="android.service.quicksettings.action.QS_TILE" />
            </intent-filter>
        </service>
```

Create `ic_tile_wall.xml` as a 24dp shield vector, same shape as `ic_nav_wall.xml`.

- [ ] **Step 4: Run to verify it passes**

```bash
./gradlew :app:testDebugUnitTest --tests "*WallTileServiceTest*"
```

Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(ui): quick settings tile for arming the wall

Reads the same live DND state as the Wall screen, so the tile and the
app can never disagree. Blocked states render as unavailable rather
than as a tappable control that cannot work."
```

---

### Task 9: Break-glass — let everything through for an hour

**Files:**
- Create: `app/src/main/java/com/anuj/notificationfirewall/service/BreakGlassController.kt`
- Create: `app/src/main/java/com/anuj/notificationfirewall/service/BreakGlassReceiver.kt`
- Modify: `app/src/main/java/com/anuj/notificationfirewall/service/BootReceiver.kt`
- Modify: `app/src/main/java/com/anuj/notificationfirewall/data/prefs/SecurePrefs.kt`
- Modify: `app/src/main/java/com/anuj/notificationfirewall/ui/wall/WallViewModel.kt`, `WallScreen.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Test: `app/src/test/java/com/anuj/notificationfirewall/service/BreakGlassControllerTest.kt`

**Interfaces:**
- Consumes: `ArmingController`, `SecurePrefs`.
- Produces:
```kotlin
class BreakGlassController(context, arming, securePrefs, clock: () -> Long) {
    fun start(durationMs: Long = DEFAULT_DURATION_MS)
    fun cancel()
    fun activeUntilMs(): Long?
    fun restoreAfterBoot()
    companion object { const val DEFAULT_DURATION_MS = 3_600_000L }
}
```

- [ ] **Step 1: Add the prefs field**

In `SecurePrefs`:

```kotlin
    /** Epoch ms at which break-glass expires and the wall re-arms. 0 when inactive. */
    var breakGlassUntilMs: Long
        get() = prefs.getLong(KEY_BREAK_GLASS_UNTIL, 0L)
        set(value) = prefs.edit { putLong(KEY_BREAK_GLASS_UNTIL, value) }
```

with `const val KEY_BREAK_GLASS_UNTIL = "break_glass_until_ms"`.

- [ ] **Step 2: Write the failing tests**

Create `app/src/test/java/com/anuj/notificationfirewall/service/BreakGlassControllerTest.kt`:

```kotlin
package com.anuj.notificationfirewall.service

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.anuj.notificationfirewall.data.prefs.SecurePrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

private const val NOW = 1_700_000_000_000L

@RunWith(RobolectricTestRunner::class)
class BreakGlassControllerTest {

    private lateinit var context: Context
    private lateinit var nm: NotificationManager
    private lateinit var prefs: SecurePrefs
    private lateinit var arming: ArmingController
    private var now = NOW
    private lateinit var breakGlass: BreakGlassController

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        nm = context.getSystemService(NotificationManager::class.java)
        shadowOf(nm).setNotificationPolicyAccessGranted(true)
        prefs = SecurePrefs(context.getSharedPreferences("test-bg", Context.MODE_PRIVATE))
        prefs.listenerConnected = true
        arming = ArmingController(context, DndController(context, prefs), prefs)
        breakGlass = BreakGlassController(context, arming, prefs) { now }
    }

    @Test
    fun startDisarmsTheWall() {
        arming.arm()
        breakGlass.start()

        assertEquals(WallState.DISARMED, arming.state())
    }

    @Test
    fun startRecordsAnExpiryOneHourOut() {
        breakGlass.start()
        assertEquals(NOW + BreakGlassController.DEFAULT_DURATION_MS, breakGlass.activeUntilMs())
    }

    @Test
    fun activeUntilIsNullOnceTheWindowHasPassed() {
        breakGlass.start()
        now = NOW + BreakGlassController.DEFAULT_DURATION_MS + 1

        assertNull(breakGlass.activeUntilMs())
    }

    @Test
    fun cancelClearsTheWindowAndReArms() {
        arming.arm()
        breakGlass.start()
        breakGlass.cancel()

        assertNull(breakGlass.activeUntilMs())
        assertEquals(WallState.ARMED, arming.state())
    }

    @Test
    fun anExactAlarmIsScheduled() {
        breakGlass.start()

        val alarms = shadowOf(context.getSystemService(android.app.AlarmManager::class.java))
        assertTrue("re-arming must not depend on the app still running", alarms.scheduledAlarms.isNotEmpty())
    }

    @Test
    fun restoreAfterBootReArmsImmediatelyIfTheWindowExpiredWhilePoweredOff() {
        arming.arm()
        breakGlass.start()
        now = NOW + BreakGlassController.DEFAULT_DURATION_MS + 60_000

        breakGlass.restoreAfterBoot()

        assertEquals(WallState.ARMED, arming.state())
        assertNull(breakGlass.activeUntilMs())
    }

    @Test
    fun restoreAfterBootReschedulesAStillLiveWindow() {
        breakGlass.start()
        now = NOW + 60_000

        breakGlass.restoreAfterBoot()

        assertEquals(NOW + BreakGlassController.DEFAULT_DURATION_MS, breakGlass.activeUntilMs())
        assertEquals(WallState.DISARMED, arming.state())
    }

    @Test
    fun customDurationIsHonoured() {
        breakGlass.start(durationMs = 15 * 60_000L)
        assertEquals(NOW + 15 * 60_000L, breakGlass.activeUntilMs())
    }
}
```

- [ ] **Step 3: Run to verify it fails**

```bash
./gradlew :app:testDebugUnitTest --tests "*BreakGlassControllerTest*"
```

Expected: FAIL — `BreakGlassController` unresolved.

- [ ] **Step 4: Implement the controller**

Create `app/src/main/java/com/anuj/notificationfirewall/service/BreakGlassController.kt`:

```kotlin
package com.anuj.notificationfirewall.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.anuj.notificationfirewall.data.prefs.SecurePrefs
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "BreakGlass"
private const val REQUEST_CODE = 7701

/**
 * "I'm expecting something important and I don't trust the wall right now."
 *
 * Disarms the wall for a fixed window and schedules an exact alarm to put it
 * back. The alarm is re-registered on boot, because the failure that actually
 * costs the user is not a wall that stays up too long — it is a wall that
 * quietly stays down for three days after a restart.
 */
@Singleton
class BreakGlassController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val arming: ArmingController,
    private val securePrefs: SecurePrefs,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    fun start(durationMs: Long = DEFAULT_DURATION_MS) {
        val until = clock() + durationMs
        securePrefs.breakGlassUntilMs = until
        arming.disarm()
        schedule(until)
        Log.i(TAG, "Break-glass active until $until")
    }

    fun cancel() {
        securePrefs.breakGlassUntilMs = 0L
        alarmManager()?.cancel(pendingIntent())
        arming.arm()
    }

    /** Expiry timestamp while a window is live, or null. */
    fun activeUntilMs(): Long? {
        val until = securePrefs.breakGlassUntilMs
        return if (until > clock()) until else null
    }

    /**
     * Called from BootReceiver. An alarm does not survive a reboot, so without
     * this the wall would stay down indefinitely after a restart during a
     * break-glass window.
     */
    fun restoreAfterBoot() {
        val until = securePrefs.breakGlassUntilMs
        if (until == 0L) return

        if (until <= clock()) {
            securePrefs.breakGlassUntilMs = 0L
            arming.arm()
        } else {
            schedule(until)
        }
    }

    private fun schedule(until: Long) {
        val alarms = alarmManager() ?: return
        val canBeExact = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S ||
            alarms.canScheduleExactAlarms()

        runCatching {
            if (canBeExact) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, until, pendingIntent())
            } else {
                // Without the exact-alarm grant the re-arm may drift by minutes.
                // Late is acceptable; never is not.
                alarms.set(AlarmManager.RTC_WAKEUP, until, pendingIntent())
            }
        }.onFailure { Log.w(TAG, "Could not schedule the re-arm alarm", it) }
    }

    private fun alarmManager(): AlarmManager? =
        context.getSystemService(AlarmManager::class.java)

    private fun pendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, BreakGlassReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val DEFAULT_DURATION_MS = 3_600_000L
    }
}
```

- [ ] **Step 5: Implement the receiver and hook boot**

Create `app/src/main/java/com/anuj/notificationfirewall/service/BreakGlassReceiver.kt`:

```kotlin
package com.anuj.notificationfirewall.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.anuj.notificationfirewall.data.prefs.SecurePrefs
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** Fires when a break-glass window expires: clears the window and re-arms. */
@AndroidEntryPoint
class BreakGlassReceiver : BroadcastReceiver() {

    @Inject lateinit var armingController: ArmingController
    @Inject lateinit var securePrefs: SecurePrefs

    override fun onReceive(context: Context, intent: Intent) {
        securePrefs.breakGlassUntilMs = 0L
        armingController.arm()
    }
}
```

Register it in the manifest with `android:exported="false"`, and add `breakGlassController.restoreAfterBoot()` to `BootReceiver`.

- [ ] **Step 6: Wire it into the Wall screen**

Inject `BreakGlassController` into `WallViewModel`, populate `WallUiState.breakGlassUntilMs` from `activeUntilMs()`, and add `fun breakGlass()` / `fun cancelBreakGlass()`. On the screen, when a window is live, replace the button with a countdown row — *"Everything is getting through · 43 min left"* — and a "Re-arm now" action.

- [ ] **Step 7: Run to verify it passes**

```bash
./gradlew :app:testDebugUnitTest --tests "*BreakGlassControllerTest*" && ./gradlew :app:assembleDebug
```

Expected: PASS, 8 tests.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "feat(wall): break-glass with boot-surviving re-arm

One button disarms the wall for an hour and schedules an exact alarm to
put it back. The alarm is re-registered on boot, because the costly
failure is not a wall that stays up — it is one that quietly stays down
for days after a restart."
```

---

### Task 10: Daily digest

**Files:**
- Rewrite: `app/src/main/java/com/anuj/notificationfirewall/ai/OpenAiDigestService.kt`
- Rewrite: `app/src/main/java/com/anuj/notificationfirewall/work/DigestWorker.kt`
- Modify: `app/src/main/java/com/anuj/notificationfirewall/work/DigestScheduler.kt`
- Modify: `app/src/main/java/com/anuj/notificationfirewall/data/db/dao/NotificationDao.kt`
- Test: `app/src/test/java/com/anuj/notificationfirewall/ai/DigestBuilderTest.kt`

**Interfaces:**
- Consumes: `NotificationDao`, `WallSettings`, `OpenAiClient`.
- Produces:
```kotlin
data class DigestData(val rang: Int, val silenced: Int, val dropped: Int,
                      val topOffender: Pair<String, Int>?, val worthALook: List<String>)
object DigestBuilder { fun summarise(records: List<NotificationRecordEntity>): DigestData }
```

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/anuj/notificationfirewall/ai/DigestBuilderTest.kt`:

```kotlin
package com.anuj.notificationfirewall.ai

import com.anuj.notificationfirewall.data.db.NotificationRecordEntity
import com.anuj.notificationfirewall.domain.wall.NotificationCategory
import com.anuj.notificationfirewall.domain.wall.WallBucket
import com.anuj.notificationfirewall.domain.wall.WallDecisionSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DigestBuilderTest {

    private fun record(
        app: String,
        bucket: WallBucket,
        importance: Float = 1.2f,
        sender: String = app,
    ) = NotificationRecordEntity(
        packageName = "com.$app", appLabel = app, title = "$sender says hi", text = "body",
        timestampEpochMs = 1_700_000_000_000L, senderKey = sender, contentShape = "shape",
        importanceScore = importance, biasApplied = 0f, category = NotificationCategory.PROMOTION,
        isTimeSensitive = 0.1f, isFromHuman = 0.1f, needsAction = 0.1f, jevConfidence = 0.9f,
        decisionSource = WallDecisionSource.JEV, bucket = bucket,
        pendingClassification = false, textPurgedAt = null, isRead = false,
    )

    @Test
    fun countsEachBucket() {
        val data = DigestBuilder.summarise(
            listOf(
                record("Myntra", WallBucket.SILENCE),
                record("Myntra", WallBucket.SILENCE),
                record("Slack", WallBucket.RING),
                record("Spam", WallBucket.DROP),
            ),
        )

        assertEquals(1, data.rang)
        assertEquals(2, data.silenced)
        assertEquals(1, data.dropped)
    }

    @Test
    fun namesTheWorstOffenderBySilencedCount() {
        val data = DigestBuilder.summarise(
            List(5) { record("Myntra", WallBucket.SILENCE) } +
                List(2) { record("Ajio", WallBucket.SILENCE) },
        )

        assertEquals("Myntra", data.topOffender?.first)
        assertEquals(5, data.topOffender?.second)
    }

    @Test
    fun theWorstOffenderIgnoresNotificationsThatRang() {
        val data = DigestBuilder.summarise(
            List(9) { record("Slack", WallBucket.RING) } +
                List(2) { record("Myntra", WallBucket.SILENCE) },
        )

        assertEquals("a top offender is one that wasted your attention", "Myntra", data.topOffender?.first)
    }

    @Test
    fun worthALookHoldsTheHighestScoringSilencedItems() {
        val data = DigestBuilder.summarise(
            listOf(
                record("Gmail", WallBucket.SILENCE, importance = 3.8f, sender = "Landlord"),
                record("Myntra", WallBucket.SILENCE, importance = 1.1f),
                record("Gmail", WallBucket.SILENCE, importance = 3.5f, sender = "Dentist"),
            ),
        )

        assertEquals(2, data.worthALook.size)
        assertTrue(data.worthALook.first().contains("Landlord"))
    }

    @Test
    fun worthALookIsCappedAtThree() {
        val data = DigestBuilder.summarise(List(10) { record("Gmail", WallBucket.SILENCE, importance = 3.9f) })
        assertTrue(data.worthALook.size <= 3)
    }

    @Test
    fun worthALookExcludesObviousNoise() {
        val data = DigestBuilder.summarise(List(5) { record("Myntra", WallBucket.SILENCE, importance = 1.1f) })
        assertTrue(data.worthALook.isEmpty())
    }

    @Test
    fun anEmptyDayHasNoOffender() {
        val data = DigestBuilder.summarise(emptyList())
        assertEquals(0, data.silenced)
        assertNull(data.topOffender)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
./gradlew :app:testDebugUnitTest --tests "*DigestBuilderTest*"
```

Expected: FAIL — `DigestBuilder` unresolved.

- [ ] **Step 3: Implement the builder**

Create it in `app/src/main/java/com/anuj/notificationfirewall/ai/OpenAiDigestService.kt`, above the service:

```kotlin
data class DigestData(
    val rang: Int,
    val silenced: Int,
    val dropped: Int,
    val topOffender: Pair<String, Int>?,
    val worthALook: List<String>,
)

/**
 * Turns a day of records into the shape a digest needs.
 *
 * Pure and separate from the network call so the numbers are testable and so a
 * missing API key degrades to a perfectly good digest without the prose.
 */
object DigestBuilder {

    /** Below this, an item is noise and does not belong in "worth a look". */
    private const val WORTH_A_LOOK_FLOOR = 2.5f
    private const val WORTH_A_LOOK_MAX = 3

    fun summarise(records: List<NotificationRecordEntity>): DigestData {
        val silencedRecords = records.filter { it.bucket == WallBucket.SILENCE }

        val topOffender = silencedRecords
            .groupingBy { it.appLabel }
            .eachCount()
            .maxByOrNull { it.value }
            ?.toPair()

        val worthALook = silencedRecords
            .filter { (it.importanceScore ?: 0f) >= WORTH_A_LOOK_FLOOR }
            .sortedByDescending { it.importanceScore }
            .take(WORTH_A_LOOK_MAX)
            .map { "${it.senderKey ?: it.appLabel}: ${it.title ?: "(content expired)"}" }

        return DigestData(
            rang = records.count { it.bucket == WallBucket.RING },
            silenced = silencedRecords.size,
            dropped = records.count { it.bucket == WallBucket.DROP },
            topOffender = topOffender,
            worthALook = worthALook,
        )
    }
}
```

- [ ] **Step 4: Rewrite the digest service and worker**

`OpenAiDigestService.summarise(data: DigestData): String` sends only the aggregate counts, the offender name, and the "worth a look" lines — never a full day of notification text. When no OpenAI key is set, it returns a deterministic locally-composed string instead of failing:

> "Yesterday: 312 silenced, 9 let through. Myntra led with 47."

`DigestWorker` loads yesterday's records, calls `DigestBuilder.summarise`, gets the prose, and posts one notification on a low-importance channel (the digest must never bypass DND — a notification app that interrupts you to tell you it prevented interruptions is its own punchline). Tapping it opens the Inbox.

`DigestScheduler` schedules it daily at `WallSettings.digestHour`, defaulting to 09:00.

- [ ] **Step 5: Run to verify it passes**

```bash
./gradlew :app:testDebugUnitTest --tests "*DigestBuilderTest*" && ./gradlew :app:assembleDebug
```

Expected: PASS, 7 tests.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat(digest): daily summary rebuilt on wall data

Counts, the worst offender by silenced volume, and up to three silenced
items worth a look. The numbers are computed locally and testably, so a
missing API key costs the prose and nothing else. Posted on a
low-importance channel that never bypasses DND."
```

---

### Task 11: Onboarding, and the last of the Still-era code

**Files:**
- Rewrite: `app/src/main/java/com/anuj/notificationfirewall/ui/onboarding/OnboardingScreen.kt`
- Modify: `app/src/main/java/com/anuj/notificationfirewall/ui/MainActivity.kt`
- Modify: `app/src/main/AndroidManifest.xml`, `app/build.gradle.kts`
- Test: `app/src/test/java/com/anuj/notificationfirewall/ui/onboarding/OnboardingStepsTest.kt`

**Interfaces:**
- Consumes: `Permissions`, `PermissionStatus`, `SecurePrefs`, `WallSettings`.
- Produces: `enum class OnboardingStep { NOTIFICATION_ACCESS, DND_ACCESS, POST_NOTIFICATIONS, JEV_KEY, BATTERY, DONE }` and `object OnboardingSteps { fun next(status: PermissionStatus, hasJevKey: Boolean): OnboardingStep }`.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/anuj/notificationfirewall/ui/onboarding/OnboardingStepsTest.kt`:

```kotlin
package com.anuj.notificationfirewall.ui.onboarding

import com.anuj.notificationfirewall.ui.permissions.PermissionStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class OnboardingStepsTest {

    private fun status(
        listener: Boolean = true,
        dnd: Boolean = true,
        post: Boolean = true,
        battery: Boolean = true,
    ) = PermissionStatus(
        notificationAccess = listener,
        dndAccess = dnd,
        contacts = true,
        postNotifications = post,
        batteryExempt = battery,
        exactAlarms = true,
        hasApiKey = true,
    )

    @Test
    fun notificationAccessComesFirst() {
        assertEquals(
            OnboardingStep.NOTIFICATION_ACCESS,
            OnboardingSteps.next(status(listener = false, dnd = false), hasJevKey = false),
        )
    }

    @Test
    fun dndAccessComesAfterNotificationAccess() {
        assertEquals(
            OnboardingStep.DND_ACCESS,
            OnboardingSteps.next(status(dnd = false), hasJevKey = false),
        )
    }

    @Test
    fun postNotificationsComesBeforeTheKey() {
        assertEquals(
            OnboardingStep.POST_NOTIFICATIONS,
            OnboardingSteps.next(status(post = false), hasJevKey = false),
        )
    }

    @Test
    fun theJevKeyIsRequiredBeforeFinishing() {
        assertEquals(OnboardingStep.JEV_KEY, OnboardingSteps.next(status(), hasJevKey = false))
    }

    @Test
    fun batteryExemptionIsTheLastAsk() {
        assertEquals(
            OnboardingStep.BATTERY,
            OnboardingSteps.next(status(battery = false), hasJevKey = true),
        )
    }

    @Test
    fun everythingGrantedIsDone() {
        assertEquals(OnboardingStep.DONE, OnboardingSteps.next(status(), hasJevKey = true))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
./gradlew :app:testDebugUnitTest --tests "*OnboardingStepsTest*"
```

Expected: FAIL — `OnboardingSteps` unresolved.

- [ ] **Step 3: Implement the step machine**

Create it at the top of `OnboardingScreen.kt`:

```kotlin
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
```

- [ ] **Step 4: Build the screen**

One card per step, with a plain explanation of *why* each grant is needed, a button launching the relevant intent from `Permissions`, and a live tick as each is satisfied. Re-evaluate on `LifecycleResumeEffect` so returning from system settings advances automatically. On `DONE`, set `securePrefs.hasSeenWelcome = true` and navigate to `Routes.WALL`.

The DND step needs the most honest copy, because it is the one that will surprise people:

> **Do Not Disturb access**
> The wall works by holding your phone in Do Not Disturb and letting only the notifications that matter ring. Calls always come through — from anyone, including repeat callers. Alarms still go off.

- [ ] **Step 5: Final sweep for Still-era remnants**

```bash
cd "/Volumes/External SSD/dev/projects/notification-firewall"
grep -rn "Still\|assessment\|Assessment\|pods\|Pods\|program\|Program\|BucketAction\|ProfileEntity" \
  app/src --include="*.kt" --include="*.xml" -i | grep -v "programmatic"
```

Expected: no meaningful hits. Remove the `READ_CONTACTS` permission from the manifest if the contacts signal is unused, and delete any unreferenced `res/drawable` icons left from the old five-tab bar.

- [ ] **Step 6: Run everything**

```bash
./gradlew :app:testDebugUnitTest && ./gradlew :app:assembleDebug
```

Expected: the full suite green.

- [ ] **Step 7: Verify on a device**

```bash
./gradlew :app:installDebug
```

Walk the whole thing by hand:

1. Fresh install → onboarding walks each grant in order and advances as you return from settings.
2. Arm the wall → a marketing notification stays silent; an OTP rings.
3. **Place a call → it rings.** Every time you touch DND code, check this by hand.
4. Flip DND off from the system shade → the Wall toggle and the Quick Settings tile both show disarmed within a second.
5. Swipe a row in the Inbox → snackbar appears, and that sender's next similar notification is judged differently.
6. Switch the system to light mode → every screen is legible, status-bar icons included.
7. Ask "which app interrupts me most?" → an answer with the SQL shown beneath it.
8. Break-glass → countdown appears; reboot mid-window → the wall is still down and still re-arms on time.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "feat(ui): permission-first onboarding; remove the last Still remnants

One ask per screen in dependency order, each explaining why it is needed
— the Do Not Disturb step says plainly that calls always come through,
because that is the question every user will have."
```

---

## Plan Self-Review

**Spec coverage.** §5.1 Wall → Task 3; §5.2 Inbox → Task 4; §5.3 Ask → Tasks 5, 6; §5.4 Settings → Task 7; §5.5 digest → Task 10; §7.3 tile → Task 8; §7.4 break-glass → Task 9; §8 theming → Tasks 1, 2. Onboarding is not a numbered spec section but is required by §5.1's blocked states, and is Task 11.

**Cross-plan consistency.** Every engine symbol this plan consumes — `ArmingController`, `WallState`, `WallBucket`, `WallDecisionSource`, `NotificationCategory`, `BiasStore`, `Correction`, `OverrideStore`, `OverrideKind`, `OverrideSource`, `VerdictCache`, `WallSettings`, `NotificationDao` — is produced by a numbered engine task. Three engine types are extended here rather than only consumed: `WallSettings` gains `themeMode` (Task 2) and `digestHour` (Task 10), `SecurePrefs` gains `breakGlassUntilMs` (Task 9), and `NotificationDao` gains `countsForDay` (Task 3). Each extension is written in the task that needs it.

**Known rough edges, stated rather than hidden:**
- Compose UI is verified by unit-testing view models and pure helpers, not by screenshot or Espresso tests. Visual correctness rests on Task 11 Step 7's manual walkthrough. Adding Compose UI tests would be a reasonable follow-up but is not in this plan.
- Task 2 leaves placeholder composables for screens that Tasks 3–7 fill in, so the build stays green between tasks. Any placeholder still present at the end of Task 11 is a bug.
- The Jev API key still requires an app restart after being changed in Settings (inherited from engine Task 10). A hot-swappable key holder is the obvious follow-up.

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-09-19-notification-wall-surface.md`.
