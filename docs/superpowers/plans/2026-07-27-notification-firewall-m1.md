# Notification Firewall — Milestone 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the on-device notification firewall for Android — profiles + rules route each notification into let-through / silence / capture buckets, with a thin OpenAI layer deciding ambiguous "is this urgent?" cases and writing a sleep digest.

**Architecture:** Six units behind clean interfaces — Listener (ingest), Profile Manager (which rules apply now), Rule Engine (deterministic matching), Importance Service (pluggable AI), Bucket Executor (cancel/re-post/sound), Store (Room). Pure-Kotlin logic is TDD'd headlessly via `./gradlew test`; device-dependent units (listener, notification re-post, UI) are coded here and verified manually in Android Studio.

**Tech Stack:** Native Android, Kotlin, Jetpack Compose, Room, WorkManager, Hilt (DI), OkHttp + kotlinx.serialization (OpenAI calls), Robolectric + JUnit4 + MockWebServer (headless tests), OpenAI `gpt-4o-mini` (classification) / `gpt-4o` (digest).

## Global Constraints

- **Platform:** Android only. `minSdk = 26` (Android 8.0 — notification channels), `compileSdk`/`targetSdk = 34`.
- **Language:** Kotlin. JVM target 17. Jetpack Compose for all UI.
- **AI provider:** OpenAI only. Models: `gpt-4o-mini` (classify), `gpt-4o` (digest). Key stored in `EncryptedSharedPreferences`, never hard-coded, never committed.
- **No backend** in M1. App calls OpenAI directly.
- **Privacy:** Notification text leaves the device only for ambiguous notifications, only when the active profile's `aiEnabled == true`.
- **Two test worlds:** Logic tasks (Store, Rule Engine, Profile Manager, Importance/Digest services) MUST end with a green `./gradlew test`. Device/UI tasks (Listener, Bucket Executor, WorkManager wiring, Compose screens, permissions) end with a **manual verification checklist** run on an emulator/device in Android Studio — do not fabricate instrumented-test assertions that can't run headlessly.
- **The re-post trade-off** (Bucket Executor): re-posting to apply a custom sound / silence loses the original app's inline actions. Rules default to `LET_THROUGH_AS_IS`; custom-sound/silence rules must surface the consent warning in the UI.

---

## File Structure

Single-module app `:app` (M1 is small enough; splitting into modules is deferred).

```
app/src/main/java/com/anuj/notificationfirewall/
  NfApplication.kt                      # Hilt @HiltAndroidApp entry
  di/AppModule.kt                       # Hilt providers (Room, OkHttp, prefs, services)

  domain/model/
    BucketAction.kt                     # enum: LET_THROUGH_AS_IS, LET_THROUGH_CUSTOM_SOUND, SILENCE, CAPTURE
    DecisionSource.kt                   # enum: RULE, AI, DEFAULT, PASS_THROUGH
    Verdict.kt                          # AI result: urgent, reason, confidence
    Condition.kt                        # sealed: AppIs, TitleContains, BodyContainsAny, IsFavoriteContact, EmailFromDomain
    IncomingNotification.kt             # normalized input to the pipeline

  data/db/
    NfDatabase.kt                       # RoomDatabase
    entity/ProfileEntity.kt, RuleEntity.kt, NotificationRecordEntity.kt
    dao/ProfileDao.kt, RuleDao.kt, NotificationDao.kt
    Converters.kt                       # Room TypeConverters (LocalTime, Set<DayOfWeek>, JSON conditions)

  domain/rules/RuleEngine.kt            # deterministic matching -> RuleDecision
  domain/profile/ProfileManager.kt      # active profile for a given Instant

  ai/ImportanceService.kt               # interface
  ai/OpenAiImportanceService.kt         # gpt-4o-mini classify impl
  ai/DigestService.kt                   # interface
  ai/OpenAiDigestService.kt             # gpt-4o digest impl
  ai/OpenAiClient.kt                    # thin OkHttp wrapper (chat/completions)

  service/NfListenerService.kt          # NotificationListenerService (device)
  service/NotificationPipeline.kt       # orchestrates units per notification
  service/BucketExecutor.kt             # cancel + re-post + channels (device)
  service/ChannelManager.kt             # creates/updates NotificationChannels
  work/DigestWorker.kt                  # WorkManager job at profile end
  work/DigestScheduler.kt               # schedules DigestWorker

  data/prefs/SecurePrefs.kt             # EncryptedSharedPreferences (API key)

  ui/ ...                               # Compose screens (device-verified)

app/src/test/java/...                   # headless JVM/Robolectric tests
```

---

## Task 1: Project bootstrap + headless build/test toolchain

**Goal:** A compiling Android project whose pure-JVM tests run headlessly with `./gradlew test`. This task is best executed directly in the main session (multi-GB SDK download), not a subagent.

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `app/build.gradle.kts`, `gradle/libs.versions.toml`, `app/src/main/AndroidManifest.xml`, `app/src/main/java/com/anuj/notificationfirewall/NfApplication.kt`
- Create: `app/src/test/java/com/anuj/notificationfirewall/SanityTest.kt`
- Create: `local.properties` (git-ignored; `sdk.dir=...`)

- [ ] **Step 1: Install command-line Android SDK + Gradle**

