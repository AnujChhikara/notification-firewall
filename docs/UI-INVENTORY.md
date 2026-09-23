# UI inventory — screens, controls, states

Reference for designing mockups. Built from the source at `c50f068`.
Quoted strings are the copy currently in the build.

## The core model

Every notification lands in exactly one bucket:

| Bucket | What happens | Notes |
|---|---|---|
| **RING** | Breaks through DND, reaches the user normally | From an OTP, a VIP sender, or a high importance score |
| **SILENCE** | Stays on the phone silently, visible in the Inbox | Only bucket that keeps the original app's buttons (reply, mark read) |
| **DROP** | Cancelled outright, still logged in the Inbox | Only the user's block list can cause this — the model never can |

Each decision carries a **source** badge:

`OTP` passcode detected, rings instantly, offline, ahead of every other rule ·
`VIP` on the always-ring list ·
`BLOCK` on the block list ·
`CACHE` identical notification already judged ·
`JEV` scored by the model over the network (the only step that leaves the phone) ·
`PENDING` waiting on a verdict ·
`EXPIRED` text purged by retention before a verdict arrived ·
`LEGACY` judged by an older scoring model

## Navigation

Four primary tabs in a bottom bar: **Wall · Inbox · Ask · Settings**.
Onboarding and Keys are pushed full-screen, not tabs.

---

## 1. Onboarding `/onboarding`

Permission-first setup, shown once. Nothing works until these are granted.
Header: "Set up the wall — a few grants, one at a time. Each one explains why it's needed."

**Grant steps** (one card each, with a reason):
- **Notification access** — required. "The wall reads every notification as it arrives so it can decide whether to let it ring. Without this, nothing works."
- **Do Not Disturb access** — required. "Calls always come through — from anyone, including repeat callers. Alarms still go off."
- **Post notifications** — required on Android 13+ so it can re-post what it lets through.
- **Battery optimisation exemption** — optional. "The app still works without this, but less dependably." → `Grant exemption` / `Skip`

**Final step:** Jev API key, masked, with `Show`/`Hide`, then `Save and continue`.
Note: "It's stored encrypted on-device and sent only to api.typesafe.ai."

**States:** each step as not-granted / granted-collapsed / granted-then-revoked. User can leave and return mid-flow.

---

## 2. Wall — home `/wall`

The daily screen. Eyebrow: "HOME · YOUR NOTIFICATION SHIELD".

**Arm control** (the hero):
- `ARMED` — "Protection is active" / "Important alerts can still reach you. Everything else stays quiet." / tap hint "Tap to let notifications through"
- `DISARMED` — "Notifications pass through" / "Arm the wall when you want interruptions filtered automatically." / "Tap to arm the wall"

**Health banner** (three states):
- Broken — "Notification access is off" + "The wall can't protect you until this is granted." + `Open notification access settings`
- Degraded — "Do Not Disturb access is off" + `Open Do Not Disturb access settings`
- Healthy — no banner

**Break-glass:**
- Closed: `Let everything through for 30 min` (duration from Settings)
- Open: "Everything is getting through · 22 min left" + "Break-glass is open. The wall re-arms itself automatically." + `Re-arm now`
- Counts down live to "0 min left"

**Today:** three counters — Rang / Silenced / Dropped. Empty: "Nothing yet today"

**Digest card:** "Yesterday's digest" — one written sentence plus a "Worth a look" list. Absent when no digest exists or digests are off.

**Quick actions:**
- Review notification inbox — "See what rang, was silenced, or was dropped"
- Ask about your notifications — "Get a quick answer from your assistant"

---

## 3. Inbox `/inbox`

The audit trail. Every decision, reviewable and correctable.

**Filters:** `All` `Rang` `Silenced` `Dropped`

**Row:** app icon + label · sender · title · body preview · time · bucket marker · source badge.
Score line where one exists: "importance 72% · confidence 88% · bias +0.15".
Purged row shows "Content expired" instead of title/text.

**Row actions:**
- Correct this one: `Ring this` / `Silence this` — teaches a per-sender nudge
- Standing rules: `Always ring this sender` · `Block this sender` · `Clear learned bias`
- Confirmation snackbar: "Noted — WhatsApp will be judged more kindly" + `Undo`

**States:** empty · loading · long scroll · row mid-undo

---

## 4. Ask `/ask`

Two things in one tab. Header: "Your history, answered on this phone".

**Stat cards** (computed on-device, no key needed):
- Last 7 days — "41 of 380 never reached your screen."
- By hour — 24-bar chart, "When notifications arrive, midnight to midnight."
- Machines vs humans
- Top offenders — ranked apps
- Noise ratio
- Correction rate — "The lower this is, the more the numbers above are worth."

**Chat:**
- Input "Ask about your notifications…" + `Ask`, hint "Try: which apps interrupted me most last week?"
- Working: "Thinking…"
- `Show query` / `Hide query` — reveals the SQL that ran
- Toggle `Include message content in this answer` → header flips to "Including message content"

**Privacy note** (permanent, under the chat): "Your messages stay here. What is sent is the question, the table and column names, and the counts that come back — which can include app names, but never a title, a body or a sender."

