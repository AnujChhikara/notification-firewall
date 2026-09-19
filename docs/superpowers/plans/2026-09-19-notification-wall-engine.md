# Notification Wall — Engine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the headless decision engine for the Notification Wall — every notification intercepted, judged by Jev, rung or silenced and filed locally — with arming state derived from live system DND.

**Architecture:** Keep the existing reliability layer (listener service, keep-alive, boot recovery, health monitor, DND controller, bypass-channel re-post). Build the new engine in a fresh `domain/wall` + `ai/jev` package tree, fully unit-tested in isolation, then switch `NfListenerService` over to it and delete the profile/rule layer. Deterministic local checks (OTP, block, VIP, cache) run before any network call; Jev is consulted only for notifications nothing local can answer.

**Tech Stack:** Kotlin, Room 2.6.1, Hilt 2.52, OkHttp 4.12.0, kotlinx-serialization 1.7.3, WorkManager 2.9.1. Tests: JUnit 4.13.2, Robolectric 4.13, Room-testing 2.6.1, MockWebServer 4.12.0, kotlinx-coroutines-test 1.8.1.

**Spec:** `docs/superpowers/specs/2026-09-19-notification-wall-design.md`

**Scope:** This plan covers the engine only. Theming, the four screens, Ask/text-to-SQL, the Quick Settings tile, break-glass, and the digest are Plan 2 (`2026-09-19-notification-wall-surface.md`, written after this plan lands).

## Global Constraints

- `minSdk = 26`, `compileSdk = 34`, `targetSdk = 34`. Anything above API 26 needs a `Build.VERSION.SDK_INT` guard.
- Package root: `com.anuj.notificationfirewall` (retained — no rename).
- Jev endpoint: `POST https://api.typesafe.ai/v1/systemone`, header `Authorization: Bearer <key>`, body field `"model": "jev-latest"`.
- Jev request timeout: **3 seconds**. A slow API degrades to silence-and-store, never delays the pipeline.
- Cache rules are inviolable: **never cache when `isFromHuman >= 0.7`**, **never cache when `confidence < 0.6`**.
- Bias is clamped to **±0.75** on the 1–5 importance scale, **0.25 per correction**.
- Default threshold: **4.0**.
- DND filter is always `INTERRUPTION_FILTER_PRIORITY`, never `_NONE` or `_ALARMS`. Policy always includes `PRIORITY_CATEGORY_CALLS` and `PRIORITY_CATEGORY_REPEAT_CALLERS` with `PRIORITY_SENDERS_ANY`.
- Armed state is derived from `NotificationManager.getCurrentInterruptionFilter()` at read time. No stored boolean may ever be the source of truth for "is the wall armed".
- Every task ends green: `./gradlew :app:testDebugUnitTest` passes and `./gradlew :app:assembleDebug` compiles.
- Commit at the end of every task. Never commit a red build.

---

### Task 1: Demolish the Still program layer

Removes the coaching-program UI and its data table. These are leaves — nothing in the notification pipeline references them — so the app still compiles and runs on profiles+rules after this task. The profile/rule layer itself is removed much later, in Task 11, once the new engine has replaced it.

**Files:**
- Delete: `app/src/main/java/com/anuj/notificationfirewall/ui/assessment/AssessmentScreen.kt`
- Delete: `app/src/main/java/com/anuj/notificationfirewall/ui/program/ProgramScreen.kt`
- Delete: `app/src/main/java/com/anuj/notificationfirewall/ui/pods/PodsScreen.kt`
- Delete: `app/src/main/java/com/anuj/notificationfirewall/ui/results/ResultsScreen.kt`
- Delete: `app/src/main/java/com/anuj/notificationfirewall/ui/analytics/AnalyticsScreen.kt`
- Delete: `app/src/main/java/com/anuj/notificationfirewall/domain/assessment/Assessment.kt`
- Delete: `app/src/main/java/com/anuj/notificationfirewall/domain/assessment/AssessmentScorer.kt`
- Delete: `app/src/main/java/com/anuj/notificationfirewall/data/db/dao/AssessmentDao.kt`
- Delete: `app/src/main/java/com/anuj/notificationfirewall/data/db/AssessmentResultEntity.kt`
- Modify: `app/src/main/java/com/anuj/notificationfirewall/data/db/NfDatabase.kt`
- Modify: `app/src/main/java/com/anuj/notificationfirewall/di/AppModule.kt`
- Modify: `app/src/main/java/com/anuj/notificationfirewall/ui/MainActivity.kt` (nav graph)
- Modify: `app/src/main/AndroidManifest.xml`
- Delete: any test file under `app/src/test/` referencing assessment
- Test: `app/src/test/java/com/anuj/notificationfirewall/data/db/MigrationTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `NfDatabase` at **version 4** with entities `ProfileEntity`, `RuleEntity`, `NotificationRecordEntity` and migration `MIGRATION_3_4` exported as a top-level `val` in `NfDatabase.kt`.

- [ ] **Step 1: Find every reference before deleting**

```bash
cd "/Volumes/External SSD/dev/projects/notification-firewall"
grep -rn "assessment\|Assessment\|ProgramScreen\|PodsScreen\|ResultsScreen\|AnalyticsScreen" \
  app/src --include="*.kt" --include="*.xml" -i
```

Write the list down. Every hit must be gone or rewired by the end of this task.

- [ ] **Step 2: Write the migration test first**

Create `app/src/test/java/com/anuj/notificationfirewall/data/db/MigrationTest.kt`:

```kotlin
package com.anuj.notificationfirewall.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private const val TEST_DB = "migration-test.db"

@RunWith(RobolectricTestRunner::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        ApplicationProvider.getApplicationContext(),
        NfDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate3To4_dropsAssessmentTable_andKeepsNotifications() {
        helper.createDatabase(TEST_DB, 3).use { db ->
            db.execSQL(
                """
                INSERT INTO notifications
                  (packageName, appLabel, title, text, timestampEpochMs, senderKey,
                   activeProfileId, matchedRuleId, decisionSource, bucket,
                   aiUrgent, aiReason, isRead)
                VALUES
                  ('com.myntra', 'Myntra', 'FLAT 70% OFF', 'Shop now', 1700000000000, 'Myntra',
                   NULL, NULL, 'DEFAULT', 'SILENCE', NULL, NULL, 0)
                """.trimIndent(),
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 4, true, MIGRATION_3_4)

        db.query("SELECT COUNT(*) FROM notifications").use { c ->
            c.moveToFirst()
            assertEquals(1, c.getInt(0))
        }
        db.query(
            "SELECT name FROM sqlite_master WHERE type='table' AND name='assessment_results'",
        ).use { c ->
            assertTrue("assessment_results should be gone", c.count == 0)
        }
    }
}
```

- [ ] **Step 3: Enable schema export so `MigrationTestHelper` has schemas to validate against**

In `app/build.gradle.kts`, inside the `android { }` block:

```kotlin
    room {
        schemaDirectory("$projectDir/schemas")
    }
```

and add the Room Gradle plugin at the top of the same file's `plugins { }` block:

```kotlin
    id("androidx.room") version "2.6.1"
```

Then in `NfDatabase.kt` change `exportSchema = false` to `exportSchema = true`.

Add the schema dir as a test source so the helper can read it — in `app/build.gradle.kts` inside `android { }`:

```kotlin
    sourceSets {
        getByName("test") {
            assets.srcDirs(files("$projectDir/schemas"))
        }
    }
```

- [ ] **Step 4: Run the test to verify it fails**

```bash
./gradlew :app:testDebugUnitTest --tests "*MigrationTest*"
```

Expected: FAIL — `MIGRATION_3_4` is unresolved.

- [ ] **Step 5: Delete the Still program files**

```bash
cd "/Volumes/External SSD/dev/projects/notification-firewall/app/src/main/java/com/anuj/notificationfirewall"
rm -rf ui/assessment ui/program ui/pods ui/results ui/analytics domain/assessment
rm -f data/db/dao/AssessmentDao.kt data/db/AssessmentResultEntity.kt
```

- [ ] **Step 6: Update `NfDatabase.kt`**

Replace the file's contents with:

```kotlin
package com.anuj.notificationfirewall.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.anuj.notificationfirewall.data.db.dao.NotificationDao
import com.anuj.notificationfirewall.data.db.dao.ProfileDao
import com.anuj.notificationfirewall.data.db.dao.RuleDao

/** Drops the Still-era assessment table. Notification history is preserved. */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS assessment_results")
    }
}

