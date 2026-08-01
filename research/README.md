# Attention & Low-Attention-Span Research — Knowledge Base

Foundation research for the **rebrand of Notification Firewall** toward an app that
helps people with **low attention spans / ADHD-style distractibility** — the pattern of
compulsively browsing apps, doomscrolling, and binge-watching for hours.

**Goal of Phase 1 (this KB): understand the problem in depth before designing.**
What actually causes low attention span? What are the mechanisms? What does the
science say works to fix it?

---

## How to read this

Start with the four synthesis notes (they answer "what's going on and why"),
then dip into the bibliography for the primary sources behind any claim.

**Part 1 — The problem (is it real, what causes it):**

| # | Note | Question it answers |
|---|------|--------------------|
| 01 | [`notes/01-causes.md`](notes/01-causes.md) | What is actually causing low attention span? Is it real? |
| 02 | [`notes/02-mechanisms.md`](notes/02-mechanisms.md) | The dopamine / reward-loop machinery apps exploit |
| 03 | [`notes/03-effects-shortform-adhd.md`](notes/03-effects-shortform-adhd.md) | Short-form video, doomscrolling & the ADHD link |
| 04 | [`notes/04-interventions.md`](notes/04-interventions.md) | What actually works to rebuild attention (design > willpower) |

**Part 2 — The foundational science (how humans work, so we build with the grain):**

| # | Note | Question it answers |
|---|------|--------------------|
| 05 | [`notes/05-how-attention-works.md`](notes/05-how-attention-works.md) | How attention is actually structured (networks, EF, working memory, flow) |
| 06 | [`notes/06-self-regulation-motivation.md`](notes/06-self-regulation-motivation.md) | Why willpower fails & what regulates behavior instead (if-then plans, SDT) |
| 07 | [`notes/07-habit-formation-behavior-change.md`](notes/07-habit-formation-behavior-change.md) | How habits actually form & change (Lally, Fogg B=MAP, COM-B, nudge) |
| 08 | [`notes/08-attention-economy-persuasive-tech.md`](notes/08-attention-economy-persuasive-tech.md) | The adversary's playbook (Hook Model, variable rewards) — to counter-design |
| 09 | [`notes/09-interventions-deep.md`](notes/09-interventions-deep.md) | Evidence-graded interventions: what to build, what to avoid |
| 10 | [`notes/10-existing-products-landscape.md`](notes/10-existing-products-landscape.md) | What existing apps do, what's validated, where the gap is |

**Part 3 — Gap-fill (the harder problems):**

| # | Note | Question it answers |
|---|------|--------------------|
| 11 | [`notes/11-binge-watching-autoplay.md`](notes/11-binge-watching-autoplay.md) | The long-session problem: autoplay, stopping cues, the default trick |
| 12 | [`notes/12-durability-retention.md`](notes/12-durability-retention.md) | **Beating the 3-week cliff** — retention data + JITAI (make-or-break) |
| 13 | [`notes/13-clinical-approaches-cbt-act-adhd.md`](notes/13-clinical-approaches-cbt-act-adhd.md) | CBT / ACT-JOMO / adult-ADHD interventions — the content layer |

- **[`bibliography.md`](bibliography.md)** — every source, with citation, key finding, link, and download status.
- **[`sources/papers/`](sources/papers/)** — downloaded open-access PDFs.
- **[`sources/articles/`](sources/articles/)** — reserved for saved article text.

---

## The one-paragraph summary (so far)

The famous "8-second attention span" stat is **fabricated** — there is no credible
science behind it. But a **real** problem sits underneath the myth: sustained attention
on screens has measurably shrunk (Gloria Mark's lab: from ~2.5 min in the mid-2000s to
~47 seconds today), interruptions are enormously costly to recover from (~23 min to
fully refocus), and heavy short-form-video / social use is consistently associated with
weaker sustained attention, working memory, and inhibitory control. The mechanism is a
**variable-reward loop** (the same reinforcement schedule as slot machines) delivered by
infinite scroll and algorithmic feeds. The link to ADHD symptoms is real but modest and
**bidirectional** — distractible people are drawn to these apps *and* the apps worsen the
symptoms. Crucially: **abstinence / "dopamine detox" barely works; environmental design
changes do.** Notification management, friction, grayscale, and getting the phone out of
reach persist where willpower fails. That last finding is the thesis of the whole product.

---

## The evidence-backed design stack (what the science says to build)

Carry into the brainstorming/design phase. Every item traces to a note:

1. **Design the environment, not the willpower.** Ego-depletion largely failed to replicate;
   willpower is unreliable. Raise *Ability* (Fogg), intervene on *Opportunity* (COM-B).
   Change what's easy, don't nag. → notes 06, 07.
2. **Friction + deliberate-choice prompt at the moment of temptation is the keystone.** The
   One Sec PNAS RCT is the best evidence in the whole field: a breath + delay + "continue?"
   → 36% abandon, and it didn't fully fade. The *option to dismiss* mattered most. → note 09.
3. **Operationalize implementation intentions.** "If [cue], then [response]" plans have
   d≈0.65 across 600+ tests. The existing profiles/rules engine is *already* an if-then
   engine — repoint it. → notes 06, 10.
4. **Protect unbroken blocks (flow), don't count minutes.** One interruption = ~23 min lost.
   The win is *absorbed, continuous* time. → notes 01, 05.
5. **Counter-design the Hook, step by step.** Strip external triggers, add friction to the
   Action, make rewards predictable, lower the cost of leaving. → note 08.
6. **Offer a restorative substitute**, not just a block (exercise/breathe/step away have real
   evidence; another feed does not restore). → notes 05, 09.
7. **Preserve autonomy** (nudge + override, never jail) to avoid reactance/uninstall. → note 06.
8. **Forgive lapses; design for a 60–250-day horizon.** Habits take ~66 days (not 21); every
   competitor dies at the **3-week novelty cliff.** → notes 07, 10.
9. **Optimize for behavior change, NOT app engagement — and build a JITAI.** The durability
   answer: app retention ≠ behavior change (~71% quit apps in 90 days; ~2% sustain). Deliver
   value in the *background* and intervene *only at vulnerable moments* (JITAI) so relevance
   beats novelty. Never chase DAU — that's the enemy's KPI. → note 12.
10. **Two layers: environment + content.** Durable base = environmental (friction, defaults,
    JITAI). Meaning layer = CBT skills (self-monitoring, if-then) + **ACT/JOMO values prompts**
    that give freed attention a *destination*, not just a wall. → note 13.
11. **Handle the long session too, not just the scroll.** Restore stopping cues / decision
    points and flip autoplay's default (stopping = default). → note 11.
12. **Serve the behavior, not a diagnosis.** ADHD link is real, modest, bidirectional. → note 03.
13. **Refuse the fake stuff.** No "dopamine detox" branding (can't fast dopamine); no
    brain-training claims (far transfer doesn't happen). → notes 02, 09.

**Strategic fit:** this is a *repointing*, not a rewrite. Notification Firewall already has
profiles+rules (→ if-then engine), a notification pipeline (→ trigger-stripping + friction),
DND control (→ flow/sleep protection), and analytics (→ externalizing internal triggers).
The rebrand adds the two proven pieces the field underuses — **moment-of-temptation friction
with autonomy**, and **restorative substitution** — engineered to survive past week 3. → note 10.

_Last updated: 2026-08-01. Living v3 (Part 1 problem + Part 2 foundational science + Part 3
gap-fill: binge/autoplay, durability/JITAI, CBT/ACT/adult-ADHD, primary reward neuroscience).
See bibliography "Remaining coverage gaps" for the lower-priority items still open._
