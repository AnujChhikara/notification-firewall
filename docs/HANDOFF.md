# Notification Wall — Handoff

**Written:** 2026-09-19, end of the engine-build session.
**Purpose:** everything a fresh session needs to continue. Read this first, then the spec.

---

## 1. What this is

An Android app that stands between every notification the phone receives and the user's
attention. Each notification is intercepted, judged by **Jev** (TypeSafe AI's System One
model — not an LLM; you send a `state` plus typed `questions`, it returns typed answers
with calibrated probabilities in ~130ms), and either rung through or silenced and filed.
Everything is stored locally so the user can later query their own notification history.

Single user, personal tool. No backend, no account, no sync.

**The platform constraint everything rests on:** Android gives no app the power to veto a
notification before it is posted — a `NotificationListenerService` sees it only after the
system already made the sound. So the wall holds the phone in Do Not Disturb and re-posts
only important notifications on a DND-bypass channel. That makes "armed" and "system DND
is on" the same fact, and it is why calls must be explicitly exempted.

**Repo:** `/Volumes/External SSD/dev/projects/notification-firewall`, branch `main`.
The user explicitly consented to working directly on `main` (declined worktree/branch).

---

## 2. Status

| | State |
|---|---|
| **Engine plan** (`docs/superpowers/plans/2026-09-19-notification-wall-engine.md`) | ✅ **COMPLETE** — 12/12 tasks, all reviewed |
| **Surface plan** (`docs/superpowers/plans/2026-09-19-notification-wall-surface.md`) | ❌ **NOT STARTED** — 11 tasks, 87 steps, written and self-reviewed |
| Usability slice (not in either plan) | ✅ done — arm toggle, key screen, health rows |
| Tests | 147 passing, 0 failures, no warnings |
| Build | `:app:assembleDebug` green |
| Device | Installed on a Vivo 1951 (`adb` id `b2902033`), launches clean |

**HEAD at handoff:** `3dc6552`. Engine work spans `eaae2537..3dc6552`, 27 commits.

### What works today

Interception → OTP fast-path → block list → VIP list → verdict cache → Jev → bias →
threshold → ring/silence/drop. Arming derived from live system DND. Call safety.
Learned per-sender bias. Re-classification of anything that arrived while Jev was
unreachable. Text retention with metadata kept forever. Arm/disarm toggle, API key entry
with a live "test this key" button, permission health rows.

### What does not exist

The four-tab UI (Wall · Inbox · Ask · Settings), light theme, Ask/text-to-SQL with stat
cards, inbox swipe corrections, Quick Settings tile, break-glass, and a working digest.
All of it is the surface plan. The screens currently on the device are the *old* ones from
the app's previous life, with dead parts removed — functional, not designed.

---

## 3. Where to continue

Execute `docs/superpowers/plans/2026-09-19-notification-wall-surface.md` using
`superpowers:subagent-driven-development`. The plan is written to the same standard as the
engine plan and its tasks are ordered so the build stays green between them.

**Do this before dispatching Task 1 of that plan:**

1. Read the spec `docs/superpowers/specs/2026-09-19-notification-wall-design.md`. It is the
   binding authority; the plans argue from it.
2. Run the plan's pre-flight conflict scan (the SDD skill describes it). The engine plan's
   scan caught two defects before any code was written; assume the surface plan has some too.
3. Carry the carry-forward items in §6 below into the relevant task dispatches.

**Things that bit us repeatedly in the engine plan — expect them again:**

- `androidx.room.testing.MigrationTestHelper` **cannot construct under Robolectric here**
  (it casts the app context to `Instrumentation`). `app/src/test/.../data/db/MigrationTest.kt`
  established the working pattern: private `openInMemoryDb()`, hand-built old schema, direct
  `migrate()` call on the real `Migration` object. Reuse it.
- The checkout path contains a space (`External SSD`), which broke the Room Gradle plugin's
  KSP arg parsing. Schema export uses `ksp { arg("room.schemaLocation", …) }` instead.
- Hilt **eager field injection on `Application` forces Android Keystore resolution at process
  startup and crashes every Robolectric test.** `NfApplication` uses a lazily-resolved
  `@EntryPoint` inside `runCatching` for exactly this reason. Do not "simplify" it to
  `@Inject lateinit var`.
- Plan text is unverified code. Several tasks' snippets did not compile as written. The
  standing instruction to implementers is: **the tests are the specification; the plan's
  implementation is a proposal.** Never weaken a test to match code that does not work.

---

## 4. Architecture map