@Database(
    entities = [
        ProfileEntity::class,
        RuleEntity::class,
        NotificationRecordEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class NfDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun ruleDao(): RuleDao
    abstract fun notificationDao(): NotificationDao
}
```

- [ ] **Step 7: Update `AppModule.kt` — drop the assessment provider, use the real migration**

Delete the `provideAssessmentDao` function and the `AssessmentDao` import. Replace the database provider with:

```kotlin
    @Provides
    @Singleton
    fun provideNfDatabase(@ApplicationContext context: Context): NfDatabase =
        Room.databaseBuilder(context, NfDatabase::class.java, DATABASE_NAME)
            .addMigrations(MIGRATION_3_4)
            .build()
```

Add `import com.anuj.notificationfirewall.data.db.MIGRATION_3_4` and remove `fallbackToDestructiveMigration()`.

- [ ] **Step 8: Rewire the nav graph**

In `MainActivity.kt`, delete every `composable(...)` route and import for the assessment, program, pods, results, and analytics screens, and delete any navigation action that targets them. Leave the remaining routes (home, inbox, digest, settings, profiles, rules, permissions, welcome, onboarding) working — they are replaced wholesale in Plan 2.

- [ ] **Step 9: Rename the app in the manifest**

In `app/src/main/AndroidManifest.xml`, replace all three occurrences of `android:label="Still"` with `android:label="Notification Wall"`.

- [ ] **Step 10: Delete orphaned tests**

```bash
cd "/Volumes/External SSD/dev/projects/notification-firewall"
grep -rln "Assessment" app/src/test --include="*.kt" | xargs -r rm -f
```

- [ ] **Step 11: Run the full suite**

```bash
./gradlew :app:testDebugUnitTest
```

Expected: PASS, including `MigrationTest`.

- [ ] **Step 12: Verify the app still builds**

```bash
./gradlew :app:assembleDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 13: Commit**

```bash
git add -A
git commit -m "refactor: remove Still program layer

Deletes the assessment, program, pods, results and analytics screens and
the assessment data table (migration 3->4). The notification pipeline is
untouched; profiles and rules are removed in a later task once the Jev
engine replaces them. Renames the app to Notification Wall and enables
Room schema export so migrations are testable."
```

---

### Task 2: Content-shape fingerprint

A pure, dependency-free normalizer. This is what lets one cached verdict cover every Myntra sale blast while keeping genuinely different messages apart. Getting this wrong in either direction is a real defect — too aggressive and distinct messages collide, too timid and the cache never hits.

**Files:**
- Create: `app/src/main/java/com/anuj/notificationfirewall/domain/wall/ContentShape.kt`
- Test: `app/src/test/java/com/anuj/notificationfirewall/domain/wall/ContentShapeTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `object ContentShape { fun of(title: String, text: String): String }` — returns a 32-char lowercase hex string. Used by Tasks 6 and 8.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/anuj/notificationfirewall/domain/wall/ContentShapeTest.kt`:

```kotlin
package com.anuj.notificationfirewall.domain.wall

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ContentShapeTest {

    @Test
    fun sameMarketingTemplateWithDifferentNumbers_collapsesToOneShape() {
        val a = ContentShape.of("Myntra", "FLAT 70% OFF ends in 3 hours. Shop now!")
        val b = ContentShape.of("Myntra", "FLAT 50% OFF ends in 6 hours. Shop now!")
        assertEquals(a, b)
    }

    @Test
    fun genuinelyDifferentMessagesFromSameSender_produceDifferentShapes() {
        val promo = ContentShape.of("Mom", "Check out this 50% off sale at Croma!")
        val real = ContentShape.of("Mom", "Reached home safely, call me when free")
        assertNotEquals(promo, real)
    }

    @Test
    fun currencyAmountsAreNormalised() {
        val a = ContentShape.of("HDFC Bank", "Rs. 1,299.00 debited from your account")
        val b = ContentShape.of("HDFC Bank", "Rs. 45,000.50 debited from your account")
        assertEquals(a, b)
    }

    @Test
    fun urlsAreNormalised() {
        val a = ContentShape.of("Swiggy", "Track your order at https://swiggy.com/o/abc123")
        val b = ContentShape.of("Swiggy", "Track your order at https://swiggy.com/o/zzz999")
        assertEquals(a, b)
    }

    @Test
    fun timesAndDatesAreNormalised() {
        val a = ContentShape.of("Calendar", "Standup at 09:30 on 12/03/2026")
        val b = ContentShape.of("Calendar", "Standup at 14:00 on 03/11/2026")
        assertEquals(a, b)
    }

    @Test
    fun caseAndWhitespaceDoNotMatter() {
        val a = ContentShape.of("Myntra", "Shop   Now!")
        val b = ContentShape.of("myntra", "shop now!")
        assertEquals(a, b)
    }

    @Test
    fun titleIsPartOfTheShape() {
        val a = ContentShape.of("Myntra", "Your order shipped")
        val b = ContentShape.of("Amazon", "Your order shipped")
        assertNotEquals(a, b)
    }

    @Test
    fun outputIs32HexChars() {
        val shape = ContentShape.of("Myntra", "anything at all")
        assertEquals(32, shape.length)
        assertEquals(true, shape.all { it in "0123456789abcdef" })
    }

    @Test
    fun emptyInputIsStable() {
        assertEquals(ContentShape.of("", ""), ContentShape.of("", ""))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
./gradlew :app:testDebugUnitTest --tests "*ContentShapeTest*"
```

Expected: FAIL — `ContentShape` unresolved.

- [ ] **Step 3: Implement**

Create `app/src/main/java/com/anuj/notificationfirewall/domain/wall/ContentShape.kt`:

```kotlin
package com.anuj.notificationfirewall.domain.wall

import java.security.MessageDigest

/**
 * A normalized fingerprint of a notification's visible content.
 *
 * Marketing senders post the same template over and over with only the numbers
 * changed. Stripping the variable parts — amounts, percentages, dates, times,
 * URLs, order ids, bare digit runs — collapses a whole campaign onto one key, so
 * a single Jev verdict can answer for all of it.
 *
 * The normalizer is deliberately conservative about *words*: nothing lexical is
 * removed. "Reached home safely" and "Check out this sale" keep every word they
 * have and therefore never collide, which is what stops a real message from a
 * person inheriting a promo's cached verdict.
 */
object ContentShape {

    private val URL = Regex("""https?://\S+|www\.\S+""", RegexOption.IGNORE_CASE)
    private val EMAIL = Regex("""[\w.+-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)+""")
    private val DATE = Regex("""\b\d{1,4}[/-]\d{1,2}[/-]\d{1,4}\b""")
    private val TIME = Regex("""\b\d{1,2}:\d{2}(?::\d{2})?\s*(?:am|pm)?\b""", RegexOption.IGNORE_CASE)
    private val PERCENT = Regex("""\b\d+(?:\.\d+)?\s*%""")
    private val MONEY = Regex(
        """(?:rs\.?|inr|usd|eur|gbp|[$€£₹])\s*\d[\d,]*(?:\.\d+)?""",
        RegexOption.IGNORE_CASE,
    )
    private val ALNUM_ID = Regex("""\b(?=\w*\d)(?=\w*[a-z])\w{6,}\b""", RegexOption.IGNORE_CASE)
    private val NUMBER = Regex("""\b\d[\d,]*(?:\.\d+)?\b""")
    private val WHITESPACE = Regex("""\s+""")

    fun of(title: String, text: String): String = hash(normalize("$title   $text"))

    private fun normalize(raw: String): String =
        raw.lowercase()
            // Order matters: the broadest patterns that contain digits must run
            // before the bare-number rule, or they lose their distinguishing
            // punctuation and collapse into <n> prematurely.
            .replace(URL, " <url> ")
            .replace(EMAIL, " <email> ")
            .replace(MONEY, " <amt> ")
            .replace(PERCENT, " <pct> ")
            .replace(DATE, " <date> ")
            .replace(TIME, " <time> ")
            .replace(ALNUM_ID, " <id> ")
            .replace(NUMBER, " <n> ")
            .replace(WHITESPACE, " ")
            .trim()

    private fun hash(normalized: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(normalized.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
```

- [ ] **Step 4: Run to verify it passes**

```bash
./gradlew :app:testDebugUnitTest --tests "*ContentShapeTest*"
```

Expected: PASS, 9 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/anuj/notificationfirewall/domain/wall/ContentShape.kt \
        app/src/test/java/com/anuj/notificationfirewall/domain/wall/ContentShapeTest.kt
git commit -m "feat(wall): content-shape fingerprint for verdict caching"
```

---

### Task 3: OTP fast-path detector

Pure and local. Runs before everything — block list, cache, Jev — because a silenced OTP has an immediate concrete cost and because it must work with no network at all.

**Files:**
- Create: `app/src/main/java/com/anuj/notificationfirewall/domain/wall/OtpDetector.kt`
- Test: `app/src/test/java/com/anuj/notificationfirewall/domain/wall/OtpDetectorTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `object OtpDetector { fun isOtp(title: String, text: String): Boolean }`. Used by Task 8.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/anuj/notificationfirewall/domain/wall/OtpDetectorTest.kt`:

```kotlin
package com.anuj.notificationfirewall.domain.wall

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OtpDetectorTest {

    @Test
    fun classicOtpMessage_isDetected() {
        assertTrue(OtpDetector.isOtp("HDFC Bank", "123456 is your OTP. Do not share it with anyone."))
    }

    @Test
    fun verificationCodePhrasing_isDetected() {
        assertTrue(OtpDetector.isOtp("WhatsApp", "Your verification code is 482-193"))
    }

    @Test
    fun codeIsPhrasing_isDetected() {
        assertTrue(OtpDetector.isOtp("Google", "Your code is 8821"))
    }

    @Test
    fun twoFactorPhrasing_isDetected() {
        assertTrue(OtpDetector.isOtp("GitHub", "2FA code: 553201"))
    }

    @Test
    fun otpKeywordInTitle_isDetected() {
        assertTrue(OtpDetector.isOtp("OTP from Axis Bank", "Use 990211 to complete your login"))
    }

    @Test
    fun marketingWithNumbers_isNotDetected() {
        assertFalse(OtpDetector.isOtp("Myntra", "FLAT 70% OFF on 12000 styles. Shop now!"))
    }

    @Test
    fun deliveryUpdateWithOrderNumber_isNotDetected() {
        assertFalse(OtpDetector.isOtp("Swiggy", "Order 483920 is on the way, arriving in 12 mins"))
    }

    @Test
    fun keywordWithoutAnyCode_isNotDetected() {
        assertFalse(OtpDetector.isOtp("Bank", "Never share your OTP with anyone, we will never ask."))
    }

    @Test
    fun codeTooLong_isNotDetected() {
        assertFalse(OtpDetector.isOtp("Bank", "Your code is 1234567890123"))
    }

    @Test
    fun ordinaryConversation_isNotDetected() {
        assertFalse(OtpDetector.isOtp("Mom", "Call me when you're free, it's about Sunday"))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
./gradlew :app:testDebugUnitTest --tests "*OtpDetectorTest*"
```

Expected: FAIL — `OtpDetector` unresolved.

- [ ] **Step 3: Implement**

Create `app/src/main/java/com/anuj/notificationfirewall/domain/wall/OtpDetector.kt`:

```kotlin
package com.anuj.notificationfirewall.domain.wall

/**
 * Detects one-time passcodes locally, with no network call.
 *
 * Requires BOTH a code-shaped token and nearby OTP vocabulary. Either signal
 * alone is far too common: marketing copy is full of bare numbers, and security
 * advice ("never share your OTP") is full of the vocabulary. Demanding both
 * keeps the false-positive rate low enough that this can safely sit in front of
 * even the block list.
 */
object OtpDetector {

    private val KEYWORD = Regex(
        """\b(otp|one[\s-]?time[\s-]?(password|code|pin)|verification[\s-]?code|""" +
            """security[\s-]?code|auth(?:entication)?[\s-]?code|passcode|2fa|mfa|""" +
            """code\s+is|confirmation[\s-]?code)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** 4–8 digits, optionally split once by a space or hyphen (e.g. "482-193"). */
    private val CODE = Regex("""(?<![\w.])\d{3,4}[\s-]?\d{1,5}(?![\w.])""")

    fun isOtp(title: String, text: String): Boolean {
        val haystack = "$title $text"
        if (!KEYWORD.containsMatchIn(haystack)) return false
        val match = CODE.find(haystack) ?: return false
        val digits = match.value.count { it.isDigit() }
        return digits in 4..8
    }
}
```

- [ ] **Step 4: Run to verify it passes**

```bash
./gradlew :app:testDebugUnitTest --tests "*OtpDetectorTest*"
```

Expected: PASS, 10 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/anuj/notificationfirewall/domain/wall/OtpDetector.kt \
        app/src/test/java/com/anuj/notificationfirewall/domain/wall/OtpDetectorTest.kt
git commit -m "feat(wall): local OTP fast-path detector"
```

---

### Task 4: Jev domain types and client

The network side of the engine. `JevClient` owns HTTP and parsing; nothing above it knows Jev's wire format.

**Files:**
- Create: `app/src/main/java/com/anuj/notificationfirewall/domain/wall/JevVerdict.kt`
- Create: `app/src/main/java/com/anuj/notificationfirewall/ai/jev/JevClient.kt`
- Create: `app/src/main/java/com/anuj/notificationfirewall/ai/jev/JevQuestions.kt`
- Test: `app/src/test/java/com/anuj/notificationfirewall/ai/jev/JevClientTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces, all used by Tasks 5–8:
  - `enum class NotificationCategory { PROMOTION, PERSONAL_MESSAGE, TRANSACTIONAL, WORK, SOCIAL, NEWS, SYSTEM, DELIVERY, OTHER }` with `companion object { fun fromWire(s: String): NotificationCategory }`
  - `data class JevVerdict(val importance: Float, val category: NotificationCategory, val isTimeSensitive: Float, val isFromHuman: Float, val needsAction: Float, val confidence: Float)`
  - `data class JevState(val app: String, val channel: String?, val title: String, val text: String, val arrivedAtLocal: String, val isReplyCapable: Boolean, val isFromContact: Boolean)`
  - `class JevException(message: String, cause: Throwable? = null) : Exception(message, cause)`
  - `interface JevApi { suspend fun classify(state: JevState): JevVerdict }`
  - `class JevClient(baseUrl: HttpUrl, apiKey: String, http: OkHttpClient) : JevApi`

- [ ] **Step 1: Write the domain types**

Create `app/src/main/java/com/anuj/notificationfirewall/domain/wall/JevVerdict.kt`:

```kotlin
package com.anuj.notificationfirewall.domain.wall

enum class NotificationCategory {
    PROMOTION,
    PERSONAL_MESSAGE,
    TRANSACTIONAL,
    WORK,
    SOCIAL,
    NEWS,
    SYSTEM,
    DELIVERY,
    OTHER;

    companion object {
        /** Maps Jev's wire option name back to the enum, defaulting to OTHER. */
        fun fromWire(s: String): NotificationCategory =
            entries.firstOrNull { it.name.equals(s, ignoreCase = true) } ?: OTHER
    }
}

/**
 * One Jev judgement of one notification.
 *
 * [importance] is on a 1.0–5.0 scale (1 = pure noise, 5 = interrupt now).
 * Jev's Score primitive returns a 0-based value; [JevClient] shifts it.
 *
 * [isTimeSensitive], [isFromHuman] and [needsAction] are Nouls — probabilities
 * in 0.0–1.0. [confidence] is Jev's calibrated confidence in the importance
 * judgement, and gates whether this verdict may be cached.
 */
data class JevVerdict(
    val importance: Float,
    val category: NotificationCategory,
    val isTimeSensitive: Float,
    val isFromHuman: Float,
    val needsAction: Float,
    val confidence: Float,
)

/** The content and local signals sent to Jev as the evaluation state. */
data class JevState(
    val app: String,
    val channel: String?,
    val title: String,
    val text: String,
    val arrivedAtLocal: String,
    val isReplyCapable: Boolean,
    val isFromContact: Boolean,
)

class JevException(message: String, cause: Throwable? = null) : Exception(message, cause)

interface JevApi {
    suspend fun classify(state: JevState): JevVerdict
}
```

- [ ] **Step 2: Write the failing client tests**

Create `app/src/test/java/com/anuj/notificationfirewall/ai/jev/JevClientTest.kt`:

```kotlin
package com.anuj.notificationfirewall.ai.jev

import com.anuj.notificationfirewall.domain.wall.JevException
import com.anuj.notificationfirewall.domain.wall.JevState
import com.anuj.notificationfirewall.domain.wall.NotificationCategory
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class JevClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: JevClient

    private val state = JevState(
        app = "Myntra",
        channel = "offers",
        title = "FLAT 70% OFF",
        text = "Shop now",
        arrivedAtLocal = "23:41",
        isReplyCapable = false,
        isFromContact = false,
    )

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        client = JevClient(server.url("/"), "test-key", OkHttpClient())
    }

    @After
    fun tearDown() = server.shutdown()

    private fun okBody(score: Double = 0.4, confidence: Double = 0.87) = """
        {
          "model": "jev-latest",
          "answers": {
            "importance": {
              "type": "score",
              "score": $score,
              "legend": {"0":"noise","1":"low","2":"routine","3":"matters","4":"critical"},
              "probabilities": {"0":0.6,"1":0.3,"2":0.1,"3":0.0,"4":0.0},
              "confidence": $confidence
            },
            "category": {
              "type": "choice",
              "choice": "promotion",
              "probabilities": {"promotion":0.95,"other":0.05},
              "confidence": 0.9
            },
            "is_time_sensitive": {"type":"noul","noul":0.12},
            "is_from_human": {"type":"noul","noul":0.03},
            "needs_action": {"type":"noul","noul":0.05}
          },
          "usage": {"input_tokens": 62, "output_tokens": 40}
        }
    """.trimIndent()

    @Test
    fun parsesVerdict_andShiftsScoreToOneBasedScale() = runTest {
        server.enqueue(MockResponse().setBody(okBody(score = 0.4)))

        val verdict = client.classify(state)

        assertEquals(1.4f, verdict.importance, 0.001f)
        assertEquals(NotificationCategory.PROMOTION, verdict.category)
        assertEquals(0.12f, verdict.isTimeSensitive, 0.001f)
        assertEquals(0.03f, verdict.isFromHuman, 0.001f)
        assertEquals(0.05f, verdict.needsAction, 0.001f)
        assertEquals(0.87f, verdict.confidence, 0.001f)
    }

    @Test
    fun topOfScaleMapsToFive() = runTest {
        server.enqueue(MockResponse().setBody(okBody(score = 4.0)))
        assertEquals(5.0f, client.classify(state).importance, 0.001f)
    }

    @Test
    fun sendsBearerTokenAndModelAndAllFiveQuestions() = runTest {
        server.enqueue(MockResponse().setBody(okBody()))
        client.classify(state)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/systemone", request.path)
        assertEquals("Bearer test-key", request.getHeader("Authorization"))

        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("jev-latest", body["model"]!!.jsonPrimitive.content)
        val questions = body["questions"]!!.jsonObject
        assertEquals(
            setOf("importance", "category", "is_time_sensitive", "is_from_human", "needs_action"),
            questions.keys,
        )
        assertEquals("score", questions["importance"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("choice", questions["category"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("noul", questions["needs_action"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun sendsStateFieldsIncludingLocalSignals() = runTest {
        server.enqueue(MockResponse().setBody(okBody()))
        client.classify(state)

        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val sent = body["state"]!!.jsonObject
        assertEquals("Myntra", sent["app"]!!.jsonPrimitive.content)
        assertEquals("FLAT 70% OFF", sent["title"]!!.jsonPrimitive.content)
        assertEquals("23:41", sent["arrived_at_local"]!!.jsonPrimitive.content)
        assertEquals("false", sent["is_reply_capable"]!!.jsonPrimitive.content)
        assertEquals("false", sent["is_from_contact"]!!.jsonPrimitive.content)
    }

    @Test
    fun unknownCategoryFallsBackToOther() = runTest {
        server.enqueue(
            MockResponse().setBody(okBody().replace("\"choice\": \"promotion\"", "\"choice\": \"weather\"")),
        )
        assertEquals(NotificationCategory.OTHER, client.classify(state).category)
    }

    @Test
    fun unauthorizedThrowsJevException() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"bad key"}"""))
        val e = assertThrows(JevException::class.java) { kotlinx.coroutines.runBlocking { client.classify(state) } }
        assertTrue(e.message!!.contains("401"))
    }

    @Test
    fun serverErrorThrowsJevException() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))
        assertThrows(JevException::class.java) { kotlinx.coroutines.runBlocking { client.classify(state) } }
    }

    @Test
    fun malformedBodyThrowsJevException() = runTest {
        server.enqueue(MockResponse().setBody("""{"answers":{}}"""))
        assertThrows(JevException::class.java) { kotlinx.coroutines.runBlocking { client.classify(state) } }
    }
}
```

- [ ] **Step 3: Run to verify it fails**

```bash
./gradlew :app:testDebugUnitTest --tests "*JevClientTest*"
```

Expected: FAIL — `JevClient` unresolved.

- [ ] **Step 4: Write the question definitions**

Create `app/src/main/java/com/anuj/notificationfirewall/ai/jev/JevQuestions.kt`:

```kotlin
package com.anuj.notificationfirewall.ai.jev

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The five questions asked of every notification, in one call.
 *
 * Jev evaluates questions in parallel against the same state, so asking five
 * costs roughly what asking one costs. Only [IMPORTANCE] drives the ring
 * decision; the rest exist so the Inbox can explain itself and so Ask can
 * answer questions about categories and humans-versus-machines later.
 *
 * This object is the single place notification policy is expressed. Changing
 * what the wall considers important is an edit here, under review.
 */
internal object JevQuestions {

    fun build(): JsonObject = buildJsonObject {
        put("importance", buildJsonObject {
            put("type", "score")
            put("instructions", "How much this notification deserves to interrupt the recipient right now")
            put("criteria", buildJsonArray {
                add("Pure noise: marketing, promotional offers, engagement bait, re-engagement nudges")
                add("Low: informational or automated, can wait days without any cost")
                add("Routine: worth reading today, no action needed within the hour")
                add("Matters: the recipient should know about this soon")
                add("Critical: time-critical or personally urgent, interrupt immediately")
            })
        })

        put("category", buildJsonObject {
            put("type", "choice")
            put("instructions", "What kind of notification this is")
            put("criteria", buildJsonObject {
                put("promotion", "Marketing, sales, offers, discounts, app re-engagement nudges")
                put("personal_message", "A message written by a person to the recipient")
                put("transactional", "Account activity: payments, OTPs, bookings, receipts, alerts")
                put("work", "Work tooling: email, chat, tickets, calendar, code review")
                put("social", "Social network activity: likes, follows, mentions, comments")
                put("news", "News, headlines, editorial content, sports scores")
                put("system", "Device or operating-system notices, updates, storage, battery")
                put("delivery", "Order, shipment, or ride status and tracking updates")
                put("other", "Does not fit any category above")
            })
        })

        put("is_time_sensitive", buildJsonObject {
            put("type", "noul")
            put("instructions", "Acting on this later rather than now would lose real value")
            put("criteria", buildJsonObject {
                put("true", "There is a deadline, a window, or something in progress right now")
                put("false", "Equally useful whenever the recipient gets to it")
            })
        })

        put("is_from_human", buildJsonObject {
            put("type", "noul")
            put("instructions", "A specific person wrote this to the recipient, rather than an automated system")
            put("criteria", buildJsonObject {
                put("true", "Written by a person: conversational, addressed to the recipient")
                put("false", "Automated, templated, or broadcast to many recipients")
            })
        })

        put("needs_action", buildJsonObject {
            put("type", "noul")
            put("instructions", "This expects a reply or an action from the recipient")
            put("criteria", buildJsonObject {
                put("true", "A question, request, approval, or task is directed at the recipient")
                put("false", "Purely informational, nothing is expected back")
            })
        })
    }
}
```

- [ ] **Step 5: Implement the client**

Create `app/src/main/java/com/anuj/notificationfirewall/ai/jev/JevClient.kt`:

```kotlin
package com.anuj.notificationfirewall.ai.jev

import com.anuj.notificationfirewall.domain.wall.JevApi
import com.anuj.notificationfirewall.domain.wall.JevException
import com.anuj.notificationfirewall.domain.wall.JevState
import com.anuj.notificationfirewall.domain.wall.JevVerdict
import com.anuj.notificationfirewall.domain.wall.NotificationCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
private const val MODEL = "jev-latest"

/**
 * Thin client over Jev's single System One endpoint.
 *
 * Every failure mode — transport, HTTP status, malformed body — surfaces as
 * [JevException], so the pipeline has exactly one thing to catch and one
 * degradation path (silence and store for later re-classification).
 */
class JevClient(
    private val baseUrl: HttpUrl,
    private val apiKey: String,
    private val http: OkHttpClient,
) : JevApi {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun classify(state: JevState): JevVerdict = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("model", MODEL)
            put("state", buildJsonObject {
                put("app", state.app)
                state.channel?.let { put("channel", it) }
                put("title", state.title)
                put("text", state.text)
                put("arrived_at_local", state.arrivedAtLocal)
                put("is_reply_capable", state.isReplyCapable)
                put("is_from_contact", state.isFromContact)
            })
            put("questions", JevQuestions.build())
        }

        val request = Request.Builder()
            .url(baseUrl.newBuilder().addPathSegments("v1/systemone").build())
            .header("Authorization", "Bearer $apiKey")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val raw = try {
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw JevException("Jev returned ${response.code}: ${text.take(200)}")
                }
                text
            }
        } catch (e: IOException) {
            throw JevException("Jev request failed", e)
        }

        parse(raw)
    }

    private fun parse(raw: String): JevVerdict = try {
        val answers = json.parseToJsonElement(raw).jsonObject
            .getValue("answers").jsonObject

        val importance = answers.getValue("importance").jsonObject
        val category = answers.getValue("category").jsonObject

        JevVerdict(
            // Jev's Score is 0-based across the legend; the app's scale is 1–5.
            importance = importance.getValue("score").jsonPrimitive.float + 1f,
            category = NotificationCategory.fromWire(
                category.getValue("choice").jsonPrimitive.content,
            ),
            isTimeSensitive = noul(answers, "is_time_sensitive"),
            isFromHuman = noul(answers, "is_from_human"),
            needsAction = noul(answers, "needs_action"),
            confidence = importance.getValue("confidence").jsonPrimitive.float,
        )
    } catch (e: Exception) {
        if (e is JevException) throw e
        throw JevException("Could not parse Jev response", e)
    }

    private fun noul(answers: Map<String, kotlinx.serialization.json.JsonElement>, key: String): Float =
        answers.getValue(key).jsonObject.getValue("noul").jsonPrimitive.float
}
```

- [ ] **Step 6: Run to verify it passes**

```bash
./gradlew :app:testDebugUnitTest --tests "*JevClientTest*"
```

Expected: PASS, 8 tests.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/anuj/notificationfirewall/domain/wall/JevVerdict.kt \
        app/src/main/java/com/anuj/notificationfirewall/ai/jev/ \
        app/src/test/java/com/anuj/notificationfirewall/ai/jev/
git commit -m "feat(wall): Jev client and the five classification questions"
```

---

### Task 5: Wall schema — entities, DAOs, migration 4→5

The whole data layer for the new engine in one task: it is a single schema version, and splitting a migration across tasks would leave the repo on a half-written schema.

