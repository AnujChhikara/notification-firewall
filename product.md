# Notification Firewall

## Product overview

Notification Firewall is an Android app that protects the user's attention by deciding which notifications deserve to interrupt them. Instead of treating every notification equally, it places each incoming notification into one of three outcomes:

- **Ring** — important notifications are allowed to alert the user.
- **Silence** — low-priority notifications are kept quiet but remain available in the app.
- **Drop** — notifications from apps the user explicitly blocks are removed.

The product is not intended to be another notification feed or an engagement loop. Its purpose is to reduce unnecessary interruptions while keeping important, urgent, and human communication accessible.

The app is currently the notification-control layer of a broader attention product tentatively called **Still**. This document describes the functionality that exists in the Android app today.

## Core product principles

- Protect attention without making the user unreachable.
- Let explicit user choices override automated decisions.
- Fail safely: if classification is unavailable, keep the notification quiet and retain it for later review rather than losing it.
- Keep common decisions fast through local rules and cached results.
- Make every automated decision inspectable and correctable.
- Store and transmit as little notification content as possible.
- Give the user control over retention, exports, credentials, and deletion.

## How notification filtering works

When the wall is armed, the app observes incoming Android notifications and evaluates them in this order:

1. **OTP fast path** — one-time passcodes can ring immediately without a network request.
2. **Never-show rules** — apps or senders on the user's block list are dropped.
3. **Always-ring rules** — apps or senders on the VIP list ring.
4. **Verdict cache** — a safe, reusable decision for matching notification content can be applied instantly.
5. **Jev classification** — notifications that still need a decision are evaluated for importance, category, urgency, and other useful signals.

The user's sensitivity threshold and learned sender correction are applied to the model's importance score. Notifications above the threshold ring; the rest are silenced.

Only the user's explicit block rules can produce the **Drop** outcome. Automated classification does not permanently discard a notification.

If the classifier is offline or unavailable, the notification is silenced, stored, and marked for later classification. A background worker fills in the missing verdict when the service becomes available again, without replaying an old interruption.

## Main product areas

### Home / Wall

The Home screen is the control center for the notification wall. It provides:

- A clear **Armed** or **Disarmed** state.
- A primary control for arming or disarming the wall.
- A summary of today's notifications: rang, silenced, and dropped.
- A temporary **break-glass** action that lets everything through for a configured duration.
- Automatic re-arming when the break-glass window ends.
- A manual **Re-arm now** action while break-glass is active.
- A card containing the most recently generated daily digest.
- Clear recovery actions when Notification Access or Do Not Disturb access is missing.
- Quick access to Inbox and Ask.

The armed state uses Android's Do Not Disturb capability as part of enforcing quiet behavior. Calls remain available according to the app's configured interruption policy.

### Inbox

Inbox is the review and correction area for notification history. It provides:

- A chronological history of handled notifications.
- Filters for **All**, **Rang**, **Silenced**, and **Dropped** results.
- Search across stored notification information.
- The app, sender, timestamp, outcome, and available explanation for each record.
- A detail view for inspecting how and why a notification was classified.
- User corrections such as making an app or sender always ring, never show, or clearing learned bias.
- Visual handling for notification content that has expired under the retention policy.

Corrections made from Inbox feed the learning system so future notifications from that sender can be nudged toward the user's preference.

### Ask

Ask helps users understand their notification behavior. It has two layers:

#### On-device insights

These work without an API key or internet connection:

- Noise ratio for the last seven days.
- Apps responsible for the most quieted notifications.
- Machine-generated versus human-written notification share.
- Notification volume by hour of day.
- Correction rate, which acts as an honesty/accuracy metric for the wall.

#### Natural-language chat

With an OpenAI API key, the user can ask questions about notification history in natural language. The model produces a constrained query, the phone validates and executes it locally, and the resulting aggregate data is used to answer the question.

By default, notification titles, bodies, and senders are not sent as part of this flow. The user can explicitly enable message content for an individual answer when needed. Generated SQL can be viewed for transparency.

### Settings

Settings is the app's control room. It includes:

