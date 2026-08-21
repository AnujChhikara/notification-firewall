# Still — Phase 1: Core Loop — Design

**Date:** 2026-08-21
**Status:** Draft for review → approved defaults per user decisions below
**Parent:** `2026-08-01-attention-platform-master-design.md` (§12 Phase 1). This spec
decomposes it; every choice traces to the master spec's doctrine and research notes (`nNN`).

---

## 0. Decisions locked (user, 2026-08-21)

| Question | Decision |
|---|---|
| Product name | **Still** (display name/branding now; `applicationId` rename deferred to store release) |
| Backend | **Firebase** (Auth + Firestore; no Cloud Functions in P1) |
| Business model | **Free in Phase 1** — no paywall code |
| First track | **Tame the Scroll** (6 steps, matches ui-mock) |
| Assessment | **Bespoke short intake** (~8 questions) — no licensed scales |

## 1. Goal — the thin full flywheel

Ship one complete turn of the flywheel from master §5: a new user goes from
install → diagnosis → track → **friction moment that actually fires** → sees real
results vs baseline → invites one friend to a pod. Everything else is Phase 2.

**Non-goals for Phase 1:** focus sessions, session/binge guardian, JITAI timing
engine, habits/daily rhythm, challenges, cohorts/strangers, AI coach layer,
cross-platform, monetization.

## 2. Scope — what gets built

1. **Rebrand + IA** — "Still" branding; 5-tab nav: Home · Program · Results · Pods · Settings.
2. **Assessment + baseline** — bespoke intake → problem profile → recommends *Tame the Scroll*; captures self-reported baseline.
3. **Track engine + content** — bundled *Tame the Scroll*; each step = micro-lesson + firewall config + tiny practice; progress persisted.
4. **Friction-at-open** — detect open of a protected app → intervention screen (breath + delay + if-then + substitutes) → log outcome. *The beating heart.*
5. **Guidance System v1** — psychoeducation lesson, habit intros, in-moment substitutes, lapse-recovery copy (voice rules from master §7).
6. **Results engine** — on-device metrics vs week-1 reference; weekly recap card (shareable image later).
7. **Firebase core** — anonymous auth; sync of account, assessment profile, track progress, results numbers. Raw notifications/intervention context never leave device.
8. **Minimal pods** — create/join by link; dashboard (not a feed); pre-set nudges; invite loop off the recap.

## 3. Architecture overview

Reuse intact (master §10): listener pipeline, bucket executor, channels/DND,
profiles/rules engine, Room store, reliability layer (keep-alive FGS, exact alarms,
health monitor), analytics logging.

New components:

```
[AccessibilityWatcher]  ── detects foreground app (event-driven)
        │
        ▼
[InterventionController] ── should we intervene? (protected app? cooldown? granted?)
        │ yes
        ▼
[InterventionActivity]   ── overlay screen: breath orb → if-then → options
        │                 (breathe / walk / intended thing / timed 5 min / continue)
        ▼
[InterventionEvent]      ── logged on-device (Room): app, outcome, latency
        │
        ▼
[ResultsEngine]          ── daily/weekly aggregates vs Week-1 reference
        │                   (derived numbers only → Firestore)
[Firebase]               ── Auth (anonymous→optional email link), Firestore sync
[Pods]                   ── pods/{id} + members; invite deep link; nudges
```

### 3.1 App-open detection — **AccessibilityService** (locked)

- `StillWatcherService` listens for `TYPE_WINDOW_STATE_CHANGED`, reads only
  `event.packageName` (**never window content**, `canRetrieveWindowContent=false`),
  filters our own package + system UI + notification-shade packages, debounces
  repeat events (~1.5s per app).
- Chosen over `UsageStatsManager` polling because it is event-driven (battery),
  reacts in ~50–100 ms (no target-app flash before the overlay), and needs no
  polling loop. UsageStats remains the fallback if accessibility is revoked
  (degraded mode: poll every 1 s while screen-on; health banner shows degraded).
- **Play-policy note:** accessibility use for app blocking requires justification at
  review time; fine for sideload/dogfood now — revisit before public release.
