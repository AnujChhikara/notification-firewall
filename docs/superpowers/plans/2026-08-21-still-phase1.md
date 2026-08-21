# Still — Phase 1 Implementation Plan

> Execution: superpowers subagent-driven-development or executing-plans; tasks are
> sequential; each ends in green `./gradlew test` (logic) or a manual device checklist
> (device), then a commit.

**Spec:** `docs/superpowers/specs/2026-08-21-still-phase1-core-loop.md`
**Goal:** the thin full flywheel — assessment → track → friction moment → results → pod invite.
**Stack:** existing app module + Firebase BoM (auth, firestore) behind a build guard.

## Global constraints

- Kotlin + Compose + Room + Hilt + WorkManager (unchanged). minSdk 26, target 34.
- Firebase optional at build time: `google-services.json` git-ignored; gradle applies
  plugin only if present; all Firebase calls go through a `StillBackend` interface with
  a no-op local impl so headless tests never touch network.
- Privacy boundary: raw notification content & usage traces never leave device.
- No comments-in-code policy from repo conventions still applies except where files
  already carry them.

---

## Task 1: Rebrand scaffold + 5-tab IA

**Files:** `ui/MainActivity.kt` (nav), `ui/theme/*`, `res/values/strings.xml`, new
`ui/program/`, `ui/results/`, `ui/pods/` placeholder screens; move Profiles/Rules
routes under Settings entry points.

- [ ] App label → "Still"; status notification text updated; launcher colors to Still palette.
- [ ] Bottom nav = Home · Program · Results · Pods · Settings (`NfBottomBar` items).
- [ ] Analytics route removed from nav (content folded into Results placeholder);
      Inbox reachable from Home card; Profiles/Rules open from Settings list.
- [ ] Keep package `com.anuj.notificationfirewall` (rename deferred).
- **Verify:** app builds; all routes navigate; tests green.
- **Commit:** `feat(still): rebrand + five-tab IA restructure`

## Task 2: Assessment flow + problem profile

**Files:** new `domain/assessment/` (Question, AssessmentAnswers, AssessmentScorer),
`data/db/entity/AssessmentResultEntity.kt` + DAO, `ui/assessment/AssessmentScreen.kt`
(replaces Welcome→Home jump: Welcome → Assessment → Diagnosis → Home).

- [ ] TDD `AssessmentScorer`: answers → `ProblemProfile(pattern, worstWindow, goal,
      protectedApps, selfReportedOpensPerDay, sleepWindow, readiness)`; deterministic,
      table-tested (8 questions incl. multi-select apps).
