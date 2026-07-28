// service/NotificationMapper.kt
package com.anuj.notificationfirewall.service

import android.Manifest
import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.content.ContextCompat
import com.anuj.notificationfirewall.domain.model.IncomingNotification
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import javax.inject.Inject

private const val TAG = "NotificationMapper"

// Matches an e-mail address anywhere in a string and captures the domain part.
// Deliberately liberal on the local part (Gmail-style display strings look like
// "Jane Doe <jane@work.com>") and requires at least one dot in the domain.
private val EMAIL_REGEX = Regex("""[\w.+-]+@([A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)+)""")

/**
 * Pure helper (unit-tested headlessly): returns the lowercased domain of the
 * first e-mail address found in [raw], or null when there is none.
 */
internal fun extractEmailDomain(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    return EMAIL_REGEX.find(raw)?.groupValues?.get(1)?.lowercase()
}

/**
 * Extracts the domain-relevant fields the rule engine / AI need from a posted
 * [StatusBarNotification]. Everything here is best-effort: messaging apps vary
 * wildly in how they populate a notification's extras, so missing data degrades
 * to empty strings / false / null rather than dropping the notification.
 */
class NotificationMapper @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun map(sbn: StatusBarNotification): IncomingNotification {
        val extras = sbn.notification.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (
            extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: extras?.getCharSequence(Notification.EXTRA_TEXT)
            )?.toString().orEmpty()

        // The title is, for most messaging apps, the sender's display name; for
        // e-mail apps it may be (or contain) the sender's address. Use it as the
        // best-effort sender key and mine both title and body for a domain.
        val senderKey = title
        val emailFromDomain = extractEmailDomain(title) ?: extractEmailDomain(text)

        return IncomingNotification(
            packageName = sbn.packageName,
            appLabel = appLabel(sbn.packageName),
            title = title,
            text = text,
            senderKey = senderKey,
            isFavoriteContact = isStarredContact(senderKey),
            emailFromDomain = emailFromDomain,
            postedAt = Instant.ofEpochMilli(sbn.postTime),
        )
    }

    private fun appLabel(packageName: String): String {
        val pm = context.packageManager
        return try {
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            // App uninstalled between posting and mapping, or a synthetic
            // package; fall back to the raw package name.
            packageName
        }
    }

    /**
     * True when [displayName] matches a starred (favorite) contact. Requires the
     * READ_CONTACTS runtime permission (requested by the onboarding UI task);
     * without it, or on any lookup failure, we conservatively return false so a
     * missing grant never turns into a false "favorite" match.
     */
    private fun isStarredContact(displayName: String): Boolean {
        if (displayName.isBlank()) return false
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return try {
            context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts._ID),
                "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} = ? AND " +
                    "${ContactsContract.Contacts.STARRED} = 1",
                arrayOf(displayName),
                null,
            )?.use { cursor -> cursor.moveToFirst() } ?: false
        } catch (e: Exception) {
            Log.w(TAG, "Starred-contact lookup failed for \"$displayName\"", e)
            false
        }
    }
}