- Grant flow joins onboarding with an honest rationale screen ("detect when a
  protected app opens so we can show your pause — we never read your screen").

### 3.2 Intervention screen — **Activity + SYSTEM_ALERT_WINDOW** (locked)

- Android 10+ blocks background activity starts; holding "Display over other apps"
  (`SYSTEM_ALERT_WINDOW`) is a documented exemption and is how blocker apps launch
  their overlays. Onboarding requests it after accessibility.
- `InterventionActivity`: edge-to-edge, blurred/scrim background, breathing orb
  (~4s), then the user's own if-then intention ("You said mornings are for a calm
  start"), then options: **Breathe · 2-min walk · Do what you meant · Okay, 5 min
  (timed)** · **Continue to ‹app›**. Autonomy-preserving: continue is always
  available, never shamed (doctrine 7, 12).
- "Okay, 5 min" starts a countdown; on expiry a quiet re-check-in (one breath +
  continue/close). No lockouts ever.
- Outcome enum: `BREATHED | SUBSTITUTE | TIMED_OK | CONTINUED | DISMISSED`.
  Logged locally as `InterventionEvent(appPackage, outcome, shownAt, decidedMs)`.

### 3.3 Intervention policy (v1, deliberately simple)

- Intervene only for apps in the user's protected set (chosen during assessment /
  track step 3; default = Instagram/TikTok/YouTube if detected installed).
- Cooldown: max 1 intervention per app per 10 min; max 6/day total; none while a
  phone call is active or during an active let-through rule match (don't fight the
  firewall). JITAI smarter timing = Phase 2.

## 4. Assessment & baseline

- ~8 plain-language questions (extends the mock's 3): pattern shape (reels/news/
  youtube/checking), worst time, cost (what it steals), goal, apps that trap them
  (multi-select, prefilled from installed launchers), rough opens/day self-report,
  sleep window, readiness (1–10).
- Output → `AssessmentResult` (problem profile + recommended track + personalization
  strings used across copy). Only one track exists in P1, so scoring confirms fit
  and personalizes voice, not routing.
- Baseline: self-reported opens/day stored immediately; measured **Week-1 reference**
  = first 7 days of on-device opens of protected apps (from InterventionEvents +
  watcher counts). Results screens label it honestly ("vs your first week").

## 5. Track engine + Tame the Scroll content

- Model: `Track(id, title, blurb)` → `TrackStep(order, title, sub, lessonMd,
  practice, firewallAction?)`. Bundled as Kotlin objects in V1 (remote delivery later).
- Firewall actions attach declaratively, e.g. step 2 → deep-link to system
  notification settings for chosen app; step 3 → enable friction for chosen apps;
  step 4 → morning protection window (reuses Profile machinery); step 5 → pick a
  substitute; step 6 → graduation (protections persist; recap offered).
- Steps advance by explicit completion (practice done), never time-gated (doctrine 8).

## 6. Guidance System v1 (content layer)

- Voice rules enforced in all copy (master §7.1): never shame; autonomy-supportive;
  concrete + tiny; names the rigged game.
- Psychoeducation = track step 1 lesson (variable rewards, anticipation, 47s/23min
  facts, "not a character flaw").
- In-moment substitutes on the intervention screen come from the user's assessment
  goal + habit library (breathing, walk, intended action, timed pass).
- Lapse handling: heavy day (>N continues) triggers a recovery card next morning:
  normalize, frame as data, offer one adjustment. No streaks anywhere.

## 7. Results engine

On-device computation from `InterventionEvent` + watcher open-counts (+ existing
`NotificationRecord` for firewall stats):

| Metric | Definition (v1) |
|---|---|
| Opens intercepted | interventions shown / day |
| Walk-away rate | (SUBSTITUTE+TIMED_OK+BREATHED) / interventions |
| Protected-app opens/day | watcher count vs Week-1 reference |
| Longest gap | longest interval between protected-app opens (focus proxy) |
| Firewall stats | captured/silenced counts (existing data) |

- `ResultsSnapshot(weekStart, metrics…)` computed weekly + current-day live view.
- Weekly recap = Results screen state rendered as a shareable card (image share in
  polish task; text share first). Recap CTA = invite to pod (virality loop).

