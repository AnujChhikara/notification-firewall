# Notification Firewall — Design Overview (Milestone 1)

**Working title:** Notification Firewall *(name TBD — see Open Questions)*
**Date:** 2026-07-27
**Status:** Draft for review — iterative
**Author:** Anuj (with Claude Code)

---

## 1. The Problem

Phone-wide Do Not Disturb is too blunt. When you go to sleep you want silence
from noise — promotional email, marketing pushes, random group chatter — but you
still need a few genuinely important things to break through: a message from your
company on Gmail, an urgent WhatsApp from a real person.

The OS gives you one dial (DND on/off) and per-app toggles. It has no concept of
*"this WhatsApp message matters but that one doesn't,"* or *"company email yes,
promo email no."* You need a **filtering layer that sits between the app's
notification and your attention** — inspecting each notification, deciding its
fate, and only then deciding whether to make a sound, stay silent, or hide it
entirely.

That layer is this app.

## 2. Vision & Three Pillars

1. **Notification Firewall** — intercept every notification, run it through
   user-defined profiles + rules, and route it into a bucket
   (let-through / silence / capture), with custom sounds for the things that
   matter most.
2. **AI Brain** — for the cases rules can't judge ("is this message *urgent*?"),
   an LLM makes the call; it also writes plain-language **digests**
   ("while you slept: 3 work emails, 12 promos, 5 group messages").
3. **Sender Analytics + Coach** — show who floods you most, label senders
   spam/important, and *suggest* what to do (mute this chat, unsubscribe from
   that newsletter) with a one-tap path where trivial, short how-to text where not.

## 3. Scope & Decomposition

This is a large product. It is built in milestones, each with its own spec →
plan → implementation cycle. **This document covers Milestone 1 only.**

| Milestone | Contents | Status |
|-----------|----------|--------|
| **M1 — Firewall Core + thin AI** | Listener pipeline, profiles, rule engine, buckets, custom sounds, in-app inbox, **OpenAI importance + sleep digest** (single-user, own key) | **This doc** |
| M2 — AI hardening | Batching, cost controls, better prompts, per-profile AI config, on-device fallback | Later |
| M3 — Sender Analytics + Coach | Volume analytics, spam/important verdicts, deep-link suggestions, email unsubscribe | Later |
| M4 — Premium & Billing | Tier gating (rules = free, AI = premium), billing, key management/backend proxy | Later |

M1 is **fully on-device** except direct calls to OpenAI. No backend server.
It is shippable and demoable on its own, and it proves the hardest technical
risk: reliable notification interception + re-posting.

## 4. Platform & Stack Decisions