```
NfListenerService (bound listener; the only entry point)
  └─ NotificationMapper        → IncomingNotification
  └─ ArmingController.isArmed()  ── disarmed? log only, skip Jev entirely
  └─ WallPipeline.decide()
        OTP fast-path  (local, offline, ahead of even the block list)
        block list     → DROP
        VIP list       → RING
        verdict cache  → reuse
        Jev            → classify
        + per-(package,sender) bias, clamped ±0.75
        → RING | SILENCE          (Jev can never produce DROP)
  └─ BucketExecutor            → cancel/re-post on the DND-bypass channel
```

**Key files**

| Path | What |
|---|---|
| `domain/wall/WallPipeline.kt` | The decision. Step order is load-bearing — read its KDoc. |
| `domain/wall/VerdictCache.kt` | Two admission rules: never cache a human's message, never cache low confidence. |
| `domain/wall/BiasStore.kt` | Learned nudges, clamped ±0.75. Tunes, never overrides. |
| `domain/wall/OverrideStore.kt` | VIP/block. The only hard overrides. |
| `domain/wall/ContentShape.kt` | Normalized fingerprint that collapses marketing templates. |
| `domain/wall/OtpDetector.kt` | Local, offline, no network. |
| `ai/jev/JevClient.kt` + `JevQuestions.kt` | The five questions. **Notification policy lives in `JevQuestions`.** |
| `service/ArmingController.kt` | Armed state, derived from live DND. Never trust a stored boolean here. |
| `service/DndController.kt` | Call-safe DND policy. Has a regression test. Treat as load-bearing. |
| `data/db/NfDatabase.kt` | Version **7**, four entities, migrations 3→4→5→6→7. |
| `work/WallWorkScheduler.kt` | Sole scheduling owner. Maintenance 15 min, reclassify 30 min. |

---

## 5. Invariants — do not break these

Each was independently verified by the final whole-branch review. If a change would violate
one, it is wrong regardless of what a plan says.

1. **Calls always ring while armed.** `INTERRUPTION_FILTER_PRIORITY` with
   `PRIORITY_CATEGORY_CALLS | REPEAT_CALLERS` and `PRIORITY_SENDERS_ANY`. Never `_NONE`/`_ALARMS`.
2. **Armed state is derived from `getCurrentInterruptionFilter()` at read time.** No stored
   boolean is the source of truth.
3. **The wall never re-arms itself** after the user turns DND off externally.
4. **Jev can never produce `DROP`.** Only the user's block list can. An unauditable silent
   deletion is what makes a user distrust and disable the wall.
5. **Pipeline order is OTP → block → VIP → cache → Jev**, OTP ahead of even the block list.
6. **Never cache a verdict when `isFromHuman >= 0.7` or `confidence < 0.6`.** This is what
   stops one WhatsApp thread's family message and its forwarded promo sharing a verdict.
7. **Bias tunes, never overrides.** ±0.75 clamp, scoped per `(package, sender)`, never per app.
8. **Any Jev failure degrades to silence-and-store**, never rings, drops, or crashes the listener.
9. **Re-classification never re-posts or rings.** A notification that missed its moment stays missed.
10. **Retention purges text, keeps metadata forever.**

---

## 6. Carry-forward items for the surface plan

Real findings that the surface plan's dispatches must account for:

- **`WallSettings` and `SecurePrefs` gain fields in the surface plan** — `themeMode`
  (Task 2), `digestHour` (Task 10), `breakGlassUntilMs` (Task 9). Each is added in the task
  that needs it.
- **`NotificationDao.countsForDay` is added in surface Task 3.**
- **The digest is inert** — zero callers, its trigger died with the profile layer. Surface
  Task 10 owns rebuilding it. `DigestScheduler.delayUntilNextMillis` and its test survive intact.
- **`exactAlarms` looks unused but must NOT be removed** — surface Task 9's break-glass
  schedules an exact alarm to re-arm.
- **The Jev API key is read once at Hilt graph creation**, so a key change needs an app
  restart. The key screen says so. A hot-swappable holder is deferred; surface Task 7 is the
  natural place if wanted.
- **`arm()` now refuses without a connected listener**, returning `BLOCKED_NO_LISTENER`
  before touching DND. The Wall screen and the Quick Settings tile must both render the
  returned state rather than assuming success.
- **Surface plan Task 2 deletes `MainViewModel`'s `KeepAliveService.start`** — but
  `ArmingController.arm()/disarm()` now start/stop it, so that deletion is safe. Verify it
  stays that way.
- **Ask (surface Task 6) must decide how to treat `decisionSource` values `LEGACY`,
  `PENDING` and `EXPIRED`** in aggregates. `EXPIRED` means text was purged before a verdict
  could be obtained; its verdict columns are NULL by design.

### Deferred minors (13, none blocking)

Test hygiene mostly: a db closed outside try/finally, two unused imports, a fully-qualified
symbol, an MD5-as-fingerprint lint risk, error bodies truncated to 200 chars, a few missing
test cases (`observeState()`, `ReclassifyWorker.doWork()` control flow, `JevClient`'s null
`channel` path), stale manifest permission comments, and `ExistingPeriodicWorkPolicy.KEEP`
on the reclassify job. The final review triaged three as worth fixing and they were fixed;
the rest are genuinely fine to leave.

