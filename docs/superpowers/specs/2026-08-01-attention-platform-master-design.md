# Master Design — Attention Platform (working title, TBD)

**Date:** 2026-08-01
**Status:** Master vision spec. Decomposes into per-phase specs (each phase gets its own
spec → implementation plan → build). This document is the source of truth for the whole
product; it is intentionally broad, not a single implementation plan.
**Research foundation:** `research/` (13 synthesis notes + annotated bibliography + 11
downloaded papers). Every design choice below traces to a note, cited as `nNN`.

---

## 1. Vision (north star)

**An evidence-based program that retrains your attention.** It diagnoses *your* specific
distraction problem, guides you through a personalized track to fix it, and enforces the
change automatically through an on-device firewall that reshapes your phone — so improvement
doesn't depend on willpower. It proves it's working with real behavioral results, and it
grows through friends who keep each other accountable.

The existing "Notification Firewall" app is **not** the product anymore — it becomes the
**enforcement arm** of a coaching program.

**One-line positioning:** *A guide that fixes your attention, not another blocker you install
and forget.*

## 2. Who it's for

People with low attention span / ADHD-style distractibility — the pattern of compulsive
app-browsing, doomscrolling, and long binge sessions. Serve the **behavior, not a diagnosis**:
the same tools help the clinically-ADHD user and the "digitally distractible" user; nothing
is gated behind a clinical label (n03, n13).

## 3. The problem (summary; full treatment in `research/`)

- Sustained on-screen attention has genuinely shrunk (~47s); one interruption costs ~23 min
  to refocus (n01, n05).
- Apps exploit **variable-reward loops** (slot-machine reinforcement); the *anticipation*
  drives compulsion (n02, n08).
- Short-form video, doomscrolling, and autoplay/binge each erode attention via distinct
  mechanisms (n03, n11).
- **Willpower is the wrong foundation** — ego-depletion largely failed to replicate; present
  bias makes the good choice lose in the moment (n06).
- The ADHD link is real, modest, and bidirectional (n03).
- Every competitor dies at the **3-week novelty cliff**; ~71% quit apps within 90 days; the
  winning combo (awareness + friction + replacement) is done well by no one (n10, n12).

## 4. Design principles (the doctrine)

Non-negotiables derived from the research. These govern every feature decision.

1. **Design the environment, not the willpower.** Raise *ability* / change *what's easy*;
   never rely on motivation being high at the moment of temptation. (n06, n07)
2. **Friction + deliberate choice at the moment of temptation is the keystone mechanism.**
   (One Sec RCT: 36% abandon — n09)
3. **Operationalize implementation intentions** ("if [cue], then [action]", d≈0.65 — n06).
4. **Intervene at vulnerable moments (JITAI), not on a fixed schedule.** Relevance beats
   novelty; this is how we beat the 3-week cliff. (n12)
5. **Value accrues in the background.** The firewall keeps working on days the app is never
   opened. (n12)
6. **Optimize for behavior change, NEVER for daily-active-use.** DAU is the enemy's KPI. (n12)
7. **Preserve autonomy — nudge + override, never hard jail.** Avoids reactance / uninstall.
   (n06)
8. **Forgive lapses.** A missed day doesn't derail habit formation (~66-day horizon); no
   shame-streaks. (n07)
9. **Substitute, don't just suppress.** Give freed attention a destination. (n07, n13)
10. **Never build our own addictive loop.** No infinite feeds, no manufactured variable
    rewards, no doomscrollable community. We refuse to become what we cure. (n08)
