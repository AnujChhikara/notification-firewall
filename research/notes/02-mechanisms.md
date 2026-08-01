# 02 — The dopamine / reward-loop machinery

Why is it *so hard to stop*? Because the apps are built on the most powerful
reinforcement schedule psychology knows.

## The variable-reward loop
Every feed refresh, like, and notification is an **unpredictable reward**. Unpredictable
rewards on a **variable-ratio schedule** are exactly what makes slot machines addictive:
the brain can't predict *which* pull pays off, so it keeps pulling. Applied to phones:

```
   anticipation  →  brief reward  →  renewed anticipation  →  ...
   (pull to refresh)   (maybe a like)      (check again)
```

Key insight from the neuroscience framing: the **anticipation phase activates the reward
system harder than the content itself does.** You're not chasing the payoff — you're
chasing the *maybe*. This is why "just checking" is compulsive even when the content is
boring.

Design features that weaponize this:
- **Infinite scroll** — removes the natural stopping cue that a "page" or "end" provided.
- **Pull-to-refresh** — a literal slot-machine lever.
- **Algorithmic delivery** — optimizes the reward's unpredictability and potency per user.
- **Notifications** — externally-triggered anticipation, on someone else's schedule.

## The primary neuroscience: reward *prediction error* (why "maybe" hooks you)
Schultz's foundational work (Hollerman & Schultz 1998; downloaded) explains the anticipation
point mechanistically. Dopamine neurons (VTA/substantia nigra) encode a **reward prediction
error** — the gap between expected and actual reward:
- Reward **better than expected** → dopamine **burst**.
- Reward **exactly as expected** → **no** response.
- Reward **worse than expected** → dopamine **dip**.
- **Unpredicted rewards drive far stronger bursts than predicted ones.**

This is *why* variable-ratio schedules are so potent: unpredictability guarantees a steady
stream of positive prediction errors, and over learning the dopamine signal shifts to the
**cue/anticipation** ("pull to refresh") rather than the outcome. Predictability kills the
signal — which is the design lever: **make the reward boring/predictable and the burst
collapses.**

## What the brain data actually says (be precise here)
The popular "apps flood your brain with dopamine" line is **too simple.** The best primary
evidence is more interesting:

**Westbrook et al. (2021, _iScience_)** — [18F]-DOPA PET in 22 adults with ~32 days of
passive phone logging. A higher **proportion of social app use** correlated with **LOWER
dopamine synthesis capacity in the putamen** (β=−1.5×10⁻³, p=1.3×10⁻⁴; explaining 55% of
variance). The effect was **selective to social use**, not screen time in general.

Two things to take from this:
1. Heavy social use tracks with a **measurably altered dopamine system** — the link to
   brain chemistry is real, not hand-waving.
2. But it's an *association* in a small cross-sectional sample, and the direction ("apps
   change dopamine" vs "dopamine profile predisposes heavy use") is unresolved. **Don't
   overclaim.**

## Why "dopamine detox" is the wrong frame
You cannot "fast" or "reset" dopamine — it's a continuous, essential neurotransmitter.
"Dopamine detox" is a pop-science oversimplification (see note 04). What actually helps is
reducing exposure to **hyper-stimulating variable rewards** and rebuilding tolerance for
ordinary, lower-stimulation activity — which is an *environmental* change, not a
neurochemical cleanse.

## Product implication
- Attack the **loop's structure**: reintroduce stopping cues, add friction to the
  "pull the lever" moment, break the anticipation→check reflex.
- The enemy is **variable-reward, algorithmically-fed content**, not "the phone."
- Frame around *changing the environment*, never around "detoxing dopamine" — the science
  doesn't support that language and informed users will bounce off it.

→ Next: [03 — short-form video & ADHD](03-effects-shortform-adhd.md)
