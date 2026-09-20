# Notification Wall UX Stabilization Design

**Date:** 2026-09-20

## Goal

Make the existing four-tab Android app feel coherent, reliable, and responsive without changing its visual identity or weakening any notification-wall behavior.

## Scope

This pass covers:

- the Wall home layout while preserving the armed/disarmed control;
- Inbox spacing and row density;
- Ask networking and failure presentation;
- Settings and API Keys control alignment;
- the `Test Jev key` crash path;
- primary-tab transition responsiveness;
- device navigation and regression validation;
- continued availability and behavior of verdict-cache clearing and text-retention controls.

It does not redesign the theme, change notification classification policy, change database schemas, or change the privacy contract for Ask.

## Confirmed Root Causes and Investigation Findings

### Network failures

`AndroidManifest.xml` does not declare `android.permission.INTERNET`. Both `OpenAiClient` and `JevClient` use OkHttp, so Android denies their socket operations. This directly accounts for the Ask message containing “permission denied / missing internet permission” and affects `Test Jev key` as well.

The fix is an install-time manifest declaration. It requires no runtime permission prompt.

### Jev key test crash boundary

`KeysViewModel.testJevKey` converts only `JevException` into `Result.failure`. Failures that happen before or outside `JevClient`'s IOException wrapper can escape the suspend function and cancel the UI coroutine. The key test must treat expected connectivity, HTTP, parsing, and client-construction failures as an inline failed result while continuing to rethrow coroutine cancellation.

The screen must always leave its `testing` state, even when the test fails.

### Layout inconsistency

The shared `NfScreen` owns the header but delegates all content insets to individual screens. The screens currently apply different nested horizontal padding, card padding, bottom spacing, and fixed heights. This produces inconsistent gutters, double padding in some views, insufficient padding in others, and fragile layouts on narrower devices.

Buttons size only from their text and local padding. Rows containing three buttons, steppers, or trailing actions can therefore collide or look distorted on narrow widths.

### Perceived transition latency

Primary navigation preserves and restores destination state, which is desirable, but destination entry also triggers lifecycle refresh work. Ask uses animated scrolling after message changes, which can make the whole tab feel occupied. Navigation should have explicit zero-duration transitions, and screen refreshes must remain purposeful and non-blocking. Message scrolling should jump promptly rather than animate through a long list.

## Design

### Shared layout contract

`NfScreen` continues to own status-bar handling and the page header. Each primary screen receives a full-size content slot, but each screen must use the same constants and rules:

- 20 dp horizontal page gutter;
- 10–12 dp spacing between related cards or rows;
- enough bottom content padding to clear the floating navigation bar;
- 48 dp minimum interactive height;
- buttons either fill their available cell or wrap into another row on narrow screens;
- no fixed content height when content may grow through font scaling or localized text.

Shared button and row primitives will enforce minimum sizing and alignment. Screen-specific padding remains only where the layout genuinely differs, such as edge-to-edge swipe backgrounds.

### Wall home

The Wall screen becomes one scrollable vertical layout. The armed/disarmed hero remains the primary control and remains convenient to tap. Its height becomes content-driven with a sensible minimum rather than a rigid 260 dp block.

The ordering is:

1. permission or blocked-state action when applicable;
2. armed/disarmed or break-glass hero;
3. today's counters;
4. arm/disarm and break-glass actions;
5. most recent digest when present.

The three counters remain in one row when width permits and use equal widths. Text must not overlap at supported font scales. The bottom of the list clears the floating navigation bar.

### Inbox

The filter row and list share the standard page gutter. Filters may scroll horizontally instead of compressing. Notification rows use a consistent content inset, 12 dp vertical rhythm, and a larger gap between primary content and metadata.

Swipe behavior, correction semantics, undo, expansion, long-press actions, and divider behavior remain unchanged. Swipe backgrounds remain edge-aligned with their row rather than inheriting a second page inset.

### Ask