```bash
# Homebrew gradle just to generate the wrapper once; project uses ./gradlew after.
brew install gradle
export ANDROID_HOME="$HOME/Library/Android/sdk"
mkdir -p "$ANDROID_HOME/cmdline-tools"
cd "$ANDROID_HOME/cmdline-tools"
curl -o cmdline-tools.zip https://dl.google.com/android/repository/commandlinetools-mac-11076708_latest.zip
unzip -q cmdline-tools.zip && rm cmdline-tools.zip && mv cmdline-tools latest
yes | ./latest/bin/sdkmanager --licenses
./latest/bin/sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"
```

- [ ] **Step 2: Write Gradle build files**

`settings.gradle.kts`, root `build.gradle.kts`, and `app/build.gradle.kts` with: AGP 8.5.x, Kotlin 2.0.x, Compose BOM, Room 2.6.x, Hilt 2.51.x, WorkManager 2.9.x, OkHttp 4.12.x, kotlinx.serialization 1.6.x, and test deps JUnit4, Robolectric 4.12.x, `androidx.room:room-testing`, `com.squareup.okhttp3:mockwebserver`, `org.robolectric:robolectric`. Enable `testOptions { unitTests { isIncludeAndroidResources = true } }` so Robolectric runs under `./gradlew test`.

`app/src/main/AndroidManifest.xml` declares `NfApplication`, `minSdk 26`, and (empty for now) permissions.

- [ ] **Step 3: Generate the Gradle wrapper**

Run: `cd /Users/anujchhikara/anuj/projects/notification-firewall && gradle wrapper --gradle-version 8.9`
Expected: `gradlew`, `gradlew.bat`, `gradle/wrapper/` created.

- [ ] **Step 4: Write the sanity test**

```kotlin
// app/src/test/java/com/anuj/notificationfirewall/SanityTest.kt
package com.anuj.notificationfirewall
import org.junit.Assert.assertEquals
import org.junit.Test
class SanityTest {
    @Test fun toolchain_runs_headless_unit_tests() = assertEquals(4, 2 + 2)
}
```

- [ ] **Step 5: Run it to verify the headless test loop works**

