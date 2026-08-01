# 12 — Durability: beating the 3-week cliff (the make-or-break)

Every competitor works for ~1 week and dies by ~3 (note 10). This note is about the one
problem that decides whether the product actually helps or joins the graveyard.

## The scale of the attrition problem (sobering)
From the mHealth engagement/attrition literature:
- **~71% of app users disengage within 90 days.**
- Up to **98%** use an app only briefly before dropping off or dropping below effective use.
- In some trials only **~2%** sustained the continuous use expected to produce real outcomes.
- **Gamification, notifications, reminders, and rewards have NOT consistently reduced dropout.**
  (Directly relevant: Forest-style gamification is *not* a durability solution — note 10.)

If we design assuming users will open our app daily forever, we will fail like everyone else.

## The reframe that changes everything ⭐
The strongest insight in this literature:

> **"Frequent or indefinite engagement with the intervention may not be required to support
> sustained engagement with the _behavior_."**

App **retention ≠ behavior change.** These are different goals, and conflating them is the
classic trap. Our product does **not** need to be a destination the user visits daily — it
needs to **change the environment and the habit** so the *behavior* sticks even as active
app-use fades. This is the opposite of the attention-economy KPI (note 08): we should *want*
the user to need us less over time.

Two consequences:
1. **Success metric = sustained behavior change (fewer compulsive opens, protected blocks),
   NOT daily active use / session count.** Measuring DAU would make us optimize for the wrong
   thing — the very thing we're fighting.
2. Much of the value should run **in the background** (rules, friction, trigger-stripping,
   DND) so it keeps working *without* requiring engagement — which is exactly where the
   existing Notification Firewall pipeline already lives.

## The research-backed durability mechanism: JITAI
**Nahum-Shani et al. (2014 framework; 2018, _Annals of Behavioral Medicine_ — downloaded).**
**Just-In-Time Adaptive Interventions**: deliver *the right type/amount of support, at the
right moment*, by **adapting to the person's changing internal + contextual state** in real
time. Core components: decision points, tailoring variables (context/state), intervention
options, and decision rules; plus the notion of **states of receptivity and vulnerability.**

Why JITAI beats static apps on durability:
- **Novelty fades; relevance doesn't.** A fixed feature gets ignored once it's predictable
  (the 3-week cliff). An intervention that fires *only when you're actually vulnerable*
  (right cue, right context) stays relevant because it's not constant noise.
- **Avoids habituation/alarm-fatigue** — the reason notification-based nudges stop working.
- **Matches receptivity** — intervene when the user *can* act, skip when they can't.

**Fit with our app:** Notification Firewall already senses context (active profile, time,
which app, notification stream) and already has an execution pipeline. That is *the sensing +
actuation substrate a JITAI needs.* The rebrand can make it a genuine JITAI for attention:
detect the vulnerable moment (opening a target app during a focus/sleep profile, an 11pm
binge, a doomscroll streak) → deliver the minimal right intervention (friction, breath,
values prompt, block) → adapt to whether it worked.

## Other durability levers from the KB
- **Design for the 66–254-day habit horizon** (note 07); **forgive lapses** (missing a day
  doesn't derail — don't punish streak breaks).
- **Preserve autonomy** (note 06) → less reactance → less uninstalling.
- **Background/environmental value** (defaults, trigger-stripping) keeps working with zero
  engagement.
- **Substitution over restriction** (notes 07/09) — a filled void is more durable than a
  policed one.
- **Low cognitive load / simple** — attrition research: time-consuming, high-load apps get
  dropped; "basic, easy-to-use" is desired.

## Product implications (the durability doctrine)
1. **Optimize for behavior change, NOT app engagement.** Never chase DAU; that's the enemy's game.
2. **Build a JITAI**, not a static toolbox: intervene at vulnerable moments, adapt, stay quiet
   otherwise. Leverage the existing context-sensing pipeline.
3. **Deliver value in the background** so effectiveness survives disengagement.
4. **Forgive lapses; keep it low-load; preserve autonomy** — the anti-cliff trio.
5. **Measure the right thing:** compulsive-open rate, protected/uninterrupted time, sleep
   window respected — outcomes, not visits.

→ Next: [13 — clinical approaches (CBT, ACT, adult ADHD)](13-clinical-approaches-cbt-act-adhd.md)