## 8. Firebase (Auth + Firestore)

- **Auth:** anonymous sign-in on first run; optional email-link upgrade in Settings
  later (P1 keeps anonymous).
- **Sync boundary (master §10, hard rule):** synced = account uid, assessment
  profile, track progress, results *numbers*, pod data. **Never** raw notification
  content, intervention context beyond outcome enums, or app-usage traces.
- Firestore sketch:
  - `users/{uid}` { createdAt, displayName?, assessmentSummary }
  - `users/{uid}/progress/track-{trackId}` { currentStep, completedSteps[], graduatedAt }
  - `users/{uid}/results/{weekStart}` { snapshot numbers }
  - `pods/{podId}` { code, name, trackId, createdBy, createdAt }
  - `pods/{podId}/members/{uid}` { displayName, colorSeed, weekNumbers, lastActiveAt }
  - `pods/{podId}/nudges/{id}` { fromUid, toUid, kind, createdAt } (read-on-open; push = P2)
- Sync via WorkManager periodic + on-change writes; offline-first (Room is source of
  truth; Firestore mirror). Conflicts: last-write-wins per doc (single device P1).
- Build guard: `google-services.json` git-ignored; gradle applies the plugin only if
  present so headless builds/tests never break without it.

## 9. Pods (minimal)

- Create pod → generates 6-char code → invite link `https://still.app/pod/<code>`
  (custom scheme `still://pod/<code>` handles it on-device; no Firebase Dynamic Links — deprecated).
- Join by link/code → membership doc. Pod dashboard = members' week numbers + status
  line each; bounded list, no feed, no infinite scroll (doctrine 10).
- Nudges = fixed encouragement set (mock chips); send writes a nudge doc; received
  nudges surface as a badge + gentle card. Pod size cap 6 (stays high-signal).

## 10. IA / screens

```
Bottom nav: Home · Program · Results · Pods · Settings
Home     → status ("You're protected"), today's numbers, next step, protections list,
           Inbox entry point (captured mail stays here)
Program  → track header, steps, skills chips; step detail = lesson + practice + config
Results  → this week vs first week, bars, insight sentence, share/invite
Pods     → pod dashboard, nudge chips, invite
Settings → account, permissions (+ accessibility & overlay NEW), profiles/rules
           (moved here), digest, about
Overlay  → InterventionActivity (over other apps)
First-run→ Welcome → Assessment → diagnosis → track start
```

Existing screens repointed: Analytics folds into Results; Profiles/Rules move under
Settings; Inbox keeps its route but is opened from Home.

## 11. Data model additions (Room)

`AssessmentResultEntity`, `TrackProgressEntity`, `InterventionEventEntity`,
`DailyOpenCountEntity`, `ResultsSnapshotEntity`, `PodEntity`(local cache),
`SubstituteEntity` (user's chosen substitutes). Mappers to domain + Firestore DTOs.

## 12. Testing strategy

Same two-worlds rule as M1: pure logic (assessment scoring, intervention policy,
metrics math, track engine, sync mappers, pod codes) TDD'd headlessly via
`./gradlew test`; device-dependent units (accessibility events, overlay activity,
Firestore integration, deep links) get manual verification checklists. Firestore
mappers tested with Robolectric fakes; no emulator required for CI-style runs.

## 13. Build order (each becomes plan tasks)

1. Rebrand scaffold + 5-tab IA restructure
2. Assessment flow + problem profile + baseline storage
3. Track engine + bundled content + Program screen
4. Accessibility watcher + intervention overlay + event logging
5. Guidance content layer (lessons, substitutes, lapse cards)
6. Results engine + Results screen + recap
7. Firebase scaffold + sync
8. Pods + invite loop
9. Polish: home wiring, graduation, share card

## 14. Open questions (non-blocking)

1. Share-card rendering (Compose → Bitmap) — confirm in polish task.
2. Email-link upgrade UX timing (Settings now or P2).
3. Whether morning-protection (step 4) reuses Profiles verbatim or a thin wrapper — decide in plan Task for step actions.