The app declares `android.permission.INTERNET`. Existing privacy behavior stays unchanged: the model receives the user's question and schema, then aggregate query results; raw notification content remains governed by the existing opt-in gate.

Network and API failures appear as assistant failure messages that are concise and actionable. They must not crash the screen. The composer remains available after a failed request.

Automatic message scrolling uses an immediate scroll when a new message or thinking row appears. The stats remain local and usable without a key or network.

### Settings and API Keys

Settings remains a single scrollable control room, with the existing sections and behaviors intact. Card widths and insets are normalized. Multi-action rows use weighted or wrapping layouts so `Remove`, `Clear`, `Save`, and stepper controls do not stretch or collide.

The appearance selector, steppers, data controls, health rows, and dialog actions use consistent alignment and minimum touch sizes. Destructive actions remain clearly separated and retain their confirmations.

The API Keys screen follows the same responsive action layout. The Jev key test:

- tests the current field or stored key as it does today;
- never stores or logs the submitted key as part of testing;
- always resets the loading state;
- renders success or failure inline;
- rethrows `CancellationException` rather than converting cancellation into a user-facing failure.

### Navigation and performance

Primary tab navigation keeps `launchSingleTop`, saved state, and restored state. The `NavHost` uses explicit no-animation transitions for primary destinations and settings/key navigation unless a back-navigation affordance materially benefits from motion. No artificial delay or animated destination transition is introduced.

Data work stays in view models and coroutines. Composition must not perform database or network work. Lifecycle resume refreshes remain only where they are needed to reflect external permission changes; ordinary tab switching should render cached observable state immediately.

## Error Handling

- Missing network capability is fixed at the manifest boundary.
- Ask reports transport/API errors in the conversation and clears its sending state.
- Jev key testing catches expected `Exception` failures, preserves cancellation, clears testing state in a `finally`-equivalent path, and presents a stable message.
- No error path exposes API keys in logs or UI.
- Existing classifier degradation behavior remains unchanged: Jev failures silence and store pending notifications.

## Testing

Implementation follows red-green-refactor.

Automated regression coverage will include:

- the merged manifest contains `android.permission.INTERNET`;
- Jev key testing returns failures for transport/client errors without throwing into the UI coroutine;
- cancellation remains cancellation;
- Ask clears sending state and records a failure message after a model failure;
- primary navigation configuration retains state and uses the intended transition policy where testable;
- cache-clearing still empties verdict-cache state and refreshes its displayed count;
- changing text retention still updates persisted settings and displayed state;
- existing cache and retention worker tests remain green.

The complete unit test suite and `:app:assembleDebug` must pass.

## Device Validation

Using Android Studio and the connected Android device or emulator:

1. Launch the app and move repeatedly through Wall, Inbox, Ask, and Settings.
2. Confirm tab switches are immediate and content does not jump or overlap.
3. Exercise armed/disarmed and break-glass controls without changing their semantics.
4. Inspect Inbox filters, expanded rows, swipe correction, and undo.
5. Send an Ask request with network available and confirm failures are inline when the service rejects a request.
6. Test a Jev key and confirm success or inline failure without a crash; inspect Logcat for uncaught exceptions.
7. Inspect Settings and API Keys at the connected device width and with increased font size when practical.
8. Use the cache-clearing control and confirm the cache count becomes zero.
9. Change text retention, leave and return to Settings, and confirm the chosen value persists.

If desktop UI automation remains unavailable because screen capture fails, this limitation must be reported explicitly. Automated tests, build validation, manifest inspection, and any available connected-device command-line evidence still run; no claim of visual device validation is made without observing it.

## Non-Regression Invariants

- Calls continue to ring while armed.
- Armed state remains derived from live system DND.
- OTP, block, VIP, cache, and Jev ordering is unchanged.
- Jev can never produce `DROP`.
- Failed Jev classification continues to silence and store.
- Reclassification never re-posts or rings.
- Text retention purges text while retaining metadata.
- Cache clearing remains safe and user-accessible.
- Armed/disarmed, break-glass, export, history deletion, retention, and health controls remain available.

