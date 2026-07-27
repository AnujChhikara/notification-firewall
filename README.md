# Notification Firewall *(working title)*

A native Android app that sits between your apps' notifications and your
attention — a smarter Do Not Disturb.

Instead of the OS's single blunt DND dial, you define **profiles** (e.g. *Sleep*,
10pm–7am) with **rules** that route each notification into a bucket:

- **Let through** — full sound, breaks through DND (with per-source custom sounds)
- **Silence** — visible in the tray, no sound/vibration/wake
- **Capture** — hidden in the app's own inbox until you look

For the calls rules can't make ("is this message actually *urgent*?"), an LLM
decides, and writes a plain-language digest of what you missed while asleep.

## Status

Early design. See the Milestone 1 design overview:
[`docs/superpowers/specs/2026-07-27-notification-firewall-design.md`](docs/superpowers/specs/2026-07-27-notification-firewall-design.md)

## Stack (planned)

Native Android · Kotlin + Jetpack Compose · Room · WorkManager · OpenAI