**Files:**
- Create: `app/src/main/java/com/anuj/notificationfirewall/domain/wall/WallBucket.kt`
- Create: `app/src/main/java/com/anuj/notificationfirewall/data/db/entity/VerdictCacheEntity.kt`
- Create: `app/src/main/java/com/anuj/notificationfirewall/data/db/entity/SenderBiasEntity.kt`
- Create: `app/src/main/java/com/anuj/notificationfirewall/data/db/entity/OverrideEntity.kt`
- Create: `app/src/main/java/com/anuj/notificationfirewall/data/db/dao/VerdictCacheDao.kt`
- Create: `app/src/main/java/com/anuj/notificationfirewall/data/db/dao/SenderBiasDao.kt`
- Create: `app/src/main/java/com/anuj/notificationfirewall/data/db/dao/OverrideDao.kt`
- Modify: `app/src/main/java/com/anuj/notificationfirewall/data/db/entity/NotificationRecordEntity.kt`
- Modify: `app/src/main/java/com/anuj/notificationfirewall/data/db/dao/NotificationDao.kt`
- Modify: `app/src/main/java/com/anuj/notificationfirewall/data/db/NfDatabase.kt`
- Modify: `app/src/main/java/com/anuj/notificationfirewall/di/AppModule.kt`
- Test: `app/src/test/java/com/anuj/notificationfirewall/data/db/MigrationTest.kt` (extend)

**Interfaces:**
- Consumes: `NotificationCategory` (Task 4).
- Produces:
  - `enum class WallBucket { RING, SILENCE, DROP }`
  - `enum class WallDecisionSource { OTP, VIP, BLOCK, CACHE, JEV, PENDING, LEGACY }`
  - `enum class OverrideKind { VIP, BLOCK }`, `enum class OverrideSource { MANUAL, SWIPE }`
  - Entities and DAOs named above; `NfDatabase` at **version 5** exposing `verdictCacheDao()`, `senderBiasDao()`, `overrideDao()`, plus `MIGRATION_4_5`.

- [ ] **Step 1: Write the bucket and source enums**

Create `app/src/main/java/com/anuj/notificationfirewall/domain/wall/WallBucket.kt`:

```kotlin
package com.anuj.notificationfirewall.domain.wall

/**
 * What the wall did with a notification.
 *
 * DROP is reachable only from the user's explicit block list. Jev is never
 * allowed to produce it: a judgement the user cannot find and review is a
 * judgement they cannot trust, and an untrusted wall gets disarmed.
 */
enum class WallBucket { RING, SILENCE, DROP }

/** Which stage of the pipeline produced the decision. */
enum class WallDecisionSource { OTP, VIP, BLOCK, CACHE, JEV, PENDING, LEGACY }

enum class OverrideKind { VIP, BLOCK }

/** Whether an override was added deliberately in Settings or accumulated from a swipe. */
enum class OverrideSource { MANUAL, SWIPE }
```

- [ ] **Step 2: Write the failing migration test**

Append to `app/src/test/java/com/anuj/notificationfirewall/data/db/MigrationTest.kt` (inside the class):

```kotlin
    @Test
    fun migrate4To5_preservesNotifications_asLegacyRows() {
        helper.createDatabase(TEST_DB, 4).use { db ->
            db.execSQL(
                """
                INSERT INTO notifications
                  (packageName, appLabel, title, text, timestampEpochMs, senderKey,
                   activeProfileId, matchedRuleId, decisionSource, bucket,
                   aiUrgent, aiReason, isRead)
                VALUES
                  ('com.myntra', 'Myntra', 'FLAT 70% OFF', 'Shop now', 1700000000000, 'Myntra',
                   1, NULL, 'AI', 'SILENCE', 0, 'marketing', 0),
                  ('com.whatsapp', 'WhatsApp', 'Mom', 'Call me', 1700000001000, 'Mom',
                   1, 2, 'RULE', 'LET_THROUGH_CUSTOM_SOUND', NULL, NULL, 1)
                """.trimIndent(),
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 5, true, MIGRATION_4_5)

        db.query("SELECT decisionSource, bucket, pendingClassification FROM notifications ORDER BY timestampEpochMs")
            .use { c ->
                assertEquals(2, c.count)
                c.moveToFirst()
                assertEquals("LEGACY", c.getString(0))
                assertEquals("SILENCE", c.getString(1))
                assertEquals(0, c.getInt(2))
                c.moveToNext()
                assertEquals("LEGACY", c.getString(0))
                assertEquals("RING", c.getString(1))
            }
    }

    @Test
    fun migrate4To5_createsWallTables_andLeavesProfileTablesForTask11() {
        helper.createDatabase(TEST_DB, 4).close()
        val db = helper.runMigrationsAndValidate(TEST_DB, 5, true, MIGRATION_4_5)

        fun tableExists(name: String): Boolean =
            db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='$name'")
                .use { it.count > 0 }

        // Still declared entities at v5, so they must still exist or Room's
        // schema validation fails on open. Task 11 drops them.
        assertTrue(tableExists("profiles"))
        assertTrue(tableExists("rules"))
        assertTrue(tableExists("verdict_cache"))
        assertTrue(tableExists("sender_bias"))
        assertTrue(tableExists("overrides"))
    }
```

Add `import org.junit.Assert.assertFalse` to the file's imports.

- [ ] **Step 3: Run to verify it fails**

```bash
./gradlew :app:testDebugUnitTest --tests "*MigrationTest*"
```

Expected: FAIL — `MIGRATION_4_5` unresolved.

- [ ] **Step 4: Rewrite `NotificationRecordEntity`**

Replace `app/src/main/java/com/anuj/notificationfirewall/data/db/entity/NotificationRecordEntity.kt` with:

```kotlin
package com.anuj.notificationfirewall.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.anuj.notificationfirewall.domain.wall.NotificationCategory
import com.anuj.notificationfirewall.domain.wall.WallBucket
import com.anuj.notificationfirewall.domain.wall.WallDecisionSource

/**
 * One notification the wall saw, and what it did about it.
 *
 * Metadata is kept indefinitely; [title] and [text] are nulled by the retention
 * job after the text window elapses, with [textPurgedAt] recording when.
 */
@Entity(
    tableName = "notifications",
    indices = [
        Index("timestampEpochMs"),
        Index("packageName"),
        Index("pendingClassification"),
    ],
)
data class NotificationRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val appLabel: String,
    val title: String?,
    val text: String?,
    val timestampEpochMs: Long,
    val senderKey: String?,
    val contentShape: String,
    /** Jev's raw 1–5 importance, before bias. Null when no Jev verdict exists. */
    val importanceScore: Float?,
    /** The bias actually added before the threshold comparison. */
    val biasApplied: Float,
    val category: NotificationCategory?,
    val isTimeSensitive: Float?,
    val isFromHuman: Float?,
    val needsAction: Float?,
    val jevConfidence: Float?,
    val decisionSource: WallDecisionSource,
    val bucket: WallBucket,
    /** True when this was silenced without a verdict and awaits re-classification. */
    val pendingClassification: Boolean,
    val textPurgedAt: Long?,
    val isRead: Boolean,
)
```

- [ ] **Step 5: Write the three new entities**

Create `app/src/main/java/com/anuj/notificationfirewall/data/db/entity/VerdictCacheEntity.kt`:

```kotlin
package com.anuj.notificationfirewall.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.anuj.notificationfirewall.domain.wall.NotificationCategory

/**
 * A reusable Jev verdict, keyed by content shape.
 *
 * Only machine-sender, high-confidence verdicts are ever written here — see
 * VerdictCache for the two rules that govern admission.
 */
@Entity(tableName = "verdict_cache")
data class VerdictCacheEntity(
    @PrimaryKey val contentShape: String,
    val packageName: String,
    val senderKey: String?,
    val importance: Float,
    val category: NotificationCategory,
    val isTimeSensitive: Float,
    val isFromHuman: Float,
    val needsAction: Float,
    val confidence: Float,
    val hitCount: Int,
    val createdAtEpochMs: Long,
    val lastUsedEpochMs: Long,
)
```

Create `app/src/main/java/com/anuj/notificationfirewall/data/db/entity/SenderBiasEntity.kt`:

```kotlin
package com.anuj.notificationfirewall.data.db

import androidx.room.Entity

/**
 * A learned nudge for one (app, sender) pair, in importance-scale units.
 *
 * Clamped to ±0.75 so it can never overturn a decisive Jev judgement — it tunes
 * the borderline cases and nothing else.
 */
@Entity(tableName = "sender_bias", primaryKeys = ["packageName", "senderKey"])
data class SenderBiasEntity(
    val packageName: String,
    val senderKey: String,
    val bias: Float,
    val correctionCount: Int,
    val lastCorrectedEpochMs: Long,
)
```

Create `app/src/main/java/com/anuj/notificationfirewall/data/db/entity/OverrideEntity.kt`:

```kotlin
package com.anuj.notificationfirewall.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.anuj.notificationfirewall.domain.wall.OverrideKind
import com.anuj.notificationfirewall.domain.wall.OverrideSource

/**
 * A hard user decision that bypasses Jev entirely.
 *
 * A null [senderKey] means the override applies to the whole app.
 */
@Entity(
    tableName = "overrides",
    indices = [Index(value = ["packageName", "senderKey"])],
)
data class OverrideEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: OverrideKind,
    val packageName: String,
    val senderKey: String?,
    val label: String,
    val source: OverrideSource,
    val createdAtEpochMs: Long,
)
```

- [ ] **Step 6: Write the DAOs**

Create `app/src/main/java/com/anuj/notificationfirewall/data/db/dao/VerdictCacheDao.kt`:

```kotlin
package com.anuj.notificationfirewall.data.db.dao

import androidx.room.Dao
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.anuj.notificationfirewall.data.db.VerdictCacheEntity

@Dao
interface VerdictCacheDao {
    @Query("SELECT * FROM verdict_cache WHERE contentShape = :shape")
    suspend fun find(shape: String): VerdictCacheEntity?

    @Upsert
    suspend fun upsert(entry: VerdictCacheEntity)

    @Query("UPDATE verdict_cache SET hitCount = hitCount + 1, lastUsedEpochMs = :nowMs WHERE contentShape = :shape")
    suspend fun recordHit(shape: String, nowMs: Long)

    @Query("DELETE FROM verdict_cache WHERE contentShape = :shape")
    suspend fun evict(shape: String)

    @Query("DELETE FROM verdict_cache WHERE lastUsedEpochMs < :cutoffMs")
    suspend fun evictUnusedSince(cutoffMs: Long): Int

    @Query("SELECT COUNT(*) FROM verdict_cache")
    suspend fun count(): Int
}
```

Create `app/src/main/java/com/anuj/notificationfirewall/data/db/dao/SenderBiasDao.kt`:

```kotlin
package com.anuj.notificationfirewall.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.anuj.notificationfirewall.data.db.SenderBiasEntity

@Dao
interface SenderBiasDao {
    @Query("SELECT * FROM sender_bias WHERE packageName = :pkg AND senderKey = :sender")
    suspend fun find(pkg: String, sender: String): SenderBiasEntity?

    @Upsert
    suspend fun upsert(entry: SenderBiasEntity)

    @Query("DELETE FROM sender_bias WHERE packageName = :pkg AND senderKey = :sender")
    suspend fun clear(pkg: String, sender: String)
}
```

Create `app/src/main/java/com/anuj/notificationfirewall/data/db/dao/OverrideDao.kt`:

```kotlin
package com.anuj.notificationfirewall.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import com.anuj.notificationfirewall.data.db.OverrideEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface OverrideDao {
    /**
     * Every override that could apply to this notification: app-wide entries
     * (senderKey IS NULL) and entries for this exact sender.
     */
    @Query(
        "SELECT * FROM overrides WHERE packageName = :pkg AND (senderKey IS NULL OR senderKey = :sender)",
    )
    suspend fun matching(pkg: String, sender: String?): List<OverrideEntity>

    @Query("SELECT * FROM overrides ORDER BY createdAtEpochMs DESC")
    fun observeAll(): Flow<List<OverrideEntity>>

    @Insert
    suspend fun insert(entry: OverrideEntity): Long

    @Delete
    suspend fun delete(entry: OverrideEntity)
}
```

- [ ] **Step 7: Extend `NotificationDao`**

Replace `app/src/main/java/com/anuj/notificationfirewall/data/db/dao/NotificationDao.kt` with:

```kotlin
package com.anuj.notificationfirewall.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.anuj.notificationfirewall.data.db.NotificationRecordEntity
import com.anuj.notificationfirewall.domain.wall.NotificationCategory
import com.anuj.notificationfirewall.domain.wall.WallBucket
import com.anuj.notificationfirewall.domain.wall.WallDecisionSource
import kotlinx.coroutines.flow.Flow

@Dao
interface NotificationDao {
    @Insert
    suspend fun insert(rec: NotificationRecordEntity): Long

    @Query("SELECT * FROM notifications ORDER BY timestampEpochMs DESC LIMIT :limit")
    fun observeRecent(limit: Int = 500): Flow<List<NotificationRecordEntity>>

    @Query("SELECT * FROM notifications WHERE timestampEpochMs BETWEEN :startMs AND :endMs ORDER BY timestampEpochMs")
    suspend fun recordsBetween(startMs: Long, endMs: Long): List<NotificationRecordEntity>

    @Query("SELECT * FROM notifications WHERE pendingClassification = 1 ORDER BY timestampEpochMs LIMIT :limit")
    suspend fun pending(limit: Int): List<NotificationRecordEntity>

    @Query(
        """
        UPDATE notifications
        SET importanceScore = :importance, category = :category,
            isTimeSensitive = :timeSensitive, isFromHuman = :fromHuman,
            needsAction = :needsAction, jevConfidence = :confidence,
            bucket = :bucket, decisionSource = :source, pendingClassification = 0
        WHERE id = :id
        """,
    )
    suspend fun applyVerdict(
        id: Long,
        importance: Float,
        category: NotificationCategory,
        timeSensitive: Float,
        fromHuman: Float,
        needsAction: Float,
        confidence: Float,
        bucket: WallBucket,
        source: WallDecisionSource,
    )

    @Query("UPDATE notifications SET isRead = 1 WHERE id = :id")
    suspend fun markRead(id: Long)

    @Query(
        "UPDATE notifications SET title = NULL, text = NULL, textPurgedAt = :nowMs " +
            "WHERE timestampEpochMs < :cutoffMs AND textPurgedAt IS NULL",
    )
    suspend fun purgeTextBefore(cutoffMs: Long, nowMs: Long): Int
}
```

- [ ] **Step 8: Extend `Converters` for the new enums**

Open `app/src/main/java/com/anuj/notificationfirewall/data/db/Converters.kt` and add converters following the existing pattern in that file (each enum stored as its `name`, read back with `valueOf`), for: `WallBucket`, `WallDecisionSource`, `NotificationCategory` (nullable), `OverrideKind`, `OverrideSource`. Remove the `BucketAction` and `DecisionSource` converters only if nothing else in the file references them — otherwise leave them until Task 11.

- [ ] **Step 9: Write migration 4→5 and register the new entities**

In `NfDatabase.kt`, add below `MIGRATION_3_4`:

```kotlin
/**
 * Swaps the profile/rule model for the wall model.
 *
 * The notifications table is rebuilt rather than altered because SQLite cannot
 * drop columns below API 34, and the old profile/rule foreign keys are exactly
 * the columns that must go. Existing rows survive with their content and
 * timestamps intact, marked LEGACY since their verdicts came from a scoring
 * model that no longer exists.
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE notifications_new (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                packageName TEXT NOT NULL,
                appLabel TEXT NOT NULL,
                title TEXT,
                text TEXT,
                timestampEpochMs INTEGER NOT NULL,
                senderKey TEXT,
                contentShape TEXT NOT NULL,
                importanceScore REAL,
                biasApplied REAL NOT NULL,
                category TEXT,
                isTimeSensitive REAL,
                isFromHuman REAL,
                needsAction REAL,
                jevConfidence REAL,
                decisionSource TEXT NOT NULL,
                bucket TEXT NOT NULL,
                pendingClassification INTEGER NOT NULL,
                textPurgedAt INTEGER,
                isRead INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO notifications_new
                (id, packageName, appLabel, title, text, timestampEpochMs, senderKey,
                 contentShape, importanceScore, biasApplied, category, isTimeSensitive,
                 isFromHuman, needsAction, jevConfidence, decisionSource, bucket,
                 pendingClassification, textPurgedAt, isRead)
            SELECT id, packageName, appLabel, title, text, timestampEpochMs, senderKey,
                   '', NULL, 0.0, NULL, NULL, NULL, NULL, NULL, 'LEGACY',
                   CASE bucket
                       WHEN 'LET_THROUGH_AS_IS' THEN 'RING'
                       WHEN 'LET_THROUGH_CUSTOM_SOUND' THEN 'RING'
                       WHEN 'CAPTURE' THEN 'SILENCE'
                       ELSE 'SILENCE'
                   END,
                   0, NULL, isRead
            FROM notifications
            """.trimIndent(),
        )
        db.execSQL("DROP TABLE notifications")
        db.execSQL("ALTER TABLE notifications_new RENAME TO notifications")
        db.execSQL("CREATE INDEX index_notifications_timestampEpochMs ON notifications (timestampEpochMs)")
        db.execSQL("CREATE INDEX index_notifications_packageName ON notifications (packageName)")
        db.execSQL("CREATE INDEX index_notifications_pendingClassification ON notifications (pendingClassification)")

        // The profiles and rules tables are deliberately NOT dropped here.
        // ProfileEntity and RuleEntity are still declared on the @Database at
        // version 5, and Room validates the open database against its declared
        // schema — dropping a declared table crashes on open. Task 11 drops both
        // tables in MIGRATION_5_6, in the same version that removes the entities.

        db.execSQL(
            """
            CREATE TABLE verdict_cache (
                contentShape TEXT PRIMARY KEY NOT NULL,
                packageName TEXT NOT NULL,
                senderKey TEXT,
                importance REAL NOT NULL,
                category TEXT NOT NULL,
                isTimeSensitive REAL NOT NULL,
                isFromHuman REAL NOT NULL,
                needsAction REAL NOT NULL,
                confidence REAL NOT NULL,
                hitCount INTEGER NOT NULL,
                createdAtEpochMs INTEGER NOT NULL,
                lastUsedEpochMs INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE sender_bias (
                packageName TEXT NOT NULL,
                senderKey TEXT NOT NULL,
                bias REAL NOT NULL,
                correctionCount INTEGER NOT NULL,
                lastCorrectedEpochMs INTEGER NOT NULL,
                PRIMARY KEY (packageName, senderKey)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE overrides (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                kind TEXT NOT NULL,
                packageName TEXT NOT NULL,
                senderKey TEXT,
                label TEXT NOT NULL,
                source TEXT NOT NULL,
                createdAtEpochMs INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX index_overrides_packageName_senderKey ON overrides (packageName, senderKey)")
    }
}
```

