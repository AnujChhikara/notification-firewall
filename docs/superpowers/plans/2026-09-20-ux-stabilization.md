# Notification Wall UX Stabilization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Repair the four primary screens, restore model connectivity and Jev-key testing, and make tab navigation feel immediate without regressing cache or retention controls.

**Architecture:** Keep the existing Compose navigation and view-model boundaries. Fix capability and failure handling at the manifest/client boundary, centralize reusable layout behavior in Compose primitives, then reshape each screen with responsive containers while preserving domain actions.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, Navigation Compose, Hilt, coroutines/Flow, OkHttp, JUnit 4, Robolectric, Gradle.

**Spec:** `docs/superpowers/specs/2026-09-20-ux-stabilization-design.md`

## Global Constraints

- Preserve the current theme and semantic colors.
- Preserve armed/disarmed and break-glass behavior.
- Preserve Ask's existing privacy contract.
- Do not change notification policy, database schemas, or classifier ordering.
- Keep cache clearing and text-retention controls available and functional.
- Rethrow `CancellationException`; never expose or log API keys.
- Use 20 dp primary gutters, 10–12 dp related-content spacing, 48 dp minimum touch height, and sufficient bottom clearance for floating navigation.

---

### Task 1: Restore Internet Access and Contain Jev Test Failures

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/com/anuj/notificationfirewall/ui/settings/KeysScreen.kt`
- Create: `app/src/test/java/com/anuj/notificationfirewall/ManifestPermissionsTest.kt`
- Create: `app/src/test/java/com/anuj/notificationfirewall/ui/settings/KeysViewModelTest.kt`

**Interfaces:**
- Consumes: existing `KeysViewModel.testJevKey(key: String): Result<Float>` UI contract.
- Produces: `android.permission.INTERNET` in the merged manifest and a testable `JevKeyTester` dependency whose `test(key)` returns `Result<Float>` while preserving cancellation.

- [ ] **Step 1: Add a failing manifest permission test**

```kotlin
@RunWith(RobolectricTestRunner::class)
class ManifestPermissionsTest {
    @Test fun internetPermissionIsDeclared() {
        val info = ApplicationProvider.getApplicationContext<Context>()
            .packageManager.getPackageInfo(
                ApplicationProvider.getApplicationContext<Context>().packageName,
                PackageManager.GET_PERMISSIONS,
            )
        assertTrue(info.requestedPermissions.orEmpty().contains(Manifest.permission.INTERNET))
    }
}
```

- [ ] **Step 2: Run the test and verify RED**

Run: `./gradlew :app:testDebugUnitTest --tests '*ManifestPermissionsTest'`

Expected: FAIL because `android.permission.INTERNET` is absent.

- [ ] **Step 3: Declare the permission and verify GREEN**

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

Run: `./gradlew :app:testDebugUnitTest --tests '*ManifestPermissionsTest'`

Expected: PASS.

- [ ] **Step 4: Add failing Jev key tester tests**

```kotlin
@Test fun transportFailureBecomesFailedResult() = runTest {
    val tester = JevKeyTester { throw IOException("offline") }
    assertTrue(tester.test("key").isFailure)
}