**Blocked / empty:**
- No key: "Chat needs an OpenAI key" + `Add a key in Settings`. Stats above still work.
- "Nothing has arrived yet." · "Nothing has been silenced yet." · "Nothing judged yet — no accuracy to report."

---

## 5. Settings `/settings`

Five sections, one scroll. Subtitle: "Tune the wall".

**Behaviour**
- Sensitivity slider, `Relaxed` ↔ `Strict`, reads "Rings at 0.62 and above"
- Live preview under it: "7 more would have rung you today" / "4 fewer would have rung" / "No change to today's notifications"
- OTP fast path toggle — "One-time codes always ring, instantly, with no network call."
- Break-glass duration, 5–120 min — "How long an emergency window stays open once you trigger it."
- Digest time (picker) or `Never`
- Always ring (VIP list) — `Add app…` searchable picker, per-row `Remove`. Empty: "No one is on the VIP list yet."
- Blocked — same shape. Empty: "Nothing is blocked yet."
- Learned corrections — "12 sender(s) with a learned nudge" / "No corrections learned yet" + `Reset all learning`

**Appearance** — theme mode. Design light and dark for every screen.

**Health** — six permission rows, each pass / fail-with-fix-button / "Checking…":
Notification access · Post notifications · Do Not Disturb access · Battery optimization exempt · Exact alarms allowed · Contacts (for VIP detection)

**Data**
- History — "1,204 notification(s) stored"
- Text retention — "Notification text is purged after 30 day(s)" / "kept forever"
- Verdict cache — "318 cached verdict(s)" + `Clear`, or "Empty"
- Export history → `notification-wall-export.json`, with opt-in checkbox "Include titles and message text in the export" (off by default — export is metadata-only)
- Delete all history — dialog "Delete all history?", type `DELETE` to confirm, then `Delete permanently`

**Keys** — link row: "Jev (required) and OpenAI (optional), stored encrypted", status "Jev API key configured"

---

## 6. Keys `/keys`

Pushed from Settings. Two keys with different consequences.

**Jev — required**
- Field "Paste key…", masked, `Show`/`Hide` · `Save` · `Clear`. Saved shows masked: "Stored: jev-••••3f9a"
- "The wall cannot classify notifications without this key."
- After save: "Restart the app for the new key to reach the live classifier — it's read once when the app process starts."

**OpenAI — optional**
- Hint "sk-… (optional)". Powers the Ask chat and the digest sentence.
- "Without it, the Ask tab's numbers still work and the digest falls back to a locally written summary."

**Diagnostics**
- `Test Jev key` → "Testing…" → "Round trip worked — importance score 0.41" or "Test failed" + reason. Guard: "Enter a key first"
- `Test notification` — posts a sample through the whole pipeline

---

## Surfaces outside the app

Most days the user never opens the app. These need mockups too.

| Surface | What it shows | States |
|---|---|---|
| **Quick Settings tile** | Arms/disarms from the shade. Labelled "Notification Wall" | Active · Inactive · Unavailable (greyed when a permission is missing) |
| **Daily digest** | One notification at the chosen hour: a sentence, the totals, what's worth a look | Model-written · local fallback (no OpenAI key) · suppressed when off |
| **Health alert** | "Tap to fix" notification, fired only when genuinely broken. Degraded stays a Home banner and never interrupts | Broken only |
| **Re-posted notifications** | Anything let through is re-posted by this app — carries title, text, icons but *not* the original app's reply/mark-read buttons | Worth making a re-post recognisable |

---

## Cross-cutting states

Settle these once instead of redrawing them six times.

| State | Where | Needs |
|---|---|---|
| Armed / disarmed | Wall hero, QS tile | One visual language shared across both surfaces |
| Break-glass open | Wall, tile | Live countdown; urgency that doesn't read as an error |
| Permission missing | Wall banner, Settings health, Onboarding | Two tiers — broken (blocks core job) vs degraded (works, less reliably) |
| No API key | Ask, Settings, Keys | Required (Jev — wall is dead) vs optional (OpenAI — feature is dimmer) |
| Empty | Inbox, Ask, VIP list, block list, corrections | Six distinct empties, each saying what would fill it |
| Content expired | Inbox, digest | Reads as intentional privacy, not data loss |
| Destructive confirm | Delete history, reset learning, clear cache | Escalating friction; type-to-confirm at the top |
| Undo | Inbox corrections | Snackbar that survives a scroll |

---

## Two constraints to design around

**Nothing vanishes silently.** DROP exists only because the user asked for it, and
dropped notifications still appear in the Inbox. The model is never allowed to
produce a verdict the user can't find and review — a wall you can't audit is a
wall that gets switched off.

**The privacy line is a visible feature, not fine print.** Titles, bodies and
senders never leave the phone by default. The Ask tab shows the query it ran, the
export defaults to metadata-only, the digest travels as counts plus an app name.
Each is a place the interface should show its work rather than hide it.

---

A clickable prototype already exists in `ui-mock/` (`npm run dev` inside it) —
it may be stale relative to this inventory.