Then update the `@Database` annotation and abstract methods:

```kotlin
@Database(
    entities = [
        ProfileEntity::class,
        RuleEntity::class,
        NotificationRecordEntity::class,
        VerdictCacheEntity::class,
        SenderBiasEntity::class,
        OverrideEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class NfDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun ruleDao(): RuleDao
    abstract fun notificationDao(): NotificationDao
    abstract fun verdictCacheDao(): VerdictCacheDao
    abstract fun senderBiasDao(): SenderBiasDao
    abstract fun overrideDao(): OverrideDao
}
```

`ProfileEntity` and `RuleEntity` stay registered as Room entities for now, and their tables are deliberately left in place by `MIGRATION_4_5`. Room validates the open database against the schema its declared entities describe, so dropping a still-declared table would crash the app on launch. The old pipeline keeps compiling against them, and Task 11 removes the entities and drops the tables together in version 6. This is deliberate: it keeps this task's migration testable without a simultaneous rewrite of the listener.

- [ ] **Step 10: Register the new DAOs in `AppModule`**

Add to `app/src/main/java/com/anuj/notificationfirewall/di/AppModule.kt`:

```kotlin
    @Provides
    fun provideVerdictCacheDao(db: NfDatabase): VerdictCacheDao = db.verdictCacheDao()

    @Provides
    fun provideSenderBiasDao(db: NfDatabase): SenderBiasDao = db.senderBiasDao()

    @Provides
    fun provideOverrideDao(db: NfDatabase): OverrideDao = db.overrideDao()
```

and extend the migration list:

```kotlin
            .addMigrations(MIGRATION_3_4, MIGRATION_4_5)
```

- [ ] **Step 11: Fix the old pipeline's compile errors**

`NfListenerService` still constructs a `NotificationRecordEntity` with the old field set, and `BucketExecutor` still switches on `BucketAction`. Make the listener compile against the new entity by passing the new fields with placeholder values — it is rewritten wholesale in Task 10:

```kotlin
                NotificationRecordEntity(
                    packageName = incoming.packageName,
                    appLabel = incoming.appLabel,
                    title = incoming.title,
                    text = incoming.text,
                    timestampEpochMs = sbn.postTime,
                    senderKey = incoming.senderKey.ifBlank { null },
                    contentShape = "",
                    importanceScore = null,
                    biasApplied = 0f,
                    category = null,
                    isTimeSensitive = null,
                    isFromHuman = null,
                    needsAction = null,
                    jevConfidence = null,
                    decisionSource = WallDecisionSource.LEGACY,
                    bucket = when (result.bucket) {
                        BucketAction.LET_THROUGH_AS_IS,
                        BucketAction.LET_THROUGH_CUSTOM_SOUND -> WallBucket.RING
                        else -> WallBucket.SILENCE
                    },
                    pendingClassification = false,
                    textPurgedAt = null,
                    isRead = false,
                )
```

- [ ] **Step 12: Run the migration tests**

```bash
./gradlew :app:testDebugUnitTest --tests "*MigrationTest*"
```

Expected: PASS, 3 tests.

- [ ] **Step 13: Run the full suite and build**

```bash
./gradlew :app:testDebugUnitTest && ./gradlew :app:assembleDebug
```

Expected: both succeed.

- [ ] **Step 14: Commit**

```bash
git add -A
git commit -m "feat(wall): wall schema — verdict cache, sender bias, overrides

Migration 4->5 rebuilds the notifications table around the wall model
(importance, category, nouls, confidence, pending flag, text purge
stamp), drops the profile and rule tables, and adds the three new wall
tables. Existing notification history survives as LEGACY rows."
```

---

### Task 6: Verdict cache with its two admission rules

The cache is where the user's stated concern lives — a WhatsApp thread carrying both family messages and forwarded promos must never let one verdict answer for the other. Both rules are encoded here and tested directly.

**Files:**
- Create: `app/src/main/java/com/anuj/notificationfirewall/domain/wall/VerdictCache.kt`
- Test: `app/src/test/java/com/anuj/notificationfirewall/domain/wall/VerdictCacheTest.kt`

**Interfaces:**
- Consumes: `VerdictCacheDao`, `VerdictCacheEntity` (Task 5); `JevVerdict`, `NotificationCategory` (Task 4).
- Produces:
```kotlin
class VerdictCache(dao: VerdictCacheDao, clock: () -> Long) {
    suspend fun get(shape: String): JevVerdict?
    suspend fun put(shape: String, pkg: String, sender: String?, verdict: JevVerdict): Boolean
    suspend fun evict(shape: String)
    suspend fun evictStale(maxAgeDays: Long): Int
}
```
`put` returns true when the verdict was stored, false when an admission rule rejected it. Used by Task 8.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/anuj/notificationfirewall/domain/wall/VerdictCacheTest.kt`:

```kotlin
package com.anuj.notificationfirewall.domain.wall

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.anuj.notificationfirewall.data.db.NfDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private const val DAY_MS = 24L * 60 * 60 * 1000

@RunWith(RobolectricTestRunner::class)
class VerdictCacheTest {

    private lateinit var db: NfDatabase
    private lateinit var cache: VerdictCache
    private var now = 1_700_000_000_000L

