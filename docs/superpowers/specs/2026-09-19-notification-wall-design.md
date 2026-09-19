# Notification Wall — Design

**Date:** 2026-09-19
**Status:** Approved, ready for implementation planning
**Supersedes:** `2026-08-01-attention-platform-master-design.md`, `2026-08-21-still-phase1-core-loop.md`

---

## 1. What this is

A single-purpose Android app that stands between every notification the phone
receives and the user's attention. Every notification is intercepted, judged by
[Jev](https://docs.typesafe.ai) — a fast, calibrated classification model — and
either rung through or silenced and filed. Everything is stored locally so the
user can query their own notification history in natural language and see how
much noise was absorbed.

It is a personal tool for a single user. There is no backend, no account, no
sync, no multi-user concern.

### The pivot

This repo previously grew into "Still", an attention-retraining *program*: a
diagnostic assessment, a coached track, pods, results. That framing is
abandoned. The notification firewall — formerly the program's enforcement arm —
becomes the entire product.

What survives the pivot is the reliability layer, which is the genuinely
expensive part of doing this correctly on Android: the listener service, the
keep-alive foreground service, boot recovery, the self-healing health monitor,
DND control, and the bypass-channel re-post mechanism. The decision brain and
the entire UI are rewritten.

### Non-goals

- No cloud sync, no backend, no account system.
- No rule-authoring DSL. The user never writes rules.
- No time-of-day profiles. One wall, one threshold.
- No screen-time tracking, app blocking, or behavior coaching.

---

## 2. The platform constraint that shapes everything

Android gives no application veto power over a notification before it is
posted. A `NotificationListenerService` is invoked *after* the system has
already made the sound and lit the screen. There is no pre-post hook, at any
API level, for a non-system app.

The only way to guarantee silence at arrival is to put the OS itself into Do
Not Disturb, so that the system suppresses every notification as it lands, and
then have this app re-post the ones that matter on a notification channel
configured to bypass DND.

Consequences, all of which the design must own explicitly:

- **The app controls system DND.** Arming the wall turns DND on; disarming
  turns it off and restores the user's previous policy verbatim.
- **Arming state and system DND state are the same fact.** They can never be
  allowed to drift (§6).
- **DND must be call-safe.** DND that blocked phone calls would be
  unacceptable. This is an invariant, not a preference (§6.2).
- **Rung notifications are re-posts.** The user sees a notification posted by
  this app, carrying the original's content, not the original notification
  object. This is a visible, accepted trade-off.

---

## 3. Decision pipeline

`NfListenerService` → `NotificationMapper` → `WallPipeline` → `BucketExecutor`

```
onNotificationPosted
  │
  ├─ ignore: ongoing / foreground-service / media-transport /
  │          group-summary / this app's own notifications
  │
  ├─ 1. OTP fast-path ─────────── code pattern matched → RING   (0ms, offline)
  ├─ 2. block list ────────────── package or sender listed → DROP + store
  ├─ 3. VIP list ──────────────── package or sender listed → RING
  ├─ 4. verdict cache ─────────── hit → reuse verdict        (0ms, offline)
  ├─ 5. Jev ───────────────────── classify                   (~130ms)
  │
  ├─ apply sender bias, clamped to ±0.75
  └─ score ≥ threshold → RING (re-post on bypass channel)
     score <  threshold → SILENCE + store
```

Steps 1–3 are deterministic and local. Step 4 is local. Only step 5 touches the
network, and only for notifications that are neither pinned nor recognized.

### 3.1 Bucket outcomes

| Bucket | Behavior |
|---|---|
| `RING` | Re-posted on the DND-bypass channel with sound; stored. |
| `SILENCE` | Not re-posted. Stored, visible in the Inbox. The original stays silently in the system shade. |
| `DROP` | Not re-posted, and the original is cancelled from the shade. Stored (so stats and Ask stay complete). Only reachable via the block list. |

`DROP` exists solely so that a user-listed spammer disappears entirely rather
than accumulating in the shade. Jev never produces `DROP` on its own — an AI
judgment is never allowed to make a notification vanish without a trace the
user can find.

### 3.2 The Jev call

One call per uncached notification, to
`POST https://api.typesafe.ai/v1/systemone` with `model: "jev-latest"`. Jev
evaluates every question in parallel against the same state, so asking five
questions costs essentially the same latency as asking one.

**State:**

```json
{
  "app": "Myntra",
  "channel": "offers",
  "title": "FLAT 70% OFF ends in 3 hours",
  "text": "Your favourites are on sale. Shop now...",
  "arrived_at_local": "23:41",
  "is_reply_capable": false,
  "is_from_contact": false
}
```

`is_reply_capable` (the notification carries a reply action) and
`is_from_contact` (sender matched against the contacts the user has granted
access to) are cheap local signals that materially help Jev separate a real
conversation from a marketing blast.

**Questions:**

| Key | Type | Purpose |
|---|---|---|
| `importance` | `score`, 5 levels | The ring/silence decision |
| `category` | `choice`, 9 options | Stats, Ask, and user legibility |
| `is_time_sensitive` | `noul` | Acting later loses value |
| `is_from_human` | `noul` | Gates caching (§3.3); powers the humans-vs-machines stat |
| `needs_action` | `noul` | Expects a reply or action from the user |

`importance` levels, low → high:

1. Pure noise — marketing, promotional, engagement bait
2. Low — informational, can wait days
3. Routine — worth reading today
4. Matters — the user should know soon
5. Critical — interrupt now

`category` options: `promotion`, `personal_message`, `transactional`, `work`,
`social`, `news`, `system`, `delivery`, `other`.

**Why `importance` is a Score and not a Noul.** Score returns a continuous
value (e.g. `3.7`) with a probability distribution across levels. That makes
the Strict↔Relaxed slider a genuinely continuous cutoff rather than three
coarse steps, and it gives the bias mechanism (§3.4) something meaningful to
shift.

**Cost.** A notification is roughly 60 input tokens. Jev bills $0.042 per
million input tokens, output free. At 300 notifications/day this is under a
cent per year. The verdict cache exists for latency and offline resilience, not
for cost.

### 3.3 The verdict cache

**Cache key:** `(package, senderKey, contentShape)`.

`contentShape` is a normalized fingerprint of title+text with digits,
currency amounts, percentages, dates, times, URLs, and order/tracking IDs
replaced by placeholder tokens, then hashed. So Myntra's
`"FLAT 70% OFF ends in 3 hours"` and `"FLAT 50% OFF ends in 6 hours"` collapse
to one cache entry, while a genuinely different message does not.

**Two hard rules on what may be cached:**

1. **Never cache when `is_from_human ≥ 0.7`.** A message from a person is
   judged on its own content, every single time. This is the rule that prevents
   the failure the user named directly: a family WhatsApp thread is one sender,
   but "reached home safely" and a forwarded Croma sale are not the same
   notification and must never share a verdict.
2. **Never cache when Jev's confidence is below 0.6.** A low-confidence snap
   judgment is used once and re-asked next time, so one uncertain call cannot
   permanently mislabel a sender.

Cache entries carry a hit count and last-used timestamp; entries unused for 90
days are evicted by the maintenance worker.

### 3.4 Feedback: bias, not rules

Swiping a notification in the Inbox records a correction. A correction **never**
creates a blanket app-level or sender-level rule.

- A correction adjusts a bias stored per `(package, senderKey)`.
- Each correction moves the bias by 0.25 in the indicated direction.
- The bias is **clamped to ±0.75** on the 1–5 importance scale.
- The bias is added to Jev's score before the threshold comparison.

The clamp is the safety property. Three "should've been silent" swipes on
Myntra's marketing drag it to −0.75, which reliably suppresses the routine
blasts. But if Myntra ever sends a genuine delivery OTP, Jev scores it 5.0 and
the bias cannot pull it below a threshold of 4.0. **The bias tunes; it never
overrides.**

Hard overrides come only from the VIP and block lists, which the user sets
deliberately and can inspect and remove at any time.

A correction also invalidates any cache entry matching that content shape.

### 3.5 Threshold

A single float, default 4.0, set by one Strict↔Relaxed slider in Settings.
Moving the slider shows a live preview: how many of today's notifications the
new value would have rung instead of silenced, and vice versa.

### 3.6 OTP fast-path

Notifications matching a one-time-code pattern (a 4–8 digit code with OTP/code/
verification vocabulary nearby) always ring, without consulting the block list,
the cache, or Jev. Zero latency, works offline, and it is the one category where
an incorrect silence has an immediate concrete cost. The user can disable the
fast-path in Settings but cannot make Jev responsible for OTPs.

### 3.7 Offline and failure handling

On a cache miss with no network, or on any Jev error (timeout, 401, 429, 5xx):

- The notification is **silenced and stored** with `pending_classification = true`.
- Nothing is lost — it is fully visible in the Inbox immediately.
- A WorkManager job, constrained on network connectivity, re-classifies the
  pending backlog when connectivity returns, so stats and Ask never contain
  holes.
- Re-classification updates the stored record's verdict, but never re-posts or
  rings retroactively. A missed ring stays missed; the app does not buzz about
  an hour-old notification.

Jev calls have a 3-second timeout. A slow API degrades to silence-and-store
rather than delaying the pipeline.

---

## 4. Data model

Room over SQLite. Entirely local, no backend, no sync.

### 4.1 `notifications`

Extends the existing `NotificationRecordEntity`:

| Column | Notes |
|---|---|
| `id`, `packageName`, `appLabel`, `title`, `text`, `timestampEpochMs`, `senderKey` | Existing |
| `contentShape` | Normalized fingerprint (§3.3) |
| `importanceScore` | Float from Jev, pre-bias |
| `biasApplied` | Float actually added |
| `category` | Jev choice |
| `isTimeSensitive`, `isFromHuman`, `needsAction` | Nouls, 0–1 |
| `jevConfidence` | Float |
| `decisionSource` | `OTP` / `VIP` / `BLOCK` / `CACHE` / `JEV` / `PENDING` |
| `bucket` | `RING` / `SILENCE` / `DROP` |
| `pendingClassification` | Boolean |
| `textPurgedAt` | Nullable timestamp |
| `isRead` | Existing |

### 4.2 `verdict_cache`

`contentShapeKey` (PK) → package, senderKey, the full verdict, confidence, hit
count, created, last used.

### 4.3 `sender_bias`

`(package, senderKey)` (PK) → bias float, correction count, last corrected.

### 4.4 `overrides`

`id`, kind (`VIP` / `BLOCK`), match target (package or package+sender), label,
source (`MANUAL` / `SWIPE`), created. The source flag lets Settings show the
user which entries they added by hand versus which accumulated from swiping.

### 4.5 Retention

- **Metadata: forever.** Package, sender, category, scores, bucket, timestamp.
  A few dozen bytes per notification; a year of heavy use is a few megabytes.
  This is what makes long-range Ask questions possible.
- **Text: 30 days.** A daily maintenance job nulls `title` and `text` on records
  older than 30 days and stamps `textPurgedAt`. Bounded privacy exposure, and
  the DB stays small.

Both windows are adjustable in Settings, including "keep text forever" for a
user who wants it.

### 4.6 Migration

The `profiles`, `rules`, and `assessment_results` tables are dropped. Existing
rows are Still-era configuration with no meaning in the new model, and this is a
single-user app running on the author's own device, so a destructive migration
is correct. Captured `notifications` rows are preserved where the schema allows;
new columns default to null with `decisionSource = 'LEGACY'`.

---

## 5. Screens

Four tabs: **Wall · Inbox · Ask · Settings**.

### 5.1 Wall

The master toggle is the screen's hero element — a single large control that
arms and disarms the wall.

**The toggle renders live system state, never a stored boolean.** See §6.1.

Below it:

- Today's counters: silenced, rung, dropped.
- The break-glass button (§7.4).
- The most recent digest as a card.
- A blocking banner, replacing the toggle entirely, if notification-listener
  access or notification-policy access has been revoked. The toggle is never
  shown in a state it cannot honor.

### 5.2 Inbox

Everything captured, newest first, with an app-grouped view.

Each row shows the notification and *why it was judged that way* —
`Myntra · promotion · 1.4 · cached` — because an opaque filter is an
untrustworthy filter. Tapping expands to the full text, the Jev verdict
including confidence, and the bias that was applied.

Swipe left: "should have been silent". Swipe right: "should have rung me".
Either writes a bias (§3.4), invalidates the matching cache entry, and shows an
undo snackbar.

Long-press offers VIP / block / never-store for that sender.

### 5.3 Ask

Stat cards above a chat box.

**Stats:** noise ratio over time, top offenders by silenced count, category
breakdown, humans-vs-machines split, hour-of-day heatmap, and the correction
rate (how often the user disagreed with Jev — the app's own honesty metric).

**Chat — text-to-SQL, so raw notification content never leaves the device:**

1. OpenAI receives the table schema and the user's question. It does **not**
   receive any notification data.
2. It returns a single read-only `SELECT`.
3. The app validates it: one statement only, `SELECT` only, no `PRAGMA`/
   `ATTACH`/`;`, only known tables, a mandatory `LIMIT`, and the `title`/`text`
   columns rejected unless the user has toggled "allow content in answers" for
   that query.
4. The app executes it locally against Room.
5. Only the resulting aggregate rows go back to OpenAI for phrasing.

The generated SQL is shown to the user, collapsed, under every answer.

### 5.4 Settings

Strict↔Relaxed threshold slider with live preview · VIP and block lists ·
OTP fast-path toggle · digest time · theme (System / Light / Dark) · API keys in
`EncryptedSharedPreferences` · permissions and health status · retention windows
· export everything to JSON · purge controls.

### 5.5 Digest

One notification per day, at a user-chosen time, generated from the new data:
counts, the worst offender, and a short list of silenced items worth a look.
This is the payoff moment that makes the silence feel earned rather than
worrying. It reuses the existing `DigestWorker`/`DigestScheduler` scaffolding.

---

## 6. Arming, DND, and state integrity

### 6.1 System DND is the single source of truth

The stale-toggle bug in the current build has a specific cause: `dndSetByApp` is
a `SharedPreferences` boolean recording the app's *belief*, never reconciled
against reality. Turning DND off from the system shade leaves that belief
stranded, and the UI happily reports an armed wall that is doing nothing.

The fix is structural, not a patch:

- `NfListenerService.onInterruptionFilterChanged(int)` fires on **any** DND
  change, system-wide, including ones the app did not initiate. It is the
  primary signal.
- An `ACTION_INTERRUPTION_FILTER_CHANGED` receiver covers the same ground for
  when the listener is temporarily unbound.
- A reconcile runs on every app resume and on boot.
- The UI derives armed-state from `NotificationManager.getCurrentInterruptionFilter()`
  at render time. There is no stored boolean the view can read.

`dndSetByApp` survives only for one narrow purpose: remembering whether *this
app* was the one that enabled DND, so the app only ever restores a policy it
actually replaced, and never disables DND the user turned on themselves.

**When the user turns DND off externally:** the wall truly disarms, and stays
disarmed until the user re-arms it. Notifications flow normally. The app keeps
logging them silently so stats and Ask remain continuous. It does not re-enable
DND, does not nag, and does not auto-re-arm.

### 6.2 Call safety is an invariant

The existing `DndController` already implements this correctly, and this design
keeps that code essentially unchanged:

- Filter is `INTERRUPTION_FILTER_PRIORITY`, never `_NONE` or `_ALARMS`.
- Priority categories include `PRIORITY_CATEGORY_CALLS` and
  `PRIORITY_CATEGORY_REPEAT_CALLERS`, with `PRIORITY_SENDERS_ANY` — a call from
  anyone rings.
- Alarms, media, and system sounds pass on API 30+.
- The user's original policy is captured before modification and restored
  verbatim on disarm.

This gets a dedicated regression test asserting the exact category mask and
sender constants, so a future refactor cannot quietly make the wall block calls.

### 6.3 Reliability layer

Carried over unchanged: `KeepAliveService` (foreground service while armed),
`BootReceiver`, `HealthMonitor`/`HealthEvaluator` (detects a killed or unbound
listener and self-heals), `ChannelManager` (bypass channel creation),
`MaintenanceWorker`. `ProfileBoundaryReceiver`, `ProfileStateReconciler`, and
`ProfileScheduler` are deleted along with the profile concept; `KeepAliveService`
is re-keyed to armed-state instead of profile windows.

---

## 7. Extras

### 7.1 OTP fast-path
See §3.6.

### 7.2 VIP list
Packages or senders that always ring, bypassing Jev, the cache, and the
threshold. The deliberate escape hatch for "never let the model decide this one".

### 7.3 Quick Settings tile
A `TileService` mirroring the master toggle, so the wall can be armed and
disarmed from the system shade. It reads the same live DND state as the Wall
screen (§6.1), so the two can never disagree.

### 7.4 Break-glass
One button: let everything through for 60 minutes. Sets
`INTERRUPTION_FILTER_ALL` and schedules an exact alarm to re-arm. The alarm is
re-registered on boot so a restart cannot strand the wall in the open state. The
Wall screen shows a countdown while it is active.

---

## 8. Theming

The current `NfTheme` is dark-only by design decision. That decision is
reversed.

- Semantic tokens — `wallSurface`, `wallSurfaceElevated`, `borderSubtle`,
  `textPrimary`/`Muted`/`Faint`, `bucketRang`, `bucketSilenced`,
  `bucketDropped`, `accent`, `danger` — provided through a `CompositionLocal`
  and resolved from a light or dark palette.
- Components reference tokens only. No composable imports a raw `Color`
  constant, which is what currently makes the app structurally dark-only.
- Follows the system theme by default, with a System/Light/Dark override in
  Settings.
- The existing dark palette is kept — it is good. A light palette is built to
  match its restraint, and the three status colors are re-tuned for adequate
  contrast against a light surface, where the current green and amber would
  fail.
- Status bar and navigation bar icon appearance follow the resolved theme.

---

## 9. Testing

The existing headless setup (JUnit + Robolectric + Room testing + MockWebServer)
is sufficient; no new infrastructure.

- **Pipeline:** table-driven over fixture notifications covering every branch —
  OTP, block, VIP, cache hit, Jev path, bias clamping at both bounds, offline
  degradation. Driven by a `FakeJevClient`.
- **Cache rules:** explicit tests that a human-sender verdict is never cached
  and that a sub-0.6-confidence verdict is never cached. These encode §3.3's
  two hard rules and the WhatsApp family-versus-promo failure directly.
- **Bias:** a −0.75-biased sender still rings a 5.0 notification.
- **Content shape:** two Myntra promos with different numbers collapse; a promo
  and a real message from the same sender do not.
- **DND:** the call-safety invariant (§6.2); policy save/restore round-trip;
  external-DND-change disarms.
- **Jev/OpenAI clients:** MockWebServer, including 401/429/5xx/timeout paths.
- **SQL validator:** injection attempts, multi-statement, writes, unknown
  tables, missing `LIMIT`, unauthorized `text` access.
- **Migration:** old schema → new schema, including the legacy-row path.

---

## 10. What gets deleted

| Path | Reason |
|---|---|
| `ui/assessment/`, `domain/assessment/`, `AssessmentDao`, `AssessmentResultEntity` | Still's diagnostic |
| `ui/program/`, `ui/pods/`, `ui/results/` | Still's coaching layer |
| `ui/welcome/`, `ui/onboarding/` | Program framing; replaced by a short permissions-only onboarding |
| `ui/profiles/`, `domain/profile/`, `ProfileEntity`, `ProfileDao` | Profiles replaced by a single wall |
| `ui/rules/`, `domain/rules/`, `RuleEntity`, `RuleDao`, `Condition` | Rule DSL replaced by Jev |
| `work/ProfileScheduler`, `service/ProfileBoundaryReceiver`, `service/ProfileStateReconciler` | Profile machinery |
| `ai/OpenAiImportanceService`, `ai/ImportanceService` | Replaced by the Jev client |
| `ui/analytics/AnalyticsScreen` | Rebuilt as part of Ask |

Roughly 2,500 lines removed. Note: Firebase appears in the README's stated stack
but was never actually wired into the build or any source file, so there is
nothing to remove there.

---

## 11. Decisions recorded

| Decision | Choice |
|---|---|
| Approach | Refactor in place; keep the reliability layer, replace brain and UI |
| Decision core | Jev-only, plus explicit VIP/block lists |
| Offline | Learned local cache first, Jev on miss, silence-and-store as the floor |
| Privacy | Jev sees content; OpenAI sees only schema and aggregate rows |
| Wall mode | Hard wall, app owns DND while armed |
| Threshold | One slider plus learned per-sender bias |
| IA | Wall · Inbox · Ask · Settings |
| Retention | Metadata forever, text 30 days |
| External DND-off | Wall truly disarms and stays disarmed |
| Feedback scope | Per `(package, sender)` bias, clamped ±0.75 — never an app-wide rule |
| Extras | OTP fast-path, VIP list, Quick Settings tile, break-glass, digest |
| Storage | Room/SQLite, local only, no backend |
| Package id | `com.anuj.notificationfirewall` retained; renaming touches every file for cosmetic gain |
| App name | Reverts from "Still" to "Notification Wall" |

## 12. Deferred

Ideas raised and consciously not built now: weekly noise report, per-app
never-store list beyond the block list, snooze-app-for-N-days, home-screen
widget, CSV export, on-device model as a Jev fallback.
