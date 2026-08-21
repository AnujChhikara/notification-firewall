# Still *(working title)*

An evidence-based program that retrains your attention. Still diagnoses *your*
specific distraction problem, guides you through a personalized track, and
enforces the change automatically through an on-device firewall — so improvement
doesn't depend on willpower.

> Formerly **Notification Firewall** — the firewall app is now the enforcement arm
> of a coaching program (see the pivot in the master design spec).

## Status

**Phase 1 — Core loop** in progress. Design docs:

- Master vision: [`docs/superpowers/specs/2026-08-01-attention-platform-master-design.md`](docs/superpowers/specs/2026-08-01-attention-platform-master-design.md)
- Phase 1 spec: [`docs/superpowers/specs/2026-08-21-still-phase1-core-loop.md`](docs/superpowers/specs/2026-08-21-still-phase1-core-loop.md)
- Phase 1 plan: [`docs/superpowers/plans/2026-08-21-still-phase1.md`](docs/superpowers/plans/2026-08-21-still-phase1.md)
- Research foundation: [`research/`](research/) (13 synthesis notes + bibliography + papers)
- Clickable UI prototype: [`ui-mock/`](ui-mock/) (`npm run dev` inside)

**Built so far:** the on-device notification firewall (profiles/rules →
let-through / silence / capture buckets, OpenAI importance + digest) and its
reliability layer (keep-alive foreground service, exact-alarm scheduling,
self-healing, health alerts). This becomes the enforcement arm of Still.

## Stack

Native Android · Kotlin + Jetpack Compose · Room · WorkManager · Hilt · Firebase
(Auth + Firestore, behind a swappable backend interface) · OpenAI (digest)

## Principles (short form)

Design the environment, not willpower · friction + deliberate choice at the
moment of temptation · value accrues in the background · optimize for behavior
change, never DAU · nudge + override, never hard jail · forgive lapses · no
shame, ever · never build our own addictive loop.
