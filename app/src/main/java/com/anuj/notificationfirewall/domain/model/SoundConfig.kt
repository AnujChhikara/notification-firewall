package com.anuj.notificationfirewall.domain.model

import android.net.Uri

/**
 * Sound/vibration/DND behavior applied when a notification is bucketed as
 * LET_THROUGH_CUSTOM_SOUND. Consumed by ChannelManager to build (and by
 * BucketExecutor to select) the NotificationChannel used to re-post the
 * notification.
 *
 * equals/hashCode are overridden because the default data-class-generated
 * versions compare LongArray by reference identity, not by content, which
 * would silently break value comparisons (e.g. ChannelManager keying a
 * channel id off this config's hashCode, or simple equality assertions).
 */
data class SoundConfig(
    val soundUri: Uri,
    val vibrationPattern: LongArray,
    val overrideDnd: Boolean,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SoundConfig) return false
        return soundUri == other.soundUri &&
            vibrationPattern.contentEquals(other.vibrationPattern) &&
            overrideDnd == other.overrideDnd
    }

    override fun hashCode(): Int {
        var result = soundUri.hashCode()
        result = 31 * result + vibrationPattern.contentHashCode()
        result = 31 * result + overrideDnd.hashCode()
        return result
    }
}