| Decision | Choice | Why |
|----------|--------|-----|
| Platform | **Native Android** (skip cross-platform) | Core feature (`NotificationListenerService`) is Android-only; iOS cannot read other apps' notifications at all. Native = best background reliability + performance. |
| Language / UI | **Kotlin + Jetpack Compose** | Modern Android standard. |
| Storage | **Room** (SQLite) | On-device, queryable history — also feeds M3 analytics for free. |
| Background / scheduling | **WorkManager** + the always-running listener service | Profile schedule checks, digest generation. |
| AI provider | **OpenAI** (user's own API key) | User already has a key; dogfooding from day one. |
| AI models | `gpt-4o-mini` for per-notification classification; `gpt-4o` for digests | Mini = cheap/fast for high-volume calls; 4o = quality for the few daily digests. |
| Key storage (M1) | On-device, `EncryptedSharedPreferences` | Single-user dogfood; a backend key proxy comes in M4. |
| Billing (M1) | None | Single user; tiering deferred to M4. |

## 5. Architecture — The Pipeline

Six units, each with one job, each testable in isolation. The AI sits behind an
interface so it can be swapped or gated behind premium later.

```
Android system notification
        │
        ▼
[1] NotificationListenerService  ← reads every posted notification
        │
        ▼
[2] Profile Manager              ← is a profile active right now (schedule)?
        │   no profile active → pass through untouched, just log it
        ▼  profile active
[3] Rule Engine (on-device)      ← match app / sender / contact / keyword, top-down
        │
        ├─ high-confidence match → assign bucket directly (free, instant)
        │
        └─ ambiguous / no rule match → [4] Importance Service (OpenAI gpt-4o-mini)
                                              returns urgent / not-urgent + reason
        │
        ▼
[5] Bucket Executor:
     • Let-through-as-is       → do nothing, original passes (keeps native reply/actions)
     • Let-through-custom-sound→ cancel original, re-post via custom channel (loses native actions)
     • Silence                 → cancel original, re-post silent copy
     • Capture                 → cancel original, store in app inbox only
        │
        ▼
[6] Store (Room)               ← log every notification + decision (inbox, digest, M3 analytics)
```

### The six units

| # | Unit | Responsibility | Depends on |
|---|------|----------------|-----------|
| 1 | **Listener** (`NotificationListenerService`) | Ingest every posted notification; extract app, title, text, sender; hand off | Android system permission |
| 2 | **Profile Manager** | Determine which profile (if any) is active now; expose its rules + default action | System clock, Store |
| 3 | **Rule Engine** | Deterministic top-down matching of a notification against a profile's rules → bucket or "ambiguous" | Contacts (favorites), Store |
| 4 | **Importance Service** *(interface)* | Given an ambiguous notification, return urgent/not + reason. M1 impl = OpenAI; interface allows swap/premium-gate | OpenAI SDK, network |
| 5 | **Bucket Executor** | Execute the bucket: cancel original, build/post notification channels, apply sound/vibration/DND | Android NotificationManager |
| 6 | **Store** (Room) | Persist notifications, decisions, profiles, rules; serve inbox + digest + analytics queries | — |

## 6. Data Model

### Profile
```
Profile
  id: Long
  name: String                     // "Sleep"
  enabled: Boolean
  schedule:
    startTime: LocalTime           // 22:00
    endTime: LocalTime             // 07:00 (wraps past midnight)
    daysOfWeek: Set<DayOfWeek>     // all 7 for Sleep
  order: Int                       // if profiles overlap, lowest order wins
  aiEnabled: Boolean               // send ambiguous notifications to LLM?
  defaultAction: BucketAction      // for notifications matching NO rule: SILENCE | CAPTURE | ASK_AI
```

### Rule
```
Rule
  id: Long
  profileId: Long
  order: Int                       // evaluated top-down within the profile
  conditions: List<Condition>      // AND-ed together
  action: BucketAction             // LET_THROUGH_AS_IS | LET_THROUGH_CUSTOM_SOUND | SILENCE | CAPTURE
  soundConfig: SoundConfig?        // only when action = LET_THROUGH_CUSTOM_SOUND

Condition (one of):
  AppIs(packageNames: Set<String>)
  TitleContains(text: String)
  BodyContainsAny(keywords: List<String>)
  IsFavoriteContact                // sender is in phone favorites
  EmailFromDomain(domain: String, matches: Boolean)  // matches=false → "NOT my company domain"

SoundConfig
  soundUri: Uri
  vibrationPattern: LongArray
  overrideDnd: Boolean             // ring even under system DND
```

### NotificationRecord (logged for every notification we see)
```
NotificationRecord
  id: Long
  packageName: String
  appLabel: String
  title: String
  text: String
  timestamp: Instant
  senderKey: String                // contact name, or email "from" address — feeds M3 analytics
  activeProfileId: Long?           // null if none active (pass-through)
  matchedRuleId: Long?
  decisionSource: RULE | AI | DEFAULT | PASS_THROUGH
  bucket: BucketAction
  aiVerdict: URGENT | NOT_URGENT | null
  aiReason: String?                // short LLM explanation, shown in inbox
  isRead: Boolean
```

### Notes
- `senderKey` and the full decision log are captured **now** even though the
  analytics page is M3 — M3 becomes a pure query over existing history.
- Everything stays on-device. The only data leaving the phone is the notification
  text sent to OpenAI, and only for *ambiguous* notifications when `aiEnabled`.

## 7. Profiles & Rules — Behavior

- **Milestone 1 ships with one profile: "Sleep"** (10pm–7am daily), but the model
  supports many; adding a "Work" profile later is just data.
- When **no profile is active**, the firewall is passive: notifications pass
  through untouched, but are still **logged** (so history/analytics stay complete).
- When a profile **is** active, its rules run **top-down**; first matching rule
  wins and assigns the bucket. No match → the profile's `defaultAction`
  (Silence / Capture / Ask-AI).
- **`Ask-AI` and ambiguous rule outcomes** are the only things that hit the LLM.

### Example Sleep profile (default seed)
| Order | Rule | Action |
|-------|------|--------|
| 1 | Gmail AND EmailFromDomain(mycompany.com, matches=true) | Let-through-custom-sound (soft tone, DND override) |
| 2 | WhatsApp AND IsFavoriteContact | Let-through-custom-sound (loud tone, DND override) |
| 3 | Gmail AND EmailFromDomain(mycompany.com, matches=false) | Capture |
| 4 | BodyContainsAny("sale","% off","offer","unsubscribe") | Capture |
| 5 | WhatsApp (any) | Ask-AI → urgent? Silence : Capture |
| — | *default* | Capture |

## 8. Buckets & Android Technical Constraints (read carefully)

Android lets our service **read and cancel** other apps' notifications, but it
**cannot edit them in place** or reproduce their action intents. This shapes the
buckets honestly:

| Bucket | How it works | Trade-off |
|--------|--------------|-----------|
| **Let-through-as-is** | Do nothing; the original passes | Keeps native quick-reply / action buttons. No custom sound. |
| **Let-through-custom-sound** | Cancel original, re-post via our notification channel with chosen sound/vibration/DND-override | **Loses native quick-reply / actions** (we can't clone the app's intents) |
| **Silence** | Cancel original, re-post a silent copy (no sound/vibration/wake), still visible in tray | Loses native actions; visible whenever you next look |
| **Capture** | Cancel original, store only in the app's own inbox | Doesn't touch you until you open the app |

### The informed-consent toggle (required UX)
Any rule using **Let-through-custom-sound** or **Silence** shows an inline warning
at the toggle, verbatim intent:

> *"Turning this on lets us set a custom sound / silence this notification — but
> you'll lose that app's quick-reply and action buttons on these notifications.
> Leave it off to keep native reply."*

Never a silent surprise; it's always a deliberate per-rule choice.

Custom sounds are implemented as **one notification channel per source/priority
combo** (e.g. `whatsapp_urgent`, `gmail_work`), each with its own sound,
vibration, and DND-override setting.

## 9. AI Layer (OpenAI)

Behind interface `ImportanceService`, M1 implementation = `OpenAiImportanceService`.

### 9.1 Classification — `classify(notification) → Verdict`
- **Model:** `gpt-4o-mini`
- **When:** only for ambiguous rule outcomes / `Ask-AI` default, and only if the
  active profile has `aiEnabled = true`.
- **Input:** app label, sender, title, body text, active profile name.
- **Output (structured / JSON):** `{ urgent: bool, reason: string, confidence: 0..1 }`
- **Reason** is stored and shown in the inbox so decisions are explainable.

Draft prompt (system):
> You are a notification triage assistant. The user is asleep. Decide whether this
> notification is *urgent enough to wake them*. Urgent = time-sensitive and
> personally important (a real person needing them, an emergency, a critical work
> message). Not urgent = social chatter, marketing, newsletters, automated
> updates, anything that can wait until morning. Respond with strict JSON:
> `{"urgent": boolean, "reason": "<one short sentence>", "confidence": 0..1}`.

### 9.2 Digest — `summarize(records) → DigestText`
- **Model:** `gpt-4o`
- **When:** at the active profile's **end time** (e.g. 7am wake), via WorkManager;
  summarizes everything Silenced + Captured during the profile window.
- **Output:** short, skimmable, grouped by category.

Draft prompt (system):
> Summarize the notifications the user missed while asleep. Group by importance
> and type. Be concise and scannable. Lead with anything that might still need
> attention, then bundle the noise (e.g. "12 promotional emails"). Plain text,
> a few short lines.

### 9.3 M1 cost/latency stance
- Latency is not critical (user is asleep); simplicity over optimization in M1.
- Batching, caching, and cost caps are **M2**. In M1 we make one call per
  ambiguous notification and one digest call per profile window.
- **Privacy note:** ambiguous notification text is sent to OpenAI. Acceptable in
  M1 (single user, own key, own consent). A privacy disclosure + opt-in is
  required before this ships to other users (M4).

## 10. Onboarding & Permissions

1. **Notification access** — deep-link the user to
   *Settings → Notification access* to grant `NotificationListenerService`
   (cannot be granted programmatically; must explain why).
2. **Contacts (optional)** — only to detect favorite contacts for rules; app
   works without it (that condition just won't match).
3. **DND override / notification policy access** — needed so "important" rules can
   ring through system DND.
4. **OpenAI API key entry** — a settings screen; stored in
   `EncryptedSharedPreferences`.
5. **Battery optimization exemption** — request so the listener service isn't
   killed (reliability).

## 11. Screens (M1)

- **Onboarding / permissions** flow (the five grants above).
- **Home** — active profile status + recent decisions.
- **Inbox** — Captured (and Silenced) notifications, grouped, with the AI reason
  shown where present; mark read.
- **Profiles** — list; edit Sleep (schedule, AI toggle, default action).
- **Rule builder** — conditions + action + the consent toggle/warning.
- **Settings** — API key, permissions status, battery exemption.
- **Digest view** — the generated wake-up summary.

## 12. Testing Strategy

- **Rule Engine** — pure, deterministic; unit-tested exhaustively against sample
  notifications (the highest-value tests).
- **Profile Manager** — schedule/active-window logic incl. past-midnight wrap and
  overlap ordering.
- **Importance Service** — interface mocked in tests; a thin contract test against
  a fake returns known verdicts. Real OpenAI calls are manual/integration only.
- **Bucket Executor** — instrumented tests on device/emulator for cancel + re-post
  + channel behavior (hardest to automate; partly manual).
- **Store** — Room DAO tests.
- End-to-end interception validated manually on a real device.

## 13. Roadmap After M1

- **M2** — AI batching, cost caps, on-device fallback model, richer per-profile AI.
- **M3** — Sender Analytics + Coach (query the history M1 already stores).
- **M4** — Premium tiering (rules free / AI premium), billing, backend key proxy,
  multi-user privacy disclosures.

## 14. Open Questions For You

1. **App name?** Working title is "Notification Firewall." Want a real name now or later?
2. **Sleep defaults** — is the seed rule table in §7 close to how *you'd* set up
   your own Sleep profile, or would you tweak the buckets/keywords?
3. **Default action** — for a WhatsApp message that AI says is *not* urgent during
   Sleep, do you want it **Silenced** (visible, no sound) or **Captured**
   (hidden until you open the app)? §7 currently assumes Capture.
4. **Digest timing** — one digest at wake (7am), or also a "as-you-fall-asleep"
   confirmation? M1 assumes wake-only.
5. **Contacts source** — is phone "favorites" the right signal for
   `IsFavoriteContact`, or do you want your own in-app allowlist of people?
6. **Key storage** — on-device encrypted is fine for M1 dogfood; confirm you're OK
   with that vs. wanting a tiny backend proxy sooner.
```