- [ ] Room entity + DAO (insert, observe latest); mapper to domain.
- [ ] UI: question cards (mock styling), progress dots, diagnosis screen ("here's what's
      going on" + recommended track card) → creates TrackProgress(step 1 current).
- [ ] Baseline self-reports persisted with result.
- **Verify:** scorer unit tests; device run-through of full intake.
- **Commit:** `feat(assessment): bespoke intake, problem profile, diagnosis screen`

## Task 3: Track engine + Tame the Scroll content + Program screen

**Files:** `domain/track/` (Track, TrackStep, StepAction sealed class, TrackEngine),
`content/TameTheScroll.kt` (bundled 6 steps w/ lesson copy from Guidance voice),
`data/db/entity/TrackProgressEntity.kt` + DAO, `ui/program/ProgramScreen.kt` +
`StepDetailScreen.kt`.

- [ ] TDD `TrackEngine`: current step resolution, completion, graduation state;
      actions parse from step definitions.
- [ ] `StepAction`: `OpenNotificationSettings(app)`, `EnableFriction(apps)`,
      `ConfigureMorningWindow`, `PickSubstitute`, `Graduate` — each handled by a small
      `StepActionHandler` map (device side effects behind interface, faked in tests).
- [ ] Program screen: header, progress bar, step rows (done/current/todo), skills chips;
      step detail = lesson (Markdown-lite render) + practice checkbox + action button.
- [ ] Completion writes progress locally (+ sync hook stub for Task 7).
- **Verify:** engine tests; device walk of all 6 steps' actions.
- **Commit:** `feat(track): track engine, bundled Tame the Scroll, program UI`

## Task 4: Friction-at-open (watcher + overlay + logging)

**Files:** `service/watcher/StillWatcherService.kt` (AccessibilityService),
`service/watcher/OpenDetector.kt` (pure debounce/filter logic),
`service/intervention/InterventionPolicy.kt` (pure), `InterventionController.kt`,
`ui/intervention/InterventionActivity.kt`, `data/db/entity/InterventionEventEntity.kt`
+ DAO, manifest (accessibility service config XML, overlay activity),
onboarding additions (accessibility + overlay grants).

- [ ] TDD `OpenDetector`: window-state events → app-open transitions (filters own pkg,
      systemui, shade; debounce 1.5s; ignore re-focus while our overlay shows).
- [ ] TDD `InterventionPolicy`: protected set, cooldowns (10min/app, 6/day), call-active
      suppression, let-through-rule suppression → `Intervene | Skip(reason)`.
- [ ] `InterventionActivity`: breath orb animation → if-then line (from assessment goal)
      → options (Breathe / Walk / Meant-to / Timed 5:00 / Continue); outcome logged;
      TIMED_OK schedules re-check-in via exact alarm reuse.
- [ ] Onboarding: two new grant cards with honest rationale; health flags extended
      (accessibility on, overlay granted) → degraded banner when missing.
- [ ] Manual checklist: open Instagram → overlay appears <300ms; continue works;
      cooldown holds; revoke access → degraded banner + UsageStats fallback poller runs.
- **Verify:** detector/policy tests green; device checklist above.
- **Commit:** `feat(friction): accessibility watcher, intervention overlay, event log`

## Task 5: Guidance content layer

**Files:** `content/guidance/` (Lessons.md strings, Substitutes, LapseCards),
`domain/guidance/SubstitutePicker.kt`, lapse detection in `ResultsEngine` input prep,
recovery card slot on Home.

- [ ] SubstitutePicker (TDD): assessment goal + habit library → ordered substitute list
      shown on intervention screen; user's "meant to" answer editable in Program.
- [ ] Lapse rule (TDD): day with ≥N CONTINUED outcomes → next-morning recovery card
      (normalize + data framing + one adjustment); fires once per lapse.
- [ ] Copy pass: all screens follow voice rules (no shame, tiny, concrete).
- **Verify:** picker/lapse tests; read-through on device.
- **Commit:** `feat(guidance): lessons, substitutes, lapse recovery`

## Task 6: Results engine + Results screen + recap

**Files:** `domain/results/MetricsCalculator.kt` (pure), `DailyOpenCountEntity` +
`ResultsSnapshotEntity` + DAOs, `service/ResultsWorker.kt` (daily aggregate via
WorkManager), `ui/results/ResultsScreen.kt`.

- [ ] TDD `MetricsCalculator`: from InterventionEvents + open counts → opens/day,
      walk-away rate, longest gap, week-over-week vs Week-1 reference; edge cases
      (empty days, first-week-not-complete → show partial honestly).
- [ ] Daily worker aggregates watcher counts; weekly snapshot persisted; live today view.
- [ ] Results screen: hero metric, grid, bars (reuse mock visual language), insight
      sentence generator (TDD'd template selection), share text + invite CTA.
- **Verify:** calculator tests; device check after a few real interventions.
- **Commit:** `feat(results): metrics engine, weekly snapshots, results UI`

## Task 7: Firebase scaffold + sync

**Files:** `backend/StillBackend.kt` (interface), `backend/fb/FirebaseBackend.kt`,
`backend/local/NoopBackend.kt`, DI binding, gradle guard + BoM deps,
`google-services.json` handling (.gitignore + sample), sync workers
(`ProgressSyncWorker`, `ResultsSyncWorker`).

- [ ] Interfaces: `auth(): Flow<Uid?>`, `syncProgress(...)`, `syncSnapshot(...)`;
      local impl used when Firebase absent → everything stays testable headless.
- [ ] Anonymous auth on first run (when configured); Firestore mirror per spec §8 docs;
      last-write-wins; WorkManager retry/backoff.
- [ ] User setup doc: console steps to create project + place json (manual step).
- **Verify:** mappers tested with fake; device: with json present, progress visible in
      console; without, app identical offline.
- **Commit:** `feat(backend): firebase auth/firestore sync behind StillBackend`

## Task 8: Pods + invite loop

**Files:** `domain/pods/PodCodes.kt` (pure), `ui/pods/PodsScreen.kt` + create/join flow,
deep-link intent filters (`still://pod/<code>` + https still.app/pod), nudge chips,
pod repository over StillBackend + local cache entities.

- [ ] TDD code gen (unambiguous alphabet, checksum char), deep-link parsing.
- [ ] Create/join flows; member list with week numbers + status lines; cap 6.
- [ ] Nudges: fixed set → write doc; badge + gentle received-card; read-on-open.
- [ ] Invite loop: Results recap CTA + Pods invite → system share sheet with link.
- **Verify:** pure tests; two-device (or emulator+device) join/nudge round-trip.
- **Commit:** `feat(pods): create/join by link, dashboard, nudges`

## Task 9: Polish + graduation

**Files:** Home wiring (next-step card, protections list, recovery card slot),
graduation flow (step 6 → recap + persist note), share-card bitmap render,
final copy pass, README rewrite.

- [ ] Home = mock parity: status, today numbers, next step, habit card slot, protections.
- [ ] Graduation: celebrate vs baseline, protections persist, offer maintenance mode.
- [ ] Share card: Compose → Bitmap → FileProvider share.
- [ ] README: product = Still; status = Phase 1 core loop; links to specs.
- **Verify:** full flywheel end-to-end on device; all tests green.
- **Commit:** `feat(still): home wiring, graduation, share card, readme`

---

## Self-review

- Spec §2 scope ↔ tasks: IA(1) assessment(2) track(3) friction(4) guidance(5)
  results(6) firebase(7) pods(8) polish(9). Covered.
- Two-world testing preserved; Firebase never blocks headless builds.
- Sequencing: every task ships value independently; friction (4) lands before results
  (6) so real data exists; backend (7) after local source-of-truth exists.
