# Reliability — Design (never silently dies)

**Date:** 2026-07-28
**Status:** approved (pending spec review)
**Goal:** Take the firewall from "works while the app is fresh in memory" to "keeps working through OEM battery-killing, reboots, and listener drops — and tells the user when it genuinely can't."

## 1. Guarantees

1. **Alive when it matters** — while any enabled profile's window is active, the app's process stays alive and keeps filtering, even on aggressive OEMs (OnePlus/ColorOS).
2. **Self-healing** — after a reboot or a process kill, the app re-arms its schedule and reconnects the listener without the user opening it.
3. **Honest failure** — if the firewall genuinely cannot function (notification access revoked, listener can't reconnect), the user gets exactly one actionable notification, auto-cleared when healthy again, plus an in-app health banner.

## 2. Non-goals

- No change to the decision pipeline, rules, buckets, AI, or analytics.
- No always-on foreground service (decided: FGS only while a profile is active).
- No multi-device sync, no new profile-overlap semantics (active profile stays "lowest `order` wins").

## 3. Decisions (locked)

- **Call-safe DND (fixed 2026-07-28):** auto-DND previously used a bare `INTERRUPTION_FILTER_PRIORITY`, which on the user's phone silenced *incoming calls* — a critical bug (missed calls). Fixed: before enabling DND we overwrite the DND policy to always allow **calls (any sender), repeat callers, and alarms** (plus media/system on Android 11+), so only app notifications are silenced. The user's original policy is saved and restored verbatim on disable. This is a prerequisite the reliability work builds on — DND is now notifications-only, never calls.
- **Keep-alive:** a foreground service runs **only while a profile window is active**, showing one quiet ongoing notification (`"‹Profile› active · filtering notifications"`). Off the rest of the day.
- **Scheduling:** profile start/end boundaries are driven by **exact alarms** (`setExactAndAllowWhileIdle`). This gives precise DND/keep-alive timing and — critically — an alarm firing is an OS-sanctioned trigger that is allowed to start a foreground service from the background. Requires the **"Alarms & reminders"** permission (Android 12+). Approved.
- **Health alerts:** post one `"Firewall stopped · tap to fix"` notification when broken; auto-remove when healthy; never repeat while already broken.

## 4. Why exact alarms (the core constraint)

Android 12+ forbids starting a foreground service from the background except from a short list of blessed triggers. A **NotificationListenerService callback is not one of them**, but an **exact alarm firing is**. So the reliable way to (a) flip DND on/off exactly at 22:00 / 07:00 and (b) legally start the keep-alive FGS is to schedule exact alarms at each profile boundary. The existing 15-minute WorkManager sweep is demoted to a *backup* that re-arms anything a missed alarm left stale.

## 5. Components

Each is small, single-purpose, and independently testable.

### 5.1 `ProfileScheduler` (new)
- **Does:** schedules/cancels exact alarms at each enabled profile's next start and next end boundary via `AlarmManager` + a `PendingIntent` to `ProfileBoundaryReceiver`. Re-armed on profile save, on boot, and after each alarm fires.
- **Pure, unit-tested:** `nextBoundaryMillis(now, minuteOfDay, days): Long` — the next epoch-ms at `minuteOfDay` whose day-of-week is in `days`, skipping a time already past today.
- **Depends on:** `AlarmManager`, `ProfileDao`, `AlarmPermission` check.
- **Degrades:** if exact-alarm permission is absent, falls back to `setAndAllowWhileIdle` (inexact) and leans on the backup sweep; health reports "degraded."

### 5.2 `ProfileBoundaryReceiver` (new, `BroadcastReceiver`)
- **Does:** on alarm fire → `goAsync()` coroutine → `ProfileStateReconciler.reconcile()` then `ProfileScheduler.rescheduleAll()`. Because it runs from an exact-alarm trigger it may legally start the FGS.
- **Depends on:** `ProfileStateReconciler`, `ProfileScheduler` (obtained via a Hilt `EntryPoint`, since receivers aren't `@AndroidEntryPoint`-injectable for fields the same way — use `EntryPointAccessors`).

### 5.3 `ProfileStateReconciler` (new — the single source of truth)
- **Does:** given "what's active now," makes the phone match it:
  - active profile exists → **start `KeepAliveService`** (anchors the process, shows the ongoing notification).
  - active profile has `autoDnd` → DND on (delegates to existing `DndController`); else DND left/undone per current rules.
  - no active profile → **stop `KeepAliveService`** + DND off.
- **Pure, unit-tested:** `decide(active: ActiveProfile?): ReconcileAction` → `{ keepAliveRunning: Boolean, dndOn: Boolean }`. The side-effecting `reconcile()` reads DB, computes `decide`, and applies it.
- **Replaces:** the direct `DndController.reconcile` calls in the listener, the boot receiver, the periodic worker, and profile-save — all now go through `ProfileStateReconciler` so DND and keep-alive can never disagree.
- **Depends on:** `ProfileDao`, `ProfileManager`, `DndController`, `KeepAliveService` (start/stop), `Context`.

### 5.4 `KeepAliveService` (new, foreground service)
- **Does:** `startForeground()` with a low-importance ongoing notification on a dedicated `firewall_status` channel; `START_STICKY`. Pure process anchor — it keeps the app (and its bound listener) alive while a profile is active. Stopped by `ProfileStateReconciler` when no profile is active.
- **Manifest:** `foregroundServiceType="specialUse"` (Android 14+) with the required special-use property; `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` permissions.
- **Guards:** catches `ForegroundServiceStartNotAllowedException` and logs (the backup sweep / next boundary retry).

### 5.5 `BootReceiver` (new, `RECEIVE_BOOT_COMPLETED`)
- **Does:** on boot → `ProfileScheduler.rescheduleAll()` + `ProfileStateReconciler.reconcile()` (starts the FGS if a window is active right now). WorkManager periodic work resumes on its own.

### 5.6 `HealthMonitor` (new)
- **Does:** computes a `HealthState` and posts/clears the broken notification.
- **Pure, unit-tested:** `evaluate(flags: HealthFlags): HealthState` where flags = { notificationAccess, listenerConnected, postNotifications, dndAccessIfNeeded, batteryExempt, alarmsExact }.
  - **Broken** (posts notification): notification access off, OR access on but listener not connected (stale beyond a grace window).
  - **Degraded** (banner only): battery not exempt, DND access missing while an auto-DND profile exists, or exact-alarm permission missing.
  - **Healthy:** clears everything.
- **Listener-connected signal:** `NfListenerService` writes a `listenerConnected` flag + `lastConnectedAt` to prefs on connect/disconnect. `HealthMonitor` (which runs from a worker, out of the listener process) reads those; if access is granted but the flag is stale, it calls the static `NotificationListenerService.requestRebind(componentName)` to nudge reconnection before declaring broken.
- **Called from:** the periodic worker, app `onResume`, and listener connect/disconnect.
- **Depends on:** `NotificationManager`, `Permissions`, prefs, `ProfileDao` (does an auto-DND profile exist?).

### 5.7 `NfListenerService` changes
- `onListenerConnected` → set `listenerConnected=true`, reconcile via `ProfileStateReconciler`, `HealthMonitor.refresh()`.
- `onListenerDisconnected` → set `listenerConnected=false`, `requestRebind(componentName)`, `HealthMonitor.refresh()`.

### 5.8 UI
- **Home health banner:** when `HealthState` is Broken/Degraded, replace the calm status card with a coloured banner (red = broken, amber = degraded) stating the problem + a **"Fix now"** button → `Onboarding` (the existing grant-everything screen doubles as the fix-it screen).
- **Broken notification tap** → opens `MainActivity` → `Onboarding`.
- **Onboarding gains** an "Alarms & reminders" step (Android 12+): explains why, deep-links to `ACTION_REQUEST_SCHEDULE_EXACT_ALARM` / app settings, live status.

## 6. Data flow

```
profile boundary alarm ─▶ ProfileBoundaryReceiver ─▶ ProfileStateReconciler.reconcile()
                                                   └▶ ProfileScheduler.rescheduleAll()
notification posted     ─▶ NfListenerService ─▶ ProfileStateReconciler.reconcile() (+ existing log/execute)
boot completed          ─▶ BootReceiver ─▶ reschedule + reconcile
every 15 min (backup)   ─▶ DndReconcileWorker → ProfileStateReconciler.reconcile() + HealthMonitor.refresh()
app opened / listener connect|disconnect ─▶ HealthMonitor.refresh()
health = broken         ─▶ post "Firewall stopped · tap to fix"   (cleared when healthy)
```

## 7. Permissions & manifest additions

- `SCHEDULE_EXACT_ALARM` + `USE_EXACT_ALARM`, `RECEIVE_BOOT_COMPLETED`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`.
- Register `KeepAliveService` (typed FGS), `ProfileBoundaryReceiver`, `BootReceiver`.
- New `firewall_status` notification channel (IMPORTANCE_LOW, no sound) for the ongoing keep-alive notification; reuse/extend a channel for the broken-health alert (IMPORTANCE_DEFAULT so it's noticed).

## 8. Error handling / edge cases

- **User's manual DND** is never clobbered (existing `dndSetByApp` ownership flag carries over).
- **Exact-alarm permission missing/revoked** → inexact fallback + backup sweep; health = degraded, onboarding surfaces the grant.
- **FGS start blocked** (`ForegroundServiceStartNotAllowedException`) → caught + logged; next boundary/backup retries.
- **Overlapping profiles** → active = lowest `order` (unchanged); keep-alive notification names that profile.
- **Profile disabled while active** → save path reconciles immediately: FGS stops, DND restored.

## 9. Testing

**Headless unit tests**
- `nextBoundaryMillis(now, minute, days)` — same-day vs next-eligible-day, day-set filtering, exact-boundary wrap.
- `HealthMonitor.evaluate(flags)` — each broken/degraded/healthy combination.
- `ProfileStateReconciler.decide(active)` — active/none → keepAlive & dnd booleans; autoDnd vs not.

**Device verification (user)**
- Kill the app from recents during an active window → notifications still filtered.
- Reboot mid-window → keep-alive notification returns, DND re-armed.
- Revoke notification access → "Firewall stopped" notification appears; tap → fix screen; re-grant → it clears.
- DND flips within seconds of 22:00 / 07:00 (not ~15 min late).

## 10. Open questions

None blocking. `USE_EXACT_ALARM` vs `SCHEDULE_EXACT_ALARM` Play-Store policy nuance is a launch-time detail, not an implementation blocker (we declare both and check `canScheduleExactAlarms()` at runtime).