    private fun verdict(
        importance: Float = 1.4f,
        fromHuman: Float = 0.03f,
        confidence: Float = 0.9f,
    ) = JevVerdict(
        importance = importance,
        category = NotificationCategory.PROMOTION,
        isTimeSensitive = 0.1f,
        isFromHuman = fromHuman,
        needsAction = 0.05f,
        confidence = confidence,
    )

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            NfDatabase::class.java,
        ).allowMainThreadQueries().build()
        cache = VerdictCache(db.verdictCacheDao()) { now }
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun machineSenderHighConfidence_isCachedAndReadBack() = runTest {
        assertTrue(cache.put("shape1", "com.myntra", "Myntra", verdict()))

        val found = cache.get("shape1")
        assertNotNull(found)
        assertEquals(1.4f, found!!.importance, 0.001f)
        assertEquals(NotificationCategory.PROMOTION, found.category)
    }

    @Test
    fun humanSenderIsNeverCached() = runTest {
        val stored = cache.put("shape2", "com.whatsapp", "Mom", verdict(fromHuman = 0.7f))

        assertFalse(stored)
        assertNull(cache.get("shape2"))
    }

    @Test
    fun humanSenderWellAboveThresholdIsNeverCached() = runTest {
        assertFalse(cache.put("shape3", "com.whatsapp", "Mom", verdict(fromHuman = 0.98f)))
        assertNull(cache.get("shape3"))
    }

    @Test
    fun justBelowHumanThresholdIsStillCached() = runTest {
        assertTrue(cache.put("shape4", "com.linkedin", "LinkedIn", verdict(fromHuman = 0.69f)))
        assertNotNull(cache.get("shape4"))
    }

    @Test
    fun lowConfidenceIsNeverCached() = runTest {
        assertFalse(cache.put("shape5", "com.myntra", "Myntra", verdict(confidence = 0.59f)))
        assertNull(cache.get("shape5"))
    }

    @Test
    fun confidenceAtThresholdIsCached() = runTest {
        assertTrue(cache.put("shape6", "com.myntra", "Myntra", verdict(confidence = 0.6f)))
        assertNotNull(cache.get("shape6"))
    }

    @Test
    fun readingAVerdictRecordsAHit() = runTest {
        cache.put("shape7", "com.myntra", "Myntra", verdict())
        cache.get("shape7")
        cache.get("shape7")

        val entry = db.verdictCacheDao().find("shape7")!!
        assertEquals(2, entry.hitCount)
    }

    @Test
    fun evictRemovesTheEntry() = runTest {
        cache.put("shape8", "com.myntra", "Myntra", verdict())
        cache.evict("shape8")
        assertNull(cache.get("shape8"))
    }

    @Test
    fun evictStaleRemovesOnlyEntriesUnusedBeyondTheWindow() = runTest {
        cache.put("old", "com.a", "A", verdict())
        now += 100 * DAY_MS
        cache.put("fresh", "com.b", "B", verdict())

        val removed = cache.evictStale(maxAgeDays = 90)

        assertEquals(1, removed)
        assertNull(cache.get("old"))
        assertNotNull(cache.get("fresh"))
    }

    @Test
    fun missReturnsNull() = runTest {
        assertNull(cache.get("never-stored"))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
./gradlew :app:testDebugUnitTest --tests "*VerdictCacheTest*"
```

Expected: FAIL — `VerdictCache` unresolved.

- [ ] **Step 3: Implement**

Create `app/src/main/java/com/anuj/notificationfirewall/domain/wall/VerdictCache.kt`:

```kotlin
package com.anuj.notificationfirewall.domain.wall

import com.anuj.notificationfirewall.data.db.VerdictCacheEntity
import com.anuj.notificationfirewall.data.db.dao.VerdictCacheDao

/** Above this, the sender is a person and the verdict is single-use. */
private const val HUMAN_THRESHOLD = 0.7f

/** Below this, Jev was unsure and the verdict must not be made permanent. */
private const val CONFIDENCE_THRESHOLD = 0.6f

/**
 * Reusable Jev verdicts, keyed by content shape.
 *
 * Two admission rules, both load-bearing:
 *
 * 1. **A verdict about a human's message is never cached.** One WhatsApp thread
 *    is one sender, but "reached home safely" and a forwarded sale are not the
 *    same notification. Messages from people are judged fresh, every time.
 * 2. **A low-confidence verdict is never cached.** Jev is calibrated, so a low
 *    confidence genuinely means "unsure" — writing that down would let one
 *    uncertain call mislabel a sender permanently.
 */
class VerdictCache(
    private val dao: VerdictCacheDao,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    suspend fun get(shape: String): JevVerdict? {
        val entry = dao.find(shape) ?: return null
        dao.recordHit(shape, clock())
        return JevVerdict(
            importance = entry.importance,
            category = entry.category,
            isTimeSensitive = entry.isTimeSensitive,
            isFromHuman = entry.isFromHuman,
            needsAction = entry.needsAction,
            confidence = entry.confidence,
        )
    }

    /** Returns true when the verdict was admitted, false when a rule rejected it. */
    suspend fun put(shape: String, pkg: String, sender: String?, verdict: JevVerdict): Boolean {
        if (verdict.isFromHuman >= HUMAN_THRESHOLD) return false
        if (verdict.confidence < CONFIDENCE_THRESHOLD) return false

        val now = clock()
        dao.upsert(
            VerdictCacheEntity(
                contentShape = shape,
                packageName = pkg,
                senderKey = sender,
                importance = verdict.importance,
                category = verdict.category,
                isTimeSensitive = verdict.isTimeSensitive,
                isFromHuman = verdict.isFromHuman,
                needsAction = verdict.needsAction,
                confidence = verdict.confidence,
                hitCount = 0,
                createdAtEpochMs = now,
                lastUsedEpochMs = now,
            ),
        )
        return true
    }

    suspend fun evict(shape: String) = dao.evict(shape)

    suspend fun evictStale(maxAgeDays: Long): Int =
        dao.evictUnusedSince(clock() - maxAgeDays * 24 * 60 * 60 * 1000)
}
```

- [ ] **Step 4: Run to verify it passes**

```bash
./gradlew :app:testDebugUnitTest --tests "*VerdictCacheTest*"
```

Expected: PASS, 10 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/anuj/notificationfirewall/domain/wall/VerdictCache.kt \
        app/src/test/java/com/anuj/notificationfirewall/domain/wall/VerdictCacheTest.kt
git commit -m "feat(wall): verdict cache with human-sender and confidence admission rules"
```

---

### Task 7: Bias store and override store

The two ways a user's intent enters the pipeline. Bias is soft and learned; overrides are hard and deliberate. Keeping them in separate classes keeps that distinction visible in the code rather than only in a doc.

**Files:**
- Create: `app/src/main/java/com/anuj/notificationfirewall/domain/wall/BiasStore.kt`
- Create: `app/src/main/java/com/anuj/notificationfirewall/domain/wall/OverrideStore.kt`
- Test: `app/src/test/java/com/anuj/notificationfirewall/domain/wall/BiasStoreTest.kt`
- Test: `app/src/test/java/com/anuj/notificationfirewall/domain/wall/OverrideStoreTest.kt`

**Interfaces:**
- Consumes: `SenderBiasDao`, `OverrideDao`, `SenderBiasEntity`, `OverrideEntity`, `OverrideKind`, `OverrideSource` (Task 5).
- Produces:
```kotlin
enum class Correction { SHOULD_HAVE_RUNG, SHOULD_HAVE_BEEN_SILENT }

class BiasStore(dao: SenderBiasDao, clock: () -> Long) {
    suspend fun biasFor(pkg: String, sender: String?): Float
    suspend fun record(pkg: String, sender: String?, correction: Correction): Float
    suspend fun clear(pkg: String, sender: String)
    companion object { const val MAX_BIAS = 0.75f; const val STEP = 0.25f }
}

class OverrideStore(dao: OverrideDao) {
    suspend fun kindFor(pkg: String, sender: String?): OverrideKind?
    suspend fun add(kind: OverrideKind, pkg: String, sender: String?, label: String, source: OverrideSource): Long
    fun observeAll(): Flow<List<OverrideEntity>>
    suspend fun remove(entry: OverrideEntity)
}
```
Used by Task 8.

- [ ] **Step 1: Write the failing bias tests**

Create `app/src/test/java/com/anuj/notificationfirewall/domain/wall/BiasStoreTest.kt`:

```kotlin
package com.anuj.notificationfirewall.domain.wall

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.anuj.notificationfirewall.data.db.NfDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BiasStoreTest {

    private lateinit var db: NfDatabase
    private lateinit var store: BiasStore

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            NfDatabase::class.java,
        ).allowMainThreadQueries().build()
        store = BiasStore(db.senderBiasDao()) { 1_700_000_000_000L }
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun unknownSenderHasZeroBias() = runTest {
        assertEquals(0f, store.biasFor("com.myntra", "Myntra"), 0.001f)
    }

    @Test
    fun nullSenderHasZeroBiasAndIsNotPersisted() = runTest {
        assertEquals(0f, store.biasFor("com.myntra", null), 0.001f)
        assertEquals(0f, store.record("com.myntra", null, Correction.SHOULD_HAVE_RUNG), 0.001f)
    }

    @Test
    fun oneSilentCorrectionMovesBiasDownOneStep() = runTest {
        val bias = store.record("com.myntra", "Myntra", Correction.SHOULD_HAVE_BEEN_SILENT)
        assertEquals(-0.25f, bias, 0.001f)
        assertEquals(-0.25f, store.biasFor("com.myntra", "Myntra"), 0.001f)
    }

    @Test
    fun oneRingCorrectionMovesBiasUpOneStep() = runTest {
        assertEquals(0.25f, store.record("com.slack", "Boss", Correction.SHOULD_HAVE_RUNG), 0.001f)
    }

    @Test
    fun biasIsClampedAtNegativeThreeQuarters() = runTest {
        repeat(10) { store.record("com.myntra", "Myntra", Correction.SHOULD_HAVE_BEEN_SILENT) }
        assertEquals(-0.75f, store.biasFor("com.myntra", "Myntra"), 0.001f)
    }

    @Test
    fun biasIsClampedAtPositiveThreeQuarters() = runTest {
        repeat(10) { store.record("com.slack", "Boss", Correction.SHOULD_HAVE_RUNG) }
        assertEquals(0.75f, store.biasFor("com.slack", "Boss"), 0.001f)
    }

    @Test
    fun correctionsInOppositeDirectionsCancel() = runTest {
        store.record("com.x", "S", Correction.SHOULD_HAVE_BEEN_SILENT)
        store.record("com.x", "S", Correction.SHOULD_HAVE_BEEN_SILENT)
        store.record("com.x", "S", Correction.SHOULD_HAVE_RUNG)
        assertEquals(-0.25f, store.biasFor("com.x", "S"), 0.001f)
    }

    @Test
    fun correctionCountIsTracked() = runTest {
        repeat(3) { store.record("com.x", "S", Correction.SHOULD_HAVE_BEEN_SILENT) }
        assertEquals(3, db.senderBiasDao().find("com.x", "S")!!.correctionCount)
    }

    @Test
    fun biasIsScopedToTheSenderNotTheApp() = runTest {
        store.record("com.whatsapp", "Myntra Offers", Correction.SHOULD_HAVE_BEEN_SILENT)
        assertEquals(0f, store.biasFor("com.whatsapp", "Mom"), 0.001f)
    }

    @Test
    fun clearResetsASender() = runTest {
        store.record("com.x", "S", Correction.SHOULD_HAVE_BEEN_SILENT)
        store.clear("com.x", "S")
        assertEquals(0f, store.biasFor("com.x", "S"), 0.001f)
    }
}
```

- [ ] **Step 2: Write the failing override tests**

Create `app/src/test/java/com/anuj/notificationfirewall/domain/wall/OverrideStoreTest.kt`:

```kotlin
package com.anuj.notificationfirewall.domain.wall

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.anuj.notificationfirewall.data.db.NfDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OverrideStoreTest {

    private lateinit var db: NfDatabase
    private lateinit var store: OverrideStore

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            NfDatabase::class.java,
        ).allowMainThreadQueries().build()
        store = OverrideStore(db.overrideDao())
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun noOverrideReturnsNull() = runTest {
        assertNull(store.kindFor("com.myntra", "Myntra"))
    }

    @Test
    fun appWideBlockMatchesAnySender() = runTest {
        store.add(OverrideKind.BLOCK, "com.myntra", null, "Myntra", OverrideSource.MANUAL)
        assertEquals(OverrideKind.BLOCK, store.kindFor("com.myntra", "anyone at all"))
        assertEquals(OverrideKind.BLOCK, store.kindFor("com.myntra", null))
    }

    @Test
    fun senderScopedVipMatchesOnlyThatSender() = runTest {
        store.add(OverrideKind.VIP, "com.whatsapp", "Mom", "Mom", OverrideSource.MANUAL)
        assertEquals(OverrideKind.VIP, store.kindFor("com.whatsapp", "Mom"))
        assertNull(store.kindFor("com.whatsapp", "Random Group"))
    }

    @Test
    fun vipWinsOverAppWideBlock() = runTest {
        store.add(OverrideKind.BLOCK, "com.linkedin", null, "LinkedIn", OverrideSource.MANUAL)
        store.add(OverrideKind.VIP, "com.linkedin", "Recruiter I like", "R", OverrideSource.MANUAL)
        assertEquals(OverrideKind.VIP, store.kindFor("com.linkedin", "Recruiter I like"))
        assertEquals(OverrideKind.BLOCK, store.kindFor("com.linkedin", "Someone else"))
    }

    @Test
    fun overrideIsScopedToItsApp() = runTest {
        store.add(OverrideKind.BLOCK, "com.myntra", null, "Myntra", OverrideSource.MANUAL)
        assertNull(store.kindFor("com.ajio", "Ajio"))
    }

    @Test
    fun removeDeletesTheOverride() = runTest {
        val id = store.add(OverrideKind.BLOCK, "com.myntra", null, "Myntra", OverrideSource.SWIPE)
        val entry = db.overrideDao().matching("com.myntra", null).first { it.id == id }
        store.remove(entry)
        assertNull(store.kindFor("com.myntra", "Myntra"))
    }
}
```

- [ ] **Step 3: Run both to verify they fail**

```bash
./gradlew :app:testDebugUnitTest --tests "*BiasStoreTest*" --tests "*OverrideStoreTest*"
```

Expected: FAIL — `BiasStore` and `OverrideStore` unresolved.

- [ ] **Step 4: Implement `BiasStore`**

Create `app/src/main/java/com/anuj/notificationfirewall/domain/wall/BiasStore.kt`:

```kotlin
package com.anuj.notificationfirewall.domain.wall

import com.anuj.notificationfirewall.data.db.SenderBiasEntity
import com.anuj.notificationfirewall.data.db.dao.SenderBiasDao

/** Which way the user said the wall got it wrong. */
enum class Correction { SHOULD_HAVE_RUNG, SHOULD_HAVE_BEEN_SILENT }

/**
 * Learned per-(app, sender) nudges, in importance-scale units.
 *
 * The clamp is the safety property. Three "should have been silent" swipes drag
 * a marketing sender to the floor of −0.75, which reliably suppresses its
 * routine blasts — but if that same sender ever emits something Jev scores 5.0,
 * the bias cannot pull it under a 4.0 threshold. **Bias tunes; it never
 * overrides.** Overriding is what [OverrideStore] is for, and that only ever
 * happens because the user asked for it explicitly.
 *
 * Scoping is per sender, never per app, because one messaging app carries both
 * a family conversation and forwarded promotional junk.
 */
class BiasStore(
    private val dao: SenderBiasDao,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    suspend fun biasFor(pkg: String, sender: String?): Float {
        if (sender.isNullOrBlank()) return 0f
        return dao.find(pkg, sender)?.bias ?: 0f
    }

    /** Applies one correction and returns the resulting clamped bias. */
    suspend fun record(pkg: String, sender: String?, correction: Correction): Float {
        if (sender.isNullOrBlank()) return 0f

        val existing = dao.find(pkg, sender)
        val delta = when (correction) {
            Correction.SHOULD_HAVE_RUNG -> STEP
            Correction.SHOULD_HAVE_BEEN_SILENT -> -STEP
        }
        val updated = ((existing?.bias ?: 0f) + delta).coerceIn(-MAX_BIAS, MAX_BIAS)

        dao.upsert(
            SenderBiasEntity(
                packageName = pkg,
                senderKey = sender,
                bias = updated,
                correctionCount = (existing?.correctionCount ?: 0) + 1,
                lastCorrectedEpochMs = clock(),
            ),
        )
        return updated
    }

    suspend fun clear(pkg: String, sender: String) = dao.clear(pkg, sender)

    companion object {
        const val MAX_BIAS = 0.75f
        const val STEP = 0.25f
    }
}
```

- [ ] **Step 5: Implement `OverrideStore`**

Create `app/src/main/java/com/anuj/notificationfirewall/domain/wall/OverrideStore.kt`:

```kotlin
package com.anuj.notificationfirewall.domain.wall

import com.anuj.notificationfirewall.data.db.OverrideEntity
import com.anuj.notificationfirewall.data.db.dao.OverrideDao
import kotlinx.coroutines.flow.Flow

/**
 * The user's hard decisions: senders that always ring, and senders that never
 * appear at all. These are the only things in the pipeline that can overrule a
 * Jev judgement, and the user sets every one of them deliberately.
 */
class OverrideStore(private val dao: OverrideDao) {

    /**
     * The override governing this notification, or null.
     *
     * A sender-scoped entry beats an app-wide one, so blocking a whole app while
     * keeping one person from it is expressible — the narrower rule is the more
     * specific statement of intent. VIP beats BLOCK at equal specificity, because
     * the cost of wrongly silencing beats the cost of wrongly ringing.
     */
    suspend fun kindFor(pkg: String, sender: String?): OverrideKind? {
        val matches = dao.matching(pkg, sender)
        if (matches.isEmpty()) return null

        val senderScoped = matches.filter { it.senderKey != null }
        val pool = senderScoped.ifEmpty { matches }
        return if (pool.any { it.kind == OverrideKind.VIP }) OverrideKind.VIP else OverrideKind.BLOCK
    }

    suspend fun add(
        kind: OverrideKind,
        pkg: String,
        sender: String?,
        label: String,
        source: OverrideSource,
    ): Long = dao.insert(
        OverrideEntity(
            kind = kind,
            packageName = pkg,
            senderKey = sender,
            label = label,
            source = source,
            createdAtEpochMs = System.currentTimeMillis(),
        ),
    )

    fun observeAll(): Flow<List<OverrideEntity>> = dao.observeAll()

    suspend fun remove(entry: OverrideEntity) = dao.delete(entry)
}
```

- [ ] **Step 6: Run to verify they pass**

```bash
./gradlew :app:testDebugUnitTest --tests "*BiasStoreTest*" --tests "*OverrideStoreTest*"
```

Expected: PASS, 16 tests.

- [ ] **Step 7: Commit**

Note: neither store carries `@Inject constructor` — both are supplied by a `@Provides` in `AppModule` (Task 10), and having both would be a duplicate-binding error in Dagger.

```bash
git add app/src/main/java/com/anuj/notificationfirewall/domain/wall/BiasStore.kt \
        app/src/main/java/com/anuj/notificationfirewall/domain/wall/OverrideStore.kt \
        app/src/test/java/com/anuj/notificationfirewall/domain/wall/BiasStoreTest.kt \
        app/src/test/java/com/anuj/notificationfirewall/domain/wall/OverrideStoreTest.kt
git commit -m "feat(wall): clamped per-sender bias and hard VIP/block overrides"
```

---

### Task 8: The wall pipeline

Everything built so far, wired into one decision. This is the task whose tests matter most — they encode the entire policy.

**Files:**
- Create: `app/src/main/java/com/anuj/notificationfirewall/domain/wall/WallDecision.kt`
- Create: `app/src/main/java/com/anuj/notificationfirewall/domain/wall/WallPipeline.kt`
- Create: `app/src/main/java/com/anuj/notificationfirewall/data/prefs/WallSettings.kt`
- Test: `app/src/test/java/com/anuj/notificationfirewall/domain/wall/WallPipelineTest.kt`

**Interfaces:**
- Consumes: `OtpDetector` (3), `ContentShape` (2), `JevApi`/`JevVerdict`/`JevState`/`JevException` (4), `WallBucket`/`WallDecisionSource` (5), `VerdictCache` (6), `BiasStore`/`OverrideStore`/`OverrideKind` (7), `IncomingNotification` (existing).
- Produces:
```kotlin
data class WallDecision(
    val bucket: WallBucket,
    val source: WallDecisionSource,
    val verdict: JevVerdict?,
    val biasApplied: Float,
    val contentShape: String,
    val pendingClassification: Boolean,
)

class WallPipeline(
    otpDetector, overrides, cache, bias, jev, settings, zone: ZoneId
) {
    suspend fun decide(n: IncomingNotification, channelId: String?, isReplyCapable: Boolean): WallDecision
}

class WallSettings(prefs: SharedPreferences) {
    var threshold: Float          // default 4.0
    var otpFastPathEnabled: Boolean  // default true
    var textRetentionDays: Int    // default 30
}
```
Used by Tasks 10, 11, 12 and all of Plan 2.

- [ ] **Step 1: Write the settings holder**

Create `app/src/main/java/com/anuj/notificationfirewall/data/prefs/WallSettings.kt`:

```kotlin
package com.anuj.notificationfirewall.data.prefs

import android.content.SharedPreferences
import androidx.core.content.edit

/** User-tunable wall policy. Backed by the same encrypted prefs as SecurePrefs. */
class WallSettings(private val prefs: SharedPreferences) {

    /** Ring when biased importance is at or above this. 1.0–5.0, default 4.0. */
    var threshold: Float
        get() = prefs.getFloat(KEY_THRESHOLD, DEFAULT_THRESHOLD)
        set(value) = prefs.edit { putFloat(KEY_THRESHOLD, value.coerceIn(1f, 5f)) }

    var otpFastPathEnabled: Boolean
        get() = prefs.getBoolean(KEY_OTP_FAST_PATH, true)
        set(value) = prefs.edit { putBoolean(KEY_OTP_FAST_PATH, value) }

    /** Days before notification text is purged. 0 means never purge. */
    var textRetentionDays: Int
        get() = prefs.getInt(KEY_TEXT_RETENTION_DAYS, DEFAULT_TEXT_RETENTION_DAYS)
        set(value) = prefs.edit { putInt(KEY_TEXT_RETENTION_DAYS, value.coerceAtLeast(0)) }

    var jevKey: String?
        get() = prefs.getString(KEY_JEV_API_KEY, null)
        set(value) = prefs.edit {
            if (value == null) remove(KEY_JEV_API_KEY) else putString(KEY_JEV_API_KEY, value)
        }

    private companion object {
        const val KEY_THRESHOLD = "wall_threshold"
        const val KEY_OTP_FAST_PATH = "wall_otp_fast_path"
        const val KEY_TEXT_RETENTION_DAYS = "wall_text_retention_days"
        const val KEY_JEV_API_KEY = "jev_api_key"
        const val DEFAULT_THRESHOLD = 4.0f
        const val DEFAULT_TEXT_RETENTION_DAYS = 30
    }
}
```

- [ ] **Step 2: Write the decision type**

Create `app/src/main/java/com/anuj/notificationfirewall/domain/wall/WallDecision.kt`:

```kotlin
package com.anuj.notificationfirewall.domain.wall

/**
 * The wall's judgement of one notification, and everything needed to explain it
 * back to the user in the Inbox.
 */
data class WallDecision(
    val bucket: WallBucket,
    val source: WallDecisionSource,
    val verdict: JevVerdict?,
    val biasApplied: Float,
    val contentShape: String,
    val pendingClassification: Boolean,
)
```

- [ ] **Step 3: Write the failing pipeline tests**

Create `app/src/test/java/com/anuj/notificationfirewall/domain/wall/WallPipelineTest.kt`:

```kotlin
package com.anuj.notificationfirewall.domain.wall

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.anuj.notificationfirewall.data.db.NfDatabase
import com.anuj.notificationfirewall.data.prefs.WallSettings
import com.anuj.notificationfirewall.domain.model.IncomingNotification
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.ZoneId

/** Records every call so tests can assert Jev was NOT consulted. */
private class FakeJev(
    var verdict: JevVerdict? = null,
    var error: Exception? = null,
) : JevApi {
    val calls = mutableListOf<JevState>()
    override suspend fun classify(state: JevState): JevVerdict {
        calls += state
        error?.let { throw it }
        return verdict ?: error("FakeJev has no verdict configured")
    }
}

@RunWith(RobolectricTestRunner::class)
class WallPipelineTest {

    private lateinit var db: NfDatabase
    private lateinit var jev: FakeJev
    private lateinit var settings: WallSettings
    private lateinit var overrides: OverrideStore
    private lateinit var bias: BiasStore
    private lateinit var cache: VerdictCache
    private lateinit var pipeline: WallPipeline

    private fun notification(
        pkg: String = "com.myntra",
        app: String = "Myntra",
        title: String = "FLAT 70% OFF",
        text: String = "Shop now",
        sender: String = "Myntra",
    ) = IncomingNotification(
        packageName = pkg,
        appLabel = app,
        title = title,
        text = text,
        senderKey = sender,
        isFavoriteContact = false,
        emailFromDomain = null,
        postedAt = Instant.ofEpochMilli(1_700_000_000_000L),
    )

    private fun verdict(
        importance: Float,
        fromHuman: Float = 0.03f,
        confidence: Float = 0.9f,
        category: NotificationCategory = NotificationCategory.PROMOTION,
    ) = JevVerdict(importance, category, 0.1f, fromHuman, 0.05f, confidence)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, NfDatabase::class.java)
            .allowMainThreadQueries().build()
        jev = FakeJev()
        settings = WallSettings(context.getSharedPreferences("test-wall", Context.MODE_PRIVATE))
        settings.threshold = 4.0f
        settings.otpFastPathEnabled = true
        overrides = OverrideStore(db.overrideDao())
        bias = BiasStore(db.senderBiasDao()) { 1_700_000_000_000L }
        cache = VerdictCache(db.verdictCacheDao()) { 1_700_000_000_000L }
        pipeline = WallPipeline(overrides, cache, bias, jev, settings, ZoneId.of("Asia/Kolkata"))
    }

    @After
    fun tearDown() = db.close()

    private suspend fun decide(n: IncomingNotification = notification()) =
        pipeline.decide(n, channelId = null, isReplyCapable = false)

    // ── OTP fast-path ────────────────────────────────────────────────────────

    @Test
    fun otpRingsWithoutConsultingJev() = runTest {
        val d = decide(notification(title = "HDFC Bank", text = "123456 is your OTP. Do not share."))

        assertEquals(WallBucket.RING, d.bucket)
        assertEquals(WallDecisionSource.OTP, d.source)
        assertTrue("Jev must not be called for an OTP", jev.calls.isEmpty())
    }

    @Test
    fun otpRingsEvenWhenTheSenderIsBlocked() = runTest {
        overrides.add(OverrideKind.BLOCK, "com.myntra", null, "Myntra", OverrideSource.MANUAL)

        val d = decide(notification(text = "Your OTP is 445566 for your Myntra order"))

        assertEquals(WallBucket.RING, d.bucket)
        assertEquals(WallDecisionSource.OTP, d.source)
    }

    @Test
    fun otpFastPathCanBeDisabled() = runTest {
        settings.otpFastPathEnabled = false
        jev.verdict = verdict(importance = 4.8f)

        val d = decide(notification(text = "123456 is your OTP"))

        assertEquals(WallDecisionSource.JEV, d.source)
        assertEquals(1, jev.calls.size)
    }

    // ── Overrides ────────────────────────────────────────────────────────────

    @Test
    fun blockedSenderIsDroppedWithoutConsultingJev() = runTest {
        overrides.add(OverrideKind.BLOCK, "com.myntra", null, "Myntra", OverrideSource.SWIPE)

        val d = decide()

        assertEquals(WallBucket.DROP, d.bucket)
        assertEquals(WallDecisionSource.BLOCK, d.source)
        assertTrue(jev.calls.isEmpty())
    }

    @Test
    fun vipSenderRingsWithoutConsultingJev() = runTest {
        overrides.add(OverrideKind.VIP, "com.whatsapp", "Mom", "Mom", OverrideSource.MANUAL)

        val d = decide(notification("com.whatsapp", "WhatsApp", "Mom", "anything", "Mom"))

        assertEquals(WallBucket.RING, d.bucket)
        assertEquals(WallDecisionSource.VIP, d.source)
        assertTrue(jev.calls.isEmpty())
    }

    // ── Jev and the threshold ────────────────────────────────────────────────

    @Test
    fun lowImportanceIsSilenced() = runTest {
        jev.verdict = verdict(importance = 1.4f)
        val d = decide()

        assertEquals(WallBucket.SILENCE, d.bucket)
        assertEquals(WallDecisionSource.JEV, d.source)
        assertEquals(1.4f, d.verdict!!.importance, 0.001f)
    }

    @Test
    fun importanceAtThresholdRings() = runTest {
        jev.verdict = verdict(importance = 4.0f)
        assertEquals(WallBucket.RING, decide().bucket)
    }

    @Test
    fun importanceJustBelowThresholdIsSilenced() = runTest {
        jev.verdict = verdict(importance = 3.99f)
        assertEquals(WallBucket.SILENCE, decide().bucket)
    }

    @Test
    fun jevNeverProducesDrop() = runTest {
        jev.verdict = verdict(importance = 1.0f)
        assertEquals(WallBucket.SILENCE, decide().bucket)
    }

    @Test
    fun relaxedThresholdLetsMoreThrough() = runTest {
        settings.threshold = 2.0f
        jev.verdict = verdict(importance = 2.5f)
        assertEquals(WallBucket.RING, decide().bucket)
    }

    @Test
    fun jevReceivesTheLocalSignals() = runTest {
        jev.verdict = verdict(importance = 2f)
        pipeline.decide(notification(), channelId = "offers", isReplyCapable = true)

        val state = jev.calls.single()
        assertEquals("Myntra", state.app)
        assertEquals("offers", state.channel)
        assertEquals(true, state.isReplyCapable)
        assertEquals("03:43", state.arrivedAtLocal)
    }

    // ── Cache ────────────────────────────────────────────────────────────────

    @Test
    fun secondIdenticalMachineNotificationUsesTheCache() = runTest {
        jev.verdict = verdict(importance = 1.4f)
        decide()

        val d = decide(notification(title = "FLAT 50% OFF", text = "Shop now"))

        assertEquals(WallDecisionSource.CACHE, d.source)
        assertEquals(1, jev.calls.size)
        assertEquals(WallBucket.SILENCE, d.bucket)
    }

    @Test
    fun humanSenderIsAlwaysReclassified() = runTest {
        jev.verdict = verdict(importance = 4.5f, fromHuman = 0.95f, category = NotificationCategory.PERSONAL_MESSAGE)
        val mom = notification("com.whatsapp", "WhatsApp", "Mom", "Reached home safely", "Mom")

        decide(mom)
        decide(mom)

        assertEquals("a human's message must never be cached", 2, jev.calls.size)
    }

    @Test
    fun promoFromAHumanSenderDoesNotInheritTheRealMessagesVerdict() = runTest {
        val real = notification("com.whatsapp", "WhatsApp", "Mom", "Reached home safely", "Mom")
        val promo = notification("com.whatsapp", "WhatsApp", "Mom", "Check out 50% off at Croma!", "Mom")

        jev.verdict = verdict(importance = 4.5f, fromHuman = 0.95f)
        assertEquals(WallBucket.RING, decide(real).bucket)

        jev.verdict = verdict(importance = 1.2f, fromHuman = 0.9f)
        assertEquals(WallBucket.SILENCE, decide(promo).bucket)
    }

    @Test
    fun lowConfidenceVerdictIsNotCached() = runTest {
        jev.verdict = verdict(importance = 2f, confidence = 0.4f)
        decide()
        decide()

        assertEquals(2, jev.calls.size)
    }

    // ── Bias ─────────────────────────────────────────────────────────────────

    @Test
    fun negativeBiasSilencesABorderlineNotification() = runTest {
        repeat(3) { bias.record("com.myntra", "Myntra", Correction.SHOULD_HAVE_BEEN_SILENT) }
        jev.verdict = verdict(importance = 4.5f)

        val d = decide()

        assertEquals(WallBucket.SILENCE, d.bucket)
        assertEquals(-0.75f, d.biasApplied, 0.001f)
    }

    @Test
    fun maximumNegativeBiasCannotSuppressACriticalNotification() = runTest {
        repeat(10) { bias.record("com.myntra", "Myntra", Correction.SHOULD_HAVE_BEEN_SILENT) }
        jev.verdict = verdict(importance = 5.0f)

        assertEquals(
            "bias tunes, it never overrides",
            WallBucket.RING,
            decide().bucket,
        )
    }

    @Test
    fun positiveBiasRingsABorderlineNotification() = runTest {
        repeat(3) { bias.record("com.slack", "Boss", Correction.SHOULD_HAVE_RUNG) }
        jev.verdict = verdict(importance = 3.5f, category = NotificationCategory.WORK)

        assertEquals(WallBucket.RING, decide(notification("com.slack", "Slack", "Boss", "ping", "Boss")).bucket)
    }

    @Test
    fun biasAppliesToCachedVerdictsToo() = runTest {
        jev.verdict = verdict(importance = 4.5f)
        decide()
        repeat(3) { bias.record("com.myntra", "Myntra", Correction.SHOULD_HAVE_BEEN_SILENT) }

        val d = decide(notification(title = "FLAT 50% OFF"))

        assertEquals(WallDecisionSource.CACHE, d.source)
        assertEquals(WallBucket.SILENCE, d.bucket)
    }

    // ── Failure handling ─────────────────────────────────────────────────────

    @Test
    fun jevFailureSilencesAndMarksPending() = runTest {
        jev.error = JevException("offline")

        val d = decide()

        assertEquals(WallBucket.SILENCE, d.bucket)
        assertEquals(WallDecisionSource.PENDING, d.source)
        assertTrue(d.pendingClassification)
        assertNull(d.verdict)
    }

    @Test
    fun jevFailureStillProducesAContentShapeForLaterRetry() = runTest {
        jev.error = JevException("offline")
        assertEquals(32, decide().contentShape.length)
    }

    @Test
    fun aSuccessfulDecisionIsNotPending() = runTest {
        jev.verdict = verdict(importance = 2f)
        val d = decide()
        assertTrue(!d.pendingClassification)
        assertNotNull(d.verdict)
    }
}
```

- [ ] **Step 4: Run to verify it fails**

```bash
./gradlew :app:testDebugUnitTest --tests "*WallPipelineTest*"
```

Expected: FAIL — `WallPipeline` unresolved.

- [ ] **Step 5: Implement the pipeline**

Create `app/src/main/java/com/anuj/notificationfirewall/domain/wall/WallPipeline.kt`:

```kotlin
package com.anuj.notificationfirewall.domain.wall

import android.util.Log
import com.anuj.notificationfirewall.data.prefs.WallSettings
import com.anuj.notificationfirewall.domain.model.IncomingNotification
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val TAG = "WallPipeline"
private val HOUR_MINUTE: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/**
 * Decides what happens to one notification.
 *
 * Ordering is deliberate and load-bearing:
 *
 * 1. **OTP** first, ahead of even the block list — a passcode has an immediate
 *    concrete cost if silenced, and this path needs no network.
 * 2. **Block**, then **VIP** — the user's explicit instructions outrank any
 *    model judgement.
 * 3. **Cache** — a verdict already earned for this exact content shape.
 * 4. **Jev** — the only step that touches the network.
 *
 * Every step before Jev is local and instant, so the common case (a sender the
 * wall already recognises) costs nothing and works on a plane.
 */
class WallPipeline(
    private val overrides: OverrideStore,
    private val cache: VerdictCache,
    private val bias: BiasStore,
    private val jev: JevApi,
    private val settings: WallSettings,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {

    suspend fun decide(
        n: IncomingNotification,
        channelId: String?,
        isReplyCapable: Boolean,
    ): WallDecision {
        val shape = ContentShape.of(n.title, n.text)
        val sender = n.senderKey.ifBlank { null }

        if (settings.otpFastPathEnabled && OtpDetector.isOtp(n.title, n.text)) {
            return WallDecision(WallBucket.RING, WallDecisionSource.OTP, null, 0f, shape, false)
        }

        when (overrides.kindFor(n.packageName, sender)) {
            OverrideKind.BLOCK ->
                return WallDecision(WallBucket.DROP, WallDecisionSource.BLOCK, null, 0f, shape, false)
            OverrideKind.VIP ->
                return WallDecision(WallBucket.RING, WallDecisionSource.VIP, null, 0f, shape, false)
            null -> Unit
        }

        val appliedBias = bias.biasFor(n.packageName, sender)

        cache.get(shape)?.let { cached ->
            return decided(cached, WallDecisionSource.CACHE, appliedBias, shape)
        }

        val verdict = try {
            jev.classify(
                JevState(
                    app = n.appLabel,
                    channel = channelId,
                    title = n.title,
                    text = n.text,
                    arrivedAtLocal = n.postedAt.atZone(zone).format(HOUR_MINUTE),
                    isReplyCapable = isReplyCapable,
                    isFromContact = n.isFavoriteContact,
                ),
            )
        } catch (e: Exception) {
            // Offline, throttled, down, or malformed. Nothing is lost: the
            // notification is stored and flagged, and the re-classification
            // worker picks it up when the network returns.
            Log.w(TAG, "Jev unavailable for ${n.packageName}; silencing pending re-classification", e)
            return WallDecision(
                bucket = WallBucket.SILENCE,
                source = WallDecisionSource.PENDING,
                verdict = null,
                biasApplied = appliedBias,
                contentShape = shape,
                pendingClassification = true,
            )
        }

        cache.put(shape, n.packageName, sender, verdict)
        return decided(verdict, WallDecisionSource.JEV, appliedBias, shape)
    }

    private fun decided(
        verdict: JevVerdict,
        source: WallDecisionSource,
        appliedBias: Float,
        shape: String,
    ): WallDecision {
        val biased = verdict.importance + appliedBias
        return WallDecision(
            bucket = if (biased >= settings.threshold) WallBucket.RING else WallBucket.SILENCE,
            source = source,
            verdict = verdict,
            biasApplied = appliedBias,
            contentShape = shape,
            pendingClassification = false,
        )
    }
}
```

- [ ] **Step 6: Run to verify it passes**

```bash
./gradlew :app:testDebugUnitTest --tests "*WallPipelineTest*"
```

Expected: PASS, 22 tests. The fixture instant `1_700_000_000_000` is 2023-11-14T22:13:20Z, which is `03:43` in `Asia/Kolkata` — that is why `jevReceivesTheLocalSignals` expects that string. If it fails on the time, fix the expectation to the actual rendered value rather than changing the zone.

- [ ] **Step 7: Run the full suite**

```bash
./gradlew :app:testDebugUnitTest && ./gradlew :app:assembleDebug
```

Expected: both succeed.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/anuj/notificationfirewall/domain/wall/ \
        app/src/main/java/com/anuj/notificationfirewall/data/prefs/WallSettings.kt \
        app/src/test/java/com/anuj/notificationfirewall/domain/wall/WallPipelineTest.kt
git commit -m "feat(wall): the wall pipeline

OTP -> block -> VIP -> cache -> Jev, with clamped bias applied to both
fresh and cached verdicts. Jev failure degrades to silence-and-store
flagged for re-classification; Jev can never produce DROP."
```

---

### Task 9: Arming state derived from live system DND

Fixes the stale-toggle defect structurally. After this task there is no stored boolean that anything can read to answer "is the wall armed" — the question is always put to the system.

**Files:**
- Create: `app/src/main/java/com/anuj/notificationfirewall/service/ArmingController.kt`
- Create: `app/src/main/java/com/anuj/notificationfirewall/service/DndChangeReceiver.kt`
- Modify: `app/src/main/java/com/anuj/notificationfirewall/service/DndController.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Test: `app/src/test/java/com/anuj/notificationfirewall/service/ArmingControllerTest.kt`
- Test: `app/src/test/java/com/anuj/notificationfirewall/service/DndControllerCallSafetyTest.kt`

**Interfaces:**
- Consumes: `SecurePrefs`, `DndController` (existing).
- Produces:
```kotlin
enum class WallState { ARMED, DISARMED, BLOCKED_NO_POLICY_ACCESS, BLOCKED_NO_LISTENER }

class ArmingController(context, dndController, securePrefs) {
    fun state(): WallState
    fun isArmed(): Boolean
    fun arm(): WallState
    fun disarm(): WallState
    fun observeState(): Flow<WallState>
    fun onSystemDndChanged()
}
```
Used by Tasks 10 and 12, and by the Wall screen and Quick Settings tile in Plan 2.

- [ ] **Step 1: Refactor `DndController` to take an explicit want-flag**

`DndController.reconcile(active: ActiveProfile?)` is tied to the profile model that Task 11 deletes. Change its signature to `fun apply(wantDnd: Boolean)` and replace `val wantDnd = active?.autoDnd == true` with the parameter. Leave every other line — the policy construction, the save/restore, the `dndSetByApp` guard — exactly as it is. Update the one call site in `ProfileStateReconciler` to `dndController.apply(active?.autoDnd == true)`.

- [ ] **Step 2: Write the call-safety regression test**

Create `app/src/test/java/com/anuj/notificationfirewall/service/DndControllerCallSafetyTest.kt`:

```kotlin
package com.anuj.notificationfirewall.service

import android.app.NotificationManager
import android.app.NotificationManager.Policy
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.anuj.notificationfirewall.data.prefs.SecurePrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Calls must ring while the wall is armed. This is the invariant the whole
 * design rests on — a wall that swallows phone calls gets uninstalled. These
 * assertions exist so a future refactor of the policy code cannot quietly
 * break it.
 */
@RunWith(RobolectricTestRunner::class)
class DndControllerCallSafetyTest {

    private lateinit var context: Context
    private lateinit var nm: NotificationManager
    private lateinit var controller: DndController

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        nm = context.getSystemService(NotificationManager::class.java)
        shadowOf(nm).setNotificationPolicyAccessGranted(true)
        controller = DndController(
            context,
            SecurePrefs(context.getSharedPreferences("test-dnd", Context.MODE_PRIVATE)),
        )
    }

    @Test
    fun armedPolicyAllowsCallsFromAnyone() {
        controller.apply(wantDnd = true)

        val policy = nm.notificationPolicy
        assertTrue(
            "PRIORITY_CATEGORY_CALLS must be set",
            policy.priorityCategories and Policy.PRIORITY_CATEGORY_CALLS != 0,
        )
        assertTrue(
            "PRIORITY_CATEGORY_REPEAT_CALLERS must be set",
            policy.priorityCategories and Policy.PRIORITY_CATEGORY_REPEAT_CALLERS != 0,
        )
        assertEquals(Policy.PRIORITY_SENDERS_ANY, policy.priorityCallSenders)
    }

    @Test
    fun armedFilterIsPriorityNeverNone() {
        controller.apply(wantDnd = true)

        assertEquals(
            NotificationManager.INTERRUPTION_FILTER_PRIORITY,
            nm.currentInterruptionFilter,
        )
        assertNotEquals(
            NotificationManager.INTERRUPTION_FILTER_NONE,
            nm.currentInterruptionFilter,
        )
        assertNotEquals(
            NotificationManager.INTERRUPTION_FILTER_ALARMS,
            nm.currentInterruptionFilter,
        )
    }

    @Test
    fun disarmRestoresTheUsersOriginalPolicyAndFilter() {
        val original = Policy(
            Policy.PRIORITY_CATEGORY_MESSAGES,
            Policy.PRIORITY_SENDERS_STARRED,
            Policy.PRIORITY_SENDERS_STARRED,
        )
        nm.notificationPolicy = original

        controller.apply(wantDnd = true)
        controller.apply(wantDnd = false)

        assertEquals(NotificationManager.INTERRUPTION_FILTER_ALL, nm.currentInterruptionFilter)
        assertEquals(original.priorityCategories, nm.notificationPolicy.priorityCategories)
        assertEquals(original.priorityCallSenders, nm.notificationPolicy.priorityCallSenders)
    }

    @Test
    fun disarmDoesNotTouchDndTheUserTurnedOnThemselves() {
        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)

        controller.apply(wantDnd = false)

        assertEquals(
            "DND the app did not enable must be left alone",
            NotificationManager.INTERRUPTION_FILTER_PRIORITY,
            nm.currentInterruptionFilter,
        )
    }
}
```

- [ ] **Step 3: Write the failing arming tests**

Create `app/src/test/java/com/anuj/notificationfirewall/service/ArmingControllerTest.kt`:

```kotlin
package com.anuj.notificationfirewall.service

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.anuj.notificationfirewall.data.prefs.SecurePrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class ArmingControllerTest {

    private lateinit var context: Context
    private lateinit var nm: NotificationManager
    private lateinit var prefs: SecurePrefs
    private lateinit var arming: ArmingController

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        nm = context.getSystemService(NotificationManager::class.java)
        shadowOf(nm).setNotificationPolicyAccessGranted(true)
        prefs = SecurePrefs(context.getSharedPreferences("test-arm", Context.MODE_PRIVATE))
        prefs.listenerConnected = true
        arming = ArmingController(context, DndController(context, prefs), prefs)
    }

    @Test
    fun startsDisarmed() {
        assertEquals(WallState.DISARMED, arming.state())
        assertFalse(arming.isArmed())
    }

    @Test
    fun armTurnsOnDndAndReportsArmed() {
        assertEquals(WallState.ARMED, arming.arm())
        assertEquals(NotificationManager.INTERRUPTION_FILTER_PRIORITY, nm.currentInterruptionFilter)
        assertTrue(arming.isArmed())
    }

    @Test
    fun disarmTurnsOffDndAndReportsDisarmed() {
        arming.arm()
        assertEquals(WallState.DISARMED, arming.disarm())
        assertFalse(arming.isArmed())
    }

    @Test
    fun externalDndOffDisarmsTheWall() {
        arming.arm()

        // The user flips DND off from the system shade.
        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)

        assertEquals(
            "armed state must follow the system, not a stored flag",
            WallState.DISARMED,
            arming.state(),
        )
        assertFalse(arming.isArmed())
    }

    @Test
    fun externalDndOffDoesNotReArmItself() {
        arming.arm()
        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)

        arming.onSystemDndChanged()
        arming.state()

        assertEquals(NotificationManager.INTERRUPTION_FILTER_ALL, nm.currentInterruptionFilter)
        assertEquals(WallState.DISARMED, arming.state())
    }

    @Test
    fun externalDndOffClearsOwnershipSoALaterDisarmDoesNotClobberUserDnd() {
        arming.arm()
        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        arming.onSystemDndChanged()

        assertFalse("the app no longer owns DND", prefs.dndSetByApp)
    }

    @Test
    fun missingPolicyAccessIsReportedAsBlocked() {
        shadowOf(nm).setNotificationPolicyAccessGranted(false)
        assertEquals(WallState.BLOCKED_NO_POLICY_ACCESS, arming.state())
    }

    @Test
    fun missingListenerIsReportedAsBlocked() {
        prefs.listenerConnected = false
        assertEquals(WallState.BLOCKED_NO_LISTENER, arming.state())
    }

    @Test
    fun armIsRefusedWithoutPolicyAccess() {
        shadowOf(nm).setNotificationPolicyAccessGranted(false)
        assertEquals(WallState.BLOCKED_NO_POLICY_ACCESS, arming.arm())
    }

    @Test
    fun userOwnedDndDoesNotCountAsArmed() {
        // DND on, but the app never turned it on.
        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        assertFalse(arming.isArmed())
        assertEquals(WallState.DISARMED, arming.state())
    }
}
```

- [ ] **Step 4: Run to verify they fail**

```bash
./gradlew :app:testDebugUnitTest --tests "*ArmingControllerTest*" --tests "*DndControllerCallSafetyTest*"
```

Expected: FAIL — `ArmingController` and `WallState` unresolved.

- [ ] **Step 5: Implement `ArmingController`**

Create `app/src/main/java/com/anuj/notificationfirewall/service/ArmingController.kt`:

```kotlin
package com.anuj.notificationfirewall.service

import android.app.NotificationManager
import android.content.Context
import com.anuj.notificationfirewall.data.prefs.SecurePrefs
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import javax.inject.Inject
import javax.inject.Singleton

enum class WallState {
    ARMED,
    DISARMED,
    BLOCKED_NO_POLICY_ACCESS,
    BLOCKED_NO_LISTENER,
}

/**
 * The one place that answers "is the wall armed".
 *
 * The answer is always computed from the live system interruption filter, never
 * read from storage. The previous build stored a boolean saying whether the app
 * had enabled DND, and when the user turned DND off from the system shade that
 * boolean went stale — the UI cheerfully reported an armed wall that was doing
 * nothing at all. Deriving the state removes the possibility rather than
 * patching the symptom.
 *
 * [SecurePrefs.dndSetByApp] survives for one narrow purpose: recording that
 * *this app* was the one that enabled DND, so a disarm only ever restores a
 * policy the app actually replaced, and DND the user turned on themselves is
 * left strictly alone.
 */
@Singleton
class ArmingController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dndController: DndController,
    private val securePrefs: SecurePrefs,
) {

    private val changes = MutableSharedFlow<Unit>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private val notificationManager: NotificationManager?
        get() = context.getSystemService(NotificationManager::class.java)

    fun state(): WallState {
        val nm = notificationManager ?: return WallState.BLOCKED_NO_POLICY_ACCESS
        if (!nm.isNotificationPolicyAccessGranted) return WallState.BLOCKED_NO_POLICY_ACCESS
        if (!securePrefs.listenerConnected) return WallState.BLOCKED_NO_LISTENER

        val dndOn = nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
        // DND the user turned on themselves is their business, not an armed wall.
        return if (dndOn && securePrefs.dndSetByApp) WallState.ARMED else WallState.DISARMED
    }

    fun isArmed(): Boolean = state() == WallState.ARMED

    fun arm(): WallState {
        val nm = notificationManager ?: return WallState.BLOCKED_NO_POLICY_ACCESS
        if (!nm.isNotificationPolicyAccessGranted) return WallState.BLOCKED_NO_POLICY_ACCESS

        dndController.apply(wantDnd = true)
        changes.tryEmit(Unit)
        return state()
    }

    fun disarm(): WallState {
        dndController.apply(wantDnd = false)
        changes.tryEmit(Unit)
        return state()
    }

    /**
     * Called whenever system DND changes, from any source.
     *
     * If DND is now off while the app still believed it owned it, the user
     * turned the wall off. Release ownership so a later disarm cannot clobber
     * DND they subsequently enable themselves, and let the state fall through
     * to DISARMED. The wall is never silently re-armed: overriding an explicit
     * user action later is exactly the behaviour this design rejects.
     */
    fun onSystemDndChanged() {
        val nm = notificationManager ?: return
        val dndOff = nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL
        if (dndOff && securePrefs.dndSetByApp) {
            securePrefs.dndSetByApp = false
            securePrefs.hasSavedDndPolicy = false
        }
        changes.tryEmit(Unit)
    }

    /** Emits the current state immediately, then on every DND change. */
    fun observeState(): Flow<WallState> = changes.onStart { emit(Unit) }.map { state() }
}
```

- [ ] **Step 6: Implement the broadcast receiver**

Create `app/src/main/java/com/anuj/notificationfirewall/service/DndChangeReceiver.kt`:

```kotlin
package com.anuj.notificationfirewall.service

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Catches DND changes while the listener service is unbound.
 *
 * NotificationListenerService.onInterruptionFilterChanged is the primary signal,
 * but it only fires while the listener is connected. This receiver covers the
 * gap, so the wall's reported state cannot drift during a rebind.
 */
@AndroidEntryPoint
class DndChangeReceiver : BroadcastReceiver() {

    @Inject lateinit var armingController: ArmingController

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED) {
            armingController.onSystemDndChanged()
        }
    }
}
```

Register it in `app/src/main/AndroidManifest.xml`, inside `<application>`:

```xml
        <receiver
            android:name=".service.DndChangeReceiver"
            android:exported="false">
            <intent-filter>
                <action android:name="android.app.action.INTERRUPTION_FILTER_CHANGED" />
            </intent-filter>
        </receiver>
```

- [ ] **Step 7: Run to verify they pass**

```bash
./gradlew :app:testDebugUnitTest --tests "*ArmingControllerTest*" --tests "*DndControllerCallSafetyTest*"
```

Expected: PASS, 14 tests.

- [ ] **Step 8: Run the full suite and build**

```bash
./gradlew :app:testDebugUnitTest && ./gradlew :app:assembleDebug
```

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "feat(wall): derive armed state from live system DND

Armed state is now computed from getCurrentInterruptionFilter() on every
read, so turning DND off from the system shade disarms the wall for real
instead of stranding a stale boolean. The wall never re-arms itself.
Pins call safety (CALLS + REPEAT_CALLERS, SENDERS_ANY, FILTER_PRIORITY)
with a regression test."
```

---

### Task 10: Switch the listener onto the wall pipeline

The cut-over. After this task the app is running on Jev end to end.

**Files:**
- Modify: `app/src/main/java/com/anuj/notificationfirewall/service/NfListenerService.kt`
- Modify: `app/src/main/java/com/anuj/notificationfirewall/service/BucketExecutor.kt`
- Modify: `app/src/main/java/com/anuj/notificationfirewall/service/ChannelManager.kt`
- Modify: `app/src/main/java/com/anuj/notificationfirewall/di/AppModule.kt`
- Test: `app/src/test/java/com/anuj/notificationfirewall/service/BucketExecutorTest.kt`

**Interfaces:**
- Consumes: `WallPipeline`, `WallDecision`, `WallBucket` (Task 8); `ArmingController`, `WallState` (Task 9).
- Produces: `BucketExecutor.execute(decision: WallDecision, sbn: StatusBarNotification)`; Hilt bindings for `JevApi`, `WallPipeline`, `WallSettings`, `VerdictCache`, `BiasStore`, `OverrideStore`.

- [ ] **Step 1: Add the Hilt bindings**

In `app/src/main/java/com/anuj/notificationfirewall/di/AppModule.kt`, add the constant and providers:

```kotlin
private const val JEV_BASE_URL = "https://api.typesafe.ai/"
```

```kotlin
    @Provides
    @Singleton
    fun provideWallSettings(@ApplicationContext context: Context): WallSettings {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        val prefs = EncryptedSharedPreferences.create(
            context,
            SECURE_PREFS_FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
        return WallSettings(prefs)
    }

    /** Short timeout on purpose: a slow Jev degrades to silence-and-store
     *  rather than holding up the notification pipeline. */
    @Provides
    @Singleton
    @JevHttp
    fun provideJevHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .callTimeout(3, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    fun provideJevApi(@JevHttp http: OkHttpClient, settings: WallSettings): JevApi =
        JevClient(JEV_BASE_URL.toHttpUrl(), settings.jevKey.orEmpty(), http)

    @Provides
    @Singleton
    fun provideVerdictCache(dao: VerdictCacheDao): VerdictCache = VerdictCache(dao)

    @Provides
    @Singleton
    fun provideBiasStore(dao: SenderBiasDao): BiasStore = BiasStore(dao)

    @Provides
    @Singleton
    fun provideOverrideStore(dao: OverrideDao): OverrideStore = OverrideStore(dao)

    @Provides
    @Singleton
    fun provideWallPipeline(
        overrides: OverrideStore,
        cache: VerdictCache,
        bias: BiasStore,
        jev: JevApi,
        settings: WallSettings,
    ): WallPipeline = WallPipeline(overrides, cache, bias, jev, settings, ZoneId.systemDefault())
```

Create the qualifier at the bottom of the same file:

```kotlin
@Retention(AnnotationRetention.BINARY)
@javax.inject.Qualifier
annotation class JevHttp
```

Add the needed imports: `java.util.concurrent.TimeUnit`, `java.time.ZoneId`, `okhttp3.HttpUrl.Companion.toHttpUrl`, the wall domain classes, `JevClient`, and `WallSettings`.

**Note on the API key:** `provideJevApi` reads the key once at graph creation. Since the key is entered in Settings (Plan 2), the provider must be re-read after it changes — do not cache a `JevClient` instance beyond the singleton, and have the Settings screen prompt a process restart after a key change. A hot-swappable key holder is deliberately deferred to Plan 2, where the Settings UI that sets it is built.

- [ ] **Step 2: Rewrite `BucketExecutor.execute`**

Replace the `execute` function and the `BucketAction` import in `BucketExecutor.kt` with:

```kotlin
    fun execute(decision: WallDecision, sbn: StatusBarNotification) {
        when (decision.bucket) {
            WallBucket.RING -> {
                // Under DND the OS has already silenced the original, so a
                // re-post on the bypass channel is the only thing that can
                // actually alert. Cancel the silent original so the tray does
                // not show the same notification twice.
                cancelOriginal(sbn)
                repost(sbn, channelManager.ringChannelId())
            }

            WallBucket.SILENCE -> {
                // Leave the original in place. DND already stripped its sound
                // and heads-up, so it sits quietly in the shade exactly as the
                // user would expect, keeping the origin app's own actions.
                cancelOurRepost(sbn)
            }

            WallBucket.DROP -> {
                cancelOriginal(sbn)
                cancelOurRepost(sbn)
            }
        }
    }
```

Add imports for `WallBucket` and `WallDecision`, and delete the `SoundConfig` parameter and import.

- [ ] **Step 3: Add `ringChannelId()` to `ChannelManager`**

Add a single always-present channel for re-posts, replacing the per-package channel lookup for the ring path:

```kotlin
    /**
     * The one channel wall re-posts ring on. Created with IMPORTANCE_HIGH and
     * DND bypass so it alerts while the wall holds the phone in DND — that
     * bypass is the entire reason a re-post can make a sound at all.
     */
    fun ringChannelId(): String {
        val id = RING_CHANNEL_ID
        if (notificationManager.getNotificationChannel(id) == null) {
            val channel = NotificationChannel(id, "Let through", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                    Notification.AUDIO_ATTRIBUTES_DEFAULT,
                )
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 300, 200, 300)
                setBypassDnd(true)
            }
            notificationManager.createNotificationChannel(channel)
        }
        return id
    }
```

with `private const val RING_CHANNEL_ID = "wall_ring"` at file scope, and imports for `NotificationChannel`, `Notification`, and `RingtoneManager`.

- [ ] **Step 4: Write the failing executor tests**

Create `app/src/test/java/com/anuj/notificationfirewall/service/BucketExecutorTest.kt`:

```kotlin
package com.anuj.notificationfirewall.service

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.anuj.notificationfirewall.domain.wall.WallBucket
import com.anuj.notificationfirewall.domain.wall.WallDecision
import com.anuj.notificationfirewall.domain.wall.WallDecisionSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class BucketExecutorTest {

    private lateinit var context: Context
    private lateinit var executor: BucketExecutor
    private val cancelled = mutableListOf<String>()

    private fun decision(bucket: WallBucket) = WallDecision(
        bucket = bucket,
        source = WallDecisionSource.JEV,
        verdict = null,
        biasApplied = 0f,
        contentShape = "shape",
        pendingClassification = false,
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        executor = BucketExecutor(context, ChannelManager(context))
        executor.canceller = NotificationCanceller { key -> cancelled += key }
    }

    @Test
    fun ringChannelBypassesDndAndIsHighImportance() {
        val id = ChannelManager(context).ringChannelId()
        val nm = context.getSystemService(NotificationManager::class.java)
        val channel = nm.getNotificationChannel(id)

        assertTrue("the ring channel must bypass DND or it cannot make a sound", channel.canBypassDnd())
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
    }

    @Test
    fun ringChannelIdIsStableAcrossCalls() {
        val manager = ChannelManager(context)
        assertEquals(manager.ringChannelId(), manager.ringChannelId())
    }
}
```

Testing `execute` itself needs a real `StatusBarNotification`, which Robolectric cannot construct usefully here; the decision logic it depends on is already covered exhaustively by `WallPipelineTest`. These two tests pin the part that is both testable and easy to break silently — the channel's DND bypass.

- [ ] **Step 5: Rewrite `NfListenerService.handle`**

Replace the profile/rule injections with the wall ones and rewrite `handle`:

```kotlin
    @Inject lateinit var wallPipeline: WallPipeline
    @Inject lateinit var bucketExecutor: BucketExecutor
    @Inject lateinit var notificationMapper: NotificationMapper
    @Inject lateinit var notificationDao: NotificationDao
    @Inject lateinit var armingController: ArmingController
    @Inject lateinit var healthMonitor: HealthMonitor
    @Inject lateinit var securePrefs: SecurePrefs
```

```kotlin
    /**
     * Any change to system DND — ours or the user's — lands here while the
     * listener is bound. ArmingController recomputes from the live filter, so
     * the toggle can never report an armed wall that is not actually armed.
     */
    override fun onInterruptionFilterChanged(interruptionFilter: Int) {
        super.onInterruptionFilterChanged(interruptionFilter)
        armingController.onSystemDndChanged()
    }

    private suspend fun handle(sbn: StatusBarNotification) {
        val incoming = notificationMapper.map(sbn)

        // Disarmed: log for continuity of stats and Ask, but never block or
        // re-post. Turning DND off means the wall is down, and a down wall does
        // not touch notifications.
        val armed = armingController.isArmed()

        val decision = wallPipeline.decide(
            n = incoming,
            channelId = sbn.notification.channelId,
            isReplyCapable = sbn.notification.actions?.any { it.remoteInputs?.isNotEmpty() == true } == true,
        )

        val signature = (incoming.title + " " + incoming.text).hashCode()
        val isNewContent = lastLoggedSignature.put(sbn.key, signature) != signature
        if (isNewContent) {
            notificationDao.insert(
                NotificationRecordEntity(
                    packageName = incoming.packageName,
                    appLabel = incoming.appLabel,
                    title = incoming.title,
                    text = incoming.text,
                    timestampEpochMs = sbn.postTime,
                    senderKey = incoming.senderKey.ifBlank { null },
                    contentShape = decision.contentShape,
                    importanceScore = decision.verdict?.importance,
                    biasApplied = decision.biasApplied,
                    category = decision.verdict?.category,
                    isTimeSensitive = decision.verdict?.isTimeSensitive,
                    isFromHuman = decision.verdict?.isFromHuman,
                    needsAction = decision.verdict?.needsAction,
                    jevConfidence = decision.verdict?.confidence,
                    decisionSource = decision.source,
                    bucket = if (armed) decision.bucket else WallBucket.RING,
                    pendingClassification = decision.pendingClassification,
                    textPurgedAt = null,
                    isRead = false,
                ),
            )
        }

        if (armed) bucketExecutor.execute(decision, sbn)
    }
```

Delete `reconcile()`, the `importantSoundConfig()` helper, and the profile-related imports and fields. In `onListenerConnected`, replace the `reconcile()` call with `healthMonitor.refresh()` only.

- [ ] **Step 6: Run the full suite and build**

```bash
./gradlew :app:testDebugUnitTest && ./gradlew :app:assembleDebug
```

Expected: both succeed. The old `NotificationPipelineTest` and any rule-engine tests will fail to compile against the changed executor signature — delete those test files; their coverage is replaced by `WallPipelineTest`.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat(wall): route the listener through the wall pipeline

Every notification is now judged by the wall (OTP/block/VIP/cache/Jev)
and executed as RING (re-post on the DND-bypass channel), SILENCE (left
quiet in the shade), or DROP. While disarmed the listener logs but never
intervenes. onInterruptionFilterChanged keeps armed state honest."
```

---

### Task 11: Delete the profile and rule layer

Nothing reads it any more. Removing it now keeps the repo honest and stops a future reader wondering which of two decision systems is live.

**Files:**
- Delete: `app/src/main/java/com/anuj/notificationfirewall/domain/profile/ProfileManager.kt`
- Delete: `app/src/main/java/com/anuj/notificationfirewall/domain/rules/RuleEngine.kt`
- Delete: `app/src/main/java/com/anuj/notificationfirewall/domain/model/Condition.kt`
- Delete: `app/src/main/java/com/anuj/notificationfirewall/domain/model/BucketAction.kt`
- Delete: `app/src/main/java/com/anuj/notificationfirewall/domain/model/DecisionSource.kt`
- Delete: `app/src/main/java/com/anuj/notificationfirewall/domain/model/Verdict.kt`
- Delete: `app/src/main/java/com/anuj/notificationfirewall/domain/model/SoundConfig.kt`
- Delete: `app/src/main/java/com/anuj/notificationfirewall/service/NotificationPipeline.kt`
- Delete: `app/src/main/java/com/anuj/notificationfirewall/service/ProfileBoundaryReceiver.kt`
- Delete: `app/src/main/java/com/anuj/notificationfirewall/service/ProfileStateReconciler.kt`
- Delete: `app/src/main/java/com/anuj/notificationfirewall/work/ProfileScheduler.kt`
- Delete: `app/src/main/java/com/anuj/notificationfirewall/work/ScheduleMath.kt`
- Delete: `app/src/main/java/com/anuj/notificationfirewall/ai/ImportanceService.kt`
- Delete: `app/src/main/java/com/anuj/notificationfirewall/ai/OpenAiImportanceService.kt`
- Delete: `app/src/main/java/com/anuj/notificationfirewall/data/db/entity/ProfileEntity.kt`
- Delete: `app/src/main/java/com/anuj/notificationfirewall/data/db/entity/RuleEntity.kt`
- Delete: `app/src/main/java/com/anuj/notificationfirewall/data/db/dao/ProfileDao.kt`
- Delete: `app/src/main/java/com/anuj/notificationfirewall/data/db/dao/RuleDao.kt`
- Delete: `app/src/main/java/com/anuj/notificationfirewall/data/mapper/` (all three)
- Delete: `app/src/main/java/com/anuj/notificationfirewall/data/seed/` (profile seeder)
- Delete: `app/src/main/java/com/anuj/notificationfirewall/ui/profiles/`, `ui/rules/`
- Modify: `NfDatabase.kt`, `AppModule.kt`, `Converters.kt`, `MainActivity.kt`, `AndroidManifest.xml`, `BootReceiver.kt`, `KeepAliveService.kt`, `HealthEvaluator.kt`, `MaintenanceWorker.kt`
- Test: `app/src/test/java/com/anuj/notificationfirewall/data/db/MigrationTest.kt` (extend)

**Interfaces:**
- Consumes: `ArmingController` (Task 9).
- Produces: `NfDatabase` at **version 6** with only the four wall entities, plus `MIGRATION_5_6`.

- [ ] **Step 1: Write the failing migration test**

Append to `MigrationTest.kt`:

```kotlin
    @Test
    fun migrate5To6_leavesWallTablesIntact() {
        helper.createDatabase(TEST_DB, 5).use { db ->
            db.execSQL(
                """
                INSERT INTO verdict_cache
                  (contentShape, packageName, senderKey, importance, category,
                   isTimeSensitive, isFromHuman, needsAction, confidence,
                   hitCount, createdAtEpochMs, lastUsedEpochMs)
                VALUES ('s1', 'com.myntra', 'Myntra', 1.4, 'PROMOTION',
                        0.1, 0.03, 0.05, 0.9, 3, 1700000000000, 1700000000000)
                """.trimIndent(),
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 6, true, MIGRATION_5_6)

        db.query("SELECT COUNT(*) FROM verdict_cache").use { c ->
            c.moveToFirst()
            assertEquals(1, c.getInt(0))
        }
    }
```

- [ ] **Step 2: Run to verify it fails**

```bash
./gradlew :app:testDebugUnitTest --tests "*MigrationTest*"
```

Expected: FAIL — `MIGRATION_5_6` unresolved.

- [ ] **Step 3: Find every remaining reference**

```bash
cd "/Volumes/External SSD/dev/projects/notification-firewall"
grep -rn "ProfileDao\|RuleDao\|ProfileEntity\|RuleEntity\|BucketAction\|ProfileManager\|RuleEngine\|NotificationPipeline\|ImportanceService\|SoundConfig\|ProfileScheduler\|ProfileStateReconciler\|ScheduleMath" \
  app/src --include="*.kt" --include="*.xml"
```

Every hit must be gone by the end of this task.

- [ ] **Step 4: Delete the files**

```bash
cd "/Volumes/External SSD/dev/projects/notification-firewall/app/src/main/java/com/anuj/notificationfirewall"
rm -rf domain/profile domain/rules data/mapper data/seed ui/profiles ui/rules
rm -f domain/model/Condition.kt domain/model/BucketAction.kt domain/model/DecisionSource.kt \
      domain/model/Verdict.kt domain/model/SoundConfig.kt
rm -f service/NotificationPipeline.kt service/ProfileBoundaryReceiver.kt service/ProfileStateReconciler.kt
rm -f work/ProfileScheduler.kt work/ScheduleMath.kt
rm -f ai/ImportanceService.kt ai/OpenAiImportanceService.kt
rm -f data/db/entity/ProfileEntity.kt data/db/entity/RuleEntity.kt
rm -f data/db/dao/ProfileDao.kt data/db/dao/RuleDao.kt
```

- [ ] **Step 5: Add migration 5→6 and trim `NfDatabase`**

In `NfDatabase.kt`, add:

```kotlin
/** Removes the last traces of the profile/rule model. Wall data is untouched. */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS profiles")
        db.execSQL("DROP TABLE IF EXISTS rules")
    }
}
```

and reduce the database declaration to:

```kotlin
@Database(
    entities = [
        NotificationRecordEntity::class,
        VerdictCacheEntity::class,
        SenderBiasEntity::class,
        OverrideEntity::class,
    ],
    version = 6,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class NfDatabase : RoomDatabase() {
    abstract fun notificationDao(): NotificationDao
    abstract fun verdictCacheDao(): VerdictCacheDao
    abstract fun senderBiasDao(): SenderBiasDao
    abstract fun overrideDao(): OverrideDao
}
```

- [ ] **Step 6: Clean up `AppModule`**

Remove `provideProfileDao`, `provideRuleDao`, `provideRuleEngine`, `provideProfileManager`, `provideNotificationPipeline`, `provideImportanceService`, and every now-dead import. Extend the migration list:

```kotlin
            .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
```

Keep `provideOpenAiClient` and `provideDigestService` — the digest still uses them, and Plan 2's Ask feature will too.

- [ ] **Step 7: Trim `Converters.kt`**

Delete the `BucketAction` and `DecisionSource` converters. Keep the wall enum converters added in Task 5.

- [ ] **Step 8: Re-key the reliability layer to armed state**

Three files still reason in terms of profile windows:

- `KeepAliveService`: it should run while the wall is armed rather than while a profile window is open. Replace any profile lookup with `armingController.isArmed()`.
- `BootReceiver`: replace the `ProfileScheduler` re-registration with `armingController.onSystemDndChanged()` so the wall's reported state is correct immediately after boot, then `healthMonitor.refresh()`.
- `HealthEvaluator`: remove the "profile active but listener dead" condition and replace it with "wall armed but listener dead", reading from `ArmingController`.

Remove the `ProfileBoundaryReceiver` entry from `AndroidManifest.xml`.

- [ ] **Step 9: Trim the nav graph**

In `MainActivity.kt`, delete the profiles and rules routes and every navigation action targeting them. The remaining screens are replaced in Plan 2.

- [ ] **Step 10: Delete orphaned tests**

```bash
cd "/Volumes/External SSD/dev/projects/notification-firewall"
grep -rln "RuleEngine\|ProfileManager\|NotificationPipeline\|ScheduleMath\|OpenAiImportanceService" \
  app/src/test --include="*.kt" | xargs -r rm -f
```

- [ ] **Step 11: Run the full suite and build**

```bash
./gradlew :app:testDebugUnitTest && ./gradlew :app:assembleDebug
```

Expected: both succeed, with no reference to the profile/rule model anywhere in `app/src/main`.

- [ ] **Step 12: Commit**

```bash
git add -A
git commit -m "refactor: delete the profile and rule layer

Removes profiles, the rule DSL and its builder, the profile schedulers
and reconciler, and the OpenAI importance service — all superseded by
the wall pipeline. Migration 5->6 drops the last empty tables. The
keep-alive service, boot receiver and health evaluator now key off
armed state instead of profile windows."
```

---

### Task 12: Re-classification and retention workers

The two background jobs the spec promises: nothing stays unclassified once the network returns, and notification text does not accumulate forever.

**Files:**
- Create: `app/src/main/java/com/anuj/notificationfirewall/work/ReclassifyWorker.kt`
- Create: `app/src/main/java/com/anuj/notificationfirewall/work/WallWorkScheduler.kt`
- Modify: `app/src/main/java/com/anuj/notificationfirewall/work/MaintenanceWorker.kt`
- Modify: `app/src/main/java/com/anuj/notificationfirewall/NfApplication.kt`
- Test: `app/src/test/java/com/anuj/notificationfirewall/work/RetentionTest.kt`

**Interfaces:**
- Consumes: `NotificationDao`, `VerdictCache`, `WallPipeline`, `JevApi`, `WallSettings`.
- Produces: `object WallWorkScheduler { fun scheduleAll(context: Context) }`, enqueuing a network-constrained `ReclassifyWorker` and the daily `MaintenanceWorker`.

- [ ] **Step 1: Write the failing retention test**

Create `app/src/test/java/com/anuj/notificationfirewall/work/RetentionTest.kt`:

```kotlin
package com.anuj.notificationfirewall.work

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.anuj.notificationfirewall.data.db.NfDatabase
import com.anuj.notificationfirewall.data.db.NotificationRecordEntity
import com.anuj.notificationfirewall.domain.wall.WallBucket
import com.anuj.notificationfirewall.domain.wall.WallDecisionSource
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private const val DAY_MS = 24L * 60 * 60 * 1000
private const val NOW = 1_700_000_000_000L

@RunWith(RobolectricTestRunner::class)
class RetentionTest {

    private lateinit var db: NfDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            NfDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun insert(ageDays: Long, title: String) = db.notificationDao().insert(
        NotificationRecordEntity(
            packageName = "com.myntra",
            appLabel = "Myntra",
            title = title,
            text = "body",
            timestampEpochMs = NOW - ageDays * DAY_MS,
            senderKey = "Myntra",
            contentShape = "shape",
            importanceScore = 1.4f,
            biasApplied = 0f,
            category = null,
            isTimeSensitive = null,
            isFromHuman = null,
            needsAction = null,
            jevConfidence = 0.9f,
            decisionSource = WallDecisionSource.JEV,
            bucket = WallBucket.SILENCE,
            pendingClassification = false,
            textPurgedAt = null,
            isRead = false,
        ),
    )

    @Test
    fun purgeNullsTextOnRecordsOlderThanTheWindowAndKeepsMetadata() = runTest {
        insert(ageDays = 45, title = "old")
        insert(ageDays = 5, title = "recent")

        val purged = db.notificationDao().purgeTextBefore(cutoffMs = NOW - 30 * DAY_MS, nowMs = NOW)

        assertEquals(1, purged)
        val all = db.notificationDao().recordsBetween(0, Long.MAX_VALUE)
        val old = all.first { it.timestampEpochMs < NOW - 30 * DAY_MS }
        val recent = all.first { it.timestampEpochMs > NOW - 30 * DAY_MS }

        assertNull(old.title)
        assertNull(old.text)
        assertNotNull("metadata must survive the purge", old.importanceScore)
        assertEquals(NOW, old.textPurgedAt)
        assertEquals("recent", recent.title)
    }

    @Test
    fun purgeIsIdempotent() = runTest {
        insert(ageDays = 45, title = "old")

        db.notificationDao().purgeTextBefore(NOW - 30 * DAY_MS, NOW)
        val second = db.notificationDao().purgeTextBefore(NOW - 30 * DAY_MS, NOW + 1000)

        assertEquals("already-purged rows must not be touched again", 0, second)
    }

    @Test
    fun pendingRecordsAreQueryable() = runTest {
        val id = db.notificationDao().insert(
            NotificationRecordEntity(
                packageName = "com.x", appLabel = "X", title = "t", text = "b",
                timestampEpochMs = NOW, senderKey = "S", contentShape = "shape",
                importanceScore = null, biasApplied = 0f, category = null,
                isTimeSensitive = null, isFromHuman = null, needsAction = null,
                jevConfidence = null, decisionSource = WallDecisionSource.PENDING,
                bucket = WallBucket.SILENCE, pendingClassification = true,
                textPurgedAt = null, isRead = false,
            ),
        )
        insert(ageDays = 1, title = "classified")

        val pending = db.notificationDao().pending(limit = 50)

        assertEquals(1, pending.size)
        assertEquals(id, pending.single().id)
    }

    @Test
    fun applyVerdictClearsThePendingFlag() = runTest {
        val id = db.notificationDao().insert(
            NotificationRecordEntity(
                packageName = "com.x", appLabel = "X", title = "t", text = "b",
                timestampEpochMs = NOW, senderKey = "S", contentShape = "shape",
                importanceScore = null, biasApplied = 0f, category = null,
                isTimeSensitive = null, isFromHuman = null, needsAction = null,
                jevConfidence = null, decisionSource = WallDecisionSource.PENDING,
                bucket = WallBucket.SILENCE, pendingClassification = true,
                textPurgedAt = null, isRead = false,
            ),
        )

        db.notificationDao().applyVerdict(
            id = id,
            importance = 2.2f,
            category = com.anuj.notificationfirewall.domain.wall.NotificationCategory.PROMOTION,
            timeSensitive = 0.1f,
            fromHuman = 0.02f,
            needsAction = 0.05f,
            confidence = 0.88f,
            bucket = WallBucket.SILENCE,
            source = WallDecisionSource.JEV,
        )

        assertEquals(0, db.notificationDao().pending(limit = 50).size)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
./gradlew :app:testDebugUnitTest --tests "*RetentionTest*"
```

Expected: FAIL if any DAO method is missing; if Task 5 was completed correctly these pass immediately, which is fine — they pin behaviour the workers depend on.

- [ ] **Step 3: Implement `ReclassifyWorker`**

Create `app/src/main/java/com/anuj/notificationfirewall/work/ReclassifyWorker.kt`:

```kotlin
package com.anuj.notificationfirewall.work

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.anuj.notificationfirewall.data.db.dao.NotificationDao
import com.anuj.notificationfirewall.data.prefs.WallSettings
import com.anuj.notificationfirewall.domain.wall.JevApi
import com.anuj.notificationfirewall.domain.wall.JevState
import com.anuj.notificationfirewall.domain.wall.VerdictCache
import com.anuj.notificationfirewall.domain.wall.WallBucket
import com.anuj.notificationfirewall.domain.wall.WallDecisionSource
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val TAG = "ReclassifyWorker"
private const val BATCH = 50
private val HOUR_MINUTE: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/**
 * Fills in verdicts for notifications that arrived while Jev was unreachable.
 *
 * Runs only when the network is up. It updates the stored record so stats and
 * Ask contain no holes, but never re-posts or rings: a notification that missed
 * its moment stays missed. Buzzing about an hour-old message would be worse
 * than the silence it replaces.
 */
@HiltWorker
class ReclassifyWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val notificationDao: NotificationDao,
    private val jev: JevApi,
    private val cache: VerdictCache,
    private val settings: WallSettings,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val pending = notificationDao.pending(BATCH)
        if (pending.isEmpty()) return Result.success()

        var failures = 0
        for (record in pending) {
            // Text may already have been purged by the retention job; without
            // content there is nothing to classify, so clear the flag and move
            // on rather than retrying forever.
            val title = record.title
            val text = record.text
            if (title == null && text == null) {
                notificationDao.applyVerdict(
                    id = record.id,
                    importance = 1f,
                    category = com.anuj.notificationfirewall.domain.wall.NotificationCategory.OTHER,
                    timeSensitive = 0f,
                    fromHuman = 0f,
                    needsAction = 0f,
                    confidence = 0f,
                    bucket = record.bucket,
                    source = WallDecisionSource.LEGACY,
                )
                continue
            }

            try {
                val verdict = jev.classify(
                    JevState(
                        app = record.appLabel,
                        channel = null,
                        title = title.orEmpty(),
                        text = text.orEmpty(),
                        arrivedAtLocal = Instant.ofEpochMilli(record.timestampEpochMs)
                            .atZone(ZoneId.systemDefault()).format(HOUR_MINUTE),
                        isReplyCapable = false,
                        isFromContact = false,
                    ),
                )
                cache.put(record.contentShape, record.packageName, record.senderKey, verdict)

                val biased = verdict.importance + record.biasApplied
                notificationDao.applyVerdict(
                    id = record.id,
                    importance = verdict.importance,
                    category = verdict.category,
                    timeSensitive = verdict.isTimeSensitive,
                    fromHuman = verdict.isFromHuman,
                    needsAction = verdict.needsAction,
                    confidence = verdict.confidence,
                    // Recorded for the history and stats only — nothing is
                    // re-posted, so a retroactive RING never makes a sound.
                    bucket = if (biased >= settings.threshold) WallBucket.RING else WallBucket.SILENCE,
                    source = WallDecisionSource.JEV,
                )
            } catch (e: Exception) {
                failures++
                Log.w(TAG, "Re-classification failed for ${record.id}", e)
            }
        }

        return if (failures == pending.size) Result.retry() else Result.success()
    }
}
```

- [ ] **Step 4: Extend `MaintenanceWorker` with retention**

Add to `MaintenanceWorker.doWork()`, keeping whatever it already does:

```kotlin
        val retentionDays = settings.textRetentionDays
        if (retentionDays > 0) {
            val now = System.currentTimeMillis()
            val cutoff = now - retentionDays * 24L * 60 * 60 * 1000
            val purged = notificationDao.purgeTextBefore(cutoff, now)
            Log.i(TAG, "Purged text from $purged notification records")
        }
        val evicted = verdictCache.evictStale(maxAgeDays = 90)
        Log.i(TAG, "Evicted $evicted stale cache entries")
```

Inject `WallSettings`, `NotificationDao`, and `VerdictCache` into the worker's constructor.

- [ ] **Step 5: Implement the scheduler**

Create `app/src/main/java/com/anuj/notificationfirewall/work/WallWorkScheduler.kt`:

```kotlin
package com.anuj.notificationfirewall.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** Registers the wall's two background jobs. Idempotent — safe to call on every start. */
object WallWorkScheduler {

    private const val RECLASSIFY = "wall-reclassify"
    private const val MAINTENANCE = "wall-maintenance"

    fun scheduleAll(context: Context) {
        val wm = WorkManager.getInstance(context)

        wm.enqueueUniquePeriodicWork(
            RECLASSIFY,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ReclassifyWorker>(30, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build(),
        )

        wm.enqueueUniquePeriodicWork(
            MAINTENANCE,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<MaintenanceWorker>(1, TimeUnit.DAYS).build(),
        )
    }
}
```

- [ ] **Step 6: Call the scheduler at startup**

In `NfApplication.onCreate()`, after the existing setup:

```kotlin
        WallWorkScheduler.scheduleAll(this)
```

- [ ] **Step 7: Run the full suite and build**

```bash
./gradlew :app:testDebugUnitTest && ./gradlew :app:assembleDebug
```

Expected: both succeed.

- [ ] **Step 8: Install on a device and verify the engine end to end**

```bash
./gradlew :app:installDebug
```

Then, on the phone: grant notification access and Do Not Disturb access, set the Jev key (temporarily via a debug entry point if Settings is not yet built), arm the wall, and confirm by observation:

1. A marketing notification arrives → no sound, stays quiet in the shade.
2. An OTP arrives → rings.
3. **Place a call to the phone → it rings normally.** This is the invariant; verify it by hand, not only in tests.
4. Turn DND off from the system shade → `adb shell dumpsys notification | grep -i "interruption"` reflects the change, and the wall reports disarmed.

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "feat(wall): re-classification and retention workers

ReclassifyWorker fills in verdicts for notifications that arrived while
Jev was unreachable, so stats and Ask have no holes; it never re-posts,
because a notification that missed its moment stays missed. Maintenance
purges notification text past the retention window (metadata is kept)
and evicts cache entries unused for 90 days."
```

---

## Plan Self-Review

**Spec coverage.** Every section of the spec maps to a task: §3.1 buckets → Task 5; §3.2 Jev call → Task 4; §3.3 cache and content shape → Tasks 2, 6; §3.4 bias → Task 7; §3.5 threshold → Task 8; §3.6 OTP → Task 3; §3.7 offline → Tasks 8, 12; §4 data model and retention → Tasks 5, 12; §6 arming, DND, call safety → Task 9; §6.3 reliability re-keying → Task 11; §7.2 VIP → Task 7; §9 testing → distributed across all tasks; §10 deletions → Tasks 1, 11.

**Deferred to Plan 2, by design:** §5 screens, §7.3 Quick Settings tile, §7.4 break-glass, §5.5 digest rebuild, §8 theming, and the SQL validator from §5.3. Task 10 also defers a hot-swappable Jev API key to Plan 2, where the Settings screen that sets it is built; until then the key is read at graph creation and a change needs a process restart.

**Known rough edge:** Task 10's `BucketExecutorTest` covers only channel configuration, because constructing a usable `StatusBarNotification` under Robolectric is not worth the scaffolding. The decision logic it executes is covered exhaustively by `WallPipelineTest`, and Task 12 Step 8 verifies the execution path by hand on a device.

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-09-19-notification-wall-engine.md`.