- **Sensitivity** — adjust the importance threshold and preview how the change would affect today's notifications.
- **Always ring** — manage VIP apps and senders.
- **Never show** — manage explicitly blocked apps and senders.
- **Learned corrections** — inspect, clear, or reset learned sender bias.
- **OTP fast path** — enable or disable instant local handling for one-time codes.
- **Digest time** — choose when the daily notification summary should arrive.
- **Break-glass duration** — configure how long temporary pass-through remains active.
- **Appearance** — choose system, light, or dark theme behavior.
- **Keys** — securely configure and test external service credentials.
- **Data retention** — control how long notification text is retained.
- **Export** — export notification history as JSON, with an explicit choice about including message content.
- **Empty verdict cache** — clear reusable classification decisions without deleting notification history.
- **Delete all history** — permanently remove stored notification records after typed confirmation.
- **Health** — inspect required permissions and service status, with direct links to Android settings when attention is required.

### APEX / API keys

The Keys screen manages credentials used by optional intelligent features:

- A **Jev key** for notification importance classification.
- An **OpenAI key** for natural-language Ask and digest prose.
- Masked key display so saved secrets are not exposed in normal use.
- Save, remove, and test actions for each supported key.
- A Jev/APEX key test that reports success or a readable error instead of crashing the app.

Keys are optional for basic history, local insights, explicit rules, and core settings. Features that need a missing key explain what is required and link the user to the relevant setup screen.

### Daily digest

The app can generate a scheduled daily summary containing:

- Counts of notifications that rang, were silenced, or were dropped.
- A brief summary headline.
- A small “worth a look” section derived from stored history when appropriate.

The digest remains useful without an OpenAI key by falling back to locally generated summary text. Tapping the digest notification opens Inbox. The latest digest can also appear on Home.

### Android quick controls and reliability

The app includes supporting Android integrations:

- A Quick Settings tile for arming and disarming the wall.
- Startup recovery after a device reboot.
- A keep-alive service for notification handling reliability.
- Periodic health and maintenance checks.
- Reconciliation of wall state, Do Not Disturb state, and break-glass expiration.
- Background reclassification of notifications that arrived while the classifier was unavailable.

## Permissions and onboarding

Onboarding explains and guides the user through the permissions needed for the product to work:

- **Notification access** to observe and manage incoming notifications.
- **Do Not Disturb access** to enforce the armed state.
- **Notification permission** on Android versions that require it for the app's own alerts and digest.
- **Internet permission** for Jev and OpenAI features.

The product should always explain why a permission is needed, show whether it is currently available, and provide a clear recovery path when it is missing.

## Privacy and data behavior

- Notification history is stored locally in a Room database.
- Users control how long notification title/body text is retained.
- Retention maintenance removes expired content while preserving non-content metadata needed for aggregate statistics.
- Stored digest content follows the same retention boundary.
- Users can export their history, optionally including retained message content.
- Users can delete all history and reset learned behavior independently.
- External keys are stored using the app's secure preferences layer.
- Ask is designed to query locally and send aggregate results by default rather than raw notification content.
- OpenAI digest prose uses aggregate counts and limited app-level context; the useful notification list is rendered on-device.

## Important product states

The UI needs to represent these states clearly:

- Armed and working normally.
- Disarmed and allowing notifications through.
- Break-glass active, including remaining time.
- Notification Access missing.
- Do Not Disturb access missing.
- Required API key missing for an optional feature.
- External service offline, denied, rate-limited, or misconfigured.
- Empty history versus populated history.
- Notification text available versus removed by retention.
- Background health issue that requires user action.

## Product terminology

- **Wall** — the notification filtering system.
- **Armed** — filtering and interruption protection are active.
- **Disarmed** — notifications pass through normally.
- **Ring** — allow the notification to interrupt the user.
- **Silence** — keep the notification quiet and available for review.
- **Drop** — remove a notification because of an explicit user block rule.
- **Break-glass** — a temporary, self-expiring pass-through window.
- **Correction** — a user action indicating that a prior decision should influence future decisions.
- **Verdict cache / decache** — reusable classification decisions and the action that clears them.
- **Retention** — the period for which notification text remains stored.
- **Jev / APEX** — the external notification-classification service used by the wall.

## Current technical foundation

- Native Android application written in Kotlin.
- Jetpack Compose user interface.
- Room for local notification history and learned data.
- Hilt for dependency injection.
- WorkManager and Android alarms for maintenance, digest scheduling, and recovery work.
- Android Notification Listener and Do Not Disturb integrations for enforcement.
- Jev/APEX for notification classification.
- OpenAI for optional Ask and digest language features.

## Product boundary

Notification Firewall is an attention-protection utility, not a messaging client. It does not replace the source applications, invent notification content, or autonomously block apps without an explicit user rule. Its job is to decide when an incoming notification should interrupt, preserve an inspectable history, learn from corrections, and give the user understandable control over the system.
