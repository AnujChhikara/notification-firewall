# 06 — Self-regulation, willpower & motivation

The single most important design lesson: **do not build on willpower.** The science says
it's the wrong foundation.

## "Willpower as a muscle that runs out" — largely FAILED to replicate
- Ego-depletion theory (Baumeister 1998): self-control is a limited resource depleted by
  use. Hagger's 2010 meta-analysis (198 tests) reported a medium effect (d≈0.62).
- **But** the 2016 pre-registered **23-lab replication** (led by Hagger & Chatzisarantis,
  the same meta-analysts) found **~no effect.** The phenomenon is now considered unreliable;
  there's a "conceptual crisis," not just a replication one.
- **Better-supported reinterpretation:** what looks like "depletion" is a **shift in
  motivation and attention** — after effort, people become *less willing* to allocate effort
  to a second task, and their attention drifts toward rewards/temptations. It's motivational,
  not a fuel gauge.

**Takeaway:** you can't count on users "having enough willpower," but you also shouldn't
model failure as "they ran out." Failure is **motivation + attention drifting toward the
easier reward.** Change the environment so the easy path *is* the desired one.

## Present bias / temporal discounting
Humans **steeply discount future rewards** vs immediate ones (hyperbolic discounting). A
feed offers an instant hit; "focus now for a payoff later" loses the trade every time on
raw motivation. This is *structural*, not a character flaw. → The design must make the good
choice **immediately** easier/cheaper, not rely on future payoff.

## Self-Determination Theory (Deci & Ryan) — motivation that lasts
Durable behavior change requires **intrinsic** motivation, supported by three needs:
- **Autonomy** — the user feels it's their choice, not coercion.
- **Competence** — they feel effective/capable.
- **Relatedness** — connection.

**Design consequence:** heavy-handed *blocking/lockout* undermines autonomy and breeds
reactance (people fight the tool, then uninstall — see the 3-week fade, note 09). Tools that
**inform and nudge** (preserve autonomy) and make the user feel *competent* at their own
goals outlast tools that police. Friction that the user *chose and can override* > hard
blocks they resent.

## What DOES work: implementation intentions ("if-then" plans)
Gollwitzer & Sheeran — the most robust self-regulation tool we found (downloaded):
- Format: **"If [situation/cue], then I will [response]."**
- Meta-analysis of 94 tests: **d ≈ 0.65** (medium-large) on goal attainment; 2024 update
  aggregates 642 tests.
- Mechanism: it **pre-delegates control to the environmental cue**, so the good response
  fires *automatically* when the cue appears — bypassing in-the-moment willpower entirely.
- Works especially for *breaking bad habits* and shielding ongoing goals from temptation.

**This is huge for the product.** "If I open Instagram out of habit, then I take one breath
and decide" is exactly the One Sec mechanism (note 09), and it's exactly an implementation
intention operationalized in software. We can let users *author* if-then rules and have the
app enforce the "then" at the cue.

## Product implications
- **Never rely on willpower/motivation being high.** Assume it's low at the moment of temptation.
- **Exploit present bias in the user's favor:** make the desired action the immediately
  easiest one; add tiny immediate friction to the tempting one.
- **Preserve autonomy** (nudge/inform, allow override) to avoid reactance and abandonment.
- **Operationalize implementation intentions**: let the user pre-commit if-then rules; the
  app supplies the environmental cue-detection + the automatic "then."

→ Next: [07 — habits & behavior change](07-habit-formation-behavior-change.md)