---

## 7. Decisions taken on the user's behalf

Fourteen rulings, recorded with reasoning at the time. Summarised; each is reversible.

| # | Decision | Cost if wrong |
|---|---|---|
| 1 | `DROP TABLE profiles/rules` moved from migration 4→5 to 5→6 | None; strictly safer |
| 2 | Fixed a wrong IST expectation (`03:43`, not `05:30`) | A visible test failure |
| 3 | Allowed hand-built-schema migration tests instead of `MigrationTestHelper` | Same coverage |
| 4 | Allowed `ksp` schema-location arg instead of the Room Gradle plugin | Identical output |
| 5 | Literal NUL byte in `NfListenerService` replaced with an escape | One duplicate inbox row, worst case |
| 6 | Rethrow `CancellationException`, catch `Exception`, narrow the `try` | None; safer than both alternatives |
| 7 | `DndChangeReceiver` moved from manifest to runtime registration | No worse than before |
| 8 | Digest left inert, handed to surface Task 10 | No daily digest until then |
| 9 | Maintenance cadence restored to 15 min (plan said daily) | Two cheap indexed queries per 15 min |
| 10 | `clearPending()` instead of fabricating a verdict for purged records | None; NULLs are skipped by aggregates |
| 11 | Verdict cache key widened to `(package, senderKey, contentShape)` per spec | One extra migration; cache self-rebuilds |
| 12 | VIP beats BLOCK at equal specificity; spec amended to match | A sender both VIP'd and blocked rings |
| 13 | Added `WallDecisionSource.EXPIRED` rather than mislabelling as `LEGACY` | One more case to render |
| 14 | Accepted that Ruling 1's stated *reasoning* was wrong; outcome stands | — |

**Six of these corrected defects in the plans themselves**, not in implementation: a
migration that would have crashed Room on launch, a health heartbeat degraded 96×, a
broadcast receiver that could never fire on `minSdk 26`, fabricated verdict data entering
future Ask answers, a cache key narrower than the spec, and a key-masking invariant that
did not hold at exactly 12 characters. Treat the surface plan with the same suspicion.

---

## 8. Device state and what still needs hand-verification

Installed on the Vivo. **Vivo's Funtouch OS is among the most aggressive Android OEMs at
killing background processes**, which makes the keep-alive foreground service and the
battery-optimisation exemption genuinely load-bearing on this device, not theoretical.

Not yet granted at handoff: notification access, DND access. The user grants these on-device.

**Must be verified by hand — no test can cover these:**

1. **Place a real call while armed.** Robolectric can prove the app *sets* the right DND
   policy; only hardware proves the OS honours it. This is the single most important check.
2. Arm, confirm a marketing notification stays silent and an OTP rings.
3. Turn DND off from the system shade → the app must show disarmed and must not re-arm itself.
4. Confirm the "Let through" channel really has *Override Do Not Disturb* enabled
   (Settings → Apps → Notification Wall → channel). Then revoke and re-grant DND access and
   check again — `ChannelManager` re-asserts it, but that path is untested on a device.
5. Deny `POST_NOTIFICATIONS`, arm, receive something important — it must stay in the shade,
   not vanish. (This was a real bug; it is fixed, but verify.)
6. Force-stop while armed, wait ~20 min, see whether "Firewall stopped" appears.
7. Reboot while armed — keep-alive should return.
8. Airplane mode → notification is silenced and stored pending; restore network and confirm
   it gains a verdict within ~30 min **without making a sound**.
9. `adb logcat | grep -i "could not reset listenerConnected\|resume reconcile failed"` — if
   either fires, the Keystore path is failing silently on this ROM.

---

## 9. Secrets

Both API keys are in `local.properties` (**gitignored**, verified untracked). Nothing reads
them from there — the app takes keys typed into the key screen and stores them in
`EncryptedSharedPreferences`. The OpenAI key was pasted into a chat transcript and **should
be rotated**. Nothing in the app uses OpenAI yet; the wall runs on Jev alone.

Never put a key in `BuildConfig`, a log, or a commit.

---

## 10. Suggested first move in the new session

> Read `docs/HANDOFF.md`, then execute
> `docs/superpowers/plans/2026-09-19-notification-wall-surface.md` with
> `superpowers:subagent-driven-development`. Run the pre-flight conflict scan first.

If the user has been using the app for a day by then, **their feedback on Jev's judgement
should be weighed before building the Ask/stats screens** — the threshold, the question
wording in `JevQuestions`, and the bias step size are all cheap to change now and expensive
to change once there is history built on them.
