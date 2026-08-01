# 11 — Binge-watching & autoplay (the long-session problem)

Short-form scroll (note 03) is one failure mode; **long binge sessions** (shows, long-form
video) are the other — and the user named both. Different mechanism, same design lesson.

## Autoplay reverses the default — and that's the whole trick
The core weapon isn't the content, it's **autoplay + the countdown**:
- The next episode starts in **5–10 seconds unless you actively stop it.** This **reverses the
  decision**: instead of choosing to *continue*, you must choose to *stop*. (Classic **default
  effect**, note 07 — the default is what most people do.)
- A few seconds is too little time for a rational decision → **loss of control** over session
  length. The natural **stopping cue** (end of episode) is deliberately removed.

## The evidence
- **Netflix autoplay experiment (2025, ACM/arXiv; downloaded):** disabling autoplay
  **reduced daily time spent and session length.** Direct causal evidence that removing the
  stopping-cue-remover shortens sessions.
- **Diary study (IADT):** autoplay associated with more binge-watching and *lower* mindful
  attention awareness.
- Predictors of *problematic* binge-watching: **impulsivity** and **motivation to escape**
  (mood regulation) — i.e., it's often avoidance-coping, not enjoyment. (Ties to ACT/values,
  note 13.)
- Binge-watching activates the same reward / anticipation / loss-of-control neural pathways
  as other compulsive behaviors (not a clinical addiction, but behaviorally adjacent).

## Why this matters distinctly from scroll
- **Time-blindness in long sessions:** the harm is the *duration* and the lost hours/sleep,
  not rapid switching. The "23-min refocus" framing (note 01) is less relevant here; the
  **stopping-cue + default** framing is the key.
- The intervention is about **restoring a decision point** and a stopping cue, not adding
  friction to *opening* (the app is already open).

## Product implications
1. **Restore stopping cues + decision points** in long sessions: episode/segment boundaries,
   time-elapsed checkpoints, an explicit "continue?" beat (the inverse of autoplay's default).
   This is the One Sec friction primitive (note 09) relocated from *app-open* to
   *session-continuation*.
2. **Flip the default:** make *stopping* the default, *continuing* the deliberate choice —
   exactly the reverse of what platforms do.
3. **Name the escape motive:** if bingeing is escape-coping, a values/JOMO prompt (note 13)
   at the checkpoint ("what did you actually want tonight?") targets the real driver.
4. **Sleep protection** (note 09) is especially relevant — bingeing eats the sleep window.

Note: on Android, the app can't inject buttons *inside* Netflix/YouTube. Levers available:
usage-time checkpoints, notifications/overlays at thresholds, DND-style interruption of the
session, or nudges at app-open with a pre-set session budget. (Feasibility for Phase 2.)

→ Next: [12 — durability (beating the 3-week cliff)](12-durability-retention.md)