Run: `./gradlew :app:testDebugUnitTest --tests "com.anuj.notificationfirewall.SanityTest"`
Expected: BUILD SUCCESSFUL, 1 test passed.

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "chore: bootstrap Android project + headless test toolchain"
```

---

## Task 2: Domain model (pure Kotlin)

**Files:**
- Create: `domain/model/BucketAction.kt`, `DecisionSource.kt`, `Verdict.kt`, `Condition.kt`, `IncomingNotification.kt`
- Test: `app/src/test/java/.../domain/model/ConditionTest.kt`

**Interfaces:**
- Produces:
  - `enum class BucketAction { LET_THROUGH_AS_IS, LET_THROUGH_CUSTOM_SOUND, SILENCE, CAPTURE }`
  - `enum class DecisionSource { RULE, AI, DEFAULT, PASS_THROUGH }`
  - `data class Verdict(val urgent: Boolean, val reason: String, val confidence: Double)`
  - `data class IncomingNotification(val packageName: String, val appLabel: String, val title: String, val text: String, val senderKey: String, val isFavoriteContact: Boolean, val emailFromDomain: String?, val postedAt: Instant)`
  - `sealed interface Condition { fun matches(n: IncomingNotification): Boolean }` with implementations `AppIs(packages: Set<String>)`, `TitleContains(text: String)`, `BodyContainsAny(keywords: List<String>)`, `IsFavoriteContact`, `EmailFromDomain(domain: String, shouldMatch: Boolean)`.

- [ ] **Step 1: Write the failing test**

```kotlin
// domain/model/ConditionTest.kt
package com.anuj.notificationfirewall.domain.model
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ConditionTest {
    private fun notif(
        pkg: String = "com.whatsapp", title: String = "Hi", text: String = "hello",
        fav: Boolean = false, domain: String? = null
    ) = IncomingNotification(pkg, "App", title, text, "sender", fav, domain, Instant.EPOCH)

    @Test fun appIs_matches_by_package() {
        assertTrue(Condition.AppIs(setOf("com.whatsapp")).matches(notif(pkg = "com.whatsapp")))
        assertFalse(Condition.AppIs(setOf("com.whatsapp")).matches(notif(pkg = "com.gmail")))
    }
    @Test fun bodyContainsAny_is_case_insensitive() {
        assertTrue(Condition.BodyContainsAny(listOf("sale", "% off")).matches(notif(text = "Big SALE today")))
        assertFalse(Condition.BodyContainsAny(listOf("sale")).matches(notif(text = "meeting at 5")))
    }
    @Test fun emailFromDomain_shouldMatch_false_means_NOT_this_domain() {
        val notMyCompany = Condition.EmailFromDomain("mycompany.com", shouldMatch = false)
        assertTrue(notMyCompany.matches(notif(domain = "promo.io")))
        assertFalse(notMyCompany.matches(notif(domain = "mycompany.com")))
    }
    @Test fun isFavoriteContact_matches_flag() {
        assertTrue(Condition.IsFavoriteContact.matches(notif(fav = true)))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*ConditionTest"`
Expected: FAIL — unresolved references.

- [ ] **Step 3: Implement the models**

```kotlin
// domain/model/Condition.kt
package com.anuj.notificationfirewall.domain.model

sealed interface Condition {
    fun matches(n: IncomingNotification): Boolean

    data class AppIs(val packages: Set<String>) : Condition {
        override fun matches(n: IncomingNotification) = n.packageName in packages
    }
    data class TitleContains(val text: String) : Condition {
        override fun matches(n: IncomingNotification) = n.title.contains(text, ignoreCase = true)
    }
    data class BodyContainsAny(val keywords: List<String>) : Condition {
        override fun matches(n: IncomingNotification) =
            keywords.any { n.text.contains(it, ignoreCase = true) }
    }
    data object IsFavoriteContact : Condition {
        override fun matches(n: IncomingNotification) = n.isFavoriteContact
    }
    data class EmailFromDomain(val domain: String, val shouldMatch: Boolean) : Condition {
        override fun matches(n: IncomingNotification): Boolean {
            val isDomain = n.emailFromDomain?.equals(domain, ignoreCase = true) == true
            return isDomain == shouldMatch
        }
    }
}
```

Create the enums and data classes (`BucketAction`, `DecisionSource`, `Verdict`, `IncomingNotification`) as specified in the Interfaces block above.

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "*ConditionTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: domain model + condition matching"
```

---

## Task 3: Room store (entities, DAOs) — Robolectric-tested

**Files:**
- Create: `data/db/NfDatabase.kt`, `Converters.kt`, `entity/ProfileEntity.kt`, `RuleEntity.kt`, `NotificationRecordEntity.kt`, `dao/ProfileDao.kt`, `RuleDao.kt`, `NotificationDao.kt`
- Test: `app/src/test/java/.../data/db/NotificationDaoTest.kt` (Robolectric, in-memory Room)

**Interfaces:**
- Produces:
  - `ProfileEntity(id, name, enabled, startMinuteOfDay: Int, endMinuteOfDay: Int, daysOfWeek: Set<Int>, order: Int, aiEnabled: Boolean, defaultAction: BucketAction)`
  - `RuleEntity(id, profileId, order, conditionsJson: String, action: BucketAction, soundConfigJson: String?)`
  - `NotificationRecordEntity(id, packageName, appLabel, title, text, timestampEpochMs, senderKey, activeProfileId: Long?, matchedRuleId: Long?, decisionSource: DecisionSource, bucket: BucketAction, aiUrgent: Boolean?, aiReason: String?, isRead: Boolean)`
  - `NotificationDao.insert(rec): Long`, `observeCaptured(): Flow<List<NotificationRecordEntity>>`, `recordsBetween(startMs, endMs): List<NotificationRecordEntity>`, `markRead(id)`
  - `ProfileDao.upsert`, `enabledProfiles(): List<ProfileEntity>`; `RuleDao.rulesForProfile(profileId): List<RuleEntity>`

- [ ] **Step 1: Write the failing test**

```kotlin
// data/db/NotificationDaoTest.kt
package com.anuj.notificationfirewall.data.db
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.anuj.notificationfirewall.domain.model.BucketAction
import com.anuj.notificationfirewall.domain.model.DecisionSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NotificationDaoTest {
    private lateinit var db: NfDatabase
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), NfDatabase::class.java
        ).allowMainThreadQueries().build()
    }
    @After fun teardown() = db.close()

    private fun rec(ts: Long, bucket: BucketAction) = NotificationRecordEntity(
        packageName = "com.whatsapp", appLabel = "WhatsApp", title = "t", text = "x",
        timestampEpochMs = ts, senderKey = "mom", activeProfileId = 1, matchedRuleId = null,
        decisionSource = DecisionSource.DEFAULT, bucket = bucket, aiUrgent = null,
        aiReason = null, isRead = false
    )

    @Test fun recordsBetween_filters_by_window() = runBlocking {
        db.notificationDao().insert(rec(100, BucketAction.CAPTURE))
        db.notificationDao().insert(rec(500, BucketAction.CAPTURE))
        db.notificationDao().insert(rec(900, BucketAction.CAPTURE))
        val inWindow = db.notificationDao().recordsBetween(200, 800)
        assertEquals(1, inWindow.size)
        assertEquals(500, inWindow.first().timestampEpochMs)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*NotificationDaoTest"`
Expected: FAIL — `NfDatabase` unresolved.

- [ ] **Step 3: Implement entities, converters, DAOs, database**

Implement the entities with fields from the Interfaces block. `Converters.kt` maps `Set<Int>` ↔ CSV, `BucketAction`/`DecisionSource` ↔ name string. `NotificationDao.recordsBetween`:

```kotlin
@Query("SELECT * FROM notifications WHERE timestampEpochMs BETWEEN :startMs AND :endMs ORDER BY timestampEpochMs")
suspend fun recordsBetween(startMs: Long, endMs: Long): List<NotificationRecordEntity>
```

`NfDatabase` lists all three entities, `@TypeConverters(Converters::class)`, `version = 1`.

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "*NotificationDaoTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: Room store for profiles, rules, notification records"
```

---

## Task 4: Rule Engine (pure Kotlin, exhaustively tested)

**Files:**
- Create: `domain/rules/RuleEngine.kt`
- Test: `app/src/test/java/.../domain/rules/RuleEngineTest.kt`

**Interfaces:**
- Consumes: `Condition`, `IncomingNotification`, `BucketAction` (Task 2).
- Produces:
  - `data class Rule(val id: Long, val order: Int, val conditions: List<Condition>, val action: BucketAction)`
  - `sealed interface RuleDecision { data class Matched(val rule: Rule) : RuleDecision; data object Ambiguous : RuleDecision }`
  - `class RuleEngine { fun evaluate(n: IncomingNotification, rules: List<Rule>): RuleDecision }`
  - Semantics: rules sorted by `order` ascending; first rule whose conditions ALL match wins → `Matched`. If a matching rule's `action` requires AI (see Task 8 `ASK_AI` handling) that is represented at the profile level, not here. No match → `Ambiguous`.

- [ ] **Step 1: Write the failing test**

```kotlin
// domain/rules/RuleEngineTest.kt
package com.anuj.notificationfirewall.domain.rules
import com.anuj.notificationfirewall.domain.model.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class RuleEngineTest {
    private val engine = RuleEngine()
    private fun n(pkg: String = "com.whatsapp", text: String = "hi", fav: Boolean = false) =
        IncomingNotification(pkg, "App", "t", text, "s", fav, null, Instant.EPOCH)

    @Test fun first_matching_rule_by_order_wins() {
        val rules = listOf(
            Rule(2, 2, listOf(Condition.AppIs(setOf("com.whatsapp"))), BucketAction.SILENCE),
            Rule(1, 1, listOf(Condition.AppIs(setOf("com.whatsapp")), Condition.IsFavoriteContact), BucketAction.LET_THROUGH_CUSTOM_SOUND),
        )
        val d = engine.evaluate(n(fav = true), rules)
        assertTrue(d is RuleDecision.Matched && d.rule.id == 1L)
    }
    @Test fun all_conditions_must_match() {
        val rules = listOf(
            Rule(1, 1, listOf(Condition.AppIs(setOf("com.whatsapp")), Condition.IsFavoriteContact), BucketAction.CAPTURE)
        )
        assertTrue(engine.evaluate(n(fav = false), rules) is RuleDecision.Ambiguous)
    }
    @Test fun no_rules_is_ambiguous() {
        assertTrue(engine.evaluate(n(), emptyList()) is RuleDecision.Ambiguous)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*RuleEngineTest"`
Expected: FAIL.

- [ ] **Step 3: Implement**

```kotlin
// domain/rules/RuleEngine.kt
package com.anuj.notificationfirewall.domain.rules
import com.anuj.notificationfirewall.domain.model.*

data class Rule(val id: Long, val order: Int, val conditions: List<Condition>, val action: BucketAction)
sealed interface RuleDecision {
    data class Matched(val rule: Rule) : RuleDecision
    data object Ambiguous : RuleDecision
}
class RuleEngine {
    fun evaluate(n: IncomingNotification, rules: List<Rule>): RuleDecision {
        val match = rules.sortedBy { it.order }
            .firstOrNull { rule -> rule.conditions.all { it.matches(n) } }
        return if (match != null) RuleDecision.Matched(match) else RuleDecision.Ambiguous
    }
}
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "*RuleEngineTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: deterministic rule engine"
```

---

## Task 5: Profile Manager (schedule logic incl. midnight wrap)

**Files:**
- Create: `domain/profile/ProfileManager.kt`
- Test: `app/src/test/java/.../domain/profile/ProfileManagerTest.kt`

**Interfaces:**
- Consumes: `BucketAction`.
- Produces:
  - `data class ActiveProfile(val id: Long, val name: String, val order: Int, val aiEnabled: Boolean, val defaultAction: BucketAction, val startMinute: Int, val endMinute: Int, val days: Set<DayOfWeek>, val enabled: Boolean)`
  - `class ProfileManager { fun activeProfile(profiles: List<ActiveProfile>, at: ZonedDateTime): ActiveProfile? }`
  - Semantics: a profile is active if `enabled`, `at.dayOfWeek ∈ days`, and `at`'s minute-of-day falls in `[startMinute, endMinute)`; if `startMinute > endMinute` the window wraps past midnight (active when minute ≥ start OR minute < end — with the day check applied to the window's start day). Among active profiles, the lowest `order` wins.

- [ ] **Step 1: Write the failing test**

```kotlin
// domain/profile/ProfileManagerTest.kt
package com.anuj.notificationfirewall.domain.profile
import com.anuj.notificationfirewall.domain.model.BucketAction
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class ProfileManagerTest {
    private val pm = ProfileManager()
    private fun sleep() = ActiveProfile(
        1, "Sleep", 0, true, BucketAction.CAPTURE,
        startMinute = 22 * 60, endMinute = 7 * 60, days = DayOfWeek.values().toSet(), enabled = true
    )
    private fun at(h: Int, m: Int, day: DayOfWeek = DayOfWeek.MONDAY) =
        ZonedDateTime.of(2026, 7, 27, h, m, 0, 0, ZoneId.of("UTC"))
            .with(java.time.temporal.TemporalAdjusters.previousOrSame(day))

    @Test fun active_before_midnight() {
        assertNotNull(pm.activeProfile(listOf(sleep()), at(23, 30)))
    }
    @Test fun active_after_midnight() {
        assertNotNull(pm.activeProfile(listOf(sleep()), at(3, 0)))
    }
    @Test fun inactive_midday() {
        assertNull(pm.activeProfile(listOf(sleep()), at(13, 0)))
    }
    @Test fun disabled_profile_never_active() {
        assertNull(pm.activeProfile(listOf(sleep().copy(enabled = false)), at(23, 30)))
    }
    @Test fun lowest_order_wins_when_overlapping() {
        val a = sleep().copy(id = 1, order = 5)
        val b = sleep().copy(id = 2, order = 1)
        assertEquals(2L, pm.activeProfile(listOf(a, b), at(23, 30))!!.id)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*ProfileManagerTest"`
Expected: FAIL.

- [ ] **Step 3: Implement**

```kotlin
// domain/profile/ProfileManager.kt
package com.anuj.notificationfirewall.domain.profile
import com.anuj.notificationfirewall.domain.model.BucketAction
import java.time.DayOfWeek
import java.time.ZonedDateTime

data class ActiveProfile(
    val id: Long, val name: String, val order: Int, val aiEnabled: Boolean,
    val defaultAction: BucketAction, val startMinute: Int, val endMinute: Int,
    val days: Set<DayOfWeek>, val enabled: Boolean,
)
class ProfileManager {
    fun activeProfile(profiles: List<ActiveProfile>, at: ZonedDateTime): ActiveProfile? {
        val minute = at.hour * 60 + at.minute
        return profiles.filter { it.enabled && at.dayOfWeek in it.days && inWindow(minute, it) }
            .minByOrNull { it.order }
    }
    private fun inWindow(minute: Int, p: ActiveProfile): Boolean =
        if (p.startMinute <= p.endMinute) minute in p.startMinute until p.endMinute
        else minute >= p.startMinute || minute < p.endMinute
}
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "*ProfileManagerTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: profile manager with midnight-wrap schedule logic"
```

---

## Task 6: OpenAI Importance Service (mocked HTTP)

**Files:**
- Create: `ai/OpenAiClient.kt`, `ai/ImportanceService.kt`, `ai/OpenAiImportanceService.kt`
- Test: `app/src/test/java/.../ai/OpenAiImportanceServiceTest.kt` (OkHttp `MockWebServer`)

**Interfaces:**
- Consumes: `IncomingNotification`, `Verdict`.
- Produces:
  - `interface ImportanceService { suspend fun classify(n: IncomingNotification, profileName: String): Verdict }`
  - `class OpenAiClient(baseUrl: HttpUrl, apiKey: String, http: OkHttpClient) { suspend fun chat(model: String, systemPrompt: String, userContent: String, jsonMode: Boolean): String }` — returns the assistant message content string; throws `IOException` on non-2xx.
  - `class OpenAiImportanceService(client: OpenAiClient) : ImportanceService` — model `gpt-4o-mini`, JSON mode on; parses `{"urgent":bool,"reason":str,"confidence":num}`; on parse/HTTP failure returns a safe fallback `Verdict(urgent = true, reason = "AI unavailable — erring toward waking you", confidence = 0.0)` (fail-safe: never silently drop a possibly-urgent notification).

- [ ] **Step 1: Write the failing test**

```kotlin
// ai/OpenAiImportanceServiceTest.kt
package com.anuj.notificationfirewall.ai
import com.anuj.notificationfirewall.domain.model.IncomingNotification
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.Instant

class OpenAiImportanceServiceTest {
    private lateinit var server: MockWebServer
    private lateinit var service: OpenAiImportanceService
    @Before fun setup() {
        server = MockWebServer(); server.start()
        val client = OpenAiClient(server.url("/v1/"), "sk-test", OkHttpClient())
        service = OpenAiImportanceService(client)
    }
    @After fun teardown() = server.shutdown()

    private fun notif() = IncomingNotification(
        "com.whatsapp", "WhatsApp", "Mom", "call me now", "mom", true, null, Instant.EPOCH)

    private fun body(content: String) =
        """{"choices":[{"message":{"content":${org.json.JSONObject.quote(content)}}}]}"""

    @Test fun parses_urgent_verdict() = runBlocking {
        server.enqueue(MockResponse().setBody(body(
            """{"urgent":true,"reason":"Mom asked to call now","confidence":0.9}""")))
        val v = service.classify(notif(), "Sleep")
        assertTrue(v.urgent); assertEquals(0.9, v.confidence, 0.001)
    }
    @Test fun fails_safe_to_urgent_on_http_error() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        val v = service.classify(notif(), "Sleep")
        assertTrue("must wake user when AI unavailable", v.urgent)
        assertEquals(0.0, v.confidence, 0.001)
    }
    @Test fun sends_bearer_auth_and_model() = runBlocking {
        server.enqueue(MockResponse().setBody(body("""{"urgent":false,"reason":"promo","confidence":0.8}""")))
        service.classify(notif(), "Sleep")
        val req = server.takeRequest()
        assertEquals("Bearer sk-test", req.getHeader("Authorization"))
        assertTrue(req.body.readUtf8().contains("gpt-4o-mini"))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*OpenAiImportanceServiceTest"`
Expected: FAIL.

- [ ] **Step 3: Implement client + service**

`OpenAiClient.chat` POSTs to `{baseUrl}chat/completions` with `Authorization: Bearer <key>`, JSON body `{model, messages:[{role:system,...},{role:user,...}], response_format:{type:"json_object"} }` (when `jsonMode`), parses `choices[0].message.content`. `OpenAiImportanceService.classify` builds the system prompt from the spec §9.1, user content = the notification fields, calls `chat("gpt-4o-mini", ...)`, parses JSON to `Verdict`, wraps everything in try/catch returning the fail-safe verdict.

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "*OpenAiImportanceServiceTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: OpenAI importance service with fail-safe verdict"
```

---

## Task 7: OpenAI Digest Service (mocked HTTP)

**Files:**
- Create: `ai/DigestService.kt`, `ai/OpenAiDigestService.kt`
- Test: `app/src/test/java/.../ai/OpenAiDigestServiceTest.kt`

**Interfaces:**
- Consumes: `OpenAiClient`, `NotificationRecordEntity` (Task 3).
- Produces:
  - `interface DigestService { suspend fun summarize(records: List<NotificationRecordEntity>): String }`
  - `class OpenAiDigestService(client: OpenAiClient) : DigestService` — model `gpt-4o`, non-JSON; empty input → returns `"Nothing came through while you were away."` without an HTTP call; on HTTP failure returns a plain count fallback (`"You missed N notifications. (Summary unavailable.)"`).

- [ ] **Step 1: Write the failing test**

```kotlin
// ai/OpenAiDigestServiceTest.kt
package com.anuj.notificationfirewall.ai
import com.anuj.notificationfirewall.data.db.NotificationRecordEntity
import com.anuj.notificationfirewall.domain.model.BucketAction
import com.anuj.notificationfirewall.domain.model.DecisionSource
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class OpenAiDigestServiceTest {
    private lateinit var server: MockWebServer
    private lateinit var service: OpenAiDigestService
    @Before fun setup() {
        server = MockWebServer(); server.start()
        service = OpenAiDigestService(OpenAiClient(server.url("/v1/"), "sk-test", OkHttpClient()))
    }
    @After fun teardown() = server.shutdown()
    private fun rec() = NotificationRecordEntity(
        packageName = "com.gmail", appLabel = "Gmail", title = "t", text = "x",
        timestampEpochMs = 1, senderKey = "promo", activeProfileId = 1, matchedRuleId = null,
        decisionSource = DecisionSource.DEFAULT, bucket = BucketAction.CAPTURE,
        aiUrgent = null, aiReason = null, isRead = false)

    @Test fun empty_input_short_circuits_without_http() = runBlocking {
        val text = service.summarize(emptyList())
        assertEquals("Nothing came through while you were away.", text)
        assertEquals(0, server.requestCount)
    }
    @Test fun returns_model_summary() = runBlocking {
        server.enqueue(MockResponse().setBody(
            """{"choices":[{"message":{"content":"3 promos, nothing urgent."}}]}"""))
        assertEquals("3 promos, nothing urgent.", service.summarize(listOf(rec())))
    }
    @Test fun http_error_falls_back_to_count() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        assertTrue(service.summarize(listOf(rec())).contains("1"))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*OpenAiDigestServiceTest"`
Expected: FAIL.

- [ ] **Step 3: Implement** the digest service per the Interfaces block, system prompt from spec §9.2, user content = a compact list of `appLabel | senderKey | title` lines.

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "*OpenAiDigestServiceTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: OpenAI digest service with empty + failure fallbacks"
```

---

## Task 8: Notification Pipeline (orchestration, headless-tested)

**Files:**
- Create: `service/NotificationPipeline.kt`
- Test: `app/src/test/java/.../service/NotificationPipelineTest.kt`

**Interfaces:**
- Consumes: `ProfileManager`, `RuleEngine`, `ImportanceService`, mapping helpers, `BucketAction`, `DecisionSource`.
- Produces:
  - `data class PipelineResult(val bucket: BucketAction, val source: DecisionSource, val verdict: Verdict?, val activeProfileId: Long?, val matchedRuleId: Long?)`
  - `class NotificationPipeline(profileManager, ruleEngine, importanceService) { suspend fun decide(n: IncomingNotification, profiles: List<ActiveProfile>, rulesByProfile: (Long) -> List<Rule>, at: ZonedDateTime): PipelineResult }`
  - Semantics: no active profile → `LET_THROUGH_AS_IS`, `PASS_THROUGH`. Active profile → RuleEngine. `Matched` with action `ASK_AI`-equivalent handled thus: a matched rule always uses its own `action`. Only when the RuleEngine returns `Ambiguous` do we consult the profile's `defaultAction`: if `defaultAction == ASK_AI` **and** `profile.aiEnabled`, call `importanceService.classify` → `SILENCE` when not urgent, `LET_THROUGH_AS_IS` when urgent (`source = AI`); otherwise use the literal `defaultAction` (`source = DEFAULT`). (Note: `BucketAction` gains an `ASK_AI` member — update Task 2's enum. It is a *profile default* choice, never a final bucket; the pipeline resolves it.)

- [ ] **Step 1: Write the failing test**

```kotlin
// service/NotificationPipelineTest.kt
package com.anuj.notificationfirewall.service
import com.anuj.notificationfirewall.ai.ImportanceService
import com.anuj.notificationfirewall.domain.model.*
import com.anuj.notificationfirewall.domain.profile.*
import com.anuj.notificationfirewall.domain.rules.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class NotificationPipelineTest {
    private fun n(fav: Boolean = false) =
        IncomingNotification("com.whatsapp", "WhatsApp", "t", "hi", "s", fav, null, Instant.EPOCH)
    private val sleep = ActiveProfile(1, "Sleep", 0, true, BucketAction.ASK_AI,
        22 * 60, 7 * 60, DayOfWeek.values().toSet(), true)
    private val night = ZonedDateTime.of(2026, 7, 27, 23, 0, 0, 0, ZoneId.of("UTC"))
    private val noon = ZonedDateTime.of(2026, 7, 27, 12, 0, 0, 0, ZoneId.of("UTC"))
    private fun pipeline(urgent: Boolean) = NotificationPipeline(
        ProfileManager(), RuleEngine(),
        object : ImportanceService {
            override suspend fun classify(n: IncomingNotification, profileName: String) =
                Verdict(urgent, "r", 0.9)
        })

    @Test fun no_active_profile_passes_through() = runBlocking {
        val r = pipeline(false).decide(n(), listOf(sleep), { emptyList() }, noon)
        assertEquals(BucketAction.LET_THROUGH_AS_IS, r.bucket)
        assertEquals(DecisionSource.PASS_THROUGH, r.source)
    }
    @Test fun matched_rule_uses_its_action() = runBlocking {
        val rule = Rule(1, 1, listOf(Condition.IsFavoriteContact), BucketAction.LET_THROUGH_CUSTOM_SOUND)
        val r = pipeline(false).decide(n(fav = true), listOf(sleep), { listOf(rule) }, night)
        assertEquals(BucketAction.LET_THROUGH_CUSTOM_SOUND, r.bucket)
        assertEquals(DecisionSource.RULE, r.source)
    }
    @Test fun ambiguous_with_ask_ai_and_not_urgent_is_silenced() = runBlocking {
        val r = pipeline(urgent = false).decide(n(), listOf(sleep), { emptyList() }, night)
        assertEquals(BucketAction.SILENCE, r.bucket)
        assertEquals(DecisionSource.AI, r.source)
    }
    @Test fun ambiguous_with_ask_ai_and_urgent_passes() = runBlocking {
        val r = pipeline(urgent = true).decide(n(), listOf(sleep), { emptyList() }, night)
        assertEquals(BucketAction.LET_THROUGH_AS_IS, r.bucket)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*NotificationPipelineTest"`
Expected: FAIL.

- [ ] **Step 3: Implement** `NotificationPipeline.decide` per the Interfaces semantics. Add `ASK_AI` to `BucketAction` (allowed only as a profile default; assert it never reaches `BucketExecutor`).

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "*NotificationPipelineTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: notification pipeline orchestration"
```

---

## Task 9: Secure prefs + Hilt wiring (headless-tested where possible)

**Files:**
- Create: `data/prefs/SecurePrefs.kt`, `di/AppModule.kt`, `NfApplication.kt` (update)
- Test: `app/src/test/java/.../data/prefs/SecurePrefsTest.kt` (Robolectric)

**Interfaces:**
- Produces: `class SecurePrefs(context) { var openAiKey: String?; var hasKey: Boolean }` backed by `EncryptedSharedPreferences`. `AppModule` provides `NfDatabase`, DAOs, `OkHttpClient`, `OpenAiClient` (reading key from `SecurePrefs`), `ImportanceService`, `DigestService`, `RuleEngine`, `ProfileManager`, `NotificationPipeline`.

- [ ] **Step 1: Write the failing test** — round-trip the key through `SecurePrefs` under Robolectric; assert `hasKey` flips.
- [ ] **Step 2: Run to verify it fails.** `./gradlew :app:testDebugUnitTest --tests "*SecurePrefsTest"`
- [ ] **Step 3: Implement** `SecurePrefs` (`MasterKey` + `EncryptedSharedPreferences.create`) and the Hilt `AppModule` providers.
- [ ] **Step 4: Run to verify pass.**
- [ ] **Step 5: Commit** — `git commit -m "feat: encrypted api-key storage + DI graph"`

---

## Task 10 (device): NotificationListenerService + mapping — manual verify

**Files:**
- Create: `service/NfListenerService.kt`, `service/NotificationMapper.kt`
- Modify: `AndroidManifest.xml` (register service with `BIND_NOTIFICATION_LISTENER_SERVICE`)

**Interfaces:**
- Consumes: `NotificationPipeline`, `BucketExecutor` (Task 11), `NotificationDao`, `NotificationMapper`.
- Produces: `NotificationMapper.map(sbn: StatusBarNotification, context): IncomingNotification` — extracts title/text from `extras`, `appLabel` via PackageManager, `senderKey` (best-effort: `EXTRA_TITLE`, or email `from` for Gmail), `isFavoriteContact` via ContactsContract starred lookup, `emailFromDomain` parsed from sender when it's an email.

**Implementation notes:** `onNotificationPosted` → map → `pipeline.decide(...)` (on a coroutine) → persist `NotificationRecordEntity` → hand the result + `sbn` to `BucketExecutor`. Guard against reacting to our own re-posted notifications (skip our package) to avoid loops.

- [ ] **Step 1: Implement** the service + mapper + manifest registration.
- [ ] **Step 2: Manual verification (Android Studio + device/emulator):**
  - [ ] Build & install: `./gradlew installDebug`.
  - [ ] Grant notification access when prompted; confirm the service connects (log in `onListenerConnected`).
  - [ ] Send a test notification (e.g. via `adb shell cmd notification post ...` or a messaging app); confirm a `NotificationRecordEntity` row is written (inspect via App Inspection → Database).
  - [ ] Confirm no infinite loop from our own re-posts.
- [ ] **Step 3: Commit** — `git commit -m "feat: notification listener + mapper (device)"`

---

## Task 11 (device): Bucket Executor + Channel Manager — manual verify

**Files:**
- Create: `service/BucketExecutor.kt`, `service/ChannelManager.kt`

**Interfaces:**
- Consumes: `PipelineResult`, `StatusBarNotification`, `SoundConfig`.
- Produces: `BucketExecutor.execute(result, sbn)` — `LET_THROUGH_AS_IS` → no-op; `LET_THROUGH_CUSTOM_SOUND` → `cancelNotification(sbn.key)` then re-post via the source/priority channel with sound + vibration + DND bypass; `SILENCE` → cancel + re-post on a silent channel; `CAPTURE` → cancel only (record already stored). `ChannelManager.channelFor(packageName, priority): String` lazily creates channels like `whatsapp_urgent`, `gmail_work`, `silent`.

**Implementation notes:** DND bypass requires `NotificationManager.isNotificationPolicyAccessGranted()` and `channel.setBypassDnd(true)`. Re-posted notifications cannot reproduce the original's action intents — this is the documented trade-off; carry over title/text/smallIcon/largeIcon only.

- [ ] **Step 1: Implement** executor + channel manager.
- [ ] **Step 2: Manual verification (device):**
  - [ ] `CAPTURE`: promo notification disappears from tray, appears only in app inbox.
  - [ ] `SILENCE`: notification stays in tray, no sound/vibration/wake.
  - [ ] `LET_THROUGH_CUSTOM_SOUND`: plays the chosen sound; with DND on and policy access granted, it still rings.
  - [ ] `LET_THROUGH_AS_IS`: original untouched, native quick-reply still present.
- [ ] **Step 3: Commit** — `git commit -m "feat: bucket executor + notification channels (device)"`

---

## Task 12 (device): Digest scheduling (WorkManager)

**Files:**
- Create: `work/DigestWorker.kt`, `work/DigestScheduler.kt`

**Interfaces:**
- Consumes: `NotificationDao.recordsBetween`, `DigestService`, `ProfileManager`.
- Produces: `DigestScheduler.scheduleForProfileEnd(profile)` — enqueues a `DigestWorker` (unique work, `ExistingWorkPolicy.REPLACE`) at the profile's `endMinute`. `DigestWorker.doWork()` loads the profile window's Silenced+Captured records, calls `DigestService.summarize`, posts the digest notification.

- [ ] **Step 1: Implement** worker + scheduler; schedule on profile enable/edit.
- [ ] **Step 2: Manual verification (device):** set a Sleep profile ending 2 minutes out; generate a few captured notifications; confirm a digest notification arrives at the end time with a sensible summary.
- [ ] **Step 3: Commit** — `git commit -m "feat: wake-up digest via WorkManager (device)"`

---

## Task 13 (device): Compose UI + onboarding/permissions

**Files:**
- Create under `ui/`: `MainActivity.kt`, `onboarding/OnboardingScreen.kt`, `home/HomeScreen.kt`, `inbox/InboxScreen.kt`, `profiles/ProfileEditScreen.kt`, `rules/RuleBuilderScreen.kt`, `settings/SettingsScreen.kt`, `digest/DigestScreen.kt`, plus ViewModels and a nav graph.

**Interfaces:**
- Consumes: DAOs (via ViewModels), `SecurePrefs`, permission helpers.
- Produces: the seven screens from spec §11 and a seeded default Sleep profile on first run (spec §7 rule table).

**Implementation notes:**
- **Onboarding** walks the five grants (spec §10): notification access (`ACTION_NOTIFICATION_LISTENER_SETTINGS`), DND policy access (`ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS`), contacts (`READ_CONTACTS`), battery exemption (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`), and OpenAI key entry. Each explains *why* before sending the user to Settings.
- **Rule builder**: the custom-sound / silence toggle MUST show the consent warning verbatim from spec §8 ("…you'll lose that app's quick-reply and action buttons…").
- **Inbox**: list Captured/Silenced records grouped by app, showing `aiReason` where present; mark-read.
- **Settings**: API key field (writes `SecurePrefs`), live permission status, battery-exemption shortcut.

- [ ] **Step 1: Implement** MainActivity + nav + the seven screens + ViewModels + first-run seed.
- [ ] **Step 2: Manual verification (device):**
  - [ ] Fresh install runs onboarding; each grant works and status reflects reality.
  - [ ] Default Sleep profile is seeded and visible.
  - [ ] Rule builder shows the consent warning when custom-sound/silence is enabled.
  - [ ] Inbox shows captured notifications with AI reasons; mark-read works.
  - [ ] Settings stores the key; a real end-to-end Sleep test silences promos and lets a favorite-contact message through.
- [ ] **Step 3: Commit** — `git commit -m "feat: Compose UI + onboarding/permissions (device)"`

---

## Self-Review

- **Spec coverage:** §4 stack → Task 1; §5 pipeline → Tasks 8/10/11; §6 data model → Tasks 2/3; §7 profiles/rules + seed → Tasks 4/5/13; §8 buckets + consent → Task 11/13; §9 AI classify + digest → Tasks 6/7; §10 permissions → Task 13; §11 screens → Task 13; §12 testing → embedded per task. Covered.
- **Headless vs device split:** Tasks 1–9 end in `./gradlew test`; Tasks 10–13 end in manual checklists (honest — they need a device).
- **Type consistency:** `BucketAction` (with added `ASK_AI` profile-default member, noted in Tasks 2 & 8), `Verdict`, `IncomingNotification`, `Rule`, `RuleDecision`, `ActiveProfile`, `PipelineResult`, `NotificationRecordEntity` names are consistent across tasks.
- **Open spec questions (§14 of design doc)** remain user-decidable and are seeded with the documented defaults (Sleep = Capture default, wake-only digest, phone favorites, on-device key). Revisit before/at Task 13 if the user changes them.
```