11. **Refuse the fake stuff.** No "dopamine detox" branding (can't fast dopamine); no
    brain-training claims (far transfer doesn't happen). (n02, n09)
12. **No shame, ever.** Struggling is a rigged-game problem, not a character flaw — and the
    app says so. (n06, n13)

## 5. The three engines & the flywheel

The product succeeds only if three things are true at once; they form one reinforcing loop.

**Engine 1 — It keeps working (durability):** background enforcement (5) + JITAI timing (4)
+ lapse-forgiveness (8) + graduation (protections persist after a track ends) + value ≠ DAU
(6). (n12, n07, n06)

**Engine 2 — It spreads through friends (community + virality):** pods are made of the
user's **own friends** (invite by link), so they work with *zero platform density* — this
solves cold-start. Inviting a friend is the growth loop; relatedness drives retention (SDT).
A pod **dashboard, not a feed**; bounded, high-signal, async — never a doomscroll. (n06, n08)

**Engine 3 — It shows results (proof):** baseline captured at assessment; real behavioral
metrics from on-device firewall data (opens intercepted, sessions abandoned, blocks
protected, hours reclaimed); surfaced as *outcomes vs your baseline* (SDT competence). The
weekly Results recap is the shareable artifact. (n12, n06, n09)

**The flywheel:**
```
  Real results → user feels it working & shares the recap → a friend joins their pod
       ↑                                                              │
       └────── accountability + relatedness boost retention ─────────┘
                    (more days protected → more results)
```
Community is not a side feature — it is the retention + growth engine, powered by results.

## 6. Feature set

### 6.1 Problem → Feature map

| Problem (research) | Feature | Mechanism |
|---|---|---|
| Notifications → 23-min refocus (n01, n05) | Notification Firewall (buckets, batching, trigger-stripping) | Remove exogenous triggers ✅ built |
| Compulsive checking / variable reward (n02, n08) | Friction-at-open (breath + delay + "continue?") | One Sec effect (n09) |
| Present bias, willpower fails (n06) | User-authored if-then rules | Implementation intentions |
| Doomscroll / short-form erosion (n03) | Session guardian (stopping cues, checkpoints) | Reintroduce stopping cues |
| Binge / autoplay time-blindness (n11) | Session interrupt (flip autoplay default) | Make stopping the default |
| Fragmented deep work (n01, n05) | Focus sessions (protected blocks) | Engineer flow |
| Sleep erosion (n09) | Sleep guardian (wind-down, DND, phone-away) | Sleep = substrate of attention ✅ partial |
| No proof → quitting (n12) | Results engine (baseline + metrics + recap) | SDT competence |
| 3-week fade / install-drop (n12) | JITAI timing + background enforcement + graduation | Relevance beats novelty |
| Reactance from hard blocks (n06) | Nudge + override everywhere | Preserve autonomy |
| Escape-coping / empty void (n07, n13) | Restorative substitutes | Substitution + ACT/JOMO values |
| ADHD-style distractibility (n03, n13) | Personalized tracks (CBT/ACT skills) | Diagnose then target |
| Loneliness / no accountability (n06) | Friend pods + nudges | SDT relatedness → retention + virality |

### 6.2 Grouped by pillar

- **A. Diagnose:** onboarding (why + permissions with rationale); Attention Assessment;
  baseline capture (opens/day, target-app time, focus-block length, sleep window).
- **B. Program (coach):** personalized Tracks (e.g., *Tame the Scroll, Reclaim Mornings,
  Beat the Binge, Deep-Work Builder, Sleep First*), each a sequence of **steps** (step =
  micro-lesson + firewall config + small practice); Skill library (CBT + ACT/JOMO);
  Graduation.
- **C. Enforce (firewall, repointed):** buckets/batching/DND/profiles; friction-at-open with
  if-then rules; focus sessions; session guardian/interrupt; sleep guardian; JITAI engine.
- **D. Prove (results):** live metrics; weekly Results recap (shareable); insights (where
  attention goes, when you're vulnerable); progress vs baseline (+ optional benchmark).
- **E. Belong (community):** friend pods (invite by link, zero-density); pod dashboard (not a
  feed) + gentle nudges; invite loop wired to the recap; *(V3)* cohorts / stranger-matching.
- **F. Sustain (scaffolding — lapse-forgiving, never dopamine-farmed):** light challenges;
  daily rhythm (morning intention / evening review); habit tracking for focus-supporting
  habits.

## 7. The Guidance System (how the app guides a struggling user)

The content/coaching layer that meets someone struggling and tells them concretely what to
do — all research-backed.

### 7.1 Coaching voice
- **Never shames** (n06, n13): says out loud that the apps are engineered slot machines, so
  the struggle isn't weakness.
- **Autonomy-supportive** (offers, never commands), **competence-building** (points at small
  wins), **always concrete + tiny** (one specific micro-action, never "be disciplined").

### 7.2 "Understand your brain" (psychoeducation)
Before asking for change, explain *why* they struggle, personalized to their assessment —
plain-language mechanism (variable rewards, anticipation, the rigged game). CBT phase 1
(n13); most reassuring thing the app can do.

### 7.3 Research-backed Habit Library
The concrete menu of actions the guide recommends — each introduced **tiny-first** (Fogg
B=MAP, n07) and **anchored to an existing routine** (implementation intention, n06),
**one at a time** (avoid overload → abandonment, n12):

| Habit | Why (research) | Tiny first step | Struggle |
|---|---|---|---|
| Phone out of bedroom | Highest-yield for sleep (n09) | "Tonight, charge it across the room." | Sleep, morning scroll |
| Kill non-human notifications | Remove triggers (n01, n04) | "Turn off notifications for one app." | Interruptions, checking |
| No-scroll first hour | Protect the day's attention (n02) | "Delay first scroll by 10 min." | Morning scroll |
| Grayscale | Strip reward salience (n04) | "Grayscale for one afternoon." | Compulsive checking |
| Move 10 min | Aerobic exercise boosts EF now (n09) | "One 10-min walk." | ADHD, focus, mood |
| Single-task a block | Protect flow (n05) | "One 15-min task, phone away." | Deep work |
| Wind-down ritual | Guard sleep window (n09) | "'Dim lights' alarm at 10pm." | Sleep, binge |
| Box breathing | Micro-mindfulness, trains EF (n09) | "Three slow breaths now." | In-moment urges |
| If-then plan | d≈0.65 breaking habits (n06) | "Finish: 'If I open X, then I'll ___.'" | All |
| Restorative break (not a feed) | Nature/low-stim restores attention (n05) | "Look out a window 60s." | Fatigue, escape-scroll |

### 7.4 In-the-moment guidance
The intervention screen offers *what to do instead*, from the library + the user's own values
(ACT/JOMO, n13). Substitution beats suppression (n07). Example:
```
You opened Instagram.  You told us you wanted your evenings back.
   → Breathe (3 breaths)      → 2-min walk
   → Do the thing you meant   → Okay, 5 minutes (timed)
```

### 7.5 Lapse & recovery guidance
On a slip: no broken-streak animation. Normalize (Lally: a missed day doesn't derail, n07),
frame the slip as **data not failure** (CBT relapse-prevention, n13), and offer one concrete
adjustment tied to the context of the slip. **Recovery, not punishment.**

### 7.6 Delivery (where guidance lives)
Track steps (psychoeducation + one habit) · intervention screen (in-moment) · JITAI engine
(right moment) · results/graduation (competence) · lapse detection (recovery) · pods (friends
echo the guidance).

## 8. Key user flows

1. **First run:** onboarding → assessment → baseline → "here's your problem" → recommend a
   track → set 1–2 if-then rules → firewall live.
2. **Intervention moment (core micro-flow, JITAI):** context detected → friction screen
   (breath + delay) → show user's own if-then intention → [continue] / [do X instead] → log
   outcome → feed results. *The product's beating heart.*
3. **Daily rhythm (optional):** morning 10-sec intention → firewall runs silently → evening
   20-sec review + reclaimed time.
4. **Weekly results → virality:** recap card → share / invite friend to pod → friend joins.
5. **Community / pod:** create or join via link → pick shared track/challenge → see pod
   progress → send/receive nudges → celebrate milestones.
6. **Graduation → durability:** finish track → celebrate results vs baseline → protections
   persist autonomously → offered next track or maintenance mode.

## 9. Screen map (information architecture)

```
Bottom nav:  Home ·  Program ·  Results ·  Pods ·  Settings

Home     → today's status, active protections, next step, quick focus-session start
Program  → your track, steps (done/next), skill library, graduation
Results  → baseline vs now, weekly recap, insights, share/invite
Pods     → your pod(s), members' progress, nudges, invite
Settings → firewall rules & profiles, if-then editor, permissions, sleep window, account
Overlay  → Intervention screen (Flow 2), appears over other apps
First-run → Onboarding + Assessment (full-screen)
```

## 10. Architecture (high level)

- **Client:** existing native Android app (Kotlin + Jetpack Compose + Room + WorkManager +
  Hilt). Reused: notification listener pipeline, bucket executor, channel/DND control,
  profiles/rules engine, analytics, reliability layer (foreground service, exact-alarm
  scheduling, health monitor).
- **Backend (new, cloud-first per user decision):** auth/accounts, sync, program-content
  delivery, pods, aggregate/benchmark stats. Tech choice deferred (see §14).
- **Privacy model (critical):** **raw notification content never leaves the device.** The
  firewall analyzes locally. Only *derived* data syncs: account, assessment profile, track
  progress, results *numbers* (not content), habit logs, pod data. This preserves the trust
  story despite a cloud backend.
- **JITAI engine:** on-device context (active profile, time, foreground app, notification
  stream, recent behavior) → decision rules → minimal intervention. Local-first for privacy
  + latency.

## 11. Data model (high level, entities)

- **Account** (auth, synced)
- **AssessmentResult** (problem profile + baseline metrics)
- **Track** / **TrackStep** (content; bundled in V1, remote later)
- **UserTrackProgress** (current step, completion, graduation, maintenance)
- **Profile** (existing: schedule, buckets, DND, autoDnd) · **Rule** (extends existing;
  includes if-then friction rules)
- **InterventionEvent** (open attempt; outcome: continued / abandoned / substitute) — *on
  device*; feeds results
- **NotificationRecord** (existing analytics) — *on device only*
- **Habit** / **HabitLog**
- **ResultsSnapshot** (baseline vs now; weekly) — derived numbers, syncable
- **FocusSession**
- **Pod** (invite link, shared track/challenge) · **PodMembership** · **Nudge**

Sync boundary: on-device = NotificationRecord, InterventionEvent, raw context. Synced =
Account, AssessmentResult, TrackProgress, ResultsSnapshot (numbers), HabitLog, Pod data.

## 12. Build phasing (design now, build day by day)

The entire design above is locked up front. Build order (each phase = its own spec → plan):

- **Phase 1 — Core loop (the thin full flywheel):** account/sync · assessment + baseline ·
  1–2 tracks · firewall wired to track steps · friction-at-open · Guidance System v1
  (psychoeducation + habit intro + in-moment + lapse) · Results engine + weekly recap ·
  minimal friend-pods + invite loop.
- **Phase 2 — Depth:** more tracks · focus sessions · session/binge guardian · JITAI timing
  engine · habits & daily rhythm · richer insights · challenges.
- **Phase 3 — Scale:** cohorts / stranger-matching · advanced personalization (optional AI
  coach layer over the structured backbone) · cross-platform.

## 13. Success metrics & non-goals

**Success = behavior change, not engagement:**
- Compulsive-open rate ↓ vs baseline; sessions abandoned after friction ↑; protected/
  uninterrupted blocks ↑; sleep window respected; hours reclaimed; track graduation rate;
  pod-invite conversion (virality); **retention past the 3-week and 90-day cliffs**.
- **Explicitly NOT** DAU / session count / time-in-app (that's the enemy's KPI — n12).

**Non-goals / anti-patterns we refuse to build:** infinite feeds, leaderboards that shame,
manufactured variable rewards, hard un-overridable lockouts, "dopamine detox" branding,
brain-training mini-games sold as attention cures, syncing raw notification content.

## 14. Open questions (to decide before/within each phase's spec)

1. **Product name** (rebrand from "Notification Firewall") — TBD.
2. **Backend tech** (e.g., Firebase / Supabase / custom) — solo-dev friendly, sync + auth +
   content + pods. Decide in Phase 1 spec.
3. **Business model** (free / freemium / subscription) — affects paywall placement. Default
   assumption: free in Phase 1, monetize later; confirm.
4. **First-track choice** for Phase 1 (recommend *Tame the Scroll* or *Reclaim Mornings* —
   highest prevalence, cleanest firewall mapping).
5. **AI coach layer** — deferred to Phase 3 as an option over the structured backbone (user
   chose structured program as the core).
6. **Assessment instrument** — adapt a validated scale vs a bespoke short intake.

---

*Derived from the `research/` knowledge base. This master spec is decomposed into per-phase
specs; Phase 1 is specced and planned first.*