@Test fun cancellationIsRethrown() = runTest {
    val tester = JevKeyTester { throw CancellationException("cancel") }
    assertFailsWith<CancellationException> { tester.test("key") }
}
```

- [ ] **Step 5: Run the tests and verify RED**

Run: `./gradlew :app:testDebugUnitTest --tests '*KeysViewModelTest'`

Expected: FAIL because `JevKeyTester` does not exist.

- [ ] **Step 6: Implement the smallest failure boundary and inject it**

```kotlin
class JevKeyTester(private val classify: suspend (String) -> Float) {
    suspend fun test(key: String): Result<Float> = try {
        Result.success(classify(key.trim()))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
}
```

Have `KeysViewModel.testJevKey` reject blank input, then delegate to the injected tester. Wrap the screen's loading-state update in `try/finally` so `testing` always returns to false.

- [ ] **Step 7: Verify focused and existing network tests**

Run: `./gradlew :app:testDebugUnitTest --tests '*KeysViewModelTest' --tests '*JevClientTest' --tests '*AskViewModelTest'`

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/AndroidManifest.xml app/src/main/java/com/anuj/notificationfirewall/ui/settings/KeysScreen.kt app/src/test/java/com/anuj/notificationfirewall/ManifestPermissionsTest.kt app/src/test/java/com/anuj/notificationfirewall/ui/settings/KeysViewModelTest.kt
git commit -m "fix: restore model network access"
```

### Task 2: Normalize Shared Controls and Navigation Motion

**Files:**
- Modify: `app/src/main/java/com/anuj/notificationfirewall/ui/Components.kt`
- Modify: `app/src/main/java/com/anuj/notificationfirewall/ui/MainActivity.kt`
- Modify: `app/src/test/java/com/anuj/notificationfirewall/ui/RoutesTest.kt`

**Interfaces:**
- Consumes: existing `NfButton`, `NfRow`, `NfNavGraph`, and primary navigation behavior.
- Produces: 48 dp minimum controls, stable destination state, and explicit zero-duration navigation transitions.

- [ ] **Step 1: Add a failing navigation policy assertion**

Add a pure `NavigationMotion` policy with `const val DESTINATION_TRANSITION_MS = 0` and assert it from `RoutesTest` before defining it.

- [ ] **Step 2: Run and verify RED**

Run: `./gradlew :app:testDebugUnitTest --tests '*RoutesTest'`

Expected: FAIL because `NavigationMotion` is unresolved.

- [ ] **Step 3: Implement the motion policy and control sizing**

```kotlin
object NavigationMotion { const val DESTINATION_TRANSITION_MS = 0 }
```

Use `EnterTransition.None` and `ExitTransition.None` on `NavHost`; retain `saveState`, `restoreState`, and `launchSingleTop`. Add `heightIn(min = 48.dp)` to `NfButton` and interactive rows.

- [ ] **Step 4: Verify**

Run: `./gradlew :app:testDebugUnitTest --tests '*RoutesTest'`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/anuj/notificationfirewall/ui/Components.kt app/src/main/java/com/anuj/notificationfirewall/ui/MainActivity.kt app/src/test/java/com/anuj/notificationfirewall/ui/RoutesTest.kt
git commit -m "perf: remove primary navigation motion"
```

### Task 3: Repair Wall and Inbox Layouts

**Files:**
- Modify: `app/src/main/java/com/anuj/notificationfirewall/ui/wall/WallScreen.kt`
- Modify: `app/src/main/java/com/anuj/notificationfirewall/ui/inbox/InboxScreen.kt`

**Interfaces:**
- Consumes: existing wall state/actions and Inbox correction callbacks.
- Produces: scroll-safe Wall content, standard gutters, horizontally scrollable Inbox filters, and bottom-navigation clearance.

- [ ] **Step 1: Establish a compile-level RED checkpoint**

Introduce calls to focused private composables `WallContentList(...)` and `InboxFilterRow(...)` before defining them, then compile.

Run: `./gradlew :app:compileDebugKotlin`

Expected: FAIL with unresolved composable references.

- [ ] **Step 2: Implement responsive Wall content**

Replace the fixed hero height with `heightIn(min = 220.dp)`. Render the full Wall body in a `LazyColumn` or vertical scroll container with `PaddingValues(start = 20.dp, end = 20.dp, bottom = 112.dp)` and 12 dp arrangement spacing. Preserve all callbacks and counter semantics.

- [ ] **Step 3: Implement consistent Inbox spacing**

Use a horizontal-scrolling filter row with the 20 dp page gutter. Apply the same gutter to list rows once, keep swipe backgrounds aligned, and use 12 dp vertical row content padding plus 8 dp metadata separation. Add bottom content padding of 112 dp.

- [ ] **Step 4: Compile and run screen view-model tests**

Run: `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --tests '*WallViewModelTest' --tests '*InboxViewModelTest'`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/anuj/notificationfirewall/ui/wall/WallScreen.kt app/src/main/java/com/anuj/notificationfirewall/ui/inbox/InboxScreen.kt
git commit -m "fix: repair wall and inbox layouts"
```

### Task 4: Repair Ask, Settings, and API Keys Layouts

**Files:**
- Modify: `app/src/main/java/com/anuj/notificationfirewall/ui/ask/AskScreen.kt`
- Modify: `app/src/main/java/com/anuj/notificationfirewall/ui/settings/SettingsScreen.kt`
- Modify: `app/src/main/java/com/anuj/notificationfirewall/ui/settings/KeysScreen.kt`
- Modify: `app/src/test/java/com/anuj/notificationfirewall/ui/settings/SettingsViewModelTest.kt`

**Interfaces:**
- Consumes: existing Ask messages/composer, Settings view-model methods, cache and retention controls.
- Produces: immediate chat scrolling, responsive action groups, aligned steppers, and explicit cache/retention regression assertions.

- [ ] **Step 1: Add failing cache/retention state tests**

Extend `SettingsViewModelTest` to assert `emptyCache()` makes `ui.value.cacheEntryCount == 0` and `setTextRetentionDays(14)` persists `settings.textRetentionDays == 14` and updates `ui.value.textRetentionDays`.

- [ ] **Step 2: Run and verify current behavior**

Run: `./gradlew :app:testDebugUnitTest --tests '*SettingsViewModelTest'`

Expected: either the new regression test fails and drives the minimum view-model correction, or it passes and proves the preserved behavior before UI edits.

- [ ] **Step 3: Make Ask scrolling immediate**

Replace `animateScrollToItem` with `scrollToItem`, keyed on message count and sending state. Give the list one standard horizontal gutter and enough bottom spacing for the composer/navigation.

- [ ] **Step 4: Make action groups responsive**

Use `FlowRow` with 8–12 dp spacing for key actions, data actions, and narrow Settings button groups. Give steppers weighted equal-width decrement/increment controls and center the value. Keep all existing labels, callbacks, confirmations, cache clearing, and retention options.

- [ ] **Step 5: Verify focused tests and compilation**

Run: `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --tests '*AskViewModelTest' --tests '*SettingsViewModelTest' --tests '*KeyMaskingTest'`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/anuj/notificationfirewall/ui/ask/AskScreen.kt app/src/main/java/com/anuj/notificationfirewall/ui/settings/SettingsScreen.kt app/src/main/java/com/anuj/notificationfirewall/ui/settings/KeysScreen.kt app/src/test/java/com/anuj/notificationfirewall/ui/settings/SettingsViewModelTest.kt
git commit -m "fix: align ask and settings controls"
```

### Task 5: Whole-App Verification and Device UX Pass

**Files:**
- Modify if evidence requires: files already named in Tasks 1–4
- Modify: `docs/HANDOFF.md` only when a durable validation limitation or device finding must be recorded

**Interfaces:**
- Consumes: completed Tasks 1–4.
- Produces: passing test/build evidence and observed device behavior or a precisely documented UI-automation blocker.

- [ ] **Step 1: Run static and unit verification**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`

Expected: BUILD SUCCESSFUL with zero failing tests.

- [ ] **Step 2: Inspect the merged manifest**

Run: `rg -n 'android.permission.INTERNET' app/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml`

Expected: one declared INTERNET permission.

- [ ] **Step 3: Launch from Android Studio and inspect every screen**

Navigate Wall → Inbox → Ask → Settings → API Keys repeatedly. Verify no overlap, clipping, long transition animation, or uncaught crash. Exercise armed/disarmed, Inbox expansion/swipe, Ask failure recovery, and Test Jev key.

- [ ] **Step 4: Verify cache and retention on device**

Use the cache-clearing button and verify the displayed cache count reaches zero. Change retention, leave Settings, return, and verify the selected value persists.

- [ ] **Step 5: Re-run tests after any evidence-driven adjustment**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit any validation-driven fixes**

If Step 3 or 4 required a source correction, stage only the already-scoped source and test files changed for that correction and commit them with `git commit -m "fix: address device UX findings"`. If no correction was required, record the validation evidence without creating an empty commit.
