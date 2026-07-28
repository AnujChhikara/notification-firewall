// data/seed/DefaultSeeder.kt
package com.anuj.notificationfirewall.data.seed

import com.anuj.notificationfirewall.data.db.ProfileEntity
import com.anuj.notificationfirewall.data.db.RuleEntity
import com.anuj.notificationfirewall.data.db.dao.ProfileDao
import com.anuj.notificationfirewall.data.db.dao.RuleDao
import com.anuj.notificationfirewall.data.mapper.ConditionJson
import com.anuj.notificationfirewall.data.mapper.toActiveProfile
import com.anuj.notificationfirewall.domain.model.BucketAction
import com.anuj.notificationfirewall.domain.model.Condition
import com.anuj.notificationfirewall.work.DigestScheduler
import javax.inject.Inject
import javax.inject.Singleton

const val GMAIL_PACKAGE = "com.google.android.gm"
const val WHATSAPP_PACKAGE = "com.whatsapp"

/**
 * Seeds the single M1 "Sleep" profile and its rule table (design §7) on first
 * run. Idempotent: does nothing once any profile exists.
 *
 * Deviation from §7, noted deliberately: the two "important" rules use
 * LET_THROUGH_AS_IS rather than LET_THROUGH_CUSTOM_SOUND. Per-rule SoundConfig
 * persistence/decoding isn't built yet, and a null-config custom-sound would
 * downgrade to a *silent* re-post — the opposite of "let the important ones
 * ring". LET_THROUGH_AS_IS lets them through untouched (native actions intact),
 * which is the correct, testable M1 behavior. The rule builder can switch a rule
 * to custom-sound once that plumbing lands. Rule 5 (WhatsApp → Ask-AI) is not
 * seeded because the pipeline only consults AI on the profile *default*, not per
 * rule; set the default to Ask-AI in Profile Edit to exercise the AI path.
 */
@Singleton
class DefaultSeeder @Inject constructor(
    private val profileDao: ProfileDao,
    private val ruleDao: RuleDao,
    private val digestScheduler: DigestScheduler,
) {
    suspend fun seedIfEmpty() {
        if (profileDao.count() > 0) return

        val profileId = profileDao.upsert(
            ProfileEntity(
                name = "Sleep",
                enabled = true,
                startMinuteOfDay = 22 * 60, // 22:00
                endMinuteOfDay = 7 * 60,    // 07:00 (wraps past midnight)
                daysOfWeek = (1..7).toSet(), // all days
                order = 0,
                aiEnabled = true,
                defaultAction = BucketAction.CAPTURE,
            ),
        )

        val rules = listOf(
            // 1. Work e-mail → let through untouched.
            listOf(
                Condition.AppIs(setOf(GMAIL_PACKAGE)),
                Condition.EmailFromDomain("mycompany.com", shouldMatch = true),
            ) to BucketAction.LET_THROUGH_AS_IS,
            // 2. Favorite contact on WhatsApp → let through untouched.
            listOf(
                Condition.AppIs(setOf(WHATSAPP_PACKAGE)),
                Condition.IsFavoriteContact,
            ) to BucketAction.LET_THROUGH_AS_IS,
            // 3. Non-work e-mail → capture for the morning.
            listOf(
                Condition.AppIs(setOf(GMAIL_PACKAGE)),
                Condition.EmailFromDomain("mycompany.com", shouldMatch = false),
            ) to BucketAction.CAPTURE,
            // 4. Obvious marketing → capture.
            listOf(
                Condition.BodyContainsAny(listOf("sale", "% off", "offer", "unsubscribe")),
            ) to BucketAction.CAPTURE,
        )

        rules.forEachIndexed { index, (conditions, action) ->
            ruleDao.upsert(
                RuleEntity(
                    profileId = profileId,
                    order = index,
                    conditionsJson = ConditionJson.encode(conditions),
                    action = action,
                    soundConfigJson = null,
                ),
            )
        }

        profileDao.profileById(profileId)?.let { seeded ->
            digestScheduler.scheduleForProfile(seeded.toActiveProfile())
        }
    }
}
